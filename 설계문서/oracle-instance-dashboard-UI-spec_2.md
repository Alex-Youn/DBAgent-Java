# Oracle 인스턴스 대시보드 (벤토형) — 화면 구성 명세

> DB Ops Console › **DASHBOARD** 탭 · 단일 Oracle 인스턴스 상세 화면 — **대안 레이아웃 (Dashboard B)**
> 시각 시안: `AI DBA 콘솔` Artifact (4번째 아트보드 `DashboardAlt`) — 이 문서는 그 시안을 실제 코드로 옮길 때 참고할 구조/데이터/토큰 명세다.
> 같은 데이터를 다루는 스택형 버전(Dashboard A)의 명세는 `oracle-instance-dashboard-spec.md` 참고. 디자인 토큰(색상·타이포그래피)은 두 버전이 **완전히 동일**하고, 레이아웃 구조만 다르다.

## 0. Dashboard A 대비 무엇이 다른가

| | Dashboard A (스택형) | Dashboard B (벤토형, 본 문서) |
|---|---|---|
| 정보 구조 | 전체 폭 카드를 위→아래로 순서대로 쌓음 | 2단 비대칭 그리드: 좌측 메인(1.9fr) + 우측 고정 사이드바(1fr) |
| 헤더 | 다단 페이지 헤더(인스턴스 정보 + 태그 + 기간 선택이 별도 행) | 한 줄 슬림 헤더(브레드크럼 + 인스턴스 칩 + 상태 + 기간 선택을 한 줄에) |
| 핵심 지표 | 동일 크기 타일 6개를 그리드로 나열 | 원형 헬스 스코어 게이지 + 지표 4개를 막대 리스트로 압축한 "히어로 스코어카드" |
| 추이 차트 | CPU/DB Time, Wait Events, Lock 추이가 **각각 별도 카드** | 세 가지를 **탭 하나로 전환**하는 단일 차트 카드 (CPU/DB Time · Wait Events · TM/TX Lock) |
| Top SQL | `<table>` | 순위 번호 + 인라인 막대가 있는 **리스트 행** (테이블 없음) |
| 알림 | 하단 2열 로우의 절반 | **사이드바 최상단에 항상 노출**되는 고정 패널(스크롤해도 먼저 보임) |
| Lock 현황 | 시계열 라인 차트 카드 | 사이드바의 **압축 위젯**(현재값 + 미니 스파크라인) |

두 버전 모두 데이터 소스와 의미는 동일하다 — **프레젠테이션 레이어만 다른 A/B 옵션**으로 보고 구현할 것.

---

## 1. 레이아웃 트리

```
AppShell
├─ TopBar (공용, Dashboard A와 100% 동일 컴포넌트 재사용)
│
└─ DashboardBentoPage
    ├─ SlimHeader (한 줄)
    │   ├─ Breadcrumb ("DASHBOARD /")
    │   ├─ InstanceChip (인스턴스명 + 환경 + 셰브론)
    │   ├─ StatusPill ("정상 운영")
    │   ├─ MetaText (버전 · 토폴로지 · 가동시간, 인라인 텍스트)
    │   └─ HeaderControls (마지막 업데이트 텍스트, 기간 선택 pill, 새로고침 버튼)
    │
    └─ BentoGrid (grid-template-columns: 1.9fr 1fr)
        ├─ MainColumn
        │   ├─ HeroScoreCard (원형 게이지 + 4행 지표 바)
        │   ├─ TabbedTrendChart (탭 3개, 기본 탭 = CPU/DB Time)
        │   └─ TopSqlList (리스트형, 테이블 아님)
        │
        └─ Sidebar (sticky 권장)
            ├─ ActiveAlertsPanel (항상 최상단)
            ├─ LockStatusWidget (TM/TX 압축 위젯)
            └─ QuickStatsList
```

---

## 2. 컴포넌트별 명세

### 2.1 TopBar — Dashboard A와 공유

변경 없음. `TopBar`를 별도 컴포넌트로 이미 뽑아뒀다면 그대로 재사용, 활성 탭만 "DASHBOARD"로 유지.

