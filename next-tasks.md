# 다음 작업 정리

> 작성 2026-09-07 · **전부 미착수**. 이 문서에 적힌 항목 중 코드에 반영된 것은 없다.
> 대상: `DBAgent-Java` / `DBAgent-Java-AIX` (2026-08-26 확정 원칙 — 두 프로젝트는 항상 같이 진행)

---

## 1. 설정 정리 후속 (2026-09-07 작업에서 파생)

`src/main/resources/application.properties`에 누락돼 있던 `@Value` 키 6개를 주석과 함께 채워 넣었다
(`dbagent.oracle.read-timeout-ms`, `dbagent.rdb.connect-timeout-ms` / `read-timeout-ms` / `pool.min-idle` /
`pool.max-size`, `sqltuning.api.timeout-ms`). 값은 전부 코드 기본값과 동일해 **동작 변화는 없다.**
여기서 파생된 후속 항목들:

### 1.1 재빌드 + dist 재배포 — 우선순위 낮음

`application.properties`는 jar에 번들되므로 반영하려면 `mvn clean package` 후 재배포가 필요하다.
다만 이번 변경은 **주석과 기본값 명시뿐이라 동작이 바뀌지 않으므로 급하지 않다.** 다음 기능 변경
빌드에 묻어가면 된다.

> `dist/application.properties`는 **수정하지 않았고, 할 필요도 없다.** Spring Boot는 jar 옆의 외부
> `application.properties`를 부분 오버라이드로 취급해, 거기 없는 키는 번들 파일에서 폴백한다.
> 환경별로 실제 값이 다른 것(포트, `tns-admin`, 모델명 등)만 두는 현재 형태를 유지할 것 —
> 전체 키를 복사하면 같은 값을 두 곳에서 관리하게 되어 drift만 생긴다.

### 1.2 `DBAgent-Java-AIX` 에 동일 정리 적용 — **완료 (2026-09-07)**

AIX 쪽 `src/main/resources/application.properties`에도 누락 키를 채웠다. AIX는 **7개**가 빠져 있었다 —
메인과 같은 5개(`dbagent.oracle.read-timeout-ms`, `dbagent.rdb.*` 4개)에 더해 **`sqltuning.*` 섹션이
통째로 없었다**(`sqltuning.api.url`, `sqltuning.api.timeout-ms`). `@Value` 기본값이 있어 동작은 했지만
문서화가 안 된 상태였다.

AIX는 값이 다른 항목이 있어 그대로 두었다.
- `aidba.errors-db-path` — H2(`data/oracle_errors`) vs 메인 SQLite(`data/oracle_errors.db`)
- `spring.datasource.*` — H2 (`username`/`password` 존재) vs 메인 SQLite
- `aidba.ollama.timeout-ms` — **AIX에만 있는 키** (아래 1.3 참조)

### 1.3 메인 `OllamaChatService` 타임아웃을 설정으로 분리 — GPU 서버 계획 있으면 권장

두 프로젝트가 갈라져 있다:

| | 메인 | AIX |
|---|---|---|
| 구현 | `.timeout(Duration.ofSeconds(300))` 하드코딩 (`OllamaChatService.java:87`) | `@Value("${aidba.ollama.timeout-ms:30000}")` (`:43`) |

`aidba`는 두 프로젝트 간 동기화 제외 영역이라 divergence 자체는 정상이다. 다만 **메인은 값을
조정하려면 재빌드가 필요하다.** GPU 서버에 더 큰 모델을 올리면 응답 시간이 달라지므로, AIX처럼
`aidba.ollama.timeout-ms`로 빼두는 편이 낫다.

### 1.4 `aidba.ollama.url` / `aidba.ollama.model` 에 `@Value` 기본값 추가 — AIX 배포 안전장치

이 두 키만 `@Value` 기본값이 없다(`OllamaChatService.java:29,32`). 즉 **이 두 줄이 없는 환경에서는
AI DBA 기능만이 아니라 앱 전체가 기동하지 못한다.**

`SqlTuningService`는 같은 이유로 이미 기본값을 두고 그 근거를 주석에 남겨놨다(2026-09-04 결정).
`OllamaChatService`만 그 규칙을 따르지 않은 상태다.

**언제 터지는가:** `dist-aix\`에는 `application.properties`가 아예 없어서 지금은 번들 기본값으로
폴백해 문제가 없다. 그런데 GPU 서버 연동을 위해 AIX 서버에 이 파일을 새로 만들면서 이 두 줄을
빠뜨리면 그 순간 앱이 안 뜬다. GPU 연동 작업 **전에** 처리해 두는 게 안전하다.

### 1.5 AIX `dist/application.properties` 에 `aidba.*` 추가 — 선택

로컬 8007에서도 gemma로 테스트하려면 `aidba.ollama.url` / `aidba.ollama.model` 두 줄이 필요하다.
현재는 없어서 번들 기본값(`qwen2.5:3b`)으로 동작한다. 메인 `dist/`에는 이미
`aidba.ollama.model=gemma4:31b-cloud`가 들어가 있다.

---

## 2. 문서 동기화

`sql-tuning-rag-design.md`(SQL 튜닝 RAG 전환 설계안)를 `DBAgent-Java-AIX` 쪽에도 복사할지 결정.
`sqltuning`은 두 프로젝트 동기화 대상이라 문서도 양쪽에 두는 편이 일관되다. 설계안대로면 Java
코드는 안 바뀌므로 코드 동기화 부담은 없다.

---

## 3. 확인 필요 (답변 대기)

- **폐쇄망 AIX 서버에 `admin` 외 계정이 있는지.** tar 전체 이관 방식에서는 `users.mv.db`가 씨드로
  덮이고 비밀번호를 재설정하는 흐름인데, 다른 계정이 있다면 이관 때마다 같이 초기화된다.
- **보유 튜닝 케이스의 형태(엑셀/문서/DB)와 건수.** RAG 설계안 §10 미결정 사항 — 정형화 스키마와
  벡터 스토어 선택(브루트포스 vs `sqlite-vec`)이 여기에 달려 있다.

---

## 4. 별도 문서로 진행 중인 건

| 문서 | 내용 | 상태 |
|---|---|---|
| `sql-tuning-rag-design.md` | 파인튜닝 → RAG 전환 설계 (8010 뒤 배치, Java 변경 0) | 제안, 미확정 |
| 〃 §7 | 폐쇄망 GPU 서버 연동 시 확인사항 6건 | GPU 서버 준비 시 적용 |
| 〃 §8 | MCP 노출 (선택, 후순위) | 조건부 |
| `rdb_dashboard_ui_overhaul_2026_09_06` | RDB 대시보드 UI 개편 + §6 잔여 지표 | 기존 계획, 별건 |

---

## 5. 조치 불필요 (기록용)

- `dist-aix/data/oracle_errors.mv.db`의 2026-09-07 08:13 변경 — 사용자가 오전에 직접 데이터를
  확충·변환해 반영한 것. 이상 징후 아님. 이 파일은 **개발기 쪽이 정본**이라 폐쇄망으로 반출하는
  방향이 맞다(앱은 읽기 전용으로만 사용).
- `cubrid-server` 컨테이너는 사용자 지시로 내려둔 상태 유지.
