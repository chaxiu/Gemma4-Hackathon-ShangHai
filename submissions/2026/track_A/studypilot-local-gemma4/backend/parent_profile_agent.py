from __future__ import annotations

from typing import Any

from backend.config import AppConfig, get_config
from backend.json_utils import json_dumps_cn
from backend.llm_client import LLMClient
from backend.prompts import parent_background_understanding_messages, profile_markdown_generation_messages, rag_chunk_generation_messages
from backend.rag_store import build_chunks_from_profile, profile_to_markdown
from backend.storage import now_iso, save_rag_chunks, save_student_profile, save_student_profile_markdown
from backend.validator import validate_rag_chunks, validate_student_profile


class ParentProfileAgent:
    """家长自然语言背景 -> 结构化学习档案 -> Markdown -> RAG chunks。"""

    def __init__(self, config: AppConfig | None = None, llm: LLMClient | None = None):
        self.config = config or get_config()
        self.llm = llm or LLMClient(self.config)

    def build_profile_draft(self, parent_text: str) -> dict[str, Any]:
        trace: list[dict[str, Any]] = []
        if not parent_text.strip():
            raise ValueError("家长背景不能为空。")

        profile_raw, llm_result = self.llm.chat_json(parent_background_understanding_messages(parent_text))
        trace.append({"step": "llm_understand_parent_background", "is_mock": llm_result.is_mock, "model": llm_result.model, "error": llm_result.error})

        profile = self._normalize_profile(profile_raw)
        profile_validation = validate_student_profile(profile).to_dict()
        trace.append({"step": "validate_profile", "result": profile_validation})

        markdown = self._generate_markdown(profile, trace)
        chunks = self._generate_chunks(profile, markdown, trace)
        chunk_validation = validate_rag_chunks(chunks).to_dict()
        trace.append({"step": "validate_rag_chunks", "result": chunk_validation})

        return {
            "profile": profile,
            "markdown": markdown,
            "chunks": chunks,
            "profile_validation": profile_validation,
            "chunk_validation": chunk_validation,
            "trace": trace,
            "raw_profile_json": profile_raw,
        }

    def save_confirmed_profile(self, profile: dict[str, Any], markdown: str, chunks: list[dict[str, Any]]) -> dict[str, Any]:
        profile = self._normalize_profile(profile)
        save_student_profile(profile, self.config)
        save_student_profile_markdown(markdown or profile_to_markdown(profile), self.config)
        save_rag_chunks(chunks or build_chunks_from_profile(profile, markdown), self.config)
        return {
            "ok": True,
            "message": "学习档案、Markdown 和 RAG chunks 已保存到本地。",
            "profile_path": str(self.config.student_profile_json_path),
            "markdown_path": str(self.config.student_profile_md_path),
            "chunks_path": str(self.config.rag_chunks_path),
        }

    def _normalize_profile(self, profile: dict[str, Any]) -> dict[str, Any]:
        normalized = dict(profile)
        normalized.setdefault("profile_version", "2.0")
        normalized.setdefault("student", {})
        normalized.setdefault("family_goal", {})
        normalized.setdefault("subjects", {})
        normalized.setdefault("weekly_schedule", [])
        normalized.setdefault("burden_rules", {})
        normalized.setdefault("communication_style", {})
        normalized.setdefault("pending_tasks", [])
        normalized.setdefault("learning_history", [])
        normalized.setdefault("energy_trend", [])
        normalized.setdefault("procrastination_signals", [])
        normalized.setdefault("rag_summary", "")
        normalized.setdefault("created_at", now_iso())
        normalized["updated_at"] = now_iso()

        student = normalized["student"]
        student.setdefault("nickname", "小航")
        student.setdefault("grade", "六年级")
        student.setdefault("stage", "小升初过渡期")

        subjects = normalized["subjects"]
        for key in ["math", "english", "chinese"]:
            subjects.setdefault(key, {})
            subjects[key].setdefault("status", "unknown")
            subjects[key].setdefault("strengths", [])
            subjects[key].setdefault("weaknesses", [])
            subjects[key].setdefault("preferred_method", "unknown")
            subjects[key].setdefault("risk_notes", "unknown")

        rules = normalized["burden_rules"]
        rules.setdefault("normal_day_max_minutes", self.config.normal_max_minutes)
        rules.setdefault("tired_day_max_minutes", self.config.tired_max_minutes)
        rules.setdefault("exhausted_day_max_minutes", self.config.exhausted_max_minutes)
        rules.setdefault("max_core_tasks_weekday", self.config.max_core_tasks_weekday)
        rules.setdefault("task_count_reduce_threshold", 6)
        rules.setdefault("no_high_intensity_after", self.config.no_high_intensity_after)
        rules.setdefault("must_have_completion_standard", True)
        rules.setdefault("unfinished_goes_to_pending", True)

        return normalized

    def _generate_markdown(self, profile: dict[str, Any], trace: list[dict[str, Any]]) -> str:
        try:
            markdown_result, llm_result = self.llm.chat_json(profile_markdown_generation_messages(profile))
            markdown = str(markdown_result.get("markdown", "")).strip()
            if not markdown:
                raise ValueError("LLM markdown 字段为空")
            trace.append({"step": "generate_profile_markdown", "is_mock": llm_result.is_mock})
            return markdown
        except Exception as exc:
            markdown = profile_to_markdown(profile)
            trace.append({"step": "generate_profile_markdown_fallback", "error": str(exc)})
            return markdown

    def _generate_chunks(self, profile: dict[str, Any], markdown: str, trace: list[dict[str, Any]]) -> list[dict[str, Any]]:
        try:
            chunk_result, llm_result = self.llm.chat_json(rag_chunk_generation_messages(profile, markdown))
            chunks = chunk_result.get("chunks", [])
            if not isinstance(chunks, list) or not chunks:
                raise ValueError("LLM chunks 字段为空或不是 list")
            trace.append({"step": "generate_rag_chunks", "is_mock": llm_result.is_mock, "chunk_count": len(chunks)})
            return chunks
        except Exception as exc:
            chunks = build_chunks_from_profile(profile, markdown)
            trace.append({"step": "generate_rag_chunks_fallback", "error": str(exc), "chunk_count": len(chunks)})
            return chunks


def build_demo_profile_json_text(profile: dict[str, Any]) -> str:
    return json_dumps_cn(profile, indent=2)
