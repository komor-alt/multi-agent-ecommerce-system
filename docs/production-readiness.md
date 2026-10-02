# 生产化加固与验收手册

更新日期：2026-09-26。这里区分**已实现机制、可运行验收、仍未验证的生产能力**。

本轮实际结果及失败修复过程见 [验证记录](production-verification-2026-09-25.md)，不把配置中的目标阈值当作测试结论。

新增的 PostgreSQL 与进程崩溃接管验收见 [恢复验证记录](recommendation-recovery-verification-2026-09-26.md)。9 月 25 日的负载结果只属于当时版本，不能直接当作新增租约后的吞吐结论。

不存在一套通用的“互联网大厂上线指标”。SLO 必须结合流量、模型延迟、上游服务能力和业务损失确定。本轮采用现有 Spring Actuator / Micrometer / Prometheus、NestJS JWT / bcrypt / Throttler、PostgreSQL 事务与租约、k6，补关键运行保障，不另造服务框架。

## 1. 本轮具体改变

| 风险 | 落地处理 | 主要代码/验收 |
|---|---|---|
| 原登录为占位，客户端可传审批人 | 配置账号 bcrypt 校验、短期 JWT、HttpOnly Cookie、Origin 校验、ADMIN 审批；operatorId 取认证主体 | `backend/src/modules/auth`、`npm run test:auth` |
| 绕过网关直连 Java | 内部服务 Token；除精确健康探针外默认拒绝；前端取消 Java 旁路 | `security/InternalApiAuthenticationFilter` |
| Agent 等待与队列无任务总预算 | 单任务 deadline、工具 Future 有界等待、HTTP 连接/读取超时、步数与商品数上限；超时关闭状态写入边界 | `RuntimeLimitsProperties`、`RecommendationPipelineState` |
| SSE 拒绝执行时漏还许可 | 创建持久 Run、建立订阅、提交执行；失败归还许可并单独事务收口；并发 close 只归还一次 | `RecommendationController`、`RecommendationRuntimeStore.failRun` |
| 慢客户端影响 Agent、跨实例断流 | 数据库游标分页追读；独立有界发送池；连接/订阅/队列上限；心跳；终态排空再关闭 | `RecommendationRunEventService` |
| Outbox 发布一直占用数据库事务 | 短事务领取 lease → 事务外发送 → claimToken 条件确认；到期重领、退避抖动、死信 | `RecommendationOutboxDispatcher` |
| 旧任务结果覆盖已完成任务 | Run/Task 终态不可回退，产物不可变，归属与来源校验；污染快照失败由独立事务取消活动任务 | `RecommendationRuntimeStore` |
| 推荐执行实例宕机后任务一直 RUNNING | 持久化完整规范化请求，数据库租约与心跳，有限次新代次重放；旧 token/过期 owner 写入被拒绝，终态和结果同事务提交 | `RecommendationRecoveryWorker`、`RecommendationExecutionLeasePostgresTest`、`RecommendationRecoveryProcessTest` |
| 并行结束时读到不一致快照 | 当前 Run 的同一短临界区覆盖任务/产物发布与完整快照；防止“产物已生成但任务尚未关联”的瞬态被持久化；不持锁调用数据库/模型/工具 | `DynamicSubAgentRuntime`、确定性 latch 回归测试 |
| H2 测过但 PostgreSQL TEXT 不兼容 | Runtime 五张表去掉 `@Lob`，统一 TEXT；加入真实 PostgreSQL 迁移/并发契约测试 | `RecommendationPostgresPersistenceTest` |
| Redis 故障后的本机缓存无限增长/并发污染 | Caffeine 按用户容量与 TTL 淘汰；每用户原子替换不可变快照；事件大小限制；Redis Lua 原子追加、裁剪、设置 TTL | `RedisFeatureStoreService`、`FeatureStoreProperties` |
| 只看调用次数，不知道线上是否健康 | 任务结果/延迟、HTTP 错误、并发、SSE、Outbox 积压；健康探针、告警与 k6 验收 | `RuntimeTelemetry`、`deploy/prometheus`、`benchmarks/load` |

