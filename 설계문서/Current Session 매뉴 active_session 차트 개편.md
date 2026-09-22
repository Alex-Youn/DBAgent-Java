# Active Session 모니터링 차트 — 구현 명세서

> 참고 프로토타입: `active_session_mockup.html` (프레임워크 의존성 없는 순수 HTML/SVG/JS).
> 이 문서는 해당 목업의 시각적·인터랙션 사양과, Oracle DB에서 실데이터를 가져오는 방법을 묶어
> CLI/개발 에이전트가 바로 구현에 착수할 수 있도록 정리한 명세서입니다.

## 1. 개요

- **대상**: Oracle 19c 기준 DB 모니터링 도구의 "Active Session" 위젯
- **핵심 지표**: AAS(Average Active Sessions) — 대기 유형(Wait Class 성격의 커스텀 분류)별 누적
- **기본 뷰**: 시간에 따른 **누적 영역 차트(Stacked Area)**
- **대안 뷰**: 동일 데이터의 **누적 막대 차트(Stacked Bar)** — 상단 토글로 전환
- **기준선**: `cpu_count` 파라미터 값(코어 수)을 수평 점선으로 표시 → 이 선을 넘는 구간이 CPU 대기 발생 구간

---

## 0. 착수 전 결정사항 (2026-09-22, 오케스트레이터 확정)

design-advisor 검토에서 제기된 착수 전 결정 항목에 대한 답변입니다. 이 문서의 나머지 섹션은 아래
결정을 전제로 합니다.

1. **RAC/gv$**: gv$ 뷰는 계속 사용하지 않는다. 이 프로젝트는 2026-09-06부로 "RAC 환경이라도 gv$를
   쓰지 않는다"는 원칙을 확정했다(인스턴스 간 조정 비용에 따른 성능 저하, `MonitorService.java:49-52`
   참고). 따라서 §3.2/§3.3/§7의 RAC 다중 인스턴스 합산(`GV$ACTIVE_SESSION_HISTORY`, `gv$parameter`
   SUM 등)은 적용하지 않고, **접속한 단일 인스턴스(v$) 기준**으로만 구현한다.
2. **배치**: 기존 Current Session 메뉴 안의 차트 2개(좌: 추이 라인, 우: Trace 산점도)를 이 목업 화면
   (§1~§9 전체: Active Session 영역/막대 차트 + Top SQL Activity Timeline)으로 **완전히 대체**한다.
   기존 산점도의 드래그→세션 상세 팝업 기능은 §8.4(Top SQL 드래그→세션 상세 표)가 동등한 기능을
   제공하므로 대체 대상에 포함한다.
3. **팔레트**: 기존 팔레트를 그대로 사용한다. §2의 7색은 이미 앱 운영 코드의 세션별 대기 분해 미니바
   (`app.js:2144, 2256`의 wait breakdown 툴팁)에서 쓰이는 색과 정확히 동일하다(CPU `#22d3ee` /
   Latch `#808000` / User I/O `#2ecc71` / TX Lock `#7c3aed` / Sys I/O `#e67e22` / TM Lock `#be123c`
   / Other `var(--text-muted)`). 즉 이 팔레트는 새로 도입하는 색이 아니라 기존 화면에 이미 쓰이고
   있던 색의 재사용이며, 별도 조정이 필요하지 않다. (참고: 이 팔레트가 Current Session 상단 추이
   차트 범례(ACTIVE SESSION/PARALLEL SESSION 등, `app.js:1693-1704`)와 `#808000`이 겹치는 부분이
   있었으나, 결정 2에 따라 그 차트 자체가 대체되므로 문제가 되지 않는다.)
4. **테마**: 흰색(라이트) 배경 화면은 없다. `style.css`의 `data-theme="light"`는 실제로는 네이비
   다크 배색이며, 이 앱에는 밝은 배경 테마가 존재하지 않는다. 따라서 §2의 "라이트 모드 대비비 부족"
   근거는 이 앱에는 적용되지 않는다. 다크 모드 기본 렌더링은 그대로 유지하되, 텍스처 토글의 접근성
   근거는 약화되므로 §7 체크리스트에서 **우선순위를 낮춘다**(2단계로 후순위 배치).

