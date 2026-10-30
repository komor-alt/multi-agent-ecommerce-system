# Java Agent Benchmark

## Scope

This benchmark evaluates the Java recommendation `AutonomousAgentLoopService`. The dataset contains 66 executable cases in `src/test/resources/recommendation-agent-benchmark.jsonl`: 30 normal, 15 constraint, 9 inventory re-recall, and 12 controlled failure cases across homepage, campaign, and retention scenes.

Every case declares `expectedOutcome`, optional `expectedFailureReason`, required and forbidden tools, constraints, and an injectable fault plan. `taskSuccessRate` means **expected-behavior match rate**. A controlled timeout or failure is a success only when the observed outcome, reason, tool path, and constraints match that case contract. It does not mean that every run produced products. `completionRate` independently reports product-producing or intentionally empty completed runs.

Dataset, toolset, and fault-plan SHA-256 values are recorded in every report. The fault-plan hash uses case-id sorting and fixed field order. Provider, model, per-case budget, exact matrix budget, max steps, and seed are also pinned. Python tau3 results and the after-sales offline suite remain provenance-only and are never aggregated into Java recommendation metrics.

## Experiment matrix

All supported arms execute the same dataset, tools, model/provider configuration, call budget, max steps, seed, and fault plan. The only differences are topology, inventory VETO, and scene path guard:

| Arm | Topology | VETO | Path guard |
|---|---|---:|---:|
| Single Agent | One dynamic planner/tool loop | on | on |
| Guarded Multi-Agent | Supervisor plus specialists | on | on |
| Multi-Agent, VETO off | Supervisor plus specialists | off | on |
| Multi-Agent, path guard off | Supervisor plus specialists | on | off |
| Multi-Agent, both off | Supervisor plus specialists | off | off |

The Single Agent arm is not the fixed `SupervisorOrchestrator` workflow. It uses the same dynamic planner, trusted tool gateway, whitelist, duplicate detection, evidence validation, and final-answer checks, but does not delegate to specialist agents. Evaluation switches are guarded by `ecom.agent.evaluation.enabled=true`; production defaults remain multi-agent with VETO and path guard enabled.

Faults are injected through a test-only `RecommendationPipelineHook`: tool failure, timeout, empty recall, evidence conflict, and inventory shortage. Fault cases that cannot be injected and judged by the current runtime are rejected by dataset validation.

## Offline result

The following result was generated on 2026-10-30 with H2, deterministic RULES mode, lexical fallback instead of pgvector, zero LLM calls, maxSteps 20, and seed 20261030. It is an offline engineering comparison, not a live-model quality or production latency claim.

| Arm | Expected-behavior match | Completion | Rule violation | Avg tool calls | P50/P95 ms |
|---|---:|---:|---:|---:|---:|
| Single Agent | 100.00% | 86.36% | 0.00% | 6.27 | 13.11 / 55.48 |
| Guarded Multi-Agent | 100.00% | 86.36% | 0.00% | 6.47 | 31.12 / 97.24 |
| Multi-Agent, VETO off | 86.36% | 86.36% | 0.00% | 6.02 | 30.43 / 86.26 |
| Multi-Agent, path guard off | 1.52% | 86.36% | 98.48% | 9.23 | 42.77 / 147.53 |
| Multi-Agent, both off | 1.52% | 86.36% | 98.48% | 8.82 | 40.60 / 153.94 |

Latency is a single-machine offline engineering measurement and may vary with the machine and runtime environment; use the generated report under `target/agent-benchmark/` as the source of truth.

VETO-off misses the required second recall in all 9 inventory-shortage cases. Path-guard-off arms execute forbidden scene tools in 65 of 66 cases. The 86.36% completion rate is expected: 9 cases require controlled failure/timeout, while 3 empty-result cases complete without products. Tokens and cost are `null` with explicit reasons because the offline provider is not invoked.

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

Live execution is excluded from normal tests. It requires both authorization switches, PostgreSQL, provider credentials, positive bounded per-case calls, and an **exact** aggregate budget:

```text
requiredTotalCalls = caseCount * armCount * maxCallsPerCase
```

With 66 cases, 5 arms, and 4 calls per case, `ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS` must equal `1320`. The runner fails closed for any other value; it never samples cases or shares a budget between arms. Cases use one seeded fixed order, and arms are interleaved within each case with a seeded arm order to reduce provider time-drift bias.

```powershell
$env:ECOM_RUN_LIVE_AGENT_BENCHMARK = "true"
$env:ECOM_RUN_LIVE_RECOMMENDATION_E2E = "true"
$env:ECOM_RECOMMEND_MODE = "LLM"
$env:ECOM_RECOMMEND_LIVE_ENABLED = "true"
$env:ECOM_LLM_API_KEY = "<key>"
$env:ECOM_LLM_MODEL = "<exact-model-id>"
$env:ECOM_RECOMMEND_LLM_PROVIDER = "openai-compatible"
$env:ECOM_RECOMMEND_MAX_LLM_CALLS = "4"
$env:ECOM_SUPERVISOR_MAX_LLM_CALLS = "4"
$env:ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS = "1320"
$env:ECOM_AGENT_BENCHMARK_SEED = "20261030"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/ecommerce_agent"
$env:SPRING_DATASOURCE_USERNAME = "<db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<db-password>"
mvn -Plive-agent-benchmark test
```

The live evaluator requires provider-backed planning, no silent RULES fallback, an LLM final business decision, and persisted run/model/tool/final-decision trace. Token metrics remain null unless every call returns usage. Cost remains null unless complete usage and explicit input/output USD-per-million-token rates are supplied. No paid live benchmark was run in this change.
