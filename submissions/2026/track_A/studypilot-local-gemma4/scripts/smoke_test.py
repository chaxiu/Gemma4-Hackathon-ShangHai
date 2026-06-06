from __future__ import annotations

import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

os.environ["STUDYPILOT_USE_MOCK_LLM"] = "true"

from backend.after_school_agent import AfterSchoolAgent
from backend.config import reload_config
from backend.eval_runner import EvalRunner
from backend.parent_profile_agent import ParentProfileAgent
from backend.reflection_agent import ReflectionAgent


def main() -> int:
    cfg = reload_config()
    print("Running StudyPilot Local V2 smoke test in mock mode...")

    parent_text = "孩子六年级，准备小升初。数学应用题弱，英语听力拖延，希望每天不超过1小时，累时不超过40分钟。"
    draft = ParentProfileAgent(cfg).build_profile_draft(parent_text)
    assert draft["profile_validation"]["passed"], draft["profile_validation"]
    ParentProfileAgent(cfg).save_confirmed_profile(draft["profile"], draft["markdown"], draft["chunks"])
    print("OK parent profile")

    plan_result = AfterSchoolAgent(cfg).run("我今天只有40分钟，有语文、数学、英语，还要预习，不知道先做什么。")
    assert plan_result["validation"]["passed"], plan_result["validation"]
    assert plan_result["plan"]["total_minutes"] <= 40
    print("OK after school plan")

    reflection = ReflectionAgent(cfg).run("数学做完了，英语听力没做，语文背了一半。数学有一道题卡住了，今天有点累。")
    assert reflection["validation"]["passed"], reflection["validation"]
    print("OK reflection")

    eval_result = EvalRunner(cfg).run(limit=3)
    assert eval_result["total"] == 3
    print("OK eval")
    print("Smoke test passed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
