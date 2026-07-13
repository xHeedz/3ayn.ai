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
 * POST /user { name, lang, emergencyContact? }  -> { userId, text }  (create profile + welcome line)
 * GET  /user/{userId}                           -> { userId, name, lang, ... } or 404
 *
 * Deliberately NO authentication: 3ayn is a single-wearer assistive device.
 * A profile personalizes narration and keys the event history — it is not an account.
 */
public class UserHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final DynamoDbClient DDB = DynamoDbClient.create();
    private static final String TABLE = System.getenv().getOrDefault("USERS_TABLE", "ThreeAynUsers");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            if ("GET".equalsIgnoreCase(event.getHttpMethod())) return getUser(event);
            return createUser(event);
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("UserHandler error: " + e);
            return ApiResponse.error(500, "User operation failed");
        }
    }

    private APIGatewayProxyResponseEvent createUser(APIGatewayProxyRequestEvent event) {
        RequestParser req = new RequestParser(event);
        String name = req.requiredField("name");
        String lang = req.lang();
        String emergency = req.field("emergencyContact", "");
        String userId = UUID.randomUUID().toString();

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("userId", AttributeValue.fromS(userId));
        item.put("name", AttributeValue.fromS(name));
        item.put("lang", AttributeValue.fromS(lang));
        item.put("createdAt", AttributeValue.fromS(Instant.now().toString()));
        if (!emergency.isBlank()) item.put("emergencyContact", AttributeValue.fromS(emergency));
        DDB.putItem(b -> b.tableName(TABLE).item(item));

        String welcome = lang.equals("ar")
            ? "أهلاً " + name + "، عين جاهزة لمساعدتك"
            : "Welcome " + name + ", 3ayn is ready to help you";
        return ApiResponse.success(Map.of("userId", userId, "text", welcome));
    }

    private APIGatewayProxyResponseEvent getUser(APIGatewayProxyRequestEvent event) {
        Map<String, String> path = event.getPathParameters();
        if (path == null || path.get("userId") == null) return ApiResponse.error(400, "userId is required");
        String userId = path.get("userId");

        GetItemResponse item = DDB.getItem(b -> b.tableName(TABLE)
            .key(Map.of("userId", AttributeValue.fromS(userId))));
        if (!item.hasItem()) return ApiResponse.error(404, "User not found");

        Map<String, String> out = new HashMap<>();
        item.item().forEach((k, v) -> out.put(k, v.s()));
        return ApiResponse.success(out);
    }
}
