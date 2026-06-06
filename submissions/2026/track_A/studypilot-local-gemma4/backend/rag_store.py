from __future__ import annotations

import re
from collections import Counter
from typing import Any

from backend.config import AppConfig, get_config
from backend.storage import load_rag_chunks, load_student_profile, load_student_profile_markdown, save_rag_chunks, save_student_profile_markdown


def tokenize(text: str) -> list[str]:
    if not text:
        return []
    lowered = text.lower()
    latin_tokens = re.findall(r"[a-zA-Z0-9_]+", lowered)
    chinese_chars = re.findall(r"[\u4e00-\u9fff]", lowered)
    chinese_bigrams = ["".join(chinese_chars[i : i + 2]) for i in range(max(0, len(chinese_chars) - 1))]
    chinese_trigrams = ["".join(chinese_chars[i : i + 3]) for i in range(max(0, len(chinese_chars) - 2))]
    return latin_tokens + chinese_chars + chinese_bigrams + chinese_trigrams


def _chunk_text(chunk: dict[str, Any]) -> str:
    keywords = chunk.get("keywords") or []
    keyword_text = " ".join(str(item) for item in keywords) if isinstance(keywords, list) else str(keywords)
    return " ".join([str(chunk.get("title", "")), str(chunk.get("content", "")), keyword_text, str(chunk.get("source_type", ""))])


def score_chunk(query: str, chunk: dict[str, Any]) -> float:
    query_tokens = tokenize(query)
    if not query_tokens:
        return 0.0
    chunk_tokens = tokenize(_chunk_text(chunk))
    if not chunk_tokens:
        return 0.0

    query_counter = Counter(query_tokens)
    chunk_counter = Counter(chunk_tokens)
    score = 0.0
    for token, q_count in query_counter.items():
        if token in chunk_counter:
            score += min(q_count, chunk_counter[token]) * 1.0

    query_lower = query.lower()
    content_lower = _chunk_text(chunk).lower()
    for keyword in chunk.get("keywords", []) or []:
        keyword_text = str(keyword).lower()
        if keyword_text and keyword_text in query_lower:
            score += 3.0
    if query_lower and query_lower in content_lower:
        score += 5.0
    return score


class RagStore:
    """
    Local RAG store.

    当前实现为关键词检索；每个 chunk 保留 embedding=null，方便未来接入 Chroma / FAISS / LanceDB。
    """

    def __init__(self, config: AppConfig | None = None):
        self.config = config or get_config()

    def load_chunks(self) -> list[dict[str, Any]]:
        return load_rag_chunks(self.config)

    def save_chunks(self, chunks: list[dict[str, Any]]) -> None:
        save_rag_chunks(chunks, self.config)

    def search(self, query: str, *, top_k: int = 5) -> list[dict[str, Any]]:
        chunks = self.load_chunks()
        scored: list[dict[str, Any]] = []
        for chunk in chunks:
            score = score_chunk(query, chunk)
            if score <= 0:
                continue
            item = dict(chunk)
            item["_score"] = round(score, 3)
            scored.append(item)
        scored.sort(key=lambda item: item.get("_score", 0), reverse=True)
        return scored[:top_k]

    def search_many(self, queries: list[str], *, top_k: int = 5) -> list[dict[str, Any]]:
        merged: dict[str, dict[str, Any]] = {}
        for query in queries:
            for chunk in self.search(query, top_k=top_k):
                chunk_id = str(chunk.get("chunk_id", chunk.get("title", "unknown")))
                if chunk_id not in merged:
                    merged[chunk_id] = chunk
                else:
                    merged[chunk_id]["_score"] = max(merged[chunk_id].get("_score", 0), chunk.get("_score", 0))
        result = list(merged.values())
        result.sort(key=lambda item: item.get("_score", 0), reverse=True)
        return result[:top_k]

    def reindex_from_profile(self) -> list[dict[str, Any]]:
        profile = load_student_profile(self.config)
        markdown = load_student_profile_markdown(self.config)
        if not markdown:
            markdown = profile_to_markdown(profile)
            save_student_profile_markdown(markdown, self.config)
        chunks = build_chunks_from_profile(profile, markdown)
        self.save_chunks(chunks)
        return chunks


