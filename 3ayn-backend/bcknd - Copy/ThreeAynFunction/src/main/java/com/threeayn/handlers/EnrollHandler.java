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
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.IndexFacesResponse;
import software.amazon.awssdk.services.rekognition.model.QualityFilter;
import software.amazon.awssdk.services.rekognition.model.ResourceAlreadyExistsException;

import java.util.Map;

/**
 * POST /enroll { image: base64-jpeg, name: string, relation: string }
 * -> { text, faceId }
 * Caregiver enrolls a known person: face goes into the Rekognition collection,
 * name + relation go into DynamoDB keyed by faceId. Consent model: ONLY
 * deliberately enrolled people are ever matched; strangers are never stored.
 */
public class EnrollHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final RekognitionClient REK = RekognitionClient.create();
    private static final DynamoDbClient DDB = DynamoDbClient.create();
    private static final String COLLECTION = System.getenv().getOrDefault("FACE_COLLECTION", "threeayn-faces");
    private static final String TABLE = System.getenv().getOrDefault("PROFILES_TABLE", "ThreeAynProfiles");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            RequestParser req = new RequestParser(event);
            byte[] image = req.imageBytes();
            String name = req.requiredField("name");
            String relation = req.field("relation", "");

            ensureCollection();

            IndexFacesResponse resp = REK.indexFaces(b -> b
                .collectionId(COLLECTION)
                .image(Image.builder().bytes(SdkBytes.fromByteArray(image)).build())
                .maxFaces(1)
                .qualityFilter(QualityFilter.AUTO));

            if (resp.faceRecords().isEmpty()) {
                return ApiResponse.error(400, "No clear face found in the photo — use a frontal, well-lit photo");
            }
            String faceId = resp.faceRecords().get(0).face().faceId();

            DDB.putItem(b -> b.tableName(TABLE).item(Map.of(
                "faceId", AttributeValue.fromS(faceId),
                "name", AttributeValue.fromS(name),
                "relation", AttributeValue.fromS(relation)
            )));

            return ApiResponse.success(Map.of(
                "text", name + " enrolled successfully",
                "faceId", faceId
            ));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("EnrollHandler error: " + e);
            return ApiResponse.error(500, "Enrollment failed");
        }
    }

    private void ensureCollection() {
        try {
            REK.createCollection(b -> b.collectionId(COLLECTION));
        } catch (ResourceAlreadyExistsException ignored) {
            // collection already exists — fine
        }
    }
}
