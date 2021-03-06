package com.wonderpush.sdk;

import android.net.Uri;
import android.os.SystemClock;
import android.util.Base64;
import android.util.Log;

import com.loopj.android.http.AsyncHttpClient;
import com.loopj.android.http.JsonHttpResponseHandler;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import cz.msebera.android.httpclient.Header;
import cz.msebera.android.httpclient.HttpEntity;
import cz.msebera.android.httpclient.message.BasicHeader;
import cz.msebera.android.httpclient.message.BasicNameValuePair;

/**
 * A REST client that lets you hit the WonderPush REST server.
 */
class WonderPushRestClient {

    private static final String TAG = WonderPushRestClient.class.getSimpleName();

    enum HttpMethod {
        GET,
        PUT,
        POST,
        DELETE,
        PATCH,
        // APPEND ONLY!
    }

    private static final int RETRY_INTERVAL_BAD_AUTH = 1 * 1000; // in milliseconds
    private static final int RETRY_INTERVAL = 30 * 1000; // in milliseconds
    protected static final int ERROR_INVALID_CREDENTIALS = 11000;
    protected static final int ERROR_INVALID_ACCESS_TOKEN = 11003;

    private static boolean sIsFetchingAnonymousAccessToken = false;
    private static final List<ResponseHandler> sPendingHandlers = new ArrayList<>();

    private static final AsyncHttpClient sClient = new AsyncHttpClient(); // to allow any HTTPS certificate use: new AsyncHttpClient(true, 80, 443);

    /**
     * A request
     *
     * @param userId
     *            The userId to perform this request for
     * @param method
     *            The HTTP method to use
     * @param resource
     *            The resource path, starting with /
     * @param params
     *            AsyncHttpClient request parameters
     * @param responseHandler
     *            An AsyncHttpClient response handler
     */
    protected static void requestForUser(String userId, HttpMethod method, String resource, RequestParams params, ResponseHandler responseHandler) {
        requestAuthenticated(new Request(userId, method, resource, params, responseHandler));
    }

    /**
     * A GET request
     *
     * @param resource
     *            The resource path, starting with /
     * @param params
     *            AsyncHttpClient request parameters
     * @param responseHandler
     *            An AsyncHttpClient response handler
     */
    protected static void get(String resource, RequestParams params, ResponseHandler responseHandler) {
        requestAuthenticated(new Request(WonderPushConfiguration.getUserId(), HttpMethod.GET, resource, params, responseHandler));
    }

    /**
     * A POST request
     *
     * @param resource
     *            The resource path, starting with /
     * @param params
     *            AsyncHttpClient request parameters
     * @param responseHandler
     *            An AsyncHttpClient response handler
     */
    protected static void post(String resource, RequestParams params, ResponseHandler responseHandler) {
        requestAuthenticated(new Request(WonderPushConfiguration.getUserId(), HttpMethod.POST, resource, params, responseHandler));
    }

    /**
     * A POST request that is guaranteed to be executed when a network connection
     * is present, surviving application reboot. The responseHandler will be
     * called only if the network is present when the request is first run.
     *
     * @param resource
     *            The resource path, starting with /
     * @param params
     *            AsyncHttpClient request parameters
     */
    protected static void postEventually(String resource, RequestParams params) {
        final Request request = new Request(WonderPushConfiguration.getUserId(), HttpMethod.POST, resource, params, null);
        WonderPushRequestVault.getDefaultVault().put(request, 0);
    }

    /**
     * A PUT request
     *
     * @param resource
     *            The resource path, starting with /
     * @param params
     *            AsyncHttpClient request parameters
     * @param responseHandler
     *            An AsyncHttpClient response handler
     */
    protected static void put(String resource, RequestParams params, ResponseHandler responseHandler) {
        requestAuthenticated(new Request(WonderPushConfiguration.getUserId(), HttpMethod.PUT, resource, params, responseHandler));
    }

    /**
     * A DELETE request
     *
     * @param resource
     *            The resource path, starting with /
     * @param responseHandler
     *            An AsyncHttpClient response handler
     */
    protected static void delete(String resource, ResponseHandler responseHandler) {
        requestAuthenticated(new Request(WonderPushConfiguration.getUserId(), HttpMethod.DELETE, resource, null, responseHandler));
    }

