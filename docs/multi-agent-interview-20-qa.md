# 跨境电商多 Agent 项目：20 个高频面试问题与参考回答

> 对应项目：`multi-agent-ecommerce-system-tau-eval` Java 推荐主链  
> 回答原则：只描述当前代码已经实现的能力，不把规划中的能力说成现状。

## 1. 请用一分钟介绍一下这个项目

我做的是一个面向东南亚跨境电商推荐与营销场景的多 Agent 系统，支持首页推荐、营销活动和用户召回三类任务。

系统采用 Supervisor 中心协作架构。Supervisor 根据任务目标和当前状态，动态委派 Profile、Product、Inventory、Copy 四个专业 Agent。每个专业 Agent 都有自己的角色 Prompt、工具权限和有界 Plan-Act-Observe Loop。例如 Product Agent 负责商品召回和重排，Inventory Agent 负责活动、履约与库存约束；如果发现商品不可用，它会发出 VETO，Supervisor 再要求 Product Agent 排除这些商品并重新召回。

工程上实现了工具白名单、服务端可信参数重建、最大步数、重复调用检测、Blackboard 字段权限、Evidence 校验和 SSE 轨迹输出。默认推荐入口运行多 Agent Runtime，原固定工作流只保留为评测基线。

## 2. 为什么要使用多 Agent，而不是一个 Agent 调用所有工具？

单 Agent 当然能完成这个任务，多 Agent 不是功能上的必要条件。这里拆分的主要原因是不同环节的职责和风险不同：用户画像关注行为特征，商品 Agent 关注召回与排序，库存 Agent 负责硬约束和否决，文案 Agent 只能处理已通过业务校验的最终商品。

如果把所有工具都交给一个 Agent，它既能生成文案，又能修改商品集合，还能决定库存校验是否执行，权限边界比较模糊。拆分后可以为每个 Agent 设置独立工具域和 Blackboard 写权限，并让 Inventory Agent 对 Product Agent 的结果进行交叉校验。

它的代价是调用次数、状态管理和调试复杂度上升。因此我保留固定工作流作为基线，通过任务成功率、工具调用正确性、无效调用数和延迟判断多 Agent 是否真正带来收益，而不是默认认为 Agent 越多越好。

## 3. 这个项目到底是真 Agent，还是固定工作流换了名字？

当前默认推荐链不是 `SupervisorOrchestrator` 固定工作流。Supervisor 每轮读取 Blackboard，模型从当前满足前置条件的 Agent 集合中选择下一名专业 Agent；专业 Agent 再通过自己的模型 Planner，从当前可执行工具集合中选择动作，并执行 Plan-Act-Observe Loop。

它与固定工作流的区别是：调度单位是 Agent，而不是下一工具；一个专业 Agent 在一次委派中可以执行多个动作，也可以因为缺少其他 Agent 的结果而交还控制权；Inventory 的 VETO 会改变 Product 后续计划。

但它仍然是中心化、受约束的多 Agent，不是多个 Agent 自由群聊。服务端仍然控制前置条件、工具权限、可信参数和最终完成条件，这是为了避免模型越权和业务流程失控。

## 4. Supervisor Agent 的 Loop 是怎么实现的？

Supervisor 的外层循环位于 `AutonomousAgentLoopService`。一次请求先创建 `RecommendationPipelineState`，随后每轮执行以下过程：

1. 根据 Scene Contract 和 Blackboard 计算当前允许工作的 Agent。
2. 将允许集合、已有证据、候选商品数量、VETO 状态等交给 Supervisor Planner。
3. 模型返回 `PROFILE`、`PRODUCT`、`INVENTORY`、`COPY` 或 `SUPERVISOR`。
4. 服务端校验该 Agent 是否在当前允许集合中。
5. 生成 `AgentTask` 和 `DELEGATE` 消息，调用相应专业 Agent。
6. 接收专业 Agent 的 `SpecialistRun`，把 Observation 和 Evidence 汇总到本轮结果。
7. 如果出现 VETO，则向 Product Agent 发出 `REQUEST_REVISION`。
8. 所有完成条件满足后，Supervisor 校验 Evidence 并提交最终答案。

外层循环受最大步数限制，不允许无限委派。

## 5. 四个专业 Agent 都有自己的 Loop 吗？

