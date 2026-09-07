# 다음 작업 정리

> 작성 2026-09-07 · 최종 갱신 2026-09-07
> 항목별 상태를 제목에 표시한다(**완료** / **불필요** / 표시 없으면 미착수).
> 대상: `DBAgent-Java` / `DBAgent-Java-AIX` (2026-08-26 확정 원칙 — 두 프로젝트는 항상 같이 진행)

---

## 1. 설정 정리 후속 (2026-09-07 작업에서 파생)

`src/main/resources/application.properties`에 누락돼 있던 `@Value` 키 6개를 주석과 함께 채워 넣었다
(`dbagent.oracle.read-timeout-ms`, `dbagent.rdb.connect-timeout-ms` / `read-timeout-ms` / `pool.min-idle` /
`pool.max-size`, `sqltuning.api.timeout-ms`). 값은 전부 코드 기본값과 동일해 **동작 변화는 없다.**
여기서 파생된 후속 항목들:

### 1.1 재빌드 + dist 재배포 — **완료 (2026-09-07)**

양쪽 `mvn clean package` 후 배포 3곳(`DBAgent-Java\dist`, `DBAgent-Java-AIX\dist`,
`DBAgent-Java-AIX\dist-aix`) 모두 교체하고 SHA256 일치를 확인했다. 8006 / 8007 재기동 후
HTTP 200 및 `err.log` 무에러 확인. `dist-aix`는 폐쇄망 전용이라 로컬 기동 검증은 생략(jar만 교체).

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

### 1.3 메인 `OllamaChatService` 타임아웃을 설정으로 분리 — **완료 (2026-09-07)**

메인의 `.timeout(Duration.ofSeconds(300))` 하드코딩을 `@Value("${aidba.ollama.timeout-ms:300000}")`
로 바꾸고 `application.properties`에도 명시했다. **기본값을 300000ms로 둬서 기존 동작은 그대로다.**

`aidba`는 두 프로젝트 간 동기화 제외 영역이라 AIX(30000ms)와 값이 다른 것은 의도된 상태로 남긴다.
이제 원격 GPU 서버 + 대형 모델로 옮겨 응답 시간이 달라져도 재빌드 없이 조정할 수 있다.

### 1.4 `aidba.ollama.url` / `aidba.ollama.model` 에 `@Value` 기본값 추가 — **불필요 (전제 오류)**

**이 항목의 원래 근거는 틀렸다.** "AIX 서버에 `application.properties`를 새로 만들면서 이 두 줄을
빠뜨리면 앱이 안 뜬다"고 적었으나, 실제로는 그렇지 않다:

- jar 옆의 외부 `application.properties`는 기본 위치를 **대체하지 않고 추가**된다. 거기 없는 키는
  jar 안 `BOOT-INF/classes/application.properties`에서 폴백한다.
- `start-aix.sh`는 `--spring.config.location` 없이 평범한 `nohup java -jar` 로 실행한다(65번 줄).
  즉 번들 properties가 항상 로드된다.
- 실증: 현재 `dist/application.properties`에는 `dbagent.databases-config`,
  `dbagent.oracle-env-path`, `aidba.errors-db-path`가 **없는데도** 앱이 정상 동작한다.

기본값 없는 키는 이 둘 외에도 `aidba.errors-db-path`, `dbagent.databases-config`,
`dbagent.oracle-env-path`가 있으나 같은 이유로 모두 문제되지 않는다. 번들 properties에서 키를
지우거나 `--spring.config.location`으로 기본 위치를 대체하는 경우에만 터지는데, 둘 다 현재 없다.

> `SqlTuningService`의 "커밋 대상에서 빠져 있다"는 주석(2026-09-04)도 지금은 맞지 않는다 —
> `src/main/resources/application.properties`는 커밋 대상이다(`git check-ignore` 무시 없음,
> 이력도 `934f377`까지 이어짐). 그 주석 자체를 고칠지는 별도 판단 필요.

### 1.5 AIX `dist/application.properties` 에 `aidba.*` 추가 — 선택, **사용자 결정 대기**

로컬 8007에서도 gemma로 테스트하려면 `aidba.ollama.url` / `aidba.ollama.model` 두 줄이 필요하다.
현재는 없어서 번들 기본값(`qwen2.5:3b`)으로 동작한다. 메인 `dist/`에는 이미
`aidba.ollama.model=gemma4:31b-cloud`가 들어가 있다.

임의로 넣지 않았다 — `-cloud` 모델은 프롬프트가 Ollama 클라우드로 전송되므로, 8007에도 적용할지는
판단이 필요한 사안이다.

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
| (문서 없음) | RDB 대시보드 UI 개편 + 잔여 지표(접속 고갈·장기 트랜잭션·버퍼 추이·temp/log 용량) | 기존 계획, 별건 |

---

## 5. 조치 불필요 (기록용)

- `dist-aix/data/oracle_errors.mv.db`의 2026-09-07 08:13 변경 — 사용자가 오전에 직접 데이터를
  확충·변환해 반영한 것. 이상 징후 아님. 이 파일은 **개발기 쪽이 정본**이라 폐쇄망으로 반출하는
  방향이 맞다(앱은 읽기 전용으로만 사용).
- `cubrid-server` 컨테이너는 사용자 지시로 내려둔 상태 유지.
