package com.dbagent.aidba;

import java.util.Map;

/**
 * AI Current SQL 분석("성능분석" 버튼, 매뉴통합.md 2-1) 요청 - 1차 성능점검 결과를 그대로 실어 보낸다.
 * previousContext/followUpQuestion은 후속질문(2026-09-15) 용 - LLM은 이전 호출을 기억하지 못하므로
 * 프론트에서 누적한 이전 분석/문답 내역을 매번 그대로 다시 실어 보내는 stateless 방식이다.
 */
public record CurrentSqlAnalyzeRequest(String query, Map<String, String> binds, String plan,
                                        String previousContext, String followUpQuestion) {
}