保护范围注意：deadline 是协作式中止加 I/O 超时，不是任意 Java 线程的强制终止。`Future.cancel(true)` 不能保证不响应中断的第三方库停止。这里没有 OS 沙箱，也没有把所有售后执行组件改成分布式任务引擎。

## 2. 验收门槛，不等同于已达到

| 维度 | 初始门槛/测试契约 | 使用方式 |
|---|---|---|
| HTTP 可用性 | 99.9% 月度目标；5xx 快速消耗预算告警 | Prometheus；没有真实月度观测前不能对外宣称达到 |
| 任务效果 | 离线任务完成率 ≥99%；返回结果必须满足全部业务硬约束 | 独立于 HTTP 200；模型/数据改变后重跑 |
| 负载 | 首先 5 RPS/1 min 冒烟，再按 10/20/50 RPS 梯度定位拐点 | 固定到达率；不能把少发请求误算成高吞吐 |
| 延迟 | RULES/本地数据初始 P95 <5s；真实模型另定 TTFT/总时延预算 | k6 `P95_MS`；不得把 RULES 结果称为 LLM 性能 |
| 过载 | 常规负载拒绝率 ≤1%；专门的过载实验允许 429，但许可必须回收、恢复后仍可接单 | k6 + 并发许可/拒绝测试；拒绝不计成功 |
| 一致性 | 并发 Outbox 领取无双 owner；过期 owner 不可确认；已终态/产物不可覆写 | H2 默认契约 + PostgreSQL CI |
| 权限 | 未登录、错误 Token、越权审批、跨 Origin 写入均拒绝 | Gateway HTTP 测试 + Java 过滤器测试 |
| 恢复 | SSE 按事件 ID 续读，无错 Run 游标；Outbox 租约可重新领取 | 断连、分页、并发提交与终态竞争测试 |

低工具调用数不能替代任务质量。压测只验证库存、商品唯一性、市场匹配等硬约束，不证明推荐相关性/转化率更高。原有 `RecommendationBusinessSimulationIntegrationTest`、`RecommendationParallelPerformanceTest` 和售后业务仿真继续保留；真实模型评测需锁定模型、提示词、任务集和预算，对照固定工作流/单 Agent 时同时报告成功率与质量，不能只报调用节省。

## 3. 登录和部署配置

### 开发环境

根目录 Compose 明确为本机演示：端口仅绑定 `127.0.0.1`，Gateway 默认 `AUTH_MODE=demo`。这不是生产配置，不能直接改成公网绑定上线。没有改变用户已运行服务或部署到远端。

### 生产启动必须显式提供

- Gateway：`NODE_ENV=production`、`AUTH_MODE=required`、`AUTH_JWT_SECRET`、`JAVA_INTERNAL_SERVICE_TOKEN`，两个密钥均至少 32 字节；从 Secret Manager 注入，不能放浏览器或提交到 Git。
- `AUTH_ALLOWED_ORIGINS`：精确 HTTPS Origin 列表，不允许通配符。
- `AUTH_ACCOUNTS_FILE` 或 `AUTH_ACCOUNTS_JSON`：至少一个启用的 ADMIN；密码是 bcrypt hash（cost 10–16），无默认密码。示例结构：`[{"subject":"ops-admin","username":"admin","passwordHash":"<bcrypt hash>","roles":["ADMIN"]}]`。
- `AUTH_SESSION_TTL_SECONDS` 默认 900；`AUTH_LOGIN_MAX_ATTEMPTS` 默认 10、`AUTH_LOGIN_WINDOW_MS` 默认 60000。登录限流是**单实例**的；多实例应由现有 API Gateway/WAF 做全局限流。`HTTP_TRUST_PROXY` 只填写真实可信的代理地址，不使用无条件信任。
- Java：`SPRING_PROFILES_ACTIVE=production`；显式 PostgreSQL URL/用户/密码。此 profile 禁用 Hibernate 自动变更和启动建表，要求预先审核应用迁移，再以 `ddl-auto=validate` 检查。
- `production` / `prod` profile 都禁止创建 Demo 数据初始化器，避免启动时向正式商品/库存表写入测试数据；开发环境也可用 `ECOM_DEMO_SEED_ENABLED=false` 关闭。生产商品、库存和用户数据需经真实数据接入流程导入。
- `migration-recommendation-runtime-postgresql.sql` 仅覆盖推荐 Runtime 五表。完整业务库还需既有 Prisma/业务表迁移；**不能拿它当完整建库脚本**。
- TLS、证书轮换、入口限流、私网隔离、Secret 轮换、数据库备份由现有基础设施承担。共享服务 Token 不是 mTLS；需要更强隔离时使用已有 Service Mesh / API Gateway。

