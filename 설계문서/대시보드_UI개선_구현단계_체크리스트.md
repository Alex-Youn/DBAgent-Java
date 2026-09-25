# 대시보드 UI 개선 — 구현 단계 체크리스트

> 대상 화면: DASHBOARD 탭 (기존 게이지·탭 화면을 8개 프레임으로 교체, 우측 하단 스위치로 새 화면 / v2 전환)
> 원본 설계문서: [`대시보드 UI 개선 설계.md`](./대시보드%20UI%20개선%20설계.md) · 목업: [`UI개선_mockup.html`](./UI개선_mockup.html)
> 검토·결정 기록: [`작업정리/작업정리_2026-09-25.md`](../작업정리/작업정리_2026-09-25.md)
> 체크리스트 원본 항목: `수정필요 및 검토 내역_체크리스트.md` 9번째-2 (대시보드 그래프 개편)
> **전체 작업 순서는 [`전체_작업순서_체크리스트.md`](./전체_작업순서_체크리스트.md)를 따른다.** 이 문서의 1단계는 전체 순서의 A단계, 2단계는 C단계, 5단계의 ASH 구간 조회·AWR 보충은 D단계(성능 분석과 공용)에서 먼저 진행하고, 나머지(3·4·5 일부·6~11단계)는 F단계다.

**공통 규칙 (모든 단계)**
- 메인(`DBAgent-Java`, Java 17, SQLite)과 AIX(`DBAgent-Java-AIX`, Java 8, H2)에 **같이** 적용한다. 각 단계의 "AIX 포팅" 항목을 체크해야 그 단계 완료다.
- AIX는 `record`, `switch` 식, `List.of`/`Map.of`, `isBlank` 금지 (`Maps.of` 등 기존 대체 유틸 사용). 저장소 upsert는 SQLite `INSERT OR REPLACE` / H2 `MERGE`.
- gv$ 뷰·`inst_id` 사용 금지. 11g 대상 DB가 있으므로 `FETCH FIRST` 금지(`ROWNUM`).
- 새 설정값은 기존 `dbagent.monitor.*` 관례로 이름을 바꾸고, `@Value` 기본값을 반드시 둔다 (`application.properties`는 skip-worktree라 pull로 새 키가 안 들어감 → 키가 없어도 기본값으로 동작해야 함).
- 단계가 끝날 때마다 로컬 Docker Oracle로 검증 → 커밋(`/srccommit`) → 작업정리 기록.

---

## 0단계 — 착수 전 결정 ✅ 완료 (2026-09-25)

- [x] 데이터 소스: ASH 사용 (Diagnostics Pack 보유 전제), gv$·`inst_id` 미사용
- [x] 대기 분류: 목업 8분류 (ASH `wait_class`), Current Session 7분류는 그대로 유지
- [x] 리프레쉬 주기: 기본 3초, 화면에서 2~60초 조절
- [x] KILL 대상: TM Lock Holder 전체(USER 세션), 확인 패널 기본 전체 선택
- [x] 확인 패널: 조건 해소돼도 자동으로 닫지 않음, DB 전환 시 닫음·요청에 db_id 고정
- [x] 장애 판정: 기존 v2 `getFailureProb()`와 동일 (60초↑ + 자기는 안 막힘 + 누군가를 막고 있음) 6개↑
- [x] v2와의 관계: 스위치 유지, "기존" 자리에 새 화면 → 새 화면 / v2
- [x] 테이블스페이스 경고: 주의 97% / 위험 98%
- [x] 체크리스트 1-7·1-8은 Current Session에만 적용, 1-8 대시보드 탭 부분·9-1 프레임 통합은 취소
- [x] 60초 기준 프로퍼티화: `dbagent.monitor.tm-holder-last-call-et-seconds`

---

## 1단계 — 선행 수정 (v2에도 바로 효과, 단독 배포 가능)

### 1-1. getFailureProb inst_id 버그 ✅ 소스 수정 완료 (2026-09-25)
- [x] 메인 `MonitorService.getFailureProb()` — 깨진 1차 쿼리+폴백 제거, `/*+ rule */` 쿼리만 유지
- [x] AIX 포팅
- [x] 로컬 Oracle에서 옛 쿼리 ORA-00904 재현, 새 쿼리 정상 실행 확인
- [x] 양쪽 `mvn compile` 성공
- [x] 커밋 (메인 `af17423`, AIX `aaeca85`, 2026-09-25)

