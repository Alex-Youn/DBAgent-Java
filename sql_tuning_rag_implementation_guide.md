# SQL 튜닝 AI 어시스턴트 구축 가이드 (RAG 기반 하이브리드 아키텍처)

본 문서는 사내 튜닝 문서 800여 건과 Gemini API가 생성한 가상 데이터를 혼합하여, **Qwen-Coder 30B 모델 기반의 SQL 튜닝 RAG 시스템**을 구축하기 위한 엔드투엔드(End-to-End) 구현 절차를 정의합니다.

---

## 🏗️ 아키텍처 개요

1. **데이터 파이프라인 (Offline):** 사내 문서 텍스트 추출 및 Gemini API를 활용한 데이터 정형화/증강
2. **지식 베이스 (Vector DB):** 데이터 임베딩 및 Vector DB 적재
3. **서비스 계층 (Online):** 사용자 질의 -> RAG 검색 -> Qwen-Coder 30B 기반 답변 생성

---

## 단계 1. 데이터 추출 및 정형화 (Data Pipeline)

이 단계의 핵심은 형태가 제각각인 비정형 문서를 AI가 검색하기 좋은 **규격화된 JSON 형태**로 변환하는 것입니다.

### 1.1 문서 텍스트 추출 (Python 스크립트 작성)
800개의 한글(HWP) 및 워드(Docx) 파일에서 순수 텍스트를 추출하는 배치(Batch) 스크립트를 작성합니다.
* **Word (.docx):** `python-docx` 라이브러리 활용
* **한글 (.hwp):** `pyhwp`, `olefile` 라이브러리를 활용하거나, OLE 객체 구조를 파싱하여 텍스트 추출 (경우에 따라 한글 자동화 API를 통한 일괄 TXT 변환 사용)
* **산출물:** 문서별 텍스트가 담긴 순수 텍스트 파일(`.txt`) 800개

### 1.2 사내 데이터 정형화 (using Gemini API)
추출된 800개의 텍스트 파일을 순차적으로 Gemini API에 전송하여 JSON 형태로 변환합니다. Gemini의 **`Structured Outputs (JSON 스키마)`** 기능을 사용하여 응답 형식을 강제하는 것이 매우 중요합니다.

* **프롬프트 전략:**
  > "다음은 사내 DB 튜닝 보고서 원문입니다. 이 텍스트를 분석하여 튜닝 전후 SQL, 실행 플랜, 핵심 원인 등을 파악하고 지정된 JSON 스키마에 맞게 반환하세요. 파악할 수 없는 항목은 null로 처리하세요."
* **JSON 스키마 정의 (예시):**
  ```json
  {
    "source": "internal_doc",
    "dbms_type": "Oracle",
    "issue_summary": "주문 테이블 Full Table Scan 성능 저하 건",
    "before_sql": "SELECT ...",
    "before_plan": "...",
    "problem_cause_analysis": "인덱스 컬럼에 SUBSTR 함수를 사용하여 인덱스를 타지 못함",
    "after_sql": "SELECT ...",
    "after_plan": "...",
    "solution": "함수 사용을 제거하고 BETWEEN 조건으로 변경하여 인덱스 Range Scan 유도"
  }
  ```

### 1.3 범용 튜닝 데이터 추가 생성 (Synthetic Data Augmentation)
사내 문서가 커버하지 못하는 범용적이고 최신 트렌드의 튜닝 케이스를 Gemini API에게 생성하도록 별도로 요청하여 데이터를 증강합니다.
* **생성 대상:** 널리 알려진 안티 패턴(Anti-pattern), 최신 DBMS 버전의 신기능(예: Window Function, JSON 인덱스) 최적화 사례 등
* **산출물:** 1.2와 동일한 JSON 스키마를 가지며, `"source": "synthetic_gemini"` 로 태깅된 가상 데이터 셋 (수백 건)

---

## 단계 2. 지식 베이스 구축 (Vector DB)

정형화된 JSON 데이터 셋(사내 800건 + Gemini 생성 데이터)을 기반으로 검색을 위한 벡터 데이터베이스를 구축합니다.

