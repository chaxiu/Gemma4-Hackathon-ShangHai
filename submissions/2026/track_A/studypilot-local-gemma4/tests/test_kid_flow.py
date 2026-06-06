from __future__ import annotations

import sys
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from fastapi.testclient import TestClient

from backend.kid_flow import KidFlowAgent
from backend.main import app
from backend.run_context import RunContext


def test_kid_plan_start_returns_questions_without_plan(monkeypatch) -> None:
    def fake_start(self: object, child_input: str) -> dict[str, Any]:
        return {
            "session_id": "kid_session_test",
            "date_label": "2026年6月4日",
            "weekday_label": "星期四",
            "companion_message": "我先问一句，再帮你安排今天。",
            "questions": [{"id": "q1", "question": "哪一项明天一定要交？", "why": "先确认优先级", "answer_type": "short_text"}],
            "can_continue_without_answers": False,
        }

    monkeypatch.setattr("backend.main.KidFlowAgent.start_plan_session", fake_start)

    response = TestClient(app).post("/kid/plan/start", json={"child_input": "我今天有数学和英语"})

    assert response.status_code == 200
    body = response.json()
    assert body["session_id"] == "kid_session_test"
    assert body["questions"][0]["question"] == "哪一项明天一定要交？"
    assert "plan" not in body


def test_kid_plan_finish_returns_quest_plan(monkeypatch) -> None:
    def fake_finish(self: object, session_id: str, followup_answers: str) -> dict[str, Any]:
        return {
            "session_id": session_id,
            "plan_title": "今天的三步小任务",
            "total_minutes": 30,
            "tasks": [
                {
                    "title": "英语听力短任务",
                    "subject": "english",
                    "minutes": 10,
                    "completion_standard": "听 8 分钟并说出 2 个关键词",
                    "quest_label": "第 1 关",
                    "reward_stars": 2,
                }
            ],
            "deferred_tasks": [],
            "child_message": "今天到这里就很好。",
        }

    monkeypatch.setattr("backend.main.KidFlowAgent.finish_plan_session", fake_finish)

    response = TestClient(app).post("/kid/plan/finish", json={"session_id": "kid_session_test", "followup_answers": "英语明天交"})

    assert response.status_code == 200
    body = response.json()
    assert body["tasks"][0]["quest_label"] == "第 1 关"
    assert body["tasks"][0]["reward_stars"] == 2


def test_kid_reflection_settle_returns_encouraging_settlement(monkeypatch) -> None:
    def fake_settle(self: object, reflection_input: str) -> dict[str, Any]:
        return {
            "settlement_title": "今日结算",
            "effort_stars": 3,
            "completion_rate": 0.5,
            "completed_summary": "数学完成，语文完成一半。",
            "stuck_points": ["数学有一道题卡住"],
            "pending_summary": "英语听力已收好，明天轻一点继续。",
            "encouragement": "你今天已经很努力了。",
            "closed_loop_status": "partial",
        }

    monkeypatch.setattr("backend.main.KidFlowAgent.settle_reflection", fake_settle)

    response = TestClient(app).post("/kid/reflection/settle", json={"reflection_input": "数学做完了，英语没做"})

    assert response.status_code == 200
    body = response.json()
    assert body["settlement_title"] == "今日结算"
    assert body["encouragement"] == "你今天已经很努力了。"


class _FakeAfterSchool:
    rag = None

    def __init__(self) -> None:
        self.rag = self
        self.started = False

    def _understand_input(self, text: str, trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {"available_minutes": 30, "energy_level": "normal", "mentioned_tasks": [{"raw": "英语", "subject": "english"}]}

    def _queries_from_understanding(self, text: str, understanding: dict[str, Any]) -> list[str]:
        return [text, "英语"]

    def search_many(self, queries: list[str], *, top_k: int = 5) -> list[dict[str, Any]]:
        return []

    def _ask_followup(self, text: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]], trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {"questions": [{"question": "英语听力明天要交吗？", "why": "先确认优先级", "answer_type": "short_text"}]}

    def run(self, child_input: str, followup_answers: str | None = None, save: bool = True) -> dict[str, Any]:
        return {
            "plan": {
                "plan_title": "今天的任务",
                "total_minutes": 10,
                "tasks": [{"title": "英语听力短任务", "subject": "english", "minutes": 10, "completion_standard": "听 8 分钟"}],
                "deferred_tasks": [],
                "child_message": "今天到这里就很好。",
            },
            "saved_plan_path": "data/runtime/plans/test.json",
        }


class _VagueAfterSchool(_FakeAfterSchool):
    def _understand_input(self, text: str, trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {"available_minutes": None, "energy_level": "unknown", "mentioned_tasks": []}

    def _ask_followup(self, text: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]], trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {"questions": [{"question": "模型随便问的问题", "why": "模型不确定", "answer_type": "short_text"}]}


class _ManyTasksNoTimeAfterSchool(_FakeAfterSchool):
    def _understand_input(self, text: str, trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "available_minutes": None,
            "energy_level": "normal",
            "mentioned_tasks": [
                {"raw": "数学", "subject": "math"},
                {"raw": "英语", "subject": "english"},
                {"raw": "语文", "subject": "chinese"},
                {"raw": "科学", "subject": "other"},
            ],
        }

    def _ask_followup(self, text: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]], trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {"questions": [{"question": f"问题 {index}", "why": "模型生成", "answer_type": "short_text"} for index in range(1, 6)]}


