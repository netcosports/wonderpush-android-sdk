package com.wonderpush.sdk;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.AudioManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.TaskStackBuilder;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

class NotificationManager {

    static final String TAG = WonderPush.TAG;

    private static WeakReference<Intent> sLastHandledIntentRef;

    protected static void onReceivedNotification(Context context, Intent intent, NotificationModel notif) {
        String loggedInstallationId = WonderPushConfiguration.getInstallationId();
        if (notif.getTargetedInstallation() != null && !notif.getTargetedInstallation().equals(loggedInstallationId)) {
            WonderPush.logDebug("Received notification is not targeted at the current installation (" + notif.getTargetedInstallation() + " does not match current installation " + loggedInstallationId + ")");
            return;
        }

        handleNotificationActions(context, notif, notif.getReceiveActions());

        try {
            final JSONObject trackData = new JSONObject();
            trackData.put("campaignId", notif.getCampaignId());
            trackData.put("notificationId", notif.getNotificationId());
            trackData.put("actionDate", TimeSync.getTime());
            boolean notifReceipt = notif.getReceipt();
            Boolean overrideNotificationReceipt = WonderPushConfiguration.getOverrideNotificationReceipt();
            if (overrideNotificationReceipt != null) {
                notifReceipt = overrideNotificationReceipt;
            }
            if (notifReceipt) {
                WonderPush.trackInternalEvent("@NOTIFICATION_RECEIVED", trackData);
            }
            WonderPushConfiguration.setLastReceivedNotificationInfoJson(trackData);
        } catch (JSONException ex) {
            Log.e(WonderPush.TAG, "Unexpected error while tracking notification received", ex);
        }

        boolean automaticallyHandled = false;
        Activity currentActivity = ActivityLifecycleMonitor.getCurrentActivity();
        boolean appInForeground = currentActivity != null && !currentActivity.isFinishing();
        String tag = generateLocalNotificationTag(notif);
        int localNotificationId = generateLocalNotificationId(tag);
        PendingIntentBuilder pendingIntentBuilder = new PendingIntentBuilder(notif, localNotificationId, intent, context);
        AlertModel alert = notif.getAlert() == null ? null : notif.getAlert().forCurrentSettings(appInForeground);
        if (alert != null && alert.getAutoDrop()) {
            WonderPush.logDebug("Automatically dropping");
            automaticallyHandled = true;
        } else if (alert != null && alert.getAutoOpen()) {
            WonderPush.logDebug("Automatically opening");
            // We can show the notification (send the pending intent) right away
            try {
                pendingIntentBuilder.buildForAutoOpen().send();
                automaticallyHandled = true;
            } catch (PendingIntent.CanceledException e) {
                Log.e(WonderPush.TAG, "Could not show notification", e);
            }
        }
        if (!automaticallyHandled) {
            WonderPushResourcesService.Work work =
                    new WonderPushResourcesService.Work(
                            notif, tag, localNotificationId, intent);
            if (shouldWorkInBackground(notif)) {
                WonderPush.logDebug("Fetching resources and displaying notification asynchronously");
                WonderPushResourcesService.enqueueWork(context, work);
            } else {
                WonderPush.logDebug("Fetching resources and displaying notification");
                fetchResourcesAndDisplay(context, work, WonderPushResourcesService.TIMEOUT_MS);
            }
        }
    }

    private static boolean shouldWorkInBackground(NotificationModel notif) {
        return notif.getAlert() != null && !notif.getAlert().getResourcesToFetch().isEmpty();
    }

