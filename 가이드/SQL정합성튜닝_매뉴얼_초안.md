# SQL 정합성/튜닝 매뉴얼 (초안 v0.1)

이 문서는 `New_sLLM` 프로젝트에서 특허청 KIPOADM `22~26년SQL검토` 실사례 문서(830건 추출 → 607건 정제)를 분석하며 확인한 **실무 DBA 판단 기준**과, sLLM 학습/평가에 쓰인 **26개 SQL 이슈 카테고리 체계**(`New_sLLM/eval/eval_cases.py`)를 결합해 작성한 참고 매뉴얼이다.

## 이 문서의 성격

- 자동화된 룰이 아니라, 실제 DBA 검토 이력에서 뽑아낸 "이 회사에서는 이렇게 판단한다"는 기준집이다.
- 현재 `SqlTuningController`의 모델 기반 analyze 엔드포인트는 sLLM 서버 미연동으로 비활성화 상태다(2026-08-29 확인, 항상 "연동 필요" 메시지 반환). 이 문서는 그와 무관하게 **사람이 직접 참고하는 정적 체크리스트**로 우선 활용한다.
- 추후 sLLM이 챔피언 갱신되고 실제로 연동되면, 이 문서 내용 중 1장("운영 환경 특이사항")을 시스템 프롬프트나 RAG 컨텍스트로 재사용하는 방안을 검토할 수 있다.

---

## 1. KIPOADM 운영 환경 특이사항 — 일반적인 SQL 튜닝 상식과 다르게 판단해야 하는 것들

### 1-1. 통계정보가 2018년 기준으로 고정되어 있음 (의도적 상태, 결함 아님)

과거 통계정보 자동 갱신(auto stats job)을 켜뒀다가 그로 인한 실제 장애가 발생한 이력이 있어, 이후 자동 갱신을 끄고 오래된(2018년 기준) 통계정보를 그대로 둔 채 튜닝을 진행해왔다.

- **하지 말 것**: "통계정보를 최신으로 갱신하세요(`DBMS_STATS.GATHER_*`)"를 만능 해법처럼 제시하는 것. 이 회사에서는 전체 자동 갱신 재활성화가 과거 장애 원인이었다.
- **대신 제안할 것**: 문제 테이블/컬럼에 국한된 수동 `DBMS_STATS.GATHER_TABLE_STATS`나 `EXTENDED_STATS`(컬럼 그룹) 생성처럼 범위를 좁힌 조치. 통계 갱신을 언급할 경우 반드시 "전체 스키마 자동 갱신 재활성화"와는 구분해서 말할 것.

### 1-2. `TABLE ACCESS FULL`(풀스캔)이 항상 튜닝 대상은 아님

실무 판단 기준: 데이터 건수가 적거나, 풀스캔이 오히려 CR(consistent gets/논리적 I/O)을 인덱스 스캔보다 줄이는 경우 의도적으로 풀스캔을 적용/유지한다. 실제로 22년 검토사례 중 `SN_RG069`(2만여건)가 "Full 스캔 발생하지만 건수 적어서 문제없음"으로 정상 처리된 사례가 확인됨.

- **하지 말 것**: 실행계획에 `TABLE ACCESS FULL`이 보인다는 이유만으로 무조건 missing_index/튜닝 대상으로 단정.
- **대신 확인할 것**: 대상 테이블의 실제 건수, 그리고 검토 의견에 실제로 "성능상 문제" 또는 "인덱스 필요"라는 판단이 명시되어 있는지. 건수가 적어 풀스캔이 유리/무해한 경우는 오히려 정상 케이스로 취급.

### 1-3. 의무 검토 프로세스로 인해 "특이사항 없음" 결과가 다수 — 정상 분포

