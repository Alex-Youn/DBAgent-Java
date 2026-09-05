package com.dbagent.sqltuning;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.query.ExecutionPlanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.util.Map;

/**
 * SQL 튜닝 sLLM(FastAPI) 연동. 2026-08-29부터 analyze* 엔드포인트는 화면 인터페이스만 유지한 채 항상
 * "연동 필요" 메시지를 반환하는 스텁이었는데(연결 시도 시 10초 connect timeout 뒤 커넥션 거부 에러가
 * 그대로 노출되던 문제를 그렇게 막았다), 2026-09-05에 AIX 이관본 쪽 연동을 역포팅하며 실제 호출로
 * 되돌렸다. 그때 막으려던 문제는 modelErrorMessage()가 대신 처리한다 - 연결 실패는 예외 문자열 대신
 * 다음 행동을 알려주는 안내문으로 바꿔 내보낸다.
 *
 * sqltuning.api.url이 이 호스트에서 실제로 도달 가능한지는 배포 환경마다 확인이 필요하다. 도달 불가면
 * analyze*는 안내문을 돌려주고, quick_check/bind_capture는 모델 없이 Oracle 조회만으로 그대로 동작한다.
 */
@RestController
@RequestMapping("/api/sqltuning")
public class SqlTuningController {

    private static final Logger log = LoggerFactory.getLogger(SqlTuningController.class);

    private final SqlTuningService sqlTuningService;
    private final ExecutionPlanService executionPlanService;
    private final DatabaseConfigService configService;
    private final AuthService authService;

    public SqlTuningController(SqlTuningService sqlTuningService, ExecutionPlanService executionPlanService,
                                DatabaseConfigService configService, AuthService authService) {
        this.sqlTuningService = sqlTuningService;
        this.executionPlanService = executionPlanService;
        this.configService = configService;
        this.authService = authService;
    }

