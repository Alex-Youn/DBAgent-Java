# MySQL / MariaDB / PostgreSQL 핵심 모니터링 항목

세 DB 모두 공통으로 챙겨야 할 항목과, 각 DB에서만 의미 있는 고유 항목을 카테고리별로 정리했습니다.

## 1. 가용성 / 업타임

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 서버 가동 시간 | `Uptime` | `Uptime` (MySQL과 동일) | `pg_postmaster_start_time()` |
| 인스턴스 up/down | 프로세스·포트 헬스체크 | 동일 | 동일 |
| 에러 로그 발생량 | Error log 내 에러/경고 건수 | 동일 | PostgreSQL 로그(`log_min_messages`) 내 ERROR/FATAL/PANIC 건수 |

## 2. 커넥션

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 현재 연결 수 | `Threads_connected` | `Threads_connected` | `pg_stat_activity` 행 수 |
| 최대 연결 대비 사용률 | `Threads_connected / max_connections` | 동일 | `count(*) / max_connections` |
| 연결 실패/거부 | `Aborted_connects`, `Connection_errors_*` | 동일 | `pg_stat_database.numbackends` 급증, 로그의 `too many connections` |
| 유휴 트랜잭션 | `Threads_running`으로 간접 추정 | 동일 | `idle in transaction` 상태 세션 수 (트랜잭션 방치로 인한 vacuum 지연 유발) |
| 커넥션 풀 상태 (ProxySQL/PgBouncer 등 사용 시) | 풀 히트율, 대기 큐 길이 | 동일 | PgBouncer `avg_wait_time`, 풀 사용률 |

## 3. 처리량 (쿼리 / 트랜잭션)

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 초당 쿼리 수 (QPS) | `Questions` 변화량 | 동일 | 쿼리 로그 또는 `pg_stat_statements.calls` 변화량 |
| 초당 트랜잭션 수 (TPS) | `Com_commit`, `Com_rollback` 변화량 | 동일 | `pg_stat_database.xact_commit`, `xact_rollback` 변화량 |
| SELECT/INSERT/UPDATE/DELETE 비율 | `Com_select`, `Com_insert`, `Com_update`, `Com_delete` | 동일 | `pg_stat_user_tables`의 `seq_scan`/`idx_scan`, `n_tup_ins/upd/del` |
| 롤백 비율 | `Com_rollback / Com_commit` | 동일 | `xact_rollback / (xact_commit + xact_rollback)` — 비율이 높으면 애플리케이션 로직 점검 필요 |

## 4. 슬로우 쿼리 / 쿼리 성능

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 느린 쿼리 건수 | `Slow_queries` (slow query log, `long_query_time` 기준) | 동일 (`log_slow_verbosity`로 상세 확장 가능) | `pg_stat_statements` 또는 `log_min_duration_statement` 기준 로그 |
| 쿼리별 평균/최대 실행시간 | `performance_schema.events_statements_summary_by_digest` | 동일 | `pg_stat_statements.mean_exec_time / max_exec_time` |
| 풀스캔 비율 | `Select_full_join`, `Select_scan` | 동일 | `seq_scan` vs `idx_scan` 비율 (테이블별) |
| 임시 테이블/파일 사용 | `Created_tmp_tables`, `Created_tmp_disk_tables` (디스크로 튀는 비율이 중요) | 동일 | `temp_files`, `temp_bytes` (`pg_stat_database`) |
| 정렬 성능 | `Sort_merge_passes` | 동일 | `pg_stat_statements`의 정렬 관련 지표, `work_mem` 부족 여부 |

## 5. 캐시 / 버퍼

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 버퍼 캐시 히트율 | InnoDB Buffer Pool 히트율 (`Innodb_buffer_pool_read_requests` 대비 `Innodb_buffer_pool_reads`) | 동일 (InnoDB) + Aria 엔진 사용 시 Aria pagecache 히트율 | `shared_buffers` 히트율 (`blks_hit / (blks_hit + blks_read)`, `pg_stat_database`) |
| 버퍼 풀 크기 / 전체 메모리 대비 비율 | `innodb_buffer_pool_size` | 동일 | `shared_buffers` 설정값, OS 페이지 캐시 활용 여부 |
| 더티 페이지 비율 | `Innodb_buffer_pool_pages_dirty` | 동일 | 체크포인트 관련 dirty buffer 비율 |
| 쿼리 캐시 | `Qcache_*` (MySQL 8.0에서 제거됨, 5.7 이하만 해당) | MariaDB는 계속 지원 (`Qcache_hits`, `Qcache_free_memory`) | 해당 없음 (PostgreSQL은 쿼리 캐시 개념이 없고 대신 실행계획 캐시 사용) |