有，但它们不是四份重复代码。`SpecialistAgentTeam` 创建四个不同身份的 `BoundedSpecialistAgent` 实例，共用一套 Loop 内核。每个实例有不同的 `AgentId`、角色目标、Prompt 和工具域。

每次收到委派后，专业 Agent 都会创建自己的局部 `thoughts`、`toolCalls`、`observations` 和 `evidences`，然后最多执行 4 次：计算当前可执行工具、模型选择 Action、权限校验、重建可信参数、执行工具、读取 Observation，再决定继续还是交还 Supervisor。

Profile 在 retention 场景可能连续执行画像和近期订单两个动作；Inventory 在 campaign 场景可能连续执行履约和库存检查；Copy 正常情况下通常只有一次工具调用。因此它们都有 Loop 结构，但不是每个 Agent 都一定会循环多轮。

## 6. Blackboard 是什么？它不就是全局变量吗？

本质上是。它是一次 Agent Run 内多个 Agent 共用的 Java 可变对象，即 `RecommendationPipelineState`。它不是 `static` 全局变量：每个请求都会创建一个新实例，所以请求 A 和请求 B 的状态相互隔离。

它保存用户画像、候选商品、排序商品、库存结果、最终商品、文案、Evidence、VETO 和 AgentMessage。称为 Blackboard，是因为在共享状态之上又定义了字段结构、所有权和完成条件。

当前 Agent 由 Supervisor 顺序调用，所以普通可变对象暂时可用。如果未来允许多个 Agent 真正并行写入，就必须加入版本控制、锁、CAS 或事件溯源，否则会出现覆盖和竞态问题。

## 7. Agent 之间是怎么通信的？

当前有两条通道。

第一条是数据通道：Agent 通过同一个 Blackboard 对象传递业务数据。例如 Product 将候选商品写入 `rawProducts`，Inventory 从中读取商品并写入 `availableIds` 和 `vetoes`。

第二条是控制和审计通道：Supervisor 把 `DELEGATE`、`RESULT`、`VETO`、`REQUEST_REVISION`、`COMPLETE`、`ERROR` 等 `AgentMessage` 写入 Blackboard 的消息列表。

需要准确说明的是，`AgentMessage` 当前不是 Kafka 或网络消息队列，也不是 Agent 自由点对点聊天。专业 Agent 不会主动轮询消息；Supervisor 负责解释 VETO 并进行下一次委派。业务数据主要通过共享对象传递，消息列表主要表达协作语义并用于审计和测试。

## 8. VETO 是什么？为什么需要它？

VETO 是 Inventory Agent 对候选商品发出的业务否决信号，不是程序异常。它包含来源 Agent、被否决商品 ID、原因和是否已经处理。

例如 Product 召回 P001、P002、P003，Inventory 发现 P002 缺货，就把 P002 写入 `VETOES`，同时向 Supervisor 返回 VETO。Supervisor 再向 Product 发送 `REQUEST_REVISION`，Product 重新召回时把 P002 加入排除集合，并清空旧库存、排序和最终商品结果，随后重新校验。

当前只允许一次 VETO 后重召回，这是为了防止 Product 和 Inventory 无限互相打回。第二轮仍无法满足约束时，系统选择阻断，而不是无限重试。

## 9. 每个 Agent 都会看到所有工具吗？

不会向模型暴露所有工具。模型只会看到当前 Agent、当前状态下的 `candidateActions`。

固定工具域是：Profile 只能使用画像和近期订单工具；Product 只能召回和重排；Inventory 只能加载活动约束、检查履约和库存；Copy 只能生成两类文案。执行前还会检查 Agent 工具域、当前可执行集合和全局白名单。

底层四个 Agent 共用同一个 `RecommendationPipelineExecutor` 和 Tool Registry，因此这是逻辑隔离，不是进程或容器级物理隔离。如果有人直接修改 Java 代码绕过校验，仍然能够访问共享执行器。

## 10. 你说“服务端重建可信参数”，具体是什么意思？

专业 Agent 的模型只返回 Action，例如 `check_inventory`，不会决定最终的商品 ID、国家、币种或仓库参数。

Action 通过校验后，Java Runtime 调用 `trustedArguments(action, blackboard)`，从原始 HTTP 请求和 Blackboard 中重新读取 `country`、`currency`、候选商品数量、VETO 商品和重召回轮次。真正执行工具时，工具拿到的也是服务端 Blackboard，而不是模型自由生成的参数 Map。

