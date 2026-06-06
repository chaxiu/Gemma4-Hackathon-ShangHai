from __future__ import annotations

from typing import Any

from backend.json_utils import compact_for_prompt, json_dumps_cn


JSON_ONLY = "只输出一个 JSON 对象。不要输出 Markdown 代码块。不要解释。"
SAFETY_LINE = "不要心理诊断，不要责备孩子，不要承诺升学结果，不要把任务排满。"
MODEL_HINT = "你在本地 Gemma 4 26B-A4B Instruct 量化模型上运行。指令要短，结论要稳。"


def _system(prompt_id: str, task: str) -> str:
    return f"""PROMPT_ID: {prompt_id}
{MODEL_HINT}
你的任务：{task}
{SAFETY_LINE}
{JSON_ONLY}"""


def parent_background_understanding_messages(parent_text: str) -> list[dict[str, str]]:
    system = _system("PARENT_PROFILE_UNDERSTANDING", "把家长自然语言背景理解为结构化学习档案草案。")
    user = f"""请阅读家长输入，抽取事实和偏好。未知就写 unknown，不要编造。

输出字段：
{{
  "student": {{"nickname": "unknown", "grade": "", "stage": ""}},
  "family_goal": {{"primary_goal": "", "secondary_goal": "", "avoid": []}},
  "subjects": {{
    "math": {{"status": "", "strengths": [], "weaknesses": [], "preferred_method": "", "risk_notes": ""}},
    "english": {{"status": "", "strengths": [], "weaknesses": [], "preferred_method": "", "risk_notes": ""}},
    "chinese": {{"status": "", "strengths": [], "weaknesses": [], "preferred_method": "", "risk_notes": ""}}
  }},
  "weekly_schedule": [],
  "burden_rules": {{
    "normal_day_max_minutes": 60,
    "tired_day_max_minutes": 40,
    "exhausted_day_max_minutes": 25,
    "max_core_tasks_weekday": 3,
    "task_count_reduce_threshold": 6,
    "no_high_intensity_after": "21:30",
    "must_have_completion_standard": true,
    "unfinished_goes_to_pending": true
  }},
  "communication_style": {{"tone": "", "child_facing": "", "parent_facing": ""}},
  "rag_summary": "",
  "confidence": 0.0,
  "needs_parent_confirmation": true
}}

示例输入：
孩子五年级，英语阅读弱，周三有钢琴，希望每天不超过45分钟。
示例输出：
{{
  "student": {{"nickname": "unknown", "grade": "五年级", "stage": "小学高年级"}},
  "family_goal": {{"primary_goal": "提高英语阅读稳定性", "secondary_goal": "保持可持续学习", "avoid": ["每天安排过满"]}},
  "subjects": {{
    "math": {{"status": "unknown", "strengths": [], "weaknesses": [], "preferred_method": "unknown", "risk_notes": "unknown"}},
    "english": {{"status": "需要支持", "strengths": [], "weaknesses": ["阅读"], "preferred_method": "短篇阅读加一句复述", "risk_notes": "任务过长会拖延"}},
    "chinese": {{"status": "unknown", "strengths": [], "weaknesses": [], "preferred_method": "unknown", "risk_notes": "unknown"}}
  }},
  "weekly_schedule": [{{"day": "周三", "fixed_event": "钢琴", "learning_note": "当天应减少学习量"}}],
  "burden_rules": {{"normal_day_max_minutes": 45, "tired_day_max_minutes": 40, "exhausted_day_max_minutes": 25, "max_core_tasks_weekday": 3, "task_count_reduce_threshold": 6, "no_high_intensity_after": "21:30", "must_have_completion_standard": true, "unfinished_goes_to_pending": true}},
  "communication_style": {{"tone": "温和具体", "child_facing": "先肯定再给小步骤", "parent_facing": "解释为什么不加量"}},
  "rag_summary": "五年级学生，英语阅读弱，周三钢琴，当天需减负，每天不超过45分钟。",
  "confidence": 0.78,
  "needs_parent_confirmation": true
}}

家长输入：
{parent_text}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def profile_markdown_generation_messages(profile: dict[str, Any]) -> list[dict[str, str]]:
    system = _system("PROFILE_MARKDOWN_GENERATION", "把结构化学习档案转成给家长可读的 Markdown 档案。")
    user = f"""请根据 JSON 生成 Markdown 档案。

输出：
{{"markdown": "# 轻舟学伴学习档案：..."}}

要求：
- 标题清楚
- 分为基本情况、学科画像、固定安排、减负规则、pending tasks、RAG 摘要
- 不要增加 JSON 中没有的关键事实

