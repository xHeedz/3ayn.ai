package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consent-gated "trusted viewer" — NOT live video. The wearer opts in from
 * Settings; a caregiver with the resulting link sees a still frame refreshed
 * every few seconds. This is the safety-net fallback behind the real live-video
 * feature (Kinesis Video Streams WebRTC) — always available, nothing to fail.
 *
 * POST /watch/start { userId? }        -> { watchId }         wearer opts in
 * POST /watch/frame { watchId, image } -> { ok:true }         wearer uploads a frame
 * GET  /watch/{watchId}                -> { active, image, updatedAt } caregiver polls
 * POST /watch/stop  { watchId }        -> { ok:true }         wearer revokes access
 */
public class WatchHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final DynamoDbClient DDB = DynamoDbClient.create();
    private static final String TABLE = System.getenv().getOrDefault("WATCH_TABLE", "ThreeAynWatch");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            String path = event.getResource();
            String method = event.getHttpMethod();

            if ("GET".equalsIgnoreCase(method)) return getFrame(event);
            if (path.endsWith("/start")) return start(event);
            if (path.endsWith("/frame")) return frame(event);
            if (path.endsWith("/stop")) return stop(event);
            return ApiResponse.error(404, "Unknown watch route");
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("WatchHandler error: " + e);
            return ApiResponse.error(500, "Watch operation failed");
        }
    }

    private APIGatewayProxyResponseEvent start(APIGatewayProxyRequestEvent event) {
        RequestParser req = new RequestParser(event);
        String userId = req.field("userId", "");
        String watchId = UUID.randomUUID().toString().substring(0, 8);

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("watchId", AttributeValue.fromS(watchId));
        item.put("active", AttributeValue.fromBool(true));
        item.put("userId", AttributeValue.fromS(userId));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        DDB.putItem(b -> b.tableName(TABLE).item(item));

        return ApiResponse.success(Map.of("watchId", watchId));
    }

    private APIGatewayProxyResponseEvent frame(APIGatewayProxyRequestEvent event) {
        RequestParser req = new RequestParser(event);
        String watchId = req.requiredField("watchId");
        byte[] image = req.imageBytes();

        // only update if the session is still active — revoked sessions ignore uploads
        GetItemResponse existing = DDB.getItem(b -> b.tableName(TABLE)
            .key(Map.of("watchId", AttributeValue.fromS(watchId))));
        if (!existing.hasItem() || !existing.item().getOrDefault("active", AttributeValue.fromBool(false)).bool()) {
            return ApiResponse.error(410, "Watch session not active");
        }

        Map<String, AttributeValue> item = new HashMap<>(existing.item());
        item.put("image", AttributeValue.fromS(java.util.Base64.getEncoder().encodeToString(image)));
        item.put("updatedAt", AttributeValue.fromS(Instant.now().toString()));
        DDB.putItem(b -> b.tableName(TABLE).item(item));

        return ApiResponse.success(Map.of("ok", true));
    }

    private APIGatewayProxyResponseEvent getFrame(APIGatewayProxyRequestEvent event) {
        Map<String, String> path = event.getPathParameters();
        if (path == null || path.get("watchId") == null) return ApiResponse.error(400, "watchId is required");
        String watchId = path.get("watchId");

        GetItemResponse item = DDB.getItem(b -> b.tableName(TABLE)
            .key(Map.of("watchId", AttributeValue.fromS(watchId))));
        if (!item.hasItem()) return ApiResponse.error(404, "Watch session not found");

        boolean active = item.item().getOrDefault("active", AttributeValue.fromBool(false)).bool();
        String image = item.item().containsKey("image") ? item.item().get("image").s() : null;
        String updatedAt = item.item().containsKey("updatedAt") ? item.item().get("updatedAt").s() : null;

        Map<String, Object> out = new HashMap<>();
        out.put("active", active);
        out.put("image", image);
        out.put("updatedAt", updatedAt);
        return ApiResponse.success(out);
    }

    private APIGatewayProxyResponseEvent stop(APIGatewayProxyRequestEvent event) {
        RequestParser req = new RequestParser(event);
        String watchId = req.requiredField("watchId");

        GetItemResponse existing = DDB.getItem(b -> b.tableName(TABLE)
            .key(Map.of("watchId", AttributeValue.fromS(watchId))));
        if (!existing.hasItem()) return ApiResponse.error(404, "Watch session not found");

        Map<String, AttributeValue> item = new HashMap<>(existing.item());
        item.put("active", AttributeValue.fromBool(false));
        item.remove("image");
        DDB.putItem(b -> b.tableName(TABLE).item(item));

        return ApiResponse.success(Map.of("ok", true));
    }
}