    /**
     * If no access token is found in the user's preferences, fetch an anonymous access token.
     *
     * @param onFetchedHandler
     *            A handler called if a request to fetch an access token has been
     *            executed successfully, never called if retrieved from cache
     * @return Whether or not a request has been executed to fetch an anonymous
     *         access token (true fetching, false retrieved from local cache)
     */
    protected static boolean fetchAnonymousAccessTokenIfNeeded(final String userId, final ResponseHandler onFetchedHandler) {
        if (!WonderPush.isInitialized()) {
            // Note: Could use WonderPush.safeDefer() here but as we require consent to proceed,
            // let's use WonderPush.safeDeferWithConsent() to additionally passively wait for SDK initialization.
            WonderPush.safeDeferWithConsent(new Runnable() {
                @Override
                public void run() {
                    if (!fetchAnonymousAccessTokenIfNeeded(userId, onFetchedHandler)) {
                        // Call the handler anyway
                        onFetchedHandler.onSuccess(null);
                    }
                }
            }, null);
            return true; // true: the handler will be called
        }

        if (null == WonderPushConfiguration.getAccessToken()) {
            fetchAnonymousAccessToken(userId, onFetchedHandler);
            return true;
        }
        return false;
    }

    /**
     * If no access token is found in the user's preferences, fetch an anonymous access token.
     */
    protected static void fetchAnonymousAccessTokenIfNeeded(final String userId) {
        fetchAnonymousAccessTokenIfNeeded(userId, null);
    }

    /**
     * Runs the specified request and ensure a valid access token is fetched if
     * necessary beforehand, or afterwards (and re-run the request) if the request
     * fails for auth reasons.
     */
    protected static void requestAuthenticated(final Request request) {
        if (null == request) {
            return;
        }

        if (!WonderPush.isInitialized()) {
            WonderPush.safeDefer(new Runnable() {
                @Override
                public void run() {
                    requestAuthenticated(request);
                }
            }, 100);
            return;
        }

        String accessToken = WonderPushConfiguration.getAccessTokenForUserId(request.getUserId());

        if (accessToken == null) {
            // User is not authenticated, request a token
            fetchAnonymousAccessTokenAndRunRequest(request);
            return;
        }

        // Add the access token to the params
        RequestParams params = request.getParams();
        if (null == params) {
            params = new RequestParams();
            request.setParams(params);
        }

        params.remove("accessToken");
        params.put("accessToken", accessToken);

        // Wrap the request handler with our own
        ResponseHandler wrapperHandler = new ResponseHandler() {
            @Override
            public void onSuccess(int status, Response response) {
                WonderPush.logDebug("Request successful: (" + status + ") " + response + " (for " + request + ")");
                if (request.getHandler() != null) {
                    request.getHandler().onSuccess(status, response);
                }
            }

            @Override
            public void onFailure(Throwable e, Response errorResponse) {
                WonderPush.logError("Request failed: " + errorResponse, e);
                if (errorResponse != null && ERROR_INVALID_ACCESS_TOKEN == errorResponse.getErrorCode()) {
                    // null out the access token
                    WonderPushConfiguration.invalidateCredentials();

                    // retry later now
                    WonderPush.safeDefer(new Runnable() {
                        @Override
                        public void run() {
                            requestAuthenticated(request);
                        }
                    }, RETRY_INTERVAL_BAD_AUTH);
                } else {
                    if (request.getHandler() != null) {
                        request.getHandler().onFailure(e, errorResponse);
                    }
                }
            }

            @Override
            public void onSuccess(Response response) {
                WonderPush.logDebug("Request successful: " + response + " (for " + request + ")");
                if (request.getHandler() != null) {
                    request.getHandler().onSuccess(response);
                }
            }
        };
        Request wrapperRequest = (Request) request.clone();
        wrapperRequest.setHandler(wrapperHandler);

        // Perform request
        request(wrapperRequest);
    }

