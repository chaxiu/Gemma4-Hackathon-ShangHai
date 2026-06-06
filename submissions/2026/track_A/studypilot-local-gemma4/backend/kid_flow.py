from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any
from urllib.parse import quote

from backend.after_school_agent import AfterSchoolAgent
from backend.config import AppConfig, get_config
from backend.rag_store import RagStore
from backend.reflection_agent import ReflectionAgent
from backend.run_context import RunContext
from backend.rule_engine import ISSUE_CLARIFICATION_MISMATCH, infer_energy_level, parse_available_minutes, sanitize_child_facing_text
from backend.storage import load_json, now_iso, save_json, timestamp_slug


WEEKDAY_CN = ["星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"]


@dataclass
class KidSession:
    session_id: str
    child_input: str
    understanding: dict[str, Any]
    followup: dict[str, Any]
    created_at: str
    run_mode: str = "official"
    business_date: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "session_id": self.session_id,
            "child_input": self.child_input,
            "understanding": self.understanding,
            "followup": self.followup,
            "created_at": self.created_at,
            "run_mode": self.run_mode,
            "business_date": self.business_date,
        }


class KidFlowAgent:
    """Child-facing two-step planning and one-step bedtime settlement."""

    def __init__(self, config: AppConfig | None = None, after_school: AfterSchoolAgent | None = None, reflection: ReflectionAgent | None = None):
        self.config = config or get_config()
        self.after_school = after_school or AfterSchoolAgent(self.config)
        self.reflection = reflection or ReflectionAgent(self.config)

    def start_plan_session(self, child_input: str, run_context: RunContext | None = None) -> dict[str, Any]:
        if not child_input.strip():
            raise ValueError("今天情况不能为空。")
        context = run_context or RunContext()

        trace: list[dict[str, Any]] = []
        understanding = self.after_school._understand_input(child_input.strip(), trace)
        available_minutes = understanding.get("available_minutes") or parse_available_minutes(child_input)
        energy_level = understanding.get("energy_level") or infer_energy_level(child_input, default="normal")
        if energy_level == "unknown":
            energy_level = infer_energy_level(child_input, default="normal")
        understanding["available_minutes"] = available_minutes
        understanding["energy_level"] = energy_level

        queries = self.after_school._queries_from_understanding(child_input, understanding)
        rag_context = self.after_school.rag.search_many(queries, top_k=5)
        followup = self.after_school._ask_followup(child_input, understanding, rag_context, trace)
        gate = self._clarification_gate(child_input, understanding)
        if gate["triggered"]:
            followup["questions"] = gate["questions"]
            followup["clarification_gate"] = {
                "triggered": True,
                "reasons": gate["reasons"],
                "issue_code": ISSUE_CLARIFICATION_MISMATCH,
            }
            trace.append({"step": "clarification_gate", "result": followup["clarification_gate"]})
        questions = self._questions_for_child(followup, understanding)
        followup["questions"] = questions
        followup["need_questions"] = True
        followup["can_plan_without_answers"] = False

        session = KidSession(
            session_id=context.normalized_session_id() if context.run_mode == "debug" else f"kid_{timestamp_slug()}",
            child_input=child_input.strip(),
            understanding=understanding,
            followup=followup,
            created_at=now_iso(),
            run_mode=context.run_mode,
            business_date=context.normalized_date(),
        )
        self._save_session(session)

        return {
            "session_id": session.session_id,
            **self._date_labels(context.normalized_date()),
            "run_mode": context.run_mode,
            "business_date": context.normalized_date(),
            "companion_message": "我先问一句，再帮你把今天排成轻松一点的任务。",
            "questions": questions,
            "can_continue_without_answers": False,
        }

    def finish_plan_session(self, session_id: str, followup_answers: str) -> dict[str, Any]:
        session = self._load_session(session_id)
        context = RunContext(run_mode=session.get("run_mode", "official"), session_id=session_id if session.get("run_mode") == "debug" else None, business_date=session.get("business_date"))
        try:
            result = self.after_school.run(session["child_input"], followup_answers, save=True, run_context=context)
        except TypeError as exc:
            if "run_context" not in str(exc):
                raise
            result = self.after_school.run(session["child_input"], followup_answers, save=True)
        plan = result["plan"]
        tasks = [self._kid_task(task, index) for index, task in enumerate(plan.get("tasks", []) or [], start=1)]
        return {
            "session_id": session_id,
            **self._date_labels(context.normalized_date()),
            "run_mode": context.run_mode,
            "business_date": context.normalized_date(),
            "plan_title": plan.get("plan_title") or "今天的轻量任务",
            "total_minutes": plan.get("total_minutes", 0),
            "tasks": tasks,
            "deferred_tasks": plan.get("deferred_tasks", []) or [],
            "child_message": sanitize_child_facing_text(str(plan.get("child_message") or "今天到这里就很好。")),
            "parent_explanation": sanitize_child_facing_text(str(plan.get("parent_explanation", "")), fallback="这段内容已改写为温和的学习建议。"),
            "saved_plan_path": result.get("saved_plan_path"),
        }

    def settle_reflection(self, reflection_input: str, run_context: RunContext | None = None) -> dict[str, Any]:
        if not reflection_input.strip():
            raise ValueError("复盘内容不能为空。")

        context = run_context or RunContext()
        result = self.reflection.run(reflection_input, save=True, run_context=context)
        daily_log = result["daily_log"]
        feedback = result["feedback"]
        return {
            "settlement_title": "今日结算",
            **self._date_labels(context.normalized_date()),
            "run_mode": context.run_mode,
            "business_date": context.normalized_date(),
            "effort_stars": self._effort_stars(daily_log),
            "completion_rate": daily_log.get("completion_rate", 0),
            "completed_summary": self._completed_summary(daily_log),
            "stuck_points": daily_log.get("new_weaknesses", []) or [],
            "pending_summary": self._pending_summary(daily_log),
            "encouragement": sanitize_child_facing_text(str(feedback.get("encouragement") or feedback.get("child_feedback") or "你今天已经很努力了。")),
            "closed_loop_status": daily_log.get("closed_loop_status", "partial"),
            "saved_log_path": result.get("saved_log_path"),
        }

    def _session_dir(self) -> Path:
        return self.config.runtime_dir / "kid_sessions"

    def _session_path(self, session_id: str) -> Path:
        return self._session_dir() / f"{quote(session_id, safe='')}.json"

    def _save_session(self, session: KidSession) -> None:
        self._session_dir().mkdir(parents=True, exist_ok=True)
        save_json(self._session_path(session.session_id), session.to_dict())

    def _load_session(self, session_id: str) -> dict[str, Any]:
        session = load_json(self._session_path(session_id), default=None)
        if not session:
            raise ValueError("没有找到这次计划会话，请重新开始。")
        return session

    def _date_labels(self, business_date: str | None = None) -> dict[str, str]:
        now = datetime.strptime(business_date, "%Y-%m-%d") if business_date else datetime.now()
        return {
            "date_label": f"{now.year}年{now.month}月{now.day}日",
            "weekday_label": WEEKDAY_CN[now.weekday()],
        }

    def _questions_for_child(self, followup: dict[str, Any], understanding: dict[str, Any]) -> list[dict[str, Any]]:
        raw_questions = list(followup.get("questions", []) or [])[:3]
        if not raw_questions:
            raw_questions = [{"question": "这些任务里，哪一项明天一定要交？", "why": "我想先帮你保住最重要的一项。", "answer_type": "short_text"}]
        return [
            {
                "id": f"q{index}",
                "question": self._child_safe_text(str(item.get("question") or "你现在感觉累不累？")),
                "why": self._child_safe_why(str(item.get("why") or "这样我能把任务排轻一点。")),
                "answer_type": str(item.get("answer_type") or "short_text"),
            }
            for index, item in enumerate(raw_questions, start=1)
        ]

    def _clarification_gate(self, child_input: str, understanding: dict[str, Any]) -> dict[str, Any]:
        tasks = understanding.get("mentioned_tasks", []) or []
        task_count = len(tasks)
        available_minutes = understanding.get("available_minutes") or parse_available_minutes(child_input)
        text = child_input.strip()
        reasons: list[str] = []
        questions: list[dict[str, str]] = []

        if task_count == 0 or len(text) < 12:
            reasons.append("empty_or_vague_tasks")
            questions.append({"question": "你今天最想先处理哪一件事？", "why": "我先抓住最重要的一步，不把今天排满。", "answer_type": "short_text"})
        if self._looks_like_daily_homework_mix(text, tasks):
            reasons.append("daily_homework_mix")
            questions.extend(self._daily_homework_questions(text, available_minutes))
        if task_count >= 3 and not available_minutes:
            reasons.append("many_tasks_without_time")
            questions.append({"question": "你大概还有多少分钟可以用？", "why": "知道时间后，我才能把任务变轻一点。", "answer_type": "short_text"})
        if task_count >= 3 and understanding.get("energy_level") in {None, "", "unknown"}:
            reasons.append("many_tasks_without_energy")
            questions.append({"question": "你现在是正常、有点累，还是很累？", "why": "我会按你的精力来减少任务。", "answer_type": "choice"})

        if not reasons:
            return {"triggered": False, "reasons": [], "questions": []}
        deduped: list[dict[str, str]] = []
        seen_questions: set[str] = set()
        for question in questions:
            if question["question"] in seen_questions:
                continue
            deduped.append(question)
            seen_questions.add(question["question"])
            if len(deduped) == 3:
                break
        return {"triggered": True, "reasons": reasons, "questions": deduped}

    def _looks_like_daily_homework_mix(self, text: str, tasks: list[dict[str, Any]]) -> bool:
        subjects = {str(task.get("subject", "")) for task in tasks}
        has_core_subjects = {"math", "english", "chinese"}.issubset(subjects)
        has_long_term = any(keyword in text for keyword in ["阅读", "打卡", "课外", "拓展", "长期"])
        has_workbook = any(keyword in text for keyword in ["大小册", "习字册", "口头作业", "背诵", "抄写"])
        return has_core_subjects and (has_long_term or has_workbook)

    def _daily_homework_questions(self, text: str, available_minutes: int | None) -> list[dict[str, str]]:
        questions = [
            {
                "question": "数学在学校已经做完多少了，到家还剩大概几题或几分钟？",
                "why": "我先确认数学剩余量，避免把已经完成的部分重复安排。",
                "answer_type": "short_text",
            },
            {
                "question": "语文里现在最费时间的是大小册、习字册，还是复习/综合实践？",
                "why": "语文通常是最大块任务，我想先帮你拆出最要紧的一步。",
                "answer_type": "short_text",
            },
        ]
        if not available_minutes:
            questions.append(
                {
                    "question": "晚饭前或睡前前半段，你大概还有多少分钟可以先用？",
                    "why": "知道时间后，我才能决定长期阅读和打卡题今天要不要轻量接住。",
                    "answer_type": "short_text",
                }
            )
        else:
            questions.append(
                {
                    "question": "阅读和打卡题今天必须做吗，还是可以明天轻量接住？",
                    "why": "长期任务不应该抢走第二天要交作业的时间。",
                    "answer_type": "short_text",
                }
            )
        return questions

    def _child_safe_text(self, text: str) -> str:
        blocked_terms = ["pending_tasks", "RAG", "JSON", "trace", "validator", "debug", "调试"]
        if any(term in text for term in blocked_terms):
            return "我再确认一个小问题，好把今天排得更轻松一点。"
        return text

    def _child_safe_why(self, text: str) -> str:
        if any(term in text for term in ["pending_tasks", "优先级"]):
            return "这样我就知道先保护哪一件最要紧的事。"
        if any(term in text for term in ["减负规则", "缩短时间", "更大幅度的减负"]):
            return "这样我可以把任务排得轻一点，不让今天太满。"
        if any(term in text for term in ["RAG", "JSON", "trace", "validator", "debug", "调试"]):
            return "这样我能更懂你今天的情况。"
        return text

    def _kid_task(self, task: dict[str, Any], index: int) -> dict[str, Any]:
        return {
            "title": task.get("title", "今日任务"),
            "subject": task.get("subject", "other"),
            "minutes": task.get("minutes", 0),
            "completion_standard": task.get("completion_standard", "完成一个最小可检查动作"),
            "quest_label": f"第 {index} 关",
            "reward_stars": max(1, min(3, 4 - index)),
        }

    def _effort_stars(self, daily_log: dict[str, Any]) -> int:
        completion_rate = float(daily_log.get("completion_rate", 0) or 0)
        if completion_rate >= 0.85:
            return 5
        if completion_rate >= 0.5:
            return 4
        return 3

    def _completed_summary(self, daily_log: dict[str, Any]) -> str:
        summary = daily_log.get("summary")
        if summary:
            return str(summary)
        return "今天的情况已经记录好了。"

    def _pending_summary(self, daily_log: dict[str, Any]) -> str:
        pending = daily_log.get("pending_tasks_added", []) or []
        if not pending:
            return "今天没有需要额外收好的任务。"
        titles = "、".join(str(item.get("title", "任务")) for item in pending[:3])
        return f"{titles} 已经收好，不用今晚硬补。"
