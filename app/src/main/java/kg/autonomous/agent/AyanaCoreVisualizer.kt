package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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
 * AYANA Core Visualizer v14.0 — LIVE AI ENERGY CORE
 *
 * Procedural live renderer based on the approved six-state AYANA reference:
 *
 * 1. ОЖИДАНИЕ       — cyan / calm coherent energy field
 * 2. РАСПОЗНАВАНИЕ  — electric blue / inbound analysis scan
 * 3. ДУМАЮ          — indigo-violet / branching reasoning field
 * 4. ВЫПОЛНЯЮ       — green / directed action flow
 * 5. ОТВЕЧАЮ        — magenta / coherent outward response wave
 * 6. СТОП           — red-orange / fractured halted field
 *
 * Important:
 * - NO bitmap/photo is used;
 * - NO six-button strip is drawn;
 * - the whole visual is rendered live with Android Canvas;
 * - all geometry is bounded mathematically inside this View;
 * - animation is state-reactive UI motion, not claimed as measured mic/model load;
 * - no ORB, routing, TTS, microphone, Accessibility or execution logic changes.
 *
 * Integration contract remains:
 *   AyanaCoreVisualizer(Context)
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density = resources.displayMetrics.density

    private val fill =
        Paint(Paint.ANTI_ALIAS_FLAG)

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

    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private val path = Path()
    private val finePath = Path()

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

        val state = stateIndex(AyanaVoiceService.currentStatusState)
        val palette = paletteFor(state)
        val motion = motionFor(state)
        val now = SystemClock.uptimeMillis()

        val w = width.toFloat()
        val h = height.toFloat()

        val cx = w * 0.50f
        val cy = h * 0.50f

        val edge = dp(10f)

        // The largest decorative element is <= 1.16R.
        val maxRByHeight =
            (h * 0.50f - edge) / 1.16f

        val maxRByWidth =
            (w * 0.50f - edge) / 1.16f

        val preferred =
            min(
                h * 0.415f,
                w * 0.315f
            )

        val radius =
            min(
                preferred,
                min(
                    maxRByHeight,
                    maxRByWidth
                )
            )
                .coerceAtLeast(dp(36f))

        drawBackdrop(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawOuterTelemetry(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawEnergyRibbons(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawCoreRings(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawStateDynamics(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawWordmark(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        if (attached) {
            postInvalidateDelayed(motion.frameDelayMs)
        }
    }

    private fun drawBackdrop(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 1.16f,
                intArrayOf(
                    withAlpha(palette.primary, if (state == STATE_STOP) 38 else 66),
                    withAlpha(palette.secondary, if (state == STATE_STOP) 18 else 34),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.58f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 1.16f,
            fill
        )
        fill.shader = null

        // Very faint center-axis reference, like the approved artwork,
        // but not a dashboard grid.
        stroke.color = withAlpha(palette.primary, 34)
        stroke.alpha = 120
        stroke.strokeWidth = dp(0.55f)

        canvas.drawLine(
            cx - radius * 1.12f,
            cy,
            cx + radius * 1.12f,
            cy,
            stroke
        )
    }

    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        path.reset()
        finePath.reset()

        val left = cx - radius * 1.14f
        val right = cx + radius * 1.14f
        val samples = 112

        val phase = now / motion.wavePeriodMs

        val stateBoost =
            when(state) {
                STATE_RECOGNITION -> 1.55f
                STATE_EXECUTING -> 1.34f
                STATE_ANSWERING -> 1.50f
                STATE_STOP -> 0.42f
                else -> 1f
            }

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + (right - left) * t

            val centerDistance =
                abs(
                    (x - cx) /
                        (radius * 1.14f)
                )
                    .coerceIn(0f, 1f)

            val envelope =
                (
                    0.28f +
                        0.72f *
                            centerDistance
                    )
                    .coerceIn(0.28f, 1f)

            val main =
                sin(
                    t *
                        PI *
                        motion.waveCycles +
                        phase
                )
                    .toFloat()

            val detail =
                sin(
                    t *
                        PI *
                        34.0 -
                        phase *
                            0.73
                )
                    .toFloat() *
                    0.26f

            val responseDetail =
                if (state == STATE_ANSWERING) {
                    sin(
                        t *
                            PI *
                            18.0 +
                            phase *
                                1.35
                    )
                        .toFloat() *
                        0.20f
                } else {
                    0f
                }

            val y =
                cy +
                    (
                        main +
                            detail +
                            responseDetail
                    ) *
                    radius *
                    0.12f *
                    envelope *
                    stateBoost

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }

            val fine =
                sin(
                    t *
                        PI *
                        62.0 +
                        phase *
                            1.12
                )
                    .toFloat()

            val fy =
                cy +
                    fine *
                    radius *
                    0.038f *
                    envelope *
                    stateBoost

            if (i == 0) {
                finePath.moveTo(x, fy)
            } else {
                finePath.lineTo(x, fy)
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
        glow.alpha = motion.waveGlowAlpha
        glow.strokeWidth = dp(7.5f)
        canvas.drawPath(path, glow)

        stroke.shader = gradient
        stroke.alpha = motion.waveAlpha
        stroke.strokeWidth = dp(1.55f)
        canvas.drawPath(path, stroke)

        stroke.shader = null
        glow.shader = null

        stroke.color = Color.WHITE
        stroke.alpha =
            if (state == STATE_STOP) {
                38
            } else {
                92
            }
        stroke.strokeWidth = dp(0.52f)
        canvas.drawPath(finePath, stroke)
    }

    /**
     * Braided inner field. The lines are deliberately non-uniform and
     * continuously phase-shifted so this is a real live renderer, not a photo.
     */
    private fun drawEnergyRibbons(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val ribbonCount =
            when(state) {
                STATE_WAITING -> 9
                STATE_RECOGNITION -> 11
                STATE_THINKING -> 14
                STATE_EXECUTING -> 12
                STATE_ANSWERING -> 11
                STATE_STOP -> 7
                else -> 9
            }

        for (ribbon in 0 until ribbonCount) {
            path.reset()

            val points = 112
            val phase =
                now /
                    motion.ribbonPeriodMs +
                    ribbon *
                        0.63

            val skew =
                (
                    ribbon -
                        (ribbonCount - 1) *
                            0.5f
                    ) /
                    ribbonCount.toFloat()

            for (i in 0..points) {
                val t =
                    i /
                        points.toFloat()

                val angle =
                    t *
                        PI *
                        2.0 +
                        phase *
                            (
                                if (ribbon % 2 == 0) {
                                    1.0
                                } else {
                                    -0.86
                                }
                            )

                val warp =
                    sin(
                        angle *
                            (
                                2.0 +
                                    ribbon %
                                        3
                                ) +
                            phase *
                                0.72
                    )
                        .toFloat()

                val micro =
                    sin(
                        angle *
                            5.0 -
                            phase *
                                0.56
                    )
                        .toFloat()

                val base =
                    radius *
                        (
                            0.64f +
                                skew *
                                    0.12f
                            )

                val rr =
                    base *
                        (
                            1f +
                                warp *
                                    motion.ribbonWarp +
                                micro *
                                    0.025f
                            )

                val x =
                    cx +
                        cos(angle)
                            .toFloat() *
                        rr

                val y =
                    cy +
                        sin(angle)
                            .toFloat() *
                        rr *
                        (
                            0.78f +
                                skew *
                                    0.18f
                            )

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            val color =
                when(ribbon % 3) {
                    0 -> palette.primary
                    1 -> palette.secondary
                    else -> palette.accent
                }

            if (ribbon % 4 == 0) {
                glow.color = color
                glow.alpha =
                    if (state == STATE_STOP) {
                        24
                    } else {
                        42
                    }
                glow.strokeWidth = dp(5.4f)
                canvas.drawPath(path, glow)
            }

            stroke.color = color
            stroke.alpha =
                when(state) {
                    STATE_THINKING -> 156
                    STATE_EXECUTING -> 150
                    STATE_STOP -> 92
                    else -> 132
                }
            stroke.strokeWidth =
                dp(
                    if (ribbon % 5 == 0) {
                        1.05f
                    } else {
                        0.72f
                    }
                )

            canvas.drawPath(path, stroke)
        }
    }

    private fun drawCoreRings(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
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

        // Inner luminous lens.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.62f,
                intArrayOf(
                    withAlpha(Color.WHITE, if (state == STATE_STOP) 100 else 205),
                    withAlpha(palette.primary, if (state == STATE_STOP) 95 else 178),
                    withAlpha(palette.secondary, if (state == STATE_STOP) 52 else 86),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.20f,
                    0.60f,
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
                    0.52f +
                        pulse *
                            0.018f
                    ),
            fill
        )
        fill.shader = null

        // Concentric technical rings.
        for (ringIndex in 0 until 8) {
            val rr =
                radius *
                    (
                        0.24f +
                            ringIndex *
                                0.082f
                        )

            stroke.color =
                when(ringIndex % 3) {
                    0 -> palette.primary
                    1 -> palette.secondary
                    else -> palette.accent
                }

            stroke.alpha =
                if (state == STATE_STOP) {
                    58 + ringIndex * 4
                } else {
                    76 + ringIndex * 8
                }

            stroke.strokeWidth =
                dp(
                    if (ringIndex % 3 == 0) {
                        0.95f
                    } else {
                        0.58f
                    }
                )

            canvas.drawCircle(
                cx,
                cy,
                rr,
                stroke
            )
        }

        // Small center point = computational focus, not a separate orb.
        fill.color = Color.WHITE
        fill.alpha =
            if (state == STATE_STOP) {
                135
            } else {
                245
            }
        canvas.drawCircle(
            cx,
            cy,
            dp(2.1f),
            fill
        )
    }

    /**
     * Outer halo made from points and tangential streaks instead of drawArc(),
     * keeping the renderer compatible with the isolated compile harness.
     */
    private fun drawOuterTelemetry(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val outerR = radius * 1.02f
        val haloR = radius * 1.105f

        val rotation =
            now /
                motion.haloPeriodMs

        val points =
            when(state) {
                STATE_THINKING -> 84
                STATE_EXECUTING -> 76
                STATE_STOP -> 58
                else -> 70
            }

        for (i in 0 until points) {
            val angle =
                i *
                    PI *
                    2.0 /
                    points +
                    rotation *
                        (
                            if (i % 2 == 0) {
                                1.0
                            } else {
                                -0.38
                            }
                        )

            val sparkle =
                (
                    0.5 +
                        0.5 *
                            sin(
                                now /
                                    360.0 +
                                    i *
                                        0.91
                            )
                    )
                    .toFloat()

            val rr =
                outerR +
                    sin(
                        i *
                            1.73 +
                            rotation
                    )
                        .toFloat() *
                        radius *
                        0.032f

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

            val color =
                when(i % 3) {
                    0 -> palette.primary
                    1 -> palette.secondary
                    else -> palette.accent
                }

            fill.color = color
            fill.alpha =
                (
                    if (state == STATE_STOP) {
                        55
                    } else {
                        80
                    } +
                        sparkle *
                            if (state == STATE_STOP) {
                                105f
                            } else {
                                155f
                            }
                    )
                    .toInt()
                    .coerceAtMost(245)

            canvas.drawCircle(
                x,
                y,
                dp(
                    0.8f +
                        sparkle *
                            1.25f
                ),
                fill
            )

            if (i % 7 == 0) {
                val tx =
                    -sin(angle)
                        .toFloat()

                val ty =
                    cos(angle)
                        .toFloat()

                val half =
                    dp(
                        2.5f +
                            sparkle *
                                5.5f
                    )

                stroke.color = color
                stroke.alpha =
                    if (state == STATE_STOP) {
                        85
                    } else {
                        165
                    }
                stroke.strokeWidth = dp(0.70f)

                canvas.drawLine(
                    x - tx * half,
                    y - ty * half,
                    x + tx * half,
                    y + ty * half,
                    stroke
                )
            }
        }

        // Sparse outer points.
        for (i in 0 until 32) {
            val angle =
                i *
                    PI *
                    2.0 /
                    32.0 -
                    rotation *
                        0.44

            val rr =
                haloR +
                    sin(
                        now /
                            820.0 +
                            i
                    )
                        .toFloat() *
                        radius *
                        0.018f

            fill.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            fill.alpha =
                if (state == STATE_STOP) {
                    65
                } else {
                    115
                }

            canvas.drawCircle(
                cx +
                    cos(angle)
                        .toFloat() *
                        rr,
                cy +
                    sin(angle)
                        .toFloat() *
                        rr,
                dp(0.8f),
                fill
            )
        }
    }

    private fun drawStateDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        when(state) {
            STATE_WAITING ->
                drawWaitingDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_RECOGNITION ->
                drawRecognitionDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_THINKING ->
                drawThinkingDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_EXECUTING ->
                drawExecutingDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_ANSWERING ->
                drawAnsweringDynamics(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            STATE_STOP ->
                drawStopDynamics(
                    canvas,
                    cx,
                    cy,
                    radius,
                    palette
                )
        }
    }

    private fun drawWaitingDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(now / 980.0)
                )
                .toFloat()

        stroke.color = palette.primary
        stroke.alpha = (95 + pulse * 90f).toInt()
        stroke.strokeWidth = dp(0.85f)

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.86f +
                        pulse *
                            0.014f
                    ),
            stroke
        )
    }

    private fun drawRecognitionDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val sweep =
            (
                now %
                    1500L
                ).toFloat() /
                1500f

        val sweepAngle =
            -PI +
                sweep *
                    PI *
                    2.0

        val x2 =
            cx +
                cos(sweepAngle)
                    .toFloat() *
                radius *
                0.95f

        val y2 =
            cy +
                sin(sweepAngle)
                    .toFloat() *
                radius *
                0.95f

        glow.color = palette.primary
        glow.alpha = 86
        glow.strokeWidth = dp(6f)
        canvas.drawLine(cx, cy, x2, y2, glow)

        stroke.color = Color.WHITE
        stroke.alpha = 225
        stroke.strokeWidth = dp(0.85f)
        canvas.drawLine(cx, cy, x2, y2, stroke)

        // Inbound analysis packets from both sides.
        for (i in 0 until 8) {
            val t =
                (
                    (
                        now /
                            900.0 +
                            i /
                                8.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val side =
                if (i % 2 == 0) {
                    -1f
                } else {
                    1f
                }

            val x =
                cx +
                    side *
                        radius *
                        (
                            1.10f -
                                0.82f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        now /
                            300.0 +
                            i
                    )
                        .toFloat() *
                        radius *
                        0.08f *
                        (
                            1f -
                                t
                            )

            fill.color =
                if (i % 3 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }
            fill.alpha = 225

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 3 == 0) {
                        2f
                    } else {
                        1.2f
                    }
                ),
                fill
            )
        }
    }

    private fun drawThinkingDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val nodeCount = 18

        for (i in 0 until nodeCount) {
            val a =
                i *
                    PI *
                    2.0 /
                    nodeCount +
                    sin(
                        now /
                            1800.0 +
                            i *
                                0.37
                    ) *
                        0.18

            val rr =
                radius *
                    (
                        0.82f +
                            (
                                i %
                                    4
                                ) *
                                0.065f
                        )

            val x =
                cx +
                    cos(a)
                        .toFloat() *
                    rr

            val y =
                cy +
                    sin(a)
                        .toFloat() *
                    rr

            fill.color =
                if (i % 4 == 0) {
                    Color.WHITE
                } else if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            fill.alpha =
                if (i % 4 == 0) {
                    240
                } else {
                    165
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 4 == 0) {
                        2f
                    } else {
                        1.1f
                    }
                ),
                fill
            )

            if (i % 2 == 0) {
                val j =
                    (
                        i * 5 +
                            3
                        ) %
                        nodeCount

                val b =
                    j *
                        PI *
                        2.0 /
                        nodeCount -
                        sin(
                            now /
                                1500.0 +
                                j
                        ) *
                            0.12

                val rr2 =
                    radius *
                        (
                            0.84f +
                                (
                                    j %
                                        3
                                    ) *
                                    0.07f
                            )

                val x2 =
                    cx +
                        cos(b)
                            .toFloat() *
                        rr2

                val y2 =
                    cy +
                        sin(b)
                            .toFloat() *
                        rr2

                stroke.color =
                    if (i % 4 == 0) {
                        palette.accent
                    } else {
                        palette.primary
                    }

                stroke.alpha = 75
                stroke.strokeWidth = dp(0.55f)
                canvas.drawLine(
                    x,
                    y,
                    x2,
                    y2,
                    stroke
                )
            }
        }
    }

    private fun drawExecutingDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val right =
            cx +
                radius *
                    1.12f

        // Chosen action path.
        glow.color = palette.primary
        glow.alpha = 90
        glow.strokeWidth = dp(7f)
        canvas.drawLine(
            cx + radius * 0.32f,
            cy,
            right,
            cy,
            glow
        )

        stroke.color = Color.WHITE
        stroke.alpha = 225
        stroke.strokeWidth = dp(0.95f)
        canvas.drawLine(
            cx + radius * 0.32f,
            cy,
            right,
            cy,
            stroke
        )

        for (i in 0 until 9) {
            val t =
                (
                    (
                        now /
                            720.0 +
                            i /
                                9.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val x =
                cx +
                    radius *
                        (
                            0.34f +
                                0.76f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        i *
                            1.4 +
                            now /
                                260.0
                    )
                        .toFloat() *
                        radius *
                        0.05f *
                        (
                            1f -
                                t
                            )

            fill.color =
                if (i % 3 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }

            fill.alpha = 235
            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 3 == 0) {
                        2.1f
                    } else {
                        1.2f
                    }
                ),
                fill
            )
        }
    }

    private fun drawAnsweringDynamics(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val pulse =
            (
                now %
                    1200L
                ).toFloat() /
                1200f

        for (i in 0 until 3) {
            val rr =
                radius *
                    (
                        0.62f +
                            pulse *
                                0.34f +
                            i *
                                0.08f
                        )

            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                (
                    160 -
                        pulse *
                            120f -
                        i *
                            18
                    )
                    .toInt()
                    .coerceAtLeast(20)

            stroke.strokeWidth = dp(0.85f)
            canvas.drawCircle(
                cx,
                cy,
                rr,
                stroke
            )
        }

        // Coherent outward packets.
        for (i in 0 until 10) {
            val t =
                (
                    (
                        now /
                            760.0 +
                            i /
                                10.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val side =
                if (i % 2 == 0) {
                    -1f
                } else {
                    1f
                }

            val x =
                cx +
                    side *
                        radius *
                        (
                            0.40f +
                                0.70f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        now /
                            310.0 +
                            i
                    )
                        .toFloat() *
                        radius *
                        0.055f

            fill.color =
                if (i % 4 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }
            fill.alpha =
                (
                    230 -
                        t *
                            90f
                    )
                    .toInt()

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 4 == 0) {
                        2f
                    } else {
                        1.1f
                    }
                ),
                fill
            )
        }
    }

    private fun drawStopDynamics(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        // Fractured segments.
        for (i in 0 until 18) {
            if (i % 4 == 1) continue

            val a =
                i *
                    PI *
                    2.0 /
                    18.0

            val gap =
                radius *
                    (
                        0.84f +
                            (
                                i %
                                    3
                                ) *
                                0.055f
                        )

            val x1 =
                cx +
                    cos(a)
                        .toFloat() *
                    gap

            val y1 =
                cy +
                    sin(a)
                        .toFloat() *
                    gap

            val x2 =
                cx +
                    cos(a)
                        .toFloat() *
                    (
                        gap +
                            radius *
                                0.13f
                        )

            val y2 =
                cy +
                    sin(a)
                        .toFloat() *
                    (
                        gap +
                            radius *
                                0.13f
                        )

            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                115 +
                    (
                        i %
                            4
                        ) *
                        20

            stroke.strokeWidth = dp(1.05f)

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                stroke
            )
        }

        // Halt cut across the center.
        stroke.color = Color.WHITE
        stroke.alpha = 205
        stroke.strokeWidth = dp(0.85f)

        canvas.drawLine(
            cx - radius * 1.05f,
            cy,
            cx - radius * 0.22f,
            cy,
            stroke
        )

        canvas.drawLine(
            cx + radius * 0.22f,
            cy,
            cx + radius * 1.05f,
            cy,
            stroke
        )
    }

    private fun drawWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        val size =
            radius *
                0.31f

        text.textSize = size
        text.color =
            if (state == STATE_STOP) {
                palette.secondary
            } else {
                palette.primary
            }
        text.alpha =
            if (state == STATE_STOP) {
                210
            } else {
                245
            }

        // A subtle halo behind the name, not a bitmap.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.42f,
                intArrayOf(
                    withAlpha(Color.WHITE, if (state == STATE_STOP) 30 else 58),
                    withAlpha(palette.primary, if (state == STATE_STOP) 26 else 46),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.44f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 0.42f,
            fill
        )
        fill.shader = null

        canvas.drawText(
            "AYANA",
            cx,
            cy +
                size *
                    0.34f,
            text
        )
    }

    private fun stateIndex(
        state: String
    ): Int {
        return when(state) {
            AyanaVoiceService.STATE_LISTENING ->
                STATE_WAITING

            AyanaVoiceService.STATE_COMMAND ->
                STATE_RECOGNITION

            AyanaVoiceService.STATE_THINKING ->
                STATE_THINKING

            AyanaVoiceService.STATE_EXECUTING ->
                STATE_EXECUTING

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT,
            AyanaVoiceService.STATE_SUCCESS ->
                STATE_ANSWERING

            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR ->
                STATE_STOP

            else ->
                STATE_WAITING
        }
    }

    private fun paletteFor(
        state: Int
    ): Palette {
        return when(state) {
            STATE_WAITING ->
                Palette(
                    primary = Color.parseColor("#19E4EC"),
                    secondary = Color.parseColor("#0CB8FF"),
                    accent = Color.parseColor("#7EF9FF")
                )

            STATE_RECOGNITION ->
                Palette(
                    primary = Color.parseColor("#147DFF"),
                    secondary = Color.parseColor("#00C8FF"),
                    accent = Color.parseColor("#8EDBFF")
                )

            STATE_THINKING ->
                Palette(
                    primary = Color.parseColor("#6D47FF"),
                    secondary = Color.parseColor("#9E46FF"),
                    accent = Color.parseColor("#D2B5FF")
                )

            STATE_EXECUTING ->
                Palette(
                    primary = Color.parseColor("#20E36A"),
                    secondary = Color.parseColor("#00CFAE"),
                    accent = Color.parseColor("#8AFFC0")
                )

            STATE_ANSWERING ->
                Palette(
                    primary = Color.parseColor("#F02BC1"),
                    secondary = Color.parseColor("#A94BFF"),
                    accent = Color.parseColor("#FF9DEB")
                )

            STATE_STOP ->
                Palette(
                    primary = Color.parseColor("#FF3D25"),
                    secondary = Color.parseColor("#FF7A1A"),
                    accent = Color.parseColor("#FF9A6F")
                )

            else ->
                paletteFor(STATE_WAITING)
        }
    }

    private fun motionFor(
        state: Int
    ): Motion {
        return when(state) {
            STATE_WAITING ->
                Motion(
                    frameDelayMs = 42L,
                    breathePeriodMs = 980.0,
                    wavePeriodMs = 760.0,
                    waveCycles = 7.0,
                    waveAlpha = 205,
                    waveGlowAlpha = 58,
                    ribbonPeriodMs = 1650.0,
                    ribbonWarp = 0.105f,
                    haloPeriodMs = 5400.0
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 28L,
                    breathePeriodMs = 620.0,
                    wavePeriodMs = 360.0,
                    waveCycles = 9.2,
                    waveAlpha = 245,
                    waveGlowAlpha = 86,
                    ribbonPeriodMs = 920.0,
                    ribbonWarp = 0.125f,
                    haloPeriodMs = 2600.0
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 27L,
                    breathePeriodMs = 540.0,
                    wavePeriodMs = 440.0,
                    waveCycles = 8.5,
                    waveAlpha = 232,
                    waveGlowAlpha = 78,
                    ribbonPeriodMs = 720.0,
                    ribbonWarp = 0.165f,
                    haloPeriodMs = 2050.0
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 26L,
                    breathePeriodMs = 470.0,
                    wavePeriodMs = 300.0,
                    waveCycles = 9.5,
                    waveAlpha = 245,
                    waveGlowAlpha = 88,
                    ribbonPeriodMs = 650.0,
                    ribbonWarp = 0.135f,
                    haloPeriodMs = 1750.0
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 27L,
                    breathePeriodMs = 520.0,
                    wavePeriodMs = 330.0,
                    waveCycles = 8.8,
                    waveAlpha = 242,
                    waveGlowAlpha = 86,
                    ribbonPeriodMs = 760.0,
                    ribbonWarp = 0.125f,
                    haloPeriodMs = 2200.0
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 76L,
                    breathePeriodMs = 1500.0,
                    wavePeriodMs = 1100.0,
                    waveCycles = 5.5,
                    waveAlpha = 150,
                    waveGlowAlpha = 36,
                    ribbonPeriodMs = 2500.0,
                    ribbonWarp = 0.075f,
                    haloPeriodMs = 8400.0
                )

            else ->
                motionFor(STATE_WAITING)
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

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int
    )

    private data class Motion(
        val frameDelayMs: Long,
        val breathePeriodMs: Double,
        val wavePeriodMs: Double,
        val waveCycles: Double,
        val waveAlpha: Int,
        val waveGlowAlpha: Int,
        val ribbonPeriodMs: Double,
        val ribbonWarp: Float,
        val haloPeriodMs: Double
    )

    companion object {
        private const val STATE_WAITING = 0
        private const val STATE_RECOGNITION = 1
        private const val STATE_THINKING = 2
        private const val STATE_EXECUTING = 3
        private const val STATE_ANSWERING = 4
        private const val STATE_STOP = 5
    }
}