    /**
     * Thin wrapper to the {@link AsyncHttpClient} library.
     */
    private static void request(final Request request) {
        if (null == request) {
            WonderPush.logError("Request with null request.");
            return;
        }

        WonderPush.safeDefer(new Runnable() {
            @Override
            public void run() {
                // Decorate parameters
                WonderPushRequestParamsDecorator.decorate(request.getResource(), request.getParams());

                // Generate signature
                BasicHeader authorizationHeader = request.getAuthorizationHeader();

                // Headers
                BasicHeader[] headers = null;
                if (null != authorizationHeader) {
                    headers = new BasicHeader[1];
                    headers[0] = authorizationHeader;
                }

                String url = WonderPushUriHelper.getAbsoluteUrl(request.getResource());
                WonderPush.logDebug("requesting url: " + request.getMethod() + " " + url + "?" + request.getParams().getURLEncodedString());
                // TODO: support other contentTypes such as "application/json"
                String contentType = "application/x-www-form-urlencoded";

                // Handler
                final ResponseHandler handler = request.getHandler();
                HttpEntity entity = null;
                if (request.getParams() != null) {
                    try {
                        entity = request.getParams() != null ? request.getParams().getEntity(null) : null;
                    } catch (IOException ex) {
                        WonderPush.logError("Failed to create HttpEntity from params " + request.getParams(), ex);
                        if (handler != null) {
                            handler.onFailure(ex, new Response(""));
                        }
                        return;
                    }
                }
                final long sendDate = SystemClock.elapsedRealtime();
                JsonHttpResponseHandler jsonHandler = new JsonHttpResponseHandler() {
                    @Override
                    public void onProgress(long bytesWritten, long totalSize) {
                        // mute this
                    }

                    @Override
                    public void onSuccess(int statusCode, Header[] headers, JSONObject response) {
                        syncTime(response);
                        WonderPush.setNetworkAvailable(true);
                        if (handler != null) {
                            handler.onSuccess(statusCode, new Response(response));
                        }
                    }

                    @Override
                    public void onSuccess(int statusCode, Header[] headers, JSONArray response) {
                        WonderPush.logError("Unexpected JSONArray answer: " + statusCode + " headers: " + Arrays.toString(headers) + " response: (" + response.length() + ") " + response.toString());
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONObject errorResponse) {
                        WonderPush.logError("Error answer: " + statusCode + " headers: " + Arrays.toString(headers) + " response: " + errorResponse);
                        syncTime(errorResponse);
                        WonderPush.logDebug("Request Error: " + errorResponse);
                        WonderPush.setNetworkAvailable(errorResponse != null);
                        if (handler != null) {
                            handler.onFailure(throwable, new Response(errorResponse));
                        }
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, Throwable throwable, JSONArray errorResponse) {
                        WonderPush.logError("Unexpected JSONArray error answer: " + statusCode + " headers: " + Arrays.toString(headers) + " response: (" + errorResponse.length() + ") " + errorResponse.toString());
                        this.onFailure(statusCode, headers, errorResponse.toString(), throwable);
                    }

                    @Override
                    public void onFailure(int statusCode, Header[] headers, String responseString, Throwable throwable) {
                        WonderPush.logError("Unexpected string error answer: " + statusCode + " headers: " + Arrays.toString(headers) + " response: (" + responseString.length() + ") \"" + responseString + "\"");
                        WonderPush.setNetworkAvailable(false);
                        if (handler != null) {
                            handler.onFailure(throwable, new Response(responseString));
                        }
                    }

                    @Override
                    public void onSuccess(int statusCode, Header[] headers, String responseString) {
                        WonderPush.logError("Unexpected string answer: " + statusCode + " headers: " + Arrays.toString(headers) + " response: (" + responseString.length() + ") \"" + responseString + "\"");
                    }

                    private void syncTime(JSONObject data) {
                        long recvDate = SystemClock.elapsedRealtime();
                        if (data == null || !data.has("_serverTime")) {
                            return;
                        }
                        TimeSync.syncTimeWithServer(sendDate, recvDate, data.optLong("_serverTime"), data.optLong("_serverTook"));
                    }
                };
                // NO UNNECESSARY WORK HERE, because of timed request
                switch (request.getMethod()) {
                    case GET:
                        sClient.get(null, url, headers, request.getParams(), jsonHandler);
                        break;
                    case PUT:
                        sClient.put(null, url, headers, entity, contentType, jsonHandler);
                        break;
                    case POST:
                        sClient.post(null, url, headers, entity, contentType, jsonHandler);
                        break;
                    case PATCH:
                        sClient.patch(null, url, headers, entity, contentType, jsonHandler);
                        break;
                    case DELETE:
                        sClient.delete(null, url, headers, request.getParams(), jsonHandler);
                        break;
                    default:
                        jsonHandler.sendFailureMessage(0, null, null, new UnsupportedOperationException("Unhandled method " + request.getMethod()));
                }
            }
        }, 0);
    }

