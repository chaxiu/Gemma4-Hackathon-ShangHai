from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient

from backend.config import AppConfig
from backend.main import app
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


def test_parent_api_returns_overview_day_history_and_pending(monkeypatch, tmp_path: Path) -> None:
    cfg = _config(tmp_path)
    store = ParentDataStore(cfg)
    store.upsert_plan(
        {
            "date": "2026-06-04",
            "plan_id": "plan-api",
            "plan_title": "今日计划",
            "total_minutes": 30,
            "tasks": [{"title": "数学", "subject": "math", "minutes": 20}],
        }
    )
    store.upsert_daily_log(
        {
            "date": "2026-06-04",
            "plan_id": "plan-api",
            "log_id": "log-api",
            "completed": [{"title": "数学", "subject": "math", "evidence": "完成"}],
            "partially_completed": [],
            "not_completed": [{"title": "英语", "subject": "english", "reason": "没做"}],
            "completion_rate": 0.5,
            "energy_level": "tired",
            "summary": "完成数学，英语没做",
            "source_input": "数学完成，英语没做",
            "feedback": {"encouragement": "已经很努力"},
            "pending_tasks_added": [{"title": "英语", "subject": "english", "reason": "没做"}],
        }
    )
    monkeypatch.setattr("backend.main.ParentDataStore", lambda: store)

    client = TestClient(app)

    overview = client.get("/parent/overview", params={"today": "2026-06-04"}).json()
    day = client.get("/parent/days/2026-06-04").json()
    history = client.get("/parent/history", params={"from_date": "2026-06-01", "to_date": "2026-06-30"}).json()
    pending = client.get("/parent/pending-tasks").json()

    assert overview["pending_count"] == 1
    assert day["plan"]["plan_title"] == "今日计划"
    assert day["daily_log"]["summary"] == "完成数学，英语没做"
    assert history["days"][0]["date"] == "2026-06-04"
    assert pending["tasks"][0]["title"] == "英语"