    protected static void fetchResourcesAndDisplay(Context context, WonderPushResourcesService.Work work, long timeoutMs) {
        NotificationModel notif = work.getNotif();
        if (notif == null) return;

        if (notif.getAlert() != null && !notif.getAlert().getResourcesToFetch().isEmpty()) {
            WonderPush.logDebug("Start fetching resources");
            long start = SystemClock.elapsedRealtime();
            Collection<AsyncTask<CacheUtil.FetchWork, Void, File[]>> tasks = new ArrayList<>(notif.getAlert().getResourcesToFetch().size());
            int i = 0;
            for (CacheUtil.FetchWork fetchWork : notif.getAlert().getResourcesToFetch()) {
                ++i;
                tasks.add(new CacheUtil.FetchWork.AsyncTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, fetchWork));
            }
            i = 0;
            for (AsyncTask<CacheUtil.FetchWork, Void, File[]> task : tasks) {
                ++i;
                try {
                    task.get(Math.max(0, start + timeoutMs - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
                } catch (InterruptedException | ExecutionException | TimeoutException e) {
                    WonderPush.logDebug("Failed to fetch resource " + i, e);
                }
            }
            // Now we must reparse the notification to have it pick up the fetched resources
            WonderPush.logDebug("Inserting resources inside the notification");
            try {
                JSONObject json = new JSONObject(notif.getInputJSONString());
                notif = NotificationModel.fromGCMNotificationJSONObject(json, null);
                if (notif == null) return;
            } catch (NotificationModel.NotTargetedForThisInstallationException | JSONException ex) {
                Log.e(TAG, "Unexpected error while reparsing notification", ex);
            }
        }

        if (notif.getAlert() != null) {
            AlertModel alternative = notif.getAlert().getAlternativeIfNeeded();
            if (alternative != null) {
                WonderPush.logDebug("Using an alternative alert");
                notif.setAlert(alternative);
                // Do not try to fetch resources for the new alternative,
                // we are likely to choose it because one resource fetch was interrupted,
                // so ignore potential resources that can stay on the alternative
                // as it might block for a very long time.
            }
        }

        WonderPush.logDebug("Building notification");
        Notification notification = buildNotification(notif, context, work.getPendingIntentBuilder(context));

        if (notification == null) {
            WonderPush.logDebug("No notification is to be displayed");
            // Fire an Intent to notify the application anyway (especially for `data` notifications)
            try {
                if (notif.getType() == NotificationModel.Type.DATA) {
                    work.getPendingIntentBuilder(context).buildForDataNotificationWillOpenBroadcast().send();
                } else {
                    work.getPendingIntentBuilder(context).buildForWillOpenBroadcast().send();
                }
            } catch (PendingIntent.CanceledException e) {
                Log.e(WonderPush.TAG, "Could not broadcast the notification will open intent", e);
            }
        } else {
            notify(context, work.getTag(), work.getLocalNotificationId(), notification);
        }
    }

    protected static String generateLocalNotificationTag(NotificationModel notif) {
        return notif.getAlert() != null && notif.getAlert().hasTag()
                ? notif.getAlert().getTag() : notif.getCampaignId();
    }

    protected static int generateLocalNotificationId(String tag) {
        if (tag != null) {
            return 0;
        } else {
            return WonderPushConfiguration.getNextTaglessNotificationManagerId();
        }
    }

    protected static void notify(Context context, String tag, int localNotificationId, Notification notification) {
        try {
            WonderPush.logDebug("Showing notification with tag " + (tag == null ? "(null)" : "\"" + tag + "\"") + " and id " + localNotificationId);
            android.app.NotificationManager mNotificationManager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.notify(tag, localNotificationId, notification);
        } catch (Exception ex) {
            Log.e(WonderPush.TAG, "Failed to show the notification", ex);
        }
    }

    protected static void cancel(Context context, String tag, int localNotificationId) {
        try {
            android.app.NotificationManager mNotificationManager = (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            mNotificationManager.cancel(tag, localNotificationId);
        } catch (Exception ex) {
            WonderPush.logError("Failed to cancel the notification", ex);
        }
    }

    protected static class PendingIntentBuilder {

        private final NotificationModel notif;
        private final int localNotificationId;
        private final Intent pushIntent;
        private final Context context;

        public PendingIntentBuilder(NotificationModel notif, int localNotificationId, Intent pushIntent, Context context) {
            this.notif = notif;
            this.localNotificationId = localNotificationId;
            this.pushIntent = pushIntent;
            this.context = context;
        }

        public PendingIntent buildForAutoOpen() {
            return buildPendingIntent(false, null, null);
        }

        public PendingIntent buildForDefault() {
            return buildPendingIntent(true, null, null);
        }

        public PendingIntent buildForButton(int buttonIndex) {
            // The button index cannot be an extra or the PendingIntent of each action will be deduplicated
            // @see Intent#filterEquals(android.content.Intent)
            Map<String, String> extraQueryParams = new HashMap<>(1);
            extraQueryParams.put(WonderPush.INTENT_NOTIFICATION_QUERY_PARAMETER_BUTTON_INDEX, String.valueOf(buttonIndex));

            Bundle extrasOverride = new Bundle();
            String targetUrl = notif.getAlert().getButtons().get(buttonIndex).targetUrl;
            if (targetUrl != null) {
                extrasOverride.putString("overrideTargetUrl", targetUrl);
            }

            return buildPendingIntent(true, extrasOverride, extraQueryParams);
        }

        public PendingIntent buildForDataNotificationWillOpenBroadcast() {
            if (!WonderPushService.isProperlySetup()) {
                return buildPendingIntent(false, null, null);
            }

            Intent activityIntent = new Intent();
            activityIntent.setAction(Intent.ACTION_VIEW);
            activityIntent.setData(Uri.parse(WonderPush.INTENT_NOTIFICATION_SCHEME + "://" + WonderPush.INTENT_NOTIFICATION_WILL_OPEN_AUTHORITY
                    + "/" + WonderPush.INTENT_NOTIFICATION_WILL_OPEN_PATH_BROADCAST));
            activityIntent.putExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_RECEIVED_PUSH_NOTIFICATION,
                    pushIntent);
            activityIntent.putExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_NOTIFICATION_TYPE,
                    notif.getType().toString());
            activityIntent.putExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_FROM_USER_INTERACTION,
                    false);
            activityIntent.putExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_AUTOMATIC_OPEN,
                    true);

            // Restrict first to this application
            activityIntent.setPackage(context.getPackageName());

            return PendingIntent.getService(context, 0, activityIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
        }

        public PendingIntent buildForWillOpenBroadcast() {
            Bundle extrasOverride = new Bundle();
            extrasOverride.putString("overrideTargetUrl",
                    WonderPush.INTENT_NOTIFICATION_SCHEME + "://" + WonderPush.INTENT_NOTIFICATION_WILL_OPEN_AUTHORITY
                            + "/" + WonderPush.INTENT_NOTIFICATION_WILL_OPEN_PATH_BROADCAST);
            return buildPendingIntent(false, extrasOverride, null);
        }

        private PendingIntent buildPendingIntent(boolean fromUserInteraction, Bundle extrasOverride, Map<String, String> extraQueryParams) {
            Intent resultIntent = new Intent();
            resultIntent.setClass(context, WonderPushService.class);
            resultIntent.putExtra("receivedPushNotificationIntent", pushIntent);
            resultIntent.putExtra("fromUserInteraction", fromUserInteraction);
            if (extrasOverride != null) {
                resultIntent.putExtras(extrasOverride);
            }

            resultIntent.setAction(Intent.ACTION_MAIN);
            resultIntent.addCategory(Intent.CATEGORY_LAUNCHER);
            resultIntent.addFlags(Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED | WonderPushCompatibilityHelper.getIntentFlagActivityNewDocument() | Intent.FLAG_ACTIVITY_SINGLE_TOP);

            Uri.Builder dataUriBuilder = new Uri.Builder()
                    .scheme(WonderPush.INTENT_NOTIFICATION_SCHEME)
                    .authority(WonderPush.INTENT_NOTIFICATION_AUTHORITY)
                    .appendQueryParameter(WonderPush.INTENT_NOTIFICATION_QUERY_PARAMETER, notif.getInputJSONString())
                    .appendQueryParameter(WonderPush.INTENT_NOTIFICATION_QUERY_PARAMETER_LOCAL_NOTIFICATION_ID, String.valueOf(localNotificationId))
                    ;
            if (extraQueryParams != null) {
                for (Map.Entry<String, String> extraQueryParamEntry : extraQueryParams.entrySet()) {
                    dataUriBuilder.appendQueryParameter(extraQueryParamEntry.getKey(), extraQueryParamEntry.getValue());
                }
            }
            Uri dataUri = dataUriBuilder.build();
            resultIntent.setDataAndType(dataUri, WonderPush.INTENT_NOTIFICATION_TYPE);

            PendingIntent resultPendingIntent;
            if (WonderPushService.isProperlySetup()) {
                resultPendingIntent = PendingIntent.getService(context, 0, resultIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            } else {
                TaskStackBuilder stackBuilder = TaskStackBuilder.create(context);
                stackBuilder.addNextIntentWithParentStack(resultIntent);
                resultPendingIntent = stackBuilder.getPendingIntent(0,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_ONE_SHOT);
            }

            return resultPendingIntent;
        }

    }

