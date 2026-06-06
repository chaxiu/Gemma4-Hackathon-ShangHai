# Agent Memory 与 Tool Calling-style 编排说明

## 赛道 A 对齐

官方赛道 A 关注 Agent 的 Memory 和 Tool Calling。StudyPilot 的实现重点是：Gemma 4 不直接输出最终可信计划，而是作为 Planner 被放进一个可审计、可修复、可持久化的工具链。

## Memory

| Memory | 位置 | 用途 |
|---|---|---|
| 学生长期档案 | `data/demo_student_profile.json` | 学科弱点、固定日程、减负规则 |
| Markdown 档案 | `data/demo_student_profile.md` | 给 RAG 和家长阅读 |
| RAG chunks | `data/rag_chunks.json` | 用 `chunk_id` 追踪检索来源 |
| SQLite | `data/runtime/studypilot.db` | 家长端主数据源 |
| daily_logs | SQLite / JSON | 孩子睡前真实复盘 |
| task_outcomes | SQLite | 每个任务真实状态 |
| pending_tasks | SQLite / profile | 未完成任务轻量接住 |
| parent_corrections | SQLite | 家长纠偏 audit trail |
| profile_snapshots | SQLite / JSON | 档案更新前后快照 |

## Tools

| Tool | 代码入口 | 输入 | 输出 |
|---|---|---|---|
| `profile_rag_retrieval` | `RagStore.search_many` | 孩子输入、理解结果生成的 queries | profile chunks |
| `gemma_planner` | `LLMClient.chat_json` | prompt、RAG、rule hints | 候选 JSON |
| `deterministic_plan_repair` | `ensure_plan_compliance` | 候选 plan、孩子输入、规则配置 | 修复后 plan、rule checks、trace |
| `plan_validator` | `validate_after_school_plan` | plan | passed/errors/warnings/issues |
| `daily_memory_store` | `ParentDataStore.upsert_plan` | plan | SQLite daily_plans |
| `reflection_memory_store` | `ParentDataStore.upsert_daily_log` | daily_log | daily_logs、task_outcomes、pending_tasks |
| `parent_correction_audit` | `ParentDataStore.add_parent_correction` | correction payload | parent_corrections、重算 completion_rate |

轻量工具注册表见 `backend/agent_tools.py`。

## 主链路

```text
start_plan_session
  -> LLM understands input
  -> clarification gate asks 1-3 questions
finish_plan_session
  -> RAG retrieves profile chunks
  -> Gemma generates candidate plan
  -> rule_engine repairs hard constraints
  -> validator records issue codes
  -> ParentDataStore persists plan
settle_reflection
  -> Gemma parses reflection
  -> rule/policy checks child-safe feedback
  -> ParentDataStore writes daily log, outcomes, pending tasks
  -> profile/RAG lightly updates
parent correction
  -> audit trail
  -> completion_rate recalculation
  -> official profile/RAG sync
```

## 为什么不是单纯 Prompt 工程

60 case 评测证明，单靠 prompt 会在任务延期、总时长、核心任务数和安全语言上出现边界问题。因此本项目把这些问题变成代码工具：

- 时间不是让模型相信自己，而是由代码重算。
- 任务数不是让模型自觉少排，而是由规则上限裁剪。
- 未完成不是丢失，而是进入 pending。
- 家长纠偏不是覆盖原始输入，而是写 audit trail。
- debug 测试不是污染正式趋势，而是通过 `run_mode/session_id` 隔离。

这也是本项目的核心工程价值。
