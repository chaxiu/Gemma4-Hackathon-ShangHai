from __future__ import annotations

import json
from pathlib import Path
from typing import Any

import pandas as pd
import streamlit as st

from backend.after_school_agent import AfterSchoolAgent
from backend.config import get_config
from backend.eval_runner import EvalRunner
from backend.json_utils import json_dumps_cn, loads_json_relaxed
from backend.llm_client import LLMClient
from backend.parent_data_store import ParentDataStore
from backend.parent_profile_agent import ParentProfileAgent
from backend.rag_store import RagStore
from backend.reflection_agent import ReflectionAgent
from backend.run_context import RUN_MODE_DEBUG, RUN_MODE_OFFICIAL, RunContext
from backend.storage import clear_runtime_data, latest_daily_log, latest_plan, load_rag_chunks, load_student_profile, load_student_profile_markdown, read_text, save_rag_chunks, save_student_profile, save_student_profile_markdown


st.set_page_config(
    page_title="轻舟学伴 StudyPilot Local",
    page_icon="⛵",
    layout="wide",
    initial_sidebar_state="expanded",
)


CSS = """
<style>
:root {
  --sp-bg: #F7F3EA;
  --sp-panel: #FFFDF8;
  --sp-card: #FFFFFF;
  --sp-ink: #263238;
  --sp-muted: #6E7B79;
  --sp-line: #E9DFCF;
  --sp-green: #4F8A78;
  --sp-green-soft: #E6F1EC;
  --sp-orange: #D98C43;
  --sp-orange-soft: #FFF0DF;
  --sp-blue: #547AA5;
  --sp-blue-soft: #E9F1FA;
  --sp-red-soft: #FDECEA;
  --sp-shadow: 0 10px 26px rgba(63, 55, 41, 0.08);
}

.stApp {
  background: linear-gradient(180deg, #F7F3EA 0%, #FBF8F1 42%, #FFFFFF 100%);
  color: var(--sp-ink);
}

[data-testid="stSidebar"] {
  background: #F0E8DA;
  border-right: 1px solid var(--sp-line);
}

[data-testid="stSidebar"] * {
  color: #30413E;
}

.block-container {
  padding-top: 1.4rem;
  padding-bottom: 3rem;
  max-width: 1280px;
}

h1, h2, h3 {
  color: #243B37;
  letter-spacing: -0.02em;
}

.sp-hero {
  background: radial-gradient(circle at top left, #E6F1EC 0, #FFFDF8 48%, #FFF6EA 100%);
  border: 1px solid var(--sp-line);
  border-radius: 26px;
  padding: 28px 30px;
  box-shadow: var(--sp-shadow);
  margin-bottom: 20px;
}

.sp-hero-title {
  font-size: 34px;
  font-weight: 800;
  color: #223B36;
  margin-bottom: 8px;
}

.sp-hero-subtitle {
  font-size: 16px;
  color: var(--sp-muted);
  max-width: 860px;
  line-height: 1.75;
}

.sp-card {
  background: var(--sp-card);
  border: 1px solid var(--sp-line);
  border-radius: 22px;
  padding: 20px;
  box-shadow: var(--sp-shadow);
  margin-bottom: 16px;
}

.sp-card-soft {
  background: #FFFDF8;
  border: 1px solid var(--sp-line);
  border-radius: 20px;
  padding: 18px;
  margin-bottom: 14px;
}

.sp-title-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
}

.sp-card-title {
  font-size: 18px;
  font-weight: 750;
  color: #243B37;
}

.sp-muted {
  color: var(--sp-muted);
  font-size: 14px;
  line-height: 1.7;
}

.sp-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 650;
  border: 1px solid transparent;
  white-space: nowrap;
}

.sp-badge.ok { background: var(--sp-green-soft); color: #2E6B5C; border-color: #C7E3D8; }
.sp-badge.warn { background: var(--sp-orange-soft); color: #A35D17; border-color: #F2D0AA; }
.sp-badge.info { background: var(--sp-blue-soft); color: #3C658D; border-color: #C9DDF2; }
.sp-badge.gray { background: #F1F0ED; color: #6C6B67; border-color: #E2DED7; }

.sp-metric {
  background: #FFFFFF;
  border: 1px solid var(--sp-line);
  border-radius: 20px;
  padding: 18px;
  box-shadow: var(--sp-shadow);
}
.sp-metric-label { color: var(--sp-muted); font-size: 13px; margin-bottom: 8px; }
.sp-metric-value { font-size: 26px; font-weight: 800; color: #263B36; }

.sp-task-card {
  border: 1px solid #E7DFD0;
  background: #FFFDF8;
  border-radius: 18px;
  padding: 16px;
  margin-bottom: 12px;
}
.sp-task-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 8px; }
.sp-task-title { font-size: 16px; font-weight: 750; color: #253B37; }
.sp-time-pill { background: #E6F1EC; color: #2F695B; padding: 4px 10px; border-radius: 999px; font-size: 13px; font-weight: 700; }

div.stButton > button {
  border-radius: 999px;
  padding: 0.65rem 1.1rem;
  border: 1px solid #C7DCCE;
  background: #4F8A78;
  color: white;
  font-weight: 700;
  box-shadow: 0 6px 16px rgba(79, 138, 120, 0.18);
}
div.stButton > button:hover {
  background: #427768;
  border-color: #B8D2C5;
  color: white;
}

[data-testid="stTextArea"] textarea, [data-testid="stTextInput"] input {
  border-radius: 16px;
  border: 1px solid #D7CCBA;
  background: #FFFDF8;
}

.sp-progress-wrap { display: flex; gap: 8px; align-items: center; margin: 10px 0 2px; }
.sp-step { flex: 1; border-radius: 999px; height: 10px; background: #E6DDCF; overflow: hidden; }
.sp-step.done { background: #4F8A78; }
.sp-step.partial { background: #D98C43; }

.sp-small-code {
  background: #2D3331;
  color: #F8F4EA;
  border-radius: 14px;
  padding: 14px;
  overflow: auto;
  font-size: 13px;
}
</style>
"""

