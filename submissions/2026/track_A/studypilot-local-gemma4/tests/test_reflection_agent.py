from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.config import AppConfig
from backend.parent_data_store import ParentDataStore
from backend.reflection_agent import ReflectionAgent


def test_daily_log_derives_pending_tasks_from_not_completed_when_model_omits_them() -> None:
    agent = ReflectionAgent()
    understanding = {
        "completed": [],
        "partially_completed": [],
        "not_completed": [{"title": "英语听力短任务", "subject": "english", "reason": "没做"}],
        "pending_tasks_to_add": [],
        "energy_level": "tired",
    }
    feedback = {
        "encouragement": "没做完也不用今晚补。",
        "tomorrow_light_suggestion": "明天先听 8-10 分钟英语。",
        "closed_loop_status": "partial",
    }

    daily_log = agent._build_daily_log("英语听力没做", {}, understanding, feedback)

    assert daily_log["pending_tasks_added"] == [
        {
            "title": "英语听力短任务",
            "subject": "english",
            "reason": "没做",
            "suggested_next_step": "明天先听 8-10 分钟英语。",
        }
    ]


def test_daily_log_completion_rate_uses_task_outcome_weights_not_model_estimate() -> None:
    agent = ReflectionAgent()
    understanding = {
        "completed": [
            {"title": "数学大小册及口头作业", "subject": "math", "evidence": "做完了"},
            {"title": "英语背诵与抄写", "subject": "english", "evidence": "完成了"},
            {"title": "语文大小册", "subject": "chinese", "evidence": "做完了"},
        ],
        "partially_completed": [{"title": "语文习字册", "subject": "chinese", "remaining": "还剩一点"}],
        "not_completed": [
            {"title": "阅读", "subject": "other", "reason": "没做"},
            {"title": "打卡题", "subject": "other", "reason": "没做"},
        ],
        "completion_rate_estimate": 0.75,
        "energy_level": "tired",
    }
    feedback = {"tomorrow_light_suggestion": "明天只接一个小动作。"}

    daily_log = agent._build_daily_log("真实复盘", {}, understanding, feedback)

    assert daily_log["completion_rate"] == 0.58


def test_profile_update_uses_daily_log_pending_tasks_when_model_patch_omits_pending() -> None:
    agent = ReflectionAgent()
    profile = {
        "profile_version": "2.0",
        "student": {"nickname": "小航", "grade": "六年级", "stage": "小升初过渡期"},
        "subjects": {},
        "weekly_schedule": [],
        "burden_rules": {},
        "pending_tasks": [],
        "learning_history": [],
        "energy_trend": [],
        "procrastination_signals": [],
    }
    understanding = {"pending_tasks_to_add": []}
    daily_log = {
        "date": "2026-06-04",
        "summary": "英语听力未完成",
        "completion_rate": 0.5,
        "energy_level": "tired",
        "new_weaknesses": [],
        "pending_tasks_added": [
            {
                "title": "英语听力短任务",
                "subject": "english",
                "reason": "没做",
                "suggested_next_step": "明天先听 8-10 分钟英语。",
            }
        ],
    }
    update = {"profile_patch": {"pending_tasks_add": []}}

    updated = agent._apply_profile_update(profile, understanding, daily_log, update)

    assert updated["pending_tasks"][0]["title"] == "英语听力短任务"
    assert updated["pending_tasks"][0]["subject"] == "english"


class _FakeReflectionLLM:
    def chat_json(self, messages, *, temperature=None, max_tokens=None):
        prompt = "\n".join(item.get("content", "") for item in messages)
        result = type("Result", (), {"is_mock": True})()
        if "REFLECTION_UNDERSTANDING" in prompt:
            return (
                {
                    "completed": [{"title": "数学", "subject": "math", "evidence": "做完了"}],
                    "partially_completed": [],
                    "not_completed": [{"title": "英语听力", "subject": "english", "reason": "没做"}],
                    "new_weaknesses": ["数学卡题"],
                    "energy_level": "tired",
                    "mood_signal": "累",
                    "completion_rate_estimate": 0.5,
                    "pending_tasks_to_add": [{"title": "英语听力", "subject": "english", "reason": "没做"}],
                },
                result,
            )
        if "REFLECTION_ENCOURAGEMENT" in prompt:
            return (
                {
                    "child_feedback": "数学完成很棒。",
                    "encouragement": "英语没做也收好，明天轻量接住。",
                    "tomorrow_light_suggestion": "明天听 8 分钟。",
                    "closed_loop_status": "partial",
                },
                result,
            )
        return (
            {
                "profile_patch": {
                    "pending_tasks_add": [],
                    "learning_history_add": {},
                    "energy_trend_add": {"energy_level": "tired"},
                    "procrastination_signals_add_or_update": [],
                    "rag_summary_update": "复盘已更新。",
                }
            },
            result,
        )


class _EmptyRag:
    def search_many(self, queries, *, top_k=5):
        return []


def test_reflection_run_persists_real_completion_to_sqlite(tmp_path) -> None:
    cfg = AppConfig(
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
    agent = ReflectionAgent(config=cfg, llm=_FakeReflectionLLM(), rag=_EmptyRag())

    result = agent.run("数学做完了，英语听力没做，今天有点累。", plan={"plan_id": "plan-sql", "date": "2026-06-04"}, save=True)
    day = ParentDataStore(cfg).get_day(result["daily_log"]["date"])

    assert day["daily_log"]["summary"]
    assert {item["title"]: item["status"] for item in day["task_outcomes"]} == {"数学": "completed", "英语听力": "missed"}
    assert day["pending_tasks"][0]["title"] == "英语听力"
