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
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v4.0.1 — REFERENCE PLASMA SPHERE COMPILE FIX.
 *
 * Clean-room rebuild from the six-state user reference.
 * The renderer intentionally avoids the previous primitive/orbit look.
 *
 * Visual contract:
 * - one dominant plasma sphere;
 * - dense luminous inner ribbons, not sparse ellipses;
 * - double technical outer ring + granular particle corona;
 * - bright horizontal high-frequency waveform through the centre;
 * - custom geometric AYANA wordmark (no system font dependency);
 * - six distinct reference palettes: cyan, blue, violet, green, pink, red/orange;
 * - all rendering stays inside this View;
 * - no microphone-amplitude claim: motion is state-reactive decorative animation.
 *
 * Integration contract:
 * - package kg.autonomous.agent;
 * - class AyanaCoreVisualizer(Context) unchanged;
 * - MainActivity unchanged;
 * - AyanaVoiceService unchanged;
 * - floating ORB untouched;
 * - no permissions, accessibility, overlay, routing or execution changes.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val plasmaPath = Path()
    private val wavePath = Path()
    private val glyphPath = Path()
    private val ringRect = RectF()

    private var attached = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postInvalidateDelayed(frameDelayMs(AyanaVoiceService.currentStatusState))
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return

        val state = AyanaVoiceService.currentStatusState
        val spec = visualSpecFor(state)
        val t = SystemClock.uptimeMillis() / 1000.0

        val compact = h < dp(180f)
        val inset = dp(if (compact) 7f else 10f)
        val left = inset
        val right = w - inset
        val top = inset
        val bottom = h - inset
        val contentW = right - left
        val contentH = bottom - top
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f

        // Reference proportions: sphere dominates height while leaving room for the wave tails.
        val hardRadius = min(contentH * 0.365f, contentW * 0.285f)
        val sphereRadius = hardRadius.coerceAtLeast(dp(28f))
        val outerRingRadius = sphereRadius * 1.075f
        val coronaRadius = sphereRadius * 1.135f

        drawBackgroundBloom(canvas, cx, cy, sphereRadius, spec)
        drawCoreDisc(canvas, cx, cy, sphereRadius, spec, t)
        drawConcentricTechnicalRings(canvas, cx, cy, sphereRadius, spec, t)
        drawDensePlasma(canvas, cx, cy, sphereRadius, spec, t)
        drawOuterRingSystem(canvas, cx, cy, sphereRadius, outerRingRadius, coronaRadius, spec, t)
        drawReferenceWave(canvas, left, right, cx, cy, sphereRadius, spec, t)
        drawAyanaWordmark(canvas, cx, cy, sphereRadius, spec)
        drawHotSparks(canvas, cx, cy, sphereRadius, coronaRadius, spec, t)

        if (attached && isShown) {
            postInvalidateDelayed(frameDelayMs(state))
        }
    }

    private fun drawBackgroundBloom(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        spec: VisualSpec
    ) {
        val shader = RadialGradient(
            cx,
            cy,
            r * 1.28f,
            intArrayOf(
                withAlpha(spec.coreHot, 88),
                withAlpha(spec.primary, 54),
                withAlpha(spec.primary, 18),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.28f, 0.73f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, r * 1.28f, fillPaint)
        fillPaint.shader = null
    }

    private fun drawCoreDisc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        spec: VisualSpec,
        t: Double
    ) {
        val pulse = 0.965f + 0.035f * sin(t * spec.pulseSpeed).toFloat()
        val coreR = r * 0.86f * pulse

        val shader = RadialGradient(
            cx,
            cy,
            coreR,
            intArrayOf(
                Color.WHITE,
                withAlpha(spec.coreHot, 252),
                withAlpha(spec.primary, 238),
                withAlpha(spec.deep, 205),
                withAlpha(spec.deep, 68),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.08f, 0.28f, 0.59f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = shader
        canvas.drawCircle(cx, cy, coreR, fillPaint)
        fillPaint.shader = null

        // Luminous lens in the centre, intentionally broad like the reference.
        val lensShader = RadialGradient(
            cx,
            cy,
            r * 0.48f,
            intArrayOf(
                withAlpha(Color.WHITE, 222),
                withAlpha(spec.coreHot, 196),
                withAlpha(spec.primary, 82),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.15f, 0.58f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = lensShader
        canvas.drawCircle(cx, cy, r * 0.48f, fillPaint)
        fillPaint.shader = null
    }

    private fun drawConcentricTechnicalRings(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        spec: VisualSpec,
        t: Double
    ) {
        val rings = floatArrayOf(0.33f, 0.46f, 0.61f, 0.74f, 0.90f)
        for (i in rings.indices) {
            val rr = r * rings[i]
            strokePaint.color = withAlpha(spec.highlight, 34 + i * 9)
            strokePaint.strokeWidth = max(dp(0.65f), r * (0.0032f + i * 0.0006f))
            canvas.drawCircle(cx, cy, rr, strokePaint)
        }

        // Very subtle rotating broken arcs give the technical texture visible in the reference.
        ringRect.set(cx - r * 0.96f, cy - r * 0.96f, cx + r * 0.96f, cy + r * 0.96f)
        for (i in 0 until 8) {
            val phase = ((t * (5.5 + i * 0.35) + i * 43.0) % 360.0).toFloat()
            strokePaint.color = withAlpha(
                if (i % 2 == 0) spec.highlight else spec.primary,
                38 + (i % 3) * 15
            )
            strokePaint.strokeWidth = max(dp(0.7f), r * 0.0045f)
            canvas.drawArc(ringRect, phase, 15f + (i % 4) * 8f, false, strokePaint)
        }
    }

    private fun drawDensePlasma(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        spec: VisualSpec,
        t: Double
    ) {
        // Dense ribbon field. Each ribbon is a deformed closed Lissajous-style loop.
        // Layering broad transparent strokes + thin bright strokes creates a plasma mass
        // instead of the sparse orbital-wire look of the rejected versions.
        val ribbonCount = 18
        val points = 118

        for (i in 0 until ribbonCount) {
            val fi = i.toFloat() / (ribbonCount - 1).toFloat()
            val direction = if (i % 2 == 0) 1.0 else -1.0
            val rotation = t * spec.rotationSpeed * direction + i * 0.41
            val phaseB = i * 0.73 + t * spec.rotationSpeed * 0.43
            val baseA = r * (0.62f + fi * 0.22f)
            val baseB = r * (0.40f + ((i * 7) % 11) / 11f * 0.30f)
            val tilt = -0.42 + (i % 7) * 0.14

            plasmaPath.reset()

            for (step in 0..points) {
                val u = step.toDouble() / points.toDouble() * PI * 2.0
                val wobble = 1.0 + 0.075 * sin(u * 3.0 + phaseB) + 0.035 * sin(u * 7.0 - phaseB * 0.7)
                val x0 = cos(u + rotation) * baseA * wobble
                val y0 = sin(u * 1.018 + phaseB * 0.18) * baseB * (1.0 + 0.07 * cos(u * 4.0 - rotation))

                val ct = cos(tilt)
                val st = sin(tilt)
                val xr = x0 * ct - y0 * st
                val yr = x0 * st + y0 * ct

                val x = cx + xr.toFloat()
                val y = cy + yr.toFloat()

                if (step == 0) plasmaPath.moveTo(x, y) else plasmaPath.lineTo(x, y)
            }

            val layerColor = when (i % 5) {
                0 -> spec.highlight
                1, 2 -> spec.primary
                else -> spec.secondary
            }

            strokePaint.color = withAlpha(layerColor, 20 + (fi * 26f).toInt())
            strokePaint.strokeWidth = r * (0.040f - fi * 0.010f)
            canvas.drawPath(plasmaPath, strokePaint)

            strokePaint.color = withAlpha(layerColor, 72 + (fi * 92f).toInt())
            strokePaint.strokeWidth = r * (0.012f - fi * 0.0035f)
            canvas.drawPath(plasmaPath, strokePaint)

            strokePaint.color = withAlpha(spec.highlight, 68 + (fi * 90f).toInt())
            strokePaint.strokeWidth = max(dp(0.65f), r * 0.0033f)
            canvas.drawPath(plasmaPath, strokePaint)
        }

        // Several high-energy arcs/whorls crossing the sphere.
        for (i in 0 until 7) {
            val angle = t * spec.rotationSpeed * 0.62 + i * (PI * 2.0 / 7.0)
            val rr = r * (0.46f + (i % 3) * 0.11f)
            val x1 = cx + cos(angle).toFloat() * rr
            val y1 = cy + sin(angle * 1.17).toFloat() * rr * 0.76f
            val x2 = cx - cos(angle + 0.72).toFloat() * rr * 0.83f
            val y2 = cy - sin(angle * 0.91 + 0.4).toFloat() * rr * 0.62f
            strokePaint.color = withAlpha(spec.highlight, 86 + i * 13)
            strokePaint.strokeWidth = r * 0.009f
            canvas.drawLine(x1, y1, x2, y2, strokePaint)
        }
    }

    private fun drawOuterRingSystem(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        outerR: Float,
        coronaR: Float,
        spec: VisualSpec,
        t: Double
    ) {
        // Broad atmospheric halo.
        strokePaint.color = withAlpha(spec.primary, 22)
        strokePaint.strokeWidth = r * 0.105f
        canvas.drawCircle(cx, cy, outerR, strokePaint)

        // Main two rings.
        strokePaint.color = withAlpha(spec.primary, 146)
        strokePaint.strokeWidth = max(dp(1.1f), r * 0.012f)
        canvas.drawCircle(cx, cy, outerR, strokePaint)

        strokePaint.color = withAlpha(spec.highlight, 118)
        strokePaint.strokeWidth = max(dp(0.7f), r * 0.005f)
        canvas.drawCircle(cx, cy, outerR * 0.965f, strokePaint)

        ringRect.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR)

        // Bright broken energy segments around the ring.
        for (i in 0 until 15) {
            val start = ((i * 24.0 + t * spec.ringSpeed * (if (i % 2 == 0) 1 else -1)) % 360.0).toFloat()
            val sweep = 7f + (i % 4) * 4.5f
            strokePaint.color = withAlpha(
                if (i % 3 == 0) spec.highlight else spec.primary,
                86 + (i % 5) * 24
            )
            strokePaint.strokeWidth = r * (0.010f + (i % 3) * 0.003f)
            canvas.drawArc(ringRect, start, sweep, false, strokePaint)
        }

        // Dense granular corona. Deterministic pseudo-noise from trig functions avoids Random allocations.
        val count = if (r < dp(64f)) 210 else 320
        for (i in 0 until count) {
            val f = i.toDouble() / count.toDouble()
            val baseAngle = f * PI * 2.0
            val angularNoise = sin(i * 12.9898 + 78.233) * 0.022
            val angle = baseAngle + angularNoise + t * spec.coronaDrift
            val radialNoise =
                sin(i * 4.132 + t * 0.73) * r * 0.031 +
                    sin(i * 1.771 - t * 1.07) * r * 0.017
            val rr = coronaR + radialNoise.toFloat()
            val x = cx + cos(angle).toFloat() * rr
            val y = cy + sin(angle).toFloat() * rr

            val twinkle = ((sin(i * 0.81 + t * 2.4) + 1.0) * 0.5).toFloat()
            val size = max(dp(0.55f), r * (0.0028f + twinkle * 0.0075f))
            particlePaint.color = withAlpha(
                if (i % 7 == 0) spec.highlight else spec.primary,
                (62 + twinkle * 188f).toInt()
            )
            canvas.drawCircle(x, y, size, particlePaint)

            if (i % 19 == 0) {
                val tick = r * (0.040f + twinkle * 0.045f)
                strokePaint.color = withAlpha(spec.primary, 90 + (twinkle * 110f).toInt())
                strokePaint.strokeWidth = max(dp(0.6f), r * 0.004f)
                canvas.drawLine(
                    cx + cos(angle).toFloat() * (outerR + tick * 0.10f),
                    cy + sin(angle).toFloat() * (outerR + tick * 0.10f),
                    cx + cos(angle).toFloat() * (outerR + tick),
                    cy + sin(angle).toFloat() * (outerR + tick),
                    strokePaint
                )
            }
        }

        // Small “circuit” blocks concentrated around the upper-left/upper arc like the reference.
        val blockCount = 18
        for (i in 0 until blockCount) {
            val f = i.toFloat() / (blockCount - 1).toFloat()
            val deg = 205f + f * 115f
            val angle = Math.toRadians(deg.toDouble())
            val rr = outerR * (1.015f + 0.018f * sin(i * 1.7).toFloat())
            val x = cx + cos(angle).toFloat() * rr
            val y = cy + sin(angle).toFloat() * rr
            val tangentX = -sin(angle).toFloat()
            val tangentY = cos(angle).toFloat()
            val len = r * (0.025f + (i % 4) * 0.008f)
            strokePaint.color = withAlpha(spec.highlight, 82 + (i % 3) * 36)
            strokePaint.strokeWidth = max(dp(0.65f), r * 0.004f)
            canvas.drawLine(x, y, x + tangentX * len, y + tangentY * len, strokePaint)
        }
    }

    private fun drawReferenceWave(
        canvas: Canvas,
        left: Float,
        right: Float,
        cx: Float,
        cy: Float,
        r: Float,
        spec: VisualSpec,
        t: Double
    ) {
        val span = right - left
        val barCount = if (span < dp(420f)) 116 else 164
        val dx = span / (barCount - 1).toFloat()

        // Wide soft glow rail first.
        strokePaint.shader = LinearGradient(
            left,
            cy,
            right,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(spec.wave, 110),
                withAlpha(spec.highlight, 225),
                withAlpha(spec.wave, 110),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.12f, 0.5f, 0.88f, 1f),
            Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = r * 0.055f
        strokePaint.alpha = 72
        canvas.drawLine(left, cy, right, cy, strokePaint)
        strokePaint.alpha = 255

        // Dense vertical spectrum spikes. The reference is visually closer to an audio spectrum
        // than to a single clean sine line.
        for (i in 0 until barCount) {
            val x = left + dx * i
            val f = i.toDouble() / (barCount - 1).toDouble()
            val norm = abs(x - cx) / max(r, 1f)
            val outside = (norm - 1.02f).coerceAtLeast(0f)

            val carrier =
                abs(sin(f * PI * 39.0 + t * spec.waveSpeed)) * 0.50 +
                    abs(sin(f * PI * 91.0 - t * spec.waveSpeed * 1.31)) * 0.31 +
                    abs(sin(f * PI * 17.0 + t * 2.2)) * 0.19

            val sideEnvelope = (0.58f + outside * 0.55f).coerceIn(0.58f, 1.28f)
            val centreSuppression = if (norm < 0.82f) 0.34f + norm * 0.53f else 1f
            val amp = r * spec.waveAmplitude * carrier.toFloat() * sideEnvelope * centreSuppression
            val minAmp = r * 0.018f
            val half = max(minAmp, amp)

            val alpha = (78 + carrier * 177.0).toInt().coerceIn(70, 255)
            strokePaint.shader = null
            strokePaint.color = withAlpha(
                if (i % 9 == 0) spec.highlight else spec.wave,
                alpha
            )
            strokePaint.strokeWidth = max(dp(0.55f), r * if (i % 5 == 0) 0.006f else 0.0035f)
            canvas.drawLine(x, cy - half, x, cy + half, strokePaint)
        }

        // Razor-bright centre rail.
        strokePaint.shader = LinearGradient(
            left,
            cy,
            right,
            cy,
            intArrayOf(
                withAlpha(spec.wave, 48),
                withAlpha(spec.highlight, 255),
                Color.WHITE,
                withAlpha(spec.highlight, 255),
                withAlpha(spec.wave, 48)
            ),
            floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )
        strokePaint.strokeWidth = max(dp(0.85f), r * 0.0065f)
        canvas.drawLine(left, cy, right, cy, strokePaint)

        // Fine continuous waveform on top, kept secondary to the spectrum bars.
        wavePath.reset()
        val samples = 140
        for (i in 0..samples) {
            val f = i.toDouble() / samples.toDouble()
            val x = left + span * f.toFloat()
            val norm = abs(x - cx) / max(r, 1f)
            val yAmp = r * 0.055f * (if (norm < 0.90f) 0.32f else 1f)
            val v =
                sin(f * PI * 24.0 + t * spec.waveSpeed * 0.64) * 0.62 +
                    sin(f * PI * 53.0 - t * spec.waveSpeed * 0.92) * 0.38
            val y = cy + v.toFloat() * yAmp
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }
        strokePaint.shader = null
        strokePaint.color = withAlpha(spec.highlight, 220)
        strokePaint.strokeWidth = max(dp(0.7f), r * 0.0044f)
        canvas.drawPath(wavePath, strokePaint)
    }

    private fun drawAyanaWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        spec: VisualSpec
    ) {
        // Geometric reference-inspired wordmark. No Android font is used.
        val totalW = r * 1.62f
        val glyphH = r * 0.37f
        val baseline = cy + glyphH * 0.50f
        val top = cy - glyphH * 0.50f
        val gap = totalW * 0.024f

        val weights = floatArrayOf(0.19f, 0.18f, 0.19f, 0.20f, 0.19f)
        val usable = totalW - gap * 4f
        val widths = FloatArray(5) { usable * weights[it] / weights.sum() }
        var x = cx - totalW * 0.5f

        val paths = ArrayList<Path>(5)
        paths.add(buildAPath(x, top, widths[0], glyphH)); x += widths[0] + gap
        paths.add(buildYPath(x, top, widths[1], glyphH)); x += widths[1] + gap
        paths.add(buildAPath(x, top, widths[2], glyphH)); x += widths[2] + gap
        paths.add(buildNPath(x, top, widths[3], glyphH)); x += widths[3] + gap
        paths.add(buildAPath(x, top, widths[4], glyphH))

        // Deep coloured halo.
        strokePaint.shader = null
        strokePaint.style = Paint.Style.STROKE
        strokePaint.strokeCap = Paint.Cap.ROUND
        strokePaint.strokeJoin = Paint.Join.ROUND
        strokePaint.color = withAlpha(spec.primary, 62)
        strokePaint.strokeWidth = r * 0.105f
        for (path in paths) canvas.drawPath(path, strokePaint)

        // Hot state-colour glow.
        strokePaint.color = withAlpha(spec.primary, 210)
        strokePaint.strokeWidth = r * 0.063f
        for (path in paths) canvas.drawPath(path, strokePaint)

        // Bright outer tube.
        strokePaint.color = withAlpha(Color.WHITE, 245)
        strokePaint.strokeWidth = r * 0.040f
        for (path in paths) canvas.drawPath(path, strokePaint)

        // Dark coloured inner stroke reproduces the outlined reference lettering.
        strokePaint.color = withAlpha(spec.deepText, 255)
        strokePaint.strokeWidth = r * 0.023f
        for (path in paths) canvas.drawPath(path, strokePaint)

        // Thin luminous inner glint.
        strokePaint.color = withAlpha(spec.highlight, 220)
        strokePaint.strokeWidth = r * 0.0060f
        for (path in paths) canvas.drawPath(path, strokePaint)

        // Tiny baseline energy glow under letters.
        strokePaint.color = withAlpha(spec.primary, 76)
        strokePaint.strokeWidth = r * 0.020f
        canvas.drawLine(cx - totalW * 0.47f, baseline + r * 0.015f, cx + totalW * 0.47f, baseline + r * 0.015f, strokePaint)
    }

    private fun buildAPath(x: Float, top: Float, w: Float, h: Float): Path {
        val p = Path()
        p.moveTo(x + w * 0.04f, top + h)
        p.lineTo(x + w * 0.50f, top)
        p.lineTo(x + w * 0.96f, top + h)
        return p
    }

    private fun buildYPath(x: Float, top: Float, w: Float, h: Float): Path {
        val p = Path()
        p.moveTo(x + w * 0.05f, top)
        p.lineTo(x + w * 0.50f, top + h * 0.48f)
        p.lineTo(x + w * 0.95f, top)
        p.moveTo(x + w * 0.50f, top + h * 0.48f)
        p.lineTo(x + w * 0.50f, top + h)
        return p
    }

    private fun buildNPath(x: Float, top: Float, w: Float, h: Float): Path {
        val p = Path()
        p.moveTo(x + w * 0.08f, top + h)
        p.lineTo(x + w * 0.08f, top)
        p.lineTo(x + w * 0.92f, top + h)
        p.lineTo(x + w * 0.92f, top)
        return p
    }

    private fun drawHotSparks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        r: Float,
        coronaR: Float,
        spec: VisualSpec,
        t: Double
    ) {
        // Reference has a handful of very bright star points; keep them sparse and deliberate.
        val sparks = 13
        for (i in 0 until sparks) {
            val angle = i * (PI * 2.0 / sparks) + t * 0.11 + sin(i * 1.23) * 0.15
            val rr = coronaR * (0.88f + ((sin(i * 2.37 + t * 0.41) + 1.0) * 0.08).toFloat())
            val x = cx + cos(angle).toFloat() * rr
            val y = cy + sin(angle).toFloat() * rr
            val pulse = ((sin(t * 2.0 + i * 0.83) + 1.0) * 0.5).toFloat()
            val core = r * (0.006f + pulse * 0.008f)

            particlePaint.color = withAlpha(spec.highlight, 230)
            canvas.drawCircle(x, y, core, particlePaint)

            if (pulse > 0.38f) {
                strokePaint.color = withAlpha(spec.highlight, (90 + pulse * 150f).toInt())
                strokePaint.strokeWidth = max(dp(0.55f), r * 0.003f)
                val arm = r * (0.025f + pulse * 0.035f)
                canvas.drawLine(x - arm, y, x + arm, y, strokePaint)
                canvas.drawLine(x, y - arm, x, y + arm, strokePaint)
            }
        }
    }

    private fun visualSpecFor(state: String): VisualSpec {
        return when (state) {
            AyanaVoiceService.STATE_LISTENING -> VisualSpec(
                primary = Color.parseColor("#00F5FF"),
                secondary = Color.parseColor("#12C8E8"),
                coreHot = Color.parseColor("#BFFFFF"),
                deep = Color.parseColor("#003D47"),
                wave = Color.parseColor("#4CFFFF"),
                highlight = Color.parseColor("#EFFFFF"),
                deepText = Color.parseColor("#006B78"),
                rotationSpeed = 0.72,
                ringSpeed = 10.5,
                coronaDrift = 0.010,
                pulseSpeed = 1.70,
                waveSpeed = 12.0,
                waveAmplitude = 0.24f
            )

            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_TEXT -> VisualSpec(
                primary = Color.parseColor("#1581FF"),
                secondary = Color.parseColor("#315BFF"),
                coreHot = Color.parseColor("#C6E6FF"),
                deep = Color.parseColor("#042B63"),
                wave = Color.parseColor("#58A9FF"),
                highlight = Color.parseColor("#EEF7FF"),
                deepText = Color.parseColor("#075BC9"),
                rotationSpeed = 1.02,
                ringSpeed = 15.0,
                coronaDrift = 0.016,
                pulseSpeed = 2.05,
                waveSpeed = 15.2,
                waveAmplitude = 0.28f
            )

            AyanaVoiceService.STATE_THINKING -> VisualSpec(
                primary = Color.parseColor("#7040FF"),
                secondary = Color.parseColor("#4E35FF"),
                coreHot = Color.parseColor("#E4D6FF"),
                deep = Color.parseColor("#26106B"),
                wave = Color.parseColor("#9B72FF"),
                highlight = Color.parseColor("#F6F0FF"),
                deepText = Color.parseColor("#4D24BD"),
                rotationSpeed = 0.88,
                ringSpeed = 12.5,
                coronaDrift = 0.013,
                pulseSpeed = 1.82,
                waveSpeed = 13.4,
                waveAmplitude = 0.25f
            )

            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SUCCESS -> VisualSpec(
                primary = Color.parseColor("#00EF79"),
                secondary = Color.parseColor("#00C967"),
                coreHot = Color.parseColor("#CCFFE5"),
                deep = Color.parseColor("#004A2A"),
                wave = Color.parseColor("#43FFA1"),
                highlight = Color.parseColor("#F0FFF7"),
                deepText = Color.parseColor("#007441"),
                rotationSpeed = 1.18,
                ringSpeed = 17.0,
                coronaDrift = 0.018,
                pulseSpeed = 2.18,
                waveSpeed = 16.4,
                waveAmplitude = 0.27f
            )

            AyanaVoiceService.STATE_SPEAKING -> VisualSpec(
                primary = Color.parseColor("#FF27CE"),
                secondary = Color.parseColor("#E51BAC"),
                coreHot = Color.parseColor("#FFD1F5"),
                deep = Color.parseColor("#67104F"),
                wave = Color.parseColor("#FF68DE"),
                highlight = Color.parseColor("#FFF1FB"),
                deepText = Color.parseColor("#A70A7D"),
                rotationSpeed = 1.12,
                ringSpeed = 16.0,
                coronaDrift = 0.017,
                pulseSpeed = 2.10,
                waveSpeed = 16.0,
                waveAmplitude = 0.29f
            )

            AyanaVoiceService.STATE_STOPPED,
            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR,
            AyanaVoiceService.STATE_BLOCKED -> VisualSpec(
                primary = Color.parseColor("#FF3B19"),
                secondary = Color.parseColor("#FF6A00"),
                coreHot = Color.parseColor("#FFE0CB"),
                deep = Color.parseColor("#651200"),
                wave = Color.parseColor("#FF7646"),
                highlight = Color.parseColor("#FFF4ED"),
                deepText = Color.parseColor("#B0270C"),
                rotationSpeed = 1.24,
                ringSpeed = 18.5,
                coronaDrift = 0.019,
                pulseSpeed = 2.35,
                waveSpeed = 17.8,
                waveAmplitude = 0.31f
            )

            else -> VisualSpec(
                primary = Color.parseColor("#00F5FF"),
                secondary = Color.parseColor("#12C8E8"),
                coreHot = Color.parseColor("#BFFFFF"),
                deep = Color.parseColor("#003D47"),
                wave = Color.parseColor("#4CFFFF"),
                highlight = Color.parseColor("#EFFFFF"),
                deepText = Color.parseColor("#006B78"),
                rotationSpeed = 0.72,
                ringSpeed = 10.5,
                coronaDrift = 0.010,
                pulseSpeed = 1.70,
                waveSpeed = 12.0,
                waveAmplitude = 0.24f
            )
        }
    }

    private fun frameDelayMs(state: String): Long {
        return when (state) {
            AyanaVoiceService.STATE_LISTENING -> 24L
            AyanaVoiceService.STATE_THINKING -> 20L
            AyanaVoiceService.STATE_SUCCESS -> 20L
            else -> 16L
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Float): Float = value * density

    private data class VisualSpec(
        val primary: Int,
        val secondary: Int,
        val coreHot: Int,
        val deep: Int,
        val wave: Int,
        val highlight: Int,
        val deepText: Int,
        val rotationSpeed: Double,
        val ringSpeed: Double,
        val coronaDrift: Double,
        val pulseSpeed: Double,
        val waveSpeed: Double,
        val waveAmplitude: Float
    )
}