### 1-2. TM Holder 60초 기준 프로퍼티화 ✅ (2026-09-25)
- [x] `MonitorService`에 `dbagent.monitor.tm-holder-last-call-et-seconds` 추가 (기본 60, 문자열로 받아 검증하는 setter — 잘못 넣어도 기동 실패 없음)
- [x] `getFailureProb()`(장애 판정·장애조치 버튼) 쿼리의 `last_call_et >= 60`을 프로퍼티 값으로 교체
- [x] `getActiveAlerts()`("Blocking Session 감지" 알림) 쿼리의 `last_call_et >= 60`도 같은 값으로 교체
- [x] 값은 int로만 결합 (주입 위험 없음)
- [x] 1 미만·숫자 아님이면 60으로 보정하고 경고 로그
- [x] `dist/application.properties.sample`(메인·AIX, git 추적 파일)에 주석 예시 추가 — 실제 `application.properties`는 skip-worktree라 손대지 않음
- [x] AIX 포팅 (같은 코드, Java 8 호환, compile 성공)
- [x] 검증 (메인 jar, dist 설정, 로컬 Oracle에 실제 블로킹 세션 생성 — 세션 A가 행 잠금 유지, 세션 B가 대기):
  - 프로퍼티 30: 40초 시점 `failure_prob count=1`, "Blocking Session 감지(SID 46)" 알림 뜸
  - 프로퍼티 없음(기본 60): 40초 시점 `count=0`·알림 없음 → 70초 시점 `count=1`·알림 뜸 (기존 동작과 동일)
  - 프로퍼티 `abc`: 경고 로그 "using 60s" 후 정상 기동
- [x] 검증: 장애조치 판정과 Blocking Session 알림이 같은 기준으로 동시에 바뀜
- [x] AIX 런타임 테스트 (H2 설정으로 실제 블로킹 세션 판정 확인, 2026-09-25 배포 ① 전)
- [x] 커밋 (메인 `57a2a7b`, AIX `21183c3`)

### 1-3. CPU 코어 수 기준 통일 (`NUM_CPU_CORES`) — 체크리스트 1-1 ✅ (2026-09-25)
> **범위 결정 (2026-09-25 오케스트레이터, (가)안)**: `NUM_CPU_CORES`는 **코어 기준선**(AAS 비교)에만 쓰고, **CPU 사용률(%)의 분모는 기존 `NUM_CPUS` 유지**. 로컬 실측 NUM_CPUS=32 / NUM_CPU_CORES=16처럼 SMT 서버는 논리 CPU가 코어의 2~8배라, CPU% 분모를 코어로 바꾸면 같은 부하에서 CPU%가 부풀어 100%를 넘는다. 화면에 "코어 수"로 보이는 값은 모두 `NUM_CPU_CORES`로 통일되므로 검토결과의 "화면마다 코어 수가 갈림" 문제는 생기지 않는다.
- [x] 공통 헬퍼 `CpuCores.query(conn)` 작성 (`NUM_CPU_CORES` 우선, 없으면 `NUM_CPUS`)
- [x] `MonitorService.getAshActivity()` — Current Session 차트 코어 기준선 → 헬퍼 사용
- [x] `InstanceMetricSamplerService.sampleOne()` — `ash_cpu_cores`(코어 기준선)만 헬퍼 사용, `cpu_pct`는 `NUM_CPUS` 유지
- [x] CPU% 3곳(`getDashboardStats`, v2 상태, 인스턴스 상세)은 `NUM_CPUS` 유지 — 변경 없음
- [x] AIX 포팅 (같은 코드, compile 성공)
- [x] 검증 (메인 jar, dist 설정, 로컬 XE): `/api/ash_activity` `cpu_cores` 32 → **16**, 샘플러 `ash_cpu_cores` 새 샘플부터 **16**, 대시보드 CPU%는 기존 계산 그대로
- [x] AIX 런타임 테스트 (2026-09-25 배포 ① 전, 코어 16 확인)
- [x] 커밋 (메인 `998846a`, AIX `8d794da`)
- [x] AIX 포팅
- [x] 검증: v2 CPU%·Current Session 코어선이 새 코어 수 기준으로 표시 (A3, 코어 기준선만 NUM_CPU_CORES — CPU% 분모는 NUM_CPUS 유지 결정)
- [x] 커밋 (A단계, 배포 ① 포함)

