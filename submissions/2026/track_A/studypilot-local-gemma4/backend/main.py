from __future__ import annotations

from typing import Any

from fastapi import FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel, Field

from backend.after_school_agent import AfterSchoolAgent
from backend.config import get_config
from backend.eval_runner import EvalRunner
from backend.kid_flow import KidFlowAgent
from backend.llm_client import LLMClient
from backend.parent_data_store import ParentDataStore
from backend.parent_profile_agent import ParentProfileAgent
from backend.rag_store import RagStore
from backend.reflection_agent import ReflectionAgent
from backend.run_context import RunContext, normalize_run_mode
from backend.storage import clear_runtime_data, latest_daily_log, latest_plan, load_rag_chunks, load_student_profile, load_student_profile_markdown


class ParentProfileDraftRequest(BaseModel):
    parent_text: str = Field(..., min_length=1)


class ParentProfileSaveRequest(BaseModel):
    profile: dict[str, Any]
    markdown: str
    chunks: list[dict[str, Any]]


class AfterSchoolPlanRequest(BaseModel):
    child_input: str = Field(..., min_length=1)
    followup_answers: str | None = None


class ReflectionRequest(BaseModel):
    reflection_input: str = Field(..., min_length=1)
    plan: dict[str, Any] | None = None


class EvalRequest(BaseModel):
    limit: int | None = None
    use_llm_judge: bool = False


class KidPlanStartRequest(BaseModel):
    child_input: str = Field(..., min_length=1)


class KidPlanFinishRequest(BaseModel):
    session_id: str = Field(..., min_length=1)
    followup_answers: str = Field(..., min_length=1)


class KidReflectionSettleRequest(BaseModel):
    reflection_input: str = Field(..., min_length=1)


class ParentCorrectionRequest(BaseModel):
    target_type: str = Field(..., min_length=1)
    target_id: str = Field(..., min_length=1)
    field: str = Field(..., min_length=1)
    old_value: str | None = None
    new_value: str = Field(..., min_length=1)
    reason: str | None = None


class DebugSessionCreateRequest(BaseModel):
    business_date: str | None = None


app = FastAPI(
    title="StudyPilot Local V2 API",
    description="轻舟学伴本地 AI Agent API",
    version="2.0.0",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)


def _run_context(run_mode: str | None = None, session_id: str | None = None, business_date: str | None = None) -> RunContext:
    return RunContext(run_mode=normalize_run_mode(run_mode), session_id=session_id, business_date=business_date)


def _call_with_optional_context(work: Any, context: RunContext, *args: Any, **kwargs: Any) -> Any:
    try:
        return work(*args, run_context=context, **kwargs)
    except TypeError as exc:
        if "run_context" not in str(exc):
            raise
        return work(*args, **kwargs)


@app.get("/health")
def health() -> dict[str, Any]:
    cfg = get_config()
    llm_status = LLMClient(cfg).health()
    return {
        "ok": True,
        "app": "StudyPilot Local V2",
        "data_dir": str(cfg.data_dir),
        "llm": llm_status,
    }


@app.get("/profile")
def get_profile() -> dict[str, Any]:
    return {
        "profile": load_student_profile(),
        "markdown": load_student_profile_markdown(),
        "chunks": load_rag_chunks(),
    }


@app.post("/parent/profile/draft")
def draft_parent_profile(request: ParentProfileDraftRequest) -> dict[str, Any]:
    try:
        return ParentProfileAgent().build_profile_draft(request.parent_text)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/parent/profile/save")
def save_parent_profile(request: ParentProfileSaveRequest) -> dict[str, Any]:
    try:
        return ParentProfileAgent().save_confirmed_profile(request.profile, request.markdown, request.chunks)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/rag/chunks")
def get_rag_chunks() -> dict[str, Any]:
    chunks = load_rag_chunks()
    return {"count": len(chunks), "chunks": chunks}


@app.post("/rag/reindex")
def reindex_rag() -> dict[str, Any]:
    try:
        chunks = RagStore().reindex_from_profile()
        return {"ok": True, "count": len(chunks), "chunks": chunks}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/after-school/plan")
def after_school_plan(
    request: AfterSchoolPlanRequest,
    x_studypilot_run_mode: str | None = Header(default=None),
    x_studypilot_session_id: str | None = Header(default=None),
    x_studypilot_business_date: str | None = Header(default=None),
) -> dict[str, Any]:
    try:
        context = _run_context(x_studypilot_run_mode, x_studypilot_session_id, x_studypilot_business_date)
        return _call_with_optional_context(AfterSchoolAgent().run, context, request.child_input, request.followup_answers)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/reflection/run")
def reflection_run(
    request: ReflectionRequest,
    x_studypilot_run_mode: str | None = Header(default=None),
    x_studypilot_session_id: str | None = Header(default=None),
    x_studypilot_business_date: str | None = Header(default=None),
) -> dict[str, Any]:
    try:
        context = _run_context(x_studypilot_run_mode, x_studypilot_session_id, x_studypilot_business_date)
        return _call_with_optional_context(ReflectionAgent().run, context, request.reflection_input, request.plan)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/kid/plan/start")
