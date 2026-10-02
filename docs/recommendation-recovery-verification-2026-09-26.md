# 推荐任务恢复与 PostgreSQL 验证记录

日期：2026-09-26。对象为当前工作区代码及未提交改动，不是基线提交 `aa78d2d` 的原始能力。

## 本轮实现

推荐任务从“仅将轨迹保存到数据库”补齐为“数据库协调的有限次执行恢复”：

1. 接单时保存规范化后的完整 `ToolLoopRequest`，包括请求、工具白名单和步数上限。同步调用在同一事务内创建并领取任务；异步调用先持久化，再由执行线程领取。
2. 每次领取生成独立的 `ownerId + token + attempt + leaseUntil`。Run 行锁串行化领取和写入；PostgreSQL 在取得锁之后读取 `clock_timestamp()`，避免把锁等待前的事务时间误当现在。
3. 实例周期续租；独立心跳调度器不与 Outbox、恢复扫描共用线程。续租数据库 I/O 不持有 Handle 锁，任务关闭不必等待慢查询。续租失败后关闭本次上下文并取消等待中的 Future。
4. 恢复扫描只提交有限数量的候选任务，受已有并发许可、有界执行池及本地排队去重约束。线程实际开始后才领取数据库租约，避免在执行队列等待时耗尽租约。
5. 实例宕机或租约过期后，其他实例重新领取并执行**整个只读推荐代次**。新 Task ID 包含 attempt；旧的未完成任务标为 CANCELLED，旧产物和事件保留，不复用可能过期的库存状态。
6. 每次状态/事件写入都在锁内重新核验 token、owner、attempt 和到期时间。旧实例恢复后不能覆盖新结果；无 token 的旧接口也不能写 recoverable Run。
7. Run 终态、终态事件和 Outbox 在同一数据库事务提交。禁止通过普通事件接口提前发布成功；提交响应丢失时，只有与已提交事件完全一致的重试才被接受。
8. 默认最多 3 次执行（含第一次），耗尽后失败收口。损坏或不再符合只读工具契约的恢复请求也会一次性失败退出队列，不会一直挡住后续任务。

## 验证环境与安全范围

- Windows、Java 17、项目既有 Spring Boot/JPA 实现。
- Docker 中独立 PostgreSQL 16.14；随机本机端口、临时账号口令与隔离 schema，不连接现有业务数据库。
- 推荐 Loop 测试使用 RULES 和零模型预算；没有调用付费模型，没有执行真实发券，没有公网部署。
- 测试包含真实独立 JVM，而不只是两个 Java 线程模拟实例。

## 验证结果

全量 `mvn -B test package`：**535 项全部通过，0 失败、0 错误、0 跳过，打包成功**，总耗时 1 分 18 秒。真实 PostgreSQL 测试包含 9 项 Runtime/Outbox、12 项执行租约、1 项双 JVM 强杀，共 **22 项**，均实际执行。

随后独立运行 `Run-PostgresValidation.ps1`，从创建全新 Compose 项目到执行这 22 项测试、自动删除测试容器和网络，端到端通过；该轮双 JVM 测试耗时 15.84 秒。它是测试用 6 秒租约下的整项测试时间（含两个 JVM 启动），不是生产恢复 RTO。

原始输出保存在 `java/target/recovery-full-validation.log`、`java/target/postgres-isolated-runner.log` 和 `java/target/surefire-reports/`。这些是被忽略的运行产物，正式留档应另存测试制品库。

善后检查：随机测试 schema 为 0，未遗留 fixture 子 JVM；本轮临时容器、其独占匿名测试卷与 runner 网络均已清理。原有三个容器未停止、未修改。临时测试数据已丢弃，真实业务数据未触碰。

关键验证分层，不混淆证据范围：

