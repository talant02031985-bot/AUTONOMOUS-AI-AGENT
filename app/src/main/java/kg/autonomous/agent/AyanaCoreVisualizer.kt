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

/**
 * AYANA Core Visualizer v2.4 — REFERENCE MATCH.
 *
 * Visual-only replacement for the main AYANA core panel.
 *
 * Goal: reproduce the supplied six-state reference as closely as possible with
 * Android Canvas primitives:
 * - dense luminous spherical energy body;
 * - smooth woven orbital fibres (NO radial spikes / broken fan);
 * - strong complete outer neon ring with small technical segments/particles;
 * - bright horizontal audio/signal band through the centre;
 * - large AYANA wordmark inside the core;
 * - state palette: cyan / blue / violet / green / magenta / red-orange;
 * - all drawing remains inside this View.
 *
 * Integration contract is unchanged: class name, constructor and package stay
 * identical; no MainActivity, VoiceService, ORB, permission or dependency changes.
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

        // Largest painted element is ~1.11R. Keep a real safety margin around it.
        val inset = dp(if (compact) 7f else 11f)
        val safeHalfW = (w * 0.50f - inset).coerceAtLeast(dp(24f))
        val safeHalfH = (h * 0.50f - inset).coerceAtLeast(dp(24f))
        val contained = min(safeHalfW, safeHalfH) / 1.11f
        val preferred = h * if (compact) 0.405f else 0.415f
        val radius = min(preferred, contained).coerceAtLeast(dp(22f))

        val energy = stateEnergy(state)
        val time = now / 1000.0
        val breathe = (0.5 + 0.5 * sin(time * (0.88 + energy * 0.22))).toFloat()

        // Order matters: reference has the waveform behind/through the sphere,
        // then a bright outer ring, then dense fibres and wordmark on top.
        drawAmbient(canvas, cx, cy, radius, breathe)
        drawSignalBand(canvas, now, cy, radius, energy, compact)
        drawOuterTechRing(canvas, now, cx, cy, radius, energy, compact)
        drawEnergyBody(canvas, cx, cy, radius, breathe)
        drawFlowingFibres(canvas, now, cx, cy, radius, energy, compact)
        drawInnerHighlights(canvas, now, cx, cy, radius, energy, compact)
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
        fillPaint.alpha = (190 + breathe * 42f).toInt().coerceIn(0, 255)
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
        val left = dp(6f)
        val right = width.toFloat() - dp(6f)
        if (right <= left) return

        val span = right - left
        val phase = now / (410.0 - energy * 92.0)
        val barStep = if (compact) dp(4.0f) else dp(4.8f)
        val maxBar = radius * (0.25f + energy * 0.055f)

        // Spectral vertical bars are a major visual feature of the reference.
        strokePaint.shader = signalShader
        strokePaint.strokeWidth = dp(if (compact) 0.62f else 0.78f)
        var x = left
        var index = 0
        while (x <= right) {
            val u = ((x - left) / span).coerceIn(0f, 1f)
            val centre = (1f - abs(u - 0.5f) * 2f).coerceIn(0f, 1f)
            val outsideBoost = 0.42f + 0.58f * (1f - centre * 0.30f)
            val wave = abs(
                sin(u * PI * 19.0 + phase * 0.72) * 0.52 +
                    sin(u * PI * 47.0 - phase * 1.17) * 0.31 +
                    sin(index * 0.89 + phase * 0.44) * 0.17
            ).toFloat()
            val height = maxBar * (0.12f + wave * 0.88f) * outsideBoost
            strokePaint.alpha = (45 + wave * 115f).toInt().coerceIn(0, 170)
            canvas.drawLine(x, cy - height, x, cy + height, strokePaint)
            x += barStep
            index++
        }

        signalPath.reset()
        fineSignalPath.reset()
        val samples = if (compact) 138 else 188
        val maxAmp = radius * (0.23f + energy * 0.06f)

        for (i in 0..samples) {
            val u = i.toFloat() / samples.toFloat()
            val px = left + span * u
            val centre = (1f - abs(u - 0.5f) * 2f).coerceIn(0f, 1f)
            val envelope = 0.28f + 0.72f * centre * centre

            val base =
                sin(u * PI * 15.0 + phase) * 0.40 +
                    sin(u * PI * 31.0 - phase * 1.29) * 0.25 +
                    sin(u * PI * 63.0 + phase * 0.58) * 0.13
            val needle = sin(u * PI * 103.0 - phase * 1.77) * 0.11
            val py = cy + (base + needle).toFloat() * maxAmp * envelope

            val fine =
                sin(u * PI * 39.0 - phase * 0.68) * 0.31 +
                    sin(u * PI * 79.0 + phase * 1.06) * 0.13
            val fy = cy + fine.toFloat() * maxAmp * envelope * 0.72f

            if (i == 0) {
                signalPath.moveTo(px, py)
                fineSignalPath.moveTo(px, fy)
            } else {
                signalPath.lineTo(px, py)
                fineSignalPath.lineTo(px, fy)
            }
        }

        // Wide low-alpha pass = optical glow without BlurMaskFilter.
        glowPaint.shader = signalShader
        glowPaint.alpha = 62
        glowPaint.strokeWidth = dp(if (compact) 6.2f else 8.4f)
        canvas.drawPath(signalPath, glowPaint)

        strokePaint.shader = signalShader
        strokePaint.alpha = 245
        strokePaint.strokeWidth = dp(if (compact) 1.18f else 1.48f)
        canvas.drawPath(signalPath, strokePaint)

        strokePaint.alpha = 112
        strokePaint.strokeWidth = dp(if (compact) 0.54f else 0.70f)
        canvas.drawPath(fineSignalPath, strokePaint)

        // Bright centre baseline.
        strokePaint.alpha = 120
        strokePaint.strokeWidth = dp(0.52f)
        canvas.drawLine(left, cy, right, cy, strokePaint)

        strokePaint.shader = null
        glowPaint.shader = null
    }

    private fun drawOuterTechRing(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val phase = continuousAngle(now, 14200f - energy * 1900f, false)
        val reverse = continuousAngle(now, 19100f - energy * 2100f, true)

        // Complete glowing halo — unlike v2.3 this never reads as a broken fan.
        glowPaint.shader = null
        glowPaint.color = palette.primary
        glowPaint.alpha = 50
        glowPaint.strokeWidth = dp(if (compact) 8.0f else 10.0f)
        canvas.drawCircle(cx, cy, radius * 1.005f, glowPaint)

        strokePaint.shader = null
        strokePaint.color = palette.primary
        strokePaint.alpha = 222
        strokePaint.strokeWidth = dp(if (compact) 1.25f else 1.55f)
        canvas.drawCircle(cx, cy, radius * 1.005f, strokePaint)

        strokePaint.color = palette.secondary
        strokePaint.alpha = 102
        strokePaint.strokeWidth = dp(0.70f)
        canvas.drawCircle(cx, cy, radius * 1.055f, strokePaint)

        strokePaint.color = palette.accent
        strokePaint.alpha = 62
        strokePaint.strokeWidth = dp(0.48f)
        canvas.drawCircle(cx, cy, radius * 1.090f, strokePaint)

        // Several rotating technical arc layers.
        drawArcLayer(canvas, cx, cy, radius * 1.040f, phase + 8f, 44f, 26f, palette.white, 170, compact)
        drawArcLayer(canvas, cx, cy, radius * 1.072f, reverse + 91f, 31f, 33f, palette.primary, 130, compact)
        drawArcLayer(canvas, cx, cy, radius * 0.965f, phase + 191f, 52f, 29f, palette.secondary, 136, compact)

        // Radial micro-ticks around the ring.
        val tickCount = if (compact) 58 else 76
        for (i in 0 until tickCount) {
            val a = i * (PI * 2.0 / tickCount) + now / 19000.0
            val emphasis = if (i % 7 == 0) 1f else 0f
            val r1 = radius * (1.025f + emphasis * 0.010f)
            val r2 = radius * (1.065f + emphasis * 0.020f)
            val x1 = cx + cos(a).toFloat() * r1
            val y1 = cy + sin(a).toFloat() * r1
            val x2 = cx + cos(a).toFloat() * r2
            val y2 = cy + sin(a).toFloat() * r2
            strokePaint.color = if (i % 5 == 0) palette.accent else palette.primary
            strokePaint.alpha = if (i % 7 == 0) 118 else 54
            strokePaint.strokeWidth = dp(if (emphasis > 0f) 0.72f else 0.46f)
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
        val secondSweep = sweep * 0.72f
        val thirdStart = secondStart + secondSweep + gap * 1.18f
        val thirdSweep = sweep * 0.42f

        glowPaint.color = color
        glowPaint.alpha = alpha / 5
        glowPaint.strokeWidth = dp(if (compact) 4.4f else 5.8f)
        canvas.drawArc(arcBounds, start, sweep, false, glowPaint)
        canvas.drawArc(arcBounds, secondStart, secondSweep, false, glowPaint)
        canvas.drawArc(arcBounds, thirdStart, thirdSweep, false, glowPaint)

        strokePaint.color = color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = dp(if (compact) 1.0f else 1.24f)
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
        fillPaint.alpha = (220 + breathe * 28f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius * (0.905f + breathe * 0.010f), fillPaint)
        fillPaint.shader = null

        // Thin body boundary gives the sphere the crisp rim visible in reference.
        strokePaint.shader = null
        strokePaint.color = palette.primary
        strokePaint.alpha = 106
        strokePaint.strokeWidth = dp(0.82f)
        canvas.drawCircle(cx, cy, radius * 0.905f, strokePaint)
    }

    private fun drawFlowingFibres(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val phase = now / (1700.0 - energy * 250.0)
        val count = if (compact) 28 else 38
        val samples = if (compact) 66 else 82

        // Smooth closed, rotated, wobbling ellipses create the reference's
        // woven-plasma appearance. There are deliberately NO radial spokes.
        for (i in 0 until count) {
            val layer = i.toFloat() / (count - 1).coerceAtLeast(1).toFloat()
            val rotation = i * 0.438 + sin(i * 0.73) * 0.34 + phase * (0.035 + (i % 4) * 0.006)
            val rx = radius * (0.58f + 0.30f * ((sin(i * 1.17) + 1.0) * 0.5).toFloat())
            val ry = radius * (0.52f + 0.31f * ((cos(i * 0.91) + 1.0) * 0.5).toFloat())
            val wobbleAmount = radius * (0.010f + 0.022f * ((sin(i * 1.31) + 1.0) * 0.5).toFloat())
            val drift = phase * (0.31 + (i % 5) * 0.043) + i * 0.57

            fibrePath.reset()
            var firstX = 0f
            var firstY = 0f
            for (s in 0..samples) {
                val t = s.toDouble() / samples.toDouble() * PI * 2.0
                val wobble = sin(t * (2.0 + (i % 3)) + drift) * wobbleAmount
                val ex = cos(t).toFloat() * (rx + wobble.toFloat())
                val ey = sin(t).toFloat() * (ry - wobble.toFloat() * 0.42f)

                // Slight nonlinear shear makes loops organic rather than CAD-perfect.
                val shear = sin(t * 3.0 + drift * 0.7).toFloat() * radius * 0.018f
                val localX = ex + shear
                val localY = ey + cos(t * 2.0 - drift).toFloat() * radius * 0.012f

                val cr = cos(rotation).toFloat()
                val sr = sin(rotation).toFloat()
                val px = cx + localX * cr - localY * sr
                val py = cy + localX * sr + localY * cr

                if (s == 0) {
                    firstX = px
                    firstY = py
                    fibrePath.moveTo(px, py)
                } else {
                    fibrePath.lineTo(px, py)
                }
            }
            fibrePath.lineTo(firstX, firstY)

            val color = when (i % 7) {
                0, 1, 2 -> palette.primary
                3, 4 -> palette.secondary
                5 -> palette.accent
                else -> palette.white
            }

            // Every few fibres get a soft glow pass; this keeps performance sane.
            if (i % 3 == 0) {
                glowPaint.shader = null
                glowPaint.color = color
                glowPaint.alpha = (18 + 18 * (1f - layer)).toInt()
                glowPaint.strokeWidth = dp(if (compact) 3.6f else 4.8f)
                canvas.drawPath(fibrePath, glowPaint)
            }

            strokePaint.shader = null
            strokePaint.color = color
            strokePaint.alpha = (68 + 92 * (1f - abs(layer - 0.48f))).toInt().coerceIn(50, 176)
            strokePaint.strokeWidth = dp(if (compact) 0.62f else 0.78f)
            canvas.drawPath(fibrePath, strokePaint)
        }

        // A few brighter broad sweeps visually bind the many fine loops together.
        drawEllipticSweep(canvas, now, cx, cy, radius * 0.80f, radius * 0.57f, 0.18f, palette.white, 105, compact)
        drawEllipticSweep(canvas, now, cx, cy, radius * 0.75f, radius * 0.67f, 1.12f, palette.primary, 138, compact)
        drawEllipticSweep(canvas, now, cx, cy, radius * 0.67f, radius * 0.80f, 2.18f, palette.secondary, 116, compact)
    }

    private fun drawEllipticSweep(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        baseRotation: Float,
        color: Int,
        alpha: Int,
        compact: Boolean
    ) {
        val rotation = baseRotation + now / 17000.0
        val samples = 88
        fibrePath.reset()
        var firstX = 0f
        var firstY = 0f
        for (s in 0..samples) {
            val t = s.toDouble() / samples.toDouble() * PI * 2.0
            val ex = cos(t).toFloat() * rx
            val ey = sin(t).toFloat() * ry
            val cr = cos(rotation).toFloat()
            val sr = sin(rotation).toFloat()
            val px = cx + ex * cr - ey * sr
            val py = cy + ex * sr + ey * cr
            if (s == 0) {
                firstX = px
                firstY = py
                fibrePath.moveTo(px, py)
            } else {
                fibrePath.lineTo(px, py)
            }
        }
        fibrePath.lineTo(firstX, firstY)

        glowPaint.color = color
        glowPaint.alpha = alpha / 4
        glowPaint.strokeWidth = dp(if (compact) 5.2f else 6.6f)
        canvas.drawPath(fibrePath, glowPaint)

        strokePaint.color = color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = dp(if (compact) 0.90f else 1.10f)
        canvas.drawPath(fibrePath, strokePaint)
    }

    private fun drawInnerHighlights(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        fillPaint.shader = centreShader
        fillPaint.alpha = 246
        canvas.drawCircle(cx, cy, radius * 0.43f, fillPaint)
        fillPaint.shader = null

        // Inner concentric energy rings.
        strokePaint.shader = null
        strokePaint.color = palette.white
        strokePaint.alpha = 44
        strokePaint.strokeWidth = dp(0.72f)
        canvas.drawCircle(cx, cy, radius * 0.46f, strokePaint)

        strokePaint.color = palette.primary
        strokePaint.alpha = 74
        strokePaint.strokeWidth = dp(0.86f)
        canvas.drawCircle(cx, cy, radius * 0.52f, strokePaint)

        // Four moving hot spots like the reference's bright knots.
        val spotCount = 4
        for (i in 0 until spotCount) {
            val a = now / (1550.0 - energy * 180.0) * (0.18 + i * 0.012) + i * PI * 0.5
            val rr = radius * (0.54f + 0.18f * ((sin(now / 930.0 + i) + 1.0) * 0.5).toFloat())
            val x = cx + cos(a).toFloat() * rr
            val y = cy + sin(a).toFloat() * rr

            pointPaint.color = palette.white
            pointPaint.alpha = 228
            canvas.drawCircle(x, y, dp(if (compact) 1.15f else 1.45f), pointPaint)

            strokePaint.color = palette.white
            strokePaint.alpha = 78
            strokePaint.strokeWidth = dp(0.52f)
            val arm = dp(if (compact) 4.0f else 5.2f)
            canvas.drawLine(x - arm, y, x + arm, y, strokePaint)
            canvas.drawLine(x, y - arm, x, y + arm, strokePaint)
        }
    }

    private fun drawAyanaWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        compact: Boolean
    ) {
        // Reference wordmark is wide and dominant, not a small centre label.
        val textSize = min(
            radius * if (compact) 0.34f else 0.36f,
            dp(if (compact) 24f else 31f)
        )
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) * 0.5f

        textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)

        // Wide coloured glow pass.
        textPaint.textSize = textSize * 1.055f
        textPaint.color = palette.primary
        textPaint.alpha = 72
        canvas.drawText("AYANA", cx, baseline, textPaint)

        // Bright face.
        textPaint.textSize = textSize
        textPaint.color = palette.white
        textPaint.alpha = 252
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
        val count = if (compact) 34 else 48
        val phase = now / (2400.0 - energy * 260.0)

        for (i in 0 until count) {
            val a = i * (PI * 2.0 / count) + phase * (if (i % 2 == 0) 0.035 else -0.022)
            val pulse = (0.5 + 0.5 * sin(phase + i * 1.47)).toFloat()
            val rr = radius * (0.975f + 0.090f * ((sin(i * 0.83) + 1.0) * 0.5).toFloat())
            val x = cx + cos(a).toFloat() * rr
            val y = cy + sin(a).toFloat() * rr

            pointPaint.color = when (i % 6) {
                0 -> palette.white
                1, 2, 3 -> palette.primary
                4 -> palette.secondary
                else -> palette.accent
            }
            pointPaint.alpha = (55 + pulse * 150f).toInt().coerceIn(0, 218)
            canvas.drawCircle(x, y, dp(0.42f + pulse * 0.58f), pointPaint)
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
                withAlpha(palette.white, 20),
                withAlpha(palette.primary, 64),
                withAlpha(palette.secondary, 38),
                withAlpha(palette.accent, 18),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 0.60f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )

        bodyShader = RadialGradient(
            cx - minSide * 0.020f,
            cy - minSide * 0.028f,
            minSide * 0.37f,
            intArrayOf(
                withAlpha(palette.white, 96),
                withAlpha(palette.primary, 112),
                withAlpha(palette.secondary, 88),
                withAlpha(palette.accent, 54),
                withAlpha(palette.deep, 180),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.16f, 0.35f, 0.58f, 0.83f, 1f),
            Shader.TileMode.CLAMP
        )

        centreShader = RadialGradient(
            cx,
            cy,
            minSide * 0.20f,
            intArrayOf(
                withAlpha(palette.white, 130),
                withAlpha(palette.primary, 132),
                withAlpha(palette.secondary, 104),
                withAlpha(palette.deep, 142),
                withAlpha(palette.deep, 220)
            ),
            floatArrayOf(0f, 0.18f, 0.42f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )

        signalShader = LinearGradient(
            w * 0.01f,
            cy,
            w * 0.99f,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(palette.primary, 180),
                palette.primary,
                palette.white,
                palette.white,
                palette.secondary,
                withAlpha(palette.primary, 180),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.09f, 0.27f, 0.47f, 0.53f, 0.73f, 0.91f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun paletteFor(state: String): Palette {
        return when (state) {
            // 2. RECOGNIZING — electric blue.
            AyanaVoiceService.STATE_COMMAND -> Palette(
                primary = Color.parseColor("#2E8CFF"),
                secondary = Color.parseColor("#1268FF"),
                accent = Color.parseColor("#78C8FF"),
                white = Color.parseColor("#F8FCFF"),
                deep = Color.parseColor("#030A1B")
            )

            // 3. THINKING — violet / blue-violet.
            AyanaVoiceService.STATE_THINKING -> Palette(
                primary = Color.parseColor("#6D55FF"),
                secondary = Color.parseColor("#3E47FF"),
                accent = Color.parseColor("#A66CFF"),
                white = Color.parseColor("#FBFAFF"),
                deep = Color.parseColor("#0D0624")
            )

            // 4. EXECUTING — vivid green.
            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SUCCESS -> Palette(
                primary = Color.parseColor("#20F48A"),
                secondary = Color.parseColor("#00CFA0"),
                accent = Color.parseColor("#70FFB6"),
                white = Color.parseColor("#F7FFFB"),
                deep = Color.parseColor("#031B12")
            )

            // 5. RESPONDING — hot magenta / fuchsia.
            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT -> Palette(
                primary = Color.parseColor("#FF29D7"),
                secondary = Color.parseColor("#B52CFF"),
                accent = Color.parseColor("#FF79E9"),
                white = Color.parseColor("#FFF9FE"),
                deep = Color.parseColor("#1C0319")
            )

            // 6. STOP / ERROR — red with orange edge.
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

            // 1. WAITING / LISTENING — bright cyan exactly as reference family.
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
