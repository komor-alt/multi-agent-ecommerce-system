# τ³ Retail：可核对的逐题结果与收尾状态

更新：2026-10-03。**114 vs 114 有效评分已补齐；这是历史结果与本次续跑合并的逐题配对，不是同日同源码控制实验。**

## 实际结果

| 口径 | Official LLMAgent | Guarded adapter |
|---|---:|---:|
| 有效评分数 / 配对任务数 | 114 / 114 | 114 / 114 |
| 成功数与成功率 | 96 / 114（84.21%） | 101 / 114（88.60%） |
| 实际工具调用总数 / 均值 | 913 / 8.01 | 896 / 7.86 |

共同通过 88 题，共同失败 5 题；仅 Guarded 通过 13 题，仅 Baseline 通过 8 题。5 个任务的净差对应 4.39 个百分点，是单 trial 观察，不是统计显著性或多 Agent 架构优势结论。

历史缺失 task 106–113 均为余额不足导致的零消息、空 reward；2026-10-03 首次试跑 106 也因无效凭据未产生评分。用户更换凭据后，仅补跑这 8 个缺失任务，全部有效评分：106、107、108、111、112、113 通过；109、110 未通过。109 的 DB 检查为 0，NL_ASSERTION 为 1；110 两项均为 0。这两条真实失败原样保留，没有再次运行挑分。原始 Baseline 114 条及 Guarded 106 条有效结果均未修改。

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
3. 续跑与历史运行跨日期；模型别名相同也不能保证服务端权重未更新。本结果称“历史结果续跑合并的逐题配对”，而不是同日同源码控制实验。
4. 官方 evaluator 逻辑保留，但 NL assertion judge 从上游默认 GPT-4.1 配置为 DeepSeek；不能宣称默认 judge 一致。
5. 单 trial，没有多 seed 稳定性验证。既不能证明多 Agent 优于单 Agent，也不能用调用数代替任务质量。
6. 老报告 Baseline 的 5.03 是“含工具调用的消息数”；此处按每个 `tool_calls` 元素计数，114 题均值分别为 8.01 和 7.86。不能据此宣称真实费用、延迟或稳定性有普遍提升。

## 免费核对与补跑规则

在 `benchmarks/tau3-retail`：

```bash
uv run --frozen python scripts/publish_compact.py --verify --output published/retail-base-single-trial
uv run --frozen pytest -m 'not live'
```

Offline CI 对 Java、Frontend、Gateway 和此适配器运行免费测试，不映射模型 Secrets；手动付费 Benchmark workflow 不在 push/PR 中触发。

本次仅运行缺失 106–113，沿用原配置和 seed；无评分尝试保留，未覆盖任何已有 reward（包括 0）。导出器 `--shard` 只接受此前未评分任务的有效结果；检查原始配置、政策、工具、任务定义和 task/trial/seed，一旦不一致立即拒绝。通过 `--attempt` 记录未评分尝试，不把它们当成新任务或有效失败。以下命令只从已有原始结果重新导出到新目录，不调用模型：

```bash
uv run --frozen python scripts/publish_compact.py \
  --historical results/retail114-deepseek-v4-flash-confirmation-fix-v2 \
  --shard env/tau2-bench/data/simulations/guarded-closeout-20261003-resume-106/results.json \
  --shard env/tau2-bench/data/simulations/guarded-closeout-20261003-resume-107-113/results.json \
  --attempt env/tau2-bench/data/simulations/guarded-closeout-20261003-106/results.json \
  --output results/compact-audit-new
```

8 题补跑与归档收尾完成。停止新增功能和付费运行；后续若无新授权，不继续扩展或重跑实验。