    protected static void fetchAnonymousAccessToken(final String userId, final ResponseHandler handler) {
        fetchAnonymousAccessToken(userId, handler, 0);
    }

    protected static void fetchAnonymousAccessToken(final String userId, final ResponseHandler handler, final int nbRetries) {
        if (sIsFetchingAnonymousAccessToken) {
            queueHandler(handler);
            return;
        }
        sIsFetchingAnonymousAccessToken = true;
        WonderPush.safeDeferWithConsent(new Runnable() {
            @Override
            public void run() {
                fetchAnonymousAccessToken_inner(userId, handler, nbRetries);
            }
        }, "fetchAnonymousAccessToken");
    }

    private static void fetchAnonymousAccessToken_inner(final String userId, final ResponseHandler handler, final int nbRetries) {
        RequestParams authParams = new RequestParams();
        authParams.put("clientId", WonderPush.getClientId());
        authParams.put("devicePlatform", "Android");
        authParams.put("deviceModel", InstallationManager.getDeviceModel());
        String deviceId = WonderPush.getDeviceId();
        if (null != deviceId) {
            authParams.put("deviceId", deviceId);
        }
        if (null != userId) {
            authParams.put("userId", userId);
        }

        String resource = "/authentication/accessToken";

        request(new Request(userId, HttpMethod.POST, resource, authParams,
                new ResponseHandler() {
                    @Override
                    public void onFailure(Throwable e, Response errorResponse) {
                        if (nbRetries <= 0) {
                            Log.e(TAG, "Error request anonymous access token (aborting): " + (errorResponse != null ? errorResponse.toString() : "null error response, aborting"), e);
                            if (errorResponse != null && ERROR_INVALID_CREDENTIALS == errorResponse.getErrorCode()) {
                                Log.e(TAG, "Check your clientId/clientSecret couple");
                            }

                            sIsFetchingAnonymousAccessToken = false;
                            if (null != handler) {
                                handler.onFailure(e, errorResponse);
                            }
                            ResponseHandler chainedHandler;
                            while ((chainedHandler = dequeueHandler()) != null) {
                                chainedHandler.onFailure(e, errorResponse);
                            }
                            return;
                        }
                        Log.e(TAG, "Error request anonymous access token (retrying: " + nbRetries + "): " + (errorResponse != null ? errorResponse.toString() : "null error response, retrying"), e);

                        WonderPush.safeDefer(new Runnable() {
                            @Override
                            public void run() {
                                WonderPush.logDebug("re-requesting access token!");

                                sIsFetchingAnonymousAccessToken = false;
                                fetchAnonymousAccessToken(userId, handler, nbRetries - 1);
                            }
                        }, RETRY_INTERVAL);
                    }

                    @Override
                    public void onSuccess(int statusCode, Response response) {
                        // Parse response
                        JSONObject json = response.getJSONObject();
                        WonderPush.logDebug("Got access token response: " + json);
                        if (json != null && json.has("token") && json.has("data")) {
                            String token = JSONUtil.getString(json, "token");
                            JSONObject data = json.optJSONObject("data");
                            if (data != null && data.has("installationId")) {
                                String prevUserId = WonderPushConfiguration.getUserId();
                                try {
                                    // Make sure we alter the storage of the appropriate user
                                    WonderPushConfiguration.changeUserId(userId);

                                    String sid = JSONUtil.getString(data, "sid");
                                    String installationId = JSONUtil.getString(data, "installationId");
                                    String userId = JSONUtil.getString(data, "userId");

                                    // Store access token
                                    WonderPushConfiguration.setAccessToken(token);
                                    WonderPushConfiguration.setSID(sid);
                                    WonderPushConfiguration.setInstallationId(installationId);
                                    WonderPushConfiguration.setUserId(userId);

                                    JSONObject installation = json.optJSONObject("_installation");
                                    if (installation != null) {
                                        JSONObject custom = installation.optJSONObject("custom");
                                        WonderPush.receivedFullInstallationCustomPropertiesFromServer(custom);
                                    }
                                } finally {
                                    // Make sure to switch back to the current user now
                                    WonderPushConfiguration.changeUserId(prevUserId);
                                }
                                sIsFetchingAnonymousAccessToken = false;

                                WonderPush.refreshPreferencesAndConfiguration(false);

                                // call handlers
                                if (null != handler) {
                                    handler.onSuccess(statusCode, response);
                                }
                                ResponseHandler chainedHandler;
                                while ((chainedHandler = dequeueHandler()) != null) {
                                    chainedHandler.onSuccess(statusCode, response);
                                }
                                return;
                            }
                        }
                        Log.e(TAG, "Could not obtain anonymous access token from server");
                    }

                    @Override
                    public void onSuccess(Response response) {
                        this.onSuccess(200, response);
                    }
                }
        ));
    }

