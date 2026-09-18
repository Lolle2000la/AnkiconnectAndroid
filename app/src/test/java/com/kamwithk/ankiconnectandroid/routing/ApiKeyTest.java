package com.kamwithk.ankiconnectandroid.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import com.google.gson.JsonObject;
import fi.iki.elonen.NanoHTTPD;
import java.util.List;
import java.util.Map;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * API key behaviour: a key is generated on demand, requests from outside loopback must present it,
 * and loopback is exempt until the user opts in.
 */
@RunWith(RobolectricTestRunner.class)
public class ApiKeyTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void generatesAStableKey() {
        String first = ApiKey.getOrCreateApiKey(context);

        assertEquals(32, first.length());
        assertEquals(first, ApiKey.getOrCreateApiKey(context));
    }

    @Test
    public void regeneratingReplacesTheKey() {
        String first = ApiKey.getOrCreateApiKey(context);
        String second = ApiKey.regenerateApiKey(context);

        assertNotEquals(first, second);
        assertEquals(second, ApiKey.getOrCreateApiKey(context));
    }

    @Test
    public void detectsLoopbackAddresses() {
        assertTrue(ApiKey.isLoopback("127.0.0.1"));
        assertTrue(ApiKey.isLoopback("::1"));
        assertTrue(ApiKey.isLoopback("0:0:0:0:0:0:0:1"));
        assertTrue(ApiKey.isLoopback("::ffff:127.0.0.1"));
        assertFalse(ApiKey.isLoopback("192.168.1.20"));
        assertFalse(ApiKey.isLoopback(null));
    }

    @Test
    public void extractsTheKeyFromEverySupportedSource() {
        assertEquals("header", ApiKey.extractKey(Map.of("x-api-key", "header"), Map.of(), null));
        assertEquals("query", ApiKey.extractKey(Map.of(), Map.of("key", List.of("query")), null));

        JsonObject json = new JsonObject();
        json.addProperty("action", "version");
        json.addProperty("key", "body");
        assertEquals("body", ApiKey.extractKey(Map.of(), Map.of(), json.toString()));

        assertNull(ApiKey.extractKey(Map.of(), Map.of(), null));
        assertNull(ApiKey.extractKey(Map.of(), Map.of(), "not json"));
    }

    @Test
    public void requiresTheKeyFromNonLoopbackByDefault() {
        String key = ApiKey.getOrCreateApiKey(context);
        assertTrue(ApiKey.isRequired(context, "192.168.1.20"));
        assertFalse(ApiKey.isRequired(context, "127.0.0.1"));

        NanoHTTPD.Response missing =
                ApiKey.verify(context, "192.168.1.20", Map.of(), Map.of(), "{\"action\":\"version\"}");
        assertNotNull(missing);
        assertEquals(NanoHTTPD.Response.Status.OK, missing.getStatus());

        NanoHTTPD.Response correct = ApiKey.verify(
                context, "192.168.1.20", Map.of(), Map.of(), "{\"action\":\"version\",\"key\":\"" + key + "\"}");
        assertNull(correct);

        assertNull(ApiKey.verify(context, "127.0.0.1", Map.of(), Map.of(), null));
    }

    @Test
    public void requestPermissionIsExemptFromTheKey() {
        assertNull(ApiKey.verify(
                context, "192.168.1.20", Map.of(), Map.of(), "{\"action\":\"requestPermission\",\"version\":6}"));
    }

    @Test
    public void canRequireTheKeyFromLoopbackToo() {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(ApiKey.PREF_REQUIRE_API_KEY_FROM_LOOPBACK, true)
                .commit();

        assertTrue(ApiKey.isRequired(context, "127.0.0.1"));
        assertNotNull(ApiKey.verify(context, "127.0.0.1", Map.of(), Map.of(), null));

        String key = ApiKey.getOrCreateApiKey(context);
        assertNull(ApiKey.verify(context, "127.0.0.1", Map.of("x-api-key", key), Map.of(), null));
    }

    @Test
    public void requirementCanBeDisabledEntirely() {
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(ApiKey.PREF_REQUIRE_API_KEY, false)
                .commit();

        assertFalse(ApiKey.isRequired(context, "192.168.1.20"));
        assertNull(ApiKey.verify(context, "192.168.1.20", Map.of(), Map.of(), null));
    }
}