### 2.1 텍스트 임베딩 (Embedding)
JSON 데이터 중 검색에 유의미한 핵심 텍스트 항목(주로 `issue_summary`, `problem_cause_analysis`, `solution`, `before_sql`)을 조합하여 임베딩 모델을 통해 벡터(Vector)로 변환합니다.
* **추천 임베딩 모델:** `text-embedding-3-small/large` (OpenAI), 또는 다국어/코드 이해도가 높은 오픈소스 모델(예: `bge-m3`)

### 2.2 Vector DB 적재 및 메타데이터 처리
벡터화된 데이터 배열과 원본 JSON 데이터를 Vector DB에 저장합니다.
* **추천 Vector DB:** ChromaDB (경량, 초기 구축 용이), Milvus (대용량 확장성), Qdrant
* **핵심 포인트:** 검색 후 필터링이나 프롬프트 분기를 위해 `source`, `dbms_type` 등의 항목은 반드시 **메타데이터(Metadata)**로 분리하여 저장해야 합니다.

---

## 단계 3. AI 서비스 계층 구현 (Online RAG & LLM)

사용자 환경과 맞닿아 실시간으로 튜닝 가이드를 제공하는 서비스 단을 구현합니다.

### 3.1 RAG 검색 로직 구현 (Retrieval)
사용자가 "이 SQL 쿼리가 느린데 원인 분석과 튜닝을 해줘"라고 입력하면 다음 절차를 수행합니다.
1. 사용자의 질문(+SQL 전문)을 임베딩 모델을 통해 벡터화합니다.
2. Vector DB에서 코사인 유사도(Cosine Similarity) 기반 검색을 통해 **가장 유사한 과거 튜닝 케이스 3~5건**을 가져옵니다. (Top-K 검색)

### 3.2 Qwen-Coder 30B 프롬프트 엔지니어링 (Generation)
검색된 RAG 문서들을 컨텍스트(Context)로 삼아 Qwen 모델에게 던질 최종 프롬프트를 동적으로 조립합니다.

* **시스템 프롬프트 (System Prompt):**
  > "당신은 15년 차 수석 데이터베이스 아키텍트입니다. 다음으로 제공되는 <사내 참고 사례>를 바탕으로, 사용자가 입력한 SQL의 성능 문제 원인을 분석하고 최적화된 쿼리와 실행 계획 개선 방향을 상세히 설명하세요."
* **사용자 프롬프트 조합 예시:**
  ```text
  [사용자 요청 사항]
  {user_input}
  
  [참고 사례 1 - 출처: {doc1.source}]
  이슈 요약: {doc1.issue_summary}
  문제 원인: {doc1.problem_cause_analysis}
  적용된 해결책: {doc1.solution}
  튜닝 후 SQL: {doc1.after_sql}
  
  [참고 사례 2 ...]
  ```

### 3.3 로컬/사내 인프라 서빙 체계 구축
* **LLM 서빙:** Qwen-Coder 30B 모델을 vLLM, Ollama 또는 Text Generation Inference(TGI) 엔진을 활용하여 사내 GPU 서버에 띄웁니다.
* **API 연동:** RAG 검색부(Vector DB 연동)와 LLM 호출부를 결합한 API 서버(FastAPI 등)를 구축하여, 클라이언트 화면(웹 UI 또는 사내 메신저 챗봇)과 통신하도록 연결합니다.

---

## 단계 4. 고도화 및 운영 (Iteration)

* **피드백 수집 및 품질 개선:** 초기 오픈 후 사용자의 "좋아요/싫어요" 등 피드백을 수집합니다. 엉뚱하게 튜닝된 답변의 원인이 된 RAG 문서는 Vector DB에서 제거하거나 JSON 내용을 보완합니다.
* **지식 자동 업데이트 파이프라인:** 향후 DBA 부서에서 새로운 튜닝 완료 보고서를 특정 폴더에 넣기만 하면, 야간 배치(Batch) 작업이 돌며 자동으로 Gemini를 거쳐 Vector DB에 추가되는 자동화 파이프라인을 최종 목표로 구축합니다.
