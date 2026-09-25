# 대시보드 UI 개선 설계

- 대상: DB InsightFlow **DASHBOARD 탭**
- 목업: 같은 폴더의 `UI개선_mockup.html` (브라우저로 열면 동작 확인 가능, 데이터는 샘플)
- 작성일: 2026-09-25
- 개정: 2026-09-25 — **ASH(`v$active_session_history`) 사용으로 변경** (Diagnostics Pack 보유 전제). 자체 세션 샘플 수집(`MON_SESSION_SAMPLE`)과 v$waitclassmetric 수집을 제거했다. RAC라도 gv$ 미사용·`inst_id` 미사용(2026-09-06 원칙).
- 개정: 2026-09-25 — **대기 분류는 목업의 8분류(Oracle 대기 클래스)로 확정** (오케스트레이터 결정). 소스는 ASH의 `wait_class` 컬럼. Current Session 메뉴의 7분류(Active Session 리스트에 맞춘 분류)는 그대로 두고, 이 대시보드만 8분류를 쓴다.

이 문서는 Claude CLI가 그대로 구현 작업을 진행할 수 있도록 화면 명세, 수집 설계, 쿼리, API, 수용 기준을 담고 있다. 목업과 문서가 다르면 **이 문서가 우선**한다.

---

## 0. Claude CLI 작업 전 확인 사항

구현 전에 반드시 기존 코드를 먼저 파악하고, 이 문서의 테이블명·API 경로·클래스명을 **기존 프로젝트 컨벤션에 맞춰 조정**한다.

1. 현재 DASHBOARD 탭의 프런트엔드 파일(게이지 4개, Active Session/Top Event 탭, 세션 테이블)과 이를 채우는 백엔드 API 위치를 찾는다.
2. 대상 DB 접속 방식(커넥션 풀, DB 목록 = 좌측 사이드바의 "통합DB #1 (ORCL1)" 등)과 기존 수집 스케줄러(1분 백그라운드 수집)의 구조를 파악한다.
3. 수집 데이터를 저장하는 저장소(Repository DB/테이블)의 위치와 기존 테이블 네이밍 규칙을 확인한다.
4. 기존 "리프레쉬 주기(초)", "수동 새로고침", "자동 갱신 중지" 컨트롤의 구현을 확인한다. 이 문서는 이 컨트롤을 **재사용**한다.
5. 기존 프로젝트의 서브에이전트를 활용한다: 수집 쿼리는 `query-performance-reviewer`, KILL 기능은 `security-reviewer`, 화면 구조는 `design-advisor`, 완료 후 `feature-tester`.

---

## 1. 목표와 범위

### 1.1 목표

- 운영자가 **한 화면(스크롤 없이)** 에서 DB 부하 상태, 병목 원인, Lock 장애를 판단하고 조치까지 할 수 있게 한다.
- 핵심 그래프는 **대기 클래스별 평균 활성 세션(AAS) 누적 영역 차트** (OEM Top Activity 형태).
- 장애와 직결되는 **TX / TM Lock 대기는 실시간(기본 3초, 조절 가능)** 으로 보여주고, 장애 조건 충족 시 즉시 조치할 수 있게 한다.

### 1.2 변경 범위

| 영역 | 변경 |
|---|---|
| 좌측 사이드바, DB 선택, 상단 탭 | **변경 없음** |
| 상태바 (인스턴스/리스너/Max Session/Max Process, 리프레쉬 주기, 수동 새로고침, 자동 갱신 중지) | **조회 구간 선택 버튼 추가** (15분/1시간/3시간/24시간) |
| 상태바 아래 전체 (CPU/메모리/장애발생 가능성/활성 세션 게이지 4개, Active Session 목록/Top Event 목록 탭, 세션 테이블) | **전부 제거**하고 아래 8개 프레임으로 교체 |

v2 대시보드와의 관계 (2026-09-25 결정): 우측 하단 전환 스위치는 **그대로 유지**한다. 스위치의 "기존" 자리에 이 새 화면이 들어가 **새 화면 / v2** 두 가지로 전환된다. 기존 게이지·탭 화면은 남기지 않는다. v2 화면·데이터 경로는 변경하지 않는다. 스위치 라벨과 마지막 선택값 저장 방식은 기존 구현을 따르고, 라벨만 "기존"에서 새 화면 이름으로 바꾼다.

체크리스트와의 관계 (2026-09-25 결정): 대시보드의 Active Session 목록이 없어지므로 1-7(Duration 정렬)과 1-8(Remote 탭 추가)은 **Current Session 매뉴에만** 적용하고, 1-8의 대시보드 탭 구성 부분과 9-1(기존 대시보드 프레임 통합)은 이 설계로 대체되어 취소한다. 이 문서는 9-2(대시보드 그래프 개편)의 설계 문서다.

### 1.3 데이터 소스 원칙 (중요)

- **Diagnostics Pack 보유를 전제로 ASH를 사용한다.** 기존 Current Session 메뉴(Active Session Wait Class 차트, Top SQL Activity Timeline, 세션 상세 팝업)와 60초 샘플러가 이미 `v$active_session_history`를 쓰고 있으므로 같은 소스·같은 분류로 맞춘다.
- **대기 분류는 Oracle 대기 클래스 8분류**다 (2.3 참조). ASH 행의 `session_state`/`wait_class`로 판정하며, ②(60초 샘플러 저장값)와 ⑤⑥⑦·드로어(구간 직접 조회)가 **같은 CASE 문**을 써야 한다. CASE 문은 공통 상수 한 곳에 두고 두 경로가 공유한다.
- Current Session 메뉴는 Active Session 리스트의 세션별 대기 분해와 맞춘 **7분류(CPU/Latch/User I/O/TX Lock/Sys I/O/TM Lock/Other)를 그대로 유지**한다. 두 화면은 분류 기준이 다르므로, 이 대시보드의 ② 차트 제목 옆에 `Oracle 대기 클래스 기준` 라벨을 붙여 구분한다.
- 사용하는 뷰:
  - ASH: `v$active_session_history` (최근 데이터, 1초 샘플), 보관 범위를 벗어난 구간만 `dba_hist_active_sess_history` (10초 샘플, 4.3 (3) 참조)
  - 기본 뷰: `v$sysmetric`, `v$session`, `v$process`, `v$sql`, `v$sqlstats`, `v$lock`, `v$osstat`
- ② AAS 추이(15분~24시간)는 원본 DB에 매번 ASH GROUP BY를 보내지 않고, **기존 60초 샘플러가 `instance_metric_history`에 쌓는 값**(이번에 추가하는 8분류 `ash_wc_*`)을 조회한다 (Current Session 6시간/24시간 경로와 같은 이유 — 보는 사람 수만큼 원본 DB 부하가 늘지 않게).
- ⑤⑥⑦ Top 목록과 드로어는 **선택 구간에 대해서만** ASH를 조회한다 (구간이 짧아 가볍다).
- **RAC**: gv$ 뷰와 `inst_id`는 쓰지 않는다. 모든 값은 **접속한 인스턴스 기준**이며, 화면에 접속 인스턴스명(`v$instance.instance_name`)과 "이 인스턴스 기준"을 표시한다. v$ 뷰에 원래 있는 `blocking_inst_id`(ASH)·`blocking_instance`(`v$session`)는 블로커가 다른 노드인지 판별하는 데만 쓴다.

---

## 2. 화면 구성

### 2.1 레이아웃

```
┌ 사이드바 ┬──────────────────────────────────────────────────────────────┐
│ (기존)   │ 통합DB #1 (ORCL1) ▾   [상태 pill] Oracle 19c · host · CPU n코어 │
│          │ DASHBOARD | Current Session | 성능 이력 조회 | ... (기존 탭)    │
│          ├──────────────────────────────────────────────────────────────┤
│          │ 인스턴스 Alive · 리스너 Alive · Max Session … · Max Process …   │
│          │        [15분|1시간|3시간|24시간] 리프레쉬 주기(초): 2           │
│          │        [↻ 수동 새로고침] [자동 갱신 중지]                        │
│          ├──────────────────────────────────────────────────────────────┤
│          │ ① KPI: 현재 AAS | 구간 평균 | 최대 | 코어 초과 | 활성 세션 | 메모리 │
│          ├───────────────────────────────┬──────────────────────────────┤
│          │ ② 평균 활성 세션(대기 클래스별) │ ③ Lock 대기 세션 (실시간)     │
│          │    누적 영역 차트               │    TX/TM 대기 건수 차트       │
│          │   (남는 세로 공간을 모두 사용)   │                              │
│          ├───────────────────────────────┴──────────────────────────────┤
│          │ 선택 구간 hh:mm – hh:mm (n분) · 평균 AAS x.x                    │
│          │ ④ 진단 배너: "Application 49%가 가장 큽니다. …"                 │
│          ├─────────────────┬────────────────────┬───────────────────────┤
│          │ ⑤ Top SQL       │ ⑥ Top 세션          │ ⑦ Top 대기 이벤트       │
│          │ (상위 5, 내용 높이만큼) │ (상위 5)      │ (상위 5)                │
│          ├─────────────────┴────────────────────┴───────────────────────┤
│          │ ⑧ 점검 알림  [위험 2 · 주의 4 · 확인 2]   [전체|위험|주의|확인]   │
│          │ [카드][카드][카드][카드][카드][카드][카드][카드]  (가로 배열)      │
└──────────┴──────────────────────────────────────────────────────────────┘
```

### 2.2 크기 규칙 (스크롤 없이 한 화면)

- 대시보드 영역은 `상단 크롬(탑바+탭+상태바)`을 뺀 나머지 높이를 flex column으로 나눈다.
- ① KPI, ④ 진단 배너, ⑤⑥⑦ 패널, ⑧ 점검 알림은 **내용 높이만큼만** 차지한다 (늘리지 않는다).
- ②③ 차트 행은 **높이 400px 고정**(처음 320px, 2026-09-26 400px로 조정) (2026-09-26 결정 — 처음엔 남는 세로 공간을 모두 가져가게 했으나 부하·배율에 따라 차트 크기가 들쭉날쭉해 고정). 남는 공간은 아래 여백.
- 차트가 낮아졌으므로 ② 툴팁은 2열 배치(클래스 4개씩 2열 + 합계)로 카드 밖으로 잘리지 않게 한다.
- 차트 SVG 높이는 고정값이 아니라 **컨테이너 높이에서 계산**한다 (`ResizeObserver`로 창 크기 변경 시 다시 그림).
- 창이 너무 작아 최소 높이로도 안 들어가면, 페이지 전체가 아니라 **대시보드 영역 안에서만** 세로 스크롤이 생기게 한다 (사이드바·탭·상태바는 고정). 내용이 잘려서 안 보이는 일이 없어야 한다.
- 기준 해상도 1920×1080(브라우저 내부 약 1920×1040)에서 스크롤이 없어야 한다.

### 2.3 색상 토큰 (다크 테마)

| 토큰 | 값 | 용도 |
|---|---|---|
| `--page` | `#0b0f14` | 페이지 배경 |
| `--surface` / `--surface-2` | `#141a21` / `#1a212a` | 카드 / 보조 면 |
| `--ink` / `--ink-2` / `--muted` | `#eef2f6` / `#b3bec9` / `#7f8b97` | 텍스트 |
| `--grid` / `--axis` | `#222b35` / `#35414d` | 차트 격자 / 축 |
| `--good` / `--warn` / `--serious` / `--crit` | `#0ca30c` / `#fab219` / `#ec835a` / `#d03b3b` | 상태 (항상 아이콘+라벨과 함께) |

대기 클래스 8분류와 색 (목업 기준. OEM 관례를 따르되 다크 배경·색약 구분 검증 완료):

