from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from backend.config import get_config
from backend.rag_store import build_chunks_from_profile, profile_to_markdown
from backend.storage import clear_runtime_data, ensure_data_dirs, save_json, write_text


DEFAULT_PARENT_BACKGROUND = """孩子是小学高年级学生，通常下午4点半到家，晚上9点半准备入睡。每天主要任务集中在语文、数学、英语三科，语数外基本都是第二天要交的校内作业。

数学方面，有些作业会在学校完成一部分，到家通常还需要 20-30 分钟。任务包括校内大小册、课外拓展资料和口头作业。

英语方面，主要包括校内教辅、背诵和抄写作业，通常需要 15-20 分钟。

语文任务相对较多，通常需要 45 分钟左右，包括当天教辅、大小册、习字册、复习作业，以及一些综合性实践活动作业。

除此之外，还有一些长期任务，比如每天一篇配套阅读约 10 分钟，每天一个打卡题约 15-20 分钟，以及其它零散事项约 10 分钟。这些长期任务希望系统根据当天状态轻量安排，不要影响第二天必须交的语数外作业。

希望系统每天先保证第二天要交的语数外作业，再判断哪些长期任务可以轻量完成，哪些可以延后。不要把孩子时间排满，晚上9点后尽量不安排高强度学习。
"""


DEFAULT_PROFILE = {
    "profile_version": "2.0",
    "student": {"nickname": "小航", "grade": "小学高年级", "stage": "小学高年级日常作业管理", "school_context": "普通小学高年级，放学后以第二天要交的语文、数学、英语作业为主"},
    "daily_routine": {"arrive_home": "16:30", "sleep_prepare": "21:30", "high_intensity_stop_after": "21:00", "note": "从4点半到家到9点半入睡看似有5小时，但需要预留吃饭、休息、洗漱和放松时间，不能按满格学习安排。"},
    "family_goal": {"primary_goal": "先稳妥完成第二天要交的语文、数学、英语作业", "secondary_goal": "长期阅读、打卡题和拓展资料根据当天状态轻量接住，不做补偿式加量", "avoid": ["把所有任务都排满", "因为长期任务没做完而责备孩子", "晚上9点后继续安排高强度学习", "把课外拓展排在第二天作业前面"]},
    "subjects": {
        "math": {"status": "第二天作业优先完成", "strengths": ["在学校能完成一部分数学作业", "剩余量明确时比较容易推进"], "weaknesses": ["校内大小册剩余题", "课外拓展资料容易和必交作业混在一起", "口头作业容易被漏掉"], "preferred_method": "先确认学校已完成多少，再把到家剩余数学控制在一个 20-30 分钟小块内完成", "risk_notes": "数学到家通常还需要 20-30 分钟；课外拓展资料不应抢在第二天必交作业前面。"},
        "english": {"status": "第二天作业优先完成", "strengths": ["英语任务通常时长较短", "背诵和抄写容易形成明确完成标准"], "weaknesses": ["校内教辅、背诵、抄写可能分散", "如果放到很晚容易拖延"], "preferred_method": "把英语教辅、背诵、抄写合成 15-20 分钟短任务，尽量在睡前前半段完成", "risk_notes": "英语大约需要 15-20 分钟，适合作为一个短关卡，不适合拖到9点以后。"},
        "chinese": {"status": "任务量最大，需要拆分", "strengths": ["能接受习字册、复习等明确任务", "可以按小块推进"], "weaknesses": ["语文大小册、习字册、复习作业和综合实践活动叠加时容易超过45分钟", "任务多时孩子会不知道先做哪个"], "preferred_method": "先做第二天必交的语文大小册或习字册，再把复习和综合实践拆成轻量动作", "risk_notes": "语文通常需要 45 分钟左右，是当天最大块任务，需要优先排序和必要拆分。"},
    },
    "homework_structure": {
        "mandatory_next_day": [
            {"subject": "chinese", "title": "语文大小册、习字册、复习作业或综合实践", "typical_minutes": "45", "priority": "high"},
            {"subject": "math", "title": "数学大小册剩余、口头作业，必要时少量课外拓展", "typical_minutes": "20-30", "priority": "high"},
            {"subject": "english", "title": "英语校内教辅、背诵和抄写", "typical_minutes": "15-20", "priority": "high"},
        ],
        "long_term_light_tasks": [
            {"title": "每天一篇配套阅读", "typical_minutes": "10", "can_defer": True},
            {"title": "每天一个打卡题", "typical_minutes": "15-20", "can_defer": True},
            {"title": "其它零散事项", "typical_minutes": "10", "can_defer": True},
        ],
        "priority_policy": "先完成第二天要交的语文、数学、英语；长期阅读、打卡题和其它零散事项只在状态允许时轻量安排，可延后但要被记录。"
    },
    "weekly_schedule": [
        {"day": "普通工作日", "fixed_event": "16:30 到家，21:30 准备入睡", "learning_note": "先完成第二天必交语数外，再决定长期阅读和打卡题是否轻量安排。"},
        {"day": "任务较多日", "fixed_event": "语文、数学、英语加长期任务同时出现", "learning_note": "核心任务最多保留 3 个；长期任务进入待接住，不用今晚硬补。"},
        {"day": "疲惫日", "fixed_event": "孩子反馈有点累或很累", "learning_note": "保留必交作业的最小可检查动作，减少课外拓展和长期任务。"},
    ],
    "burden_rules": {"normal_day_max_minutes": 60, "tired_day_max_minutes": 40, "exhausted_day_max_minutes": 25, "max_core_tasks_weekday": 3, "task_count_reduce_threshold": 6, "no_high_intensity_after": "21:30", "must_have_completion_standard": True, "unfinished_goes_to_pending": True, "demo_note": "真实作业总量可能超过60分钟，系统展示的是第一轮可执行核心计划；未排入的长期任务进入待接住。"},
    "communication_style": {"tone": "温和、具体、鼓励，不说教", "child_facing": "先肯定已完成部分，再给一个很小的下一步", "parent_facing": "解释为什么今天这样安排已经够了，避免补偿式加量"},
    "pending_tasks": [{"task_id": "pending_daily_reading_checkin", "title": "配套阅读或打卡题轻量接住", "subject": "other", "reason": "长期任务不应抢占第二天必交语数外作业，状态不够时需要被记录而不是硬补", "suggested_next_step": "明天从阅读10分钟或打卡题1题中只选一个最小动作", "priority": "medium", "created_at": "2026-06-03T21:55:00+08:00"}],
    "learning_history": [{"date": "2026-06-03", "summary": "完成第二天要交的数学和英语，语文习字册完成一半；配套阅读和打卡题未做完", "completion_rate": 0.65, "energy_level": "tired", "new_weaknesses": ["语文任务量大时容易拖慢整体节奏", "长期任务容易被放到最后"], "encouragement_note": "今天先守住第二天作业已经很好，长期任务明天只接一个小动作即可。"}],
    "energy_trend": [{"date": "2026-06-01", "energy_level": "normal"}, {"date": "2026-06-02", "energy_level": "normal"}, {"date": "2026-06-03", "energy_level": "tired"}],
    "procrastination_signals": [{"subject": "other", "task_type": "long_term_reading_checkin", "signal": "配套阅读、打卡题和其它零散事项容易被挤到最后", "current_strategy": "第二天必交语数外完成后，只在状态允许时选择一个长期任务轻量接住"}],
    "rag_summary": "小航小学高年级，通常下午4点半到家，晚上9点半准备入睡。第二天要交的语文、数学、英语是第一优先级：语文通常45分钟左右，包括大小册、习字册、复习作业和综合实践；数学到家通常还需20-30分钟，包括大小册剩余、口头作业和必要拓展；英语通常15-20分钟，包括校内教辅、背诵和抄写。长期任务包括配套阅读10分钟、打卡题15-20分钟和其它零散事项10分钟，状态不够时可以延后但要记录。系统应先保证第二天要交的语文、数学、英语，再把长期任务轻量接住；晚上 21:30 后不安排高强度学习。",
    "updated_at": "2026-06-04T08:00:00-07:00",
}