学习档案 JSON：
{compact_for_prompt(profile)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def rag_chunk_generation_messages(profile: dict[str, Any], markdown: str) -> list[dict[str, str]]:
    system = _system("RAG_CHUNK_GENERATION", "把学习档案切分成适合关键词检索的 RAG chunks。")
    user = f"""请生成 RAG chunks。

输出：
{{
  "chunks": [
    {{
      "chunk_id": "profile_overview_001",
      "source_type": "student_profile",
      "title": "",
      "content": "",
      "keywords": [],
      "metadata": {{}},
      "embedding": null
    }}
  ]
}}

要求：
- 5 到 10 个 chunk
- 每个 chunk 只讲一个主题
- keywords 包含中文关键词
- embedding 固定为 null
- 不要做向量计算

学习档案 JSON：
{compact_for_prompt(profile, max_chars=4000)}

Markdown：
{markdown[:4000]}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def after_school_understanding_messages(child_input: str) -> list[dict[str, str]]:
    system = _system("AFTER_SCHOOL_INPUT_UNDERSTANDING", "理解孩子放学后的任务、时间、精力和困惑。")
    user = f"""从孩子输入中抽取今日状态。不要安排计划，只做理解。

输出：
{{
  "available_minutes": null,
  "energy_level": "normal|tired|exhausted|unknown",
  "mentioned_tasks": [
    {{"raw": "", "subject": "", "task_type": "", "estimated_difficulty": "low|medium|high|unknown"}}
  ],
  "confusion": "",
  "needs_follow_up": true,
  "missing_info": [],
  "risk_flags": []
}}

示例输入：我今天只有40分钟，有数学英语，还很累。
示例输出：
{{
  "available_minutes": 40,
  "energy_level": "tired",
  "mentioned_tasks": [
    {{"raw": "数学", "subject": "math", "task_type": "unknown", "estimated_difficulty": "medium"}},
    {{"raw": "英语", "subject": "english", "task_type": "unknown", "estimated_difficulty": "medium"}}
  ],
  "confusion": "不知道先做什么",
  "needs_follow_up": true,
  "missing_info": ["任务具体内容"],
  "risk_flags": ["时间有限", "疲惫"]
}}

孩子输入：
{child_input}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def active_question_messages(child_input: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]]) -> list[dict[str, str]]:
    system = _system("ACTIVE_FOLLOW_UP_QUESTION", "决定是否主动追问，并生成 1-3 个关键问题。")
    user = f"""只追问能显著影响今日计划的问题。不要问太多。

输出：
{{
  "need_questions": true,
  "questions": [
    {{"question": "", "why": "", "answer_type": "short_text|choice|number"}}
  ],
  "can_plan_without_answers": true
}}

判断原则：
- 已有可用时间、精力、任务大类时，可以先计划
- 缺少任务优先级或作业是否明天交，可以追问
- 最多 3 个问题

孩子输入：
{child_input}

理解结果：
{compact_for_prompt(understanding)}

RAG 依据：
{compact_for_prompt(rag_context, max_chars=2500)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def task_classification_messages(child_input: str, understanding: dict[str, Any], rag_context: list[dict[str, Any]]) -> list[dict[str, str]]:
    system = _system("TASK_CLASSIFICATION", "把今日任务分类为核心任务、轻量任务和可延期任务候选。")
    user = f"""请分类，不做精确时间计算。时间由规则引擎处理。

输出：
{{
  "tasks": [
    {{
      "title": "",
      "subject": "math|english|chinese|other",
      "task_type": "",
      "priority": "high|medium|low",
      "intensity": "low|medium|high",
      "can_defer": false,
      "reason": "",
      "completion_standard_hint": ""
    }}
  ],
  "defer_candidates": [],
  "burden_risk": "low|medium|high"
}}

孩子输入：
{child_input}

理解结果：
{compact_for_prompt(understanding)}

