package com.dbagent.sqltuning;

import com.fasterxml.jackson.annotation.JsonProperty;

public record BindCaptureRequest(
        @JsonProperty("db_id") String dbId,
        String account,
        String token,
        @JsonProperty("hash_value") String hashValue) {
}
