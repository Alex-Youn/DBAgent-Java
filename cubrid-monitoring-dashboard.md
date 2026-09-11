# CUBRID 모니터링 대시보드 설계 노트

> Claude CLI(Claude Code)에서 CUBRID 모니터링 대시보드를 구현할 때 참고용으로 정리한 문서입니다.
> 대상 버전: CUBRID 9.x ~ 11.x 공통 (버전별로 유틸리티 옵션 문자가 조금씩 다를 수 있으므로,
> 실제 구현 전 대상 서버에서 `cubrid tranlist --help`, `cubrid lockdb --help`, `cubrid spacedb --help`,
> `cubrid broker status --help` 로 옵션을 재확인할 것을 권장합니다.

## 0. 먼저 알아야 할 전제 (중요)

CUBRID는 Oracle의 `V$SESSION`, `V$LOCK`이나 MySQL의 `performance_schema`, `information_schema.INNODB_TRX` 같은
**SQL 시스템 카탈로그 뷰를 세션·Lock·디스크 용량용으로 제공하지 않습니다.**

CUBRID의 시스템 카탈로그(`db_class`, `db_attribute`, `db_index`, `db_partition`, `db_user`,
`db_authorization`, `db_trig`, `db_serial`, `db_stored_procedure`, `db_charset`, `db_collation` 등,
`csql`에서 `SELECT * FROM db_class` 형태로 조회 가능)는 **스키마(테이블/인덱스/사용자/권한) 정보만** 제공하며,
런타임 세션·트랜잭션·Lock·볼륨 사용량 정보는 포함하지 않습니다.

따라서 이번 대시보드의 세션/Lock/용량 데이터는 **SQL SELECT가 아니라 CUBRID 관리 유틸리티(CLI)의 텍스트 출력을
주기적으로 실행·파싱하는 방식**으로 수집해야 합니다. 아래 각 절에서 "조회 쿼리"라고 표현한 것은 이 CLI 명령과
그 출력 스키마를 의미합니다. 백엔드 구현 시:

- 유틸리티들은 DB 서버가 설치된 호스트에서 실행해야 하므로, 대시보드 서버 → (SSH exec 또는 동일 호스트의 로컬 실행) → `cubrid` CLI 순서로 호출하는 수집기(collector) 프로세스가 필요합니다.
- `tranlist`/`lockdb`/`killtran`은 기본적으로 DBA 권한이 필요합니다.
- 텍스트 출력을 파싱해 JSON으로 변환한 뒤, 대시보드 API가 그 JSON을 서빙하는 구조를 권장합니다.
- 수집 주기는 세션/Lock은 2~5초, 테이블스페이스 용량은 1~5분 정도가 일반적입니다(용량은 변화가 느림).

---

## 1. 모니터링 대시보드 필수 항목 정의 및 조회 방법

### 1.1 항목 카탈로그

| 분류 | 항목 | 조회 방법 | 비고 |
|---|---|---|---|
| 서버 상태 | DB 서버 프로세스 기동 여부, 버전, PID | `cubrid server status` / `cubrid service status` | 예: `Server testdb (rel 11.3, pid 24465)` |
| 트랜잭션/세션 | 활성 세션(트랜잭션) 수, 세션별 상태(ACTIVE/COMMITTED/ABORTED 등) | `cubrid tranlist <dbname>` | 2절 참고 |
| 트랜잭션/세션 | 장시간 실행 쿼리(Long Query), 장시간 트랜잭션(Long Transaction) | `cubrid tranlist` 결과의 Query time/Tran time 정렬 또는 `cubrid broker status`의 `LONG-T`/`LONG-Q` | 임계값(threshold)은 `cubrid.conf`/`broker.conf`의 `long_query_time`, `long_transaction_time` 설정과 연동 |
| Lock | 대기 중인 Lock 수, Lock 대기 세션 수, 데드락 발생 여부 | `cubrid lockdb <dbname>` | 3절 참고 |
| 브로커(Broker) | 브로커별 TPS/QPS, 연결 수, CAS(Application Server) 상태, 에러 쿼리 수 | `cubrid broker status -b` | 클라이언트 접속 계층 상태 |
| 공간(Tablespace) | 볼륨별/전체 DB 용량, 여유 공간, 사용률(%) | `cubrid spacedb -s -p <dbname>` | 4절 참고 |
| 엔진 성능 | 버퍼 캐시 히트율, 디스크 I/O(페이지 fetch/flush), Lock 대기·에스컬레이션·데드락 누적 카운터 | `cubrid statdump <dbname>` | 서버 실행 중 주기적 스냅샷 비교로 초당 값 계산 |
| HA(복제, 이중화 구성 시) | 마스터/슬레이브 상태, 복제 지연(replication delay) | `cubrid changemode`, `cubrid heartbeat status` (HA 구성 시) | 단일 서버 구성이면 생략 가능 |

### 1.2 대시보드 화면 구성 제안

- 상단 요약 카드: DB 상태(정상/경고/장애), 활성 세션 수, 대기 중 Lock 수, 전체 용량 사용률(%), 브로커 TPS
- 중단 좌: 세션 리스트 테이블(2절) / 중단 우: 실시간 Lock Holder-Waiter 트리(3절)
- 하단: 테이블스페이스(볼륨)별 용량 막대그래프(4절) + 엔진 성능 추이 그래프(버퍼 히트율, TPS 시계열)
- 임계치 경고 기준 예시: 여유 공간 10% 미만 → 경고, 5% 미만 → 위험 / Lock 대기 30초 초과 → 경고 / Long Query 설정값 초과 → 경고

---

## 2. 세션 리스트 / Lock 조회 / 특정 세션·Lock 상세 조회

### 2.1 세션(트랜잭션) 목록 조회 — `cubrid tranlist`

```bash
cubrid tranlist [옵션] <db_name>
# 예
cubrid tranlist -u dba mydb
```

주요 옵션:

| 옵션 | 설명 |
|---|---|
| `-u, --user=USER` | 접속 사용자(기본 DBA) |
| `-p, --password=PASSWORD` | 비밀번호 |
| `-s, --summary` | 쿼리/Lock 상세 없이 요약만 표시 |
| `--sort-key=번호` | 지정한 컬럼 번호 기준 오름차순 정렬 |
| `--reverse` | 정렬 역순 |

출력 컬럼(대시보드 세션 리스트 테이블에 그대로 매핑 가능):

| 컬럼 | 설명 |
|---|---|
| Tran index | 트랜잭션(세션) 식별자. 클릭 시 상세 조회의 키(key)로 사용 |
| Status | ACTIVE / RECOVERY / COMMITTED / COMMITTING / ABORTED / KILLED |
| User name | 접속 DB 사용자 |
| Host name | 클라이언트(또는 CAS) 호스트 |
| Process id | 클라이언트 프로세스 ID |
| Program name | 접속 프로그램명(csql, JDBC 앱 등) |
| Query time | 현재 쿼리 실행 경과 시간(초) |
| Tran time | 트랜잭션 시작 후 경과 시간(초) |
| Wait for lock holder | 이 세션이 대기 중인 Lock을 보유한 상대 Tran index (Lock 대기 시에만 값 존재) |
| SQL_ID | 실행 중 쿼리 식별자 |
| SQL Text | 실행 중 쿼리문(앞부분 일부만 표시) |

세션 리스트 API 응답(JSON) 설계 예:

```json
{
  "collected_at": "2026-09-05T10:00:00+09:00",
  "sessions": [
    {
      "tran_index": 12,
      "status": "ACTIVE",
      "user": "APPUSER",
      "host": "10.0.1.23",
      "pid": 24581,
      "program": "cas_3",
      "query_time_sec": 4.2,
      "tran_time_sec": 12.7,
      "wait_for_tran_index": null,
      "sql_id": "a1b2c3",
      "sql_text": "UPDATE orders SET status = ..."
    }
  ]
}
```

### 2.2 세션 강제 종료(옵션 기능) — `cubrid killtran`

대시보드에서 특정 세션을 강제 종료하는 버튼을 제공한다면:

```bash
cubrid killtran -i <tran_index> <db_name>
```

주요 옵션: `-i, --kill-transaction-index=ID[,ID...]`(다중 지정 가능), `--kill-user-name=`, `--kill-host-name=`,
`--kill-program-name=`, `--kill-sql-id=`, `-f, --force`(확인 프롬프트 생략). DBA 권한 필요.
운영 환경에서는 오작동 방지를 위해 반드시 확인 다이얼로그 + 권한 체크를 거치도록 구현하는 것을 권장합니다.

### 2.3 Lock 목록 조회 — `cubrid lockdb`

```bash
cubrid lockdb <db_name>
# 파일로 저장
cubrid lockdb -o lockdb_$(date +%Y%m%d_%H%M%S).log <db_name>
```

출력은 3개 구획으로 구성됩니다.

1. **서버 Lock 설정**: 데드락 감지 주기(deadlock detection interval) 등
2. **접속 클라이언트 목록**: Tran index, Program name, User, Host, PID, Isolation level, Lock timeout
3. **Object Lock Table**: 객체(클래스/인스턴스)별 Lock 보유자(holder)·대기자(waiter) 목록

Object Lock Table 출력 형식(예시, 버전에 따라 표기 미세 차이 있음):

```
Object Lock Table:
Current number of objects which are locked = 3
Object type: Class = orders.
LOCK HOLDERS:
  Tran_index = 12, Granted_mode = X_LOCK, Count = 1
BLOCKED LOCK HOLDERS (WAITERS):
  Tran_index = 15, Granted_mode = NULL_LOCK, Blocked_mode = X_LOCK
  Start_waiting_at = 2026-09-05 10:00:03.120
  Wait_for_nsecs = -1
```

핵심 필드:

| 필드 | 설명 |
|---|---|
| Object type / Class | Lock이 걸린 대상(테이블/클래스명, 인스턴스 OID) |
| Tran_index | 해당 Lock을 보유(또는 대기)하는 세션 — `tranlist`의 Tran index와 조인(join) 가능 |
| Granted_mode | 현재 부여된 Lock 모드(S_LOCK, X_LOCK, IS_LOCK, IX_LOCK, SIX_LOCK, U_LOCK, NX_LOCK, NS_LOCK, SCH_S_LOCK, SCH_M_LOCK 등) |
| Blocked_mode | 대기 중인 세션이 요청했으나 아직 못 받은 Lock 모드 |
| Start_waiting_at | 대기 시작 시각 → 대기 경과 시간 계산에 사용 |

Lock 목록 API 응답(JSON) 설계 예:

```json
{
  "collected_at": "2026-09-05T10:00:05+09:00",
  "locks": [
    {
      "object_type": "class",
      "object_name": "orders",
      "holders": [
        { "tran_index": 12, "granted_mode": "X_LOCK", "count": 1 }
      ],
      "waiters": [
        {
          "tran_index": 15,
          "blocked_mode": "X_LOCK",
          "start_waiting_at": "2026-09-05T10:00:03.120+09:00",
          "wait_sec": 1.9
        }
      ]
    }
  ]
}
```

### 2.4 특정 세션 클릭 시 상세 조회

특정 Tran index를 클릭했을 때 보여줄 상세 정보는 **새로운 쿼리를 던지는 게 아니라, 2.1/2.3에서 이미 수집한
`tranlist`/`lockdb` 결과를 tran_index 기준으로 조인**해서 구성합니다.

상세 패널 구성 예:

- 기본 정보: User, Host, Program, PID, Isolation level, Lock timeout (tranlist + lockdb 클라이언트 목록에서)
- 실행 중 SQL 전문 (tranlist, 단 CUBRID는 기본적으로 SQL Text를 일부만 노출하므로 전체 쿼리 로깅이 필요하면
  브로커의 SQL 로그(`$CUBRID/log/broker/sql_log/*.log`)를 별도로 tail하여 sql_id로 매칭)
- 이 세션이 보유 중인 Lock 목록 (holders 중 tran_index 일치 항목)
- 이 세션이 대기 중인 Lock과 그 Lock을 보유한 상대 세션(wait_for_tran_index → 3절 트리와 동일 데이터)
- 액션 버튼: 강제 종료(`killtran`)

### 2.5 특정 Lock 클릭 시 상세 조회

Lock 항목(2.3의 `locks[i]`)을 클릭하면:

- 대상 객체명(테이블/인스턴스), Lock 유형
- 현재 holder 목록과 각 holder의 세션 상세(2.4와 동일한 세션 상세 패널 재사용)
- 현재 waiter 목록과 대기 경과 시간, 대기 순번
- "이 Lock을 강제로 풀려면 holder 세션을 종료해야 함" 안내 + holder 세션 종료 버튼 연결

---

## 3. 실시간 Lock 조회 — Holder/Waiter 트리 화면 구성

### 3.1 데이터 모델

Lock 대기는 본질적으로 **"세션이 세션을 기다리는" 방향 그래프(누가 누구를 막고 있는가)**이므로,
트리(정확히는 DAG, 데드락 시 사이클 발생 가능) 구조로 표현합니다.

- 노드(Node) = 세션(Tran index). 라벨: `Tran #12 (APPUSER@host, X_LOCK on orders)`
- 엣지(Edge) = "waiter → holder" 방향의 대기 관계. 라벨: 대기 대상 객체명 + 대기 시간
- 루트(Root) = 아무도 기다리지 않고 Lock을 보유만 한 세션(체인의 최상위 holder)
- 리프(Leaf) = 다른 세션에게 막혀 대기만 하는 세션

트리 생성 로직(수집기에서 매 polling마다 수행):

1. `lockdb` 결과에서 모든 (waiter_tran_index → holder_tran_index) 쌍을 추출한다(같은 오브젝트의 waiters × holders 조합).
2. 이 쌍들로 그래프를 구성하고, 각 waiter를 자식(child), holder를 부모(parent)로 두는 트리로 변환한다.
3. 한 세션이 여러 holder를 기다리면(멀티 홀더) 다중 부모가 되므로, UI는 트리가 아니라 그래프 렌더러(DAG)를 쓰거나,
   가장 오래 대기 중인 holder를 1차 부모로 선택해 트리로 단순화한다.
4. 사이클이 발견되면(A가 B를 기다리고 B가 A를 기다림) **데드락**으로 판정하여 별도 강조 표시한다(CUBRID 서버 자체도
   내부적으로 주기적 데드락 감지를 수행하지만, 대시보드에서 시각적으로 즉시 보여주는 것이 유용함).

트리 데이터(JSON) 예:

```json
{
  "collected_at": "2026-09-05T10:00:05+09:00",
  "lock_trees": [
    {
      "root_tran_index": 12,
      "node": {
        "tran_index": 12,
        "user": "APPUSER",
        "host": "10.0.1.23",
        "holding": [{ "object": "orders", "mode": "X_LOCK" }],
        "children": [
          {
            "tran_index": 15,
            "user": "BATCHUSER",
            "host": "10.0.1.40",
            "waiting_for": { "object": "orders", "requested_mode": "X_LOCK" },
            "wait_sec": 1.9,
            "children": [
              {
                "tran_index": 18,
                "user": "APPUSER",
                "waiting_for": { "object": "orders", "requested_mode": "S_LOCK" },
                "wait_sec": 0.4,
                "children": []
              }
            ]
          }
        ]
      },
      "is_deadlock": false
    }
  ]
}
```

### 3.2 화면 구성 가이드

- 트리 레이아웃: 최상위 holder를 좌측(또는 상단)에 고정하고, 오른쪽(또는 하단)으로 waiter가 뻗어나가는
  가로형(또는 세로형) 계층 트리. 라이브러리 예: D3.js `d3-hierarchy`(tree/cluster layout), 또는 React라면
  `react-d3-tree`, `react-flow` 등.
- 노드 색상: 정상 holder(초록), 대기 중(노랑, 대기시간에 따라 진해지도록 그라데이션), 데드락 연루 노드(빨강 + 점멸).
- 노드 클릭 시 2.4의 세션 상세 패널을 사이드 패널/모달로 오픈.
- 엣지에는 대기 시간(초)을 실시간 카운트업으로 표시.
- 트리가 없을 때(대기 Lock 0건) "현재 대기 중인 Lock 없음" 빈 상태 표시.
- 자동 갱신 주기: 2~5초 폴링, 또는 서버가 WebSocket/SSE로 push. 트리 구조가 바뀔 때만 부드러운 리레이아웃 애니메이션 적용(매 tick마다 완전히 다시 그리면 깜빡임 발생하므로 diff 기반 업데이트 권장).
- 데드락 감지 시 상단 배너로 즉시 알림 + 관련 세션 강제 종료 액션 제공.

---

## 4. 테이블스페이스(볼륨) 용량 조회

### 4.1 DB 전체 및 볼륨별 용량 — `cubrid spacedb`

```bash
cubrid spacedb -s -p <db_name>
# 단위 지정(H = 자동 스케일)
cubrid spacedb -s -p --size-unit=H <db_name>
```

주요 옵션:

| 옵션 | 설명 |
|---|---|
| `-s, --summarize` | 볼륨 목적(DATA/INDEX/GENERIC/TEMP)별로 합산 표시 |
| `-p, --purpose` | 목적별 사용량(data_size/index_size/temp_size 등) 구분 표시 |
| `--size-unit={PAGE\|M\|G\|T\|H}` | 출력 단위(H = 자동) |
| `-o FILE` | 결과를 파일로 저장 |
| `-S, --SA-mode` / `-C, --CS-mode` | 스탠드얼론/클라이언트-서버 모드 지정 |

기본 출력 컬럼:

| 컬럼 | 설명 |
|---|---|
| Volid | 볼륨 ID |
| Purpose | 볼륨 용도(GENERIC/DATA/INDEX/TEMP 등) |
| total_size | 볼륨 총 크기 |
| free_size | 여유 공간 |
| Vol Name | 실제 볼륨 파일 경로 |

용량 API 응답(JSON) 설계 예:

```json
{
  "collected_at": "2026-09-05T10:05:00+09:00",
  "db_name": "mydb",
  "total_size_mb": 51200,
  "used_size_mb": 38900,
  "free_size_mb": 12300,
  "usage_pct": 76.0,
  "volumes": [
    {
      "vol_id": 0,
      "purpose": "GENERIC",
      "total_mb": 20480,
      "free_mb": 3100,
      "path": "/db/mydb/mydb_vol0"
    },
    {
      "vol_id": 1,
      "purpose": "DATA",
      "total_mb": 20480,
      "free_mb": 6200,
      "path": "/db/mydb/mydb_data0"
    },
    {
      "vol_id": 2,
      "purpose": "INDEX",
      "total_mb": 10240,
      "free_mb": 3000,
      "path": "/db/mydb/mydb_idx0"
    }
  ]
}
```

대시보드에서는 `usage_pct` 기준 게이지/막대그래프 + 볼륨별 스택 바 차트로 표시하고,
`free_size / total_size` 가 임계치(예: 10%) 미만이면 경고 배지를 표시합니다.

### 4.2 테이블(클래스) 단위 용량에 대한 한계

CUBRID는 위 `db_class` 계열 시스템 카탈로그에 **테이블별 바이트 크기 컬럼을 제공하지 않으며**,
Oracle의 `DBA_SEGMENTS`나 MySQL의 `information_schema.TABLES.DATA_LENGTH` 같은 손쉬운 SQL 조회 방법이 없습니다.
테이블 단위 용량이 꼭 필요하다면 다음 중 하나로 근사치를 구현해야 합니다.

- **행 수 기반 추정**: `SELECT COUNT(*) FROM <table>` 로 행 수를 구하고, 평균 로우 크기(컬럼 타입/길이 합산으로 추정)를
  곱해 근사 용량을 계산. 정확한 페이지 단위 크기는 아니지만 상대 비교(어떤 테이블이 가장 큰지)에는 충분히 유용합니다.
- **볼륨 목적(purpose) 분리 운영**: 테이블/인덱스를 전용 볼륨으로 분리해 생성해 두면, `cubrid spacedb -p` 결과를
  볼륨(=사실상 테이블 그룹) 단위로 매핑해 근사 관리가 가능합니다. 신규 설계 단계라면 이 방식을 권장합니다.
- CUBRID Manager(GUI 툴)의 "테이블 정보" 화면도 내부적으로 위와 같은 근사 방식을 사용하며, 별도의 숨은 SQL API가
  있는 것은 아닙니다.

---

## 5. 수집기(Collector) 구현 메모

- 각 유틸리티는 사람이 읽기 좋은 고정폭 텍스트 출력을 내므로, 정규식 기반 파서를 작성해 위 JSON 스키마로 변환합니다.
  버전별로 컬럼 간격/문구가 조금씩 다를 수 있어, 대상 CUBRID 버전에서 실제 출력을 캡처해 파서를 맞추는 작업이
  구현 초기에 필요합니다.
- `tranlist` + `lockdb`는 짧은 주기(2~5초), `spacedb`는 긴 주기(1~5분), `broker status`/`statdump`는 중간 주기(5~10초)
  로 분리 스케줄링하는 것을 권장합니다.
- 수집기 실행 계정은 DBA 권한이 필요하므로, 대시보드 서버 자체에는 최소 권한만 주고 수집기만 별도 격리된
  서비스 계정으로 운영하는 구조를 권장합니다.
- `killtran` 같은 파괴적 명령은 감사 로그(누가/언제/어떤 세션을 종료했는지)를 반드시 남깁니다.

---

## 참고한 CUBRID 공식 문서

- [CUBRID Database Transaction / Lock (isolation level, lock mode, lockdb 개념)](https://www.cubrid.org/manual/en/9.3.0/sql/transaction.html)
- [CUBRID Controlling Processes (`cubrid broker status`, `cubrid server status`)](https://www.cubrid.org/manual/en/11.3/admin/control.html)
- [CUBRID System Catalog (시스템 카탈로그 뷰 전체 목록)](https://cubrid-manual.readthedocs.io/en/release-10.0/sql/catalog.html)
- [CUBRID cubrid Utilities 개요 (tranlist/killtran/lockdb/spacedb 목록)](https://www.cubrid.org/manual/en/11.3/admin/admin_utils.html)
- [naver/rye admin_utils.md — CUBRID 코드베이스 기반 포크(Rye)의 tranlist/killtran/lockdb/spacedb 상세 옵션·출력 컬럼](https://github.com/naver/rye/blob/master/doc/content/manual/v1.0/admin/admin_utils.md) *(CUBRID 엔진 코드를 기반으로 한 동일 계열 유틸리티로, 정확한 옵션 문자는 설치된 CUBRID 버전에서 `--help`로 최종 확인 필요)*