因此信任边界是：模型负责建议做什么，服务端决定用哪些真实业务参数执行。如果这里所说的 Harness 指我们自建的 Java Agent Runtime，那么参数确实由 Harness 生成；但它不是由 LLM 或 τ³ Benchmark Harness 生成的。

## 11. Profile Agent 如何构造用户偏好？会保存每个用户的全部对话吗？

不会保存全部对话。当前画像来自用户行为 Feature Store，而不是聊天历史。

系统按 `userId` 记录浏览、点击、购买、商品 ID、metadata 和时间戳，数据写入 Redis、PostgreSQL，并在 Redis 不可用时使用进程内 Map。每次画像最多读取最近 20 条事件，聚合行为次数、Top 商品和最近事件，再让模型生成 `segments`、`preferred_categories`、`price_range`、RFM 风格分数和实时标签。

当前不足是：RFM 还不是严格按照完整订单金额和时间公式计算，fallback 数值比较固定，价格偏好也没有完整的统计建模。因此准确说法是“基于近期行为特征的结构化用户画像”，不能说成完整用户长期记忆。

## 12. 各个 Agent 有自己的上下文吗？

每个 Agent 有自己的角色 Prompt、当前 `AgentTask` 和本次委派的局部执行轨迹，但没有独立的持久化对话上下文窗口。

专业 Agent Planner 当前主要看到 `taskId`、目标、Scene、候选 Action、已知 Evidence ID 和 VETO 商品。它通过更新后的 Blackboard 感知上一动作结果，而不是把全部历史自然语言消息重新塞进 Prompt。

所以更准确的描述是：每个 Agent 有独立角色和临时执行上下文，但共享主要业务状态，没有独立持续增长的聊天历史。

## 13. 当前系统有记忆管理吗？

没有真正的 Agent 记忆管理。当前没有 Agent 经验向量库、跨会话 Trace 召回、对话压缩、失败经验沉淀或每个 Agent 的长期记忆。

现有状态分为三类：本轮 Blackboard、本次委派的局部 Trace，以及跨请求保存的用户行为 Feature Store。用户行为数据能让 Profile 在下一次请求中继续读取用户近期浏览和购买，但这属于业务特征，不是 Agent 记忆。

下一次运行不会自动知道上一次 Supervisor 的思考、Product 的召回策略或 Inventory 的历史 VETO。因此面试时应该直接承认：当前有用户特征持久化，没有 Agent 跨会话记忆。

## 14. 系统如何防止 Agent 无限循环和重复调用？

系统有多层边界：Supervisor 有全局最大步骤；专业 Agent 每次委派最多 4 个局部步骤；每次工具调用会根据 `AgentId + Action + trustedArguments` 生成 fingerprint；相同 fingerprint 再次出现会被判定为重复调用并阻断。

此外，VETO 后重召回次数限制为一次，工具调用还受全局白名单和每个 Agent 工具域限制。任务只有在 Scene Contract 完成后才能进入 `final_answer`。

这些机制主要防止模型空转、反复调用相同工具、两个 Agent 无限互相打回和证据不足时提前结束。

## 15. ScenePathEnforcer 会不会又把系统变成固定工作流？

它保留了业务前置条件，但默认多 Agent 入口不是按照固定数组逐个执行工具。`ScenePathEnforcer` 会根据当前 Blackboard 计算 `allowedAgents` 和每个 Agent 的 `executableTools`，模型只能从允许集合中选择。

例如 campaign 初始阶段 Inventory 可以先加载活动约束；候选商品产生后，Inventory 才能检查履约；排序只有在库存结果存在后才能执行。这些是业务依赖和安全约束，不应该交给模型绕过。

所以它限制的是“哪些动作现在合法”，不是由旧 `SupervisorOrchestrator` 直接执行完整流程。系统仍然是受约束自治，而不是自由自治。

## 16. Evidence ID 是做什么的？

每个工具 Observation 都会返回 Evidence ID，例如商品证据、库存证据、活动约束证据和文案证据。Supervisor 最终提交答案时，会检查引用的 Evidence ID 是否来自本轮工具执行或 Blackboard 已知证据。

