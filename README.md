# Agentic Commerce Platform

面向电商推荐与售后运营场景的 Agent 工程平台：涵盖受限 Agent 决策、证据规划、Human-in-the-loop、可靠副作用执行、实时 Trace 与评测。

核心交付物是 **Java 售后 Agent 信任边界**：模型可以参与理解与规划，但任何真实业务决策和副作用都必须穿过服务端确定性边界、审批 Gate 与可靠执行链。

```text
Customer Message
      ↓
Bounded LLM Intake                       ← 模型只能输出受限分类
      ↓
Server-side Decision Route               ← Java 决定路线与 requiredEvidence
      ↓
Constrained Evidence Planner             ← 模型只能选择下一项证据
      ↓
Trusted Read-only Tools                  ← 参数只来自服务端 trustedArguments
      ↓
Evidence（ORDER / SHIPMENT / POLICY）
      ↓
Deterministic Java Decision
      ↓
     Route
 ┌────┴─────────┐
 │              │
ANSWER       COMPENSATION
 │              │
RESOLVED     Rule Engine（阈值/国家/版本/金额）
                ↓
             ActionProposal
                ↓
              HITL（人工审批）
                ↓
        ApprovalPolicyGate                ← 审批前服务端重验业务不变量
                ↓
        Idempotent Executor               ← 唯一执行任务，绝不重复副作用
                ↓
        Retry / Dead Letter
```

---

## Trust Boundary

这是本项目最大的设计点：模型只能提出计划，Java 决定计划是否允许执行。

### LLM 可以

- 理解客户诉求并输出受限 Intent（`TRACK_SHIPMENT` / `REQUEST_REFUND`）
- 选择下一项 Evidence（`ORDER` / `SHIPMENT` / `POLICY` / `READY_FOR_DECISION`）

### LLM 不可以

- 生成真实工具参数（金额、订单号、政策版本等一律由服务端 `AfterSalesToolExecutor.trustedArguments(...)` 产生）
- 决定补偿金额 / 政策版本
- 批准或驳回方案、直接执行副作用
- 修改 Policy、修改 requiredEvidence、输出决策路线

### Java 控制

- `DecisionRouteResolver`：白名单意图 → 路线，服务端重建 requiredEvidence
- `EvidencePreconditionGate`：证据依赖校验（SHIPMENT 依赖 ORDER，POLICY 依赖 ORDER+SHIPMENT，READY 要求证据齐备），LLM 规划形式合法但前置不满足 → `LLM_INVALID_PLAN` → 规则兜底
- `AfterSalesToolExecutor.trustedArguments`：工具参数唯一来源
- `DemoAfterSalesPolicyCatalogService`：版本化政策目录，按订单国家 + 问题类型 + 发生时间匹配历史版本
- `CompensationRuleService`：确定性补偿计算（阈值、比例、上限）
- `ApprovalPolicyGate`：审批前重验 Proposal 状态、Ticket/Run 终态、四类证据完整（order/shipment/policy/calculation）、政策版本与上下文、金额/币种/动作类型与规则重算一致
- Idempotency / Execution / Retry / Dead Letter

### 客户端控制

- 审批人只提交 `{"comment": "..."}`；`operatorId` 由可信 Gateway Header（`X-Authenticated-Operator`）注入，Java 不接受请求 body 中的身份字段。生产环境该 Header 必须由可信 Gateway 清洗并覆盖，客户端不可自行设置。

---

## 真实 Demo Scenarios

演示订单为 SEA 多国家（SG / MY / TH / ID / VN）确定性种子数据，如 `O-VN-5002`（VN，10 天物流未更新，可补偿）、`O-VN-5003`（2 天，未达阈值）。

### 1. 只查物流

```text
TRACK_SHIPMENT → ANSWER_ONLY → ORDER → SHIPMENT → RESOLVED
```

只取证订单与物流，直接以可信物流状态答复，不检索政策、不计算补偿。

### 2. 延迟不足（无可补偿动作）

```text
REQUEST_REFUND → COMPENSATION_EVALUATION → Policy → eligible=false → NO_ACTION_REQUIRED
```

物流未更新天数低于政策阈值，规则引擎判定不可补偿，工单直接完成，不生成任何方案。

### 3. 满足补偿条件（Human-in-the-loop）

```text
eligible=true → ActionProposal → Human Approval → ApprovalPolicyGate → Idempotent Execution
```

