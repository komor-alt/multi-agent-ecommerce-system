# 多 Agent 电商推荐项目一天掌握指南

> 适用目标：一天内把这个项目掌握到“能讲清楚、能跑通主链路、能回答常见面试追问”的程度。  
> 重点对象：`java` 子项目中的 Spring Boot 多 Agent 推荐服务，同时补充前端工作台、NestJS Gateway、SSE 和事件落库的衔接方式。

---

## 1. 先用一句话讲清楚项目

这个项目是一个面向电商推荐与营销场景的多 Agent 系统：用户在工作台发起推荐任务后，系统通过 Supervisor 编排多个 Agent，先生成用户画像，再召回和重排商品，同时校验库存，最后生成个性化营销文案，并把运行过程、延迟和结果记录下来，方便运营人员观察和后续评测。

面试时可以这样说：

> 我做的是一个多 Agent 电商推荐与营销工作台。核心链路是：前端创建推荐任务，后端生成 Agent Run 并调用 Agent 服务，Agent 服务用 Supervisor 模式编排用户画像、商品推荐、库存决策和营销文案四类 Agent，最终返回推荐商品、个性化文案、实验分组和每个 Agent 的执行结果。

---

## 2. 项目模块怎么分

### 2.1 Java Agent 服务

路径：

`D:\研究生\AI\multi-agent-ecommerce-system\java`

它是你最应该重点掌握的部分，负责多 Agent 推荐核心逻辑。

关键文件：

| 文件 | 作用 |
|---|---|
| `java\src\main\java\com\ecommerce\config\RecommendationController.java` | 推荐接口入口，暴露 `/api/v1/recommend` |
| `java\src\main\java\com\ecommerce\orchestrator\SupervisorOrchestrator.java` | 多 Agent 编排核心 |
| `java\src\main\java\com\ecommerce\agent\BaseAgent.java` | Agent 基类，封装异步执行、重试、失败兜底 |
| `java\src\main\java\com\ecommerce\agent\UserProfileAgent.java` | 用户画像 Agent |
| `java\src\main\java\com\ecommerce\agent\ProductRecAgent.java` | 商品召回和 LLM 重排 Agent |
| `java\src\main\java\com\ecommerce\agent\InventoryAgent.java` | 库存决策 Agent |
| `java\src\main\java\com\ecommerce\agent\MarketingCopyAgent.java` | 营销文案 Agent |
| `java\src\main\java\com\ecommerce\service\ABTestService.java` | A/B 实验分桶 |
| `java\src\main\resources\application.yml` | Spring AI、模型、H2 数据源配置 |

### 2.2 NestJS Gateway

路径：

`D:\研究生\AI\multi-agent-ecommerce-system\backend`

它负责工作台后端能力，例如任务创建、Agent Run、事件持久化、SSE 转发、PostgreSQL 数据模型。

关键文件：

| 文件 | 作用 |
|---|---|
| `backend\src\modules\recommendations\recommendations.service.ts` | 创建推荐任务，生成 taskId/runId，调用 Agent 服务 |
| `backend\src\infrastructure\agent-client\agent-client.service.ts` | 调用 Agent 服务并读取 SSE 事件流 |
| `backend\src\modules\agent-runs\agent-runs.service.ts` | 提供 Run 列表、详情、事件查询、SSE stream |
| `backend\src\modules\agent-runs\agent-runs.repository.ts` | Agent 事件和运行结果落库 |
| `backend\prisma\schema.prisma` | PostgreSQL 表结构，包括 AgentRun、AgentEvent、ToolCall、EvaluationRun 等 |

注意：Java 子项目现在已经补充 `/api/v1/recommend/stream`，可以用 `SseEmitter` 推送 `run.started`、`phase.started`、`agent.completed`、`run.completed` 等过程事件；同时新增 `/api/v1/evaluations/smoke` 和 `/api/v1/metrics`，用于支撑基础评测回归和运行观测。

### 2.3 React 前端工作台

路径：

`D:\研究生\AI\multi-agent-ecommerce-system\frontend`

关键文件：

| 文件 | 作用 |
|---|---|
| `frontend\src\pages\recommendations\RecommendationConsole.tsx` | 推荐任务创建页，展示运行事件、商品、画像、文案 |
| `frontend\src\pages\runs\RunDetailPage.tsx` | Agent Run 详情页，通过 SSE 展示过程 |
| `frontend\src\hooks\useAgentRunSse.ts` | 创建 EventSource，连接 Gateway SSE |

---

## 3. 一次推荐请求的完整链路

### 3.1 从接口看入口

Java 接口入口在：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\config\RecommendationController.java`

核心代码：

```java
@PostMapping("/recommend")
public RecommendationResponse recommend(@RequestBody RecommendationRequest request) {
    return orchestrator.recommend(request);
}
```

请求体对应：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\model\RecommendationRequest.java`

字段很简单：

```java
private String userId;
private String scene = "homepage";
private int numItems = 10;
private Map<String, Object> context;
```

