# TikTok Shop AI 全栈工程师面试问答

> 岗位方向：AI 全栈工程师（业务平台）  
> 项目方向：面向跨境电商推荐场景的多 Agent 运营工作台  
> 回答原则：诚实定位为“面试级完整 Agent 工程化 demo / 原型系统”，不要夸大成生产级系统。

## 一、项目与业务理解

### 1. 你这个项目主要解决什么问题？

这个项目面向东南亚跨境电商推荐与营销场景，目标不是简单调用一次大模型，而是把推荐链路拆成多个可控的 Agent 步骤，包括用户画像解析、商品候选召回与重排、库存履约校验、营销文案生成，并通过 SSE、Metrics、评测回归把执行过程观测出来。

如果从业务价值讲，它解决三个问题：

- 推荐链路透明：运营或研发能看到每一步 Agent 做了什么。
- 大模型可控：通过工具白名单、最大步数、重复调用检测、Evidence ID 校验限制模型乱调工具和编造结果。
- 工程可迭代：通过延迟、工具调用、Token 估算和评测结果持续优化 Agent 效果。

### 2. 为什么叫“跨境电商推荐场景”？

因为这个项目不是普通单站推荐，而是显式考虑了跨境业务约束。代码里的商品和请求都包含国家/地区、币种、语言、平台、海外仓、配送时效、库存、是否跨境可售等字段。

普通推荐可能只看用户偏好和商品相关性，但跨境推荐还要判断：

- 商品是否支持目标国家销售；
- 币种是否匹配；
- 海外仓和配送时效是否可接受；
- 当前库存是否可履约；
- 营销文案是否符合当地语言和场景。

所以我把推荐链路设计成“画像 + 召回重排 + 库存履约 + 本地化文案”的多 Agent 流程。

### 3. 为什么选择东南亚业务？

TikTok Shop 本身在东南亚有比较典型的跨境电商场景。东南亚市场存在多国家、多语言、多币种、多平台、多仓履约等问题，例如新加坡、马来西亚、泰国、印尼、越南之间的语言、币种、配送时效和用户偏好都不同。

所以这个项目用东南亚场景比较自然，可以体现跨境电商推荐和普通推荐的差异。

### 4. 运营工作台具体给谁用？

主要面向电商运营、算法运营或平台研发。它不是一个纯 C 端推荐页面，而是一个能观察和调试 Agent 推荐链路的工作台。

用户可以看到：

- 推荐任务请求；
- Agent 执行步骤；
- SSE 实时事件；
- 推荐结果；
- 工具调用记录；
- 延迟、Token 估算、失败原因；
- 基础评测结果。

目前项目更偏原型系统，后续如果生产化，会把这些运行记录落库，并在前端形成 Agent Timeline、Tool Calls、Metrics Dashboard。

### 5. 如果接入 TikTok Shop 真实业务，第一步会怎么做？

我会先选一个低风险、可观测的推荐运营场景，比如“跨境商品推荐解释与营销文案生成”，而不是直接替代主推荐链路。

落地路径是：

1. 先接真实商品库、库存和用户行为特征，只让 Agent 生成候选解释或营销文案；
2. 用离线评测和人工审核验证输出质量；
3. 接入灰度流量，用 A/B Test 观察点击率、转化率、停留时长、人工运营效率；
4. 最后再考虑让 Agent 参与更核心的重排或策略决策。

这样风险更可控。

## 二、Agent 架构

### 6. 你的 Agent 架构是自建的，还是基于 LangChain / LangGraph？

Java 版本是自建的 Agent 编排框架，不是 LangChain，也不是 LangGraph。项目使用 Spring Boot 作为服务框架，通过 Spring AI 接入大模型能力。

整体架构分三层：

- `SupervisorOrchestrator`：稳定的多 Agent 工作流编排；
- `ConstrainedToolLoopService`：受限 Tool Loop，偏规则驱动；
- `AutonomousAgentLoopService`：更接近真实 Agent Loop，由模型 Planner 决定下一步 Action。

我这样设计是因为 Java 生态里 Spring Boot 更适合业务平台集成，自建编排也方便把并发、超时、SSE、指标、Evidence 校验这些工程能力接进去。

### 7. 项目里有几个 Agent？分别做什么？

核心有 4 个业务 Agent：