### 2.2 SlimHeader

```ts
interface SlimHeaderProps {
  instance: { name: string; environment: string; status: "정상 운영" | "주의" | "장애" };
  metaText: string;          // "Oracle 19c · Single Instance · 가동시간 12일 4시간" — 태그 칩 대신 인라인 텍스트 한 줄
  lastUpdatedAt: string;     // "방금 전"
  range: "realtime" | "1h" | "24h" | "7d";
}
```

- Dashboard A의 `MetaTags`(칩 4개)를 이 버전에서는 **한 줄 텍스트**로 압축했다 — 세로 공간을 아껴 벤토 그리드에 더 할애하기 위함. 칩이 필요한 경우(클릭 가능한 필터 등) Dashboard A 쪽 패턴을 따를 것.
- `flex-wrap: wrap`으로 좁은 화면에서 좌/우 그룹이 다음 줄로 떨어지도록 한다.

### 2.3 HeroScoreCard

가장 큰 구조적 차이. 6개의 동일 크기 타일 대신, **하나의 합성 점수**를 원형 게이지로 크게 보여주고 그 옆에 구성 지표를 압축 리스트로 둔다.

```ts
interface HeroScoreCard {
  healthScore: number;        // 0-100, 합성 지표 (계산 로직은 §5 참고)
  breakdown: Array<{
    label: string;            // "CPU 사용률" 등
    value: string;            // 표시용 포맷 값 ("42%", "37/150")
    pct: number;               // 0-100, 막대 길이 계산용 (37/150 같은 분수형도 0-100으로 정규화)
    tone?: "default" | "success" | "warning" | "danger";
  }>;
}
```

**원형 게이지 구현 메모**:

- SVG 2개의 `<circle>`을 겹쳐서 구현: 배경 트랙(`--panel-3`) + 진행 아크.
- 진행 아크 색상은 점수 구간에 따라 상태색 매핑: `>=90` → `--success`, `70-89` → `--warning`, `<70` → `--danger` (Dashboard A의 임계치 로직과 동일한 상태색 규칙 재사용).
- 계산: `circumference = 2 * PI * r` (시안 기준 r=64 → 약 402.1), `dash = circumference * (healthScore / 100)`, `stroke-dasharray="{dash} {circumference}"`, `transform="rotate(-90 cx cy)"`로 12시 방향에서 시작.
- 중앙에 점수 숫자(굵게, tabular-nums) + "종합 헬스 스코어" 라벨을 절대 위치로 겹침.

**breakdown 리스트**: 4개 행(CPU/메모리/Active Sessions/Buffer Cache Hit), 각 행은 `라벨(고정폭) + 얇은 진행 막대(flex:1) + 값(고정폭, 우측 정렬)` — Dashboard A의 개별 StatTile 카드들을 하나의 카드 안에 압축한 형태.

### 2.4 TabbedTrendChart

```ts
type TrendTab = "cpu_dbtime" | "wait_events" | "lock";

interface TabbedTrendChart {
  activeTab: TrendTab;
  cpuDbTime?: { points: { t: string; cpuPct: number; dbTimeAas: number }[] };
  waitEvents?: { events: { name: string; seconds: number }[] };
  lock?: { points: { t: string; tmLockWaiting: number; txLockWaiting: number }[] };
}
```

- 탭 전환 시 **차트 영역만 교체**, 범례/축은 탭마다 해당 차트 스펙에 맞게 바뀐다(막대 차트 탭은 범례가 필요 없을 수 있음 — 단일 계열이면 범례 생략, §7 참고).
- 각 차트 자체의 스펙(선 두께, 그리드라인, 호버 크로스헤어, 정규화 방식 등)은 Dashboard A 명세의 §3.4/§3.5/§3.7과 **동일** — 여기서는 "탭 안에 넣는다"는 배치만 다르다.
- 기본 활성 탭은 "CPU / DB Time". 탭 버튼은 pill 형태, 활성 탭만 `--panel-3` 배경 + `--text-primary`, 비활성은 `--text-tertiary`.

