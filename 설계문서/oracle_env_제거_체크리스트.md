# oracle.env 제거 마이그레이션 체크리스트

설계 근거는 `설계문서/oracle_env_제거_설계검토_2026-09-18.md` 참고. 이 문서는 실행용 체크리스트만 담는다. 진행하면서 `[ ]`를 `[x]`로 갱신할 것.

## 0단계 — 사전 정보 수집 (오케스트레이터, 폐쇄망에서 직접 확보)

- [x] **A. tnsnames.ora 정의 전문** — 8개 alias(`ORCL`,`ORCL2`,`SRCH1`,`SRCH2`,`PORT1`,`PORT2`,`ORCL3`,`ORCL4`) 항목 전체 확보

  | alias | HOST | PORT | SID/SERVICE_NAME | ADDRESS 개수 | LOAD_BALANCE/FAILOVER 여부 |
  |---|---|---|---|---|---|
  | ORAKIPO1|10.133.103.217 |1520 |ORAKIPO1/ORAKIPO|2| RAC(Real Aoolicatiin Cluster) |
  | ORAKIPO2|10.133.103.219 |1520 |ORAKIPO2/ORAKIPO|2| RAC(Real Aoolicatiin Cluster) |
  | ORANPSH1|10.133.103.221 |1521 |ORANPSH1/ORANPSH|2| RAC(Real Aoolicatiin Cluster) |
  | ORANPSH2|10.133.103.223 |1521 |ORANPSH2/ORANPSH|2| RAC(Real Aoolicatiin Cluster) |
  | ORANPS1 |10.133.106.166 |1521 |ORANPS1/ORANPS|2| RAC(Real Aoolicatiin Cluster) |
  | ORANPS2 |10.133.106.167 |1521 |ORANPS2/ORANPS|2| RAC(Real Aoolicatiin Cluster) |
  | ORAKMS1 |10.133.111.111 |1521 |ORAKMS1/ORAKMS|2| RAC(Real Aoolicatiin Cluster) |
  | ORAKMS2 |10.133.111.112 |1521 |ORAKMS2/ORAKMS|2| RAC(Real Aoolicatiin Cluster) |
  | ORACFE1 |10.133.106.193 |1521 |ORACFE1/ORACFE|2| RAC(Real Aoolicatiin Cluster) |
  | ORACFE2 |10.133.106.194 |1521 |ORACFE2/ORACFE|2| RAC(Real Aoolicatiin Cluster) |
  | ORAODS  |10.133.101.51  |9002 |ORAODS/ORAODS|1| Single DB                      |
 

- [x] **B. 실접속 기준표** — 8개 인스턴스 각각에서 실행 결과 수집

  ```sql
  SELECT instance_name, host_name, version FROM v$instance;
  SELECT name FROM v$database;
  ```

  | alias(=db_id) | instance_name | host_name | version | database name |
  |---|---|---|---|---|
  | ORAKIPO1 |ORAKIPO1| 10.133.103.217 |11.2.4| ORAKIPO |
  | ORAKIPO2 |ORAKIPO2| 10.133.103.219 |11.2.4| ORAKIPO |
  | ORANPSH1 |ORANPSH1| 10.133.103.221 |12.2.0| ORANPSH |
  | ORANPSH2 |ORANPSH2| 10.133.103.223 |12.2.0| ORANPSH |
  | ORANPS1  |ORANPS1| 10.133.106.166| 12.2.0 | ORANPS|
  | ORANPS2  |ORANPS2 | 10.133.106.166| 12.2.0 | ORANPS|
  | ORAKMS1  |ORAKMS1|10.133.111.111 |19c |ORAKMS|
  | ORAKMS2  |ORAKMS2|10.133.111.112 |19c |ORAKMS|
  | ORACFE1  |ORACFE1|10.133.106.193 |19c |ORACFE|
  | ORACFE2  |ORACFE2|10.133.106.194 |19c |ORACFE|
  | ORAODS   |ORAODS|10.133.101.51   |19c |ORAODS|                      |
 
