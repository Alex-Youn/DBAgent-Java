# Oracle 인스턴스 대시보드 — 화면 구성 명세

> DB Ops Console › **DASHBOARD** 탭 · 단일 Oracle 인스턴스 상세 화면
> 시각 시안: `AI DBA 콘솔` Artifact (3번째 아트보드 `Dashboard`) — 이 문서는 그 시안을 실제 코드로 옮길 때 참고할 구조/데이터/토큰 명세다.

## 0. 이 문서를 쓰는 방법

이 문서는 **Claude Code(CLI)에게 이 화면을 구현시킬 때 함께 넘기는 스펙**이다. 레이아웃 트리 → 각 컴포넌트의 목적·문구·데이터 필드 → 디자인 토큰 → 차트 스펙 순으로 정리했다. 실제 구현 시:

- 프레임워크는 지정하지 않는다(React/Vue/Svelte 등 프로젝트에 맞춰 변환).
- 문구(한글 라벨)는 시안에 쓰인 그대로를 기본값으로 두고, i18n이 있다면 키로 뺀다.
- 아래 데이터 필드는 TypeScript `interface`로 표기했지만 이는 **구조를 명확히 하기 위한 표기**일 뿐, 실제 언어/스키마는 프로젝트에 맞춘다.
- 색상·타이포그래피는 5번 섹션의 디자인 토큰을 **그대로 CSS 변수/테마 값으로 이식**하는 것을 전제로 작성했다.

---

## 1. 화면 목적

하나의 Oracle 인스턴스(예: `ORCL01`)를 골랐을 때, DBA가 한 화면에서:

1. 지금 인스턴스가 정상인지(CPU/메모리/세션/DB Time 등 핵심 지표)
2. 최근 추이(CPU·DB Time, Lock 대기)
3. 지금 가장 부하를 주는 쿼리(Top SQL)와 대기 이벤트(Top Wait Events)
4. 즉시 조치가 필요한 알림(Lock 경합, 장시간 쿼리 등)

을 한눈에 파악하도록 하는 **인스턴스 상세 대시보드**다. 다중 인스턴스 목록/비교 화면이 아니라 **드릴다운된 단일 인스턴스 화면**이라는 점이 중요 — 상단에 인스턴스 선택기가 있고, 그 아래는 전부 그 인스턴스 하나의 데이터다.

---

## 2. 레이아웃 트리

```
AppShell
├─ TopBar (공용, 모든 탭에서 재사용)
│   ├─ Logo + Product name ("DB Ops Console")
│   ├─ NavTabs (9개, "DASHBOARD" active)
│   └─ StatusArea (모델 연결 상태 배지, 사용자 아바타)
│
└─ DashboardPage
    ├─ PageHeader
    │   ├─ InstancePicker (인스턴스명 + 환경 + 드롭다운)
    │   ├─ StatusBadge ("정상 운영")
    │   ├─ MetaTags (PROD / 버전 / 가동시간 / SGA·PGA)
    │   └─ RangeControls (실시간/1시간/24시간/7일 + 새로고침)
    │
    ├─ KpiTileRow (6개 StatTile)
    │
    ├─ ChartsRow (2열)
    │   ├─ CpuDbTimeLineChart   (좌, 넓게)
    │   └─ TopWaitEventsBarChart (우)
    │
    ├─ TopSqlTable
    │
    └─ LockAndAlertsRow (2열)
        ├─ LockTrendChart (TM Lock / TX Lock 추이)   ← 이번에 테이블스페이스 카드를 대체
        └─ ActiveAlertsList
```

TopBar는 AI DBA 화면 등 다른 탭과 **완전히 동일한 컴포넌트**를 재사용한다(활성 탭만 다름). 별도 컴포넌트로 분리해 구현할 것.

---

## 3. 컴포넌트별 명세

### 3.1 TopBar (공용)

