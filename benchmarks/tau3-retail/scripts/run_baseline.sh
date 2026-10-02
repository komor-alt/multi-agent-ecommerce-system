#!/usr/bin/env bash
# Experiment A (baseline): OFFICIAL llm_agent via the official `tau2 run` CLI.
#
# Usage: bash scripts/run_baseline.sh [config.yaml] [runner args...]
# Default config: configs/smoke.yaml (10 tasks x 1 trial).
# configs/full.yaml is a full-base-split run via a top-level `run:` block
# (114 tasks x 1 trial) — runconfig resolves `run:` directly, so no
# TAU3_STAGE is involved (same resolution in run_guarded.sh and
# ecommerce_tau3.run). Extra arguments are forwarded verbatim to the
# official `tau2 run` CLI; e.g. a 50-task development run:
#   bash scripts/run_baseline.sh configs/full.yaml --num-tasks 50
#
# Settings (agent model, user model, seed, trials, task set) are read from
# the config so that Experiment A and Experiment B are IDENTICAL except for
# the agent implementation. Official results are written by the official
# runner into <TAU2_DATA_DIR>/simulations/<save-to>/ and then copied
# verbatim into results/<run-id>/<save-to>/ (never modified).
#
# Both experiments share ONE results directory when TAU3_RUN_ID is set:
#   TAU3_RUN_ID=20260101-000000 bash scripts/run_baseline.sh
#   TAU3_RUN_ID=20260101-000000 bash scripts/run_guarded.sh
# so scripts/summarize.py can compare them.
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
# TAU3_STAGE is only relevant for legacy configs with dev/final blocks;
# configs with a top-level `run:` block (smoke.yaml, full.yaml) ignore it.
STAGE_FLAG=()
[ -n "${TAU3_STAGE:-}" ] && STAGE_FLAG=(--stage "$TAU3_STAGE")
eval "$(uv run python -m ecommerce_tau3.runconfig --config "$CONFIG" --impl baseline "${STAGE_FLAG[@]}")"

if [ "$AGENT_IMPL" != "llm_agent" ]; then
  echo "ERROR: baseline run requires the official llm_agent, got: $AGENT_IMPL" >&2
  exit 1
fi

echo "==> Official baseline: $AGENT_IMPL | domain=$DOMAIN split=$SPLIT tasks=${TASK_IDS:-<all>} numTasks=${NUM_TASKS:-<all>} trials=$TRIALS seed=$SEED"
echo "    agent-llm=$AGENT_LLM user-llm=$USER_LLM"
echo "    results -> $RESULTS_DIR/$SAVE_NAME"

# The official runner refuses to overwrite an existing save directory. Use a
# run-scoped source name so repeated benchmark runs cannot resume from or
# collide with stale official artifacts; copy it under the stable comparison
# name only after the run succeeds.
OFFICIAL_SAVE_NAME="${SAVE_NAME}-${RUN_ID}"

# Official runner writes into <TAU2_DATA_DIR>/simulations/<save-to>/
export TAU2_DATA_DIR="$TAU2_CHECKOUT/data"

CMD=(tau2 run --domain "$DOMAIN" --task-split-name "$SPLIT")
[ -n "$TASK_IDS" ] && CMD+=(--task-ids $TASK_IDS)
[ -n "$NUM_TASKS" ] && CMD+=(--num-tasks "$NUM_TASKS")
CMD+=(
  --agent llm_agent
  --agent-llm "$AGENT_LLM" --agent-llm-args "$AGENT_LLM_ARGS"
  --user user_simulator --user-llm "$USER_LLM" --user-llm-args "$USER_LLM_ARGS"
  --num-trials "$TRIALS" --seed "$SEED"
  --max-steps "$MAX_STEPS" --max-errors "$MAX_ERRORS"
  --max-concurrency "$MAX_CONCURRENCY"
  --save-to "$OFFICIAL_SAVE_NAME"
)
# Forward extra CLI arguments after the config path (e.g. --num-tasks 50).
# $1 is the config path and must NOT reach the runner.
"${CMD[@]}" "${@:2}"

# Copy official artifacts verbatim into the benchmark results directory.
cp -r "$TAU2_DATA_DIR/simulations/$OFFICIAL_SAVE_NAME" "$RESULTS_DIR/$SAVE_NAME"
echo "==> Official results (byte-identical copy): $RESULTS_DIR/$SAVE_NAME"
echo "    run dir: $RESULTS_DIR"