| 분류 (키) | 색 | 판정 (ASH 행 기준, 위에서부터 먼저 맞는 것) |
|---|---|---|
| CPU (`cpu`) | `#1c9a68` | `session_state = 'ON CPU'` |
| User I/O (`user_io`) | `#4270e8` | `wait_class = 'User I/O'` |
| System I/O (`system_io`) | `#13a9a4` | `wait_class = 'System I/O'` |
| Concurrency (`concurrency`) | `#a45a2c` | `wait_class = 'Concurrency'` |
| Application (`application`) | `#ec4868` | `wait_class = 'Application'` (enq: TX/TM 행 잠금·테이블 잠금 포함) |
| Commit (`commit`) | `#a8830a` | `wait_class = 'Commit'` |
| Network (`network`) | `#8f7ae6` | `wait_class = 'Network'` |
| Other (`other`) | `#d46a9e` | 그 밖의 대기: Configuration, Cluster, Scheduler, Administrative, Queueing, Other (`Idle` 제외) |

- 누적 순서(아래→위): CPU, User I/O, System I/O, Concurrency, Application, Commit, Network, Other.
- ③ Lock 차트: TX 선 색 = Application 색, TM 선 색 = Network 색(보라). TX/TM 실시간 구분은 ③ Lock 카드(선 색 + 상단 건수)가 맡는다.
- 라이트 테마에서도 대비가 충분한지 확인하고, 부족하면 라이트용 값을 따로 둔다.
- Current Session 메뉴의 7분류 색(`app.js`의 `ASH_ACTIVITY_CATEGORIES`)은 바꾸지 않는다.
- 위 토큰은 새로 만들지 말고 기존 `style.css`의 테마 변수에 매핑한다. **라이트 테마 값도 함께 정의**한다 (앱은 `html[data-theme="light"]`로 라이트 테마를 전환하고 나머지는 CSS 변수로 따라감). 대기 클래스 색은 라이트 배경에서도 대비를 확인한다.

### 2.4 폐쇄망·브라우저 제약 (필수)

운영 환경은 외부 인터넷이 없는 폐쇄망(AIX 이관본 포함)이고, 브라우저 버전을 보장할 수 없다. 목업은 참고용이며 아래 항목은 목업과 다르게 구현한다.

| 항목 | 목업 | 구현 |
|---|---|---|
| 폰트 | Google Fonts 로드 | **외부 폰트 금지.** 시스템 폰트 스택(`'Malgun Gothic', 'Segoe UI', sans-serif`, 숫자·SQL은 `Consolas, monospace`)과 기존 `style.css` 폰트를 쓴다. "1920×1040에서 스크롤 없음"은 이 실제 폰트로 다시 확인한다. |
| `color-mix()` | 카드·배지 배경에 사용 | **쓰지 않는다** (Chrome 111+ 필요). 필요한 반투명 색은 `rgba()` 값으로 미리 계산해 토큰으로 둔다. |
| `100dvh` | 전체 높이 | `100vh` + flex 레이아웃으로 대체 (`dvh`는 Chrome 108+). |
| 차트 | SVG 직접 그림 | 그대로 SVG 직접 그림. 외부 차트 라이브러리·CDN을 추가하지 않는다. |
| 클립보드 ("KILL 구문 복사", "조회 쿼리 복사") | `navigator.clipboard` | `navigator.clipboard`는 HTTPS/localhost에서만 동작하므로 폐쇄망 `http://IP:포트`에서는 실패한다. `textarea` + `document.execCommand('copy')`로 대체하고, 그것도 실패하면 텍스트를 선택 상태로 만들고 "Ctrl+C로 복사하세요"를 안내한다. |
| 화면 배율 | 없음 | 앱의 `.app-container`에 `zoom: 90%`가 걸려 있다(`index.html`. 2026-09-25 체크리스트 4-1로 80%로 줄였다가 2026-09-26 글씨·균형 문제로 90%로 되돌림, RDB·FO 화면도 같은 90%). `position:fixed` 요소가 zoom 컨테이너에 묶이는 버그 전례가 있으므로 **드로어·툴팁·오버레이는 `body` 바로 아래에 붙인다.** 차트의 포인터 좌표는 `getBoundingClientRect()` 기준 비율로 계산하고(목업 방식), zoom 80% 상태에서 호버·드래그 위치를 반드시 확인한다. zoom 값이 바뀌어도 동작해야 한다(좌표를 고정 비율로 하드코딩하지 말 것). |

- 백엔드는 AIX(Java 8)로 같이 포팅한다: `record`, `switch` 식, `List.of`/`Map.of`, `String.isBlank` 등을 쓰지 않거나 AIX 쪽 대체 유틸(`Maps.of` 등 기존 관례)로 바꾼다. 저장소 upsert 문법도 SQLite/H2가 다르다(4.2).

---

## 3. 상태바 변경

| 요소 | 명세 |
|---|---|
| 조회 구간 | 세그먼트 버튼 `15분 / 1시간 / 3시간 / 24시간`, 기본값 **1시간**. ① ② ④ ⑤ ⑥ ⑦에 적용. ③ Lock 차트는 구간과 무관하게 항상 최근 10분 실시간. |
| 리프레쉬 주기(초) | 기존 입력창 재사용, **기본 3초**(2026-09-25 결정). 운영자가 입력창에서 바꿀 수 있고(허용 범위 `lock.refreshSecMin`~`lock.refreshSecMax`, 기본 2~60초, 범위 밖 값은 가장 가까운 경계로 보정), 바꾸면 다음 주기부터 바로 적용. ③ Lock 차트가 이 주기로 갱신. ① KPI와 ② AAS 차트는 수집 주기(1분)마다 갱신. |
| 수동 새로고침 | ①~⑦ 전체 즉시 재조회 |
| 자동 갱신 중지 / 재개 | 토글. 중지 시 버튼 라벨 "자동 갱신 재개", ③ 카드의 LIVE 표시가 "일시정지됨"으로 바뀜 |

---

## 4. 데이터 수집 설계

### 4.1 수집 주기

| 수집 | 주기 | 소스 | 저장 |
|---|---|---|---|
| 대기 클래스별 AAS (8분류) | 60초 | `v$active_session_history` | **기존** `instance_metric_history`에 metric 8개 추가: `ash_wc_cpu`, `ash_wc_user_io`, `ash_wc_system_io`, `ash_wc_concurrency`, `ash_wc_application`, `ash_wc_commit`, `ash_wc_network`, `ash_wc_other` (기존 7분류 `ash_*`는 Current Session용으로 그대로 유지) |
| DB 요약 (총 AAS, 코어) | 60초 | `v$sysmetric`, `v$osstat` | **기존** `instance_metric_history`의 `db_time_aas`, `ash_cpu_cores` (코어 쿼리만 `NUM_CPU_CORES`로 수정) |
| Top SQL/세션/이벤트 (⑤⑥⑦·드로어) | 수집 없음 | `v$active_session_history` (보관 범위 밖은 `dba_hist_active_sess_history`) | 저장하지 않음 — 선택 구간을 조회할 때 직접 집계 |
| SQL 통계 델타 | 60초 | `v$sqlstats` | `MON_SQLSTAT_DELTA` |
| Lock 실시간 | 리프레쉬 주기(기본 3초) | `v$session`, `v$lock` | 메모리(최근 10분) + `MON_LOCK_SAMPLE`(1분 요약) |
| 점검 항목 (⑧) — 가벼운 항목 | 10분 | 테이블스페이스, FRA, TEMP, 스케줄러 잡 실패 (5장 ⑧ 표) | `MON_CHECK_RESULT` |
| 점검 항목 (⑧) — 무거운 항목 | 1일 1회 (`check.dailyHour`, 기본 새벽 3시) | 테이블 용량, 무효 객체, 통계 오래됨 (5장 ⑧ 표) | `MON_CHECK_RESULT` |
| 세그먼트 크기 스냅샷 | 1일 1회 (위와 같은 실행에서) | `dba_segments` | `MON_SEGMENT_SIZE` (테이블 증가량 계산용). 테이블 용량 점검과 **같은 `dba_segments` 조회 1번**의 결과를 함께 쓴다 |

> 점검 주기를 나누는 이유: `dba_segments` 전체 GROUP BY, `dba_objects`, `dba_tab_statistics`는 딕셔너리 뷰 스캔이라 무겁고(기존 v2 대시보드가 딕셔너리 뷰 조인으로 운영 장애를 겪은 전례, `InstanceMetricSamplerService` 주석 참고), 값이 하루에 크게 바뀌지 않는다. 앱 기동 직후에는 1일 항목을 한 번 즉시 실행해 빈 화면을 막는다(이후는 매일 정해진 시각).

> 자체 세션 샘플 테이블을 두지 않는 이유: ASH가 이미 활성 세션을 1초마다 샘플링해 SQL·세션·이벤트별 드릴다운에 필요한 컬럼을 모두 갖고 있다. 같은 데이터를 10초마다 복제 저장하면 인스턴스 11개 기준 하루 수백만 행이 SQLite/H2 저장소에 쌓여 쓰기 락·파일 비대화 위험만 생긴다.
>
> ASH 인메모리 보관 범위는 SGA 크기와 부하에 따라 다르다(보통 수십 분~수 시간). 24시간 구간의 과거 부분은 `dba_hist_active_sess_history`(10초 간격으로 걸러 저장된 값)에서 읽는다. AWR 보관 기간(기본 8일)과 스냅샷 주기(기본 1시간)를 따르므로, 가장 최근 스냅샷 이후~ASH 최하단 사이에 빈 구간이 없도록 두 소스의 경계를 `MIN(sample_time)` 기준으로 나눈다.

### 4.2 수집 테이블 DDL (Oracle 문법으로 표기 — 실제 저장소 문법으로 변환)

- 실제 저장소: 메인은 SQLite(`metrics.db`), AIX는 H2(Java 8). 기존 관례대로 테이블명은 소문자 snake_case, 시각은 epoch ms INTEGER, upsert는 SQLite `INSERT OR REPLACE` / H2 `MERGE`로 바꾼다.
- 아래 DDL은 항목 정의를 보여주기 위한 표기일 뿐 그대로 실행하지 않는다.