st.markdown(CSS, unsafe_allow_html=True)

cfg = get_config()


def current_run_context() -> RunContext:
    mode = st.session_state.get("run_mode", RUN_MODE_OFFICIAL)
    session_id = st.session_state.get("debug_session_id") if mode == RUN_MODE_DEBUG else None
    return RunContext(run_mode=mode, session_id=session_id)


def card_start(title: str, badge: str | None = None, badge_kind: str = "info") -> None:
    badge_html = f'<span class="sp-badge {badge_kind}">{badge}</span>' if badge else ""
    st.markdown(f'<div class="sp-card"><div class="sp-title-row"><div class="sp-card-title">{title}</div>{badge_html}</div>', unsafe_allow_html=True)


def card_end() -> None:
    st.markdown("</div>", unsafe_allow_html=True)


def soft_note(text: str, kind: str = "info") -> None:
    st.markdown(f'<div class="sp-card-soft"><span class="sp-badge {kind}">提示</span><div class="sp-muted" style="margin-top:8px;">{text}</div></div>', unsafe_allow_html=True)


def hero() -> None:
    st.markdown(
        """
        <div class="sp-hero">
          <div class="sp-hero-title">⛵ 轻舟学伴 StudyPilot Local</div>
          <div class="sp-hero-subtitle">
            面向六年级小升初学生的本地化学习规划与减负 AI Agent。它把家长自然语言背景转为学习档案，
            再通过 RAG、规则引擎、计划校验和睡前复盘，形成“放学计划 → 睡前复盘 → 档案更新”的闭环。
          </div>
        </div>
        """,
        unsafe_allow_html=True,
    )


def render_badge(text: str, kind: str = "info") -> None:
    st.markdown(f'<span class="sp-badge {kind}">{text}</span>', unsafe_allow_html=True)


