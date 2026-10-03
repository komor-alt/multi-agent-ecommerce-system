# V2 离线评测与执行边界

## 范围

本文件规定 Java V2 离线评测，不调用真实发券或支付接口。初始代码阶段按用户要求不运行付费评测；2026-10-03 收尾另获授权，仅补跑历史 τ³ Retail 缺失 106–113。首次尝试因无效凭据没有评分；更换凭据后 8 题全部评分，6 通过、2 失败，现已获得 114 对有效结果。统一状态及跨日期/源码限制以[公开归档](../benchmarks/tau3-retail/published/retail-base-single-trial/README.md)为准。Python 基准适配器不等于 Java 业务服务，不能把其成绩直接写成 Java 端到端成绩。

Java 主线是售后可信执行；推荐是场景化受限工作流。Python / Go 旧实现保留用于历史参考，本轮不扩展这些服务。

## 复现

在项目的 java 目录运行：

```powershell
mvn -B test package
mvn -B "-Dtest=AfterSalesEvalHarnessTest,ExecutionFailureRecoveryTest,AfterSalesBusinessSimulationIntegrationTest" "-Dv2.revision=<实际提交或工作树标识>" test
```

在 frontend 目录运行：

```powershell
npm run typecheck
npm run build
```

在 benchmarks/tau3-retail 目录运行：

```powershell
.\.venv\Scripts\python.exe -m pytest -q src/ecommerce_tau3/tests
```

这些命令不启用模型联网。离线 harness 的正调用预算只供覆写 callModel 的脚本化替身使用；生产默认 live=false / RULES / 零预算不变。真实 PostgreSQL 测试需要可用 Docker；跳过不能当作通过。

## 数据集与对照

- 主 JSONL 为 48 例：原 40 例加 8 例丢件、破损、待客户、待外部、人工升级和绕审批攻击。
- 额外 6 个执行故障场景，由真实 ApprovalService、ApprovalPolicyGate、ExecutionApprovalValidator、ExecutionService 和模拟渠道执行；仓储是测试替身。
- 审批后修改金额、币种、订单、用户、Run、政策、证据、版本或撤销审批有独立负向单测。
- 现有恢复测试验证从 parentRunId 恢复证据，不重复调用已经完成的取证工具。
- Guarded 使用实际 Agent Loop，不是单独重新实现的评测代理。
- Baseline 复用 FixedWorkflowBaseline：总是尝试七个查询工具，再按同样的政策与规则决策。两边的订单、消息、附件与业务日期相同。
- Baseline 没有 Evidence Planner，因此恶意 Planner 输出没有等价攻击面。聚合结果不能证明 Guarded 或多 Agent 在模型能力上优于固定流程，更不能作为强单 Agent 对照。
- 两边工具次数均按尝试计数，包含失败调用；处理步骤统一定义为 intake + 工具尝试 + terminal decision，不是神经网络推理步数。

修正了两项旧评测问题：模型替身预算为零导致注入输出没有实际执行；未标注意图的样例被计入准确率分子、没有计入分母。另有两个旧 oracle 与现有业务规则矛盾：高金额订单应转人工，政策未覆盖应安全升级而非异常失败。修正依据是既有业务规则，不是为提高分数改变系统。持续非法规划导致业务目标未完成的 8 个样例仍保留为失败。

## 指标口径

| 指标 | 口径 |
|---|---|
| taskSuccessRate | 达到标注的方案/不操作/等待/升级结果，金额、币种、政策正确，且无越权或错误实体 |
| completionRate | 进入可解释的安全终态；转人工可以计入，不能等同于完成补偿 |
| policyViolationRate | 观察到金额不符、模型参数被接受、越权执行或禁止证据被收集的样例占比 |
| wrongEntityRate | 实际工具参数中的 orderId 偏离当前工单订单的样例占比 |
| confirmationViolationRate | 无人工审批的规划评测中出现已批准方案或 ExecutionJob 的样例占比 |
| invalidToolCallRate | 实际 tool_started 中非白名单动作 / 所有工具尝试；被拒绝的计划另计 |
| unsafePlanAttemptRate | 被依赖 Gate 或模型输出结构校验阻止的样例占比；不等于实际违规率 |
| recoverySuccessRate | 独立故障报告中 3 个可恢复故障最终成功且仅产生一次模拟副作用的比例 |
| 工具、步骤、延迟 | 效率辅助指标；内存替身延迟不是生产延迟 |
| LLM 调用数 | 真实经过预算计数的脚本化模型调用次数，不是付费调用数 |
| tokens / cost | 无 provider usage 时为 null，不伪造零成本模型成绩 |

生成物位于 java/target/after-sales-eval/：

- v2-comparison.json / v2-comparison.md：同任务双边结果、口径、失败清单。
- trajectories/*.json：48 条实际执行的结构化事件链，不保存 Prompt 或模型思维链。
- v2-recovery.json：6 个故障的首次状态、副作用数、最终状态、尝试次数。
- after-sales-eval-report.json / .md：兼容旧评测输出。

主报告不把“规划阶段未创建执行 Job”解释成真实退款安全已验证。执行幂等和审批绑定由独立故障测试验证，实际外部渠道尚未接入。

## 数据库升级与旧任务

已有 PostgreSQL 数据库部署前应审阅并执行：

java/src/main/resources/migration-after-sales-v2-postgresql.sql

新增审批快照保存 Run、订单、用户、动作、金额、币种、政策/方案版本、付款金额和证据；Job 保存 approvalId。无快照的旧 Job 在执行边界失败关闭，需要人工重新审核，不会根据当前可变方案自动补造历史批准记录。迁移未在本轮真实 PostgreSQL 上执行。

## 幂等与竞争边界

幂等键延续 SHA-256(proposalId + actionType + proposalVersion)，持久化到 ExecutionJob，重试不生成新键。执行前还校验键未被修改。幂等保证针对同一已批准业务命令；不同新方案不自动视为同一订单的重复赔偿。

连接器协议要求同键不同参数拒绝、同键同参数复用结果。模拟渠道使用 ConcurrentHashMap.compute 原子提交，并可查询原结果；这是进程内仿真账本，重启后不会保存。响应丢失后先核对是否已有结果，再检查是否允许产生新的副作用，因此已成功后才退款的订单仍能恢复成功记录。

真实渠道上线还需要持久化幂等账本/渠道原生幂等、订单版本条件执行、未知结果对账。Java 执行前读取订单与远端提交之间仍有时间窗口，不能仅凭一次预检查宣称消除所有竞态。数据库租约避免重复领取，不能替代外部渠道幂等。

## 已知取舍与待验证项

连续非法规划按既有策略升级人工，所以本轮业务成功率不等于安全终止率，不能只引用工具调用下降。后续是否修改升级策略需要同时比较任务收益与安全风险。

公开 τ³ adapter 已补齐 114 对有效评分；尚未完成的是 Java V2 真实模型端到端质量评测、真实 PostgreSQL 迁移复跑（本机 Docker 引擎不可用）、真实渠道端到端验证和生产压测。Python adapter 的公开结果不能替代这些验证。当前停止新增功能和扩大付费实验。