```sql
-- SQL 통계 델타 (1분)
CREATE TABLE MON_SQLSTAT_DELTA (
  DB_ID            VARCHAR2(30)  NOT NULL,
  COLLECT_TS       DATE          NOT NULL,
  SQL_ID           VARCHAR2(13)  NOT NULL,
  PLAN_HASH_VALUE  NUMBER        NOT NULL,  -- v$sqlstats는 (sql_id, plan_hash_value) 단위라 PK에 포함
  EXECUTIONS_D     NUMBER,
  ELAPSED_US_D     NUMBER,
  CPU_US_D         NUMBER,
  BUFFER_GETS_D    NUMBER,
  DISK_READS_D     NUMBER,
  ROWS_D           NUMBER,
  SQL_TEXT         VARCHAR2(1000),         -- 앞부분만, 전문은 조회 시 v$sql
  CONSTRAINT PK_MON_SQLSTAT_DELTA PRIMARY KEY (DB_ID, COLLECT_TS, SQL_ID, PLAN_HASH_VALUE)
);

-- ③ Lock 1분 요약 (실시간 값은 메모리)
CREATE TABLE MON_LOCK_SAMPLE (
  DB_ID              VARCHAR2(30) NOT NULL,
  COLLECT_TS         DATE         NOT NULL,
  TX_WAIT_MAX        NUMBER,
  TM_WAIT_MAX        NUMBER,
  TM_HOLDER_OVER_MAX NUMBER,               -- 장애 판정 수(60초↑ 블로킹 TM Holder, ③-1 기준)의 1분 내 최대값
  INCIDENT_YN        CHAR(1),
  CONSTRAINT PK_MON_LOCK_SAMPLE PRIMARY KEY (DB_ID, COLLECT_TS)
);

-- 장애 처리(KILL) 감사 로그
CREATE TABLE MON_KILL_AUDIT (
  AUDIT_ID      NUMBER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  DB_ID         VARCHAR2(30)  NOT NULL,
  KILL_TS       TIMESTAMP     DEFAULT SYSTIMESTAMP NOT NULL,
  EXECUTED_BY   VARCHAR2(64)  NOT NULL,     -- 대시보드 로그인 사용자
  SID           NUMBER        NOT NULL,
  SERIAL#       NUMBER        NOT NULL,
  INSTANCE_NAME VARCHAR2(16),               -- 접속 인스턴스(v$instance), RAC에서 어느 노드였는지 기록용
  USERNAME      VARCHAR2(128),
  PROGRAM       VARCHAR2(84),
  MACHINE       VARCHAR2(64),
  LOCK_OBJECT   VARCHAR2(261),
  LAST_CALL_ET  NUMBER,
  REASON        VARCHAR2(100),              -- 'TM_HOLDER_INCIDENT' / 'MANUAL'
  RESULT        VARCHAR2(20),               -- SUCCESS / SKIPPED(재조회 시 없음) / FAILED
  ERR_MSG       VARCHAR2(300)
);

-- ⑧ 점검 결과 (10분)
CREATE TABLE MON_CHECK_RESULT (
  DB_ID        VARCHAR2(30)   NOT NULL,
  CHECK_TS     DATE           NOT NULL,
  CHECK_TYPE   VARCHAR2(30)   NOT NULL,   -- TABLESPACE / TABLE_SIZE / FRA / TEMP / JOB_FAIL / INVALID_OBJ / STALE_STATS
  TARGET_NAME  VARCHAR2(261)  NOT NULL,   -- 테이블스페이스명, OWNER.TABLE, 잡 이름 등
  SEVERITY     VARCHAR2(10)   NOT NULL,   -- CRIT / WARN / INFO / OK(점검 완료·이상 없음, target_name='*') / ERROR(점검 실패)
  METRIC_VALUE NUMBER,                    -- 사용률(%), 크기(GB), 건수
  THRESHOLD    NUMBER,
  DETAIL_JSON  CLOB,                      -- 카드 보조 문구·상세 표시용 값
  ACK_BY       VARCHAR2(64),              -- 확인 처리자 (추후 확장)
  ACK_TS       DATE,
  CONSTRAINT PK_MON_CHECK_RESULT PRIMARY KEY (DB_ID, CHECK_TS, CHECK_TYPE, TARGET_NAME)
);

-- 테이블 크기 일 스냅샷 (증가량 계산용, dba_hist_seg_stat 대체)
CREATE TABLE MON_SEGMENT_SIZE (
  DB_ID        VARCHAR2(30)  NOT NULL,
  SNAP_DATE    DATE          NOT NULL,   -- TRUNC(SYSDATE)
  OWNER        VARCHAR2(128) NOT NULL,
  SEGMENT_NAME VARCHAR2(128) NOT NULL,
  SIZE_BYTES   NUMBER        NOT NULL,
  CONSTRAINT PK_MON_SEGMENT_SIZE PRIMARY KEY (DB_ID, SNAP_DATE, OWNER, SEGMENT_NAME)
);
-- 테이블스페이스 7일 추이는 MON_CHECK_RESULT의 일별 최댓값을 사용한다.
```

보관 주기 (설정값): 1분 테이블 30일, `MON_KILL_AUDIT` 1년. 일 배치로 삭제한다. `instance_metric_history`는 기존 보관 정책을 그대로 따른다.

### 4.3 수집 쿼리

**(1) 대기 클래스별 AAS — 60초마다 (기존 샘플러 쿼리를 확장)**

`InstanceMetricSamplerService.sampleOne()`의 기존 ASH 쿼리(7분류)를 **한 번의 스캔으로 7분류와 8분류를 같이** 집계하도록 바꾼다. 원본 DB 조회 횟수는 늘지 않는다. 결과를 Java에서 두 배열로 합산해 기존 `ash_*` 7개와 신규 `ash_wc_*` 8개를 모두 저장한다 (AAS = `COUNT / interval_sec`).

```sql
SELECT cat7, cat8, COUNT(*) AS cnt FROM (
  SELECT
    -- 기존 7분류 (Current Session용, 변경 없음)
    CASE
      WHEN h.session_state = 'ON CPU'        THEN 'cpu'
      WHEN h.wait_class = 'User I/O'         THEN 'user_io'
      WHEN h.wait_class = 'System I/O'       THEN 'system_io'
      WHEN h.event LIKE 'latch%'             THEN 'latch'
      WHEN h.event LIKE 'enq: TX%'           THEN 'tx_lock'
      WHEN h.event LIKE 'enq: TM%'           THEN 'tm_lock'
      WHEN h.wait_class NOT IN ('User I/O','System I/O','Idle') THEN 'other'
    END AS cat7,
    -- 신규 8분류 (이 대시보드용)
    CASE
      WHEN h.session_state = 'ON CPU'        THEN 'cpu'
      WHEN h.wait_class = 'User I/O'         THEN 'user_io'
      WHEN h.wait_class = 'System I/O'       THEN 'system_io'
      WHEN h.wait_class = 'Concurrency'      THEN 'concurrency'
      WHEN h.wait_class = 'Application'      THEN 'application'
      WHEN h.wait_class = 'Commit'           THEN 'commit'
      WHEN h.wait_class = 'Network'          THEN 'network'
      WHEN h.wait_class <> 'Idle'            THEN 'other'
    END AS cat8
  FROM   v$active_session_history h
  LEFT JOIN dba_users u ON h.user_id = u.user_id
  WHERE  h.sample_time >= SYSDATE - (:interval_sec / 86400)
  AND    h.session_type = 'FOREGROUND'
  AND    (u.username IS NULL OR u.username <> :monitoring_user)   -- 모니터링 계정 세션 제외
)
GROUP BY cat7, cat8;
-- cat7이 NULL인 행은 7분류 합산에서만, cat8이 NULL인 행은 8분류 합산에서만 뺀다.
```

- **배포 직후 빈 이력**: `ash_wc_*`는 배포 시점부터 쌓인다. 앱 기동 시(또는 신규 DB 등록 시) ASH로 최근 1시간을 1분 버킷으로 한 번 집계해 채워 넣는다(backfill, 체크리스트 1-10과 같은 방식). 그보다 이전 구간은 "수집 이전" 빈 구간으로 표시한다.

**(2) DB 요약 — 60초마다 (기존 샘플러, 코어 쿼리만 수정)**

```sql
SELECT value FROM v$sysmetric WHERE metric_name = 'Average Active Sessions';  -- db_time_aas (기존)
SELECT value FROM v$osstat    WHERE stat_name   = 'NUM_CPU_CORES';            -- ash_cpu_cores (NUM_CPUS → 변경)
```

- 코어 수 기준을 `NUM_CPU_CORES`로 통일한다(폐쇄망 실측으로 확정, 체크리스트). 샘플러·`getAshActivity()`·이 대시보드가 모두 같은 값을 쓰게 한다. `NUM_CPU_CORES`가 없으면 `NUM_CPUS`로 대체. **2026-09-25 구현 완료** — 공통 헬퍼 `CpuCores.query()`.
- **CPU 사용률(%)의 분모는 `NUM_CPUS`(논리 CPU)를 유지한다** (2026-09-25 결정). SMT 서버는 논리 CPU가 코어의 2~8배라 분모를 코어로 바꾸면 CPU%가 100%를 넘는다. 코어 수는 AAS와 비교하는 기준선에만 쓴다.
- 수집 실패는 기존 샘플러 방식대로 해당 분에 행을 남기지 않는다. 화면은 빈 분을 0으로 잇지 않고 **끊어서** 그리며, 최근 수집 실패는 상태 pill의 "수집 실패"로 표시한다.

**(3) ASH 구간 조회 — ⑤⑥⑦·드로어 공통 (저장 없음, 화면 요청 시)**

선택 구간 `[:from_ts, :to_ts]`에 대해 아래 인라인 뷰 `ash_base`를 공통으로 쓴다. 분류 CASE는 (1)의 `cat8`과 같다.

```sql
-- ash_base: 선택 구간의 ASH 행 + 8분류
SELECT h.sample_time, h.session_id AS sid, h.session_serial# AS serial#,
       u.username, h.program, h.module, h.machine,
       h.sql_id, h.sql_plan_hash_value,
       CASE WHEN h.session_state = 'ON CPU' THEN 'ON CPU' ELSE h.event END AS event,
       h.blocking_session, h.blocking_inst_id,
       CASE ... (1)의 cat8과 같은 8분류 ... END AS category
FROM   v$active_session_history h
LEFT JOIN dba_users u ON h.user_id = u.user_id
WHERE  h.sample_time BETWEEN :from_ts AND :to_ts
AND    h.session_type = 'FOREGROUND'
AND    (u.username IS NULL OR u.username <> :monitoring_user)
```

- **AAS = 샘플 수 × 샘플 간격(초) ÷ 구간 길이(초)**. `v$active_session_history`는 간격 1초, `dba_hist_active_sess_history`는 10초.
- **보관 범위 밖 구간**: `:from_ts`가 `SELECT MIN(sample_time) FROM v$active_session_history`보다 이르면, 그 앞부분만 `dba_hist_active_sess_history`(같은 컬럼, `dbid`/`instance_number`는 접속 인스턴스로 고정)에서 읽고 샘플 간격 10초로 환산해 합친다. 어느 소스를 썼는지 응답에 `source: "ash" | "awr" | "mixed"`로 내려 화면 데이터 소스 라벨에 표시한다.
- **시각 기준**: `:from_ts`/`:to_ts`는 DB 시각(SYSDATE 기준)이다. ②의 `instance_metric_history`는 앱 서버 epoch ms라, 60초 샘플러가 매 사이클 `SYSDATE`를 같이 읽어 인스턴스별 "DB 시각 − 앱 시각" 차이를 메모리에 캐시하고, `/api/metric_history` 응답에 그 값(`dbClockOffsetMs`, 필드 추가 — 원본 DB 추가 조회 없음)을 내려주고 화면은 그 차이로 선택 구간을 DB 시각으로 바꿔 요청한다 (기존 `getAshActivity()`가 SYSDATE를 따로 읽는 것과 같은 이유 — 도커/폐쇄망에서 DB와 앱 서버 시간대가 다를 수 있음).
- 조회 타임아웃은 기존 `ashActivityQueryTimeoutSeconds`를 재사용한다. 선택 구간은 최대 24시간으로 제한한다.
- RAC 환경이라도 `v$active_session_history`만 사용한다(접속 인스턴스 기준, gv$·`inst_id` 미사용 — 2026-09-06 원칙).

**(4) SQL 통계 델타 — 60초마다**

```sql
SELECT sql_id, plan_hash_value, executions, elapsed_time, cpu_time,
       buffer_gets, disk_reads, rows_processed,
       SUBSTR(sql_text, 1, 1000) AS sql_text
FROM   v$sqlstats
WHERE  last_active_time >= SYSDATE - 2/1440;
```