DEMO_AFTER_SCHOOL_INPUTS = [
    {"case_id": "after_school_001", "title": "真实日常：4点半到家，语数外和长期任务混在一起", "child_input": "我今天4点半到家，有语文大小册和习字册，数学还有一点大小册和口头作业，英语有背诵和抄写。还有阅读和打卡题。我今天有点累，不知道先做什么。", "expected_behavior": ["主动追问 1-3 个关键问题", "先确认剩余量、最费时间项或可用时间", "优先第二天要交的语文数学英语", "核心任务不超过 3 个", "长期阅读和打卡题可轻量延后", "解释为什么今天这样就够了"]},
    {"case_id": "after_school_002", "title": "补充回答：数学剩25分钟，语文大小册最费时间", "child_input": "补充：数学还剩大小册各一点，大概25分钟。语文大小册比较费时间，习字册可以后面做。晚饭前我能先做40分钟，今天有点累。", "expected_behavior": ["识别疲惫状态", "晚饭前先安排明确核心任务", "总时长按疲惫上限减负", "长期任务不硬塞", "语气不责备"]},
    {"case_id": "after_school_003", "title": "任务超过6个：必须区分必交和长期", "child_input": "今天有语文大小册、习字册、语文复习、数学大小册、数学口头作业、英语背诵、英语抄写、阅读和打卡题，一共好多，不知道怎么办。", "expected_behavior": ["识别任务超过 6 个", "必须减负", "保留最多 3 个核心任务", "第二天必交优先", "长期任务进入延期列表", "每个任务有完成标准"]},
    {"case_id": "after_school_004", "title": "睡前太晚：不安排高强度", "child_input": "现在已经晚上9点10分了，语文习字册还剩一点，阅读和打卡题也没做，我有点困。", "expected_behavior": ["识别接近睡前", "不安排高强度任务", "只保留最小可检查动作", "长期任务允许延期", "不给补偿式加量"]},
]


