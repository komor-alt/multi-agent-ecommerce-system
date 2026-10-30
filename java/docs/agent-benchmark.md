# Java Agent Benchmark

## Scope and execution identity

This benchmark evaluates the Java recommendation mainline. Its six synthetic cases live in `src/test/resources/recommendation-agent-benchmark.jsonl`; normalized-LF SHA-256 is pinned into every report so Windows/Linux checkout line endings do not change the dataset identity. The toolset hash is derived from the sorted `ScenePathEnforcer.DEFAULT_WHITELIST`. Provider, model, per-case call budget, total worst-case budget, and seed are recorded per variant.

The only currently executable live arm is `java-guarded-multi-agent-veto-on-path-on`, which invokes the existing `AutonomousAgentLoopService`, existing specialist/runtime/tool path, and existing PostgreSQL run/event persistence. It is classified `LIVE_LLM` only when its trace proves provider invocation. A case is unsuccessful if the run does not persist/complete, the requested scene or market differs, no final LLM business decision exists, the response has no products, a guard/market violation is observed, or any LLM stage silently falls back.

The registry deliberately marks the following unsupported rather than approximating them:

| Variant | Status | Reason |
|---|---|---|
| Single Agent | `UNSUPPORTED` | `ConstrainedToolLoopService` is a fixed after-sales sequence, not a live dynamic Single Agent executor. |
| Multi-Agent without guard constraints | `UNSUPPORTED` | `SupervisorOrchestrator` is a Fixed Workflow baseline, not a live unguarded dynamic Multi-Agent executor; the current `AutonomousAgentLoopService` is already guarded. |
| VETO disabled | `UNSUPPORTED` | The production runtime has no independent VETO switch. |
| Scene-path constraint disabled | `UNSUPPORTED` | The production runtime has no independent path-constraint switch. |

Therefore this release provides a reproducible framework and a real-run entry point, but cannot yet produce a fair ablation comparison or causal claim about VETO/path constraints. Unsupported arms appear in the report as `NOT_EXECUTED`, with null metrics.

## Dataset provenance

- Java live recommendation cases: included only in Java `LIVE_LLM` aggregates.
- Existing `after-sales-eval.jsonl`: recorded as `OFFLINE_SIMULATION` provenance only. The harness injects scripted model outputs and is not a live Java recommendation result.
- `benchmarks/tau3-retail/published/.../summary.json` and `metadata.json`: recorded as `EXTERNAL_REFERENCE` with hashes, never parsed into or aggregated with Java metrics. These results belong to the Python τ³ adapter.

## Metrics

The report includes task success rate, rule violation rate, average tool calls, nearest-rank P50/P95 end-to-end latency, total/average tokens, and cost per task. Token fields are null unless every provider call for every executed case returned usage. Cost is null unless actual prompt/completion usage is complete and both explicit input/output USD-per-million-token prices are supplied. No token or cost estimate is substituted.

Each case records completion, success, violation, tool count, latency, `providerInvoked`, usage status, tokens, cost, and a sanitized failure reason. Reports include aggregate failure counts and all dataset/variant provenance in `target/agent-benchmark/`.

## Offline checks

Run from `java/`:

```powershell
mvn "-Dtest=AgentBenchmarkFrameworkTest,LiveAgentBenchmarkGuardTest,LiveRecommendationE2EGuardTest" test
mvn test
```

These commands do not start the live benchmark profile or call a model.

## Explicit live run

The live profile is separate from normal CI and requires two explicit run switches, real model configuration, PostgreSQL, a non-zero bounded call budget, fixed seed, and an aggregate worst-case budget covering every case. The runner also verifies PostgreSQL with `SELECT 1` before it starts the Spring application.

PowerShell example (review the limits and potential provider charges before setting these values):

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
$env:ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS = "24"
$env:ECOM_AGENT_BENCHMARK_SEED = "20261030"
$env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5432/ecommerce_agent"
$env:SPRING_DATASOURCE_USERNAME = "<db-user>"
$env:SPRING_DATASOURCE_PASSWORD = "<db-password>"
mvn -Plive-agent-benchmark test
```

The worst-case ceiling is at most 96 provider calls; the configured aggregate ceiling must be at least `caseCount * callsPerCase`. Optional cost rates must be supplied together as `ECOM_AGENT_BENCHMARK_INPUT_USD_PER_1M` and `ECOM_AGENT_BENCHMARK_OUTPUT_USD_PER_1M`, using the provider's applicable USD rates. Live execution writes `agent-benchmark-report.json` and `.md` under `target/agent-benchmark/`. This repository change does not execute the live command.

For fair future ablations, each runnable arm must share the same provider/model, dataset hash, toolset hash, per-case and total budget, and seed. A matrix with only one runnable arm is reported as non-comparable.
