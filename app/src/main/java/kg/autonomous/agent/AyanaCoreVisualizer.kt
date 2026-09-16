package kg.autonomous.agent

import android.content.Context
import android.graphics.BlurMaskFilter
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v3.1 â€” REFERENCE SPHERE REBUILD.
 *
 * Rebuilt from the approved six-state visual reference:
 * - one centered luminous energy sphere;
 * - one clean outer ring with particle corona;
 * - a horizontal audio-style wave crossing the sphere;
 * - large centered AYANA wordmark;
 * - six clear state palettes without the previous â€śweb / orbit spaghettiâ€ť look.
 *
 * Integration contract:
 * - package unchanged: kg.autonomous.agent
 * - class unchanged: AyanaCoreVisualizer(Context)
 * - no MainActivity change
 * - no AyanaVoiceService change
 * - ORB untouched
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL)
    }
    private val softGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        maskFilter = BlurMaskFilter(dp(18f), BlurMaskFilter.Blur.NORMAL)
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        strokeCap = Paint.Cap.ROUND
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val waveGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        maskFilter = BlurMaskFilter(dp(7f), BlurMaskFilter.Blur.NORMAL)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }
    private val textGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        maskFilter = BlurMaskFilter(dp(8f), BlurMaskFilter.Blur.NORMAL)
    }

    private val wavePath = Path()
    private val filamentPath = Path()
    private val ringBounds = RectF()

    private var attached = false

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        setLayerType(LAYER_TYPE_SOFTWARE, null)
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
        val spec = paletteFor(state)
        val now = SystemClock.uptimeMillis()
        val t = now / 1000.0

        val inset = dp(if (h < dp(180f)) 8f else 12f)
        val left = inset
        val top = inset
        val right = w - inset
        val bottom = h - inset
        val contentW = right - left
        val contentH = bottom - top
        val cx = (left + right) * 0.5f
        val cy = (top + bottom) * 0.5f
        val minSide = min(contentW, contentH)

        val sphereRadius = minSide * if (contentH < dp(150f)) 0.355f else 0.385f
        val outerRingRadius = sphereRadius * 1.12f
        val coronaRadius = sphereRadius * 1.18f
        val ambientRadius = sphereRadius * 1.36f

        drawAmbient(canvas, cx, cy, ambientRadius, spec)
        drawOuterRings(canvas, t, cx, cy, sphereRadius, outerRingRadius, coronaRadius, spec)
        drawFilaments(canvas, t, cx, cy, sphereRadius, spec)
        drawWave(canvas, t, left, right, cx, cy, sphereRadius, spec)
        drawWordmark(canvas, cx, cy, sphereRadius, spec)
        drawSparkles(canvas, t, cx, cy, coronaRadius, spec)

        if (attached && isShown) {
            postInvalidateDelayed(frameDelayMs(state))
        }
    }

    private fun drawAmbient(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        spec: VisualSpec
    ) {
        val ambientShader = RadialGradient(
            cx,
            cy,
            radius,
            intArrayOf(
                withAlpha(spec.core, 145),
                withAlpha(spec.glow, 54),
                withAlpha(spec.ring, 20),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.26f, 0.66f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = ambientShader
        fillPaint.alpha = 255
        canvas.drawCircle(cx, cy, radius, fillPaint)

        val coreShader = RadialGradient(
            cx,
            cy,
            radius * 0.72f,
            intArrayOf(
                Color.WHITE,
                withAlpha(spec.core, 252),
                withAlpha(spec.glow, 168),
                withAlpha(spec.core, 64),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.12f, 0.36f, 0.74f, 1f),
            Shader.TileMode.CLAMP
        )
        fillPaint.shader = coreShader
        canvas.drawCircle(cx, cy, radius * 0.66f, fillPaint)
    }

    private fun drawOuterRings(
        canvas: Canvas,
        t: Double,
        cx: Float,
        cy: Float,
        sphereRadius: Float,
        outerRingRadius: Float,
        coronaRadius: Float,
        spec: VisualSpec
    ) {
        ringBounds.set(
            cx - outerRingRadius,
            cy - outerRingRadius,
            cx + outerRingRadius,
            cy + outerRingRadius
        )

        val ringShader = RadialGradient(
            cx,
            cy,
            coronaRadius,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(spec.ring, 28),
                withAlpha(spec.ring, 100),
                withAlpha(spec.glow, 32),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.64f, 0.82f, 0.92f, 1f),
            Shader.TileMode.CLAMP
        )
        strokePaint.shader = ringShader
        strokePaint.strokeWidth = sphereRadius * 0.020f
        canvas.drawCircle(cx, cy, outerRingRadius, strokePaint)

        glowStrokePaint.shader = null
        glowStrokePaint.color = withAlpha(spec.ring, 168)
        glowStrokePaint.strokeWidth = sphereRadius * 0.026f
        canvas.drawCircle(cx, cy, outerRingRadius * 0.995f, glowStrokePaint)

        // Clean highlight arcs inspired by the reference, not orbit spaghetti.
        repeat(3) { index ->
            val start = ((t * (13.0 + index * 2.0) + index * 118.0) % 360.0).toFloat()
            val sweep = 44f + index * 12f
            glowStrokePaint.color = withAlpha(spec.wave, 150 - index * 24)
            glowStrokePaint.strokeWidth = sphereRadius * (0.030f - index * 0.004f)
            canvas.drawArc(ringBounds, start, sweep, false, glowStrokePaint)
        }

        strokePaint.shader = null
        strokePaint.color = withAlpha(spec.highlight, 190)
        strokePaint.strokeWidth = sphereRadius * 0.0085f
        canvas.drawCircle(cx, cy, sphereRadius * 0.98f, strokePaint)

        // Particle corona concentrated around the ring edge.
        val particleCount = if (sphereRadius < dp(70f)) 120 else 176
        for (i in 0 until particleCount) {
            val fraction = i.toFloat() / particleCount.toFloat()
            val angle = fraction * PI * 2.0 + t * spec.rotation * 0.18
            val radialJitter = sin(fraction * PI * 18.0 + t * 1.7).toFloat() * sphereRadius * 0.015f
            val radius = coronaRadius + radialJitter
            val x = cx + cos(angle).toFloat() * radius
            val y = cy + sin(angle).toFloat() * radius
            val sparkle = 0.45f + 0.55f * ((sin(angle * 6.0 - t * 2.2) + 1.0) * 0.5).toFloat()
            val size = sphereRadius * (0.004f + 0.010f * sparkle)
            particlePaint.color = withAlpha(spec.wave, (90 + sparkle * 140f).toInt())
            canvas.drawCircle(x, y, size, particlePaint)
        }
    }

    private fun drawFilaments(
        canvas: Canvas,
        t: Double,
        cx: Float,
        cy: Float,
        sphereRadius: Float,
        spec: VisualSpec
    ) {
        val filamentCount = 6
        for (i in 0 until filamentCount) {
            filamentPath.reset()
            val progress = i / filamentCount.toFloat()
            val tilt = (-28f + i * 11f)
            val a = sphereRadius * (0.72f + progress * 0.18f)
            val b = sphereRadius * (0.42f + ((i + 2) % 4) * 0.10f)
            val rotation = t * (spec.rotation * (0.55 + progress * 0.28)) + progress * PI * 1.7
            val points = 84

            for (step in 0..points) {
                val f = step.toFloat() / points.toFloat()
                val ang = f * PI * 2.0
                val orbitX = cos(ang).toFloat() * a
                val orbitY = sin(ang).toFloat() * b
                val pulse = 1f + 0.07f * sin(ang * 3.0 + rotation).toFloat()
                val rx = orbitX * pulse
                val ry = orbitY * pulse
                val rotX = (
                    rx * cos(rotation).toFloat() -
                        ry * sin(rotation).toFloat()
                    )
                val rotY = (
                    rx * sin(rotation).toFloat() +
                        ry * cos(rotation).toFloat()
                    )
                val twistedY = rotY * cos(Math.toRadians(tilt.toDouble())).toFloat()
                val x = cx + rotX
                val y = cy + twistedY
                if (step == 0) filamentPath.moveTo(x, y) else filamentPath.lineTo(x, y)
            }

            val alpha = (88 + i * 18).coerceAtMost(210)
            glowStrokePaint.shader = null
            glowStrokePaint.color = withAlpha(spec.glow, alpha)
            glowStrokePaint.strokeWidth = sphereRadius * (0.010f + progress * 0.006f)
            canvas.drawPath(filamentPath, glowStrokePaint)

            strokePaint.shader = null
            strokePaint.color = withAlpha(spec.highlight, (120 + i * 16).coerceAtMost(235))
            strokePaint.strokeWidth = sphereRadius * (0.0035f + progress * 0.0025f)
            canvas.drawPath(filamentPath, strokePaint)
        }
    }

    private fun drawWave(
        canvas: Canvas,
        t: Double,
        left: Float,
        right: Float,
        cx: Float,
        cy: Float,
        sphereRadius: Float,
        spec: VisualSpec
    ) {
        wavePath.reset()
        val span = right - left
        val steps = max(72, (span / dp(5f)).toInt())
        val leftPad = dp(2f)
        val rightPad = dp(2f)
        val baseAmp = sphereRadius * spec.waveAmplitude

        for (i in 0..steps) {
            val f = i.toFloat() / steps.toFloat()
            val x = left + leftPad + (span - leftPad - rightPad) * f
            val offsetFromCenter = abs(x - cx) / sphereRadius
            val sphereMask = 1f - (1f - offsetFromCenter.coerceIn(0f, 1f)).coerceIn(0f, 1f)
            val localAmpBoost = if (offsetFromCenter < 1.2f) 0.72f else 1f
            val envelope = (0.22f + 0.78f * abs(f - 0.5f) * 2f).coerceIn(0.22f, 1f)
            val harmonic =
                sin(f * PI * 38.0 + t * spec.waveSpeed) * 0.56 +
                    sin(f * PI * 86.0 - t * spec.waveSpeed * 1.42) * 0.22 +
                    sin(f * PI * 8.0 + t * 2.0) * 0.22
            val amp = baseAmp * envelope * localAmpBoost * (0.72f + sphereMask * 0.28f)
            val y = cy + harmonic.toFloat() * amp
            if (i == 0) wavePath.moveTo(x, y) else wavePath.lineTo(x, y)
        }

        val waveShader = LinearGradient(
            left,
            cy,
            right,
            cy,
            intArrayOf(
                withAlpha(spec.wave, 40),
                withAlpha(spec.wave, 255),
                withAlpha(spec.highlight, 255),
                withAlpha(spec.wave, 255),
                withAlpha(spec.wave, 40)
            ),
            floatArrayOf(0f, 0.14f, 0.5f, 0.86f, 1f),
            Shader.TileMode.CLAMP
        )

        waveGlowPaint.shader = waveShader
        waveGlowPaint.strokeWidth = sphereRadius * 0.030f
        canvas.drawPath(wavePath, waveGlowPaint)

        wavePaint.shader = waveShader
        wavePaint.strokeWidth = sphereRadius * 0.0085f
        canvas.drawPath(wavePath, wavePaint)

        // Bright center beam through the sphere.
        strokePaint.shader = waveShader
        strokePaint.strokeWidth = sphereRadius * 0.018f
        strokePaint.alpha = 120
        canvas.drawLine(
            cx - sphereRadius * 1.04f,
            cy,
            cx + sphereRadius * 1.04f,
            cy,
            strokePaint
        )
        strokePaint.alpha = 255
        strokePaint.shader = null
    }

    private fun drawWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        sphereRadius: Float,
        spec: VisualSpec
    ) {
        val textSize = sphereRadius * 0.47f
        textGlowPaint.textSize = textSize
        textGlowPaint.letterSpacing = 0.11f
        textGlowPaint.color = withAlpha(spec.highlight, 215)
        canvas.drawText("AYANA", cx, cy + textSize * 0.16f, textGlowPaint)

        textPaint.textSize = textSize
        textPaint.letterSpacing = 0.11f
        textPaint.color = Color.WHITE
        canvas.drawText("AYANA", cx, cy + textSize * 0.16f, textPaint)

        // Subtle inner tint to avoid a flat white wordmark.
        strokePaint.shader = null
        strokePaint.color = withAlpha(spec.core, 110)
        strokePaint.strokeWidth = sphereRadius * 0.010f
        strokePaint.style = Paint.Style.STROKE
        strokePaint.textAlign = Paint.Align.CENTER
        strokePaint.textSize = textSize
        strokePaint.typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        strokePaint.letterSpacing = 0.11f
        canvas.drawText("AYANA", cx, cy + textSize * 0.16f, strokePaint)
        strokePaint.style = Paint.Style.STROKE
    }

    private fun drawSparkles(
        canvas: Canvas,
        t: Double,
        cx: Float,
        cy: Float,
        radius: Float,
        spec: VisualSpec
    ) {
        val sparkCount = 16
        for (i in 0 until sparkCount) {
            val fraction = i.toFloat() / sparkCount.toFloat()
            val angle = fraction * PI * 2.0 + t * 0.24 + i * 0.17
            val localRadius = radius * (0.92f + 0.18f * sin(t * 0.9 + i).toFloat())
            val x = cx + cos(angle).toFloat() * localRadius
            val y = cy + sin(angle).toFloat() * localRadius
            val glow = (0.35f + 0.65f * ((sin(t * 1.8 + i * 0.7) + 1.0) * 0.5)).toFloat()
            val size = dp(1.2f) + radius * 0.010f * glow
            particlePaint.color = withAlpha(spec.highlight, (120 + glow * 120f).toInt())
            canvas.drawCircle(x, y, size, particlePaint)

            if (glow > 0.72f) {
                strokePaint.color = withAlpha(spec.highlight, (60 + glow * 120f).toInt())
                strokePaint.strokeWidth = max(dp(0.7f), radius * 0.0032f)
                canvas.drawLine(x - size * 2.2f, y, x + size * 2.2f, y, strokePaint)
                canvas.drawLine(x, y - size * 2.2f, x, y + size * 2.2f, strokePaint)
            }
        }
    }

    private fun paletteFor(state: String): VisualSpec {
        return when (state) {
            AyanaVoiceService.STATE_LISTENING ->
                VisualSpec(
                    core = Color.parseColor("#1DF4FF"),
                    glow = Color.parseColor("#0DD8F2"),
                    ring = Color.parseColor("#3BFBFF"),
                    wave = Color.parseColor("#75FFFF"),
                    highlight = Color.parseColor("#F2FFFF"),
                    waveAmplitude = 0.11f,
                    waveSpeed = 9.8,
                    rotation = 0.52
                )

            AyanaVoiceService.STATE_COMMAND ->
                VisualSpec(
                    core = Color.parseColor("#2A7CFF"),
                    glow = Color.parseColor("#1E62FF"),
                    ring = Color.parseColor("#4FA2FF"),
                    wave = Color.parseColor("#87BFFF"),
                    highlight = Color.parseColor("#F2F9FF"),
                    waveAmplitude = 0.14f,
                    waveSpeed = 12.4,
                    rotation = 0.78
                )

            AyanaVoiceService.STATE_THINKING ->
                VisualSpec(
                    core = Color.parseColor("#6F41FF"),
                    glow = Color.parseColor("#7C33FF"),
                    ring = Color.parseColor("#9B63FF"),
                    wave = Color.parseColor("#B78CFF"),
                    highlight = Color.parseColor("#FBF7FF"),
                    waveAmplitude = 0.12f,
                    waveSpeed = 10.8,
                    rotation = 0.66
                )

            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SUCCESS ->
                VisualSpec(
                    core = Color.parseColor("#14EC87"),
                    glow = Color.parseColor("#11D476"),
                    ring = Color.parseColor("#3AF3A0"),
                    wave = Color.parseColor("#87FFD0"),
                    highlight = Color.parseColor("#F2FFF9"),
                    waveAmplitude = 0.13f,
                    waveSpeed = 13.5,
                    rotation = 0.86
                )

            AyanaVoiceService.STATE_SPEAKING ->
                VisualSpec(
                    core = Color.parseColor("#FF4AE3"),
                    glow = Color.parseColor("#FF37D3"),
                    ring = Color.parseColor("#FF73EA"),
                    wave = Color.parseColor("#FFA1F0"),
                    highlight = Color.parseColor("#FFF3FD"),
                    waveAmplitude = 0.15f,
                    waveSpeed = 14.6,
                    rotation = 0.94
                )

            AyanaVoiceService.STATE_ERROR,
            AyanaVoiceService.STATE_STOPPED,
            AyanaVoiceService.STATE_CANCELLED ->
                VisualSpec(
                    core = Color.parseColor("#FF4B2B"),
                    glow = Color.parseColor("#FF3B17"),
                    ring = Color.parseColor("#FF7A2E"),
                    wave = Color.parseColor("#FFB06A"),
                    highlight = Color.parseColor("#FFF6F1"),
                    waveAmplitude = 0.17f,
                    waveSpeed = 16.2,
                    rotation = 1.08
                )

            else ->
                VisualSpec(
                    core = Color.parseColor("#1DF4FF"),
                    glow = Color.parseColor("#0DD8F2"),
                    ring = Color.parseColor("#3BFBFF"),
                    wave = Color.parseColor("#75FFFF"),
                    highlight = Color.parseColor("#F2FFFF"),
                    waveAmplitude = 0.11f,
                    waveSpeed = 9.8,
                    rotation = 0.52
                )
        }
    }

    private fun frameDelayMs(state: String): Long {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_ERROR,
            AyanaVoiceService.STATE_STOPPED -> 16L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SUCCESS -> 20L

            else -> 24L
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        val a = alpha.coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    private fun dp(value: Float): Float = value * density

    private data class VisualSpec(
        val core: Int,
        val glow: Int,
        val ring: Int,
        val wave: Int,
        val highlight: Int,
        val waveAmplitude: Float,
        val waveSpeed: Double,
        val rotation: Double
    )
}
