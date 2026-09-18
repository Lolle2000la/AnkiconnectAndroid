package com.kamwithk.ankiconnectandroid.routing;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.kamwithk.ankiconnectandroid.request_parsers.Parser;
import fi.iki.elonen.NanoHTTPD;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;

/**
 * API key handling, mirroring the desktop AnkiConnect add-on: a random key is generated and every
 * request from outside loopback must supply it. Apps on the phone (loopback) are exempt by default
 * so Yomitan keeps working without configuration; the user can opt into requiring the key from
 * loopback too.
 */
public final class ApiKey {
    public static final String PREF_API_KEY = "api_key";
    public static final String PREF_REQUIRE_API_KEY = "require_api_key";
    public static final String PREF_REQUIRE_API_KEY_FROM_LOOPBACK = "require_api_key_from_loopback";

    private static final String ERROR_MESSAGE = "valid api key must be provided";
    private static final int KEY_BYTES = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKey() {}

    /** Returns the stored key, generating and persisting a new one the first time. */
    public static String getOrCreateApiKey(Context context) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        String key = preferences.getString(PREF_API_KEY, null);
        if (key == null || key.isEmpty()) {
            key = generateKey();
            preferences.edit().putString(PREF_API_KEY, key).apply();
        }
        return key;
    }

    /** Replaces the stored key with a fresh random one and returns it. */
    public static String regenerateApiKey(Context context) {
        String key = generateKey();
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_API_KEY, key)
                .apply();
        return key;
    }

    static String generateKey() {
        byte[] bytes = new byte[KEY_BYTES];
        RANDOM.nextBytes(bytes);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /** Whether a request from this address must supply the key. */
    public static boolean isRequired(Context context, String remoteIpAddress) {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean(PREF_REQUIRE_API_KEY, true)) {
            return false;
        }
        if (isLoopback(remoteIpAddress)) {
            return preferences.getBoolean(PREF_REQUIRE_API_KEY_FROM_LOOPBACK, false);
        }
        return true;
    }

    /** Returns an error response when the request is not authorized, or {@code null} when it is. */
    public static NanoHTTPD.Response verify(Context context, NanoHTTPD.IHTTPSession session, String postData) {
        return verify(context, session.getRemoteIpAddress(), session.getHeaders(), session.getParameters(), postData);
    }

    static NanoHTTPD.Response verify(
            Context context,
            String remoteIpAddress,
            Map<String, String> headers,
            Map<String, List<String>> parameters,
            String postData) {
        if (!isRequired(context, remoteIpAddress)) {
            return null;
        }

        // Desktop AnkiConnect exempts requestPermission so a browser extension can discover whether
        // a key is needed before it has one; mirror that.
        if (isPermissionRequest(parameters, postData)) {
            return null;
        }

        String expected = getOrCreateApiKey(context);
        String provided = extractKey(headers, parameters, postData);
        if (provided != null
                && MessageDigest.isEqual(
                        provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        return errorResponse();
    }

    /** Accepts the key as an {@code X-Api-Key} header, a {@code key} parameter, or a JSON field. */
    static String extractKey(Map<String, String> headers, Map<String, List<String>> parameters, String postData) {
        if (headers != null) {
            String header = headers.get("x-api-key");
            if (header != null && !header.isEmpty()) {
                return header;
            }
        }

        if (parameters != null) {
            List<String> queryKey = parameters.get("key");
            if (queryKey != null && !queryKey.isEmpty()) {
                return queryKey.get(0);
            }
        }

        if (postData != null && !postData.isEmpty()) {
            try {
                JsonObject json = JsonParser.parseString(postData).getAsJsonObject();
                JsonElement key = json.get("key");
                if (key != null && !key.isJsonNull()) {
                    return key.getAsString();
                }
            } catch (RuntimeException ignored) {
                // Body was not JSON; treat it as unauthenticated.
            }
        }

        return null;
    }

    /** requestPermission is exempt from the key check, matching desktop AnkiConnect. */
    static boolean isPermissionRequest(Map<String, List<String>> parameters, String postData) {
        if (parameters != null) {
            List<String> action = parameters.get("action");
            if (action != null && action.contains("requestPermission")) {
                return true;
            }
        }
        if (postData != null && !postData.isEmpty()) {
            try {
                JsonObject json = JsonParser.parseString(postData).getAsJsonObject();
                JsonElement action = json.get("action");
                if (action != null && !action.isJsonNull() && "requestPermission".equals(action.getAsString())) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // Not JSON; not a permission request.
            }
        }
        return false;
    }

    static boolean isLoopback(String remoteIpAddress) {
        if (remoteIpAddress == null || remoteIpAddress.isEmpty()) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteIpAddress).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }

    private static NanoHTTPD.Response errorResponse() {
        JsonObject error = new JsonObject();
        error.add("result", null);
        error.addProperty("error", ERROR_MESSAGE);
        return NanoHTTPD.newFixedLengthResponse(
                NanoHTTPD.Response.Status.OK, "application/json", Parser.gson.toJson(error));
    }
}