当前为单租户运营 RBAC，不提供多租户数据行隔离。退出只清 Cookie，窃取的 JWT 在 TTL 内仍可能有效；如有即时撤销需求，应对接企业 OIDC/统一认证平台。账号文件适合小规模管理，不是完整 IAM 系统。

## 4. 运行配置

所有容量先按部署资源测量，不根据线程数凭空推算 QPS。

| 配置 | 默认 | 意义 |
|---|---:|---|
| `ECOM_AGENT_MAX_CONCURRENT_RUNS` | 12 | 单实例在途推荐上限 |
| `ECOM_AGENT_RECOVERY_ENABLED` | true | 只对新建的只读推荐任务启用恢复 |
| `ECOM_AGENT_RECOVERY_LEASE_DURATION` / `ECOM_AGENT_RECOVERY_HEARTBEAT_INTERVAL` | 30s / 5s | 执行租约/独立调度心跳，心跳间隔必须小于半个租约 |
| `ECOM_AGENT_RECOVERY_RENEWAL_TIMEOUT_SECONDS` | 3 | 续租事务超时；不等价于网络层绝对超时 |
| `ECOM_AGENT_RECOVERY_SCAN_INTERVAL` / `ECOM_AGENT_RECOVERY_BATCH_SIZE` | 1s / 16 | 恢复扫描间隔/批量上限，同时受本机并发许可和执行队列限制 |
| `ECOM_AGENT_RECOVERY_MAX_ATTEMPTS` | 3 | 包含首次执行的最大代次数；耗尽后失败收口 |
| `ECOM_AGENT_RECOVERY_WORKER_ID` | 主机名+随机 UUID | 实例身份；真正的排他凭据是每次领取生成的 token+attempt |
| `ECOM_AGENT_RUN_TIMEOUT` | 60s | 推荐 Loop 等待/执行预算 |
| `ECOM_OUTBOUND_CONNECT_TIMEOUT` / `ECOM_OUTBOUND_READ_TIMEOUT` | 3s / 15s | 经 Boot HTTP Builder 创建的出站客户端超时 |
| `ECOM_AGENT_MAX_STEPS` / `ECOM_AGENT_MAX_ITEMS` | 32 / 100 | 客户端不能突破的步数/结果数量上限 |
| `ECOM_AGENT_EVENTS_MAX_SUBSCRIBERS` / `ECOM_AGENT_EVENTS_MAX_PER_RUN` | 256 / 8 | 每实例 SSE 总数/单 Run 订阅上限 |
| `ECOM_AGENT_EVENTS_THREADS` / `ECOM_AGENT_EVENTS_QUEUE_CAPACITY` | 4 / 256 | SSE 发送隔离池 |
| `ECOM_AGENT_EVENTS_PAGE_SIZE` / `ECOM_AGENT_EVENTS_HISTORY_LIMIT` | 100 / 500 | 追读页大小/历史接口页上限 |
| `ECOM_AGENT_EVENTS_POLL_MS` / `ECOM_AGENT_EVENTS_HEARTBEAT_MS` | 250 / 15000 | 追读频率/心跳间隔 |
| `ECOM_AGENT_OUTBOX_LEASE_MS` | 60000 | 一次投递 owner 的租约；须覆盖真实 transport 超时 |
| `ECOM_AGENT_OUTBOX_SCAN_MS` / `ECOM_AGENT_OUTBOX_BATCH_SIZE` | 250 / 200 | 扫描间隔/单次最多投递数量；一条请求会生成多条领域事件 |
| `ECOM_AGENT_OUTBOX_RETRY_DELAY_MS` / `ECOM_AGENT_OUTBOX_RETRY_MAX_DELAY_MS` | 5000 / 300000 | 指数重试起点/上限 |
| `ECOM_AGENT_OUTBOX_JITTER_RATIO` / `ECOM_AGENT_OUTBOX_MAX_ATTEMPTS` | 0.2 / 10 | 打散重试/达到上限进死信 |
| `ECOM_DB_MAX_POOL_SIZE` / `ECOM_SCHEDULER_POOL_SIZE` | 20 / 4 | 数据库连接池/调度线程池 |
| `ECOM_FEATURE_MAX_CACHED_USERS` / `ECOM_FEATURE_MAX_RECENT_EVENTS` | 1000 / 20 | 本机降级缓存最多用户数/每用户事件数 |
| `ECOM_FEATURE_MAX_EVENT_BYTES` | 4096 | 单事件 UTF-8 序列化大小上限 |
| `ECOM_FEATURE_MEMORY_TTL` / `ECOM_FEATURE_REDIS_TTL` | 30m / 24h | 本机缓存/Redis 行为列表过期时间 |

