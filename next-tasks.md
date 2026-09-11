# 다음 작업 정리

> 작성 2026-09-07 · 최종 갱신 2026-09-11
> 항목별 상태를 제목에 표시한다(**완료** / **불필요** / 표시 없으면 미착수).
> 대상: `DBAgent-Java` / `DBAgent-Java-AIX` (2026-08-26 확정 원칙 — 두 프로젝트는 항상 같이 진행)

---

## 0. 필수 진행 사항 (2026-09-07 지시) — **전부 완료 (2026-09-11)**

세션 상세정보가 실제와 다르게 나오는 문제. 데이터 정확성 이슈라 UI 개선보다 우선했다.
0.1 의 원인이 0.3(성능이력조회/Lock 트리)까지 같이 설명해 줘서, 서버 수정 한 번으로 세 항목이
함께 해결됐다.

### 0.1 [버그] 트레이스 그래프 드래그 → 세션 클릭 시 엉뚱한 쿼리가 나온다 — **완료**

**증상 (사용자 보고):** Current Session 의 트레이스 그래프에서 드래그하면 해당 세션들이 팝업
목록으로 뜬다. 그 목록의 `command` 항목은 UPDATE 로 표시되는데, 정작 그 세션을 클릭해서 상세정보를
열면 전혀 상관없는 SELECT 쿼리가 나온다.

**근본 원인 (확인됨):** `MonitorService.getSessionQuery()` 가 클라이언트가 넘긴 `sql_id` 를
사실상 무시하고 있었다. `sid` 가 살아있는 세션이면 `v$session` 을 다시 조회해 **그 세션이 지금
실행 중인(또는 직전) SQL** 로 무조건 덮어썼다(`sSqlId = rowSqlId != null ? rowSqlId : sSqlId`).
드래그 팝업이 넘기는 `sql_id` 는 드래그 시점의 스냅샷인데, 클릭 시점에 서버가 "지금" 상태로
재조회하니 그 사이 세션이 다음 SQL 을 실행했다면 다른 쿼리가 나올 수밖에 없었다.

여기에 더해, 세션 행에 `data-serial` 이 아예 없어서 `/api/session_query` 도 `sid` 만으로
`v$session` 을 조회했다 — Oracle 은 SID 를 재사용하므로, 원래 세션이 이미 끊기고 다른 세션이 같은
SID 를 물려받은 경우 완전히 무관한 세션의 SQL 을 보여줄 수 있는 구조였다(두 원인이 겹쳐서 증상을
더 키웠다).

**수정 내용:**
- `MonitorService.getSessionQuery()` 에 `serial#` 파라미터를 추가해 `WHERE sid=? AND serial#=?`
  로 세션 정체성을 확인. 클라이언트가 이미 `sql_id` 를 갖고 있으면(드래그 팝업/이력조회/Lock 트리
  등 스냅샷 기반 클릭) **그 값을 그대로 신뢰**하고 v$session/ASH 로 덮어쓰지 않는다. `sid` 만 있고
  `sql_id` 가 없는 라이브 행 클릭은 기존처럼 "현재 상태"를 그대로 보여준다(의도된 동작이라 유지).
  plan 조회용 `child_number` 는 v$sql 에서도 보완 조회하도록 추가.
- `MonitorController`, `session-list.html`, `session-detail.html`, `app.js`(전역 클릭 위임 +
  세션 행이 렌더링되는 6곳 전부: Dashboard/Current Session/Active Transaction/Parallel
  Session/Lock Holder-Waiter Tree/성능이력조회)에 `serial` 전달 경로 추가.
- `app.js` 캐시버스터 `v=96 → v=98`(0.2 수정 포함).
- `DBAgent-Java`, `DBAgent-Java-AIX` 양쪽 다 적용, 빌드 확인, `dist`(8006)/`dist`(8007) 재기동
  검증, `dist-aix` 는 jar 교체만(폐쇄망 전용, 로컬 미기동 원칙 유지).

### 0.2 [개선] 드래그 선택 정확도 — **완료**

**원래 가설(캔버스 스케일/DPR, 차트 패딩, 스크롤 오프셋)은 전부 이미 정상 처리 중이었다** —
`scatterPointerToLocal()` 이 zoom 스케일을 이미 보정하고, `getBoundingClientRect()` 는 스크롤을
자동 보정하며, Chart.js 가 padding 을 스케일 내부에서 처리한다.

**실제 원인:** Current Session 의 Trace 차트는 폴링 주기(기본 5 초)마다 x 축 시간창을 앞으로
밀며 `chart.update()` 로 다시 그린다. 드래그 중(마우스다운~마우스업, 수 초 소요 가능)에도 이
폴링이 멈추지 않아 화면의 점들이 드래그 도중 옆으로 밀렸다. `mouseup` 의 좌표→값 변환은 **그
순간의 차트 축 상태** 를 기준으로 계산하므로, 이미 밀린 뒤라 사용자가 본 박스 위치와 실제 선택된
데이터가 어긋났다.