def test_parent_api_history_accepts_plan_query_aliases(monkeypatch, tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_daily_log(
        {
            "date": "2026-06-04",
            "plan_id": "plan-alias",
            "completed": [{"title": "math", "subject": "math", "evidence": "done"}],
            "partially_completed": [],
            "not_completed": [],
            "completion_rate": 1.0,
            "energy_level": "normal",
            "summary": "done",
            "source_input": "done",
            "feedback": {},
            "pending_tasks_added": [],
        }
    )
    monkeypatch.setattr("backend.main.ParentDataStore", lambda: store)
    client = TestClient(app)

    response = client.get("/parent/history", params={"from": "2026-06-01", "to": "2026-06-30"})

    assert response.status_code == 200
    assert response.json()["days"][0]["date"] == "2026-06-04"


def test_parent_api_overview_is_stable_without_data(monkeypatch, tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    monkeypatch.setattr("backend.main.ParentDataStore", lambda: store)
    client = TestClient(app)

    overview = client.get("/parent/overview", params={"today": "2026-06-04"}).json()
    day = client.get("/parent/days/2026-06-04").json()

    assert overview["today"]["date"] == "2026-06-04"
    assert overview["today"]["plan"] is None
    assert overview["today"]["daily_log"] is None
    assert overview["recent"]["average_completion_rate"] == 0.0
    assert overview["pending_count"] == 0
    assert day["task_outcomes"] == []


def test_parent_api_overview_is_stable_with_plan_before_reflection(monkeypatch, tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_plan(
        {
            "date": "2026-06-04",
            "plan_id": "plan-only",
            "plan_title": "待结算计划",
            "total_minutes": 35,
            "tasks": [{"title": "数学短练", "subject": "math", "minutes": 15}],
        }
    )
    monkeypatch.setattr("backend.main.ParentDataStore", lambda: store)
    client = TestClient(app)

    overview = client.get("/parent/overview", params={"today": "2026-06-04"}).json()

    assert overview["today"]["plan"]["plan_title"] == "待结算计划"
    assert overview["today"]["daily_log"] is None
    assert overview["recent"]["days_with_logs"] == 0
    assert overview["pending_tasks"] == []


def test_parent_api_correction_and_pending_resolve(monkeypatch, tmp_path: Path) -> None:
    cfg = _config(tmp_path)
    store = ParentDataStore(cfg)
    store.upsert_daily_log(
        {
            "date": "2026-06-04",
            "plan_id": "plan-api",
            "completed": [],
            "partially_completed": [],
            "not_completed": [{"title": "英语", "subject": "english", "reason": "没做"}],
            "completion_rate": 0.0,
            "energy_level": "normal",
            "summary": "英语没做",
            "source_input": "英语没做",
            "feedback": {},
            "pending_tasks_added": [{"title": "英语", "subject": "english", "reason": "没做"}],
        }
    )
    monkeypatch.setattr("backend.main.ParentDataStore", lambda: store)
    client = TestClient(app)
    pending_id = store.list_pending_tasks(status="open")[0]["pending_id"]

    correction = client.post(
        "/parent/days/2026-06-04/corrections",
        json={
            "target_type": "task_outcome",
            "target_id": "英语",
            "field": "status",
            "old_value": "missed",
            "new_value": "completed",
            "reason": "家长确认已完成",
        },
    ).json()
    resolved = client.post(f"/parent/pending-tasks/{pending_id}/resolve").json()

    assert correction["correction"]["new_value"] == "completed"
    assert correction["day"]["daily_log"]["completion_rate"] == 1.0
    assert resolved["task"]["status"] == "resolved"


def test_parent_api_defaults_to_official_and_filters_debug(monkeypatch, tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_daily_log(
        {
            "date": "2026-06-05",
            "plan_id": "official-plan",
            "completed": [{"title": "数学", "subject": "math", "evidence": "完成"}],
            "partially_completed": [],
            "not_completed": [],
            "completion_rate": 1.0,
            "energy_level": "normal",
            "summary": "official summary",
            "source_input": "official",
            "feedback": {},
            "pending_tasks_added": [],
        }
    )
    store.upsert_daily_log(
        {
            "date": "2026-06-05",
            "plan_id": "debug-plan",
            "completed": [],
            "partially_completed": [],
            "not_completed": [{"title": "英语", "subject": "english", "reason": "debug"}],
            "completion_rate": 0.0,
            "energy_level": "tired",
            "summary": "debug summary",
            "source_input": "debug",
            "feedback": {},
            "pending_tasks_added": [{"title": "英语", "subject": "english", "reason": "debug"}],
        },
        run_mode="debug",
        session_id="debug-api",
    )
    monkeypatch.setattr("backend.main.ParentDataStore", lambda: store)
    client = TestClient(app)

    official_day = client.get("/parent/days/2026-06-05").json()
    official_pending = client.get("/parent/pending-tasks").json()

    assert official_day["daily_log"]["summary"] == "official summary"
    assert official_day["run_mode"] == "official"
    assert official_pending["tasks"] == []


def test_debug_api_sessions_and_accept_as_official(monkeypatch, tmp_path: Path) -> None:
    store = ParentDataStore(_config(tmp_path))
    store.upsert_plan(
        {
            "date": "2026-06-05",
            "plan_id": "debug-plan",
            "plan_title": "调试计划",
            "total_minutes": 20,
            "tasks": [{"title": "数学", "subject": "math", "minutes": 20}],
        },
        run_mode="debug",
        session_id="debug-api-promote",
    )
    store.upsert_daily_log(
        {
            "date": "2026-06-05",
            "plan_id": "debug-plan",
            "completed": [{"title": "数学", "subject": "math", "evidence": "完成"}],
            "partially_completed": [],
            "not_completed": [],
            "completion_rate": 1.0,
            "energy_level": "normal",
            "summary": "debug promoted",
            "source_input": "debug",
            "feedback": {},
            "pending_tasks_added": [],
        },
        run_mode="debug",
        session_id="debug-api-promote",
    )
    monkeypatch.setattr("backend.main.ParentDataStore", lambda: store)
    client = TestClient(app)

    sessions = client.get("/debug/sessions").json()
    detail = client.get("/debug/sessions/debug-api-promote").json()
    accepted = client.post("/debug/sessions/debug-api-promote/accept-as-official").json()
    official_day = client.get("/parent/days/2026-06-05").json()

    assert sessions["sessions"][0]["session_id"] == "debug-api-promote"
    assert detail["day"]["run_mode"] == "debug"
    assert accepted["accepted"] is True
    assert official_day["daily_log"]["summary"] == "debug promoted"