也就是说，用户只要传入用户 ID、推荐场景、推荐数量和上下文，后端就能启动一次推荐。

### 3.2 Supervisor 是主控大脑

核心文件：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\orchestrator\SupervisorOrchestrator.java`

它的职责不是自己做画像、推荐、库存或文案，而是安排各个 Agent 在合适的阶段执行。

整体流程：

```text
用户请求
  |
  v
SupervisorOrchestrator.recommend()
  |
  |-- A/B 分组
  |
  |-- Phase 1 并行：用户画像 Agent + 商品召回 Agent
  |
  |-- Phase 2 并行：商品重排 Agent + 库存决策 Agent
  |
  |-- 聚合：按库存过滤商品，取 TopN
  |
  |-- Phase 3 串行：营销文案 Agent
  |
  v
返回 RecommendationResponse
```

为什么 Phase 1 和 Phase 2 可以并行？

- 用户画像和商品初始召回互不依赖，可以同时做。
- 商品重排依赖画像，库存检查依赖召回商品，但二者彼此独立，可以同时做。
- 营销文案需要最终商品和用户画像，所以放到最后串行执行。

这就是项目的核心亮点：固定流程型任务适合 Supervisor 模式，既能集中控制，又能并行降低延迟。

---

## 4. Agent 基类：所有 Agent 的共同能力

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\agent\BaseAgent.java`

每个业务 Agent 都继承 `BaseAgent`。它负责三件事：

1. 异步执行：返回 `CompletableFuture<AgentResult>`。
2. 失败重试：失败后指数退避重试。
3. 失败兜底：全部失败后返回 fallback 结果，不让整个推荐链路直接崩。

关键代码逻辑：

```java
public CompletableFuture<AgentResult> runAsync(Map<String, Object> params) {
    return CompletableFuture.supplyAsync(() -> {
        int attempt = 0;
        while (attempt < maxRetries) {
            try {
                AgentResult result = execute(params);
                result.setLatencyMs(latency);
                return result;
            } catch (Exception e) {
                attempt++;
                Thread.sleep(500 * Math.pow(2, attempt - 1));
            }
        }
        return fallback(latency, lastError);
    });
}
```

你可以这样理解：

> BaseAgent 是所有 Agent 的模板。子类只关心自己的业务逻辑，实现 `execute()` 就行；异步、重试、计时、失败返回这些通用能力都由父类统一处理。

`AgentResult` 返回结构在：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\model\AgentResult.java`

核心字段：

| 字段 | 含义 |
|---|---|
| `agentName` | 哪个 Agent 的结果 |
| `success` | 是否执行成功 |
| `latencyMs` | 这个 Agent 的耗时 |
| `error` | 失败原因 |
| `data` | 业务结果 |
| `confidence` | 结果置信度 |

面试时可以说：

> 我没有让每个 Agent 自己处理重试和计时，而是抽到了 BaseAgent。这样用户画像、推荐、库存、文案四个 Agent 都有一致的执行协议和返回结构，也方便后续做监控和评测。

---

## 5. 四个核心 Agent 逐个讲清楚

## 5.1 用户画像 Agent

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\agent\UserProfileAgent.java`

它做什么：

> 把用户行为数据转成结构化画像，例如用户分群、偏好类目、价格区间、RFM 得分、实时标签。

执行步骤：

1. 根据 `userId` 收集用户行为。
2. 拼接系统提示词，调用大模型。
3. 要求模型输出 JSON。
4. 把 JSON 解析成 `UserProfile` 对象。
5. 如果模型输出不稳定或 JSON 解析失败，返回默认画像。

代码里模拟的行为数据：

```java
behavior.put("recent_views", List.of("手机", "耳机", "平板"));
behavior.put("recent_purchases", List.of("充电器"));
behavior.put("view_count_7d", 25);
behavior.put("purchase_count_30d", 3);
behavior.put("avg_order_amount", 299.0);
```

LLM 系统提示词要求输出：

```json
{
  "segments": ["active"],
  "preferred_categories": ["手机"],
  "price_range": [0, 10000],
  "rfm_score": {
    "recency": 0.8,
    "frequency": 0.5,
    "monetary": 0.6
  },
  "real_time_tags": {
    "活跃时段": "晚间"
  }
}
```

重要兜底逻辑：

```java
catch (Exception e) {
    return UserProfile.builder()
            .userId(userId)
            .segments(List.of("active"))
            .build();
}
```

这段说明系统不完全信任模型输出。如果模型返回了非 JSON、字段缺失或格式异常，系统不会崩，而是返回默认活跃用户画像。

面试讲法：

> 用户画像 Agent 的输入是用户 ID 和上下文，内部会收集用户近期浏览、购买次数、客单价等行为特征，然后调用 LLM 输出结构化画像。这里我重点做了 JSON 输出约束和解析兜底，避免模型格式不稳定影响后续推荐链路。

---