| 验证 | 实际覆盖 | 不代表什么 |
|---|---|---|
| PostgreSQL Runtime/Outbox 契约 | 真实迁移、并发领取、短事务发布、旧 token、回滚、中文 TEXT | 不证明完整业务库迁移均兼容 PostgreSQL |
| H2/PG 共享执行租约契约 | 排他领取、过期隔离、代次命名、旧轨迹保留、毒化请求、次数上限、终态原子性与精确重试 | 不证明任意业务副作用可安全重放 |
| 双 JVM 强杀接管 | A 提交 Task/Artifact/Event 后被强杀，B 到期接管；旧轨迹保留、未完任务取消、事件连续、唯一终态 | fixture 使用生产 Store/EventService，但没有跑完整 HTTP/Gateway/模型链路 |
| 三场景完整 Spring Loop | 首页、活动、用户召回；真实 H2/JPA 与自动注入，完成结果含 final_answer，代次正确，心跳清零，查询以数据库为准 | 不证明真实模型质量或 PostgreSQL 全服务端到端恢复 |
| 心跳/Worker/上下文单测 | 许可回收、线程启动才领取、排队去重、慢续租可关闭、过期实例不能假发终态 | 不替代跨网络故障注入和长期 soak |

## 可复现命令

在仓库根目录执行：

```powershell
./deploy/validation/Run-PostgresValidation.ps1
```

脚本新建随机 Compose 项目，用 tmpfs 保存一次性 PostgreSQL 数据，运行三组 PostgreSQL 测试，最后回收本次项目。不会采用已有容器或开发数据库。默认测试包含 `RecommendationPostgresPersistenceTest`、`RecommendationExecutionLeasePostgresTest`、`RecommendationRecoveryProcessTest`。

完整 Java 回归可在仅指向专用测试库的 `ECOM_POSTGRES_TEST_*` 环境变量下运行：

```powershell
cd java
mvn -B test package
```

未设置 `ECOM_RUN_POSTGRES_TESTS=true` 时，真实 PostgreSQL 测试会跳过；不能把跳过算作通过。CI 的 `runtime-contract.yml` 已纳入三组真实 PostgreSQL 测试，但本记录不宣称已执行远端 CI。

## 本轮发现并修复的问题

- 默认请求含只读工具 `filter_products`，原默认白名单却漏掉它。新恢复校验暴露了这个不一致，现已统一，保留未知/写入工具的拒绝逻辑。
- Windows Java 17 的参数文件与中文 classpath 导致子 JVM 找不到测试类。改为 manifest-only classpath JAR，使用 ASCII 编码的 file URI；强杀测试已真实执行，不再停留在子进程启动阶段。
- 失租实例若只抛异常，会在本机注册表遗留不可淘汰的 RUNNING 项。现只做本地失败收尾，不写入接管者的数据库状态。
- 慢续租原先持有 Handle 锁，会挡住关闭与超时检查。现将数据库调用移出锁，并显式保留普通调度器和独立心跳调度器，避免自定义 Bean 让默认调度器退让后反而共享。
- 终态与事件分开提交、普通接口提前写终态、提交响应丢失后误通知失败，均增加数据库契约或故障注入单测。

## 部署与剩余边界

- 先审查并应用 `migration-recommendation-runtime-postgresql.sql` 的增量字段与索引，再启用新版本。`production` profile 仍使用 `ddl-auto=validate`，不会自动替正式库迁移。
- 旧 Run 的 `recoverable=false`，缺少完整执行配置时不会凭猜测自动重跑。开发默认 H2 内存库在重启后数据丢失，跨进程恢复需要共享持久数据库。
- 默认参数为 lease 30s、heartbeat 5s、scan 1s、maxAttempts 3，均可配置；恢复时延还受到线程排队、数据库可用性和重新计算耗时影响，**不是 31 秒内必然完成**。
- 任务 timeout 和模型调用预算目前是每代次预算；重放可能重复只读请求并产生额外推理成本，不承诺整个逻辑 Run 恰好执行一次或全代次总成本不变。
- JDBC 续租事务超时不是网络 socket 绝对超时。连续数据库超时可能拖慢串行续租并产生保守接管；应在部署时配置数据库/驱动网络超时并做故障演练。最终写入仍由数据库 fencing 保证。
- 不能强杀任意不响应中断的第三方 Java 调用；上下文关闭、Future 取消和数据库写入隔离是不同层次的保护。
- 本轮不自动重放售后发券/退款等副作用；此类操作仍需连接器幂等键、状态查询与对账。
- 未验收完整 Gateway/HTTP/SSE 进程故障、多实例真实模型负载、网络分区、滚动发布、长期 soak、备份恢复及真实平台连接器。不能据此宣称已经达到生产容量或覆盖所有极端场景。
- 9 月 25 日的 20 RPS/P95 数据属于新增执行租约前的版本，本轮不复用为新版本性能结论。
