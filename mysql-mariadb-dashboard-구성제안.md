# MySQL/MariaDB 전용 경량 대시보드 구성 제안

> [mysql-mariadb-monitoring-support-analysis.md](mysql-mariadb-monitoring-support-analysis.md)의 후속 논의. 아직 검토 단계이며 구현 전.

## 배경

기존 `index.html` 대시보드는 ASH(Active Session History), 락 트리, PX 세션 등 Oracle 전용 동적 성능 뷰에 강하게 결합되어 있어, MySQL/MariaDB 데이터를 그대로 얹기 어렵다. Oracle만큼 많은 기능이 필요하지도 않으므로, MySQL/MariaDB는 **별도의 경량 대시보드**를 만들고 Fleet Overview(FO)에서 점프시키는 방식을 검토한다.

## 구성 제안

### 1. `databases.json`에 DB 타입 필드 추가

- 인스턴스 스키마에 `db_type` 필드 추가 (`"oracle"` / `"mysql"` / `"mariadb"`)
- 필드가 없는 기존 데이터는 `oracle`로 하위 호환 처리
- 환경설정(`db-mgmt.html`)의 "새 DB 추가" 폼에 DB 타입 선택 드롭다운 추가
  - 타입에 따라 입력 필드 구성도 달라짐 (Oracle: SID/서비스명, MySQL/MariaDB: 스키마 목록 등)

### 2. 백엔드는 얕게 분기

- 기존 `MonitorService`(Oracle 전용, 1000줄+)는 건드리지 않음
- 세션/스토리지 등 최소 기능만 담당하는 별도의 `MySqlMonitorService`(가칭) 신설
- `db_type` 값으로 어느 서비스를 탈지 라우팅
- Oracle 코드에 MySQL 분기를 끼워 넣는 방식보다 안전 — 나중에 폐기해도 Oracle 쪽에 영향 없음

### 3. 화면도 별도 페이지로 분리

- `mysql-dashboard.html`(가칭) 신설
- Oracle 대시보드의 탭 구조는 재사용하되, 락/ASH/PX 관련 탭은 제외
- 최소 구성 후보:
  - 세션 목록 (`SHOW PROCESSLIST` / `performance_schema.threads`)
  - 커넥션 수
  - 스토리지/테이블스페이스(스키마별 용량) 사용량

### 4. FO 카드에서 DB 타입별 라우팅 분기

- 현재 FO는 인스턴스 카드 클릭 시 `index.html`로 이동
- `db_type`이 `oracle`이 아니면 새 경량 페이지로 이동하도록 분기
- FO 카드 자체의 미니 통계(CPU/커넥션 등)는 MySQL도 유사한 지표라 기존 카드 UI 재사용 가능할 것으로 예상

## 단계적 확장 순서 (참고: 기존 분석 문서 제안 순서와 동일)

1. 세션 목록 모니터링
2. 테이블스페이스/스토리지 용량 모니터링
3. 간단한 프로세스 모니터링
4. (이후) 락/대기 등 확장 검토

## 트레이드오프

- **장점**: Oracle 코드를 거의 건드리지 않고 안전하게 확장 가능
- **단점**: 세션/락 표시 방식이 Oracle 대시보드와 다른 두 번째 UI 언어가 생겨 유지보수 지점이 하나 늘어남
  - 다만 Oracle 전용 코드에 MySQL을 억지로 통합하는 것보다는 유지보수 부담이 낮다고 판단