    /**
     * Fetches an anonymous access token and run the given request with that token.
     * Retries when access token cannot be fetched.
     *
     * @param request
     *            The request to be run
     */
    protected static void fetchAnonymousAccessTokenAndRunRequest(final Request request) {
        fetchAnonymousAccessToken(request.getUserId(), new ResponseHandler() {
            @Override
            public void onSuccess(Response response) {
                requestAuthenticated(request);
            }

            @Override
            public void onFailure(Throwable e, Response errorResponse) {
            }
        });
    }

    private static void queueHandler(ResponseHandler handler) {
        if (null == handler) {
            return;
        }

        synchronized (sPendingHandlers) {
            sPendingHandlers.add(handler);
        }
    }

    private static ResponseHandler dequeueHandler() {
        ResponseHandler handler = null;
        synchronized (sPendingHandlers) {
            if (sPendingHandlers.size() > 0) {
                handler = sPendingHandlers.get(0);
                if (null != handler) {
                    sPendingHandlers.remove(0);
                }
            }
        }
        return handler;
    }

    /**
     * A serializable object that represents a request to the WonderPush API.
     */
    protected static class Request {

        String mUserId;
        HttpMethod mMethod;
        RequestParams mParams;
        ResponseHandler mHandler;
        String mResource;

        public Request(String userId, HttpMethod method, String resource, RequestParams params, ResponseHandler handler) {
            mUserId = userId;
            mMethod = method;
            mParams = params;
            mHandler = handler;
            mResource = resource;
        }

        public Request(JSONObject data) throws JSONException {
            mUserId = data.has("userId") ? JSONUtil.getString(data, "userId") : WonderPushConfiguration.getUserId();
            try {
                mMethod = HttpMethod.valueOf(JSONUtil.getString(data, "method"));
            } catch (IllegalArgumentException ex) {
                mMethod = HttpMethod.values()[data.getInt("method")];
            }
            mResource = data.getString("resource");
            JSONObject paramsJson = data.getJSONObject("params");
            mParams = new RequestParams();
            @SuppressWarnings("unchecked")
            Iterator<String> keys = paramsJson.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                mParams.put(key, paramsJson.getString(key));
            }
        }

        public JSONObject toJSON() {
            try {
                JSONObject result = new JSONObject();
                result.put("userId", mUserId);
                result.put("method", mMethod.name());
                result.put("resource", mResource);
                JSONObject params = new JSONObject();
                if (null != mParams) {
                    for (BasicNameValuePair pair : mParams.getParamsList()) {
                        params.put(pair.getName(), pair.getValue());
                    }
                }
                result.put("params", params);
                return result;
            } catch (JSONException e) {
                WonderPush.logError("Failed to serialize job", e);
                return null;
            }
        }

