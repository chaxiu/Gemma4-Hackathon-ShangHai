from __future__ import annotations

from datetime import datetime
from typing import Any

from backend.config import AppConfig, get_config
from backend.llm_client import LLMClient
from backend.prompts import active_question_messages, after_school_understanding_messages, task_classification_messages, today_plan_generation_messages
from backend.parent_data_store import ParentDataStore
from backend.rag_store import RagStore
from backend.run_context import RunContext
from backend.rule_engine import ensure_plan_compliance, infer_energy_level, parse_available_minutes, rule_summary_for_prompt
from backend.storage import save_agent_trace, save_plan, today_str, timestamp_slug
from backend.validator import validate_after_school_plan


class AfterSchoolAgent:
    """放学后孩子输入 -> 主动追问 -> RAG -> 规则引擎 -> 今日计划。"""

    def __init__(self, config: AppConfig | None = None, llm: LLMClient | None = None, rag: RagStore | None = None):
        self.config = config or get_config()
        self.llm = llm or LLMClient(self.config)
        self.rag = rag or RagStore(self.config)

    def run(self, child_input: str, followup_answers: str | None = None, now_dt: datetime | None = None, save: bool = True, run_context: RunContext | None = None) -> dict[str, Any]:
        if not child_input.strip():
            raise ValueError("孩子输入不能为空。")

        trace: list[dict[str, Any]] = []
        combined_input = child_input.strip()
        if followup_answers and followup_answers.strip():
            combined_input += f"\n补充回答：{followup_answers.strip()}"

        understanding = self._understand_input(combined_input, trace)
        available_minutes = understanding.get("available_minutes") or parse_available_minutes(combined_input)
        energy_level = understanding.get("energy_level") or infer_energy_level(combined_input, default="normal")
        if energy_level == "unknown":
            energy_level = infer_energy_level(combined_input, default="normal")
        understanding["available_minutes"] = available_minutes
        understanding["energy_level"] = energy_level

        queries = self._queries_from_understanding(combined_input, understanding)
        rag_context = self.rag.search_many(queries, top_k=5)
        trace.append({"step": "rag_retrieve", "queries": queries, "chunk_count": len(rag_context), "chunk_ids": [c.get("chunk_id") for c in rag_context]})

        followup = self._ask_followup(combined_input, understanding, rag_context, trace)
        classified = self._classify_tasks(combined_input, understanding, rag_context, trace)

        rule_summary = rule_summary_for_prompt(energy_level=energy_level, available_minutes=available_minutes, now_dt=now_dt, text=combined_input, config=self.config)
        trace.append({"step": "rule_summary", "result": rule_summary})

        plan = self._generate_plan(combined_input, understanding, classified, rag_context, rule_summary, trace)
        context = run_context or RunContext(business_date=today_str())
        plan["date"] = context.normalized_date()
        plan.setdefault("plan_id", f"after_school_{timestamp_slug()}")
        plan["run_mode"] = context.run_mode
        plan["session_id"] = context.normalized_session_id()
        plan["business_date"] = context.normalized_date()
        plan["agent_type"] = "after_school_planner"
        plan["rag_chunk_ids"] = [chunk.get("chunk_id") for chunk in rag_context]
        plan["available_rag_chunk_ids"] = [chunk.get("chunk_id") for chunk in rag_context]

        repaired_plan, rule_checks, repair_trace = ensure_plan_compliance(plan, child_input=combined_input, now_dt=now_dt, config=self.config)
        trace.extend(repair_trace)
        validation = validate_after_school_plan(repaired_plan, rule_checks=rule_checks).to_dict()
        repaired_plan["rule_checks"] = rule_checks
        repaired_plan["validation_summary"] = {
            "passed": validation.get("passed"),
            "issues": validation.get("details", {}).get("issues", []),
        }

        saved_plan_path = None
        saved_trace_path = None
        if save:
            saved_plan_path = save_plan(repaired_plan, self.config)
            ParentDataStore(self.config).upsert_plan(repaired_plan, run_mode=context.run_mode, session_id=context.normalized_session_id())
            saved_trace_path = save_agent_trace({"trace_id": f"after_school_trace_{timestamp_slug()}", "type": "after_school", "input": combined_input, "trace": trace, "plan": repaired_plan, "rule_checks": rule_checks}, self.config)

        return {
            "input": child_input,
            "followup_answers": followup_answers,
            "understanding": understanding,
            "followup": followup,
            "classified_tasks": classified,
            "rag_context": rag_context,
            "rule_summary": rule_summary,
            "plan": repaired_plan,
            "rule_checks": rule_checks,
            "validation": validation,
            "agent_trace": trace,
            "saved_plan_path": str(saved_plan_path) if saved_plan_path else None,
            "saved_trace_path": str(saved_trace_path) if saved_trace_path else None,
        }

    def _understand_input(self, text: str, trace: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            result, llm_result = self.llm.chat_json(after_school_understanding_messages(text))
            trace.append({"step": "llm_understand_after_school_input", "is_mock": llm_result.is_mock})
            return result
        except Exception as exc:
            trace.append({"step": "understand_after_school_input_fallback", "error": str(exc)})
            return self._fallback_understanding(text)

    def _ask_followup(self, text: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]], trace: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            result, llm_result = self.llm.chat_json(active_question_messages(text, understanding, rag_context))
            questions = result.get("questions", [])[:3]
            result["questions"] = questions
            trace.append({"step": "active_followup_questions", "is_mock": llm_result.is_mock, "question_count": len(questions)})
            return result
        except Exception as exc:
            trace.append({"step": "active_followup_questions_fallback", "error": str(exc)})
            return {"need_questions": True, "questions": [{"question": "这些任务里，哪一个明天一定要交？", "why": "决定优先级", "answer_type": "short_text"}, {"question": "你现在是正常、有点累，还是很累？", "why": "决定减负上限", "answer_type": "choice"}], "can_plan_without_answers": True}

    def _classify_tasks(self, text: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]], trace: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            result, llm_result = self.llm.chat_json(task_classification_messages(text, understanding, rag_context))
            trace.append({"step": "task_classification", "is_mock": llm_result.is_mock, "task_count": len(result.get("tasks", []))})
            return result
        except Exception as exc:
            trace.append({"step": "task_classification_fallback", "error": str(exc)})
            return self._fallback_task_classification(text, understanding)

    def _generate_plan(self, text: str, understanding: dict[str, Any], classified: dict[str, Any], rag_context: list[dict[str, Any]], rule_summary: dict[str, Any], trace: list[dict[str, Any]]) -> dict[str, Any]:
        try:
            result, llm_result = self.llm.chat_json(today_plan_generation_messages(text, understanding, classified, rag_context, rule_summary))
            trace.append({"step": "today_plan_generation", "is_mock": llm_result.is_mock})
            return result
        except Exception as exc:
            trace.append({"step": "today_plan_generation_fallback", "error": str(exc)})
            return self._fallback_plan(classified, rule_summary)

    def _queries_from_understanding(self, text: str, understanding: dict[str, Any]) -> list[str]:
        queries = [text]
        for task in understanding.get("mentioned_tasks", []) or []:
            raw = task.get("raw") or task.get("subject")
            if raw:
                queries.append(str(raw))
        queries.append("减负规则 今日计划 完成标准")
        return queries

    def _fallback_understanding(self, text: str) -> dict[str, Any]:
        mentioned_tasks: list[dict[str, Any]] = []
        task_map = [("数学", "math", "practice", "high"), ("英语", "english", "listening", "medium"), ("语文", "chinese", "recitation", "medium"), ("预习", "other", "preview", "low"), ("科学", "other", "homework", "medium")]
        for label, subject, task_type, difficulty in task_map:
            if label in text:
                mentioned_tasks.append({"raw": label, "subject": subject, "task_type": task_type, "estimated_difficulty": difficulty})
        return {"available_minutes": parse_available_minutes(text), "energy_level": infer_energy_level(text, default="unknown"), "mentioned_tasks": mentioned_tasks, "confusion": "需要排序", "needs_follow_up": True, "missing_info": ["截止时间", "当前精力"], "risk_flags": ["任务较多"]}

    def _fallback_task_classification(self, text: str, understanding: dict[str, Any]) -> dict[str, Any]:
        tasks: list[dict[str, Any]] = []
        for task in understanding.get("mentioned_tasks", []) or []:
            subject = task.get("subject", "other")
            if subject == "math":
                tasks.append({"title": "数学重点短练", "subject": "math", "task_type": "practice", "priority": "high", "intensity": "medium", "can_defer": False, "reason": "数学是长期薄弱点", "completion_standard_hint": "完成 1-2 道重点题并圈关键词"})
            elif subject == "english":
                tasks.append({"title": "英语听力短任务", "subject": "english", "task_type": "listening", "priority": "medium", "intensity": "low", "can_defer": False, "reason": "听力有拖延风险", "completion_standard_hint": "听 8-10 分钟，说出 2 个关键词"})
            elif subject == "chinese":
                tasks.append({"title": "语文轻量背诵", "subject": "chinese", "task_type": "recitation", "priority": "medium", "intensity": "low", "can_defer": True, "reason": "语文相对稳定", "completion_standard_hint": "背一小段或复述大意"})
            else:
                tasks.append({"title": task.get("raw", "其他任务"), "subject": "other", "task_type": task.get("task_type", "unknown"), "priority": "low", "intensity": "low", "can_defer": True, "reason": "时间有限时可延期", "completion_standard_hint": "完成一个最小动作"})
        return {"tasks": tasks, "defer_candidates": [t["title"] for t in tasks if t.get("can_defer")], "burden_risk": "high" if len(tasks) > 3 else "medium"}

    def _fallback_plan(self, classified: dict[str, Any], rule_summary: dict[str, Any]) -> dict[str, Any]:
        cap = int(rule_summary.get("final_time_cap_minutes") or 40)
        selected = list(classified.get("tasks", []) or [])[:3]
        default_minutes = max(8, min(15, cap // max(1, len(selected))))
        tasks = []
        for item in selected:
            tasks.append({"title": item.get("title", "今日任务"), "subject": item.get("subject", "other"), "minutes": default_minutes, "intensity": item.get("intensity", "low"), "priority": item.get("priority", "medium"), "completion_standard": item.get("completion_standard_hint") or "完成一个最小可检查动作", "why_first_or_later": item.get("reason", "优先完成最关键的小步骤"), "can_defer": bool(item.get("can_defer", False))})
        deferred = [{"title": item.get("title", "延期任务"), "reason": "今日时间有限，按减负规则延期", "suggested_next_time": "明天做一个最小步骤"} for item in (classified.get("tasks", []) or [])[3:]]
        return {"plan_title": "轻量放学计划", "date": today_str(), "available_minutes": cap, "energy_level": rule_summary.get("energy_level", "normal"), "tasks": tasks, "deferred_tasks": deferred, "burden_reduction_note": "计划已按减负规则保留少量核心任务。", "why_this_is_enough": "今天稳定完成关键动作就够了。", "parent_explanation": "未完成任务会进入延期和复盘，不建议补偿式加量。", "child_message": "先做最小的几步，完成后就可以休息。"}
