package com.dbagent.config;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * users.db(계정/세션, spring.datasource.*)와 metrics.db(instance_metric_history,
 * dbagent.metrics.datasource.*)를 별도 파일 + 별도 HikariCP 풀로 분리한다(오케스트레이터 요청,
 * 2026-09-18) - 샘플러(InstanceMetricSamplerService)가 60초마다 계속 쓰는 유일한 고빈도 쓰기
 * 주체라, users.db에서 떼어내는 것만으로 세션 검증/로그인 경로와의 락 경합이 사실상 사라진다.
 *
 * DataSource 타입 빈을 하나라도 직접 정의하면 Spring Boot의 DataSourceAutoConfiguration/
 * JdbcTemplateAutoConfiguration이 @ConditionalOnMissingBean 때문에 기존 spring.datasource.*
 * 기반 기본 DataSource/JdbcTemplate 생성을 통째로 건너뛴다 - 그래서 users.db용 기본 빈도 여기서
 * 같이 명시적으로 만들어 @Primary로 지정한다(Boot가 내부적으로 쓰는 것과 동일한
 * DataSourceProperties + spring.datasource.hikari.* 바인딩 방식이라 동작은 기존과 동일).
 */
@Configuration
public class DataSourceConfig {

    @Primary
    @Bean(name = "usersDataSourceProperties")
    @ConfigurationProperties("spring.datasource")
    public DataSourceProperties usersDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Primary
    @Bean(name = "dataSource")
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource dataSource(@Qualifier("usersDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Primary
    @Bean(name = "jdbcTemplate")
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "metricsDataSourceProperties")
    @ConfigurationProperties("dbagent.metrics.datasource")
    public DataSourceProperties metricsDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "metricsDataSource")
    @ConfigurationProperties("dbagent.metrics.datasource.hikari")
    public HikariDataSource metricsDataSource(@Qualifier("metricsDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "metricsJdbcTemplate")
    public JdbcTemplate metricsJdbcTemplate(@Qualifier("metricsDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    // db_instances(구 databases.json) 전용 SQLite - 같은 이유로 users.db/metrics.db와 파일을 분리한다
    // (oracle.env 제거 마이그레이션 4-1단계, 2026-09-21). 읽기 빈도는 훨씬 높지만(대시보드 요청마다
    // resolve() 호출) 쓰기는 관리자 DB 추가/수정/삭제 때뿐이라 락 경합 위험 자체는 낮지만, 관심사 분리
    // 원칙을 그대로 따른다.
    @Bean(name = "dbConfigDataSourceProperties")
    @ConfigurationProperties("dbagent.dbconfig.datasource")
    public DataSourceProperties dbConfigDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean(name = "dbConfigDataSource")
    @ConfigurationProperties("dbagent.dbconfig.datasource.hikari")
    public HikariDataSource dbConfigDataSource(@Qualifier("dbConfigDataSourceProperties") DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    @Bean(name = "dbConfigJdbcTemplate")
    public JdbcTemplate dbConfigJdbcTemplate(@Qualifier("dbConfigDataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