## 5.2 商品推荐 Agent

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\agent\ProductRecAgent.java`

它做什么：

> 先召回候选商品，再结合用户画像调用 LLM 做商品重排，输出最终候选商品列表。

当前代码里商品数据是 `MOCK_PRODUCTS`，包含 iPhone、华为、耳机、平板、显示器等商品。

执行步骤：

1. `recall(profile, limit)`：先召回候选商品。
2. 如果用户有偏好类目，则把偏好类目的商品排到前面。
3. `rerank(profile, candidates, numItems)`：调用 LLM 对候选商品 ID 排序。
4. 根据 LLM 返回的商品 ID，从候选商品 Map 里取出最终商品。
5. 如果 LLM 返回不足，就用默认候选补齐。

召回逻辑：

```java
if (profile != null && profile.getPreferredCategories() != null) {
    Set<String> preferred = new HashSet<>(profile.getPreferredCategories());
    candidates.sort((a, b) -> Boolean.compare(
            preferred.contains(b.getCategory()),
            preferred.contains(a.getCategory())
    ));
}
```

LLM 重排提示词：

```java
String prompt = String.format(
    "根据用户偏好类目%s和价格范围%.0f-%.0f,从以下商品中选出最优%d个,输出ID数组..."
);
```

模型只需要输出类似：

```json
["P001", "P003", "P007", "P005", "P010"]
```

失败兜底：

```java
catch (Exception e) {
    return candidates.stream()
            .map(Product::getProductId)
            .limit(numItems)
            .collect(Collectors.toList());
}
```

也就是说，如果 LLM 重排失败，系统退回默认候选顺序，保证仍然有推荐结果。

面试讲法：

> 商品推荐 Agent 采用“两阶段推荐”：第一阶段做规则召回，当前实现是基于用户偏好类目对候选商品排序；第二阶段调用 LLM 对候选商品进行个性化重排。为了防止模型返回异常，我只让模型输出商品 ID 数组，并且解析失败时回退到默认排序。

---

## 5.3 库存决策 Agent

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\agent\InventoryAgent.java`

它做什么：

> 检查候选商品库存，过滤无货商品，同时生成库存预警和限购策略。

核心阈值：

```java
private static final int SAFETY_STOCK_THRESHOLD = 50;
private static final int LOW_STOCK_THRESHOLD = 100;
private static final int HOT_ITEM_PURCHASE_LIMIT = 2;
```

执行步骤：

1. 遍历候选商品。
2. 库存小于等于 0 的商品直接剔除。
3. 库存低于 50，生成 critical 预警。
4. 库存低于 100，生成 warning 预警。
5. 对新品、旗舰商品，根据库存设置限购。

返回数据：

```java
data.put("available_products", available);
data.put("low_stock_alerts", alerts);
data.put("purchase_limits", purchaseLimits);
data.put("total_checked", products.size());
data.put("available_count", available.size());
```

限购逻辑：

```java
if (stock <= SAFETY_STOCK_THRESHOLD) return 1;
if (stock <= LOW_STOCK_THRESHOLD && isHot) return HOT_ITEM_PURCHASE_LIMIT;
if (isHot && stock <= 300) return 3;
return null;
```

面试讲法：

> 库存 Agent 的作用是避免推荐缺货商品。它会返回可售商品 ID、低库存预警和限购策略。Supervisor 最终会用 `available_products` 过滤重排后的商品，保证最终推荐结果更贴近真实交易场景。

---

## 5.4 营销文案 Agent

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\agent\MarketingCopyAgent.java`

它做什么：

> 根据用户画像选择文案模板，再让 LLM 给每个商品生成个性化营销文案，并做广告法敏感词过滤。

模板类型：

```java
"new_user"        -> 新用户欢迎文案
"high_value"      -> VIP 用户尊享文案
"price_sensitive" -> 价格敏感用户文案
"active"          -> 活跃用户文案
"churn_risk"      -> 流失风险用户召回文案
```

模板选择逻辑：

```java
List<String> priority = List.of("new_user", "high_value", "churn_risk", "price_sensitive", "active");
for (String seg : priority) {
    if (profile.getSegments().contains(seg)) return seg;
}
return "active";
```

模型输出格式：

```json
[
  {
    "product_id": "P001",
    "copy": "根据您的偏好，为您推荐这款旗舰手机，适合日常拍摄和高效办公。"
  }
]
```

敏感词过滤：

```java
private static final List<String> FORBIDDEN_WORDS = List.of(
    "最好", "第一", "国家级", "全球首", "绝对", "100%", "永久", "万能"
);
```

替换逻辑：

```java
text = text.replace(word, "***");
```

面试讲法：

> 营销文案 Agent 会根据用户分群动态选择 Prompt 模板，例如新用户、价格敏感用户、流失风险用户使用不同话术。模型生成后会做敏感词过滤，避免出现“最好”“第一”“绝对”等广告法风险词。

---

## 6. Supervisor 代码怎么串起来

核心文件：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\orchestrator\SupervisorOrchestrator.java`

### 6.1 A/B 分组

```java
String experimentGroup = abTestService.assign(request.getUserId())
        .getOrDefault("group", "control")
        .toString();
```