---

## 2. 카테고리 & 색상 팔레트

지정된 색상 조건을 그대로 사용하되, 범례/스택 순서는 **색각이상(CVD) 시뮬레이션 검증**을 통과하도록
고정했습니다. 이 순서를 바꾸면 안전성이 깨질 수 있으므로 구현 시 아래 순서를 유지해 주세요.

| 순서 | 카테고리 | 색상 (hex) | 비고 |
|---|---|---|---|
| 1 | CPU | `#22d3ee` | 스카이블루(시안) |
| 2 | Latch | `#808000` | 올리브 — 회색·주황과 가까워 검증상 순서 배치로 분리 필요 |
| 3 | User I/O | `#2ecc71` | 초록 |
| 4 | TX Lock | `#7c3aed` | 보라 |
| 5 | Sys I/O | `#e67e22` | 주황 |
| 6 | TM Lock | `#be123c` | 크림슨(진빨강) |
| 7 | Other | `var(--text-muted)` (`#898781`) | 테마 회색(고정), 나머지 전부 |

**접근성 보완 장치 (필수 구현)**
- 범례는 항상 표시 (색상 단독 식별 금지)
- "고대비 텍스처" 토글: CVD/저시력 사용자를 위해 45°/135° 사선 패턴으로 채우기 전환
- "표로 보기" 토글: 모든 수치를 텍스트 표로 노출 (contrast 경고 항목의 대체 접근 경로)
- **다크 모드가 기본값**: 라이트 모드에서는 CPU(시안)·User I/O(초록)·Sys I/O(주황) 색상이 밝은 배경과
  대비비 3:1 미만(§2 표 참고)이라 릴리프 규칙(범례·표 보기)에 계속 의존해야 하지만, 다크 배경에서는
  이 대비 문제가 대부분 해소됩니다. 그래서 두 목업 모두 **초기 로드시 다크 테마로 렌더링**하고,
  우상단 토글로 라이트 모드를 켤 수 있게 했습니다. 구현 시 최초 렌더가 다크가 되도록 `<html
  data-theme="dark">`를 기본으로 두고, 토글이 `data-theme`을 `"light"` ↔ `"dark"`로 전환하는 방식을
  그대로 따르는 것을 권장합니다.

---

## 3. Oracle 데이터 소스

### 3.1 카테고리 매핑 (SQL CASE 표현식)

Oracle의 표준 `WAIT_CLASS`는 이 7개 커스텀 분류와 1:1로 대응하지 않습니다(예: `enq: TX -`로 시작하는
이벤트도 하위 사유에 따라 `WAIT_CLASS`가 Application/Concurrency/Configuration 등으로 제각각 분산됩니다).
따라서 `WAIT_CLASS`가 아니라 **`SESSION_STATE` + `EVENT` 이름 패턴**으로 직접 분류하는 것이 정확합니다.

```sql
CASE
  WHEN session_state = 'ON CPU'        THEN 'CPU'
  WHEN event LIKE 'latch%'             THEN 'Latch'
  WHEN event LIKE 'enq: TX%'           THEN 'TX Lock'
  WHEN event LIKE 'enq: TM%'           THEN 'TM Lock'
  WHEN wait_class = 'User I/O'         THEN 'User I/O'
  WHEN wait_class = 'System I/O'       THEN 'Sys I/O'
  ELSE 'Other'
END AS category
```

> 실제 운영 환경에 적용하기 전에 `SELECT DISTINCT event, wait_class FROM v$event_name WHERE event LIKE 'enq: T%' OR event LIKE 'latch%'`
> 로 해당 버전/환경의 이벤트 목록을 확인해 분류 규칙을 보정하는 것을 권장합니다.

### 3.2 시계열 집계 쿼리 (예시: 최근 N분, 1분 버킷)

