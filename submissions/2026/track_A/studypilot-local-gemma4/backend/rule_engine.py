from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import datetime, time
from typing import Any

from backend.config import AppConfig, get_config


@dataclass
class RuleCheck:
    rule_id: str
    title: str
    passed: bool
    severity: str
    detail: str
    issue_code: str | None = None
    repair_hint: str | None = None
    blocking: bool = False

    def to_dict(self) -> dict[str, Any]:
        data = {"rule_id": self.rule_id, "title": self.title, "passed": self.passed, "severity": self.severity, "detail": self.detail}
        if self.issue_code:
            data["issue_code"] = self.issue_code
        if self.repair_hint:
            data["repair_hint"] = self.repair_hint
        if self.blocking:
            data["blocking"] = self.blocking
        return data


ENERGY_NORMAL = "normal"
ENERGY_TIRED = "tired"
ENERGY_EXHAUSTED = "exhausted"
ENERGY_UNKNOWN = "unknown"

ISSUE_CORE_TASK_LIMIT_EXCEEDED = "V006_CORE_TASK_LIMIT_EXCEEDED"
ISSUE_DELAYED_TASKS_MISSING = "V008_DELAYED_TASKS_MISSING"
ISSUE_TIME_SUM_INCONSISTENT = "V012_TIME_SUM_INCONSISTENT"
ISSUE_CLARIFICATION_MISMATCH = "V003_CLARIFICATION_MISMATCH"
ISSUE_UNSAFE_LANGUAGE = "V015_UNSAFE_LANGUAGE"

BLAME_WORDS = ["你怎么又", "你总是", "你就是", "太懒", "懒惰", "不努力", "不自觉", "活该", "必须补完", "怎么这么", "真差"]
GUARANTEE_OR_THREAT_WORDS = ["肯定考不上", "一定考不上", "保证考上", "保证提分", "一定能上岸", "再这样就完了", "升学没希望"]
DIAGNOSIS_WORDS = ["抑郁症", "焦虑症", "多动症", "ADHD", "心理有问题", "心理疾病", "有病", "诊断"]
PEER_COMPARISON_WORDS = ["全班第", "班级排名", "排名第", "别人家的孩子", "同学都比你", "比同学差"]

SAFE_CHILD_FALLBACK = "今天不用责备自己，我们只保留一个小步骤，完成多少都可以如实复盘。"
SAFE_PARENT_FALLBACK = "这段内容已按儿童学习场景改写：只保留轻量学习建议，避免负面评价或结果承诺。"


def infer_energy_level(text: str, default: str = ENERGY_NORMAL) -> str:
    lowered = text.lower()
    exhausted_keywords = ["特别累", "非常累", "很累很累", "累爆", "困死", "撑不住", "完全不想动", "崩溃", "最多只能学25", "只能学25"]
    tired_keywords = ["有点累", "有些累", "累", "疲惫", "困", "打完篮球", "不想做", "状态不好"]
    if any(keyword in lowered for keyword in exhausted_keywords):
        return ENERGY_EXHAUSTED
    if any(keyword in lowered for keyword in tired_keywords):
        return ENERGY_TIRED
    return default


def parse_available_minutes(text: str) -> int | None:
    if not text:
        return None
    patterns = [r"只有\s*(\d{1,3})\s*分钟", r"最多\s*(\d{1,3})\s*分钟", r"(\d{1,3})\s*分钟", r"(\d{1,3})\s*分\b"]
    for pattern in patterns:
        match = re.search(pattern, text)
        if match:
            value = int(match.group(1))
            if 1 <= value <= 180:
                return value
    if "半小时" in text:
        return 30
    if "一小时" in text or "1小时" in text or "一个小时" in text:
        return 60
    return None


def parse_clock_time(text: str) -> time | None:
    if not text:
        return None
    match = re.search(r"(\d{1,2})\s*[点:：]\s*(\d{1,2})?", text)
    if not match:
        return None
    hour = int(match.group(1))
    minute = int(match.group(2) or 0)
    if "晚上" in text or "晚" in text:
        if 1 <= hour <= 11:
            hour += 12
    if 0 <= hour <= 23 and 0 <= minute <= 59:
        return time(hour=hour, minute=minute)
    return None


def parse_hhmm(value: str) -> time:
    hour_text, minute_text = value.split(":", 1)
    return time(hour=int(hour_text), minute=int(minute_text))


