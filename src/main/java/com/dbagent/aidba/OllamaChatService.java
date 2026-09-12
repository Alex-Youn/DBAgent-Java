package com.dbagent.aidba;

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

/**
 * Calls the SQL Tune Advisor GPU 서버의 sqlrestapi - Ollama(11434)에 더 이상 직접 붙지 않는다.
 * GPU 서버 방화벽이 REST API 포트(9300) 하나만 열어주는 구성으로 확정되면서(2026-09-11), Ollama
 * 포트는 그 서버 밖에서 도달 불가능해졌다 - sqlrestapi(RAGController)가 그 앞단의 프록시 역할을
 * 대신한다.
 *
 * 시스템 프롬프트는 이제 이 서비스가 문자열로 들고 있지 않는다 - sqlrestapi 쪽에 promptId별 파일
 * (prompts/chatbot.md)로 옮겨서 SQL Tune Advisor(promptId=tuning)와 구조를 통일했다(2026-09-11).
 * 어떤 LLM을 쓰는지도 마찬가지로 sqlrestapi 쪽(sqltune.llm.model) 설정을 따른다.
 *
 * 하이브리드 검색: ErrorSearchService가 ORA 코드 정확 일치/키워드로 컨텍스트를 찾으면 ask()(검색
 * 없는 /api/chat)로 바로 답변을 받고, 못 찾으면 askWithSemanticSearch()로 sqlrestapi의
 * OpenSearch 시맨틱 검색(error_dictionary 인덱스)에 맡긴다 - AiDbaController가 이 둘을 고른다.
 *
 * aidba.ollama.url 프로퍼티 이름은 과거 호환을 위해 그대로 유지했지만, 이제 값은 Ollama가 아니라
 * sqlrestapi의 베이스 URL을 가리켜야 한다(예: http://<GPU서버>:9300).
 */
@Service
public class OllamaChatService {

    private static final String PROMPT_ID = "chatbot";
    private static final String ERROR_INDEX = "error_dictionary";

    @Value("${aidba.ollama.url}")
    private String chatApiUrl;

    // 생성 응답을 기다리는 한도. 하드코딩 300초였으나, 모델 크기와 실행 위치(로컬 vs 원격 GPU 서버)에
    // 따라 적정값이 크게 달라져 설정으로 뺐다 - 재빌드 없이 조정할 수 있어야 한다. 기존 동작을 그대로
    // 유지하려고 기본값도 300000ms 로 뒀다(DBAgent-Java-AIX 쪽은 30000ms 로 별도 운영 중).
    @Value("${aidba.ollama.timeout-ms:300000}")
    private int timeoutMs;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 검색 없이 컨텍스트(caller가 이미 조립)를 그대로 붙여 답변만 받는다 - ORA 코드 정확 일치 경로. */
    public String ask(String prompt, String context) throws IOException, InterruptedException {
        String finalPrompt = "[참고 자료]\n" + context + "\n\n[사용자 질문]\n" + prompt
                + "\n\n반드시 한국어로 답하세요.";
        return askWithPrompt(PROMPT_ID, finalPrompt);
    }

    /**
     * RAG 검색 없이 완성된 프롬프트를 그대로 sqlrestapi(promptId별 시스템 프롬프트)에 던진다. AI Current
     * SQL 분석(promptId=current-sql, 매뉴통합.md 2-1)처럼 눈앞의 실측치를 해석하는 작업 - 사내 사례를
     * 찾는 게 아니므로 인덱스 검색이 필요 없는 화면들이 공용으로 쓴다.
     */
    public String askWithPrompt(String promptId, String finalPrompt) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("promptId", promptId);
        payload.put("prompt", finalPrompt);

        HttpResponse<String> resp = post(chatApiUrl + "/api/chat", payload);
        if (resp.statusCode() >= 400) {
            throw new IOException("sqlrestapi /api/chat returned HTTP " + resp.statusCode());
        }

        String answer = mapper.readTree(resp.body()).path("answer").asText("");
        return answer.isBlank() ? "답변을 생성하지 못했습니다." : answer;
    }

    /**
     * ORA 코드 정확 일치/키워드로 아무것도 못 찾았을 때의 폴백 - sqlrestapi가 bge-m3로 질문을
     * 임베딩해 error_dictionary 인덱스에서 의미상 가까운 사례를 찾고, 그걸 컨텍스트로 붙여 직접
     * 답변까지 생성해 돌려준다(SQL Tune Advisor의 /api/query와 동일한 메커니즘, 인덱스/프롬프트만
     * 다름). 반환 맵: {"answer": String, "references": List<Map<source,content>>}.
     */
    public Map<String, Object> askWithSemanticSearch(String userMessage) throws IOException, InterruptedException {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("promptId", PROMPT_ID);
        payload.put("index", ERROR_INDEX);
        payload.put("query", userMessage);
        payload.put("n_results", 3);

        HttpResponse<String> resp = post(chatApiUrl + "/api/query", payload);
        if (resp.statusCode() >= 400) {
            throw new IOException("sqlrestapi /api/query returned HTTP " + resp.statusCode());
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
     * 좌측 프레임 상단 모델명 표시용(매뉴통합.md 3절). sqlrestapi의 isConnected()가 타임아웃 없이
     * OpenSearch 응답을 무한정 기다릴 수 있어(sqlrestapi 개선 후보, 이 저장소 밖) 여기서라도 짧은
     * 타임아웃(5초)을 걸어 화면이 멈추지 않게 한다 - 실패하면 호출자가 표시를 생략한다.
     */
    public Map<String, Object> health() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(chatApiUrl + "/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() >= 400) {
            throw new IOException("sqlrestapi /health returned HTTP " + resp.statusCode());
        }
        JsonNode root = mapper.readTree(resp.body());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llm_model", root.path("llm_model").asText(""));
        result.put("vector_db", root.path("vector_db").asText(""));
        return result;
    }

    private HttpResponse<String> post(String url, ObjectNode payload) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(timeoutMs))
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
}
