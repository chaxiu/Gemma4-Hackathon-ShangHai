from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Optional

import httpx

from backend.config import AppConfig, get_config
from backend.json_utils import coerce_json_object, loads_json_relaxed


class LLMClientError(RuntimeError):
    pass


@dataclass
class LLMResult:
    text: str
    model: str
    raw: dict[str, Any]
    is_mock: bool = False
    error: Optional[str] = None


class LLMClient:
    """
    LM Studio OpenAI-compatible client.

    默认调用真实 LM Studio API。
    当 STUDYPILOT_USE_MOCK_LLM=true 时，使用本地 mock 响应，便于 UI 演示和 smoke test。
    """

    def __init__(self, config: AppConfig | None = None):
        self.config = config or get_config()

    def health(self) -> dict[str, Any]:
        if self.config.use_mock_llm:
            return {
                "ok": True,
                "mode": "mock",
                "message": "当前使用 Mock LLM。适合演示流程，但不代表真实 Gemma 4 能力。",
                "base_url": self.config.lm_studio_base_url,
                "model": self.config.model_name,
            }

        try:
            with httpx.Client(timeout=5, trust_env=False) as client:
                response = client.get(self.config.lm_models_url)
                response.raise_for_status()
                payload = response.json()
            return {
                "ok": True,
                "mode": "lm_studio",
                "message": "LM Studio API 可访问。",
                "base_url": self.config.lm_studio_base_url,
                "model": self.config.model_name,
                "models": payload.get("data", payload),
            }
        except Exception as exc:
            return {
                "ok": False,
                "mode": "lm_studio",
                "message": f"无法访问 LM Studio API：{exc}",
                "base_url": self.config.lm_studio_base_url,
                "model": self.config.model_name,
            }

    def chat(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> LLMResult:
        if self.config.use_mock_llm:
            return self._mock_chat(messages)

        payload = {
            "model": self.config.model_name,
            "messages": messages,
            "temperature": self.config.llm_temperature if temperature is None else temperature,
            "max_tokens": self.config.llm_max_tokens if max_tokens is None else max_tokens,
            "stream": False,
        }

        try:
            with httpx.Client(timeout=self.config.llm_timeout_seconds, trust_env=False) as client:
                response = client.post(self.config.lm_chat_completions_url, json=payload)
                response.raise_for_status()
                data = response.json()

            text = data["choices"][0]["message"]["content"]
            return LLMResult(text=text, model=data.get("model", self.config.model_name), raw=data, is_mock=False)
        except Exception as exc:
            if self.config.llm_fallback_to_mock:
                mock = self._mock_chat(messages)
                mock.error = f"真实 LM Studio 调用失败，已回退 Mock：{exc}"
                return mock
            raise LLMClientError(
                "LM Studio 调用失败。请确认服务已启动，或在 .env 中设置 "
                f"STUDYPILOT_USE_MOCK_LLM=true。错误：{exc}"
            ) from exc

    def chat_json(
        self,
        messages: list[dict[str, str]],
        *,
        temperature: float | None = None,
        max_tokens: int | None = None,
    ) -> tuple[dict[str, Any], LLMResult]:
        result = self.chat(messages, temperature=temperature, max_tokens=max_tokens)
        parsed = loads_json_relaxed(result.text)
        return coerce_json_object(parsed, name="LLM JSON output"), result

    def _mock_chat(self, messages: list[dict[str, str]]) -> LLMResult:
        joined = "\n".join(message.get("content", "") for message in messages)
        prompt_id = self._detect_prompt_id(joined)
        text = self._mock_response_for_prompt(prompt_id, joined)
        return LLMResult(text=text, model=f"mock::{self.config.model_name}", raw={"mock_prompt_id": prompt_id}, is_mock=True)

    @staticmethod
    def _detect_prompt_id(text: str) -> str:
        for line in text.splitlines():
            if line.startswith("PROMPT_ID:"):
                return line.replace("PROMPT_ID:", "").strip()
        return "UNKNOWN"

    def _mock_response_for_prompt(self, prompt_id: str, joined_prompt: str) -> str:
        if prompt_id == "PARENT_PROFILE_UNDERSTANDING":
            return '''
{
  "student": {"nickname": "小航", "grade": "六年级", "stage": "小升初过渡期", "school_context": "普通小学六年级，正在为初中衔接做准备"},
  "family_goal": {"primary_goal": "进入初中后数学不要明显掉队", "secondary_goal": "保持稳定心态，形成轻量学习闭环", "avoid": ["每天安排过满", "因为未完成任务责备孩子", "持续焦虑"]},
  "subjects": {
    "math": {"status": "需要重点支持", "strengths": ["能完成基础题"], "weaknesses": ["应用题", "行程问题", "百分比应用题"], "preferred_method": "短题组，先圈关键词，再写数量关系", "risk_notes": "一次安排太多数学题容易挫败"},
    "english": {"status": "有拖延风险", "strengths": ["单词基础尚可"], "weaknesses": ["听力拖延"], "preferred_method": "8-12 分钟短听力，完成后说出关键词", "risk_notes": "听力不宜放到睡前最后一项"},
    "chinese": {"status": "相对稳定", "strengths": ["背诵还可以"], "weaknesses": ["背诵量大时会拖慢节奏"], "preferred_method": "分段背诵，睡前轻量回顾", "risk_notes": "疲惫时不宜安排大量新背内容"}
  },
  "weekly_schedule": [
    {"day": "周二", "fixed_event": "篮球", "learning_note": "当天应减负"},
    {"day": "周三", "fixed_event": "英语班", "learning_note": "不再额外安排过多英语"},
    {"day": "周四", "fixed_event": "篮球", "learning_note": "当天应减负"}
  ],
  "burden_rules": {"normal_day_max_minutes": 60, "tired_day_max_minutes": 40, "exhausted_day_max_minutes": 25, "max_core_tasks_weekday": 3, "task_count_reduce_threshold": 6, "no_high_intensity_after": "21:30", "must_have_completion_standard": true, "unfinished_goes_to_pending": true},
  "communication_style": {"tone": "温和、具体、鼓励", "child_facing": "先肯定已完成部分，再给一个很小的下一步", "parent_facing": "解释为什么今天这样安排已经够了，避免补偿式加量"},
  "rag_summary": "小航六年级，正在小升初过渡。数学应用题较弱，英语听力有拖延风险，语文背诵相对稳定。周二周四篮球，周三英语班。正常不超过60分钟，疲惫不超过40分钟。",
  "confidence": 0.86,
  "needs_parent_confirmation": true
}
'''

        if prompt_id == "PROFILE_MARKDOWN_GENERATION":
            return '''
{
  "markdown": "# 轻舟学伴学习档案：小航\n\n## 基本情况\n\n小航六年级，处于小升初过渡期。家庭目标是数学进入初中后不要明显掉队，同时保持稳定心态。\n\n## 学科画像\n\n### 数学\n\n应用题、行程问题和百分比应用题需要重点支持。建议短题组、圈关键词、写数量关系。\n\n### 英语\n\n听力容易拖延，建议 8-12 分钟短听力，并设置明确完成标准。\n\n### 语文\n\n背诵相对稳定，适合分段背诵和睡前轻量回顾。\n\n## 固定安排\n\n周二、周四篮球；周三英语班。这几天应自动减负。\n\n## 减负规则\n\n工作日最多 3 个核心任务。正常不超过 60 分钟，疲惫不超过 40 分钟，很疲惫不超过 25 分钟。晚上 21:30 后不安排高强度学习。\n\n## RAG 摘要\n\n数学应用题与英语听力是主要关注点，计划必须轻量、明确、可完成。"
}
'''

        if prompt_id == "RAG_CHUNK_GENERATION":
            return '''
{
  "chunks": [
    {"chunk_id": "profile_overview_001", "source_type": "student_profile", "title": "学生基本画像与家庭目标", "content": "小航六年级，处于小升初过渡期。家庭目标是数学不掉队，同时保持稳定心态，不希望每天安排过满。", "keywords": ["六年级", "小升初", "数学不掉队", "减负"], "metadata": {"category": "overview"}, "embedding": null},
    {"chunk_id": "subject_math_001", "source_type": "student_profile", "title": "数学薄弱点", "content": "数学应用题、行程问题和百分比应用题需要重点支持。适合短题组、圈关键词、写数量关系。", "keywords": ["数学", "应用题", "行程问题", "百分比"], "metadata": {"subject": "math"}, "embedding": null},
    {"chunk_id": "subject_english_001", "source_type": "student_profile", "title": "英语听力拖延", "content": "英语听力容易拖延，建议 8-12 分钟短听力，完成后说出 2 个关键词。", "keywords": ["英语", "听力", "拖延", "短听力"], "metadata": {"subject": "english"}, "embedding": null},
    {"chunk_id": "rules_001", "source_type": "rule_profile", "title": "减负规则", "content": "工作日最多 3 个核心任务，正常最多 60 分钟，疲惫最多 40 分钟，很疲惫最多 25 分钟，晚上 21:30 后不安排高强度学习。", "keywords": ["减负", "3个核心任务", "60分钟", "40分钟", "25分钟", "21:30"], "metadata": {"category": "rules"}, "embedding": null}
  ]
}
'''

        if prompt_id == "AFTER_SCHOOL_INPUT_UNDERSTANDING":
            energy = "tired" if any(x in joined_prompt for x in ["累", "困", "篮球", "不想做"]) else "unknown"
            minutes = 25 if "25" in joined_prompt else 40 if "40" in joined_prompt else 60 if "60" in joined_prompt else None
            return f'''
{{
  "available_minutes": {"null" if minutes is None else minutes},
  "energy_level": "{energy}",
  "mentioned_tasks": [
    {{"raw": "语文", "subject": "chinese", "task_type": "unknown", "estimated_difficulty": "medium"}},
    {{"raw": "数学", "subject": "math", "task_type": "practice", "estimated_difficulty": "high"}},
    {{"raw": "英语", "subject": "english", "task_type": "listening", "estimated_difficulty": "medium"}},
    {{"raw": "预习", "subject": "other", "task_type": "preview", "estimated_difficulty": "medium"}}
  ],
  "confusion": "不知道先做什么",
  "needs_follow_up": true,
  "missing_info": ["哪些任务明天必须交", "今天累不累"],
  "risk_flags": ["时间有限", "多任务"]
}}
'''

        if prompt_id == "ACTIVE_FOLLOW_UP_QUESTION":
            return '''
{
  "need_questions": true,
  "questions": [
    {"question": "这些任务里，哪一个是明天一定要交的？", "why": "决定优先级，避免把不急的任务塞进今天", "answer_type": "short_text"},
    {"question": "你现在是正常、有点累，还是很累？", "why": "决定今天最多安排 60、40 还是 25 分钟", "answer_type": "choice"}
  ],
  "can_plan_without_answers": true
}
'''

        if prompt_id == "TASK_CLASSIFICATION":
            return '''
{
  "tasks": [
    {"title": "数学应用题短练", "subject": "math", "task_type": "practice", "priority": "high", "intensity": "medium", "can_defer": false, "reason": "数学应用题是长期薄弱点，但今天只做短练", "completion_standard_hint": "完成 2 道题，并圈出关键词"},
    {"title": "英语听力短任务", "subject": "english", "task_type": "listening", "priority": "medium", "intensity": "low", "can_defer": false, "reason": "英语听力有拖延风险，适合前置为短任务", "completion_standard_hint": "听 8-10 分钟，说出 2 个关键词"},
    {"title": "语文背诵轻量回顾", "subject": "chinese", "task_type": "recitation", "priority": "medium", "intensity": "low", "can_defer": true, "reason": "语文相对稳定，可轻量完成", "completion_standard_hint": "背一小段或复述大意"},
    {"title": "预习", "subject": "other", "task_type": "preview", "priority": "low", "intensity": "low", "can_defer": true, "reason": "时间有限时可延期", "completion_standard_hint": "看标题和例题，不做额外练习"}
  ],
  "defer_candidates": ["预习"],
  "burden_risk": "medium"
}
'''

        if prompt_id == "TODAY_PLAN_GENERATION":
            return '''
{
  "plan_title": "40分钟轻量放学计划",
  "date": "",
  "available_minutes": 40,
  "energy_level": "tired",
  "tasks": [
    {"title": "数学应用题短练", "subject": "math", "minutes": 16, "intensity": "medium", "priority": "high", "completion_standard": "完成 2 道应用题，并圈出题目关键词", "why_first_or_later": "数学是长期薄弱点，先做短练，不拉长战线", "can_defer": false},
    {"title": "英语听力短任务", "subject": "english", "minutes": 10, "intensity": "low", "priority": "medium", "completion_standard": "听 8-10 分钟，并说出 2 个听到的关键词", "why_first_or_later": "听力容易拖延，今天做成短任务即可", "can_defer": false},
    {"title": "语文背诵轻量回顾", "subject": "chinese", "minutes": 10, "intensity": "low", "priority": "medium", "completion_standard": "背一小段或复述大意，不追求整篇全背", "why_first_or_later": "语文相对稳定，轻量收尾", "can_defer": true}
  ],
  "deferred_tasks": [{"title": "预习", "reason": "今天只有 40 分钟，预习可以延期，不影响今日核心闭环", "suggested_next_time": "明天放学后先看标题和例题 5 分钟"}],
  "burden_reduction_note": "今天不补偿式加量，只保留 3 个核心动作。",
  "why_this_is_enough": "数学碰到薄弱点、英语没有继续拖延、语文做轻量回顾，今天就已经完成关键目标。",
  "parent_explanation": "今天计划控制在 40 分钟内，重点是稳定完成而不是多做。预习延期是为了避免孩子在疲惫时过载。",
  "child_message": "今天不用全都做完，先把最关键的三件小事做好就可以。"
}
'''

        if prompt_id == "PLAN_REPAIR":
            return '''
{"plan_title":"修复后的轻量计划","date":"","available_minutes":40,"energy_level":"tired","tasks":[{"title":"数学应用题短练","subject":"math","minutes":15,"intensity":"medium","priority":"high","completion_standard":"完成 2 道应用题，并圈出关键词","why_first_or_later":"保留最关键的薄弱点练习","can_defer":false},{"title":"英语听力短任务","subject":"english","minutes":10,"intensity":"low","priority":"medium","completion_standard":"听 8-10 分钟，说出 2 个关键词","why_first_or_later":"防止听力继续拖延","can_defer":false},{"title":"语文轻量回顾","subject":"chinese","minutes":10,"intensity":"low","priority":"medium","completion_standard":"背一小段或复述大意","why_first_or_later":"低压力收尾","can_defer":true}],"deferred_tasks":[{"title":"预习","reason":"时间有限，今日不塞满","suggested_next_time":"明天 5 分钟浏览"}],"burden_reduction_note":"已按规则压缩到 35 分钟，留出缓冲。","why_this_is_enough":"今天完成关键薄弱点和拖延项即可。","parent_explanation":"计划已减负，避免在疲惫状态下补偿式加量。","child_message":"做完这三小步就可以收工。"}
'''

        if prompt_id == "REFLECTION_UNDERSTANDING":
            return '''
{
  "completed": [{"title": "数学", "subject": "math", "evidence": "数学做完了"}],
  "partially_completed": [{"title": "语文背诵", "subject": "chinese", "evidence": "语文背了一半", "remaining": "另一半未完成"}],
  "not_completed": [{"title": "英语听力", "subject": "english", "reason": "孩子说没做"}],
  "new_weaknesses": ["数学有一道题卡住"],
  "energy_level": "tired",
  "mood_signal": "有点累",
  "completion_rate_estimate": 0.55,
  "pending_tasks_to_add": [
    {"title": "英语听力短任务", "subject": "english", "reason": "今日未完成，且历史上容易拖延", "suggested_next_step": "明天听 8-10 分钟，说出 2 个关键词"},
    {"title": "语文背诵剩余部分", "subject": "chinese", "reason": "今日只完成一半", "suggested_next_step": "明天只补一小段，不整篇重来"}
  ],
  "risk_flags": ["英语听力继续拖延", "数学题卡住"]
}
'''

        if prompt_id == "REFLECTION_ENCOURAGEMENT":
            return '''
{
  "child_feedback": "今天数学能做完已经很不错，说明你把最难开始的一项先推进了。",
  "encouragement": "英语听力没做也没关系，我们把它变成明天一个很短的小任务，不用今晚补。",
  "tomorrow_light_suggestion": "明天先做 8-10 分钟英语听力，听完说出 2 个关键词就算完成。",
  "parent_note": "今晚不建议再补任务。孩子已经有疲惫信号，保留未完成项到明天更利于持续。",
  "closed_loop_status": "partial"
}
'''

        if prompt_id == "PROFILE_UPDATE":
            return '''
{
  "profile_patch": {
    "pending_tasks_add": [{"title": "英语听力短任务", "subject": "english", "reason": "睡前复盘显示今日未完成，且历史上容易拖延", "suggested_next_step": "明天听 8-10 分钟，说出 2 个关键词", "priority": "medium"}],
    "learning_history_add": {"summary": "数学完成，英语听力未完成，语文背诵完成一半。数学有一道题卡住，孩子有点累。", "completion_rate": 0.55, "energy_level": "tired", "new_weaknesses": ["数学题卡住"]},
    "energy_trend_add": {"energy_level": "tired"},
    "procrastination_signals_add_or_update": [{"subject": "english", "task_type": "listening", "signal": "英语听力再次未完成", "current_strategy": "放到明天前半段，控制在 8-10 分钟"}],
    "rag_summary_update": "最新复盘显示：数学能完成但仍有卡题；英语听力继续有拖延风险；疲惫时应继续减负。"
  },
  "update_reason": "睡前复盘产生了未完成任务、精力状态和新薄弱点。",
  "needs_parent_review": false
}
'''

        if prompt_id == "EVAL_SCORING":
            return '''
{"score":0.8,"passed":true,"passed_rules":["no_blame","has_completion_standard","burden_reduction"],"failed_rules":[],"failure_reasons":[],"capability_boundary_note":"Mock 评分只用于演示，真实评测应结合规则引擎和人工抽查。"}
'''

        return '{"message":"mock response","note":"未识别 Prompt ID，返回通用 JSON。"}'
