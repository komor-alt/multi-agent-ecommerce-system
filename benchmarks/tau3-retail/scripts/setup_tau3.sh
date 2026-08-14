#!/usr/bin/env bash
# Setup: install the pinned τ³ environment and verify it (offline).
#
# What this does:
#   1. Clones the OFFICIAL tau2-bench repo at the pinned benchmark-lock SHA
#      into env/tau2-bench/ (used read-only: official tasks/db/policy are
#      loaded from there; NOTHING is copied into this project).
#   2. `uv sync --frozen` from the COMMITTED uv.lock: strict Python 3.12,
#      pinned tau2 package (git dependency, same SHA as benchmark-lock.json).
#   3. Verifies the pinned commit and the official data directory.
#
# uv.lock IS committed — setup never re-resolves dependencies (`--frozen`).
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$BENCH_DIR"

PINNED_COMMIT="$(python -c 'import json;print(json.load(open("benchmark-lock.json"))["commit"])')"
REPO_URL="https://github.com/sierra-research/tau2-bench"

# --- 1. Pinned official checkout (data only; official repo is never modified) ---
TAU2_CHECKOUT="$BENCH_DIR/env/tau2-bench"
if [ ! -d "$TAU2_CHECKOUT/.git" ]; then
  echo "==> Cloning official $REPO_URL at $PINNED_COMMIT (shallow)..."
  mkdir -p "$BENCH_DIR/env"
  git init -q "$TAU2_CHECKOUT"
  git -C "$TAU2_CHECKOUT" remote add origin "$REPO_URL"
  git -C "$TAU2_CHECKOUT" fetch -q --depth 1 origin "$PINNED_COMMIT"
  git -C "$TAU2_CHECKOUT" checkout -q FETCH_HEAD
fi
echo "==> Pinned checkout: $TAU2_CHECKOUT"
CHECKED_OUT_COMMIT="$(git -C "$TAU2_CHECKOUT" rev-parse HEAD)"
if [ "$CHECKED_OUT_COMMIT" != "$PINNED_COMMIT" ]; then
  echo "ERROR: $TAU2_CHECKOUT is at $CHECKED_OUT_COMMIT, expected $PINNED_COMMIT (benchmark-lock.json)." >&2
  echo "       Re-clone it (remove env/tau2-bench and rerun) to get the pinned official data." >&2
  exit 1
fi

# --- 2. Project environment: Python 3.12 + uv, from the COMMITTED lock ---
echo "==> uv sync --frozen (committed uv.lock; strict Python 3.12)..."
uv python install 3.12
uv sync --frozen --extra dev --python 3.12

# --- 3. Verification (offline; no API keys needed for these checks) ---
# Point tau2.utils.utils.DATA_DIR at the official data in the pinned checkout;
# without TAU2_DATA_DIR the package falls back to its install dir (.venv) for
# non-editable installs and the retail-tasks check below would fail.
export TAU2_DATA_DIR="$TAU2_CHECKOUT/data"
echo "==> Verification:"
uv run python - <<'PY'
import json, pathlib, sys
from importlib.metadata import distribution
from tau2.utils.utils import DATA_DIR

lock = json.loads(pathlib.Path("benchmark-lock.json").read_text())
pinned = lock["commit"]

# Verify the INSTALLED tau2 package is exactly the pinned commit
# (read from the wheel's direct_url.json, which uv writes for git deps).
installed = None
try:
    dist = distribution("tau2")
    direct_url = dist.read_text("direct_url.json")
    if direct_url:
        info = json.loads(direct_url)
        installed = info.get("vcs_info", {}).get("commit_id")
except Exception as exc:  # pragma: no cover - reporting only
    print(f"WARNING: could not read installed tau2 metadata: {exc}")

print(f"pinned commit   : {pinned}")
print(f"installed commit: {installed or 'unknown'}")
if installed and installed != pinned:
    print("ERROR: installed tau2 commit does not match benchmark-lock.json.",
          file=sys.stderr)
    sys.exit(1)

print(f"data dir        : {DATA_DIR}")
if not (DATA_DIR / 'tau2' / 'domains' / 'retail' / 'tasks.json').exists():
    print('ERROR: official retail tasks not found under DATA_DIR. '
          'Set TAU2_DATA_DIR=env/tau2-bench/data or run from the checkout.',
          file=sys.stderr)
    sys.exit(1)
print("retail tasks    : OK")
PY

# --- 4. API keys (required for any actual run; never faked) ---
if [ -z "${OPENAI_API_KEY:-}" ] && [ -z "${ANTHROPIC_API_KEY:-}" ]; then
  echo ""
  echo "WARNING: no LLM API key found (OPENAI_API_KEY / ANTHROPIC_API_KEY)."
  echo "         Setup is complete, but runs require a real key (no fake model fallback)."
fi

echo ""
echo "Setup complete. Next:"
echo "  uv run pytest                 # offline guard tests"
echo "  bash scripts/run_baseline.sh  # official LLMAgent smoke (needs API key)"
echo "  TAU3_RUN_ID=\$(date +%Y%m%d-%H%M%S) bash scripts/run_baseline.sh  # share one results dir"
echo "  TAU3_RUN_ID=... bash scripts/run_guarded.sh                       # ... with the guarded run"