---

## 2단계 — 수집기 확장 (60초 샘플러, 백엔드) ✅ 구현·검증 완료 (2026-09-25, 전체 순서 C단계)

- [x] 7분류 CASE / 8분류 CASE를 공통 상수로 분리 (7분류: 샘플러·`getAshActivity()` 공유, 8분류: 샘플러·신규 `/top` 공유)
- [x] 샘플러 ASH 쿼리를 한 번 스캔으로 `cat7, cat8` 동시 집계하도록 변경 (설계 4.3 (1))
- [x] 기존 `ash_*` 7개 저장 유지 + 신규 `ash_wc_*` 8개 저장 (`ash_wc_cpu` ~ `ash_wc_other`)
- [x] cat7/cat8이 NULL인 행 처리 (각 합산에서만 제외)
- [x] 샘플러가 매 사이클 `SYSDATE`를 읽어 DB별 "DB 시각 − 앱 시각" 차이를 메모리 캐시
- [x] 앱 기동 시·신규 DB 등록 시 ASH로 최근 1시간 1분 버킷 backfill (`ash_wc_*`)
- [x] `/api/metric_history` range 키 `15m`, `30m`(Current Session 1-10용), `3h` 추가 (알 수 없는 range는 기존대로 1h - 유지, v2 화면 호환)
- [x] `/api/metric_history` 응답에 `dbClockOffsetMs` 필드 추가 (원본 DB 추가 조회 없이 캐시 값)
- [x] AIX 포팅 (H2 저장 확인)
- [x] 검증: 같은 분의 `ash_*` 7개 합 = `ash_wc_*` 8개 합
- [x] 검증: Current Session 화면이 변경 전과 똑같이 동작
- [x] `query-performance-reviewer` 검토 (반영·이월 내역은 전체 작업순서 체크리스트 C단계)
- [x] 커밋 (main 8b27c6d / AIX d00ae18, 배포 ③)

---

## 3단계 — 저장 테이블 (SQLite / H2) ✅ 완료 (2026-09-25, 전체 순서 F1)

- [x] `mon_sqlstat_delta` (PK에 `plan_hash_value` 포함)
- [x] `mon_lock_sample` (1분 요약, `tm_holder_over_max` = 장애 판정 수 최대값)
- [x] `mon_kill_audit` (`instance_name` 포함, `inst_id` 없음) — 컬럼명 `serial_no`/`kill_result`(H2 예약어·특수문자 회피)
- [x] `mon_check_result` (`severity`: CRIT/WARN/INFO/OK/ERROR)
- [x] `mon_segment_size`
- [x] 시각은 epoch ms INTEGER, 테이블명 소문자 snake_case (기존 `instance_metric_history` 관례)
- [x] 보관 주기 배치: 1분 테이블 30일, `mon_kill_audit` 1년 (분할 삭제로 긴 쓰기 락 방지) — 매일 03:50, 5000행씩, 설정 `store-retention-days`/`kill-audit-retention-days`
- [x] 비밀번호 암호화 전례처럼 파일 크기 증가 대응 확인 (SQLite VACUUM / H2 DEFRAG) — 지운 공간은 재사용(SQLite free page / H2 auto compact)되어 보관 기간 이후 파일이 더 커지지 않음. VACUUM·DEFRAG_ALWAYS는 쓰기 락·종료 지연 때문에 쓰지 않음(지운 값이 남으면 안 되는 요구 없음)
- [x] AIX 포팅 (H2 1.4.200: `MERGE ... KEY`, `DELETE ... LIMIT`, `AUTO_INCREMENT` — 복사본 DB로 실행 확인)
- [x] 커밋

---

## 4단계 — Lock 실시간 API + KILL API (백엔드) ✅ 완료 (2026-09-25, 전체 순서 F2)

