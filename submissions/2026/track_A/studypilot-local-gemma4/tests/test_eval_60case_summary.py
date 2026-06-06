from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts.eval_60case_summary import summarize_eval_results


def test_summarize_eval_results_counts_outcomes_and_issues(tmp_path: Path) -> None:
    path = tmp_path / "eval_results.jsonl"
    rows = [
        {"case_id": "c1", "status": "pass", "issues": []},
        {"case_id": "c2", "status": "soft_pass", "issues": [{"issue_code": "V008_DELAYED_TASKS_MISSING"}]},
        {"case_id": "c3", "status": "fail", "issues": [{"issue_code": "V008_DELAYED_TASKS_MISSING"}, {"issue_code": "V012_TIME_SUM_INCONSISTENT"}]},
    ]
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8")

    summary = summarize_eval_results(path)

    assert summary["total"] == 3
    assert summary["status_counts"] == {"pass": 1, "soft_pass": 1, "fail": 1}
    assert summary["issue_counts"]["V008_DELAYED_TASKS_MISSING"] == 2
    assert summary["issue_counts"]["V012_TIME_SUM_INCONSISTENT"] == 1


def test_summarize_eval_results_accepts_nested_validation_shape(tmp_path: Path) -> None:
    path = tmp_path / "eval_results.jsonl"
    rows = [
        {"case_id": "c1", "validation": {"pass_status": "pass", "issues": []}},
        {"case_id": "c2", "validation": {"pass_status": "soft_pass", "issues": [{"code": "V003_CLARIFICATION_MISMATCH"}]}},
    ]
    path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows), encoding="utf-8")

    summary = summarize_eval_results(path)

    assert summary["status_counts"] == {"pass": 1, "soft_pass": 1}
    assert summary["issue_counts"]["V003_CLARIFICATION_MISMATCH"] == 1
