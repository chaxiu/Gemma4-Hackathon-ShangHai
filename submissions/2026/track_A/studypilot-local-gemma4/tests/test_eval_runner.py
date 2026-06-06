from __future__ import annotations

import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.eval_runner import EvalRunner


def test_rag_scoring_accepts_8_to_12_minute_short_listening_task() -> None:
    output = {
        "rag_context": [{"title": "英语学习画像", "content": "英语听力容易拖延。"}],
        "plan": {
            "tasks": [
                {
                    "title": "英语短时听力训练",
                    "subject": "english",
                    "minutes": 15,
                    "completion_standard": "完成8-12分钟听力并说出2个关键词",
                }
            ]
        },
    }

    score = EvalRunner()._score_rag({}, output)

    assert "short_task" in score["passed_rules"]
    assert score["passed"] is True