def is_after_no_high_intensity_time(now_dt: datetime | None = None, *, text: str = "", config: AppConfig | None = None) -> bool:
    cfg = config or get_config()
    cutoff = parse_hhmm(cfg.no_high_intensity_after)
    explicit_time = parse_clock_time(text)
    if explicit_time is not None:
        return explicit_time >= cutoff
    current = now_dt or datetime.now()
    return current.time() >= cutoff


def max_minutes_for_energy(energy_level: str, config: AppConfig | None = None) -> int:
    cfg = config or get_config()
    if energy_level == ENERGY_EXHAUSTED:
        return cfg.exhausted_max_minutes
    if energy_level == ENERGY_TIRED:
        return cfg.tired_max_minutes
    return cfg.normal_max_minutes


def rule_summary_for_prompt(*, energy_level: str, available_minutes: int | None, now_dt: datetime | None = None, text: str = "", config: AppConfig | None = None) -> dict[str, Any]:
    cfg = config or get_config()
    energy_cap = max_minutes_for_energy(energy_level, cfg)
    final_cap = min(available_minutes, energy_cap) if available_minutes else energy_cap
    return {
        "max_core_tasks_weekday": cfg.max_core_tasks_weekday,
        "normal_max_minutes": cfg.normal_max_minutes,
        "tired_max_minutes": cfg.tired_max_minutes,
        "exhausted_max_minutes": cfg.exhausted_max_minutes,
        "energy_level": energy_level,
        "available_minutes": available_minutes,
        "final_time_cap_minutes": final_cap,
        "task_count_reduce_threshold": 6,
        "no_high_intensity_after": cfg.no_high_intensity_after,
        "is_after_no_high_intensity_time": is_after_no_high_intensity_time(now_dt, text=text, config=cfg),
        "must_have_completion_standard": True,
        "unfinished_goes_to_pending": True,
        "principle": "减负优先，未确定时少排一点。",
    }


def _task_minutes(task: dict[str, Any]) -> int:
    try:
        return max(0, int(task.get("minutes", 0)))
    except (TypeError, ValueError):
        return 0


def total_task_minutes(tasks: list[dict[str, Any]]) -> int:
    return sum(_task_minutes(task) for task in tasks)


def has_completion_standard(task: dict[str, Any]) -> bool:
    standard = str(task.get("completion_standard", "")).strip()
    return len(standard) >= 4


def _default_completion_standard(task: dict[str, Any]) -> str:
    title = str(task.get("title", "这个任务"))
    subject = str(task.get("subject", ""))
    if subject == "math" or "数学" in title:
        return "完成 1-2 道重点题，并写出卡住的位置或关键词"
    if subject == "english" or "英语" in title or "听力" in title:
        return "完成 8-10 分钟短听力，并说出 2 个听到的关键词"
    if subject == "chinese" or "语文" in title or "背" in title:
        return "完成一小段背诵或复述大意，不要求整篇重来"
    return "完成一个最小可检查动作，并在睡前复盘中打勾"


def _priority_rank(task: dict[str, Any]) -> int:
    priority = str(task.get("priority", "medium"))
    return {"high": 0, "medium": 1, "low": 2}.get(priority, 1)


def _move_to_deferred(task: dict[str, Any], reason: str) -> dict[str, Any]:
    return {"title": task.get("title", "未命名任务"), "subject": task.get("subject", "other"), "reason": reason, "suggested_next_time": "明天或下一次精力较好时再做一个最小步骤"}


def _normalize_deferred_tasks(tasks: list[Any]) -> tuple[list[dict[str, Any]], int]:
    normalized: list[dict[str, Any]] = []
    repaired_count = 0
    for item in tasks:
        task = dict(item) if isinstance(item, dict) else {"title": str(item)}
        before = dict(task)
        task["title"] = str(task.get("title") or "未命名任务")
        task["subject"] = str(task.get("subject") or "other")
        task["reason"] = str(task.get("reason") or "今天先收起来，避免睡前硬补。")
        task["suggested_next_time"] = str(task.get("suggested_next_time") or task.get("suggested_next_step") or "明天或下一次精力较好时再做一个最小步骤")
        if task != before:
            repaired_count += 1
        normalized.append(task)
    return normalized, repaired_count


