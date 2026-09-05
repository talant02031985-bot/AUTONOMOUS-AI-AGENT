package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v19.2 — SIX PALETTE MATCH
 *
 * Corrective rebuild after v19 device failure.
 *
 * IMPORTANT:
 * - NO RuntimeShader/AGSL: v19 produced overexposure and square artifacts on-device.
 * - One clean core geometry across all six states.
 * - Only palette, speed and intensity change by state.
 * - No extra dashboards, nodes, buttons, load meters or "agent maps".
 * - No PNG/JPEG.
 * - No ORB / routing / TTS / microphone / Accessibility changes.
 *
 * States:
 * WAITING       cyan
 * RECOGNITION   electric blue
 * THINKING      indigo-violet
 * EXECUTING     green
 * ANSWERING     magenta
 * STOP          red-orange
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val glow =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val path = Path()

    private var attached = false

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        isLongClickable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val state = stateFor(AyanaVoiceService.currentStatusState)
        val palette = paletteFor(state)
        val motion = motionFor(state)
        val now = SystemClock.uptimeMillis()

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * 0.50f

        val radius =
            min(
                h * 0.445f,
                w * 0.305f
            )
                .coerceAtLeast(dp(42f))

        drawBlackField(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette
        )

        drawWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawCoreGlow(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            now = now
        )

        drawLissajousRibbons(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawReferenceRings(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette
        )

        drawHaloParticles(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawAyanaWordmark(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette
        )

        if (attached) {
            postInvalidateDelayed(motion.frameDelayMs)
        }
    }

    private fun drawBlackField(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 1.15f,
                intArrayOf(
                    withAlpha(palette.primary, 34),
                    withAlpha(palette.secondary, 12),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.56f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 1.15f,
            fill
        )
        fill.shader = null
    }

    /**
     * Reference-style waveform: strong on both sides, quieter through the core.
     */
    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        path.reset()

        val left = dp(1f)
        val right = width.toFloat() - dp(1f)
        val samples = 184
        val phase = now / motion.wavePeriodMs

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + (right - left) * t

            val normalizedDistance =
                abs(
                    (x - cx) /
                        (width * 0.50f)
                )
                    .coerceIn(0f, 1f)

            val envelope =
                (
                    0.08f +
                        normalizedDistance *
                            0.98f
                    )
                    .coerceIn(0.08f, 1f)

            val main =
                sin(
                    t * PI * 8.0 +
                        phase
                )
                    .toFloat()

            val detail =
                sin(
                    t * PI * 29.0 -
                        phase * 0.73
                )
                    .toFloat() *
                    0.24f

            val fine =
                sin(
                    t * PI * 67.0 +
                        phase * 1.12
                )
                    .toFloat() *
                    0.08f

            val y =
                cy +
                    (
                        main +
                            detail +
                            fine
                    ) *
                    radius *
                    0.145f *
                    envelope *
                    motion.waveStrength

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        val gradient =
            LinearGradient(
                left,
                cy,
                right,
                cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    palette.primary,
                    Color.WHITE,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.24f,
                    0.50f,
                    0.76f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        glow.shader = gradient
        glow.alpha = 56
        glow.strokeWidth = dp(10f)
        canvas.drawPath(path, glow)

        glow.alpha = 34
        glow.strokeWidth = dp(5f)
        canvas.drawPath(path, glow)

        stroke.shader = gradient
        stroke.alpha = 220
        stroke.strokeWidth = dp(1.25f)
        canvas.drawPath(path, stroke)

        glow.shader = null
        stroke.shader = null

        drawSpectrum(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )
    }

    private fun drawSpectrum(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val count = 104
        val exclusion = radius * 0.74f

        for (i in 0 until count) {
            val x =
                width *
                    i /
                    (count - 1).toFloat()

            val d = abs(x - cx)

            if (d < exclusion) continue

            val edge =
                (
                    (d - exclusion) /
                        (width * 0.50f - exclusion)
                    )
                    .coerceIn(0f, 1f)

            val pulse =
                abs(
                    sin(
                        now / 250.0 +
                            i * 0.67
                    )
                        .toFloat()
                )

            val h =
                radius *
                    (
                        0.018f +
                            edge * 0.075f +
                            pulse * 0.065f
                        ) *
                    motion.waveStrength

            val color =
                when(i % 4) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    else -> palette.accent
                }

            glow.color = color
            glow.alpha = 26
            glow.strokeWidth = dp(2.4f)

            canvas.drawLine(
                x,
                cy - h,
                x,
                cy + h,
                glow
            )

            stroke.color = color
            stroke.alpha = 105
            stroke.strokeWidth = dp(0.45f)

            canvas.drawLine(
                x,
                cy - h,
                x,
                cy + h,
                stroke
            )
        }
    }

    /**
     * Controlled volumetric glow.
     * Intentionally much dimmer than v19 to prevent blown-out white core.
     */
    private fun drawCoreGlow(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        now: Long
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(now / motion.breathePeriodMs)
                )
                .toFloat()

        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.73f,
                intArrayOf(
                    withAlpha(Color.WHITE, 112),
                    withAlpha(palette.primary, 145),
                    withAlpha(palette.secondary, 74),
                    withAlpha(palette.primary, 24),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.20f,
                    0.50f,
                    0.74f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.70f +
                        pulse * 0.015f
                    ),
            fill
        )
        fill.shader = null

        // Small white-hot center only, not the whole core.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.22f,
                intArrayOf(
                    withAlpha(Color.WHITE, 170),
                    withAlpha(palette.primary, 82),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.36f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 0.22f,
            fill
        )
        fill.shader = null
    }

    /**
     * Reference-style luminous energy loops.
     * Uses controlled Lissajous paths instead of dense random scribbles.
     */
    private fun drawLissajousRibbons(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val ribbonCount = 24

        for (ribbon in 0 until ribbonCount) {
            path.reset()

            val samples = 156
            val phase =
                now /
                    motion.ribbonPeriodMs +
                    ribbon * 0.41

            val family =
                ribbon % 4

            val ax =
                when(family) {
                    0 -> 3.0
                    1 -> 4.0
                    2 -> 5.0
                    else -> 6.0
                }

            val ay =
                when(family) {
                    0 -> 4.0
                    1 -> 5.0
                    2 -> 6.0
                    else -> 7.0
                }

            val scale =
                0.74f +
                    (ribbon % 6) * 0.018f

            val rx =
                radius *
                    scale

            val ry =
                radius *
                    (
                        0.63f +
                            (ribbon % 5) * 0.022f
                        )

            for (i in 0..samples) {
                val t =
                    i /
                        samples.toFloat()

                val a =
                    t *
                        PI *
                        2.0

                val x =
                    cx +
                        sin(
                            ax * a +
                                phase
                        )
                            .toFloat() *
                        rx *
                        0.72f

                val y =
                    cy +
                        sin(
                            ay * a -
                                phase * 0.86 +
                                ribbon * 0.12
                        )
                            .toFloat() *
                        ry *
                        0.72f

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            val color =
                when(ribbon % 5) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    3 -> palette.accent
                    else -> palette.primary
                }

            if (ribbon % 4 == 0) {
                glow.color = color
                glow.alpha = 30
                glow.strokeWidth = dp(7f)
                canvas.drawPath(path, glow)
            }

            stroke.color = color
            stroke.alpha =
                if (color == Color.WHITE) {
                    190
                } else {
                    155
                }

            stroke.strokeWidth =
                dp(
                    if (color == Color.WHITE) {
                        1.05f
                    } else {
                        0.68f
                    }
                )

            canvas.drawPath(path, stroke)
        }
    }

    private fun drawReferenceRings(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        for (i in 0 until 5) {
            val rr =
                radius *
                    (
                        0.28f +
                            i * 0.095f
                        )

            stroke.color =
                when(i % 3) {
                    0 -> palette.primary
                    1 -> palette.secondary
                    else -> Color.WHITE
                }

            stroke.alpha =
                48 +
                    i * 9

            stroke.strokeWidth =
                dp(
                    if (i % 2 == 0) {
                        0.65f
                    } else {
                        0.42f
                    }
                )

            canvas.drawCircle(
                cx,
                cy,
                rr,
                stroke
            )
        }
    }

    /**
     * Clean round halo particles. No square AGSL artifacts.
     */
    private fun drawHaloParticles(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val count = 88
        val rotation = now / motion.haloPeriodMs

        for (i in 0 until count) {
            val angle =
                i *
                    PI *
                    2.0 /
                    count +
                    rotation *
                        (
                            if (i % 2 == 0) {
                                1.0
                            } else {
                                -0.38
                            }
                        )

            val shell =
                when(i % 4) {
                    0 -> 0.88f
                    1 -> 0.93f
                    2 -> 0.98f
                    else -> 1.025f
                }

            val rr =
                radius *
                    shell

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    rr

            val y =
                cy +
                    sin(angle)
                        .toFloat() *
                    rr

            val sparkle =
                (
                    0.5 +
                        0.5 *
                            sin(
                                now / 380.0 +
                                    i * 0.91
                            )
                    )
                    .toFloat()

            val color =
                when(i % 4) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    else -> palette.accent
                }

            fill.color = color
            fill.alpha =
                (
                    70 +
                        sparkle * 130f
                    )
                    .toInt()

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 17 == 0) {
                        1.8f
                    } else {
                        0.70f
                    }
                ),
                fill
            )

            if (i % 22 == 0) {
                drawStar(
                    canvas = canvas,
                    x = x,
                    y = y,
                    size =
                        dp(
                            3.8f +
                                sparkle * 3.0f
                        ),
                    color = color
                )
            }
        }
    }

    private fun drawStar(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        color: Int
    ) {
        glow.color = color
        glow.alpha = 36
        glow.strokeWidth = dp(3f)

        canvas.drawLine(
            x - size,
            y,
            x + size,
            y,
            glow
        )

        canvas.drawLine(
            x,
            y - size,
            x,
            y + size,
            glow
        )

        stroke.color = Color.WHITE
        stroke.alpha = 165
        stroke.strokeWidth = dp(0.55f)

        canvas.drawLine(
            x - size,
            y,
            x + size,
            y,
            stroke
        )

        canvas.drawLine(
            x,
            y - size,
            x,
            y + size,
            stroke
        )
    }

    /**
     * Reference wordmark: dark center with bright cyan/white edges.
     */
    private fun drawAyanaWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val glyphHeight =
            radius *
                0.30f

        val widths =
            floatArrayOf(
                glyphHeight * 0.72f,
                glyphHeight * 0.72f,
                glyphHeight * 0.72f,
                glyphHeight * 0.78f,
                glyphHeight * 0.72f
            )

        val gap =
            radius *
                0.048f

        val totalWidth =
            widths.sum() +
                gap * 4f

        val startX =
            cx -
                totalWidth * 0.50f

        val top =
            cy -
                glyphHeight * 0.50f

        val bottom =
            cy +
                glyphHeight * 0.50f

        // Outer glow.
        glow.color = palette.primary
        glow.alpha = 86
        glow.strokeWidth = dp(10f)

        drawWordmarkGeometry(
            canvas,
            startX,
            top,
            bottom,
            widths,
            gap,
            glow
        )

        // Bright edge.
        stroke.color = Color.WHITE
        stroke.alpha = 230
        stroke.strokeWidth = dp(4.0f)

        drawWordmarkGeometry(
            canvas,
            startX,
            top,
            bottom,
            widths,
            gap,
            stroke
        )

        // Colored inner edge.
        stroke.color = palette.primary
        stroke.alpha = 235
        stroke.strokeWidth = dp(2.5f)

        drawWordmarkGeometry(
            canvas,
            startX,
            top,
            bottom,
            widths,
            gap,
            stroke
        )

        // Dark core of the stroke so the letters remain readable over the glow.
        stroke.color = Color.parseColor("#07121A")
        stroke.alpha = 245
        stroke.strokeWidth = dp(1.2f)

        drawWordmarkGeometry(
            canvas,
            startX,
            top,
            bottom,
            widths,
            gap,
            stroke
        )
    }

    private fun drawWordmarkGeometry(
        canvas: Canvas,
        startX: Float,
        top: Float,
        bottom: Float,
        widths: FloatArray,
        gap: Float,
        paint: Paint
    ) {
        var x = startX

        drawLambda(
            canvas,
            x,
            top,
            bottom,
            widths[0],
            paint
        )

        x += widths[0] + gap

        drawY(
            canvas,
            x,
            top,
            bottom,
            widths[1],
            paint
        )

        x += widths[1] + gap

        drawLambda(
            canvas,
            x,
            top,
            bottom,
            widths[2],
            paint
        )

        x += widths[2] + gap

        drawN(
            canvas,
            x,
            top,
            bottom,
            widths[3],
            paint
        )

        x += widths[3] + gap

        drawLambda(
            canvas,
            x,
            top,
            bottom,
            widths[4],
            paint
        )
    }

    private fun drawLambda(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        val center =
            left +
                width * 0.50f

        canvas.drawLine(
            left,
            bottom,
            center,
            top,
            paint
        )

        canvas.drawLine(
            center,
            top,
            left + width,
            bottom,
            paint
        )
    }

    private fun drawY(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        val center =
            left +
                width * 0.50f

        val fork =
            top +
                (
                    bottom - top
                    ) *
                    0.45f

        canvas.drawLine(
            left,
            top,
            center,
            fork,
            paint
        )

        canvas.drawLine(
            left + width,
            top,
            center,
            fork,
            paint
        )

        canvas.drawLine(
            center,
            fork,
            center,
            bottom,
            paint
        )
    }

    private fun drawN(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        canvas.drawLine(
            left,
            bottom,
            left,
            top,
            paint
        )

        canvas.drawLine(
            left,
            top,
            left + width,
            bottom,
            paint
        )

        canvas.drawLine(
            left + width,
            bottom,
            left + width,
            top,
            paint
        )
    }

    private fun stateFor(
        state: String
    ): AgentState {
        return when(state) {
            AyanaVoiceService.STATE_LISTENING ->
                AgentState.WAITING

            AyanaVoiceService.STATE_COMMAND ->
                AgentState.RECOGNITION

            AyanaVoiceService.STATE_THINKING ->
                AgentState.THINKING

            AyanaVoiceService.STATE_EXECUTING ->
                AgentState.EXECUTING

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT,
            AyanaVoiceService.STATE_SUCCESS ->
                AgentState.ANSWERING

            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR ->
                AgentState.STOP

            else ->
                AgentState.WAITING
        }
    }

    private fun paletteFor(
        state: AgentState
    ): Palette {
        return when(state) {
            AgentState.WAITING ->
                Palette(
                    primary = Color.parseColor("#00F7FF"),
                    secondary = Color.parseColor("#00C9E8"),
                    accent = Color.parseColor("#A7FFFF")
                )

            AgentState.RECOGNITION ->
                Palette(
                    primary = Color.parseColor("#1378FF"),
                    secondary = Color.parseColor("#00A8FF"),
                    accent = Color.parseColor("#B5DEFF")
                )

            AgentState.THINKING ->
                Palette(
                    primary = Color.parseColor("#5635FF"),
                    secondary = Color.parseColor("#8A38FF"),
                    accent = Color.parseColor("#C9B7FF")
                )

            AgentState.EXECUTING ->
                Palette(
                    primary = Color.parseColor("#16E66B"),
                    secondary = Color.parseColor("#00C98C"),
                    accent = Color.parseColor("#A0FFD0")
                )

            AgentState.ANSWERING ->
                Palette(
                    primary = Color.parseColor("#FF28C8"),
                    secondary = Color.parseColor("#D52CFF"),
                    accent = Color.parseColor("#FFB0EF")
                )

            AgentState.STOP ->
                Palette(
                    primary = Color.parseColor("#FF3425"),
                    secondary = Color.parseColor("#FF7200"),
                    accent = Color.parseColor("#FFB08D")
                )
        }
    }

    private fun motionFor(
        state: AgentState
    ): Motion {
        return when(state) {
            AgentState.WAITING ->
                Motion(
                    frameDelayMs = 38L,
                    breathePeriodMs = 1050.0,
                    wavePeriodMs = 820.0,
                    waveStrength = 0.78f,
                    ribbonPeriodMs = 1900.0,
                    haloPeriodMs = 5600.0
                )

            AgentState.RECOGNITION ->
                Motion(
                    frameDelayMs = 28L,
                    breathePeriodMs = 760.0,
                    wavePeriodMs = 430.0,
                    waveStrength = 1.02f,
                    ribbonPeriodMs = 1250.0,
                    haloPeriodMs = 3200.0
                )

            AgentState.THINKING ->
                Motion(
                    frameDelayMs = 27L,
                    breathePeriodMs = 700.0,
                    wavePeriodMs = 500.0,
                    waveStrength = 0.92f,
                    ribbonPeriodMs = 1050.0,
                    haloPeriodMs = 2700.0
                )

            AgentState.EXECUTING ->
                Motion(
                    frameDelayMs = 26L,
                    breathePeriodMs = 620.0,
                    wavePeriodMs = 360.0,
                    waveStrength = 1.05f,
                    ribbonPeriodMs = 900.0,
                    haloPeriodMs = 2300.0
                )

            AgentState.ANSWERING ->
                Motion(
                    frameDelayMs = 27L,
                    breathePeriodMs = 680.0,
                    wavePeriodMs = 390.0,
                    waveStrength = 1.03f,
                    ribbonPeriodMs = 980.0,
                    haloPeriodMs = 2600.0
                )

            AgentState.STOP ->
                Motion(
                    frameDelayMs = 80L,
                    breathePeriodMs = 1500.0,
                    wavePeriodMs = 1200.0,
                    waveStrength = 0.48f,
                    ribbonPeriodMs = 3000.0,
                    haloPeriodMs = 9000.0
                )
        }
    }

    private fun withAlpha(
        color: Int,
        alpha: Int
    ): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(
        value: Float
    ): Float {
        return value * density
    }

    private enum class AgentState {
        WAITING,
        RECOGNITION,
        THINKING,
        EXECUTING,
        ANSWERING,
        STOP
    }

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int
    )

    private data class Motion(
        val frameDelayMs: Long,
        val breathePeriodMs: Double,
        val wavePeriodMs: Double,
        val waveStrength: Float,
        val ribbonPeriodMs: Double,
        val haloPeriodMs: Double
    )
}