```sql
SELECT
  TRUNC(sample_time, 'MI')                         AS bucket_time,
  CASE
    WHEN session_state = 'ON CPU'  THEN 'CPU'
    WHEN event LIKE 'latch%'       THEN 'Latch'
    WHEN event LIKE 'enq: TX%'     THEN 'TX Lock'
    WHEN event LIKE 'enq: TM%'     THEN 'TM Lock'
    WHEN wait_class = 'User I/O'   THEN 'User I/O'
    WHEN wait_class = 'System I/O' THEN 'Sys I/O'
    ELSE 'Other'
  END                                               AS category,
  COUNT(*) / 60                                     AS aas   -- 1초 샘플링 가정 시 근사 AAS
FROM v$active_session_history
WHERE sample_time >= SYSDATE - (:range_minutes / 1440)
GROUP BY TRUNC(sample_time, 'MI'),
  CASE
    WHEN session_state = 'ON CPU'  THEN 'CPU'
    WHEN event LIKE 'latch%'       THEN 'Latch'
    WHEN event LIKE 'enq: TX%'     THEN 'TX Lock'
    WHEN event LIKE 'enq: TM%'     THEN 'TM Lock'
    WHEN wait_class = 'User I/O'   THEN 'User I/O'
    WHEN wait_class = 'System I/O' THEN 'Sys I/O'
    ELSE 'Other'
  END
ORDER BY 1;
```

**주의할 점**

- `V$ACTIVE_SESSION_HISTORY`는 SGA 인메모리 순환 버퍼라 보관 기간이 짧습니다(보통 최근 1시간 내외,
  워크로드에 따라 더 짧을 수 있음). 6시간/24시간 등 장기 구간을 조회하려면 `DBA_HIST_ACTIVE_SESS_HISTORY`
  (AWR)를 써야 하며, 이는 **Diagnostics Pack 라이선스**가 필요합니다. 라이선스가 없다면 자체 샘플링
  테이블을 별도로 적재하는 방식을 검토해야 합니다.
- **gv$는 사용하지 않습니다**(§0 결정 1). RAC 환경이라도 접속한 단일 인스턴스의 `v$active_session_history`
  기준으로만 조회하고, 다른 인스턴스 데이터를 취합하지 않습니다.
- `COUNT(*) / 60`은 샘플링 주기를 1초로 가정한 근사치입니다. 실제 샘플 간격이 다르면 분모를 그에
  맞게 조정하세요.

### 3.3 CPU 코어 기준선 쿼리

```sql
SELECT value AS cpu_count FROM v$parameter WHERE name = 'cpu_count';
```

접속한 단일 인스턴스 기준값만 사용합니다(§0 결정 1 — gv$ 미사용).

`cpu_count`는 Oracle EM의 ASH Analytics가 실제로 사용하는 기준값입니다. 물리 코어 수를 보고 싶다면
`v$osstat`의 `NUM_CPU_CORES`도 참고용으로 함께 조회할 수 있으나, 화면의 기준선은 `cpu_count`를
우선 사용하세요(가상화/리소스 매니저 설정을 반영하기 때문).

---

## 4. 프론트엔드 데이터 계약 (JSON)

백엔드가 위 쿼리 결과를 아래 형태로 내려주면, 목업의 렌더링 로직을 그대로 재사용할 수 있습니다.

```json
{
  "range_minutes": 60,
  "step_minutes": 1,
  "cpu_cores": 8,
  "categories": ["CPU", "Latch", "User I/O", "TX Lock", "Sys I/O", "TM Lock", "Other"],
  "series": [
    { "time": "2026-09-22T05:00:00Z", "values": [3.5, 0.2, 1.1, 0.3, 0.7, 0.1, 0.4] },
    { "time": "2026-09-22T05:01:00Z", "values": [3.1, 0.2, 1.4, 0.2, 0.6, 0.1, 0.3] }
  ]
}
```

- `values` 배열의 순서는 §2의 카테고리 순서와 **반드시 동일**해야 합니다(CVD 안전성이 순서에 종속됨).
- `time`은 ISO 8601 UTC 권장. 화면 표시 시 사용자 타임존으로 변환.