| 요소 | 내용 |
|---|---|
| 로고 | 30×30 아이콘 배지(봇 아이콘) + "DB Ops Console" |
| 탭 (9개, 좌→우) | DASHBOARD · Current Session · 성능 이력 조회 · Lock Holder/Waiter Tree · 테이블스페이스 조회 · Table Parent/Child 관계 · SQL 실행 · AI DBA · SQL 정합성/튜닝 |
| 탭 스타일 | 아이콘(15px) + 라벨, 비활성은 `text-secondary`, 활성은 배경 `accent-soft-bg` + 글자색 `accent`, 폭 좁으면 가로 스크롤 |
| 우측 상태 배지 | 점(dot) + "모델 연결됨"/"모델 확인 중" — 연결 시 `success`, 확인 중엔 `warning` + pulse 애니메이션 |
| 우측 아바타 | 30px 원형, 유저 아이콘 (개인화 정보 없음, 제너릭 아이콘) |

### 3.2 PageHeader

```ts
interface DashboardHeader {
  instance: {
    name: string;          // "ORCL01"
    environment: "Production" | "Staging" | "Development";
    status: "정상 운영" | "주의" | "장애";
    dbVersion: string;     // "Oracle 19c"
    topology: string;      // "Single Instance" | "RAC (2 nodes)" 등
    uptime: string;        // "12일 4시간"
    sgaSize: string;       // "24G"
    pgaSize: string;       // "8G"
    tags: string[];        // ["PROD", "Oracle 19c · Single Instance", "가동시간 12일 4시간", "SGA 24G / PGA 8G"]
  };
  range: "realtime" | "1h" | "24h" | "7d";
  lastUpdatedAt: string;   // ISO timestamp, "방금 전" 등으로 상대 표기
}
```

- **인스턴스 선택기**: 클릭 시 인스턴스 전환 드롭다운(다른 화면/기능, 이 문서 범위 밖). 현재는 DB 아이콘 + 인스턴스명(굵게) + 환경명(연하게) + 셰브론.
- **상태 배지**: 점 색상 + 텍스트, 색상만으로 의미를 전달하지 않는다(항상 텍스트 동반). 정상=`success`, 주의=`warning`, 장애=`danger`.
- **기간 선택**: pill 그룹, 현재 선택된 값만 `accent-soft-bg` 배경. 새로고침 아이콘 버튼은 수동 갱신 트리거.

### 3.3 KpiTileRow — 6개 StatTile

```ts
interface StatTile {
  label: string;
  value: string;              // 표시용 포맷된 값, tabular-nums 적용
  unit?: string;
  sparkline?: number[];       // 최근 N개 포인트 (선택)
  delta?: {
    text: string;             // "지난 시간 대비 +5%p"
    direction: "up" | "down" | "flat";
    tone: "danger" | "warning" | "success" | "neutral";  // 값이 커지는 게 나쁜지 좋은지는 지표마다 다름
  };
  capacityBar?: { value: number; max: number; tone: "success" | "warning" | "danger" };
}
```

6개 타일과 데이터 소스:

1. **CPU 사용률** — `42%`, 스파크라인, delta(상승=주의 톤)
2. **메모리 사용률 (SGA/PGA)** — `68%`, 스파크라인, delta(안정 시 neutral/success)
3. **Active Sessions** — `37 / 150`, capacity bar (25%)
4. **DB Time (AAS)** — `1.8`, 스파크라인, Average Active Sessions 설명 텍스트
5. **TPS** — `212`, 스파크라인, "건/초"
6. **Buffer Cache Hit Ratio** — `99.2%`, capacity bar(success), "권장 기준 ≥ 95%" 안내

레이아웃: `grid-template-columns: repeat(6, minmax(0,1fr))`, gap 14px. 좁은 화면에서는 3열×2행 또는 2열×3행으로 랩.

### 3.4 CpuDbTimeLineChart

