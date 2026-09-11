package com.dbagent.sqltuneadvisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SQL Tune Advisor 화면(AI DBA 메뉴 아래) 전용 엔드포인트. Oracle 접속/실행 권한과 무관하게 사용자가
 * 입력한 텍스트를 그대로 GPU 서버의 RAG API로 전달하므로, SqlTuningController의 자동 실행계획 조회
 * 기능들과 달리 관리자 제한을 두지 않는다(AiDbaController의 /chat과 동일한 성격).
 */
@RestController
@RequestMapping("/api/sqltuneadvisor")
public class SqlTuneAdvisorController {

    private static final Logger log = LoggerFactory.getLogger(SqlTuneAdvisorController.class);

    private final SqlTuneAdvisorService sqlTuneAdvisorService;
    private final ObjectMapper mapper = new ObjectMapper();
    // sqlrestapi로의 스트리밍 호출은 HttpClient.send()가 응답을 다 받을 때까지 블로킹되므로,
    // 요청을 받은 Tomcat 워커 스레드에서 그대로 돌리면 그 스레드가 통째로 몇 분간 묶인다.
    // ResponseBodyEmitter 응답은 별도 스레드에서 채워야 컨트롤러 메서드가 즉시 리턴하고
    // 스트리밍이 백그라운드에서 진행된다.
    private final ExecutorService streamExecutor = Executors.newCachedThreadPool();

    public SqlTuneAdvisorController(SqlTuneAdvisorService sqlTuneAdvisorService) {
        this.sqlTuneAdvisorService = sqlTuneAdvisorService;
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> query(@RequestBody SqlTuneAdvisorRequest req) {
        String query = req.query();
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "질의할 SQL/질문을 입력해주세요."));
        }
        try {
            Map<String, Object> result = sqlTuneAdvisorService.query(query);
            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "answer", result.get("answer"),
                    "references", result.get("references")));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", modelErrorMessage(e)));
        }
    }

    /**
     * /query와 같은 질의를 sqlrestapi의 /api/query/stream(NDJSON)으로 그대로 중계한다 - 토큰이
     * 도착하는 대로 {"chunk": "..."} 줄을 브라우저에 흘려보내 답변이 한 글자씩 생성되는 것처럼
     * 보이게 한다. 마지막 줄은 {"references": [...]}. 연결 실패 등으로 스트리밍을 아예 시작 못하면
     * {"error": "..."} 한 줄만 보내고 끝낸다 - /query의 success:false와 같은 안내문을 쓴다.
     */
    @PostMapping(value = "/query/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    public ResponseBodyEmitter queryStream(@RequestBody SqlTuneAdvisorRequest req) {
        ResponseBodyEmitter emitter = new ResponseBodyEmitter(0L);
        String query = req.query();
        if (query == null || query.isBlank()) {
            sendErrorLine(emitter, "질의할 SQL/질문을 입력해주세요.");
            emitter.complete();
            return emitter;
        }
        streamExecutor.execute(() -> {
            try {
                sqlTuneAdvisorService.queryStream(query, line -> {
                    try {
                        emitter.send(line + "\n", MediaType.APPLICATION_NDJSON);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                sendErrorLine(emitter, modelErrorMessage(e));
                emitter.complete();
            }
        });
        return emitter;
    }

    private void sendErrorLine(ResponseBodyEmitter emitter, String message) {
        try {
            emitter.send(mapper.writeValueAsString(Map.of("error", message)) + "\n", MediaType.APPLICATION_NDJSON);
        } catch (IOException ignored) {
            // 클라이언트가 이미 연결을 끊었을 뿐이라 조용히 무시 - complete()는 호출자가 마저 부른다.
        }
    }

    /** SqlTuningController.modelErrorMessage()와 동일한 이유(연결 실패 예외 문자열을 그대로 노출하지 않음). */
    private String modelErrorMessage(Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpTimeoutException) {
                log.warn("SQL Tune Advisor 서버 연결 실패 (url={}): {}", sqlTuneAdvisorService.apiUrl(), e.toString());
                return "SQL Tune Advisor 서버(" + sqlTuneAdvisorService.apiUrl() + ")에 연결할 수 없습니다. "
                        + "서버가 켜져 있는지, 이 호스트에서 도달 가능한 주소인지 확인해주세요.";
            }
        }
        log.warn("SQL Tune Advisor 조회 실패", e);
        return "조회 오류: " + e.getMessage();
    }
}
