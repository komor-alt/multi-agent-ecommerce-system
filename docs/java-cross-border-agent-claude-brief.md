# Claude Code 任务说明：Java 版跨境电商推荐多 Agent 补强

## 目标

把 Java 子项目从“通用电商推荐 Demo”补强为“面向跨境电商推荐场景的多 Agent 运营工作台核心服务”，要求简历中的每个关键词都能在代码、接口、模型或文档中找到支撑。

项目主标题：

> 面向跨境电商推荐场景的多 Agent 运营工作台

核心定位：

> 面向东南亚跨境电商推荐与营销场景，基于 Spring Boot + Spring AI 自建多 Agent 编排与受限 Agent Loop，支持区域/币种/语言/平台/海外仓等跨境约束下的用户画像、商品召回重排、库存履约校验、营销文案本地化、运行事件流、工具调用审计和基础评测。

不要实现“售后工单、政策检索、人工审批退款”这些当前方向之外的内容。

---

## 必须补齐的关键词映射

| 简历关键词 | 必须有的代码支撑 |
|---|---|
| 跨境电商 | `region`、`locale`、`currency`、`platform`、`warehouseRegion`、`deliveryDays` 等字段和过滤逻辑 |
| 推荐场景 | `/api/v1/recommend`、`ProductRecAgent`、推荐任务请求/响应 |
| 多 Agent | `UserProfileAgent`、`ProductRecAgent`、`InventoryAgent`、`MarketingCopyAgent`、新增跨境物流/本地化逻辑也可以合入现有 Agent |
| 运营工作台 | 保留/兼容前端与 Gateway 任务创建、SSE、Run 展示；Java 至少要提供可被工作台调用的接口 |
| Java / Spring Boot | Java 子项目核心服务 |
| Spring AI | LLM 画像、重排、文案生成、Planner |
| TypeScript / React | 前端已有，若改动要让页面能传 `region/locale/currency/platform` |
| PostgreSQL | 后端/Gateway 已有 Prisma schema；Java 如不接 PostgreSQL，文档必须说明 PostgreSQL 在 Gateway 层负责任务/事件持久化 |
| Redis | Java 版应新增 Redis FeatureStore 或至少提供 Redis-backed 用户行为特征服务，并 fallback 到 context/mock |
| SSE | `/api/v1/recommend/stream` 已有，需确保事件里包含跨境字段和 Agent 阶段 |
| Docker | docker-compose 已有；如新增 Java 配置，需补环境变量说明 |
| Tool Loop | `/api/v1/recommend/tool-loop` 和 `/api/v1/recommend/agent-loop` |
| 工具白名单 | `ToolLoopConfig.toolWhitelist` |
| 最大步数 | `ToolLoopConfig.maxSteps` |
| 重复调用检测 | `tool + trustedArguments` fingerprint |
| Evidence ID 校验 | `EvidenceRecord`、`ToolObservation.evidenceIds`、`final_answer` 校验 |
| 评测回归 | `/api/v1/evaluations/smoke`，检查跨境约束、库存、文案本地化、证据、延迟 |

---

## Java 必做改动

### 1. 扩展请求模型

修改 `RecommendationRequest`，新增：

- `platform`：默认 `shopify`
- `region`：默认 `SEA`
- `country`：默认 `SG`
- `locale`：默认 `en-SG`
- `currency`：默认 `SGD`

要求：

- 保持向后兼容，旧请求只传 `userId/numItems` 仍能运行。
- 这些字段必须进入 SSE 事件、Tool Loop trusted arguments、最终响应或 AgentResult data。

### 2. 扩展商品模型

修改 `Product`，新增：

- `supportedRegions: List<String>`
- `currency`
- `warehouseRegion`
- `deliveryDays`
- `platform`
- `crossBorderEligible`

要求：

- mock 商品中至少覆盖 `SG/MY/TH/ID/VN` 中 3 个国家。
- 至少存在一两个商品因区域/仓库/跨境资格被过滤。

### 3. 增加 Commerce Connector 抽象

新增包建议：

`com.ecommerce.connector`

新增接口：

```java
public interface CommerceConnector {
    String platform();
    List<Product> searchProducts(RecommendationRequest request, UserProfile profile, int limit);
    Map<String, Object> getOrderSnapshot(String userId, String region);
}
```

新增实现：

```java
@Service
public class ShopifyDevelopmentStoreConnector implements CommerceConnector
```

要求：

- 当前可以用 mock 数据，但必须明确模拟 Shopify Development Store。
- ProductRecAgent 不再直接持有 `MOCK_PRODUCTS` 作为唯一数据源，优先通过 connector 搜索商品。
- 如果暂时不想大改 ProductRecAgent，可以先抽出 `ProductCatalogService`，但接口名要体现 connector/platform。

### 4. Redis 用户行为特征

新增服务建议：

`RedisFeatureStoreService`

能力：

- `recordBehavior(userId, behaviorType, productId, metadata)`
- `getUserFeatures(userId, request)`

要求：

- 使用 `StringRedisTemplate` 或 Spring Data Redis。
- Redis 不可用时 fallback 到 request context/mock。
- 暴露接口：
  - `POST /api/v1/users/{userId}/behaviors`
  - `GET /api/v1/users/{userId}/features`
- `UserProfileAgent` 优先读取 Redis 特征，再 fallback。

### 5. 跨境推荐过滤与重排

ProductRecAgent 必须考虑：

