# SQL 정합성/튜닝 — RAG 전환 설계안

> **상태: 제안 (미확정, 코드 미변경)** · 작성 2026-09-07
> 대상: `com.dbagent.sqltuning` (DBAgent-Java / DBAgent-Java-AIX **양쪽 동기화 대상**)

---

## 1. 배경

현재 SQL 정합성/튜닝은 자체 파인튜닝 모델(Qwen2.5-Coder-7B + QLoRA 어댑터)을 WSL FastAPI 서버(`New_sLLM/serve/api_server.py`, 포트 8010)로 서빙해 호출한다.

**문제: 파인튜닝 결과 품질이 기대에 못 미친다.**

원인 분석 — QLoRA는 **출력 형식·문체**를 가르치는 데는 효과적이지만 **지식을 주입**하는 데는 약하다. 튜닝 사례처럼 "구체적 케이스를 알고 있어야 답할 수 있는" 문제는 파인튜닝보다 **RAG(검색 증강 생성)** 가 정석이다.

**방향 전환: 보유 튜닝 내역 + 외부 수집 튜닝 케이스를 벡터 DB로 구축해 검색 결과를 프롬프트에 실어 Ollama로 생성한다.**

---

## 2. 핵심 설계 결정

### 2.1 MCP는 크리티컬 패스에 넣지 않는다

RAG에는 MCP도 tool calling도 **필요 없다.**

- MCP는 "모델이 도구를 호출하게" 하는 프로토콜이다.
- RAG는 모델이 검색을 호출할 필요가 없다 — **검색 먼저 → 결과를 프롬프트에 삽입 → 생성 1회**로 끝난다. 모델은 검색이 일어났다는 사실을 몰라도 된다.

**이 선택의 실익:** 머지한 파인튜닝 모델이 tool call을 안정적으로 뱉는지 검증하는 관문을 통째로 우회한다. QLoRA를 튜닝 리포트 출력 형식으로 학습시켰다면 tool-call 포맷은 학습에 없었을 가능성이 높고, 직접 만든 GGUF는 Modelfile 템플릿에 tool 지원이 없어 Ollama가 `tools` 파라미터를 거부하는 경우도 흔하다. RAG는 이 위험을 아예 만들지 않는다. 생성기로 파인튜닝 모델이든 범용 모델이든 자유롭게 쓸 수 있다.

또한 agentic loop가 없으므로 분석 1건당 생성이 1회로 유지된다. 폐쇄망 GPU 1대를 여러 DBA가 공유하는 환경에서 응답 시간 예측이 가능하다.

### 2.2 이미 코드베이스에 있는 패턴이다

AI DBA(`com.dbagent.aidba`)가 정확히 같은 구조로 동작한다:

```java
List<String> docs = errorSearchService.retrieveDocs(prompt);  // 검색
String context = ...;                                          // 조립
String answer = ollamaChatService.ask(prompt, context);        // 생성 1회
```

`ErrorSearchService`의 현재 검색은 정규식 + SQL `LIKE`다 (그래서 `application.properties` 주석에 "RAG-lite"). 이번 작업은 **그 검색부를 벡터로 격상하는 것**이지 새 아키텍처 도입이 아니다.

---

## 3. 아키텍처 — 기존 8010 뒤에 배치

`sqltuning.api.url`을 그대로 두고, **그 뒤 FastAPI 서버의 내용물만 교체**한다.

```
[Java / DBAgent]
      |  POST {"prompt": ...}           ← 계약 변경 없음
      v
[FastAPI :8010]
      |-- 1. 문제 signature 추출
      |-- 2. 벡터 검색 (top-K 튜닝 케이스)
      |-- 3. 프롬프트 조립 (질의 + 실행계획 + 유사 케이스)
      |-- 4. Ollama 생성 호출
      v
   {"response": ...}                    ← 계약 변경 없음
```

### 이 배치의 이점

| 항목 | 효과 |
|---|---|
| **Java 변경량** | **0** — `SqlTuningService` / `SqlTuningController` / 프로퍼티 모두 그대로 |
| 두 프로젝트 동기화 | 불필요 (Java가 안 바뀌므로 AIX 포팅 작업 없음) |
| AIX 호환성 | AIX는 지금처럼 HTTP 1회만 던짐 |
| 롤백 | 8010 서버만 되돌리면 됨 |

### AIX 제약이 이 배치를 강제한다

