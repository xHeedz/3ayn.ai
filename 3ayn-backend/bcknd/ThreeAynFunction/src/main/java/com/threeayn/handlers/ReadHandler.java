package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * POST /read { image: base64-jpeg, lang }
 * -> { text }  OCR via Amazon Textract; reads signs, menus, labels, documents.
 */
public class ReadHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final TextractClient TEXTRACT = TextractClient.create();

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            RequestParser req = new RequestParser(event);
            byte[] image = req.imageBytes();

            DetectDocumentTextResponse resp = TEXTRACT.detectDocumentText(b -> b
                .document(Document.builder().bytes(SdkBytes.fromByteArray(image)).build()));

            String text = resp.blocks().stream()
                .filter(bl -> bl.blockType() == BlockType.LINE)
                .map(bl -> bl.text())
                .collect(Collectors.joining("\n"));

            if (text.isBlank()) {
                String none = req.lang().equals("ar") ? "لا يوجد نص واضح أمامك" : "No readable text in front of you";
                return ApiResponse.success(Map.of("text", none));
            }
            return ApiResponse.success(Map.of("text", text));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("ReadHandler error: " + e);
            return ApiResponse.error(500, "Text reading failed");
        }
    }
}