- 누적값이므로 애플리케이션에서 `(db_id, sql_id, plan_hash_value)`별 이전 수집값(메모리)과의 차이(델타)를 계산해 저장한다.
- **첫 관측은 기준값으로만 쓰고 저장하지 않는다**: 앱 재기동 직후나 새로 보인 SQL은 이전값이 없으므로, 누적값 전체를 델타로 넣으면 그 1분에 급등값이 찍힌다. 이전값을 메모리에 기록만 하고 다음 수집부터 델타를 저장한다.
- **델타가 음수면 그 1분은 버리고 기준값만 갱신한다**: 커서가 aged-out 되었다가 다시 올라와 누적값이 리셋된 경우다. 현재값으로 대체하면 리셋 이후 누적분 전체가 1분에 몰려 급등값이 된다.
- 델타가 모두 0인 행(1분 동안 실행 안 됨)은 저장하지 않는다.
- 조회 창(`last_active_time >= SYSDATE - 2/1440`)은 60초 샘플러의 주기 지연을 흡수하기 위한 여유다. 이 창에서 빠진 SQL의 이전값은 10분 동안 보이지 않으면 메모리에서 지운다.

---

## 5. 프레임별 명세

### ① KPI (6칸, 한 카드 — 2026-09-25 활성 세션·메모리 사용률 칸 추가)

| 항목 | 값 | 보조 표시 |
|---|---|---|
| 현재 AAS | 가장 최근 1분의 `AAS_TOTAL` | `hh:mm · 부하율 0.65` |
| 구간 평균 AAS | 조회 구간 평균 | `최근 1시간` |
| 최대 AAS | 조회 구간 최대 | `hh:mm · 코어의 166%` |
| CPU 코어 초과 | 구간 내 `AAS_TOTAL > CPU_CORES` 인 분 수 | 0분=정상(●), 1~9분=serious(◆), 10분 이상=crit(■) + 라벨 |
| 활성 세션 | 현재 ACTIVE 세션 수 (기존 대시보드 "활성 세션"과 같은 정의: `v$session` `status='ACTIVE'`, 백그라운드·username 없는 세션·모니터링 계정 제외) | 숫자 **아래 작은 가로 막대 1개** (10칸 세그먼트, DB별 세션 임계치의 5번째 값 = 가득 참, 색은 기존 활성 세션 임계치 색 규칙). 기존 대시보드의 도넛형 그래프를 대체 |
| 메모리 사용률 | (SGA + PGA 할당량) ÷ 물리 메모리 × 100 (기존 대시보드 "메모리"와 같은 정의: `v$sgastat` 합 + `v$pgastat` 'total PGA allocated', `v$osstat` PHYSICAL_MEMORY_BYTES) | 숫자(%) **아래 작은 가로 막대 1개** (10칸 세그먼트, 100% = 가득 참, 80% 이상 주의·90% 이상 위험 색 — 기존과 같음). 기존 대시보드의 도넛형 그래프를 대체 |

- **`AAS_TOTAL` = 그 분의 `ash_wc_*` 8분류 합계**다. ② 차트의 누적 높이와 KPI 숫자가 항상 같도록 `v$sysmetric`의 `db_time_aas`는 KPI에 쓰지 않는다.
- `CPU_CORES` = `ash_cpu_cores` (4.3 (2), `NUM_CPU_CORES`).
- **활성 세션 칸 (2026-09-25 오케스트레이터 요청 추가)**: 다른 4칸은 조회 구간(1분 수집) 기준이지만 이 칸은 **현재 값**이다. ③ Lock 실시간과 같은 리프레쉬 주기(기본 3초)로 갱신하며, 값은 `/api/dashboard/{dbId}/lock/realtime` 응답의 `activeSessions`로 받는다 — 같은 커넥션에서 `COUNT(*)` 1개만 더 하고 2초 캐시·진행 중 요청 공유를 그대로 타므로 원본 DB 조회 횟수가 늘지 않는다. 조회 실패 시 `—`(판단 보류, 0으로 그리지 않음). 막대 기준(임계치)은 기존 DB별 세션 임계치 설정(`currentSessionThresholds`)을 그대로 쓴다.
- **메모리 사용률 칸 (2026-09-25 오케스트레이터 요청 추가)**: 같은 `/lock/realtime` 응답의 `memoryPct`로 받는다. 메모리는 천천히 변하므로 서버가 DB별로 **60초 캐시**해 3초 주기마다 조회하지 않는다. 실패 시 `—`.

탑바의 DB 상태 pill: 최근 10분 평균 부하율 기준 `0.7 미만 정상 / 1.0 미만 주의 / 1.0 이상 초과`, 최근 수집이 3분 이상 비어 있으면 `수집 실패`.

```
-- 조회 구간 AAS 요약: 기존 /api/metric_history 재사용
GET /api/metric_history?db_id=..&range=15m|1h|3h|24h
    &metrics=ash_wc_cpu,ash_wc_user_io,ash_wc_system_io,ash_wc_concurrency,ash_wc_application,ash_wc_commit,ash_wc_network,ash_wc_other,ash_cpu_cores
-- range 키 15m, 3h는 새로 추가한다 (MonitorController의 range 매핑).
```

### ② 평균 활성 세션 (대기 클래스별) — 핵심 차트

- 형태: **누적 영역 차트**, x=시간(1분), y=AAS. 누적 순서와 색은 2.3 참조.
- **CPU 코어 기준선**: `CPU_CORES` 위치에 점선 + 오른쪽에 "CPU 코어 / n" 라벨. 그 위로 올라간 부분이 큐잉.
- 범례: 버튼형, 누르면 해당 클래스 숨김 (색은 유지, 다시 누르면 표시). 모두 숨길 수는 없다.
- 호버: 세로 크로스헤어 + 툴팁(시각, 클래스별 AAS 위에서부터, 합계와 코어 대비 %).
- **구간 선택**
  - 처음 열거나 조회 구간 변경 시: 구간 내 **10분 평균 부하가 가장 높은 구간을 자동 선택** (라벨 "자동 선택 구간 (구간 내 최대 부하)").
  - 클릭: 해당 시각 중심 5분 선택 / 드래그: 원하는 범위 선택 / 키보드: ←→ 이동, Enter 5분 선택.
  - 사용자가 직접 선택하면 차트 아래 **"선택 해제"** 버튼 표시 → 누르면 자동 선택으로 복귀.
  - 선택 구간은 반투명 사각형 + 점선 테두리.
- x축 눈금 간격: 15분 → 5분, 1시간 → 10분, 3시간 → 30분, 24시간 → 2시간.
- "표로 보기": 분 단위 표 (시각, 클래스별 값, 합계). 코어 초과 분은 붉은 배경.
- 데이터 소스 라벨: `Oracle 대기 클래스 기준 · v$active_session_history · 60초 수집 · 1분 평균 · 접속 인스턴스 기준`.
- 데이터: ① 과 같은 `/api/metric_history` 응답을 그대로 쓴다 (추가 조회 없음).

### ③ Lock 대기 세션 (실시간)

- 갱신: 리프레쉬 주기(기본 3초, 조절 가능). 최근 10분을 **시간 기준**으로 메모리에 유지한다(포인트 수 고정 아님 — 3초면 200포인트, 주기를 바꿔도 x축은 항상 10분).
- 헤더: "Lock 대기 세션" + `LIVE · n초 주기` 표시(n은 현재 설정값, 자동 갱신 중지 시 "일시정지됨").
- 상단 4칸:

| 항목 | 기준 |
|---|---|
| TX 대기 세션 | 0 정상 / 1~4 주의 / **5 이상 위험** |
| TM 대기 세션 | 0 정상 / 1~2 주의 / **3 이상 위험** |
| 블로킹 TM Holder (60초↑) | 장애 판정 수(③-1 기준). `n / 6개`, 3개 이상 주의("기준까지 n개"), **6개 이상 "장애 기준 도달"**. 보조 문구에 `전체 Holder m개` |
| 최장 last_call_et | TM Holder 중 최댓값, 30초 주의 / 60초 위험 |

- 차트: 계단형 선(step) 2개 (TX, TM), TX는 옅은 면 채움. 경고 기준 5건 점선. 오른쪽 끝에 현재값 직접 라벨 (`TX 14`, `TM 12`).
- 범례 줄 오른쪽에 장애 기준 문구 표시: `장애 기준: 60초↑ 블로킹 TM Holder 6개↑`.
- 장애 조건이 성립했던 시간대는 차트 배경을 붉게 칠한다. KILL 실행 시각은 붉은 세로선 + "KILL" 라벨.

```sql
-- TX / TM 대기 건수 (리프레쉬 주기마다)
SELECT COUNT(CASE WHEN event LIKE 'enq: TX%' THEN 1 END) AS tx_cnt,
       COUNT(CASE WHEN event LIKE 'enq: TM%' THEN 1 END) AS tm_cnt
FROM   v$session
WHERE  state = 'WAITING'
AND    (event LIKE 'enq: TX%' OR event LIKE 'enq: TM%');

-- TM Lock Holder (리프레쉬 주기마다) — 세션 단위로 1행 (DISTINCT)
SELECT /*+ rule */
       s.sid, s.serial#, MAX(s.type) AS session_type,
       MAX(s.username) AS username, MAX(s.program) AS program,
       MAX(s.machine) AS machine, MAX(s.status) AS status, MAX(s.last_call_et) AS last_call_et,
       MAX(s.blocking_session) AS blocking_session,   -- 장애 판정: 자기가 막혀 있으면 제외
       MIN(l.id1)  AS obj_id,          -- 대표 객체 1개 (여러 개면 obj_cnt로 "외 n개" 표시)
       COUNT(DISTINCT l.id1) AS obj_cnt,
       MAX(l.block) AS block,
       (SELECT COUNT(*) FROM v$session w WHERE w.blocking_session = s.sid) AS waiters
FROM   v$lock l
JOIN   v$session s ON s.sid = l.sid
WHERE  l.type  = 'TM'
AND    l.lmode > 0
GROUP  BY s.sid, s.serial#;
```

- **세션 단위 집계(필수)**: 한 세션이 여러 테이블에 TM Lock을 잡으면 `v$lock`에 행이 여러 개 나온다. 행 수로 세면 Holder 수가 부풀어 **장애 판정이 오탐**하므로 반드시 `sid, serial#`로 묶는다.
- **한 번 조회해서 두 가지로 나눠 쓴다** (쿼리에는 필터를 넣지 않는다):
  - **장애 판정 수** (③-1, v2 `getFailureProb()`와 같은 규칙): `last_call_et >= tmHolder.lastCallEtSec` AND `blocking_session IS NULL` AND `waiters > 0`인 행의 수. v2 쿼리처럼 세션 종류로 거르지 않는다.
  - **KILL 대상 목록** (③-1 결정: Holder 전체): `session_type = 'USER'`인 행 전체. 설정 `tmHolder.blockingOnly`/`inactiveOnly`가 켜져 있으면 여기서만 `block > 0`/`status = 'INACTIVE'`로 좁힌다. 백그라운드 세션은 목록에도 넣지 않는다.
- 장애 판정 수가 v2 화면(`/api/failure_prob`의 `count`)과 같은 순간 같은 값이어야 한다. 구현 후 같은 DB에서 두 값을 비교해 확인한다.
- `/*+ rule */`은 기존 `getFailureProb()`가 폐쇄망에서 검증된 방식 그대로다 (v$lock 조인이 느린 환경 대응).
- **부하 제어 (필수)**:
  - 쿼리 타임아웃은 기존 `lockQueryTimeoutSeconds`를 쓴다 (v$lock 스캔 49초 실측 전례).
  - 서버는 DB별로 결과를 `lock.refreshSecMin`(2초) 동안 캐시하고, 같은 DB에 대한 요청이 진행 중이면 새 조회를 띄우지 않고 그 결과를 기다린다 (기존 `failureProbInFlight` 패턴). 보는 사람이 여러 명이어도 원본 DB 조회는 주기당 1회다.
  - 조회가 타임아웃·실패하면 직전 값을 "판단 보류"로 표시하고 0으로 그리지 않는다 (5장 상태 정의 참조).
