# 推荐系统多 Agent 架构

## 当前默认链路

`POST /api/v1/recommend` 与流式入口默认进入 `AutonomousAgentLoopService`。它是 Root/Supervisor Agent，负责动态创建子任务、组织依赖、专业 Agent 委派、VETO 修订和最终验收，不直接替专业 Agent 逐个调用业务工具。

| Agent | 独立目标 | 可调用工具 | Blackboard 写权限 |
|---|---|---|---|
| Profile Agent | 构建用户和近期订单上下文 | `get_user_profile`、`get_recent_orders` | `PROFILE` |
| Product Agent | 召回、VETO 后换候选、重排 | `search_products`、`rerank` | `RAW_PRODUCTS`、`RANKED_PRODUCTS` |
| Inventory Agent | 校验活动、履约和库存并否决风险商品 | `load_campaign_constraints`、`check_fulfillment`、`check_inventory` | `AVAILABLE_IDS`、`VETOES` |
| Copy Agent | 仅对最终商品生成本地化文案 | `generate_localized_copy`、`generate_retention_copy` | `COPIES` |
| Supervisor Agent | 委派、修订、验收 | `final_answer` | `FINAL_PRODUCTS` |

`UserProfileAgent`、`ProductRecAgent`、`InventoryAgent`、`MarketingCopyAgent` 仍保留，但它们现在是专业 Agent 调用的业务工具实现，不再被当作拥有自主 Loop 的 Agent。

## 一次运行的控制流

1. Root 从当前状态和 Scene Contract 得到可执行能力，运行时创建 `SubAgentTask`；每个任务记录父 Agent、依赖任务、工具范围、最大步骤和不可变 `ContextSnapshot`。
2. `SpecialistParallelPlanner` 根据每个动作由服务端声明的状态读集合与写集合，选择最多 `max-parallel-specialists` 个互不冲突的动作。批次中的任务拥有相同依赖前沿，下一批任务依赖这些兄弟任务。
3. 当批次包含至少两个动作时，Root 为每个任务冻结 `agent + tool + taskId + candidateVersion`，发送带 `parallel=true` 的 `DELEGATE` 并提交到独立协调线程池；否则创建单个子任务。
4. `SpecialistAgentTeam` 从能力定义目录为每次委派创建新的 Agent 实例。子 Agent 使用创建时的上下文快照进行规划，在独立有界 Plan-Act-Observe Loop 中执行；服务端工具仍从可信业务状态生成参数。
5. 子 Agent 的 Observation 被包装为带生产者、上下文版本和 Evidence 的 Artifact；候选派生结果通过 `CandidateStatePatch` 合并。Patch 版本落后或生产者无字段权限时拒绝写入。
6. Root 等待批次全部完成，在屏障处按任务顺序合并 `RESULT`。库存与重排都来自当前候选版本后，Root 才取 `rankedProducts ∩ availableIds` 生成 `FINAL_PRODUCTS`。
7. Inventory 发现不可用商品时发送 `VETO`。Root 随后向 Product 发送 `REQUEST_REVISION` 并创建新的召回任务；重新召回前清理旧履约、库存、重排、文案结果及对应 Evidence。
8. 所有 Scene 完成条件成立后，Root 执行 Evidence 校验并提交最终答案；完整任务 DAG 和 Artifact 随运行指标返回。

## 子 Agent Runtime

- `DynamicSubAgentRuntime`：为每次请求维护任务序号、父子身份、依赖前沿、状态机和 Artifact，不依赖具体业务工具。
- `ContextSnapshot`：子 Agent 创建时冻结候选版本、可见商品 ID、VETO 和 Evidence。LLM 规划只读取该快照，避免并行过程中 Prompt 观察到一半更新的共享状态。
- `CandidateStatePatch`：Product 只能提交排序结果，Inventory 只能提交库存与履约结果；Root 仅接受 `baseCandidateVersion` 等于当前版本的 Patch。
- 生命周期：`PENDING → RUNNING → COMPLETED/FAILED`。当前同步 API 在响应的 `llmMetrics.subAgentTasks` 中返回状态和依赖，流式 API继续通过 SSE 返回执行事件。
- Artifact：每个 Observation 生成一个不可变 Artifact，包含生产任务、角色、类型、候选版本、数据和 Evidence 引用，并出现在 `RESULT.artifactIds` 中。
- 可观测性：`GET /api/v1/agent-runs/{runId}/subagents` 返回运行状态、任务 DAG 和 Artifact；`agent.orchestration.max-retained-runs` 对已完成运行进行有界内存保留，内存未命中时从数据库投影恢复。

这一阶段已经实现“Root 动态创建任务实例、独立规划上下文、任务 DAG、Artifact 交接和版本化合并”，并持久化 Run、Task、Artifact 与 SSE Event。当前角色能力目录仍固定为四类，真正执行任务的 Runtime 仍运行在单 JVM 内；服务重启后可以恢复审计视图和事件流，但不能续跑崩溃时尚未完成的任务。嵌套子 Agent、代码沙箱和 Git worktree 也不属于当前能力。

## 持久化、一致性与恢复边界

