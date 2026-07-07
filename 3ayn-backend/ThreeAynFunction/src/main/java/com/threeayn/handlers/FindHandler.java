package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectLabelsResponse;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.Instance;
import software.amazon.awssdk.services.rekognition.model.Label;

import java.util.Map;

/**
 * POST /find { image: base64-jpeg, object: englishLabel, lang }
 * -> { text }  Locates the requested object with Rekognition DetectLabels
 * and answers with a rough direction (left / right / in front) from the bounding box.
 */
public class FindHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final RekognitionClient REK = RekognitionClient.create();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            RequestParser req = new RequestParser(event);
            byte[] image = req.imageBytes();
            String target = req.requiredField("object");
            String targetAr = req.field("objectAr", target);
            String lang = req.lang();

            DetectLabelsResponse resp = REK.detectLabels(b -> b
                .image(Image.builder().bytes(SdkBytes.fromByteArray(image)).build())
                .maxLabels(30)
                .minConfidence(55f));

            Label match = resp.labels().stream()
                .filter(l -> l.name().equalsIgnoreCase(target)
                    || l.parents().stream().anyMatch(p -> p.name().equalsIgnoreCase(target)))
                .findFirst().orElse(null);

            String text;
            if (match == null) {
                text = lang.equals("ar")
                    ? "لم أجد " + targetAr + " أمامك — جرّبي تحريك الكاميرا"
                    : "I don't see a " + target + " in front of you — try moving the camera";
            } else {
                String direction = direction(match, lang);
                text = lang.equals("ar")
                    ? "يوجد " + targetAr + " " + direction
                    : "There is a " + target + " " + direction;
            }
            return ApiResponse.success(Map.of("text", text));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("FindHandler error: " + e);
            return ApiResponse.error(500, "Object search failed");
        }
    }

    /** Direction from bounding-box center. NOTE: the frontend mirrors the preview but sends the
     *  un-mirrored frame, so box x maps directly to the wearer's real left/right. */
    private String direction(Label label, String lang) {
        if (label.instances().isEmpty()) {
            return lang.equals("ar") ? "أمامك" : "in front of you";
        }
        Instance inst = label.instances().get(0);
        float cx = inst.boundingBox().left() + inst.boundingBox().width() / 2f;
        if (cx < 0.35f) return lang.equals("ar") ? "إلى يسارك" : "to your left";
        if (cx > 0.65f) return lang.equals("ar") ? "إلى يمينك" : "to your right";
        return lang.equals("ar") ? "أمامك مباشرة" : "directly in front of you";
    }
}
