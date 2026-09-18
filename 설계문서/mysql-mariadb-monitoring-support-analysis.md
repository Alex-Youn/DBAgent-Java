# MariaDB/MySQL 모니터링 지원 검토

## 질문
현재 Docker에 MariaDB, MySQL이 설치되어 있는데, 이를 DB 모니터링 도구(DBAgent-Java)에 적용할 수 있는가?

## 결론
가능은 하지만, 현재 코드베이스는 **Oracle 전용으로 깊게 결합**되어 있어 단순히 JDBC 드라이버만 추가하는 수준이 아니라 상당한 작업이 필요하다.

## 현재 구조 (Oracle 전용)
- 패키지명부터 `com.dbagent.oracle`, JDBC 드라이버는 `ojdbc11`(Oracle)만 `pom.xml`에 등록되어 있음
- 커넥션 풀(`OracleConnectionPoolManager`)이 `jdbc:oracle:thin:@...` DSN을 하드코딩
- 모니터링 로직(`MonitorService`, 1000줄+)이 `V$SESSION`, `V$ACTIVE_SESSION_HISTORY`(ASH), `V$TRANSACTION`, `V$PX_SESSION` 같은 **Oracle 전용 동적 성능 뷰**로 세션/락/대기 정보를 조회
- `databases.json`도 Oracle SID/포트 구조(1521)로만 구성되어 있음

## MySQL/MariaDB로 확장 시 필요한 작업
1. `mysql-connector-j` / `mariadb-java-client` 드라이버 추가 + DSN 빌드 로직에 DB 타입 분기 추가
2. Oracle V$ 뷰에 대응하는 MySQL/MariaDB 쿼리로 새 QueryHelper 작성 필요
   - 세션 → `performance_schema.threads` / `SHOW PROCESSLIST`
   - 락 → `performance_schema.data_lock_waits` 또는 `information_schema.innodb_lock_waits`
   - ASH(Active Session History) 같은 히스토리 기능은 MySQL에 직접적인 대응 기능이 없음
3. `TargetDbConfig`에 DB 타입 필드 추가, 서비스 계층을 벤더별로 분기(또는 인터페이스로 추상화)하는 리팩토링

## 제안 방향
한 번에 전체를 이식하기보다, 다음 순서로 단계적 지원을 권장:
1. 세션 목록 모니터링
2. 테이블스페이스/스토리지 용량 모니터링
3. 간단한 프로세스 모니터링

위 항목부터 MySQL/MariaDB용 최소 기능으로 붙여보고, 이후 락/대기/AWR 유사 기능으로 확장하는 방식이 현실적이다.