- TX만 row lock으로 좁히려면 `event = 'enq: TX - row lock contention'` 사용 (설정값).
- `obj_id` → 객체명은 `dba_objects`를 매 주기 조인하지 말고 수집기에서 캐시한다 (11g에서 딕셔너리 뷰 조인으로 무응답 블로킹된 전례, `getFailureProb()` 주석 참고).
- RAC: `v$lock`, `v$session`만 사용(접속 인스턴스 기준, `inst_id` 미사용 — 2026-09-06 gv$ 미사용 원칙).

### ③-1 TM Lock 장애 처리

**장애 조건** (2026-09-25 결정 — **기존 v2 대시보드 `getFailureProb()`와 같은 기준**): 아래를 모두 만족하는 **블로킹 TM Holder**가 **6개 이상** (60초·6개는 설정값).
- TM Lock을 잡고 있고(`v$lock.type='TM'`, `lmode > 0`)
- `last_call_et ≥ 60초`
- 자기는 다른 세션에 막혀 있지 않고(`blocking_session IS NULL`)
- 실제로 다른 세션을 막고 있다(`waiters > 0`, 즉 `v$session.blocking_session = 이 SID`인 세션이 있음)

커밋하지 않고 오래 도는 정상 배치처럼 **아무도 막지 않는 Holder는 판정 수에 넣지 않는다** (잘못된 경보 방지). v2와 새 화면을 스위치로 오가므로 같은 순간에 한쪽만 장애로 뜨지 않도록 기준을 하나로 맞춘다. **KILL 대상은 판정 기준과 별개로 TM Lock Holder 전체**다 (아래 결정).

조건 충족 시:
1. ③ 카드 전체가 붉게 바뀌고(테두리·배경·은은한 깜빡임, `prefers-reduced-motion`이면 깜빡임 없음), 카드 상단에 붉은 알림 바 표시:
   `TM Lock 장애 감지 — 60초 이상 다른 세션을 막고 있는 TM Lock Holder n개 · 기준 6개 이상 · 전체 Holder m개`
2. 알림 바에 **"장애 처리 · TM Holder m개 KILL"** 버튼.
3. 버튼 클릭 → 카드 안에 확인 패널(모달 대신 인라인):
   - 대상 목록: 체크박스(기본 전체 선택), SID,SERIAL#, 사용자·프로그램, 잠금 객체, last_call_et(60초 미만은 "60초 미만" 표시), 막고 있는 세션 수
   - 실행될 `ALTER SYSTEM KILL SESSION` 구문 미리보기
   - `취소` / `KILL 실행 (n개)` 버튼
4. KILL 실행:
   - **실행 직전에 Holder 쿼리를 다시 실행**해서, 선택된 세션 중 아직 존재하고 `SERIAL#`이 같은 세션만 KILL. 사라진 세션은 `SKIPPED`로 기록.
   - 세션별로 `ALTER SYSTEM KILL SESSION 'sid,serial#' IMMEDIATE`. RAC라도 `@inst_id`를 붙이지 않는다 — 대상은 모두 접속 인스턴스의 `v$lock`/`v$session`에서 찾은 세션이므로 같은 커넥션에서 실행한다.
   - 모든 결과를 `MON_KILL_AUDIT`에 저장.
   - 완료 후 카드 아래에 처리 기록 1줄 표시: `hh:mm:ss 장애 처리 완료 · TM Lock Holder n개 세션 KILL (SID …)`.
5. 조건이 해소되면 카드는 정상 표시로 돌아간다. **열려 있던 확인 패널은 자동으로 닫지 않는다** (2026-09-25 결정):
   - 패널 상단에 `장애 조건 해소됨 (현재 블로킹 Holder n개)` 안내를 표시하고, 조건이 다시 성립하면 안내만 사라진다. 패널 자체는 열리고 닫히지 않는다 (값이 기준 근처에서 오르내려도 깜빡이지 않게).
   - 대상 목록은 매 주기 갱신한다. 이미 종료된 세션은 목록에 `종료됨`으로 표시하고 체크박스를 비활성화한다. 새로 생긴 Holder는 목록 아래에 추가하되 **자동으로 체크하지 않는다** (운영자가 본 적 없는 세션이 KILL되지 않게).
   - KILL 여부는 운영자가 판단하고, 패널은 `취소`/`KILL 실행`/Esc로만 닫힌다. 실행 시에는 4번의 재조회 규칙이 그대로 적용된다.
6. **DB 전환 시 오전송 방지 (필수)**:
   - 확인 패널을 여는 순간의 `db_id`를 패널에 고정하고, KILL 요청 본문에 그 `db_id`를 담는다. 클릭 시점의 "현재 선택 DB" 전역값을 읽지 않는다.
   - 패널이 열린 상태에서 사이드바로 DB를 바꾸면 패널을 닫는다 (다른 DB의 대상 목록을 새 DB 화면 위에 남기지 않는다).
   - 서버는 요청의 `db_id`로만 커넥션을 잡아 재조회하고, 그 결과와 요청 대상의 교집합(`sid`+`serial#` 일치)만 KILL한다. `canAccessDb` + 관리자 권한을 모두 확인한다.

**보안·안전 요건 (필수)**
- KILL 버튼은 관리자 권한 사용자에게만 표시.
- 수집 계정과 KILL 계정을 분리하는 것을 권장 (KILL 계정만 `ALTER SYSTEM` 권한).
- `s.type = 'USER'` 조건은 제거 불가. 백그라운드 프로세스 KILL은 인스턴스 장애로 이어진다.

> **결정 (2026-09-25 오케스트레이터) — KILL 대상 범위: TM Lock Holder 전체**
> 장애 조건(60초 이상 블로킹 TM Holder 6개 이상)에 도달한 시점이면 운영 경험상 업무가 거의 마비된 상태이므로, 빠른 복구를 우선해 **TM Lock Holder 전체를 KILL 대상으로 하고 확인 패널은 기본 전체 선택**으로 둔다 (60초 미만 Holder 포함, 목록에는 "60초 미만"으로 표시).
> - 커밋 전 정상 배치·긴 트랜잭션도 대상에 들어갈 수 있다는 점은 감수한다. 운영자는 확인 패널에서 체크를 해제해 개별 제외할 수 있다.
> - 설정값 `tmHolder.blockingOnly`(기본 `false`), `tmHolder.inactiveOnly`(기본 `false`)는 남겨 두어, 운영 환경에 따라 대상을 좁힐 수 있게 한다.
> - `s.type = 'USER'` 고정, 실행 직전 재조회, 감사 기록은 그대로 필수다.

### ④ 진단 배너

- 제목 줄: `자동 선택 구간 (구간 내 최대 부하) 13:52 – 14:02 (11분) · 평균 AAS 24.3` (직접 선택 시 "선택 구간").
- 배너: 선택 구간에서 비중이 가장 큰 클래스와 비율 + 클래스별 조치 문구. 구간 평균이 코어를 넘으면 "구간 평균이 CPU 코어 n개를 넘어 세션이 대기열에 쌓이고 있습니다." 추가.

| 클래스 | 조치 문구 |
|---|---|
| CPU | CPU 사용이 대부분입니다. 논리 읽기(buffer gets)가 많은 SQL의 실행계획부터 확인하세요. |
| User I/O | 디스크 읽기 대기입니다. Full Scan, 인덱스 누락, 대량 배치 SQL을 확인하세요. |
| System I/O | LGWR·DBWR 같은 백그라운드 쓰기 대기입니다. 스토리지 쓰기 지연을 확인하세요. |
| Concurrency | 같은 블록이나 라이브러리 캐시에 대한 경합입니다. 핫 블록과 하드 파싱을 확인하세요. |
| Application | 잠금(enq: TX 행 잠금 / enq: TM 테이블 잠금) 대기입니다. 블로킹 세션과 커밋되지 않은 트랜잭션을 확인하고, TX/TM 구분은 ③ Lock 카드와 ⑦ Top 대기 이벤트에서 확인하세요. |
| Commit | log file sync 대기입니다. 커밋 빈도와 redo 로그 디스크 쓰기 속도를 확인하세요. |
| Network | SQL*Net 대기입니다. 대량 fetch나 클라이언트·네트워크 지연을 확인하세요. |
| Other | Configuration, Cluster, Scheduler 등 기타 대기의 합입니다. ⑦ Top 대기 이벤트에서 어떤 이벤트인지 확인하세요. |

### ⑤ Top SQL / ⑥ Top 세션 / ⑦ Top 대기 이벤트

공통:
- 선택 구간 기준, ASH(`ash_base`, 4.3 (3))에서 집계. 세 목록을 **API 한 번**(`/top`)으로 받는다.
- **AAS = 샘플 수 × 샘플 간격(초) ÷ 구간 길이(초)** (ASH 1초, AWR 10초)
- 데이터 소스 라벨: `v$active_session_history` (보관 범위 밖이 섞이면 `+ dba_hist_active_sess_history`).
- 각 행: 식별자, AAS 값, 구간 전체 대비 %, **대기 클래스 구성 막대**(가로 길이 = 1위 대비 비율, 색 = 클래스 구성).
- 표시 개수: Top SQL 5, Top 세션 5, Top 대기 이벤트 5 (⑧ 점검 알림 공간 확보를 위해 5개로 제한).
- 패널 높이는 내용만큼 (2.2 참조).
- **행 클릭(또는 Enter/Space) → 오른쪽 상세 드로어** (6장).

⑤ Top SQL — 행 구성: SQL_ID(mono), AAS·%, 구성 막대, SQL 텍스트 한 줄(말줄임).

```sql
SELECT sql_id, category, COUNT(*) AS cnt
FROM   (ash_base)
WHERE  sql_id IS NOT NULL
GROUP  BY sql_id, category;
-- 애플리케이션에서 sql_id별 합계로 정렬해 상위 5개, 분류별 값은 막대 구성에 사용
-- SQL 텍스트는 v$sqlstats.sql_text(앞부분)에서 상위 5개만 IN 조회. aged-out이면 "SQL 텍스트 없음(공유 풀에서 밀려남)"
```

⑥ Top 세션 — 행 구성: `SID n`, AAS·%, 구성 막대, 사용자·프로그램, Application 비중 30% 이상이고 블로커가 있으면 붉은 `블로커 SID n`.

```sql
SELECT sid, serial#, MAX(username) AS username, MAX(program) AS program,
       category, MAX(blocking_session) AS blocking_session,
       MAX(blocking_inst_id) AS blocking_inst_id,
       COUNT(*) AS cnt
FROM   (ash_base)
GROUP  BY sid, serial#, category;
```

- `blocking_inst_id`가 접속 인스턴스 번호(`v$instance.instance_number`)와 다르면 블로커는 다른 RAC 노드의 세션이다. 이때는 `블로커: 다른 인스턴스(n번) SID m`으로만 표시하고 **세션 상세로 이동하지 않는다** (로컬 `v$session`에서 같은 SID를 찾으면 엉뚱한 세션이 나온다).

⑦ Top 대기 이벤트 — 행 구성: 이벤트명(mono), AAS·%, 단색 막대(분류 색), 분류명. CPU는 `ON CPU`로 표시.

```sql
SELECT * FROM (
  SELECT event, category, COUNT(*) AS cnt
  FROM   (ash_base)
  GROUP  BY event, category
  ORDER  BY cnt DESC
) WHERE ROWNUM <= 5;      -- 11g 대상 DB가 있어 FETCH FIRST(12c+) 대신 ROWNUM
```


### ⑧ 점검 알림

대시보드 맨 아래 **가로 한 줄** 프레임. 임계치를 넘은 점검 항목을 카드로 나열해서, 운영자가 점검·조치를 요청받는 창구로 쓴다. 실시간이 아니라 **주기 점검** 결과를 보여준다 (가벼운 항목 10분, 무거운 항목 1일 — 4.1 참조).