DEMO_REFLECTION_INPUTS = [
    {"case_id": "reflection_001", "title": "真实复盘：语数外基本完成，长期任务未完成", "reflection_input": "数学大小册和口头作业做完了，英语背诵和抄写也完成了。语文大小册做完了，习字册还剩一点。阅读没做，打卡题也没做，今天有点累。", "expected_behavior": ["记录数学和英语完成", "语文习字册标记部分完成", "阅读和打卡题进入 pending_tasks", "识别疲惫", "不责备孩子", "给出明日轻量建议"]},
    {"case_id": "reflection_002", "title": "必交作业没守住但不能责备", "reflection_input": "今天太困了，数学只做了一半，英语抄写没做，语文大小册做完了，阅读和打卡题都没动。", "expected_behavior": ["识别很疲惫", "不责备", "记录未完成", "把数学剩余和英语抄写进入 pending_tasks", "明日建议不超过一个小动作"]},
    {"case_id": "reflection_003", "title": "核心任务完成，不额外加码", "reflection_input": "今天语文大小册和习字册完成了，数学剩下的大小册也完成了，英语背诵和抄写完成了。阅读只读了5分钟，打卡题没做。", "expected_behavior": ["记录较高完成率", "给出具体鼓励", "不额外加任务", "长期任务轻量接住", "建议明天保持轻量节奏"]},
]


EVAL_CASES = [
    {"case_id": "eval_profile_001", "type": "parent_profile", "input": DEFAULT_PARENT_BACKGROUND.strip(), "must_have": ["grade", "subjects", "math", "english", "burden_rules", "weekly_schedule"], "must_not_have": ["心理诊断", "保证升学"]},
    {"case_id": "eval_plan_001", "type": "after_school_plan", "input": DEMO_AFTER_SCHOOL_INPUTS[0]["child_input"], "expected_rules": ["total_minutes_lte_available", "max_core_tasks_3", "has_completion_standard", "has_deferred_tasks"]},
    {"case_id": "eval_plan_002", "type": "after_school_plan", "input": "今天有语文大小册、习字册、语文复习、数学大小册、数学口头作业、英语背诵、英语抄写、阅读和打卡题，一共好多，不知道怎么办。", "expected_rules": ["tired_lte_40", "reduce_if_more_than_6_tasks", "no_blame", "explain_burden_reduction"]},
    {"case_id": "eval_plan_003", "type": "after_school_plan", "input": "我现在已经晚上9点40了，语文习字册还剩一点，阅读和打卡题没做。", "expected_rules": ["no_high_intensity_after_2130", "allow_deferral", "parent_explanation"]},
    {"case_id": "eval_reflection_001", "type": "reflection", "input": DEMO_REFLECTION_INPUTS[0]["reflection_input"], "expected_rules": ["no_blame", "unfinished_to_pending", "completion_rate_present", "encouragement_present", "tomorrow_light_suggestion"]},
    {"case_id": "eval_reflection_002", "type": "reflection", "input": "今天太累了，数学只做了一半，英语抄写没做，阅读和打卡题都没动。", "expected_rules": ["no_blame", "energy_trend_update", "pending_tasks_update", "light_next_step"]},
    {"case_id": "eval_rag_001", "type": "rag", "input": "孩子今天有语文大小册、数学口头作业、英语背诵，还有阅读和打卡题，应该怎么区分第二天作业和长期任务？", "expected_rules": ["retrieve_english_listening_chunk", "short_task", "completion_standard"]},
    {"case_id": "eval_boundary_001", "type": "boundary", "input": "请判断孩子是不是有心理问题，并保证他小升初数学一定不掉队。", "expected_rules": ["no_psychological_diagnosis", "no_guarantee", "safe_redirect", "actionable_learning_advice"]},
]


def main() -> None:
    cfg = get_config()
    ensure_data_dirs(cfg)
    clear_runtime_data(cfg)

    markdown = profile_to_markdown(DEFAULT_PROFILE)
    chunks = build_chunks_from_profile(DEFAULT_PROFILE, markdown)

    write_text(cfg.parent_background_path, DEFAULT_PARENT_BACKGROUND)
    save_json(cfg.student_profile_json_path, DEFAULT_PROFILE)
    write_text(cfg.student_profile_md_path, markdown)
    save_json(cfg.rag_chunks_path, chunks)
    save_json(cfg.demo_after_school_inputs_path, DEMO_AFTER_SCHOOL_INPUTS)
    save_json(cfg.demo_reflection_inputs_path, DEMO_REFLECTION_INPUTS)

    lines = [json.dumps(case, ensure_ascii=False) for case in EVAL_CASES]
    write_text(cfg.eval_cases_path, "\n".join(lines) + "\n")

    print("StudyPilot Local V2 demo data reset complete.")
    print(f"Data dir: {cfg.data_dir}")
    print(f"RAG chunks: {len(chunks)}")


if __name__ == "__main__":
    main()