---

## 5. UI 컴포넌트 구조

```
[필터 행]  시간범위(30분/1시간/6시간/24시간) | 뷰전환(영역형/막대형) | 텍스처 토글 | 표 보기 토글
[KPI 행]   현재 AAS(+직전 대비 delta) | CPU 코어 수 | 선택 구간 평균 | 기준선 초과 비율
[차트 카드]
  ├─ 제목 + 설명 캡션
  ├─ SVG 차트 (영역형 ↔ 막대형, 동일 데이터 재사용)
  │    ├─ y축 그리드/눈금 (0 기준, 보기 좋은 간격으로 반올림)
  │    ├─ x축 시간 눈금 (~6개 라벨)
  │    ├─ CPU 코어 기준선 (점선 + 라벨)
  │    ├─ 크로스헤어 + 호버 툴팁
  │    └─ (텍스처 모드) 카테고리별 45°/135° 사선 패턴
  ├─ 범례 (항상 표시, 색상 스와치 + 라벨)
  └─ 표 보기 (토글 시 시간/카테고리별 값 + 합계 테이블)
```

### 스타일 규격 (마크 스펙)

- 막대: 폭 ≤ 24px, 세그먼트 사이 2px 여백(surface 색상), 최상단 세그먼트만 2px 라운드
- 영역: 세그먼트 사이 2px surface 색 스트로크로 경계 분리, fill-opacity ~0.82(텍스처 미사용 시)
- 그리드라인: 실선 헤어라인(1px), 절대 점선 금지 — 기준선(점선)은 그리드라인이 아니라 별도 주석이므로 예외
- 텍스트는 항상 텍스트 토큰(primary/secondary/muted) 색상 사용, 시리즈 색을 텍스트에 입히지 않음

---

## 6. 인터랙션 스펙

- **크로스헤어**: 포인터 이동 시 가장 가까운 시간 인덱스로 스냅, 수직 헤어라인 표시
- **툴팁**: 해당 시점의 전 카테고리 값 + 합계(AAS)를 한 번에 표시. 값은 강조(primary), 라벨은 보조색.
  합계가 `cpu_cores`를 초과하면 경고 문구("CPU 코어 수 초과 — CPU 대기 발생 구간") 노출
- **막대 모드**: 크로스헤어 대신 막대 자체가 히트 타겟(hover 시 해당 막대만 반응)
- **필터**: 한 행에 모아서 배치, 변경 시 KPI·차트·표 모두 동일 구간으로 재계산
- **재조회 시**: 로딩 중 이전 렌더를 유지(저투명도)하고 스켈레톤/깜빡임 없이 전환 — 실데이터 연동 시 필수

---

## 7. 구현 체크리스트

- [ ] §3.1 카테고리 매핑을 해당 환경의 `v$event_name`으로 검증
- [ ] 시간 범위별 데이터 소스 분기 (ASH vs AWR, 라이선스 여부 확인)
- [ ] ~~RAC 다중 인스턴스 합산 로직~~ (§0 결정 1 — gv$ 미사용, 접속 인스턴스 기준으로만 구현)
- [ ] `cpu_count` 기준선 주기적 갱신 (파라미터가 런타임에 바뀔 수 있음)
- [ ] §2 색상/순서 고정 유지 — 임의 재정렬 금지 (§0 결정 3 — 기존 앱 팔레트 재사용, 신규 색 아님)
- [ ] 범례 상시 표시, 표 보기 토글 — 접근성 구현 (텍스처 토글은 §0 결정 4에 따라 2단계로 후순위)
- [ ] **다크 모드를 기본값으로 렌더** (라이트는 토글로만 진입), `prefers-color-scheme`과 무관하게 초기
      상태는 다크 고정
- [ ] 크로스헤어/툴팁 키보드 포커스에서도 동일하게 동작 (접근성)
- [ ] `values` 배열 순서와 `categories` 순서 일치 여부 단위 테스트

---

## 8. Top SQL Activity Timeline (보조 차트)