**헤더**: `점검 알림` + 심각도별 건수(`■ 위험 2 · ▲ 주의 4 · ○ 확인 2`) + `마지막 점검 hh:mm · 10분 주기` + 필터 세그먼트 `전체 / 위험 / 주의 / 확인`.

**카드 (가로 배열, 위험 → 주의 → 확인 순)**
- 1행: 심각도 pill + 항목 종류 (예: `위험 · 테이블스페이스`)
- 2행: 대상 이름(mono, 말줄임) + 값 (예: `APP_DATA  92.4%`)
- 3행: 사용률/크기 항목만 게이지 막대 + 임계치 눈금
- 4행: 보조 문구 한 줄 (예: `438.9 / 446.0 GB · 여유 7.1 GB · 약 3일 후 가득 참`)
- 왼쪽 테두리 색 = 심각도. 위험 카드는 배경도 옅게 붉다.
- 카드가 화면 폭보다 많으면 **프레임 안에서만** 가로 스크롤.
- 카드 클릭 → 상세 드로어 (6.4).

**점검 항목과 기준 (설정값)**

| 항목 | 주의 | 위험 | 주기 | 소스 |
|---|---|---|---|---|
| 테이블스페이스 사용률 | **97%** | **98%** | 10분 | `dba_tablespace_usage_metrics` (autoextend MAXSIZE 기준 사용률). 2026-09-25 결정 — v2 대시보드 기준(97%)과 맞춤 |
| 테이블 용량 | 30 GB | 40 GB | 1일 | `dba_segments` + 증가량은 `MON_SEGMENT_SIZE` |
| FRA 사용률 (회수 가능 제외) | 80% | 90% | 10분 | `v$recovery_file_dest` (FRA 미설정 DB는 점검 제외), `v$recovery_area_usage` |
| TEMP 사용률 | 75% | 90% | 10분 | `dba_temp_free_space`(사용량) + `dba_temp_files`(autoextend MAXSIZE 기준 최대 크기) |
| 스케줄러 잡 실패 | 1회 | 2회 연속 | 10분 | `dba_scheduler_job_run_details` |
| 무효 객체 | 1개 이상 (확인) | — | 1일 | `dba_objects` |
| 통계 정보 오래됨 | 1개 이상 (확인) | — | 1일 | `dba_tab_statistics` |

- 카드 1행 오른쪽에 항목별 마지막 점검 시각을 작게 표시한다 (`10분 주기 · hh:mm` / `1일 주기 · 03:00`). 헤더의 `마지막 점검`은 10분 항목 기준이다.

> ⑧ 점검 소스는 모두 기본 제공 뷰다. 테이블 증가 추이는 `dba_hist_seg_stat`(AWR 보관 기간·스냅샷 대상 세그먼트에 따라 누락이 있음) 대신 `MON_SEGMENT_SIZE` 일 스냅샷 차이로 계산한다.

**점검 쿼리**

```sql
-- 테이블스페이스 사용률
SELECT m.tablespace_name,
       ROUND(m.used_space      * t.block_size / POWER(1024,3), 1) AS used_gb,
       ROUND(m.tablespace_size * t.block_size / POWER(1024,3), 1) AS max_gb,
       ROUND(m.used_percent, 1)                                    AS used_pct
FROM   dba_tablespace_usage_metrics m
JOIN   dba_tablespaces t ON t.tablespace_name = m.tablespace_name
WHERE  m.used_percent >= :warn_pct
ORDER  BY m.used_percent DESC;

-- 테이블 용량
SELECT owner, segment_name,
       ROUND(SUM(bytes) / POWER(1024,3), 1) AS size_gb
FROM   dba_segments
WHERE  segment_type IN ('TABLE','TABLE PARTITION','TABLE SUBPARTITION')
AND    owner NOT IN ('SYS','SYSTEM','AUDSYS','XDB','MDSYS','CTXSYS')
GROUP  BY owner, segment_name
HAVING SUM(bytes) >= :warn_gb * POWER(1024,3)
ORDER  BY size_gb DESC;

-- FRA (FRA를 설정하지 않은 DB는 space_limit = 0 → 행을 제외해 ORA-01476(0으로 나누기)을 막는다)
SELECT name,
       ROUND(space_limit / POWER(1024,3))                                         AS limit_gb,
       ROUND(space_used  / POWER(1024,3))                                         AS used_gb,
       ROUND((space_used - space_reclaimable) / NULLIF(space_limit, 0) * 100, 1)  AS used_pct
FROM   v$recovery_file_dest
WHERE  space_limit > 0;
-- 결과가 0행이면 "FRA 미설정"으로 보고 카드를 만들지 않는다 (오류·위험으로 표시하지 않음).

-- TEMP (v$sort_segment.total_blocks는 지금까지 할당된 정렬 세그먼트 크기일 뿐 최대 크기가 아니라서 쓰지 않는다)
SELECT f.tablespace_name,
       ROUND((f.tablespace_size - f.free_space) / POWER(1024,3), 1)           AS used_gb,
       ROUND(m.max_bytes / POWER(1024,3), 1)                                  AS max_gb,
       ROUND((f.tablespace_size - f.free_space) / NULLIF(m.max_bytes, 0) * 100, 1) AS used_pct
FROM   dba_temp_free_space f
JOIN  (SELECT tablespace_name,
              SUM(CASE WHEN autoextensible = 'YES' THEN GREATEST(maxbytes, bytes) ELSE bytes END) AS max_bytes
       FROM   dba_temp_files
       GROUP  BY tablespace_name) m
  ON   m.tablespace_name = f.tablespace_name;
-- 드로어(6.4) "관련 객체"의 TEMP 사용 세션·SQL은 v$tempseg_usage에서 따로 조회한다.

-- 스케줄러 잡 실패 (최근 24시간)
SELECT owner, job_name, status, actual_start_date, error#, additional_info
FROM   dba_scheduler_job_run_details
WHERE  log_date > SYSDATE - 1
AND    status <> 'SUCCEEDED'
ORDER  BY log_date DESC;

-- 무효 객체
SELECT owner, object_type, object_name, last_ddl_time
FROM   dba_objects
WHERE  status = 'INVALID'
AND    owner NOT IN ('SYS','SYSTEM','AUDSYS','XDB','MDSYS','CTXSYS');

-- 통계 정보 오래됨
SELECT owner, table_name, partition_name, last_analyzed, num_rows
FROM   dba_tab_statistics
WHERE  stale_stats = 'YES'
AND    owner NOT IN ('SYS','SYSTEM','AUDSYS','XDB','MDSYS','CTXSYS');

-- 화면 조회 (항목 종류별 최신 점검 결과) — 저장소(SQLite/H2) 쿼리
-- 10분 항목과 1일 항목의 check_ts가 다르므로 전체 MAX(check_ts)가 아니라 check_type별 MAX를 쓴다.
SELECT r.check_type, r.target_name, r.severity, r.metric_value, r.threshold, r.detail_json, r.check_ts
FROM   mon_check_result r
JOIN  (SELECT check_type, MAX(check_ts) AS last_ts
       FROM   mon_check_result
       WHERE  db_id = :db_id
       GROUP  BY check_type) x
  ON   x.check_type = r.check_type AND x.last_ts = r.check_ts
WHERE  r.db_id = :db_id
ORDER  BY CASE r.severity WHEN 'CRIT' THEN 0 WHEN 'WARN' THEN 1 ELSE 2 END, r.metric_value DESC;
```

- 점검이 정상(임계치 미만)이면 해당 항목 행을 남기지 않는다. 대신 "이 항목을 점검했다"는 기록이 있어야 카드가 사라진 이유(해소 vs 점검 실패)를 구분할 수 있으므로, 점검 실행마다 항목 종류별로 `severity='OK'`, `target_name='*'` 행을 1개 남긴다. 화면은 `OK` 행을 카드로 그리지 않고 마지막 점검 시각 표시에만 쓴다.
- 점검 쿼리가 실패(권한 없음, 타임아웃)하면 그 항목은 `severity='ERROR'` 행과 오류 메시지를 남기고, 화면은 `점검 실패 · 항목명` 카드(회색)로 표시한다. 실패를 "알림 없음"으로 보이게 하지 않는다.

- 시스템 스키마 제외 목록은 설정값으로 둔다.
- `dba_*` 뷰 조회 권한(`SELECT_CATALOG_ROLE` 또는 개별 GRANT)이 수집 계정에 있어야 한다.

### 화면 상태 정의 (프레임 아님 — ①~⑧ 공통 규칙, 필수)

값이 없거나 실패한 상황을 **"정상·0"으로 보이게 하지 않는 것**이 원칙이다. 빈 차트나 0을 정상처럼 그리면 장애를 놓친다.

| 상태 | 판정 | 표시 |
|---|---|---|
| 첫 로딩 | 탭 진입·DB 선택 직후, 응답 전 | 프레임별 **스켈레톤**(회색 블록 + 차트 축·격자만)을 즉시 그린다. 검은 빈 화면을 두지 않는다 (체크리스트 1-2 불만 사항). |
| DB 전환 중 | 사이드바에서 다른 DB 선택 | 진행 중인 요청을 모두 취소(`AbortController`)하고 DB 세대 번호를 올린다. 이전 DB 응답이 늦게 도착하면 세대 번호가 달라 버린다. 화면은 스켈레톤으로 바꾸고 열린 드로어·KILL 확인 패널은 닫는다. (체크리스트 1-5 "DB 이동 후 갱신 지연·검은 화면" 대응) |
| 데이터 공백 | 신규 DB 등록·앱 재기동 직후, 수집 전 구간 | ② 차트는 값이 없는 분을 **0으로 잇지 않고 끊어서** 그리고, 공백 구간에 `수집 이전`을 옅게 표시. ① KPI는 계산 가능한 값만 표시하고 나머지는 `—`. |
| 수집 지연 | 가장 최근 수집 시각이 3분 이상 지남 | ①② 카드 제목 옆에 `수집 지연 · 마지막 hh:mm` 배지, 탑바 상태 pill은 `수집 실패`. |
| 접속 실패 | 대상 DB 연결 불가 | 탑바 pill `수집 실패` + ORA 메시지(툴팁). ③은 `연결 실패`, ⑤⑥⑦은 오류 문구 + `다시 시도` 버튼. |
| ③ 판단 보류 | Lock 조회 타임아웃·실패, `/api/failure_prob` 503 | 직전 값을 흐리게 유지하고 `판단 보류 · 조회 지연`을 표시. 차트에 0을 찍지 않고 그 구간을 끊는다. 장애 표시(붉은 카드)는 켜거나 끄지 않고 직전 상태를 유지한다. **판단 보류 중에는 KILL 실행 버튼을 비활성화**한다 (서버 재조회도 같은 이유로 실패할 수 있으므로). |
| ASH 조회 실패 | ⑤⑥⑦·드로어 ASH 쿼리 오류(권한 없음 ORA-00942, 타임아웃) | 해당 패널에 `ASH 조회 실패 — 권한 또는 조회 시간 초과` + 오류 코드. 다른 프레임은 그대로 동작. |
| 빈 결과 | 선택 구간에 활성 세션 없음 | ⑤⑥⑦에 `선택 구간에 활성 세션이 없습니다`. ④ 진단 배너는 `선택 구간 평균 AAS 0 — 부하 없음`. |
| DB 권한 없음 | `canAccessDb` 실패(403) | 대시보드 영역 전체에 `이 DB를 조회할 권한이 없습니다` 한 줄만 표시. 다른 프레임을 그리지 않는다. |
| 비관리자 + 장애 | 장애 조건 성립, 관리자 아님 | 붉은 알림 바는 그대로 보이고, KILL 버튼 자리에 `장애 처리는 관리자만 할 수 있습니다` 안내. 드로어의 `KILL 구문 복사`도 숨긴다. |
| 비Oracle DB | MySQL/MariaDB/MSSQL/PostgreSQL 선택 | 이 대시보드와 전환 스위치를 표시하지 않고, 기존 RDB 대시보드를 그대로 보여준다 (RDB 쪽은 변경 없음). |
| ⑧ 점검 실패 | 점검 쿼리 권한 없음·타임아웃 (`severity='ERROR'`) | 회색 `점검 실패 · 항목명` 카드 + 오류 메시지. 헤더 건수에 `실패 n` 추가. |
| 화면 숨김 | 다른 탭으로 이동, 브라우저 탭 숨김(`document.hidden`) | 모든 폴링을 멈추고, 다시 보이면 즉시 1회 갱신 후 재개한다. "자동 갱신 중지" 상태면 재개하지 않는다. |

