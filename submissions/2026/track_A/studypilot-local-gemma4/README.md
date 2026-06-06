# 时间规划小助手（StudyPilot Local Gemma 4）

队伍：妙妙爱学习  
赛道：A - AI Agent  
核心模型：`gemma-4-26b-a4b-it`，通过 LM Studio 的 OpenAI-compatible API 本地调用。

## 演示视频

演示视频已放在仓库内：

`docs/assets/demo/studypilot_live_demo_hardsub_manual_voice.mp4`

视频为 3 分 33 秒的真实自动化操作录屏，带硬字幕和中文配音，覆盖一个 FastAPI 后端、孩子端 React、家长端 React、Streamlit 调试台以及同一份 SQLite 数据闭环。

## 项目简介

时间规划小助手是一个面向小学生家庭作业场景的本地 AI Agent demo。孩子端只做两件事：放学后说出今天情况，睡前做一次真实复盘。家长端只做审核、理解趋势和轻量纠偏。Streamlit 保留为内部调试台，用来展示 RAG、trace、JSON、评测和调试模式。

项目不是简单聊天机器人。Gemma 4 负责理解自然语言、提出关键反问、生成候选计划和鼓励式复盘；外层代码工具负责检索、规则约束、校验、持久化和审计，避免把儿童学习安排完全交给模型裸跑。

## 为什么选择 Gemma 4 26B-A4B

我们先对 `gemma-4-26b-a4b-it` 做了 60 条边界 case 自动化评测，然后再决定产品架构。评测结论是：Gemma 4 适合作为本地 Planner，但不能裸用。

评测设置：

| 项目 | 值 |
|---|---|
| 模型 | `gemma-4-26b-a4b-it` |
| 本地接口 | `http://127.0.0.1:1234/v1` |
| temperature | `0.1` |
| max_tokens | `4096` |
| case 数 | 60 条合成 StudyPilot 边界 case |
| 评分方式 | Gemma 只输出 JSON，Python validator 独立评分 |

关键结果：

- 平均分：`89.8 / 100`
- pass：`57%`
- soft pass：`25%`
- fail：`18%`
- JSON 可解析率：约 `98%`
- 平均延迟：约 `11.97s / case`

评测暴露出的高频问题直接进入工程设计：

- `V008_DELAYED_TASKS_MISSING`：任务过载时必须由代码补 deferred/pending。
- `V012_TIME_SUM_INCONSISTENT`：总时长必须由代码重算覆盖。
- `V006_CORE_TASK_LIMIT_EXCEEDED`：核心任务数量必须硬限制。
- `V003_CLARIFICATION_MISMATCH`：孩子端必须有二次反问 gate。
- `V015_UNSAFE_LANGUAGE`：儿童可见文案必须经过安全语气过滤。

完整评测材料在 `docs/evaluation/`，摘要见 `docs/evaluation_summary.md`。

## Agent Memory 与工具调用式编排

本项目采用 Tool Calling-style orchestration。这里不夸大为完整原生函数调用框架，而是把模型调用放在一个明确的工具链中：

```text
孩子输入
  -> KidFlowAgent 二次反问 gate
  -> RagStore 检索学生档案
  -> Gemma 4 生成候选计划
  -> rule_engine 确定性减负和修复
  -> validator 输出 issue codes
  -> SQLite / JSON 写入每日计划
  -> 睡前复盘
  -> task_outcomes / pending_tasks / profile snapshots
  -> 家长端审核纠偏与审计
```

Memory：

- `data/demo_student_profile.json` / `.md`：学生长期档案。
- `data/rag_chunks.json`：RAG profile chunks。
- `data/runtime/studypilot.db`：每日计划、真实完成、pending tasks、家长纠偏。
- `data/runtime/profile_snapshots/`：学习档案更新快照。

Tools：

- RAG 检索：`backend.rag_store.RagStore.search_many`
- 规则修复：`backend.rule_engine.ensure_plan_compliance`
- 计划校验：`backend.validator.validate_after_school_plan`
- SQLite memory：`backend.parent_data_store.ParentDataStore`
- 家长审计：`ParentDataStore.add_parent_correction`
- 工具注册说明：`backend/agent_tools.py`

## 功能入口

| Surface | 命令 | URL |
|---|---|---|
| FastAPI 后端 | `python scripts/run_backend.py` | `http://127.0.0.1:8000` |
| 孩子端 React | `python scripts/run_kid_frontend.py` | `http://127.0.0.1:5173` |
| 家长端 React | `python scripts/run_parent_frontend.py` | `http://127.0.0.1:5174` |
| Streamlit 调试台 | `python scripts/run_streamlit.py` | `http://127.0.0.1:8501` |
| LM Studio | 手动启动 Local Server | `http://localhost:1234/v1` |

## 快速开始

```powershell
pip install -r requirements.txt
copy .env.example .env
python scripts/reset_demo_data.py
python scripts/run_backend.py
```

另开终端：

```powershell
python scripts/run_kid_frontend.py
python scripts/run_parent_frontend.py
python scripts/run_streamlit.py
```

`.env` 关键配置：

```text
STUDYPILOT_LM_STUDIO_BASE_URL=http://localhost:1234/v1
STUDYPILOT_MODEL_NAME=gemma-4-26b-a4b-it
STUDYPILOT_USE_MOCK_LLM=false
```

如果只是检查 UI 和接口，可以临时使用 mock：

```text
STUDYPILOT_USE_MOCK_LLM=true
```

## Demo 流程

1. 在孩子端输入今天任务和状态。
2. Agent 必须先反问 1-3 个关键问题。
3. 孩子补充后，Gemma 4 + RAG + 规则工具生成今日轻量计划。
4. 睡前孩子输入一次真实复盘。
5. 系统生成今日结算、鼓励语、任务结果和 pending tasks。
6. 家长端读取同一 SQLite 数据，看真实完成、趋势、判断依据和纠偏。
7. Streamlit 调试台展示 trace、RAG chunks、official/debug 隔离和评测材料。

推荐输入见 `docs/demo_script.md`。

## 测试

```powershell
pytest -q
cd kid-frontend
npm install
npm run build
cd ..\parent-frontend
npm install
npm run build
```

最近本地验证结果：`pytest -q` 为 49 个测试通过，两个前端 build 通过。

## 隐私与边界

- MVP 使用本地 SQLite，不接云端账号体系。
- 评测数据为合成学生 `demo_stu_001`，不包含真实儿童隐私。
- 系统不做心理或医疗诊断，不承诺升学结果，不做同伴排名比较。
- 家长端只做审核纠偏，不替孩子伪造真实完成记录。

## 提交说明

本项目提交在官方仓库：

`submissions/2026/track_A/studypilot-local-gemma4/`

PR 标题建议：

`[赛道A] 时间规划小助手 - 妙妙爱学习`