def render_plan_tasks(tasks: list[dict[str, Any]]) -> None:
    for idx, task in enumerate(tasks, start=1):
        st.markdown(
            f"""
            <div class="sp-task-card">
              <div class="sp-task-head">
                <div class="sp-task-title">{idx}. {task.get('title', '未命名任务')}</div>
                <div class="sp-time-pill">{task.get('minutes', '?')} 分钟</div>
              </div>
              <div class="sp-muted"><b>完成标准：</b>{task.get('completion_standard', '未设置')}</div>
              <div class="sp-muted"><b>为什么这样排：</b>{task.get('why_first_or_later', '')}</div>
            </div>
            """,
            unsafe_allow_html=True,
        )


def render_rule_checks(checks: list[dict[str, Any]]) -> None:
    for check in checks:
        kind = "ok" if check.get("passed") else "warn" if check.get("severity") == "warning" else "gray"
        label = "通过" if check.get("passed") else "已修复/提醒"
        issue_code = check.get("issue_code")
        repair_hint = check.get("repair_hint")
        blocking = " · blocking" if check.get("blocking") else ""
        meta = f"<div class='sp-muted' style='margin-top:6px;'>issue_code：{issue_code}{blocking}</div>" if issue_code else ""
        hint = f"<div class='sp-muted' style='margin-top:6px;'>repair_hint：{repair_hint}</div>" if repair_hint else ""
        st.markdown(
            f"""
            <div class="sp-card-soft">
              <span class="sp-badge {kind}">{label}</span>
              <b style="margin-left:8px;">{check.get('title')}</b>
              <div class="sp-muted" style="margin-top:8px;">{check.get('detail')}</div>
              {meta}
              {hint}
            </div>
            """,
            unsafe_allow_html=True,
        )


def json_text_area(label: str, value: Any, height: int = 360) -> str:
    if isinstance(value, str):
        text = value
    else:
        text = json_dumps_cn(value, indent=2)
    return st.text_area(label, text, height=height)


def sidebar_nav() -> str:
    with st.sidebar:
        st.markdown("### ⛵ 轻舟学伴")
        st.caption("Local Agent · RAG · 减负闭环")
        mode_label = st.radio("数据模式", ["正式模式", "调试模式"], horizontal=True)
        st.session_state["run_mode"] = RUN_MODE_DEBUG if mode_label == "调试模式" else RUN_MODE_OFFICIAL
        if st.session_state["run_mode"] == RUN_MODE_DEBUG:
            store = ParentDataStore(cfg)
            if not st.session_state.get("debug_session_id"):
                st.session_state["debug_session_id"] = store.create_debug_session()["session_id"]
            st.markdown('<span class="sp-badge warn">调试数据不进入正式趋势</span>', unsafe_allow_html=True)
            st.caption(f"Session：{st.session_state['debug_session_id']}")
            c1, c2 = st.columns(2)
            with c1:
                if st.button("新建调试", use_container_width=True):
                    st.session_state["debug_session_id"] = store.create_debug_session()["session_id"]
                    st.rerun()
            with c2:
                if st.button("清空调试", use_container_width=True):
                    store.clear_debug_sessions()
                    st.session_state["debug_session_id"] = store.create_debug_session()["session_id"]
                    st.rerun()
        else:
            st.markdown('<span class="sp-badge ok">正式数据</span>', unsafe_allow_html=True)
        page = st.radio(
            "导航",
            ["首页 Dashboard", "家长背景输入", "学习档案", "放学后计划", "睡前复盘", "评测"],
            label_visibility="collapsed",
        )
        st.markdown("---")
        health = LLMClient(cfg).health()
        if health.get("ok"):
            st.markdown('<span class="sp-badge ok">模型可用</span>', unsafe_allow_html=True)
        else:
            st.markdown('<span class="sp-badge warn">模型未连接</span>', unsafe_allow_html=True)
        st.caption(f"模式：{health.get('mode')}")
        st.caption(f"数据：{cfg.data_dir}")
    return page


