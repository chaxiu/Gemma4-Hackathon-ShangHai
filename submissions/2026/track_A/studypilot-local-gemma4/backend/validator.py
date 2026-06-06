from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from backend.rule_engine import has_completion_standard, reflection_policy_checks, total_task_minutes


@dataclass
class ValidationResult:
    passed: bool
    errors: list[str] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
    details: dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        return {"passed": self.passed, "errors": self.errors, "warnings": self.warnings, "details": self.details}


def validate_student_profile(profile: dict[str, Any]) -> ValidationResult:
    errors: list[str] = []
    warnings: list[str] = []
    if not isinstance(profile, dict):
        return ValidationResult(False, ["profile 必须是 JSON object"], [])
    for key in ["student", "family_goal", "subjects", "burden_rules"]:
        if key not in profile:
            errors.append(f"缺少顶层字段：{key}")
    student = profile.get("student", {})
    if not student.get("grade"):
        warnings.append("student.grade 为空，建议家长确认年级。")
    subjects = profile.get("subjects", {})
    for subject in ["math", "english", "chinese"]:
        if subject not in subjects:
            warnings.append(f"subjects.{subject} 缺失。")
    burden_rules = profile.get("burden_rules", {})
    if burden_rules.get("normal_day_max_minutes", 60) > 90:
        warnings.append("normal_day_max_minutes 偏高，可能不符合减负目标。")
    if burden_rules.get("max_core_tasks_weekday", 3) > 3:
        errors.append("max_core_tasks_weekday 不能超过 3。")
    return ValidationResult(len(errors) == 0, errors, warnings, {"profile_version": profile.get("profile_version"), "nickname": student.get("nickname")})


def validate_rag_chunks(chunks: list[dict[str, Any]]) -> ValidationResult:
    errors: list[str] = []
    warnings: list[str] = []
    if not isinstance(chunks, list):
        return ValidationResult(False, ["rag_chunks 必须是 list"], [])
    seen_ids: set[str] = set()
    for index, chunk in enumerate(chunks):
        if not isinstance(chunk, dict):
            errors.append(f"第 {index} 个 chunk 不是 object。")
            continue
        chunk_id = chunk.get("chunk_id")
        if not chunk_id:
            errors.append(f"第 {index} 个 chunk 缺少 chunk_id。")
        elif chunk_id in seen_ids:
            errors.append(f"重复 chunk_id：{chunk_id}")
        else:
            seen_ids.add(chunk_id)
        if not chunk.get("content"):
            errors.append(f"chunk {chunk_id or index} 缺少 content。")
        keywords = chunk.get("keywords")
        if not isinstance(keywords, list) or not keywords:
            warnings.append(f"chunk {chunk_id or index} 缺少 keywords，检索效果可能下降。")
        if "embedding" not in chunk:
            warnings.append(f"chunk {chunk_id or index} 缺少 embedding 字段，建议设为 null 以便后续扩展。")
    return ValidationResult(len(errors) == 0, errors, warnings, {"chunk_count": len(chunks)})


def validate_after_school_plan(plan: dict[str, Any], *, rule_checks: list[dict[str, Any]] | None = None) -> ValidationResult:
    errors: list[str] = []
    warnings: list[str] = []
    issues: list[dict[str, Any]] = []
    tasks = plan.get("tasks", []) or []
    if not isinstance(tasks, list):
        errors.append("plan.tasks 必须是 list。")
        tasks = []
    if len(tasks) > 3:
        errors.append("工作日核心任务超过 3 个。")
    for index, task in enumerate(tasks):
        if not has_completion_standard(task):
            errors.append(f"第 {index + 1} 个任务缺少完成标准。")
        if not task.get("title"):
            errors.append(f"第 {index + 1} 个任务缺少 title。")
        if int(task.get("minutes", 0) or 0) <= 0:
            warnings.append(f"第 {index + 1} 个任务 minutes 不合理。")
    total_minutes = total_task_minutes(tasks)
    cap = plan.get("time_cap_minutes") or plan.get("available_minutes")
    if isinstance(cap, int) and total_minutes > cap:
        errors.append(f"总时长 {total_minutes} 超过上限 {cap}。")
    if not plan.get("why_this_is_enough"):
        warnings.append("缺少 why_this_is_enough，减负解释不足。")
    if not plan.get("parent_explanation"):
        warnings.append("缺少 parent_explanation，家长侧解释不足。")
    if rule_checks:
        issues = [
            {
                "rule_id": check.get("rule_id"),
                "issue_code": check.get("issue_code"),
                "severity": check.get("severity"),
                "detail": check.get("detail"),
                "repair_hint": check.get("repair_hint"),
                "blocking": bool(check.get("blocking", False)),
            }
            for check in rule_checks
            if check.get("issue_code")
        ]
        hard_failed = [check for check in rule_checks if not check.get("passed") and check.get("severity") == "error"]
        if hard_failed:
            errors.extend(f"规则失败：{check.get('title')}" for check in hard_failed)
    available_chunk_ids = set(plan.get("available_rag_chunk_ids", []) or plan.get("rag_context_chunk_ids", []) or [])
    referenced_chunk_ids = [str(chunk_id) for chunk_id in plan.get("rag_chunk_ids", []) or [] if chunk_id]
    if available_chunk_ids:
        missing_chunk_ids = [chunk_id for chunk_id in referenced_chunk_ids if chunk_id not in available_chunk_ids]
        if missing_chunk_ids:
            warnings.append(f"计划引用了不存在的 RAG chunk：{', '.join(missing_chunk_ids)}")
            issues.append(
                {
                    "rule_id": "rag_chunk_ids_exist",
                    "issue_code": "RAG_CHUNK_ID_NOT_FOUND",
                    "severity": "warning",
                    "detail": f"不存在的 chunk_id：{', '.join(missing_chunk_ids)}",
                    "repair_hint": "Only keep chunk ids returned by current RagStore search.",
                    "blocking": False,
                }
            )
    return ValidationResult(len(errors) == 0, errors, warnings, {"task_count": len(tasks), "total_minutes": total_minutes, "time_cap_minutes": cap, "issues": issues})


def validate_reflection_output(feedback: dict[str, Any], daily_log: dict[str, Any]) -> ValidationResult:
    errors: list[str] = []
    warnings: list[str] = []
    checks = reflection_policy_checks(feedback, daily_log)
    for check in checks:
        if not check.get("passed") and check.get("severity") == "error":
            errors.append(f"规则失败：{check.get('title')} - {check.get('detail')}")
        elif not check.get("passed"):
            warnings.append(f"规则提醒：{check.get('title')} - {check.get('detail')}")
    if not feedback.get("encouragement"):
        errors.append("缺少 encouragement。")
    if not feedback.get("tomorrow_light_suggestion"):
        warnings.append("缺少 tomorrow_light_suggestion。")
    if "completion_rate" not in daily_log:
        warnings.append("daily_log 缺少 completion_rate。")
    return ValidationResult(len(errors) == 0, errors, warnings, {"policy_checks": checks})


def validate_eval_case(case: dict[str, Any]) -> ValidationResult:
    errors: list[str] = []
    warnings: list[str] = []
    if not case.get("case_id"):
        errors.append("评测用例缺少 case_id。")
    if not case.get("type"):
        errors.append("评测用例缺少 type。")
    if "input" not in case:
        errors.append("评测用例缺少 input。")
    if not case.get("expected_rules") and not case.get("must_have"):
        warnings.append("评测用例缺少 expected_rules 或 must_have，可能无法自动判断。")
    return ValidationResult(len(errors) == 0, errors, warnings, {"case_id": case.get("case_id"), "type": case.get("type")})
