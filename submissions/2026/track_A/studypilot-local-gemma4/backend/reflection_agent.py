from __future__ import annotations

from typing import Any

from backend.config import AppConfig, get_config
from backend.llm_client import LLMClient
from backend.prompts import profile_update_messages, reflection_encouragement_messages, reflection_understanding_messages
from backend.parent_data_store import ParentDataStore
from backend.rag_store import RagStore, build_chunks_from_profile, profile_to_markdown
from backend.run_context import RUN_MODE_OFFICIAL, RunContext
from backend.rule_engine import sanitize_child_facing_text
from backend.storage import latest_plan, now_iso, save_agent_trace, save_daily_log, save_rag_chunks, save_student_profile, save_student_profile_markdown, timestamp_slug, today_str, load_student_profile
from backend.validator import validate_reflection_output


class ReflectionAgent:
    """睡前复盘 -> daily_log -> 鼓励反馈 -> 更新学习档案与 RAG。"""

    def __init__(self, config: AppConfig | None = None, llm: LLMClient | None = None, rag: RagStore | None = None):
        self.config = config or get_config()
        self.llm = llm or LLMClient(self.config)
        self.rag = rag or RagStore(self.config)

    def run(self, reflection_input: str, plan: dict[str, Any] | None = None, save: bool = True, run_context: RunContext | None = None) -> dict[str, Any]:
        if not reflection_input.strip():
            raise ValueError("睡前复盘输入不能为空。")

        trace: list[dict[str, Any]] = []
        current_plan = plan or latest_plan(self.config) or {}
        understanding = self._understand_reflection(reflection_input, current_plan, trace)
        rag_context = self.rag.search_many([reflection_input, "睡前复盘 未完成 pending_tasks 鼓励", "英语听力 数学 卡住 疲惫"], top_k=5)
        trace.append({"step": "rag_retrieve_for_reflection", "chunk_ids": [c.get("chunk_id") for c in rag_context]})

        feedback = self._sanitize_feedback(self._generate_feedback(reflection_input, understanding, rag_context, trace))
        context = run_context or RunContext()
        daily_log = self._build_daily_log(reflection_input, current_plan, understanding, feedback, context)
        validation = validate_reflection_output(feedback, daily_log).to_dict()

        current_profile = load_student_profile(self.config)
        profile_update = self._generate_profile_update(current_profile, understanding, daily_log, trace)
        updated_profile = self._apply_profile_update(current_profile, understanding, daily_log, profile_update)
        updated_markdown = profile_to_markdown(updated_profile)
        updated_chunks = build_chunks_from_profile(updated_profile, updated_markdown)

        saved_log_path = None
        saved_trace_path = None
        if save:
            saved_log_path = save_daily_log(daily_log, self.config)
            ParentDataStore(self.config).upsert_daily_log(daily_log, run_mode=context.run_mode, session_id=context.normalized_session_id())
            if context.run_mode == RUN_MODE_OFFICIAL:
                save_student_profile(updated_profile, self.config)
                save_student_profile_markdown(updated_markdown, self.config)
                save_rag_chunks(updated_chunks, self.config)
            saved_trace_path = save_agent_trace({"trace_id": f"reflection_trace_{timestamp_slug()}", "type": "reflection", "input": reflection_input, "trace": trace, "daily_log": daily_log, "profile_update": profile_update}, self.config)

        return {
            "input": reflection_input,
            "latest_plan": current_plan,
            "understanding": understanding,
            "feedback": feedback,
            "daily_log": daily_log,
            "profile_update": profile_update,
            "updated_profile_summary": updated_profile.get("rag_summary", ""),
            "updated_profile": updated_profile,
            "updated_markdown": updated_markdown,
            "updated_chunks": updated_chunks,
            "rag_context": rag_context,
            "validation": validation,
            "agent_trace": trace,
            "saved_log_path": str(saved_log_path) if saved_log_path else None,
            "saved_trace_path": str(saved_trace_path) if saved_trace_path else None,
        }

    def _understand_reflection(self, text: str, plan: dict[str, Any], trace: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            result, llm_result = self.llm.chat_json(reflection_understanding_messages(text, plan))
            trace.append({"step": "llm_understand_reflection", "is_mock": llm_result.is_mock})
            return result
        except Exception as exc:
            trace.append({"step": "understand_reflection_fallback", "error": str(exc)})
            return self._fallback_understanding(text)

    def _generate_feedback(self, text: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]], trace: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            result, llm_result = self.llm.chat_json(reflection_encouragement_messages(text, understanding, rag_context))
            trace.append({"step": "llm_reflection_encouragement", "is_mock": llm_result.is_mock})
            return result
        except Exception as exc:
            trace.append({"step": "reflection_encouragement_fallback", "error": str(exc)})
            return {"child_feedback": "今天已经完成了一部分关键任务，这一点值得肯定。", "encouragement": "没完成的内容不用今晚硬补，我们把它变成明天的小步骤。", "tomorrow_light_suggestion": "明天先做一个 8-10 分钟的小任务，完成后就打勾。", "parent_note": "今晚不建议补偿式加量，保留睡前稳定感更重要。", "closed_loop_status": "partial"}

    def _sanitize_feedback(self, feedback: dict[str, Any]) -> dict[str, Any]:
        sanitized = dict(feedback or {})
        for field in ["child_feedback", "encouragement", "tomorrow_light_suggestion"]:
            if isinstance(sanitized.get(field), str):
                sanitized[field] = sanitize_child_facing_text(str(sanitized[field]))
        if isinstance(sanitized.get("parent_note"), str):
            sanitized["parent_note"] = sanitize_child_facing_text(str(sanitized["parent_note"]), fallback="今晚建议保持轻量复盘，不做责备或补偿式加量。")
        return sanitized

    def _generate_profile_update(self, current_profile: dict[str, Any], understanding: dict[str, Any], daily_log: dict[str, Any], trace: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            result, llm_result = self.llm.chat_json(profile_update_messages(current_profile, understanding, daily_log))
            trace.append({"step": "llm_profile_update", "is_mock": llm_result.is_mock})
            return result
        except Exception as exc:
            trace.append({"step": "profile_update_fallback", "error": str(exc)})
            return {"profile_patch": {"pending_tasks_add": understanding.get("pending_tasks_to_add", []), "learning_history_add": {"summary": daily_log.get("summary", ""), "completion_rate": daily_log.get("completion_rate"), "energy_level": daily_log.get("energy_level"), "new_weaknesses": understanding.get("new_weaknesses", [])}, "energy_trend_add": {"energy_level": daily_log.get("energy_level", "unknown")}, "procrastination_signals_add_or_update": [], "rag_summary_update": "最近复盘已更新，未完成任务进入 pending_tasks。"}, "update_reason": "fallback deterministic update", "needs_parent_review": False}

    def _build_daily_log(self, text: str, plan: dict[str, Any], understanding: dict[str, Any], feedback: dict[str, Any], run_context: RunContext | None = None) -> dict[str, Any]:
        completion_rate = self._estimate_completion_rate(understanding)
        pending_tasks = self._pending_tasks_from_understanding(understanding, feedback)

        context = run_context or RunContext()
        return {
            "log_id": f"daily_log_{timestamp_slug()}",
            "date": context.normalized_date(),
            "created_at": now_iso(),
            "source_input": text,
            "plan_id": plan.get("plan_id"),
            "run_mode": context.run_mode,
            "session_id": context.normalized_session_id(),
            "business_date": context.normalized_date(),
            "completed": understanding.get("completed", []),
            "partially_completed": understanding.get("partially_completed", []),
            "not_completed": understanding.get("not_completed", []),
            "new_weaknesses": understanding.get("new_weaknesses", []),
            "energy_level": understanding.get("energy_level", "unknown"),
            "mood_signal": understanding.get("mood_signal", ""),
            "completion_rate": round(max(0.0, min(1.0, completion_rate)), 2),
            "pending_tasks_added": pending_tasks,
            "feedback": feedback,
            "summary": self._summary_from_understanding(understanding),
            "closed_loop_status": feedback.get("closed_loop_status", "partial"),
        }

    def _apply_profile_update(self, profile: dict[str, Any], understanding: dict[str, Any], daily_log: dict[str, Any], update: dict[str, Any]) -> dict[str, Any]:
        profile = dict(profile or {})
        profile.setdefault("profile_version", "2.0")
        profile.setdefault("student", {"nickname": "小航", "grade": "六年级", "stage": "小升初过渡期"})
        profile.setdefault("family_goal", {})
        profile.setdefault("subjects", {})
        profile.setdefault("weekly_schedule", [])
        profile.setdefault("burden_rules", {})
        profile.setdefault("pending_tasks", [])
        profile.setdefault("learning_history", [])
        profile.setdefault("energy_trend", [])
        profile.setdefault("procrastination_signals", [])

        patch = update.get("profile_patch", {}) if isinstance(update, dict) else {}
        pending_to_add = []
        pending_to_add.extend(understanding.get("pending_tasks_to_add", []) or [])
        pending_to_add.extend(daily_log.get("pending_tasks_added", []) or [])
        pending_to_add.extend(patch.get("pending_tasks_add", []) or [])
        self._append_pending_tasks(profile, pending_to_add)

        history_item = patch.get("learning_history_add") or {}
        if not history_item:
            history_item = {"summary": daily_log.get("summary", ""), "completion_rate": daily_log.get("completion_rate"), "energy_level": daily_log.get("energy_level"), "new_weaknesses": daily_log.get("new_weaknesses", [])}
        history_item.setdefault("date", daily_log.get("date"))
        history_item.setdefault("summary", daily_log.get("summary", ""))
        history_item.setdefault("completion_rate", daily_log.get("completion_rate"))
        history_item.setdefault("energy_level", daily_log.get("energy_level"))
        history_item.setdefault("new_weaknesses", daily_log.get("new_weaknesses", []))
        profile["learning_history"].append(history_item)

        energy_item = patch.get("energy_trend_add") or {"energy_level": daily_log.get("energy_level", "unknown")}
        energy_item.setdefault("date", daily_log.get("date"))
        profile["energy_trend"].append(energy_item)

        for signal in patch.get("procrastination_signals_add_or_update", []) or []:
            self._upsert_signal(profile, signal)

        rag_update = patch.get("rag_summary_update")
        if rag_update:
            old = profile.get("rag_summary", "")
            profile["rag_summary"] = (old + "\n" + rag_update).strip() if old else rag_update
        else:
            profile["rag_summary"] = (profile.get("rag_summary", "") + f"\n最新复盘：{daily_log.get('summary', '')}").strip()
        profile["updated_at"] = now_iso()
        return profile

    def _append_pending_tasks(self, profile: dict[str, Any], tasks: list[dict[str, Any]]) -> None:
        existing_keys = {(str(t.get("title", "")), str(t.get("subject", ""))) for t in profile.get("pending_tasks", [])}
        for task in tasks:
            if not task:
                continue
            key = (str(task.get("title", "")), str(task.get("subject", "")))
            if key in existing_keys or not key[0]:
                continue
            item = dict(task)
            item.setdefault("task_id", f"pending_{timestamp_slug()}_{len(profile.get('pending_tasks', [])) + 1}")
            item.setdefault("priority", "medium")
            item.setdefault("created_at", now_iso())
            profile["pending_tasks"].append(item)
            existing_keys.add(key)

    def _pending_tasks_from_understanding(self, understanding: dict[str, Any], feedback: dict[str, Any]) -> list[dict[str, Any]]:
        pending: list[dict[str, Any]] = []
        pending.extend(understanding.get("pending_tasks_to_add", []) or [])

        existing_keys = {(str(task.get("title", "")), str(task.get("subject", ""))) for task in pending}
        tomorrow_step = str(feedback.get("tomorrow_light_suggestion", "")).strip() or "明天做一个最小步骤。"
        for task in (understanding.get("not_completed", []) or []) + (understanding.get("partially_completed", []) or []):
            title = str(task.get("title", "")).strip()
            subject = str(task.get("subject", "other")).strip() or "other"
            if not title:
                continue
            key = (title, subject)
            if key in existing_keys:
                continue
            pending.append(
                {
                    "title": title,
                    "subject": subject,
                    "reason": task.get("reason") or task.get("remaining") or "今日未完全完成",
                    "suggested_next_step": tomorrow_step,
                }
            )
            existing_keys.add(key)
        return pending

    def _upsert_signal(self, profile: dict[str, Any], signal: dict[str, Any]) -> None:
        subject = signal.get("subject")
        task_type = signal.get("task_type")
        for existing in profile.get("procrastination_signals", []):
            if existing.get("subject") == subject and existing.get("task_type") == task_type:
                existing.update(signal)
                return
        profile["procrastination_signals"].append(signal)

    def _fallback_understanding(self, text: str) -> dict[str, Any]:
        completed = []
        partial = []
        not_completed = []
        pending = []
        if "数学" in text and ("做完" in text or "完成" in text):
            completed.append({"title": "数学", "subject": "math", "evidence": "输入中提到数学完成"})
        if "语文" in text and ("一半" in text or "背了一半" in text):
            partial.append({"title": "语文背诵", "subject": "chinese", "evidence": "输入中提到语文一半", "remaining": "剩余部分"})
            pending.append({"title": "语文背诵剩余部分", "subject": "chinese", "reason": "今日部分完成", "suggested_next_step": "明天只补一小段"})
        if "英语" in text and ("没做" in text or "没动" in text or "未做" in text):
            not_completed.append({"title": "英语听力", "subject": "english", "reason": "输入中提到英语没做"})
            pending.append({"title": "英语听力短任务", "subject": "english", "reason": "今日未完成", "suggested_next_step": "明天听 8-10 分钟并说出 2 个关键词"})
        energy = "tired" if any(x in text for x in ["累", "困", "太困"]) else "normal"
        return {"completed": completed, "partially_completed": partial, "not_completed": not_completed, "new_weaknesses": ["数学卡题"] if "卡" in text else [], "energy_level": energy, "mood_signal": "疲惫" if energy == "tired" else "平稳", "completion_rate_estimate": self._estimate_completion_rate({"completed": completed, "partially_completed": partial, "not_completed": not_completed}), "pending_tasks_to_add": pending, "risk_flags": []}

    def _estimate_completion_rate(self, understanding: dict[str, Any]) -> float:
        completed = len(understanding.get("completed", []) or [])
        partial = len(understanding.get("partially_completed", []) or [])
        not_completed = len(understanding.get("not_completed", []) or [])
        total = completed + partial + not_completed
        if total == 0:
            return 0.0
        return (completed + 0.5 * partial) / total

    def _summary_from_understanding(self, understanding: dict[str, Any]) -> str:
        parts: list[str] = []
        if understanding.get("completed"):
            parts.append("完成：" + "、".join(item.get("title", "任务") for item in understanding.get("completed", [])))
        if understanding.get("partially_completed"):
            parts.append("部分完成：" + "、".join(item.get("title", "任务") for item in understanding.get("partially_completed", [])))
        if understanding.get("not_completed"):
            parts.append("未完成：" + "、".join(item.get("title", "任务") for item in understanding.get("not_completed", [])))
        if understanding.get("new_weaknesses"):
            parts.append("新卡点：" + "、".join(understanding.get("new_weaknesses", [])))
        if not parts:
            return "今日完成情况较少，需要明天轻量跟进。"
        return "；".join(parts)
