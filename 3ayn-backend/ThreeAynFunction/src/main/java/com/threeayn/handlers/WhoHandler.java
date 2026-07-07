package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.InvalidParameterException;
import software.amazon.awssdk.services.rekognition.model.SearchFacesByImageResponse;

import java.util.Map;

/**
 * POST /who { image: base64-jpeg, lang }
 * -> { text, match: boolean }
 * Matches the largest face in the frame against enrolled profiles.
 */
public class WhoHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final RekognitionClient REK = RekognitionClient.create();
    private static final DynamoDbClient DDB = DynamoDbClient.create();
    private static final String COLLECTION = System.getenv().getOrDefault("FACE_COLLECTION", "threeayn-faces");
    private static final String TABLE = System.getenv().getOrDefault("PROFILES_TABLE", "ThreeAynProfiles");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            RequestParser req = new RequestParser(event);
            byte[] image = req.imageBytes();
            String lang = req.lang();

            SearchFacesByImageResponse resp;
            try {
                resp = REK.searchFacesByImage(b -> b
                    .collectionId(COLLECTION)
                    .image(Image.builder().bytes(SdkBytes.fromByteArray(image)).build())
                    .faceMatchThreshold(90f)
                    .maxFaces(1));
            } catch (InvalidParameterException noFace) {
                String t = lang.equals("ar") ? "لا يوجد وجه واضح أمام الكاميرا" : "No clear face in front of the camera";
                return ApiResponse.success(Map.of("text", t, "match", false));
            }

            if (resp.faceMatches().isEmpty()) {
                String t = lang.equals("ar") ? "شخص غير معروف أمامك" : "An unrecognized person is in front of you";
                return ApiResponse.success(Map.of("text", t, "match", false));
            }

            String faceId = resp.faceMatches().get(0).face().faceId();
            GetItemResponse item = DDB.getItem(b -> b.tableName(TABLE)
                .key(Map.of("faceId", AttributeValue.fromS(faceId))));

            if (!item.hasItem()) {
                String t = lang.equals("ar") ? "شخص غير معروف أمامك" : "An unrecognized person is in front of you";
                return ApiResponse.success(Map.of("text", t, "match", false));
            }

            String name = item.item().get("name").s();
            String relation = item.item().getOrDefault("relation", AttributeValue.fromS("")).s();

            String text = lang.equals("ar")
                ? "أمامك " + name + (relation.isBlank() ? "" : "، " + relation)
                : name + (relation.isBlank() ? "" : ", your " + relation) + " is in front of you";
            return ApiResponse.success(Map.of("text", text, "match", true));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("WhoHandler error: " + e);
            return ApiResponse.error(500, "Face recognition failed");
        }
    }
}
