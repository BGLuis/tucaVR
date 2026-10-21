#!/usr/bin/env python3
"""Regenerates on the PC the 5 charts of the stats panel (F5/F6)
from a versioned telemetry CSV, with no headset needed — the same data and the
same 5 views (app/src/main/java/com/tucavr/designsystem/VoidChart.kt), for offline triage.

Usage:
    python3 scripts/plot-session.py path/to/session-XXXX.csv [--out output.png]

Requires: matplotlib (pip install matplotlib). No other dependency — it reads the CSV
with the standard `csv` module, not `pandas`, on purpose (a standalone script that does
not depend on the rest of the project's toolchain).

D-05/schema_version: each CSV row carries the schema version in the 1st column
(see DebugTelemetryExporter.SCHEMA_VERSION). Columns that did not exist in older
versions (e.g. v1 has no video_stall_count, introduced in v2) simply result in the
corresponding chart being skipped with a warning — never a crash.
"""
import argparse
import csv
import sys

try:
    import matplotlib.pyplot as plt
except ImportError:
    print("Error: matplotlib is not installed. Run: pip install matplotlib", file=sys.stderr)
    sys.exit(1)

# The same thresholds/edges used natively — see BottleneckStageAnalyzer.kt and
# vr_player_app_vulkan.cpp (kFrameTimeHistogramEdgesMs).
STALL_THRESHOLD_MS = 500.0
QUEUE_EMPTY_THRESHOLD = 5
QUEUE_FULL_THRESHOLD = 80
HIST_BUCKET_LABELS = ["<11.1", "<16.7", "<20", "<33.3", "<50", "<100", "<250", ">=250"]

STAGE_NONE, STAGE_NETWORK, STAGE_PRESENTATION = "NONE", "NETWORK", "PRESENTATION"
STAGE_COLORS = {STAGE_NONE: "#4CAF50", STAGE_NETWORK: "#4A90D9", STAGE_PRESENTATION: "#F44336"}