AIX 서버는 POWER/ppc64 아키텍처다. Milvus·Qdrant·Chroma 등 벡터 DB는 ppc64 바이너리가 사실상 없어 올릴 수 없다. **벡터 DB와 임베딩 모델은 전부 x86 GPU 서버 쪽에 두어야 한다.** 8010 뒤에 배치하는 설계가 이 제약과 정확히 맞물린다.

### 부수 효과: "Java에서 Ollama 직접 호출" 안은 불필요해짐

별도로 검토했던 안 — `SqlTuningService`가 FastAPI 대신 Ollama `/api/generate`를 직접 호출하도록 바꾸는 것(요청에 `model`/`stream:false` 추가, URL 접미사 변경, 응답 `.response`는 동일이라 3줄 수준) — 은 **이 설계에서는 채택하지 않는다.** 검색·조립 단계가 8010에 남아야 하므로 Java가 Ollama를 직접 볼 이유가 없다.

> 다만 그 안을 나중에 되살릴 경우를 위해 기록: 새로 만드는 `@Value`에는 **반드시 기본값**을 줄 것 (`${sqltuning.ollama.model:...}`). `dist-aix\`에는 `application.properties`가 아예 없어 기본값 없는 속성은 **앱 전체 기동을 막는다** (`SqlTuningService` 주석의 2026-09-04 결정 참조).

---

## 4. 검색 설계

### 4.1 SQL 원문을 임베딩하지 말 것

텍스트가 비슷한 쿼리와 **튜닝 문제가 비슷한 쿼리는 다르다.** 원문을 임베딩하면 테이블명만 다른 무관한 쿼리가 상위로 올라오고, 정작 같은 병목(예: FULL SCAN + NESTED LOOPS 조합)을 가진 케이스는 못 잡는다.

**대신 "문제 signature"를 임베딩한다:**

- 실행계획의 오퍼레이션 구성 (FULL SCAN / NESTED LOOPS / HASH JOIN / SORT 등)
- 대기 이벤트
- 카디널리티 추정 오차 (예상 로우 수 vs 실측)
- 인덱스 부재 여부
- 버퍼 gets / 실행 시간 등 실측 지표

재료는 이미 있다 — `ExecutionPlanService`가 `DBMS_XPLAN.DISPLAY_CURSOR(ALLSTATS LAST)`로 실측 통계까지 뽑고 있고, 컨트롤러에 `analyze_from_query`(EXPLAIN PLAN, 쿼리 미실행) / `analyze_from_query_actual`(실제 실행 후 실측) 두 경로가 이미 구현돼 있다.

### 4.2 1 케이스 = 1 문서

문제 SQL + 실행계획 + 진단 + 조치 + 결과가 **한 덩어리**여야 의미가 있다. 512토큰씩 기계적으로 자르면 진단과 조치가 서로 다른 청크로 갈라져 검색 품질이 무너진다.

### 4.3 자체 튜닝 내역에 가중치를 둘 것

보유 튜닝 내역이 외부 수집 케이스보다 **훨씬 값지다** — 실 스키마·실 워크로드 기반이고 정답이 이미 검증돼 있다. 외부 케이스는 양은 많아도 신호가 약하므로, 혼합 시 자체 케이스에 가중치를 주거나 우선 노출한다.

---

## 5. 구성요소 선택

| 구성요소 | 권장 | 근거 |
|---|---|---|
| 임베딩 모델 | Ollama `/api/embed` + `bge-m3` | 폐쇄망이므로 임베딩도 로컬이어야 함. 튜닝 내역이 한국어라 다국어 모델이 유리 |
| 벡터 스토어 | `sqlite-vec` 또는 numpy 브루트포스 코사인 | 케이스 수천 건 규모에 Milvus는 오버엔지니어링. 운영 부담만 늘어남 |
| 생성 모델 | 평가 후 결정 (§6) | RAG가 지식을 공급하면 범용 모델로 충분할 수 있음 |

---

## 6. 평가 — 먼저 만들 것

**정답을 아는 실제 케이스 20건 내외를 홀드아웃 평가셋으로 먼저 확정한다.**

같은 잣대가 없으면 RAG가 파인튜닝보다 나아졌는지 판단할 방법이 없다. 현재 "파인튜닝 결과가 별로"라는 것도 체감 기반인데, 평가셋 없이 진행하면 다음 버전도 같은 상태가 된다.

### 같이 검증할 것: 파인튜닝 모델을 계속 쓸지

동일 평가셋으로 비교한다:

- `gemma4:31b + RAG`
- `머지 파인튜닝 모델 + RAG`

전자가 낫거나 비슷하면 **파인튜닝 파이프라인 자체를 걷어낼 수 있다.** 학습·머지·GGUF 변환 과정이 사라져 운영이 크게 단순해진다.

---

## 7. 폐쇄망 / GPU 서버 연동 시 확인사항

1. **`sqltuning.api.url` 기본값 함정** — 기본값이 `http://localhost:8010`인데 `dist-aix\`에는 `application.properties`가 없다. AIX 서버에 이 파일을 만들어 GPU 서버 주소를 명시하지 않으면 localhost로 붙으려다 조용히 실패한다.
2. **`aidba.ollama.url` / `aidba.ollama.model` 은 `@Value` 기본값이 없다.** AIX에 `application.properties`를 새로 만들 때 이 두 줄을 빠뜨리면 앱이 기동하지 못한다. 넣거나, 코드에 기본값을 추가하는 작업이 선행돼야 한다.
3. **Ollama 바인딩** — 기본이 `127.0.0.1:11434`라 원격에서 안 붙는다. GPU 서버에서 `OLLAMA_HOST=0.0.0.0:11434`로 기동해야 한다.
4. **모델 미존재 시 오해를 부르는 에러** — `/api/chat`이 404를 주면 `OllamaChatService`가 "구버전 Ollama"로 해석해 `/api/generate`로 폴백하고, 결국 `"Ollama /api/generate returned HTTP 404"` 메시지가 뜬다. 실제 원인은 대개 **해당 host에 모델을 pull 안 한 것**이다.
5. **VRAM** — `gemma4:31b`는 32.68B 파라미터 BF16 기준 가중치만 약 65GB, 컨텍스트 262K라 KV 캐시도 크다. 단일 GPU로 부족하면 Q4 계열 양자화(대략 20GB 선) 검토.
6. **모델 이름의 `-cloud` 접미사 제거** — `gemma4:31b-cloud`는 Ollama 클라우드 중계용 태그다. GPU 서버에 직접 올리면 `gemma4:31b`를 써야 한다. 폐쇄망에서 `-cloud`를 그대로 두면 외부 중계를 시도하다 실패한다.

