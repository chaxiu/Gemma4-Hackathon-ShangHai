# 技术报告：时间规划小助手

## 1. 目标与问题

小学生每天的作业不是单纯的待办清单。孩子到家后常常同时面对语文、数学、英语、阅读、打卡和长期任务；家长关心完成情况，但不希望把孩子推向家长控制台式压力管理。

本项目的目标是做一个本地教育 Agent：

- 孩子端极简：今天计划、睡前结算。
- 家长端可信：真实完成、趋势、判断依据、纠偏审计。
- 调试端透明：RAG、trace、JSON、评测和 debug session。
- 数据本地化：一个后端、一个 SQLite、三个前端读取同一份数据。

## 2. 评测驱动的模型选型

我们没有先假设 Gemma 4 能稳定解决全部规划问题，而是先构造 60 条 StudyPilot 边界 case，对 `gemma-4-26b-a4b-it` 做本地自动化评测。

测试环境：

- 本地 LM Studio OpenAI-compatible API：`http://127.0.0.1:1234/v1`
- 模型：`gemma-4-26b-a4b-it`
- temperature：`0.1`
- max_tokens：`4096`
- 测试数据：合成学生 `demo_stu_001`
- 评分方式：Gemma 只输出 JSON，Python validator 独立评分

结果显示模型平均分 `89.8/100`，pass `57%`，soft pass `25%`，fail `18%`。这说明 Gemma 4 适合作为候选 Planner，但不适合裸用。失败集中在延迟任务缺失、时间求和不一致、核心任务数超限、追问边界和安全语言。

因此最终架构采用“Gemma Planner + 工具兜底”：

- 让 Gemma 做自然语言理解、反问、计划草案和鼓励表达。
- 让代码处理硬约束、事实检索、校验、持久化和审计。

## 3. 架构

```text
LM Studio / Gemma 4
        ^
        |
FastAPI backend
  |-- LLMClient
  |-- KidFlowAgent
  |-- AfterSchoolAgent
  |-- ReflectionAgent
  |-- RagStore
  |-- rule_engine
  |-- validator
  |-- ParentDataStore(SQLite)
        |
        +-- Kid React frontend
        +-- Parent React frontend
        +-- Streamlit debug console
```

关键模块：

- `backend/llm_client.py`：调用 LM Studio OpenAI-compatible API，支持真实模型和 mock。
- `backend/kid_flow.py`：孩子端两步规划，包含 clarification gate。
- `backend/after_school_agent.py`：放学后计划主链路。
- `backend/reflection_agent.py`：睡前复盘和学习档案更新。
- `backend/rule_engine.py`：减负、总时长、任务上限、安全语气等确定性规则。
- `backend/validator.py`：结构和规则校验，输出 issue codes。
- `backend/parent_data_store.py`：SQLite memory、正式/调试隔离、pending tasks、家长纠偏审计。

## 4. Agent Memory

Memory 不只是一段 prompt，而是可读写的本地状态：

- 学生档案：`data/demo_student_profile.json` 与 Markdown 档案。
- RAG chunks：`data/rag_chunks.json`，用 `chunk_id` 追踪来源。
- 每日计划：`daily_plans`。
- 真实复盘：`daily_logs`。
- 任务结果：`task_outcomes`，状态为 `completed / partial / missed`。
- 待接住任务：`pending_tasks`，用于第二天轻量跟进。
- 家长纠偏：`parent_corrections`，保留 audit trail。
- 档案快照：`profile_snapshots`。

这使系统能从“今天孩子说了什么”走向“孩子最近真实完成了什么、哪些任务总被拖延、家长是否修正过模型判断”。

## 5. Tool Calling-style 编排

本项目将模型放在工具链中，而不是让模型独立决定全部结果。主要工具如下：

| 工具 | 代码入口 | 作用 |
|---|---|---|
| RAG retrieval | `RagStore.search_many` | 检索学生档案片段 |
| Deterministic repair | `ensure_plan_compliance` | 控制任务数量、时间、延期和安全语气 |
| Validator | `validate_after_school_plan` | 输出 issue code 与 warnings |
| SQLite memory | `ParentDataStore.upsert_plan/upsert_daily_log` | 写入计划、复盘、任务结果 |
| Parent audit | `ParentDataStore.add_parent_correction` | 记录家长纠偏并触发轻量档案更新 |

`backend/agent_tools.py` 提供了工具注册表，方便评审定位 Agent 的 memory 与工具边界。

## 6. 评测结果如何进入代码

| 评测发现 | 工程处理 |
|---|---|
| `V008_DELAYED_TASKS_MISSING` | 被裁剪、延期、未完成的任务进入 `deferred_tasks` / `pending_tasks` |
| `V012_TIME_SUM_INCONSISTENT` | `rule_engine` 重新计算 `total_minutes` |
| `V006_CORE_TASK_LIMIT_EXCEEDED` | 工作日核心任务限制为最多 3 个 |
| `V003_CLARIFICATION_MISMATCH` | `KidFlowAgent.start_plan_session()` 增加轻量反问 gate |
| `V015_UNSAFE_LANGUAGE` | 最终孩子可见文案经过 safety filter |

完整 fact_id 白名单和多轮 LLM repair loop 是评测报告提出的下一阶段方向。本 demo 为了稳定展示，只吸收最高频、最可控的问题，采用 deterministic repair，不引入复杂循环。

## 7. 正式模式与调试模式

系统使用同一个 SQLite 数据库，但通过运行上下文隔离：

- `run_mode=official`：正式数据，家长端默认只看 canonical 记录。
- `run_mode=debug`：调试数据，同一天可多次测试，不污染正式趋势、档案和 pending。
- `session_id`：debug session 唯一标识。
- `is_canonical` 与 `revision`：保留正式记录修订轨迹。

这解决了 demo 中“一天测试很多次”和真实产品中“一天一份有效闭环”的冲突。

## 8. 儿童安全与产品边界

系统明确不做：

- 心理或医疗诊断。
- 升学保证或恐吓。
- 同伴排名比较。
- 家长替孩子伪造复盘。

孩子端只显示计划、反问和结算，不显示 JSON、trace、issue code 或 RAG。家长端显示判断依据但不变成密集调试后台。完整技术细节放在 Streamlit 调试台。

## 9. 验证

本地回归命令：

```powershell
pytest -q
cd kid-frontend && npm run build
cd parent-frontend && npm run build
```

项目当前包含后端单测、家长 API 测试、孩子流程测试、SQLite memory 测试、rule engine 测试、60 case 摘要脚本测试和两个前端 build 验证。