def safety_policy_checks(text: str) -> list[dict[str, Any]]:
    categories = {
        "blame_or_shame": [word for word in BLAME_WORDS if word in text],
        "admission_guarantee_or_threat": [word for word in GUARANTEE_OR_THREAT_WORDS if word in text],
        "diagnosis": [word for word in DIAGNOSIS_WORDS if word in text],
        "peer_comparison": [word for word in PEER_COMPARISON_WORDS if word in text],
    }
    found = {name: words for name, words in categories.items() if words}
    if not found:
        return [RuleCheck("child_safety_language", "儿童场景安全表达", True, "info", "未发现责备、恐吓、诊断或同伴比较表达。").to_dict()]
    detail = "；".join(f"{name}: {', '.join(words)}" for name, words in found.items())
    return [
        RuleCheck(
            "child_safety_language",
            "儿童场景安全表达",
            False,
            "error",
            f"发现不适合直接展示给孩子的表达：{detail}",
            issue_code=ISSUE_UNSAFE_LANGUAGE,
            repair_hint="Replace unsafe child-facing wording with neutral encouragement and actionable next step.",
            blocking=True,
        ).to_dict()
    ]


def sanitize_child_facing_text(text: str, *, fallback: str = SAFE_CHILD_FALLBACK) -> str:
    if not text:
        return text
    unsafe = any(not check.get("passed") for check in safety_policy_checks(text))
    return fallback if unsafe else text