A/B 服务在：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\service\ABTestService.java`

它用用户 ID 和实验 ID 做 MD5 哈希，得到 0 到 99 的桶：

```java
String group = bucket < 50 ? "control" : "treatment_llm";
```

好处：

- 同一个用户每次会进同一个实验组。
- 推荐策略或模型版本可以做对比实验。
- 当前实现是固定 50/50 分流，不是真正动态 Thompson Sampling。

面试时要稳一点：

> 当前 Java 实现主要是基于用户 ID 哈希的一致性分桶，保证同一用户稳定进入同一实验组。后续可以在这个基础上扩展点击反馈和 Thompson Sampling 动态调流量。

### 6.2 Phase 1：画像和召回并行

```java
CompletableFuture<AgentResult> profileFuture = userProfileAgent.runAsync(
        Map.of("userId", request.getUserId()));

CompletableFuture<AgentResult> recFuture = productRecAgent.runAsync(
        Map.of("numItems", request.getNumItems() * 2));

AgentResult profileResult = profileFuture.join();
AgentResult recResult = recFuture.join();
```

这里体现了并行编排。

### 6.3 Phase 2：重排和库存并行

```java
CompletableFuture<AgentResult> rerankFuture = productRecAgent.runAsync(
        Map.of("userProfile", profile, "numItems", request.getNumItems()));

CompletableFuture<AgentResult> inventoryFuture = inventoryAgent.runAsync(
        Map.of("products", rawProducts));
```

重排依赖用户画像，库存检查依赖候选商品。二者可以同时做。

### 6.4 聚合：用库存过滤最终商品

```java
Set<String> availSet = new HashSet<>(availableIds);
List<Product> finalProducts = rankedProducts.stream()
        .filter(p -> availSet.contains(p.getProductId()))
        .limit(request.getNumItems())
        .collect(Collectors.toList());
```

如果库存过滤后为空，会退回 rankedProducts：

```java
if (finalProducts.isEmpty()) {
    finalProducts = rankedProducts.stream()
            .limit(request.getNumItems())
            .collect(Collectors.toList());
}
```

这属于结果兜底，避免接口返回空列表。

### 6.5 Phase 3：生成文案

```java
AgentResult copyResult = marketingCopyAgent.runAsync(
        Map.of("userProfile", profile, "products", finalProducts))
        .join();
```

文案必须等最终商品确定后再做，所以是串行。

---

## 7. 最终响应长什么样

响应模型在：

`D:\研究生\AI\multi-agent-ecommerce-system\java\src\main\java\com\ecommerce\model\RecommendationResponse.java`

核心字段：

| 字段 | 含义 |
|---|---|
| `requestId` | 本次请求 ID |
| `userId` | 用户 ID |
| `products` | 最终推荐商品 |
| `marketingCopies` | 商品对应的营销文案 |
| `experimentGroup` | A/B 实验分组 |
| `agentResults` | 每个 Agent 的中间结果 |
| `totalLatencyMs` | 总耗时 |
| `timestamp` | 响应时间 |

这点面试很重要，因为它说明项目不是只返回一个黑盒结果，而是把每个 Agent 的中间结果也返回了，方便调试和观察。

---

## 8. 工作台、SSE、事件落库怎么理解

### 8.1 前端如何创建任务

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\frontend\src\pages\recommendations\RecommendationConsole.tsx`

页面上可以输入：

- 用户 ID
- 推荐场景
- 推荐数量
- 近期浏览类目
- 30 天购买次数
- 客单价
- 补充上下文

点击后会创建推荐任务：

```ts
const task = await createRecommendationTask({
  userId: form.user_id.trim() || "user_001",
  scene: form.scene,
  numItems: Number(form.num_items || 5),
  context: buildPayload(form).context,
  agentConfig: {
    model: "deepseek-v4-flash",
    maxSteps: 8,
    toolWhitelist: ["get_user_profile", "search_products", "check_inventory", "generate_copy"],
  },
});
```

这里的 `toolWhitelist` 体现了受控 Agent 思路：让 Agent 只能使用允许的工具。

### 8.2 Gateway 如何创建任务和 Run

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\backend\src\modules\recommendations\recommendations.service.ts`

创建任务时会生成：

```ts
const taskId = randomUUID();
const runId = randomUUID();
```

然后写入数据库，并异步执行 Agent：

```ts
await this.repository.createTaskWithRun(...);
void this.executeAgentRun(...);
```

返回给前端：

```ts
return {
  id: taskId,
  runId,
  status: "running",
  streamUrl: `/api/v1/agent-runs/${runId}/stream`,
};
```

### 8.3 Gateway 如何读取 Agent SSE

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\backend\src\infrastructure\agent-client\agent-client.service.ts`

核心是：

```ts
const response = await fetch(`${this.baseUrl()}/api/v1/recommend/stream`, {
  method: "POST",
  headers: { "Content-Type": "application/json", Accept: "text/event-stream" },
  body: JSON.stringify(...),
});
```

然后逐帧读取 SSE：