### 4-1. Lock 실시간
- [x] TX/TM 대기 건수 쿼리 (설계 5장 ③)
- [x] TM Holder 쿼리: `/*+ rule */`, 세션 단위 `GROUP BY sid, serial#`, **필터 없음**, `session_type`·`blocking_session`·`waiters`·`obj_cnt` 포함
- [x] 앱에서 분리: 장애 판정 수 = `last_call_et >= 프로퍼티` AND `blocking_session IS NULL` AND `waiters > 0`
- [x] 앱에서 분리: KILL 대상 = `session_type='USER'` 전체 (`blockingOnly`/`inactiveOnly` 설정 시에만 좁힘)
- [x] `obj_id` → 객체명은 캐시 (매 주기 `dba_objects` 조인 금지)
- [x] 쿼리 타임아웃 `lockQueryTimeoutSeconds`
- [x] DB별 결과 캐시(`2초`) + 진행 중 요청 공유(`failureProbInFlight` 패턴) → 보는 사람 수와 무관하게 주기당 1회
- [x] 타임아웃·실패 시 응답에 "판단 보류" 표시 (0으로 내려주지 않음)
- [x] `mon_lock_sample` 1분 요약 저장
- [x] **(2026-09-25 추가)** 응답에 `activeSessions` — 기존 대시보드 활성 세션과 같은 정의(`status='ACTIVE'`, 백그라운드·username 없음·모니터링 계정 제외), 같은 커넥션·같은 캐시, 실패 시 판단 보류
- [x] **(2026-09-25 추가)** 응답에 `memoryPct` — 기존 대시보드 메모리와 같은 정의((SGA+PGA 할당)/물리 메모리), DB별 60초 캐시
- [x] 검증: 한 세션이 테이블 2개에 TM Lock → Holder 1개로 셈 (objCnt 2, "T1 외 1개")
- [x] 검증: 아무도 막지 않는 60초↑ Holder 여러 개 → 장애 아님 (6개 → 판정 수 0)
- [x] 검증: 같은 순간 `/api/failure_prob`의 `count`와 새 판정 수가 같음 (1=1, 6=6, main·AIX)

### 4-2. KILL API
- [x] `POST .../lock/tm-holders/kill`, 본문 `{dbId, targets:[{sid, serial}]}`, 경로·본문 dbId 불일치 시 400
- [x] 관리자 권한 + `canAccessDb` 둘 다 확인
- [x] 실행 직전 Holder 재조회 → 요청 대상과 `sid+serial#` 교집합만 KILL, 없어진 세션은 SKIPPED
- [x] `session_type='USER'` 서버 강제 (백그라운드 절대 KILL 불가)
- [x] `ALTER SYSTEM KILL SESSION 'sid,serial#' IMMEDIATE` (`@inst_id` 없음), `sid`/`serial#`는 정수 파싱이 유일한 방어선
- [x] 결과 전부 `mon_kill_audit` 기록 (실행자, 인스턴스명, 결과, 오류)
- [x] 기존 `/api/kill_session`은 건드리지 않음 (새 화면은 새 API만 사용)
- [x] AIX 포팅
- [x] `security-reviewer` 검토 — Critical/High 없음. 반영: 감사 저장 1회 재시도 + 실패 시 응답 `auditWriteFailed`, 예외 시에도 KILL 표시·캐시 무효화(finally). 참고: 재조회~KILL 사이 SID 재사용은 sid+serial 매칭으로 충분(Low)
- [x] 커밋

---

## 5단계 — Top / 드로어 API + SQL 통계 델타 (백엔드) ✅ 완료 (2026-09-25, 전체 순서 D1·D2 + F3)

- [x] `ash_base` 공통 인라인 뷰 (8분류 CASE, FOREGROUND, 모니터링 계정 제외, `blocking_inst_id` 포함) — `AshRange` + `DashboardQueryService.BASE_COLUMNS`
- [x] `/top`: Top SQL·세션·이벤트 5개씩 한 번에, AAS = 샘플 수 × 간격 ÷ 구간 초 — GROUPING SETS로 ASH 1회 스캔(19c/21c EXPLAIN으로 TEMP TABLE TRANSFORMATION 확인, **11g는 폐쇄망 실측 필요**)
- [x] 보관 범위 밖 구간: `MIN(sample_time)` 기준으로 `dba_hist_active_sess_history`(10초) 보충, 응답 `source: ash|awr|mixed` (D2, `AshRange`)
- [x] 구간은 DB 시각 기준, 최대 24시간, 타임아웃 `ashActivityQueryTimeoutSeconds` (D1, AWR 섞이면 `ash-awr-query-timeout-seconds` 60초)
- [x] SQL 텍스트는 상위 5개만 `v$sqlstats`에서 조회, aged-out이면 "SQL 텍스트 없음" — IN 대신 단건 UNION ALL(X$ 고정 테이블은 IN이면 전체 스캔, 리뷰 실측)
- [x] 세션 상세 / SQL 상세 / 이벤트 상세 API (설계 6장) — 분류별 합계와 구간 전체 합계를 한 스캔(조건부 SUM + `SUM(SUM(w)) OVER()`), 응답 `queries`(조회 쿼리 보기), SQL 전문은 2000자 절단
- [x] 블로커가 다른 인스턴스면(`blocking_inst_id`/`blocking_instance` ≠ 접속 인스턴스) 로컬 조회하지 않고 표시만 — `blockerRemote` (단일 인스턴스라 true 경로는 로컬 검증 불가)
- [x] SQL 통계 델타 수집기: `(sql_id, plan_hash_value)`별, 첫 관측은 기준값만, 음수는 버림, 0 델타 미저장, 10분 미관측 키 제거 — `SqlStatDeltaCollector`(60초, 5초↑ 걸리면 경고 로그)
- [x] AIX 포팅
- [x] `query-performance-reviewer` 검토 — 반영: SQL 텍스트 IN→UNION ALL+타임아웃, 드로어 재스캔 병합, CLOB 절단, 수집 시간 로그. 이월: 11g에서 GROUPING SETS 1회 스캔 여부·운영 공유 풀에서 수집 시간 실측(폐쇄망)
- [x] 커밋

