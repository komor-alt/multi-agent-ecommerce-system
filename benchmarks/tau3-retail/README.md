# τ³-bench Retail — Public Benchmark Adapter

Public benchmark validation of the project's **bounded-planning + server-side
guard** design against the **official τ³ Retail environment** — without
modifying any official component.

> **Positioning (read first).** `Tau3TrustBoundaryAgent` is a **benchmark
> adapter** that transfers the *design principles* of the Java after-sales
> agent (bounded planning, server-side policy/tool guards, confirmation
> gates, evidence-based preconditions) into the official τ³ Retail
> environment. It is **not** the Java production agent running on τ³ — the
> τ³ Retail tools, policy, database, conversation protocol and evaluator are
> official and untouched. The τ³ adapter validates the architecture, not the
> exact Java production execution path.

## τ³ Version (pinned)

```text
repository: sierra-research/tau2-bench   (the τ³-bench project)
commit:     79975ac5741e23fbb1d2ac44262d62398a6d87bd
domain:     retail
taskSplit:  base
```

- `benchmark-lock.json` records the pin and a snapshot of the official retail
  tool inventory; tests fail loudly if the official tool set changes.
- The upstream repo is shallow-cloned read-only at `.tau3-upstream/` (repo
  root) for review verification; scripts re-clone the same SHA into
  `env/tau2-bench/` (gitignored) for runs. No official data is copied into
  this project.

## Status

```
PUBLIC_BENCHMARK_NOT_RUN_NO_API_KEY
```

Delivered: pinned config, committed `uv.lock`, offline guard tests, official
registration path, bounded LLM planning via the official `generate` API, and
run scripts. **No benchmark has been run** — running requires real LLM API
keys (agent + user simulator); both scripts stop with
`PUBLIC_BENCHMARK_NOT_RUN_NO_API_KEY` before any model call when
`OPENAI_API_KEY` is absent. Results will be reported only from actual runs;
nothing is fabricated or estimated.

- ✅ Official interfaces confirmed from the pinned source (CLI, agent
  factory, HalfDuplexAgent, retail domain/splits, result structure)
- ✅ Guard chain implemented and unit-tested offline (auth binding,
  confirmation gate, tool inventory pinning, registration)
- ✅ Bounded LLM planner implemented on the official `tau2.utils.llm_utils.generate`
  API (system prompt keeps the complete official policy + bounded protocol;
  malformed / multiple tool calls fail safely to the deterministic fallback)
- ✅ Smoke config: 10 real tasks from the official retail `base` split,
  1 trial
- ✅ `uv.lock` committed; setup uses `uv sync --frozen` (strict Python 3.12)
- ⏳ Actual runs (needs API keys)

## Install