def _sanitize_plan_text_fields(plan: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    sanitized = dict(plan)
    changed = False
    child_fields = ["plan_title", "burden_reduction_note", "why_this_is_enough", "child_message"]
    parent_fields = ["parent_explanation"]
    for field in child_fields:
        if isinstance(sanitized.get(field), str):
            safe_value = sanitize_child_facing_text(str(sanitized[field]))
            changed = changed or safe_value != sanitized[field]
            sanitized[field] = safe_value
    for field in parent_fields:
        if isinstance(sanitized.get(field), str):
            safe_value = sanitize_child_facing_text(str(sanitized[field]), fallback=SAFE_PARENT_FALLBACK)
            changed = changed or safe_value != sanitized[field]
            sanitized[field] = safe_value
    return sanitized, changed


def ensure_plan_compliance(plan: dict[str, Any], *, child_input: str = "", now_dt: datetime | None = None, config: AppConfig | None = None) -> tuple[dict[str, Any], list[dict[str, Any]], list[dict[str, Any]]]:
    cfg = config or get_config()
    repaired = dict(plan)
    trace: list[dict[str, Any]] = []

    energy_level = str(repaired.get("energy_level") or infer_energy_level(child_input, default=ENERGY_NORMAL))
    if energy_level not in {ENERGY_NORMAL, ENERGY_TIRED, ENERGY_EXHAUSTED}:
        energy_level = infer_energy_level(child_input, default=ENERGY_NORMAL)

    available_minutes = repaired.get("available_minutes")
    if not isinstance(available_minutes, int):
        available_minutes = parse_available_minutes(child_input)

    energy_cap = max_minutes_for_energy(energy_level, cfg)
    final_cap = min(available_minutes, energy_cap) if available_minutes else energy_cap
    repaired["energy_level"] = energy_level
    repaired["available_minutes"] = available_minutes or final_cap
    repaired["time_cap_minutes"] = final_cap

    tasks = list(repaired.get("tasks", []) or [])
    deferred_tasks = list(repaired.get("deferred_tasks", []) or [])
    original_task_count = len(tasks)
    original_total_minutes = repaired.get("total_minutes")
    checks: list[RuleCheck] = []

    if original_task_count > 6:
        checks.append(
            RuleCheck(
                "task_count_reduce_threshold",
                "任务超过 6 个必须减负",
                False,
                "warning",
                f"原计划包含 {original_task_count} 个任务，已触发减负。",
                issue_code=ISSUE_CORE_TASK_LIMIT_EXCEEDED,
                repair_hint="Keep at most 3 core tasks and move overflow tasks into deferred_tasks.",
            )
        )
        trace.append({"step": "reduce_many_tasks", "detail": f"任务数 {original_task_count} > 6，优先保留高优先级任务。"})
        tasks = sorted(tasks, key=_priority_rank)
        overflow = tasks[cfg.max_core_tasks_weekday :]
        tasks = tasks[: cfg.max_core_tasks_weekday]
        deferred_tasks.extend(_move_to_deferred(task, "今天任务过多，按减负规则延期") for task in overflow)
    else:
        checks.append(RuleCheck("task_count_reduce_threshold", "任务超过 6 个必须减负", True, "info", f"原计划任务数为 {original_task_count}。"))

    if len(tasks) > cfg.max_core_tasks_weekday:
        overflow = tasks[cfg.max_core_tasks_weekday :]
        tasks = tasks[: cfg.max_core_tasks_weekday]
        deferred_tasks.extend(_move_to_deferred(task, "工作日最多保留 3 个核心任务") for task in overflow)
        checks.append(
            RuleCheck(
                "max_core_tasks_weekday",
                "工作日最多 3 个核心任务",
                False,
                "warning",
                "已把超过 3 个的任务移入延期列表。",
                issue_code=ISSUE_CORE_TASK_LIMIT_EXCEEDED,
                repair_hint="Move all tasks after the first 3 into deferred_tasks with child-friendly reasons.",
            )
        )
        trace.append({"step": "cap_core_tasks", "detail": f"任务数压缩到 {cfg.max_core_tasks_weekday} 个以内。"})
    else:
        checks.append(RuleCheck("max_core_tasks_weekday", "工作日最多 3 个核心任务", True, "info", f"核心任务数为 {len(tasks)}。"))

    after_cutoff = is_after_no_high_intensity_time(now_dt, text=child_input, config=cfg)
    if after_cutoff:
        kept: list[dict[str, Any]] = []
        moved_high = 0
        for task in tasks:
            if str(task.get("intensity", "")).lower() == "high":
                deferred_tasks.append(_move_to_deferred(task, f"晚上 {cfg.no_high_intensity_after} 后不安排高强度学习"))
                moved_high += 1
            else:
                kept.append(task)
        tasks = kept
        checks.append(
            RuleCheck(
                "no_high_intensity_after",
                f"晚上 {cfg.no_high_intensity_after} 后不安排高强度学习",
                moved_high == 0,
                "warning" if moved_high else "info",
                "已延期高强度任务。" if moved_high else "没有高强度任务。",
                issue_code=ISSUE_DELAYED_TASKS_MISSING if moved_high else None,
                repair_hint="Move late high-intensity work into deferred_tasks with suggested_next_time." if moved_high else None,
            )
        )
        if moved_high:
            trace.append({"step": "defer_high_intensity_after_cutoff", "detail": f"已延期 {moved_high} 个高强度任务。"})
    else:
        checks.append(RuleCheck("no_high_intensity_after", f"晚上 {cfg.no_high_intensity_after} 后不安排高强度学习", True, "info", "当前不在睡前高强度限制时段，或未识别到晚间时间。"))

    for task in tasks:
        if not has_completion_standard(task):
            task["completion_standard"] = _default_completion_standard(task)
            trace.append({"step": "add_completion_standard", "task": task.get("title", "未命名任务"), "detail": "已补充完成标准。"})

    missing_standard_count = sum(1 for task in tasks if not has_completion_standard(task))
    checks.append(RuleCheck("must_have_completion_standard", "每个任务必须有完成标准", missing_standard_count == 0, "error" if missing_standard_count else "info", "所有任务已有完成标准。" if missing_standard_count == 0 else f"{missing_standard_count} 个任务缺少完成标准。"))

    current_total = total_task_minutes(tasks)
    if current_total > final_cap:
        trace.append({"step": "reduce_total_minutes", "detail": f"原总时长 {current_total} 分钟超过上限 {final_cap} 分钟。"})
        tasks = _reduce_minutes_to_cap(tasks, final_cap, deferred_tasks)

    final_total = total_task_minutes(tasks)
    try:
        model_total = int(original_total_minutes)
    except (TypeError, ValueError):
        model_total = None
    if model_total is not None and model_total != final_total:
        checks.append(
            RuleCheck(
                "model_total_minutes_consistency",
                "模型总时长必须等于任务时长合计",
                False,
                "warning",
                f"模型给出的 total_minutes 为 {model_total}，代码已改为 {final_total}。",
                issue_code=ISSUE_TIME_SUM_INCONSISTENT,
                repair_hint="Overwrite total_minutes with sum(tasks[].minutes) after deterministic repairs.",
            )
        )
    checks.append(RuleCheck("total_minutes_lte_cap", "总时间不能超过可用时间和精力上限", final_total <= final_cap, "error" if final_total > final_cap else "info", f"总时长 {final_total} 分钟，上限 {final_cap} 分钟。"))

    if final_total < final_cap:
        repaired["buffer_minutes"] = final_cap - final_total
    repaired["tasks"] = tasks
    repaired["deferred_tasks"] = deferred_tasks
    repaired["total_minutes"] = final_total

    if not repaired.get("burden_reduction_note"):
        repaired["burden_reduction_note"] = f"今天按 {energy_level} 状态控制在 {final_cap} 分钟以内，未完成内容可以进入延期列表，不需要补偿式加量。"
    if not repaired.get("why_this_is_enough"):
        repaired["why_this_is_enough"] = "今天只完成最关键的少量任务即可。能稳定完成，比临时做很多更重要。"
    if not repaired.get("parent_explanation"):
        repaired["parent_explanation"] = "本计划优先保护孩子的持续性和睡前状态，未完成任务会记录到 pending_tasks，不会简单消失，也不建议今晚补偿式加量。"

    deferred_tasks, repaired_deferred_count = _normalize_deferred_tasks(deferred_tasks)
    if repaired_deferred_count:
        checks.append(
            RuleCheck(
                "deferred_tasks_are_structured",
                "延期任务必须有原因和下一步",
                False,
                "warning",
                f"已补齐 {repaired_deferred_count} 个延期任务的 title/reason/suggested_next_time。",
                issue_code=ISSUE_DELAYED_TASKS_MISSING,
                repair_hint="Every deferred task must include title, reason and suggested_next_time.",
            )
        )
    repaired["deferred_tasks"] = deferred_tasks

    repaired, safety_changed = _sanitize_plan_text_fields(repaired)
    safety_text = " ".join(str(repaired.get(field, "")) for field in ["plan_title", "burden_reduction_note", "why_this_is_enough", "parent_explanation", "child_message"])
    safety_checks = safety_policy_checks(safety_text)
    if safety_changed:
        checks.append(
            RuleCheck(
                "child_safety_language_repaired",
                "儿童场景安全表达",
                False,
                "warning",
                "已替换不适合直接展示给孩子的表达。",
                issue_code=ISSUE_UNSAFE_LANGUAGE,
                repair_hint="Use warm, non-comparative wording and avoid guarantees, threats or diagnosis.",
            )
        )
    elif safety_checks:
        checks.extend(RuleCheck(**check) for check in safety_checks if check.get("severity") == "error")

    checks.append(RuleCheck("unfinished_goes_to_pending", "未完成任务应进入 pending_tasks", True, "info", "计划中的延期任务会在复盘后进入 pending_tasks。"))
    return repaired, [check.to_dict() for check in checks], trace


def _reduce_minutes_to_cap(tasks: list[dict[str, Any]], final_cap: int, deferred_tasks: list[dict[str, Any]]) -> list[dict[str, Any]]:
    tasks = [dict(task) for task in tasks]
    tasks.sort(key=_priority_rank)
    while total_task_minutes(tasks) > final_cap and tasks:
        lowest_priority_task = tasks[-1]
        minutes = _task_minutes(lowest_priority_task)
        if minutes > 10:
            lowest_priority_task["minutes"] = max(10, minutes - 5)
        else:
            moved = tasks.pop()
            deferred_tasks.append(_move_to_deferred(moved, "总时长超过今日上限，按减负规则延期"))
    if total_task_minutes(tasks) > final_cap and len(tasks) == 1:
        tasks[0]["minutes"] = final_cap
    return tasks


def validate_no_blame_text(text: str) -> tuple[bool, list[str]]:
    found = [word for word in BLAME_WORDS if word in text]
    return len(found) == 0, found


def reflection_policy_checks(feedback: dict[str, Any], daily_log: dict[str, Any] | None = None) -> list[dict[str, Any]]:
    text = " ".join(str(value) for value in feedback.values() if isinstance(value, (str, int, float)))
    no_blame, found_words = validate_no_blame_text(text)
    checks: list[RuleCheck] = [
        RuleCheck(
            "reflection_no_blame",
            "睡前复盘不得责备孩子",
            no_blame,
            "error" if not no_blame else "info",
            "未发现责备表达。" if no_blame else f"发现可能责备表达：{', '.join(found_words)}",
            issue_code=ISSUE_UNSAFE_LANGUAGE if not no_blame else None,
            repair_hint="Replace blame with encouragement and a light next step." if not no_blame else None,
            blocking=not no_blame,
        )
    ]
    for check in safety_policy_checks(text):
        if not check.get("passed") and check.get("rule_id") != "reflection_no_blame":
            checks.append(RuleCheck(**check))

    if daily_log is not None:
        not_completed = daily_log.get("not_completed", [])
        pending_added = daily_log.get("pending_tasks_added", [])
        if not_completed:
            passed = len(pending_added) > 0
            detail = "未完成任务已进入 pending_tasks。" if passed else "存在未完成任务，但没有 pending_tasks_added。"
        else:
            passed = True
            detail = "本次复盘没有未完成任务。"
        checks.append(RuleCheck("unfinished_goes_to_pending", "未完成任务应进入 pending_tasks", passed, "error" if not passed else "info", detail))
    return [check.to_dict() for check in checks]
