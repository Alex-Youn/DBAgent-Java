package com.dbagent.sqltuning;

import com.dbagent.auth.AuthService;
import com.dbagent.oracle.DatabaseConfigService;
import com.dbagent.oracle.TargetDbConfig;
import com.dbagent.query.ExecutionPlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/sqltuning")
public class SqlTuningController {

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
            return ResponseEntity.ok(Map.of("success", false,
                    "message", "SQL 튜닝 모델 서버 통신 오류: " + e.getMessage()
                            + " (WSL의 serve/api_server.py가 실행 중인지 확인하세요)"));
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
        try {
            TargetDbConfig target = configService.resolve(req.dbId(), req.account());
            String plan = executionPlanService.explain(target, query);
            return analyzeWithPlan(query, plan);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "실행계획 조회/분석 오류: " + e.getMessage()));
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
        try {
            TargetDbConfig target = configService.resolve(req.dbId(), req.account());
            String plan = executionPlanService.explainActual(target, query, req.binds());
            return analyzeWithPlan(query, plan);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "실제 실행/분석 오류: " + e.getMessage()));
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
        try {
            TargetDbConfig target = configService.resolve(req.dbId(), req.account());
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
        try {
            TargetDbConfig target = configService.resolve(req.dbId(), req.account());
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

    private ResponseEntity<Map<String, Object>> analyzeWithPlan(String query, String plan) throws Exception {
        if (plan == null || plan.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "실행계획을 가져오지 못했습니다."));
        }
        String prompt = "[쿼리]\n" + query + "\n\n[실행계획]\n" + plan
                + "\n위 쿼리의 문제를 분석하고 튜닝 방안을 제시해줘.";
        String answer = sqlTuningService.analyze(prompt);
        return ResponseEntity.ok(Map.of("success", true, "answer", answer, "plan", plan));
    }
}
