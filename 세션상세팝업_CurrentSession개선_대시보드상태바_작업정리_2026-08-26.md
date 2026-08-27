# 세션 상세 팝업창 + Current Session 개선 + 대시보드 상태바 확장 (2026-08-26)

`DBAgent-Java`(Java 17/Spring Boot 3.3.5, 원본)에 적용한 작업. `DBAgent-Java-AIX`(Java 8 이관본)에도 같은 세션에서 반영함 — 반영 상세는 맨 아래 "AIX 이관본 반영 내역" 참고.

> **⚠️ 작업 원칙 (2026-08-26부로 확정)**: 앞으로 이 프로젝트의 수정 작업은 **`DBAgent-Java`와 `DBAgent-Java-AIX`를 항상 같이 진행**한다. 한쪽만 고치고 끝내지 말 것 — 매번 두 프로젝트 다 반영(필요 시 Java 8 문법 치환 등 AIX 쪽 재작성 포함)하고, 재빌드/`dist`·`dist-aix` 배포까지 마친 뒤 작업을 완료로 본다.

---

## Part 1. 세션 상세정보에 HASH_VALUE + "튜닝" 버튼 추가

### 배경
"Active Session"(대시보드)/"Current Session" 화면에서 세션 행을 클릭하면 뜨는 상세정보(SQL_ID, SQL 텍스트, 실행계획, 바인드 변수)에 HASH_VALUE도 보여주고, 거기서 바로 "SQL 정합성/튜닝" 메뉴로 넘어가 이어서 분석할 수 있게 해달라는 요청.

### 수정 파일
- `MonitorService.getSessionQuery()` — `v$sql` 조회 시 `sql_fulltext`와 함께 `hash_value`도 같이 가져와 응답에 포함
- `app.js` — 세션 상세 화면에 `HASH_VALUE` 표시 + "튜닝" 버튼 추가. 버튼 클릭 시 해당 세션의 SQL 원문/HASH_VALUE/바인드 변수 값을 "SQL 정합성/튜닝" 메뉴로 그대로 넘겨서 바로 이어서 분석 가능(바인드 이름은 `V$SQL_BIND_CAPTURE` NAME 컬럼의 `:` 접두사를 제거해서 튜닝 메뉴가 기대하는 이름 형식에 맞춤)

---

## Part 2. 세션 상세정보 표시 방식: 모달 → 별도 브라우저 창(팝업)으로 변경

### 배경
기존엔 화면 전체를 덮는 모달(`#image-modal`)이라 뒤쪽 대시보드/세션 리스트가 완전히 막혔음. "모달과 메인 화면을 분리해서 따로 조작하고 싶다"는 요청에서 출발해서 다음 순서로 검토/구현:
1. 배경 없는 드래그 가능한 플로팅 패널(같은 브라우저 탭 안에서만 이동 가능) — 구현했다가
2. "모니터 2대 중 다른 모니터로 옮기고 싶다"는 요청에 따라, 같은 탭 안 이동으로는 한계가 있음을 확인(뷰포트 밖으로 못 나감) → **진짜 별도 브라우저 창(`window.open`)** 방식으로 최종 전환

### 최종 구현
- **신규 파일 `session-detail.html`**: 독립된 팝업 페이지. 메인 페이지와 별도 문서라 JS 스코프를 공유 못하므로, 자체적으로 `sessionStorage`에서 토큰을 읽어(`window.open`으로 띄운 팝업은 같은 오리진이면 열 당시의 sessionStorage를 그대로 물려받음) `/api/session_query`를 직접 호출하고 SQL_ID/HASH_VALUE/SQL·Plan·Bind 탭/튜닝 버튼을 렌더링. 저장된 테마(`localStorage`)도 읽어서 다크/라이트 일치시킴.
- **`app.js`**: `.clickable-session-row` 전역 클릭 delegate가 `/api/session_query`를 직접 fetch하던 걸 걷어내고, 대신 `window.open()`으로 `session-detail.html` 팝업을 띄우도록 변경. 같은 세션(SID)을 다시 클릭하면 새 창을 또 띄우지 않고 기존 창에 포커스만 줌(SID 기준 창 이름 고정).
- **튜닝 버튼 연동**: 팝업은 별도 문서라 메인 페이지 함수를 직접 못 부르므로, `window.opener.openSqlTuningFromPopup(sql, hashValue, binds)`를 호출 — 메인 창에 새로 만든 전역 함수가 "SQL 정합성/튜닝" 메뉴로 전환하고 값을 채워넣음.
- Active Session(대시보드)/Current Session/TM Lock 목록 등 `.clickable-session-row`를 쓰는 모든 화면이 공통으로 이 팝업 방식을 씀. TM Lock 전용 별도 모달(`#image-modal` 기반, 다른 코드 경로)은 이번 변경 범위 밖이라 그대로 둠.
- 브라우저 팝업 차단 설정에 걸리면 `window.open`이 조용히 실패하므로, 실사용 전 팝업 차단 해제 확인 필요.