- [x] **C. 폐쇄망 dist `application.properties`에 `dbagent.oracle.tns-admin=` 값이 명시돼 있는지 확인** — 있음 / 없음(있으면 값: C:/oracle_docker_data)
- [x] **D. 루트 `databases.json`의 `ORCL5` 중복("포탈 #1/#2") 의도 확인** — 의도된 것 / 실수(정리 필요) => 인터넷망 테스트 환경이기 때문에 삭제해도 무방
- [x] **E. 기본 DB fallback(빈 db_id) 제거 여부 결정** — 완전 제거(권고) / `"default": true` 플래그로 유지 => 완전 제거

> A~E 전부 체크되기 전에는 1단계를 시작하지 않는다.

- [x] RAC 여부 확정 (2026-09-18, 오케스트레이터 확인): **RAC DB 4개 + 싱글 인스턴스 DB 1개 = 총 8개 접속 항목**. 대응 방식: **노드별 개별 등록**(SID 기반) — V$ 뷰가 인스턴스 로컬이라 service_name보다 이 방식이 모니터링 목적에 더 적합(설계 문서 "RAC 대응" 절 참고)

## 1단계 — 코드 변경 (동작 무변화)

- [x] `OracleConnectionPoolManager.buildDsn()`에 `connect_mode`(sid/service/descriptor) 분기 추가 — 미지정 시 현행 동작 유지
- [x] 풀 생성 시 실제 DSN INFO 로그 추가 (`oracle-<id> -> host:port:sid`)
- [x] `DatabaseConfigService.init()`에 id 중복 / `(host,port,sid)` 중복 WARN 로그 추가
- [x] `TnsAdminInitializer.deriveFromOracleHome()` 제거 (0단계 C 확인 후에만)
- [x] `PoolTestController`(`/api/pool/test`)에 `authService.canAccessDb()` 인증 체크 추가 (부수 발견 보안 이슈, 이 단계에서 같이 처리 권장)
- [x] 로컬 도커에서 기존 동작(alias 접속) 회귀 없음 확인
- [x] main 컴파일/빌드/배포
- [x] AIX 동일 포팅 + 컴파일/빌드/배포
- [x] git 커밋 + push (main/AIX 각각)

## 2단계 — 데이터 전환 (인스턴스 단위, 점진 — 폐쇄망에서만 실검증 가능)

순서: TEST → 검색DB → 포탈 → 통합DB(마지막)

- [x] ORAKIPO1
- [x] ORAKIPO2
- [x] ORANPSH1
- [x] ORANPSH2
- [x] ORANPS1
- [x] ORANPS2
- [x] ORACFE1
- [x] ORACFE2
- [x] ORAKMS1
- [x] ORAKMS2
- [x] ORAODS

각 항목 공통 절차:
1. [x] `databases.json.bak-YYYYMMDD` 백업
2. [x] `host`/`port`/`sid` 채움 (0단계 A 표 기준)
3. [x] `GET /api/pool/test?db_id=...` 접속 성공 확인
4. [x] `v$instance`/`v$database` 결과를 0단계 B 기준표와 대조 — 불일치 시 즉시 중단, host 재확인
5. [x] 문제없으면 다음 인스턴스로

## 3단계 — tnsnames.ora 의존 제거

- [x] 8개 전부 2단계 통과 확인
- [x] `dbagent.oracle.tns-admin` 값 비움 (프로퍼티는 유지)
- ~~ [x] 며칠 정상 운영 확인 (alias 폴백 코드는 유지 상태) ~~
- * 몇칠 테스트 필요 없음, 지금 테스트로 끝내고 4단계로 진행

## 4단계 — oracle.env 제거

- [x] `DatabaseConfigService.resolve("")` → null 반환으로 변경 (resolve(dbId)/resolve(dbId, account) 둘 다, main/AIX 포팅 완료, 8006에서 회귀테스트 확인)
- [x] `listAccounts()`의 blank 분기 정리 (blank dbId → 빈 배열, main/AIX 포팅 완료)
- [x] `app.js`의 `window.currentDbId || ""` 요청 지점에 "빈 값이면 요청 안 보냄" 가드 추가 (switchTab()으로 탭 전환 시 자동 발사되는 3곳 — tablespace/tmlock/session — 에 가드 추가, main/AIX 동일 반영, 컴파일+빌드 확인. 나머지는 수동 액션 트리거이거나 이미 graceful하게 처리되고 있어 범위에서 제외)
- [x] `oracle.env` 파일 삭제 — 루트 (오케스트레이터 직접 삭제, 2026-09-21)
- [x] `oracle.env` 파일 삭제 — dist (오케스트레이터 직접 삭제, 2026-09-21)
- [ ] `oracle.env` 파일 삭제 — 폐쇄망 배포본 (오케스트레이터가 직접 진행해야 함)
- [x] `dbagent.oracle-env-path` 프로퍼티 제거 + `TnsAdminInitializer`/`DatabaseConfigService`의 관련 `@Value` 필드 동시 제거
- [x] `가이드/IMPLEMENTATION.md` 문서 갱신 (oracle.env 설명 부분, main/AIX 둘 다)
- [x] `dist/application.properties.sample` 정리 (main/AIX, dist/dist-aix 전부)
- [x] main/AIX 동일 반영, `mvn clean package` 빌드 성공 확인 (정적 리소스 라인 수 일치 확인)
- [ ] dist/dist-aix 배포 + 폐쇄망 반영
- [ ] 커밋+push (오케스트레이터 요청대로 마지막에 일괄 진행)

