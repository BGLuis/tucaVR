package com.tucavr.designsystem

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.tucavr.debug.BottleneckStage
import kotlin.math.max
import kotlin.math.min

/**
 * F5 (docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md, seção 2.5): primeiro `View` com `onDraw`
 * customizado do projeto — nenhum existia antes desta fase. Renderiza os 5 gráficos (G1-G5)
 * via composição de [ChartSeries]/[ChartMarker]/[TimelineSample] em vez de 5 classes bespoke,
 * já que os 5 casos se reduzem a: linha, área, barras (histograma) ou faixa colorida por
 * estado (timeline) — todos sobre o mesmo eixo X (tempo ou índice de bucket) e eixo Y (valor).
 *
 * IMPORTANTE (pitfall registrado no relatório, seção 4): o quad do modal é 1024×768px
 * projetando um painel de 760×580dp — traços de 1px desaparecem nessa densidade. Todo
 * dimensionamento aqui usa dp (via [VoidTheme.dp]) com espessura mínima de 2dp, mas a
 * legibilidade real só pode ser confirmada no headset (não verificado nesta sessão — ver
 * docs/reports/TRIAGEM-TELEMETRIA-E-GRAFICOS.md seção 5).
 */
enum class SeriesStyle { LINE, AREA, BARS }

data class ChartSeries(
    val points: List<Float>,
    val color: Int,
    val style: SeriesStyle,
    /** Se null, usa [VoidChart.yMin]/[VoidChart.yMax] (série compartilha a escala do gráfico).
     * Setar aqui normaliza ESTA série à própria faixa — necessário quando duas séries de
     * unidades diferentes dividem o mesmo gráfico (G3: segundos de buffer vs. MB/s). */
    val minValueOverride: Float? = null,
    val maxValueOverride: Float? = null
)

data class ChartMarker(val value: Float, val label: String, val color: Int)

/** G1: uma amostra da linha do tempo, já resolvida para uma cor (ver [BottleneckStage]). */
data class TimelineSample(val stage: BottleneckStage)