Active Session 그래프 옆에 나란히 배치하는 companion 위젯입니다. "언제 세션이 몰렸는지"(Active
Session, §1~§7)와 "그 시점에 어떤 SQL이 원인이었는지"(이 섹션)를 함께 봅니다.

### 8.1 색상 재사용 원칙 (중요)

**이 차트는 §2의 팔레트를 그대로 재사용합니다.** SQL_ID별로 별도 색상을 배정하지 않고, 각 Top SQL을
**그 SQL의 지배적인 대기 이벤트 카테고리**에 매핑해 §2와 동일한 hex를 씁니다. 두 그래프를 나란히 볼 때
"오른쪽에서 보라색이 튀면 왼쪽의 TX Lock과 같은 사건"이라는 것을 색만으로 바로 알 수 있게 하기 위함입니다.

| 순서 | SQL 예시 | 매핑 카테고리 | 색상 (hex, §2와 동일) |
|---|---|---|---|
| 1 | 빈번한 단건 조회 (예: `SELECT * FROM ORDERS WHERE ...`) | CPU | `#22d3ee` |
| 2 | 핫블록 반복 접근 쿼리 | Latch | `#808000` |
| 3 | 대용량 풀스캔/리포트 쿼리 | User I/O | `#2ecc71` |
| 4 | 행 단위 UPDATE (`enq: TX` 유발) | TX Lock | `#7c3aed` |
| 5 | 테이블 락 유발 배치/MERGE (`enq: TM` 유발) | TM Lock | `#be123c` |
| — | Other(Top-N 밖 전체) | Other | `var(--text-muted)` |

> Sys I/O(주황)는 이 차트에서는 쓰지 않습니다. System I/O는 통상 백그라운드 프로세스(DBWR/LGWR/ARCH)
> 대기라 특정 SQL_ID 하나에 자연스럽게 귀속시키기 어렵기 때문입니다. 반대로 Latch는 특정 SQL의 핫블록
> 접근 패턴에서 실제로 기인할 수 있어 포함했습니다.
>
> 색상 순서(CPU, Latch, User I/O, TX Lock, TM Lock, Other)는 §2와 별개로 **이 서브셋 기준으로 다시
> CVD 검증**을 거쳤습니다. Sys I/O를 넣고 빼는 등 카테고리 구성을 바꾸면 인접 색상 조합이 달라지므로
> 재검증이 필요합니다.

각 SQL의 대기 이벤트 카테고리는 실제로는 최근 N분간 해당 SQL_ID의 ASH 샘플을 `session_state`/`event`
기준으로 집계해 최빈 카테고리를 구하면 됩니다(§3.1의 CASE 표현식을 SQL_ID 기준으로 GROUP BY).

### 8.2 Top-N 산정 방식

Top 5는 **차트가 보여주는 전체 구간(예: 최근 1시간) 기준으로 한 번만 산정**하고, 그 5개 SQL_ID는 사용자가
구간을 드래그해서 좁혀 봐도 바뀌지 않습니다(색상·범례가 계속 안정적으로 유지되어야 하므로). 드래그 구간
안에서의 활동량 비교는 이미 고정된 이 5개 + Other 안에서만 재계산합니다.

```sql
SELECT sql_id, COUNT(*) / 60 AS aas
FROM v$active_session_history
WHERE sample_time >= SYSDATE - (:range_minutes / 1440)
  AND sql_id IS NOT NULL
GROUP BY sql_id
ORDER BY aas DESC
FETCH FIRST 5 ROWS ONLY;
```

### 8.3 데이터 계약 (JSON)

