package com.kamwithk.ankiconnectandroid.routing;

import android.content.Context;
import androidx.preference.PreferenceManager;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.router.RouterNanoHTTPD;
import java.io.IOException;

public class Router extends RouterNanoHTTPD {
    // Loopback only by default: the intended client (Yomitan in a browser on this device) connects
    // to localhost:8765, and keeping the listening socket off the Wi-Fi interface stops it from
    // holding the Wi-Fi chip awake. The "allow_lan_access" preference restores the wildcard bind
    // for users who need to reach the server from another device.
    private static final String LOOPBACK_HOST = "127.0.0.1";

    private Context context;
    public static String contentType;

    public Router(Integer port, Context context) throws IOException {
        super(resolveBindHost(context), port);
        this.context = context;

        contentType = new ContentType("; charset=UTF-8").getContentTypeHeader();
        addMappings();
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
    }

    /**
     * Returns the address to bind, or {@code null} for the wildcard (all interfaces). A null host
     * makes NanoHTTPD use {@code new InetSocketAddress(port)}, which is what the app always did.
     */
    private static String resolveBindHost(Context context) {
        boolean allowLan =
                PreferenceManager.getDefaultSharedPreferences(context).getBoolean("allow_lan_access", false);
        return allowLan ? null : LOOPBACK_HOST;
    }

    public void setContext(Context context) {
        this.context = context;
    }

    @Override
    public void addMappings() {
        addRoute("/", RouteHandler.class, this.context);
        addRoute("/localaudio/(.)+", LocalAudioRouteHandler.class, this.context);
        // for some reason, none of these work, so the above is used instead
        // addRoute("/localaudio/:source/(.)+", LocalAudioRouteHandler.class, this.context);
        // addRoute("/localaudio/:source/:file", LocalAudioRouteHandler.class, this.context);
    }
}