- 2계열 라인 차트: **CPU %**(series-1, 파랑) vs **DB Time/AAS**(series-2, 주황), 단일 축(퍼센트 축 기준, DB Time은 보조 스케일로 같은 트랙에 정규화해서 그림 — **듀얼 y축 금지**, 대신 두 값 모두 0-100 범위로 정규화하거나 legend에 실제 단위를 명시하는 방식 채택).
- x축: 시간(13:32 ~ 14:32, 15분 간격 라벨), y축: 0/25/50/75/100% 그리드라인.
- 호버 시 세로 크로스헤어 + 두 계열 값 툴팁(시안에는 스냅샷으로 고정 표시 — 실제 구현은 마우스/터치 호버 시 노출).
- 범례는 색상 스와치 + 텍스트(색상 단독으로 구분하지 않음).

```ts
interface TimeSeriesPoint { t: string; cpuPct: number; dbTimeAas: number; }
interface CpuDbTimeChartData { range: "1h" | "24h" | "7d"; points: TimeSeriesPoint[]; }
```

### 3.5 TopWaitEventsBarChart

- 단일 계열(단일 색상, series-1) 가로 막대 — **값의 크기만 표현**하므로 카테고리별 색상 구분은 쓰지 않는다(이 부분이 다계열 색상 규칙과 다른 지점: 크기 비교이지 정체성 구분이 아니므로 sequential 1-hue를 쓴다).
- 내림차순 정렬, 막대 길이 = 최댓값 대비 비율.

```ts
interface WaitEvent { name: string; seconds: number; }
interface TopWaitEventsData { windowLabel: string; events: WaitEvent[]; } // events는 seconds desc 정렬
```

기본 5개 이벤트: `DB CPU`, `User I/O`, `Concurrency`, `Commit`, `Cluster`.

### 3.6 TopSqlTable

| 컬럼 | 타입 | 비고 |
|---|---|---|
| SQL_ID | string (mono) | 링크 가능(상세 화면으로) |
| SQL TEXT | string (mono, truncate) | 말줄임표, hover 시 전체 텍스트 툴팁 권장 |
| 실행 횟수 | number (tabular) | 우측 정렬 |
| 평균 응답(ms) | number (tabular) | 우측 정렬, 임계치 초과 시 색상(`danger`/`warning`) |
| CPU | number (%) | 우측 정렬 |
| 상태 | badge | `튜닝 필요`(danger) / `주시 중`(warning) / `정상`(success) |

```ts
interface TopSqlRow {
  sqlId: string;
  sqlTextPreview: string;
  execCount: number;
  avgElapsedMs: number;
  cpuPct: number;
  status: "tuning_needed" | "watching" | "normal";
}
```

정렬 기준은 카드 제목대로 **Elapsed Time 합산 기준 내림차순**. 상태 배지 텍스트는 임계치 로직에 따라 서버에서 계산해 내려주는 것을 권장(클라이언트에서 임계치 하드코딩 지양).

### 3.7 LockTrendChart — TM Lock / TX Lock 추이 *(신규, 테이블스페이스 카드 대체)*

기존 시안의 "테이블스페이스 사용률" 카드를 제거하고, 같은 자리에 **Lock 경합 모니터링** 카드로 교체했다. 테이블스페이스 사용률은 별도 탭("테이블스페이스 조회")에서 이미 다루므로 이 화면에서는 Lock 경합처럼 즉시성이 더 높은 지표를 우선한다.

- 2계열 라인 차트: **TM Lock 대기 건수**(series-1, 파랑) vs **TX Lock 대기 건수**(series-2, 주황), 최근 1시간, y축 0~8건.
- 급증 지점에는 세로 점선 마커로 강조 — 아래 `ActiveAlertsList`의 "Blocking Session 감지(SID 214)" 알림과 **동일 시점**을 가리키도록 시각적으로 연결한다(두 컴포넌트가 같은 이벤트를 참조하는 구조).
- 하단에 현재 스냅샷 2개: "현재 TM Lock 대기" / "현재 TX Lock 대기" — TX Lock이 임계치를 넘으면 카드 배경을 `danger-soft`로, 값에 관련 세션(`SID 214`) 참조 텍스트를 함께 노출.