    @PostMapping("/analyze")
    public ResponseEntity<Map<String, Object>> analyze(@RequestBody SqlTuningRequest req) {
        String prompt = req.prompt();
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "쿼리/실행계획을 입력해주세요."));
        }
        try {
            String answer = sqlTuningService.analyze(prompt);
            return ResponseEntity.ok(Map.of("success", true, "answer", answer));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", modelErrorMessage("SQL 튜닝 분석", e)));
        }
    }

    /** 쿼리만 입력받아 EXPLAIN PLAN으로 실행계획을 직접 조회한 뒤 분석까지 이어서 수행 (관리자 전용, 쿼리 미실행). */
    @PostMapping("/analyze_from_query")
    public ResponseEntity<Map<String, Object>> analyzeFromQuery(@RequestBody SqlTuningAutoRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Map.of("success", false, "message", "실행계획 자동 조회는 관리자만 사용할 수 있습니다."));
        }
        String query = req.query();
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "쿼리를 입력해주세요."));
        }
        TargetDbConfig target = configService.resolve(req.dbId(), req.account());
        if (target == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "등록되지 않은 DB입니다."));
        }
        try {
            return analyzeWithPlan(query, executionPlanService.explain(target, query));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", modelErrorMessage("실행계획 조회/분석", e)));
        }
    }

    /**
     * 쿼리를 실제로 실행해 DBMS_XPLAN.DISPLAY_CURSOR(ALLSTATS LAST)의 실측 통계(실제 로우 수/시간/버퍼)로
     * 분석 (관리자 전용, SELECT/WITH만 허용 - 데이터 반환 없이 draining만 하지만 실제 실행은 됨).
     */
    @PostMapping("/analyze_from_query_actual")
    public ResponseEntity<Map<String, Object>> analyzeFromQueryActual(@RequestBody SqlTuningAutoRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Map.of("success", false, "message", "실제 실행 통계 분석은 관리자만 사용할 수 있습니다."));
        }
        String query = req.query();
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "쿼리를 입력해주세요."));
        }
        TargetDbConfig target = configService.resolve(req.dbId(), req.account());
        if (target == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "등록되지 않은 DB입니다."));
        }
        try {
            return analyzeWithPlan(query, executionPlanService.explainActual(target, query, req.binds()));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", modelErrorMessage("실제 실행/분석", e)));
        }
    }

    /**
     * "실제 실행 통계로 분석"과 동일하게 쿼리를 실제 실행해 DISPLAY_CURSOR(ALLSTATS LAST) 실측치를
     * 얻지만, sLLM(FastAPI) 호출 없이 그 결과만 그대로 반환한다 (관리자 전용, SELECT/WITH만 허용).
     * DBA가 AI 분석 전에 직접 눈으로 먼저 훑어보는 1차 성능점검 용도 - 응답이 훨씬 빠름.
     */
    @PostMapping("/quick_check")
    public ResponseEntity<Map<String, Object>> quickCheck(@RequestBody SqlTuningAutoRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Map.of("success", false, "message", "성능점검은 관리자만 사용할 수 있습니다."));
        }
        String query = req.query();
        if (query == null || query.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "쿼리를 입력해주세요."));
        }
        TargetDbConfig target = configService.resolve(req.dbId(), req.account());
        if (target == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "등록되지 않은 DB입니다."));
        }
        try {
            String plan = executionPlanService.explainActual(target, query, req.binds());
            if (plan == null || plan.isBlank()) {
                return ResponseEntity.ok(Map.of("success", false, "message", "실행계획을 가져오지 못했습니다."));
            }
            return ResponseEntity.ok(Map.of("success", true, "plan", plan));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "성능점검 오류: " + e.getMessage()));
        }
    }

    /** V$SQL_BIND_CAPTURE에서 HASH_VALUE로 과거 실행된 바인드 값을 조회 (관리자 전용, 일괄 입력용). */
    @PostMapping("/bind_capture")
    public ResponseEntity<Map<String, Object>> bindCapture(@RequestBody BindCaptureRequest req) {
        if (!authService.isAdmin(req.token())) {
            return ResponseEntity.ok(Map.of("success", false, "message", "관리자만 사용할 수 있습니다."));
        }
        if (req.hashValue() == null || req.hashValue().isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "HASH_VALUE를 입력해주세요."));
        }
        TargetDbConfig target = configService.resolve(req.dbId(), req.account());
        if (target == null) {
            return ResponseEntity.ok(Map.of("success", false, "message", "등록되지 않은 DB입니다."));
        }
        try {
            Map<String, String> binds = executionPlanService.fetchBindCapture(target, req.hashValue());
            if (binds.isEmpty()) {
                return ResponseEntity.ok(Map.of("success", false,
                        "message", "V$SQL_BIND_CAPTURE에서 해당 HASH_VALUE의 바인드 값을 찾지 못했습니다 (커서가 이미 캐시에서 밀려났을 수 있습니다)."));
            }
            return ResponseEntity.ok(Map.of("success", true, "binds", binds));
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "조회 오류: " + e.getMessage()));
        }
    }

    /**
     * 여기서 만든 문자열은 app.js가 그대로 빨간 글씨로 화면에 출력하므로(success:false -> data.message)
     * 원인 예외 문자열을 그대로 흘리면 안 된다. 연결 자체가 안 되는 상황(sLLM 서버가 안 떠 있거나 이
     * 호스트에서 도달 불가)은 운영 중 흔한 경우라, "java.net.ConnectException: Connection refused" 같은
     * 예외 대신 다음 행동을 알려주는 안내문으로 바꿔준다. 원인은 서버 로그로만 남긴다.
     */
    private String modelErrorMessage(String what, Exception e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpTimeoutException) {
                log.warn("SQL 튜닝 모델 서버 연결 실패 (url={}): {}", sqlTuningService.apiUrl(), e.toString());
                return "SQL 튜닝 sLLM 서버(" + sqlTuningService.apiUrl() + ")에 연결할 수 없습니다. "
                        + "서버가 켜져 있는지, 이 호스트에서 도달 가능한 주소인지 확인해주세요. "
                        + "지금은 \"1차 성능점검\" 버튼으로 실행계획/실측 통계만 확인할 수 있습니다.";
            }
        }
        log.warn("SQL 튜닝 {} 실패", what, e);
        return what + " 오류: " + e.getMessage();
    }

    private ResponseEntity<Map<String, Object>> analyzeWithPlan(String query, String plan)
            throws java.io.IOException, InterruptedException {
        if (plan == null || plan.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "실행계획을 가져오지 못했습니다."));
        }
        String prompt = "[쿼리]\n" + query + "\n\n[실행계획]\n" + plan
                + "\n위 쿼리의 문제를 분석하고 튜닝 방안을 제시해줘.";
        String answer = sqlTuningService.analyze(prompt);
        return ResponseEntity.ok(Map.of("success", true, "answer", answer, "plan", plan));
    }
}
