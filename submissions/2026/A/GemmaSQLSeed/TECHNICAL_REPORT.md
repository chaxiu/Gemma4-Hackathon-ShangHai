# GemmaSQLSeed 技术报告

## 1. 项目概述

GemmaSQLSeed 是一款基于 Gemma 4 大模型的智能 SQLite 测试数据生成工具。通过 MCP（Model Context Protocol）协议，AI Agent 可以直接与数据库交互，实现 schema 自动分析、数据配置智能生成和批量数据填充。开发者只需一行命令即可让 Gemma 为任意 SQLite 数据库生成高质量测试数据。

## 2. 模型选型理由

### 为什么选择 Gemma 4？

1. **原生函数调用（Native Function Calling）能力**：Gemma 4 支持原生函数调用，SchemaAnalyzer 和 AiConfigRefiner 通过函数调用返回结构化的列映射建议和修正后的 YAML 配置，确保输出格式的可靠性。

2. **多规格适配**：Gemma 4 提供 2B/4B/26B MoE/31B Dense 多种规格。schema 分析等结构化任务用 4B 即可；复杂自纠正流程用 31B Dense 提供更强推理能力。

3. **端侧部署潜力**：轻量规格（2B/4B）为未来端侧部署提供可能，使数据生成工具可在本地离线运行，保护数据库 schema 隐私。

4. **开源与合规**：Gemma 4 开源许可允许深度集成，无需担心 API 调用限制和成本。

### 模型使用方式

- **SchemaAnalyzer**：使用 Gemma 4 原生函数调用，输入数据库 schema 信息，输出结构化列映射建议
- **AiConfigRefiner**：利用 Gemma 4 推理能力，对生成配置验证和修正，最多3轮自纠正
- **MCP Tool Calling**：通过 MCP 协议将 Gemma 4 能力暴露为标准化工具接口

## 3. 架构设计

### 整体架构

```
AI Agent Layer (Any MCP-compatible Agent)
    | MCP Protocol
MCP Server Layer
    |- inspect_schema | generate_yaml_config | fill_table
    |
Core Engine Layer
    |- Orchestrator | Mapper(9-level) | SchemaInferrer
    |- ColumnDAG | RelationResolver | ExpressionEngine
    |- ConstraintSolver
    |
Generator Layer
    |- Base | Faker | Mimesis (31 generators)
    |
Database Layer
    |- SQLite-Utils Adapter | Raw SQLite Adapter
```

### 核心模块

1. **DataOrchestrator**：中央协调器，管理数据生成完整生命周期。采用上下文管理器模式。

2. **ColumnMapper**：9级列映射策略链：
   - Level 1: Autoincrement PK 检测
   - Level 2: 用户配置覆盖
   - Level 3: 精确名称匹配（74条规则）
   - Level 4: 默认值检测
   - Level 5: 模式匹配（26条正则规则）
   - Level 6: 可空字段处理
   - Level 7: 类型回退
   - Level 8-9: AI 辅助映射（Gemma 驱动）

3. **SchemaInferrer**：自动读取 SQLite schema，解析 CREATE TABLE SQL，检测 autoincrement。

4. **RelationResolver + SharedPool**：跨表外键完整性保障。通过名称匹配自动发现隐式关联。

5. **ColumnDAG**：基于拓扑排序的列依赖解析，处理 derive_from 表达式依赖。

6. **ExpressionEngine**：基于 simpleeval 的安全表达式引擎，5秒超时保护，21个白名单函数。

7. **ConstraintSolver**：UNIQUE 约束求解器，支持回溯重试和概率模式（>100K行自动切换SHA256哈希）。

### AI 集成架构

**SchemaAnalyzer** 利用 Gemma 4 原生函数调用：
- 识别列语义（email、phone、address 等）
- 推荐数据生成策略和参数
- 处理特殊约束（UNIQUE、CHECK 等）

**AiConfigRefiner** 自纠正流程：
1. Gemma 生成初始 YAML 配置
2. 系统验证配置（类型检查、约束验证、依赖完整性）
3. 如有错误，将错误信息反馈给 Gemma
4. Gemma 修正配置
5. 最多3轮自纠正

### MCP Server 工具

1. **inspect_schema**：分析数据库 schema，返回表结构、列信息、外键关系
2. **generate_yaml_config**：AI 生成数据配置 YAML，含自纠正流程
3. **fill_table**：批量填充数据到指定表

任何兼容 MCP 的 AI Agent（如 Claude、GPT 等）都可以直接调用这些工具，实现零代码接入。

## 4. 数据生成流水线

```
Schema Inference -> Column Mapping -> DAG Sort -> Batch Generation -> Constraint Check -> DB Insert
SchemaInferrer     ColumnMapper     ColumnDAG   DataStream         ConstraintSolver   DatabaseAdapter
                  (9-level)        (TopoSort)  (Iterator)         (UNIQUE/Backtrack) (Batch Insert)
```

## 5. 性能设计

- **流式生成**：DataStream 实现 Iterator 模式，100万行数据与1000行内存占用相同
- **批量写入**：默认1000行/批次，PRAGMA 优化（journal_mode=WAL, synchronous=OFF）
- **约束求解**：小数据量回溯重试，>100K行自动切换概率模式（SHA256哈希）
- **Provider 降级**：mimesis -> faker -> base 自动降级，确保核心功能始终可用

## 6. 创新点总结

1. **Gemma 驱动的 SchemaAnalyzer**：首次将大模型用于数据库 schema 语义分析
2. **自纠正流程（AiConfigRefiner）**：Gemma 生成配置后自动验证并修正
3. **MCP 标准化接口**：任何兼容 MCP 的 AI Agent 可零代码接入
4. **9级列映射策略链**：从自动增量到 AI 辅助，覆盖所有场景
5. **DAG 依赖排序**：确保 derive_from 列表达式的正确计算顺序
6. **跨表外键完整性**：SharedPool + RelationResolver 自动维护引用完整性

## 7. 未来规划

- 端侧部署 Gemma 4 2B/4B，实现完全离线数据生成
- 支持更多数据库（PostgreSQL、MySQL）
- 可视化配置编辑器
- 数据质量评估报告