### 2.5 TopSqlList (리스트형, 테이블 아님)

```ts
interface TopSqlListRow {
  rank: number;
  status: "tuning_needed" | "watching" | "normal";
  sqlId: string;
  sqlTextPreview: string;
  elapsedMs: number;
  elapsedPctOfMax: number;   // 인라인 막대 길이 (0-100, 해당 목록 내 최댓값 기준 정규화)
}
```

한 행 구성(좌→우): 순위 번호(`01`, mono) → 상태 배지(작게) → SQL_ID(mono, accent-2) → SQL 텍스트(말줄임, flex-grow) → 인라인 미니 막대(고정폭 64px, 상태색) → 응답시간(우측 정렬, 고정폭).

Dashboard A의 `<table>` 대비 장점: 반응형 랩이 쉽고, 상태·크기·텍스트가 한 시선 흐름에 들어와 훑어보기(scanning)에 유리 — 대신 다열 정렬 비교(컬럼별 정렬 클릭 등)가 필요하면 Dashboard A의 테이블 쪽이 더 적합하니, 실제 제품에서는 사용 맥락에 따라 둘 중 하나를 고른다.

### 2.6 ActiveAlertsPanel (사이드바 고정)

```ts
interface AlertItem {
  id: string;
  severity: "critical" | "warning" | "info" | "success";
  message: string;
  detail?: string;       // "확장 필요", "SID 214" 등 보조 텍스트
  occurredAt: string;
}
```

- Dashboard A와 데이터 구조는 동일하지만, 표시 위치가 **사이드바 최상단으로 고정**된다는 점이 핵심 차이 — 메인 컬럼을 스크롤해도 알림이 항상 시야에 있어야 하므로, 구현 시 사이드바 전체 또는 이 패널만 `position: sticky; top: <TopBar 높이>`로 고정할 것을 권장.
- 카드 테두리를 `--danger-soft` 톤으로 은은하게 둘러 "지금 확인이 필요한 카드"라는 우선순위를 시각적으로 표시(색상 단독이 아니라 카운트 배지 "N건"과 함께 사용).
- 각 알림 행은 좌측 2px 보더로 심각도 색을 표시 — 이 화면에서만 예외적으로 좌측 보더 강조를 쓴다(사이드바처럼 촘촘한 리스트에서 스캔 속도를 위한 의도적 선택). 카드 자체(패널 레벨)에는 여전히 좌측 보더 강조를 쓰지 않는다.

### 2.7 LockStatusWidget

```ts
interface LockStatusWidget {
  tmLock: { current: number; sparkline: number[] };
  txLock: { current: number; sparkline: number[]; blockingSid?: string };
}
```

- 2열 미니 카드: TM Lock(파랑, `--series-1`) / TX Lock(주황, `--series-2`). TX Lock이 임계치를 넘으면 카드 배경을 `--danger-soft`로, 숫자 색을 `--danger`로 전환.
- 각 카드 하단에 아주 작은 스파크라인(22px 높이)만 넣어 "지금 값 + 최근 추세"를 동시에 전달 — 축/그리드/툴팁 없는 최소 스파크라인이라는 점에서 §2.4의 정식 라인차트와 다르다.
- 하단에 관련 알림 연결 텍스트: "SID 214에서 Blocking 발생 중 → Lock Tree 보기" 링크로 `Lock Holder/Waiter Tree` 탭과 교차 연결.

### 2.8 QuickStatsList

```ts
interface QuickStat { label: string; value: string; }
```

TPS, DB Time(AAS), SGA/PGA, 가동시간 — 라벨/값 두 컬럼의 얇은 행 리스트, 구분선은 `border-bottom: 1px solid var(--border-soft)`(마지막 행 제외).

---

## 3. 반응형 동작