---

## 8. MCP는 나중에 얹는다 (선택)

같은 벡터 DB 위에 `search_tuning_cases` 같은 도구를 MCP로 노출하는 것은 **추후 추가 작업**으로 가능하다.

- **값을 하는 경우:** Claude Code 등 외부 범용 클라이언트로 탐색적 DBA 분석을 하고 싶을 때.
- **값을 못 하는 경우:** 내부 웹 UI만 쓸 거라면 오버엔지니어링. Ollama 네이티브 tool calling + Java 루프가 훨씬 적은 일이고, agent loop 구현이라는 진짜 작업량은 MCP를 쓰든 안 쓰든 동일하다.

**남는 위험 (MCP·네이티브 tool calling 공통):** 모델에게 도구 실행권을 주면 운영 DB에 대한 blast radius가 커진다. 현재는 Java가 어떤 SQL이 실서버로 나가는지 완전히 통제한다 (SQL Runner `max-rows-limit=5000`, `timeout-seconds=30`). 실 운영 Oracle 8개를 보는 앱이므로 **도구를 읽기 전용으로 한정하는 설계가 전제**다.

---

## 9. 진행 순서

1. **평가셋 확정** — 정답 아는 실제 케이스 20건 홀드아웃
2. **케이스 정형화** — 보유 튜닝 내역을 "1 케이스 = 1 문서" 스키마로 구조화
3. **8010 서버에 검색 + 조립 추가** — 임베딩 인덱싱, top-K 검색, 프롬프트 조립
4. **현행 대비 비교** — 평가셋으로 `gemma + RAG` vs `머지모델 + RAG` vs 현행
5. (선택) GPU 서버 이전 — §7 확인사항 적용
6. (선택) MCP 노출 — §8 조건 충족 시

---

## 10. 미결정 사항

- 보유 튜닝 케이스의 현재 보관 형태 (엑셀 / 문서 / DB) — 정형화 스키마 확정에 필요
- 케이스 총 건수 — 벡터 스토어 선택(브루트포스 vs sqlite-vec)에 영향
- 외부 수집 케이스의 출처·라이선스
- GPU 서버 사양 (VRAM) — 생성 모델 및 양자화 수준 결정