Caffeine 的容量淘汰是维护驱动的有界缓存机制，不是精确的进程 RSS 限额。默认最大事件 payload 的数量级约为 80MB，实际还包含字符串、索引、对象与 Caffeine 开销；调大容量前必须核对 JVM 堆和容器内存预算。

任务 deadline 从开始执行 Loop 计时，工具/专业 Agent 排队计入剩余预算；HTTP 请求在顶层执行队列中等待的时间需计入端到端压测，不能把它当已经覆盖的全链路绝对 deadline。

## 5. 如何测试

### 回归与故障契约

```powershell
cd D:\研究生\AI\multi-agent-ecommerce-system-tau-eval\java
mvn -q test
cd ..\backend
npm ci
npm run prisma:generate
npm run test:auth
npm run typecheck
cd ..\frontend
npm ci
npm run typecheck
npm run build
```

推荐使用隔离 Docker 验收脚本，自动创建随机项目、loopback 端口及临时凭据，并清理本次容器：

```powershell
./deploy/validation/Run-PostgresValidation.ps1
```

也可以使用专用测试库，账号需要创建 schema 的权限。测试仅创建并清理随机 `rec_test_*`、`rec_lease_*`、`rec_process_*` schema，不要指向生产：

```powershell
$env:ECOM_RUN_POSTGRES_TESTS = 'true'
$env:ECOM_POSTGRES_TEST_URL = 'jdbc:postgresql://127.0.0.1:5432/runtime_test'
$env:ECOM_POSTGRES_TEST_USER = '<test user>'
$env:ECOM_POSTGRES_TEST_PASSWORD = '<test password>'
mvn -q '-Dtest=RecommendationPostgresPersistenceTest,RecommendationExecutionLeasePostgresTest,RecommendationRecoveryProcessTest' test
```

CI 的 `.github/workflows/runtime-contract.yml` 配置 PostgreSQL 16 临时服务运行同一契约，另外用真正的 promtool 检查规则和抓取配置。本地 Docker 不可用不等于 PostgreSQL 测试通过。

### 监控

- `/actuator/health/liveness`：仅进程存活状态，不依赖数据库，避免依赖抖动触发重启风暴。
- `/actuator/health/readiness`：应用可接流量状态 + 数据库。Redis 当前可降级，不放入 readiness；如果业务变成强依赖，必须修改 readiness 契约。
- `/actuator/prometheus`：必须携带内部服务 Token（启用鉴权时）；对外不暴露细节。
- `/api/v1/health` 是旧的连通性接口，**不要用它替代 readiness**。
- Prometheus 示例使用 `X-Internal-Service-Token` 的 secret 文件。只挂载 secret，不把密钥写进 YAML。告警模板阈值须随 SLO 调整，正式运行还需接入组织现有 Alertmanager。
- 指标标签限于 scene/outcome/tool/state 等有限集合，不把 userId/runId/error text 放入标签。
- Outbox 是共享数据库 gauge，多实例看 `max`，不能 `sum` 重复计数；指标刷新失败时数据可能过期，单独告警。

