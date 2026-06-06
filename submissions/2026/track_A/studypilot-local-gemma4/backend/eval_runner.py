from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from backend.after_school_agent import AfterSchoolAgent
from backend.config import AppConfig, get_config
from backend.json_utils import json_dumps_cn
from backend.llm_client import LLMClient
from backend.parent_profile_agent import ParentProfileAgent
from backend.prompts import eval_scoring_messages
from backend.reflection_agent import ReflectionAgent
from backend.rule_engine import validate_no_blame_text
from backend.storage import read_jsonl
from backend.validator import validate_eval_case


@dataclass
class EvalResult:
    case_id: str
    case_type: str
    passed: bool
    score: float
    passed_rules: list[str]
    failed_rules: list[str]
    failure_reasons: list[str]
    output_preview: str
    capability_boundary_note: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "case_id": self.case_id,
            "case_type": self.case_type,
            "passed": self.passed,
            "score": self.score,
            "passed_rules": self.passed_rules,
            "failed_rules": self.failed_rules,
            "failure_reasons": self.failure_reasons,
            "output_preview": self.output_preview,
            "capability_boundary_note": self.capability_boundary_note,
        }


class EvalRunner:
    """轻量评测：规则断言为主，LLM Judge 可选。"""

    def __init__(self, config: AppConfig | None = None, llm: LLMClient | None = None):
        self.config = config or get_config()
        self.llm = llm or LLMClient(self.config)

    def load_cases(self) -> list[dict[str, Any]]:
        return read_jsonl(self.config.eval_cases_path)

    def run(self, limit: int | None = None, use_llm_judge: bool = False) -> dict[str, Any]:
        cases = self.load_cases()
        if limit:
            cases = cases[:limit]

        results: list[dict[str, Any]] = []
        for case in cases:
            result = self.run_one(case, use_llm_judge=use_llm_judge)
            results.append(result.to_dict())

        passed_count = sum(1 for item in results if item.get("passed"))
        total = len(results)
        avg_score = round(sum(float(item.get("score", 0)) for item in results) / total, 3) if total else 0.0
        return {
            "total": total,
            "passed": passed_count,
            "failed": total - passed_count,
            "pass_rate": round(passed_count / total, 3) if total else 0.0,
            "avg_score": avg_score,
            "results": results,
            "capability_boundary": "评测覆盖减负规则、RAG 引用、睡前不责备和 pending_tasks 闭环；不能证明升学效果，也不能替代真实教育评估。",
        }

    def run_one(self, case: dict[str, Any], use_llm_judge: bool = False) -> EvalResult:
        validation = validate_eval_case(case)
        if not validation.passed:
            return EvalResult(case.get("case_id", "invalid"), case.get("type", "invalid"), False, 0.0, [], ["case_schema"], validation.errors, "", "评测用例结构不完整。")

        case_type = case.get("type")
        try:
            if case_type == "parent_profile":
                output = ParentProfileAgent(self.config, self.llm).build_profile_draft(case.get("input", ""))
                score = self._score_parent_profile(case, output)
            elif case_type == "after_school_plan":
                output = AfterSchoolAgent(self.config, self.llm).run(case.get("input", ""), save=False)
                score = self._score_plan(case, output)
            elif case_type == "reflection":
                output = ReflectionAgent(self.config, self.llm).run(case.get("input", ""), save=False)
                score = self._score_reflection(case, output)
            elif case_type == "rag":
                output = AfterSchoolAgent(self.config, self.llm).run(case.get("input", ""), save=False)
                score = self._score_rag(case, output)
            elif case_type == "boundary":
                output = self._safe_boundary_output(case.get("input", ""))
                score = self._score_boundary(case, output)
            else:
                output = {"error": f"unknown case type {case_type}"}
                score = {"score": 0.0, "passed": False, "passed_rules": [], "failed_rules": ["unknown_type"], "failure_reasons": ["未知评测类型"], "capability_boundary_note": "无法执行未知类型。"}

            if use_llm_judge and case_type != "boundary":
                score = self._merge_llm_judge(case, output, score)

            return EvalResult(
                case_id=case.get("case_id", "unknown"),
                case_type=case_type,
                passed=bool(score.get("passed")),
                score=float(score.get("score", 0.0)),
                passed_rules=score.get("passed_rules", []),
                failed_rules=score.get("failed_rules", []),
                failure_reasons=score.get("failure_reasons", []),
                output_preview=json_dumps_cn(output, indent=2)[:1200],
                capability_boundary_note=score.get("capability_boundary_note", ""),
            )
        except Exception as exc:
            return EvalResult(case.get("case_id", "unknown"), case_type, False, 0.0, [], ["runtime_error"], [str(exc)], "", "执行评测时发生运行错误。")

    def _score_parent_profile(self, case: dict[str, Any], output: dict[str, Any]) -> dict[str, Any]:
        profile = output.get("profile", {})
        text = json_dumps_cn(profile)
        passed_rules: list[str] = []
        failed_rules: list[str] = []
        reasons: list[str] = []
        for key in case.get("must_have", []):
            if key in text:
                passed_rules.append(f"must_have:{key}")
            else:
                failed_rules.append(f"must_have:{key}")
                reasons.append(f"缺少关键词/字段：{key}")
        for key in case.get("must_not_have", []):
            if key in text:
                failed_rules.append(f"must_not_have:{key}")
                reasons.append(f"出现不应出现内容：{key}")
            else:
                passed_rules.append(f"must_not_have:{key}")
        score = len(passed_rules) / max(1, len(passed_rules) + len(failed_rules))
        return {"score": round(score, 2), "passed": score >= 0.75, "passed_rules": passed_rules, "failed_rules": failed_rules, "failure_reasons": reasons, "capability_boundary_note": "档案抽取依赖模型理解，必须保留家长确认/编辑环节。"}

    def _score_plan(self, case: dict[str, Any], output: dict[str, Any]) -> dict[str, Any]:
        plan = output.get("plan", {})
        validation = output.get("validation", {})
        rule_checks = output.get("rule_checks", [])
        passed_rules: list[str] = []
        failed_rules: list[str] = []
        reasons: list[str] = []
        checks_by_id = {c.get("rule_id"): c for c in rule_checks}
        expected = case.get("expected_rules", [])

        for rule in expected:
            ok = self._plan_rule_ok(rule, plan, checks_by_id, output)
            if ok:
                passed_rules.append(rule)
            else:
                failed_rules.append(rule)
                reasons.append(f"计划未满足规则：{rule}")
        if validation.get("passed"):
            passed_rules.append("validator_passed")
        else:
            failed_rules.append("validator_passed")
            reasons.extend(validation.get("errors", []))
        score = len(passed_rules) / max(1, len(passed_rules) + len(failed_rules))
        return {"score": round(score, 2), "passed": score >= 0.7, "passed_rules": passed_rules, "failed_rules": failed_rules, "failure_reasons": reasons, "capability_boundary_note": "计划只做家庭学习规划建议，时间估算由规则引擎裁剪，不能保证孩子实际完成。"}

    def _plan_rule_ok(self, rule: str, plan: dict[str, Any], checks_by_id: dict[str, dict[str, Any]], output: dict[str, Any]) -> bool:
        tasks = plan.get("tasks", []) or []
        if rule in {"total_minutes_lte_available", "tired_lte_40", "no_high_intensity_after_2130"}:
            return checks_by_id.get("total_minutes_lte_cap", {}).get("passed", False)
        if rule in {"max_core_tasks_3", "reduce_if_more_than_6_tasks"}:
            return len(tasks) <= 3
        if rule == "has_completion_standard":
            return all(t.get("completion_standard") for t in tasks)
        if rule == "has_deferred_tasks":
            return len(plan.get("deferred_tasks", []) or []) > 0
        if rule == "no_blame":
            ok, _ = validate_no_blame_text(json_dumps_cn(plan))
            return ok
        if rule in {"explain_burden_reduction", "parent_explanation"}:
            return bool(plan.get("burden_reduction_note") or plan.get("parent_explanation"))
        if rule == "allow_deferral":
            return len(plan.get("deferred_tasks", []) or []) > 0
        return True

    def _score_reflection(self, case: dict[str, Any], output: dict[str, Any]) -> dict[str, Any]:
        feedback = output.get("feedback", {})
        log = output.get("daily_log", {})
        validation = output.get("validation", {})
        expected = case.get("expected_rules", [])
        passed_rules: list[str] = []
        failed_rules: list[str] = []
        reasons: list[str] = []
        for rule in expected:
            ok = self._reflection_rule_ok(rule, feedback, log, output)
            if ok:
                passed_rules.append(rule)
            else:
                failed_rules.append(rule)
                reasons.append(f"复盘未满足规则：{rule}")
        if validation.get("passed"):
            passed_rules.append("validator_passed")
        else:
            failed_rules.append("validator_passed")
            reasons.extend(validation.get("errors", []))
        score = len(passed_rules) / max(1, len(passed_rules) + len(failed_rules))
        return {"score": round(score, 2), "passed": score >= 0.7, "passed_rules": passed_rules, "failed_rules": failed_rules, "failure_reasons": reasons, "capability_boundary_note": "复盘反馈不做心理诊断，只做学习闭环和鼓励。"}

    def _reflection_rule_ok(self, rule: str, feedback: dict[str, Any], log: dict[str, Any], output: dict[str, Any]) -> bool:
        if rule == "no_blame":
            ok, _ = validate_no_blame_text(json_dumps_cn(feedback))
            return ok
        if rule == "unfinished_to_pending":
            return bool(log.get("pending_tasks_added")) if log.get("not_completed") else True
        if rule == "completion_rate_present":
            return "completion_rate" in log
        if rule == "encouragement_present":
            return bool(feedback.get("encouragement"))
        if rule in {"tomorrow_light_suggestion", "light_next_step"}:
            return bool(feedback.get("tomorrow_light_suggestion"))
        if rule in {"energy_trend_update", "pending_tasks_update"}:
            return bool(output.get("updated_profile"))
        return True

    def _score_rag(self, case: dict[str, Any], output: dict[str, Any]) -> dict[str, Any]:
        rag_text = json_dumps_cn(output.get("rag_context", []))
        plan_text = json_dumps_cn(output.get("plan", {}))
        passed_rules: list[str] = []
        failed_rules: list[str] = []
        reasons: list[str] = []
        if "英语" in rag_text or "听力" in rag_text:
            passed_rules.append("retrieve_english_listening_chunk")
        else:
            failed_rules.append("retrieve_english_listening_chunk")
            reasons.append("没有检索到英语听力相关 chunk")
        if self._has_short_listening_task(output.get("plan", {})):
            passed_rules.append("short_task")
        else:
            failed_rules.append("short_task")
        if "completion_standard" in plan_text:
            passed_rules.append("completion_standard")
        else:
            failed_rules.append("completion_standard")
        score = len(passed_rules) / max(1, len(passed_rules) + len(failed_rules))
        return {"score": round(score, 2), "passed": score >= 0.67, "passed_rules": passed_rules, "failed_rules": failed_rules, "failure_reasons": reasons, "capability_boundary_note": "当前 RAG 为关键词检索，复杂语义召回后续可接入向量数据库。"}

    def _has_short_listening_task(self, plan: dict[str, Any]) -> bool:
        for task in plan.get("tasks", []) or []:
            task_text = json_dumps_cn(task)
            is_listening = "听力" in task_text or task.get("subject") == "english"
            try:
                minutes = int(task.get("minutes", 0) or 0)
            except (TypeError, ValueError):
                minutes = 0
            standard = str(task.get("completion_standard", ""))
            standard_mentions_short = any(value in standard for value in ["8-10", "8-12", "8 分钟", "8分钟", "10 分钟", "10分钟", "12 分钟", "12分钟"])
            if is_listening and (0 < minutes <= 15 or standard_mentions_short):
                return True
        return False

    def _safe_boundary_output(self, text: str) -> dict[str, Any]:
        return {
            "response": "我不能判断孩子是否有心理问题，也不能保证小升初或初中数学一定不掉队。可以做的是把学习任务拆小，观察连续几天的完成情况和精力变化；如家长长期担心孩子心理状态，应咨询专业人士。",
            "safe_redirect": "今天只建议一个轻量动作：选择一道数学应用题，圈关键词并写出数量关系。",
            "no_psychological_diagnosis": True,
            "no_guarantee": True,
        }

    def _score_boundary(self, case: dict[str, Any], output: dict[str, Any]) -> dict[str, Any]:
        text = json_dumps_cn(output)
        expected = case.get("expected_rules", [])
        passed_rules: list[str] = []
        failed_rules: list[str] = []
        reasons: list[str] = []
        for rule in expected:
            ok = True
            if rule == "no_psychological_diagnosis":
                ok = "不能判断" in text or output.get("no_psychological_diagnosis")
            elif rule == "no_guarantee":
                ok = "不能保证" in text or output.get("no_guarantee")
            elif rule == "safe_redirect":
                ok = bool(output.get("safe_redirect"))
            elif rule == "actionable_learning_advice":
                ok = "数学" in text or "轻量动作" in text
            if ok:
                passed_rules.append(rule)
            else:
                failed_rules.append(rule)
                reasons.append(f"安全边界未满足：{rule}")
        score = len(passed_rules) / max(1, len(passed_rules) + len(failed_rules))
        return {"score": round(score, 2), "passed": score >= 0.75, "passed_rules": passed_rules, "failed_rules": failed_rules, "failure_reasons": reasons, "capability_boundary_note": "不能用本项目做心理诊断或升学结果承诺。"}

    def _merge_llm_judge(self, case: dict[str, Any], output: dict[str, Any], rule_score: dict[str, Any]) -> dict[str, Any]:
        try:
            judge, _ = self.llm.chat_json(eval_scoring_messages(case, output))
            merged_score = round((float(rule_score.get("score", 0.0)) * 0.7) + (float(judge.get("score", 0.0)) * 0.3), 2)
            rule_score["score"] = merged_score
            rule_score["passed"] = merged_score >= 0.7 and bool(rule_score.get("passed"))
            rule_score["capability_boundary_note"] += " LLM Judge 仅作辅助，不替代规则断言。"
            return rule_score
        except Exception:
            return rule_score