```ts
for await (const frame of this.readSseFrames(response.body)) {
  sequence += 1;
  yield this.toRawEvent(request.run_id, sequence, frame.event, frame.data);
}
```

也就是 Agent 服务不断吐出事件，Gateway 把这些事件转换成统一的 `AgentServiceRawEvent`。

### 8.4 事件怎么落库

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\backend\src\modules\agent-runs\agent-runs.repository.ts`

核心代码：

```ts
await this.prisma.agentEvent.upsert({
  where: { runId_sequence: { runId, sequence: event.sequence } },
  update: this.toAgentEventUpdateInput(event),
  create: this.toAgentEventCreateInput(runId, event),
});
```

这样做的好处：

- 每个事件有 sequence，方便按顺序还原执行过程。
- upsert 可以避免重复写入。
- 前端刷新后仍能从数据库恢复历史事件。

### 8.5 事件怎么推给前端

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\backend\src\modules\agent-runs\agent-run-event-bus.ts`

核心代码：

```ts
publish(event: AgentServiceRawEvent) {
  this.subject(event.run_id).next({
    id: event.event_id,
    type: event.type,
    data: this.toSsePayload(event)
  });
}
```

`AgentRunsService.stream()` 会把历史事件、实时事件和心跳合并：

```ts
return merge(history$, this.eventBus.stream(runId), heartbeat$);
```

面试讲法：

> Gateway 层会把 Agent 服务返回的运行事件转成统一事件模型，一边写入 PostgreSQL，一边通过 RxJS Subject 推送到 SSE 流。前端用 EventSource 订阅 `/agent-runs/:runId/stream`，所以既能实时看到执行过程，也能刷新后恢复历史事件。

---

## 9. 数据库模型怎么讲

文件：

`D:\研究生\AI\multi-agent-ecommerce-system\backend\prisma\schema.prisma`

你重点记这些表：

| 表 | 作用 |
|---|---|
| `User` | 用户和角色 |
| `Product` | 商品信息、库存、标签、Embedding 状态 |
| `Order` | 订单信息，平台字段默认 shopify |
| `RecommendationTask` | 推荐任务 |
| `AgentRun` | 一次 Agent 执行记录 |
| `AgentEvent` | Agent 执行过程事件 |
| `ToolCall` | 工具调用记录 |
| `RetrievalEvidence` | 检索证据 |
| `EvaluationDataset` | 评测数据集 |
| `EvaluationRun` | 评测运行 |
| `AuditLog` | 审计日志 |

要注意：

- Schema 里有 `ToolCall`、`RetrievalEvidence`、`EvaluationRun` 等模型。
- 当前 Java Agent 代码重点实现的是推荐链路，工具调用和评测回归更像是平台层的观测与扩展设计。
- 简历中可以写“设计指标记录模型、预留评测回归链路”，不要夸成完整生产级自动评测闭环。

---

## 10. 这个项目的三个核心亮点

### 亮点 1：Supervisor 多 Agent 编排

不是一个大模型一次性做所有事情，而是拆成多个职责清晰的 Agent：

- 用户画像 Agent：理解用户。
- 推荐 Agent：选商品。
- 库存 Agent：保证可售。
- 文案 Agent：做个性化表达。

Supervisor 统一调度，适合流程固定、可并行、有明确中间结果的业务场景。

### 亮点 2：可靠性设计

可靠性不是一句口号，代码里有具体实现：

- `BaseAgent` 封装重试。
- 失败后指数退避。
- 失败后 fallback 返回结构化失败结果。
- LLM JSON 解析失败时有默认结果。
- 推荐结果为空时有兜底商品。

### 亮点 3：可观测和可评测基础

项目不是只展示最终答案，还记录：

- 每个 Agent 的执行结果。
- 每个 Agent 的耗时。
- Agent Run 状态。
- Agent Event 序列。
- Token、工具调用、评测运行等数据模型。

面试时可以说：

> 我把 Agent 执行过程显式化了，不只是返回最终结果，还把运行事件、中间结果和指标记录下来，方便后续分析失败原因、优化 Prompt 和做回归评测。

---

## 11. 一天掌握路线

### 上午：先跑通和理解主流程

目标：能讲清楚“请求从哪里进，怎么编排，结果怎么出”。

你要看：

1. `RecommendationController.java`
2. `SupervisorOrchestrator.java`
3. `RecommendationRequest.java`
4. `RecommendationResponse.java`

你要能回答：

- 接口是什么？
- 输入字段有哪些？
- 输出字段有哪些？
- Supervisor 为什么要分三个 Phase？
- 哪些 Agent 是并行的，哪些是串行的？

### 下午：逐个 Agent 看代码

目标：能讲清楚四个 Agent 的职责、输入、输出、兜底。

你要看：

1. `BaseAgent.java`
2. `UserProfileAgent.java`
3. `ProductRecAgent.java`
4. `InventoryAgent.java`
5. `MarketingCopyAgent.java`
6. `ABTestService.java`

你要能回答：

