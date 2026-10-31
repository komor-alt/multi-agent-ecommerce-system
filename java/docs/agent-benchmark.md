# Java Agent Benchmark

## Scope

This benchmark evaluates the Java recommendation `AutonomousAgentLoopService` with two independently hashed suites:

- `recommendation-agent-benchmark.jsonl`: the original 66-case regression suite, retained byte-for-byte.
- `recommendation-agent-complex-business.jsonl`: 36 higher-complexity cases, split evenly across homepage, campaign, and retention. It covers multi-turn constraints, conflicts, market/currency/fulfillment rules, inventory changes and re-recall, adversarial tool requests, empty results, tool failures, timeouts, and evidence conflicts. Every completed case requires `minProducts = numItems`; the intentional `completed_empty` case requires zero. Nine representative complex cases are marked for the live PILOT scope.

The PILOT selection is fixed to `complex-homepage-001`, `complex-homepage-004`, `complex-homepage-005`, `complex-campaign-014`, `complex-campaign-017`, `complex-campaign-018`, `complex-retention-025`, `complex-retention-028`, and `complex-retention-030`. It covers all three scenes and the `multi_turn`, `adversarial`, `inventory`, `conflict`, and `failure` categories, including wrong-tool requests, inventory changes, and timeouts.

Regression cases retain their exact tool-path contract. Complex cases instead declare business invariants and required/forbidden capabilities, so different legal tool traces can pass. Evaluator-only constraints, expected outcomes, expected scenes, capability/tool contracts, case IDs, and fault plans are not passed to the Runtime request or model-visible context. Offline scene input is derived from executable task text; Live uses `scene=auto`. Fault injection uses a test-only concurrent registry keyed by run ID and is removed in `finally`. Reports separate `businessTaskSuccessRate`, `expectedBehaviorMatchRate`, `completionRate`, and `ruleViolationRate`. Business success depends only on a completed output satisfying RecommendationPlan invariants and minimum product count; it does not depend on capability-path or live-provenance checks. A completed case can therefore be a business success while failing expected-behavior matching or recording a rule violation. A correctly handled timeout or failure may improve expected-behavior match, but it is never counted as business task success.

Dataset, toolset, and fault-plan SHA-256 values are recorded in every report. The fault-plan hash uses case-id sorting and fixed field order. Provider, model, per-case budget, exact matrix budget, max steps, and seed are also pinned. Python tau3 results and the after-sales offline suite remain provenance-only and are never aggregated into Java recommendation metrics.

## Experiment matrix

All supported arms execute the same suites, tools, model/provider configuration, call budget, max steps, seed, and fault plan. The recorded differences are topology, inventory VETO, scene path guard, and parallel scheduling:

| Arm | Topology | VETO | Path guard | Parallel scheduling |
|---|---|---:|---:|---:|
| Single Agent | One dynamic planner/tool loop | on | on | off (topology-inherent) |
| Guarded Multi-Agent | Supervisor plus specialists | on | on | on |
| Multi-Agent, VETO off | Supervisor plus specialists | off | on | on |
| Multi-Agent, path guard off | Supervisor plus specialists | on | off | on |
| Multi-Agent, both off | Supervisor plus specialists | off | off | on |

The Single Agent arm is not the fixed `SupervisorOrchestrator` workflow. It uses the same dynamic planner, trusted tool gateway, whitelist, duplicate detection, evidence validation, and final-answer checks, but does not delegate to specialist agents. Path-guard off no longer disables the parallel planner; the planner receives path and parallel switches independently. Evaluation switches are guarded by `ecom.agent.evaluation.enabled=true`; production defaults remain multi-agent with VETO, path guard, and parallel scheduling enabled. Evaluation settings are explicitly captured for asynchronous Specialist tasks and restored in `finally`, so pooled threads return to production defaults after each task.

Faults are injected through a test-only `RecommendationPipelineHook`: tool failure, timeout, empty recall, evidence conflict, and inventory shortage. Fault cases that cannot be injected and judged by the current runtime are rejected by dataset validation.

## Offline result

The following result was generated on 2026-10-31 by executing all 102 cases against H2 in deterministic RULES mode, with lexical fallback instead of pgvector, zero LLM calls, maxSteps 20, and seed 20261030. Cases use one fixed seeded order and the five arms are interleaved within each case. It is an offline engineering comparison, not a live-model quality or production latency claim.

| Arm | Business success | Expected match | Completion | Rule violation | Avg/P95 provider calls | Avg tool calls | P50/P95 ms |
|---|---:|---:|---:|---:|---:|---:|---:|
| Single Agent | 79.41% | 100.00% | 83.33% | 0.00% | 0.00 / 0.00 | 6.12 | 8.88 / 28.42 |
| Guarded Multi-Agent | 79.41% | 100.00% | 83.33% | 0.00% | 0.00 / 0.00 | 6.30 | 19.34 / 60.90 |
| Multi-Agent, VETO off | 76.47% | 88.24% | 83.33% | 2.94% | 0.00 / 0.00 | 5.91 | 19.61 / 58.73 |
| Multi-Agent, path guard off | 79.41% | 27.45% | 83.33% | 72.55% | 0.00 / 0.00 | 9.82 | 29.94 / 94.54 |
| Multi-Agent, both off | 76.47% | 24.51% | 83.33% | 75.49% | 0.00 / 0.00 | 9.35 | 30.15 / 99.59 |

