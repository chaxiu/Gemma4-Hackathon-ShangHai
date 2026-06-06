from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.rag_store import build_chunks_from_profile, profile_to_markdown
from scripts.reset_demo_data import DEFAULT_PARENT_BACKGROUND, DEFAULT_PROFILE, DEMO_AFTER_SCHOOL_INPUTS


def test_demo_seed_uses_realistic_homework_background() -> None:
    assert "4点半到家" in DEFAULT_PARENT_BACKGROUND
    assert "晚上9点半" in DEFAULT_PARENT_BACKGROUND
    assert "语数外基本都是第二天要交" in DEFAULT_PARENT_BACKGROUND
    assert "长期任务" in DEFAULT_PARENT_BACKGROUND

    subjects = DEFAULT_PROFILE["subjects"]
    assert "20-30 分钟" in subjects["math"]["risk_notes"]
    assert "15-20 分钟" in subjects["english"]["risk_notes"]
    assert "45 分钟" in subjects["chinese"]["risk_notes"]
    assert DEFAULT_PROFILE["daily_routine"]["arrive_home"] == "16:30"
    assert DEFAULT_PROFILE["daily_routine"]["sleep_prepare"] == "21:30"


def test_demo_seed_generates_rag_chunks_for_mandatory_and_long_term_tasks() -> None:
    markdown = profile_to_markdown(DEFAULT_PROFILE)
    chunks = build_chunks_from_profile(DEFAULT_PROFILE, markdown)
    combined = "\n".join(f"{chunk['title']}\n{chunk['content']}" for chunk in chunks)

    assert "第二天要交的语文、数学、英语" in combined
    assert "配套阅读" in combined
    assert "打卡题" in combined
    assert "长期任务" in combined
    assert "晚上 21:30 后不安排高强度学习" in combined


def test_demo_after_school_input_matches_video_story() -> None:
    first_case = DEMO_AFTER_SCHOOL_INPUTS[0]
    assert "4点半到家" in first_case["child_input"]
    assert "语文大小册和习字册" in first_case["child_input"]
    assert "数学还有一点大小册和口头作业" in first_case["child_input"]
    assert "阅读和打卡题" in first_case["child_input"]
    assert "主动追问" in " ".join(first_case["expected_behavior"])


def test_kid_frontend_prefill_matches_demo_story() -> None:
    source = (ROOT / "kid-frontend" / "src" / "main.tsx").read_text(encoding="utf-8")

    assert "我今天4点半到家" in source
    assert "语文大小册和习字册" in source
    assert "数学还有一点大小册和口头作业" in source
    assert "阅读没做，打卡题也没做" in source
