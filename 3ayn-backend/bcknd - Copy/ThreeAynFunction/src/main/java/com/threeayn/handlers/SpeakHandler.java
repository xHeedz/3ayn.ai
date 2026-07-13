package com.threeayn.handlers;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyRequestEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayProxyResponseEvent;
import com.threeayn.util.ApiResponse;
import com.threeayn.util.RequestParser;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.services.polly.PollyClient;
import software.amazon.awssdk.services.polly.model.Engine;
import software.amazon.awssdk.services.polly.model.OutputFormat;
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechRequest;
import software.amazon.awssdk.services.polly.model.SynthesizeSpeechResponse;

import java.util.Base64;
import java.util.Map;

/**
 * POST /speak { text: string, lang: "ar"|"en" }
 * -> { audio: base64-mp3 }
 * Arabic default: Hala (ar-AE) on the NEURAL engine. Zeina exists only on the
 * STANDARD engine — set POLLY_VOICE=Zeina and POLLY_ENGINE=standard to use her.
 */
public class SpeakHandler implements RequestHandler<APIGatewayProxyRequestEvent, APIGatewayProxyResponseEvent> {

    private static final PollyClient POLLY = PollyClient.create();
    private static final String AR_VOICE = System.getenv().getOrDefault("POLLY_VOICE", "Hala");
    private static final String AR_ENGINE = System.getenv().getOrDefault("POLLY_ENGINE", "neural");

    @Override
    public APIGatewayProxyResponseEvent handleRequest(APIGatewayProxyRequestEvent event, Context context) {
        try {
            RequestParser req = new RequestParser(event);
            String text = req.requiredField("text");
            String lang = req.lang();

            String voice = lang.equals("ar") ? AR_VOICE : "Joanna";
            Engine engine = lang.equals("ar") ? Engine.fromValue(AR_ENGINE) : Engine.NEURAL;

            SynthesizeSpeechRequest speech = SynthesizeSpeechRequest.builder()
                .text(text)
                .voiceId(voice)
                .engine(engine)
                .outputFormat(OutputFormat.MP3)
                .build();

            ResponseBytes<SynthesizeSpeechResponse> audio = POLLY.synthesizeSpeechAsBytes(speech);
            String b64 = Base64.getEncoder().encodeToString(audio.asByteArray());
            return ApiResponse.success(Map.of("audio", b64));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(400, e.getMessage());
        } catch (Exception e) {
            context.getLogger().log("SpeakHandler error: " + e);
            return ApiResponse.error(500, "Speech synthesis failed");
        }
    }
}