### 4-1. `databases.json` → DB 저장소 이전 (2026-09-21 논의)

파일 기반 저장을 걷어내고 기존 `users.db`(내장 DB)에 통합하는 작업. 파일→DB 이전 자체는 보안 개선이 아님 — 비밀번호 암호화 방식을 같이 바꿔야 실제로 의미가 있음.

- [x] 저장 방식 결정: **별도 스키마 채택** — `users.db` 테이블 추가가 아니라 `metrics.db`와 같은 선례로 전용 DB 파일 분리 (main: `dbconfig.db` SQLite, AIX: `dbconfig.mv.db` H2). `DataSourceConfig`에 `dbConfigDataSource`/`dbConfigJdbcTemplate` 빈 추가.
- [ ] 비밀번호 저장 방식 개선: 현재 B64 인코딩(암호화 아님) → 실제 키 기반 암호화(AES 등)로 교체 — 6단계에서 진행 예정, 이번 4-1 범위에서는 저장 위치만 이전(B64 그대로 컬럼에 저장)
- [x] `DatabaseConfigService`를 JSON 파일 I/O → `JdbcTemplate` 기반 DB CRUD로 전면 재작성 (`resolve`/`resolve+account`/`listAccounts`/`listAllInstances`/`safeConfig`/`createInstance`/`updateInstance`/`deleteInstance` 전부). `db_instances` 테이블: id/group_name/group_order/instance_order/name/db_type/host/port/sid/db_user/password/connect_mode/pool_min_idle/pool_max_size/session_thresholds(JSON)/accounts(JSON). group_name 컬럼명은 `user`가 H2 예약어와 충돌 위험이 있어 `db_user`로 명명. main/AIX 동일 반영, 컴파일 성공.
- [x] `DbConfigAdminController`/`db-mgmt.html` 연동 — **API 요청/응답 형식 100% 동일 유지, 컨트롤러/프런트 코드 변경 없음** (내부 저장소만 교체). 실제 create/update/delete API 호출로 검증 완료.
- [x] 기존 `databases.json` 내용을 신규 DB 테이블로 1회성 이관 — `db_config_meta.migrated_from_json` 플래그로 최초 기동 시 1회만 자동 이관, 이후 파일 재확인 안 함. 인터넷망 개발 PC에서 실제 13개(main)/12개(AIX) 인스턴스로 마이그레이션+CRUD+접속 테스트 전부 통과.
- [x] 모든 RDB 대시보드(Oracle/MySQL/MariaDB/PostgreSQL/MSSQL/CUBRID) 회귀 확인 — 6개 엔진 전부 실접속 검증 완료(2026-09-21). `/api/pool/test`는 Oracle 전용 엔드포인트라 다른 엔진 테스트에 안 맞음 — `/api/rdb/sessions?db_id=...`(db_type 기반으로 엔진 분기)로 MySQL/MariaDB/PostgreSQL/MSSQL/CUBRID 도커 컨테이너 재기동 후 전부 세션 조회 성공 확인. host/port/user/password/db_type 등 DB 백엔드 경유로도 정확히 round-trip 확인됨.
- [x] 이관 검증 후 `databases.json` 파일 삭제 — main 루트/dist, AIX 루트/dist, `dist-aix` 스테이징까지 전부 삭제 완료(2026-09-21). 폐쇄망 실서버 파일만 남음 — 이미 관리 화면으로 정리 끝난 상태라 오케스트레이터가 원하실 때 지우면 됨.
- [x] `dbagent.databases-config` 프로퍼티 제거 (main/AIX `application.properties` 둘 다 제거, 마이그레이션 경로는 코드에 `"databases.json"`로 하드코딩)