이 시스템은 모든 변경 쿼리가 DBA 검토를 의무적으로 거치도록 되어 있다. 그래서 검토 의견 중 상당수가 "특이사항 없음"/"성능상 문제 없습니다" 같은, 실제로 문제가 없는 결과다. 검토 문서를 대량으로 훑을 때 이 비율이 높게 나와도 추출/분류 과정의 결함으로 의심할 필요는 없다 — 검토가 의무이므로 "이미 괜찮은 쿼리"가 표본에 자연스럽게 많이 섞여 있는 것이 정상이다.

### 1-4. DB 링크가 시노님(synonym)으로 가려져 있음 — 겉보기엔 평범한 테이블명

KIPOADM 스키마는 원격 테이블을 `table@link_name` 형태로 직접 쓰지 않고 **시노님으로 감싸서 사용**한다(예: `SN_KR170` → 실제로는 `KIPONPSADM.TB_KR170@LN_KIPO_ORANPS2_NPSUSER`). 그래서 SQL 텍스트에 `@`가 전혀 보이지 않으며, `@` 텍스트 검색만으로 "이 쿼리는 DB링크를 안 쓴다"고 판단하면 틀릴 수 있다.

- **확인 방법**: `SELECT * FROM dba_synonyms WHERE owner='KIPOADM' AND db_link IS NOT NULL` (2026-08-28 기준 34개 확인됨). 대상 원격 스키마는 `KIPODTSHADM`/`KIPONPSADM`/`KIPONPSHADM`/`NPS`/`PPUSER`/`SERVICEDESK`이며, 확인된 접두어 예시로 `SN_DS*`/`SN_KR17*`/`SN_SD*`/`SN_SU*`/`SN_REF_KS04`/`SN_USERBLGT`/`SD_SERVICECALLS` 등이 있다. DB 링크 예시: `LN_KIPO_ORANPSH_NPSHADM`, `LN_KIPO_ORANPS2_NPSUSER`, `LN_KIPO_NPS_NPSUSER`, `LN_KIPO_OVSD_SERVICEDESK`, `LN_KIPO_CP_PPUSER`.
- SQL에 위 시노님 패턴의 테이블명이 등장하면, 원격 조인/DB링크 특성(네트워크 비용, 원격 통계 신뢰도 등)을 감안해서 진단할 것.

### 1-5. `ORANPS`/`ORANPSH` 접두 자료는 다른 DB — KIPOADM 사례와 혼동 금지

튜닝 이력 파일명 중 `YYYY-ORANPS-*`/`YYYY-ORANPSH-*` 접두어는 KIPOADM이 아닌 **별도 DB** 대상 튜닝 건이다. 이 DB는 현재 스키마 이식/실측 검증 환경이 없으므로, 이 계열 사례를 KIPOADM 튜닝 판단에 그대로 적용하지 않도록 주의한다.

---

## 2. SQL 이슈 카테고리 체크리스트

New_sLLM 학습/평가에 쓰인 26개 카테고리 분류(성능 14개 + 정합성 12개, `parallel_dop_issue` 제외 시 25개 실사용)를 기반으로, 실무에서 이슈를 진단할 때 유형을 빠르게 스크리닝하기 위한 체크리스트다. "혼동 주의" 열은 실행계획/증상이 비슷해 보이지만 원인·해법이 다른 인접 카테고리다.

### 성능(Performance) 계열

