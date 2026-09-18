# oracle.env 제거 마이그레이션 체크리스트

설계 근거는 `설계문서/oracle_env_제거_설계검토_2026-09-18.md` 참고. 이 문서는 실행용 체크리스트만 담는다. 진행하면서 `[ ]`를 `[x]`로 갱신할 것.

## 0단계 — 사전 정보 수집 (오케스트레이터, 폐쇄망에서 직접 확보)

- [ ] **A. tnsnames.ora 정의 전문** — 8개 alias(`ORCL`,`ORCL2`,`SRCH1`,`SRCH2`,`PORT1`,`PORT2`,`ORCL3`,`ORCL4`) 항목 전체 확보

  | alias | HOST | PORT | SID/SERVICE_NAME | ADDRESS 개수 | LOAD_BALANCE/FAILOVER 여부 |
  |---|---|---|---|---|---|
  | ORCL | | | | | |
  | ORCL2 | | | | | |
  | SRCH1 | | | | | |
  | SRCH2 | | | | | |
  | PORT1 | | | | | |
  | PORT2 | | | | | |
  | ORCL3 | | | | | |
  | ORCL4 | | | | | |

- [ ] **B. 실접속 기준표** — 8개 인스턴스 각각에서 실행 결과 수집

  ```sql
  SELECT instance_name, host_name, version FROM v$instance;
  SELECT name FROM v$database;
  ```

  | alias(=db_id) | instance_name | host_name | version | database name |
  |---|---|---|---|---|
  | ORCL | | | | |
  | ORCL2 | | | | |
  | SRCH1 | | | | |
  | SRCH2 | | | | |
  | PORT1 | | | | |
  | PORT2 | | | | |
  | ORCL3 | | | | |
  | ORCL4 | | | | |

- [ ] **C. 폐쇄망 dist `application.properties`에 `dbagent.oracle.tns-admin=` 값이 명시돼 있는지 확인** — 있음 / 없음(있으면 값: ________________)
- [ ] **D. 루트 `databases.json`의 `ORCL5` 중복("포탈 #1/#2") 의도 확인** — 의도된 것 / 실수(정리 필요)
- [ ] **E. 기본 DB fallback(빈 db_id) 제거 여부 결정** — 완전 제거(권고) / `"default": true` 플래그로 유지

> A~E 전부 체크되기 전에는 1단계를 시작하지 않는다.

- [x] RAC 여부 확정 (2026-09-18, 오케스트레이터 확인): **RAC DB 4개 + 싱글 인스턴스 DB 1개 = 총 8개 접속 항목**. 대응 방식: **노드별 개별 등록**(SID 기반) — V$ 뷰가 인스턴스 로컬이라 service_name보다 이 방식이 모니터링 목적에 더 적합(설계 문서 "RAC 대응" 절 참고)

## 1단계 — 코드 변경 (동작 무변화)

- [ ] `OracleConnectionPoolManager.buildDsn()`에 `connect_mode`(sid/service/descriptor) 분기 추가 — 미지정 시 현행 동작 유지
- [ ] 풀 생성 시 실제 DSN INFO 로그 추가 (`oracle-<id> -> host:port:sid`)
- [ ] `DatabaseConfigService.init()`에 id 중복 / `(host,port,sid)` 중복 WARN 로그 추가
- [ ] `TnsAdminInitializer.deriveFromOracleHome()` 제거 (0단계 C 확인 후에만)
- [ ] `PoolTestController`(`/api/pool/test`)에 `authService.canAccessDb()` 인증 체크 추가 (부수 발견 보안 이슈, 이 단계에서 같이 처리 권장)
- [ ] 로컬 도커에서 기존 동작(alias 접속) 회귀 없음 확인
- [ ] main 컴파일/빌드/배포
- [ ] AIX 동일 포팅 + 컴파일/빌드/배포
- [ ] git 커밋 + push (main/AIX 각각)

## 2단계 — 데이터 전환 (인스턴스 단위, 점진 — 폐쇄망에서만 실검증 가능)

순서: TEST → 검색DB → 포탈 → 통합DB(마지막)

- [ ] TEST01
- [ ] TEST02
- [ ] SRCH1
- [ ] SRCH2
- [ ] PORT1
- [ ] PORT2
- [ ] ORCL3
- [ ] ORCL4(또는 통합DB 실제 id)

각 항목 공통 절차:
1. [ ] `databases.json.bak-YYYYMMDD` 백업
2. [ ] `host`/`port`/`sid` 채움 (0단계 A 표 기준)
3. [ ] `GET /api/pool/test?db_id=...` 접속 성공 확인
4. [ ] `v$instance`/`v$database` 결과를 0단계 B 기준표와 대조 — 불일치 시 즉시 중단, host 재확인
5. [ ] 문제없으면 다음 인스턴스로

## 3단계 — tnsnames.ora 의존 제거

- [ ] 8개 전부 2단계 통과 확인
- [ ] `dbagent.oracle.tns-admin` 값 비움 (프로퍼티는 유지)
- [ ] 며칠 정상 운영 확인 (alias 폴백 코드는 유지 상태)

## 4단계 — oracle.env 제거

- [ ] `DatabaseConfigService.resolve("")` → null 반환으로 변경
- [ ] `listAccounts()`의 blank 분기 정리
- [ ] `app.js`의 `window.currentDbId || ""` 요청 지점에 "빈 값이면 요청 안 보냄" 가드 추가
- [ ] `oracle.env` 파일 삭제 — 루트
- [ ] `oracle.env` 파일 삭제 — dist
- [ ] `oracle.env` 파일 삭제 — 폐쇄망 배포본
- [ ] `dbagent.oracle-env-path` 프로퍼티 제거 + `TnsAdminInitializer`/`DatabaseConfigService`의 관련 `@Value` 필드 동시 제거
- [ ] `가이드/IMPLEMENTATION.md` 문서 갱신 (oracle.env 설명 부분)
- [ ] `dist/application.properties.sample` 정리
- [ ] main/AIX 동일 반영, 빌드/배포, 커밋+push

## 5단계 — alias 경로 완전 삭제 (최종 정리)

- [ ] `buildDsn()`의 `"tns"` 레거시 분기 제거
- [ ] `TnsAdminInitializer` 클래스 삭제
- [ ] `dbagent.oracle.tns-admin` 프로퍼티 삭제
- [ ] `host` 빈 Oracle 인스턴스는 기동 시 명시적 오류 처리
- [ ] `db-mgmt.html` 라벨 "Host (필수)"로 수정 + `connect_mode` 드롭다운 추가
- [ ] `expected_instance_name` 필드 추가 + 최초 접속 시 `v$instance` 대조 검증 로직 구현
- [ ] main/AIX 동일 반영, 빌드/배포, 커밋+push

## 공통 주의사항 (매 단계 배포 시)

- `application.properties`/`oracle.env`/`databases.json`은 git skip-worktree 대상 — 파일 삭제/수정은 서버에서 직접, git으로 전파 안 됨
- 회사 PC는 pull 전 백업 원칙 준수
- 모든 코드 변경은 DBAgent-Java와 DBAgent-Java-AIX 양쪽에 동일 반영 (dual-project-sync 원칙)
- 각 단계 배포 후 `curl`로 실제 서빙 콘텐츠가 최신인지 확인 (build_environment 메모리 참고)