- `UserProfileAgent`：解析用户画像，结合用户行为和请求上下文生成偏好、价格区间、地区语言等信息。
- `ProductRecAgent`：负责商品候选召回和 LLM 重排，结合跨境约束筛选商品。
- `InventoryAgent`：负责库存和履约校验，判断商品是否有库存、是否可配送。
- `MarketingCopyAgent`：负责生成本地化营销文案。

这 4 个 Agent 不是完全自治互聊，而是由 Supervisor 或 Tool Loop 编排调用。

### 8. Supervisor 模式在项目里怎么体现？

`SupervisorOrchestrator` 是主推荐链路的调度者。它负责把一次推荐请求拆成多个阶段：

1. 用户画像解析和商品召回并行执行；
2. 商品重排和库存校验并行执行；
3. 根据库存过滤最终商品；
4. 生成本地化营销文案；
5. 聚合结果并返回 `RecommendationResponse`。

Supervisor 的价值是稳定、可控、低延迟，适合线上主链路。它不像完全自主 Agent 那样每一步都让模型决定，而是用固定流程保证推荐任务能稳定完成。

### 9. Agent 之间怎么通信？

当前不是 Agent-to-Agent 协议，也不是消息队列通信。Agent 之间通过 Java 对象、`Map`、`AgentResult` 和 `CompletableFuture` 传递上下文和结果。

例如 `UserProfileAgent` 输出 `UserProfile`，后续 `ProductRecAgent` 重排时会把这个画像作为输入；`InventoryAgent` 输出可用商品 ID，后续过滤阶段会根据这些 ID 得到最终商品。

面试时我会明确说：项目的 Agent 通信是由 Supervisor 统一编排的上下文传递，不是多个 Agent 自主协商通信。

### 10. 这个到底是真 Agent，还是工作流？

两者都有，但主链路更偏工作流。

- `/recommend` 对应 `SupervisorOrchestrator`，是稳定的多 Agent 工作流。
- `/recommend/tool-loop` 对应 `ConstrainedToolLoopService`，是受限工具循环。
- `/recommend/agent-loop` 对应 `AutonomousAgentLoopService`，更接近真实 Agent Loop，支持 Thought -> Action -> Observation，由 Planner 决定下一步工具。

所以最准确的说法是：核心推荐链路用 Supervisor 工作流保证稳定性，同时提供受限 Tool Loop 和 Autonomous Agent Loop 来体现 Agent 自主调用工具能力。

### 11. `SupervisorOrchestrator`、`ConstrainedToolLoopService`、`AutonomousAgentLoopService` 有什么区别？

三者的区别在于“谁决定下一步”：

- `SupervisorOrchestrator`：代码固定流程决定下一步，适合稳定主链路。
- `ConstrainedToolLoopService`：代码根据当前上下文选择下一步工具，同时做白名单、最大步数、重复调用检测。
- `AutonomousAgentLoopService`：模型 Planner 根据已有 Observation 决定下一步 Action，服务端再做白名单、可信参数、重复调用和 Evidence 校验。

重构后，这三者共享 `RecommendationPipelineExecutor` 执行工具，只保留决策方式不同，避免同一套工具逻辑重复写三遍。

### 12. Thought -> Action -> Observation 在哪里实现？

主要在 `AutonomousAgentLoopService`。

流程是：

1. Planner 根据当前状态生成 `thought` 和 `action`；
2. 服务端检查 action 是否在工具白名单内；
3. 服务端重建可信参数，不完全相信模型传入参数；
4. 调用对应工具，得到 `ToolObservation`；
5. 把 Observation 和 Evidence 放回上下文；
6. 模型下一轮继续基于 Observation 选择工具；
7. 满足条件后调用 `final_answer`，并校验 Evidence ID。

这就是 ReAct 风格的 Thought -> Action -> Observation 循环。

## 三、Tool Calling、Spring AI 与大模型

### 13. 模型是怎么选择工具的？

在 `AutonomousAgentLoopService` 里，模型 Planner 会根据当前状态输出 JSON，包含 `thought`、`action`、`arguments`、`finalAnswer`、`evidenceIds`。

但模型输出不是直接执行，服务端还会做几层约束：

- action 必须在工具白名单里；
- arguments 只是建议，服务端会重建 userId、country、currency、platform 等可信参数；
- 重复工具调用会被拦截；
- final_answer 必须引用已有 Evidence ID。