| 카테고리 | 대표 증상 | 우선 확인할 해결 방향 | 혼동 주의 |
|---|---|---|---|
| missing_index | WHERE 조건 컬럼에 인덱스가 없어 FULL SCAN, Cost 높음 | 해당 컬럼에 단일 인덱스 생성 | 복합 인덱스 컬럼 순서 문제(index_column_order_mismatch), 함수 기반 인덱스 필요(function_based_index_missing), 카디널리티 오추정(cardinality_misestimate)과 구분 |
| stale_stats | 대량 적재 후 통계 미갱신, 옵티마이저가 실제보다 훨씬 적은 건수로 추정 | 해당 테이블 `GATHER_TABLE_STATS` (※ 1-1 참고, 전체 자동 갱신과는 구분) | function_based_index_missing, partition_pruning_fail와 구분 |
| bad_join_order | 대용량 테이블을 드라이빙 테이블로 삼아 NESTED LOOPS로 반복 조회 | 조인 순서/HASH JOIN 유도, 히스토그램 확인 | 파티션/함수기반인덱스/카디널리티 오추정 이슈와 구분 |
| cardinality_misestimate | 상관관계 있는 두 컬럼을 함께 조건으로 걸 때 예상 Rows 과소추정 | 확장 통계(`EXTENDED_STATS`, 컬럼 그룹) 생성 | function_based_index_missing, partition_pruning_fail와 구분 |
| partition_pruning_fail | 파티션 키 컬럼에 함수를 적용해서 파티션 전체 스캔 | WHERE 절에서 파티션 키에 함수 적용하지 않도록 재작성 | 복합 인덱스/히스토그램 이슈와 구분 |
| subquery_unnesting | 상관 서브쿼리가 매 행마다 반복 실행 | 조인/인라인뷰로 언네스팅 | 인덱스 생성/파티션 이슈와 구분 |
| bind_peeking | 값 분포가 편향된 상태에서 최초 실행 시 캐시된 플랜이 계속 재사용됨 | 히스토그램, Adaptive Cursor Sharing 검토 | 파티션/팬아웃 이슈와 구분 |
| function_based_index_missing | 컬럼에 함수(`UPPER` 등)를 적용해서 일반 인덱스를 못 씀 | 함수 기반 인덱스 생성 | 복합 인덱스 컬럼 순서, 파티션과 구분 |
| index_column_order_mismatch | 복합 인덱스는 있는데 선두 컬럼 없이 조회해서 인덱스가 활용되지 않음 | 인덱스 컬럼 순서 재검토(선행 컬럼 확인) | "인덱스가 아예 없다"(missing_index)와 구분 — 이미 있는데 순서가 안 맞는 경우 |
| materialized_view_staleness | 구체화 뷰가 최신화되지 않아 원본 테이블을 직접 집계 | 구체화 뷰 `REFRESH`, 쿼리 재작성 검토 | 인덱스/파티션 이슈와 구분 |
| connect_by_hierarchical_perf | 자기참조 컬럼에 인덱스가 없어 CONNECT BY가 레벨마다 테이블 전체를 반복 스캔 | 자기참조 컬럼에 인덱스 생성 | 파티션/팬아웃 이슈와 구분 |
| redo_undo_contention | 수백만 건을 단일 트랜잭션으로 UPDATE 후 한 번에 COMMIT | 배치로 나눠 커밋(UNDO/REDO 부하 분산) | 인덱스/파티션 이슈와 구분 |
| temp_tablespace_sort_spill | 대량 정렬 시 PGA 메모리 부족으로 TEMP 디스크 스필 발생 | 정렬 범위 축소, PGA/정렬 방식 검토 | 인덱스/파티션 프루닝과 구분 |

### 정합성(Correctness) 계열

