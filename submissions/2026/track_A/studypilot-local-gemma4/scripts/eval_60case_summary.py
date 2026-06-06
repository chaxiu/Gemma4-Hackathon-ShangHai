from __future__ import annotations

import argparse
import json
from collections import Counter
from pathlib import Path
from typing import Any


def _read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        rows.append(json.loads(line))
    return rows


def _status(row: dict[str, Any]) -> str:
    validation = row.get("validation") if isinstance(row.get("validation"), dict) else {}
    raw = str(row.get("status") or row.get("outcome") or row.get("result") or validation.get("pass_status") or "").strip().lower()
    if raw in {"pass", "passed", "ok"}:
        return "pass"
    if raw in {"soft_pass", "soft-pass", "soft pass", "warning"}:
        return "soft_pass"
    if raw in {"fail", "failed", "error"}:
        return "fail"
    score = row.get("score", validation.get("score"))
    try:
        numeric_score = float(score)
    except (TypeError, ValueError):
        return "unknown"
    if numeric_score >= 90:
        return "pass"
    if numeric_score >= 75:
        return "soft_pass"
    return "fail"


def _issue_code(issue: Any) -> str | None:
    if isinstance(issue, str):
        return issue or None
    if isinstance(issue, dict):
        return str(issue.get("issue_code") or issue.get("code") or issue.get("validator") or "").strip() or None
    return None


def summarize_eval_results(path: str | Path) -> dict[str, Any]:
    source = Path(path)
    rows = _read_jsonl(source)
    status_counts = Counter(_status(row) for row in rows)
    issue_counts: Counter[str] = Counter()
    for row in rows:
        validation = row.get("validation") if isinstance(row.get("validation"), dict) else {}
        issues = row.get("issues") or row.get("failed_issues") or row.get("validators") or validation.get("issues") or []
        if isinstance(issues, dict):
            issues = list(issues.values())
        for issue in issues:
            code = _issue_code(issue)
            if code:
                issue_counts[code] += 1
    ordered_status = {key: status_counts.get(key, 0) for key in ["pass", "soft_pass", "fail", "unknown"] if status_counts.get(key, 0)}
    return {
        "source": str(source),
        "total": len(rows),
        "status_counts": ordered_status,
        "issue_counts": dict(issue_counts.most_common()),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Summarize sidecar 60-case StudyPilot eval results.")
    parser.add_argument("path", help="Path to eval_results.jsonl style output")
    args = parser.parse_args()
    print(json.dumps(summarize_eval_results(args.path), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
