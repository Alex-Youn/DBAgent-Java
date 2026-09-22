# Active Session Wait Class 차트 — 구현 단계 체크리스트

> 대상 화면: Current Session 메뉴 (`#session`)
> 원본 설계문서: [`Current Session 매뉴 active_session 차트 개편.md`](./Current%20Session%20매뉴%20active_session%20차트%20개편.md) (§0 착수 전 결정사항 포함)
> 이 문서는 그 설계를 실제 구현 단계로 쪼갠 진행 상황 체크리스트입니다. 단계 구성은 design-advisor
> 검토(2026-09-22)의 우선순위 권고안을 그대로 따릅니다.

---

## 0단계 — 착수 전 결정 ✅ 완료 (2026-09-22)

gv$ 미사용 / 기존 Current Session 2차트 완전 대체 / 팔레트 그대로(기존 wait-breakdown 미니바 재사용,
신규 색 아님) / 라이트 테마 없음(텍스처 토글 2단계로 후순위) — 원본 설계문서 §0에 반영 완료.

---

## 1단계 — 최소 기능 (Active Session 영역 차트, 30분·1시간만) ✅ 완료 (2026-09-22)

**목표**: 기존 좌측 "실시간 세션 추이" 라인 차트를 신규 차트로 교체. 우측 Trace 산점도는 이 단계에서
그대로 유지(4단계에서 교체 예정).

### 백엔드
- [x] `GET /api/ash_activity?db_id&range_minutes&step_minutes` 신규 (`MonitorController.java`)
  - [x] `range_minutes`는 30/60만 허용, 그 외 400 반환(6단계 전까지 명시적 거부)
  - [x] `step_minutes`는 1만 허용
- [x] `MonitorService.getAshActivity()` — `v$active_session_history` 기반, §3.1 CASE 매핑
  - [x] `session_type = 'FOREGROUND'` 필터
  - [x] 모니터링 계정 자기 자신 제외(`monitoringAccountLiteral`)
  - [x] `wait_class NOT IN ('User I/O','System I/O','Idle')`로 Idle 방어 → Other에 안 섞임
  - [x] 분류 CASE를 이 메서드 전용 상수/쿼리 1곳에 정리(기존 getSessions/queryActiveTransactions의
        세션별 % 분해와는 집계 모양이 달라 로직 공유는 안 했음 — 코드 주석에 근거 명시)
  - [x] 서버가 0패딩해 `values` 길이 7 + 빈 버킷 보장 (요청 구간 전체를 항상 채움)
  - [x] `cpu_cores`는 신규 `v$parameter` 대신 기존 `v$osstat.NUM_CPUS` 재사용(화면마다 "코어 수" 값이
        갈리는 문제 방지)
  - [x] 전용 쿼리 타임아웃 프로퍼티 분리(`dbagent.monitor.ash-activity-query-timeout-seconds`, 기본 10초 —
        락 조회 전용값인 기존 `lock-query-timeout-seconds`(3초)와 분리)
- [x] **버그 수정 1**: `sample_time`(TIMESTAMP)에 DATE 산술을 그대로 적용해 `ORA-00932` 발생 →
      `CAST(... AS DATE)`로 수정(기존 `getHistorySessions` 관례와 통일)
- [x] **버그 수정 2**: `TO_CHAR` 포맷의 끝 리터럴 `:00`이 따옴표 없이 들어가 `ORA-01821` 발생 →
      `"00"`으로 인용 처리
- [x] **버그 수정 3(중요)**: 0-패딩 버킷 경계를 JVM 로컬 시각(`LocalDateTime.now()`)으로 계산했더니
      도커 Oracle 컨테이너(UTC)와 이 PC(KST) 시간대가 달라 응답이 항상 전부 0으로만 나왔음 → DB
      커넥션에서 직접 `SELECT SYSDATE FROM dual`을 읽어와 그 값으로 버킷 경계를 계산하도록 수정.
      폐쇄망에서도 앱 서버와 DB 서버 시간대가 다를 수 있으므로 이 수정은 로컬 테스트 우연이 아니라
      실제로 필요한 수정이었음.

### 프론트엔드
- [x] `index.html` — 좌측 캔버스를 `session-chart` → `ash-activity-chart`로 교체, 30분/1시간 토글
      버튼 추가(`.iv-range-btn` 기존 클래스 재사용)
