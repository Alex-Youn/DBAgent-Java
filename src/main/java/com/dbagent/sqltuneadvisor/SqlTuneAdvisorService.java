package com.dbagent.sqltuneadvisor;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Calls the SQL Tune Advisor RAG server (sqltune-rag-java / RAGController, GPU 서버에서 Ollama(bge-m3)로
 * 질의를 임베딩하고 OpenSearch에서 사내 튜닝 사례를 검색한 뒤 qwen3-coder:30b로 분석까지 수행한다).
 * SqlTuningService(자체 파인튜닝 sLLM, 별개 서버)와는 목적이 다른 연동이라 패키지/설정 키를 분리했다.
 *
 * sqltuneadvisor.api.url이 이 호스트에서 실제로 도달 가능한지는 배포 환경마다 확인이 필요하다
 * (sqltuning.api.url과 동일한 주의사항 - SqlTuningService 클래스 주석 참고).
 */
@Service
public class SqlTuneAdvisorService {

    @Value("${sqltuneadvisor.api.url:http://localhost:9300}")
    private String apiUrl;

    @Value("${sqltuneadvisor.api.timeout-ms:180000}")
    private int timeoutMs;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 연결 실패 안내에 대상 주소를 함께 보여주려고 컨트롤러가 읽는다. */
    public String apiUrl() {
        return apiUrl;
    }

    public Map<String, Object> query(String query) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("query", query);
        payload.put("n_results", 3);

        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/api/query"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IOException("SQL Tune Advisor 서버가 HTTP " + resp.statusCode() + "를 반환했습니다.");
        }

        JsonNode root = mapper.readTree(resp.body());
        String answer = root.path("answer").asText("");

        List<Map<String, String>> references = new ArrayList<>();
        for (JsonNode ref : root.path("references")) {
            Map<String, String> r = new LinkedHashMap<>();
            r.put("source", ref.path("source").asText(""));
            r.put("content", ref.path("content").asText(""));
            references.add(r);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer.isBlank() ? "답변을 생성하지 못했습니다." : answer);
        result.put("references", references);
        return result;
    }

    /**
     * sqlrestapi의 /api/query/stream(NDJSON)을 그대로 중계한다. 한 줄이 곧 한 JSON 객체 -
     * {"chunk": "...", "done": false} 가 토큰마다 오다가 마지막에 {"references": [...]} 한 줄로
     * 끝난다(RAGController.queryTuningStream 참고). 이 메서드는 그 줄들을 파싱하지 않고 그대로
     * onLine으로 넘겨주기만 한다 - 형식을 아는 건 호출자(컨트롤러 -> 브라우저)뿐이면 된다.
     *
     * HttpClient.send()는 동기 호출이지만 BodyHandlers.ofLines()가 만드는 Stream<String>은 응답
     * 본문을 즉시 전부 읽어들이는 게 아니라 BufferedReader 위에서 지연 평가되므로, forEach가 도는
     * 동안 실제로 한 줄씩 도착하는 대로 처리된다 - 토큰 단위 스트리밍이 그대로 살아있다.
     */
    public void queryStream(String query, Consumer<String> onLine) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("query", query);
        payload.put("n_results", 3);
        payload.put("stream", true);

        HttpRequest req = HttpRequest.newBuilder(URI.create(apiUrl + "/api/query/stream"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<Stream<String>> resp = http.send(req, HttpResponse.BodyHandlers.ofLines());
        if (resp.statusCode() >= 400) {
            throw new IOException("SQL Tune Advisor 서버가 HTTP " + resp.statusCode() + "를 반환했습니다.");
        }
        try (Stream<String> lines = resp.body()) {
            lines.forEach(onLine);
        }
    }
}
