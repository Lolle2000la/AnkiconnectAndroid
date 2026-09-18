package com.kamwithk.ankiconnectandroid.routing;

import static fi.iki.elonen.NanoHTTPD.MIME_PLAINTEXT;
import static fi.iki.elonen.NanoHTTPD.newFixedLengthResponse;

import android.content.Context;
import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.router.RouterNanoHTTPD;
import java.util.Map;

public class LocalAudioRouteHandler extends RouterNanoHTTPD.DefaultHandler {
    private LocalAudioAPIRouting routing = null;

    public LocalAudioRouteHandler() {
        super();
    }

    @Override
    public String getText() {
        return "not implemented";
    }

    @Override
    public String getMimeType() {
        return "text/json";
    }

    @Override
    public NanoHTTPD.Response.IStatus getStatus() {
        return NanoHTTPD.Response.Status.OK;
    }

    public NanoHTTPD.Response get(
            RouterNanoHTTPD.UriResource uriResource, Map<String, String> urlParams, NanoHTTPD.IHTTPSession session) {
        // setup ???
        // TODO this looks like a hack (same with the main handler!)
        Context context = uriResource.initParameter(0, Context.class); // ???
        if (routing == null) {
            routing = new LocalAudioAPIRouting(context);
        }

        // Requests from outside loopback must carry the API key (unless disabled in the settings).
        boolean keyRequired = ApiKey.isRequired(context, session.getRemoteIpAddress());
        NanoHTTPD.Response unauthorized = ApiKey.verify(context, session, null);
        if (unauthorized != null) {
            return unauthorized;
        }

        String uri = session.getUri();
        if (uri.equals("/localaudio/get/")) { // get sources
            // When the caller needed a key, embed it in the generated file URLs so fetching the audio
            // itself stays authorized too (relevant when the key is required from loopback).
            String urlKey = keyRequired ? ApiKey.getOrCreateApiKey(context) : null;
            return routing.getAudioSourcesHandleError(session.getParameters(), urlKey);
        }

        // otherwise, it's getting the actual audio file instead
        // the uri should be of the format: /localaudio/SOURCE/FILE_NAME
        // components should be: ["", "localaudio", SOURCE, FILE_NAME]
        String[] uriComponents = uri.split("/", 4);
        if (uriComponents.length != 4) {
            return newFixedLengthResponse(NanoHTTPD.Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid uri: " + uri);
        }
        return routing.getAudioHandleError(uriComponents[2], uriComponents[3]);
    }
}
