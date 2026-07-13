package com.threeayn.util;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Base64;

/** Parses the shared request shape: { image?: base64-jpeg, lang?: "ar"|"en", ... }. */
public final class RequestParser {
    private static final Gson GSON = new Gson();
    private final JsonObject json;

    public RequestParser(APIGatewayProxyRequestEvent event) {
        String body = event.getBody();
        if (body == null || body.isBlank()) throw new IllegalArgumentException("Empty request body");
        this.json = GSON.fromJson(body, JsonObject.class);
    }

    public byte[] imageBytes() {
        if (!json.has("image")) throw new IllegalArgumentException("Missing 'image' field");
        String data = json.get("image").getAsString();
        int comma = data.indexOf(',');                 // tolerate full data URLs
        if (comma >= 0) data = data.substring(comma + 1);
        return Base64.getDecoder().decode(data);
    }

    public String lang() {
        return json.has("lang") ? json.get("lang").getAsString() : "ar";
    }

    public String field(String name, String fallback) {
        return json.has(name) ? json.get(name).getAsString() : fallback;
    }

    public String requiredField(String name) {
        if (!json.has(name) || json.get(name).getAsString().isBlank())
            throw new IllegalArgumentException("Missing '" + name + "' field");
        return json.get(name).getAsString();
    }
}
