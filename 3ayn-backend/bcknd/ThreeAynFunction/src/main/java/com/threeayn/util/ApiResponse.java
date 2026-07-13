package com.threeayn.util;

import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.google.gson.Gson;
import java.util.Map;

/** Every response carries CORS headers — the Lambda owns them, not just API Gateway. */
public final class ApiResponse {
    private static final Gson GSON = new Gson();
    private static final Map<String, String> HEADERS = Map.of(
        "Content-Type", "application/json",
        "Access-Control-Allow-Origin", "*",
        "Access-Control-Allow-Headers", "Content-Type",
        "Access-Control-Allow-Methods", "POST,OPTIONS"
    );

    private ApiResponse() {}

    public static APIGatewayProxyResponseEvent success(Object body) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(200).withHeaders(HEADERS).withBody(GSON.toJson(body));
    }

    public static APIGatewayProxyResponseEvent error(int status, String message) {
        return new APIGatewayProxyResponseEvent()
            .withStatusCode(status).withHeaders(HEADERS)
            .withBody(GSON.toJson(Map.of("error", true, "message", message)));
    }
}