    protected static Notification buildNotification(NotificationModel notif, Context context,
                                                    PendingIntentBuilder pendingIntentBuilder) {
        if (NotificationModel.Type.DATA.equals(notif.getType())) {
            return null;
        }
        // Read notification content override if application is foreground
        Activity currentActivity = ActivityLifecycleMonitor.getCurrentActivity();
        boolean appInForeground = currentActivity != null && !currentActivity.isFinishing();
        AlertModel alert = notif.getAlert() == null ? null : notif.getAlert().forCurrentSettings(appInForeground);
        if (alert == null || (alert.getTitle() == null && alert.getText() == null)) {
            // Nothing to display, don't create a notification
            return null;
        }
        // Apply defaults
        if (alert.getTitle() == null && Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            final PackageManager pm = context.getApplicationContext().getPackageManager();
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfo(context.getPackageName(), 0);
            } catch (PackageManager.NameNotFoundException e) {
                ai = null;
            } catch (NullPointerException e) {
                ai = null;
            }
            alert.setTitle(ai != null ? pm.getApplicationLabel(ai) : null);
        }
        int defaultIconResource = WonderPushFirebaseMessagingService.getNotificationIcon(context);
        int defaultColor = WonderPushFirebaseMessagingService.getNotificationColor(context);

        WonderPushChannel channel = WonderPushUserPreferences.channelToUseForNotification(alert.getChannel());
        boolean canVibrate = context.getPackageManager().checkPermission(android.Manifest.permission.VIBRATE, context.getPackageName()) == PackageManager.PERMISSION_GRANTED;
        boolean lights = true;
        boolean lightsCustomized = false;
        boolean vibrates = true;
        boolean vibratesCustomPattern = false;
        boolean noisy = true;
        boolean noisyCustomUri = false;
        int defaults = Notification.DEFAULT_ALL;
        if (!canVibrate) {
            defaults &= ~Notification.DEFAULT_VIBRATE;
            vibrates = false;
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, channel.getId())
                .setContentIntent(pendingIntentBuilder.buildForDefault())
                .setAutoCancel(true)
                .setContentTitle(alert.getTitle())
                .setContentText(alert.getText())
                .setSubText(alert.getSubText())
                .setContentInfo(alert.getInfo())
                .setTicker(alert.getTicker())
                .setSmallIcon(alert.hasSmallIcon() && alert.getSmallIcon() != 0 ? alert.getSmallIcon() : defaultIconResource)
                .setLargeIcon(alert.getLargeIcon())
                .setCategory(alert.getCategory())
                .setGroup(alert.getGroup())
                //.setGroupSummary(alert.getGroupSummary())
                .setSortKey(alert.getSortKey())
                .setOngoing(alert.getOngoing())
                ;
        if (alert.hasPriority()) {
            builder.setPriority(alert.getPriority());
        }
        if (alert.hasColor()) {
            builder.setColor(alert.getColor());
        } else if (defaultColor != 0) {
            builder.setColor(defaultColor);
        }
        if (alert.hasLocalOnly()) {
            builder.setLocalOnly(alert.getLocalOnly());
        }
        if (alert.hasNumber()) {
            builder.setNumber(alert.getNumber());
        }
        if (alert.hasOnlyAlertOnce()) {
            builder.setOnlyAlertOnce(alert.getOnlyAlertOnce());
        }
        if (alert.hasWhen()) {
            builder.setWhen(alert.getWhen());
        }
        if (alert.hasShowWhen()) {
            builder.setShowWhen(alert.getShowWhen());
        }
        if (alert.hasUsesChronometer()) {
            builder.setUsesChronometer(alert.getUsesChronometer());
        }
        if (alert.hasVisibility()) {
            builder.setVisibility(alert.getVisibility());
        }
        if (alert.getPersons() != null) {
            for (String person : alert.getPersons()) {
                builder.addPerson(person);
            }
        }
        if (alert.hasProgress()) {
            builder.setProgress(alert.getProgressMax(), alert.getProgress(), alert.isProgressIndeterminate());
        }

        if (alert.hasLightsColor() || alert.hasLightsOn() || alert.hasLightsOff()) {
            lights = true;
            lightsCustomized = true;
            builder.setLights(alert.getLightsColor(), alert.getLightsOn(), alert.getLightsOff());
            defaults &= ~Notification.DEFAULT_LIGHTS;
        } else if (alert.hasLights()) {
            if (alert.getLights()) {
                lights = true;
                lightsCustomized = false;
                defaults |= Notification.DEFAULT_LIGHTS;
            } else {
                lights = false;
                defaults &= ~Notification.DEFAULT_LIGHTS;
            }
        }
        if (canVibrate) {
            if (alert.getVibratePattern() != null) {
                vibrates = true;
                vibratesCustomPattern = true;
                builder.setVibrate(alert.getVibratePattern());
                defaults &= ~Notification.DEFAULT_VIBRATE;
            } else if (alert.hasVibrate()) {
                if (alert.getVibrate()) {
                    vibrates = true;
                    defaults |= Notification.DEFAULT_VIBRATE;
                } else {
                    vibrates = false;
                    defaults &= ~Notification.DEFAULT_VIBRATE;
                }
            }
        }
        if (alert.getSoundUri() != null) {
            noisy = true;
            noisyCustomUri = true;
            builder.setSound(alert.getSoundUri());
            defaults &= ~Notification.DEFAULT_SOUND;
        } else if (alert.hasSound()) {
            if (alert.getSound()) {
                noisy = true;
                defaults |= Notification.DEFAULT_SOUND;
            } else {
                noisy = false;
                defaults &= ~Notification.DEFAULT_SOUND;
            }
        }