```ts
interface LockTrendPoint { t: string; tmLockWaiting: number; txLockWaiting: number; }
interface LockTrendData {
  points: LockTrendPoint[];
  current: { tmLockWaiting: number; txLockWaiting: number };
  relatedAlert?: { sid: string; kind: "blocking_session"; detectedAt: string };
}
```

> 구현 시 참고: TM Lock은 DML(테이블) 락, TX Lock은 트랜잭션 락으로, 실제 데이터는 `V$LOCK` / `V$LOCKED_OBJECT` / `DBA_BLOCKERS` 계열 뷰를 주기적으로 샘플링해 시계열로 적재하는 방식을 권장.

### 3.8 ActiveAlertsList

```ts
interface AlertItem {
  id: string;
  severity: "critical" | "warning" | "info" | "success";
  message: string;
  occurredAt: string;   // 상대 시간으로 표시 ("5분 전")
  relatedSid?: string;
}
```

- 상단에 "N건 확인 필요" 카운트 배지(critical+warning 합산, `danger` 톤).
- 각 행: 심각도 아이콘(색상은 severity에 매핑, 텍스트 라벨은 message 자체가 의미를 전달 — 아이콘+텍스트로 색맹 사용자도 구분 가능) + 메시지 + 상대 시간.
- 예시 4건: TEMP 테이블스페이스 92%(critical) · Blocking Session 감지(warning, `LockTrendChart`와 연결) · 장시간 실행 쿼리 3건(warning) · 일일 백업 정상 완료(success/info).

---

## 4. 아이콘 정책

- **아이콘 라이브러리에 의존하지 않는다.** 모든 아이콘은 24×24 viewBox, `stroke="currentColor"`, `stroke-width="1.8~2"`, `stroke-linecap/linejoin="round"`, `fill="none"` (색이 채워지는 점만 `fill="currentColor" stroke="none"`)로 통일된 인라인 SVG.
- 실제 구현에서는 이 스타일 규칙에 맞는 아이콘 세트(Lucide 등 stroke 기반 세트)로 1:1 교체 가능 — 핵심은 **하나의 stroke 두께·라운드 처리로 통일된 톤**을 유지하는 것.
- 이모지·픽토그램 사용 금지.

---

## 5. 디자인 토큰

CSS 커스텀 프로퍼티로 이식할 것을 전제로 한 값(다크 테마 기준, 시안과 동일):

```css
:root {
  /* surfaces */
  --bg:      oklch(0.150 0.018 258);
  --panel:   oklch(0.187 0.018 258);
  --panel-2: oklch(0.222 0.020 258);
  --panel-3: oklch(0.245 0.020 258);

  /* borders */
  --border:      oklch(0.320 0.020 258 / 0.7);
  --border-soft: oklch(0.320 0.020 258 / 0.38);

  /* text */
  --text-primary:   oklch(0.94 0.008 258);
  --text-secondary: oklch(0.685 0.018 258);
  --text-tertiary:  oklch(0.50 0.018 258);

  /* brand accents */
  --accent:          oklch(0.685 0.165 252);   /* 주요 액션, 활성 탭 */
  --accent-strong:   oklch(0.60 0.185 252);
  --accent-soft-bg:  oklch(0.685 0.165 252 / 0.15);
  --accent-2:        oklch(0.78 0.13 196);     /* 보조 강조(링크, AI 관련) */
  --accent-2-soft:   oklch(0.78 0.13 196 / 0.14);

  /* status (뱃지·경고 등 상태 전용 — 카테고리 색과 혼용 금지) */
  --danger:       oklch(0.665 0.19 24);
  --danger-soft:  oklch(0.665 0.19 24 / 0.15);
  --warning:      oklch(0.79 0.145 76);
  --warning-soft: oklch(0.79 0.145 76 / 0.15);
  --success:      oklch(0.735 0.15 148);
  --success-soft: oklch(0.735 0.15 148 / 0.15);

  /* chart series (카테고리 색 — 색맹 대비 검증됨, 8절 참고) */
  --series-1: #3987e5;  /* 파랑 */
  --series-2: #d95926;  /* 주황 */
}
```