## 6. 락 / 대기

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 테이블 락 대기 | `Table_locks_waited` vs `Table_locks_immediate` | 동일 | 해당 개념 약함 (MVCC 기반이라 읽기 락 최소화) |
| 로우 락 대기 | `Innodb_row_lock_waits`, `Innodb_row_lock_time` | 동일 | `pg_locks`에서 `granted=false` 건수, 대기 시간 |
| 데드락 발생 건수 | `SHOW ENGINE INNODB STATUS`의 deadlock 섹션 | 동일 | `pg_stat_database.deadlocks` |
| 장기 실행 트랜잭션 | `INFORMATION_SCHEMA.INNODB_TRX`에서 실행시간 긴 트랜잭션 | 동일 | `pg_stat_activity`에서 `xact_start` 오래된 세션 (autovacuum 방해 요인) |

## 7. 복제 (Replication)

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 복제 지연 | `Seconds_Behind_Master` (`SHOW REPLICA STATUS`) | 동일 (`Seconds_Behind_Master`) | `pg_stat_replication`의 `replay_lag`, `write_lag`, `flush_lag` |
| 복제 스레드 상태 | `Replica_IO_Running`, `Replica_SQL_Running` | 동일 | `pg_stat_wal_receiver`의 상태, `walsender`/`walreceiver` 프로세스 여부 |
| GTID 진행 상황 | `gtid_executed` | `gtid_current_pos`, `gtid_slave_pos` | 물리 복제는 WAL LSN 기준(`pg_current_wal_lsn`), 논리 복제는 슬롯별 지연 |
| 복제 슬롯/바이너리 로그 적체 | 바이너리 로그 용량, `binlog_expire_logs_seconds` | 동일 | `pg_replication_slots`의 `active`, 슬롯별 미회수 WAL 용량 (누적 시 디스크 고갈 위험) |
| 클러스터 관련 | 그룹 복제(Group Replication) 상태 | **Galera Cluster** 사용 시: `wsrep_cluster_size`, `wsrep_local_state_comment`, `wsrep_flow_control_paused`, `wsrep_local_recv_queue`, 인증 충돌(cert failures) | 논리 복제/Patroni 등 HA 스택 사용 시 해당 스택의 리더/팔로워 상태 |

## 8. 스토리지 / 유지보수

| 항목 | MySQL | MariaDB | PostgreSQL |
|---|---|---|---|
| 데이터/인덱스 용량 증가 추이 | `information_schema.TABLES` 크기 합계 | 동일 | `pg_database_size()`, `pg_total_relation_size()` |
| 테이블/인덱스 팽창(bloat) | 프래그멘테이션 (`OPTIMIZE TABLE` 필요 여부) | 동일 | 데드 튜플로 인한 bloat — **PostgreSQL에서 특히 중요** |
| 백그라운드 정리 작업 | `innodb_purge` 스레드 지연 여부 | 동일 | **Autovacuum** 실행 빈도/지속시간, 테이블별 dead tuple 수 (`n_dead_tup`) |
| 트랜잭션 ID 소진 위험 | 해당 없음(내부적으로 관리) | 해당 없음 | `age(datfrozenxid)` — **PostgreSQL 고유 위험 지표**, 임계치 근접 시 서비스 중단 위험 |
| WAL/Redo 로그 생성량 | InnoDB redo log 생성 속도, `innodb_log_file_size` | 동일 | WAL 생성량(`pg_current_wal_lsn` 증가 속도), `max_wal_size` |
| 체크포인트 | InnoDB 체크포인트 연속 발생 여부 | 동일 | 체크포인트 빈도/소요시간, `checkpoints_timed` vs `checkpoints_req` 비율 (강제 체크포인트가 많으면 설정 조정 필요) |

## 9. 리소스 (OS 레벨, 3종 공통)

- CPU 사용률 (전체 및 DB 프로세스 기준)
- 메모리 사용률 및 스왑 발생 여부
- 디스크 I/O (읽기/쓰기 처리량, iowait, 큐 길이)
- 디스크 여유 공간 (특히 로그/WAL 파티션)
- 네트워크 처리량 및 에러/재전송률

## 10. 요약 — 각 DB에서 "가장 먼저 봐야 할" 항목

- **MySQL**: 커넥션 수/사용률, QPS, InnoDB 버퍼 풀 히트율, 슬로우 쿼리 건수, 복제 지연(`Seconds_Behind_Master`), 로우 락 대기
- **MariaDB**: MySQL과 동일한 항목 + (Galera 클러스터 구성 시) `wsrep_cluster_size`·`wsrep_local_state`·flow control 일시정지 여부
- **PostgreSQL**: 커넥션 수/사용률, TPS(commit/rollback), 캐시 히트율, 슬로우 쿼리(`pg_stat_statements`), **Autovacuum 지연 및 dead tuple 누적**, 복제 지연, **트랜잭션 ID 소진 위험(`age(datfrozenxid)`)**

> PostgreSQL은 MVCC 구조 특성상 vacuum/dead tuple/트랜잭션 ID 소진 관련 항목이 MySQL·MariaDB에는 없는 고유 리스크이므로, 통합 대시보드를 만들 때 별도 섹션으로 분리하는 것을 권장합니다.
