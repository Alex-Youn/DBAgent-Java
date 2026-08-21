package com.dbagent.aidba;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/** Calls a local Ollama process, same contract as the Python route (chat API with a generate-API fallback). */
@Service
public class OllamaChatService {

    private static final String SYSTEM_PROMPT_TEMPLATE =
            "당신은 오라클 데이터베이스(Oracle DB) 에러 해결을 도와주는 20년차 전문 DBA(AI 어시스턴트)입니다. "
                    + "사용자의 질문에 대해 아래 제공된 [참고 자료]를 바탕으로 명확하고 친절하게 답변해주세요. "
                    + "중요: 당신의 모든 답변은 무조건 한국어(Korean)로만 작성해야 합니다. 절대 영어로 답변하지 마세요. "
                    + "참고 자료에 없는 내용을 추측해서 지어내지(Hallucination) 마세요. "
                    + "해결 방법을 안내할 때는 가독성 좋게 글머리 기호를 사용해주세요.\n\n[참고 자료]\n%s";

    @Value("${aidba.ollama.url}")
    private String ollamaUrl;

    @Value("${aidba.ollama.model}")
    private String model;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public String ask(String prompt, String context) throws IOException, InterruptedException {
        String systemPrompt = String.format(SYSTEM_PROMPT_TEMPLATE, context);

        String answer = callChatApi(systemPrompt, prompt);
        if (answer == null) {
            // Older Ollama versions only expose /api/generate.
            answer = callGenerateApi(systemPrompt, prompt);
        }
        return (answer == null || answer.isBlank()) ? "답변을 생성하지 못했습니다." : answer;
    }

    private String callChatApi(String systemPrompt, String prompt) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        ArrayNode messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", systemPrompt);
        messages.addObject().put("role", "user").put("content", prompt);
        payload.put("stream", false);
        payload.putObject("options").put("temperature", 0);

        HttpResponse<String> resp = post(ollamaUrl + "/api/chat", payload);
        if (resp.statusCode() == 404) {
            return null;
        }
        if (resp.statusCode() >= 400) {
            throw new IOException("Ollama /api/chat returned HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body()).path("message").path("content").asText("");
    }

    private String callGenerateApi(String systemPrompt, String prompt) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("model", model);
        payload.put("prompt", systemPrompt + "\n\n사용자 질문: " + prompt);
        payload.put("stream", false);
        payload.putObject("options").put("temperature", 0);

        HttpResponse<String> resp = post(ollamaUrl + "/api/generate", payload);
        if (resp.statusCode() >= 400) {
            throw new IOException("Ollama /api/generate returned HTTP " + resp.statusCode());
        }
        return mapper.readTree(resp.body()).path("response").asText("");
    }

    private HttpResponse<String> post(String url, JsonNode payload) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(300))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
