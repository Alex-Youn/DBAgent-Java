package com.dbagent.oracle;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** password null/blank means "keep the currently stored password" - see DatabaseConfigService.updateInstance. */
public record UpdateDbInstanceRequest(
        String token,
        String name,
        // "oracle" (default) / "mysql" / "mariadb" / "postgres" - see TargetDbConfig.
        @JsonProperty("db_type") String dbType,
        String host,
        int port,
        String sid,
        String user,
        String password,
        @JsonProperty("pool_min_idle") Integer poolMinIdle,
        @JsonProperty("pool_max_size") Integer poolMaxSize,
        // Extra accounts: each map has "user" and "password" keys; a blank password keeps that
        // account's currently stored password - see DatabaseConfigService.applyAccounts.
        List<Map<String, String>> accounts,
        // 5 ascending ints, or null/empty to not override the global default - see databases.json's
        // "session_thresholds" and DatabaseConfigService.applySessionThresholds.
        @JsonProperty("session_thresholds") List<Integer> sessionThresholds) {
}