---

## 6단계 — 프런트 뼈대 ✅ 완료 (2026-09-25, 전체 순서 F4)

- [x] 스위치 "기존" 자리에 새 화면 연결 (라벨 변경, 마지막 선택값 저장은 기존 방식), 기존 게이지·탭 제거, v2 무변경 — 예전 DOM은 기존 JS 이벤트 바인딩 때문에 숨김만(스위치 제외·폴링 정지), 저장값 'legacy'는 새 화면으로 열림
- [x] 비Oracle DB면 새 화면·스위치 숨기고 기존 RDB 대시보드 그대로 — 비Oracle은 선택 시 별도 페이지(rdb/mysql/… dashboard.html)로 이동하는 기존 경로가 보장
- [x] 상태바: 구간 버튼 15분/1시간/3시간/24시간(기본 1시간)
- [x] 리프레쉬 입력창 기본 3초, 2~60 보정, 변경 즉시 적용
- [x] 수동 새로고침 / 자동 갱신 중지·재개 연결
- [x] 색 토큰: 기존 `style.css` 변수에 매핑, 라이트 테마(`data-theme="light"`) 값 정의
- [x] 폐쇄망 제약: 외부 폰트·CDN 없음, `color-mix()`·`dvh` 없음 (rgba 토큰·vh)
- [x] 첫 로딩 스켈레톤 (검은 빈 화면 없음)
- [x] DB 전환: `AbortController` + DB 세대 번호로 늦은 응답 폐기, 드로어·패널 닫기
- [x] 화면 숨김(`document.hidden`)·다른 탭 이동 시 폴링 중지, 복귀 시 1회 갱신 후 재개
- [x] 권한 없음(403) 한 줄 표시
- [x] 1920×1040 스크롤 없음 레이아웃 (flex, ②③ 행이 남는 높이), 작은 창은 대시보드 영역만 스크롤 — 높이는 JS가 실제 zoom 배율을 재서 계산(80% 하드코딩 없음). 1280×520: 페이지 스크롤 0, 영역 내부 176px
- [x] AIX 포팅
- [x] 커밋

---

## 7단계 — ① KPI · ② AAS 차트 · ④ 진단 배너 ✅ 완료 (2026-09-26, 전체 순서 F5 — `/top` 요청 연결만 F7)

