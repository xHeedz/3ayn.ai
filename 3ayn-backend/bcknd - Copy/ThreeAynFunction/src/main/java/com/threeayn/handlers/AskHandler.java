package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.*;

import java.util.Map;

/**
 * POST /ask { image: base64-jpeg, lang: "ar"|"en", question?: string }
 * -> { text }  Scene description via Bedrock Nova Lite (multimodal).
 */
public class AskHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final BedrockRuntimeClient BEDROCK = BedrockRuntimeClient.create();
    private static final String MODEL_ID = System.getenv().getOrDefault("MODEL_ID", "eu.amazon.nova-lite-v1:0");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            RequestParser req = new RequestParser(event);
            byte[] image = req.imageBytes();
            String lang = req.lang();
            String question = req.field("question", null);

            String instruction = lang.equals("ar")
                ? "أنت عينا شخص كفيف. صف المشهد في هذه الصورة بالعربية في جملتين إلى ثلاث جمل قصيرة وواضحة."
                  + " ركّز على الأشخاص وما يفعلونه، والعوائق، وأي شيء مهم للسلامة أو التفاعل الاجتماعي."
                  + " لا تذكر أنك تحلل صورة — تكلم مباشرة."
                : "You are the eyes of a blind person. Describe the scene in this image in two to three short,"
                  + " clear English sentences. Focus on people and what they are doing, obstacles, and anything"
                  + " relevant to safety or social interaction. Do not mention you are analyzing an image — speak directly.";
            if (question != null && !question.isBlank()) {
                instruction += lang.equals("ar")
                    ? " أجب أيضاً عن هذا السؤال: " + question
                    : " Also answer this question: " + question;
            }

            ContentBlock img = ContentBlock.fromImage(ImageBlock.builder()
                .format(ImageFormat.JPEG)
                .source(ImageSource.fromBytes(SdkBytes.fromByteArray(image)))
                .build());
            ContentBlock txt = ContentBlock.fromText(instruction);
            Message msg = Message.builder().role(ConversationRole.USER).content(img, txt).build();

            ConverseResponse resp = BEDROCK.converse(r -> r
                .modelId(MODEL_ID)
                .messages(msg)
                .inferenceConfig(c -> c.maxTokens(300).temperature(0.3f)));

            String text = resp.output().message().content().get(0).text().trim();
            return ApiResponse.success(Map.of("text", text));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("AskHandler error: " + e);
            return ApiResponse.error(500, "Scene description failed");
        }
    }
}