def page_dashboard() -> None:
    hero()
    context = current_run_context()
    if context.run_mode == RUN_MODE_DEBUG:
        soft_note(f"当前为调试模式：{context.normalized_session_id()}。调试数据不会进入家长正式趋势、学习档案或正式 RAG。", "warn")
        if st.button("采纳当前调试会话为正式记录", use_container_width=True):
            try:
                result = ParentDataStore(cfg).accept_debug_session_as_official(context.normalized_session_id())
                st.success(f"已采纳为 {result['business_date']} 的正式记录。")
                st.json(result)
            except Exception as exc:
                st.error(str(exc))
    else:
        soft_note("当前为正式模式：家长端趋势、待跟进和学习档案默认读取这一层数据。", "ok")
    profile = load_student_profile(cfg)
    plan = latest_plan(cfg)
    log = latest_daily_log(cfg)
    chunks = load_rag_chunks(cfg)
    health = LLMClient(cfg).health()

    profile_done = bool(profile)
    plan_done = bool(plan)
    log_done = bool(log)
    progress_value = sum([profile_done, plan_done, log_done]) / 3
    st.progress(progress_value, text="今日闭环进度：家长档案 → 放学计划 → 睡前复盘")

    col1, col2, col3, col4 = st.columns(4)
    with col1:
        st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">学习档案</div><div class="sp-metric-value">{"已建立" if profile_done else "待建立"}</div></div>', unsafe_allow_html=True)
    with col2:
        st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">RAG chunks</div><div class="sp-metric-value">{len(chunks)}</div></div>', unsafe_allow_html=True)
    with col3:
        st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">最近计划</div><div class="sp-metric-value">{"已生成" if plan_done else "待生成"}</div></div>', unsafe_allow_html=True)
    with col4:
        st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">睡前复盘</div><div class="sp-metric-value">{"已闭环" if log_done else "待复盘"}</div></div>', unsafe_allow_html=True)

    left, right = st.columns([1.1, 0.9])
    with left:
        card_start("最近一次今日计划", "计划卡", "info")
        if plan:
            st.markdown(f"**{plan.get('plan_title', '今日计划')}**")
            render_plan_tasks(plan.get("tasks", []) or [])
            st.markdown(f"<div class='sp-muted'><b>减负说明：</b>{plan.get('burden_reduction_note', '')}</div>", unsafe_allow_html=True)
        else:
            soft_note("还没有生成今日计划。可以进入“放学后计划”页面体验完整 Agent 流程。", "warn")
        card_end()

    with right:
        card_start("本地模型状态", "LM Studio", "ok" if health.get("ok") else "warn")
        st.markdown(f"<div class='sp-muted'><b>模式：</b>{health.get('mode')}</div>", unsafe_allow_html=True)
        st.markdown(f"<div class='sp-muted'><b>模型：</b>{health.get('model')}</div>", unsafe_allow_html=True)
        st.markdown(f"<div class='sp-muted'><b>状态：</b>{health.get('message')}</div>", unsafe_allow_html=True)
        if not health.get("ok"):
            soft_note("未启动 LM Studio 时，可在 .env 中设置 STUDYPILOT_USE_MOCK_LLM=true 先演示流程。", "warn")
        card_end()

        card_start("最近一次睡前复盘", "复盘卡", "info")
        if log:
            st.markdown(f"<div class='sp-muted'><b>日期：</b>{log.get('date')}</div>", unsafe_allow_html=True)
            st.markdown(f"<div class='sp-muted'><b>完成率：</b>{int(float(log.get('completion_rate', 0))*100)}%</div>", unsafe_allow_html=True)
            st.markdown(f"<div class='sp-muted'><b>鼓励：</b>{log.get('feedback', {}).get('encouragement', '')}</div>", unsafe_allow_html=True)
        else:
            soft_note("今晚睡前让孩子用一句话复盘，就能更新档案。", "gray")
        card_end()


