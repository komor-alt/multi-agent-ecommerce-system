# Java Cross-Border Agent Implementation Notes

## Scope

The Java service is the core runtime for a cross-border ecommerce recommendation workstation. It uses Spring Boot plus Spring AI, with a self-built Supervisor workflow and a bounded Agent Loop. It does not call the production Shopify API in this demo.

## Current Platform Connector

`ShopifyDevelopmentStoreConnector` is a mock connector that simulates a Shopify Development Store catalog. It recalls platform-level candidates from SEA products across SG, MY, TH, ID, and VN. `ProductRecAgent` then applies the server-side cross-border filters for country/region support, currency, platform, and `crossBorderEligible`, so blocked products can be surfaced in agent output.

## Persistence Boundary

Java exposes recommendation, SSE, tool-loop, behavior, feature, metrics, and smoke-evaluation APIs. Redis-backed behavior features are implemented in Java through `RedisFeatureStoreService` with memory/request fallback. PostgreSQL task/event persistence belongs to the existing Gateway/workstation layer; Java responses and SSE events are shaped so the workstation can persist them.

## Core Verification Points

- Request context: `platform`, `region`, `country`, `locale`, `currency`.
- Product context: `supportedRegions`, `currency`, `warehouseRegion`, `deliveryDays`, `platform`, `crossBorderEligible`.
- Business usage: Product recommendation filters by platform/country/region/currency/cross-border eligibility; inventory checks fulfillment and delivery; marketing copy uses locale/country/currency.
- Agent Loop: default tools are `get_user_profile`, `search_cross_border_products`, `rerank_products`, `check_fulfillment_inventory`, `filter_products`, `generate_localized_copy`, `final_answer`.
- Safety controls: tool whitelist, max steps, duplicate call fingerprint, server-side trusted arguments, and final-answer Evidence ID validation.


## 数据与高并发防护补充

### 数据补位现在怎么做

Java 版不再只是几条写死商品。`DemoCatalogDataFactory` 构造了一个确定性的 demo 商品目录，当前覆盖 60 个商品，包含 Shopify/Shopee 平台、SG/MY/TH/ID/VN 市场、多币种、多类目、多海外仓区域、低库存/无库存、以及 `crossBorderEligible` 可跨境标记。这样面试时可以明确说：当前不是生产数据，但已经具备跨境推荐链路需要的字段维度和可回归的 deterministic 测试数据。

相关入口：

- `GET /api/v1/data/catalog`：查看 demo 商品目录。
- `GET /api/v1/data/catalog/summary`：查看数据覆盖范围，例如国家、币种、平台、仓库、库存状态。
- `POST /api/v1/data/seed-behaviors`：写入一批确定性用户行为到 Redis Feature Store；Redis 不可用时走内存兜底。

面试口径：

> 这个项目的数据层分成三类。商品侧用模拟 Shopify Development Store 的 deterministic catalog，重点覆盖跨境推荐约束字段，比如国家、币种、平台、海外仓、配送天数、库存和跨境可售状态。用户行为侧通过 RedisFeatureStoreService 做实时特征写入，demo 环境 Redis 不可用时有内存 fallback。履约侧提供确定性的订单/库存/SLA 风险样例，用来解释为什么推荐链路需要库存校验和降级兜底。这样既能本地稳定演示，也能把接口边界留给真实商品库、订单库、WMS/物流接口和埋点流。

### 高并发来了怎么防

Java Agent 服务现在不是无限制创建线程或无脑调用模型，而是有几层保护：

- `AgentExecutionConfig` 定义有界 `agentExecutor`，用于 Agent 并行执行；同时定义独立 `sseExecutor`，避免 SSE 长连接占满 Agent 执行线程。
- `AgentConcurrencyGuard` 使用 `Semaphore` 限制同时运行的推荐/Tool Loop/Agent Loop/评测请求。超过容量时 Controller 返回 HTTP 429，而不是继续堆积请求拖垮服务。
- `BaseAgent.runAsync(params, executor)` 统一接入线程池执行，并带超时控制；异常、超时、线程池拒绝时返回失败结果，Supervisor 可做降级兜底。
- `SupervisorOrchestrator` 将可并行的画像分析与商品召回、重排与库存校验并行执行，减少串行等待。
- `ConstrainedToolLoopService` 和 `AutonomousAgentLoopService` 共享同一个 Agent 执行池，并保留工具白名单、最大步数、重复调用检测、固定停止条件、可信参数重建和 Evidence ID 校验，避免模型在高并发下空转或越权扩大消耗。
- `MetricsCollector` 增加 `protection` 指标，记录被拒绝请求、超时 Agent、被阻断工具调用；`/api/v1/metrics` 同时返回 `runtime_guard` 当前并发状态。

面试口径：

> 我不会说这个 demo 已经做过生产级压测，但它具备基础并发防护。入口层用 Semaphore 做并发准入，超过阈值直接 429；执行层用有界线程池隔离 Agent 任务和 SSE 长连接；单个 Agent 有超时和失败降级；Agent Loop 还有最大步数、重复调用检测和工具白名单，避免一次请求无限消耗模型资源。后续生产化可以把本地 Semaphore 扩展成 Redis 分布式限流，并接入压测指标做容量评估。

### 面试官可能追问

**Q：你的数据是不是太少，能说明真实业务吗？**

A：当前是 demo 数据，不是生产数据。我刻意用 deterministic seed，而不是随机数据，是为了稳定演示和 CI 回归。数据覆盖了跨境推荐的关键维度：国家/地区、币种、平台、海外仓、库存、配送时效、是否可跨境销售，以及订单履约状态、SLA 风险和受限商品。真实上线时只需要把 `ShopifyDevelopmentStoreConnector` 和 demo fulfillment 数据替换为真实商品、OMS、WMS、物流和埋点 connector，Agent 上层编排逻辑不需要大改。