def read_rows(csv_path):
    with open(csv_path, newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        rows = list(reader)
    if not rows:
        raise ValueError(f"Empty CSV: {csv_path}")
    versions = sorted({row.get("schema_version", "?") for row in rows})
    print(f"Read {len(rows)} samples. schema_version present: {', '.join(versions)}")
    return rows


def col_float(row, key, default=0.0):
    try:
        return float(row.get(key, default))
    except (TypeError, ValueError):
        return default


def col_int(row, key, default=0):
    try:
        return int(float(row.get(key, default)))
    except (TypeError, ValueError):
        return default


def has_columns(rows, *keys):
    return all(key in rows[0] for key in keys)


def bottleneck_stage(row):
    frame_gap_ms = col_float(row, "frame_gap_ms")
    queue_depth = col_int(row, "video_q_depth")
    if frame_gap_ms <= STALL_THRESHOLD_MS:
        return STAGE_NONE
    if queue_depth <= QUEUE_EMPTY_THRESHOLD:
        return STAGE_NETWORK
    if queue_depth >= QUEUE_FULL_THRESHOLD:
        return STAGE_PRESENTATION
    return STAGE_NONE


def plot_g1_timeline(ax, rows):
    if not has_columns(rows, "frame_gap_ms", "video_q_depth"):
        ax.set_visible(False)
        print("G1 skipped: frame_gap_ms/video_q_depth are absent in this schema.")
        return
    stages = [bottleneck_stage(r) for r in rows]
    for i, stage in enumerate(stages):
        ax.axvspan(i, i + 1, color=STAGE_COLORS[stage], linewidth=0)
    ax.set_xlim(0, len(stages))
    ax.set_yticks([])
    ax.set_title("G1 — Pipeline stage (bottleneck_stage)")


def plot_g2_histogram(ax, rows):
    bucket_keys = [f"hist_bucket_{i}" for i in range(8)]
    if not has_columns(rows, *bucket_keys):
        ax.set_visible(False)
        print("G2 skipped: hist_bucket_0..7 are absent in this schema (introduced in v5).")
        return
    last = rows[-1]  # cumulative counters — the last sample already holds the session total.
    counts = [col_int(last, k) for k in bucket_keys]
    ax.bar(HIST_BUCKET_LABELS, counts, color="#4A90D9")
    ax.set_title("G2 — Frame time histogram (ms, cumulative over the session)")
    ax.tick_params(axis="x", rotation=45)


def plot_g3_buffer_and_network(ax, rows):
    has_buffered = has_columns(rows, "buffered_ahead_sec", "net_mbs")
    has_legacy = has_columns(rows, "video_q_depth", "video_fps", "net_mbs")
    if not (has_buffered or has_legacy):
        ax.set_visible(False)
        print("G3 skipped: buffered_ahead_sec (or video_q_depth/video_fps) and net_mbs are absent in this schema.")
        return
    buffer_health = []
    net_mbs = []
    for r in rows:
        if has_buffered:
            buffer_health.append(col_float(r, "buffered_ahead_sec"))
        else:
            fps = col_float(r, "video_fps")
            q = col_int(r, "video_q_depth")
            buffer_health.append(q / fps if fps > 0 else 0.0)
        net_mbs.append(col_float(r, "net_mbs"))
    x = range(len(rows))
    ax.fill_between(x, buffer_health, color="#4CAF50", alpha=0.4, label="Buffer health (s)")
    ax2 = ax.twinx()
    ax2.plot(x, net_mbs, color="#B388FF", label="Network (MB/s)")
    ax.set_title("G3 — Buffer health (s) / Network (MB/s)")
    ax.legend(loc="upper left")
    ax2.legend(loc="upper right")


def plot_g4_av_drift(ax, rows):
    if not has_columns(rows, "av_drift_ms"):
        ax.set_visible(False)
        print("G4 skipped: av_drift_ms is absent in this schema.")
        return
    drift = [col_float(r, "av_drift_ms") for r in rows]
    x = range(len(rows))
    ax.plot(x, drift, color="#F44336")
    ax.axhline(0, color="gray", linewidth=1)
    ax.axhline(40, color="gray", linestyle="--", linewidth=1)
    ax.axhline(-40, color="gray", linestyle="--", linewidth=1)
    ax.set_title("G4 — A/V drift (ms), ±40ms band")


def plot_g5_gpu_vs_budget(ax, rows):
    if not has_columns(rows, "smoothed_gpu_time_ms", "refresh_rate"):
        ax.set_visible(False)
        print("G5 skipped: smoothed_gpu_time_ms/refresh_rate are absent in this schema.")
        return
    gpu_ms = [col_float(r, "smoothed_gpu_time_ms") for r in rows]
    x = range(len(rows))
    ax.plot(x, gpu_ms, color="#4A90D9", label="GPU time (ms)")
    refresh_rate = col_float(rows[-1], "refresh_rate", 90.0) or 90.0
    frame_interval_ms = 1000.0 / refresh_rate
    # The same gpu_budget_ms formula as the QualityController (rust/media-logic/src/quality.rs).
    budget_ms = frame_interval_ms * 0.85
    ax.axhline(budget_ms, color="#FF6B00", linestyle="--", label=f"budget ({budget_ms:.1f}ms)")
    ax.set_title("G5 — GPU time vs. budget (ms)")
    ax.legend()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv_path", help="Path of the session CSV (session-*.csv)")
    parser.add_argument("--out", help="Path of the output PNG (default: shows interactively)")
    args = parser.parse_args()

    rows = read_rows(args.csv_path)

    fig, axes = plt.subplots(5, 1, figsize=(10, 16))
    fig.suptitle(f"tucaVR — {args.csv_path}")
    plot_g1_timeline(axes[0], rows)
    plot_g2_histogram(axes[1], rows)
    plot_g3_buffer_and_network(axes[2], rows)
    plot_g4_av_drift(axes[3], rows)
    plot_g5_gpu_vs_budget(axes[4], rows)
    fig.tight_layout()

    if args.out:
        fig.savefig(args.out, dpi=150)
        print(f"Saved to {args.out}")
    else:
        plt.show()


if __name__ == "__main__":
    main()