所以模型有一定自主选择工具能力，但执行权掌握在服务端。

### 14. Tool Calling 是原生 function calling 吗？

当前更准确地说是自建工具调用循环，不是完全依赖 OpenAI function calling 或 LangChain Tool。

模型输出结构化 JSON，服务端根据 action 映射到内部工具，比如：

- `get_user_profile`
- `search_cross_border_products`
- `rerank_products`
- `check_fulfillment_inventory`
- `filter_products`
- `generate_localized_copy`

这种方式的好处是可控，方便加入白名单、最大步数、重复检测和 Evidence ID 校验。

### 15. 为什么要做工具白名单、最大步数和重复调用检测？

因为 Agent 系统不能完全信任模型自由行动。模型可能会：

- 调不存在的工具；
- 重复调用同一个工具造成空转；
- 在没有足够证据时直接输出结论；
- 构造越权参数。

所以我做了这些限制：

- 工具白名单：只允许调用业务定义过的工具；
- 最大步数：防止无限循环消耗 Token；
- 重复调用检测：防止模型卡在同一步；
- 固定停止条件：确保有足够上下文才能生成最终答案；
- 服务端可信参数重建：避免模型篡改用户、国家、币种等关键参数。

### 16. Evidence ID 校验解决什么问题？

Evidence ID 校验主要解决模型幻觉和无证据引用问题。

在 Agent Loop 里，每次工具调用都会产生 Evidence，比如：

- `profile:user_001`
- `product:P001`
- `inventory:P001`
- `copy:P001`

最终回答时，模型必须引用已有 Evidence ID。如果引用了不存在的 Evidence ID，服务端会拦截 `final_answer`，返回 blocked。

这可以避免模型编造商品、库存、价格或引用不存在的信息。

### 17. Spring AI 在项目里怎么用？

项目通过 Spring AI 的 `ChatClient` 接入大模型能力。几个 Agent 会使用 LLM 做不同任务：

- 用户画像解析：把用户行为和请求上下文转成结构化画像；
- 商品候选重排：结合用户画像、商品信息和跨境约束进行 LLM rerank；
- 营销文案生成：根据商品、用户画像、locale、country 生成本地化文案；
- Autonomous Agent Loop：Planner 根据当前状态决定下一步工具。

Spring AI 的作用是把模型调用接入 Spring Boot 服务体系，便于结合 Bean、配置、测试和业务服务。

### 18. LLM 重排和传统排序有什么区别？

传统排序通常依赖特征工程和模型训练，比如 CTR/CVR 模型、GBDT、DeepFM 等，适合大规模稳定线上推荐。

LLM 重排适合冷启动、解释性和复杂约束场景。它可以理解用户画像、商品描述、国家、币种、库存、语言等文本和结构化信息，给出更灵活的排序依据。

但 LLM 重排也有缺点：

- 成本更高；
- 延迟更大；
- 输出不稳定；
- 需要服务端约束防止幻觉。

所以我的项目里采用“召回 + LLM 重排 + 库存过滤”的方式，而不是完全依赖 LLM 生成推荐结果。

### 19. 如果 LLM 返回格式不稳定怎么办？

我的处理思路是：

1. Prompt 中要求模型返回 JSON；
2. 服务端用结构化解析；
3. 解析失败时走 fallback 决策；
4. 关键业务字段不信任模型，由服务端重建；
5. 最终结果必须通过 Evidence ID 校验。

这样即使模型输出不稳定，系统也不会直接崩掉或执行危险操作。

### 20. 大模型超时或失败怎么处理？

在 `BaseAgent` 里做了超时、重试和 fallback。Agent 通过有界线程池异步执行，如果超时、异常或线程池拒绝，会返回失败的 `AgentResult`，而不是让整个服务无限等待。

Supervisor 侧会对失败结果做降级，比如没有画像就用默认画像，没有重排结果就回退到召回结果，没有文案就返回空文案列表。

这种设计适合业务平台：模型失败不能拖垮整条链路。

## 四、全栈接口与前后端协作

### 21. 前端和后端怎么交互？

后端通过 REST API 和 SSE 提供能力：