- [x] ① 4칸: 현재 AAS(`ash_wc_*` 합), 구간 평균, 최대(코어 대비 %), 코어 초과 분 수
- [x] **(2026-09-25 추가) ① 5번째 칸 "활성 세션"**: 숫자 + 아래 작은 가로 막대 1개(10칸 세그먼트, DB별 세션 임계치 5번째 값=가득, 기존 임계치 색) — 기존 대시보드 도넛형 대체. 리프레쉬 주기(3초)로 `/lock/realtime`의 `activeSessions` 사용, 실패 시 `—`
- [x] **(2026-09-25 추가) ① 6번째 칸 "메모리 사용률"**: 숫자(%) + 아래 작은 가로 막대 1개(10칸, 100%=가득, 80/90% 색) — 기존 대시보드 도넛형 대체. `/lock/realtime`의 `memoryPct`, 실패 시 `—`
- [x] 검증: 같은 순간 기존 대시보드 활성 세션·메모리 값과 일치, 6칸이 한 줄에 들어가는지(80% 배율·좁은 화면)
- [x] ② 8분류 누적 영역 SVG, 누적 순서·색 설계 2.3대로, CPU 코어 점선
- [x] "Oracle 대기 클래스 기준" 라벨 + 데이터 소스 라벨
- [x] 범례 토글 (전부 숨김 불가), 호버 크로스헤어·2열 툴팁
- [x] 구간 선택: 최초·구간 변경 시 최대 부하 10분 자동 선택 / 클릭 5분 / 드래그 / 키보드 / "선택 해제"
- [ ] 선택 구간을 `dbClockOffsetMs`로 DB 시각 변환해 `/top` 요청 — 선택 구간은 DB 시각으로 `selection` 이벤트 발행까지 완료, `/top` 호출은 F7(⑤⑥⑦)에서
- [x] 데이터 공백은 끊어 그림 + "수집 이전", 수집 지연 3분↑ 배지
- [x] "표로 보기" (코어 초과 분 붉은 배경)
- [x] ④ 진단 배너: 최대 비중 클래스 + 조치 문구(설계 5장 ④ 표), 코어 초과 문구, 빈 구간 문구
- [x] zoom 90%에서 호버·드래그 좌표 확인
- [x] AIX 포팅
- [x] 커밋

---

## 8단계 — ③ Lock 카드 · ③-1 장애 처리 UI ✅ 완료 (2026-09-26, 전체 순서 F6)

- [x] 상단 4칸: TX 대기, TM 대기, 블로킹 TM Holder(60초↑) `n / 6개`, 최장 last_call_et
- [x] 계단형 차트, 최근 10분 **시간 기준** 버퍼, 경고선, 끝 라벨, 장애 구간 붉은 배경, KILL 세로선
- [x] 헤더 `LIVE · n초 주기` / "일시정지됨"
- [x] 판단 보류: 직전 값 흐리게, 0 안 찍음, 장애 표시 유지, KILL 버튼 비활성
- [x] 장애 시 카드 붉게 + 알림 바 문구(설계 ③-1) + 버튼
- [x] 비관리자: 버튼 대신 "장애 처리는 관리자만" 안내
- [x] 확인 패널: 기본 전체 선택, `60초 미만` 표시, 구문 미리보기
- [x] 확인 패널: 조건 해소 시 자동으로 닫지 않고 "장애 조건 해소됨" 안내
- [x] 확인 패널: 종료 세션 `종료됨`+비활성, 새 Holder는 `새로 생김`+미선택
- [x] 확인 패널: 취소/KILL/Esc로만 닫힘, DB 전환 시 닫힘, 요청 dbId는 패널 연 시점 값
- [x] KILL 완료 후 처리 기록 1줄
- [x] AIX 포팅
- [x] 커밋

---

## 9단계 — ⑤⑥⑦ Top 목록 · 상세 드로어

- [ ] ⑤ Top SQL / ⑥ Top 세션 / ⑦ Top 이벤트 (5개, 구성 막대, 빈 결과 문구, ASH 조회 실패 문구)
- [ ] 드로어: 오버레이, `body` 바로 아래 부착(zoom 버그 회피), Esc/배경/✕ 닫기, 포커스 복귀, 스택 뒤로가기
- [ ] 세션 / SQL / 이벤트 상세, "조회 쿼리 보기"
- [ ] 다른 인스턴스 블로커는 "다른 인스턴스(n번) SID m"으로만 표시
- [ ] "KILL 구문 복사"(관리자만): `navigator.clipboard` → `execCommand('copy')` → 수동 복사 안내 순서
- [ ] 접속 인스턴스명 + "이 인스턴스 기준" 표시
- [ ] AIX 포팅
- [ ] 커밋

---

## 10단계 — ⑧ 점검 알림

