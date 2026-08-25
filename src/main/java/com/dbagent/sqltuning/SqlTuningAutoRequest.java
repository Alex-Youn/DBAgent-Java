package com.dbagent.sqltuning;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

public record SqlTuningAutoRequest(
        @JsonProperty("db_id") String dbId,
        String account,
        String token,
        String query,
        // 바인드 변수명(콜론 제외, 예: "1", "SID") -> 값. 실제 실행(analyze_from_query_actual)에서만 사용.
        Map<String, String> binds) {
}