- BaseAgent 解决了什么重复问题？
- 用户画像 Agent 如何约束 LLM 输出？
- 商品推荐 Agent 为什么只让模型输出 ID 数组？
- 库存 Agent 如何过滤缺货商品？
- 文案 Agent 如何避免广告法风险词？
- A/B 分桶如何保证同一用户稳定进同一组？

### 晚上：准备面试话术

目标：能流畅讲 2 分钟项目，能答常见追问。

你要准备：

1. 30 秒项目介绍。
2. 2 分钟完整项目介绍。
3. 三个技术亮点。
4. 五个风险点和诚实说法。
5. 十个面试问答。

---

## 12. 30 秒项目介绍

> 这个项目是一个多 Agent 电商推荐与营销系统。用户在工作台发起推荐任务后，后端会创建 Agent Run，并由 Agent 服务通过 Supervisor 模式编排用户画像、商品推荐、库存决策和营销文案四个 Agent。用户画像 Agent 负责结构化理解用户偏好，推荐 Agent 做候选召回和 LLM 重排，库存 Agent 过滤缺货商品，文案 Agent 生成个性化营销文案。系统还设计了 Agent 运行事件、延迟、Token、工具调用等观测模型，方便后续做效果分析和评测回归。

---

## 13. 2 分钟项目介绍

> 我这个项目面向电商推荐和运营营销场景，核心目标是把原本分散的用户画像、商品推荐、库存校验和营销文案生成，组织成一个可观测的多 Agent 推荐链路。
>
> 在 Java Agent 服务里，我采用了 Supervisor 编排模式。一次推荐请求进来后，Supervisor 会先做 A/B 分组，然后并行执行用户画像 Agent 和商品召回 Agent。画像 Agent 会根据用户行为调用大模型输出结构化画像，比如用户分群、偏好类目、价格区间和 RFM 得分；推荐 Agent 会先做候选商品召回，再结合画像调用 LLM 做商品 ID 重排。第二阶段，系统会并行执行商品重排和库存检查，库存 Agent 会过滤无货商品，并返回低库存预警和限购策略。最后，营销文案 Agent 根据用户分群选择 Prompt 模板，为最终商品生成个性化文案，并做敏感词过滤。
>
> 为了提高稳定性，我把 Agent 通用执行逻辑抽到了 BaseAgent，包括异步执行、重试、耗时统计和失败兜底。对于 LLM 输出不稳定的问题，代码里也做了 JSON 清洗、解析失败降级和默认结果补齐。
>
> 在工作台层面，项目还设计了 NestJS Gateway 和 React 前端。Gateway 负责创建推荐任务、生成 Agent Run、持久化运行事件，并通过 SSE 把执行过程推给前端。数据库层使用 Prisma 建模了 AgentRun、AgentEvent、ToolCall、RetrievalEvidence、EvaluationRun 等表，为后续观测 Agent 延迟、Token 消耗、工具调用次数和评测回归提供基础。

---

## 14. 高频面试问题与答案

### Q1：为什么用 Multi-Agent，不直接让一个大模型完成推荐？

答：

> 单个大模型直接处理所有任务会导致上下文过长、职责混乱、输出不稳定，也不好定位问题。这个项目把任务拆成用户画像、商品推荐、库存决策和营销文案四个 Agent，每个 Agent 只处理自己的子任务。这样职责更清晰，中间结果可观测，也方便单独优化某个 Agent，比如只优化推荐重排或只优化文案 Prompt。

### Q2：为什么用 Supervisor 模式？

答：

> 这个业务流程比较固定：先画像和召回，再重排和库存，再生成文案。Supervisor 模式适合这种有明确阶段、有并行机会、最终需要聚合结果的任务。相比 Agent 之间自由交接，Supervisor 更容易控制流程、限制异常扩散，也更适合工程落地。

### Q3：项目中哪些步骤是并行的？

答：

> Phase 1 里用户画像和商品初始召回并行，因为它们互不依赖。Phase 2 里商品重排和库存检查并行，因为重排依赖画像，库存检查依赖候选商品，但二者可以同时执行。最后文案生成依赖最终商品列表，所以是串行。

### Q4：BaseAgent 的作用是什么？

答：

> BaseAgent 是所有 Agent 的公共模板，统一封装异步执行、重试、耗时统计和失败兜底。这样每个业务 Agent 只需要实现自己的 `execute()` 方法，不需要重复写通用逻辑。后续如果要加监控、限流或者统一日志，也可以在 BaseAgent 里扩展。

### Q5：LLM 输出 JSON 不稳定怎么办？

答：

> 项目里做了几层处理。第一，在 Prompt 中明确要求只输出 JSON。第二，解析前会清理 Markdown code block。第三，解析失败时会 catch 异常并返回默认结构，比如用户画像解析失败就返回 active 用户，商品重排失败就回退到默认候选顺序。这样 LLM 的格式问题不会导致整个链路失败。

### Q6：商品推荐 Agent 是怎么做推荐的？

答：

