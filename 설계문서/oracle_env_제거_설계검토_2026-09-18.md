# oracle.env 제거 및 IP 직접 접속 전환 설계 (2026-09-18)

`설계문서/DB커넥션설정_개선검토_2026-09-15.md`의 후속. design-advisor 에이전트로 코드/설정을 전수 재확인하고 설계안을 도출했다. **코드 변경은 아직 없음 - 설계 단계.**

## 목표

- `databases.json`에 DB를 추가할 때 `tnsnames.ora` alias 등록이 더 이상 필요 없게 한다.
- `oracle.env` 파일 자체를 배포물에서 제거한다(아래 2역할 모두 대체).
- Oracle 접속을 **IP(또는 호스트명) + 포트 + 실제 인스턴스 이름(SID)** 직접 접속으로 통일한다.
- 과거 2회 발생한 "id/sid 중복 → 엉뚱한 DB 접속" 사고의 재발 경로를 함께 차단한다.

## 현황 진단 (2026-09-18 코드 재확인)

### 2026-09-15 조사 내용 검증 — 대체로 유효, 한 가지 정정

| 2026-09-15 기록 | 2026-09-18 재확인 결과 |
|---|---|
| `buildDsn()`이 host 있으면 `host:port:sid`, 없으면 sid를 TNS alias로 사용 | 그대로 유효 (`OracleConnectionPoolManager.java` 144~151행). service_name 분기 없음도 그대로 |
| `TnsAdminInitializer`가 tns-admin 미설정 시 `ORACLE_HOME`에서 유도 | 그대로 유효 (`TnsAdminInitializer.java` 27~51행) |
| "현재는 tns-admin을 의도적으로 비워두고 ORACLE_HOME에서 유도 중" | **정정 필요.** 비어 있는 것은 소스 트리의 `src/main/resources/application.properties`(jar 내부 기본값)뿐. 실제 실행에 쓰이는 외부 설정(`application.properties`, `dist/application.properties`)은 둘 다 `dbagent.oracle.tns-admin=C:/oracle_docker_data`를 **명시**하고 있어, 최소 로컬/dist 실행 환경에서는 ORACLE_HOME 유도 경로가 이미 죽은 코드다(폐쇄망 배포본은 별도 확인 필요) |
| `loadOracleEnvFallback()`이 기본 DB fallback 제공 | 그대로 유효 (`DatabaseConfigService.java` 219~250행) |

### `oracle.env` 실물 내용

```
ORACLE_HOME=C:\Oracle\app\oracle\product\19.0.0\dbhome_1
SID=ORCL
USER=SYS
PASSWORD=B64(...)
HOST=
PORT=
```

`HOST`가 비어 있어 fallback DSN은 TNS alias `ORCL`이 되고, `USER=SYS`라 `sysdba` 분기를 타 `internal_logon=sysdba`로 접속한다. **즉 `db_id`가 빈 요청은 전부 통합DB #1에 SYS/SYSDBA로 붙는다** — 기능 편의용 fallback이 사실상 최고 권한 기본 접속 경로가 되어 있어, 보안 측면에서도 제거 근거가 된다.

### `resolve()` 호출부 전수 조사 — 핵심 발견

| 파일 | resolve 호출 | null 체크 |
|---|---|---|
| `monitor/MonitorController.java` | 21 | 21 |
| `rdb/RdbMonitorController.java` | 16 | 16 |
| `sqltuning/SqlTuningController.java` | 4 | 4 |
| `sqlwriter/SqlWriterController.java` | 1 | 1 |
| `query/SqlQueryController.java` | 1 | 1 |
| `oracle/PoolTestController.java` | 1 | 1 |
| `oracle/DbConfigAdminController.java` | 1 | 1 |

**모든 호출부가 이미 null을 "등록되지 않은 DB"로 안전하게 처리한다.** `resolve("")`가 fallback 대신 null을 반환하도록 바꿔도 NPE 없이 404성 응답으로 degrade — 기본 DB fallback 제거의 난이도가 예상보다 낮다는 뜻.

### 프런트엔드의 빈 db_id 발생 구간