---

## 6. 상세 드로어

- 오른쪽에서 열리는 패널(폭 470px, 모바일은 전체 폭), 뒤 배경은 어둡게. 레이아웃을 밀지 않는다(오버레이).
- 닫기: ✕ 버튼, 배경 클릭, Esc. 닫으면 클릭했던 행으로 포커스 복귀.
- 드로어 안에서 다른 상세로 이동하면 **‹ 뒤로** 버튼이 생긴다 (스택 방식).
- 선택 구간이 바뀌면 열려 있는 드로어 내용도 새 구간 기준으로 다시 그린다.
- **값의 출처를 구분한다**: "선택 구간 활동"은 ASH(`ash_base`, 과거), "현재 상태"는 `v$session`(지금). 세션이 이미 종료되었으면 현재 상태 영역에 "세션 종료됨"과 마지막 샘플 시각을 표시한다.
- 각 상세 하단에 "조회 쿼리 보기"(접힘)로 사용 쿼리를 보여준다.

### 6.1 세션 상세 (⑥ 행, 또는 SQL/이벤트 상세의 세션 목록에서 진입)

| 섹션 | 항목 |
|---|---|
| 헤더 | `SID n, serial#` + 상태 pill(ACTIVE / BACKGROUND) + 사용자 · 프로그램 |
| 기본 정보 | USERNAME, PROGRAM, MODULE, MACHINE, OSUSER, 서버 PID(SPID), LOGON_TIME |
| 현재 상태 | STATE, EVENT, WAIT_CLASS, 대기 시간, LAST_CALL_ET, BLOCKING_SESSION(있으면 붉게, 블로커의 사용자·상태 함께) |
| 선택 구간 활동 | AAS, 구간 전체 대비 %, 구성 막대, 클래스별 값·% 목록 |
| 현재 SQL | SQL_ID(누르면 SQL 상세로 이동), PLAN_HASH_VALUE, SQL 전문. 백그라운드는 "실행 중인 사용자 SQL이 없습니다." |
| 하단 버튼 | **KILL 구문 복사** (백그라운드 세션은 비활성, 관리자만 표시) |

```sql
-- 현재 상태
SELECT s.sid, s.serial#, s.username, s.status, s.program, s.module,
       s.machine, s.osuser, p.spid, s.logon_time, s.last_call_et,
       s.state, s.event, s.wait_class,
       ROUND(s.wait_time_micro / 1e6) AS wait_sec,
       s.blocking_session, s.sql_id, q.plan_hash_value, q.sql_fulltext
FROM   v$session s
LEFT JOIN v$process p ON p.addr = s.paddr
LEFT JOIN v$sql q     ON q.sql_id = s.sql_id
                     AND q.child_number = s.sql_child_number
WHERE  s.sid = :sid AND s.serial# = :serial;

-- 블로커 정보 (v$session.blocking_instance가 접속 인스턴스 번호와 같을 때만 조회,
--             다르면 "다른 인스턴스(n번) SID m"으로만 표시)
SELECT sid, serial#, username, status, program, last_call_et
FROM   v$session WHERE sid = :blocking_session;

-- 선택 구간 활동
SELECT category, COUNT(*) AS cnt
FROM   (ash_base)
WHERE  sid = :sid AND serial# = :serial
GROUP  BY category;
```

### 6.2 SQL 상세 (⑤ 행에서 진입)

| 섹션 | 항목 |
|---|---|
| 헤더 | SQL_ID + `AAS · 구간 전체의 n%` |
| SQL 정보 | SQL_ID, PLAN_HASH_VALUE, 구간 실행 횟수, 평균 수행 시간, 평균 Buffer Gets, 구간 DB Time |
| 선택 구간 활동 | 구성 막대 + 클래스별 값 |
| SQL 텍스트 | 전문 |
| 실행 세션 목록 | 이 SQL로 샘플된 세션(SID, 사용자, 현재 이벤트, 블로커, AAS). **누르면 세션 상세** |

```sql
-- 구간 SQL 통계
SELECT SUM(executions_d) AS execs,
       SUM(elapsed_us_d) / NULLIF(SUM(executions_d), 0) / 1000 AS ela_ms_per_exec,
       SUM(buffer_gets_d) / NULLIF(SUM(executions_d), 0)       AS gets_per_exec,
       SUM(elapsed_us_d) / 1e6                                  AS db_time_sec,
       MAX(plan_hash_value)                                     AS plan_hash_value
FROM   MON_SQLSTAT_DELTA
WHERE  db_id = :db_id AND sql_id = :sql_id
AND    collect_ts BETWEEN :from_ts AND :to_ts;

-- SQL 전문
SELECT sql_fulltext FROM v$sqlstats WHERE sql_id = :sql_id;

-- 선택 구간 활동 (구성 막대)
SELECT category, COUNT(*) AS cnt
FROM   (ash_base)
WHERE  sql_id = :sql_id
GROUP  BY category;

-- 실행 세션 목록
SELECT sid, serial#, MAX(username) AS username, COUNT(*) AS cnt
FROM   (ash_base)
WHERE  sql_id = :sql_id
GROUP  BY sid, serial#
ORDER  BY cnt DESC;
```

### 6.3 대기 이벤트 상세 (⑦ 행에서 진입)

| 섹션 | 항목 |
|---|---|
| 헤더 | 이벤트명 + `클래스 · AAS` |
| 이벤트 정보 | EVENT, 대기 클래스(8분류), 구간 평균 AAS, 구간 비중, 구간 |
| 확인할 점 | 클래스별 조치 문구 (5장 ④ 표) |
| 대기 세션 목록 | 이 이벤트로 대기한 세션(AAS 순). **누르면 세션 상세** |

```sql
SELECT sid, serial#, MAX(username) AS username, MAX(sql_id) AS sql_id,
       MAX(blocking_session) AS blocking_session,
       MAX(blocking_inst_id) AS blocking_inst_id,
       COUNT(*) AS cnt
FROM   (ash_base)
WHERE  event = :event
GROUP  BY sid, serial#
ORDER  BY cnt DESC;
```


### 6.4 점검 알림 상세 (⑧ 카드에서 진입)

| 섹션 | 항목 |
|---|---|
| 헤더 | `점검 알림 · 항목 종류`, 대상 이름, 심각도 pill + 값 · 임계치 |
| 상태 | 항목별 주요 값 (예: 테이블스페이스 = 사용률, 사용량, 최대 크기, 여유, 자동 확장 상태, 일 평균 증가 → 가득 찰 때까지 남은 일수) |
| 최근 7일 추이 | 사용률/크기 항목만. 선 그래프 + 임계치 점선 (`MON_CHECK_RESULT` 일별 최댓값, 테이블은 `MON_SEGMENT_SIZE`) |
| 관련 객체 | 테이블스페이스 → 큰 세그먼트 5개 / 테이블 → 테이블·인덱스 세그먼트 / FRA → 파일 종류별 비중 / TEMP → 사용 세션·SQL / 잡 → 최근 실행 이력 / 무효 객체·통계 → 대상 목록 |
| 조치 권장 | 항목별 조치 문구 (예: "데이터파일을 추가하거나 MAXSIZE를 늘리세요…") |
| 조회 쿼리 | 5장 ⑧ 쿼리 중 해당 항목 |

---

## 7. API (제안 — 기존 경로 규칙에 맞춰 조정)

| Method | Path | 파라미터 | 용도 |
|---|---|---|---|
| GET | `/api/metric_history` (**기존**) | `db_id`, `range`(15m/1h/3h/24h), `metrics` | ① ② : 분별 8분류 AAS(`ash_wc_*`) + 코어. range 키 `15m`/`3h`와 응답 필드 `dbClockOffsetMs` 추가 |
| GET | `/api/dashboard/{dbId}/top` | `from`, `to` (DB 시각) | ⑤ ⑥ ⑦ : ASH 구간 집계 Top 3종 + `source`(ash/awr/mixed) + `dbNow`. ④ 진단은 ② 데이터로 화면에서 계산 |
| GET | `/api/dashboard/{dbId}/lock/realtime` | - | ③ : TX/TM 건수, Holder 목록, 장애 여부 + ① 활성 세션·메모리 칸의 `activeSessions`, `memoryPct` (2026-09-25 추가) |
| GET | `/api/dashboard/{dbId}/session/{sid}/{serial}` | `from`, `to` | 6.1 세션 상세 |
| GET | `/api/dashboard/{dbId}/sql/{sqlId}` | `from`, `to` | 6.2 SQL 상세 |
| GET | `/api/dashboard/{dbId}/event` | `name`, `from`, `to` | 6.3 이벤트 상세 |
| POST | `/api/dashboard/{dbId}/lock/tm-holders/kill` | body: `{dbId, targets: [{sid, serial}]}` (경로와 본문의 `dbId`가 다르면 400) | ③-1 장애 처리 (관리자 전용, 재조회 후 KILL, 감사 기록) |
| GET | `/api/dashboard/{dbId}/checks` | `severity`(선택) | ⑧ 최신 점검 결과 목록 |
| GET | `/api/dashboard/{dbId}/checks/{checkType}/{targetName}` | - | 6.4 점검 알림 상세 (7일 추이, 관련 객체) |

① ② 응답은 기존 `/api/metric_history` 형식을 그대로 쓴다 (metric_name별 `[epoch_ms, value]` 시계열: `ash_wc_cpu`, `ash_wc_user_io`, `ash_wc_system_io`, `ash_wc_concurrency`, `ash_wc_application`, `ash_wc_commit`, `ash_wc_network`, `ash_wc_other`, `ash_cpu_cores`) + `dbClockOffsetMs`.

응답 예시 (`/top`):

```json
{
  "from": "2026-09-24T13:52:00", "to": "2026-09-24T14:02:00",
  "dbNow": "2026-09-24T14:30:04", "instanceName": "ORCL1", "source": "ash",
  "topSql": [
    { "sqlId": "7h35uxf5uhmm1", "aas": 9.4, "pct": 38.7, "sqlText": "UPDATE ORDER_ITEMS SET ...",
      "byCategory": { "cpu": 1.2, "application": 7.9, "user_io": 0.3 } }
  ],
  "topSession": [ { "sid": 944, "serial": 13551, "username": "BATCH", "program": "sqlplus@batch01",
                    "aas": 4.1, "pct": 16.9, "blockingSession": null, "blockingInstId": null, "byCategory": { "application": 4.1 } } ],
  "topEvent":   [ { "event": "enq: TX - row lock contention", "category": "application", "aas": 11.9, "pct": 49.0 } ]
}
```

응답 예시 (`/lock/realtime`):