def page_parent_profile() -> None:
    hero()
    st.subheader("家长背景输入")
    demo_text = read_text(cfg.parent_background_path, default="孩子六年级，准备小升初。数学应用题比较弱，英语听力总是拖延，希望每天别超过1小时。")
    parent_text = st.text_area("请用自然语言描述孩子背景、固定安排、薄弱点和家庭期待", demo_text, height=180)

    if st.button("让 Gemma 4 理解并生成学习档案", use_container_width=True):
        with st.spinner("Agent 正在理解家长背景，并生成结构化档案..."):
            try:
                st.session_state["profile_draft"] = ParentProfileAgent(cfg).build_profile_draft(parent_text)
                st.success("已生成学习档案草案。请检查后确认保存。")
            except Exception as exc:
                st.error(str(exc))

    draft = st.session_state.get("profile_draft")
    if draft:
        tab1, tab2, tab3, tab4 = st.tabs(["结构化 JSON", "Markdown 档案", "RAG chunks", "Agent trace"])
        with tab1:
            profile_text = json_text_area("可编辑 JSON", draft["profile"], height=520)
        with tab2:
            markdown_text = st.text_area("可编辑 Markdown", draft["markdown"], height=520)
        with tab3:
            chunks_text = json_text_area("可编辑 RAG chunks", draft["chunks"], height=520)
        with tab4:
            st.json(draft.get("trace", []))

        if st.button("确认保存到本地 RAG", use_container_width=True):
            try:
                profile = loads_json_relaxed(profile_text)
                chunks = loads_json_relaxed(chunks_text)
                result = ParentProfileAgent(cfg).save_confirmed_profile(profile, markdown_text, chunks)
                st.success(result["message"])
                st.json(result)
            except Exception as exc:
                st.error(f"保存失败：{exc}")


def page_profile() -> None:
    hero()
    st.subheader("学习档案")
    profile = load_student_profile(cfg)
    markdown = load_student_profile_markdown(cfg)
    chunks = load_rag_chunks(cfg)

    col1, col2, col3 = st.columns(3)
    with col1:
        if st.button("重新索引 RAG", use_container_width=True):
            chunks = RagStore(cfg).reindex_from_profile()
            st.success(f"已重新生成 {len(chunks)} 个 chunks。")
    with col2:
        if st.button("清空运行记录", use_container_width=True):
            clear_runtime_data(cfg)
            st.success("已清空 runtime 数据，保留 demo 档案。")
    with col3:
        st.caption("如需恢复 demo 数据，请运行：python scripts/reset_demo_data.py")

    tab1, tab2, tab3 = st.tabs(["结构化档案", "Markdown 档案", "RAG chunks"])
    with tab1:
        if profile:
            edited = json_text_area("当前 student_profile.json", profile, height=560)
            if st.button("保存 JSON 修改", key="save_profile_json"):
                try:
                    parsed = loads_json_relaxed(edited)
                    save_student_profile(parsed, cfg)
                    st.success("已保存学习档案 JSON。")
                except Exception as exc:
                    st.error(str(exc))
        else:
            soft_note("还没有学习档案，请先在“家长背景输入”页面生成。", "warn")
    with tab2:
        if markdown:
            edited_md = st.text_area("当前 Markdown 档案", markdown, height=560)
            if st.button("保存 Markdown 修改", key="save_profile_md"):
                save_student_profile_markdown(edited_md, cfg)
                st.success("已保存 Markdown 档案。")
        else:
            soft_note("Markdown 档案为空。", "warn")
    with tab3:
        if chunks:
            for chunk in chunks:
                st.markdown(f"<div class='sp-card-soft'><span class='sp-badge info'>{chunk.get('chunk_id')}</span><b style='margin-left:8px;'>{chunk.get('title')}</b><div class='sp-muted' style='margin-top:8px;'>{chunk.get('content')}</div></div>", unsafe_allow_html=True)
            chunks_text = json_text_area("编辑 chunks JSON", chunks, height=360)
            if st.button("保存 chunks 修改", key="save_chunks"):
                try:
                    parsed = loads_json_relaxed(chunks_text)
                    save_rag_chunks(parsed, cfg)
                    st.success("已保存 RAG chunks。")
                except Exception as exc:
                    st.error(str(exc))
        else:
            soft_note("RAG chunks 为空。", "warn")


