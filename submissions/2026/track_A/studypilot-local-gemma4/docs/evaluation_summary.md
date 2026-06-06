# Gemma 4 能力边界评测摘要

## 评测目的

本项目先评测 `gemma-4-26b-a4b-it`，再决定产品架构。评测目标不是证明模型完美，而是回答：

- 哪些能力可以交给 Gemma 4？
- 哪些能力必须由 RAG、rule engine、validator 或 safety filter 兜底？
- 本地教育 Agent 的风险边界在哪里？

## 评测设置

| 项目 | 值 |
|---|---|
| 模型 | `gemma-4-26b-a4b-it` |
| 本地接口 | `http://127.0.0.1:1234/v1` |
| temperature | `0.1` |
| max_tokens | `4096` |
| case 数量 | 60 |
| 数据来源 | 合成学生 `demo_stu_001` |
| 评分方式 | Gemma 输出 JSON，Python validator 独立评分 |

核心文件：

- `docs/evaluation/study_pilot_eval_cases_v1.jsonl`
- `docs/evaluation/eval_harness.py`
- `docs/evaluation/eval_results.jsonl`
- `docs/evaluation/StudyPilot_Local_Gemma_Eval_Report.md`
- `docs/evaluation/StudyPilot_Local_Gemma_Eval_Report.pdf`

## 总体结果

- 平均分：`89.8 / 100`
- 中位数：`95.0`
- pass：`57%`
- soft pass：`25%`
- fail：`18%`
- JSON 可解析率：约 `98%`
- 平均延迟：约 `11.97s / case`

![pass status](evaluation/charts/pass_status.png)

## 高频失败类型

![top issues](evaluation/charts/top_issues.png)

| issue code | 工程含义 | 当前落地 |
|---|---|---|
| `V008_DELAYED_TASKS_MISSING` | 过载时容易漏掉延期任务 | deferred_tasks / pending_tasks |
| `V012_TIME_SUM_INCONSISTENT` | 模型 total_minutes 不可信 | rule_engine 重新求和 |
| `V006_CORE_TASK_LIMIT_EXCEEDED` | 核心任务数需硬限制 | 工作日最多 3 个核心任务 |
| `V003_CLARIFICATION_MISMATCH` | 追问边界不稳定 | KidFlowAgent clarification gate |
| `V015_UNSAFE_LANGUAGE` | 儿童语气需独立过滤 | safety_policy_checks |

## 场景表现

![scene scores](evaluation/charts/scene_scores.png)

表现较弱的场景包括学生疲惫、任务延期判断、时间计算边界、固定日程冲突和安全语气边界。它们共同指向一个结论：Gemma 4 可以生成候选计划，但生产可见结果必须经过工具层。

## 融入开发的方式

1. 评测发现模型理解能力较强，因此保留 Gemma 4 作为 Planner。
2. 评测发现硬约束不稳定，因此加入 rule_engine 确定性修复。
3. 评测发现追问边界不稳定，因此孩子端第一步后必须反问。
4. 评测发现未完成任务可能丢失，因此把 pending_tasks 写入 SQLite。
5. 评测发现安全语气不能只靠模型自觉，因此最终输出前做 safety filter。
6. 评测报告建议完整 fact_id 和 repair loop，本 demo 先采用 chunk_id 和 deterministic repair，避免过度改造。

## 结论

`gemma-4-26b-a4b-it` 适合承担本地教育 Agent 的自然语言理解和候选计划生成，但不适合裸用。StudyPilot 的最终设计就是把模型能力放到可验证、可回归、可纠偏的产品架构中。