        // Apply channel options for importance
        if (channel.getImportance() != null) {
            switch (channel.getImportance()) {
                case NotificationManagerCompat.IMPORTANCE_MAX:
                    builder.setPriority(NotificationCompat.PRIORITY_MAX);
                    break;
                case NotificationManagerCompat.IMPORTANCE_HIGH:
                    builder.setPriority(NotificationCompat.PRIORITY_HIGH);
                    break;
                case NotificationManagerCompat.IMPORTANCE_DEFAULT:
                    builder.setPriority(NotificationCompat.PRIORITY_DEFAULT);
                    break;
                case NotificationManagerCompat.IMPORTANCE_LOW:
                    builder.setPriority(NotificationCompat.PRIORITY_LOW);
                    break;
                case NotificationManagerCompat.IMPORTANCE_MIN:
                case NotificationManagerCompat.IMPORTANCE_NONE:
                    builder.setPriority(NotificationCompat.PRIORITY_MIN);
                    break;
                case NotificationManagerCompat.IMPORTANCE_UNSPECIFIED:
                    // noop
                    break;
                default:
                    if (channel.getImportance() < NotificationManagerCompat.IMPORTANCE_NONE) {
                        builder.setPriority(NotificationCompat.PRIORITY_MIN);
                    } else if (channel.getImportance() > NotificationManagerCompat.IMPORTANCE_MAX) {
                        builder.setPriority(NotificationCompat.PRIORITY_MAX);
                    }
                    break;
            }
        }

        // Apply channel options for lights
        if (channel.getLights() != null) {
            if (channel.getLights()) {
                if (!lights) {
                    lights = true;
                    lightsCustomized = false;
                    defaults |= Notification.DEFAULT_LIGHTS;
                }
            } else {
                if (lights) {
                    lights = false;
                    builder.setLights(Color.TRANSPARENT, 0, 0);
                    defaults &= ~Notification.DEFAULT_LIGHTS;
                }
            }
        }
        if (lights && channel.getLightColor() != null) {
            if (channel.getLightColor() == Color.TRANSPARENT) {
                lights = false;
                builder.setLights(Color.TRANSPARENT, 0, 0);
                defaults &= ~Notification.DEFAULT_LIGHTS;
            } else {
                lightsCustomized = true;
                builder.setLights(channel.getLightColor(), alert.getLightsOn(), alert.getLightsOff());
                defaults &= ~Notification.DEFAULT_LIGHTS;
            }
        }

        // Apply channel options for vibration
        if (canVibrate) {
            if (channel.getVibrate() != null) {
                if (channel.getVibrate()) {
                    if (!vibrates) {
                        vibrates = true;
                        defaults |= Notification.DEFAULT_VIBRATE;
                    }
                } else {
                    if (vibrates) {
                        vibrates = false;
                        vibratesCustomPattern = false;
                        defaults &= ~Notification.DEFAULT_VIBRATE;
                        builder.setVibrate(null);
                    }
                }
            }
            if (vibrates && channel.getVibrationPattern() != null) {
                vibrates = true;
                vibratesCustomPattern = true;
                defaults &= ~Notification.DEFAULT_VIBRATE;
                builder.setVibrate(channel.getVibrationPattern());
            }
        }

        // Apply channel options for sound
        if (channel.getSound() != null) {
            if (channel.getSound()) {
                if (!noisy) {
                    noisy = true;
                    defaults |= Notification.DEFAULT_SOUND;
                }
            } else {
                if (noisy) {
                    noisy = false;
                    defaults &= ~Notification.DEFAULT_SOUND;
                    builder.setSound(null);
                }
            }
        }
        if (noisy && channel.getSoundUri() != null) {
            defaults &= ~Notification.DEFAULT_SOUND;
            builder.setSound(channel.getSoundUri());
        }