def profile_to_markdown(profile: dict[str, Any]) -> str:
    student = profile.get("student", {})
    family_goal = profile.get("family_goal", {})
    subjects = profile.get("subjects", {})
    weekly_schedule = profile.get("weekly_schedule", [])
    burden_rules = profile.get("burden_rules", {})
    pending_tasks = profile.get("pending_tasks", [])
    history = profile.get("learning_history", [])
    rag_summary = profile.get("rag_summary", "")
    nickname = student.get("nickname") or "学生"

    lines: list[str] = [
        f"# 轻舟学伴学习档案：{nickname}", "",
        "## 1. 基本情况", "",
        f"- 年级：{student.get('grade', 'unknown')}",
        f"- 阶段：{student.get('stage', 'unknown')}",
        f"- 学习目标：{family_goal.get('primary_goal', 'unknown')}",
        f"- 次要目标：{family_goal.get('secondary_goal', 'unknown')}", "",
        "## 2. 学科画像", "",
    ]

    subject_names = {"math": "数学", "english": "英语", "chinese": "语文"}
    for subject_key, subject_name in subject_names.items():
        subject = subjects.get(subject_key, {})
        lines.extend([
            f"### {subject_name}", "",
            f"- 状态：{subject.get('status', 'unknown')}",
            f"- 优势：{', '.join(subject.get('strengths', []) or ['unknown'])}",
            f"- 薄弱点：{', '.join(subject.get('weaknesses', []) or ['unknown'])}",
            f"- 适合方法：{subject.get('preferred_method', 'unknown')}",
            f"- 风险提醒：{subject.get('risk_notes', 'unknown')}", "",
        ])

    lines.extend(["## 3. 固定安排", ""])
    if weekly_schedule:
        for item in weekly_schedule:
            lines.append(f"- {item.get('day', 'unknown')}：{item.get('fixed_event', 'unknown')}；{item.get('learning_note', '')}")
    else:
        lines.append("- 暂无固定安排。")

    lines.extend(["", "## 4. 减负规则", ""])
    lines.extend([
        f"- 工作日最多核心任务：{burden_rules.get('max_core_tasks_weekday', 3)} 个",
        f"- 正常状态最多：{burden_rules.get('normal_day_max_minutes', 60)} 分钟",
        f"- 疲惫状态最多：{burden_rules.get('tired_day_max_minutes', 40)} 分钟",
        f"- 很疲惫状态最多：{burden_rules.get('exhausted_day_max_minutes', 25)} 分钟",
        f"- 晚上 {burden_rules.get('no_high_intensity_after', '21:30')} 后不安排高强度学习",
        "- 未完成任务进入 pending_tasks，不简单消失", "",
        "## 5. 当前 pending tasks", "",
    ])

    if pending_tasks:
        for task in pending_tasks:
            lines.extend([
                f"### {task.get('task_id') or task.get('title', 'pending task')}", "",
                f"- 任务：{task.get('title', 'unknown')}",
                f"- 学科：{task.get('subject', 'unknown')}",
                f"- 原因：{task.get('reason', 'unknown')}",
                f"- 建议下一步：{task.get('suggested_next_step', 'unknown')}", "",
            ])
    else:
        lines.append("- 暂无 pending tasks。")

    lines.extend(["", "## 6. 最近记录", ""])
    if history:
        for item in history[-5:]:
            lines.extend([
                f"### {item.get('date', 'unknown')}", "",
                f"- 摘要：{item.get('summary', '')}",
                f"- 完成率：{item.get('completion_rate', 'unknown')}",
                f"- 精力状态：{item.get('energy_level', 'unknown')}", "",
            ])
    else:
        lines.append("- 暂无历史记录。")

    lines.extend(["", "## 7. RAG 摘要", "", rag_summary or "暂无摘要。", ""])
    return "\n".join(lines).strip() + "\n"


def _make_chunk(chunk_id: str, source_type: str, title: str, content: str, keywords: list[str], metadata: dict[str, Any] | None = None) -> dict[str, Any]:
    return {"chunk_id": chunk_id, "source_type": source_type, "title": title, "content": content, "keywords": keywords, "metadata": metadata or {}, "embedding": None}