```json
{
  "range_minutes": 60,
  "step_minutes": 1,
  "sql_categories": [
    { "sql_id": "7fkt2u1zqm5xr", "label": "SQL-A1B2C3", "category": "CPU",     "module": "OrderService" },
    { "sql_id": "9h2mdk3jq8f1z", "label": "SQL-G7H8I9", "category": "Latch",    "module": "Analytics" },
    { "sql_id": "b3nq7x0plr4wc", "label": "SQL-D4E5F6", "category": "User I/O", "module": "ReportBatch" },
    { "sql_id": "k1pz9vc2mh6tj", "label": "SQL-J1K2L3", "category": "TX Lock",  "module": "InventoryService" },
    { "sql_id": "r5wq3ntf8xz0m", "label": "SQL-M4N5O6", "category": "TM Lock",  "module": "BatchLoader" }
  ],
  "series": [
    { "time": "2026-09-22T05:00:00Z", "values": [1.2, 0.3, 0.9, 0.4, 0.2, 0.5] }
  ]
}
```

- `values` 순서는 `sql_categories` 순서 + 마지막에 `Other` 합계를 붙인 형태.
- 프론트는 각 SQL의 색상을 `sql_categories[i].category`로 §2 팔레트에서 조회.

### 8.4 인터랙션: 드래그로 세션 상세 보기

