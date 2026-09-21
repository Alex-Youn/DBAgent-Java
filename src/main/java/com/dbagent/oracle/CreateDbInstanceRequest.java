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
        // null/blank = host:port:sid (default). "sid"/"service"/"descriptor" - see
        // OracleConnectionPoolManager.buildDsn(). Oracle-only; ignored for other db_type values.
        @JsonProperty("connect_mode") String connectMode,
        // null/blank = no check - compared against v$instance.instance_name on first connection.
        // Oracle-only; see TargetDbConfig, PoolTestController.
        @JsonProperty("expected_instance_name") String expectedInstanceName,
        @JsonProperty("pool_min_idle") Integer poolMinIdle,
        @JsonProperty("pool_max_size") Integer poolMaxSize,
        // Extra accounts: each map has "user" and "password" keys - see db_instances' "accounts" column.
        List<Map<String, String>> accounts,
        // 5 ascending ints, or null/empty to not override the global default - see db_instances'
        // "session_thresholds" column and DatabaseConfigService.buildSessionThresholdsJson.
        @JsonProperty("session_thresholds") List<Integer> sessionThresholds) {
}
