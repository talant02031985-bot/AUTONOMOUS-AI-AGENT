package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * AYANA Core Visualizer v2.5 — DENSE REFERENCE SPHERE.
 *
 * Visual-only replacement for the main AYANA core panel.
 *
 * Design target from the approved reference:
 * - dense luminous spherical energy body, not a hollow ring;
 * - many flowing plasma fibres crossing the whole sphere volume;
 * - bright central energy bloom behind the AYANA wordmark;
 * - expressive horizontal audio/signal band through the centre;
 * - irregular technical halo, segments and particles around the sphere;
 * - large AYANA wordmark inside the core;
 * - state palette: cyan / blue / violet / green / magenta / red-orange;
 * - all drawing is clamped inside this View.
 *
 * Integration contract is unchanged: package, class name and constructor stay
 * identical. No MainActivity, VoiceService, ORB, permission or dependency changes.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val white: Int,
        val deep: Int
    )

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val signalPath = Path()
    private val fineSignalPath = Path()
    private val fibrePath = Path()
    private val arcBounds = RectF()

    private var attached = false
    private var shaderWidth = -1
    private var shaderHeight = -1
    private var shaderState = ""

    private var ambientShader: RadialGradient? = null
    private var bodyShader: RadialGradient? = null
    private var centreShader: RadialGradient? = null
    private var signalShader: LinearGradient? = null

    private var palette = defaultPalette()

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        rebuildShaders(AyanaVoiceService.currentStatusState)
        postInvalidateDelayed(frameDelayMs(AyanaVoiceService.currentStatusState))
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderWidth = -1
        shaderHeight = -1
        rebuildShaders(AyanaVoiceService.currentStatusState)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val state = AyanaVoiceService.currentStatusState
        if (state != shaderState || width != shaderWidth || height != shaderHeight) {
            rebuildShaders(state)
        }

        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * 0.50f
        val compact = h < dp(180f)

        // The farthest particles/ticks use <= 1.105R. Leave a real containment margin.
        val inset = dp(if (compact) 7f else 11f)
        val safeHalfW = (w * 0.50f - inset).coerceAtLeast(dp(24f))
        val safeHalfH = (h * 0.50f - inset).coerceAtLeast(dp(24f))
        val contained = min(safeHalfW, safeHalfH) / 1.105f
        val preferred = h * if (compact) 0.405f else 0.415f
        val radius = min(preferred, contained).coerceAtLeast(dp(22f))

        val energy = stateEnergy(state)
        val time = now / 1000.0
        val breathe = (0.5 + 0.5 * sin(time * (0.86 + energy * 0.20))).toFloat()

        drawAmbient(canvas, cx, cy, radius, breathe)
        drawSignalBand(canvas, now, cy, radius, energy, compact)
        drawOuterTechHalo(canvas, now, cx, cy, radius, energy, compact)
        drawEnergyBody(canvas, cx, cy, radius, breathe)
        drawCentralBloom(canvas, cx, cy, radius, breathe)
        drawVolumeFibres(canvas, now, cx, cy, radius, energy, compact)
        drawPlasmaChords(canvas, now, cx, cy, radius, energy, compact)
        drawEnergyKnots(canvas, now, cx, cy, radius, energy, compact)
        drawAyanaWordmark(canvas, cx, cy, radius, compact)
        drawRingParticles(canvas, now, cx, cy, radius, energy, compact)

        if (attached) {
            postInvalidateDelayed(frameDelayMs(state))
        }
    }

    private fun drawAmbient(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fillPaint.shader = ambientShader
        fillPaint.alpha = (178 + breathe * 38f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius * 1.105f, fillPaint)
        fillPaint.shader = null
    }

    private fun drawSignalBand(
        canvas: Canvas,
        now: Long,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val left = dp(5f)
        val right = width.toFloat() - dp(5f)
        if (right <= left) return

        val span = right - left
        val phase = now / (390.0 - energy * 86.0)
        val barStep = if (compact) dp(3.8f) else dp(4.4f)
        val maxBar = radius * (0.26f + energy * 0.060f)

        // Dense spectral bars. Keep strongest energy near the sphere and its edges.
        strokePaint.shader = signalShader
        strokePaint.strokeWidth = dp(if (compact) 0.62f else 0.80f)
        var x = left
        var index = 0
        while (x <= right) {
            val u = ((x - left) / span).coerceIn(0f, 1f)
            val centre = (1f - abs(u - 0.5f) * 2f).coerceIn(0f, 1f)
            val sphereBand = (1f - abs(abs(u - 0.5f) - 0.23f) * 3.4f).coerceIn(0f, 1f)
            val wave = abs(
                sin(u * PI * 19.0 + phase * 0.78) * 0.48 +
                    sin(u * PI * 43.0 - phase * 1.14) * 0.31 +
                    sin(index * 0.93 + phase * 0.51) * 0.21
            ).toFloat()
            val envelope = 0.24f + 0.36f * centre + 0.40f * sphereBand
            val height = maxBar * (0.14f + wave * 0.86f) * envelope
            strokePaint.alpha = (58 + wave * 142f).toInt().coerceIn(0, 205)
            canvas.drawLine(x, cy - height, x, cy + height, strokePaint)
            x += barStep
            index++
        }

        signalPath.reset()
        fineSignalPath.reset()
        val samples = if (compact) 144 else 204
        val maxAmp = radius * (0.25f + energy * 0.067f)

        for (i in 0..samples) {
            val u = i.toFloat() / samples.toFloat()
            val px = left + span * u
            val centre = (1f - abs(u - 0.5f) * 2f).coerceIn(0f, 1f)
            val envelope = 0.30f + 0.70f * centre * centre

            val base =
                sin(u * PI * 15.0 + phase) * 0.42 +
                    sin(u * PI * 31.0 - phase * 1.31) * 0.27 +
                    sin(u * PI * 67.0 + phase * 0.61) * 0.14
            val needle = sin(u * PI * 111.0 - phase * 1.81) * 0.10
            val py = cy + (base + needle).toFloat() * maxAmp * envelope

            val fine =
                sin(u * PI * 41.0 - phase * 0.71) * 0.32 +
                    sin(u * PI * 83.0 + phase * 1.03) * 0.14
            val fy = cy + fine.toFloat() * maxAmp * envelope * 0.76f

            if (i == 0) {
                signalPath.moveTo(px, py)
                fineSignalPath.moveTo(px, fy)
            } else {
                signalPath.lineTo(px, py)
                fineSignalPath.lineTo(px, fy)
            }
        }

        glowPaint.shader = signalShader
        glowPaint.alpha = 78
        glowPaint.strokeWidth = dp(if (compact) 7.2f else 9.6f)
        canvas.drawPath(signalPath, glowPaint)

        strokePaint.shader = signalShader
        strokePaint.alpha = 255
        strokePaint.strokeWidth = dp(if (compact) 1.34f else 1.66f)
        canvas.drawPath(signalPath, strokePaint)

        strokePaint.alpha = 136
        strokePaint.strokeWidth = dp(if (compact) 0.60f else 0.76f)
        canvas.drawPath(fineSignalPath, strokePaint)

        strokePaint.alpha = 158
        strokePaint.strokeWidth = dp(0.58f)
        canvas.drawLine(left, cy, right, cy, strokePaint)

        strokePaint.shader = null
        glowPaint.shader = null
    }

    private fun drawOuterTechHalo(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val phase = continuousAngle(now, 15100f - energy * 1750f, false)
        val reverse = continuousAngle(now, 20300f - energy * 1950f, true)

        // Reference halo is bright but not a perfect mechanical bullseye.
        glowPaint.shader = null
        glowPaint.color = palette.primary
        glowPaint.alpha = 48
        glowPaint.strokeWidth = dp(if (compact) 7.2f else 9.0f)
        canvas.drawCircle(cx, cy, radius * 0.995f, glowPaint)

        strokePaint.shader = null
        strokePaint.color = palette.primary
        strokePaint.alpha = 206
        strokePaint.strokeWidth = dp(if (compact) 1.16f else 1.46f)
        canvas.drawCircle(cx, cy, radius * 0.995f, strokePaint)

        strokePaint.color = palette.secondary
        strokePaint.alpha = 72
        strokePaint.strokeWidth = dp(0.60f)
        canvas.drawCircle(cx, cy, radius * 1.052f, strokePaint)

        drawArcLayer(canvas, cx, cy, radius * 1.028f, phase + 9f, 36f, 34f, palette.white, 168, compact)
        drawArcLayer(canvas, cx, cy, radius * 1.073f, reverse + 93f, 24f, 42f, palette.primary, 124, compact)
        drawArcLayer(canvas, cx, cy, radius * 0.958f, phase + 197f, 46f, 35f, palette.secondary, 116, compact)

        // Irregular ticks rather than a uniform gear-like ring.
        val tickCount = if (compact) 46 else 62
        for (i in 0 until tickCount) {
            val jitter = sin(i * 2.17) * 0.026 + sin(i * 0.71) * 0.014
            val a = i * (PI * 2.0 / tickCount) + now / 21500.0 + jitter
            val emphasis = if (i % 9 == 0 || i % 13 == 0) 1f else 0f
            val r1 = radius * (1.018f + emphasis * 0.008f)
            val r2 = radius * (1.045f + emphasis * 0.028f)
            val x1 = cx + cos(a).toFloat() * r1
            val y1 = cy + sin(a).toFloat() * r1
            val x2 = cx + cos(a).toFloat() * r2
            val y2 = cy + sin(a).toFloat() * r2
            strokePaint.color = if (i % 6 == 0) palette.accent else palette.primary
            strokePaint.alpha = if (emphasis > 0f) 112 else 42
            strokePaint.strokeWidth = dp(if (emphasis > 0f) 0.74f else 0.44f)
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
    }

    private fun drawArcLayer(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        start: Float,
        sweep: Float,
        gap: Float,
        color: Int,
        alpha: Int,
        compact: Boolean
    ) {
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val secondStart = start + sweep + gap
        val secondSweep = sweep * 0.74f
        val thirdStart = secondStart + secondSweep + gap * 1.26f
        val thirdSweep = sweep * 0.38f

        glowPaint.color = color
        glowPaint.alpha = alpha / 5
        glowPaint.strokeWidth = dp(if (compact) 4.1f else 5.4f)
        canvas.drawArc(arcBounds, start, sweep, false, glowPaint)
        canvas.drawArc(arcBounds, secondStart, secondSweep, false, glowPaint)
        canvas.drawArc(arcBounds, thirdStart, thirdSweep, false, glowPaint)

        strokePaint.color = color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = dp(if (compact) 0.96f else 1.18f)
        canvas.drawArc(arcBounds, start, sweep, false, strokePaint)
        canvas.drawArc(arcBounds, secondStart, secondSweep, false, strokePaint)
        canvas.drawArc(arcBounds, thirdStart, thirdSweep, false, strokePaint)
    }

    private fun drawEnergyBody(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fillPaint.shader = bodyShader
        fillPaint.alpha = (230 + breathe * 22f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius * (0.910f + breathe * 0.008f), fillPaint)
        fillPaint.shader = null

        strokePaint.shader = null
        strokePaint.color = palette.primary
        strokePaint.alpha = 92
        strokePaint.strokeWidth = dp(0.76f)
        canvas.drawCircle(cx, cy, radius * 0.912f, strokePaint)
    }

    private fun drawCentralBloom(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        // Bright additive-looking bloom: no dark centre, no hollow donut.
        fillPaint.shader = centreShader
        fillPaint.alpha = (208 + breathe * 34f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius * (0.56f + breathe * 0.018f), fillPaint)
        fillPaint.shader = null

        pointPaint.color = palette.white
        pointPaint.alpha = (32 + breathe * 22f).toInt().coerceIn(0, 64)
        canvas.drawCircle(cx, cy, radius * 0.25f, pointPaint)

        strokePaint.color = palette.white
        strokePaint.alpha = 56
        strokePaint.strokeWidth = dp(0.66f)
        canvas.drawCircle(cx, cy, radius * 0.47f, strokePaint)

        strokePaint.color = palette.primary
        strokePaint.alpha = 52
        strokePaint.strokeWidth = dp(0.78f)
        canvas.drawCircle(cx, cy, radius * 0.59f, strokePaint)
    }

    private fun drawVolumeFibres(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val phase = now / (1650.0 - energy * 235.0)
        val count = if (compact) 24 else 32
        val samples = if (compact) 74 else 92

        // Lissajous-like plasma loops cross the middle of the sphere instead of
        // merely wrapping around its perimeter. This is the key v2.5 change.
        for (i in 0 until count) {
            val family = i % 4
            val layer = i.toFloat() / (count - 1).coerceAtLeast(1).toFloat()
            val rotation = i * 0.347 + sin(i * 0.81) * 0.42 + phase * (0.025 + (i % 5) * 0.004)
            val scale = radius * (0.66f + 0.20f * ((sin(i * 1.13) + 1.0) * 0.5).toFloat())
            val vertical = radius * (0.54f + 0.27f * ((cos(i * 0.97) + 1.0) * 0.5).toFloat())
            val drift = phase * (0.34 + (i % 6) * 0.039) + i * 0.59

            fibrePath.reset()
            for (s in 0..samples) {
                val t = s.toDouble() / samples.toDouble() * PI * 2.0

                val ex: Float
                val ey: Float
                when (family) {
                    0 -> {
                        ex = (sin(t + drift * 0.16) * scale).toFloat()
                        ey = (sin(t * 2.0 + drift) * vertical * 0.78).toFloat()
                    }
                    1 -> {
                        ex = (sin(t * 2.0 + drift * 0.72) * scale * 0.92).toFloat()
                        ey = (sin(t * 3.0 - drift * 0.43) * vertical * 0.70).toFloat()
                    }
                    2 -> {
                        ex = (cos(t) * scale * 0.96).toFloat()
                        ey = (sin(t * 3.0 + drift) * vertical * 0.62 + sin(t) * vertical * 0.28).toFloat()
                    }
                    else -> {
                        val radial = (0.70 + 0.16 * sin(t * 4.0 + drift)).toFloat()
                        ex = cos(t).toFloat() * scale * radial
                        ey = sin(t * 2.0 - drift * 0.37).toFloat() * vertical * 0.74f
                    }
                }

                val cr = cos(rotation).toFloat()
                val sr = sin(rotation).toFloat()
                val px = cx + ex * cr - ey * sr
                val py = cy + ex * sr + ey * cr

                if (s == 0) fibrePath.moveTo(px, py) else fibrePath.lineTo(px, py)
            }

            val color = when (i % 8) {
                0, 1, 2, 3 -> palette.primary
                4, 5 -> palette.secondary
                6 -> palette.accent
                else -> palette.white
            }

            if (i % 3 == 0) {
                glowPaint.shader = null
                glowPaint.color = color
                glowPaint.alpha = (22 + 18 * (1f - layer)).toInt().coerceIn(0, 48)
                glowPaint.strokeWidth = dp(if (compact) 3.8f else 5.0f)
                canvas.drawPath(fibrePath, glowPaint)
            }

            strokePaint.shader = null
            strokePaint.color = color
            strokePaint.alpha = (74 + 98 * (1f - abs(layer - 0.50f))).toInt().coerceIn(64, 184)
            strokePaint.strokeWidth = dp(if (compact) 0.62f else 0.78f)
            canvas.drawPath(fibrePath, strokePaint)
        }
    }

    private fun drawPlasmaChords(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val phase = now / (1420.0 - energy * 190.0)
        val chordCount = if (compact) 15 else 21
        val samples = if (compact) 54 else 66

        // Curved chords literally traverse the orb from edge to edge, filling the
        // centre with energy and eliminating the ring/donut appearance.
        for (i in 0 until chordCount) {
            val rotation = i * (PI / chordCount) + phase * (0.022 + (i % 4) * 0.003)
            val offset = radius * 0.30f * sin(i * 1.71 + phase * 0.13).toFloat()
            val amplitude = radius * (0.16f + 0.10f * ((sin(i * 0.91) + 1.0) * 0.5).toFloat())
            val frequency = 1.6 + (i % 4) * 0.37

            fibrePath.reset()
            for (s in 0..samples) {
                val p = s.toFloat() / samples.toFloat()
                val t = p * 2f - 1f
                val edge = sqrt((1f - t * t).coerceAtLeast(0f))
                val localX = t * radius * 0.82f
                val localY = offset * edge + sin(t * PI * frequency + phase + i * 0.47).toFloat() * amplitude * edge

                val cr = cos(rotation).toFloat()
                val sr = sin(rotation).toFloat()
                val px = cx + localX * cr - localY * sr
                val py = cy + localX * sr + localY * cr

                if (s == 0) fibrePath.moveTo(px, py) else fibrePath.lineTo(px, py)
            }

            val color = when (i % 6) {
                0 -> palette.white
                1, 2, 3 -> palette.primary
                4 -> palette.secondary
                else -> palette.accent
            }

            if (i % 4 == 0) {
                glowPaint.color = color
                glowPaint.alpha = 30
                glowPaint.strokeWidth = dp(if (compact) 4.6f else 6.0f)
                canvas.drawPath(fibrePath, glowPaint)
            }

            strokePaint.color = color
            strokePaint.alpha = if (i % 5 == 0) 188 else 132
            strokePaint.strokeWidth = dp(if (compact) 0.72f else 0.92f)
            canvas.drawPath(fibrePath, strokePaint)
        }
    }

    private fun drawEnergyKnots(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val spotCount = 6
        for (i in 0 until spotCount) {
            val a = now / (1500.0 - energy * 165.0) * (0.16 + i * 0.010) + i * PI / 3.0
            val rr = radius * (0.30f + 0.47f * ((sin(now / 980.0 + i * 0.73) + 1.0) * 0.5).toFloat())
            val x = cx + cos(a).toFloat() * rr
            val y = cy + sin(a).toFloat() * rr

            pointPaint.color = palette.white
            pointPaint.alpha = 236
            canvas.drawCircle(x, y, dp(if (compact) 1.18f else 1.52f), pointPaint)

            glowPaint.color = palette.white
            glowPaint.alpha = 48
            glowPaint.strokeWidth = dp(if (compact) 3.3f else 4.2f)
            canvas.drawCircle(x, y, dp(if (compact) 2.2f else 2.9f), glowPaint)

            if (i % 2 == 0) {
                strokePaint.color = palette.white
                strokePaint.alpha = 84
                strokePaint.strokeWidth = dp(0.50f)
                val arm = dp(if (compact) 3.7f else 4.8f)
                canvas.drawLine(x - arm, y, x + arm, y, strokePaint)
                canvas.drawLine(x, y - arm, x, y + arm, strokePaint)
            }
        }
    }

    private fun drawAyanaWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        compact: Boolean
    ) {
        val textSize = min(
            radius * if (compact) 0.39f else 0.42f,
            dp(if (compact) 28f else 36f)
        )

        textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        textPaint.textSize = textSize
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) * 0.5f

        // Coloured bloom around the letters.
        textPaint.textSize = textSize * 1.075f
        textPaint.color = palette.primary
        textPaint.alpha = 86
        canvas.drawText("AYANA", cx, baseline, textPaint)

        textPaint.textSize = textSize
        textPaint.color = palette.white
        textPaint.alpha = 255
        canvas.drawText("AYANA", cx, baseline, textPaint)
    }

    private fun drawRingParticles(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val count = if (compact) 42 else 58
        val phase = now / (2320.0 - energy * 245.0)

        for (i in 0 until count) {
            val a = i * (PI * 2.0 / count) + phase * (if (i % 2 == 0) 0.032 else -0.020)
            val pulse = (0.5 + 0.5 * sin(phase + i * 1.43)).toFloat()
            val radialNoise = ((sin(i * 0.83) + sin(i * 2.27) * 0.36 + 1.36) / 2.72).toFloat()
            val rr = radius * (0.952f + 0.135f * radialNoise)
            val x = cx + cos(a).toFloat() * rr
            val y = cy + sin(a).toFloat() * rr

            pointPaint.color = when (i % 7) {
                0 -> palette.white
                1, 2, 3, 4 -> palette.primary
                5 -> palette.secondary
                else -> palette.accent
            }
            pointPaint.alpha = (52 + pulse * 168f).toInt().coerceIn(0, 224)
            canvas.drawCircle(x, y, dp(0.40f + pulse * 0.62f), pointPaint)
        }
    }

    private fun rebuildShaders(state: String) {
        if (width <= 0 || height <= 0) return

        shaderWidth = width
        shaderHeight = height
        shaderState = state
        palette = paletteFor(state)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * 0.50f
        val minSide = min(w, h)

        ambientShader = RadialGradient(
            cx,
            cy,
            minSide * 0.49f,
            intArrayOf(
                withAlpha(palette.white, 24),
                withAlpha(palette.primary, 62),
                withAlpha(palette.secondary, 34),
                withAlpha(palette.accent, 16),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.34f, 0.60f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )

        // IMPORTANT: centre is luminous, not dark. The deep tone only appears near
        // the outer edge so the body reads as a glowing sphere rather than a hole.
        bodyShader = RadialGradient(
            cx - minSide * 0.018f,
            cy - minSide * 0.024f,
            minSide * 0.37f,
            intArrayOf(
                withAlpha(palette.white, 214),
                withAlpha(palette.primary, 206),
                withAlpha(palette.primary, 172),
                withAlpha(palette.secondary, 132),
                withAlpha(palette.deep, 82),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.12f, 0.31f, 0.56f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )

        centreShader = RadialGradient(
            cx,
            cy,
            minSide * 0.245f,
            intArrayOf(
                withAlpha(palette.white, 246),
                withAlpha(palette.white, 216),
                withAlpha(palette.primary, 190),
                withAlpha(palette.secondary, 106),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.16f, 0.38f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )

        signalShader = LinearGradient(
            w * 0.01f,
            cy,
            w * 0.99f,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(palette.primary, 194),
                palette.primary,
                palette.white,
                palette.white,
                palette.secondary,
                withAlpha(palette.primary, 194),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.08f, 0.25f, 0.46f, 0.54f, 0.75f, 0.92f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun paletteFor(state: String): Palette {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND -> Palette(
                primary = Color.parseColor("#2E8CFF"),
                secondary = Color.parseColor("#1268FF"),
                accent = Color.parseColor("#78C8FF"),
                white = Color.parseColor("#F8FCFF"),
                deep = Color.parseColor("#030A1B")
            )

            AyanaVoiceService.STATE_THINKING -> Palette(
                primary = Color.parseColor("#6D55FF"),
                secondary = Color.parseColor("#3E47FF"),
                accent = Color.parseColor("#A66CFF"),
                white = Color.parseColor("#FBFAFF"),
                deep = Color.parseColor("#0D0624")
            )

            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SUCCESS -> Palette(
                primary = Color.parseColor("#20F48A"),
                secondary = Color.parseColor("#00CFA0"),
                accent = Color.parseColor("#70FFB6"),
                white = Color.parseColor("#F7FFFB"),
                deep = Color.parseColor("#031B12")
            )

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT -> Palette(
                primary = Color.parseColor("#FF29D7"),
                secondary = Color.parseColor("#B52CFF"),
                accent = Color.parseColor("#FF79E9"),
                white = Color.parseColor("#FFF9FE"),
                deep = Color.parseColor("#1C0319")
            )

            AyanaVoiceService.STATE_ERROR,
            AyanaVoiceService.STATE_BLOCKED,
            AyanaVoiceService.STATE_STOPPED -> Palette(
                primary = Color.parseColor("#FF2A35"),
                secondary = Color.parseColor("#FF5B20"),
                accent = Color.parseColor("#FF9A22"),
                white = Color.parseColor("#FFF8F4"),
                deep = Color.parseColor("#230305")
            )

            AyanaVoiceService.STATE_CANCELLED -> Palette(
                primary = Color.parseColor("#FF7B26"),
                secondary = Color.parseColor("#FF4040"),
                accent = Color.parseColor("#FFC14A"),
                white = Color.parseColor("#FFF9F3"),
                deep = Color.parseColor("#251006")
            )

            else -> defaultPalette()
        }
    }

    private fun defaultPalette(): Palette = Palette(
        primary = Color.parseColor("#00F5F2"),
        secondary = Color.parseColor("#00B7D8"),
        accent = Color.parseColor("#63FFF5"),
        white = Color.parseColor("#F7FFFF"),
        deep = Color.parseColor("#031619")
    )

    private fun stateEnergy(state: String): Float {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND -> 0.76f
            AyanaVoiceService.STATE_THINKING -> 0.83f
            AyanaVoiceService.STATE_EXECUTING -> 0.96f
            AyanaVoiceService.STATE_SPEAKING -> 0.88f
            AyanaVoiceService.STATE_TEXT -> 0.74f
            AyanaVoiceService.STATE_LISTENING -> 0.64f
            AyanaVoiceService.STATE_SUCCESS -> 0.54f
            AyanaVoiceService.STATE_ERROR -> 0.67f
            AyanaVoiceService.STATE_BLOCKED -> 0.50f
            AyanaVoiceService.STATE_CANCELLED -> 0.42f
            AyanaVoiceService.STATE_STOPPED -> 0.34f
            else -> 0.58f
        }
    }

    private fun continuousAngle(now: Long, cycleMs: Float, reverse: Boolean): Float {
        val safeCycle = cycleMs.coerceAtLeast(900f)
        val fraction = (now % safeCycle.toLong()).toFloat() / safeCycle
        val angle = fraction * 360f
        return if (reverse) -angle else angle
    }

    private fun frameDelayMs(state: String): Long {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SPEAKING -> 30L

            AyanaVoiceService.STATE_LISTENING -> 34L
            else -> 42L
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(value: Float): Float = value * density
}