```json
{
  "ts": "2026-09-24T14:30:04",
  "txWait": 14, "tmWait": 12,
  "holderOverCount": 6, "holderTotal": 7, "maxLastCallEt": 158,
  "incident": true,
  "holders": [
    { "sid": 944, "serial": 13551, "username": "BATCH", "program": "sqlplus@batch01",
      "object": "APP.ORDER_ITEMS", "lastCallEt": 160, "waiters": 2, "block": 1 }
  ]
}
```

---

## 8. 설정값

| 키 | 기본값 | 설명 |
|---|---|---|
| `dashboard.defaultRangeMin` | 60 | 기본 조회 구간 |
| `ash.awrFallback` | true | 선택 구간이 ASH 인메모리 보관 범위를 벗어나면 `dba_hist_active_sess_history`로 보충 |
| `ash.maxWindowHours` | 24 | ⑤⑥⑦·드로어 ASH 조회 최대 구간 |
| `lock.refreshSec` | 3 | 기존 "리프레쉬 주기(초)" 입력창의 기본값 (화면에서 조절 가능) |
| `lock.refreshSecMin` / `lock.refreshSecMax` | 2 / 60 | 입력 허용 범위. 서버도 같은 범위로 요청 빈도를 제한한다 |
| `lock.txWarn` | 5 | TX 대기 위험 기준 |
| `lock.tmWarn` | 3 | TM 대기 위험 기준 |
| `tmHolder.lastCallEtSec` | 60 | 장애 판정 last_call_et 기준. **실제 프로퍼티는 `dbagent.monitor.tm-holder-last-call-et-seconds`** (2026-09-25 결정) — v2 `getFailureProb()`·`getActiveAlerts()`와 새 대시보드가 같은 값을 쓴다 |
| `tmHolder.incidentCount` | 6 | 장애 판정 수 기준 (60초↑ 블로킹 TM Holder, v2와 같은 규칙) |
| `tmHolder.blockingOnly` | false | true면 KILL 대상을 `block > 0`인 Holder로 좁힘 (기본은 전체 — 2026-09-25 결정). 장애 판정에는 영향 없음 |
| `tmHolder.inactiveOnly` | false | true면 KILL 대상을 `status='INACTIVE'`로 좁힘. 장애 판정에는 영향 없음 |
| `lock.txRowLockOnly` | false | true면 TX를 row lock contention만 집계 |
| `kill.allowedRoles` | ADMIN | KILL 버튼 표시·API 허용 역할 |
| `check.intervalMin` | 10 | ⑧ 가벼운 항목(테이블스페이스·FRA·TEMP·잡 실패) 점검 주기 |
| `check.dailyHour` | 3 | ⑧ 무거운 항목(테이블 용량·무효 객체·통계) + 세그먼트 스냅샷 실행 시각(시, 0~23). 앱 기동 직후 1회는 즉시 실행 |
| `check.tablespace.warnPct` / `critPct` | 97 / 98 | 2026-09-25 결정 |
| `check.tableSize.warnGb` / `critGb` | 30 / 40 | |
| `check.fra.warnPct` / `critPct` | 80 / 90 | |
| `check.temp.warnPct` / `critPct` | 75 / 90 | |
| `check.excludeOwners` | SYS, SYSTEM, AUDSYS, XDB, MDSYS, CTXSYS | |
| `retention.minuteTablesDays` | 30 | |

---

## 9. 작업 순서

0. ~~**선행 버그 수정**~~ **완료 (2026-09-25, 메인·AIX)** — `MonitorService.getFailureProb()`의 1차 쿼리가 `v$session`에 없는 `s.inst_id`를 참조해 매번 ORA-00904 후 `/*+ rule */` 폴백이 돌던 문제. 운영에서 실제로 돌던 rule 힌트 쿼리 하나만 남기고 깨진 1차 쿼리와 폴백 구조를 제거했다(판정 결과 동일).
1. **코드 파악** (0장).
2. **수집 테이블 생성** (4.2) — `MON_SQLSTAT_DELTA`, `MON_LOCK_SAMPLE`, `MON_KILL_AUDIT`, `MON_CHECK_RESULT`, `MON_SEGMENT_SIZE`. 저장소(SQLite/H2) 문법으로 변환.
3. **수집기 수정** (4.3) — 기존 60초 샘플러의 ASH 쿼리를 7분류+8분류 동시 집계로 확장해 `ash_wc_*` 8개를 추가 저장하고(기존 `ash_*` 7개 유지), 기동 시 1시간 backfill, DB 시각 차이 캐시 (코어 쿼리 `NUM_CPU_CORES` 변경은 2026-09-25 완료). 7분류 CASE(샘플러·`getAshActivity()` 공유)와 8분류 CASE(샘플러·신규 `/top` 공유)를 각각 공통 상수로 모은다. (4) SQL 통계 델타 추가. ASH 구간 조회(4.3 (3))와 AWR 보충 경로는 `query-performance-reviewer`로 검토.
4. **API 구현** (7장).
5. **상태바 수정** — 구간 버튼 추가, 수동 새로고침/자동 갱신 중지 연결 (3장).
6. **DASHBOARD 본문 교체** — 기존 게이지·탭·테이블 제거, 8프레임 배치 (2장, 5장). 목업 `UI개선_mockup.html`의 HTML/CSS/차트 코드를 참고해도 된다 (차트는 라이브러리 없이 SVG로 그림).
7. **상세 드로어** (6장).
8. **TM Lock 장애 처리** (③-1) — 관리자 권한, 재조회, 감사 로그. `security-reviewer`로 검토.
8-1. **점검 알림** (⑧, 6.4) — 10분 점검 잡 + 1일 점검 잡(세그먼트 스냅샷 포함), `MON_CHECK_RESULT`/`MON_SEGMENT_SIZE`, 카드·필터·상세.
9. **검증** (10장) — `feature-tester`.

---

## 10. 수용 기준

- [ ] 1920×1040 브라우저에서 DASHBOARD 탭에 페이지 스크롤이 없다. 더 작은 창에서는 대시보드 영역 안에서만 스크롤되고, 내용이 잘리지 않는다.
- [ ] 상태바에 15분/1시간/3시간/24시간 버튼이 있고, 바꾸면 ①②④⑤⑥⑦이 같은 구간으로 다시 그려진다. ①② 24시간도 `instance_metric_history`에서 조회된다(원본 DB에 ASH GROUP BY를 보내지 않는다).
- [ ] ② 차트는 8개 대기 클래스(CPU, User I/O, System I/O, Concurrency, Application, Commit, Network, Other) 누적 영역 + CPU 코어 점선을 그리고, 범례로 클래스를 숨길 수 있다. "Oracle 대기 클래스 기준" 라벨이 보인다.
- [ ] 같은 분(minute)의 8분류 합계와 Current Session 7분류 합계가 같다 (같은 ASH 스캔에서 나온 값이므로 분류만 다르고 총량은 일치). Current Session 화면은 변경 전과 똑같이 동작한다.
- [ ] ① KPI의 AAS가 ② 차트 누적 높이와 일치한다.
- [ ] 처음 열면 최대 부하 10분 구간이 자동 선택된다. 클릭(5분)/드래그로 선택하면 "선택 해제" 버튼이 생기고, 누르면 자동 선택으로 돌아간다.
- [ ] ③은 리프레쉬 주기(기본 3초)로 갱신되고, 입력창에서 주기를 바꾸면 즉시 반영되며(범위 밖 값은 보정), 헤더의 `LIVE · n초 주기`도 바뀐다. "자동 갱신 중지"로 멈추고 "재개"로 다시 시작된다.
- [ ] 60초 이상 다른 세션을 막고 있는(자기는 막히지 않은) TM Holder가 6개 이상이면 ③ 카드가 붉게 바뀌고 장애 처리 버튼이 나타난다. 같은 순간 v2 화면의 장애 판정과 결과가 같고, 아무도 막지 않는 Holder만 많을 때는 장애로 판정하지 않는다. 조건이 풀리면 카드는 원래대로 돌아오지만, 열려 있던 확인 패널은 닫히지 않고 "장애 조건 해소됨"만 표시된다.
- [ ] 확인 패널의 기본 선택은 TM Lock Holder 전체이며, 패널을 연 뒤 새로 생긴 Holder는 자동 선택되지 않는다.
- [ ] 확인 패널을 연 채 DB를 바꾸면 패널이 닫히고, KILL 요청은 패널을 연 DB로만 전송된다.
- [ ] KILL은 관리자만 할 수 있고, 실행 직전에 대상을 재조회하며, 모든 결과가 `MON_KILL_AUDIT`에 남는다. `type='USER'`가 아닌 세션은 어떤 경우에도 KILL되지 않는다.
- [ ] ⑤⑥⑦ 행을 클릭하면 상세 드로어가 열리고, SQL/이벤트 상세의 세션 목록에서 세션 상세로 이동·뒤로가기가 된다. Esc/배경 클릭으로 닫힌다.
- [ ] ⑤⑥⑦·드로어는 선택 구간의 ASH로 집계되고, ASH 보관 범위 밖 구간을 고르면 `dba_hist_active_sess_history`로 보충되어 데이터 소스 라벨에 표시된다.
- [ ] gv$ 뷰와 그 `inst_id` 컬럼을 어디에서도 쓰지 않는다 (코드 검색으로 확인. v$ 뷰에 원래 있는 `blocking_inst_id`/`blocking_instance`는 블로커가 다른 노드인지 판별하는 용도로만 허용). 화면에 접속 인스턴스명이 표시되고, 다른 인스턴스의 블로커는 "다른 인스턴스"로만 표시된다.
- [ ] 11g 대상 DB에서도 모든 조회가 동작한다 (`FETCH FIRST` 등 12c 전용 문법 없음).
- [ ] ⑧ 점검 알림이 대시보드 맨 아래 가로로 표시되고, 위험→주의→확인 순으로 정렬되며, 필터와 카드 클릭 상세가 동작한다. 카드가 많으면 프레임 안에서만 가로 스크롤된다.
- [ ] 대상 DB 접속 실패 시 "수집 실패"와 ORA 메시지가 보이고, 빈 차트를 정상으로 표시하지 않는다.
- [ ] "화면 상태 정의" 표의 상태(첫 로딩 스켈레톤, DB 전환, 데이터 공백, 수집 지연, 판단 보류, ASH 조회 실패, 빈 결과, 권한 없음, 비관리자, 비Oracle, 점검 실패, 화면 숨김)가 각각 표대로 표시된다. 어떤 실패도 0이나 "알림 없음"으로 보이지 않는다.
- [ ] 한 세션이 여러 테이블에 TM Lock을 잡아도 Holder 수는 1로 센다 (세션 단위 집계).
- [ ] 여러 사람이 같은 DB 대시보드를 동시에 열어도 Lock 조회는 DB별 주기당 1회만 원본에 나간다.
- [ ] FRA를 설정하지 않은 DB에서 ⑧ 점검이 오류 없이 동작하고 FRA 카드가 나오지 않는다. TEMP 사용률은 autoextend MAXSIZE 기준이다.
- [ ] 테이블 용량·무효 객체·통계 점검은 하루 1회(기본 03시, 앱 기동 직후 1회)만 실행되고, 카드에 항목별 마지막 점검 시각이 보인다.
- [ ] SQL 통계 델타에 앱 재기동 직후·커서 재적재 시 급등값이 저장되지 않는다.
- [ ] 외부 폰트·CDN·`color-mix()`·`dvh`를 쓰지 않는다. zoom 80% 상태에서 드로어·툴팁 위치와 차트 호버·드래그 좌표가 맞다. http 주소에서 "구문 복사"가 동작하거나 수동 복사 안내가 나온다. 라이트 테마에서도 색 대비가 유지된다.
- [ ] 메인(SQLite, Java 17)과 AIX(H2, Java 8) 양쪽에서 같은 결과로 동작한다.
