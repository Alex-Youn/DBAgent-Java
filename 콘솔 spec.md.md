# DB 모니터링 콘솔 · 첫 화면(Database Instances) 구현 스펙

이 문서는 첨부 이미지(다중 인스턴스 목록 화면)를 기준으로, Claude CLI 등 코딩 에이전트가 실제 프론트엔드 코드로 구현할 수 있도록 정리한 디자인/컴포넌트 스펙입니다. 프레임워크는 지정하지 않았으니 사용 중인 스택(React, Vue, plain HTML 등)에 맞춰 그대로 매핑해서 구현하면 됩니다.

## 1. 개요

- **화면 이름**: Database Instances (로그인 후 진입하는 콘솔 첫 화면)
- **목적**: 등록된 모든 DB 인스턴스의 상태를 카드 그리드로 한눈에 보여주고, 검색/상태 필터로 좁혀볼 수 있게 함. 카드 클릭 시 해당 인스턴스의 상세 대시보드로 이동.
- **테마**: 다크 모드 전용. 브랜드명 "PULSE", 브랜드 컬러는 시안→그린 그라디언트.
- **캔버스 기준 해상도**: 1440×900 (데스크톱). 좌우 패딩 40px, 상하 패딩 36px.

## 2. 디자인 토큰

### 색상

```
--bg-page:        #05070a   /* 전체 배경 */
--bg-card:        #0e131a   /* 카드 배경 */
--border:         rgba(255,255,255,0.08)   /* 기본 카드 보더 */
--border-strong:  rgba(255,255,255,0.09)

--text-primary:   #f5f7fa
--text-secondary: #8b97a6
--text-muted:     #5b6674

--brand-a:        #22D3EE   /* 시안 (accent A) */
--brand-b:        #34D399   /* 그린 (accent B) */
--brand-gradient: linear-gradient(135deg, var(--brand-a), var(--brand-b))

/* 상태(Status) 컬러 — 다른 용도로 재사용하지 않음 */
--status-good:     #0ca30c   /* Alive / 정상 */
--status-warning:  #fab219   /* Degraded / 주의 */
--status-critical: #d03b3b   /* Down / 위험 */
```

상태 컬러는 로직에 고정된 의미(정상/주의/위험)를 가지므로 다른 UI 요소(카테고리 구분 등)에 재사용하지 않습니다.

### 타이포그래피

Google Fonts: `Space Grotesk`(제목/버튼), `IBM Plex Sans`(본문/라벨), `IBM Plex Mono`(수치/코드성 텍스트 — 인스턴스명, 통계값, 배지).

```
--font-display: 'Space Grotesk', ui-sans-serif, system-ui, sans-serif;
--font-body:    'IBM Plex Sans', ui-sans-serif, system-ui, sans-serif;
--font-mono:    'IBM Plex Mono', ui-monospace, monospace;
```

| 용도 | 폰트 | 굵기 | 크기 |
|---|---|---|---|
| 로고 텍스트 (PULSE) | display | 700 | 17px |
| 페이지 타이틀 (Database Instances) | display | 600 | 16px |
| 카드 내 인스턴스명 | mono | 600 | 14px |
| 카드 부제 (엔진 · 리전) | body | 500 | 11px |
| 상태 라벨 (ALIVE 등) | display | 700 | 13px |
| 통계 라벨 (Uptime 등) | body | 400 | 11px |
| 통계 값 | mono | 700 | 15px |

### 여백 / 반경

```
--radius-card: 16px;
--radius-pill: 999px;
--radius-sm:   8px;   /* 아이콘 박스, 미니 배지 */
--radius-md:   10px;  /* 버튼, 검색창, 필터칩 */

--gap-grid:   18px;   /* 카드 사이 간격 */
--pad-card:   20px 22px;
--pad-page:   36px 40px;
```

## 3. 레이아웃 구조

```
Page (1440×900, bg: --bg-page)
├─ Header (row, space-between)
│  ├─ Left: Logo(PULSE) + divider + PageTitle("Database Instances")
│  └─ Right: Bell icon button(36×36) + Avatar(36×36, gradient, "AY")
├─ Toolbar (row, space-between)
│  ├─ Left: SearchInput(280px) + FilterChip×4 ("All·6" 활성 / "Alive·4" / "Degraded·1" / "Down·1")
│  └─ Right: "+ Add Instance" 버튼 (브랜드 그라디언트)
└─ Grid (3열, gap 18px, flex:1)
   └─ InstanceCard × 6 (2행 × 3열)
```