        public String getUserId() {
            return mUserId;
        }

        public HttpMethod getMethod() {
            return mMethod;
        }

        public RequestParams getParams() {
            return mParams;
        }

        public ResponseHandler getHandler() {
            return mHandler;
        }

        public String getResource() {
            return mResource;
        }

        public void setMethod(HttpMethod mMethod) {
            this.mMethod = mMethod;
        }

        public void setParams(RequestParams mParams) {
            this.mParams = mParams;
        }

        public void setHandler(ResponseHandler mHandler) {
            this.mHandler = mHandler;
        }

        public void setResource(String resource) {
            this.mResource = resource;
        }

        @Override
        protected Object clone() {
            return new Request(mUserId, mMethod, mResource, mParams, mHandler);
        }

        /**
         * Generates X-WonderPush-Authorization header with request signature
         *
         * @return The authorization header or null for GET requests
         */
        protected BasicHeader getAuthorizationHeader() {
            try {
                StringBuilder sb = new StringBuilder();

                // Step 1: add HTTP method uppercase
                sb.append(mMethod.name().toUpperCase());
                sb.append('&');

                // Step 2: add the URI
                Uri uri = Uri.parse(mResource);

                // Query string is stripped from resource
                sb.append(encode(String.format("%s%s", WonderPush.getBaseURL(),
                        uri.getEncodedPath())));

                // Step 3: add URL encoded parameters
                sb.append('&');

                // Params from the URL
                List<BasicNameValuePair> unencodedParams = new ArrayList<>();
                RequestParams queryStringParams = QueryStringParser.getRequestParams(uri.getQuery());
                if (queryStringParams != null) {
                    unencodedParams.addAll(queryStringParams.getParamsList());
                }

                // Params from the request
                if (mParams != null) {
                    unencodedParams.addAll(mParams.getParamsList());
                }

                // Encode and sort params
                List<BasicNameValuePair> encodedParams = new ArrayList<>(unencodedParams.size());
                for (BasicNameValuePair pair : unencodedParams) {
                    encodedParams.add(new BasicNameValuePair(encode(pair.getName()), encode(pair.getValue())));
                }
                Collections.sort(encodedParams, new Comparator<BasicNameValuePair>() {
                    @Override
                    public int compare(BasicNameValuePair lhs, BasicNameValuePair rhs) {
                        int rtn = lhs.getName().compareTo(rhs.getName());
                        if (rtn == 0) {
                            rtn = lhs.getValue().compareTo(rhs.getValue());
                        }
                        return rtn;
                    }
                });

                // Append to the clear signature
                boolean first = true;
                for (BasicNameValuePair pair : encodedParams) {
                    if (first) {
                        first = false;
                    } else {
                        sb.append("%26");
                    }
                    sb.append(encode(String.format("%s=%s", pair.getName(), pair.getValue())));
                }

                // Step 4: add body
                sb.append('&');
                // TODO: add the body here when we support other content types than application/x-www-form-urlencoded

                // Final step: Hash and format header
                Mac mac = Mac.getInstance("HmacSHA1");
                SecretKeySpec secret = new SecretKeySpec(WonderPush.getClientSecret().getBytes("UTF-8"), mac.getAlgorithm());
                mac.init(secret);
                byte[] digest = mac.doFinal(sb.toString().getBytes());
                String sig = Base64.encodeToString(digest, Base64.DEFAULT).trim();
                String encodedSig = encode(sig.trim());
                return new BasicHeader("X-WonderPush-Authorization", String.format("WonderPush sig=\"%s\", meth=\"0\"", encodedSig));
            } catch (Exception e) {
                Log.e(TAG, "Could not generate signature", e);
                return null;
            }
        }

        private static String encode(String s) throws UnsupportedEncodingException {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
        }

        @Override
        public String toString() {
            return "" + mMethod + " " + mResource + "?" + mParams;
        }

    }

}
