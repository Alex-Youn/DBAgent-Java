package com.dbagent.oracle;

public record TargetDbConfig(
        String id,
        String name,
        String user,
        String password,
        String host,
        int port,
        String sid,
        // null = not set in databases.json, caller should fall back to the application.properties default.
        Integer poolMinIdle,
        Integer poolMaxSize) {
}