- 그리드는 `grid-template-columns: 1.9fr 1fr`. 뷰포트가 좁아지면(예: <1080px) `1fr`(1열)로 전환하고 **Sidebar가 MainColumn 아래로 이동** — 이때 `ActiveAlertsPanel`은 sticky를 해제한다(좁은 화면에서 알림이 화면을 가리지 않도록).
- `HeroScoreCard`는 좁은 화면에서 `flex-direction: column`으로 전환(게이지 위, 지표 리스트 아래).
- `TopSqlList`의 SQL 텍스트 컬럼은 항상 `min-width:0` + `text-overflow:ellipsis`를 유지해 좁은 화면에서도 레이아웃이 깨지지 않게 한다.

---

## 4. 디자인 토큰 (Dashboard A와 동일)

```css
:root {
  --bg:      oklch(0.150 0.018 258);
  --panel:   oklch(0.187 0.018 258);
  --panel-2: oklch(0.222 0.020 258);
  --panel-3: oklch(0.245 0.020 258);

  --border:      oklch(0.320 0.020 258 / 0.7);
  --border-soft: oklch(0.320 0.020 258 / 0.38);

  --text-primary:   oklch(0.94 0.008 258);
  --text-secondary: oklch(0.685 0.018 258);
  --text-tertiary:  oklch(0.50 0.018 258);

  --accent:          oklch(0.685 0.165 252);
  --accent-strong:   oklch(0.60 0.185 252);
  --accent-soft-bg:  oklch(0.685 0.165 252 / 0.15);
  --accent-2:        oklch(0.78 0.13 196);
  --accent-2-soft:   oklch(0.78 0.13 196 / 0.14);

  --danger:       oklch(0.665 0.19 24);
  --danger-soft:  oklch(0.665 0.19 24 / 0.15);
  --warning:      oklch(0.79 0.145 76);
  --warning-soft: oklch(0.79 0.145 76 / 0.15);
  --success:      oklch(0.735 0.15 148);
  --success-soft: oklch(0.735 0.15 148 / 0.15);

  --series-1: #3987e5;  /* 파랑 — CVD 검증됨, 앞선 명세 §6/§8 참고 */
  --series-2: #d95926;  /* 주황 */
}
```

폰트, 카드 라운드(12–14px, 사이드바 카드는 16px), 아이콘 정책(24 viewBox, stroke 1.8–2px, 라이브러리 비의존)은 Dashboard A 명세의 §4/§5와 동일하게 따른다.

---

## 5. 헬스 스코어 계산 (구현 참고용 제안)

시안의 `92`는 예시값이며, 실제 계산 로직은 백엔드에서 정의해야 한다. 참고용 가중 평균 제안:

```
healthScore = clamp(
  100
  - w1 * max(0, cpuPct - cpuTarget)
  - w2 * max(0, memPct - memTarget)
  - w3 * (txLockWaiting > 0 ? penaltyPerBlockingLock * txLockWaiting : 0)
  - w4 * (activeAlerts.critical * penaltyCritical + activeAlerts.warning * penaltyWarning)
  , 0, 100
)
```

가중치(`w1..w4`)와 목표치(`cpuTarget` 등)는 인스턴스 SLA/운영 기준에 맞춰 설정 가능한 설정값으로 둘 것을 권장 — 하드코딩하지 말 것.

---

## 6. 두 대시보드 중 무엇을 언제 쓸까 (제품 결정 메모)

- **Dashboard A(스택형)**: 여러 지표를 동시에 나란히 비교해야 하는 심층 분석/리뷰 상황, 넓은 모니터에서 상시 띄워두는 운영실 화면에 적합.
- **Dashboard B(벤토형, 본 문서)**: "지금 이 인스턴스 괜찮아?"를 3초 안에 답해야 하는 순찰(triage) 상황, 알림에 즉시 반응해야 하는 온콜 시나리오에 적합 — 헬스 스코어 하나 + 항상 보이는 알림 사이드바가 핵심.

두 화면 모두 같은 데이터 계약을 쓰므로, 사용자 설정으로 레이아웃을 토글하는 것도 고려할 수 있다(추가 개발 범위이며 이 문서의 필수 요구사항은 아님).
