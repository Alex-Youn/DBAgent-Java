package com.dbagent.sqltuning;

import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Calls the local QLoRA-finetuned Qwen2.5-Coder-7B inference server (FastAPI, WSL,
 * New_sLLM/serve/api_server.py). Same contract as OllamaChatService, but for our own
 * SQL 정합성/튜닝 sLLM instead of Ollama's generic model.
 */
@Service
public class SqlTuningService {

    @Value("${sqltuning.api.url}")
    private String apiUrl;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public String analyze(String prompt) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("prompt", prompt);

        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IOException("SQL 튜닝 모델 서버가 HTTP " + resp.statusCode() + "를 반환했습니다.");
        }
        String answer = mapper.readTree(resp.body()).path("response").asText("");
        return answer.isBlank() ? "답변을 생성하지 못했습니다." : answer;
    }
}