- `POST /api/v1/recommend`：普通推荐；
- `POST /api/v1/recommend/stream`：SSE 实时推荐事件；
- `POST /api/v1/recommend/tool-loop`：受限 Tool Loop；
- `POST /api/v1/recommend/agent-loop`：Autonomous Agent Loop；
- `GET /api/v1/metrics`：指标；
- `POST /api/v1/evaluations/smoke`：基础评测；
- `GET /api/v1/data/catalog/summary`：demo 数据概览。

前端工作台可以基于这些接口展示推荐任务、Agent Timeline、工具调用、推荐结果和指标面板。

### 22. SSE 在项目里解决什么问题？

Agent 任务不是瞬时完成的，中间会经过画像、召回、重排、库存、文案等多个阶段。如果只用普通 HTTP，前端只能等最终结果，用户不知道当前执行到哪一步。

SSE 可以把每一步事件实时推给前端，比如：

- run started；
- agent started；
- agent completed；
- phase completed；
- run completed。

这样运营工作台可以展示 Agent 执行时间线，方便调试和观察。

### 23. 为什么不用轮询？

轮询实现简单，但缺点是：

- 请求次数多；
- 实时性差；
- 后端压力更大；
- 前端状态同步麻烦。

SSE 更适合服务端向前端持续推送 Agent 执行事件，尤其是 Agent 运行过程天然是事件流。

项目里还把 SSE 线程池和 Agent 执行线程池拆开，避免长连接占满 Agent 执行资源。

### 24. 如果要把运行事件落 PostgreSQL，你会设计哪些表？

我会设计至少 4 张表：

- `agent_run`：一次推荐运行，包含 run_id、user_id、country、currency、status、latency、created_at；
- `agent_event`：运行事件，包含 run_id、sequence、event_type、status、summary、payload；
- `tool_call`：工具调用记录，包含 run_id、tool_name、arguments、status、latency、error_message；
- `evaluation_run`：评测记录，包含 run_id、score、checks、failed_reason、created_at。

如果要更完整，还可以加：

- `recommendation_result`：最终推荐商品和文案；
- `retrieval_evidence`：Evidence ID 和证据来源。

当前 Java demo 还没有完整生产化落库，所以面试时我会说这是后续 P0 补强项。

### 25. Redis 在项目里做什么？

Redis 主要用于用户行为和画像特征缓存，比如用户最近浏览、点击、购买、偏好类目等。推荐链路需要低延迟读取这些特征，所以 Redis 比直接查关系型数据库更适合。

当前项目里的 `RedisFeatureStoreService` 支持行为写入和特征读取，同时 demo 环境下 Redis 不可用会走内存 fallback，方便本地演示。

生产环境里会把 Redis 作为实时 Feature Store，结合埋点流、订单流和商品交互流更新用户特征。

## 五、性能、并发与可靠性

### 26. 高并发请求来了系统怎么保护自己？

项目做了几层基础保护：

- `AgentConcurrencyGuard` 用 Semaphore 控制同时运行的 Agent 请求数；
- 超过容量直接返回 429，避免请求无限堆积；
- `agentExecutor` 是有界线程池，限制 Agent 执行资源；
- `sseExecutor` 单独处理 SSE 长连接，避免占用 Agent 线程；
- 单个 Agent 有超时、重试和 fallback；
- Tool Loop 有最大步数和重复调用检测，避免模型空转。

我不会说它已经是生产级高并发系统，因为还没有正式压测报告。但它已经具备基础并发防护和生产化扩展点。

### 27. 为什么用 Semaphore？

Semaphore 适合做单机并发准入控制。Agent 请求通常成本比较高，会消耗线程、大模型调用和 Token 预算。如果不限制并发，高峰期可能把线程池、模型 API 或下游服务打满。

所以入口层先用 Semaphore 判断是否还有运行名额。如果没有，快速返回 429，让系统保持稳定。

生产化时可以进一步扩展成 Redis 分布式限流或网关限流。

### 28. 为什么 Agent 线程池和 SSE 线程池要分开？

SSE 是长连接，请求可能持续较长时间。如果 SSE 和 Agent 执行共用线程池，大量前端连接可能占住执行线程，导致真正的 Agent 任务无法调度。

所以项目里把它们拆开：

- `agentExecutor`：执行画像、推荐、库存、文案等 Agent 任务；
- `sseExecutor`：处理事件流推送。

这属于资源隔离，可以提升系统稳定性。

### 29. 如果 100 个请求同时进来会怎样？

