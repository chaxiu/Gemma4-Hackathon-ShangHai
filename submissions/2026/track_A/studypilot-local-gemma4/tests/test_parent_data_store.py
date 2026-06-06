from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.config import AppConfig
from backend.parent_data_store import ParentDataStore


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


def test_store_upserts_plan_log_outcomes_and_pending_tasks(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    plan = {
        "plan_id": "plan-1",
        "date": "2026-06-04",
        "plan_title": "今日轻量学习计划",
        "total_minutes": 40,
        "tasks": [
            {"title": "数学作业攻坚", "subject": "math", "minutes": 20},
            {"title": "英语听力短任务", "subject": "english", "minutes": 10},
        ],
    }
    daily_log = {
        "log_id": "log-1",
        "date": "2026-06-04",
        "created_at": "2026-06-04T20:00:00+08:00",
        "source_input": "数学做完了，英语没做。",
        "plan_id": "plan-1",
        "completed": [{"title": "数学作业攻坚", "subject": "math", "evidence": "做完了"}],
        "partially_completed": [],
        "not_completed": [{"title": "英语听力短任务", "subject": "english", "reason": "没做"}],
        "new_weaknesses": ["数学题目卡点"],
        "energy_level": "tired",
        "mood_signal": "累",
        "completion_rate": 0.5,
        "pending_tasks_added": [
            {
                "title": "英语听力短任务",
                "subject": "english",
                "reason": "没做",
                "suggested_next_step": "明天听 8 分钟",
            }
        ],
        "feedback": {"encouragement": "今天已经很努力了。", "closed_loop_status": "partial"},
        "summary": "完成：数学作业攻坚；未完成：英语听力短任务",
        "closed_loop_status": "partial",
    }

    store.upsert_plan(plan)
    store.upsert_daily_log(daily_log)
    store.upsert_daily_log(daily_log)

    day = store.get_day("2026-06-04")
    assert day["plan"]["plan_title"] == "今日轻量学习计划"
    assert day["daily_log"]["completion_rate"] == 0.5
    assert [item["status"] for item in day["task_outcomes"]] == ["completed", "missed"]
    assert len(day["pending_tasks"]) == 1
    assert day["pending_tasks"][0]["title"] == "英语听力短任务"


def test_parent_correction_recalculates_day_and_keeps_audit(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_daily_log(
        {
            "log_id": "log-2",
            "date": "2026-06-05",
            "plan_id": "plan-2",
            "completed": [],
            "partially_completed": [],
            "not_completed": [{"title": "英语听力", "subject": "english", "reason": "模型判断没做"}],
            "completion_rate": 0.0,
            "energy_level": "normal",
            "summary": "未完成：英语听力",
            "source_input": "英语其实做完了。",
            "feedback": {},
            "pending_tasks_added": [{"title": "英语听力", "subject": "english", "reason": "模型判断没做"}],
        }
    )

    correction = store.add_parent_correction(
        "2026-06-05",
        {
            "target_type": "task_outcome",
            "target_id": "英语听力",
            "field": "status",
            "old_value": "missed",
            "new_value": "completed",
            "reason": "家长确认孩子已完成",
        },
    )

    day = store.get_day("2026-06-05")
    assert correction["new_value"] == "completed"
    assert day["daily_log"]["completion_rate"] == 1.0
    assert day["task_outcomes"][0]["status"] == "completed"
    assert day["corrections"][0]["reason"] == "家长确认孩子已完成"


def test_parent_correction_updates_profile_snapshot_index_and_rag(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_daily_log(
        {
            "log_id": "log-2b",
            "date": "2026-06-05",
            "plan_id": "plan-2b",
            "completed": [],
            "partially_completed": [],
            "not_completed": [{"title": "数学应用题", "subject": "math", "reason": "模型判断没做"}],
            "completion_rate": 0.0,
            "energy_level": "normal",
            "summary": "未完成：数学应用题",
            "source_input": "数学其实卡了一半。",
            "feedback": {},
            "pending_tasks_added": [{"title": "数学应用题", "subject": "math", "reason": "模型判断没做"}],
        }
    )

    store.add_parent_correction(
        "2026-06-05",
        {
            "target_type": "task_outcome",
            "target_id": "数学应用题",
            "field": "status",
            "old_value": "missed",
            "new_value": "partial",
            "reason": "家长确认做到一半，剩余步骤明天接住",
        },
    )

    profile = store.config.student_profile_json_path.read_text(encoding="utf-8")
    chunks = store.config.rag_chunks_path.read_text(encoding="utf-8")
    day = store.get_day("2026-06-05")

    assert "家长纠偏" in profile
    assert "数学应用题" in chunks
    assert day["profile_snapshots"]
    assert day["profile_snapshots"][0]["reason"] == "parent_correction"


def test_pending_task_can_be_resolved(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_daily_log(
        {
            "log_id": "log-3",
            "date": "2026-06-06",
            "plan_id": "plan-3",
            "completed": [],
            "partially_completed": [],
            "not_completed": [{"title": "语文背诵", "subject": "chinese", "reason": "剩一半"}],
            "completion_rate": 0.0,
            "energy_level": "tired",
            "summary": "未完成：语文背诵",
            "source_input": "语文还剩一半。",
            "feedback": {},
            "pending_tasks_added": [{"title": "语文背诵", "subject": "chinese", "reason": "剩一半"}],
        }
    )
    pending = store.list_pending_tasks(status="open")

    resolved = store.resolve_pending_task(pending[0]["pending_id"])

    assert resolved["status"] == "resolved"
    assert store.list_pending_tasks(status="open") == []


def test_overview_and_history_use_sqlite_aggregates(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    for date, rate, energy in [
        ("2026-06-04", 0.5, "tired"),
        ("2026-06-05", 1.0, "normal"),
    ]:
        store.upsert_daily_log(
            {
                "log_id": f"log-{date}",
                "date": date,
                "plan_id": f"plan-{date}",
                "completed": [{"title": "数学", "subject": "math", "evidence": "完成"}],
                "partially_completed": [],
                "not_completed": [] if rate == 1.0 else [{"title": "英语", "subject": "english", "reason": "没做"}],
                "completion_rate": rate,
                "energy_level": energy,
                "summary": f"{date} summary",
                "source_input": "复盘",
                "feedback": {"encouragement": "继续保持"},
                "pending_tasks_added": [] if rate == 1.0 else [{"title": "英语", "subject": "english", "reason": "没做"}],
                "new_weaknesses": ["应用题"] if rate < 1.0 else [],
            }
        )

    overview = store.get_overview(today="2026-06-05", days=7)
    history = store.get_history("2026-06-01", "2026-06-30")

    assert overview["today"]["date"] == "2026-06-05"
    assert overview["recent"]["average_completion_rate"] == 0.75
    assert overview["pending_count"] == 1
    assert history["days"][0]["date"] == "2026-06-04"
    assert history["days"][1]["completion_rate"] == 1.0


def test_official_mode_keeps_one_canonical_day_and_revisions(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))

    store.upsert_plan(
        {
            "date": "2026-06-07",
            "plan_id": "first-plan-id",
            "plan_title": "第一版计划",
            "total_minutes": 30,
            "tasks": [{"title": "数学", "subject": "math", "minutes": 20}],
        }
    )
    store.upsert_plan(
        {
            "date": "2026-06-07",
            "plan_id": "second-plan-id",
            "plan_title": "第二版计划",
            "total_minutes": 35,
            "tasks": [{"title": "英语", "subject": "english", "minutes": 15}],
        }
    )

    day = store.get_day("2026-06-07")
    debug_sessions = store.list_debug_sessions()

    assert day["run_mode"] == "official"
    assert day["session_id"] == "official:2026-06-07"
    assert day["plan"]["plan_id"] == "official:2026-06-07::r2"
    assert day["plan"]["plan_title"] == "第二版计划"
    assert day["plan"]["revision"] == 2
    assert day["plan"]["is_canonical"] == 1
    assert debug_sessions == []


def test_debug_mode_does_not_pollute_official_history_or_pending(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))

    store.upsert_daily_log(
        {
            "date": "2026-06-08",
            "plan_id": "debug-plan-1",
            "completed": [],
            "partially_completed": [],
            "not_completed": [{"title": "英语", "subject": "english", "reason": "debug 未完成"}],
            "completion_rate": 0.0,
            "energy_level": "tired",
            "summary": "debug run 1",
            "source_input": "debug",
            "feedback": {},
            "pending_tasks_added": [{"title": "英语", "subject": "english", "reason": "debug 未完成"}],
        },
        run_mode="debug",
        session_id="debug-a",
    )
    store.upsert_daily_log(
        {
            "date": "2026-06-08",
            "plan_id": "debug-plan-2",
            "completed": [{"title": "数学", "subject": "math", "evidence": "debug 完成"}],
            "partially_completed": [],
            "not_completed": [],
            "completion_rate": 1.0,
            "energy_level": "normal",
            "summary": "debug run 2",
            "source_input": "debug",
            "feedback": {},
            "pending_tasks_added": [],
        },
        run_mode="debug",
        session_id="debug-b",
    )

    overview = store.get_overview(today="2026-06-08", days=1)
    debug_day = store.get_day("2026-06-08", run_mode="debug", session_id="debug-a")
    sessions = store.list_debug_sessions()

    assert overview["today"]["daily_log"] is None
    assert overview["recent"]["days_with_logs"] == 0
    assert overview["pending_count"] == 0
    assert debug_day["daily_log"]["summary"] == "debug run 1"
    assert len(debug_day["pending_tasks"]) == 1
    assert {item["session_id"] for item in sessions} == {"debug-a", "debug-b"}


def test_accept_debug_session_as_official_promotes_canonical_record(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_plan(
        {
            "date": "2026-06-09",
            "plan_id": "debug-plan",
            "plan_title": "调试计划",
            "total_minutes": 25,
            "tasks": [{"title": "数学", "subject": "math", "minutes": 15}],
        },
        run_mode="debug",
        session_id="debug-promote",
    )
    store.upsert_daily_log(
        {
            "date": "2026-06-09",
            "plan_id": "debug-plan",
            "completed": [{"title": "数学", "subject": "math", "evidence": "完成"}],
            "partially_completed": [],
            "not_completed": [],
            "completion_rate": 1.0,
            "energy_level": "normal",
            "summary": "debug 可采纳",
            "source_input": "debug",
            "feedback": {"encouragement": "很好"},
            "pending_tasks_added": [],
        },
        run_mode="debug",
        session_id="debug-promote",
    )

    result = store.accept_debug_session_as_official("debug-promote")
    day = store.get_day("2026-06-09")
    history = store.get_history("2026-06-09", "2026-06-09")

    assert result["accepted"] is True
    assert day["run_mode"] == "official"
    assert day["plan"]["plan_title"] == "调试计划"
    assert day["daily_log"]["summary"] == "debug 可采纳"
    assert history["days"][0]["completion_rate"] == 1.0


def test_official_revisions_keep_previous_raw_log_for_audit(tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    base_log = {
        "date": "2026-06-10",
        "plan_id": "plan-audit",
        "completed": [{"title": "数学", "subject": "math", "evidence": "完成"}],
        "partially_completed": [],
        "not_completed": [],
        "completion_rate": 1.0,
        "energy_level": "normal",
        "source_input": "first",
        "feedback": {},
        "pending_tasks_added": [],
    }

    store.upsert_daily_log({**base_log, "summary": "first official log"})
    store.upsert_daily_log({**base_log, "summary": "second official log"})

    day = store.get_day("2026-06-10")
    with store._connect() as conn:
        rows = conn.execute(
            "SELECT summary, is_canonical, superseded_at FROM daily_logs WHERE business_date = ? AND run_mode = ? ORDER BY revision ASC",
            ("2026-06-10", "official"),
        ).fetchall()

    assert day["daily_log"]["summary"] == "second official log"
    assert [row["summary"] for row in rows] == ["first official log", "second official log"]
    assert [row["is_canonical"] for row in rows] == [0, 1]
    assert rows[0]["superseded_at"]