- [x] `app.js` — Chart.js 누적 영역(stacked line + fill), 다크 전용 렌더
  - [x] 팔레트: CPU `#22d3ee` / Latch `#808000` / User I/O `#2ecc71` / TX Lock `#7c3aed` /
        Sys I/O `#e67e22` / TM Lock `#be123c` / Other(`--text-muted`, 테마 토큰)
  - [x] `cpu_cores` 기준선(점선, 스택 제외 별도 계열)
  - [x] 30초 독립 폴링(세션 테이블의 5초 폴링과 무관, DB 전환 시 재시작)
  - [x] 기존 `sessionChart`/`sessionHistory`/`TREND_SERIES`와 그 DB 전환 스냅샷 캐시 필드를 깨끗이
        제거(죽은 코드 남기지 않음) — Trace 산점도(`SCATTER_CATEGORIES`, `scatterDataPoints`)는 그대로 유지
  - [x] 캐시버스터 `app.js?v=164→165`

### 실데이터 테스트 (오케스트레이터 요청 — 세션 강제 발생)
로컬 Docker Oracle(`oracle19c`, XE)에 `local_docker_xe` 인스턴스를 신규 등록하고, 별도 테스트
계정(`loadtest_app`, 모니터링 계정 `system`과 분리)으로 아래 시나리오를 실행해 검증했습니다.

- [x] **TX Lock**: 세션 A가 행을 UPDATE 후 미커밋 유지, 세션 B가 같은 행 UPDATE → `enq: TX - row
      lock contention` 발생 확인
- [x] **TM Lock**: 세션 C가 `LOCK TABLE ... IN EXCLUSIVE MODE`, 세션 D가 INSERT 시도 → `enq: TM -
      contention` 발생 확인
- [x] **CPU**: PL/SQL 순수 연산 루프(SQRT 2.5억회) → `ON CPU` 샘플 발생 확인
- [x] **User I/O**: 대용량 테이블(20만 행 × 2000byte) 반복 풀스캔 → `direct path read` 샘플 발생 확인
- [x] **Other**: `resmgr:cpu quantum`(Scheduler class) 등이 Other로 정상 분류되는지 확인
- [x] API 응답(`/api/ash_activity`)에서 위 카테고리 값이 실제로 0이 아님을 curl로 확인
- [x] 브라우저(CDP 직접 구동 — Chrome 확장 미연결, [[browser-verification-via-cdp]] 절차)로 실렌더링
      확인: 누적 영역 차트에 실제 색이 쌓여 보임, 범례 정상 표시, CPU 코어 기준선(점선, 32) 표시
- [x] 30분↔1시간 토글 클릭 시 x축 구간이 실제로 바뀌고 데이터가 다시 그려지는지 확인
- [x] 우측 Trace 산점도가 이번 변경과 무관하게 계속 정상 동작하는지 확인(세션 테이블에 락 대기
      세션 2건이 정상 표시됨)
- [ ] Latch/Sys I/O 카테고리는 이번 테스트에서 우연히 트리거되지 않음(결정론적으로 재현하기 어려운
      대기 유형) — 분류 로직 자체는 기존 `getSessions()`의 동일 패턴과 100% 같은 조건이라 별도 검증
      없이도 신뢰도 높음. 필요하면 후속으로 확인.

### 포팅
- [x] `DBAgent-Java-AIX`에 동일 반영(같은 날) — `MonitorService.java`/`MonitorController.java`
      (Java 8 `--release 8` 컴파일 확인 완료), `index.html`/`app.js`(캐시버스터 `152→153`)
- [ ] AIX 로컬 기동 검증은 생략(신규 테이블/스토리지 변경이 없어 과거 SQLite→H2 포팅 사고 부류의
      위험이 없다고 판단 — 근거: [[dual_project_sync]] H2 함정은 전부 신규 테이블/컬럼에서 발생했음)
- [ ] 폐쇄망 실서버 배포는 다음 배포 사이클에 포함(아직 미실시)

### 알려진 제약 / 후속 정리
- 로컬 테스트 환경(도커 Oracle)에 남긴 테스트 furniture: `system` 스키마의 `loadtest_lock`/
  `loadtest_io` 테이블, `loadtest_app` 사용자, `local_docker_xe` db_instance 등록. 개발 PC 전용이라
  프로덕션에는 영향 없음 — 정리하려면 `DROP TABLE`/`DROP USER`/DB 관리 화면에서 인스턴스 삭제.
