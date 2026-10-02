# 多 Agent 推荐系统接入 EComAgentBench 的实现方案

## 1. 最终选择

主公开 Benchmark 选择 **EComAgentBench**：

- 官方代码：https://github.com/Morizeyao/EComAgentBench_
- 公开任务：662 个已验证购物任务。
- 商品数据：基于 Amazon Reviews 2023，官方提供约 25 GB 的预构建 `product.db`。
- 任务特点：用户需求分散在可见 Query、工具可见的 Persona 和脚本化 Clarification 中；Agent 需要搜索、查看详情和评论，最后推荐一个商品。
- 官方指标：`overall_accuracy`、`exact_match_rate`、Rubric Satisfaction、分 Intent 准确率、finish rate、工具调用数、Persona 使用率和 Clarification 使用率。

不把 WebShop 作为主评测。WebShop 主要测试网页搜索、翻页、选择按钮和购买操作，会把当前项目变成 Browser Agent；Amazon Reviews 2023 单独使用只是推荐数据集，不是完整 Agent Benchmark。

## 2. 评测要回答的问题

本轮不是只跑出一个准确率，而是回答四个问题：

1. 当前 Java 多 Agent 能否在公开商品环境中完成长程购物任务？
2. 相同模型、相同工具、相同任务下，多 Agent 是否优于单 Agent？
3. Profile、Clarification、约束验证和 VETO 分别带来多少收益？
4. 收益是否值得额外的模型调用、延迟和工具调用成本？

## 3. 公开 Benchmark 与当前 Agent 的映射

| 当前角色 | EComAgentBench 中的职责 | Benchmark 工具 |
|---|---|---|
| Supervisor | 判断下一名专业 Agent、处理修订、提交唯一商品 | `recommend_product` |
| Profile Agent | 主动获取隐藏 Persona；信息不足时发起 Clarification | `get_user_profile`、`ask_clarification` |
| Product Agent | 搜索、过滤、查看商品详情和评论 | `search_products`、`filter_products`、`get_product_details`、`get_reviews` |
| Inventory Agent | Benchmark 模式下作为硬约束验证者，校验预算、属性、负向条件和评论证据，并对候选商品发出 VETO | 不新增能看到答案的工具，只消费 Product 已获取的官方 Observation |
| Copy Agent | 根据已验证 Evidence 生成最终推荐理由，不允许改变 Product ID | 无额外检索工具，只整理已知 Evidence |

注意：EComAgentBench 没有真实库存和履约数据，所以这一公开评测只能验证 Inventory Agent 的“约束验证/VETO”能力，不能据此声称库存准确率得到公开 Benchmark 验证。真实库存和 Copy 权限仍由项目已有的内部协作测试覆盖。

## 4. 不能采用的错误接法

### 4.1 不把 `target_product` 或 `rubrics` 放入请求

官方 `benchmark.jsonl` 中包含目标商品和评分 Rubric，但这些只能由 Evaluator 读取。Java Agent 只能看到：

- `sample_id`
- `user_query`
- 通过工具主动获取的 Persona
- 通过工具主动获得的 Clarification
- 官方搜索、详情和评论工具返回的数据

如果把 `target_product`、`rubrics`、`expected_value` 或 `linked_rubric_ids` 放入 Blackboard，就属于答案泄漏，结果无效。

### 4.2 不把 Benchmark 数据转成 Prompt 后一次性喂给模型

这样只是在做长 Prompt 问答，测不到工具使用、Profile Agent、Clarification 和多 Agent 协作。

### 4.3 不在 Java 中重新实现一份简化搜索器

必须调用官方工具和官方 `product.db`，否则商品候选空间、检索行为和官方 Agent 不一致，无法公平比较。

## 5. 总体集成架构

```text
EComAgentBench benchmark.jsonl
          ↓（只传 sample_id + user_query）
Python Pair Runner
          ↓ HTTP
Java SupervisorMultiAgentRuntime
          ↓
Profile / Product / Inventory-Constraint / Copy Agent
          ↓ HTTP tool call（携带 sample_id/session_id）
Official Tool Sidecar
          ↓
EComAgentBench official tools + product.db
          ↓ Observation
Java Blackboard
          ↓
recommend_product(product_id, evidence)
          ↓
Official predictions JSONL
          ↓
EComAgentBench official evaluator
```

