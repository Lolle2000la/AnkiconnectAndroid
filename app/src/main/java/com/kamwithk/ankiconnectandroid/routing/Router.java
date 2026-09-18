package com.kamwithk.ankiconnectandroid.routing;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.router.RouterNanoHTTPD;
import java.io.IOException;

public class Router extends RouterNanoHTTPD {
    // Loopback only: the intended client (Yomitan in a browser on this device) connects to
    // localhost:8765. Binding to 127.0.0.1 instead of the wildcard keeps the server off the LAN,
    // so other devices cannot reach it and the Wi-Fi radio is not woken by inbound connections.
    private static final String HOST = "127.0.0.1";

    private Context context;
    public static String contentType;

    public Router(Integer port, Context context) throws IOException {
        super(HOST, port);
        this.context = context;

        contentType = new ContentType("; charset=UTF-8").getContentTypeHeader();
        addMappings();
        start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
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