def kid_plan_start(
    request: KidPlanStartRequest,
    x_studypilot_run_mode: str | None = Header(default=None),
    x_studypilot_session_id: str | None = Header(default=None),
    x_studypilot_business_date: str | None = Header(default=None),
) -> dict[str, Any]:
    try:
        context = _run_context(x_studypilot_run_mode, x_studypilot_session_id, x_studypilot_business_date)
        return _call_with_optional_context(KidFlowAgent().start_plan_session, context, request.child_input)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/kid/plan/finish")
def kid_plan_finish(request: KidPlanFinishRequest) -> dict[str, Any]:
    try:
        return KidFlowAgent().finish_plan_session(request.session_id, request.followup_answers)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/kid/reflection/settle")
def kid_reflection_settle(
    request: KidReflectionSettleRequest,
    x_studypilot_run_mode: str | None = Header(default=None),
    x_studypilot_session_id: str | None = Header(default=None),
    x_studypilot_business_date: str | None = Header(default=None),
) -> dict[str, Any]:
    try:
        context = _run_context(x_studypilot_run_mode, x_studypilot_session_id, x_studypilot_business_date)
        return _call_with_optional_context(KidFlowAgent().settle_reflection, context, request.reflection_input)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/parent/overview")
def parent_overview(today: str | None = None, days: int = 7, run_mode: str | None = None, session_id: str | None = None) -> dict[str, Any]:
    try:
        context = _run_context(run_mode, session_id, today)
        return ParentDataStore().get_overview(today=today, days=days, run_mode=context.run_mode, session_id=context.session_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/parent/days/{date}")
def parent_day(date: str, run_mode: str | None = None, session_id: str | None = None) -> dict[str, Any]:
    try:
        context = _run_context(run_mode, session_id, date)
        return ParentDataStore().get_day(date, run_mode=context.run_mode, session_id=context.session_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/parent/history")
def parent_history(
    from_date: str | None = Query(default=None),
    to_date: str | None = Query(default=None),
    from_alias: str | None = Query(default=None, alias="from"),
    to_alias: str | None = Query(default=None, alias="to"),
    run_mode: str | None = None,
    session_id: str | None = None,
) -> dict[str, Any]:
    date_from = from_date or from_alias
    date_to = to_date or to_alias
    if not date_from or not date_to:
        raise HTTPException(status_code=422, detail="from_date/to_date or from/to are required")
    try:
        context = _run_context(run_mode, session_id)
        return ParentDataStore().get_history(date_from, date_to, run_mode=context.run_mode, session_id=context.session_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/parent/days/{date}/corrections")
def parent_correction(date: str, request: ParentCorrectionRequest, run_mode: str | None = None, session_id: str | None = None) -> dict[str, Any]:
    try:
        context = _run_context(run_mode, session_id, date)
        store = ParentDataStore()
        correction = store.add_parent_correction(date, request.model_dump(), run_mode=context.run_mode, session_id=context.session_id)
        return {"correction": correction, "day": store.get_day(date, run_mode=context.run_mode, session_id=context.session_id)}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/parent/pending-tasks")
def parent_pending_tasks(status: str | None = "open", run_mode: str | None = None, session_id: str | None = None) -> dict[str, Any]:
    try:
        context = _run_context(run_mode, session_id)
        return {"tasks": ParentDataStore().list_pending_tasks(status=status, run_mode=context.run_mode, session_id=context.session_id)}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/parent/pending-tasks/{pending_id}/resolve")
def parent_resolve_pending_task(pending_id: str) -> dict[str, Any]:
    try:
        return {"task": ParentDataStore().resolve_pending_task(pending_id)}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/debug/sessions")
def create_debug_session(request: DebugSessionCreateRequest | None = None) -> dict[str, Any]:
    try:
        return ParentDataStore().create_debug_session(business_date=request.business_date if request else None)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/debug/sessions")
def list_debug_sessions() -> dict[str, Any]:
    try:
        return {"sessions": ParentDataStore().list_debug_sessions()}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/debug/sessions/{session_id}")
def get_debug_session(session_id: str) -> dict[str, Any]:
    try:
        return ParentDataStore().get_debug_session(session_id)
    except Exception as exc:
        raise HTTPException(status_code=404, detail=str(exc)) from exc


@app.post("/debug/sessions/{session_id}/accept-as-official")
def accept_debug_session(session_id: str) -> dict[str, Any]:
    try:
        return ParentDataStore().accept_debug_session_as_official(session_id)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.delete("/debug/sessions")
def clear_debug_sessions() -> dict[str, Any]:
    try:
        return ParentDataStore().clear_debug_sessions()
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.get("/dashboard")
def dashboard() -> dict[str, Any]:
    return {
        "profile_exists": bool(load_student_profile()),
        "latest_plan": latest_plan(),
        "latest_daily_log": latest_daily_log(),
        "rag_chunk_count": len(load_rag_chunks()),
        "llm": LLMClient().health(),
    }


@app.post("/eval/run")
def eval_run(request: EvalRequest) -> dict[str, Any]:
    try:
        return EvalRunner().run(limit=request.limit, use_llm_judge=request.use_llm_judge)
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc


@app.post("/runtime/clear")
def clear_runtime() -> dict[str, Any]:
    clear_runtime_data()
    return {"ok": True, "message": "runtime 数据已清空。"}