规则计算补偿金额（如 `O-VN-5002`：1899000 VND × 10% 与 150000 VND 上限取小 = 150000.00 VND 延迟补偿券），生成待审批方案；审批通过后创建带幂等键的唯一执行任务。

### 4. 执行异常（重试 / 死信）

```text
Execution Failed → RETRY_WAIT → Retry → DEAD_LETTER / SUCCEEDED
```

执行任务失败进入重试等待，超过策略上限进入 DEAD_LETTER，全程可追踪。

---

## 模块布局

| 目录 | 内容 |
|---|---|
| `java/` | Spring Boot 3.4 服务：`com.ecommerce.aftersales`（售后信任边界，见上）与推荐模块。LLM 走 Spring AI 的 OpenAI 兼容协议（默认 DeepSeek base URL），AUTO 模式下无 key 时纯规则运行、不发网络请求 |
| `backend/` | NestJS Gateway：API 网关、认证、`/api/v1/after-sales/**` 路由、SSE 事件代理、`X-Authenticated-Operator` 注入 |
| `frontend/` | React + Ant Design 运营工作台：售后工单队列与三栏 Workspace（工单 / SSE Trace / 审批），审批人只读展示 |
| `python/` | 推荐 Agent 模块（历史实验代码，依赖与测试不稳定，**未纳入 CI**） |
| `go/` | 推荐服务 Go 实现（历史代码） |
| `docs/` | 文档；历史教程/面试/简历内容已归档，见「文档」一节 |

---

## 本地运行

```bash
docker compose up --build
```

启动后访问 `http://localhost:5173/after-sales`。Gateway 在 `:3000`，Java 服务在 `:8080`。不配置 `ECOM_LLM_API_KEY`（或使用占位值）时，Intake / Planner 的 AUTO 模式直接走 Java 规则，不发任何网络请求、不产生费用。

## 测试与评测

```bash
cd java && mvn test          # 单元 + 集成 + 离线 Agent 评测（默认不含 Live LLM Eval）
```

### Offline Eval（默认，随 `mvn test` 运行）

离线评测（`AfterSalesEvalHarnessTest`，随 `mvn test` 运行）：

- 40 条真实 JSONL 用例（`java/src/test/resources/after-sales-eval.jsonl`），覆盖意图/路线、政策阈值/国家/版本、Planner 正常/非法/重复/跳过前置/过早 READY、Prompt Injection 安全
- 指标由执行真实生产组件（Intake / Route / Planner / Gate / 工具 / 规则引擎）计算
- 安全门禁：**Unauthorized Action Rate、Model Amount Acceptance Rate、Model Tool Argument Acceptance Rate 必须全为 0**，否则测试失败
- 报告输出到 `java/target/after-sales-eval/`（`after-sales-eval-report.json` 机器可读 + `after-sales-eval-report.md`）

最近一次全量运行的汇总（来自真实执行，会随代码变化）：

| 指标 | 值 |
|---|---|
| Case Count | 40 |
| Intent / Route Accuracy | 100% / 100% |
| Evidence Precision / Recall | 100% / 100% |
| Planner Valid / Fallback Rate | 75.69% / 24.31% |
| Completion Rate | 97.50%（1 条按设计失败关闭：无适用政策版本 → `POLICY_NOT_FOUND`） |
| Unauthorized Action / Model Amount / Model Tool Args | 0% / 0% / 0% |

### Optional Live LLM Eval（可选，手动运行，默认关闭）

与 Offline Eval 完全分离的另一套评测：使用**真实配置的模型**测量真实模型对客户意图的理解质量与证据规划质量。它调用外部 LLM API、**可能产生费用**、依赖网络、且有随机性——**绝不进入默认 CI 门禁**。普通 `mvn test` 只运行 Offline Eval；Live Eval 需要显式开启 Maven profile **和** 环境变量：

```bash
cd java
ECOM_RUN_LIVE_EVAL=true mvn test -Plive-eval
```

- `-Plive-eval`：Maven profile，只选择 `LiveAfterSalesEvalRunnerTest`（`java/src/test/java/com/ecommerce/aftersales/eval/live/`），普通 `mvn test` 无法选中它（surefire 排除 Live Runner）
- `ECOM_RUN_LIVE_EVAL=true`：**必须**显式设置（大小写不敏感，`TRUE`/`True` 均可，与 JUnit 条件注解语义一致）；未设置时 Live Runner 在 JUnit 条件阶段干净跳过——不启动 Spring 上下文、零网络/模型调用
- 开启后 `ECOM_LLM_API_KEY` 缺失或为占位值（`your_api_key_here`）→ 测试**清晰失败**并给出修复指引，绝不静默回退规则
- Base URL / 模型无硬编码默认值：Live Runner 直接读取 Spring 属性 `spring.ai.openai.base-url` / `spring.ai.openai.chat.options.model`（默认值与 env 覆盖见下表，来自 `application.yml`）

