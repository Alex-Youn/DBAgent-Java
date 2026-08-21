package com.dbagent.query;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SqlQueryRequest(
        @JsonProperty("db_id") String dbId,
        String sql,
        @JsonProperty("max_rows") Integer maxRows,
        String account,
        String token) {
}