def build_chunks_from_profile(profile: dict[str, Any], markdown: str = "") -> list[dict[str, Any]]:
    student = profile.get("student", {})
    family_goal = profile.get("family_goal", {})
    subjects = profile.get("subjects", {})
    weekly_schedule = profile.get("weekly_schedule", [])
    burden_rules = profile.get("burden_rules", {})
    pending_tasks = profile.get("pending_tasks", [])
    history = profile.get("learning_history", [])
    rag_summary = profile.get("rag_summary", "")

    chunks: list[dict[str, Any]] = []
    chunks.append(_make_chunk("profile_overview_001", "student_profile", "学生基本画像与家庭目标", f"{student.get('nickname', '学生')}{student.get('grade', '')}，{student.get('stage', '')}。主要目标：{family_goal.get('primary_goal', '')}。次要目标：{family_goal.get('secondary_goal', '')}。避免：{', '.join(family_goal.get('avoid', []) or [])}。", ["学生画像", "家庭目标", "小升初", "减负", "稳定心态"], {"category": "overview"}))

    subject_map = {"math": ("数学", "subject_math_001"), "english": ("英语", "subject_english_001"), "chinese": ("语文", "subject_chinese_001")}
    for key, (label, chunk_id) in subject_map.items():
        subject = subjects.get(key, {})
        chunks.append(_make_chunk(chunk_id, "student_profile", f"{label}学习画像", f"{label}状态：{subject.get('status', 'unknown')}。优势：{', '.join(subject.get('strengths', []) or [])}。薄弱点：{', '.join(subject.get('weaknesses', []) or [])}。适合方法：{subject.get('preferred_method', 'unknown')}。风险提醒：{subject.get('risk_notes', 'unknown')}。", [label, key] + list(subject.get("weaknesses", []) or []), {"category": "subject", "subject": key}))

    schedule_text = "；".join(f"{item.get('day', '')}{item.get('fixed_event', '')}，{item.get('learning_note', '')}" for item in weekly_schedule)
    chunks.append(_make_chunk("schedule_001", "student_profile", "固定日程与减负日", schedule_text or "暂无固定日程。", ["固定安排", "周二", "周三", "周四", "篮球", "英语班", "减负日"], {"category": "weekly_schedule"}))

    chunks.append(_make_chunk("burden_rules_001", "rule_profile", "减负规则", f"工作日最多 {burden_rules.get('max_core_tasks_weekday', 3)} 个核心任务。正常最多 {burden_rules.get('normal_day_max_minutes', 60)} 分钟，疲惫最多 {burden_rules.get('tired_day_max_minutes', 40)} 分钟，很疲惫最多 {burden_rules.get('exhausted_day_max_minutes', 25)} 分钟。晚上 {burden_rules.get('no_high_intensity_after', '21:30')} 后不安排高强度学习。任务超过 6 个必须减负。每个任务必须有完成标准。未完成任务进入 pending_tasks。", ["减负规则", "3个核心任务", "60分钟", "40分钟", "25分钟", "21:30", "pending_tasks"], {"category": "rules"}))

    if pending_tasks:
        pending_content = "；".join(f"{task.get('title', '')}：{task.get('reason', '')}，下一步：{task.get('suggested_next_step', '')}" for task in pending_tasks)
        chunks.append(_make_chunk("pending_001", "student_profile", "当前未完成任务", pending_content, ["pending", "未完成", "延期", "下一步"], {"category": "pending_tasks"}))

    for index, item in enumerate(history[-3:], start=1):
        chunks.append(_make_chunk(f"history_{index:03d}", "daily_log", f"{item.get('date', 'unknown')} 学习记录", f"{item.get('date', '')}：{item.get('summary', '')}。完成率：{item.get('completion_rate', 'unknown')}。精力：{item.get('energy_level', 'unknown')}。新薄弱点：{', '.join(item.get('new_weaknesses', []) or [])}。", ["历史记录", "复盘", "完成率", "精力状态"], {"category": "learning_history", "date": item.get("date")}))

    if rag_summary:
        chunks.append(_make_chunk("rag_summary_001", "student_profile", "综合 RAG 摘要", rag_summary, ["RAG摘要", "综合画像", "长期背景"], {"category": "rag_summary"}))
    return chunks
