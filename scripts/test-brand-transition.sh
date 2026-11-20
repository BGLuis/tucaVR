#!/usr/bin/env bash
set -euo pipefail

# ==============================================================================
# Automated E2E Test Runner for tucaVR Brand Identity Transition (Epic #102)
#
# Covers all 4 Tiers:
#   Tier 1: Feature Coverage (R1, R2, R3, R4)
#   Tier 2: Boundary & Corner Cases (Precision, Safe zone, Zero diff, Duration)
#   Tier 3: Cross-Feature Combinations (Pairwise asset & layout parity)
#   Tier 4: Quality gates run through Gradle (UiSourceGuardsTest, ktlint)
# ==============================================================================

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# The reproduction tests re-run design/logo/logo.py, whose PNG output depends on the
# exact cairosvg stack, so by default the suite runs from a venv built from the pinned
# design/logo/requirements.txt (rebuilt whenever that file changes). Set PYTHON to use
# an interpreter that already has those packages.
if [[ -z "${PYTHON:-}" ]]; then
    if ! command -v python3 >/dev/null 2>&1; then
        echo "❌ Error: Python 3 not found on PATH." >&2
        exit 1
    fi
    VENV="$REPO_ROOT/build/brand-venv"
    REQUIREMENTS="$REPO_ROOT/design/logo/requirements.txt"
    if ! cmp -s "$REQUIREMENTS" "$VENV/requirements.txt"; then
        echo "Setting up $VENV from design/logo/requirements.txt..."
        rm -rf "$VENV"
        python3 -m venv "$VENV"
        "$VENV/bin/pip" install --quiet --disable-pip-version-check -r "$REQUIREMENTS"
        cp "$REQUIREMENTS" "$VENV/requirements.txt"
    fi
    PYTHON="$VENV/bin/python"
fi

exec "$PYTHON" "$REPO_ROOT/scripts/test_brand_transition.py" "$@"
