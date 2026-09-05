package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v18.0 — RUNTIME SHADER AI CORE
 *
 * Major rendering reset after device review of v17.
 *
 * Android 13+:
 * - full procedural AGSL RuntimeShader for volumetric energy / plasma / halo;
 * - Canvas overlays only for state-specific agent behavior and AYANA signature.
 *
 * Older Android:
 * - bounded Canvas fallback; no static image dependency.
 *
 * Six factual AYANA states:
 * WAITING / RECOGNITION / THINKING / EXECUTING / ANSWERING / STOP.
 *
 * No PNG/JPEG.
 * No fake model-load percentage.
 * No ORB, routing, TTS, microphone, Accessibility or execution changes.
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

    private var shaderBridge: ShaderBridge? = null

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

        if (
            Build.VERSION.SDK_INT >= 33 &&
            shaderBridge == null
        ) {
            shaderBridge =
                Api33ShaderBridge()
        }

        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val state =
            stateFor(
                AyanaVoiceService.currentStatusState
            )

        val palette =
            paletteFor(state)

        val motion =
            motionFor(state)

        val now =
            SystemClock.uptimeMillis()

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val cx =
            w * 0.50f

        val cy =
            h * 0.50f

        val margin =
            dp(6f)

        val radius =
            min(
                (h * 0.50f - margin) / 1.03f,
                min(
                    (w * 0.50f - margin) / 1.03f,
                    min(
                        h * 0.485f,
                        w * 0.410f
                    )
                )
            )
                .coerceAtLeast(
                    dp(38f)
                )

        val bridge =
            shaderBridge

        if (
            bridge != null &&
            Build.VERSION.SDK_INT >= 33
        ) {
            bridge.draw(
                canvas = canvas,
                width = w,
                height = h,
                timeSeconds =
                    now /
                        1000f *
                        motion.shaderSpeed,
                state = state,
                palette = palette
            )
        } else {
            drawFallbackCore(
                canvas = canvas,
                now = now,
                cx = cx,
                cy = cy,
                radius = radius,
                state = state,
                palette = palette,
                motion = motion
            )
        }

        drawCarrier(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            state = state,
            palette = palette,
            motion = motion
        )

        drawStateSpecificAgentLayer(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            state = state,
            palette = palette
        )

        drawAyanaSignature(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            state = state,
            palette = palette
        )

        if (attached) {
            postInvalidateDelayed(
                motion.frameDelayMs
            )
        }
    }

    private fun drawCarrier(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AgentState,
        palette: Palette,
        motion: Motion
    ) {
        path.reset()

        val left =
            dp(1f)

        val right =
            width.toFloat() -
                dp(1f)

        val samples =
            160

        val phase =
            now /
                motion.wavePeriodMs

        val boost =
            when(state) {
                AgentState.WAITING ->
                    0.82f

                AgentState.RECOGNITION ->
                    1.60f

                AgentState.THINKING ->
                    1.08f

                AgentState.EXECUTING ->
                    1.42f

                AgentState.ANSWERING ->
                    1.55f

                AgentState.STOP ->
                    0.38f
            }

        for (i in 0..samples) {
            val t =
                i /
                    samples.toFloat()

            val x =
                left +
                    (
                        right - left
                    ) *
                    t

            val edge =
                abs(
                    (x - cx) /
                        (
                            width *
                                0.50f
                            )
                )
                    .coerceIn(
                        0f,
                        1f
                    )

            val envelope =
                (
                    0.08f +
                        edge *
                            0.98f
                    )
                    .coerceIn(
                        0.08f,
                        1f
                    )

            val a =
                sin(
                    t *
                        PI *
                        motion.waveCycles +
                        phase
                )
                    .toFloat()

            val b =
                sin(
                    t *
                        PI *
                        37.0 -
                        phase *
                            0.74
                )
                    .toFloat() *
                    0.29f

            val c =
                sin(
                    t *
                        PI *
                        79.0 +
                        phase *
                            1.16
                )
                    .toFloat() *
                    0.10f

            val y =
                cy +
                    (
                        a +
                            b +
                            c
                    ) *
                    radius *
                    0.19f *
                    envelope *
                    boost

            if (
                i ==
                0
            ) {
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

        glow.shader =
            gradient

        glow.alpha =
            if (
                state ==
                AgentState.STOP
            ) {
                34
            } else {
                98
            }

        glow.strokeWidth =
            dp(15f)

        canvas.drawPath(
            path,
            glow
        )

        glow.alpha =
            if (
                state ==
                AgentState.STOP
            ) {
                24
            } else {
                66
            }

        glow.strokeWidth =
            dp(7f)

        canvas.drawPath(
            path,
            glow
        )

        stroke.shader =
            gradient

        stroke.alpha =
            if (
                state ==
                AgentState.STOP
            ) {
                120
            } else {
                240
            }

        stroke.strokeWidth =
            dp(1.65f)

        canvas.drawPath(
            path,
            stroke
        )

        glow.shader =
            null

        stroke.shader =
            null

        drawSpectrumBars(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            state = state,
            palette = palette
        )
    }

    private fun drawSpectrumBars(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AgentState,
        palette: Palette
    ) {
        val count =
            136

        val exclusion =
            radius *
                0.72f

        for (i in 0 until count) {
            val x =
                width *
                    i /
                    (
                        count -
                            1
                        ).toFloat()

            val distance =
                abs(
                    x -
                        cx
                )

            if (
                distance <
                exclusion
            ) {
                continue
            }

            val normalized =
                (
                    (
                        distance -
                            exclusion
                        ) /
                        (
                            width *
                                0.50f -
                                exclusion
                            )
                    )
                    .coerceIn(
                        0f,
                        1f
                    )

            val pulse =
                abs(
                    sin(
                        now /
                            210.0 +
                            i *
                                0.63
                    )
                        .toFloat()
                )

            val stateBoost =
                when(state) {
                    AgentState.RECOGNITION ->
                        1.44f

                    AgentState.EXECUTING ->
                        1.23f

                    AgentState.ANSWERING ->
                        1.42f

                    AgentState.STOP ->
                        0.38f

                    else ->
                        0.90f
                }

            val barHeight =
                radius *
                    (
                        0.025f +
                            normalized *
                                0.11f +
                            pulse *
                                0.12f
                        ) *
                    stateBoost

            val color =
                when(
                    i %
                        4
                ) {
                    0 ->
                        Color.WHITE

                    1 ->
                        palette.primary

                    2 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            glow.color =
                color

            glow.alpha =
                if (
                    state ==
                    AgentState.STOP
                ) {
                    22
                } else {
                    58
                }

            glow.strokeWidth =
                dp(3.2f)

            canvas.drawLine(
                x,
                cy -
                    barHeight,
                x,
                cy +
                    barHeight,
                glow
            )

            stroke.color =
                color

            stroke.alpha =
                if (
                    state ==
                    AgentState.STOP
                ) {
                    70
                } else {
                    145
                }

            stroke.strokeWidth =
                dp(0.60f)

            canvas.drawLine(
                x,
                cy -
                    barHeight,
                x,
                cy +
                    barHeight,
                stroke
            )
        }
    }

    private fun drawStateSpecificAgentLayer(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AgentState,
        palette: Palette
    ) {
        when(state) {
            AgentState.WAITING ->
                drawWaitingLayer(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.RECOGNITION ->
                drawRecognitionLayer(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.THINKING ->
                drawThinkingLayer(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.EXECUTING ->
                drawExecutingLayer(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.ANSWERING ->
                drawAnsweringLayer(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )

            AgentState.STOP ->
                drawStopLayer(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius,
                    palette
                )
        }
    }

    private fun drawWaitingLayer(
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
                if (
                    i %
                        2 ==
                        0
                ) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                (
                    118 -
                        i *
                            22 +
                        pulse *
                            48f
                    )
                    .toInt()

            stroke.strokeWidth =
                dp(0.72f)

            canvas.drawCircle(
                cx,
                cy,
                radius *
                    (
                        0.67f +
                            i *
                                0.10f +
                            pulse *
                                0.008f
                        ),
                stroke
            )
        }
    }

    private fun drawRecognitionLayer(
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
                    1380L
                ).toFloat() /
                1380f

        val angle =
            -PI +
                sweep *
                    PI *
                    2.0

        val endX =
            cx +
                cos(angle)
                    .toFloat() *
                radius *
                0.96f

        val endY =
            cy +
                sin(angle)
                    .toFloat() *
                radius *
                0.96f

        glow.color =
            palette.primary

        glow.alpha =
            92

        glow.strokeWidth =
            dp(10f)

        canvas.drawLine(
            cx,
            cy,
            endX,
            endY,
            glow
        )

        stroke.color =
            Color.WHITE

        stroke.alpha =
            230

        stroke.strokeWidth =
            dp(0.85f)

        canvas.drawLine(
            cx,
            cy,
            endX,
            endY,
            stroke
        )

        for (i in 0 until 18) {
            val t =
                (
                    (
                        now /
                            800.0 +
                            i /
                                18.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val side =
                if (
                    i %
                        2 ==
                        0
                ) {
                    -1f
                } else {
                    1f
                }

            val x =
                cx +
                    side *
                        radius *
                        (
                            1.04f -
                                0.79f *
                                    t
                            )

            val y =
                cy +
                    sin(
                        now /
                            245.0 +
                            i *
                                0.72
                    )
                        .toFloat() *
                        radius *
                        0.10f *
                        (
                            1f -
                                t
                            )

            fill.color =
                if (
                    i %
                        4 ==
                        0
                ) {
                    Color.WHITE
                } else {
                    palette.primary
                }

            fill.alpha =
                235

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (
                        i %
                            4 ==
                            0
                    ) {
                        2.1f
                    } else {
                        1.1f
                    }
                ),
                fill
            )
        }
    }

    private fun drawThinkingLayer(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val nodes =
            32

        for (i in 0 until nodes) {
            val angle =
                i *
                    PI *
                    2.0 /
                    nodes +
                    sin(
                        now /
                            1620.0 +
                            i *
                                0.37
                    ) *
                        0.20

            val rr =
                radius *
                    (
                        0.69f +
                            (
                                i %
                                    6
                                ) *
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
                        9 +
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
                            1280.0 +
                            target
                    ) *
                        0.14

            val targetR =
                radius *
                    (
                        0.70f +
                            (
                                target %
                                    5
                                ) *
                                0.061f
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
                when(
                    i %
                        3
                ) {
                    0 ->
                        palette.accent

                    1 ->
                        palette.primary

                    else ->
                        palette.secondary
                }

            stroke.alpha =
                if (
                    i %
                        5 ==
                        0
                ) {
                    120
                } else {
                    66
                }

            stroke.strokeWidth =
                dp(0.60f)

            canvas.drawLine(
                x,
                y,
                x2,
                y2,
                stroke
            )

            fill.color =
                if (
                    i %
                        5 ==
                        0
                ) {
                    Color.WHITE
                } else if (
                    i %
                        2 ==
                        0
                ) {
                    palette.primary
                } else {
                    palette.secondary
                }

            fill.alpha =
                if (
                    i %
                        5 ==
                        0
                ) {
                    245
                } else {
                    185
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (
                        i %
                            5 ==
                            0
                    ) {
                        2.2f
                    } else {
                        1.1f
                    }
                ),
                fill
            )
        }
    }

    private fun drawExecutingLayer(
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
                    0.28f

        val end =
            width.toFloat() -
                dp(2f)

        glow.color =
            palette.primary

        glow.alpha =
            108

        glow.strokeWidth =
            dp(14f)

        canvas.drawLine(
            start,
            cy,
            end,
            cy,
            glow
        )

        stroke.color =
            Color.WHITE

        stroke.alpha =
            235

        stroke.strokeWidth =
            dp(1.0f)

        canvas.drawLine(
            start,
            cy,
            end,
            cy,
            stroke
        )

        for (i in 0 until 17) {
            val t =
                (
                    (
                        now /
                            670.0 +
                            i /
                                17.0
                        ) %
                        1.0
                    )
                    .toFloat()

            val x =
                start +
                    (
                        end -
                            start
                        ) *
                    t

            val y =
                cy +
                    sin(
                        now /
                            235.0 +
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
                if (
                    i %
                        4 ==
                        0
                ) {
                    Color.WHITE
                } else {
                    palette.primary
                }

            fill.alpha =
                238

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (
                        i %
                            4 ==
                            0
                    ) {
                        2.2f
                    } else {
                        1.15f
                    }
                ),
                fill
            )
        }
    }

    private fun drawAnsweringLayer(
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
                    1220L
                ).toFloat() /
                1220f

        for (i in 0 until 4) {
            val rr =
                radius *
                    (
                        0.46f +
                            phase *
                                0.46f +
                            i *
                                0.058f
                        )

            if (
                rr >
                radius *
                    0.98f
            ) {
                continue
            }

            stroke.color =
                if (
                    i %
                        2 ==
                        0
                ) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                (
                    190 -
                        phase *
                            128f -
                        i *
                            15
                    )
                    .toInt()
                    .coerceAtLeast(
                        20
                    )

            stroke.strokeWidth =
                dp(1.05f)

            canvas.drawCircle(
                cx,
                cy,
                rr,
                stroke
            )
        }
    }

    private fun drawStopLayer(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        for (i in 0 until 26) {
            if (
                i %
                    4 ==
                    1
            ) {
                continue
            }

            val angle =
                i *
                    PI *
                    2.0 /
                    26.0

            val r1 =
                radius *
                    (
                        0.71f +
                            (
                                i %
                                    4
                                ) *
                                0.055f
                        )

            val r2 =
                r1 +
                    radius *
                        0.17f

            stroke.color =
                if (
                    i %
                        2 ==
                        0
                ) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                175

            stroke.strokeWidth =
                dp(1.2f)

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

        // Central break.
        stroke.color =
            Color.WHITE

        stroke.alpha =
            205

        stroke.strokeWidth =
            dp(0.90f)

        canvas.drawLine(
            dp(2f),
            cy,
            cx -
                radius *
                    0.24f,
            cy,
            stroke
        )

        canvas.drawLine(
            cx +
                radius *
                    0.24f,
            cy,
            width.toFloat() -
                dp(2f),
            cy,
            stroke
        )
    }

    /**
     * Canvas fallback used only below Android 13.
     */
    private fun drawFallbackCore(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AgentState,
        palette: Palette,
        motion: Motion
    ) {
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    Color.WHITE,
                    palette.primary,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.18f,
                    0.58f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha =
            if (
                state ==
                AgentState.STOP
            ) {
                150
            } else {
                245
            }

        canvas.drawCircle(
            cx,
            cy,
            radius *
                0.78f,
            fill
        )

        fill.shader =
            null

        val ribbons =
            if (
                state ==
                AgentState.THINKING
            ) {
                30
            } else {
                22
            }

        for (ribbon in 0 until ribbons) {
            path.reset()

            val phase =
                now /
                    motion.ribbonPeriodMs +
                    ribbon *
                        0.58

            for (i in 0..110) {
                val t =
                    i /
                        110f

                val angle =
                    t *
                        PI *
                        2.0 +
                        phase *
                            if (
                                ribbon %
                                    2 ==
                                    0
                            ) {
                                1.0
                            } else {
                                -0.82
                            }

                val warp =
                    sin(
                        angle *
                            (
                                2.0 +
                                    ribbon %
                                        4
                                ) +
                            phase *
                                0.70
                    )
                        .toFloat()

                val rr =
                    radius *
                        (
                            0.50f +
                                (
                                    ribbon -
                                        ribbons *
                                            0.5f
                                    ) /
                                    ribbons *
                                    0.18f
                            ) *
                        (
                            1f +
                                warp *
                                    motion.ribbonWarp
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
                            0.74f +
                                (
                                    ribbon %
                                        5
                                    ) *
                                    0.05f
                            )

                if (
                    i ==
                    0
                ) {
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

            val color =
                when(
                    ribbon %
                        4
                ) {
                    0 ->
                        Color.WHITE

                    1 ->
                        palette.primary

                    2 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            glow.color =
                color

            glow.alpha =
                30

            glow.strokeWidth =
                dp(8f)

            if (
                ribbon %
                    4 ==
                    0
            ) {
                canvas.drawPath(
                    path,
                    glow
                )
            }

            stroke.color =
                color

            stroke.alpha =
                if (
                    state ==
                    AgentState.STOP
                ) {
                    90
                } else {
                    190
                }

            stroke.strokeWidth =
                dp(
                    if (
                        color ==
                        Color.WHITE
                    ) {
                        1.3f
                    } else {
                        0.85f
                    }
                )

            canvas.drawPath(
                path,
                stroke
            )
        }
    }

    /**
     * Geometric ΛYΛNΛ signature; no custom font dependency.
     */
    private fun drawAyanaSignature(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AgentState,
        palette: Palette
    ) {
        val glyphHeight =
            radius *
                0.33f

        val widths =
            floatArrayOf(
                glyphHeight *
                    0.72f,
                glyphHeight *
                    0.72f,
                glyphHeight *
                    0.72f,
                glyphHeight *
                    0.78f,
                glyphHeight *
                    0.72f
            )

        val gap =
            radius *
                0.048f

        val totalWidth =
            widths.sum() +
                gap *
                    4f

        val startX =
            cx -
                totalWidth *
                    0.50f

        val top =
            cy -
                glyphHeight *
                    0.50f

        val bottom =
            cy +
                glyphHeight *
                    0.50f

        for (pass in 0 until 2) {
            glow.color =
                if (
                    pass ==
                    0
                ) {
                    palette.primary
                } else {
                    palette.secondary
                }

            glow.alpha =
                if (
                    state ==
                    AgentState.STOP
                ) {
                    32
                } else if (
                    pass ==
                    0
                ) {
                    118
                } else {
                    62
                }

            glow.strokeWidth =
                dp(
                    if (
                        pass ==
                        0
                    ) {
                        13f
                    } else {
                        7f
                    }
                )

            drawWordmarkGeometry(
                canvas = canvas,
                startX = startX,
                top = top,
                bottom = bottom,
                widths = widths,
                gap = gap,
                paint = glow
            )
        }

        stroke.color =
            if (
                state ==
                AgentState.STOP
            ) {
                palette.secondary
            } else {
                Color.WHITE
            }

        stroke.alpha =
            248

        stroke.strokeWidth =
            dp(2.15f)

        drawWordmarkGeometry(
            canvas = canvas,
            startX = startX,
            top = top,
            bottom = bottom,
            widths = widths,
            gap = gap,
            paint = stroke
        )

        stroke.color =
            palette.primary

        stroke.alpha =
            if (
                state ==
                AgentState.STOP
            ) {
                76
            } else {
                180
            }

        stroke.strokeWidth =
            dp(0.75f)

        drawWordmarkGeometry(
            canvas = canvas,
            startX = startX,
            top = top,
            bottom = bottom,
            widths = widths,
            gap = gap,
            paint = stroke
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
        var x =
            startX

        drawLambda(
            canvas,
            x,
            top,
            bottom,
            widths[0],
            paint
        )

        x +=
            widths[0] +
                gap

        drawY(
            canvas,
            x,
            top,
            bottom,
            widths[1],
            paint
        )

        x +=
            widths[1] +
                gap

        drawLambda(
            canvas,
            x,
            top,
            bottom,
            widths[2],
            paint
        )

        x +=
            widths[2] +
                gap

        drawN(
            canvas,
            x,
            top,
            bottom,
            widths[3],
            paint
        )

        x +=
            widths[3] +
                gap

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
            left +
                width,
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
                    bottom -
                        top
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
            left +
                width,
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
            left +
                width,
            bottom,
            paint
        )

        canvas.drawLine(
            left +
                width,
            bottom,
            left +
                width,
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
                    primary =
                        Color.parseColor(
                            "#12E9EC"
                        ),
                    secondary =
                        Color.parseColor(
                            "#00BFFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#B8FFFF"
                        )
                )

            AgentState.RECOGNITION ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#167FFF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#00D7FF"
                        ),
                    accent =
                        Color.parseColor(
                            "#B9EDFF"
                        )
                )

            AgentState.THINKING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#7048FF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#B043FF"
                        ),
                    accent =
                        Color.parseColor(
                            "#E3CEFF"
                        )
                )

            AgentState.EXECUTING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#1FE16C"
                        ),
                    secondary =
                        Color.parseColor(
                            "#00D6A5"
                        ),
                    accent =
                        Color.parseColor(
                            "#B5FFD8"
                        )
                )

            AgentState.ANSWERING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#F22CC2"
                        ),
                    secondary =
                        Color.parseColor(
                            "#B049FF"
                        ),
                    accent =
                        Color.parseColor(
                            "#FFC0EF"
                        )
                )

            AgentState.STOP ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#FF3B23"
                        ),
                    secondary =
                        Color.parseColor(
                            "#FF7A17"
                        ),
                    accent =
                        Color.parseColor(
                            "#FFC09D"
                        )
                )
        }
    }

    private fun motionFor(
        state: AgentState
    ): Motion {
        return when(state) {
            AgentState.WAITING ->
                Motion(
                    frameDelayMs = 34L,
                    shaderSpeed = 0.78f,
                    wavePeriodMs = 760.0,
                    waveCycles = 7.2,
                    ribbonPeriodMs = 1500.0,
                    ribbonWarp = 0.18f
                )

            AgentState.RECOGNITION ->
                Motion(
                    frameDelayMs = 26L,
                    shaderSpeed = 1.28f,
                    wavePeriodMs = 345.0,
                    waveCycles = 9.7,
                    ribbonPeriodMs = 870.0,
                    ribbonWarp = 0.20f
                )

            AgentState.THINKING ->
                Motion(
                    frameDelayMs = 25L,
                    shaderSpeed = 1.42f,
                    wavePeriodMs = 425.0,
                    waveCycles = 8.9,
                    ribbonPeriodMs = 690.0,
                    ribbonWarp = 0.25f
                )

            AgentState.EXECUTING ->
                Motion(
                    frameDelayMs = 24L,
                    shaderSpeed = 1.55f,
                    wavePeriodMs = 290.0,
                    waveCycles = 9.9,
                    ribbonPeriodMs = 610.0,
                    ribbonWarp = 0.21f
                )

            AgentState.ANSWERING ->
                Motion(
                    frameDelayMs = 25L,
                    shaderSpeed = 1.34f,
                    wavePeriodMs = 320.0,
                    waveCycles = 9.1,
                    ribbonPeriodMs = 705.0,
                    ribbonWarp = 0.20f
                )

            AgentState.STOP ->
                Motion(
                    frameDelayMs = 72L,
                    shaderSpeed = 0.22f,
                    wavePeriodMs = 1100.0,
                    waveCycles = 5.5,
                    ribbonPeriodMs = 2500.0,
                    ribbonWarp = 0.10f
                )
        }
    }

    private fun withAlpha(
        color: Int,
        alpha: Int
    ): Int {
        return Color.argb(
            alpha.coerceIn(
                0,
                255
            ),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(
        value: Float
    ): Float {
        return value *
            density
    }

    private interface ShaderBridge {
        fun draw(
            canvas: Canvas,
            width: Float,
            height: Float,
            timeSeconds: Float,
            state: AgentState,
            palette: Palette
        )
    }

    /**
     * Loaded only on Android 13+.
     */
    private class Api33ShaderBridge : ShaderBridge {

        private val shader =
            RuntimeShader(
                AGSL_SOURCE
            )

        private val paint =
            Paint(
                Paint.ANTI_ALIAS_FLAG
            )

        override fun draw(
            canvas: Canvas,
            width: Float,
            height: Float,
            timeSeconds: Float,
            state: AgentState,
            palette: Palette
        ) {
            shader.setFloatUniform(
                "uResolution",
                width,
                height
            )

            shader.setFloatUniform(
                "uTime",
                timeSeconds
            )

            shader.setFloatUniform(
                "uState",
                state.shaderValue
            )

            shader.setFloatUniform(
                "uPrimary",
                Color.red(
                    palette.primary
                ) /
                    255f,
                Color.green(
                    palette.primary
                ) /
                    255f,
                Color.blue(
                    palette.primary
                ) /
                    255f
            )

            shader.setFloatUniform(
                "uSecondary",
                Color.red(
                    palette.secondary
                ) /
                    255f,
                Color.green(
                    palette.secondary
                ) /
                    255f,
                Color.blue(
                    palette.secondary
                ) /
                    255f
            )

            shader.setFloatUniform(
                "uAccent",
                Color.red(
                    palette.accent
                ) /
                    255f,
                Color.green(
                    palette.accent
                ) /
                    255f,
                Color.blue(
                    palette.accent
                ) /
                    255f
            )

            paint.shader =
                shader

            canvas.drawRect(
                0f,
                0f,
                width,
                height,
                paint
            )

            paint.shader =
                null
        }
    }

    private enum class AgentState(
        val shaderValue: Float
    ) {
        WAITING(0f),
        RECOGNITION(1f),
        THINKING(2f),
        EXECUTING(3f),
        ANSWERING(4f),
        STOP(5f)
    }

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int
    )

    private data class Motion(
        val frameDelayMs: Long,
        val shaderSpeed: Float,
        val wavePeriodMs: Double,
        val waveCycles: Double,
        val ribbonPeriodMs: Double,
        val ribbonWarp: Float
    )

    companion object {

        /**
         * AGSL / SkSL fragment shader.
         *
         * Fixed-count loops are intentional for RuntimeShader compatibility.
         */
        private const val AGSL_SOURCE =
            """
            uniform float2 uResolution;
            uniform float uTime;
            uniform float uState;
            uniform float3 uPrimary;
            uniform float3 uSecondary;
            uniform float3 uAccent;

            float hash21(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            float ring(float r, float target, float width) {
                return 1.0 - smoothstep(
                    width,
                    width + 0.010,
                    abs(r - target)
                );
            }

            float lineGlow(float d, float width) {
                return exp(-abs(d) / width);
            }

            float plasma(float2 p, float t) {
                float v = 0.0;
                float a = atan(p.y, p.x);
                float r = length(p);

                v += sin(a * 7.0 + t * 1.7 + sin(r * 12.0 - t));
                v += sin(a * 11.0 - t * 1.3 + cos(r * 17.0 + t * 0.7));
                v += sin(a * 17.0 + t * 0.9 + sin(r * 23.0 - t * 1.1));
                v += sin((p.x + p.y) * 18.0 + t * 1.5);
                v += sin((p.x - p.y) * 22.0 - t * 1.2);

                return v / 5.0;
            }

            half4 main(float2 fragCoord) {
                float2 res = uResolution;
                float2 p = (fragCoord - 0.5 * res) / min(res.x, res.y);

                float t = uTime;
                float r = length(p);
                float a = atan(p.y, p.x);

                float3 color = float3(0.0);
                float alpha = 0.0;

                // Soft volumetric base.
                float coreMask =
                    1.0 -
                    smoothstep(
                        0.12,
                        0.52,
                        r
                    );

                float haloMask =
                    1.0 -
                    smoothstep(
                        0.45,
                        0.68,
                        r
                    );

                float pv =
                    plasma(
                        p * 1.35,
                        t
                    );

                float plasmaBands =
                    smoothstep(
                        0.05,
                        0.88,
                        0.5 + 0.5 * pv
                    );

                float whiteCore =
                    exp(
                        -r *
                        r *
                        42.0
                    );

                float innerGlow =
                    exp(
                        -r *
                        r *
                        11.0
                    );

                color +=
                    uPrimary *
                    innerGlow *
                    1.25;

                color +=
                    uSecondary *
                    coreMask *
                    plasmaBands *
                    0.95;

                color +=
                    uAccent *
                    coreMask *
                    pow(
                        plasmaBands,
                        3.0
                    ) *
                    0.65;

                color +=
                    float3(1.0) *
                    whiteCore *
                    1.65;

                // Braided luminous filaments.
                for (
                    int i = 0;
                    i < 18;
                    i++
                ) {
                    float fi = float(i);

                    float phase =
                        t *
                        (
                            0.46 +
                            0.025 * fi
                        ) *
                        (
                            mod(
                                fi,
                                2.0
                            ) <
                            1.0
                                ? 1.0
                                : -0.86
                        );

                    float target =
                        0.29 +
                        0.018 *
                        sin(
                            a *
                            (
                                2.0 +
                                mod(
                                    fi,
                                    5.0
                                )
                            ) +
                            phase +
                            fi *
                            0.71
                        ) +
                        0.055 *
                        sin(
                            a *
                            (
                                4.0 +
                                mod(
                                    fi,
                                    3.0
                                )
                            ) -
                            phase *
                            0.73
                        );

                    float d =
                        abs(
                            r -
                            target
                        );

                    float filament =
                        exp(
                            -d *
                            (
                                260.0 +
                                mod(
                                    fi,
                                    4.0
                                ) *
                                55.0
                            )
                        );

                    float pulse =
                        0.52 +
                        0.48 *
                        sin(
                            a *
                            (
                                3.0 +
                                mod(
                                    fi,
                                    4.0
                                )
                            ) +
                            phase *
                            1.8 +
                            fi
                        );

                    float3 fc =
                        mod(
                            fi,
                            3.0
                        ) <
                        1.0
                            ? uPrimary
                            : (
                                mod(
                                    fi,
                                    3.0
                                ) <
                                2.0
                                    ? uSecondary
                                    : uAccent
                            );

                    color +=
                        fc *
                        filament *
                        (
                            0.38 +
                            0.48 *
                            pulse
                        );
                }

                // Segmented outer data halo.
                for (
                    int j = 0;
                    j < 5;
                    j++
                ) {
                    float fj = float(j);

                    float rr =
                        0.39 +
                        fj *
                        0.035;

                    float seg =
                        0.5 +
                        0.5 *
                        sin(
                            a *
                            (
                                28.0 +
                                fj *
                                5.0
                            ) +
                            t *
                            (
                                0.35 +
                                fj *
                                0.09
                            ) *
                            (
                                mod(
                                    fj,
                                    2.0
                                ) <
                                1.0
                                    ? 1.0
                                    : -1.0
                            )
                        );

                    seg =
                        smoothstep(
                            0.52,
                            0.90,
                            seg
                        );

                    float rg =
                        ring(
                            r,
                            rr,
                            0.0045
                        ) *
                        seg;

                    float3 rc =
                        mod(
                            fj,
                            2.0
                        ) <
                        1.0
                            ? uPrimary
                            : uSecondary;

                    color +=
                        rc *
                        rg *
                        (
                            0.45 +
                            0.18 *
                            fj
                        );
                }

                // Spark field around the halo.
                float2 cell =
                    floor(
                        p *
                        42.0
                    );

                float noise =
                    hash21(
                        cell
                    );

                float sparkGate =
                    step(
                        0.91,
                        noise
                    );

                float sparkBand =
                    1.0 -
                    smoothstep(
                        0.06,
                        0.20,
                        abs(
                            r -
                            0.46
                        )
                    );

                float sparkle =
                    sparkGate *
                    sparkBand *
                    (
                        0.30 +
                        0.70 *
                        abs(
                            sin(
                                t *
                                2.1 +
                                noise *
                                13.0
                            )
                        )
                    );

                color +=
                    mix(
                        uPrimary,
                        float3(1.0),
                        noise
                    ) *
                    sparkle *
                    1.35;

                // State-specific shader behavior.
                if (
                    uState >
                    0.5 &&
                    uState <
                    1.5
                ) {
                    // Recognition scan.
                    float scanA =
                        t *
                        1.8;

                    float2 dir =
                        float2(
                            cos(scanA),
                            sin(scanA)
                        );

                    float scan =
                        lineGlow(
                            dot(
                                p,
                                float2(
                                    -dir.y,
                                    dir.x
                                )
                            ),
                            0.010
                        ) *
                        smoothstep(
                            0.52,
                            0.05,
                            r
                        );

                    color +=
                        float3(1.0) *
                        scan *
                        0.95;
                }

                if (
                    uState >
                    1.5 &&
                    uState <
                    2.5
                ) {
                    // Thinking: denser branching interference.
                    float branch =
                        sin(
                            p.x *
                            37.0 +
                            sin(
                                p.y *
                                18.0 +
                                t
                            ) *
                            4.0
                        ) *
                        sin(
                            p.y *
                            31.0 -
                            cos(
                                p.x *
                                16.0 -
                                t
                            ) *
                            4.0
                        );

                    branch =
                        pow(
                            abs(branch),
                            7.0
                        );

                    color +=
                        uAccent *
                        branch *
                        haloMask *
                        0.75;
                }

                if (
                    uState >
                    2.5 &&
                    uState <
                    3.5
                ) {
                    // Executing: directed rightward energy.
                    float jet =
                        lineGlow(
                            p.y,
                            0.020
                        ) *
                        smoothstep(
                            -0.05,
                            0.40,
                            p.x
                        );

                    color +=
                        float3(1.0) *
                        jet *
                        0.55;

                    color +=
                        uPrimary *
                        jet *
                        0.95;
                }

                if (
                    uState >
                    3.5 &&
                    uState <
                    4.5
                ) {
                    // Answering: coherent response rings.
                    float response =
                        ring(
                            r,
                            0.18 +
                            0.19 *
                            fract(
                                t *
                                0.34
                            ),
                            0.006
                        ) +
                        ring(
                            r,
                            0.27 +
                            0.17 *
                            fract(
                                t *
                                0.29
                            ),
                            0.006
                        );

                    color +=
                        uPrimary *
                        response *
                        0.85;
                }

                if (
                    uState >
                    4.5
                ) {
                    // Stop: fracture and reduce coherence.
                    float crack =
                        pow(
                            abs(
                                sin(
                                    a *
                                    11.0 +
                                    sin(
                                        r *
                                        23.0
                                    )
                                )
                            ),
                            16.0
                        );

                    float broken =
                        smoothstep(
                            0.24,
                            0.55,
                            r
                        ) *
                        crack;

                    color +=
                        uSecondary *
                        broken *
                        1.25;

                    color *=
                        0.62;
                }

                float vignette =
                    1.0 -
                    smoothstep(
                        0.47,
                        0.72,
                        r
                    );

                color *=
                    0.88 +
                    0.12 *
                    vignette;

                alpha =
                    clamp(
                        max(
                            max(
                                color.r,
                                color.g
                            ),
                            color.b
                        ) *
                        0.95,
                        0.0,
                        1.0
                    );

                return
                    half4(
                        half3(color),
                        half(alpha)
                    );
            }
            """
    }
}
