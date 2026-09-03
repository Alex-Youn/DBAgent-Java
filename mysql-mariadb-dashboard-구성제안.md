# MySQL/MariaDB/PostgreSQL 전용 경량 대시보드 구성 제안

> [mysql-mariadb-monitoring-support-analysis.md](mysql-mariadb-monitoring-support-analysis.md)의 후속 논의. 2026-09-03부로 구현 단계 진입 (로컬 Docker에 mysql/mariadb/postgres 컨테이너 준비 완료).

## 배경

기존 `index.html` 대시보드는 ASH(Active Session History), 락 트리, PX 세션 등 Oracle 전용 동적 성능 뷰에 강하게 결합되어 있어, 다른 DB 엔진 데이터를 그대로 얹기 어렵다. Oracle만큼 많은 기능이 필요하지도 않으므로, Oracle 이외 엔진은 **별도의 경량 대시보드**를 만들고 Fleet Overview(FO)에서 점프시키는 방식을 채택한다.

대상 엔진: **MySQL, MariaDB, PostgreSQL** (로컬 Docker `mysql-server`/`mariadb-server`/`postgres-server` 컨테이너로 검증).

## 구성 제안

### 1. `databases.json`에 DB 타입 필드 추가

- 인스턴스 스키마에 `db_type` 필드 추가 (`"oracle"` / `"mysql"` / `"mariadb"` / `"postgres"`)
- 필드가 없는 기존 데이터는 `oracle`로 하위 호환 처리
- 환경설정(`db-mgmt.html`)의 "새 DB 추가" 폼에 DB 타입 선택 드롭다운 추가
  - 타입에 따라 입력 필드 구성도 달라짐 (Oracle: SID/서비스명, MySQL/MariaDB: 스키마 목록, PostgreSQL: 데이터베이스명 등)

### 2. 백엔드는 얕게 분기, 엔진별 서비스 신설

- 기존 `MonitorService`(Oracle 전용, 1000줄+)는 건드리지 않음
- 세션/스토리지 등 최소 기능만 담당하는 신규 서비스를 엔진별로 신설:
  - `MySqlMonitorService` — MySQL과 MariaDB는 와이어 프로토콜·`SHOW`/`performance_schema` 계열 카탈로그가 사실상 호환되므로 하나로 묶어 `db_type`(mysql/mariadb)만 구분
  - `PostgresMonitorService` — PostgreSQL은 카탈로그(`pg_stat_activity`, `pg_database` 등)와 드라이버가 달라 별도 서비스로 분리
- `db_type` 값으로 어느 서비스를 탈지 라우팅
- Oracle 코드에 분기를 끼워 넣는 방식보다 안전 — 나중에 폐기해도 Oracle 쪽에 영향 없음

### 3. 화면도 별도 페이지로 분리

- `mysql-dashboard.html`(MySQL/MariaDB 공용), `postgres-dashboard.html`(PostgreSQL) 신설
- Oracle 대시보드의 탭 구조는 재사용하되, 락/ASH/PX 관련 탭은 제외
- 최소 구성 후보:
  - 세션 목록 (MySQL/MariaDB: `SHOW PROCESSLIST` / `performance_schema.threads`, PostgreSQL: `pg_stat_activity`)
  - 커넥션 수
  - 스토리지/테이블스페이스(스키마·데이터베이스별 용량) 사용량 (PostgreSQL: `pg_database_size`)

### 4. FO 카드에서 DB 타입별 라우팅 분기

- 현재 FO는 인스턴스 카드 클릭 시 `index.html`로 이동
- `db_type`이 `oracle`이 아니면 해당 엔진의 경량 페이지로 이동하도록 분기 (mysql/mariadb → `mysql-dashboard.html`, postgres → `postgres-dashboard.html`)
- FO 카드 자체의 미니 통계(CPU/커넥션 등)는 엔진 공통으로 유사한 지표라 기존 카드 UI 재사용 가능할 것으로 예상

## 단계적 확장 순서 (참고: 기존 분석 문서 제안 순서와 동일)

1. 세션 목록 모니터링
2. 테이블스페이스/스토리지 용량 모니터링
3. 간단한 프로세스 모니터링
4. (이후) 락/대기 등 확장 검토

## 트레이드오프

- **장점**: Oracle 코드를 거의 건드리지 않고 안전하게 확장 가능
- **단점**: 세션/락 표시 방식이 Oracle 대시보드와 다른 두 번째(MySQL 계열)·세 번째(PostgreSQL) UI 언어가 생겨 유지보수 지점이 늘어남
  - 다만 Oracle 전용 코드에 억지로 통합하는 것보다는 유지보수 부담이 낮다고 판단
