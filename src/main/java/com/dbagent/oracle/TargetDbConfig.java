package com.dbagent.oracle;

public record TargetDbConfig(
        String id,
        String name,
        // "oracle" (default, legacy instances have no db_type in databases.json) / "mysql" / "mariadb" / "postgres" / "mssql" / "cubrid".
        String dbType,
        String user,
        String password,
        String host,
        int port,
        String sid,
        // null/blank = legacy behavior (host blank -> sid treated as TNS alias, host set -> host:port:sid).
        // "sid" = explicit host:port:sid; "service" = host:port/service_name (sid field holds the service
        // name); "descriptor" = sid field holds a full connect descriptor/TNS string, host/port ignored.
        String connectMode,
        // null = not set in databases.json, caller should fall back to the application.properties default.
        Integer poolMinIdle,
        Integer poolMaxSize) {
}