def page_after_school() -> None:
    hero()
    st.subheader("放学后计划")
    context = current_run_context()
    if context.run_mode == RUN_MODE_DEBUG:
        soft_note(f"调试模式会话：{context.normalized_session_id()}。本次计划不会污染正式家长趋势。", "warn")
    soft_note("孩子只需要说今天有什么、还有多少时间、累不累。Agent 会主动追问，并用规则引擎控制任务数量和总时长。", "info")
    child_input = st.text_area("孩子输入今日任务和状态", "我今天只有40分钟，有语文、数学、英语，还要预习，不知道先做什么。", height=140)
    followup_answers = st.text_area("可选：回答 Agent 追问", "", height=90)

    if st.button("生成今日减负计划", use_container_width=True):
        with st.spinner("Agent 正在检索学习档案、生成计划并做规则校验..."):
            try:
                result = AfterSchoolAgent(cfg).run(child_input, followup_answers or None, run_context=context)
                st.session_state["after_school_result"] = result
                st.success("今日计划已生成。")
            except Exception as exc:
                st.error(str(exc))

    result = st.session_state.get("after_school_result")
    if result:
        plan = result["plan"]
        left, right = st.columns([1.05, 0.95])
        with left:
            card_start("今日计划卡", f"{plan.get('total_minutes', 0)} 分钟", "ok")
            st.markdown(f"### {plan.get('plan_title', '今日计划')}")
            render_plan_tasks(plan.get("tasks", []) or [])
            if plan.get("deferred_tasks"):
                st.markdown("#### 可以延期")
                for item in plan.get("deferred_tasks", []):
                    st.markdown(f"- **{item.get('title')}**：{item.get('reason')}（{item.get('suggested_next_time', '')}）")
            card_end()

            card_start("减负说明卡", "不是排满", "ok")
            st.markdown(f"<div class='sp-muted'>{plan.get('burden_reduction_note')}</div>", unsafe_allow_html=True)
            st.markdown(f"<div class='sp-muted'><b>为什么今天这样就够了：</b>{plan.get('why_this_is_enough')}</div>", unsafe_allow_html=True)
            st.markdown(f"<div class='sp-muted'><b>给家长：</b>{plan.get('parent_explanation')}</div>", unsafe_allow_html=True)
            card_end()

        with right:
            card_start("Agent 主动追问", "1-3 个关键问题", "info")
            for q in result.get("followup", {}).get("questions", []) or []:
                st.markdown(
                    f"<div class='sp-card-soft'><b>{q.get('question')}</b><div class='sp-muted'>{q.get('why')}</div></div>",
                    unsafe_allow_html=True,
                )
            card_end()

            card_start("规则校验结果", "Validator", "ok")
            render_rule_checks(result.get("rule_checks", []))
            issues = result.get("validation", {}).get("details", {}).get("issues", [])
            if issues:
                st.caption("Validator issues")
                st.json(issues)
            card_end()

            card_start("RAG 依据卡", f"{len(result.get('rag_context', []))} 条", "info")
            for chunk in result.get("rag_context", []) or []:
                st.markdown(f"<div class='sp-card-soft'><span class='sp-badge gray'>{chunk.get('_score', '')}</span><b style='margin-left:8px;'>{chunk.get('title')}</b><div class='sp-muted' style='margin-top:8px;'>{chunk.get('content')}</div></div>", unsafe_allow_html=True)
            card_end()

        with st.expander("Agent trace"):
            st.json(result.get("agent_trace", []))