Latency is a single-machine offline engineering measurement and may vary with the machine and runtime environment; use the generated report under `target/agent-benchmark/` as the source of truth.

The generated report also groups every metric by suite, category, and complexity and includes per-case capability/tool traces, LLM/provider call counts, and mismatch reasons. On the 36-case complex suite, Single, Guarded Multi-Agent, and Path Guard off record 75.00% business success. VETO off and both-off record 66.67% because three inventory-change cases complete with fewer products than requested after re-recall. Expected-behavior match is 100.00% for Single and Guarded Multi-Agent, 91.67% with VETO off, 77.78% with Path Guard off, and 69.44% with both off. Rule-violation rates are respectively 0.00%, 0.00%, 8.33%, 22.22%, and 30.56%. These are observed results under the stricter quantity contract, not an assertion that one topology must outperform another. Offline provider-call counts are exactly zero; tokens and cost are `null` with explicit reasons because the provider is not invoked.

Reports are generated from execution, not hard-coded:

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

## Explicit live run

Live execution is excluded from normal tests. It requires both authorization switches, an explicit `PILOT` or `FULL` scope, PostgreSQL, provider credentials, positive bounded per-case calls, and an **exact** aggregate budget:

```text
requiredTotalCalls = caseCount * armCount * maxCallsPerCase
```

Run PILOT first. It executes exactly the nine marked complex cases. `ECOM_AGENT_BENCHMARK_ARMS` defaults to `CORE`, which compares only Single Agent and Guarded Multi-Agent; with a four-call ceiling the exact static preflight budget is `9 * 2 * 4 = 72`. Set the arm mode explicitly to `FULL` to run all five ablation arms; the corresponding PILOT budget is `9 * 5 * 4 = 180`. The PILOT report records each case's `llmCallCount` and `providerCallCount`, plus `averageProviderCalls` and `p95ProviderCalls`. These static ceilings are configuration guards, not observed usage. After an authorized PILOT, a human must review the actual provider-call distribution, choose a reasonable margin above observed `p95ProviderCalls`, and explicitly set the FULL-scope per-case and aggregate budgets. The framework does not infer a budget or provider price. FULL scope executes all 102 cases and uses the same selected arm mode. The runner fails closed for any mismatched case, arm, or aggregate budget; it never samples cases or shares a budget between arms. Cases use one seeded fixed order, and arms are interleaved within each case with a seeded arm order to reduce provider time-drift bias.

PILOT provenance is selection-specific: the report contains a manifest reference hashed from the full dataset hash and the exact nine selected case IDs listed above. The loader rejects a PILOT that does not contain exactly nine cases, cover all three scenes, and include every required category. The complete 36-case complex resource remains a source reference but is marked `includedInJavaMetrics=false`; FULL continues to report the complete suite hashes.

```powershell
$env:ECOM_RUN_LIVE_AGENT_BENCHMARK = "true"
$env:ECOM_AGENT_BENCHMARK_SCOPE = "PILOT"
$env:ECOM_AGENT_BENCHMARK_ARMS = "CORE"
$env:ECOM_RUN_LIVE_RECOMMENDATION_E2E = "true"
$env:ECOM_RECOMMEND_MODE = "LLM"
$env:ECOM_RECOMMEND_LIVE_ENABLED = "true"
$env:ECOM_LLM_API_KEY = "<key>"
$env:ECOM_LLM_MODEL = "<exact-model-id>"
$env:ECOM_RECOMMEND_LLM_PROVIDER = "openai-compatible"
$env:ECOM_RECOMMEND_MAX_LLM_CALLS = "4"
$env:ECOM_SUPERVISOR_MAX_LLM_CALLS = "4"
$env:ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS = "72"
$env:ECOM_AGENT_BENCHMARK_SEED = "20261030"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/ecommerce_agent"
$env:SPRING_DATASOURCE_USERNAME = "<db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<db-password>"
mvn -Plive-agent-benchmark test
```

The live report records scope and arm mode, provider/model, LLM/provider call counts, RULES fallback detection, actual token/cost availability, P50/P95, tool trace, and failure reason. The evaluator requires provider-backed model planning, no silent RULES fallback, an LLM final business decision for completed outcomes, and persisted run/model/LLM latency and usage/tool/terminal/final-decision trace. Token metrics remain null unless every call returns usage. Cost remains null unless complete usage and explicit input/output USD-per-million-token rates are supplied. No paid live benchmark was run in this change, so no observed provider-call distribution or recommended FULL budget is claimed.