class VoidChart @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        val COLOR_HEALTHY = Color.parseColor("#4CAF50")
        val COLOR_NETWORK = Color.parseColor("#4A90D9")
        val COLOR_PRESENTATION = Color.parseColor("#F44336")
        val COLOR_SECONDARY_LINE = Color.parseColor("#B388FF")
        val COLOR_GRID = Color.parseColor("#3A3A3A")
    }

    /** G2, G3, G4, G5 — séries desenhadas sobre o mesmo eixo Y (0..[yMax]). Vazio = nada. */
    var series: List<ChartSeries> = emptyList()
        set(value) { field = value; invalidate() }

    /** Linhas horizontais de referência (G2: 11.1/20/250ms; G4: banda ±40ms; G5: orçamento). */
    var markers: List<ChartMarker> = emptyList()
        set(value) { field = value; invalidate() }

    /** G1 — quando não-nulo, desenha faixas coloridas por estado em vez de séries/eixo Y. */
    var timeline: List<TimelineSample>? = null
        set(value) { field = value; invalidate() }

    /** Teto do eixo Y para todas as séries (ignorado no modo timeline). */
    var yMax: Float = 100f
        set(value) { field = value; invalidate() }

    /** Zero simétrico (G4: drift pode ser negativo) — desenha uma linha de referência em 0. */
    var showZeroLine: Boolean = false
        set(value) { field = value; invalidate() }
    var yMin: Float = 0f
        set(value) { field = value; invalidate() }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = VoidTheme.dp(context, 2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = VoidTheme.dp(context, 1f)
        color = COLOR_GRID
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = VoidTheme.dp(context, 1.5f)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(VoidTheme.dp(context, 4f), VoidTheme.dp(context, 4f)), 0f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = VoidTheme.colorTextSecondary
        textSize = VoidTheme.dp(context, 10f)
        typeface = VoidTheme.typefaceMono
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val tl = timeline
        if (tl != null) {
            drawTimeline(canvas, tl, w, h)
            return
        }

        drawGrid(canvas, w, h)
        drawSeries(canvas, w, h)
        drawMarkers(canvas, w, h)
    }

    private fun valueToY(value: Float, h: Float, min: Float = yMin, max: Float = yMax): Float {
        val range = (max - min).takeIf { it > 0f } ?: 1f
        val clamped = value.coerceIn(min, max)
        return h - ((clamped - min) / range) * h
    }

    private fun drawGrid(canvas: Canvas, w: Float, h: Float) {
        // 4 linhas horizontais de referência (25/50/75/100%) — sem rótulo, só orientação visual.
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (showZeroLine && yMin < 0f && yMax > 0f) {
            val zeroY = valueToY(0f, h)
            canvas.drawLine(0f, zeroY, w, zeroY, gridPaint)
        }
    }

    private fun drawSeries(canvas: Canvas, w: Float, h: Float) {
        for (s in series) {
            if (s.points.isEmpty()) continue
            when (s.style) {
                SeriesStyle.BARS -> drawBars(canvas, s, w, h)
                SeriesStyle.AREA -> drawLineOrArea(canvas, s, w, h, filled = true)
                SeriesStyle.LINE -> drawLineOrArea(canvas, s, w, h, filled = false)
            }
        }
    }

    private fun drawBars(canvas: Canvas, s: ChartSeries, w: Float, h: Float) {
        val localMax = s.maxValueOverride ?: (s.points.maxOrNull()?.takeIf { it > 0f } ?: 1f)
        val n = s.points.size
        val slot = w / n
        val barWidth = slot * 0.7f
        fillPaint.color = s.color
        for (i in 0 until n) {
            val v = s.points[i]
            val barH = if (localMax > 0f) (v / localMax) * h else 0f
            val left = i * slot + (slot - barWidth) / 2f
            canvas.drawRect(left, h - barH, left + barWidth, h, fillPaint)
        }
    }

    private fun drawLineOrArea(canvas: Canvas, s: ChartSeries, w: Float, h: Float, filled: Boolean) {
        val n = s.points.size
        if (n < 2) return
        val seriesMin = s.minValueOverride ?: yMin
        val seriesMax = s.maxValueOverride ?: yMax
        val stepX = w / (n - 1)
        val path = Path()
        for (i in 0 until n) {
            val x = i * stepX
            val y = valueToY(s.points[i], h, seriesMin, seriesMax)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        if (filled) {
            val areaPath = Path(path)
            areaPath.lineTo((n - 1) * stepX, h)
            areaPath.lineTo(0f, h)
            areaPath.close()
            fillPaint.color = Color.argb(70, Color.red(s.color), Color.green(s.color), Color.blue(s.color))
            canvas.drawPath(areaPath, fillPaint)
        }
        linePaint.color = s.color
        canvas.drawPath(path, linePaint)
    }

    private fun drawMarkers(canvas: Canvas, w: Float, h: Float) {
        for (m in markers) {
            val y = valueToY(m.value, h)
            markerPaint.color = m.color
            canvas.drawLine(0f, y, w, y, markerPaint)
            canvas.drawText(m.label, VoidTheme.dp(context, 4f), y - VoidTheme.dp(context, 2f), labelPaint)
        }
    }

    private fun drawTimeline(canvas: Canvas, samples: List<TimelineSample>, w: Float, h: Float) {
        if (samples.isEmpty()) return
        val slot = w / samples.size
        for (i in samples.indices) {
            fillPaint.color = when (samples[i].stage) {
                BottleneckStage.NONE -> COLOR_HEALTHY
                BottleneckStage.NETWORK -> COLOR_NETWORK
                BottleneckStage.PRESENTATION -> COLOR_PRESENTATION
            }
            canvas.drawRect(i * slot, 0f, (i + 1) * slot + 1f, h, fillPaint)
        }
    }

    private fun Float.coerceIn(minVal: Float, maxVal: Float): Float = max(minVal, min(maxVal, this))
}