**빈 테이블 안전성 확인(2026-09-21, 오케스트레이터 질문에 대한 실측 답변)**: `dbconfig.db`/`databases.json` 둘 다 없는 상태로 기동 → `/api/config`가 `{"groups":[]}` 정상 반환, `/api/pool/test`는 404(정상, 크래시 아님), `err.log` 완전히 비어있음 확인. **초기 데이터 없어도 전혀 문제 없음 — 필수 아님.**

**폐쇄망 적용 절차(오케스트레이터 요청: ORAKIPO1/ORAKIPO2 2개만 우선 이관)**:
1. 폐쇄망 서버의 `databases.json`을 ORAKIPO1/ORAKIPO2 두 항목만 남기고 나머지는 제거(또는 백업 후 삭제)
2. 이 커밋 이후 빌드된 jar로 교체, 최초 기동 → `dbconfig.db`/`dbconfig.mv.db`가 새로 생성되며 그 2개만 자동 이관됨
3. 나머지 인스턴스는 이후 관리 화면(DB 추가)에서 직접 등록 — 재작업 아님, 정상 경로
4. 이관 후 `databases.json`은 다시 읽히지 않으므로 그대로 둬도 되고, 확인 끝나면 삭제해도 무방(4-1 체크리스트 "이관 검증 후 삭제" 항목)

**폐쇄망 실환경 검증 완료(2026-09-21, 오케스트레이터 직접 테스트)**:
- 시나리오 1: `dbconfig.db` 전체 삭제 후 빈 상태에서 관리 화면으로 수동 등록 → 정상 완료
- 시나리오 2: `databases.json`으로 최초 DB 구축(자동 마이그레이션) 후 개별 인스턴스 추가/삭제 → 정상 완료
- 개발 PC 드라이런(`C:\AI-PROJECTS\Conf_file\databases.json`, 실운영과 동일한 11개 인스턴스/6개 그룹 구조)에서도 그룹핑·session_thresholds·accounts까지 정확히 일치 확인 완료
- → **4-1 핵심 기능(마이그레이션 + CRUD + 빈 테이블 안전성)은 개발/폐쇄망 양쪽에서 전부 실증됨**

- [ ] main/AIX 동일 반영(dual-project-sync), 빌드/배포, 커밋+push

## 5단계 — alias 경로 완전 삭제 (최종 정리) — 2026-09-21 완료

- [x] `buildDsn()`의 legacy alias 분기 제거 — `legacyDsn()`(host 빈값→SID를 TNS alias로 취급) 삭제, connect_mode 미지정 시 그냥 `host:port:sid`로 고정. main/AIX 동일 반영.
- [x] `TnsAdminInitializer` 클래스 삭제 (main/AIX 둘 다)
- [x] `dbagent.oracle.tns-admin` 프로퍼티 삭제 — 관련 있던 모든 `application.properties`/`.sample`(루트·dist·dist-aix, main/AIX)에서 프로퍼티 및 관련 주석 제거
- [x] `host` 빈 Oracle 인스턴스는 기동 시 명시적 오류 처리 — `DatabaseConfigService.errorOnMissingOracleHost()` 추가, `db_type='oracle' AND host 빈값`인 행을 ERROR 로그로 남김(기동은 막지 않음, warnOnDuplicates()와 동일 원칙). **추가로** `createInstance`/`updateInstance`에도 Oracle+host빈값 조합을 아예 거부하는 검증을 넣어 신규 등록 자체를 막음(체크리스트 원문보다 한 단계 더 강화). 실측: 개발 PC dbconfig.db의 기존 alias 인스턴스 8개(patent_integ_1/2, patent_search_1/2, patent_portal_1/2, TEST01/02)가 정확히 ERROR 로그로 잡히는 것 확인, host 없이 생성 시도 시 "Host는 필수입니다" 거부 확인(main/AIX 둘 다).
- [x] `db-mgmt.html` 라벨 "Host (필수)"로 수정 + `connect_mode` 드롭다운(기본/Service Name/전체 디스크립터) 추가 — main/AIX 동일 반영, dbtype 변경 시 Oracle에서만 노출되도록 토글 처리.
- [x] `expected_instance_name` 필드 추가 + 최초 접속 시 `v$instance` 대조 검증 로직 구현 — `TargetDbConfig`에 필드 추가, `db_instances` 테이블에 컬럼 추가(기존 dbconfig.db/mv.db에는 ALTER TABLE로 무중단 추가), db-mgmt.html에 입력 필드 추가, `PoolTestController`가 Oracle이고 expected_instance_name이 설정된 경우 `v$instance.instance_name`과 대조해 불일치 시 409로 실패 처리. 실측: 일부러 틀린 값 넣어 409 확인 → 올바른 값으로 고쳐서 200 확인(connect_mode=service, host=127.0.0.1:1522/XE로 실접속 테스트).
- [x] main/AIX 동일 반영, `mvn clean package` 빌드 성공, 기존 dbconfig.db/mv.db(스키마 변경 전 데이터) 대상으로 무중단 마이그레이션 확인. 배포(dist 반영)까지 완료. 커밋+push는 오케스트레이터 요청대로 마지막에 일괄 진행.

