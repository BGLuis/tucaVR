#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Automated E2E Test Runner for tucaVR Brand Identity Transition (Epic #102)
#
# Covers all 4 Tiers:
#   Tier 1: Feature Coverage (R1, R2, R3, R4)
#   Tier 2: Boundary & Corner Cases (Precision, Safe zone, Zero diff, Duration)
#   Tier 3: Cross-Feature Combinations (Pairwise asset & layout parity)
#   Tier 4: Real-World Scenarios (Lifecycle, sleep/wake, skip, reduced motion, lint)
# ==============================================================================

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

PYTHON="${PYTHON:-python3}"

if ! command -v "$PYTHON" >/dev/null 2>&1; then
    echo "❌ Error: Python 3 not found on PATH." >&2
    exit 1
fi

exec "$PYTHON" "$REPO_ROOT/scripts/test_brand_transition.py" "$@"