在当前配置下，系统会先经过并发 Guard。容量内的请求会进入 Agent 执行流程，超过容量的请求会被快速拒绝并返回 429。

进入执行流程后，Agent 任务会进入有界线程池。线程池满了也不会无限创建线程，避免拖垮服务。

所以它不是保证 100 个都成功，而是保证系统在突发流量下不会失控。面试里我会强调：这是基础保护机制，不是正式压测 QPS 承诺。

### 30. 你怎么记录 Token、延迟和工具调用指标？

项目里有 `MetricsCollector`，会记录：

- 推荐调用次数；
- 平均延迟；
- Agent 成功率；
- Agent 最后错误；
- 工具调用次数；
- 工具成功率；
- blocked tool call；
- timeout agent；
- rejected run；
- Token 估算值。

这里的 Token 更准确说是估算指标，不是 billing 级别的真实模型账单。生产化时可以接入模型 API 返回的 usage 字段，并把指标上报到 Prometheus / Grafana。

## 六、数据、评测与 RAG

### 31. 当前数据是真实数据还是 mock 数据？

当前主要是 deterministic demo seed，不是真实生产数据。这样做是为了本地演示和测试回归稳定。

demo 数据覆盖了跨境推荐所需字段：

- 国家/地区；
- 币种；
- 平台；
- 类目；
- 海外仓；
- 配送时效；
- 库存；
- 是否跨境可售。

面试里我会诚实说明：它不是生产数据，但数据结构是按真实跨境推荐约束抽象的，后续可以把 connector 替换为真实商品库或 Shopify API。

### 32. 评测回归怎么做？

项目里有基础 `RecommendationEvaluator` 和 smoke evaluation。评测维度包括：

- 是否返回足够商品；
- 商品是否符合国家和币种；
- 是否有库存；
- 是否生成营销文案；
- Agent 是否成功；
- 延迟和工具调用是否在可接受范围内。

它目前是基础评测回归，不是完整推荐算法评测。生产化还要加入点击率、转化率、GMV、退货率、人工审核通过率等业务指标。

### 33. Evidence ID 和 RAG 有什么区别？

Evidence ID 校验不是完整 RAG。

RAG 通常包括文档切分、Embedding、向量检索、召回证据、基于证据生成回答。当前项目没有完整向量库检索链路，所以不能夸大成完整 RAG。

当前更准确的说法是：工具调用产生结构化 Evidence，最终回答必须引用已有 Evidence ID，用来约束模型不要编造信息。

如果要升级成完整 RAG，可以引入商品知识、政策文档、运营规则，通过 pgvector 或 Milvus 做向量检索，再把召回证据纳入 Evidence ID 校验。

## 七、AI Coding 与工程能力

### 34. 你在项目里怎么使用 AI Coding 工具？

我把 AI Coding 工具当作协作开发助手，而不是直接让它无约束改代码。

我的流程是：

1. 先让 AI 分析项目短板；
2. 我确定优先级和边界；
3. 让 AI 做小范围补丁；
4. 我审查代码结构、命名、依赖、测试；
5. 本地运行 `mvn test`；
6. 如果设计不合理，再要求重构。

比如最近一次我发现三个服务重复实现同一套流水线，就把共性执行逻辑抽成 `RecommendationPipelineExecutor` 和 `RecommendationPipelineState`，让三层服务只保留决策差异。

### 35. AI 生成代码你重点审查什么？

我主要审查：

- 有没有改错业务边界；
- 有没有过度抽象；
- 有没有破坏现有接口；
- 有没有重复逻辑；
- 有没有引入不必要依赖；
- 并发和异常处理是否安全；
- 测试是否覆盖关键路径；
- 简历口径是否和代码一致。

AI 代码能跑不代表设计合理，所以我会重点看架构是否更清晰、后续维护成本是否降低。

### 36. 最近一次重构解决了什么问题？

最近一次重构解决了三套服务重复实现同一条推荐流水线的问题。

原先 `SupervisorOrchestrator`、`ConstrainedToolLoopService`、`AutonomousAgentLoopService` 都分别写了 profile、recall、rerank、inventory、filter、copy 的逻辑，后续加一个工具或改一个跨境字段要改三遍。

现在抽成：

- `RecommendationPipelineState`：统一保存运行上下文；
- `RecommendationPipelineExecutor`：统一执行 Agent 工具、库存过滤、Evidence 生成、响应构建。