- 헤더 배경 뒤에 아주 은은한 방사형 글로우(브랜드 시안, opacity ~0.08)를 페이지 상단 중앙에 배치해도 좋음(장식용, 필수 아님).
- 그리드는 `display:grid; grid-template-columns:repeat(3,1fr); gap:18px;`. 카드 수가 늘어나면 행이 자동으로 늘어나야 함(반응형 시 2열/1열로 줄어드는 것 권장).

## 4. 컴포넌트 스펙

### 4.1 Header

- 로고: 34×34 라운드 사각형(cyan 8% 배경 + 보더) 안에 심전도/펄스 라인 아이콘(스트로크, brand-a 색) + "PULSE" 텍스트.
- 구분선: 1px × 22px, `--border`.
- 페이지 타이틀: 본문과 같은 톤, primary 텍스트.
- 우측 아이콘 버튼(벨): 36×36, `--bg-card` 배경 + 보더, 아이콘은 secondary 색.
- 아바타: 36×36, 브랜드 그라디언트 배경, 이니셜 텍스트(다크 네이비 `#04141a`, bold).

### 4.2 Toolbar

**검색창**: 280×38px, `--bg-card` 배경, 보더, 좌측 돋보기 아이콘 + placeholder "Search instances".

**필터 칩** (pill, `--radius-pill`, padding 8px 14px):
- 활성 칩("All·6")은 브랜드 그라디언트 배경 + 다크 텍스트.
- 비활성 칩은 투명 배경 + hairline 보더 + secondary 텍스트, 좌측에 상태색 점(dot) 표시 — Alive는 good, Degraded는 warning, Down은 critical.

**Add Instance 버튼**: 높이 38px, 브랜드 그라디언트 배경, 다크 텍스트, 좌측 `+` 아이콘.

### 4.3 InstanceCard (핵심 컴포넌트)

카드 하나의 데이터 모델(구현 시 이 구조로 컴포넌트화 권장):

```ts
type InstanceStatus = "alive" | "degraded" | "down";

interface DbInstance {
  name: string;          // "prod-primary-01" (mono, 14px, 600)
  engine: string;        // "PostgreSQL" | "MySQL" | "Redis"
  region: string;        // "us-east-1"
  environment: "production" | "staging";
  status: InstanceStatus;
  statusNote?: string;   // down 상태일 때 부가 텍스트: "down 6m ago"
  errorMessage?: string; // down 상태일 때 원인 메시지
  uptimePct?: number;    // null이면 "—" 표시 (down 상태)
  latencyMs?: number;
  connections?: number;
  cpuPct?: number;
  memPct?: number;
}
```

**카드 공통 구조** (위→아래):

1. 헤더 행: 좌측 [엔진 아이콘 박스 30×30 + 인스턴스명(mono) + "엔진 · 리전"(secondary, 11px)], 우측 환경 배지 pill (`PRODUCTION`은 brand-a 톤, `STAGING`은 중립 회색 톤).
2. 상태 행: 상태 점(8px, pulse 애니메이션) + 상태 라벨(대문자, 상태색) — `ALIVE` / `DEGRADED` / `NOT ALIVE`. down 상태는 우측에 `statusNote`를 muted 텍스트로 추가 표시.
3. 통계 3분할 행 (Uptime / Latency / Conns) — 라벨은 muted, 값은 primary(mono, 15px, 700). 값이 없으면 "—"를 muted 색으로. **값 자체의 색은 임계치를 넘었을 때만 상태색으로 강조** (예: latency 210ms → warning 색), 정상 범위면 primary 색 유지.
4. CPU/MEM 미니 바 2개 (라벨 32px 고정폭 mono + 트랙 5px 높이 rounded + 우측 퍼센트 텍스트). 바 채움색은 값 구간에 따라 상태색 매핑:
   - `< 60%` → good
   - `60–80%` → warning
   - `> 80%` → critical