        // Apply channel options for vibration in silent mode
        if (channel.getVibrateInSilentMode() != null) {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am.getRingerMode() == AudioManager.RINGER_MODE_VIBRATE) {
                if (channel.getVibrateInSilentMode()) {
                    if (!vibrates && !noisy) {
                        vibrates = true;
                        defaults |= Notification.DEFAULT_VIBRATE;
                    }
                } else {
                    if (vibrates || noisy) {
                        vibrates = false;
                        noisy = false;
                        defaults &= ~Notification.DEFAULT_VIBRATE;
                        defaults &= ~Notification.DEFAULT_SOUND;
                        builder.setSound(null);
                        builder.setVibrate(null);
                    }
                }
            }
        }

        // Apply channel options for lockscreen visibility
        if (channel.getLockscreenVisibility() != null) {
            builder.setVisibility(channel.getLockscreenVisibility());
        }

        // Apply channel options for color
        if (channel.getColor() != null) {
            builder.setColor(channel.getColor());
        }

        // Apply channel options for local only
        if (channel.getLocalOnly() != null) {
            builder.setLocalOnly(channel.getLocalOnly());
        }

        builder.setDefaults(defaults);

        if (alert.getButtons() != null) {
            int i = 0;
            for (NotificationButtonModel button : alert.getButtons()) {
                PendingIntent buttonPendingIntent = pendingIntentBuilder.buildForButton(i);
                int icon = button.icon;
                if (icon == 0) {
                    icon = android.R.color.transparent;
                }
                builder.addAction(
                        new NotificationCompat.Action.Builder(icon, button.label, buttonPendingIntent)
                                .build());
                ++i;
            }
        }

        switch (alert.getType()) {
            case NONE:
                // Explicitly no particular style
                builder.setStyle(null);
                break;
            default:
                Log.e(TAG, "Unhandled notification type " + alert.getType());
                // $FALLTHROUGH
            case NULL:
                // No specific style configured
                builder.setStyle(new NotificationCompat.BigTextStyle()
                        .bigText(alert.getText()));
                break;
            case BIG_TEXT:
                AlertBigTextModel alertBigText = (AlertBigTextModel) alert;
                builder.setStyle(new NotificationCompat.BigTextStyle()
                        .setBigContentTitle(alertBigText.getBigTitle()) // automatically falls back to alert.getTitle()
                        .bigText(alertBigText.getBigText() != null ? alertBigText.getBigText() : alert.getText())
                        .setSummaryText(alertBigText.getSummaryText())
                );
                break;
            case BIG_PICTURE:
                AlertBigPictureModel alertBigPicture = (AlertBigPictureModel) alert;
                builder.setStyle(new NotificationCompat.BigPictureStyle()
                        .bigLargeIcon(alertBigPicture.getBigLargeIcon() != null ? alertBigPicture.getBigLargeIcon() : alert.getLargeIcon())
                        .bigPicture(alertBigPicture.getBigPicture())
                        .setBigContentTitle(alertBigPicture.getBigTitle()) // automatically falls back to alert.getTitle()
                        .setSummaryText(alertBigPicture.getSummaryText() != null ? alertBigPicture.getSummaryText() : alert.getText())
                );
                break;
            case INBOX:
                AlertInboxModel alertInbox = (AlertInboxModel) alert;
                NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle()
                        .setBigContentTitle(alertInbox.getBigTitle()) // automatically falls back to alert.getTitle()
                        .setSummaryText(alertInbox.getSummaryText());
                if (alertInbox.getLines() != null) {
                    for (CharSequence line : alertInbox.getLines()) {
                        style.addLine(line);
                    }
                } else {
                    // We could split the text by lines, but a CharSequence (in HTML mode) is impractical
                    style.addLine(alert.getText());
                }
                builder.setStyle(style);
                break;
        }

        return builder.build();
    }

    public static void ensureNotificationDismissed(Context context, Intent intent, NotificationModel notif) {
        // Manually dismiss the notification and close the system drawer, when an action button is clicked.
        // May be a kind of bug, or may be a feature when the associated PendingIntent resolves a Service instead of an Activity.
        context.sendBroadcast(new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS));
        String localNotificationIdStr = intent.getData().getQueryParameter(WonderPush.INTENT_NOTIFICATION_QUERY_PARAMETER_LOCAL_NOTIFICATION_ID);
        int localNotificationId = -1;
        try {
            if (localNotificationIdStr != null) {
                localNotificationId = Integer.parseInt(localNotificationIdStr);
            }
        } catch (Exception ignored) { // NumberFormatException
            WonderPush.logError("Failed to parse localNotificationId " + localNotificationIdStr, ignored);
        }
        cancel(context, generateLocalNotificationTag(notif), localNotificationId);
    }

    public static boolean showPotentialNotification(Context context, Intent intent) {
        if (containsExplicitNotification(intent) || containsWillOpenNotificationAutomaticallyOpenable(intent)) {
            final NotificationModel notif = NotificationModel.fromLocalIntent(intent);
            if (notif == null) {
                Log.e(TAG, "Failed to extract notification object");
                return false;
            }

            sLastHandledIntentRef = new WeakReference<>(intent);

            if (!WonderPushService.isProperlySetup()) {
                handleOpenedNotificationFromService(context, intent, notif);
            } else if (WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_NOTIFICATION_TYPE_DATA.equals(
                    intent.getStringExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_NOTIFICATION_TYPE))) {
                // Track data notification opens, and display any in-app
                handleOpenedManuallyDisplayedDataNotification(context, intent, notif);
            }

            InAppManager.handleInApp(context, notif);
            return true;
        }
        return false;
    }

    protected static boolean containsExplicitNotification(Intent intent) {
        return  intent != null
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
                && (sLastHandledIntentRef == null || intent != sLastHandledIntentRef.get())
                && WonderPush.INTENT_NOTIFICATION_TYPE.equals(intent.getType())
                && intent.getData() != null
                && WonderPush.INTENT_NOTIFICATION_SCHEME.equals(intent.getData().getScheme())
                && WonderPush.INTENT_NOTIFICATION_AUTHORITY.equals(intent.getData().getAuthority())
                ;
    }

    protected static boolean containsWillOpenNotification(Intent intent) {
        return  intent != null
                // action may or may not be INTENT_NOTIFICATION_WILL_OPEN
                && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
                && (sLastHandledIntentRef == null || intent != sLastHandledIntentRef.get())
                && intent.hasExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_RECEIVED_PUSH_NOTIFICATION)
                ;
    }

    protected static boolean containsWillOpenNotificationAutomaticallyOpenable(Intent intent) {
        return  containsWillOpenNotification(intent)
                && intent.hasExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_AUTOMATIC_OPEN) // makes it default to false if removed
                && intent.getBooleanExtra(WonderPush.INTENT_NOTIFICATION_WILL_OPEN_EXTRA_AUTOMATIC_OPEN, false)
                ;
    }

    public static void handleOpenedNotificationFromService(Context context, Intent intent, NotificationModel notif) {
        ensureNotificationDismissed(context, intent, notif);

        WonderPush.logDebug("Handling opened notification: " + notif.getInputJSONString());
        trackOpenedNotification(intent, notif);
        notifyNotificationOpened(intent);
        handleOpenedNotification(context, intent, notif);
    }

    public static void handleOpenedManuallyDisplayedDataNotification(Context context, Intent intent, NotificationModel notif) {
        WonderPush.logDebug("Handling opened manually displayed data notification: " + notif.getInputJSONString());
        trackOpenedNotification(intent, notif);
        notifyNotificationOpened(intent);
        handleOpenedNotification(context, intent, notif);
    }

    private static void trackOpenedNotification(Intent intent, NotificationModel notif) {
        int clickedButtonIndex = getClickedButtonIndex(intent);
        try {
            JSONObject trackData = new JSONObject();
            trackData.put("campaignId", notif.getCampaignId());
            trackData.put("notificationId", notif.getNotificationId());
            trackData.put("actionDate", TimeSync.getTime());
            if (clickedButtonIndex >= 0 && notif.getAlert() != null && notif.getAlert().getButtons() != null && clickedButtonIndex < notif.getAlert().getButtons().size()) {
                NotificationButtonModel button = notif.getAlert().getButtons().get(clickedButtonIndex);
                trackData.put("buttonLabel", button.label);
            }
            WonderPush.trackInternalEvent("@NOTIFICATION_OPENED", trackData);

            WonderPushConfiguration.setLastOpenedNotificationInfoJson(trackData);
        } catch (JSONException e) {
            Log.e(TAG, "Failed to parse notification JSON object", e);
        }
    }

    private static int getClickedButtonIndex(Intent intent) {
        if (intent.getData() != null) {
            String buttonIndexStr = intent.getData().getQueryParameter(WonderPush.INTENT_NOTIFICATION_QUERY_PARAMETER_BUTTON_INDEX);
            try {
                if (buttonIndexStr != null) {
                    return Integer.parseInt(buttonIndexStr);
                }
            } catch (Exception ignored) { // NumberFormatException
                WonderPush.logError("Failed to parse buttonIndex " + buttonIndexStr, ignored);
            }
        }
        return -1;
    }

    private static void notifyNotificationOpened(Intent intent) {
        boolean fromUserInteraction = intent.getBooleanExtra("fromUserInteraction", true);
        Intent receivedPushNotificationIntent = intent.getParcelableExtra("receivedPushNotificationIntent");
        int buttonIndex = getClickedButtonIndex(intent);

        // Notify the application that the notification has been opened
        Intent notificationOpenedIntent = new Intent(WonderPush.INTENT_NOTIFICATION_OPENED);
        notificationOpenedIntent.putExtra(WonderPush.INTENT_NOTIFICATION_OPENED_EXTRA_FROM_USER_INTERACTION, fromUserInteraction);
        notificationOpenedIntent.putExtra(WonderPush.INTENT_NOTIFICATION_OPENED_EXTRA_RECEIVED_PUSH_NOTIFICATION, receivedPushNotificationIntent);
        notificationOpenedIntent.putExtra(WonderPush.INTENT_NOTIFICATION_OPENED_EXTRA_BUTTON_INDEX, buttonIndex);
        LocalBroadcastManager.getInstance(WonderPush.getApplicationContext()).sendBroadcast(notificationOpenedIntent);
    }

    private static void handleOpenedNotification(Context context, Intent intent, NotificationModel notif) {
        int clickedButtonIndex = getClickedButtonIndex(intent);
        List<ActionModel> actions = null;
        if (clickedButtonIndex < 0) {
            // Notification opened actions
            actions = notif.getActions();
        } else if (
                notif.getAlert() != null
                && notif.getAlert().getButtons() != null
                && clickedButtonIndex < notif.getAlert().getButtons().size()
        ) {
            // Notification button-specific actions
            actions = notif.getAlert().getButtons().get(clickedButtonIndex).actions;
        }
        handleNotificationActions(context, notif, actions);
    }

    protected static void handleNotificationActions(Context context, NotificationModel notif, List<ActionModel> actions) {
        if (actions == null)
            return;

        try {
            for (ActionModel action : actions) {
                handleAction(context, notif, action);
            }
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected error while handling actions", ex);
        }
    }

    protected static void handleAction(Context context, NotificationModel notif, ActionModel action) {
        try {
            if (action == null || action.getType() == null) {
                // Skip unrecognized action types
                return;
            }
            WonderPush.logDebug("Running action " + action.getType());
            switch (action.getType()) {
                case CLOSE:
                    // Noop
                    break;
                case MAP_OPEN:
                    handleMapOpenAction(context, notif, action);
                    break;
                case LINK:
                    handleLinkAction(context, action);
                    break;
                case RATING:
                    handleRatingAction(context, action);
                    break;
                case TRACK_EVENT:
                    handleTrackEventAction(notif, action);
                    break;
                case UPDATE_INSTALLATION:
                    handleUpdateInstallationAction(action);
                    break;
                case ADD_PROPERTY:
                    handleAddPropertyAction(action);
                    break;
                case REMOVE_PROPERTY:
                    handleRemovePropertyAction(action);
                    break;
                case RESYNC_INSTALLATION:
                    handleResyncInstallationAction(action);
                case ADD_TAG:
                    handleAddTagAction(action);
                    break;
                case REMOVE_TAG:
                    handleRemoveTagAction(action);
                    break;
                case REMOVE_ALL_TAGS:
                    handleRemoveAllTagsAction(action);
                    break;
                case METHOD:
                    handleMethodAction(action);
                    break;
                case _DUMP_STATE:
                    handleDumpStateAction(action);
                    break;
                case _OVERRIDE_SET_LOGGING:
                    handleOverrideSetLoggingAction(action);
                    break;
                case _OVERRIDE_NOTIFICATION_RECEIPT:
                    handleOverrideNotificationReceiptAction(action);
                    break;
                default:
                    Log.w(TAG, "Unhandled action \"" + action.getType() + "\"");
                    break;
            }
        } catch (Exception ex) {
            Log.e(TAG, "Unexpected error while handling action " + action, ex);
        }
    }

    protected static void handleLinkAction(Context context, ActionModel action) {
        try {
            String url = action.getUrl();
            if (url == null) {
                Log.e(TAG, "No url in a " + ActionModel.Type.LINK + " action!");
                return;
            }

            url = WonderPush.delegateUrlForDeepLink(new DeepLinkEvent(context, url));
            if (url == null) return;

            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
            } else {
                Log.e(TAG, "No service for intent " + intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to perform a " + ActionModel.Type.LINK + " action", e);
        }
    }

    protected static void handleRatingAction(Context context, ActionModel action) {
        try {
            String url = "market://details?id=" + context.getPackageName();

            url = WonderPush.delegateUrlForDeepLink(new DeepLinkEvent(context, url));
            if (url == null) return;

            Uri uri = Uri.parse(url);
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
            } else {
                Log.e(TAG, "No service for intent " + intent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to perform a " + ActionModel.Type.RATING + " action", e);
        }
    }

    protected static void handleTrackEventAction(NotificationModel notif, ActionModel action) {
        JSONObject event = action.getEvent();
        if (event == null) {
            Log.e(TAG, "Got no event to track for a " + ActionModel.Type.TRACK_EVENT + " action");
            return;
        }
        if (!event.has("type") || JSONUtil.getString(event, "type") == null) {
            Log.e(TAG, "Got no type in the event to track for a " + ActionModel.Type.TRACK_EVENT + " action");
            return;
        }
        JSONObject trackingData = new JSONObject();
        try {
            trackingData.putOpt("campaignId", notif.getCampaignId());
            trackingData.putOpt("notificationId", notif.getNotificationId());
        } catch (JSONException ex) {
            Log.e(TAG, "Unexpected error while adding notification tracking info in trackEvent", ex);
        }
        WonderPush.trackEvent(JSONUtil.getString(event, "type"), trackingData, event.optJSONObject("custom"));
    }

    protected static void handleUpdateInstallationAction(ActionModel action) {
        JSONObject installation = action.getInstallation();
        JSONObject custom = installation != null ? installation.optJSONObject("custom") : action.getCustom();
        if (custom == null) {
            Log.e(TAG, "Got no installation custom properties to update for a " + ActionModel.Type.UPDATE_INSTALLATION + " action");
            return;
        }
        if (custom.length() == 0) {
            WonderPush.logDebug("Empty installation custom properties for an update, for a " + ActionModel.Type.UPDATE_INSTALLATION + " action");
            return;
        }
        try {
            if (action.getAppliedServerSide(false)) {
                WonderPush.logDebug("Received server custom properties diff: " + custom);
                JSONSyncInstallationCustom.forCurrentUser().receiveDiff(custom);
            } else {
                WonderPush.logDebug("Putting custom properties diff: " + custom);
                JSONSyncInstallationCustom.forCurrentUser().put(custom);
            }
        } catch (JSONException ex) {
            Log.e(WonderPush.TAG, "Failed to handle action " + ActionModel.Type.UPDATE_INSTALLATION, ex);
        }
    }

    protected static void handleAddPropertyAction(ActionModel action) {
        JSONObject installation = action.getInstallation();
        JSONObject custom = installation != null ? installation.optJSONObject("custom") : action.getCustom();
        if (custom == null) {
            Log.e(TAG, "Got no installation custom properties to update for a " + ActionModel.Type.ADD_PROPERTY + " action");
            return;
        }
        if (custom.length() == 0) {
            WonderPush.logDebug("Empty installation custom properties for an update, for a " + ActionModel.Type.ADD_PROPERTY + " action");
            return;
        }
        try {
            Iterator<String> it = custom.keys();
            while (it.hasNext()) {
                String field = it.next();
                Object value = custom.get(field);
                WonderPush.addProperty(field, value);
            }
        } catch (JSONException ex) {
            Log.e(WonderPush.TAG, "Failed to handle action " + ActionModel.Type.ADD_PROPERTY, ex);
        }
    }

    protected static void handleRemovePropertyAction(ActionModel action) {
        JSONObject installation = action.getInstallation();
        JSONObject custom = installation != null ? installation.optJSONObject("custom") : action.getCustom();
        if (custom == null) {
            Log.e(TAG, "Got no installation custom properties to update for a " + ActionModel.Type.REMOVE_PROPERTY + " action");
            return;
        }
        if (custom.length() == 0) {
            WonderPush.logDebug("Empty installation custom properties for an update, for a " + ActionModel.Type.REMOVE_PROPERTY + " action");
            return;
        }
        try {
            Iterator<String> it = custom.keys();
            while (it.hasNext()) {
                String field = it.next();
                Object value = custom.get(field);
                WonderPush.removeProperty(field, value);
            }
        } catch (JSONException ex) {
            Log.e(WonderPush.TAG, "Failed to handle action " + ActionModel.Type.REMOVE_PROPERTY, ex);
        }
    }

    protected static void handleResyncInstallationAction(final ActionModel action) {
        if (action.getInstallation() != null) {
            handleResyncInstallationAction_inner(action);
        } else {
            WonderPush.logDebug("Fetching installation for action " + ActionModel.Type.RESYNC_INSTALLATION);
            WonderPush.get("/installation", null, new ResponseHandler() {
                @Override
                public void onFailure(Throwable ex, Response errorResponse) {
                    Log.e(WonderPush.TAG, "Failed to fetch installation for running action " + ActionModel.Type.RESYNC_INSTALLATION + ", got " + errorResponse, ex);
                }

                @Override
                public void onSuccess(Response response) {
                    if (response.isError()) {
                        Log.e(WonderPush.TAG, "Failed to fetch installation for running action " + ActionModel.Type.RESYNC_INSTALLATION + ", got " + response);
                    } else {
                        ActionModel enrichedAction = null;
                        try {
                            enrichedAction = (ActionModel) action.clone();
                        } catch (CloneNotSupportedException ex) {
                            WonderPush.logError("Failed to clone action " + action, ex);
                            enrichedAction = action;
                        }
                        JSONObject installation = response.getJSONObject();
                        Iterator<String> it = installation.keys();
                        while (it.hasNext()) {
                            String key = it.next();
                            if (key.startsWith("_")) {
                                it.remove();
                            }
                        }
                        WonderPush.logDebug("Got installation: " + installation);
                        enrichedAction.setInstallation(installation);
                        handleResyncInstallationAction_inner(enrichedAction);
                    }
                }
            });
        }
    }

    private static void handleResyncInstallationAction_inner(ActionModel action) {
        JSONObject installation = action.getInstallation();
        JSONObject custom = installation == null ? null : installation.optJSONObject("custom");
        if (custom == null) {
            if (installation != null) {
                // If an installation has no custom, use {}
                custom = new JSONObject();
            } else { // we still have no installation
                Log.e(TAG, "Got no installation custom properties to resync with for a " + ActionModel.Type.RESYNC_INSTALLATION + " action");
                return;
            }
        }

        // Take or reset custom
        try {
            if (action.getReset(false)) {
                JSONSyncInstallationCustom.forCurrentUser().receiveState(custom, action.getForce(false));
            } else {
                JSONSyncInstallationCustom.forCurrentUser().receiveServerState(custom);
            }
        } catch (JSONException ex) {
            Log.e(WonderPush.TAG, "Failed to resync installation", ex);
        }

        WonderPush.refreshPreferencesAndConfiguration(true);
    }

    private static void handleAddTagAction(ActionModel action) {
        JSONArray actionTags = action.getTags();
        if (actionTags == null) return;
        ArrayList<String> tags = new ArrayList<>(actionTags.length());
        for (int i = 0, e = actionTags.length(); i < e; ++i) {
            try {
                Object item = actionTags.get(i);
                if (item instanceof String) {
                    tags.add((String) item);
                }
            } catch (JSONException ex) {
                Log.e(WonderPush.TAG, "Unexpected error while getting an item of the tags array for the addTag action", ex);
            }
        }
        WonderPush.addTag(tags.toArray(new String[0]));
    }

    private static void handleRemoveTagAction(ActionModel action) {
        JSONArray actionTags = action.getTags();
        if (actionTags == null) return;
        ArrayList<String> tags = new ArrayList<>(actionTags.length());
        for (int i = 0, e = actionTags.length(); i < e; ++i) {
            try {
                Object item = actionTags.get(i);
                if (item instanceof String) {
                    tags.add((String) item);
                }
            } catch (JSONException ex) {
                Log.e(WonderPush.TAG, "Unexpected error while getting an item of the tags array for the addTag action", ex);
            }
        }
        WonderPush.removeTag(tags.toArray(new String[0]));
    }

    private static void handleRemoveAllTagsAction(ActionModel action) {
        WonderPush.removeAllTags();
    }

    protected static void handleMethodAction(ActionModel action) {
        String method = action.getMethod();
        String arg = action.getMethodArg();
        if (method == null) {
            Log.e(TAG, "Got no method to call for a " + ActionModel.Type.METHOD + " action");
            return;
        }
        Intent intent = new Intent();
        intent.setPackage(WonderPush.getApplicationContext().getPackageName());
        intent.setAction(WonderPush.INTENT_NOTIFICATION_BUTTON_ACTION_METHOD_ACTION);
        intent.setData(new Uri.Builder()
                .scheme(WonderPush.INTENT_NOTIFICATION_BUTTON_ACTION_METHOD_SCHEME)
                .authority(WonderPush.INTENT_NOTIFICATION_BUTTON_ACTION_METHOD_AUTHORITY)
                .appendPath(method)
                .build());
        intent.putExtra(WonderPush.INTENT_NOTIFICATION_BUTTON_ACTION_METHOD_EXTRA_METHOD, method);
        intent.putExtra(WonderPush.INTENT_NOTIFICATION_BUTTON_ACTION_METHOD_EXTRA_ARG, arg);
        LocalBroadcastManager.getInstance(WonderPush.getApplicationContext()).sendBroadcast(intent);
    }

    private static void handleMapOpenAction(Context context, NotificationModel notif, ActionModel action) {
        try {
            NotificationMapModel.Place place;
            try {
                place = ((NotificationMapModel) notif).getMap().getPlace();
            } catch (Exception e) {
                Log.e(NotificationManager.TAG, "Could not get the place from the map", e);
                return;
            }
            NotificationMapModel.Point point = place.getPoint();

            Uri.Builder geo = new Uri.Builder();
            geo.scheme("geo");
            if (point != null) {
                if (place.getName() != null) {
                    geo.authority("0,0");
                    geo.appendQueryParameter("q", point.getLat() + "," + point.getLon() + "(" + place.getName() + ")");
                } else {
                    geo.authority(point.getLat() + "," + point.getLon());
                    if (place.getZoom() != null) {
                        geo.appendQueryParameter("z", place.getZoom().toString());
                    }
                }
            } else if (place.getQuery() != null) {
                geo.authority("0,0");
                geo.appendQueryParameter("q", place.getQuery());
            }

            String url = geo.build().toString();
            url = WonderPush.delegateUrlForDeepLink(new DeepLinkEvent(context, url));
            if (url == null) return;

            Intent open = new Intent(Intent.ACTION_VIEW);
            open.setData(Uri.parse(url));
            if (open.resolveActivity(context.getPackageManager()) != null) {
                WonderPush.logDebug("Will open location " + open.getDataString());
                context.startActivity(open);
            } else {
                WonderPush.logDebug("No activity can open location " + open.getDataString());
                WonderPush.logDebug("Falling back to regular URL");
                geo = new Uri.Builder();
                geo.scheme("https");
                geo.authority("maps.google.com");
                geo.path("maps");
                if (point != null) {
                    geo.appendQueryParameter("q", point.getLat() + "," + point.getLon());
                    if (place.getZoom() != null) {
                        geo.appendQueryParameter("z", place.getZoom().toString());
                    }
                } else if (place.getQuery() != null) {
                    geo.appendQueryParameter("q", place.getQuery());
                } else if (place.getName() != null) {
                    geo.appendQueryParameter("q", place.getName());
                }
                open = new Intent(Intent.ACTION_VIEW);
                open.setData(geo.build());
                if (open.resolveActivity(context.getPackageManager()) != null) {
                    WonderPush.logDebug("Opening URL " + open.getDataString());
                    context.startActivity(open);
                } else {
                    WonderPush.logDebug("No activity can open URL " + open.getDataString());
                    Log.w(NotificationManager.TAG, "Cannot open map!");
                    Toast.makeText(context, R.string.wonderpush_could_not_open_location, Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {
            Log.e(NotificationManager.TAG, "Unexpected error while opening map", e);
            Toast.makeText(context, R.string.wonderpush_could_not_open_location, Toast.LENGTH_SHORT).show();
        }
    }

    private static void handleDumpStateAction(ActionModel action) {
        JSONObject stateDump = WonderPushConfiguration.dumpState();
        Log.d(WonderPush.TAG, "STATE DUMP: " + stateDump);
        if (stateDump == null) stateDump = new JSONObject();
        JSONObject custom = new JSONObject();
        try {
            custom.put("ignore_sdkStateDump", stateDump);
        } catch (JSONException ex) {
            Log.e(WonderPush.TAG, "Failed to add state dump to event custom", ex);
        }
        WonderPush.trackInternalEvent("@DEBUG_DUMP_STATE", null, custom);
    }

    private static void handleOverrideSetLoggingAction(ActionModel action) {
        Boolean value = action.getForce();
        Log.d(WonderPush.TAG, "OVERRIDE setLogging: " + value);
        WonderPushConfiguration.setOverrideSetLogging(value);
        WonderPush.applyOverrideLogging(value);
    }

    private static void handleOverrideNotificationReceiptAction(ActionModel action) {
        Boolean value = action.getForce();
        Log.d(WonderPush.TAG, "OVERRIDE notification receipt: " + value);
        WonderPushConfiguration.setOverrideNotificationReceipt(value);
    }

}