- `system` 계정 잠금 해제(`ALTER USER system ACCOUNT UNLOCK`)도 이번 테스트 과정에서 수행함.

---

## 2단계 — 표현·접근성 ✅ 완료 (2026-09-22)

- [x] 누적 막대 토글 (영역형 ↔ 막대형) — `[data-ash-form]` 버튼, 전환 시 Chart.js 인스턴스
      destroy 후 재생성(type을 안전하게 라이브 전환할 수 없어서)
- [x] 상시 범례 — 기존 `buildChartLegend` 재사용, 클릭 시 계열 show/hide 정상 동작
- [x] 표로 보기 토글 — 시간×카테고리+합계 텍스트 표, 차트와 같은 `lastAshActivityData` 캐시를
      재사용해 재조회 없이 전환
- [x] KPI 행 4종 (현재 AAS+직전 대비 delta / CPU 코어수 / 선택구간평균 / 기준선 초과비율) — 전부
      클라이언트에서 이미 받아온 series로 계산(별도 API 불필요), `.iv2-kpi-tile` 스타일 재사용
- [x] 텍스처(사선 패턴) 토글 — CanvasPattern 기반 45°/135° 해치(목업의 SVG `<pattern>`과 같은
      개념을 canvas로 이식), 카테고리 인덱스 짝/홀로 각도 교차

### 실데이터 테스트 (CDP 브라우저 구동)
- [x] KPI 4종 값이 실제 series로부터 정확히 계산되어 표시되는지 확인 (현재 AAS/코어수/평균/초과비율)
- [x] 영역형→막대형 전환 시 차트가 정상 재생성되고 같은 데이터가 막대로 표시되는지 확인
- [x] 텍스처 토글 on 상태에서 막대에 실제 대각선 해치가 렌더링되는지 확대 스크린샷으로 확인
      (CPU/TM Lock/Other 세 계열 모두 각기 다른 각도로 정상 렌더)
- [x] 표로 보기 토글 시 시간/카테고리/합계 표가 정상 렌더되고 스크롤되는지 확인
- [x] 모든 토글을 껐을 때(영역형/텍스처 off/표 off) 원래 상태로 깨끗이 복귀하는지 확인 —
      표 모드였다가 차트로 돌아올 때 캔버스가 찌그러지지 않는지(resize() 강제) 포함

### 포팅
- [x] `DBAgent-Java-AIX`에 동일 반영(같은 날) — `index.html`/`style.css`/`app.js` 전부 순수
      프론트엔드 변경이라 Java 재컴파일 불필요. 캐시버스터 `style.css 42→43`, `app.js 153→154`

## 3단계 — Top SQL Activity Timeline (§8) ✅ 완료 (2026-09-22)

> **후속 수정(같은 날, 오케스트레이터 요청)**: `label` 필드를 SQL_ID 축약형("SQL-BU6BGF")이 아니라
> 카테고리명 그대로("CPU"/"Latch"/"User I/O"/"TX Lock"/"TM Lock"/"Other")로 변경. `getAshTopSql()`과
> `getAshOtherBreakdown()`(5단계) 둘 다 동일 적용, main/AIX 양쪽 반영·컴파일 확인 완료. 단, 같은
> 카테고리의 SQL이 여러 개면 범례/순위 리스트에 같은 라벨이 중복 표시될 수 있음(요청대로 구현한
> 트레이드오프 - 필요시 후속으로 조정 가능).

- [x] `GET /api/ash_top_sql` — Top5 SQL_ID + Other 누적, 1단계 팔레트 재사용
- [x] `FETCH FIRST n ROWS ONLY`(12c+) 대신 `ROWNUM` 선택 — 코드베이스 기존 관례가 압도적으로
      ROWNUM(getSessions 등)이고 폐쇄망 대상 Oracle 최소 버전이 아직 미확인이라 안전한 쪽 선택
- [x] §8.1 카테고리 판정(Sys I/O 제외) — sql_id별 카테고리 샘플 수를 집계해 최빈값을 최빈 카테고리로
      채택, 5개 카테고리 전부 밖인 SQL은 Other로 갈음