- `request.region/country`
- `request.currency`
- `request.platform`
- `supportedRegions`
- `crossBorderEligible`

LLM 重排 prompt 中必须包含：

- 用户区域
- 本地币种
- 预计配送天数
- 仓库区域
- 平台

禁止让模型编造商品字段。模型只输出商品 ID。

### 6. 库存/履约 Agent 增强

InventoryAgent 增强为“库存与跨境履约校验”：

- 有库存
- 支持目标国家/区域
- 可跨境销售
- 海外仓/本地仓配送天数
- 低库存预警

输出 data 中至少包含：

- `available_products`
- `fulfillment_warnings`
- `delivery_estimates`
- `blocked_products`

### 7. 营销文案本地化

MarketingCopyAgent 增强：

- 根据 `locale` 生成文案。
- `en-SG` 输出英文。
- `zh-CN` 输出中文。
- 可支持 `ms-MY`、`th-TH` 的 prompt 描述，但不要求真实高质量多语言。

要求：

- Prompt 中包含 `locale/currency/country`。
- 合规词检查区分中文和英文基本风险词。
- 返回 `copy_locale` 或 `locale`。

### 8. Agent Loop 增强

`AutonomousAgentLoopService` 需要把跨境字段纳入：

- Planner state
- trusted arguments
- evidence IDs

工具建议增加或重命名：

- `get_user_profile`
- `search_cross_border_products`
- `rerank_products`
- `check_fulfillment_inventory`
- `generate_localized_copy`
- `final_answer`

可以保留旧工具名做兼容，但新接口返回中要能体现跨境语义。

### 9. Smoke Evaluation 增强

`RecommendationEvaluator` 增加检查：

- 所有推荐商品支持 request.country 或 request.region。
- 所有推荐商品 `crossBorderEligible == true`。
- 商品币种与 request.currency 一致，或明确有转换字段。
- 文案 locale 与 request.locale 一致。
- final answer evidence IDs 均来自已产生 evidence。

### 10. SSE 事件增强

`/api/v1/recommend/stream` 事件 data 中要包含：

- `platform`
- `region`
- `country`
- `locale`
- `currency`

Phase summary 要体现：

- 跨境商品召回
- 库存与履约校验
- 本地化文案生成

---

## 推荐接口示例

### Supervisor 推荐

```json
{
  "userId": "user_001",
  "scene": "homepage",
  "numItems": 5,
  "platform": "shopify",
  "region": "SEA",
  "country": "SG",
  "locale": "en-SG",
  "currency": "SGD",
  "context": {
    "recent_views": ["phone", "earbuds"],
    "avg_order_amount": 300
  }
}
```

### Agent Loop 推荐

```json
{
  "request": {
    "userId": "user_001",
    "scene": "homepage",
    "numItems": 5,
    "platform": "shopify",
    "region": "SEA",
    "country": "SG",
    "locale": "en-SG",
    "currency": "SGD"
  },
  "config": {
    "maxSteps": 8,
    "toolWhitelist": [
      "get_user_profile",
      "search_cross_border_products",
      "rerank_products",
      "check_fulfillment_inventory",
      "filter_products",
      "generate_localized_copy",
      "final_answer"
    ]
  }
}
```

---

## 面试时必须能说清楚

### 为什么是跨境电商？

必须有代码支撑的回答：

> 项目不是只在标题上写跨境，而是在请求、商品、推荐过滤、库存履约和文案生成里都加入了跨境约束。请求中有平台、区域、国家、语言和币种；商品模型中有支持区域、仓库区域、配送天数、币种和是否支持跨境销售；推荐 Agent 会按目标国家和平台召回商品，库存 Agent 会做可售和履约校验，文案 Agent 会根据 locale 做本地化生成。

### Agent 架构是什么？

> Java 版基于 Spring Boot + Spring AI 自建 Agent Runtime，没有使用 LangChain。主链路是 Supervisor 工作流型多 Agent；同时提供 LLM Planner 驱动的受限 Agent Loop，支持 Thought -> Action -> Observation 循环、工具白名单、最大步数、重复调用检测、可信参数重建和 Evidence ID 校验。

### 和普通 Service 有什么区别？

> 普通 Service 是固定业务函数调用；这里每个 Agent 有独立目标、输入输出和 LLM 推理能力。LLM Planner 版本中，模型可以根据中间 Observation 选择下一步工具，但服务端仍然负责工具治理和证据校验。

---

## 不要做的事

- 不要把项目改成售后工单系统。
- 不要把没有真实实现的功能写成生产级能力。
- 不要让模型直接决定可信参数，例如 userId、country、currency 必须由服务端 request 重建。
- 不要让最终答案引用不存在的 evidence ID。
- 不要把 mock connector 说成真实 Shopify API 调用，除非真的接 API。

---

## 验收标准

1. Java 项目可以编译通过。
2. `/api/v1/recommend` 能返回带跨境字段过滤后的商品。
3. `/api/v1/recommend/stream` 能看到跨境推荐过程事件。
4. `/api/v1/recommend/agent-loop` 返回 thoughts、toolCalls、observations、evidences。
5. 白名单缺少必要工具时，Agent Loop 被阻断。
6. final answer 引用不存在 evidence ID 时，必须被阻断。
7. `/api/v1/evaluations/smoke` 能检查跨境区域、库存履约、文案 locale 和基础延迟。
8. README 或文档明确说明：当前 Shopify connector 是 Development Store mock，不是生产 API。