**수정 내용:** `window.scatterDragActive` 플래그를 추가해 드래그 중에는 해당 차트의 시각적
갱신(축 이동 + `update()`)만 멈추고, 데이터 수집(`scatterDataPoints`)은 계속 진행 — 드래그가
끝나면 다음 폴링에서 한 번에 따라잡는다. 성능이력조회 탭은 폴링이 아니라 "조회" 버튼으로만
갱신되는 정적 데이터라 이 문제가 없어 손대지 않았다. 두 프로젝트 모두 적용.

### 0.3 [점검] 오라클 화면의 세션 상세정보 조회가 정확한지 전수 점검 — **완료**

Dashboard / Current Session / 성능이력조회 / Lock Holder-Waiter Tree 모두 같은
`session-list.html → session-detail.html → /api/session_query` 경로를 공유한다는 걸 확인했다.
0.1 의 서버 수정(클라이언트 sql_id 신뢰 + serial# 확인) 하나로 네 화면 전부 해결됐다 — 별도 수정
불필요.

### 0.4 [점검] RDB 화면의 세션 상세정보 조회 점검 — **불필요 (조사 결과 해당 없음)**

MySQL / MariaDB / PostgreSQL / MS SQL Server / CUBRID 5개 엔진의 `getSessionDetail()` 을 모두
확인했다. RDB 화면에는 애초에 Oracle 의 드래그-선택/스냅샷 팝업 같은 기능이 없다 — 행 클릭 시
바로 `session_id` 로 라이브 조회하고, 세션이 이미 끝났으면 `found:false`("이미 종료되었습니다")
로 정상 처리한다. 이건 Oracle 의 "라이브 행 클릭"과 같은 구조이며, 그 경로는 원래도 문제없다고
판단한 패턴이다. 즉 0.1 이 고친 "클라이언트 스냅샷을 서버가 무시" 하는 결함 자체가 RDB 쪽엔
존재하지 않는다.

> 수정은 `DBAgent-Java` / `DBAgent-Java-AIX` **양쪽에 반영**했다(2026-08-26 확정 원칙).
> monitor / rdb 는 동기화 대상이다(동기화 제외는 `com.dbagent.aidba` 뿐).

---

## 1. 설정 정리 후속 (2026-09-07 작업에서 파생)

`src/main/resources/application.properties`에 누락돼 있던 `@Value` 키 6개를 주석과 함께 채워 넣었다
(`dbagent.oracle.read-timeout-ms`, `dbagent.rdb.connect-timeout-ms` / `read-timeout-ms` / `pool.min-idle` /
`pool.max-size`, `sqltuning.api.timeout-ms`). 값은 전부 코드 기본값과 동일해 **동작 변화는 없다.**
여기서 파생된 후속 항목들:

### 1.1 재빌드 + dist 재배포 — **완료 (2026-09-07)**

양쪽 `mvn clean package` 후 배포 3곳(`DBAgent-Java\dist`, `DBAgent-Java-AIX\dist`,
`DBAgent-Java-AIX\dist-aix`) 모두 교체하고 SHA256 일치를 확인했다. 8006 / 8007 재기동 후
HTTP 200 및 `err.log` 무에러 확인. `dist-aix`는 폐쇄망 전용이라 로컬 기동 검증은 생략(jar만 교체).

> `dist/application.properties`는 **수정하지 않았고, 할 필요도 없다.** Spring Boot는 jar 옆의 외부
> `application.properties`를 부분 오버라이드로 취급해, 거기 없는 키는 번들 파일에서 폴백한다.
> 환경별로 실제 값이 다른 것(포트, `tns-admin`, 모델명 등)만 두는 현재 형태를 유지할 것 —
> 전체 키를 복사하면 같은 값을 두 곳에서 관리하게 되어 drift만 생긴다.

### 1.2 `DBAgent-Java-AIX` 에 동일 정리 적용 — **완료 (2026-09-07)**

AIX 쪽 `src/main/resources/application.properties`에도 누락 키를 채웠다. AIX는 **7개**가 빠져 있었다 —
메인과 같은 5개(`dbagent.oracle.read-timeout-ms`, `dbagent.rdb.*` 4개)에 더해 **`sqltuning.*` 섹션이
통째로 없었다**(`sqltuning.api.url`, `sqltuning.api.timeout-ms`). `@Value` 기본값이 있어 동작은 했지만
문서화가 안 된 상태였다.

AIX는 값이 다른 항목이 있어 그대로 두었다.
- `aidba.errors-db-path` — H2(`data/oracle_errors`) vs 메인 SQLite(`data/oracle_errors.db`)
- `spring.datasource.*` — H2 (`username`/`password` 존재) vs 메인 SQLite
- `aidba.ollama.timeout-ms` — **AIX에만 있는 키** (아래 1.3 참조)

### 1.3 메인 `OllamaChatService` 타임아웃을 설정으로 분리 — **완료 (2026-09-07)**

메인의 `.timeout(Duration.ofSeconds(300))` 하드코딩을 `@Value("${aidba.ollama.timeout-ms:300000}")`
로 바꾸고 `application.properties`에도 명시했다. **기본값을 300000ms로 둬서 기존 동작은 그대로다.**

`aidba`는 두 프로젝트 간 동기화 제외 영역이라 AIX(30000ms)와 값이 다른 것은 의도된 상태로 남긴다.
이제 원격 GPU 서버 + 대형 모델로 옮겨 응답 시간이 달라져도 재빌드 없이 조정할 수 있다.

### 1.4 `aidba.ollama.url` / `aidba.ollama.model` 에 `@Value` 기본값 추가 — **불필요 (전제 오류)**

**이 항목의 원래 근거는 틀렸다.** "AIX 서버에 `application.properties`를 새로 만들면서 이 두 줄을
빠뜨리면 앱이 안 뜬다"고 적었으나, 실제로는 그렇지 않다:

- jar 옆의 외부 `application.properties`는 기본 위치를 **대체하지 않고 추가**된다. 거기 없는 키는
  jar 안 `BOOT-INF/classes/application.properties`에서 폴백한다.
- `start-aix.sh`는 `--spring.config.location` 없이 평범한 `nohup java -jar` 로 실행한다(65번 줄).
  즉 번들 properties가 항상 로드된다.
- 실증: 현재 `dist/application.properties`에는 `dbagent.databases-config`,
  `dbagent.oracle-env-path`, `aidba.errors-db-path`가 **없는데도** 앱이 정상 동작한다.

기본값 없는 키는 이 둘 외에도 `aidba.errors-db-path`, `dbagent.databases-config`,
`dbagent.oracle-env-path`가 있으나 같은 이유로 모두 문제되지 않는다. 번들 properties에서 키를
지우거나 `--spring.config.location`으로 기본 위치를 대체하는 경우에만 터지는데, 둘 다 현재 없다.

> `SqlTuningService`의 "커밋 대상에서 빠져 있다"는 주석(2026-09-04)도 지금은 맞지 않는다 —
> `src/main/resources/application.properties`는 커밋 대상이다(`git check-ignore` 무시 없음,
> 이력도 `934f377`까지 이어짐). 그 주석 자체를 고칠지는 별도 판단 필요.

### 1.5 AIX `dist/application.properties` 에 `aidba.*` 추가 — 선택, **사용자 결정 대기**

로컬 8007에서도 gemma로 테스트하려면 `aidba.ollama.url` / `aidba.ollama.model` 두 줄이 필요하다.
현재는 없어서 번들 기본값(`qwen2.5:3b`)으로 동작한다. 메인 `dist/`에는 이미
`aidba.ollama.model=gemma4:31b-cloud`가 들어가 있다.

임의로 넣지 않았다 — `-cloud` 모델은 프롬프트가 Ollama 클라우드로 전송되므로, 8007에도 적용할지는
판단이 필요한 사안이다.

---

## 2. 문서 동기화

`sql-tuning-rag-design.md`(SQL 튜닝 RAG 전환 설계안)를 `DBAgent-Java-AIX` 쪽에도 복사할지 결정.
`sqltuning`은 두 프로젝트 동기화 대상이라 문서도 양쪽에 두는 편이 일관되다. 설계안대로면 Java
코드는 안 바뀌므로 코드 동기화 부담은 없다.

---

## 3. 확인 필요 (답변 대기)

- **폐쇄망 AIX 서버에 `admin` 외 계정이 있는지.** tar 전체 이관 방식에서는 `users.mv.db`가 씨드로
  덮이고 비밀번호를 재설정하는 흐름인데, 다른 계정이 있다면 이관 때마다 같이 초기화된다.
- **보유 튜닝 케이스의 형태(엑셀/문서/DB)와 건수.** RAG 설계안 §10 미결정 사항 — 정형화 스키마와
  벡터 스토어 선택(브루트포스 vs `sqlite-vec`)이 여기에 달려 있다.

---

## 4. 별도 문서로 진행 중인 건

| 문서 | 내용 | 상태 |
|---|---|---|
| `sql-tuning-rag-design.md` | 파인튜닝 → RAG 전환 설계 (8010 뒤 배치, Java 변경 0) | 제안, 미확정 |
| 〃 §7 | 폐쇄망 GPU 서버 연동 시 확인사항 6건 | GPU 서버 준비 시 적용 |
| 〃 §8 | MCP 노출 (선택, 후순위) | 조건부 |
| (문서 없음) | RDB 대시보드 UI 개편 + 잔여 지표(접속 고갈·장기 트랜잭션·버퍼 추이·temp/log 용량) | 기존 계획, 별건 |

---

## 5. 조치 불필요 (기록용)

- `dist-aix/data/oracle_errors.mv.db`의 2026-09-07 08:13 변경 — 사용자가 오전에 직접 데이터를
  확충·변환해 반영한 것. 이상 징후 아님. 이 파일은 **개발기 쪽이 정본**이라 폐쇄망으로 반출하는
  방향이 맞다(앱은 읽기 전용으로만 사용).
- `cubrid-server` 컨테이너는 사용자 지시로 내려둔 상태 유지.
