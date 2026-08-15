# 跨境电商推荐多 Agent 运营工作台

这是一个以**商品推荐为主业务**的跨境电商运营工作台。运营人员创建 `homepage`、`campaign` 或 `retention` 推荐任务，Java Recommendation Agent 在服务端约束的 Workflow 中完成用户画像、商品召回、库存/履约约束、Rerank、本地化方案和 `RecommendationPlan`，再通过 SSE Trace、Run 持久化、历史回放与 Dashboard 观察全过程。

```text
运营人员创建 homepage / campaign / retention 任务
        ↓
Java Recommendation Agent
        ↓
用户画像 → 商品召回 → 库存 / 履约约束 → Rerank → 本地化方案
        ↓
RecommendationPlan
        ↓
SSE Trace → Run 持久化 → 历史回放 / Dashboard
```

这里的 Agent 是 **Workflow 约束下的 Bounded Agent Loop**，不是自由 ReAct：模型（如果显式开启）只能提出下一步，Java `ScenePathEnforcer` 负责白名单、顺序、可信参数与完成条件。默认 `RULES`、Embedding `live=false`、调用预算为零，构建、测试和默认演示不会调用真实模型或 Embedding API。

## 三种推荐 Scene Path

| Scene | 服务端 Tool Path | 业务含义 |
|---|---|---|
| `homepage` | `get_user_profile → search_products → check_inventory → rerank → RecommendationPlan` | 首页快速推荐，不生成多余营销文案 |
| `campaign` | `load_campaign_constraints → search_products → check_fulfillment → check_inventory → rerank → generate_localized_copy → RecommendationPlan` | 活动约束、履约检查和本地化活动文案 |
| `retention` | `get_user_profile → get_recent_orders → search_products → check_inventory → rerank → generate_retention_copy → RecommendationPlan` | 基于用户与近期订单的 win-back 推荐 |

不同 Scene 只执行所需工具；服务端会拒绝越过当前路径的工具调用。推荐链路中的用户、商品、库存、订单和 user events 使用 Java 数据服务连接 PostgreSQL/Redis；售后是第二业务场景，不改变推荐主链。

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
| `ECOM_EMBEDDING_BASE_URL` | `https://dashscope.aliyuncs.com/compatible-mode/v1` | live 时可覆盖，指向 provider 的 embeddings endpoint base URL |
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