---

## Part 3. Current Session 메뉴 개선

### 3-1. 좌측 그래프를 새 지표 4종의 멀티 라인차트로 교체
- **변경 전**: ACTIVE/INACTIVE/ACTIVE TRANSACTION 3계열(백엔드 쿼리가 애초에 ACTIVE 세션만 조회해서 INACTIVE는 항상 0으로 찍히던 죽은 라인이었음)
- **변경 후**: 아래 4계열로 전면 교체
  - **Active Transaction** — 초록 `#10b981`
  - **Parallel Session** — 올리브 `#808000`
  - **2pc Pending Transaction** — 파랑 `#3b82f6`
  - **Lock Wait** — 자주 `#9333ea`
- y축 고정 범위(0~120)를 없애고 자동 스케일링으로 변경(신규 지표들은 세션 수보다 훨씬 작은 값대)

### 3-2. 신규 백엔드 엔드포인트 `/api/session_extra`
- `MonitorService.getSessionExtra()` — 한 커넥션에서 4개 조회를 모두 수행:
  - `active_transactions`: `v$transaction` ⋈ `v$session` (SID/Serial#/User/Status/Machine/Program/SQL_ID/Start Time/Used Undo Blocks·Records)
  - `parallel_sessions`: `v$px_session` ⋈ `v$session` (QC SID/QC Serial#/SID/Serial#/Server#/Degree/Req Degree/User/Status/Program/Machine)
  - `pending_2pc`: `dba_2pc_pending` (Local/Global Tran ID/State/Mixed/Comment/Host/Fail Time/Retry Time/OS User)
  - `lock_wait_count`: `v$lock`에서 `request > 0`인 세션 수(DISTINCT SID)
- 각 쿼리는 개별 try/catch로 감싸서, 예를 들어 `DBA_2PC_PENDING` 조회 권한이 없는 계정이어도 그 탭만 비고 나머지는 정상 동작(best-effort)
- `MonitorController`에 `GET /api/session_extra` 라우트 추가(기존 패턴과 동일하게 `canAccessDb` 체크)

### 3-3. "Active 세션목록" → 4개 탭 메뉴로 재구성
- **Active Session**(기존 테이블 그대로) / **Active Transaction** / **Parallel Session** / **2pc Pending Transaction**
- Active Transaction·Parallel Session 행은 `.clickable-session-row`라 클릭하면 Part 2의 세션 상세 팝업이 뜸(2pc Pending은 특정 세션에 안 묶이는 개념이라 클릭 비활성)

### 3-4. 리셋 동작 (다른 메뉴/DB 전환 시)
- `resetSessionMonitor()` 신설 — 좌측 라인차트와 **우측 Trace 스캐터차트 둘 다** `destroy()`하고 히스토리 배열/`scatterDataPoints`를 비운 뒤 즉시 재조회
- DB 인스턴스를 바꿀 때(`resetAllDashboardWidgets()`와 나란히 호출), "Current Session" 메뉴에 진입할 때(최초 진입뿐 아니라 재진입 때도 항상) 양쪽에서 호출되도록 연결 — 다른 DB의 데이터가 이전 DB 데이터와 한 그래프에 섞여 그려지는 일이 없음

---

## Part 4. 대시보드 상단 상태바에 Max Session / Max Process 추가

### 정의
- **Max Session / Max Process**: 현재 DB에 **설정된 파라미터 값**(`v$parameter`의 `sessions`/`processes`) — 실시간 사용량이 아니라 설정 상한
- **Active / Inactive**: 현재 세션 수 (`v$session.status`, `type != 'BACKGROUND'`, 대시보드의 다른 세션 카운트와 동일 기준)
- **Dedicate / Dispatcher**: 현재 세션 수 (`v$session.server = 'DEDICATED'` / `'SHARED'`) — **Dispatcher = shared server로 접속한 세션 개수**(dispatcher 프로세스 자체 개수가 아님, 사용자 확인하에 이 정의로 확정)

### 수정 파일
- `MonitorService.getHealth()` — 인스턴스/리스너 체크에 이어서 6개 값 추가 조회(`max_sessions`, `active_sessions`, `inactive_sessions`, `max_processes`, `dedicated_sessions`, `shared_sessions`). 각각 개별 try/catch로 감싸서 `v$parameter` 조회 권한이 없어도 인스턴스/리스너 상태 판정 자체엔 영향 없음
- `index.html` — `db-mini-status` 바에 인스턴스/리스너 옆으로 `Max Session -- EA [Active -- EA / Inactive -- EA]`, `Max Process -- EA [Dedicate -- EA / Dispatcher -- EA]` 추가(숫자와 EA 사이 공백 포함)
- `app.js` — `fetchHealth()` 성공 시 6개 값 반영, DB 다운 감지(`markHealthDown`) 시·DB 전환 리셋(`resetHealth`) 시 모두 `--`로 초기화. 값이 없을 때(쿼리 실패)는 `0`이 아니라 `--`로 표시해서 "정말 0개"와 "조회 실패"를 구분

---

## Part 5. 화면 하단 여백 버그 수정

### 증상
화면 하단에 항상 빈 공간이 남아있음.

### 원인
`.app-container`에 `zoom: 90%`가 인라인으로 걸려있는데(이전 세션의 "화면 확대/축소 90% 기본값" 기능), 정작 `.app-container`의 CSS `height`는 `100vh`로 고정. `zoom`은 요소를 크기 그대로 축소해서 그리는 속성이라, "100vh짜리 박스를 90%로 축소"하면 실제 화면에는 뷰포트 높이의 90%만 차지 → 나머지 10%가 `body` 배경만 보이는 빈 공간으로 남음.

### 수정
`style.css`의 `.app-container { height: 100vh; }` → `height: calc(100vh / 0.9);`로 보정. 줌 배율만큼 미리 키워서, 90%로 축소된 후에도 뷰포트 전체를 채우도록 함.

---

## Part 6. SQL 정합성/튜닝 "실제 실행 통계로 분석" / "1차 성능점검" 확인 팝업 제거

두 버튼 다 클릭 시 `confirm('이 쿼리를 실제로 실행합니다... 계속할까요?')`로 한 번 더 확인받던 걸 제거. 바로 실행되도록 `runSqlTuningAutoMode`/`quick_check` 클릭 핸들러에서 `if (!confirm(...)) return;` 줄만 삭제.

---

## Part 7. Current Session 그래프 X축/포인트 모양 조정 (+ Chart.js stepSize 버그 발견)

### 요청 배경
우측 Trace 산점도의 포인트 모양(`★`)을 `x` 자 모양으로, 좌우 그래프 X축을 "10분 단위 눈금"으로 바꿔달라는 요청 → 이후 "12개"에서 "20개"로 조정 → 그 과정에서 실제로는 6분 간격처럼 보이는 버그 발견/수정까지 이어짐.

### 변경 사항
- **포인트 모양**: 우측 Trace 산점도 `pointStyle: 'star'` → `pointStyle: 'crossRot'` (Chart.js에서 X자 모양에 해당하는 옵션명)
- **X축 범위**: 좌측 라인차트/우측 산점도 공통 상수 `CHART_WINDOW_MS`를 `20 * 10 * 60 * 1000`(200분, 10분×20틱)으로 설정. 리프레쉬 주기가 바뀌어도 항상 이 창을 채우도록 좌측 차트의 데이터 보관 개수(`maxDataPoints`)를 `sessionIntervalInput` 값 기준으로 매 fetch마다 재계산
- **라벨 가로 고정**: 좌측 차트 X축 틱에 `maxRotation: 0, minRotation: 0` 추가(우측 산점도는 원래부터 있었음)
- 우측 산점도는 기존 `type:'linear'` + 수동 HH:mm:ss 포맷팅 콜백 방식을 걷어내고, 좌측과 동일한 `type:'time'` 방식으로 통일

### 🐛 실제 버그: `stepSize`를 잘못된 위치에 넣었었음
`time: { unit: 'minute', stepSize: 10, ... }`로 넣었더니 10분 간격이 전혀 안 먹히고 대략 6분처럼 애매한 간격으로 나오는 문제 발생. 번들된 `lib/chart.js`(v4.5.1) 소스를 직접 grep해서 확인한 결과, TimeScale 구현이 `stepSize`를 **`scales.x.ticks.stepSize`에서만 읽고 `scales.x.time.stepSize`는 아예 안 읽음**(`time.unit`은 읽지만 `time.stepSize`는 무시됨)이 원인. `stepSize`가 없으니 Chart.js가 1분 단위를 기본값으로 잡고 화면 폭에 맞춰 "적당히 보기 좋은" 간격을 자체 계산하다 보니 6분 같은 어중간한 값이 나온 것.

**수정**: `time.stepSize` → `ticks.stepSize`로 위치 이동 (좌측/우측 차트 둘 다). Chart.js v4에서 시간축에 고정 간격을 강제하려면 `time.unit`(단위)과 `ticks.stepSize`(간격 배수)를 **서로 다른 옵션 객체**에 나눠 넣어야 한다는 게 이번에 확인된 핵심 포인트 — 향후 시간축 관련 작업 시 참고.

---

## Part 8. 대시보드 "장애발생 가능성" 라벨 포맷 변경

`(TM Lock: --건) (TX Lock: --건)` → `[TM Lock -- EA / TX Lock -- EA]`로 변경. `index.html`의 초기 placeholder와 `app.js`의 `fetchDashboard()` 내 실제 값 대입 템플릿 리터럴 둘 다 수정.

---

## Part 9. 트러블슈팅: `git checkout -- target/`로 인한 정적 리소스 배포 누락 버그

### 증상
로그인 화면이 CSS 하나도 안 먹은 것처럼 위에서부터 아래로 요소가 그냥 쌓여서 보임(username/password/큰 로고 이미지가 상단에, 그 아래 "실시간 대시보드" 미리보기 텍스트).

### 원인
이번 세션 내내 재빌드 후 "정리 차원"에서 `git checkout -- target/`을 반복 실행했는데, 이 프로젝트(정확히는 `DBAgent-Java-AIX`)는 `target/` 폴더 자체가 git에 커밋되어 있는 특이한 구조. `git checkout`으로 `target/classes/static/style.css` 등을 예전 커밋 시점 내용으로 되돌리면서 파일의 mtime도 "방금"으로 갱신됨 → 이후 소스 파일을 다시 안 건드리는 한, Maven이 "target이 소스보다 최신이니 리소스 재복사 불필요"라고 판단해 **낡은 정적 리소스가 계속 jar에 실림**. 실제로 로컬에 그 jar를 직접 띄워서 `curl`로 서빙되는 `style.css`를 받아보니 로그인 리디자인 CSS(262줄) 자체가 통째로 빠져 있는 걸로 확인.

### 수정
- `git checkout -- target/` 사용 중단
- 재빌드는 항상 **`mvn clean package`**로 전환(리소스/클래스 강제 재생성)
- 재발 방지 확인 절차 확립: 재빌드 후 `wc -l target/classes/static/{app.js,index.html,style.css}`를 소스 파일과 비교해서 줄 수가 정확히 일치하는지 확인하고 나서 배포

이 버그는 `DBAgent-Java-AIX`에서 먼저 발견됐지만 원인이 프로젝트 구조(`target/`이 git 추적 대상)에 있는 것이라 앞으로 그쪽에서도 계속 조심할 것.

### 부수 발견: 디스크 공간 100% 소진
같은 시점에 C: 드라이브가 238GB 중 0바이트 남을 정도로 꽉 찼던 것도 발견(이번 세션에 쌓인 `dist`/`dist-aix`의 `.bak`~`.bak5` 백업 파일들이 일부 원인이었으나, 238GB 전체가 이 프로젝트와 무관하게 꽉 찬 것은 별개의 더 큰 문제 — 사용자가 직접 불필요 파일 정리해서 해결).

---

## 배포 반영 (최종)

- 캐시버스터: `app.js` v29(전 세션 종료 시점) → **v41**, `style.css` v13 → **v14**
- `.\build.ps1 -Dist`는 알려진 버그로 계속 안 씀 — `mvn clean package` 후 `target\dbagent-java-0.1.0.jar`를 `dist\dbagent-java-0.1.0.jar`로 수동 동기화(`.bak` 백업), `dist\databases.json`에 `session_thresholds` 필드도 반영 완료(포트 `1521` 이슈는 기존 정리대로 손 안 댐)
- 로컬 `dist\`/루트 둘 다 재빌드 결과물과 byte 단위로 동일한 것까지 확인 후 배포
- 세션 중 로컬 개발 서버(포트 8006)가 여러 번 떠 있어서 재빌드 때마다 종료 후 진행 (`taskkill`)

---

## AIX 이관본(`DBAgent-Java-AIX`) 반영 내역

이 문서의 Part 1~4를 같은 세션에서 `DBAgent-Java-AIX`에도 포팅 완료(2026-08-26). 새 백엔드 코드(`getSessionExtra`, `getHealth` 확장분)는 애초에 record/`Map.of`/`String.isBlank()` 등 최신 문법 없이 작성돼 있어서 기계적으로 그대로 옮겨 붙였고, Java 8 대상 컴파일(`mvn package`)로 검증 완료.

- [x] Part 1: `MonitorService.getSessionQuery()`에 `hash_value` 추가 완료
- [x] Part 2: `session-detail.html` 신규 생성 + `app.js`의 `.clickable-session-row` delegate를 `window.open()` 팝업 방식으로 전환 완료
- [x] Part 3: `/api/session_extra`(`MonitorController`/`MonitorService`) + Current Session 프런트엔드(4계열 차트, 4탭 메뉴, `resetSessionMonitor`) 전체 포팅 완료
- [x] Part 4: `MonitorService.getHealth()` 확장 + 대시보드 상태바(Max Session/Process) 포팅 완료
- [ ] Part 5: **해당 없음** — AIX의 `.app-container`에는 애초에 `zoom: 90%`가 적용돼 있지 않음(그 기능 자체가 이전 세션에 AIX 이관 범위 밖으로 확인됨). 따라서 하단 여백 버그도 없고 수정도 불필요
- [x] **"SQL 정합성/튜닝" 메뉴 신규 이관** (2026-08-26 추가 요청) — 처음엔 AIX에 이 메뉴가 없어서 튜닝 버튼을 뺐었는데, "모델 없이 DBA가 성능점검만 할 수 있는 기능은 필요하다"는 요청에 따라 메뉴 자체를 이관함:
  - `ExecutionPlanService`(`com.dbagent.query`) 신규 이식 — `.strip()`/`.stripLeading()`/`.stripTrailing()`(Java 11+)을 `Strings.strip()`/`Strings.stripTrailing()`로 치환
  - `SqlTuningRequest`/`SqlTuningAutoRequest`/`BindCaptureRequest`(원본은 record) → AIX 기존 패턴대로 일반 클래스+`@JsonCreator`로 재작성
  - `SqlTuningController` 신규 작성 — **`analyze`/`analyze_from_query`/`analyze_from_query_actual`(모델 필요) 3개는 항상 "이 환경에는 SQL 튜닝 sLLM 서버가 연동되어 있지 않습니다" 메시지만 반환하도록 스텁 처리**(WSL/GPU 필요한 `SqlTuningService`의 `java.net.http.HttpClient` 자체를 이식하지 않음 - 어차피 호출할 서버가 없어서 이식할 필요가 없어짐). `quick_check`(1차 성능점검)/`bind_capture`(V$SQL_BIND_CAPTURE 조회)는 모델이 필요 없는 순수 Oracle 조회라 원본 그대로 완전히 동작
  - 프런트엔드(`index.html`/`app.js`)는 원본과 100% 동일 코드 재사용 - 모델 버튼도 특별한 분기 없이 그냥 서버가 돌려주는 스텁 메시지를 그대로 표시할 뿐이라 프런트 코드 수정이 불필요했음(버튼/설명 문구에 "AI 모델 필요"만 표기 추가)
  - 세션 상세 팝업(`session-detail.html`)의 "튜닝" 버튼과 `window.openSqlTuningFromPopup`도 이제 AIX에 추가 — 이제 AIX에서도 세션 상세 → 튜닝 메뉴로 점프해서 1차 성능점검을 이어갈 수 있음

### 캐시버스터 버그 (2026-08-26 발견/수정)
AIX에 세션 내내 `app.js`/`style.css`를 계속 수정했는데 `index.html`의 `app.js?v=32`/`style.css?v=13`를 한 번도 안 올려서, 브라우저가 낡은 캐시를 계속 쓰면서 로그인 화면이 깨져 보이고 새 기능이 "반영 안 된 것처럼" 보이는 문제가 있었음. 처음엔 이게 원인인 줄 알고 버전을 올렸는데, 나중에 **Part 9의 `target/` 캐시 오염 버그**가 진짜 원인이었던 것으로 추가 확인됨(둘 다 겹쳐서 발생했었음). 최종 버전은 아래 참고.

- [x] Part 6~8(confirm 팝업 제거, 차트 X축/포인트 모양 + `stepSize` 버그 수정, 장애 라벨 포맷 변경): 전부 동일하게 AIX에도 포팅 완료
- [x] Part 9(`git checkout -- target/` 버그): AIX에서 먼저 발견 → `mvn clean package`로 전환, 이후 모든 재빌드에 적용
- [x] `dist-aix\`, `dist\` 동기화 완료 (여러 차례, 매번 재빌드 결과물과 byte 단위 동일 확인 후 배포)
- 최종 캐시버스터: `app.js` v32 → **v45**, `style.css` v13 → **v15**
- 재기동은 아직 안 함 — AIX 서버에서 `stop-aix.sh` → `start-aix.sh` 필요

**AIX 이관 작업의 상세 내역(백엔드 이식 세부사항, SQL 정합성/튜닝 메뉴 스텁 처리 등)은 `DBAgent-Java-AIX\작업정리_2026-08-26.md`에도 별도로 기록함.**

---

## 다음에 할 작업 (아직 미착수, 아이디어 단계)

### `databases.json` DB 목록을 화면에서 직접 관리 (추가/삭제)
- **배경**: 지금은 `databases.json` 파일을 직접 편집해야 모니터링 대상 DB를 추가/삭제할 수 있음. 계정 관리 화면처럼 관리자 전용 환경설정 UI로 만들 수 있는지 질문 받음 → 가능하다고 답변, 실제 구현은 아직 안 함
- **구현 방향**: 계정 관리 모달과 동일한 패턴(관리자 전용 모달 + `POST/PUT/DELETE` API + JSON 파일 갱신)로 DB 추가/수정/삭제 UI 구성
- **⚠️ 반드시 같이 처리해야 할 것**: `DatabaseConfigService`가 `databases.json`을 앱 **기동 시 딱 한 번만** `@PostConstruct`로 읽어 메모리(`JsonNode config`)에 캐싱하는 구조라, 파일만 갱신해서는 재시작 전까지 반영 안 됨. **파일 쓰기 + 메모리 캐시 재로드(reload)를 함께 구현**해야 재시작 없이 즉시 반영됨(동시 수정 시 스레드 안전성도 고려 필요)
- 비밀번호는 기존 관례대로 `B64(...)` 형태(실제 암호화 아님, 평문 감추기 수준)로 저장 — UI 쪽 입력/저장 방식도 이 관례에 맞출 것
- **`DBAgent-Java`/`DBAgent-Java-AIX` 둘 다 적용 대상** (위 작업 원칙 참고)

### 로그인 후 첫 화면을 "Fleet Overview"(전체 DB 현황판)로 재설계
- **설계 문서**: `db_모니터링_첫화면_설계안_1.md`(이 프로젝트 루트에 있음), 목업 HTML `db_fleet_overview_mockup.html`(현재 `C:\Users\윤인수\Downloads\`에 있음 — 프로젝트로 옮겨올지 검토 필요)
- **배경/목적**: 지금은 로그인하면 바로 특정 DB 하나의 상세 대시보드로 들어가는데, 이걸 "관리 중인 여러 DB 상태를 한눈에 스캔하고 문제 있는 DB로 바로 들어가는" 요약 화면(Fleet Overview, Datadog Infrastructure/AWS RDS 콘솔/Oracle EM Fleet 화면과 같은 패턴)으로 바꾸자는 설계안. 지금의 개별 DB 상세 모니터링(Active Session/Transaction, Lock Wait, Parallel Session, 2PC Pending 등 시계열 차트) 화면은 그대로 유지하되, 그 앞단에 이 요약 화면을 새로 추가하고 카드/행 클릭 시 해당 DB의 기존 상세 화면으로 드릴다운하는 구조
- **화면 구성 요약**:
  - 상단 요약 바: 전체 DB 수 + 정상/경고/위험 개수 스탯 타일
  - DB 목록: 카드형(DB 8~10개 이하)/테이블형(그 이상) 토글, 기본 정렬은 이름순이 아니라 **위험도순**(위험→경고→정상, 동순위는 CPU 높은 순) 고정
  - 검색(DB명) + 필터(환경: Production/Staging/Dev, 상태 칩: 전체/위험/경고/정상)
  - 우측 "주의 필요 Top 5" 패널 — 실시간 알림 피드 대신 CPU 기준 위험도 상위 5개 고정 순위 목록(검색/필터와 무관하게 항상 전체 기준)
  - 목록 박스는 고정 높이 없이 콘텐츠 양만큼만 차지(좌우 컬럼 독립적으로 상단 정렬, 카드 최대 너비 제한으로 DB 적을 때 카드가 억지로 안 커지게)
  - 행/카드 클릭 → 해당 DB의 기존 실시간 세션 모니터링 상세 화면으로 이동
- **색상 규칙**: 페이지 전체 고정 다크 테마(라이트 모드 없음), 배경 `#0f172a`/카드 `#1e293b`/hover `#24334a` 등 네이비 계열로 통일. **상태색(정상 `#0ca30c`/경고 `#fab219`/위험 `#d03b3b`)은 DB 상태 전용 색으로 고정** — 상세 화면의 지표 구분색(Active Session/Transaction 등에 쓰는 파랑/올리브/보라 등)과 절대 겹치지 않게 유지, 상태는 색만이 아니라 항상 텍스트 라벨도 같이 표기. WCAG 대비 기준(텍스트 4.5:1, 그래픽 요소 3:1) 검증까지 설계안에 포함돼 있음
- **확장 고려사항**: DB 수 수십~수백 개로 늘면 테이블형에 페이지네이션/가상 스크롤, 그룹/태그 필터 추가 가능성, 정렬 기준(위험도순)은 사용자가 임의로 못 바꾸게 하거나 쉽게 기본값 복귀 가능하게
- **⚠️ 검토 필요 사항** (설계안엔 없고 구현 시 추가로 고민할 것):
  - "정상/경고/위험" 판정 기준(임계치)을 어느 지표로 어떻게 계산할지 — 기존 대시보드의 `session_thresholds`/CPU·메모리 임계치 로직 재사용 가능한지 확인
  - 전체 DB 목록을 한 화면에서 보여주려면 모든 DB에 동시에 폴링(헬스체크/CPU 등)해야 함 — 지금은 사용자가 선택한 DB 하나만 폴링하는 구조라 커넥션 풀/부하 영향 검토 필요
  - 다크 전용이라고 못 박았는데, 현재 앱은 라이트/다크 토글 기능이 이미 있음 — 이 화면만 예외로 둘지, 라이트 테마도 만들지 결정 필요
- **`DBAgent-Java`/`DBAgent-Java-AIX` 둘 다 적용 대상** (위 작업 원칙 참고)