def page_reflection() -> None:
    hero()
    st.subheader("睡前复盘")
    context = current_run_context()
    if context.run_mode == RUN_MODE_DEBUG:
        soft_note(f"调试模式会话：{context.normalized_session_id()}。复盘会写入 SQLite 调试层，但不会更新正式学习档案/RAG。", "warn")
    soft_note("睡前复盘不责备、不临时加量。未完成任务进入 pending_tasks，明天用轻量动作继续。", "info")
    reflection_input = st.text_area("孩子输入今日完成情况", "数学做完了，英语听力没做，语文背了一半。数学有一道题卡住了，今天有点累。", height=140)

    if st.button("生成复盘反馈并更新学习档案", use_container_width=True):
        with st.spinner("Agent 正在理解复盘、写 daily_log 并更新档案..."):
            try:
                result = ReflectionAgent(cfg).run(reflection_input, run_context=context)
                st.session_state["reflection_result"] = result
                st.success("今日闭环完成。")
            except Exception as exc:
                st.error(str(exc))

    result = st.session_state.get("reflection_result")
    if result:
        feedback = result["feedback"]
        log = result["daily_log"]
        col1, col2 = st.columns([1.05, 0.95])
        with col1:
            card_start("今日闭环完成", "睡前复盘", "ok")
            st.progress(float(log.get("completion_rate", 0)), text=f"今日完成率估计：{int(float(log.get('completion_rate', 0))*100)}%")
            st.markdown(f"<div class='sp-muted'><b>给孩子：</b>{feedback.get('child_feedback')}</div>", unsafe_allow_html=True)
            st.markdown(f"<div class='sp-muted'><b>鼓励：</b>{feedback.get('encouragement')}</div>", unsafe_allow_html=True)
            st.markdown(f"<div class='sp-muted'><b>明日轻量建议：</b>{feedback.get('tomorrow_light_suggestion')}</div>", unsafe_allow_html=True)
            card_end()

            card_start("更新学习档案摘要", "Profile updated", "info")
            st.markdown(f"<div class='sp-muted'>{result.get('updated_profile_summary', '')}</div>", unsafe_allow_html=True)
            card_end()

        with col2:
            card_start("daily_log JSON", "本地记录", "gray")
            st.json(log)
            card_end()

            card_start("校验结果", "不责备 / pending", "ok" if result.get("validation", {}).get("passed") else "warn")
            policy_checks = result.get("validation", {}).get("details", {}).get("policy_checks", [])
            if policy_checks:
                render_rule_checks(policy_checks)
            st.json(result.get("validation"))
            card_end()

        with st.expander("Agent trace"):
            st.json(result.get("agent_trace", []))


def page_eval() -> None:
    hero()
    st.subheader("评测")
    soft_note("评测覆盖减负规则、完成标准、RAG 依据、睡前不责备、pending_tasks 闭环和能力边界。它不能证明升学效果。", "info")
    col1, col2 = st.columns([0.7, 0.3])
    with col1:
        limit = st.number_input("最多运行用例数", min_value=1, max_value=50, value=8)
    with col2:
        use_judge = st.checkbox("启用 LLM Judge", value=False)

    if st.button("运行评测", use_container_width=True):
        with st.spinner("正在运行评测用例..."):
            try:
                st.session_state["eval_result"] = EvalRunner(cfg).run(limit=int(limit), use_llm_judge=use_judge)
                st.success("评测完成。")
            except Exception as exc:
                st.error(str(exc))

    result = st.session_state.get("eval_result")
    if result:
        c1, c2, c3, c4 = st.columns(4)
        with c1:
            st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">用例总数</div><div class="sp-metric-value">{result.get("total")}</div></div>', unsafe_allow_html=True)
        with c2:
            st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">通过</div><div class="sp-metric-value">{result.get("passed")}</div></div>', unsafe_allow_html=True)
        with c3:
            st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">通过率</div><div class="sp-metric-value">{int(float(result.get("pass_rate", 0))*100)}%</div></div>', unsafe_allow_html=True)
        with c4:
            st.markdown(f'<div class="sp-metric"><div class="sp-metric-label">平均分</div><div class="sp-metric-value">{result.get("avg_score")}</div></div>', unsafe_allow_html=True)

        st.markdown("### 结果明细")
        rows = []
        for item in result.get("results", []):
            rows.append({"case_id": item.get("case_id"), "type": item.get("case_type"), "passed": item.get("passed"), "score": item.get("score"), "failed_rules": ", ".join(item.get("failed_rules", [])), "failure_reasons": "; ".join(item.get("failure_reasons", []))})
        st.dataframe(pd.DataFrame(rows), use_container_width=True, hide_index=True)

        with st.expander("完整评测输出"):
            st.json(result)

        card_start("Gemma 4 能力边界说明", "Boundary", "warn")
        st.markdown(f"<div class='sp-muted'>{result.get('capability_boundary')}</div>", unsafe_allow_html=True)
        card_end()


page = sidebar_nav()
if page == "首页 Dashboard":
    page_dashboard()
elif page == "家长背景输入":
    page_parent_profile()
elif page == "学习档案":
    page_profile()
elif page == "放学后计划":
    page_after_school()
elif page == "睡前复盘":
    page_reflection()
elif page == "评测":
    page_eval()
