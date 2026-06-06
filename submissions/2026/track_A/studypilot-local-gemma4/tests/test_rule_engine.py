from __future__ import annotations

import sys
from datetime import datetime
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.config import AppConfig
from backend import rule_engine
from backend.rule_engine import ensure_plan_compliance
from backend.validator import validate_after_school_plan


def _config(tmp_path: Path) -> AppConfig:
    return AppConfig(
        env="test",
        root_dir=tmp_path,
        data_dir=tmp_path / "data",
        lm_studio_base_url="http://localhost:1234/v1",
        model_name="mock",
        llm_temperature=0.2,
        llm_max_tokens=100,
        llm_timeout_seconds=30,
        use_mock_llm=True,
        llm_fallback_to_mock=False,
        streamlit_port=8501,
        api_port=8000,
        normal_max_minutes=60,
        tired_max_minutes=40,
        exhausted_max_minutes=25,
        max_core_tasks_weekday=3,
        no_high_intensity_after="21:30",
        parent_confirm_required=True,
        log_level="INFO",
    )


def _task(index: int, *, minutes: int = 15, priority: str = "medium", intensity: str = "low") -> dict[str, Any]:
    return {
        "title": f"任务 {index}",
        "subject": "other",
        "minutes": minutes,
        "priority": priority,
        "intensity": intensity,
        "completion_standard": "完成一个最小动作",
    }


def _issue_codes(rule_checks: list[dict[str, Any]]) -> set[str]:
    return {str(check.get("issue_code")) for check in rule_checks if check.get("issue_code")}


def test_core_task_limit_emits_issue_code_and_deferred_tasks_are_structured(tmp_path: Path) -> None:
    plan = {"available_minutes": 90, "energy_level": "normal", "tasks": [_task(i) for i in range(1, 6)]}

    repaired, rule_checks, _ = ensure_plan_compliance(plan, child_input="今天有五个任务", config=_config(tmp_path))

    assert len(repaired["tasks"]) == 3
    assert "V006_CORE_TASK_LIMIT_EXCEEDED" in _issue_codes(rule_checks)
    assert len(repaired["deferred_tasks"]) == 2
    assert all(item.get("title") and item.get("reason") and item.get("suggested_next_time") for item in repaired["deferred_tasks"])


def test_time_sum_mismatch_is_recomputed_and_reported(tmp_path: Path) -> None:
    plan = {
        "available_minutes": 45,
        "energy_level": "normal",
        "total_minutes": 999,
        "tasks": [_task(1, minutes=10), _task(2, minutes=12)],
    }

    repaired, rule_checks, _ = ensure_plan_compliance(plan, child_input="今天只有45分钟", config=_config(tmp_path))

    assert repaired["total_minutes"] == 22
    assert "V012_TIME_SUM_INCONSISTENT" in _issue_codes(rule_checks)


def test_late_high_intensity_task_is_deferred_with_issue_code(tmp_path: Path) -> None:
    plan = {
        "available_minutes": 40,
        "energy_level": "normal",
        "tasks": [
            _task(1, minutes=20, priority="high", intensity="high"),
            _task(2, minutes=10, priority="medium", intensity="low"),
        ],
    }

    repaired, rule_checks, _ = ensure_plan_compliance(
        plan,
        child_input="晚上9点40了，数学难题和英语听力还没做",
        now_dt=datetime(2026, 6, 5, 21, 40),
        config=_config(tmp_path),
    )

    assert [task["title"] for task in repaired["tasks"]] == ["任务 2"]
    assert any(item["title"] == "任务 1" for item in repaired["deferred_tasks"])
    assert "V008_DELAYED_TASKS_MISSING" in _issue_codes(rule_checks)


def test_validator_details_include_machine_readable_issues() -> None:
    plan = {"available_minutes": 30, "time_cap_minutes": 30, "tasks": [_task(1)]}
    rule_checks = [
        {
            "rule_id": "max_core_tasks_weekday",
            "title": "工作日最多 3 个核心任务",
            "passed": False,
            "severity": "warning",
            "detail": "已裁剪。",
            "issue_code": "V006_CORE_TASK_LIMIT_EXCEEDED",
            "repair_hint": "Move overflow tasks to deferred_tasks.",
            "blocking": False,
        }
    ]

    validation = validate_after_school_plan(plan, rule_checks=rule_checks).to_dict()

    assert validation["details"]["issues"][0]["issue_code"] == "V006_CORE_TASK_LIMIT_EXCEEDED"
    assert validation["details"]["issues"][0]["repair_hint"]


def test_safety_policy_replaces_child_visible_unsafe_language() -> None:
    unsafe = "你怎么又没做完，太懒了。这样下去肯定考不上。"

    assert hasattr(rule_engine, "safety_policy_checks")
    assert hasattr(rule_engine, "sanitize_child_facing_text")

    checks = rule_engine.safety_policy_checks(unsafe)
    safe = rule_engine.sanitize_child_facing_text(unsafe)

    assert checks[0]["issue_code"] == "V015_UNSAFE_LANGUAGE"
    assert safe != unsafe
    assert "太懒" not in safe
    assert "考不上" not in safe