环境变量：

| 变量 | 默认值 | 说明 |
|---|---|---|
| `ECOM_RUN_LIVE_EVAL` | （无） | `true`（忽略大小写）才运行 Live Eval |
| `ECOM_LLM_API_KEY` | `your_api_key_here` | 真实 API key，缺失/占位时开启即失败 |
| `ECOM_LLM_BASE_URL` | `https://api.deepseek.com` | OpenAI 兼容 base URL（`application.yml` 默认值；报告只记录清洗后的 URL） |
| `ECOM_LLM_MODEL` | `deepseek-v4-flash` | 评测模型（`application.yml` 默认值） |

Live Eval 执行的真实管线（停在副作用之前，绝不审批/建执行任务/调外部业务接口，取证在场为模拟的只读证据）：

```text
客户消息 → 真实 LLM AfterSalesIntakeService(mode=LLM) → DecisionRouteResolver
→ 真实 LLM AfterSalesEvidencePlannerService(mode=LLM) → EvidencePreconditionGate
```

规划循环与生产语义一致：每次 `plan()` 尝试恰好是四类之一——**accepted non-fallback plan**（LLM 输出解析成功且过前置 Gate）、**model output failure**（JSON/输出解析失败、超时、空响应、繁忙等，服务内降级为规则兜底）、**gate rejection**（解析成功但违反业务前置，计为 invalid 并用 `deterministicPlan`，不重复调用模型）、**invalid input**（防御性输入缺陷，fail-closed）。接受的兜底计划必须过同一 Gate；有界循环/无进展按失败关闭。报告输出到：

```text
java/target/after-sales-live-eval/live-eval-report.json   # 机器可读
java/target/after-sales-live-eval/live-eval-report.md     # 人读
```

报告记录：`generatedAt`、清洗后的 `baseUrl`、`model`、case 数、每个 case 的 ID/结果与实测指标（Intake Intent Accuracy、Route Accuracy、Planner Attempts / Accepted Non-Fallback / Invalid Plans（model failures + gate rejections）/ Fallback Cycles 及对应三个 Rate——**分母统一为 raw planner attempts**，任何 Rate 不可能 > 1，100% invalid 场景正确报告 100% Invalid；平均 Planner 调用数/工具调用数、Completion、Intake/Planner/E2E 延迟 p50/p95——**Planner 延迟为 per-case cumulative**，即同一 case 多轮 planner 调用的累计耗时）。**报告绝不包含**客户消息原文、完整 prompt、模型原始输出、思维链、API key 或认证头。

模型对比（分别生成报告，注意同一报告路径，跑第二个模型前请先归档第一个的报告）：

```bash
ECOM_RUN_LIVE_EVAL=true ECOM_LLM_MODEL=model-a mvn test -Plive-eval
ECOM_RUN_LIVE_EVAL=true ECOM_LLM_MODEL=model-b mvn test -Plive-eval
```

Token/Cost 指标限制：当前服务接口（`AfterSalesIntakeService` / `AfterSalesEvidencePlannerService`）只暴露结构化结果，不暴露 ChatResponse usage，因此报告中的 token/cost 指标标记为 **unavailable**，**绝不估算伪造**。

Live Eval 是**人工阅读的报告**，不是 CI 门禁：不用 100% 准确率做硬性断言；只有结构性安全（**未装配任何副作用能力，by construction 不可能产生审批/执行任务/外部工具调用**，报告中的零是结构事实而非观测值）与配置要求（flag/key）会硬性失败。

Live Eval 用例：`java/src/test/resources/after-sales-live-eval.jsonl`（28 条，与 Offline 的 40 条完全分离），覆盖：物流追踪、补偿/退款、模糊表达、英文/东南亚口音表达、Prompt Injection。

## Public Benchmark: τ³-bench Retail

与内部评测完全独立的第三方公开基准。三套评测数据互不混合、各自成报告：