**향후 검토(2026-09-21, 오케스트레이터 확인)**: `createInstance`/`updateInstance`의 Host 필수 검증이 `connect_mode`와 무관하게 걸려있어서, `descriptor` 모드(Host/Port 필드가 실제로는 안 쓰이고 SID란의 전체 커넥트 디스크립터만 사용)에서도 Host 필드에 값을 채워야 저장됨. 지금 당장 고치지 않고 향후 검토 항목으로 남김 — 필요해지면 `connect_mode == "descriptor"`일 때는 Host 필수 검증을 건너뛰도록 수정.

## 6단계 — 보안 강화: 암호화 설계 검토 및 적용

4-1에서 나온 "비밀번호 실제 암호화" 필요성을 앱 전체 범위로 넓혀 다루는 단계. 4단계 DB 이전과 맞물리므로, 순서상 4-1 구현 직전/직후에 같이 진행하는 것을 권장.

- [ ] **현황 조사**: 평문/B64 인코딩(암호화 아님)으로 저장 중인 값 전수 조사 — `databases.json`의 DB 비밀번호, `oracle.env`의 `PASSWORD`, `users.db`의 계정 비밀번호(해시 여부 확인), 기타 설정 파일
- [ ] **암호화 방식 결정**: 대칭키(AES-256-GCM 등) 채택 여부, 라이브러리 선택 — main(Java 17)/AIX(Java 8) 양쪽에서 동일하게 쓸 수 있는 것으로 (표준 `javax.crypto`면 양쪽 다 사용 가능)
- [ ] **키 관리 방안 결정**: 암호화 키를 어디에 보관할지 (환경변수 / 별도 키 파일 + 파일권한 제한 / OS 자격증명 저장소 등) — 키 자체가 평문 파일에 같이 있으면 암호화 의미 없음, git에는 절대 커밋 금지
- [ ] **키 로테이션 정책 검토** (교체 주기, 교체 시 기존 암호화 값 재암호화 절차)
- [ ] **구현**: 암/복호화 유틸리티 작성 (`Encryptor`/`Decryptor` 등), 저장 경로(`persist()`)와 조회 경로(`resolve()`/커넥션 풀 생성) 양쪽에 적용
- [ ] **기존 저장값 마이그레이션**: 현재 B64 값들을 신규 암호화 값으로 1회성 재암호화하는 스크립트/로직
- [ ] **회귀 확인**: 모든 DB 엔진(Oracle/MySQL/MariaDB/PostgreSQL/MSSQL/CUBRID) 접속 정상 동작 확인
- [ ] main/AIX 동일 반영(dual-project-sync), 빌드/배포, 커밋+push

## 공통 주의사항 (매 단계 배포 시)

- `application.properties`/`oracle.env`/`databases.json`은 git skip-worktree 대상 — 파일 삭제/수정은 서버에서 직접, git으로 전파 안 됨
- 회사 PC는 pull 전 백업 원칙 준수
- 모든 코드 변경은 DBAgent-Java와 DBAgent-Java-AIX 양쪽에 동일 반영 (dual-project-sync 원칙)
- 각 단계 배포 후 `curl`로 실제 서빙 콘텐츠가 최신인지 확인 (build_environment 메모리 참고)
