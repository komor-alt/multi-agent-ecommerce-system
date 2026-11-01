# PostgreSQL 隔离验收

本目录用于真实 PostgreSQL 的测试，不连接项目现有数据库，不启动业务服务或调用模型 API。

## 环境

- Docker Desktop 已启动，Docker Compose v2 可用。
- Java 17、Maven 已加入 PATH。
- 本地有 `pgvector/pgvector:pg16`，或允许 Docker 拉取该镜像。

在仓库根目录执行：

```powershell
./deploy/validation/Run-PostgresValidation.ps1
```

脚本使用随机 Compose 项目名、随机本机端口、随机临时口令；PostgreSQL 16 + pgvector 只监听
`127.0.0.1`。容器数据保存在 tmpfs，不使用业务数据目录或共享持久卷。每个测试类还在
数据库中创建独立的随机 schema，结束后只删除该 schema。

默认验收四组测试：

- `RecommendationPostgresPersistenceTest`：并发抢占、事务回滚不发布、投递不持有数据库事务、Outbox 租约与中文大字段映射。
- `RecommendationExecutionLeasePostgresTest`：执行租约抢占、过期 fencing、恢复请求持久化、终态与事件原子提交。
- `RecommendationRecoveryProcessTest`：真正启动两个独立 JVM，强杀持有租约的 A，让 B 等待数据库租约过期并接管；验证旧任务轨迹/产物保留、未完成任务取消和唯一终态。
- `LiveBenchmarkIsolationPostgresTest`：验证 Benchmark schema advisory lock、持久化 Provider 调用账本、重复实例拒绝，以及异常关闭后 namespaced Run 不可恢复并进入终态。
- `LiveBenchmarkSpringLifecyclePostgresTest`：启动真实 Spring Benchmark Context，使用不联网的 Fake Provider 执行 namespaced Agent Run，并验证异常中止后的 Run、manifest/session、预算拒绝和共享 schema 隔离。
- `LiveBenchmarkPilotPostgresTest`：使用独立 schema 和 Fake Provider 执行完整 9 Case × 2 CORE arms，验证确定性 fixture、库存二次召回、工具超时、持久化预算账本与 abort/recovery 隔离。

双 JVM 测试使用生产 Store/EventService，但任务产物由测试 fixture 构造，并非完整推荐 HTTP 或真实模型链路。
它验证实际进程崩溃后的持久化与接管边界，不代表整服务端到端恢复已全部验收。
Surefire XML 和文本报告位于 `java/target/surefire-reports/`。

可指定测试集和启动等待时间：

```powershell
./deploy/validation/Run-PostgresValidation.ps1 `
  -TestFilter 'RecommendationPostgresPersistenceTest' `
  -StartupTimeoutSeconds 90
```

默认无论测试成功或失败均回收本次新建的 Compose 项目。`-KeepContainer` 会保留测试容器
供进一步检查，脚本会输出随机项目名；停止容器即丢弃临时数据。已有容器和数据库不受影响。
脚本不输出口令，也不向仓库写入 `.env` 或凭据文件。

注意：这些数据库契约测试不能替代真实模型效果评测、整服务进程故障接管测试或生产容量测试。
