#!/usr/bin/env bash
# Experiment B (guarded): Tau3TrustBoundaryAgent via the official runner.
#
# Usage: bash scripts/run_guarded.sh [config.yaml] [runner args...]
# Default config: configs/smoke.yaml (10 tasks x 1 trial).
# configs/full.yaml is a full-base-split run via a top-level `run:` block
# (114 tasks x 1 trial) — runconfig resolves `run:` directly, so no
# TAU3_STAGE is involved (same resolution in run_baseline.sh and
# ecommerce_tau3.run). Extra arguments are forwarded verbatim to
# `python -m ecommerce_tau3.run`; e.g. a 50-task development run:
#   bash scripts/run_guarded.sh configs/full.yaml --num-tasks 50
#
# The official tau2 CLI only exposes agents registered inside the tau2
# package (llm_agent, ...). Custom agents follow the OFFICIAL pattern from
# examples/agents/minimal_text_agent.py: register in-process via
# `tau2.registry.registry.register_agent_factory`, then run through
# `tau2.runner.run_domain(TextRunConfig(...))` — the exact same code path
# the CLI uses. `python -m ecommerce_tau3.run` does that.
#
# Settings are read from the SAME config as run_baseline.sh, so both
# experiments differ only in the agent implementation. Both experiments land
# in ONE results directory when TAU3_RUN_ID is set:
#   TAU3_RUN_ID=20260101-000000 bash scripts/run_guarded.sh
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$BENCH_DIR"

CONFIG="${1:-$BENCH_DIR/configs/smoke.yaml}"
TAU2_CHECKOUT="$BENCH_DIR/env/tau2-bench"

# --- API-key gate: stop BEFORE any model call; never fake a run ---
if [ -z "${OPENAI_API_KEY:-}" ]; then
  echo "PUBLIC_BENCHMARK_NOT_RUN_NO_API_KEY"
  echo "Required OpenAI credentials (OPENAI_API_KEY) are absent; no model call was made."
  exit 1
fi

# --- Shared results timestamp (baseline + guarded must land in ONE dir) ---
RUN_ID="${TAU3_RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RESULTS_DIR="$BENCH_DIR/results/$RUN_ID"
mkdir -p "$RESULTS_DIR"

# --- Read config (same values for both experiments) ---
STAGE_FLAG=()
[ -n "${TAU3_STAGE:-}" ] && STAGE_FLAG=(--stage "$TAU3_STAGE")
eval "$(uv run python -m ecommerce_tau3.runconfig --config "$CONFIG" --impl guarded "${STAGE_FLAG[@]}")"

echo "==> Guarded agent: $AGENT_IMPL | domain=$DOMAIN split=$SPLIT tasks=${TASK_IDS:-<all>} numTasks=${NUM_TASKS:-<all>} trials=$TRIALS seed=$SEED"
echo "    agent-llm=$AGENT_LLM user-llm=$USER_LLM"
echo "    results -> $RESULTS_DIR/$SAVE_NAME"

# Official runner writes into <TAU2_DATA_DIR>/simulations/<save-to>/
export TAU2_DATA_DIR="$TAU2_CHECKOUT/data"

ARGS=(--config "$CONFIG" --agent "$AGENT_IMPL" --save-to "$SAVE_NAME")
[ -n "${TAU3_STAGE:-}" ] && ARGS+=(--stage "$TAU3_STAGE")
[ -n "$TASK_IDS" ] && ARGS+=(--task-ids $TASK_IDS)
[ -n "$NUM_TASKS" ] && ARGS+=(--num-tasks "$NUM_TASKS")
ARGS+=(
  --agent-llm "$AGENT_LLM"
  --user-llm "$USER_LLM"
  --num-trials "$TRIALS"
  --seed "$SEED"
)

# Forward extra CLI arguments after the config path (e.g. --num-tasks 50).
uv run python -m ecommerce_tau3.run "${ARGS[@]}" "${@:2}"

# Copy official artifacts + adapter guard events verbatim into results/.
cp -r "$TAU2_DATA_DIR/simulations/$SAVE_NAME" "$RESULTS_DIR/$SAVE_NAME"
echo "==> Official results (byte-identical copy): $RESULTS_DIR/$SAVE_NAME"
echo "    run dir: $RESULTS_DIR"