| | Internal Offline Eval | Internal Live LLM Eval | τ³-bench Retail |
|---|---|---|---|
| 数据 | 40 条自建 JSONL | 28 条自建 JSONL | 官方 Retail task（base split，114 条） |
| 环境 | 自建 Java 组件 | 自建 Java 组件 | 官方 Retail Environment / Tools / Policy |
| User Simulator | 无 | 无 | 官方 User Simulator |
| Evaluator | 自建断言 | 自建断言 | 官方 Evaluator（Official Reward） |
| 运行方式 | `mvn test`（CI 门禁） | `ECOM_RUN_LIVE_EVAL=true mvn test -Plive-eval`（手动） | `benchmarks/tau3-retail/scripts/*.sh`（手动/专用 workflow） |

- 实现为 **benchmark adapter**：`Tau3TrustBoundaryAgent` 把项目的
  Bounded Planning + Server-side Guard 设计迁移进官方 Retail 环境验证，并
  非 Java 生产 Agent 直接运行于 τ³（官方工具/政策/DB/协议与生产不同）。
- τ³ 版本固定：`sierra-research/tau2-bench @ 79975ac5741e23fbb1d2ac44262d62398a6d87bd`，
  domain `retail`、split `base`（见 `benchmarks/tau3-retail/benchmark-lock.json`）。
- 主指标为官方 reward；guard 指标（拒绝/确认拦截等）仅作 supplemental。
- 未修改任何官方组件；不进默认 push/PR CI；未配置 API Key 时明确不运行、
  不伪造结果。详见 [benchmarks/tau3-retail/README.md](benchmarks/tau3-retail/README.md)。

## CI

`.github/workflows/ci.yml`，三个独立 job，触发条件为 **`main` / `feature/agent-platform-workbench` 的 push 与 PR**（当前默认开发分支为 `feature/agent-platform-workbench`，push 会触发 CI；若代码提交后 Actions 尚未出现运行记录，说明 workflow 已配置、等待 GitHub Actions 执行）：

- **java**：`mvn test`（含离线评测，不含 Live Eval），上传 `java/target/after-sales-eval/` 报告为 artifact
- **gateway**：Node 20 + `npm ci` + `prisma generate` + `typecheck` + `build`
- **frontend**：Node 20 + `npm ci` + `typecheck` + `build`

没有 Python job：Python 模块的历史依赖锁定与测试环境不稳定，不做 CI 是为了不把不稳定的失败强加到主线上；这是一个已知问题（见 Limitations），应作为独立任务修复依赖与测试后再加入。

CI 全部离线运行：不调用任何外部 LLM API、不需要 API key、不产生费用（无 key 时 AUTO 模式走规则，LLM 相关测试使用注入的模型输出替身）。Live LLM Eval 需要真实 API key、可能产生费用、依赖外部网络、有随机性，因此**不进入默认 push / PR CI 门禁**。

## 文档

- 本文件为工程 README；历史「面向小白」教程、三语言对比、面试八股文、简历模板等材料已归档：
  - 旧 README 全文：`docs/archived/legacy-tutorial-readme-2026-08.md`
  - `docs/interview-guide.md`、`docs/resume-template.md`、`docs/agent-one-day-guide.md`、`docs/tiktok-shop-ai-fullstack-interview-qa.md` 等

---

## Limitations（如实说明，尚未解决）

- **真实 OMS/WMS/物流未接入**：订单/物流数据来自 `MockShopifyAfterSalesConnector` 的确定性演示种子
- **Policy Catalog 仍为 Demo**：`DemoAfterSalesPolicyCatalogService` 只覆盖 SEA 五国（SG/MY/TH/ID/VN）与 `SHIPMENT_DELAY`，不代表任何真实法律
- **售后类型仅 `SHIPMENT_DELAY`**：未实现 `DAMAGED_ITEM` 等其它类型，`REQUEST_MORE_INFO` / `HUMAN_ESCALATION` 路线预留但未启用
- **未接真实生产认证**：审批人身份来自 Gateway 注入的 `X-Authenticated-Operator` Header（Demo 方案）；接入 JWT/统一登录后应改为从认证上下文取 principal
- **Python 模块未纳入 CI**：依赖与测试环境不稳定，需独立修复
- **推荐模块与售后模块的 LLM 共用同一 OpenAI 兼容配置**：模型输出被严格白名单解析，但真实模型的少数输出仍可能触发规则降级（评测中的 Planner Invalid Plan Rate 即来自对抗用例的预期降级）
- **Live LLM Eval 尚未积累长期基线**：需要真实 API key 手动运行（`ECOM_RUN_LIVE_EVAL=true mvn test -Plive-eval`），本仓库不携带任何 Live 评测结果；token/cost 指标当前不可用（服务接口不暴露 usage），不作估算