- 폰트: 본문 `'IBM Plex Sans KR'`(400/500/600/700), 코드/숫자 `'IBM Plex Mono'`(400/500/600). 폴백: `-apple-system, 'Apple SD Gothic Neo', 'Malgun Gothic', sans-serif`.
- 숫자(특히 테이블·타일의 값)에는 `font-variant-numeric: tabular-nums` 적용.
- 카드: `border-radius: 12~14px`, `border: 1px solid var(--border-soft)`, 좌측 컬러 보더 강조는 쓰지 않음(상태는 배지/아이콘으로 표현).
- 라운드 처리된 좌측 보더 강조, 이모지, 과도한 그라디언트는 이 디자인 언어에서 의도적으로 배제했다 — 새 컴포넌트 추가 시에도 유지할 것.

---

## 6. 차트 색상 검증 근거

`--series-1`(#3987e5) / `--series-2`(#d95926) 조합은 다크 서페이스(#0e131b 근사치) 기준으로 색맹 대비(CVD) 시뮬레이션 검증을 통과했다(Anthropic dataviz 방법론의 `validate_palette.js` 사용):

```
lightness band     PASS  (L 0.48–0.67 범위)
chroma floor       PASS
CVD separation     PASS  (ΔE 26.8, 목표 ≥8)
normal-vision      PASS  (ΔE 31.8, 목표 ≥15)
contrast vs surface PASS (모두 ≥3:1)
```

새 차트를 추가할 때도 이 두 색을 우선 재사용하고, 3계열 이상이 필요해지면 임의로 색을 늘리지 말고 검증 스크립트로 순서를 재확인할 것. 상태색(`--danger/--warning/--success`)은 카테고리 색과 별도 용도이므로 시리즈 색으로 재사용하지 않는다(단, 임계치 초과를 강조하는 단일 지표 막대/텍스트에는 사용 가능 — 예: TEMP 사용률 92% 막대, Top SQL 응답시간 임계 초과 색상).

---

## 7. 상호작용 · 반응형 메모

- **차트 호버**: 라인 차트는 크로스헤어 + 툴팁, 값 2개(계열별) 동시 표시. 막대 차트는 막대별 호버 툴팁.
- **범위 선택(실시간/1시간/24시간/7일)**을 바꾸면 KPI 타일의 스파크라인과 두 차트가 함께 갱신되어야 한다(같은 상태를 공유).
- **실시간 모드**에서는 폴링 또는 웹소켓으로 KPI/차트/알림을 주기 갱신(권장 주기: 10~30초), 상단 "마지막 업데이트" 텍스트도 함께 갱신.
- 6-타일 KPI 로우와 2열 차트/2열 Lock+Alerts 로우는 뷰포트가 좁아지면 각각 3열→2열, 2열→1열로 랩되도록 그리드 구성.
- 상태(성공/경고/위험)는 **색상 단독으로 전달하지 않는다** — 항상 아이콘 또는 텍스트 라벨을 동반(접근성).

---

## 8. 참고: 시안 아트보드 목록

같은 디자인 시스템(다크 테마, 동일 토큰/타이포)으로 함께 만든 화면들:

1. **Main** — AI DBA 챗봇형 분석 결과 화면 (SQL 튜닝 대화)
2. **EmptyState** — AI DBA 초기 진입 화면 (추천 질문 칩)
3. **Dashboard** — 본 문서가 다루는 인스턴스 대시보드

세 화면 모두 동일한 `TopBar`를 공유하므로, 구현 시 `TopBar`를 가장 먼저 공용 컴포넌트로 뽑아내는 것을 권장한다.