- [ ] 10분 점검 잡: 테이블스페이스(97/98), FRA(`NULLIF`, 미설정 제외), TEMP(`dba_temp_free_space`+`dba_temp_files` MAXSIZE), 잡 실패
- [ ] 1일 점검 잡(`dbagent.monitor.check-daily-hour`, 기본 3시, 기동 직후 1회): 테이블 용량+세그먼트 스냅샷(`dba_segments` 1회), 무효 객체, 통계
- [ ] 항목별 OK/ERROR 행 기록, 화면은 항목별 최신 결과 조회
- [ ] 카드(위험→주의→확인, 점검 실패 회색), 필터, 항목별 마지막 점검 시각, 프레임 안 가로 스크롤
- [ ] 상세 드로어(6.4): 7일 추이, 관련 객체, 조치 문구
- [ ] 수집 계정 `dba_*` 권한 확인 (없으면 ERROR 카드)
- [ ] AIX 포팅
- [ ] `query-performance-reviewer` 검토
- [ ] 커밋

---

## 11단계 — 전체 검증 · 배포

- [ ] 설계 문서 10장 수용 기준 전체 확인 (`feature-tester`)
- [ ] 브라우저 실렌더 검증 (로컬 Chrome + CDP, 앱 8006 포트): 1920×1040 스크롤 없음, zoom 90%, 라이트 테마
- [ ] 화면 상태 13종 각각 재현 확인 (설계 5장 "화면 상태 정의")
- [ ] 11g 대상 DB 동작 확인 (가능하면)
- [ ] 여러 브라우저 탭으로 동시 조회 시 Lock 조회가 주기당 1회인지 로그 확인
- [ ] 메인 빌드·배포 (`/appbuild`, dist) — 캐시버스터 번호 새로 부여
- [ ] AIX 빌드 (Java 8 타깃) 확인
- [ ] 폐쇄망 적용 절차서 작성 (새 프로퍼티 키 안내 포함: `tm-holder-last-call-et-seconds`, 점검 시각 등)
- [ ] 작업정리 기록 + 메모리 갱신
- [ ] 체크리스트 9번째-2 완료 표시

---

## 범위 밖 (별도 진행)

- 체크리스트 1-7 Duration 정렬 — Current Session 메뉴에만
- 체크리스트 1-8 Remote 탭 추가 — Current Session 메뉴에만
- v2 대시보드 테이블스페이스 계산 방식(`dba_data_files`)은 변경하지 않음

## `수정필요 및 검토 내역_체크리스트_검토결과.md`(2026-09-24)와의 관계

이 체크리스트는 **9번째-2(대시보드 그래프 개편)** 하나를 구현하는 문서다. 검토결과 문서의 나머지 항목은 여기 포함되지 않으며, 아래처럼 겹치는 부분만 연결한다.

| 검토결과 항목 | 이 체크리스트에서 | 비고 |
|---|---|---|
| 1-1 CPU 코어 수 `NUM_CPU_CORES` | **포함** — 1단계 1-3 | 5곳 전부 동시 변경 |
| 1-2 폐쇄망 그래프 로딩 (스켈레톤) | 새 대시보드에만 적용 (6단계 스켈레톤) | Current Session 메뉴 쪽은 별도 작업. 같은 스켈레톤 컴포넌트를 재사용하면 좋음 |
| 1-5 DB 전환 후 그래프 먹통 (레이스) | 새 대시보드에만 적용 (6단계 AbortController·세대 번호) | Current Session 메뉴 쪽은 별도 작업. 같은 패턴으로 고치면 됨 |
| 1-3 좌측 그래프 드래그 | 포함 안 됨 | 7단계 ② 드래그 구현을 재사용 가능 |
| 1-7 Duration 정렬, 1-8 Remote 탭 | 포함 안 됨 (범위 밖) | 2026-09-25 결정: Current Session 메뉴에만 |
| 1-9 Current Session 리프레쉬 3초 | 포함 안 됨 | 새 대시보드는 3초(조절 가능)로 확정. Current Session 쪽 타이머 위치는 여전히 미확인 |
| 2번째 성능 분석 개편 | 포함 안 됨 | 5단계 `ash_base` + AWR 보충 경로(`dba_hist_active_sess_history`)를 성능 분석 ①"getAshActivity 시작/종료 시각 파라미터화 + AWR 폴백"과 **공용으로** 만들면 중복 구현을 피할 수 있음 |
| 4-2 팝업 통일 | 포함 안 됨 | 새 대시보드 상세는 팝업이 아니라 드로어 — 팝업 통일 대상 아님 |
| 3번째, 4-1, 4-3, 5번째, 6번째, 7번째(테이블스페이스 화면 값 잔존 버그), 8번째 | 포함 안 됨 | 각각 독립 작업 |
