# Java Agent Benchmark

## Scope

This benchmark evaluates the Java recommendation `AutonomousAgentLoopService` with two independently hashed suites:

- `recommendation-agent-benchmark.jsonl`: the original 66-case regression suite, retained byte-for-byte.
- `recommendation-agent-complex-business.jsonl`: 36 higher-complexity cases, split evenly across homepage, campaign, and retention. It covers multi-turn constraints, conflicts, market/currency/fulfillment rules, inventory changes and re-recall, adversarial tool requests, empty results, tool failures, timeouts, and evidence conflicts. Every completed case requires `minProducts = numItems`; the intentional `completed_empty` case requires zero. Nine representative complex cases are marked for the live PILOT scope.

The PILOT selection is fixed to `complex-homepage-001`, `complex-homepage-004`, `complex-homepage-005`, `complex-campaign-014`, `complex-campaign-017`, `complex-campaign-018`, `complex-retention-025`, `complex-retention-028`, and `complex-retention-030`. It covers all three scenes and the `multi_turn`, `adversarial`, `inventory`, `conflict`, and `failure` categories, including wrong-tool requests, inventory changes, and timeouts.

Regression cases retain their exact tool-path contract. Complex cases instead declare business invariants and required/forbidden capabilities, so different legal tool traces can pass. Evaluator-only constraints, expected outcomes, expected scenes, capability/tool contracts, case IDs, and fault plans are not passed to the Runtime request or model-visible context. Offline scene input is derived from executable task text; Live uses `scene=auto`. Fault injection uses a test-only concurrent registry keyed by run ID and is removed in `finally`. Reports separate `businessTaskSuccessRate`, `safeTaskSuccessRate`, `expectedBehaviorMatchRate`, `completionRate`, and `ruleViolationRate`. Business success depends only on a completed output satisfying RecommendationPlan invariants and minimum product count. Safe success additionally requires no rule violation. A completed case can therefore be a business success while failing safe success or expected-behavior matching. A correctly handled timeout or failure may improve expected-behavior match, but it is never counted as business or safe task success.

An experiment case is invalid only when the trace explicitly reports `llm_budget_exhausted` or `llm_budget_unavailable`. Invalid cases remain in per-case output but are excluded from business, safe, expected-behavior, completion, and rule-violation denominators; reports expose `validCaseCount`, `invalidCaseCount`, and `invalidCaseRate`. Provider timeout/error remains a valid observed failure. All 102 offline cases are valid because RULES mode makes zero provider calls and does not exhaust a live budget.

`conversationTurns` in the complex JSON is a single-request history fixture, not durable cross-run state. `AgentBenchmarkCrossTurnIntegrationTest` separately submits four H2-backed runs. The harness reads the previous persisted Run request, RunView tasks/artifacts, terminal event cursor, and source Run revision (`stateVersion` plus `executionAttempt` metadata); applies explicit latest-turn overrides; and continues after a failed Run. Its Recovery Worker case replays the complete persisted normalized request for an unclaimed Run, rather than resuming an interrupted pipeline from a checkpoint. This is a benchmark/integration harness. The recommendation production API still has no automatic “new request inherits prior run” contract and this project does not claim a memory system.

Dataset, toolset, and fault-plan SHA-256 values are recorded in every report. The fault-plan hash uses case-id sorting and fixed field order. Provider, model, per-case budget, exact matrix budget, max steps, and seed are also pinned. Python tau3 results and the after-sales offline suite remain provenance-only and are never aggregated into Java recommendation metrics.

## Experiment matrix

All supported arms execute the same suites, tools, model/provider configuration, call budget, max steps, seed, and fault plan. The recorded differences are topology, inventory VETO, scene path guard, and parallel scheduling:

| Arm | Topology | VETO | Path guard | Parallel scheduling |
|---|---|---:|---:|---:|
| Single Agent | One dynamic planner/tool loop | on | on | off (topology-inherent) |
| Guarded Multi-Agent | Supervisor plus specialists | on | on | on |
| Multi-Agent, parallel off | Supervisor plus specialists | on | on | off |
| Multi-Agent, VETO off | Supervisor plus specialists | off | on | on |
| Multi-Agent, path guard off | Supervisor plus specialists | on | off | on |
| Multi-Agent, both off | Supervisor plus specialists | off | off | on |

The Single Agent arm is not the fixed `SupervisorOrchestrator` workflow. It uses the same dynamic planner, trusted tool gateway, whitelist, duplicate detection, evidence validation, and final-answer checks, but does not delegate to specialist agents. Path-guard off no longer disables the parallel planner; the planner receives path and parallel switches independently. Evaluation switches are guarded by `ecom.agent.evaluation.enabled=true`; production defaults remain multi-agent with VETO, path guard, and parallel scheduling enabled. Evaluation settings are explicitly captured for asynchronous Specialist tasks and restored in `finally`, so pooled threads return to production defaults after each task.

