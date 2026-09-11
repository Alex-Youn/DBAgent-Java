#!/usr/bin/env python3
"""data/oracle_errors.db(error_dictionary 테이블) -> JSONL 변환.

OpenSearchIndexerService.indexData()가 읽는 형식(JSONL, 줄마다 하나의 JSON 객체)에 맞춰
_filename(=error_code), error_code, cause, action 필드를 내보낸다. query_or_log는 색인
대상 인덱스(error_dictionary)에 포함하지 않는다 - 기존 error_dictionary_full.jsonl/
error_dictionary_sample.jsonl과 동일한 스키마.

Usage:
    python export_error_dictionary_to_jsonl.py [output.jsonl] [--limit N]

인자 없이 실행하면 data/oracle_errors.db 전체를 error_dictionary_full.jsonl로 내보낸다.
"""
import argparse
import json
import sqlite3
from pathlib import Path

DB_PATH = Path(__file__).resolve().parent / "data" / "oracle_errors.db"


def export(output_path: str, limit: int | None) -> int:
    conn = sqlite3.connect(DB_PATH)
    cur = conn.cursor()
    sql = "SELECT error_code, cause, action FROM error_dictionary ORDER BY error_code"
    if limit:
        sql += f" LIMIT {limit}"
    cur.execute(sql)

    count = 0
    with open(output_path, "w", encoding="utf-8") as f:
        for error_code, cause, action in cur.fetchall():
            row = {
                "_filename": error_code,
                "error_code": error_code,
                "cause": cause,
                "action": action,
            }
            f.write(json.dumps(row, ensure_ascii=False) + "\n")
            count += 1

    conn.close()
    return count


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("output", nargs="?", default="error_dictionary_full.jsonl")
    parser.add_argument("--limit", type=int, default=None, help="테스트용: 앞에서 N건만 내보냄")
    args = parser.parse_args()

    if not DB_PATH.exists():
        raise SystemExit(f"DB 파일을 찾을 수 없습니다: {DB_PATH}")

    n = export(args.output, args.limit)
    print(f"{n}건을 {args.output}에 저장했습니다.")
