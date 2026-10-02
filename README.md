# 跨境电商推荐多 Agent 运营工作台

生产化加固、配置、SLO 目标与验收方式见 [生产化验收手册](docs/production-readiness.md)。目标阈值不等于已达到的生产容量；真实平台和多实例恢复的未完成项在手册中明确列出。

这是一个以**商品推荐为主业务**的跨境电商运营工作台。运营人员创建 `homepage`、`campaign` 或 `retention` 推荐任务，Java Root/Supervisor Agent 在运行时创建带父子关系和依赖边的专业子 Agent 任务；当前能力目录包含 Profile、Product、Inventory、Copy 四类角色。每次委派都会生成新的 Agent 实例、不可变规划上下文和独立有界 Plan-Act-Observe Loop，结果以版本化 Artifact 返回，由 Root 验证并合并为 `RecommendationPlan`。

```text
运营人员创建 homepage / campaign / retention 任务
        ↓
Supervisor Agent
        ↓ 依赖分析与有界并行调度
Profile Agent  Product Agent  Inventory Agent  Copy Agent
        ↘          ↓             ↙
          共享 Blackboard / 结构化消息
专业 Agent 内部 Plan → Act(tool) → Observe
        ↓
RecommendationPlan
        ↓
SSE Trace → Run 持久化 → 历史回放 / Dashboard
```

推荐默认入口现在是 **Root/Supervisor + 动态 Sub-Agent Tasks**，不是 `SupervisorOrchestrator` 固定工作流。Root 根据服务端声明的状态读写集合，把无数据依赖、无写冲突的任务作为同一批兄弟节点并行执行；后续任务显式依赖上一批 Artifact。子 Agent 使用创建时的不可变上下文做规划，业务结果通过带 `candidateVersion` 的 `CandidateStatePatch` 合并，旧版本结果和越权字段会被拒绝。Inventory 可以 VETO，Root 会向 Product 发出 REQUEST_REVISION、清理旧候选派生状态并动态创建重新召回任务。

每次运行的 `llmMetrics` 会返回 `subAgentTasks`、`subAgentArtifacts` 和 `subAgentTaskCount`，可查看任务的父节点、依赖、上下文版本、生命周期、工具范围和交付物。当前执行器仍是单 JVM Runtime，角色能力目录固定为四类，尚未提供代码沙箱、Git worktree、未完成任务的跨进程续跑或任意层级子 Agent 创建；这些边界不会包装成已经完成的能力。

运行中和最近完成的任务 DAG 还可以通过 `GET /api/v1/agent-runs/{runId}/subagents` 查询；内存保留数量由 `ECOM_AGENT_MAX_RETAINED_RUNS` 控制，默认 100，活跃任务不会因历史记录淘汰而被移除。内存记录被淘汰或服务重启后，该查询会从数据库中的持久化投影还原 Run、Task 和 Artifact。

## 推荐运行持久化与事件恢复

推荐主链现在把以下数据写入关系数据库：

| 数据 | 作用 |
|---|---|
| `recommendation_agent_runs` | Run 状态、停止原因、输入请求、单调状态版本和乐观锁版本 |
| `recommendation_agent_tasks` | 每个动态子 Agent 的依赖、不可变输入快照、工具范围和生命周期 |
| `recommendation_agent_artifacts` | 子 Agent 产出的不可变结果、证据 ID 和候选集版本 |
| `recommendation_run_events` | 带 Run 内 sequence 的 SSE 事件日志 |
| `recommendation_outbox` | 与状态更新处于同一事务的待发布领域事件 |

客户端断线后可以携带 `Last-Event-ID` 请求 `GET /api/v1/agent-runs/{runId}/events`，服务端使用同一数据库游标补发并继续追读，支持连接切换应用实例；也可以通过 `GET /api/v1/agent-runs/{runId}/event-history?afterSequence=0&limit=100` 分页查询事件历史。`GET /api/v1/metrics` 的 `recommendation_persistence` 字段暴露运行中 Run/Task、Outbox backlog 和死信数量；`/actuator/prometheus` 提供低基数监控指标，开启鉴权时需要内部服务 Token。

Outbox 默认由本地 `ApplicationEventPublisher` 投递，但写入、去重键、失败重试和死信状态已经独立于传输实现。单机运行使用 `ECOM_AGENT_OUTBOX_TRANSPORT=local`；多实例部署时应提供 Kafka/RabbitMQ 的 `RecommendationOutboxTransport` 实现，并把消费者按 `dedupKey` 做幂等。扫描间隔、批大小、最大重试次数和退避时间均可通过 `ECOM_AGENT_OUTBOX_*` 环境变量配置。

PostgreSQL 生产迁移位于 `java/src/main/resources/migration-recommendation-runtime-postgresql.sql`。当前恢复能力覆盖审计查询、SSE 断点续传和已完成 DAG 的重建；正在执行的 Java 调用仍不会在 JVM 崩溃后从中间步骤自动续跑。要实现这一点，还需要把子任务执行改成基于 lease 的独立 Worker 消费模型，不能仅凭已有持久化表宣称已经支持。