> 当前 Java 版本是一个可演示实现，先基于 mock 商品池做候选召回，如果有用户偏好类目，就把对应类目的商品提前。然后把候选商品和用户画像传给 LLM，让模型返回排序后的商品 ID 数组。系统再根据 ID 映射回商品对象。如果 LLM 调用失败或返回异常，就回退到默认顺序。

### Q7：为什么让模型只输出商品 ID 数组？

答：

> 这是为了降低模型输出的不确定性。商品详情、价格、库存这些字段应该来自系统可信数据，而不是让模型重新生成。模型只负责排序决策，输出商品 ID；系统再用 ID 从候选集中取商品，这样可以减少幻觉，比如编造商品、编造价格或推荐不存在的商品。

### Q8：库存 Agent 有什么业务价值？

答：

> 它解决推荐和库存脱节的问题。传统推荐可能推荐缺货商品，影响用户体验。库存 Agent 会返回可售商品 ID，Supervisor 用它过滤最终结果。同时库存 Agent 还能生成低库存预警和限购策略，比如旗舰或新品库存低时限制购买数量。

### Q9：文案 Agent 怎么做个性化？

答：

> 它根据用户画像里的 segments 选择 Prompt 模板。比如新用户用欢迎和新人权益风格，价格敏感用户突出性价比和促销，流失风险用户用召回话术。然后模型基于商品列表生成每个商品对应的文案，最后再做敏感词过滤。

### Q10：广告法合规怎么做的？

答：

> 当前实现是敏感词替换，维护了“最好”“第一”“绝对”“100%”等风险词列表，生成文案后逐个替换成 `***`。这属于基础合规防护。更完整的版本可以接入规则引擎或合规模型，对夸大宣传、绝对化用语和价格表述做更细粒度校验。

### Q11：A/B 测试怎么保证同一个用户稳定分组？

答：

> ABTestService 用 `userId + experimentId` 做 MD5 哈希，然后对 100 取模得到 bucket。只要 userId 和 experimentId 不变，同一个用户每次都会落到同一个桶，从而保证实验体验一致。当前 Java 实现是 50% control、50% treatment_llm 的静态分桶。

### Q12：项目里的 Thompson Sampling 是否完整实现了？

答：

> Java 代码里当前主要实现的是哈希分桶，还没有完整实现基于点击反馈动态更新流量的 Thompson Sampling。可以诚实说这是后续扩展方向：在记录点击、转化等反馈后，为每个实验组维护 Beta 分布参数，再动态调整流量。

### Q13：SSE 在项目里解决什么问题？

答：

> Agent 运行不是瞬时完成的，中间会经历开始、分组、各 Agent 执行、完成等多个事件。SSE 可以把这些过程实时推给前端，让运营人员看到 Agent 正在做什么，而不是只等待一个最终结果。同时事件也会落库，刷新页面后可以恢复历史执行过程。

### Q14：当前 Java 服务本身支持 SSE 吗？

答：

> 支持。Java 服务新增了 `/api/v1/recommend/stream`，Controller 使用 `SseEmitter` 创建流式响应，Supervisor 在关键阶段通过事件回调发送 `run.started`、`experiment.assigned`、`phase.started`、`agent.started`、`agent.completed`、`phase.completed` 和 `run.completed`。这样前端或 Gateway 可以实时展示 Agent 运行过程。

### Q15：Agent 运行事件是怎么落库的？

答：

> Gateway 将 Agent 服务返回的 SSE frame 转换成统一的 `AgentServiceRawEvent`，然后通过 `AgentRunsRepository.persistEvent()` 写入 `AgentEvent` 表。落库使用 runId 和 sequence 做唯一定位，保证事件有序，也避免重复写入。

### Q16：为什么要记录 AgentRun 和 AgentEvent？

答：

> AgentRun 表示一次完整的 Agent 执行，记录状态、模型名、Prompt 版本、延迟、Token 和最终结果；AgentEvent 表示执行过程中的每一步事件。这样既能展示过程，也能分析失败原因，比如哪个 Agent 慢、哪个步骤失败、最终结果来自哪些中间决策。

### Q17：这个项目有哪些降级策略？

答：

> 第一，BaseAgent 失败后会返回 fallback，不让整个链路崩溃。第二，用户画像 JSON 解析失败会返回默认 active 用户。第三，商品 LLM 重排失败会回退到默认候选顺序。第四，库存过滤后如果为空，会回退到 rankedProducts 的前 N 个。第五，文案解析失败会返回空文案列表。

### Q18：这个项目目前的不足是什么？

答：

> 我认为主要有三点。第一，Java 版本的数据源还是 mock 商品和 H2 配置，离真实生产还需要接入真实商品库、订单库和 Redis 特征。第二，A/B 测试当前是静态哈希分桶，还没有完整反馈闭环。第三，Gateway 有事件、工具调用和评测数据模型，但完整自动评测回归还需要继续补充数据集、评测指标计算和 CI 集成。

### Q19：如果让你继续优化，你会做什么？

答：