5. (down 상태 전용) 4번 대신 경고 아이콘 + `errorMessage`를 담은 critical 톤 배너 (배경 `rgba(208,59,59,0.08)`, 텍스트 critical).

**카드 보더 색 규칙**:
- 기본(alive): `--border`
- degraded: `rgba(250,178,25,0.25)`
- down: `rgba(208,59,59,0.28)`

**엔진 아이콘 색**: alive/degraded 카드는 상태와 무관하게 엔진 아이콘 배경을 brand-a 톤(정상 계열) 또는 warning 톤으로, down 카드는 회색(비활성) 톤으로 — "이 인스턴스는 지금 응답이 없다"는 느낌을 아이콘에서도 전달.

### 4.4 샘플 데이터 (스크린샷 기준 6건)

```json
[
  { "name": "prod-primary-01", "engine": "PostgreSQL", "region": "us-east-1", "environment": "production", "status": "alive", "uptimePct": 99.99, "latencyMs": 12, "connections": 482, "cpuPct": 35, "memPct": 65 },
  { "name": "prod-replica-02", "engine": "PostgreSQL", "region": "us-east-1", "environment": "production", "status": "down", "statusNote": "down 6m ago", "errorMessage": "Connection timeout — replication halted", "connections": 0 },
  { "name": "prod-cache-01", "engine": "Redis", "region": "us-east-1", "environment": "production", "status": "alive", "uptimePct": 100, "latencyMs": 1, "connections": 1204, "cpuPct": 18, "memPct": 42 },
  { "name": "staging-primary-01", "engine": "MySQL", "region": "eu-west-1", "environment": "staging", "status": "degraded", "uptimePct": 98.42, "latencyMs": 210, "connections": 88, "cpuPct": 82, "memPct": 74 },
  { "name": "analytics-warehouse-01", "engine": "PostgreSQL", "region": "us-west-2", "environment": "production", "status": "alive", "uptimePct": 99.95, "latencyMs": 45, "connections": 156, "cpuPct": 55, "memPct": 60 },
  { "name": "staging-cache-01", "engine": "Redis", "region": "eu-west-1", "environment": "staging", "status": "alive", "uptimePct": 99.80, "latencyMs": 2, "connections": 340, "cpuPct": 22, "memPct": 30 }
]
```

## 5. 아이콘

모두 스트로크 기반, 20px 그리드, 라운드 캡/조인, 이모지 사용 금지.

| 아이콘 | 용도 |
|---|---|
| 심전도 라인(pulse) | 로고 |
| 종(bell) | 헤더 알림 버튼 |
| 돋보기 | 검색창 |
| `+` | Add Instance 버튼 |
| DB 실린더 | 카드 엔진 아이콘 |
| 경고 삼각형 | down 카드 에러 배너 |

## 6. 인터랙션 메모

- 필터 칩 클릭 → 그리드가 해당 상태로 필터링, 카운트 배지 갱신.
- 검색 입력 → 인스턴스명/엔진명 부분 일치 필터.
- 카드 클릭(또는 카드 우상단 확장 아이콘) → 해당 인스턴스의 상세 대시보드 화면으로 라우팅.
- 상태 점(dot)은 `alive`/`degraded`일 때만 은은한 pulse 애니메이션(2.2s, opacity 1→0.35, scale 1→1.7), `down`은 정적.
- 반응형: 1024px 이하에서 그리드 2열, 640px 이하에서 1열로 전환 권장.

## 7. 함께 참고할 연관 화면

이 콘솔 화면은 아래 두 화면과 같은 디자인 시스템(같은 컬러 토큰·폰트·컴포넌트 스타일)을 공유합니다. 전체 플로우를 함께 구현할 경우 참고하세요.

- **로그인 화면**: 좌측 폼 + 우측 라이브 메트릭/펄스 그래프 비주얼 패널의 스플릿 레이아웃.
- **인스턴스 상세 대시보드**: 상단 4개 통계 타일(Uptime/Latency/Storage/Connections) + 하단 리소스 개요(DB Status, CPU/Memory 바) + 클러스터 상세 리스트.

두 화면 모두 아래 링크의 캔버스에 함께 게시되어 있습니다:
https://claude.ai/code/artifact/54f6ac8b-d05a-4aaa-8919-4f2ab9a14b1b