Faults are injected through a test-only `RecommendationPipelineHook`: tool failure, timeout, empty recall, evidence conflict, and inventory shortage. Fault cases that cannot be injected and judged by the current runtime are rejected by dataset validation.

## Offline result

The following result was generated on 2026-10-31 by executing all 102 cases against H2 in deterministic RULES mode, with lexical fallback instead of pgvector, zero LLM calls, maxSteps 20, and seed 20261030. Cases use one fixed seeded order and the six arms are interleaved within each case. It is an offline engineering comparison, not a live-model quality or production latency claim.

| Arm | Business success | Safe success | Expected match | Completion | Rule violation | Valid/invalid | Avg tools | P50/P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Single Agent | 79.41% | 79.41% | 100.00% | 83.33% | 0.00% | 102 / 0 | 6.12 | 8.7480 / 23.1112 |
| Guarded Multi-Agent | 79.41% | 79.41% | 100.00% | 83.33% | 0.00% | 102 / 0 | 6.30 | 19.1665 / 60.9683 |
| Multi-Agent, parallel off | 79.41% | 79.41% | 100.00% | 83.33% | 0.00% | 102 / 0 | 6.12 | 20.9389 / 60.8179 |
| Multi-Agent, VETO off | 76.47% | 76.47% | 88.24% | 83.33% | 2.94% | 102 / 0 | 5.91 | 18.9659 / 47.5332 |
| Multi-Agent, path guard off | 79.41% | 18.63% | 27.45% | 83.33% | 72.55% | 102 / 0 | 9.82 | 29.7382 / 103.2785 |
| Multi-Agent, both off | 76.47% | 15.69% | 24.51% | 83.33% | 75.49% | 102 / 0 | 9.35 | 28.8499 / 74.5726 |

Latency is a single-machine offline engineering measurement and may vary with the machine and runtime environment; use the generated report under `target/agent-benchmark/` as the source of truth.

The generated report also groups every metric by suite, category, and complexity and includes per-case validity, capability/tool traces, LLM/provider call counts, and mismatch reasons. On the 36-case complex suite, Single, Guarded Multi-Agent, parallel-off, and Path Guard off record 75.00% business success; their safe-success rates are 75.00%, 75.00%, 75.00%, and 52.78%. VETO off and both-off record 66.67% business success and 66.67%/44.44% safe success. These are observed results under the stricter quantity and rule-compliance contracts, not an assertion that one topology must outperform another. Offline provider-call counts are exactly zero; tokens and cost are `null` with explicit reasons because the provider is not invoked.

Reports are generated from execution, not hard-coded:

- Offline runs keep the stable default files `target/agent-benchmark/agent-benchmark-report.json` and `target/agent-benchmark/agent-benchmark-report.md`.
- Live runs never overwrite those files. They write to a stable scope/arm directory such as `target/agent-benchmark/live-smoke-core/`, `target/agent-benchmark/live-pilot-core/`, or `target/agent-benchmark/live-full-full/`, with the same JSON and Markdown filenames inside that directory.

```text
target/agent-benchmark/agent-benchmark-report.json
target/agent-benchmark/agent-benchmark-report.md
```

## Offline verification

Run from `java/`:

```powershell
mvn "-Dtest=AgentBenchmarkFrameworkTest,AgentBenchmarkOfflineRunnerTest,LiveAgentBenchmarkGuardTest,LiveRecommendationE2EGuardTest" test
mvn test
```

Default tests use RULES mode and budgets of zero. They do not call an LLM or embedding provider.

## Authorized LIVE_SMOKE_CORE result (2026-10-31)

The second, authorized live SMOKE used provider/model `deepseek/deepseek-flash`, PostgreSQL and Redis, with live embedding disabled. It ran the three fixed normal cases against the two CORE arms. The configured ceiling was 12 LLM calls per case and 72 total calls (`3 cases * 2 arms * 12`). The observed run made 53 provider calls and consumed 52,396 tokens. Cost is `null` with status `unavailable_price_not_configured`; the framework did not estimate a price.

| Arm | Valid | Business | Safe | Expected match | Completion | Rule violation | Avg/P95 provider calls | Avg tokens | P50/P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Single Agent | 3 / 3 | 100.00% | 100.00% | 100.00% | 100.00% | 0.00% | 9.67 / 11 | 9,096.00 | 35,773.8558 / 39,261.6685 |
| Guarded Multi-Agent | 3 / 3 | 100.00% | 100.00% | 33.33% | 100.00% | 0.00% | 8.00 / 10 | 8,369.33 | 24,920.2076 / 28,801.3405 |