Python 只承担官方环境适配和批量运行；Agent 规划、Agent 委派、Blackboard、VETO、工具权限和停止条件都由 Java Runtime 执行。不能在 Python 中复制一套多 Agent 逻辑，否则评测的不是当前项目代码。

## 6. 第一阶段：抽象可插拔 Agent Environment

当前 `SpecialistAgentTeam` 和 `AutonomousAgentLoopService` 直接依赖 `RecommendationPipelineState`、`ScenePathEnforcer` 和 `RecommendationPipelineExecutor`。为了接入公开环境，先抽象三个接口。

### 6.1 拟新增接口

```java
public interface MultiAgentBlackboard {
    String runId();
    String scene();
    Set<String> knownEvidenceIds();
    List<AgentMessage> messages();
    boolean hasUnhandledVeto();
}

public interface MultiAgentEnvironment<S extends MultiAgentBlackboard> {
    List<AgentId> allowedAgents(S state);
    List<String> executableTools(AgentId agent, S state);
    Map<String, Object> trustedArguments(AgentId agent, String tool, S state);
    ToolObservation execute(AgentId agent, String tool, S state);
    boolean completionSatisfied(S state);
    Object finalAnswer(S state);
}

public interface AgentEnvironmentFactory {
    String environmentName();
    MultiAgentEnvironment<?> create(EvaluationTask task);
}
```

### 6.2 生产环境适配器

新增 `RecommendationAgentEnvironment`，内部继续调用现有：

- `RecommendationPipelineState`
- `ScenePathEnforcer`
- `RecommendationPipelineExecutor`

这一步只重构依赖，不改变 `/api/v1/recommend` 的行为。原 355 项 Java 测试必须全部通过，工具顺序、VETO 和接口响应不得变化。

### 6.3 通用 Runtime

将 Supervisor 外层 Loop 和 Specialist 内层 Loop 抽到：

```text
SupervisorMultiAgentRuntime
BoundedSpecialistAgentRuntime
```

Runtime 只依赖 `MultiAgentEnvironment`，不直接知道 PostgreSQL、库存或 EComAgentBench。

## 7. 第二阶段：官方工具 Sidecar

在 `benchmarks/ecomagentbench/adapter/` 增加 Python Sidecar，直接导入官方工具实现。

### 7.1 Sidecar API

```text
POST /sessions
POST /sessions/{sessionId}/tools/get_user_profile
POST /sessions/{sessionId}/tools/ask_clarification
POST /sessions/{sessionId}/tools/search_products
POST /sessions/{sessionId}/tools/filter_products
POST /sessions/{sessionId}/tools/get_product_details
POST /sessions/{sessionId}/tools/get_reviews
POST /sessions/{sessionId}/finish
DELETE /sessions/{sessionId}
```

### 7.2 Session 初始化

Runner 只把 `sample_id` 交给 Sidecar。Sidecar 内部读取完整 Benchmark Sample，但对 Java 只开放官方 Agent 本应通过工具看到的信息。

Sidecar 必须实现字段级输出过滤测试，禁止以下字段出现在任何 Agent Observation 中：

```text
target_product
rubrics
expected_value
linked_rubric_ids
validation_*
judge_*
```

### 7.3 会话隔离

每个 Sample 建立独立 `sessionId`，Clarification 的已询问槽位、工具步数和结束状态不能跨样本共享。Sidecar 不负责 Agent 规划。

## 8. 第三阶段：Java EComAgentBench Environment

### 8.1 Blackboard

新增 `EcomAgentBenchBlackboard`，只保存本轮必要数据：

```text
sampleId
userQuery
personaFacts
clarificationFacts
searchResults
inspectedProducts
reviewEvidence
candidateProductIds
vetoedProductIds
selectedProductId
agentMessages
toolCalls
evidenceIds
```

### 8.2 工具权限

Benchmark 模式的工具域必须在服务端写死：

```text
PROFILE:
  get_user_profile
  ask_clarification

PRODUCT:
  search_products
  filter_products
  get_product_details
  get_reviews

INVENTORY:
  verify_candidate_constraints

COPY:
  draft_evidence_grounded_rationale

SUPERVISOR:
  recommend_product
```

`verify_candidate_constraints` 和 `draft_evidence_grounded_rationale` 是 Java 本地逻辑/模型动作，只能使用 Blackboard 已有 Observation，不能访问官方答案字段。

