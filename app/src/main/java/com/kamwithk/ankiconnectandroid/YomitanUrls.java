package com.kamwithk.ankiconnectandroid;

import android.content.Context;
import com.kamwithk.ankiconnectandroid.routing.ApiKey;

/**
 * Ready-to-paste URLs for Yomitan, so users don't have to assemble them from the documentation.
 *
 * <p>The local audio and Forvo URLs embed the API key only when the user has opted into requiring
 * the key from loopback; the AnkiConnect URL never does because Yomitan keeps the key in a separate
 * settings field.
 */
public final class YomitanUrls {
    private static final String LOOPBACK = "127.0.0.1";

    private YomitanUrls() {}

    /** Base URL for Yomitan's Anki integration (no path and no template). */
    public static String ankiConnect() {
        return "http://" + LOOPBACK + ":" + Service.PORT;
    }

    /** Custom audio-source URL for the imported local audio database. */
    public static String localAudio(Context context) {
        return withKey(context, "http://localhost:" + Service.PORT + "/localaudio/get/?term={term}&reading={reading}");
    }

    /** Custom audio-source URL for the scraped Forvo source (root path). */
    public static String forvo(Context context) {
        return withKey(context, "http://localhost:" + Service.PORT + "/?term={term}&reading={reading}");
    }

    private static String withKey(Context context, String url) {
        if (ApiKey.isRequired(context, LOOPBACK)) {
            return url + "&key=" + ApiKey.getOrCreateApiKey(context);
        }
        return url;
    }
}
