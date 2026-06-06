from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.after_school_agent import AfterSchoolAgent


class _FakeLlm:
    def chat_json(self, messages: list[dict[str, str]], **kwargs: Any) -> tuple[dict[str, Any], Any]:
        prompt = messages[0]["content"]
        if "AFTER_SCHOOL_INPUT_UNDERSTANDING" in prompt:
            return {
                "available_minutes": 30,
                "energy_level": "normal",
                "mentioned_tasks": [{"raw": "英语听力", "subject": "english"}],
            }, _Result()
        if "ACTIVE_FOLLOW_UP_QUESTION" in prompt:
            return {"need_questions": False, "questions": [], "can_plan_without_answers": True}, _Result()
        if "TASK_CLASSIFICATION" in prompt:
            return {
                "tasks": [
                    {
                        "title": "英语听力",
                        "subject": "english",
                        "priority": "high",
                        "intensity": "low",
                        "can_defer": False,
                        "completion_standard_hint": "听 8 分钟",
                    }
                ]
            }, _Result()
        if "TODAY_PLAN_GENERATION" in prompt:
            return {
                "plan_title": "测试计划",
                "date": "2099-01-01",
                "available_minutes": 30,
                "energy_level": "normal",
                "tasks": [
                    {
                        "title": "英语听力",
                        "subject": "english",
                        "minutes": 8,
                        "intensity": "low",
                        "priority": "high",
                        "completion_standard": "听 8 分钟并说出关键词",
                    }
                ],
                "deferred_tasks": [],
            }, _Result()
        raise AssertionError(prompt)


class _Result:
    is_mock = False


class _EmptyRag:
    def search_many(self, queries: list[str], *, top_k: int = 5) -> list[dict[str, Any]]:
        return []


def test_agent_overrides_model_supplied_date_with_today(monkeypatch) -> None:
    monkeypatch.setattr("backend.after_school_agent.today_str", lambda: "2026-06-04")

    result = AfterSchoolAgent(llm=_FakeLlm(), rag=_EmptyRag()).run("今天只有30分钟，英语听力怎么办？", save=False)

    assert result["plan"]["date"] == "2026-06-04"
