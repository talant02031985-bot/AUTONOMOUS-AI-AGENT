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
 * AYANA Core Visualizer v17.0 — LIVING AGENT CORE
 *
 * Device-driven redesign after rejecting v16.
 *
 * Goal:
 * reproduce the approved AYANA six-state energy language as a REAL live
 * Android Canvas renderer instead of a static picture.
 *
 * Visual structure:
 * - full-width live carrier / spectrum;
 * - dense volumetric luminous core;
 * - 30–44 braided energy ribbons;
 * - segmented data halo + particles + star flashes;
 * - geometric AYANA signature;
 * - state-specific AI-agent behavior:
 *
 *   WAITING       cyan      calm coherent breathing field
 *   RECOGNITION   blue      inbound scan / convergence
 *   THINKING      violet    branching reasoning graph
 *   EXECUTING     green     selected action stream
 *   ANSWERING     magenta   coherent outward response resonance
 *   STOP          red       fractured / halted field
 *
 * No PNG/JPEG is used.
 * No bottom six-button strip is used.
 * No fake load percentage is displayed.
 * No ORB, routing, TTS, microphone, Accessibility or execution logic changes.
 *
 * Integration contract:
 *   AyanaCoreVisualizer(Context)
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

        val state = stateFor(AyanaVoiceService.currentStatusState)
        val palette = paletteFor(state)
        val motion = motionFor(state)
        val now = SystemClock.uptimeMillis()

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * 0.50f

        val margin = dp(7f)

        // Largest element is <= 1.04R, therefore the visual stays inside View.
        val radius =
            min(
                (h * 0.50f - margin) / 1.04f,
                min(
                    (w * 0.50f - margin) / 1.04f,
                    min(
                        h * 0.475f,
                        w * 0.405f
                    )
                )
            )
                .coerceAtLeast(dp(40f))

        drawBackground(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawCarrierAndSpectrum(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawVolumetricPlasma(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawBraidedField(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawInnerStructure(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawSegmentedHalo(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawParticlesAndFlares(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawAgentStateSignature(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        drawAyanaSignature(
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

    private fun drawBackground(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: AgentState
    ) {
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 1.04f,
                intArrayOf(
                    withAlpha(
                        Color.WHITE,
                        if (state == AgentState.STOP) 14 else 42
                    ),
                    withAlpha(
                        palette.primary,
                        if (state == AgentState.STOP) 34 else 98
                    ),
                    withAlpha(
                        palette.secondary,
                        if (state == AgentState.STOP) 16 else 44
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.31f,
                    0.69f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 1.04f,
            fill
        )
        fill.shader = null

        // Very subtle center axis only. No "dashboard" grid.
        stroke.color = withAlpha(palette.primary, 34)
        stroke.alpha = 110
        stroke.strokeWidth = dp(0.55f)

        canvas.drawLine(
            dp(1f),
            cy,
            width.toFloat() - dp(1f),
            cy,
            stroke
        )
    }

    /**
     * Full-width carrier with high-frequency spectrum spikes on the sides.
     * This is deliberately stronger than v16, which looked too thin on-device.
     */
    private fun drawCarrierAndSpectrum(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: AgentState
    ) {
        path.reset()
        finePath.reset()

        val left = dp(1f)
        val right = width.toFloat() - dp(1f)
        val samples = 144
        val phase = now / motion.wavePeriodMs

        val boost =
            when(state) {
                AgentState.WAITING -> 0.92f
                AgentState.RECOGNITION -> 1.58f
                AgentState.THINKING -> 1.10f
                AgentState.EXECUTING -> 1.42f
                AgentState.ANSWERING -> 1.62f
                AgentState.STOP -> 0.44f
            }

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + (right - left) * t

            val edge =
                abs(
                    (x - cx) /
                        (width * 0.50f)
                )
                    .coerceIn(0f, 1f)

            // The waveform is smaller through the core and larger outside it.
            val carrierEnvelope =
                (
                    0.18f +
                        edge *
                            0.90f
                    )
                    .coerceIn(0.18f, 1f)

            val primary =
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
                        39.0 -
                        phase *
                            0.72
                )
                    .toFloat() *
                    0.28f

            val micro =
                sin(
                    t *
                        PI *
                        73.0 +
                        phase *
                            1.13
                )
                    .toFloat() *
                    0.11f

            val y =
                cy +
                    (
                        primary +
                            detail +
                            micro
                    ) *
                    radius *
                    0.175f *
                    carrierEnvelope *
                    boost

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }

            val fine =
                sin(
                    t *
                        PI *
                        92.0 +
                        phase *
                            1.21
                )
                    .toFloat()

            val fy =
                cy +
                    fine *
                    radius *
                    0.045f *
                    carrierEnvelope *
                    boost

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
        glow.strokeWidth = dp(14f)
        canvas.drawPath(path, glow)

        glow.alpha = motion.waveGlowAlpha / 2
        glow.strokeWidth = dp(7f)
        canvas.drawPath(path, glow)

        stroke.shader = gradient
        stroke.alpha = motion.waveAlpha
        stroke.strokeWidth = dp(1.85f)
        canvas.drawPath(path, stroke)

        stroke.shader = null
        glow.shader = null

        stroke.color = Color.WHITE
        stroke.alpha =
            if (state == AgentState.STOP) {
                36
            } else {
                100
            }
        stroke.strokeWidth = dp(0.52f)
        canvas.drawPath(finePath, stroke)

        drawSpectrumBars(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )
    }

    private fun drawSpectrumBars(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: AgentState
    ) {
        val barCount = 128
        val coreHalf = radius * 0.72f

        for (i in 0 until barCount) {
            val x =
                width *
                    i /
                    (barCount - 1).toFloat()

            val d = abs(x - cx)

            if (d < coreHalf) continue

            val edgeProgress =
                (
                    (d - coreHalf) /
                        (width * 0.50f - coreHalf)
                    )
                    .coerceIn(0f, 1f)

            val pulse =
                abs(
                    sin(
                        now /
                            220.0 +
                            i *
                                0.61
                    )
                        .toFloat()
                )

            val stateScale =
                when(state) {
                    AgentState.RECOGNITION -> 1.45f
                    AgentState.EXECUTING -> 1.25f
                    AgentState.ANSWERING -> 1.45f
                    AgentState.STOP -> 0.42f
                    else -> 0.90f
                }

            val h =
                radius *
                    (
                        0.025f +
                            edgeProgress *
                                0.11f +
                            pulse *
                                0.105f
                        ) *
                    stateScale

            val color =
                when(i % 4) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    else -> palette.accent
                }

            glow.color = color
            glow.alpha =
                if (state == AgentState.STOP) {
                    28
                } else {
                    60
                }
            glow.strokeWidth = dp(3.8f)

            canvas.drawLine(
                x,
                cy - h,
                x,
                cy + h,
                glow
            )

            stroke.color = color
            stroke.alpha =
                if (state == AgentState.STOP) {
                    80
                } else {
                    150
                }
            stroke.strokeWidth = dp(0.65f)

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
     * Volumetric center: overlapping animated plasma blobs + white-hot lens.
     * This removes the flat "thin rings on black" look of v16.
     */
    private fun drawVolumetricPlasma(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: AgentState
    ) {
        val blobCount =
            when(state) {
                AgentState.THINKING -> 21
                AgentState.EXECUTING -> 18
                AgentState.ANSWERING -> 19
                AgentState.STOP -> 10
                else -> 16
            }

        for (i in 0 until blobCount) {
            val angle =
                now /
                    (
                        1450.0 +
                            i *
                                43.0
                        ) +
                    i *
                        0.72

            val orbit =
                radius *
                    (
                        0.05f +
                            (i % 5) *
                                0.045f
                    )

            val bx =
                cx +
                    cos(angle)
                        .toFloat() *
                    orbit

            val by =
                cy +
                    sin(
                        angle *
                            (
                                1.08 +
                                    (i % 3) *
                                        0.14
                                )
                    )
                        .toFloat() *
                    orbit *
                    0.80f

            val color =
                when(i % 4) {
                    0 -> Color.WHITE
                    1 -> palette.primary
                    2 -> palette.secondary
                    else -> palette.accent
                }

            val blobR =
                radius *
                    (
                        0.17f +
                            (i % 4) *
                                0.045f
                    )

            fill.shader =
                RadialGradient(
                    bx,
                    by,
                    blobR,
                    intArrayOf(
                        withAlpha(
                            color,
                            if (state == AgentState.STOP) 25 else 98
                        ),
                        withAlpha(
                            if (color == Color.WHITE) {
                                palette.primary
                            } else {
                                color
                            },
                            if (state == AgentState.STOP) 16 else 54
                        ),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(
                        0f,
                        0.38f,
                        1f
                    ),
                    Shader.TileMode.CLAMP
                )

            fill.alpha = 255
            canvas.drawCircle(
                bx,
                by,
                blobR,
                fill
            )
            fill.shader = null
        }

        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius * 0.44f,
                intArrayOf(
                    withAlpha(
                        Color.WHITE,
                        if (state == AgentState.STOP) 78 else 245
                    ),
                    withAlpha(
                        palette.primary,
                        if (state == AgentState.STOP) 88 else 215
                    ),
                    withAlpha(
                        palette.secondary,
                        if (state == AgentState.STOP) 38 else 105
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.22f,
                    0.60f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * 0.44f,
            fill
        )
        fill.shader = null
    }

    /**
     * Dense tangled energy mass. Unlike v16, ribbons occupy the full core
     * volume and use multiple glow passes.
     */
    private fun drawBraidedField(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: AgentState
    ) {
        val count =
            when(state) {
                AgentState.WAITING -> 30
                AgentState.RECOGNITION -> 34
                AgentState.THINKING -> 44
                AgentState.EXECUTING -> 38
                AgentState.ANSWERING -> 36
                AgentState.STOP -> 20
            }

        for (ribbon in 0 until count) {
            path.reset()

            val points = 118

            val phase =
                now /
                    motion.ribbonPeriodMs +
                    ribbon *
                        0.56

            val family =
                ribbon %
                    4

            val bias =
                (
                    ribbon -
                        (count - 1) *
                            0.5f
                    ) /
                    count.toFloat()

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
                                if (family % 2 == 0) {
                                    1.0
                                } else {
                                    -0.88
                                }
                            )

                val waveA =
                    sin(
                        angle *
                            (
                                2.0 +
                                    family *
                                        0.55
                                ) +
                            phase *
                                0.72
                    )
                        .toFloat()

                val waveB =
                    sin(
                        angle *
                            (
                                4.0 +
                                    (ribbon % 3)
                                ) -
                            phase *
                                0.54
                    )
                        .toFloat()

                val base =
                    radius *
                        (
                            0.48f +
                                bias *
                                    0.20f
                            )

                val rr =
                    base *
                        (
                            1f +
                                waveA *
                                    motion.ribbonWarp +
                                waveB *
                                    0.070f
                            )

                val squash =
                    (
                        0.74f +
                            (ribbon % 5) *
                                0.055f
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
                        squash

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

            if (ribbon % 3 == 0) {
                glow.color = color
                glow.alpha =
                    if (state == AgentState.STOP) {
                        24
                    } else {
                        48
                    }
                glow.strokeWidth = dp(9f)
                canvas.drawPath(
                    path,
                    glow
                )
            }

            if (ribbon % 7 == 0) {
                glow.color = palette.primary
                glow.alpha =
                    if (state == AgentState.STOP) {
                        18
                    } else {
                        38
                    }
                glow.strokeWidth = dp(14f)
                canvas.drawPath(
                    path,
                    glow
                )
            }

            stroke.color = color

            stroke.alpha =
                when(state) {
                    AgentState.WAITING ->
                        if (color == Color.WHITE) 232 else 195

                    AgentState.RECOGNITION ->
                        if (color == Color.WHITE) 246 else 220

                    AgentState.THINKING ->
                        if (color == Color.WHITE) 252 else 232

                    AgentState.EXECUTING ->
                        if (color == Color.WHITE) 248 else 224

                    AgentState.ANSWERING ->
                        if (color == Color.WHITE) 250 else 226

                    AgentState.STOP ->
                        if (color == Color.WHITE) 130 else 105
                }

            stroke.strokeWidth =
                dp(
                    when {
                        color == Color.WHITE -> 1.50f
                        ribbon % 5 == 0 -> 1.20f
                        else -> 0.88f
                    }
                )

            canvas.drawPath(
                path,
                stroke
            )
        }
    }

    private fun drawInnerStructure(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: AgentState
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

        for (i in 0 until 5) {
            val rr =
                radius *
                    (
                        0.24f +
                            i *
                                0.11f
                        ) *
                    (
                        1f +
                            pulse *
                                0.008f
                        )

            stroke.color =
                when(i % 3) {
                    0 -> palette.primary
                    1 -> palette.secondary
                    else -> palette.accent
                }

            stroke.alpha =
                if (state == AgentState.STOP) {
                    54 + i * 5
                } else {
                    92 + i * 14
                }

            stroke.strokeWidth =
                dp(
                    if (i == 0) {
                        1.10f
                    } else {
                        0.68f
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
     * Multiple rotating segmented rings + tangential dashes.
     */
    private fun drawSegmentedHalo(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: AgentState
    ) {
        val ringCount = 7

        for (ringIndex in 0 until ringCount) {
            val segments =
                56 +
                    ringIndex *
                        8

            val rr =
                radius *
                    (
                        0.72f +
                            ringIndex *
                                0.043f
                        )

            val rotation =
                now /
                    (
                        motion.haloPeriodMs +
                            ringIndex *
                                280.0
                        ) *
                    (
                        if (ringIndex % 2 == 0) {
                            1.0
                        } else {
                            -0.72
                        }
                    )

            for (i in 0 until segments) {
                val show =
                    (
                        i +
                            ringIndex *
                                3
                        ) %
                        (
                            3 +
                                ringIndex %
                                    3
                            ) !=
                        0

                if (!show) continue

                val angle =
                    i *
                        PI *
                        2.0 /
                        segments +
                        rotation

                val wobble =
                    sin(
                        now /
                            740.0 +
                            i *
                                0.42 +
                            ringIndex
                    )
                        .toFloat()

                val localR =
                    rr +
                        wobble *
                            radius *
                            0.010f

                val x =
                    cx +
                        cos(angle)
                            .toFloat() *
                        localR

                val y =
                    cy +
                        sin(angle)
                            .toFloat() *
                        localR

                val tx =
                    -sin(angle)
                        .toFloat()

                val ty =
                    cos(angle)
                        .toFloat()

                val half =
                    dp(
                        1.5f +
                            ringIndex *
                                0.24f +
                            abs(wobble) *
                                2.4f
                    )

                val color =
                    when(
                        (
                            i +
                                ringIndex
                            ) %
                            4
                    ) {
                        0 -> Color.WHITE
                        1 -> palette.primary
                        2 -> palette.secondary
                        else -> palette.accent
                    }

                if (
                    ringIndex %
                        2 ==
                        0 &&
                    i %
                        8 ==
                        0
                ) {
                    glow.color = color
                    glow.alpha =
                        if (state == AgentState.STOP) {
                            18
                        } else {
                            42
                        }
                    glow.strokeWidth = dp(5f)

                    canvas.drawLine(
                        x - tx * half,
                        y - ty * half,
                        x + tx * half,
                        y + ty * half,
                        glow
                    )
                }

                stroke.color = color
                stroke.alpha =
                    if (state == AgentState.STOP) {
                        58
                    } else {
                        112 +
                            ringIndex *
                                9
                    }
                stroke.strokeWidth =
                    dp(
                        if (ringIndex % 3 == 0) {
                            0.82f
                        } else {
                            0.55f
                        }
                    )

                canvas.drawLine(
                    x - tx * half,
                    y - ty * half,
                    x + tx * half,
                    y + ty * half,
                    stroke
                )
            }
        }
    }

    private fun drawParticlesAndFlares(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: AgentState
    ) {
        val count =
            when(state) {
                AgentState.THINKING -> 220
                AgentState.EXECUTING -> 196
                AgentState.STOP -> 105
                else -> 175
            }

        val rotation = now / motion.particlePeriodMs

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
                                -0.31
                            }
                        )

            val shell =
                when(i % 5) {
                    0 -> 0.77f
                    1 -> 0.84f
                    2 -> 0.91f
                    3 -> 0.97f
                    else -> 1.02f
                }

            val rr =
                radius *
                    shell +
                    sin(
                        now /
                            520.0 +
                            i *
                                0.77
                    )
                        .toFloat() *
                        radius *
                        0.016f

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
                                now /
                                    270.0 +
                                    i *
                                        0.93
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
                    if (state == AgentState.STOP) {
                        45
                    } else {
                        78
                    } +
                        sparkle *
                            if (state == AgentState.STOP) {
                                90f
                            } else {
                                170f
                            }
                    )
                    .toInt()
                    .coerceAtMost(248)

            val pointR =
                dp(
                    when {
                        i % 19 == 0 -> 2.7f
                        i % 8 == 0 -> 1.65f
                        else -> 0.82f
                    }
                )

            if (i % 19 == 0) {
                glow.color = color
                glow.alpha =
                    if (state == AgentState.STOP) {
                        28
                    } else {
                        72
                    }
                glow.strokeWidth = dp(6f)
                canvas.drawCircle(
                    x,
                    y,
                    pointR * 1.8f,
                    glow
                )
            }

            canvas.drawCircle(
                x,
                y,
                pointR,
                fill
            )

            if (i % 23 == 0) {
                drawStarFlare(
                    canvas = canvas,
                    x = x,
                    y = y,
                    size =
                        dp(
                            4.5f +
                                sparkle *
                                    4.5f
                        ),
                    color = color,
                    alpha =
                        if (state == AgentState.STOP) {
                            105
                        } else {
                            205
                        }
                )
            }
        }
    }

    private fun drawStarFlare(
        canvas: Canvas,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        alpha: Int
    ) {
        glow.color = color
        glow.alpha = alpha / 3
        glow.strokeWidth = dp(4.2f)

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
        stroke.alpha = alpha
        stroke.strokeWidth = dp(0.72f)

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
     * Agent-specific state signature. The base energy object stays recognizable
     * while the dynamics change substantially by factual AYANA state.
     */
    private fun drawAgentStateSignature(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: AgentState
    ) {
        when(state) {
            AgentState.WAITING ->
                drawWaitingSignature(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.RECOGNITION ->
                drawRecognitionSignature(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.THINKING ->
                drawThinkingSignature(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.EXECUTING ->
                drawExecutingSignature(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.ANSWERING ->
                drawAnsweringSignature(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.STOP ->
                drawStopSignature(
                    canvas,
                    cx,
                    cy,
                    radius,
                    palette
                )
        }
    }

    private fun drawWaitingSignature(
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
                        sin(
                            now /
                                980.0
                        )
                )
                .toFloat()

        for (i in 0 until 3) {
            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                (
                    110 -
                        i *
                            22 +
                        pulse *
                            55f
                    )
                    .toInt()

            stroke.strokeWidth = dp(0.82f)

            canvas.drawCircle(
                cx,
                cy,
                radius *
                    (
                        0.63f +
                            i *
                                0.095f +
                            pulse *
                                0.010f
                        ),
                stroke
            )
        }
    }

    private fun drawRecognitionSignature(
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
                    1450L
                ).toFloat() /
                1450f

        val angle =
            -PI +
                sweep *
                    PI *
                    2.0

        val ex =
            cx +
                cos(angle)
                    .toFloat() *
                radius *
                0.96f

        val ey =
            cy +
                sin(angle)
                    .toFloat() *
                radius *
                0.96f

        glow.color = palette.primary
        glow.alpha = 96
        glow.strokeWidth = dp(9f)
        canvas.drawLine(
            cx,
            cy,
            ex,
            ey,
            glow
        )

        stroke.color = Color.WHITE
        stroke.alpha = 230
        stroke.strokeWidth = dp(0.88f)
        canvas.drawLine(
            cx,
            cy,
            ex,
            ey,
            stroke
        )

        // Inbound data packets.
        for (i in 0 until 14) {
            val t =
                (
                    (
                        now /
                            820.0 +
                            i /
                                14.0
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
                            1.03f -
                                0.77f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        now /
                            250.0 +
                            i *
                                0.77
                    )
                        .toFloat() *
                        radius *
                        0.11f *
                        (
                            1f -
                                t
                            )

            fill.color =
                if (i % 4 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }
            fill.alpha = 235

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 4 == 0) {
                        2.2f
                    } else {
                        1.15f
                    }
                ),
                fill
            )
        }
    }

    private fun drawThinkingSignature(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val nodes = 28

        for (i in 0 until nodes) {
            val angle =
                i *
                    PI *
                    2.0 /
                    nodes +
                    sin(
                        now /
                            1650.0 +
                            i *
                                0.37
                    ) *
                        0.18

            val rr =
                radius *
                    (
                        0.76f +
                            (i % 5) *
                                0.055f
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
                    rr

            val target =
                (
                    i *
                        7 +
                        5
                    ) %
                    nodes

            val targetAngle =
                target *
                    PI *
                    2.0 /
                    nodes -
                    sin(
                        now /
                            1320.0 +
                            target
                    ) *
                        0.13

            val targetR =
                radius *
                    (
                        0.77f +
                            (target % 4) *
                                0.06f
                        )

            val x2 =
                cx +
                    cos(targetAngle)
                        .toFloat() *
                    targetR

            val y2 =
                cy +
                    sin(targetAngle)
                        .toFloat() *
                    targetR

            stroke.color =
                if (i % 3 == 0) {
                    palette.accent
                } else {
                    palette.primary
                }

            stroke.alpha =
                if (i % 5 == 0) {
                    118
                } else {
                    70
                }

            stroke.strokeWidth = dp(0.62f)

            canvas.drawLine(
                x,
                y,
                x2,
                y2,
                stroke
            )

            fill.color =
                if (i % 5 == 0) {
                    Color.WHITE
                } else {
                    if (i % 2 == 0) {
                        palette.primary
                    } else {
                        palette.secondary
                    }
                }

            fill.alpha =
                if (i % 5 == 0) {
                    245
                } else {
                    185
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 5 == 0) {
                        2.2f
                    } else {
                        1.1f
                    }
                ),
                fill
            )
        }
    }

    private fun drawExecutingSignature(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val start =
            cx +
                radius *
                    0.30f

        val end =
            width.toFloat() -
                dp(2f)

        glow.color = palette.primary
        glow.alpha = 115
        glow.strokeWidth = dp(14f)

        canvas.drawLine(
            start,
            cy,
            end,
            cy,
            glow
        )

        stroke.color = Color.WHITE
        stroke.alpha = 235
        stroke.strokeWidth = dp(1.10f)

        canvas.drawLine(
            start,
            cy,
            end,
            cy,
            stroke
        )

        for (i in 0 until 15) {
            val t =
                (
                    (
                        now /
                            690.0 +
                            i /
                                15.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val x =
                start +
                    (
                        end - start
                    ) *
                    t

            val y =
                cy +
                    sin(
                        now /
                            240.0 +
                            i *
                                0.81
                    )
                        .toFloat() *
                        radius *
                        0.055f *
                        (
                            1f -
                                t
                            )

            fill.color =
                if (i % 4 == 0) {
                    Color.WHITE
                } else {
                    palette.primary
                }
            fill.alpha = 238

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 4 == 0) {
                        2.25f
                    } else {
                        1.20f
                    }
                ),
                fill
            )
        }
    }

    private fun drawAnsweringSignature(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val phase =
            (
                now %
                    1250L
                ).toFloat() /
                1250f

        for (i in 0 until 4) {
            val rr =
                radius *
                    (
                        0.46f +
                            phase *
                                0.45f +
                            i *
                                0.060f
                        )

            if (rr > radius * 0.98f) continue

            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                (
                    188 -
                        phase *
                            125f -
                        i *
                            15
                    )
                    .toInt()
                    .coerceAtLeast(20)

            stroke.strokeWidth = dp(1.05f)

            canvas.drawCircle(
                cx,
                cy,
                rr,
                stroke
            )
        }

        // Two coherent response lobes.
        for (side in -1..1 step 2) {
            path.reset()

            val left =
                cx +
                    side *
                        radius *
                        0.34f

            val right =
                cx +
                    side *
                        radius *
                        1.03f

            val count = 42

            for (i in 0..count) {
                val t =
                    i /
                        count.toFloat()

                val x =
                    left +
                        (
                            right - left
                        ) *
                        t

                val y =
                    cy +
                        sin(
                            t *
                                PI *
                                5.0 +
                                now /
                                    310.0
                        )
                            .toFloat() *
                        radius *
                        0.12f *
                        sin(
                            PI * t
                        )
                            .toFloat()

                if (i == 0) {
                    path.moveTo(
                        x,
                        y
                    )
                } else {
                    path.lineTo(
                        x,
                        y
                    )
                }
            }

            glow.color = palette.primary
            glow.alpha = 65
            glow.strokeWidth = dp(8f)
            canvas.drawPath(
                path,
                glow
            )

            stroke.color = Color.WHITE
            stroke.alpha = 215
            stroke.strokeWidth = dp(0.85f)
            canvas.drawPath(
                path,
                stroke
            )
        }
    }

    private fun drawStopSignature(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        for (i in 0 until 24) {
            if (i % 4 == 1) continue

            val angle =
                i *
                    PI *
                    2.0 /
                    24.0

            val r1 =
                radius *
                    (
                        0.74f +
                            (i % 3) *
                                0.06f
                        )

            val r2 =
                r1 +
                    radius *
                        0.17f

            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                170 +
                    (i % 3) *
                        18

            stroke.strokeWidth = dp(1.15f)

            canvas.drawLine(
                cx +
                    cos(angle)
                        .toFloat() *
                    r1,
                cy +
                    sin(angle)
                        .toFloat() *
                    r1,
                cx +
                    cos(angle)
                        .toFloat() *
                    r2,
                cy +
                    sin(angle)
                        .toFloat() *
                    r2,
                stroke
            )
        }

        // Break the carrier at the center.
        stroke.color = Color.WHITE
        stroke.alpha = 205
        stroke.strokeWidth = dp(0.88f)

        canvas.drawLine(
            dp(2f),
            cy,
            cx - radius * 0.25f,
            cy,
            stroke
        )

        canvas.drawLine(
            cx + radius * 0.25f,
            cy,
            width.toFloat() - dp(2f),
            cy,
            stroke
        )
    }

    /**
     * Custom geometric ΛYΛNΛ signature. No font asset is required.
     */
    private fun drawAyanaSignature(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: AgentState
    ) {
        val glyphH = radius * 0.31f
        val gap = radius * 0.050f

        val widths =
            floatArrayOf(
                glyphH * 0.72f,
                glyphH * 0.72f,
                glyphH * 0.72f,
                glyphH * 0.78f,
                glyphH * 0.72f
            )

        val totalGlyphWidth =
            widths.sum() +
                gap *
                    4f

        var x =
            cx -
                totalGlyphWidth *
                    0.50f

        val top =
            cy -
                glyphH *
                    0.50f

        val bottom =
            cy +
                glyphH *
                    0.50f

        val color =
            if (state == AgentState.STOP) {
                palette.secondary
            } else {
                Color.WHITE
            }

        // Wordmark glow.
        for (pass in 0 until 2) {
            glow.color =
                if (pass == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }
            glow.alpha =
                if (state == AgentState.STOP) {
                    34
                } else {
                    if (pass == 0) 110 else 60
                }
            glow.strokeWidth =
                dp(
                    if (pass == 0) {
                        13f
                    } else {
                        7f
                    }
                )

            drawGeometricWordmark(
                canvas = canvas,
                startX = x,
                top = top,
                bottom = bottom,
                widths = widths,
                gap = gap,
                paint = glow
            )
        }

        stroke.color = color
        stroke.alpha =
            if (state == AgentState.STOP) {
                215
            } else {
                250
            }
        stroke.strokeWidth = dp(2.10f)

        drawGeometricWordmark(
            canvas = canvas,
            startX = x,
            top = top,
            bottom = bottom,
            widths = widths,
            gap = gap,
            paint = stroke
        )

        // Bright internal line.
        stroke.color = palette.primary
        stroke.alpha =
            if (state == AgentState.STOP) {
                80
            } else {
                175
            }
        stroke.strokeWidth = dp(0.72f)

        drawGeometricWordmark(
            canvas = canvas,
            startX = x,
            top = top,
            bottom = bottom,
            widths = widths,
            gap = gap,
            paint = stroke
        )
    }

    private fun drawGeometricWordmark(
        canvas: Canvas,
        startX: Float,
        top: Float,
        bottom: Float,
        widths: FloatArray,
        gap: Float,
        paint: Paint
    ) {
        var x = startX

        // Λ
        drawLambda(
            canvas,
            x,
            top,
            bottom,
            widths[0],
            paint
        )
        x += widths[0] + gap

        // Y
        drawY(
            canvas,
            x,
            top,
            bottom,
            widths[1],
            paint
        )
        x += widths[1] + gap

        // Λ
        drawLambda(
            canvas,
            x,
            top,
            bottom,
            widths[2],
            paint
        )
        x += widths[2] + gap

        // N
        drawN(
            canvas,
            x,
            top,
            bottom,
            widths[3],
            paint
        )
        x += widths[3] + gap

        // Λ
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
                width *
                    0.50f

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
                width *
                    0.50f

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
                    primary = Color.parseColor("#17E9EE"),
                    secondary = Color.parseColor("#00BFFF"),
                    accent = Color.parseColor("#A9FFFF")
                )

            AgentState.RECOGNITION ->
                Palette(
                    primary = Color.parseColor("#1680FF"),
                    secondary = Color.parseColor("#00D2FF"),
                    accent = Color.parseColor("#A6E7FF")
                )

            AgentState.THINKING ->
                Palette(
                    primary = Color.parseColor("#7048FF"),
                    secondary = Color.parseColor("#A945FF"),
                    accent = Color.parseColor("#DBC4FF")
                )

            AgentState.EXECUTING ->
                Palette(
                    primary = Color.parseColor("#1FE36C"),
                    secondary = Color.parseColor("#00D2A4"),
                    accent = Color.parseColor("#A2FFD0")
                )

            AgentState.ANSWERING ->
                Palette(
                    primary = Color.parseColor("#F02CC1"),
                    secondary = Color.parseColor("#AD47FF"),
                    accent = Color.parseColor("#FFB0ED")
                )

            AgentState.STOP ->
                Palette(
                    primary = Color.parseColor("#FF3C24"),
                    secondary = Color.parseColor("#FF7818"),
                    accent = Color.parseColor("#FFB08A")
                )
        }
    }

    private fun motionFor(
        state: AgentState
    ): Motion {
        return when(state) {
            AgentState.WAITING ->
                Motion(
                    frameDelayMs = 36L,
                    breathePeriodMs = 980.0,
                    wavePeriodMs = 760.0,
                    waveCycles = 7.2,
                    waveAlpha = 220,
                    waveGlowAlpha = 78,
                    ribbonPeriodMs = 1550.0,
                    ribbonWarp = 0.17f,
                    haloPeriodMs = 5200.0,
                    particlePeriodMs = 4800.0
                )

            AgentState.RECOGNITION ->
                Motion(
                    frameDelayMs = 27L,
                    breathePeriodMs = 620.0,
                    wavePeriodMs = 350.0,
                    waveCycles = 9.5,
                    waveAlpha = 248,
                    waveGlowAlpha = 104,
                    ribbonPeriodMs = 900.0,
                    ribbonWarp = 0.19f,
                    haloPeriodMs = 2500.0,
                    particlePeriodMs = 2200.0
                )

            AgentState.THINKING ->
                Motion(
                    frameDelayMs = 26L,
                    breathePeriodMs = 540.0,
                    wavePeriodMs = 430.0,
                    waveCycles = 8.8,
                    waveAlpha = 240,
                    waveGlowAlpha = 96,
                    ribbonPeriodMs = 700.0,
                    ribbonWarp = 0.24f,
                    haloPeriodMs = 1950.0,
                    particlePeriodMs = 1700.0
                )

            AgentState.EXECUTING ->
                Motion(
                    frameDelayMs = 25L,
                    breathePeriodMs = 470.0,
                    wavePeriodMs = 295.0,
                    waveCycles = 9.8,
                    waveAlpha = 250,
                    waveGlowAlpha = 108,
                    ribbonPeriodMs = 620.0,
                    ribbonWarp = 0.20f,
                    haloPeriodMs = 1680.0,
                    particlePeriodMs = 1450.0
                )

            AgentState.ANSWERING ->
                Motion(
                    frameDelayMs = 26L,
                    breathePeriodMs = 520.0,
                    wavePeriodMs = 325.0,
                    waveCycles = 9.0,
                    waveAlpha = 248,
                    waveGlowAlpha = 104,
                    ribbonPeriodMs = 720.0,
                    ribbonWarp = 0.19f,
                    haloPeriodMs = 2100.0,
                    particlePeriodMs = 1650.0
                )

            AgentState.STOP ->
                Motion(
                    frameDelayMs = 70L,
                    breathePeriodMs = 1500.0,
                    wavePeriodMs = 1100.0,
                    waveCycles = 5.5,
                    waveAlpha = 148,
                    waveGlowAlpha = 42,
                    ribbonPeriodMs = 2500.0,
                    ribbonWarp = 0.10f,
                    haloPeriodMs = 8200.0,
                    particlePeriodMs = 7600.0
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
        val waveCycles: Double,
        val waveAlpha: Int,
        val waveGlowAlpha: Int,
        val ribbonPeriodMs: Double,
        val ribbonWarp: Float,
        val haloPeriodMs: Double,
        val particlePeriodMs: Double
    )
}
