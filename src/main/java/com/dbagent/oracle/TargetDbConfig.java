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
        // null = not set in databases.json, caller should fall back to the application.properties default.
        Integer poolMinIdle,
        Integer poolMaxSize) {
}