### 8.3 可信参数

模型仍然只选择 Action。Java 根据 Blackboard 重建参数：

- Profile 工具固定绑定当前 `sessionId`。
- Search Query 可以由 Product Agent生成，但必须保存原始值和长度限制。
- 商品详情和评论的 `product_id` 必须来自本轮 `searchResults`。
- 最终 `recommend_product.product_id` 必须来自已查看详情并通过约束验证的候选集合。

## 9. 第四阶段：配对实验设计

必须使用同一个任务集合、商品数据库、Agent 模型、Judge 和调用上限。

### 9.1 实验组

| 组别 | 作用 |
|---|---|
| `official_single_agent` | 官方 EComAgentBench Agent，作为公开基线 |
| `java_single_agent` | 一个 Java Agent 拿到同一组官方工具，用于排除语言和 HTTP Adapter 差异 |
| `java_multi_agent` | Supervisor + Profile + Product + Inventory-Constraint + Copy |
| `multi_no_profile` | 去掉 Profile Agent，测 Persona 收益 |
| `multi_no_clarification` | 禁止 Clarification，测主动询问收益 |
| `multi_no_veto` | Constraint 只打分、不否决，测 VETO 收益 |

简历最终只报告前三组的主要结果；后三组用于解释提升来自哪里。

### 9.2 模型设置

Agent 模型统一使用 DeepSeek Flash：

```text
model = deepseek-v4-flash（以实际可用模型 ID 为准）
temperature = 0.0 或 provider 支持的最低值
max_tool_calls = 40
max_llm_calls = 60
timeout = 120s / sample
```

必须把实际模型 ID、base URL、运行日期和服务端返回的模型版本写入报告，不能只写“DeepSeek Flash”。

官方评测使用独立 Judge。严格可比版本使用 EComAgentBench 配置的 Gemini Judge；如果暂时只能使用 DeepSeek Judge，结果必须标注为 `non-official judge exploratory result`，不能与论文数字直接横向比较。

### 9.3 两种公平口径

同时报告：

1. **能力上限口径**：相同工具步数，允许多 Agent 消耗额外 Supervisor 调用。
2. **等成本口径**：限制所有组的总 LLM 调用数或总 Token/费用。

如果只控制工具步数而不控制模型调用，多 Agent 的优势可能只是多花了模型调用成本。

### 9.4 重复运行

即使 temperature 较低，也至少运行 3 个独立 Seed：

```text
seed = 11, 22, 33
```

最终报告均值、标准差，并保留每个 Sample 的配对结果。

## 10. 指标体系

### 10.1 官方指标

必须原样保留官方名称：

- `overall_accuracy`
- `exact_match_rate`
- `non_exact_but_correct_rate`
- `overall_rubric_satisfaction`
- Query / Persona / Clarification Rubric Satisfaction
- Attribute / Numeric Range / Entity / Negative Attribute / Budget / Review Opinion Satisfaction
- `by_intent`
- finish rate
- persona utilization rate
- clarification usage
- tool-call count

主指标使用 `overall_accuracy`，确定性的 `exact_match_rate` 必须单独报告，不能只报告 LLM Judge 分数。

### 10.2 项目附加指标

这些不是官方 Benchmark 指标，需要加 `custom_` 前缀：

- `custom_delegation_valid_rate`
- `custom_scope_violation_rate`
- `custom_duplicate_tool_rate`
- `custom_veto_trigger_rate`
- `custom_veto_recovery_rate`
- `custom_evidence_valid_rate`
- `custom_avg_supervisor_calls`
- `custom_avg_specialist_calls`
- `custom_avg_latency_ms`
- `custom_avg_llm_calls`
- `custom_avg_tokens`
- `custom_estimated_cost`

## 11. 数据切分与防止调参污染

官方 662 个任务全部公开，因此工程上仍要人为冻结调参边界：

1. 按 `hash(sample_id) % 10` 确定划分。
2. `0-1` 为开发集，约 20%。
3. `2-9` 为最终保留集，约 80%。
4. 按 8 个 Intent 分层检查，避免某类任务集中在单一集合。
5. 开发期间只查看开发集的逐题答案和失败轨迹。
6. 最终保留集只在方案冻结后完整运行。