它主要解决两个问题：第一，最终结果需要能追溯到实际工具调用；第二，模型不能随意编造一个不存在的证据 ID 来证明任务完成。

当前 Evidence 主要验证“是否存在对应工具证据”，还不是完整的数据血缘系统。它没有对每一个自然语言结论做逐句引用，也没有不可篡改签名。

## 17. 工具调用失败时如何处理？

专业 Agent 执行工具时会捕获异常并生成状态为 `failed` 的 `ToolCallRecord`，随后以 `tool_failed` 结束本次 SpecialistRun。Supervisor 将其记录为 `ERROR`，整个推荐运行返回失败，而不是继续使用不完整状态生成结果。

模型输出非法 JSON、非法 Agent 或非法 Action 时，服务端会拒绝非法选择或者使用受约束的安全选择；但业务工具本身失败时不会伪造成功结果。最终答案还需要通过完成条件和 Evidence 校验。

生产化还需要增加工具级超时、按错误类型区分是否可重试、熔断和降级数据源。当前实现主要提供有限重试和显式失败记录。

## 18. 如何做可观测和调试？

系统通过 SSE 输出 run started、Agent delegated、planner decision、tool started、tool completed、Observation、VETO 和 run completed 等事件。每条事件包含 runId、顺序号、Agent、工具、耗时和 Evidence ID。

最终 `AgentLoopResponse` 还包含 thoughts、toolCalls、observations、evidences、总延迟和 LLM 调用指标。AgentMessage 会记录 Supervisor 与专业 Agent 之间的委派、结果和修订关系。

因此出现错误时，可以判断是 Supervisor 选错 Agent、专业 Agent 选错工具、工具执行失败、库存 VETO、重复调用，还是 Evidence 不足，而不是只看到一个错误的最终答案。

## 19. 这个多 Agent 系统应该怎么评测？

至少需要三组对照：固定工作流基线、单 Agent 加全部工具、多 Agent Supervisor。三组使用相同任务、商品和库存数据，比较任务成功率、约束满足率、工具选择准确率、无效工具调用率、平均步骤、延迟和模型调用成本。

还要单独构造协作测试：Inventory VETO 后 Product 是否重新召回；被否决商品是否重新进入最终结果；Copy 是否尝试修改商品列表；模型提前选择 Copy 时是否被前置条件拦截；重复调用是否停止；Evidence 不完整时是否拒绝结束。

当前 Java 测试已经覆盖正常 Scene、VETO 重规划、工具白名单、Blackboard 权限、LLM 非法 Agent 选择和结构化消息。业务模拟可以对固定工作流与 Agent Loop 做离线比较，但它仍是确定性 seed 数据评测，不应该包装成线上业务收益。

## 20. 当前实现最大的不足是什么？下一步最值得改什么？

第一，四个专业 Agent 虽然是不同实例，但共享同一个 Loop 内核和同一个进程内 Tool Executor，属于逻辑隔离，不是进程级隔离。第二，Blackboard 是普通可变对象，适合当前顺序执行，不适合直接扩展为多个 Agent 并行写。第三，AgentMessage 主要用于控制语义和审计，还不是异步消息总线。第四，Profile 画像比较基础，当前没有 Agent 跨会话记忆。第五，VETO 只允许一次重召回，复杂约束下可能过早阻断。

下一步我会优先做两件事：一是把 Blackboard 改为带版本号的状态变更或事件流，使并发协作可控；二是完成固定工作流、单 Agent 和多 Agent 的配对评测，验证多 Agent 的质量收益是否大于额外延迟和成本。只有评测证明拆分有效，才继续增加 Agent 数量或长期记忆。

## 面试回答时需要避免的表述

- 不要说“Blackboard 是复杂的分布式共享内存”；当前就是请求内 Java 对象。
- 不要说“Agent 之间通过消息队列通信”；当前主要通过共享对象和 Supervisor 调度。
- 不要说“每个 Agent 都有长期记忆”；当前没有 Agent 跨会话记忆。
- 不要把 `UserProfileAgent`、`ProductRecAgent` 等底层工具类继续称为真正的专业 Agent Loop。
- 不要说“每个 Agent 物理隔离全部工具”；当前是模型工具可见性和运行时权限的逻辑隔离。
- 不要把离线 seed 数据评测说成线上业务指标。
