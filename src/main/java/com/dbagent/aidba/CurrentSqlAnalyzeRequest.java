package com.dbagent.aidba;

import java.util.Map;

/** AI Current SQL 분석("성능분석" 버튼, 매뉴통합.md 2-1) 요청 - 1차 성능점검 결과를 그대로 실어 보낸다. */
public record CurrentSqlAnalyzeRequest(String query, Map<String, String> binds, String plan) {
}
