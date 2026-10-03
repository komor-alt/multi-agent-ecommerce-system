# τ³ Retail：可核对的逐题结果与收尾状态

更新：2026-10-03。**当前只有 106 对有效评分，未完成 114 vs 114。**

## 实际结果

| 口径 | Official LLMAgent | Guarded adapter |
|---|---:|---:|
| 全部任务有效评分数 | 114 / 114 | 106 / 114 |
| 全部有效评分中的成功数 | 96 / 114（84.21%） | 95 / 106（89.62%） |
| 双方共同有效的 106 题 | 89 / 106（83.96%） | 95 / 106（89.62%） |

共同通过 83 题，共同失败 5 题；仅 Guarded 通过 12 题，仅 Baseline 通过 6 题。6 个任务的净差是单次观察，不是统计显著性结论。不能用不同分母的 84.21% 与 89.62% 直接宣称提升。

缺失 task 106–113：历史运行返回余额不足，均为零消息、空 reward 的基础设施错误。2026-10-03 仅试跑 106，服务返回 API Key 无效，仍为零消息、空 reward。官方 runner 自带错误重试也未成功；随后停止其余 7 题。无有效失败被删掉或重新挑选。

## 入库文件

- [cases.jsonl](cases.jsonl)：114 个 task/trial/seed 键，双方逐题 reward、reward breakdown、终止原因、实际工具调用数、时间、原始仿真 ID 与 SHA-256。
- [summary.json](summary.json)：从逐题记录计算的总数、分母、通过率和四格配对结果。
- [metadata.json](metadata.json)：官方 commit、模型配置、种子、评测器配置、原始文件哈希、每个来源的 runner HEAD、当前适配器指纹、无评分补跑记录与限制。

不提交 API Key、原始对话、用户/工具参数或大体积官方数据集。完整原始结果保留在本地 ignored results 目录；哈希支持核对保留文件，但不等于公开完整轨迹审计。任何人可免费重算此处汇总；重新执行官方仿真则需要自己的模型密钥与费用。

## 可复现配置与边界

官方仓库 `sierra-research/tau2-bench`，commit `79975ac5741e23fbb1d2ac44262d62398a6d87bd`；Retail base split；114 tasks × 1 trial；run seed 300（trial seed 626729）；maxSteps 200、maxErrors 10；agent/user/NL assertion judge 都使用 `openai/deepseek-v4-flash`，temperature 0、thinking disabled。配置见 [deepseek-flash-full.yaml](../../configs/deepseek-flash-full.yaml)。

必须同时披露：

1. 这是 Python τ³ adapter，不是 Java 服务端到端结果，也不是官方排行榜提交。
2. 历史运行只保存 runner HEAD，未保存当时未提交源码的完整指纹；无法严格证明与当前适配器源码相同。此次指纹不能倒推为历史指纹。
3. 续跑与历史运行跨日期；模型别名相同也不能保证服务端权重未更新。即使补齐，也应称“历史结果续跑合并的逐题配对”，而不是同日同源码控制实验。
4. 官方 evaluator 逻辑保留，但 NL assertion judge 从上游默认 GPT-4.1 配置为 DeepSeek；不能宣称默认 judge 一致。
5. 单 trial，没有多 seed 稳定性验证。既不能证明多 Agent 优于单 Agent，也不能用调用数代替任务质量。
6. 老报告 Baseline 的 5.03 是“含工具调用的消息数”；此处按每个 `tool_calls` 元素计数，实际为 8.01，Guarded 为 7.84。两者分母不同，不据此声称效率提升。

## 免费核对与补跑规则

在 `benchmarks/tau3-retail`：

```bash
uv run --frozen python scripts/publish_compact.py --verify --output published/retail-base-single-trial
uv run --frozen pytest -m 'not live'
```

Offline CI 对 Java、Frontend、Gateway 和此适配器运行免费测试，不映射模型 Secrets；手动付费 Benchmark workflow 不在 push/PR 中触发。

凭据修复后只运行缺失 106–113，沿用原配置和 seed；保留本次无评分尝试，不能覆盖任何已有 reward（包括 0）。导出器 `--shard` 只接受此前未评分任务的有效结果；检查原始配置、政策、工具、任务定义和 task/trial/seed，一旦不一致立即拒绝。通过 `--attempt` 记录未评分尝试，不把它们当成新任务或有效失败。向新的输出目录导出，审核后替换发布快照；禁止直接修改 JSON 数字。

```bash
uv run --frozen python scripts/publish_compact.py \
  --historical results/retail114-deepseek-v4-flash-confirmation-fix-v2 \
  --attempt env/tau2-bench/data/simulations/guarded-closeout-20261003-106/results.json \
  --output results/compact-audit-new
# 恢复成功后，对每个完整补跑结果增加 --shard <official-results.json>
```

当前停止新增功能；剩余工作只是在有效密钥可用后补齐并更新本归档。