### k6：先离线小负载，再真实模型/预发布

在本地隔离服务或经批准的压测环境运行。默认是 Java 内部服务接口，不含 Gateway 登录链路；鉴权链路另由 HTTP 契约覆盖。

```powershell
cd D:\研究生\AI\multi-agent-ecommerce-system-tau-eval
k6 run -e BASE_URL=http://127.0.0.1:8080 -e RATE=5 -e DURATION=1m `
  -e P95_MS=5000 --summary-export=load-summary.json benchmarks/load/recommendation.js
```

鉴权开启时从当前 shell 的安全环境提供 `JAVA_INTERNAL_SERVICE_TOKEN`，不要写入脚本或报告。`RATE`、`DURATION`、`PREALLOCATED_VUS`、`MAX_VUS`、`P95_MS`、`MIN_SUCCESS_RATE`、`MAX_REJECTION_RATE` 均可配置。脚本在三类场景间轮换；阈值失败返回非零退出码。`dropped_iterations=0` 防止负载发生器少发请求掩盖容量不足。结束后还有恢复门禁：`RECOVERY_TIMEOUT_SECONDS`（默认30）内许可全部归还、无活动任务、Outbox 无积压或死信；此断言应在独立压测环境执行，不能和其他流量混跑。

过载实验与常规性能验收分开：逐级加压到 429 出现，记录拐点；降回常规流量后检查可恢复接单、许可无泄漏、没有长期 RUNNING/IN_FLIGHT 积压。真实模型压测必须另设请求预算，不能沿用本地 RULES 的延迟结论。

## 6. 仍然不能宣称的能力

1. 推荐恢复是**重新执行整个只读推荐代次**，不是从任意工具步骤精确续跑。它保存旧 Task/Artifact/Event，新代次使用新任务 ID；不自动复用旧库存结果，也不重放发券等售后副作用。双 JVM 持久化边界强杀测试已通过，但完整 HTTP/Gateway/SSE 链路的进程故障演练仍需补齐。旧版无完整执行配置的 Run 不会被自动接管。
2. Outbox 默认 Local Transport 只在本进程发布；它是至少一次投递，不是跨服务 exactly-once。接 Kafka/RabbitMQ 后消费方仍须用 `dedupKey` 持久去重。
3. 售后真实平台连接器/真实发券凭证核对、未知结果人工对账、库存预占与真实订单一致性，需要实际平台接口和业务规则验收；不能用 Mock 测试代替。
4. 未完成整套多租户隔离、企业 SSO、生产级全局限流、数据库备份恢复演练、事件表保留/归档策略、Java 全依赖漏洞扫描及真实模型质量回归。
5. 未完成真实 PostgreSQL/Redis 多实例持续压测、网络故障注入、完整服务滚动发布、长时间 soak 和备份恢复。因此这里只能说“生产化机制已补强”，不能说“达到大厂容量/任意极端情况都能处理”。

上线顺序：先跑全部离线/权限/真实 PostgreSQL 契约 → 接入测试平台与真实模型的小额预算评测 → 预发布环境容量/故障/恢复演练 → 审核阻断项 → 小流量灰度与回滚演练。每一步保存环境、模型、数据集和原始报告。

参考：[Spring Boot Actuator](https://docs.spring.io/spring-boot/3.4/reference/actuator/endpoints.html)、[Micrometer Prometheus](https://docs.micrometer.io/micrometer/reference/implementations/prometheus.html)、[k6 thresholds](https://grafana.com/docs/k6/latest/using-k6/thresholds/)。