Smoke 集固定为 16 题，每个 Intent 选择 2 题，只检查链路是否正常，不用于调 Prompt 指标。

## 12. 实现目录

```text
benchmarks/ecomagentbench/
├── README.md
├── upstream.lock
├── configs/
│   ├── deepseek-flash-smoke.yaml
│   ├── deepseek-flash-dev.yaml
│   └── deepseek-flash-full.yaml
├── adapter/
│   ├── official_tool_sidecar.py
│   ├── sample_guard.py
│   ├── prediction_writer.py
│   └── java_pair_runner.py
├── scripts/
│   ├── bootstrap.ps1
│   ├── run_smoke.ps1
│   ├── run_pair.ps1
│   ├── evaluate.ps1
│   └── summarize.ps1
└── tests/
    ├── test_no_target_leakage.py
    ├── test_tool_contract.py
    ├── test_prediction_schema.py
    └── test_pairing.py

java/src/main/java/com/ecommerce/eval/ecomagentbench/
├── EcomAgentBenchController.java
├── EcomAgentBenchEnvironment.java
├── EcomAgentBenchBlackboard.java
├── EcomAgentBenchToolClient.java
├── EcomAgentBenchRequest.java
└── EcomAgentBenchPrediction.java
```

`upstream.lock` 必须记录官方仓库 URL、commit SHA、数据集版本和 `product.db` SHA-256，防止 Benchmark 更新后结果不可复现。

## 13. 计划中的运行命令

以下命令在实现完成后提供，不能在脚本中硬编码 API Key：

```powershell
cd benchmarks/ecomagentbench
./scripts/bootstrap.ps1

# 16 题链路测试
./scripts/run_smoke.ps1

# 开发集三组配对实验
./scripts/run_pair.ps1 -Split dev -Seeds 11,22,33

# 冻结后运行保留集
./scripts/run_pair.ps1 -Split heldout -Seeds 11,22,33

# 官方评测和配对汇总
./scripts/evaluate.ps1
./scripts/summarize.ps1
```

Java 测试：

```powershell
cd java
mvn test
```

普通 `mvn test` 和 Python 单元测试不能发起真实 LLM 调用。Live Benchmark 必须要求显式：

```text
ECOM_RUN_LIVE_ECOM_BENCH=true
ECOM_LLM_API_KEY=...
ECOM_LLM_MODEL=...
ECOM_ECOM_BENCH_DB=...
```

## 14. 输出文件

每次运行生成：

```text
runs/{timestamp}/manifest.json
runs/{timestamp}/predictions_{variant}_{seed}.jsonl
runs/{timestamp}/trajectories_{variant}_{seed}.jsonl
runs/{timestamp}/official_evaluation_summary.json
runs/{timestamp}/paired_comparison.json
runs/{timestamp}/failure_analysis.md
runs/{timestamp}/report.md
```

`manifest.json` 至少记录：

- Git commit 和 dirty 状态
- Benchmark commit / dataset checksum
- Agent 模型、Judge 模型和 provider
- Prompt hash
- 工具和模型调用上限
- Seed
- 开始/结束时间
- 完成样本数、失败样本 ID

## 15. 统计和晋级门槛

不能只比较两个百分比。对每个 Sample 做配对统计：

- `overall_accuracy` 使用 paired bootstrap，10,000 次重采样，报告 95% CI。
- Exact Match 使用 McNemar 检验。
- Rubric Satisfaction 报告宏平均和按 Intent 分层结果。
- 成本和工具调用报告中位数、P90，不只报告平均数。

多 Agent 版本的建议晋级条件：

1. `overall_accuracy` 相对 `java_single_agent` 提升至少 3 个百分点。
2. 配对 bootstrap 的 95% CI 下界大于 0。
3. Exact Match 不下降超过 1 个百分点。
4. `custom_scope_violation_rate = 0`。
5. `custom_evidence_valid_rate = 100%`。
6. finish rate 不低于单 Agent。
7. 等成本口径下仍有正收益，或者明确说明质量与成本交换关系。

如果未达到门槛，也必须保留并报告真实结果，不能只挑选成功样本。

## 16. 跑完以后如何修改

### 16.1 先冻结 V0

第一次 Full Run 前冻结：Prompt、Agent 工具域、模型参数、数据划分和代码 commit。V0 结果禁止覆盖。

### 16.2 按失败位置分类