默认 `RULES`、Embedding `live=false`、调用预算为零，因此测试和离线演示使用同一套多 Agent Runtime 的确定性 Planner fallback，不发真实模型请求；显式开启 LLM 模式和正预算后，Supervisor 与各专业 Agent 都会进行受约束的模型规划。旧 `SupervisorOrchestrator` 仅通过 `/api/v1/recommend/workflow-baseline` 暴露，用于固定工作流对照。

## 三种推荐 Scene Path

| Scene | 依赖感知执行路径 | 业务含义 |
|---|---|---|
| `homepage` | `get_user_profile → search_products → [check_inventory ∥ rerank] → merge → RecommendationPlan` | 首页快速推荐，不生成多余营销文案 |
| `campaign` | `[load_campaign_constraints ∥ search_products] → check_fulfillment → [check_inventory ∥ rerank] → merge → generate_localized_copy → RecommendationPlan` | 活动约束、履约检查和本地化活动文案 |
| `retention` | `get_user_profile → get_recent_orders → search_products → [check_inventory ∥ rerank] → merge → generate_retention_copy → RecommendationPlan` | 基于用户与近期订单的 win-back 推荐 |

`∥` 表示实际提交到独立协调线程池并发执行，`merge` 表示 Supervisor 屏障后的确定性合并。上表是依赖关系而不是硬编码的逐工具执行器；不同 Scene 只开放当前可执行的 Agent/工具，服务端会拒绝越权调用和不满足前置条件的委派。推荐链路中的用户、商品、库存、订单和 user events 使用 Java 数据服务连接 PostgreSQL/Redis；售后是第二业务场景，不改变推荐主链。详细边界见 `docs/multi-agent-architecture.md`。

并行度、协调线程池和是否允许推测式重排均由 `agent.orchestration` 配置控制；可通过 `ECOM_AGENT_PARALLEL_ENABLED`、`ECOM_AGENT_MAX_PARALLEL_SPECIALISTS`、`ECOM_AGENT_ORCHESTRATION_CORE_SIZE`、`ECOM_AGENT_ORCHESTRATION_MAX_SIZE`、`ECOM_AGENT_ORCHESTRATION_QUEUE_CAPACITY` 与 `ECOM_AGENT_SPECULATIVE_RERANK_ENABLED` 覆盖。协调线程池与业务工具线程池隔离，队列饱和时采用调用线程执行形成反压，不无限创建任务。

## Real Embedding + pgvector

商品 seed/import 时将以下完整文档送入配置的 Embedding Model：

```text
name + category + description + tags
```

查询时只生成 query embedding，然后在 PostgreSQL/pgvector 中使用 cosine distance（`<=>`）对市场过滤后的候选商品排序。旧 SHA-256 八维演示向量已移除。

当前 schema 使用不固定维度的 `vector` 列：默认连接到 Qwen 的 OpenAI-compatible endpoint，使用 `text-embedding-v4` 和 1024 维；provider、base URL、model 与 dimensions 都可通过环境变量覆盖。每个向量同时保存 provider、model、dimensions 和 content hash；查询按当前 provider/model/dimensions 及 `vector_dims(embedding)` 过滤，避免混用不同模型空间，并执行 exact cosine scan。由于没有固定 typmod，不能建立 pgvector 的定长 ANN index，生产数据量增大后需要单独设计定长迁移与索引方案。旧 `vector(8)` 数据按显式 schema/migration 策略清空，待配置完成后由 seed/import 重新生成。

运行时 schema probe 只读检测，不执行 `DROP/ALTER`；如果迁移尚未执行、仍是固定维度或缺少 metadata 列，会明确记录 fallback reason 并走 lexical fallback。

Embedding 走现有 Spring AI OpenAI-compatible 依赖；默认选择 Qwen `text-embedding-v4` / 1024 维和其 OpenAI-compatible endpoint，所有设置均可通过环境变量覆盖。API key 默认为空，live 默认关闭，预算默认为 0：

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `ECOM_EMBEDDING_PROVIDER` | `qwen` | Qwen 通过 OpenAI-compatible 协议接入 |
| `ECOM_EMBEDDING_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode` | live 时可覆盖，指向 provider 的 embeddings endpoint base URL |
| `ECOM_EMBEDDING_API_KEY` | 空 | live 时必填；不要提交到仓库 |
| `ECOM_EMBEDDING_MODEL` | `text-embedding-v4` | 可通过环境变量覆盖 |
| `ECOM_EMBEDDING_DIMENSIONS` | `1024` | 与默认模型配置匹配，可通过环境变量覆盖 |
| `ECOM_EMBEDDING_LIVE_ENABLED` | `false` | 显式开关，默认离线 |
| `ECOM_EMBEDDING_MAX_CALLS` | `0` | 每个 seed/import 批次或单次检索作用域独立的 embedding 预算 |

缺少配置、live 关闭、预算耗尽、provider/响应不可用或维度不匹配时，系统不生成伪向量，保留 lexical fallback，并记录 `source`、`vectorUsed=false` 与 `fallbackReason`。测试和 CI 不设置 live 开关，不发真实网络请求。

