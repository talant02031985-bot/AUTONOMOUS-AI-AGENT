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
 * AYANA Core Visualizer v20.0 — SPHERICAL REFERENCE CORE
 *
 * Purpose:
 * - keep the approved reference geometry: round luminous energy sphere,
 *   circular telemetry halo, centered AYANA wordmark, horizontal waveform;
 * - use the SAME geometry for all six states;
 * - change only palette, speed and intensity.
 *
 * Major fix vs v19.2:
 * - removes Lissajous x/y curves that created a visible square/box;
 * - inner ribbons are now projected 3D spherical great-circle loops,
 *   so the energy mass remains circular/volumetric at every frame.
 *
 * No PNG/JPEG.
 * No RuntimeShader/AGSL.
 * No buttons / agent diagrams / fake load.
 * No ORB / routing / TTS / microphone / Accessibility changes.
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
                h * 0.455f,
                w * 0.315f
            )
                .coerceAtLeast(dp(42f))

        drawAmbientField(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette
        )

        drawReferenceWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawVolumetricCore(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawSphericalRibbons(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawConcentricReferenceRings(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawOuterEnergyHalo(
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

    private fun drawAmbientField(
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
                radius * 1.10f,
                intArrayOf(
                    withAlpha(palette.primary, 30),
                    withAlpha(palette.secondary, 12),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.60f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 1.10f,
            fill
        )
        fill.shader = null
    }

    /**
     * Horizontal signal crossing the sphere, matching the reference.
     * The center is quieter; the side waveforms are stronger.
     */
    private fun drawReferenceWaveform(
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
        val samples = 192
        val phase = now / motion.wavePeriodMs

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + (right - left) * t

            val distance =
                abs(
                    (x - cx) /
                        (width * 0.50f)
                )
                    .coerceIn(0f, 1f)

            val envelope =
                (
                    0.055f +
                        distance * 1.02f
                    )
                    .coerceIn(0.055f, 1f)

            val main =
                sin(
                    t * PI * 8.0 +
                        phase
                )
                    .toFloat()

            val detail =
                sin(
                    t * PI * 27.0 -
                        phase * 0.70
                )
                    .toFloat() *
                    0.24f

            val fine =
                sin(
                    t * PI * 63.0 +
                        phase * 1.11
                )
                    .toFloat() *
                    0.075f

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
                    0.25f,
                    0.50f,
                    0.75f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        glow.shader = gradient
        glow.alpha = 48
        glow.strokeWidth = dp(9f)
        canvas.drawPath(path, glow)

        glow.alpha = 28
        glow.strokeWidth = dp(4.5f)
        canvas.drawPath(path, glow)

        stroke.shader = gradient
        stroke.alpha = 220
        stroke.strokeWidth = dp(1.15f)
        canvas.drawPath(path, stroke)

        glow.shader = null
        stroke.shader = null

        drawSideSpectrum(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )
    }

    private fun drawSideSpectrum(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val count = 112
        val exclusion = radius * 0.72f

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
                        now / 245.0 +
                            i * 0.69
                    )
                        .toFloat()
                )

            val barHeight =
                radius *
                    (
                        0.014f +
                            edge * 0.070f +
                            pulse * 0.060f
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
            glow.alpha = 22
            glow.strokeWidth = dp(2.2f)

            canvas.drawLine(
                x,
                cy - barHeight,
                x,
                cy + barHeight,
                glow
            )

            stroke.color = color
            stroke.alpha = 100
            stroke.strokeWidth = dp(0.42f)

            canvas.drawLine(
                x,
                cy - barHeight,
                x,
                cy + barHeight,
                stroke
            )
        }
    }

    /**
     * Controlled luminous volume.
     * Keeps a bright center but does not blow out the entire object.
     */
    private fun drawVolumetricCore(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                motion.breathePeriodMs
                        )
                )
                .toFloat()

        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.76f,
                intArrayOf(
                    withAlpha(Color.WHITE, 122),
                    withAlpha(palette.primary, 150),
                    withAlpha(palette.secondary, 72),
                    withAlpha(palette.primary, 20),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.17f,
                    0.46f,
                    0.72f,
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
                    0.72f +
                        pulse * 0.012f
                    ),
            fill
        )
        fill.shader = null

        // Small hot core.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.20f,
                intArrayOf(
                    withAlpha(Color.WHITE, 180),
                    withAlpha(palette.primary, 90),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.40f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 0.20f,
            fill
        )
        fill.shader = null
    }

    /**
     * 3D projected great-circle loops.
     *
     * Every ribbon is a circular loop on a virtual sphere with a different
     * 3D orientation. Projecting those loops to 2D creates the round woven
     * energy geometry seen in the reference and cannot form the square box
     * produced by the previous Lissajous implementation.
     */
    private fun drawSphericalRibbons(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val ribbonCount = 30
        val samples = 128

        for (ribbon in 0 until ribbonCount) {
            path.reset()

            val u =
                ribbon /
                    ribbonCount.toFloat()

            val tiltX =
                (
                    -0.92 +
                        u * 1.84
                    )

            val tiltY =
                sin(
                    ribbon * 1.618
                ) *
                    0.88

            val tiltZ =
                ribbon * 0.73 +
                    now /
                        motion.ribbonPeriodMs *
                        (
                            if (ribbon % 2 == 0) {
                                1.0
                            } else {
                                -0.74
                            }
                        )

            val scale =
                0.68f +
                    (ribbon % 6) * 0.018f

            for (i in 0..samples) {
                val t =
                    i /
                        samples.toFloat() *
                        PI *
                        2.0

                var x3 = cos(t)
                var y3 = sin(t)
                var z3 = 0.0

                // Rotate around X.
                run {
                    val c = cos(tiltX)
                    val s = sin(tiltX)
                    val ny = y3 * c - z3 * s
                    val nz = y3 * s + z3 * c
                    y3 = ny
                    z3 = nz
                }

                // Rotate around Y.
                run {
                    val c = cos(tiltY)
                    val s = sin(tiltY)
                    val nx = x3 * c + z3 * s
                    val nz = -x3 * s + z3 * c
                    x3 = nx
                    z3 = nz
                }

                // Rotate around Z.
                run {
                    val c = cos(tiltZ)
                    val s = sin(tiltZ)
                    val nx = x3 * c - y3 * s
                    val ny = x3 * s + y3 * c
                    x3 = nx
                    y3 = ny
                }

                val breathing =
                    1.0 +
                        sin(
                            t * 3.0 +
                                ribbon * 0.47 +
                                now /
                                    motion.ribbonPeriodMs *
                                    0.6
                        ) *
                            motion.ribbonWarp

                val perspective =
                    1.0 +
                        z3 * 0.08

                val x =
                    cx +
                        (
                            x3 *
                                radius *
                                scale *
                                breathing *
                                perspective
                            )
                            .toFloat()

                val y =
                    cy +
                        (
                            y3 *
                                radius *
                                scale *
                                breathing *
                                perspective
                            )
                            .toFloat()

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

            if (ribbon % 5 == 0) {
                glow.color = color
                glow.alpha = 30
                glow.strokeWidth = dp(7f)
                canvas.drawPath(path, glow)
            }

            if (ribbon % 11 == 0) {
                glow.color = palette.primary
                glow.alpha = 20
                glow.strokeWidth = dp(11f)
                canvas.drawPath(path, glow)
            }

            stroke.color = color
            stroke.alpha =
                if (color == Color.WHITE) {
                    208
                } else {
                    168
                }

            stroke.strokeWidth =
                dp(
                    if (color == Color.WHITE) {
                        1.10f
                    } else {
                        0.70f
                    }
                )

            canvas.drawPath(path, stroke)
        }
    }

    private fun drawConcentricReferenceRings(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val phase =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                motion.breathePeriodMs *
                                0.72
                        )
                )
                .toFloat()

        for (i in 0 until 7) {
            val rr =
                radius *
                    (
                        0.23f +
                            i * 0.083f +
                            phase * 0.003f
                        )

            stroke.color =
                when(i % 3) {
                    0 -> palette.primary
                    1 -> palette.secondary
                    else -> Color.WHITE
                }

            stroke.alpha =
                34 +
                    i * 7

            stroke.strokeWidth =
                dp(
                    if (i % 2 == 0) {
                        0.55f
                    } else {
                        0.36f
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
     * Circular outer halo only: dots, tiny streaks and star flashes.
     * No square cells / box geometry.
     */
    private fun drawOuterEnergyHalo(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val count = 150
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
                when(i % 5) {
                    0 -> 0.84f
                    1 -> 0.89f
                    2 -> 0.94f
                    3 -> 0.99f
                    else -> 1.025f
                }

            val rr =
                radius *
                    shell +
                    sin(
                        now / 520.0 +
                            i * 0.79
                    )
                        .toFloat() *
                        radius *
                        0.008f

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
                                now / 330.0 +
                                    i * 0.93
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
                    58 +
                        sparkle * 135f
                    )
                    .toInt()
                    .coerceAtMost(220)

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 23 == 0) {
                        1.75f
                    } else {
                        0.62f
                    }
                ),
                fill
            )

            if (i % 19 == 0) {
                val tx =
                    -sin(angle)
                        .toFloat()

                val ty =
                    cos(angle)
                        .toFloat()

                val half =
                    dp(
                        1.6f +
                            sparkle * 3.6f
                    )

                stroke.color = color
                stroke.alpha = 120
                stroke.strokeWidth = dp(0.48f)

                canvas.drawLine(
                    x - tx * half,
                    y - ty * half,
                    x + tx * half,
                    y + ty * half,
                    stroke
                )
            }

            if (i % 47 == 0) {
                drawStar(
                    canvas = canvas,
                    x = x,
                    y = y,
                    size =
                        dp(
                            3.6f +
                                sparkle * 2.8f
                        ),
                    color = color
                )
            }
        }

        // Two thin bright perimeter rings.
        stroke.color = palette.primary
        stroke.alpha = 72
        stroke.strokeWidth = dp(0.70f)
        canvas.drawCircle(
            cx,
            cy,
            radius * 0.965f,
            stroke
        )

        stroke.color = palette.secondary
        stroke.alpha = 54
        stroke.strokeWidth = dp(0.45f)
        canvas.drawCircle(
            cx,
            cy,
            radius * 1.015f,
            stroke
        )
    }

    private fun drawStar(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        color: Int
    ) {
        glow.color = color
        glow.alpha = 30
        glow.strokeWidth = dp(2.8f)

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
        stroke.alpha = 155
        stroke.strokeWidth = dp(0.50f)

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
     * Thick geometric ΛYΛNΛ:
     * bright edge + palette edge + dark inner stroke, closer to the reference.
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
                0.305f

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
                0.047f

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

        glow.color = palette.primary
        glow.alpha = 82
        glow.strokeWidth = dp(11f)

        drawWordmarkGeometry(
            canvas,
            startX,
            top,
            bottom,
            widths,
            gap,
            glow
        )

        stroke.color = Color.WHITE
        stroke.alpha = 228
        stroke.strokeWidth = dp(5.0f)

        drawWordmarkGeometry(
            canvas,
            startX,
            top,
            bottom,
            widths,
            gap,
            stroke
        )

        stroke.color = palette.primary
        stroke.alpha = 238
        stroke.strokeWidth = dp(3.3f)

        drawWordmarkGeometry(
            canvas,
            startX,
            top,
            bottom,
            widths,
            gap,
            stroke
        )

        stroke.color = Color.parseColor("#061018")
        stroke.alpha = 250
        stroke.strokeWidth = dp(1.65f)

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
                PALETTE_WAITING

            AgentState.RECOGNITION ->
                PALETTE_RECOGNITION

            AgentState.THINKING ->
                PALETTE_THINKING

            AgentState.EXECUTING ->
                PALETTE_EXECUTING

            AgentState.ANSWERING ->
                PALETTE_ANSWERING

            AgentState.STOP ->
                PALETTE_STOP
        }
    }

    private fun motionFor(
        state: AgentState
    ): Motion {
        return when(state) {
            AgentState.WAITING ->
                MOTION_WAITING

            AgentState.RECOGNITION ->
                MOTION_RECOGNITION

            AgentState.THINKING ->
                MOTION_THINKING

            AgentState.EXECUTING ->
                MOTION_EXECUTING

            AgentState.ANSWERING ->
                MOTION_ANSWERING

            AgentState.STOP ->
                MOTION_STOP
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
        val ribbonWarp: Float,
        val haloPeriodMs: Double
    )

    companion object {

        // 1. Ожидание — aqua/cyan.
        private val PALETTE_WAITING =
            Palette(
                primary = Color.parseColor("#00F4F6"),
                secondary = Color.parseColor("#00C8EE"),
                accent = Color.parseColor("#A9FFFF")
            )

        // 2. Распознавание — electric blue.
        private val PALETTE_RECOGNITION =
            Palette(
                primary = Color.parseColor("#087CFF"),
                secondary = Color.parseColor("#00BFFF"),
                accent = Color.parseColor("#A6E5FF")
            )

        // 3. Думаю — indigo/violet.
        private val PALETTE_THINKING =
            Palette(
                primary = Color.parseColor("#4F3BFF"),
                secondary = Color.parseColor("#7C38FF"),
                accent = Color.parseColor("#C9B8FF")
            )

        // 4. Выполняю — neon green.
        private val PALETTE_EXECUTING =
            Palette(
                primary = Color.parseColor("#17E96A"),
                secondary = Color.parseColor("#00CE99"),
                accent = Color.parseColor("#A7FFD1")
            )

        // 5. Отвечаю — magenta/fuchsia.
        private val PALETTE_ANSWERING =
            Palette(
                primary = Color.parseColor("#FF25C8"),
                secondary = Color.parseColor("#D42DFF"),
                accent = Color.parseColor("#FFB4EF")
            )

        // 6. Стоп — red/orange.
        private val PALETTE_STOP =
            Palette(
                primary = Color.parseColor("#FF2B22"),
                secondary = Color.parseColor("#FF6B00"),
                accent = Color.parseColor("#FFC09A")
            )

        private val MOTION_WAITING =
            Motion(
                frameDelayMs = 38L,
                breathePeriodMs = 1100.0,
                wavePeriodMs = 850.0,
                waveStrength = 0.78f,
                ribbonPeriodMs = 2100.0,
                ribbonWarp = 0.016f,
                haloPeriodMs = 6000.0
            )

        private val MOTION_RECOGNITION =
            Motion(
                frameDelayMs = 29L,
                breathePeriodMs = 780.0,
                wavePeriodMs = 450.0,
                waveStrength = 1.00f,
                ribbonPeriodMs = 1450.0,
                ribbonWarp = 0.018f,
                haloPeriodMs = 3600.0
            )

        private val MOTION_THINKING =
            Motion(
                frameDelayMs = 27L,
                breathePeriodMs = 720.0,
                wavePeriodMs = 520.0,
                waveStrength = 0.92f,
                ribbonPeriodMs = 1180.0,
                ribbonWarp = 0.022f,
                haloPeriodMs = 3000.0
            )

        private val MOTION_EXECUTING =
            Motion(
                frameDelayMs = 26L,
                breathePeriodMs = 650.0,
                wavePeriodMs = 380.0,
                waveStrength = 1.04f,
                ribbonPeriodMs = 1000.0,
                ribbonWarp = 0.020f,
                haloPeriodMs = 2500.0
            )

        private val MOTION_ANSWERING =
            Motion(
                frameDelayMs = 27L,
                breathePeriodMs = 700.0,
                wavePeriodMs = 410.0,
                waveStrength = 1.02f,
                ribbonPeriodMs = 1080.0,
                ribbonWarp = 0.019f,
                haloPeriodMs = 2850.0
            )

        private val MOTION_STOP =
            Motion(
                frameDelayMs = 82L,
                breathePeriodMs = 1600.0,
                wavePeriodMs = 1250.0,
                waveStrength = 0.46f,
                ribbonPeriodMs = 3300.0,
                ribbonWarp = 0.010f,
                haloPeriodMs = 9500.0
            )
    }
}
