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
 * Calls the QLoRA-finetuned Qwen2.5-Coder-7B inference server (FastAPI, New_sLLM/serve/api_server.py).
 * Same contract as OllamaChatService, but for our own SQL 정합성/튜닝 sLLM instead of Ollama's generic model.
 *
 * sqltuning.api.url doesn't have to point at localhost - this service only does plain HTTP(JSON), so it
 * works the same whether the FastAPI server runs on this machine, a WSL instance, or a GPU box on the LAN
 * (see OllamaChatService's aidba.ollama.url for the same pattern). It does NOT work if this host has no
 * network path to wherever that server actually runs - verify reachability before relying on it.
 */
@Service
public class SqlTuningService {

    // 기본값을 반드시 둘 것. 이 속성은 application.properties 에만 있는데 그 파일은 환경마다 값이 달라
    // 커밋 대상에서 빠져 있다(2026-09-04 결정). 기본값이 없으면 속성이 없는 환경에서 플레이스홀더 해석에
    // 실패해 SQL 튜닝 기능만이 아니라 **앱 전체가 기동하지 못한다**.
    @Value("${sqltuning.api.url:http://localhost:8010}")
    private String apiUrl;

    // GPU 서버 성능과 프롬프트 길이에 따라 응답 시간이 크게 달라져 설정으로 뺐다(하드코딩 180초였음).
    @Value("${sqltuning.api.timeout-ms:180000}")
    private int timeoutMs;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 연결 실패 안내에 대상 주소를 함께 보여주려고 컨트롤러가 읽는다. */
    public String apiUrl() {
        return apiUrl;
    }

    public String analyze(String prompt) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("prompt", prompt);

        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/generate"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
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