> 我会先把商品和用户行为从 mock 数据替换成 PostgreSQL 和 Redis Feature Store；然后把 LLM 的输出用 JSON Schema 或函数调用进一步约束；再补充工具调用记录和评测数据集，针对工具选择、证据有效性、延迟、Token 成本和推荐准确率做自动回归。最后可以把 Java 服务也补上流式事件接口，和 Gateway 的 SSE 链路完全打通。

### Q20：这个项目中你最想强调的技术点是什么？

答：

> 我会强调三点：第一是 Supervisor 模式下的多 Agent 并行编排；第二是 LLM 接入后的结构化输出约束和失败兜底；第三是 Agent 运行过程的可观测设计，包括事件流、运行记录、延迟和 Token 指标模型。这三点比单纯调用大模型更能体现工程化能力。

---

## 15. 简历表述建议

推荐写法：

> 基于 Spring AI 接入大模型能力，实现用户画像解析、商品候选 LLM 重排和个性化营销文案生成；结合 SSE 与 PostgreSQL 持久化 Agent 运行事件，沉淀延迟、工具调用、Token 消耗等观测指标，为评测回归和效果优化提供数据基础。

更稳妥版本：

> 基于 Spring AI 接入大模型能力，实现用户画像解析、商品候选 LLM 重排和个性化营销文案生成；设计 Agent 运行事件落库、延迟/工具调用/Token 等指标记录模型，并预留评测回归链路，支持后续 Agent 效果分析与持续优化。

不要过度写成：

- 已完整接入 Shopify Development Store。
- 已实现完整售后工单、人审退款、死信重试链路。
- 已完整实现 Thompson Sampling 自动调流量。
- Java Agent 服务已完整支持 SSE 流式输出。

这些点在当前 Java 代码里支撑不够，面试被追问会比较危险。

---

## 16. 你真正需要背熟的 10 句话

1. 这个项目不是单 Agent，而是 Supervisor 编排的多 Agent 推荐系统。
2. 用户画像和商品召回可以并行，因为二者互不依赖。
3. 商品重排和库存检查可以并行，因为二者分别依赖画像和候选商品。
4. 文案生成必须放最后，因为它依赖最终商品列表。
5. BaseAgent 统一封装异步执行、重试、耗时和失败兜底。
6. 用户画像 Agent 让 LLM 输出结构化 JSON，并有解析失败兜底。
7. 推荐 Agent 只让 LLM 输出商品 ID 数组，避免模型编造商品信息。
8. 库存 Agent 用可售商品 ID 过滤最终结果，避免推荐缺货商品。
9. 文案 Agent 根据用户分群选择 Prompt 模板，并过滤广告法风险词。
10. Gateway 和前端层通过 SSE 展示 Agent 运行过程，并把事件持久化到 PostgreSQL。

---

## 17. 最后一天复习顺序

如果只剩一天，按这个顺序来：

1. 先读 `SupervisorOrchestrator.java`，把三阶段流程画出来。
2. 再读 `BaseAgent.java`，理解异步、重试、fallback。
3. 按顺序读四个 Agent：画像、推荐、库存、文案。
4. 读 `ABTestService.java`，记住哈希分桶。
5. 读 `RecommendationResponse.java`，记住最终返回结构。
6. 快速看 `RecommendationConsole.tsx`，知道前端如何创建任务。
7. 快速看 `agent-client.service.ts` 和 `agent-runs.repository.ts`，知道事件怎么流转和落库。
8. 背熟第 12、13、14、16 节。

掌握到这个程度，你就可以比较稳地讲这个项目了。

---

## 附录：LLM Planner 驱动的 Agent Loop

Java 版进一步补充了一个模型自主选择工具的 Agent Loop 接口：

`POST /api/v1/recommend/agent-loop`

这一版和 `/recommend/tool-loop` 的区别是：

- `/recommend/tool-loop`：下一步工具由服务端状态机确定，适合稳定演示受限工具链。
- `/recommend/agent-loop`：下一步工具由 LLM Planner 输出 `thought/action/arguments/evidenceIds`，更接近 ReAct 风格的 `Thought -> Action -> Observation` 循环。

服务端仍然做强约束：

- 工具白名单：Planner 不能调用白名单外工具。
- 参数重建：LLM 输出的 arguments 只作为建议，真实参数由服务端根据上下文重建。
- 最大步数：超过 `maxSteps` 后强制停止。
- 重复调用检测：相同 `tool + trustedArguments` 重复调用会被阻断。
- Observation 回填：每次工具执行后生成 `ToolObservation`，下一轮 Planner 会看到历史 observation。
- Evidence ID 校验：最终 `final_answer` 必须引用已由工具产生的 evidence ID，否则被阻断。

面试讲法：

> Java 版有两种 Agent 执行模式。主推荐接口用 Supervisor 做固定工作流编排，追求低延迟和稳定性；`/recommend/agent-loop` 则实现了 LLM Planner 驱动的受限自治 Agent Loop。Planner 每轮输出 Thought 和 Action，服务端校验白名单、重建可信参数、执行工具并返回 Observation，最终答案还要通过 Evidence ID 校验。这样既有模型自主选择工具的能力，也能限制空转、越权和幻觉。