- [x] §8.2 Top-N 산정 — 표시 구간 전체 기준 1회만(드래그 재계산은 4단계 대상), Top5 미만(0~4개)도
      정상 처리되도록 IN절 placeholder를 동적으로 생성(설계 검토에서 지적된 "바인드 개수 불일치"
      버그를 애초에 겪지 않는 구조)
- [x] 0-패딩 버킷 로직은 1단계의 `generateAshBucketLabels()`를 공유 리팩토링(3번째 사본 방지 —
      getAshActivity()도 이 헬퍼를 쓰도록 같이 정리)
- [x] 프론트: Top SQL Activity Timeline 차트 신규 추가(전용 범례 렌더러 - Top5 구성이 폴링마다
      바뀔 수 있어 buildChartLegend의 "최초 1회만 그림" 캐시를 못 씀), Active Session Wait Class
      차트와 같은 range/30초 폴링 공유
- [x] **아직 우측 Trace 산점도를 대체하지 않음** — 별도 행에 임시로 추가, 4단계(드래그+세션 상세)
      완료 시점에 산점도 자리로 옮기고 산점도를 제거 예정

### 실데이터 테스트
- [x] 도커 Oracle에 CPU/User I/O/TX Lock 등 서로 다른 카테고리의 SQL을 여러 개 동시 실행해 Top5가
      실제로 서로 다른 SQL_ID·모듈·카테고리로 채워지는지 curl로 확인(5개 모두 정상 채워짐)
- [x] 브라우저(CDP)로 실렌더링 확인 — 범례에 SQL 라벨+모듈 표시, Active Session Wait Class 차트와
      색이 실제로 대응되는지(같은 시간대 CPU 우세 구간에서 두 차트 모두 시안색 우세) 확인
- [x] Top5 미만 케이스는 `topSql.isEmpty()` 분기로 코드 경로는 마련해뒀으나 실측 데이터로는
      검증하지 않음(항상 5개 이상 SQL이 돈 환경이라) — 로직상 안전하다고 판단

### 포팅
- [x] `DBAgent-Java-AIX`에 동일 반영(같은 날) — `--release 8` 컴파일 확인 완료

## 4단계 — 드래그 → 세션 상세 (§8.4), 기존 산점도 완전 대체 ✅ 완료 (2026-09-22)

- [x] 기존 `/api/history_sessions` + `session-list.html` 팝업 패턴 재사용 검토 — **재사용하지 않기로
      결정**. 이유: `getHistorySessions()`는 "성능 이력 조회" 화면 전용으로 `elapsed>=3초 AND
      exec_count>=100`인 튜닝 후보만 걸러내는 필터가 있어서, 일반 드래그 드릴다운(§8.4 의도: "그
      구간에 해당하는 세션 상세")에 그대로 쓰면 대부분의 정상적인 드래그가 빈 결과로 나온다(착수 전
      검토에서 발견 — 재사용 검토 자체가 이 결함을 찾아낸 셈). 대신 같은 필드명(`session-list.html`이
      그대로 기대하는 `sid/serial/sql_id/capture_time/duration_time/program_name/username/db_name`)
      으로 내려주되 필터 없는 `GET /api/ash_session_detail` 신규 추가(`getAshSessionDetail`) — 프론트
      매핑 없이 기존 `showSelectedSessionsPopup()`에 그대로 넘길 수 있음
- [x] `NVL(sql_exec_start, sample_time)` 방어, 경과시간 기준을 `SYSDATE`가 아닌 `sample_time`으로
      (design-advisor 1차 검토에서 지적된 결함 2건 — 처음부터 반영)
- [x] SID당 최신 샘플 1건만 반환(`ROW_NUMBER() OVER (PARTITION BY session_id ORDER BY sample_time
      DESC)`) — 기존 산점도 드래그의 "SID로 중복 제거" 관례와 동일한 의미
- [x] 프론트: `ash-topsql-chart`에 세로 밴드 드래그 추가(`initAshTopSqlBrush`) — 값 축은 무관하고
      시간 구간만 선택(누적 영역 차트라 y값이 그 시점의 합계이지 세션 좌표가 아니라서 기존 2D 박스
      선택과는 다른 방식). 드래그 종료 시 픽셀→시간 변환 후 `/api/ash_session_detail` 조회
