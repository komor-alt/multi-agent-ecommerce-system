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
| Single Agent | 79.41% | 79.41% | 100.00% | 83.33% | 0.00% | 102 / 0 | 6.12 | 9.0215 / 28.9985 |
| Guarded Multi-Agent | 79.41% | 79.41% | 100.00% | 83.33% | 0.00% | 102 / 0 | 6.30 | 20.8953 / 63.2601 |
| Multi-Agent, parallel off | 79.41% | 79.41% | 100.00% | 83.33% | 0.00% | 102 / 0 | 6.12 | 23.6890 / 68.4384 |
| Multi-Agent, VETO off | 76.47% | 76.47% | 88.24% | 83.33% | 2.94% | 102 / 0 | 5.91 | 19.7981 / 53.7552 |
| Multi-Agent, path guard off | 79.41% | 18.63% | 27.45% | 83.33% | 72.55% | 102 / 0 | 9.82 | 32.3005 / 120.0183 |
| Multi-Agent, both off | 76.47% | 15.69% | 24.51% | 83.33% | 75.49% | 102 / 0 | 9.35 | 31.3648 / 91.3129 |

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

The persisted reason means the provider response was successfully parsed as an empty selection while market-filtered candidates were available for the deterministic fallback. The old prompt said only "max N" and therefore did not explicitly prohibit zero selections; the old parser also accepted only a top-level string array. The production reranker now requires one or more IDs, accepts a top-level array or one of four explicit object fields, trims IDs and checks every value against the exact candidate whitelist. Only empty or malformed output is eligible for one retry. The retry uses a distinct auditable fingerprint but the same `LlmCallBudget`; no remaining budget means no retry. Illegal IDs, provider errors and timeouts do not retry. A final safe fallback records the first/final reason, retry count, candidate count, invalid-ID count and selected count in the Agent result and rerank Tool Observation without storing prompts, raw output, user IDs or product IDs in the diagnostic block. The durable Observation Trace carries only those whitelisted diagnostics. The evaluator treats a failed first attempt as recovered only when every failed call is a provider-invoked `product_rerank` empty/format error and the final diagnostic is `llm_retry_success` with `fallbackUsed=false`; every other failure remains a fallback. This repair is covered only by offline tests until another live run is separately authorized.

First-stage offline verification also found that the old base rerank fingerprint did not distinguish a changed candidate set after inventory VETO and re-recall. The base fingerprint now includes only a SHA-256 digest of the ordered, length-prefixed candidate snapshot fields rendered into the rerank prompt: ID, name, category, currency, price, warehouse, delivery days, platform, supported regions, cross-border eligibility and stock. Candidate order and duplicate entries are preserved in the snapshot so the fingerprint matches the prompt-visible sequence. The same snapshot remains duplicate-blocked, while changed IDs or prompt-relevant attributes may make a new bounded call. Raw candidate values are not added to the LLM call Trace. The retry appends `:retry:1` to that base and shares the same atomic call budget. A skipped retry caused by exhausted budget emits a non-provider local fallback trace, so the budget reason remains visible without consuming another call. The run deadline still checks before waiting, bounds queue plus execution time, checks again after completion and cancels pending futures on expiry; extreme configured durations are now clamped to a wrap-safe relative nanosecond delay. On 2026-10-31 the focused offline suite ran 70 tests with no failures, the complete Java suite ran 653 tests with 0 failures, 0 errors and 22 skipped, and the isolated PostgreSQL persistence/lease/recovery suite ran 22 tests with no failures. No Live SMOKE or PILOT was executed for these changes.

The valid report is stored under `target/agent-benchmark/live-smoke-core/`. The first attempt, archived under `target/agent-benchmark/live-smoke-core-attempt-1/`, used a 24-call aggregate ceiling and is invalid because calls exhausted the budget and the Live runner fixed the model timeout at 15 seconds. The call budget was raised for the authorized second attempt; LLM-backed Agent outer timeouts now follow the effective client timeout with margin, and the Live runner now leaves timeout selection to `ECOM_RECOMMEND_LLM_TIMEOUT_MS`. The nine-case PILOT has not been authorized or executed.

An authorized rerun on revision `272744f` was stopped during the first case and is not a benchmark result. The shell loaded the complete local `.env`; its live Qwen embedding variables had higher priority than the runner's old Spring default properties, and the shared `public` schema allowed a resident Recovery Worker to claim the interrupted Run with another runtime image/topology. The runner produced no case observation and did not overwrite the valid report. Its redacted abort evidence is stored under `target/agent-benchmark/live-smoke-core-aborted-272744f/`. No quality rate may be derived from that partial Trace.

## Explicit live run

Live execution is excluded from normal tests. It requires both authorization switches, an explicit `SMOKE`, `PILOT`, or `FULL` scope, PostgreSQL, provider credentials, positive bounded per-case calls, a positive per-call output-token cap, and an **exact** aggregate call budget:

```text
requiredTotalCalls = caseCount * armCount * maxCallsPerCase
```

