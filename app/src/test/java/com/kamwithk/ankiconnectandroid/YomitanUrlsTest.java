package com.kamwithk.ankiconnectandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import com.kamwithk.ankiconnectandroid.routing.ApiKey;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** The copy-to-clipboard URLs must match the documented values exactly. */
@RunWith(RobolectricTestRunner.class)
public class YomitanUrlsTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit();
    }

    @Test
    public void ankiConnectUrlIsTheBaseAddress() {
        assertEquals("http://127.0.0.1:" + Service.PORT, YomitanUrls.ankiConnect());
    }

    @Test
    public void audioUrlsUseTheDocumentedTemplatesWithoutAKeyByDefault() {
        assertEquals(
                "http://localhost:" + Service.PORT + "/localaudio/get/?term={term}&reading={reading}",
                YomitanUrls.localAudio(context));
        assertEquals(
                "http://localhost:" + Service.PORT + "/?term={term}&reading={reading}", YomitanUrls.forvo(context));
    }

    @Test
    public void audioUrlsEmbedTheKeyWhenLoopbackRequiresIt() {
        String key = ApiKey.getOrCreateApiKey(context);
        PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putBoolean(ApiKey.PREF_REQUIRE_API_KEY_FROM_LOOPBACK, true)
                .commit();

        assertTrue(YomitanUrls.localAudio(context).endsWith("&key=" + key));
        assertTrue(YomitanUrls.forvo(context).endsWith("&key=" + key));
        // AnkiConnect never carries the key: Yomitan has a separate field for it.
        assertEquals("http://127.0.0.1:" + Service.PORT, YomitanUrls.ankiConnect());
    }
}