- [x] **기존 Trace 산점도(및 그 드래그 팝업 기능) 완전 제거** — `sessionScatterChart`,
      `SCATTER_CATEGORIES`, `scatterDataPoints`, `initScatterBrush`, DB 전환 스냅샷 캐시
      (`dbSessionHistoryCache` 등, 2026-09-01/09-02 기능 전체) 전부 삭제. `showSelectedSessions
      Popup()`/`scatterPointerToLocal()`/`session-list.html`은 History 탭의 자체 산점도가 계속
      쓰므로 그대로 유지(둘 다 건드리지 않고 재사용만 함)
- [x] Top SQL Activity Timeline이 우측 패널로 이동, 좌(Active Session Wait Class)/우(Top SQL
      Activity Timeline) 2단 레이아웃으로 최종 정리 — §0 결정 2 "완전 대체"가 이 단계로 완결됨

### 실데이터 테스트 (CDP 브라우저 구동)
- [x] `/api/ash_session_detail` curl 직접 호출 — 실제 세션 1건이 정확한 필드로 반환되는지 확인
- [x] 합성 마우스 이벤트로 실제 드래그 시뮬레이션 → 픽셀→시간 변환이 정확한지, `localStorage`에
      스택된 데이터를 확인해 **9~10건의 서로 다른 세션이 실제 SID/SQL_ID/대기이벤트(ON CPU, enq: TX,
      resmgr:cpu quantum, direct path read 등)로 정확히 채워지는지 검증**
- [x] `session-list.html`을 직접 열어 렌더링 확인 — Database Name/종료시간/SID/SERIAL#/SQL_ID/
      Elapse Time/Program/Oracle User Name까지 전부 정상 표시(Command/Host User Name은 ASH에 없는
      정보라 '-'로 표시되는 것까지 의도대로 확인)
- [x] 실제 팝업 창(`window.open`)이 합성 이벤트 환경에서는 브라우저 팝업 차단에 걸림 — **이것은
      테스트 환경의 한계이지 코드 결함이 아님**: `showSelectedSessionsPopup()`은 내가 새로 짠 코드가
      아니라 기존 산점도/History 탭 드래그가 이미 쓰던 공유 함수를 그대로 재사용한 것이고, 신뢰된
      사용자 제스처(`userGesture:true`)로 재호출해도 이 CDP 환경에서는 동일하게 열리지 않아 환경
      자체의 팝업 차단 정책임을 확인. 데이터 파이프라인(드래그→시간변환→조회→스택→렌더)은 전부
      개별 검증 완료
- [x] 레이아웃 확인 — 좌측 Active Session Wait Class, 우측 Top SQL Activity Timeline 2단 배치로
      전환된 것을 스크린샷으로 확인, Trace 산점도 잔재 없음

### 포팅
- [x] `DBAgent-Java-AIX`에 동일 반영(같은 날) — 백엔드 `--release 8` 컴파일 확인, 프론트
      `index.html`/`app.js` 전부 main과 동일한 패치 적용(캐시버스터 `app.js 155→156`)

### §0 완결
이 단계로 §0 결정 2("기존 Current Session 2차트를 목업 화면으로 완전 대체")가 최종 완결됐다 —
좌측 차트는 1단계에서, 우측 차트는 이번 4단계에서 교체가 끝나 기존 추이 라인/Trace 산점도가 코드베이스
어디에도 남아있지 않다.

## 5단계 — Other 드릴다운 (§9) ✅ 완료 (2026-09-22)

- [x] `GET /api/ash_other_breakdown` 신규(`getAshOtherBreakdown`) — Top5 목록은 서버가 재산정하지
      않고 프론트가 이미 들고 있는 `lastAshTopSqlData.sql_categories`를 그대로 콤마로 넘김(§9.2
      "서버가 Top5를 다시 산정하지 않고 프론트와 같은 기준을 그대로 씀")