Run SMOKE first. It executes three fixed normal regression cases, one per scene. `ECOM_AGENT_BENCHMARK_ARMS` defaults to `CORE`, comparing only Single Agent and Guarded Multi-Agent. The successful authorized run above used a 12-call ceiling, so its exact aggregate ceiling was `3 * 2 * 12 = 72`; the earlier four-call/24-total attempt was invalid and is retained only as failure provenance. The prepared rerun uses 14 calls per case, an exact aggregate call ceiling of `3 * 2 * 14 = 84`, and `ECOM_AGENT_BENCHMARK_MAX_OUTPUT_TOKENS=2048`. The runner injects that value through a highest-priority benchmark property source; environment variables cannot re-enable Embedding, demo startup seed, or Recovery Worker. This limits completion tokens per provider call. `84 * 2048` is only a completion-token ceiling and must not be reported as a total-token ceiling because input tokens remain separate and are controlled by the fixed dataset, tools, candidate bounds and prompts. PILOT/FULL additionally require `ECOM_AGENT_BENCHMARK_SMOKE_PASSED=true`. Explicit `ECOM_AGENT_BENCHMARK_ARMS=FULL` runs all six arms, including parallel-off. Budget mismatch fails with `LIVE_AGENT_BENCHMARK_BUDGET` before execution. Runtime `llm_budget_exhausted` or `llm_budget_unavailable` cases are marked invalid rather than counted as model business failures. A human must select every later per-case budget and matching aggregate ceiling from observed evidence plus an explicit margin; the framework does not infer prices or budgets.

PILOT provenance is selection-specific: the report contains a manifest reference hashed from the full dataset hash and the exact nine selected case IDs listed above. The loader rejects a PILOT that does not contain exactly nine cases, cover all three scenes, and include every required category. The complete 36-case complex resource remains a source reference but is marked `includedInJavaMetrics=false`; FULL continues to report the complete suite hashes.

Use the fail-closed entry point after separate paid-run authorization. It imports only the LLM credential/provider allowlist from `.env`; it never imports embedding settings. Do not source the complete `.env` before a benchmark.

```powershell
.\java\scripts\run-live-agent-benchmark.ps1 -Scope SMOKE -Arms CORE -CallsPerCase 14 -ConfirmPaidRun
```

The entry point derives the exact aggregate budget and sets `ECOM_AGENT_BENCHMARK_SCHEMA=agent_benchmark`. Startup acquires a PostgreSQL advisory lock, rejects an active benchmark session or RUNNING row, creates a unique Run namespace, and forces the effective Spring Environment to `agent.embedding.live-enabled=false`, `agent.embedding.max-calls=0`, `agent.demo.seed-enabled=false`, and `agent.recovery.enabled=false`. It derives a datasource URL with `currentSchema=agent_benchmark`; the automatic demo initializer stays absent, then an explicit deterministic benchmark fixture import runs only after the embedding-off properties are verified. The fixture may use lexical retrieval but cannot call an embedding provider.

Every run is registered with namespace, full commit SHA, config hash, case, arm, and topology before execution. A PostgreSQL call ledger reserves each provider attempt transactionally and applies case-arm, arm-total, and whole-session ceilings before the provider executor is submitted. Duplicate fingerprints and commit/config/topology mismatches fail closed. Normal, exception, cancellation, and JVM-shutdown paths mark manifest rows terminal and make recommendation rows non-recoverable; hard process death may leave an isolated stale session, but the next startup rejects it rather than recovering or clearing it automatically. Historical benchmark and production data are never deleted. On 2026-11-01 this isolation change passed the complete 683-test Java suite (0 failures, 0 errors, 24 opt-in PostgreSQL tests skipped) and 24 isolated PostgreSQL 16.14 + pgvector contracts with no failures. The PostgreSQL suite includes a real Benchmark Spring context with a no-network Fake Provider, durable Provider claims and Trace, an injected abort, terminal/non-recoverable Run cleanup, ABORTED manifest/session fencing and a shared-schema write check. No Live profile or model provider was executed for this verification.

After a SMOKE report is reviewed and accepted, set `ECOM_AGENT_BENCHMARK_SMOKE_PASSED=true` before changing the scope to `PILOT`. The observed SMOKE P95 was 11 provider calls for Single and 10 for Guarded Multi. The prepared CORE PILOT therefore uses a human-selected ceiling of 14 calls per case (three calls above the higher observed P95), a 2048-token per-call completion cap, a 30-second provider timeout, and the exact aggregate call ceiling `9 * 2 * 14 = 252`. Product reranking may make two sequential provider calls, so its outer deadline is `2 * providerTimeout + 2s` (62 seconds for this configuration). Live preflight now requires `ECOM_AGENT_RUN_TIMEOUT` to be strictly greater than that deadline; the prepared command uses 90 seconds so the global run deadline cannot cut off the bounded repair attempt. Set both per-case budget variables to `14`, set `ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS=252`, keep `ECOM_AGENT_BENCHMARK_MAX_OUTPUT_TOKENS=2048`, retain `ECOM_AGENT_BENCHMARK_ARMS=CORE`, and change the scope to `PILOT` only after separate authorization. The framework does not infer this margin, silently promote scopes, sample cases or share budget across arms.

The live report records scope and arm mode, provider/model, LLM/provider call counts, the shared per-call output-token cap, RULES fallback detection, actual token/cost availability, P50/P95, tool trace, and failure reason. Fair-control validation requires the output cap to match across arms. The evaluator requires provider-backed model planning, no silent RULES fallback, an LLM final business decision for completed outcomes, and persisted run/model/LLM latency and usage/tool/terminal/final-decision trace. Token metrics remain null unless every call returns usage. Cost remains null unless complete usage and explicit input/output USD-per-million-token rates are supplied. The authorized SMOKE above supplies observed provider-call and token data, but no PILOT/FULL result or provider-priced cost claim.
