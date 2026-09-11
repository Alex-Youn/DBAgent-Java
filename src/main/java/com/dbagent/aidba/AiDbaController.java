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

    /**
     * 하이브리드 검색(2026-09-11): ORA 코드 정확 일치/키워드(ErrorSearchService.retrieveDocs,
     * SQLite LIKE)로 뭔가 찾으면 그걸 그대로 컨텍스트로 붙여 빠르게 답변한다(짧은 문자열인 에러
     * 코드는 임베딩 유사도보다 정확 일치가 훨씬 신뢰도가 높다). 아무것도 못 찾으면(에러 코드 없이
     * 증상만 설명하는 자연어 질문 등) sqlrestapi의 OpenSearch 시맨틱 검색(error_dictionary
     * 인덱스)으로 폴백한다 - 둘 다 실패하면 LLM이 참고 자료 없이 일반 지식으로만 답한다.
     */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody ChatRequest req) {
        String prompt = req.prompt();
        if (prompt == null || prompt.isBlank()) {
            return ResponseEntity.ok(Map.of("success", false, "message", "질문이 없습니다."));
        }
        try {
            List<String> docs = errorSearchService.retrieveDocs(prompt);

            Map<String, Object> body = new LinkedHashMap<>();
            if (!docs.isEmpty()) {
                String context = String.join("\n\n", docs);
                String answer = ollamaChatService.ask(prompt, context);
                body.put("success", true);
                body.put("answer", answer);
                body.put("context_used", context);
            } else {
                Map<String, Object> result = ollamaChatService.askWithSemanticSearch(prompt);
                body.put("success", true);
                body.put("answer", result.get("answer"));
                body.put("context_used", formatSemanticContext(result));
            }
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            return ResponseEntity.ok(Map.of("success", false, "message", "서버 오류: " + e.getMessage()));
        }
    }

    /**
     * app.js는 context_used를 "참고자료 원문"으로 그대로 펼쳐 보여주므로(있으면 토글 노출, 없으면
     * 숨김), 시맨틱 검색 결과도 정확 일치 경로와 같은 문자열 형태로 맞춰준다 - 프론트엔드는 이
     * 컨텍스트가 정확 일치로 왔는지 검색으로 왔는지 몰라도 된다.
     */
    @SuppressWarnings("unchecked")
    private String formatSemanticContext(Map<String, Object> result) {
        Object refsObj = result.get("references");
        if (!(refsObj instanceof List)) {
            return null;
        }
        List<Map<String, String>> refs = (List<Map<String, String>>) refsObj;
        if (refs.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, String> ref : refs) {
            sb.append("[").append(ref.get("source")).append("]\n").append(ref.get("content")).append("\n\n");
        }
        return sb.toString();
    }
}
