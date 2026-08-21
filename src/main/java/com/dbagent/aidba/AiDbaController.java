package com.dbagent.aidba;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/aidba")
public class AiDbaController {

    private final ErrorSearchService errorSearchService;
    private final OllamaChatService ollamaChatService;

    public AiDbaController(ErrorSearchService errorSearchService, OllamaChatService ollamaChatService) {
        this.errorSearchService = errorSearchService;
        this.ollamaChatService = ollamaChatService;
    }

    @GetMapping("/error_search")
    public ResponseEntity<Map<String, Object>> errorSearch(
            @RequestParam(name = "code", required = false, defaultValue = "") String code) {
        if (code.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No error code provided"));
        }
        return ResponseEntity.ok(errorSearchService.getErrorSolution(code));
    }

    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest req) {
        String prompt = req.prompt();
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "질문이 없습니다."));
        }
        try {
            List<String> docs = errorSearchService.retrieveDocs(prompt);
            String context = docs.isEmpty()
                    ? "관련 에러 정보를 찾을 수 없습니다. 일반적인 오라클 DBA 지식을 바탕으로 답변합니다."
                    : String.join("\n\n", docs);

            String answer = ollamaChatService.ask(prompt, context);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", true);
            body.put("answer", answer);
            body.put("context_used", context);
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "서버 오류: " + e.getMessage()));
        }
    }
}