重构后：

- Supervisor 只负责阶段式并行编排；
- Constrained Tool Loop 只负责白名单和固定顺序；
- Autonomous Agent Loop 只负责 Planner 和 Evidence 校验。

测试 `mvn test` 通过，说明行为没有被破坏。

## 八、项目不足与改进

### 37. 这个项目最大的不足是什么？

最大不足是生产真实性还不够。

具体包括：

- 当前数据主要是 demo seed，不是真实商品库；
- Java 主链路还没有完整 PostgreSQL 落库；
- 高并发只有基础防护，没有正式压测报告；
- 前端工作台展示能力需要实际联调确认；
- Evidence ID 不是完整 RAG；
- Token 成本目前更偏估算。

所以我会把它定位为 Agent 工程化 demo，而不是生产级系统。

### 38. 如果再给你一天，你优先补什么？

我会优先补 5 件：

1. 补 AgentRun、ToolCall、RecommendationResult、EvaluationRun 的 H2/PostgreSQL 落库；
2. 补前端 Agent Timeline、Tool Calls、Metrics 面板；
3. 补轻量压测脚本，记录成功数、429 数、平均延迟；
4. 强化 Agent Loop 的 JSON schema 和非法工具测试；
5. 补 Docker Compose 一键启动说明。

这些补强最能提升项目可信度，也最贴合 TikTok Shop AI 全栈岗位。

### 39. 如果线上推荐结果异常，你怎么排查？

我会按链路拆：

1. 看请求参数：国家、币种、平台、numItems 是否正常；
2. 看 SSE 事件：卡在哪个 Agent 或哪个 phase；
3. 看 ToolCall：哪个工具失败、是否 blocked、是否 timeout；
4. 看 AgentResult：画像、召回、重排、库存、文案哪个输出异常；
5. 看 Metrics：延迟、失败率、rejected run 是否升高；
6. 看数据源：商品、库存、Redis 特征是否缺失；
7. 看 LLM：是否 prompt 解析失败、返回格式异常或模型超时。

这样能把问题定位到参数、数据、模型、工具、并发或前端展示其中一层。

### 40. 如果要从 demo 变成生产系统，你会怎么演进？

我会分阶段演进：

第一阶段：生产化数据接入  
接真实商品库、库存、用户行为和订单数据，替换 demo connector。

第二阶段：运行记录落库  
把 AgentRun、ToolCall、Evidence、EvaluationRun 落 PostgreSQL。

第三阶段：观测和评测  
接 Prometheus / Grafana，补离线评测集和线上 A/B Test。

第四阶段：高并发治理  
引入网关限流、Redis 分布式限流、熔断、降级、压测报告。

第五阶段：更完整的 RAG 和策略系统  
接 embedding、向量库、运营规则和政策知识库。

这样逐步从面试级 demo 走向真实业务平台。

## 九、最稳项目介绍

面试开场可以这样说：

> 我这个项目是围绕东南亚跨境电商推荐与营销场景做的一个多 Agent 运营工作台原型。Java 后端基于 Spring Boot 自建 Agent 编排框架，并通过 Spring AI 接入大模型能力。主链路用 Supervisor 编排用户画像、商品推荐重排、库存履约校验和营销文案生成，保证推荐流程稳定；增强链路实现了受限 Tool Loop 和 Autonomous Agent Loop，支持工具白名单、最大步数、重复调用检测、服务端可信参数重建和 Evidence ID 校验。工程上还做了 SSE 实时事件流、Redis 用户特征、Metrics 指标、并发准入、有界线程池和基础评测回归。当前它是一个面试级完整 demo，不是生产级系统，后续生产化会重点补真实数据接入、PostgreSQL 落库、压测和完整前后端联调。

## 十、不能这么说的危险话术

不要说：

> 这是一个生产级 TikTok Shop 跨境电商多 Agent 系统，已经支持真实 Shopify 数据、完整 PostgreSQL 落库、完整 RAG、生产级高并发、Agent 自主通信和全量线上部署。

这句话风险很大。面试官只要追问表结构、压测数据、真实 API、RAG 向量库、Agent 通信协议，就容易被问穿。

最稳的定位是：

> 完整度较高的 AI Agent 工程化 demo，重点展示全栈交付、Agent 编排、工具约束、可观测、并发保护和跨境推荐业务建模能力。
