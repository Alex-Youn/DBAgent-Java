package com.dbagent.monitor;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 대시보드 추이 차트(CpuDbTimeLineChart, LockTrendChart)용 시계열 저장소.
 * V$ 뷰는 현재 시점 스냅샷만 주므로, 주기적으로 샘플링한 값을 이 테이블에 쌓아 추이를 그린다.
 * AuthService와 동일하게 앱 자체 SQLite(users.db)를 쓴다 - 여러 인스턴스를 한 곳에 모아 저장할 수 있고
 * 모니터링 대상 Oracle에 쓰기 권한이 필요 없다.
 */
@Service
public class InstanceMetricHistoryService {

    private static final Logger log = LoggerFactory.getLogger(InstanceMetricHistoryService.class);

    private final JdbcTemplate jdbc;

    // AuthService.sessionTtlDays와 같은 이유로 설정값화 - 운영 중 보존 기간을 재빌드 없이 바꿀 수 있어야 함
    // (2026-09-15 agent 백로그 논의에서 정한 원칙을 이 테이블에도 그대로 적용).
    @Value("${dbagent.monitor.metric-retention-days:30}")
    private int retentionDays;

    public InstanceMetricHistoryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    void init() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS instance_metric_history (" +
                "instance_name TEXT NOT NULL, " +
                "metric_name TEXT NOT NULL, " +
                "sampled_at INTEGER NOT NULL, " +
                "value REAL, " +
                "PRIMARY KEY (instance_name, metric_name, sampled_at))");
        jdbc.execute("CREATE INDEX IF NOT EXISTS idx_instance_metric_history_lookup " +
                "ON instance_metric_history (instance_name, metric_name, sampled_at)");
    }

    /** 같은 사이클에서 샘플러가 재시도해도 죽지 않도록 REPLACE - PK 충돌은 정상 경로다. */
    public void record(String instanceName, String metricName, long sampledAtMillis, double value) {
        jdbc.update("INSERT OR REPLACE INTO instance_metric_history " +
                        "(instance_name, metric_name, sampled_at, value) VALUES (?, ?, ?, ?)",
                instanceName, metricName, sampledAtMillis, value);
    }

    public List<Map<String, Object>> query(String instanceName, String metricName, long fromMillis, long toMillis) {
        return jdbc.query(
                "SELECT sampled_at, value FROM instance_metric_history " +
                        "WHERE instance_name = ? AND metric_name = ? AND sampled_at BETWEEN ? AND ? " +
                        "ORDER BY sampled_at ASC",
                (rs, rowNum) -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("sampledAt", rs.getLong("sampled_at"));
                    row.put("value", rs.getDouble("value"));
                    return row;
                },
                instanceName, metricName, fromMillis, toMillis);
    }

    @Scheduled(cron = "0 45 3 * * *")
    void cleanupOldSamples() {
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
        int deleted = jdbc.update("DELETE FROM instance_metric_history WHERE sampled_at < ?", cutoff);
        if (deleted > 0) {
            log.info("Cleaned up {} instance metric sample(s) older than {} day(s)", deleted, retentionDays);
        }
    }
}
