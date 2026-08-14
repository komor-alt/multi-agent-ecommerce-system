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
cd java && mvn test          # 187 tests：单元 + 集成 + 离线 Agent 评测
```

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

## CI

`.github/workflows/ci.yml`，三个独立 job：

- **java**：`mvn test`（含离线评测），上传 `java/target/after-sales-eval/` 报告为 artifact
- **gateway**：Node 20 + `npm ci` + `prisma generate` + `typecheck` + `build`
- **frontend**：Node 20 + `npm ci` + `typecheck` + `build`

没有 Python job：Python 模块的历史依赖锁定与测试环境不稳定，不做 CI 是为了不把不稳定的失败强加到主线上；这是一个已知问题（见 Limitations），应作为独立任务修复依赖与测试后再加入。

CI 全部离线运行：不调用任何外部 LLM API、不需要 API key、不产生费用（无 key 时 AUTO 模式走规则，LLM 相关测试使用注入的模型输出替身）。

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