自动生成以下失败标签：

```text
profile_not_used
clarification_not_asked
query_constraint_missed
persona_constraint_missed
clarification_constraint_missed
retrieval_miss
details_not_inspected
review_evidence_missed
constraint_validator_false_accept
constraint_validator_false_veto
premature_finish
tool_budget_exhausted
invalid_final_product
```

### 16.3 只在开发集单变量修改

每轮只修改一个因素：

- Profile 使用率低：调整 Supervisor 委派条件，不修改 Product Prompt。
- Clarification 使用率低：增加“缺失关键约束”检测，不直接把 Clarification 内容注入 Prompt。
- Retrieval Miss：修改 Product Query Reformulation 或搜索轮次。
- Review Rubric 低：要求 Product 对候选 Top-N 调用 `get_reviews`。
- False Accept 高：修改 Inventory-Constraint 的结构化验证输出。
- Premature Finish：加强 Supervisor 完成条件和 Evidence Gate。
- 成本过高：减少重复详情/评论调用，使用候选缓存，而不是降低正确性约束。

每次改动必须记录实验 ID、父实验、唯一变量、开发集结果和回滚条件。

### 16.4 保留集只做最终验证

在开发集选择一个最终版本后，才运行 held-out 集。若 held-out 没有提升，不继续针对 held-out 调 Prompt；应该回到错误分类和开发集重新设计，防止把公开测试集调成训练集。

## 17. 测试要求

实现时至少增加：

1. Target/Rubric 泄漏测试。
2. Agent 工具域测试。
3. Profile 未调用前 Persona 不可见测试。
4. Clarification 只能通过工具获取测试。
5. Product 只能查看搜索结果中的商品测试。
6. Copy 无法修改最终 Product ID 测试。
7. VETO 后候选移除测试。
8. 重复工具调用阻断测试。
9. 超步数停止测试。
10. 官方 Prediction JSONL Schema 测试。
11. 同一 Sample/Seed 可复现测试。
12. Java 服务失败时 Runner 记录失败而不是静默跳题测试。

## 18. 预计实施顺序

### P0：官方环境验证

- 锁定 EComAgentBench commit。
- 下载 `benchmark.jsonl`。
- 只下载官方预构建 `product.db`，不先重建 25 GB 数据。
- 用官方 Agent 跑 2 题 Smoke，确认工具和 Evaluator 可用。

### P1：Runtime 抽象

- 抽出 `MultiAgentEnvironment`。
- 用 `RecommendationAgentEnvironment` 保持生产接口行为不变。
- 全量 Java 回归测试通过。

### P2：Benchmark Adapter

- 实现 Python Tool Sidecar。
- 实现 Java Benchmark Environment 和 Prediction 输出。
- 通过泄漏、权限和 Schema 测试。

### P3：小规模实验

- 16 题 Smoke。
- 开发集单 Seed。
- 修复链路错误，不根据保留集调参。

### P4：正式配对实验

- 三个 Seed。
- Official Single、Java Single、Java Multi。
- 官方 Judge + 配对统计 + 失败分析。

## 19. 最终简历指标格式

结果跑完前不要预写数字。满足统计门槛后可写：

> 基于公开 EComAgentBench 662 个长程购物任务构建 Java 多 Agent 适配与配对评测，在相同 DeepSeek Flash、官方工具和调用预算下，相较单 Agent，Overall Accuracy 由 X% 提升至 Y%，Persona/Clarification Rubric Satisfaction 分别提升 A/B 个百分点；同时保持工具越权率 0%，平均模型调用增加 C 次。

如果只跑了子集，必须写明：

> 在 EComAgentBench 分层抽样 N 题上……

不能把 N 题结果写成完整 Benchmark 成绩。

## 20. 本方案的边界

- EComAgentBench 主要验证购物推荐、隐藏偏好获取、搜索和评论证据，不验证真实库存系统。
- LLM Judge 不是绝对真值，所以必须同时报告 Exact Match，并抽样人工复核 Judge。
- 官方商品库约 25 GB，下载和存储需要提前准备。
- 当前 Benchmark 很新，必须锁定 commit 和数据 checksum，避免未来版本漂移。
- 只有使用官方任务、官方工具环境和官方 Evaluator 的结果，才能称为 EComAgentBench 结果；内部改写任务只能称为 EComAgentBench-derived。