Guarded Multi-Agent produced safe, business-valid results for all three cases, but retention and homepage encountered `empty_rerank_selection` and used the RULES fallback. Those two cases therefore fail the strict provider-only expected-behavior contract even though their final business results remain safe. This distinction is why business/safe success and expected-behavior match are reported separately. Three normal SMOKE cases are not sufficient evidence that Multi-Agent is better than Single Agent.

The valid report is stored under `target/agent-benchmark/live-smoke-core/`. The first attempt, archived under `target/agent-benchmark/live-smoke-core-attempt-1/`, used a 24-call aggregate ceiling and is invalid because calls exhausted the budget and the Live runner fixed the model timeout at 15 seconds. The call budget was raised for the authorized second attempt; LLM-backed Agent outer timeouts now follow the effective client timeout with margin, and the Live runner now leaves timeout selection to `ECOM_RECOMMEND_LLM_TIMEOUT_MS`. The nine-case PILOT has not been authorized or executed.

## Explicit live run

Live execution is excluded from normal tests. It requires both authorization switches, an explicit `SMOKE`, `PILOT`, or `FULL` scope, PostgreSQL, provider credentials, positive bounded per-case calls, and an **exact** aggregate budget:

```text
requiredTotalCalls = caseCount * armCount * maxCallsPerCase
```

Run SMOKE first. It executes three fixed normal regression cases, one per scene. `ECOM_AGENT_BENCHMARK_ARMS` defaults to `CORE`, comparing only Single Agent and Guarded Multi-Agent. The successful authorized run above used a 12-call ceiling, so its exact aggregate ceiling was `3 * 2 * 12 = 72`; the earlier four-call/24-total attempt was invalid and is retained only as failure provenance. PILOT/FULL additionally require `ECOM_AGENT_BENCHMARK_SMOKE_PASSED=true`. Explicit `ECOM_AGENT_BENCHMARK_ARMS=FULL` runs all six arms, including parallel-off. Budget mismatch fails with `LIVE_AGENT_BENCHMARK_BUDGET` before execution. Runtime `llm_budget_exhausted` or `llm_budget_unavailable` cases are marked invalid rather than counted as model business failures. A human must select every later per-case budget and matching aggregate ceiling from observed evidence plus an explicit margin; the framework does not infer prices or budgets.

PILOT provenance is selection-specific: the report contains a manifest reference hashed from the full dataset hash and the exact nine selected case IDs listed above. The loader rejects a PILOT that does not contain exactly nine cases, cover all three scenes, and include every required category. The complete 36-case complex resource remains a source reference but is marked `includedInJavaMetrics=false`; FULL continues to report the complete suite hashes.

```powershell
$env:ECOM_RUN_LIVE_AGENT_BENCHMARK = "true"
$env:ECOM_AGENT_BENCHMARK_SCOPE = "SMOKE"
$env:ECOM_AGENT_BENCHMARK_ARMS = "CORE"
$env:ECOM_RUN_LIVE_RECOMMENDATION_E2E = "true"
$env:ECOM_RECOMMEND_MODE = "LLM"
$env:ECOM_RECOMMEND_LIVE_ENABLED = "true"
$env:ECOM_LLM_API_KEY = "<key>"
$env:ECOM_LLM_MODEL = "<exact-model-id>"
$env:ECOM_RECOMMEND_LLM_PROVIDER = "openai-compatible"
$env:ECOM_RECOMMEND_LLM_TIMEOUT_MS = "30000"
$env:ECOM_RECOMMEND_MAX_LLM_CALLS = "12"
$env:ECOM_SUPERVISOR_MAX_LLM_CALLS = "12"
$env:ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS = "72"
$env:ECOM_AGENT_BENCHMARK_SEED = "20261030"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/ecommerce_agent"
$env:SPRING_DATASOURCE_USERNAME = "<db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<db-password>"
mvn -Plive-agent-benchmark test
```

After a SMOKE report is reviewed and accepted, set `ECOM_AGENT_BENCHMARK_SMOKE_PASSED=true` before changing the scope to `PILOT`. If an authorized PILOT retained the shown 12-call CORE ceiling, its exact aggregate ceiling would be `9 * 2 * 12 = 216`; this is arithmetic guidance, not authorization to execute it. The flag is an explicit operator attestation, and the framework does not silently promote scopes.

The live report records scope and arm mode, provider/model, LLM/provider call counts, RULES fallback detection, actual token/cost availability, P50/P95, tool trace, and failure reason. The evaluator requires provider-backed model planning, no silent RULES fallback, an LLM final business decision for completed outcomes, and persisted run/model/LLM latency and usage/tool/terminal/final-decision trace. Token metrics remain null unless every call returns usage. Cost remains null unless complete usage and explicit input/output USD-per-million-token rates are supplied. The authorized SMOKE above supplies observed provider-call and token data, but no PILOT/FULL result or provider-priced cost claim.