**Q：这些订单/履约数据有没有真的参与推荐？**

A：当前推荐主链路直接使用商品 catalog、用户行为特征和库存 Agent；订单/履约 seed 主要用于演示数据维度、观测接口和面试讲解。生产扩展时可以把履约状态作为 InventoryAgent 的输入，把历史订单和退货行为作为 UserProfileAgent 的画像特征。

**Q：高并发下会不会把大模型调用打爆？**

A：不会直接无界放大。请求入口有 `AgentConcurrencyGuard`，执行层有有界线程池和队列，Agent 本身有超时，Agent Loop 有最大步数和重复调用检测。超过容量会快速返回 429，保护系统和模型预算。

**Q：为什么 SSE 要单独线程池？**

A：SSE 是长连接，如果和 Agent 执行共用线程池，连接多时可能占住执行线程，导致新任务无法调度。单独 `sseExecutor` 可以做资源隔离。

**Q：为什么不直接说支持高并发？**

A：面试中要严谨。当前代码实现的是基础并发治理和保护机制，包括准入、隔离、超时、降级和指标，但还没有完整压测报告。因此更稳的说法是：具备高并发场景下的防护设计，后续可以通过 Redis 分布式限流、消息队列削峰、水平扩容和压测来验证容量。
### 本轮新增的回归验证

- `application.yml` 显式暴露 `agent.guard.max-concurrent-runs`、`agent.executor.*`、`agent.sse.*`，方便在本地、Docker 和面试演示环境调整容量阈值。
- `AgentConcurrencyGuardTest` 增加 burst 并发测试，验证突发请求下只放行配置容量内的 Agent Run，其余请求被拒绝并计入 rejected 指标。
- `MetricsCollectorTest` 覆盖 rejected run、timeout agent、blocked tool call 和跨境国家/币种统计，避免后续改动把保护指标破坏。

面试补充口径：

> 我把高并发防护做成了可配置能力，不是写死在代码里。比如 `ECOM_AGENT_MAX_CONCURRENT_RUNS` 可以控制同时运行的 Agent 请求数，Agent 执行池和 SSE 执行池也能通过环境变量调整。测试里专门模拟了突发并发，确认只有容量内请求会进入执行，其余会被快速拒绝并进入指标。
## 流水线重构说明

原先 `SupervisorOrchestrator`、`ConstrainedToolLoopService`、`AutonomousAgentLoopService` 都各自实现了一套 `profile -> recall -> rerank -> inventory -> filter -> copy` 执行逻辑，后续维护成本较高。现在将公共执行层抽到两个共享组件：

- `RecommendationPipelineState`：保存一次推荐运行中的共享上下文，包括用户画像、召回商品、重排商品、库存可用 ID、最终商品、营销文案、AgentResult 和 Evidence ID。
- `RecommendationPipelineExecutor`：统一封装用户画像、商品召回、商品重排、库存履约校验、最终过滤、营销文案生成、可信参数重建、EvidenceRecord 生成和 RecommendationResponse 构建。

三层服务现在只保留各自差异：

- `SupervisorOrchestrator`：负责稳定的阶段式并行编排和 SSE 事件输出。
- `ConstrainedToolLoopService`：负责固定顺序下的工具白名单、最大步数、重复调用检测和停止条件。
- `AutonomousAgentLoopService`：负责 LLM Planner、Thought -> Action -> Observation 循环、非法工具阻断和最终 Evidence ID 校验。

面试口径：

> 早期版本里三种运行模式各自写了一套工具执行逻辑，维护成本高。我后来把共性的推荐工具执行层抽成 `RecommendationPipelineExecutor`，把运行状态抽成 `RecommendationPipelineState`。这样 Supervisor、受限 Tool Loop、自主 Agent Loop 只保留“如何决定下一步”的差异，真正调用 Agent、过滤库存、生成 Evidence 和构建响应都复用同一套实现，避免同一个业务规则改三遍。
## 轻量 Tool Registry 与 Lifecycle Hook

参考通用 Agent Runtime 的设计后，Java 版没有直接引入完整插件系统，而是在共享推荐执行层增加了两类轻量扩展点：

- `RecommendationPipelineToolRegistry`：维护工具名和别名到工具执行函数的映射，例如 `search_cross_border_products` 与 `search_products` 指向同一工具。后续新增推荐工具时，可以在执行器中注册工具，而不是继续扩展大段 `switch`。
- `RecommendationPipelineHook`：提供 `beforeTool`、`afterTool`、`onToolError` 三个生命周期钩子，可用于工具调用审计、可信参数检查、埋点、策略拦截或调试追踪。

当前内置工具仍然集中在 `RecommendationPipelineExecutor` 中注册，避免过早引入复杂插件系统。`SupervisorOrchestrator`、`ConstrainedToolLoopService` 和 `AutonomousAgentLoopService` 继续复用同一执行器，因此 Hook 和工具注册能力会同时作用于三种运行模式。

面试口径：

> 我参考过通用 Agent Runtime 的生命周期和工具注册思想，但没有把业务项目做成复杂插件框架。我的做法是在共享执行层增加轻量 `ToolRegistry` 和 `LifecycleHook`：工具名通过注册表映射到执行函数，工具执行前后会触发 hook。这样后续做审计、埋点、参数校验或新增工具，不需要改三套编排逻辑，也不会让业务系统过度框架化。