Requires Python 3.12 and [uv](https://docs.astral.sh/uv/). This benchmark
environment is fully isolated from the rest of the repo (no `python/`
deps, no main CI).

```bash
bash scripts/setup_tau3.sh     # clone pinned checkout + frozen sync + verify
uv run pytest                  # offline guard tests (no API keys needed)
```

`uv.lock` is **committed**; `setup_tau3.sh` never re-resolves dependencies
(`uv sync --frozen --extra dev --python 3.12`) and verifies the installed
tau2 package is exactly the pinned commit.

`pyproject.toml` pins the official τ³ package to the same SHA as
`benchmark-lock.json`:

```toml
"tau2 @ git+https://github.com/sierra-research/tau2-bench@79975ac5741e23fbb1d2ac44262d62398a6d87bd"
```

## Integration (how it plugs into the official framework)

- **CLI (official):** `tau2 run --domain retail --agent llm_agent ...` —
  the official `llm_agent` baseline needs no changes.
- **Custom agent:** the official tau2 CLI only exposes agents registered
  inside the tau2 package. Custom agents use the official pattern from
  `examples/agents/minimal_text_agent.py`: register in-process via
  `tau2.registry.registry.register_agent_factory(...)` (see
  `src/ecommerce_tau3/factory.py`), then run through the same official code
  path the CLI uses, `tau2.runner.run_domain(TextRunConfig(...))` — this is
  exactly what `python -m ecommerce_tau3.run` does
  (`scripts/run_guarded.sh`).
- **Zero official code modified.** Official tasks, user simulator, retail
  environment, tools, policy, and evaluator are used unmodified.

## Experiments

### Experiment A — Official Baseline (`llm_agent`)

Official `tau2` `llm_agent` (native tool calling), unmodified prompt:

```text
User
 ↓
LLM (official llm_agent prompt + official retail policy)
 ↓
native tool calling
 ↓
τ³ Retail Tools
```

### Experiment B — `GuardedRetailAgent` (this adapter)

```text
              τ³ Official User Simulator
                         │
                         ▼
                 Retail Conversation
                         │
          ┌──────────────┴──────────────┐
          │                             │
   Official LLMAgent             GuardedRetailAgent
                                        │
                              Intent / Planning (bounded)
                                        │
                               Policy / Tool Guard
                                        │
                              Confirmation Gate
                                        │
          └──────────────┬──────────────┘
                         ▼
                  τ³ Retail Tools
                         │
                         ▼
                  τ³ Retail DB
                         │
                         ▼
                Official Evaluator
                         │
                         ▼
                      Reward
```

Ideas transferred from the Java after-sales agent:

| Java idea | τ³ adapter module |
|---|---|
| Bounded LLM Intake / planning | `planner.py` (`BoundedPlanner` → `BoundedPlan`) |
| Server-side decision / guards | `tool_guard.py` + `policy_guard.py` (deterministic, evidence-based) |
| Confirmation / HITL gate | confirmation gate in `agent.py` + `agent_state.py` (`pending_action`) |
| Authentication / user binding | `policy_guard.py` (`authenticated_user_id`, order/payment evidence) |
| Evidence preconditions | `known_entities` learned from the agent's own tool observations |
| Guard events (structured, no CoT) | `metrics.py` (`GuardEvent`, supplemental metrics) |

τ³-specific adapter parts: official tool classification via the pinned
`ToolType` metadata, official id-based tool-call linkage, official
`HalfDuplexAgent` interface, driver `run.py`.

**Rules are generic** — derived from the official retail policy and tool
metadata. There are no task-id rules and no use of gold actions/expected
answers anywhere in the agent (requirements §15/§16/§37).

## Fair comparison

Both experiments share the **same** task set, seed, trial count, agent model,
user model, temperatures, max steps, and τ³ commit — only the agent
implementation differs. Settings live in `configs/*.yaml` and are recorded
again in the official results metadata (`info` in results.json).

| Setting | smoke.yaml | official default |
|---|---|---|
| domain / split | retail / base | retail / base |
| tasks | 10 real base-split IDs | — |
| trials | 1 | 1 |
| seed | 300 | 300 |
| agent model | gpt-4.1-2025-04-14 | gpt-4.1-2025-04-14 |
| user model | gpt-4.1-2025-04-14 | gpt-4.1-2025-04-14 |
| temperature | 0.0 / 0.0 | 0.0 / 0.0 |
| max_steps / max_errors | 200 / 10 | 200 / 10 |

The 10 smoke task IDs (`0, 10, 17, 22, 40, 48, 56, 67, 89, 113`) are real
IDs from the official retail `base` split of the pinned commit, chosen to
cover different task categories (exchange, return, address change, payment
method, gift card, cancel, read-only inquiry, product search, escalation).
They were selected from task IDs + user-scenario metadata only — no gold
actions were read.

## Run

```bash
# smoke: 10 tasks x 1 trial, both agents, identical settings
RUN_ID=$(date +%Y%m%d-%H%M%S)
TAU3_RUN_ID=$RUN_ID bash scripts/run_baseline.sh   # Experiment A -> results/$RUN_ID/official-baseline/
TAU3_RUN_ID=$RUN_ID bash scripts/run_guarded.sh    # Experiment B -> results/$RUN_ID/guarded-agent/

# summarize + failure analysis (official data only)
uv run python scripts/summarize.py results/$RUN_ID
uv run python scripts/analyze_failures.py results/$RUN_ID/guarded-agent
```

`TAU3_RUN_ID` shares ONE results directory between both experiments so
`summarize.py` can compare them; without it each script stamps its own
timestamp (`results/<timestamp>/`). Both scripts stop with
`PUBLIC_BENCHMARK_NOT_RUN_NO_API_KEY` (exit 1) — before creating any
results directory and before any model call — when `OPENAI_API_KEY` is
absent.

Stages (recommended): 5-task install check → 10-task smoke (both agents) →
50-task development run → full retail `base` split (114 tasks, ≥1 trial).
`configs/full.yaml` is a full-base-split run (top-level `run:` block,
114 tasks x 1 trial); a 50-task development run uses the same config with a
`--num-tasks` override, which both scripts forward verbatim:

```bash
bash scripts/run_baseline.sh configs/full.yaml --num-tasks 50    # dev run, 50 tasks
bash scripts/run_guarded.sh  configs/full.yaml                   # full split, 114 tasks
```

Configs are resolved by `src/ecommerce_tau3/runconfig.py` — the single
source of truth shared by both scripts and `ecommerce_tau3.run`: a top-level
`run:` block always wins (smoke.yaml, full.yaml); legacy `dev:`/`final:`
blocks are selected via `TAU3_STAGE`.

## Results

The official runner writes official artifacts to
`<TAU2_DATA_DIR>/simulations/<save-to>/` (results.json + trajectories);
scripts copy them **verbatim** into:

```text
benchmarks/tau3-retail/results/
└── <run-id>/                 # TAU3_RUN_ID (default: timestamp)
    ├── metadata.json                 (generated by summarize.py)
    ├── official-baseline/            official tau2 results (untouched)
    │   ├── results.json
    │   └── guard_events.json         (only in guarded-agent/)
    ├── guarded-agent/                official tau2 results (untouched)
    ├── comparison.json               (summarize.py)
    ├── comparison.md                 (summarize.py)
    └── failure-analysis.md           (analyze_failures.py, per agent dir)
```

Before writing anything, `summarize.py` validates the two experiments as a
**fair pair**: the *installed* τ³/tau2 dependency commit (read from the
installed distribution's `direct_url.json`) equals the `benchmark-lock.json`
pin — fail closed if the installed commit is unknown — and the runner git
commits stamped into the official results (`Results.info.git_commit` = the
runner's working-tree commit at run time, *not* the tau2 commit) equal each
other. Also checked: same environment domain, same agent LLM + args, same
user implementation/LLM/args, same seed / trials / max_steps, and the same
exact `(task_id, trial)` set; the agent implementation is expected to differ.
Any mismatch aborts with a clear error and writes nothing. `metadata.json`
records the reproducibility facts — UTC generation time, the
benchmark-lock repository/domain/split, the pinned tau2 commit (`commit`)
and the installed tau2 commit verified against it (`installedTau2Commit`),
the runner git commit from the official results
(`officialResultsRunnerGitCommit` — deliberately distinct from the tau2
commit), per-run model + args, seed/max_steps, task/simulation/trial counts
and parity status — and never contains scores (scores live in
`comparison.json`, always derived from the actual official results).

- **Primary metric: official τ³ reward** (`RewardInfo.reward`), computed by
  the official evaluator (DB end-state + communicate components; reference
  actions are never treated as a per-call requirement).
- Supplemental metrics only: tool calls/task, guard rejections, confirmation
  blocks, auth blocks, fallbacks, turns — from guard events / official
  messages. Never a substitute for the official reward. Unavailable values
  are reported `unavailable`, never estimated.
- No Pass@k claims unless enough trials are actually run and computed by
  official tools.

## Guarded agent design (requirements §11–§13)

1. **Tool classification** — derived from the pinned official retail tool
   metadata (`ToolType`): READ_ONLY / MUTATION / HANDOFF / GENERIC; unknown
   or unlisted tools **fail closed**. Tests pin the official inventory
   (`tests/test_tool_guard.py`): official tool changes → test failure.
2. **Policy guard** — defense-in-depth on top of the official policy (which
   stays fully in the system prompt): authentication required (policy:
   "authenticate the user identity ... via email, or via name + zip"),
   user binding (`user_id` must be the authenticated user), order/payment
   evidence preconditions (the agent may only mutate entities it actually
   observed through its own successful reads).
3. **Confirmation gate** — DB-updating mutations (cancel/modify/return/
   exchange/address) require explicit user confirmation matching the exact
   pending action (tool + arguments). Old confirmations cannot be reused
   across actions; a new pending action invalidates the old one; the
   confirmation is consumed when the mutation executes.

## Limitations (honest)

- **Benchmark adapter ≠ exact Java production codepath** — it validates the
  architecture, not the Java execution chain.
- **τ³ Retail ≠ the project's self-built shipment-delay domain** — different
  tools, policy, DB, protocol.
- User simulator and agent model both influence results.
- Single-trial runs do not represent stable results; no Pass@k is claimed
  unless actually run with official statistics.
- The bounded planner calls the official `generate` API with real model
  credentials; malformed model output fails safely to the deterministic
  clarifying fallback, which means guard behavior can be unit-tested offline
  but end-to-end rewards require real runs.
- Runs require real API keys; without them the scripts stop with
  `PUBLIC_BENCHMARK_NOT_RUN_NO_API_KEY` — nothing is run and nothing is
  fabricated.

## Requirements coverage

- Pinned commit, no floating `main` — `benchmark-lock.json` + git dep pin.
- No official data copied; official components unmodified.
- Baseline (`llm_agent`) preserved and unmodified.
- Guarded agent registered via official factory; official evaluator used
  unchanged; official reward is the primary metric.
- No task-id rules, no gold-action reads, no prompt tuning to benchmark
  tasks.
- Isolated from main CI; a manual `workflow_dispatch`-only workflow exists
  (`.github/workflows/tau3-retail-benchmark.yml`) — it creates ONE
  `TAU3_RUN_ID` persisted via `GITHUB_ENV`, shares it between both
  experiment steps (so summarize sees a single directory), and fails
  without `OPENAI_API_KEY` (no fake fallback).
