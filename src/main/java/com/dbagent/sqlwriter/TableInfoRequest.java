package com.dbagent.sqlwriter;

import com.fasterxml.jackson.annotation.JsonProperty;

/** AI SQL 작성기 - 테이블 구조 조회("조회" 버튼) 요청. */
public record TableInfoRequest(
        @JsonProperty("db_id") String dbId,
        String account,
        String token,
        @JsonProperty("table_name") String tableName) {
}
