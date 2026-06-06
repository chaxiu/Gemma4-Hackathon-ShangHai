from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class AgentToolSpec:
    name: str
    module: str
    callable_name: str
    purpose: str
    memory_read: tuple[str, ...] = ()
    memory_write: tuple[str, ...] = ()


AGENT_TOOLS: tuple[AgentToolSpec, ...] = (
    AgentToolSpec(
        name="profile_rag_retrieval",
        module="backend.rag_store",
        callable_name="RagStore.search_many",
        purpose="Retrieve local student-profile chunks before planning.",
        memory_read=("data/rag_chunks.json",),
    ),
    AgentToolSpec(
        name="deterministic_plan_repair",
        module="backend.rule_engine",
        callable_name="ensure_plan_compliance",
        purpose="Apply hard constraints for time, core task count, delayed tasks, and child-safe wording.",
    ),
    AgentToolSpec(
        name="plan_validator",
        module="backend.validator",
        callable_name="validate_after_school_plan",
        purpose="Return machine-readable validation results and issue codes for generated plans.",
    ),
    AgentToolSpec(
        name="parent_memory_store",
        module="backend.parent_data_store",
        callable_name="ParentDataStore.upsert_plan",
        purpose="Persist canonical and debug daily plans into SQLite.",
        memory_write=("data/runtime/studypilot.db",),
    ),
    AgentToolSpec(
        name="daily_reflection_memory",
        module="backend.parent_data_store",
        callable_name="ParentDataStore.upsert_daily_log",
        purpose="Persist bedtime reflection, task outcomes, completion rate, and pending tasks.",
        memory_write=("data/runtime/studypilot.db", "data/runtime/daily_logs"),
    ),
    AgentToolSpec(
        name="parent_correction_audit",
        module="backend.parent_data_store",
        callable_name="ParentDataStore.add_parent_correction",
        purpose="Record parent corrections without overwriting the child's original reflection.",
        memory_read=("data/runtime/studypilot.db",),
        memory_write=("data/runtime/studypilot.db", "data/demo_student_profile.json", "data/rag_chunks.json"),
    ),
)


def list_agent_tools() -> list[dict[str, object]]:
    return [tool.__dict__ for tool in AGENT_TOOLS]