- `app.js`: `window.currentDbId = ""`로 시작, 좌측 트리에서 인스턴스를 고를 때(또는 자동 선택 로직) 채워짐
- 자동 선택이 실패하는 경우(계정의 숨김 DB 설정으로 모든 인스턴스가 restricted, `jumpToDbId`가 unknown 등 — "no silent fallback to first" 정책) `currentDbId`는 `""`로 남는다
- 30곳 이상이 `window.currentDbId || ""` 형태로 요청을 보냄
- `AuthService.canAccessDb()`는 blank dbId를 **무조건 허용** → "모든 DB가 숨김 처리된 계정"도 fallback DB(SYS)의 데이터는 볼 수 있는 경로가 열려 있음. fallback 제거로 이 경로도 같이 닫힌다.

### `databases.json` 현황 — 개발본과 실서비스본이 다름

- **루트(개발/도커용)**: Oracle 8개, 전부 `host: ""`. `ORCL`, `ORCL2`, `ORCL3`, `ORCL4`, **`ORCL5`, `ORCL5`(포탈 #1/#2 중복 잔존)**, `ORCL6`, `ORCL7`
- **`dist`(실서비스)**: Oracle 8개, 전부 `host: ""`. `ORCL`, `ORCL2`, `SRCH1`, `SRCH2`, `PORT1`, `PORT2`, `ORCL3`, `ORCL4` — 중복 없음
- → sid 중복은 실서비스 문제가 아니라 **로컬 도커 alias 재사용에서 비롯된 개발본 문제**로 보임. 그래도 방치하면 혼동 유발 가능.

기타:
- RDB 6종(mysql/mariadb/postgres/mssql/cubrid)은 이미 `host: "127.0.0.1"`로 IP 직결로 매일 동작 중 — "host를 채우는 운영 방식" 자체는 이미 검증된 경로.
- `db-mgmt.html`에 이미 host 입력 필드가 있고 라벨이 "Host (Oracle만 비워두면 tnsnames.ora의 SID를 별칭으로 사용)" — **UI 변경 없이도 데이터만 채우면 전환 가능.**
- 로컬 도커는 `ORCL~ORCL10` alias가 전부 같은 XE 인스턴스로 매핑됨. 로컬에서 host/port/sid를 채우면 8개가 전부 `127.0.0.1:1521:XE`가 되므로 **"인스턴스별로 서로 다른 DB에 제대로 붙는지"는 로컬에서 검증 불가** — 실매핑 검증은 폐쇄망에서만 가능.
- repo 내에 `tnsnames.ora` 사본이 없음 → 폐쇄망 alias 8개가 SID/SERVICE_NAME 기반인지, RAC 여부인지 **현재 자료만으로는 판단 불가.**

### 부수 발견 (본 주제 밖, 별건 처리 권장)

`PoolTestController`의 `GET /api/pool/test`에 토큰/권한 검사가 없음 — db_id만 알면 누구나 해당 DB 접속 성공 여부를 확인할 수 있음. 아래 마이그레이션에서 이 엔드포인트를 전환 검증용으로 쓸 예정이므로, 그 김에 `authService.canAccessDb()`를 붙이는 것을 권장.

## 제안 설계

### 접속 식별자 모델

**옵션 A — 현행 유지**(host 비면 alias, 채워지면 SID 콜론): 코드 변경 0, 즉시 적용 가능하지만 service_name 표현 수단이 없고 host를 실수로 지우면 조용히 alias 모드로 되돌아감(다른 DB에 붙을 위험).

**옵션 B — sid 문자열에 포맷 힌트**(`/` 포함 시 service_name): 스키마 추가 없지만 암묵지라 오타 위험. 비추천.

**옵션 C(권고) — `connect_mode` 필드 명시**(`"sid" | "service" | "descriptor"`): 접속 방식이 데이터에 드러나고 관리자 화면 드롭다운으로 노출 가능, RAC/다중 주소를 `descriptor` 모드로 흡수 가능. 코드+스키마+UI 3곳 변경 필요.

alias를 없앤다는 것은 alias가 담던 정보(주소·포트·SID/서비스·다중주소)를 전부 `databases.json`으로 옮긴다는 뜻이므로, 그 정보를 담을 명시적 자리가 필요 → **옵션 C 권고.**

### oracle.env 역할 1 — TNS_ADMIN 유도 대체

- 옵션 A: `TnsAdminInitializer` 즉시 삭제 + 프로퍼티 제거 — 가장 깨끗하지만 전환 끝나기 전엔 롤백 수단 없음
- 옵션 B(권고, 과도기): 클래스는 유지하되 `deriveFromOracleHome()`만 삭제 — `tns-admin`이 명시되어 있으면 설정, 없으면 아무것도 안 함. `oracle.env` 의존은 즉시 끊기고 롤백 수단(alias 접속)은 남음

**권고: B → (전환 완료 확인) → A.**

구현 주의: `dbagent.oracle-env-path`는 **기본값 없는 필수 프로퍼티**라 필드만 남기고 프로퍼티를 지우면 기동 자체가 실패한다. 필드 2곳(`TnsAdminInitializer`, `DatabaseConfigService`) + 소스/외부/dist/폐쇄망 배포본 properties를 **동시에** 정리해야 함.

### oracle.env 역할 2 — 기본 DB fallback 대체

- 옵션 A: `databases.json`에 `"default": true` 플래그 — 현행 동작 보존하지만 fallback의 본질적 문제(숨김 DB 권한 우회 등)는 그대로 남음
- **옵션 B(권고): `resolve("")`가 null 반환** — 55개 호출부 전부가 이미 null을 안전 처리하므로 실질 변경 3곳뿐(`resolve()` 2개 오버로드 + `listAccounts()`). SYS/SYSDBA 기본 접속 경로와 숨김 DB 우회 경로가 동시에 닫힘. 단, 로그인 직후~자동선택 완료 사이 짧은 구간에 404성 응답 증가 가능 → `app.js`의 `window.currentDbId || ""` 지점에 "빈 값이면 요청 안 보냄" 가드로 완화
- 옵션 C: 첫 번째 인스턴스를 암묵 기본값으로 — 비추천(과거 사고와 성격 동일, `app.js`의 "no silent fallback to first" 정책과도 어긋남)

**권고: 옵션 B.** 운영상 기본 DB 개념이 꼭 필요하면 옵션 A를 얹되, 플래그 없으면 반드시 null(첫 인스턴스 자동 승격 금지).

### 중복/오설정 재발 방지 (사고 대책 — 전환 자체보다 중요할 수 있음)

1. **기동 시 검증**(`DatabaseConfigService.init()`): id 중복(치명), `(host,port,sid)` 동일 조합 중복(경고), name 중복(정보). 1단계 WARN 로그 + `/api/config` `warnings` 배열 → 2단계 관리자 화면 배너 → 3단계 id 중복은 기동 실패로 승격.
2. **쓰기 시 검증**: `createInstance()`는 id 중복 체크 이미 있음. `(host,port,sid)` 중복 경고 추가, `updateInstance()`에도 동일 적용.
3. **실제 DSN 가시화**: 지금은 어느 로그에도 실접속 문자열이 안 남음(과거 사고 원인 규명이 늦어진 핵심 요인). 풀 생성 시 INFO 1회 `oracle-<id> -> 10.x.x.11:1521:ORCL1 (user=...)` 로그 + 관리자 화면에 계산된 DSN 표시(비밀번호 제외).
4. **접속 대상 실검증**(2단계 권장): `expected_instance_name` 필드를 두고 최초 접속 성공 시 `select instance_name, host_name from v$instance`를 대조해 다르면 WARN/비활성화. **이름/설정이 중복돼도 실제로 붙은 DB가 다르면 즉시 잡히는 유일한 수단.**

### RAC 대응

**확정(오케스트레이터 확인, 2026-09-18)**: 폐쇄망 대상은 **RAC DB 4개(각 DB를 인스턴스별로 개별 등록) + 싱글 인스턴스 DB 1개 = 총 8개 접속 항목**. 기존 `databases.json`의 8개 Oracle 엔트리 구조와 정확히 일치 — 지금까지도 사실상 "노드별 개별 등록" 방식으로 운영되어 온 것으로 보인다.

판별 기준(참고용, 이미 확정됨): `ADDRESS` 2개 이상/`LOAD_BALANCE`·`FAILOVER` 지정, `SID` 대신 `SERVICE_NAME`, SCAN 이름(`-scan` 접미), `(FAILOVER_MODE=...)`(TAF) 존재.

RAC 대응 선택지:
1. **노드별 개별 등록**(`ORCL1_n1`, `ORCL1_n2`): 순수 host:port:sid로 가능. 관리 항목이 늘고 노드 장애 시 해당 항목이 down으로 보임(알림 정책 조정 필요).
2. **`descriptor` 모드**: 전체 DESCRIPTION 문자열을 그대로 담아 thin 드라이버에 전달 — tnsnames.ora 없이 다중주소/페일오버 추상화를 그대로 흡수하는 유일한 안. JSON 가독성/오타 위험 상승.
3. **SCAN + service_name(EZ Connect)**: `//scan-host:1521/ORCLSVC`, DNS가 3개 IP로 물려있으면 성립. 가장 짧지만 DNS 의존이 생겨 "순수 IP" 목표와 일부 상충.

**왜 service_name이 아니라 SID(노드별 개별 등록)가 이 앱에는 더 맞는가 (2026-09-18 추가 논의)**:

일반적으로 RAC 접속의 "정석"은 SERVICE_NAME(옵션 3)이다 - 여러 노드에 걸친 로드밸런싱/페일오버를 추상화해주기 때문에, 트랜잭션을 처리하는 일반 애플리케이션이라면 "어느 노드로 붙었는지" 몰라도 되고 오히려 몰라야 한다(장애 시 자동으로 살아있는 노드로 전환).

**그런데 DBAgent-Java는 그런 종류의 앱이 아니다.** 이 앱이 조회하는 `v$lock`, `v$sysmetric`, `v$osstat`, `v$session` 등은 전부 **인스턴스 로컬 V$ 뷰**다 - RAC에서 V$ 뷰는 접속한 바로 그 인스턴스(노드)의 데이터만 보여주고, 클러스터 전체를 보려면 `GV$`(Global V$, `inst_id` 컬럼으로 노드 구분) 뷰를 따로 써야 한다. 그 결과:

- **service_name으로 접속**하면 → 리스너가 그때그때 임의의 노드로 로드밸런싱 → 지금 보고 있는 게 어느 노드의 데이터인지도 알 수 없고, 나머지 노드의 데이터는 아예 안 보임(단일 노드 스냅샷을 "전체"인 것처럼 오인할 위험).
- **SID로 노드별 개별 등록**하면 → "노드 1의 CPU/락 상태", "노드 2의 CPU/락 상태"를 **결정적으로, 각각, 전부** 볼 수 있음 → 모니터링 도구 목적에 정확히 부합.

즉 3.5의 "노드별 개별 등록 시 노드 장애가 해당 항목의 down으로 보인다"는 것도 단점이 아니라 **의도된 동작**이다 - 정확히 어느 노드가 죽었는지 그 항목에서 바로 드러나는 것이 모니터링 관점에서는 오히려 장점이다.

(참고: 코드의 모든 조회를 `v$*` → `gv$*`로 바꾸면 service_name 연결 1개로도 `inst_id`별 전체 노드 데이터를 볼 수 있지만, 이는 접속 방식이 아니라 **모든 모니터링 쿼리를 재작성**해야 하는 훨씬 큰 작업이라 이번 전환 범위 밖이다.)

**최종 권고**: 노드별 개별 등록(옵션 1)을 RAC 4개 DB의 기본 방식으로 채택. `connect_mode`에 `descriptor`는 향후 확장용으로 남겨두되(다중 노드를 하나의 db_id로 묶어야 하는 별도 요구가 생길 경우 대비), 지금 전환에서는 사용하지 않는다.

### 스키마 최종안

```json
{
  "id": "patent_integ_1",
  "name": "통합DB #1",
  "db_type": "oracle",
  "connect_mode": "sid",              // "sid" | "service" | "descriptor" (생략 시 "sid")
  "host": "10.x.x.11",
  "port": 1521,
  "sid": "ORCL1",                     // connect_mode="service"면 service_name으로 해석
  "descriptor": "(DESCRIPTION=...)",  // connect_mode="descriptor"일 때만 사용
  "expected_instance_name": "ORCL1",  // 선택, 접속 후 v$instance 대조용
  "user": "dbagent_user",
  "password": "B64(...)"
}
```

`buildDsn()` 의사코드:
```
mode = connect_mode (없으면: host가 비었으면 "tns"(레거시), 아니면 "sid")
switch (mode):
  "sid"        -> host + ":" + port + ":" + sid
  "service"    -> "//" + host + ":" + port + "/" + sid      // "//" 필수(EZ Connect)
  "descriptor" -> descriptor 원문 그대로
  "tns"        -> sid (레거시 경로, 5단계에서 제거)
```
`service` 모드는 현재 `"jdbc:oracle:thin:@" + dsn`으로 조립하므로 `//` 접두가 없으면 thin 드라이버가 EZ Connect로 인식하지 못함.

하위호환: `connect_mode` 없고 `host` 비어 있으면 현행 alias 동작 유지(과도기). 5단계에서 이 분기 제거, 그 상태의 Oracle 인스턴스는 기동 시 명시적 오류.

## 마이그레이션 순서 (상세)

체크리스트 형태는 별도 문서 `설계문서/oracle_env_제거_체크리스트.md` 참고. 여기서는 각 단계를 "누가 무엇을 준비/실행하는지" 기준으로 상세 기술한다.

### 0단계 — 사전 정보 수집 (코드 변경 없음, 착수 전 필수)

이 단계 없이는 1단계도 시작할 수 없다. **오케스트레이터가 폐쇄망에서 직접 확보해야 하는 준비물 5가지**:

**A. `tnsnames.ora` 정의 전문** — 폐쇄망 서버의 `%ORACLE_HOME%\network\admin\tnsnames.ora`(또는 `dbagent.oracle.tns-admin`이 가리키는 경로)에서 현재 8개 alias(`ORCL`,`ORCL2`,`SRCH1`,`SRCH2`,`PORT1`,`PORT2`,`ORCL3`,`ORCL4`) 항목 전체를 확보하고, alias별로 HOST/PORT/SID 또는 SERVICE_NAME/ADDRESS 개수(1개=단일노드, 2개 이상=RAC 의심)를 표로 정리. `LOAD_BALANCE`/`FAILOVER`/`(FAILOVER_MODE=...)` 문구 유무도 같이 확인(RAC/TAF 판단 근거).

**B. 현재(alias) 상태의 실접속 기준표** — 8개 인스턴스 각각에서 아래 쿼리 결과 수집:
```sql
SELECT instance_name, host_name, version FROM v$instance;
SELECT name FROM v$database;
```
전환 후 "같은 DB에 붙는지" 판정할 유일한 정답표 — 이것 없이 전환하면 오접속 사고가 재발해도 못 잡는다.

**C. 폐쇄망 배포본 외부 `application.properties` 확인** — `dbagent.oracle.tns-admin=...` 줄이 있는지만 확인. 없으면 1단계 착수 전 그 값부터 채워야 함(안 그러면 아직 `oracle.env`의 ORACLE_HOME 유도에 의존 중).

**D. 루트 `databases.json`의 `ORCL5` 중복 의도 확인** — "포탈 #1"/"포탈 #2"가 개발용에서 같은 sid를 쓰는 게 의도인지 방치된 실수인지 확인(실서비스 dist는 이미 `PORT1`/`PORT2`로 분리돼 있어 문제 없음).

**E. 기본 DB fallback 제거 여부 결정** — 로그인 직후 `db_id`가 빈 상태에서 화면에 의미 있는 게 그려지는지 확인. 없으면 fallback 완전 제거(권고안) 그대로, 있으면 `"default": true` 플래그 방식으로 조정.

A~E가 모두 준비되면 1단계 착수.

### 1단계 — 코드 변경 (동작 무변화, 안전)

준비물 A~E 확보 후 진행. 이 단계까지는 **실제 DB 연결 방식이 하나도 안 바뀐다** — 언제든 안전하게 롤백 가능한 지점.

- `buildDsn()`에 `connect_mode`(sid/service/descriptor) 분기 추가 — 미지정 시 현행 100% 동일 동작
- 풀 생성 시 실제 DSN을 INFO 로그로 1회 남기도록 추가(`oracle-<id> -> host:port:sid`)
- `DatabaseConfigService.init()`에 id 중복(경고)/`(host,port,sid)` 중복(경고) 검증 로그 추가
- `TnsAdminInitializer.deriveFromOracleHome()` 제거 — 준비물 C가 "명시돼 있음"으로 확인된 경우에만 진행(안 그러면 tns-admin 값부터 채우는 작업이 선행)
- 컴파일 + 로컬 도커 환경에서 기존 동작(alias 접속) 그대로 되는지 회귀 확인
- main/AIX 양쪽 동일 반영, 빌드/배포

### 2단계 — 데이터 전환 (인스턴스 단위, 점진)

인스턴스 1개씩, 중요도 낮은 것부터: **TEST 그룹 → 검색DB(SRCH1/2) → 포탈(PORT1/2) → 통합DB(가장 중요, 맨 마지막)**.

각 인스턴스마다:
1. `databases.json.bak-YYYYMMDD` 백업(기존 관례)
2. 해당 항목에 `host`/`port`/`sid`(준비물 A 표 기준 실제 값) 채우기
3. `GET /api/pool/test?db_id=...` 호출해 접속 성공 확인
4. 접속 성공 시 `v$instance`/`v$database` 결과를 준비물 B 기준표와 대조 — **다르면 즉시 중단하고 host 재확인**(조용히 다른 DB에 붙는 게 가장 위험한 케이스)
5. 문제없으면 다음 인스턴스로

롤백: 문제 생기면 해당 인스턴스의 `host`만 다시 비우면 즉시 alias 모드로 복귀(1단계에서 alias 경로를 남겨뒀기 때문).

**주의**: 로컬 도커는 alias 8개가 전부 같은 XE로 매핑돼 있어 이 단계의 실습/리허설만 가능하고 실검증은 불가능하다. 실제 검증은 반드시 폐쇄망에서.

### 3단계 — tnsnames.ora 의존 제거

8개 전부 2단계 통과 확인 후 `dbagent.oracle.tns-admin` 값을 비움(프로퍼티 자체는 유지 — 롤백용). 며칠 정상 운영 확인(alias 폴백 코드는 아직 유지).

### 4단계 — `oracle.env` 제거

- `resolve("")` → null 반환으로 변경, `listAccounts()`의 blank 분기 정리(실질 3곳)
- `app.js`의 `window.currentDbId || ""` 요청 지점에 "빈 값이면 요청 안 보냄" 가드 추가
- `oracle.env` 파일 삭제 — **루트 + dist + 폐쇄망 배포본, 3곳 다 수동 삭제 필요**(skip-worktree라 git으로 안 전파됨, 오케스트레이터가 직접 삭제)
- `dbagent.oracle-env-path` 프로퍼티 + 관련 `@Value` 필드 동시 제거(미제거 시 기동 실패)
- `가이드/IMPLEMENTATION.md`, `dist/application.properties.sample` 문서 갱신

### 5단계 — alias 경로 완전 삭제 (최종 정리)

- `buildDsn()`의 `"tns"` 레거시 분기 제거
- `TnsAdminInitializer` 클래스 삭제, `dbagent.oracle.tns-admin` 프로퍼티 삭제
- `host` 빈 Oracle 인스턴스는 기동 시 명시적 오류로 처리
- `db-mgmt.html` 라벨을 "Host (필수)"로 수정 + `connect_mode` 드롭다운 추가
- `expected_instance_name` + `v$instance` 실접속 검증 로직 도입(사고 재발 방지 최종 장치)

**배포·운영 주의**: `application.properties`/`oracle.env`/`databases.json`은 git skip-worktree 대상이라 서버에서 직접 수정해야 하고 파일 "삭제"는 git으로 전파되지 않음 — 배포 절차서에 수동 삭제 단계 명시 필요. 회사 PC는 pull 전 백업 원칙 있으니 4단계 배포 시 특히 주의. 모든 단계는 [[dual-project-sync]] 원칙대로 DBAgent-Java/DBAgent-Java-AIX 두 프로젝트 동일 반영 필요.

## 리스크 / 트레이드오프

**잃는 것**:
1. "tnsnames.ora 한 파일만 고치면 네트워크 이전 끝"이라는 운영 편의 — 대신 화면으로 수정 가능해진다는 점은 개선이지만, DB가 수십 개로 늘면 IP 대역 변경 시 일괄 치환 도구 필요.
2. alias 뒤에 숨겨져 있던 다중 주소/페일오버 추상화 — `descriptor` 모드로 흡수 가능하나 JSON 긴 문자열의 가독성/오타 위험.
3. 이름 기반 간접 참조 — IP 직접 박으면 IP 변경 시 전수 수정 필요. 완화책: 사내 DNS 있으면 IP 대신 호스트명 사용("순수 IP"가 아니라 "tnsnames.ora 제거"가 목적이면 이쪽이 안전).

**리스크**:

| 리스크 | 심각도 | 완화책 |
|---|---|---|
| SID와 SERVICE_NAME 혼동 지정 | 낮음 | ORA-12505/12514로 즉시 실패(조용한 오접속 아님). 0단계 정답표로 사전 차단 |
| SID는 맞는데 실제로는 다른 노드/서버 | **높음** | 조용히 성공하므로 가장 위험. `expected_instance_name` + v$instance 대조가 유일한 근본 대책 |
| 리스너의 valid_node_checking 등 보안 정책이 직접 접속 차단 | 중간 | 폐쇄망에서 인스턴스 1개로 먼저 검증 |
| RAC인데 단일 노드 직결로 전환 | ~~중간~~ 해소 | RAC 4개+싱글 1개=8개 구조 확정(2026-09-18), 노드별 개별 등록(옵션 1)으로 채택 — V$ 뷰 특성상 오히려 이 방식이 모니터링 목적에 더 맞음(RAC 대응 절 참고) |
| fallback 제거로 로그인 직후 404성 응답 증가 | 낮음 | app.js 빈 db_id 가드 |
| 로컬 도커에서는 전환 검증 불가 | 중간 | alias 8개가 전부 XE 하나로 매핑되어 로컬은 "코드 회귀 없음"만 검증, 실매핑은 폐쇄망에서만 |
| `dbagent.oracle-env-path` 필드/프로퍼티 비동기 제거로 기동 실패 | 낮음 | 기본값 없는 @Value임을 인지하고 동시 제거 |

## 확인이 필요한 미결 사항

1. **폐쇄망 `tnsnames.ora`의 8개 alias 정의 전문(HOST/PORT/SID)** — RAC 여부는 확정됨(RAC 4개+싱글 1개), 남은 건 각 인스턴스의 실제 접속값. 오케스트레이터가 확보 예정(2026-09-18). 이것 없이는 1단계 이상 진행 불가.
2. **루트 `databases.json`의 `ORCL5` 중복(포탈 #1/#2)이 의도된 것인지** — `dist`(실서비스)는 `PORT1`/`PORT2`로 분리돼 있어 개발본만 도커 alias 재사용으로 중복된 상태로 보임.
3. **기본 DB fallback을 제거(옵션 B)해도 되는지** — 빈 db_id 상태에서 실제로 의미 있는 화면이 그려지고 있는지 확인 필요.
4. **폐쇄망 배포본 외부 application.properties에 `dbagent.oracle.tns-admin`이 명시되어 있는지** — 비어 있으면 현재 폐쇄망은 여전히 ORACLE_HOME 유도 경로로 동작 중이라 1단계에서 명시값 선행 필요.

## 최종 권고 요약

`connect_mode` 필드 도입 + `TnsAdminInitializer`의 ORACLE_HOME 유도 제거 후 최종 삭제(B→A) + `resolve("")` null화를 5단계로 점진 적용하되, **0단계의 "alias ↔ 실제 인스턴스 정답표" 수집을 반드시 선행**할 것. 전환 자체는 기술적으로 어렵지 않다(코드는 이미 절반 준비되어 있고 호출부 방어도 완비) — 진짜 리스크는 "붙긴 붙었는데 다른 DB"를 검출할 수단이 현재 전무하다는 점. 최종 결정, 특히 기본 DB fallback 제거 여부는 실제 운영 화면 동작을 아는 사용자 몫.

## 참고 파일 경로

- `src/main/java/com/dbagent/oracle/OracleConnectionPoolManager.java` (buildDsn 144~151, createPool 153~178)
- `src/main/java/com/dbagent/oracle/DatabaseConfigService.java` (resolve 60~104, listAccounts 112~129, loadOracleEnvFallback 219~250, createInstance 273~316)
- `src/main/java/com/dbagent/oracle/TnsAdminInitializer.java`
- `src/main/java/com/dbagent/oracle/TargetDbConfig.java`
- `src/main/java/com/dbagent/oracle/DbConfigAdminController.java`
- `src/main/java/com/dbagent/oracle/PoolTestController.java` (인증 누락 발견)
- `src/main/java/com/dbagent/auth/AuthService.java` (canAccessDb 173~181)
- `src/main/java/com/dbagent/rdb/RdbConnectionPoolManager.java` (IP 직결 선례)
- `src/main/resources/static/app.js` (462, 669, 697행 부근)
- `src/main/resources/static/db-mgmt.html` (163행 host 필드)
- `databases.json` / `dist/databases.json`
- `oracle.env` / `dist/oracle.env`
- `src/main/resources/application.properties` / 루트·dist `application.properties`
- `가이드/IMPLEMENTATION.md` (30, 32, 101행 — 문서 갱신 대상)
- `설계문서/DB커넥션설정_개선검토_2026-09-15.md` (선행 검토)