| 카테고리 | 대표 증상 | 우선 확인할 해결 방향 | 혼동 주의 |
|---|---|---|---|
| duplicate_rows_from_join | 1:N 조인으로 팬아웃(fan-out) 발생, SUM 결과가 실제보다 부풀려짐 | 집계를 조인 전에 하거나 인라인뷰로 분리 | NULL/3치 논리 이슈와 구분 |
| null_comparison_error | `NOT IN` 서브쿼리 결과에 NULL이 섞여 있으면 항상 0건 | `NOT EXISTS`로 대체 | 팬아웃 이슈와 구분 |
| implicit_type_conversion | NUMBER 컬럼을 문자열 리터럴과 비교해서 인덱스를 활용 못 함 | 타입을 일치시켜 비교(암묵적 형변환 제거) | 파티션/팬아웃과 구분 |
| referential_integrity_violation | FK 제약이 없어 고아 행(orphan row)이 리포트에 영향을 줌 | FK 제약 추가 또는 `NOT EXISTS`로 필터링 | NULL 비교/3치 논리와 구분 |
| isolation_anomaly | 같은 로직에서 SELECT를 두 번 나눠 실행하는 사이 다른 세션이 COMMIT | 단일 쿼리 또는 일관된 스냅샷 시점으로 통합 | 팬아웃/참조무결성 이슈와 구분 |
| incorrect_aggregation | GROUP BY 결과에서 `MIN` 등으로 뽑은 값을 그룹 대표값처럼 오인 | grain(집계 단위) 재확인 | NULL/3치 논리와 구분 |
| outer_join_null_misinterpretation | LEFT JOIN 후 WHERE 절에서 우측 테이블 컬럼에 조건을 걸어 사실상 INNER JOIN처럼 동작 | 조건을 ON 절로 이동 | 팬아웃/3치 논리와 구분 |
| date_range_boundary_error | `BETWEEN`으로 날짜 범위 지정 시 마지막 날 시간대 데이터가 누락 | 다음 날 자정 미만(`< 다음날 00:00:00`)으로 재작성 | NULL/팬아웃과 구분 |
| rollup_grouping_sets_misuse | `ROLLUP` 소계 행을 애플리케이션에서 다시 SUM해서 결과가 2배로 나옴 | `GROUPING` 함수로 소계/전체합계 행을 구분한 뒤 처리 | 팬아웃/NULL 비교와 구분 |
| autonomous_transaction_side_effect | 자율 트랜잭션(`PRAGMA AUTONOMOUS_TRANSACTION`) 내 COMMIT이 상위 트랜잭션 ROLLBACK과 무관하게 유지됨 | 자율 트랜잭션 범위를 감사 로그처럼 진짜 독립적인 처리로만 한정 | NULL/팬아웃과 구분 |
| sequence_gap_assumption | 시퀀스 값 사이 간격(gap)을 데이터 누락으로 오판 | 시퀀스 gap은 CACHE/롤백 등으로 정상 발생함을 감안, 업무 로그 기반 등 별도 검증 방법 사용 | FK/참조무결성과 구분 |
| merge_statement_duplicate_update | `MERGE`의 `USING` 소스가 키 기준 1건이 아니어서 `ORA-30926` 또는 중복 업데이트 발생 | 소스 쪽을 키 기준 1건으로 사전 집계/중복 제거 | ROLLUP 이슈와 구분 |

> 참고: `parallel_dop_issue`(병렬 처리 부족) 카테고리는 New_sLLM 모델 평가에서 `materialized_view_staleness`와 지속적으로 혼동되는 구조적 한계가 확인되어 평가셋에서 제외된 바 있다. 사람이 판단할 때도 "병렬 처리 여부"와 "구체화 뷰 최신성"을 먼저 명확히 구분하고 접근할 것.

---

## 3. 문서 상태 및 다음 단계

- 이 문서는 **초안(v0.1)**이며, New_sLLM 프로젝트의 review_docs 실사례 분석(2026-08-25~28)과 eval 카테고리 체계(`New_sLLM/eval/eval_cases.py`)를 기반으로 작성됨. 실제 서빙 중인 sLLM이나 `DBAgent-Java` 코드와는 아직 연동되지 않은 정적 참고 문서다.
- 최신 소스: `New_sLLM/sLLM 구축 및 train 진행현황.md`(모델 실험 이력 전체).
- 향후 후보 작업:
  1. DBA 실사용 피드백을 받아 카테고리별 설명에 살 붙이기(현재는 정의 수준).
  2. sLLM이 챔피언 갱신·연동되면 1장 내용을 시스템 프롬프트나 RAG 컨텍스트로 재사용하는 방안 검토.
  3. `SqlTuningController`가 재연동되면 이 문서를 관리자 화면에 "참고 매뉴얼" 링크로 노출하는 것도 고려 가능(현재는 미정, 코드 변경 없음).