RAG 依据：
{compact_for_prompt(rag_context, max_chars=2500)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def today_plan_generation_messages(child_input: str, understanding: dict[str, Any], classified_tasks: dict[str, Any], rag_context: list[dict[str, Any]], rule_summary: dict[str, Any]) -> list[dict[str, str]]:
    system = _system("TODAY_PLAN_GENERATION", "生成放学后今日计划草案。不要做复杂时间计算，必须给完成标准。")
    user = f"""请生成今日计划草案。规则引擎稍后会校验和修复时间。

输出：
{{
  "plan_title": "",
  "date": "",
  "available_minutes": null,
  "energy_level": "",
  "tasks": [
    {{
      "title": "",
      "subject": "",
      "minutes": 10,
      "intensity": "low|medium|high",
      "priority": "high|medium|low",
      "completion_standard": "",
      "why_first_or_later": "",
      "can_defer": false
    }}
  ],
  "deferred_tasks": [
    {{"title": "", "reason": "", "suggested_next_time": ""}}
  ],
  "burden_reduction_note": "",
  "why_this_is_enough": "",
  "parent_explanation": "",
  "child_message": ""
}}

硬要求：
- 工作日最多 3 个核心任务
- 每个任务必须有 completion_standard
- 不要把任务排满
- 未确定时倾向减负
- 不责备孩子

孩子输入：
{child_input}

理解结果：
{compact_for_prompt(understanding)}

任务分类：
{compact_for_prompt(classified_tasks)}

RAG 依据：
{compact_for_prompt(rag_context, max_chars=2500)}

规则摘要：
{compact_for_prompt(rule_summary)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def plan_repair_messages(plan: dict[str, Any], rule_checks: list[dict[str, Any]], rule_summary: dict[str, Any]) -> list[dict[str, str]]:
    system = _system("PLAN_REPAIR", "根据规则校验结果修复今日计划。")
    user = f"""请修复计划，只调整必要部分。输出字段与输入 plan 保持一致。

修复优先级：
1. 总时间不能超过上限
2. 核心任务不能超过 3 个
3. 每个任务必须有完成标准
4. 晚上 21:30 后不安排高强度学习
5. 可以延期，不要强行塞满

原计划：
{compact_for_prompt(plan)}

规则校验：
{compact_for_prompt(rule_checks)}

规则摘要：
{compact_for_prompt(rule_summary)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def reflection_understanding_messages(reflection_input: str, latest_plan: dict[str, Any] | None) -> list[dict[str, str]]:
    system = _system("REFLECTION_UNDERSTANDING", "理解睡前复盘，记录完成、未完成、卡点和精力。")
    user = f"""请理解孩子复盘。不要责备，不要加任务。

输出：
{{
  "completed": [{{"title": "", "subject": "", "evidence": ""}}],
  "partially_completed": [{{"title": "", "subject": "", "evidence": "", "remaining": ""}}],
  "not_completed": [{{"title": "", "subject": "", "reason": ""}}],
  "new_weaknesses": [],
  "energy_level": "normal|tired|exhausted|unknown",
  "mood_signal": "",
  "completion_rate_estimate": 0.0,
  "pending_tasks_to_add": [{{"title": "", "subject": "", "reason": "", "suggested_next_step": ""}}],
  "risk_flags": []
}}

孩子睡前输入：
{reflection_input}

今日计划：
{compact_for_prompt(latest_plan or {}, max_chars=3000)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def reflection_encouragement_messages(reflection_input: str, reflection_understanding: dict[str, Any], rag_context: list[dict[str, Any]]) -> list[dict[str, str]]:
    system = _system("REFLECTION_ENCOURAGEMENT", "生成睡前复盘反馈、鼓励和明日轻量建议。")
    user = f"""请输出温和、简短、具体的反馈。

输出：
{{
  "child_feedback": "",
  "encouragement": "",
  "tomorrow_light_suggestion": "",
  "parent_note": "",
  "closed_loop_status": "completed|partial|needs_light_followup"
}}

要求：
- 先肯定已完成部分
- 未完成也不责备
- 明日建议只能是轻量动作
- 不说“你应该更努力”这类话

孩子输入：
{reflection_input}

理解结果：
{compact_for_prompt(reflection_understanding)}

RAG 依据：
{compact_for_prompt(rag_context, max_chars=2500)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def profile_update_messages(current_profile: dict[str, Any], reflection_understanding: dict[str, Any], daily_log: dict[str, Any]) -> list[dict[str, str]]:
    system = _system("PROFILE_UPDATE", "根据睡前复盘更新学习档案摘要。")
    user = f"""请生成学习档案更新建议。不要删除未完成任务。

输出：
{{
  "profile_patch": {{
    "pending_tasks_add": [],
    "learning_history_add": {{}},
    "energy_trend_add": {{}},
    "procrastination_signals_add_or_update": [],
    "rag_summary_update": ""
  }},
  "update_reason": "",
  "needs_parent_review": false
}}

当前学习档案：
{compact_for_prompt(current_profile, max_chars=5000)}

复盘理解：
{compact_for_prompt(reflection_understanding)}

daily_log：
{compact_for_prompt(daily_log)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]


def eval_scoring_messages(case: dict[str, Any], agent_output: dict[str, Any]) -> list[dict[str, str]]:
    system = _system("EVAL_SCORING", "根据评测用例给 Agent 输出打分。")
    user = f"""请判断输出是否满足用例要求。

输出：
{{
  "score": 0.0,
  "passed": false,
  "passed_rules": [],
  "failed_rules": [],
  "failure_reasons": [],
  "capability_boundary_note": ""
}}

评分规则：
- 1.0 表示完全满足
- 0.7 表示主流程满足但有小缺失
- 0.4 表示有关键规则缺失
- 0.0 表示完全不满足或不安全

评测用例：
{json_dumps_cn(case)}

Agent 输出：
{compact_for_prompt(agent_output, max_chars=5000)}
"""
    return [{"role": "system", "content": system}, {"role": "user", "content": user}]