Top SQL 그래프를 마우스로 드래그하면:
1. 드래그 구간이 반투명 밴드 + 점선 경계로 표시됨
2. 구간 요약(선택 구간, 구간 평균 AAS, 주요 SQL)이 표시됨
3. 그 구간에 해당하는 세션 상세 표(SID, SERIAL#, SQL_ID, SQL 미리보기, 모듈, 대기 이벤트, 경과시간)가 나타남

실제 데이터 조회 쿼리 예시:

```sql
SELECT sid, session_serial# AS serial#, sql_id, module, event AS wait_event,
       ROUND((SYSDATE - sql_exec_start) * 86400) AS elapsed_sec
FROM v$active_session_history ash
WHERE sample_time BETWEEN :window_start AND :window_end
ORDER BY elapsed_sec DESC
FETCH FIRST 10 ROWS ONLY;
```

SQL 텍스트 미리보기는 `v$sql.sql_text`(공유 풀에 남아있는 경우) 또는 장기 구간 조회 시
`dba_hist_sqltext.sql_text`(AWR, CLOB이므로 앞부분만 자름)로 채웁니다.

---

## 9. "Other" 드릴다운

Top SQL 그래프의 Other는 Top 5 밖의 모든 SQL을 하나로 뭉친 값입니다. 범례의 **Other 항목을 클릭**하면
그 안에 뭉쳐 있던 SQL들의 순위를 한 번 더 펼쳐 보여줍니다 — Top-N으로 가려졌던 "5등 밖" SQL들을
드릴다운으로 확인하는 2단계 탐색입니다.

### 9.1 동작 방식

- **범례의 Other 항목**에 클릭 가능 표시(밑줄 호버 + `▸` 화살표)를 추가합니다.
- 클릭 시 차트 카드 안에 순위 패널이 펼쳐집니다: 순위, SQL_ID, 막대(비중 시각화), 비율(%), 대기 이벤트
  카테고리(색상 점 + 라벨)를 가진 리스트.
- **범위는 현재 드래그 선택과 연동**됩니다 — 이미 구간을 선택한 상태에서 열면 그 구간 기준으로,
  선택이 없으면 현재 화면에 보이는 전체 구간 기준으로 랭킹을 계산합니다.
- 이후 사용자가 구간을 새로 드래그하거나 시간 범위(30분/1시간/…)를 바꾸면, 열려 있는 드릴다운
  패널도 자동으로 새 범위 기준으로 갱신됩니다(다시 클릭할 필요 없음).
- 상위 10개만 개별 표시하고, 그 밖의 SQL은 "이 외 N개 SQL이 약 X AAS를 차지"로 요약합니다
  (하위 항목까지 전부 펼치면 리스트가 무한정 길어질 수 있으므로).
- 닫기 버튼으로 패널을 접습니다.

### 9.2 데이터 소스 (Oracle)

핵심은 "이미 Top 5로 뽑힌 SQL_ID를 제외한 나머지"를 다시 랭킹하는 것입니다. §8.2에서 구한 Top 5의
`sql_id` 목록을 그대로 `NOT IN`에 사용합니다.

```sql
WITH tail AS (
  SELECT sql_id, COUNT(*) / 60 AS aas
  FROM v$active_session_history
  WHERE sample_time BETWEEN :window_start AND :window_end
    AND sql_id IS NOT NULL
    AND sql_id NOT IN (:top5_sql_id_1, :top5_sql_id_2, :top5_sql_id_3, :top5_sql_id_4, :top5_sql_id_5)
  GROUP BY sql_id
),
ranked AS (
  SELECT sql_id, aas, RANK() OVER (ORDER BY aas DESC) AS rnk
  FROM tail
)
SELECT sql_id, aas, rnk FROM ranked WHERE rnk <= 10
ORDER BY aas DESC;

-- 표시되지 않는 나머지(11위 이하) 요약
SELECT COUNT(*) AS tail_count, SUM(aas) AS tail_aas
FROM tail
WHERE sql_id NOT IN (SELECT sql_id FROM ranked WHERE rnk <= 10);
```

각 행의 "대기 이벤트 카테고리"(색상 점)는 §3.1의 CASE 표현식을 해당 `sql_id` + 선택 구간 조건으로
다시 집계해서 최빈 카테고리를 구하면 됩니다. `window_start`/`window_end`는 드래그 선택이 있으면
그 구간, 없으면 차트가 보여주는 전체 범위(§1의 `range_minutes`)를 사용합니다.

### 9.3 UI 구조

```
[Other 안의 SQL 순위]                                    [닫기]
선택 구간 -42분 ~ -50분 · Other 합계 AAS 4.70

1. ● SQL-xxxxxxxx  ▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░  43.7%  Latch
2. ● SQL-xxxxxxxx  ▓▓▓▓▓░░░░░░░░░░░░░░░  17.8%  Latch
...
10.● SQL-xxxxxxxx  ▓░░░░░░░░░░░░░░░░░░░   2.2%  User I/O

이 외 14개 SQL이 약 2.86 AAS를 차지 (개별 미표시)
```

- 막대 길이는 리스트 내 1위 값을 100%로 한 상대값.
- 색상 점은 §2/§8.1과 동일한 팔레트 — 여기서도 "같은 색 = 같은 이벤트" 규칙이 유지됩니다.
- 순위 항목을 다시 클릭해 §8.4의 세션 상세 표를 그 SQL_ID로 필터링하는 3단계 드릴다운으로 확장할
  수도 있습니다(선택 구현 — 최소 구현 범위에서는 순위 리스트까지만).

### 9.4 구현 체크리스트 (추가분)

- [ ] Top 5 산정은 전체 표시 구간 기준 1회만 (드래그로 재계산되지 않음)
- [ ] Other 드릴다운 범위는 드래그 선택 유무에 따라 분기 (선택 있음 → 구간, 없음 → 전체 표시 구간)
- [ ] 드릴다운 패널이 열려 있는 동안 구간/범위 변경 시 자동 갱신
- [ ] 상위 10개 초과분은 "이 외 N개, 합계 X AAS"로 요약 (무한 스크롤 방지)
- [ ] 드릴다운 항목 색상도 §2 팔레트 기준 (새로운 색 생성 금지)

---

## 10. 참고 파일

- `active_session_mockup.html` — §1~§7 사양이 구현된 정적 프로토타입(순수 JS/SVG, 외부 라이브러리
  없음). Active Session 단독 위젯의 전체 기능(막대 전환·텍스처·표 보기)이 여기 있습니다.
- `top_sql_activity_mockup.html` — §8~§9 사양이 구현된 프로토타입. Active Session과 Top SQL
  Activity Timeline을 나란히 배치하고, 드래그 세션 상세와 Other 드릴다운까지 포함한 동작하는
  레퍼런스입니다. 데이터 생성 로직(`genData1`/`genData2`), 드래그 브러시(`brush`, `finishBrush`),
  드릴다운 로직(`genOtherBreakdown`, `renderOtherDrill`)을 그대로 참고하거나 프로젝트의 프론트엔드
  스택에 맞게 포팅해 사용하세요.