1. `RecommendationRuntimeStore` 在 Run 创建和每次任务状态变化后写入数据库。更新 Run 时使用行锁，`stateVersion` 记录业务修订号，JPA `@Version` 防止静默覆盖。
2. Task 保存创建时的 `ContextSnapshot`、依赖、工具域和候选版本；Artifact 只追加不覆盖，因此可以核对一个结果由哪个任务和哪版候选生成。
3. 状态事务同时写入带唯一 `dedupKey` 的 `recommendation_outbox`。Dispatcher 采用至少一次投递、可配置退避和最大次数，超过阈值进入 `DEAD_LETTER`；生产消费者仍必须幂等。
4. `RecommendationRunEventService` 将 Run 内 sequence 和事件 ID 持久化。客户端通过 `Last-Event-ID` 重连时先回放数据库中缺失的事件，再订阅当前 JVM 的实时事件。
5. 未捕获的运行异常会把 Run 收口为 `FAILED`、记录失败事件并关闭 SSE 订阅，避免数据库长期留下假 `RUNNING` 状态。

数据库投影解决的是审计、查询和断线回放，不等于分布式任务队列。若要让执行中任务在进程故障后续跑，需要进一步把 Task 增加 claim/lease/attempt/nextRetry 状态，由独立 Worker 从 Broker 消费并以任务幂等键提交结果；当前代码没有声称具备这一能力。

## 实际并行边界

- `campaign` 初始阶段：`load_campaign_constraints ∥ search_products`。商品召回实现不读取活动约束，两者可安全并行；活动约束在后续 `check_fulfillment` 使用。
- 候选商品产生后：`check_inventory ∥ rerank`。两者只读候选商品，分别写库存集合与排序结果；Supervisor 在屏障后做最终交集。
- `check_fulfillment` 不能与 `rerank` 并行，因为履约检查可能过滤并改写候选商品；状态读写契约会识别这一冲突。
- Copy 必须等待 `FINAL_PRODUCTS`，因此不会与库存或重排并行。
- VETO 后的再次召回必须等待旧批次结束并完成状态失效，不与旧候选的下游动作交叉。

这里采用的是“依赖感知的部分并行”，不是把四个 Agent 无条件同时启动。这样能缩短独立 I/O 阶段的关键路径，同时保留业务依赖和结果一致性。推测式重排可能在库存 VETO 时产生一次无效计算，因此可用 `agent.orchestration.speculative-rerank-enabled=false` 关闭，以延迟换调用成本。

## 并发与状态保护

- 每次请求创建独立 `RecommendationPipelineState`，不同用户请求不共享任务状态；多个请求由 Web 容器并发处理。
- 单个请求内的 `agentResults`、Evidence 集合、数据源、VETO 和拒绝写记录使用并发集合；可变快照使用 `volatile` 与不可变副本发布，受保护的 Blackboard 写入和 VETO 状态变更使用同步方法。
- 状态字段有唯一写入者，例如 Product 写 `RANKED_PRODUCTS`、Inventory 写 `AVAILABLE_IDS`、Supervisor 写 `FINAL_PRODUCTS`。并行规划器还会拒绝读写或写写冲突的动作组合。
- 协调线程池和底层业务调用线程池分离，避免嵌套 Future 互相占满造成饥饿；线程数、队列长度和最大并行专业 Agent 数均可配置，队列满时由调用线程执行形成反压。
- 并行工具完成顺序不决定业务合并顺序；Supervisor 按冻结计划合并结果，使 Trace 和最终状态可重复。

## 为什么这不是固定工作流换名字

- 调度单位从“下一工具”变成“下一专业 Agent”；Supervisor 不执行专业工具。
- 每个专业 Agent 都有独立身份、目标、Prompt、局部循环和严格工具域。
- 一个专业 Agent 在一次委派中可以执行多个动作，也可以因为需要其他 Agent 的证据而主动交还控制权。
- Agent 之间通过结构化消息和 Blackboard 协作，Inventory 的 VETO 会改变 Product 后续计划。
- `ScenePathEnforcer` 给出允许集合和安全前置条件，不要求 LLM 严格复述唯一的下一工具。

这仍然是中心化、受约束的多 Agent，而不是无边界的自由群聊。业务安全和完成条件由服务端控制。

## 模式和基线

- 默认 `RULES`：零网络、零模型费用，用于 CI 和离线回归；是多 Agent Runtime 的确定性 Planner fallback。
- `LLM`：需要显式设置 live 开关、API Key 和正的调用预算；Supervisor 与专业 Agent 都会做模型规划，但输出必须通过允许集合、工具域、前置条件、重复调用和 Evidence 校验。
- 固定工作流基线：`POST /api/v1/recommend/workflow-baseline`，仅用于与多 Agent 运行时做对照，不是默认产品入口。

## 关键实现

- `java/src/main/java/com/ecommerce/service/AutonomousAgentLoopService.java`
- `java/src/main/java/com/ecommerce/service/SpecialistAgentTeam.java`
- `java/src/main/java/com/ecommerce/service/SpecialistParallelPlanner.java`
- `java/src/main/java/com/ecommerce/runtime/DynamicSubAgentRuntime.java`
- `java/src/main/java/com/ecommerce/runtime/persistence/RecommendationRuntimeStore.java`
- `java/src/main/java/com/ecommerce/runtime/persistence/RecommendationRunEventService.java`
- `java/src/main/java/com/ecommerce/runtime/persistence/RecommendationOutboxDispatcher.java`
- `java/src/main/resources/migration-recommendation-runtime-postgresql.sql`
- `java/src/main/java/com/ecommerce/service/CandidateStatePatch.java`
- `java/src/main/java/com/ecommerce/service/ScenePathEnforcer.java`
- `java/src/main/java/com/ecommerce/service/RecommendationPipelineState.java`
- `java/src/main/java/com/ecommerce/config/RecommendationOrchestrationProperties.java`
- `java/src/main/java/com/ecommerce/config/AgentExecutionConfig.java`
- `java/src/main/java/com/ecommerce/config/RecommendationController.java`