## Business Simulation

`RecommendationBusinessSimulationIntegrationTest` 会实际执行：

- Fixed Workflow Baseline：调用 `SupervisorOrchestrator.fixedWorkflowToolPath()` 的工具并记录每次执行；
- Scene-aware Agent Loop：调用真实 `AutonomousAgentLoopService`，记录实际 Tool Call、Step、状态和最终计划；
- 统计 Task Success Rate、Completion Rate、Average Tool Calls、Average Steps、Average Latency、Invalid Tool Call Rate、Market Constraint Violation Rate、Average Unnecessary Tool Calls。

运行：

```bash
cd java
mvn test
```

推荐业务评测报告由测试实际执行生成，不硬编码结果：

```text
java/target/recommendation-eval/recommendation-business-report.json
java/target/recommendation-eval/recommendation-business-report.md
```

该报告是离线 demo simulation，不是生产收益、质量或延迟承诺。

并行编排另有一组可复现的合成 I/O 对照测试：保持任务、工具结果和运行容量一致，只切换 `parallel-enabled`，比较单请求延迟、并发吞吐、成功率与输出一致性：

```bash
cd java
mvn -q -Dtest=RecommendationParallelPerformanceTest test
```

结果写入 `java/target/recommendation-eval/recommendation-parallel-report.md` 和 `.json`。该测试用于验证并行调度是否真正缩短独立 I/O 的关键路径，不代表生产流量下的延迟提升；生产结论仍需使用真实连接器和目标并发量压测。

## 业务边界与模块

| 目录 | 定位 |
|---|---|
| `java/` | 主线：Spring Boot 3.4 推荐 Agent、数据访问、Scene Path、RecommendationPlan、SSE/Run 闭环；同时承载现有售后信任边界 |
| `backend/` | NestJS Gateway：认证、推荐任务/Run、SSE 代理、历史回放与 Dashboard API；售后 API 是第二业务场景 |
| `frontend/` | React + Ant Design 运营工作台：推荐控制台、Trace、Run、Dashboard，以及售后页面 |
| `python/` | legacy / experimental，不是本轮主线，不新增功能 |
| `go/` | legacy / experimental，不是本轮主线，不新增功能 |

售后链路继续保持确定性 Java trust boundary：模型不能决定真实订单参数、补偿金额或审批副作用；真实执行前仍经过服务端规则、审批 Gate 和幂等执行。此轮不扩展售后模块。

## 本地运行与验证

```bash
docker compose up --build
```

首次使用 Embedding 配置时，可将仓库根目录的 `.env.example` 复制为 `.env`（Windows 可手动复制），再只在该根目录 `.env` 中填写真实 API key。真实 key 不要写入 `docker-compose.yml`、README、源码或提交记录；根目录 `.env` 已被 `.gitignore` 忽略。
`docker-compose.yml` 会把 `ECOM_EMBEDDING_PROVIDER`、`ECOM_EMBEDDING_BASE_URL`、`ECOM_EMBEDDING_API_KEY`、`ECOM_EMBEDDING_MODEL`、`ECOM_EMBEDDING_DIMENSIONS`、`ECOM_EMBEDDING_LIVE_ENABLED` 和 `ECOM_EMBEDDING_MAX_CALLS` 透传给 `java-agent-service`。模板默认使用 Qwen / OpenAI-compatible endpoint / `text-embedding-v4` / 1024 维，并保持 `live=false`、`max-calls=0`。
默认只做离线 fallback；修改配置后可先运行 `docker compose config` 检查解析结果，不会因此发起模型请求。

默认不配置 live key 时，推荐和售后模型能力均走规则/模板 fallback。推荐 Java 测试、Gateway 与 Frontend 的检查：

```bash
cd java && mvn test
cd ../backend && npm ci && npm run typecheck && npm run build
cd ../frontend && npm ci && npm run typecheck && npm run build
```

普通 Java 测试不运行任何 Live LLM/Embedding Eval。若未来需要人工运行真实 provider，必须由操作者显式提供配置、正预算和 live 开关；本仓库不保存 key，也不把 live 调用纳入默认 CI。

## 已知限制

- 演示商品、库存、订单和用户事件是确定性 seed 数据，不代表真实 OMS/WMS 或物流结果。
- Embedding 默认使用 Qwen `text-embedding-v4` / 1024 维和 OpenAI-compatible endpoint；API key 默认为空、live 默认关闭、预算默认为 0，均可通过环境变量覆盖。价格和能力可能变化，不在本 README 固化数字；当前仍只验证离线 fallback 和 mock/stub 路径。
- 不固定维度的 pgvector 列目前使用 exact cosine scan，没有 ANN 索引，适合演示和候选集检索；大规模生产使用前需要定长迁移与索引设计。
- 售后仍是第二场景，Python/Go 保留为 legacy/experimental；本轮没有增加 Agent、Gate、Memory、MCP、RAG 或新的大模块。

更多 API contract、SSE 事件和 Java 设计说明见 `docs/`。
