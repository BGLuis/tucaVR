#!/usr/bin/env python3
"""Regenera no PC os 5 gráficos do painel de estatísticas (F5/F6 de
docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md) a partir de um CSV de telemetria
versionado, sem precisar do headset — mesmos dados, mesmas 5 visões
(app/src/main/java/com/tucavr/designsystem/VoidChart.kt), pra triagem offline.

Uso:
    python3 scripts/plot-session.py caminho/para/session-XXXX.csv [--out saida.png]

Requer: matplotlib (pip install matplotlib). Nenhuma outra dependência — lê o CSV
com o módulo padrão `csv`, não `pandas`, de propósito (script standalone, sem
depender do resto do toolchain do projeto).

D-05/schema_version: cada linha do CSV carrega a versão do schema na 1ª coluna
(ver DebugTelemetryExporter.SCHEMA_VERSION). Colunas que não existiam em versões
antigas (ex.: v1 não tem video_stall_count, introduzido em v2) simplesmente
resultam no gráfico correspondente sendo pulado com um aviso — nunca um crash.
"""
import argparse
import csv
import sys

try:
    import matplotlib.pyplot as plt
except ImportError:
    print("Erro: matplotlib não instalado. Rode: pip install matplotlib", file=sys.stderr)
    sys.exit(1)

# Mesmos limiares/bordas usados nativamente — ver BottleneckStageAnalyzer.kt e
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
        raise ValueError(f"CSV vazio: {csv_path}")
    versions = sorted({row.get("schema_version", "?") for row in rows})
    print(f"Lidas {len(rows)} amostras. schema_version presente(s): {', '.join(versions)}")
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
        print("G1 pulado: frame_gap_ms/video_q_depth ausentes neste schema.")
        return
    stages = [bottleneck_stage(r) for r in rows]
    for i, stage in enumerate(stages):
        ax.axvspan(i, i + 1, color=STAGE_COLORS[stage], linewidth=0)
    ax.set_xlim(0, len(stages))
    ax.set_yticks([])
    ax.set_title("G1 — Estágio do pipeline (bottleneck_stage)")


def plot_g2_histogram(ax, rows):
    bucket_keys = [f"hist_bucket_{i}" for i in range(8)]
    if not has_columns(rows, *bucket_keys):
        ax.set_visible(False)
        print("G2 pulado: hist_bucket_0..7 ausentes neste schema (introduzidos em v5).")
        return
    last = rows[-1]  # contadores cumulativos — a última amostra já tem o total da sessão.
    counts = [col_int(last, k) for k in bucket_keys]
    ax.bar(HIST_BUCKET_LABELS, counts, color="#4A90D9")
    ax.set_title("G2 — Histograma de frame time (ms, cumulativo da sessão)")
    ax.tick_params(axis="x", rotation=45)


def plot_g3_buffer_and_network(ax, rows):
    if not has_columns(rows, "video_q_depth", "video_fps", "net_mbs"):
        ax.set_visible(False)
        print("G3 pulado: video_q_depth/video_fps/net_mbs ausentes neste schema.")
        return
    buffer_health = []
    net_mbs = []
    for r in rows:
        fps = col_float(r, "video_fps")
        q = col_int(r, "video_q_depth")
        buffer_health.append(q / fps if fps > 0 else 0.0)
        net_mbs.append(col_float(r, "net_mbs"))
    x = range(len(rows))
    ax.fill_between(x, buffer_health, color="#4CAF50", alpha=0.4, label="Buffer health (s)")
    ax2 = ax.twinx()
    ax2.plot(x, net_mbs, color="#B388FF", label="Rede (MB/s)")
    ax.set_title("G3 — Saúde do buffer (s) / Rede (MB/s)")
    ax.legend(loc="upper left")
    ax2.legend(loc="upper right")


def plot_g4_av_drift(ax, rows):
    if not has_columns(rows, "av_drift_ms"):
        ax.set_visible(False)
        print("G4 pulado: av_drift_ms ausente neste schema.")
        return
    drift = [col_float(r, "av_drift_ms") for r in rows]
    x = range(len(rows))
    ax.plot(x, drift, color="#F44336")
    ax.axhline(0, color="gray", linewidth=1)
    ax.axhline(40, color="gray", linestyle="--", linewidth=1)
    ax.axhline(-40, color="gray", linestyle="--", linewidth=1)
    ax.set_title("G4 — Drift A/V (ms), banda ±40ms")


def plot_g5_gpu_vs_budget(ax, rows):
    if not has_columns(rows, "smoothed_gpu_time_ms", "refresh_rate"):
        ax.set_visible(False)
        print("G5 pulado: smoothed_gpu_time_ms/refresh_rate ausentes neste schema.")
        return
    gpu_ms = [col_float(r, "smoothed_gpu_time_ms") for r in rows]
    x = range(len(rows))
    ax.plot(x, gpu_ms, color="#4A90D9", label="GPU time (ms)")
    refresh_rate = col_float(rows[-1], "refresh_rate", 90.0) or 90.0
    frame_interval_ms = 1000.0 / refresh_rate
    # Mesma formula de gpu_budget_ms do QualityController (rust/media-logic/src/quality.rs).
    budget_ms = frame_interval_ms * 0.85
    ax.axhline(budget_ms, color="#FF6B00", linestyle="--", label=f"budget ({budget_ms:.1f}ms)")
    ax.set_title("G5 — GPU time vs. orçamento (ms)")
    ax.legend()


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("csv_path", help="Caminho do CSV de sessão (session-*.csv)")
    parser.add_argument("--out", help="Caminho do PNG de saída (padrão: mostra interativamente)")
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
        print(f"Salvo em {args.out}")
    else:
        plt.show()


if __name__ == "__main__":
    main()