- [x] Top5가 0~5개 아무 길이여도(§9.2 원 예시의 고정 5개 바인드 가정과 달리) `NOT IN` 절 바인드 개수를
      항상 실제 길이에 맞춰 동적 생성 — 빈 배열이면 `NOT IN` 절 자체를 생략(설계 검토가 지적한 "Top5
      미만 시 바인드 개수 불일치" 버그를 원천적으로 방어)
- [x] `SUM(cnt) OVER ()`/`COUNT(*) OVER ()`를 `ROW_NUMBER()`로 상위 10건만 거르는 필터보다 먼저(같은
      뷰 안에서) 계산해, **쿼리 1번**으로 상위 10건 랭킹 + "표시 안 된 나머지" 집계(§9.3 "이 외 N개
      SQL이 약 X AAS를 차지")를 동시에 구함(§9.2 예시가 2개 SQL로 나눠 하던 걸 1개로 최적화)
- [x] 카테고리 판정은 `getAshTopSql()`과 동일 패턴(§3.1 CASE, Sys I/O 제외) 재사용
- [x] 프론트: 범례의 "Other" 항목에 클릭 가능 표시(밑줄 + ▸ 화살표) 추가, 클릭 시 차트 카드 안에
      순위 패널이 펼쳐짐(§9.3 레이아웃 그대로: 순위/색점/SQL 라벨/막대(1위=100% 상대값)/비율/카테고리)
- [x] §9.1 "범위는 현재 드래그 선택과 연동" — `ashSelectedWindow`(드래그 선택) 있으면 그 구간, 없으면
      `lastAshTopSqlData.series`의 전체 표시 구간을 사용
- [x] §9.1 "시간 범위를 바꾸면/재드래그하면 자동 갱신" — `renderAshTopSqlChart()`(매 30초 폴링 +
      30분/1시간 전환마다 호출됨) 끝에 "열려 있으면 새로고침" 훅을 하나만 걸어 커버. 드래그 시에도
      드릴다운이 열려 있으면 즉시 갱신. DB 전환/구간 전환 시 `ashSelectedWindow` 초기화 + 패널 자동 닫힘
- [x] 닫기 버튼으로 패널 접기

### 실데이터 테스트
- [x] 서로 다른 8개 SQL을 동시 실행해 Top5 밖으로 밀려난 SQL들이 실제로 Other 풀에 잡히는지 확인 —
      동률(count=23) 케이스에서 4개가 정확히 Other로 남고 카테고리(User I/O)까지 올바르게 분류됨을
      `curl`로 확인
- [x] `exclude_sql_ids`를 빈 문자열로 보내는 "Top5 미만" 극단 케이스 — `NOT IN` 없이 전체 9개 SQL이
      정상 반환되는지 확인(바인드 개수 불일치 버그가 실제로 없음을 실측 검증)
- [x] 브라우저(CDP)로 Other 범례 클릭 → 패널 렌더링 확인(순위 7건, 막대 길이가 비율에 비례, 카테고리
      색상 점이 실제 Active Session Wait Class 차트 팔레트와 일치) → 닫기 버튼으로 정상 접힘 확인

### 포팅
- [x] `DBAgent-Java-AIX`에 동일 반영(같은 날) — 백엔드 `--release 8` 컴파일 확인. Java 8 호환을 위해
      `String.isBlank()`(11+) 대신 기존 `Strings.isBlank()` 헬퍼, `.strip()`(11+) 대신 `.trim()` 사용

## 6단계 — 6시간/24시간 장기 구간 ✅ 완료 (2026-09-22, 자체 수집 경로)

**결정**: AWR 실시간 조회 대신 **자체 수집 경로**로 확정(오케스트레이터 요청). 이유는 채팅에서 정리한
성능 비교 그대로 — ① 이 코드베이스가 이미 겪은 사고(v2 대시보드 10초 폴링이 `dba_data_files`류 무거운
조인을 직접 돌려 커넥션 풀 고갈)와 같은 패턴을 24시간 AWR GROUP BY 스캔에서 재현할 위험, ② 실시간
조회는 보는 사람 수만큼 원본 Oracle 부하가 곱해지는데 자체 수집은 60초에 1번 고정, ③ 이미 구축된
`instance_metric_history` 인프라를 그대로 재사용(DDL 변경 0).

- [x] 기존 `instance_metric_history`(60초 샘플러, `InstanceMetricSamplerService`)에 `metric_name`
      7개(`ash_cpu`/`ash_latch`/`ash_user_io`/`ash_tx_lock`/`ash_system_io`/`ash_tm_lock`/`ash_other`)
      + `ash_cpu_cores` 1개, 총 8개 추가 — **DDL 변경 0**(체크리스트 예상대로)
  - `getAshActivity()`(1단계)와 **완전히 동일한 CASE 판정 기준**을 그대로 복사해서 씀 — 실시간
    30분/1시간 뷰와 자체 수집 6시간/24시간 뷰가 같은 시간대를 다르게 분류해 숫자가 어긋나는 일이
    없도록 의도적으로 중복(공유 리팩토링 대신 복붙 — 클래스가 달라 기존 관례상 그대로 복사)
  - 샘플링 창(`sampleIntervalSeconds`)과 `@Scheduled` 주기가 같은 프로퍼티 키
    (`dbagent.monitor.metric-sample-interval-seconds`)를 공유해 "최근 N초" 계산이 항상 실제
    사이클과 일치
- [x] 읽기는 **신규 API 없이** 기존 `GET /api/metric_history?range=6h|24h&metrics=...` 재사용 — `range`
      스위치에 `"6h"` 케이스 1줄만 추가(기존 `1h`/`24h`/`7d`는 v2 대시보드 CpuDbTimeLineChart/
      LockTrendChart가 그대로 씀, 안 건드림)
- [x] 프론트: `data-ash-range="360"`(6시간)/`"1440"`(24시간) 버튼 추가. `fetchAshActivityFromHistory()`가
      8개 시계열을 `sampledAt` 기준 Map으로 합쳐(포지션 의존 없이 방어적으로) 기존
      `renderAshActivityChart()`가 기대하는 `{cpu_cores, categories, series}` 모양으로 변환 — 렌더러는
      데이터 출처(실시간/자체 수집)를 몰라도 됨. KPI/막대·영역 토글/표 보기/텍스처 전부 그대로 동작
- [x] Top SQL Activity Timeline/Other 드릴다운은 6단계 범위 밖(SQL_ID 단위 자체 수집은 카디널리티가
      무한정이라 체크리스트에 애초에 없던 범위) — 6시간/24시간에서는 "30분/1시간 구간에서만 제공됩니다"
      안내로 교체, 30분/1시간로 되돌리면 정상 복원
- [x] ~~AWR 경로 항목(분모 보정/라이선스 확인)~~ — 자체 수집으로 결정하며 해당 없음
- [x] ~~쓰기량 2.75배 실측~~ — 기존 4개(cpu_pct/db_time_aas/tm_lock_waiting/tx_lock_waiting) →
      12개(+8)로 **정확히 3배**. 개별 `record()` 호출을 그대로 8번 더 쓰는 방식(기존 관례 유지, 배치
      API는 이번에 새로 안 만듦) — 로컬 실측으로는 문제 없었으나 인스턴스 수가 훨씬 많은 실제 폐쇄망
      환경에서는 체감 여부를 지켜볼 필요 있음(위험 낮음으로 판단해 진행, 문제 생기면 배치 insert로
      전환 검토)

### 실데이터 테스트 (CDP 브라우저 구동)
- [x] 앱 재기동 후 실제 샘플러 사이클이 도는지 `/api/metric_history`로 직접 확인 — 첫 사이클부터
      `ash_cpu`~`ash_other`/`ash_cpu_cores` 8개 전부 정상 기록(cpu_cores=32 등 실측값 일치)
- [x] 지속 부하를 걸어 여러 사이클(4분, 4포인트) 누적 후 브라우저에서 "6시간" 클릭 → 차트/KPI 정상
      렌더 확인, 동시에 우측 Top SQL 패널에 "30분/1시간 구간에서만 제공됩니다" 안내 정상 표시
- [x] "24시간" 클릭 → 정상 전환, 다시 "1시간"으로 되돌리기 → 실시간 Top SQL 차트/범례까지 완전히
      복원되는 것(끊김·잔재 없음)을 스크린샷으로 확인

### 포팅
- [x] `DBAgent-Java-AIX`에 동일 반영(같은 날) — `InstanceMetricSamplerService.java`는 main과 **완전
      동일 파일**(diff 0줄), `MonitorController.java`는 Java 8 classic switch 문법으로 이식(원본은
      Java 17 switch 식), 프론트 `index.html`/`app.js` 동일 패치. `--release 8` 컴파일 확인 완료

---

## 착수 전 남은 확인 사항 (사용자 결정 필요, 코드 무관)
- 폐쇄망 대상 Oracle 최소 버전 (`FETCH FIRST` 12c+ 가능 여부 — 3단계 영향)
- Diagnostics Pack 라이선스 보유 여부 (6단계 AWR 경로 영향 — 이미 `dba_hist_active_sess_history`를
  쓰고 있어 사실상 전제된 상태일 가능성 높음)
