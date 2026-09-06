package com.dbagent.oracle;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

public record CreateDbInstanceRequest(
        String token,
        @JsonProperty("group_name") String groupName,
        String id,
        String name,
        // "oracle" (default) / "mysql" / "mariadb" / "postgres" / "mssql" / "cubrid" - see TargetDbConfig.
        @JsonProperty("db_type") String dbType,
        String host,
        int port,
        String sid,
        String user,
        String password,
        @JsonProperty("pool_min_idle") Integer poolMinIdle,
        @JsonProperty("pool_max_size") Integer poolMaxSize,
        // Extra accounts: each map has "user" and "password" keys - see databases.json's "accounts".
        List<Map<String, String>> accounts,
        // 5 ascending ints, or null/empty to not override the global default - see databases.json's
        // "session_thresholds" and DatabaseConfigService.applySessionThresholds.
        @JsonProperty("session_thresholds") List<Integer> sessionThresholds) {
}
