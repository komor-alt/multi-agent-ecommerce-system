# Java V2 实施与验证状态

基线：a8ea737；V2 代码提交：e7c5aaa；发布收尾版本以 Git 提交记录为准。最初只授权代码与离线测试；2026-10-03 用户授权补跑历史公开基准剩余 8 题，并要求停止新增功能。

## 已落地

| 阶段 | 实现 | 验证 |
|---|---|---|
| Phase 1 | AvailableEvidenceResolver 按依赖给出合法动作；LLM 与规则共用 Gate；记录 allowedActions / selectedAction / rejectionReason | 两种合法取证顺序、跨图/重复/提前结束/非法参数等回归 |
| Phase 2 | ApprovalRecord 保存批准快照，Job 绑定 approvalId；执行前核对实体、金额、币种、证据、政策、方案版本、幂等键与订单状态 | 审批后篡改负例；丢件和破损各自规则重算；退款阻止执行 |
| Phase 3 | AfterSalesConnector 接口；模拟渠道原子幂等账本与结果查询；读超时、瞬时写失败、响应丢失后的重试 | 6 个故障场景，24 调用并发去重；现有父 Run 恢复测试保留 |
| Phase 4 | 48 例真实 Loop 离线评测，固定七查询同任务对照；按尝试统一工具计数；保存逐例轨迹与数据集指纹 | 自动生成任务成功、安全终止、安全性、调用数和失败明细；独立恢复报告 |
| Phase 5 离线部分 | README 主线改为可信售后执行；推荐明确为受限工作流；工作台显示 Guard 原因 | 前端类型检查/构建；τ³ 适配器 120 项离线测试 |

细节与复现命令见 [评测口径](v2-evaluation-contract.md)。

## 本轮测试

Java 全量测试与打包：562 项，540 通过，22 跳过，0 失败、0 错误。跳过的真实 PostgreSQL / Testcontainers 测试需要 Docker；本机 Docker Linux 引擎当前不可连接，不能把这些项计作通过。

前端 typecheck、build 通过；构建仍有大 chunk 提示。V2 代码阶段 τ³ 适配器离线 pytest 为 120 通过；收尾增加结果归档完整性测试，当前数量由公开 Offline CI 报告给出。Java、Frontend、Gateway 与 τ³ adapter 的首轮[公开离线 CI](https://github.com/komor-alt/multi-agent-ecommerce-system/actions/runs/37093357931)全部通过；CI 不配置模型密钥，不执行付费评测。

公开模型评测与上述离线测试分开：2026-10-03 更换凭据后仅补跑 106–113，6 题通过，109、110 两题失败，全部产生有效评分。合并后 Baseline 96/114（84.21%），Guarded 101/114（88.60%），114 对均有效；不替换原先任何有效成功或失败。首次凭据失败记录保留，不新开 10/50-task 或多 seed 实验。逐题来源、历史源码不完整与跨日期模型漂移限制见[公开基准归档](../benchmarks/tau3-retail/published/retail-base-single-trial/README.md)。

## 结果应如何解释

48 例中，Guarded 业务目标达成 40/48，固定全量查询 43/48。Guarded 安全终止 48/48，但不能写成“补偿任务成功率 100%”。8 个业务未达成样例为持续非法模型规划触发人工升级，失败样例仍保留，没有为了提高分数取消安全策略。

工具调用只作为辅助指标。两边的攻击面不同，且模型为脚本化替身，不能据此宣称多 Agent 优于单 Agent，也不能替代真实模型或公开基准。

6 个故障场景通过；其中 3 个可恢复故障最终成功、模拟副作用各一次。这个结论只覆盖测试进程内模拟渠道，不意味着真实支付平台 exactly-once。

运行结果由代码生成：

- [同任务对照](../java/target/after-sales-eval/v2-comparison.md)
- [完整机器结果](../java/target/after-sales-eval/v2-comparison.json)
- [故障恢复结果](../java/target/after-sales-eval/v2-recovery.json)

## 明确未完成 / 未执行

- Java V2 真实模型端到端评测尚未执行。Python τ³ Retail 114 对续跑合并结果和历史 10-task smoke 已完成，但不能冒充 Java 成绩；不再启动新的 50-task 实验。
- 真实 PostgreSQL V2 迁移及多实例复跑：Docker 引擎未运行；迁移文件已提供，未对用户数据库执行。
- 真实业务连接器：未接入，尚无外部持久化幂等、条件写及对账验证。
- 生产性能/真实模型质量：本轮没有测量，不能宣称达到大厂上线指标。
- 幂等范围是同一批准方案；跨新方案的重复赔偿识别不等于现有 Job 幂等。
- WAITING_CUSTOMER / WAITING_EXTERNAL / ESCALATED 已有业务终态；连接器错误目前区分终止与可重试，WAITING_EXTERNAL 类错误仍使用持久化重试队列，没有新增独立外部回调编排。

## 兼容性

V2 保留现有 API 和推荐运行时。旧 ExecutionJob 没有 approvalId 或审批快照时失败关闭，需要人工重审；不会静默补造历史审批。已有 PostgreSQL 请先审阅 migration-after-sales-v2-postgresql.sql。用户已有两个 PDF 未修改，不纳入本轮 V2 提交。