class _RealHomeworkAfterSchool(_FakeAfterSchool):
    def _understand_input(self, text: str, trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {
            "available_minutes": None,
            "energy_level": "tired",
            "mentioned_tasks": [
                {"raw": "语文大小册", "subject": "chinese"},
                {"raw": "习字册", "subject": "chinese"},
                {"raw": "数学大小册", "subject": "math"},
                {"raw": "数学口头作业", "subject": "math"},
                {"raw": "英语背诵", "subject": "english"},
                {"raw": "英语抄写", "subject": "english"},
                {"raw": "阅读", "subject": "other"},
                {"raw": "打卡题", "subject": "other"},
            ],
        }

    def _ask_followup(self, text: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]], trace: list[dict[str, Any]]) -> dict[str, Any]:
        return {"questions": [{"question": "你大概还有多少分钟可以用？", "why": "知道时间后，我才能把任务变轻一点。", "answer_type": "short_text"}]}


def test_kid_flow_start_then_finish_uses_two_step_contract(tmp_path, monkeypatch) -> None:
    from backend.config import AppConfig

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
    agent = KidFlowAgent(config=cfg, after_school=_FakeAfterSchool())

    started = agent.start_plan_session("今天只有30分钟，还有英语听力。")
    finished = agent.finish_plan_session(started["session_id"], "英语明天要交")

    assert started["can_continue_without_answers"] is False
    assert "tasks" not in started
    assert finished["tasks"][0]["quest_label"] == "第 1 关"
    assert finished["tasks"][0]["completion_standard"] == "听 8 分钟"


def test_kid_flow_debug_session_id_can_be_saved_and_loaded(tmp_path) -> None:
    from backend.config import AppConfig

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
    agent = KidFlowAgent(config=cfg, after_school=_FakeAfterSchool())
    debug_session_id = "debug:2026-06-05:case-1"

    started = agent.start_plan_session(
        "english listening, 30 minutes",
        run_context=RunContext(run_mode="debug", session_id=debug_session_id, business_date="2026-06-05"),
    )
    finished = agent.finish_plan_session(started["session_id"], "english is due tomorrow")

    assert started["session_id"] == debug_session_id
    assert finished["session_id"] == debug_session_id
    assert finished["tasks"][0]["title"]


def test_kid_flow_clarification_gate_asks_targeted_questions_for_vague_input(tmp_path) -> None:
    from backend.config import AppConfig

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
    agent = KidFlowAgent(config=cfg, after_school=_VagueAfterSchool())

    started = agent.start_plan_session("好多事情，不知道怎么办")

    assert 1 <= len(started["questions"]) <= 3
    assert started["questions"][0]["id"] == "q1"
    assert any("哪" in item["question"] or "时间" in item["question"] or "累" in item["question"] for item in started["questions"])


def test_kid_flow_clarification_gate_limits_many_task_questions(tmp_path) -> None:
    from backend.config import AppConfig

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
    agent = KidFlowAgent(config=cfg, after_school=_ManyTasksNoTimeAfterSchool())

    started = agent.start_plan_session("数学英语语文科学都要做")

    assert len(started["questions"]) <= 3
    assert any("多少分钟" in item["question"] for item in started["questions"])


def test_kid_flow_real_homework_gate_asks_remaining_load_and_hardest_block(tmp_path) -> None:
    from backend.config import AppConfig

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
    agent = KidFlowAgent(config=cfg, after_school=_RealHomeworkAfterSchool())

    started = agent.start_plan_session("我今天4点半到家，有语文大小册和习字册，数学还有一点大小册和口头作业，英语有背诵和抄写。还有阅读和打卡题。我今天有点累，不知道先做什么。")
    questions = " ".join(item["question"] for item in started["questions"])

    assert 2 <= len(started["questions"]) <= 3
    assert "数学" in questions and ("剩" in questions or "多少" in questions)
    assert "语文" in questions and ("费时间" in questions or "最难" in questions)
    assert "分钟" in questions or "晚饭前" in questions


def test_kid_flow_hides_backend_terms_from_child_questions(tmp_path) -> None:
    from backend.config import AppConfig

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
    agent = KidFlowAgent(config=cfg, after_school=_FakeAfterSchool())

    questions = agent._questions_for_child(
        {
            "questions": [
                {
                    "question": "这些任务里，哪一个明天必须交？",
                    "why": "确定任务优先级，决定是否需要将部分任务移至 pending_tasks。",
                    "answer_type": "short_text",
                },
                {
                    "question": "你现在的精力状态是怎样的？",
                    "why": "根据减负规则（疲惫时缩短时间），判断是否需要进行更大幅度的减负。",
                    "answer_type": "choice",
                },
            ]
        },
        {},
    )

    visible_text = " ".join(item["why"] for item in questions)
    assert "pending_tasks" not in visible_text
    assert "减负规则" not in visible_text
    assert "优先级" not in visible_text
