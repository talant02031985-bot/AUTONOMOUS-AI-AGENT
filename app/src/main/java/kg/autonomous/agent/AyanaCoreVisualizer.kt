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
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v10.0 — NEURAL FABRIC.
 *
 * Full visual direction reset after rejecting orb / radar / HUD metaphors.
 *
 * This visualizer represents an AI AGENT as a live computational graph:
 * input -> perception -> reasoning -> memory/tool routing -> action/answer.
 *
 * Visual language:
 * - NO big circular orb;
 * - NO radar screen;
 * - NO static PNG;
 * - NO giant AYANA wordmark;
 * - layered neural/data graph spanning the whole panel;
 * - moving information packets;
 * - live reasoning cross-links;
 * - dedicated memory/tool junctions;
 * - explicit left-to-right execution flow;
 * - six factual AYANA runtime states.
 *
 * States:
 * 0 Ожидание
 * 1 Распознавание
 * 2 Думаю
 * 3 Выполняю
 * 4 Отвечаю
 * 5 Стоп
 *
 * Public integration contract:
 *   AyanaCoreVisualizer(Context)
 *
 * Visual-only component. No ORB, routing, TTS, microphone capture,
 * Accessibility actions or command execution logic is changed here.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

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
            typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )
        }

    private val path =
        Path()

    private val path2 =
        Path()

    private val nodeRect =
        RectF()

    private var attached =
        false

    private var hostNormalized =
        false

    init {
        importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO

        isFocusable =
            false

        isClickable =
            false

        isLongClickable =
            false

        setBackgroundColor(
            Color.TRANSPARENT
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        attached =
            true

        post {
            normalizeHostLayoutOnce()
        }

        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached =
            false

        super.onDetachedFromWindow()
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(canvas)

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val state =
            stateIndex(
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

        val compact =
            h < dp(180f)

        val railHeight =
            if (compact) {
                0f
            } else {
                min(
                    dp(42f),
                    h * 0.145f
                )
            }

        val contentBottom =
            h - railHeight

        val leftX =
            w * 0.055f

        val rightX =
            w * 0.945f

        val topY =
            h * 0.085f

        val bottomY =
            contentBottom * 0.90f

        drawBackground(
            canvas = canvas,
            now = now,
            topY = topY,
            bottomY = bottomY,
            palette = palette,
            motion = motion
        )

        drawInputBus(
            canvas = canvas,
            now = now,
            x0 = leftX,
            x1 = w * 0.20f,
            centerY = (topY + bottomY) * 0.50f,
            palette = palette,
            motion = motion,
            state = state
        )

        drawNeuralFabric(
            canvas = canvas,
            now = now,
            left = w * 0.18f,
            right = w * 0.77f,
            top = topY,
            bottom = bottomY,
            palette = palette,
            motion = motion,
            state = state
        )

        drawDecisionGateway(
            canvas = canvas,
            now = now,
            x = w * 0.72f,
            top = topY,
            bottom = bottomY,
            palette = palette,
            motion = motion,
            state = state
        )

        drawActionBus(
            canvas = canvas,
            now = now,
            x0 = w * 0.74f,
            x1 = rightX,
            centerY = (topY + bottomY) * 0.50f,
            palette = palette,
            motion = motion,
            state = state
        )

        drawPackets(
            canvas = canvas,
            now = now,
            left = leftX,
            right = rightX,
            top = topY,
            bottom = bottomY,
            palette = palette,
            motion = motion,
            state = state
        )

        drawStateSignature(
            canvas = canvas,
            now = now,
            left = leftX,
            right = rightX,
            top = topY,
            bottom = bottomY,
            palette = palette,
            state = state
        )

        if (
            railHeight > 0f
        ) {
            drawStateRail(
                canvas = canvas,
                top = contentBottom,
                railHeight = railHeight,
                active = state
            )
        }

        if (attached) {
            postInvalidateDelayed(
                motion.frameDelayMs
            )
        }
    }

    private fun normalizeHostLayoutOnce() {
        if (hostNormalized) {
            return
        }

        hostNormalized =
            true

        val card =
            parent as?
                LinearLayout
                ?: return

        if (
            card.orientation !=
            LinearLayout.HORIZONTAL ||
            card.childCount < 2
        ) {
            return
        }

        val copy =
            card.getChildAt(0) as?
                LinearLayout
                ?: return

        val ownParams =
            layoutParams as?
                LinearLayout.LayoutParams

        val copyParams =
            copy.layoutParams as?
                LinearLayout.LayoutParams

        if (
            ownParams != null &&
            copyParams != null
        ) {
            copyParams.weight =
                0.72f

            ownParams.weight =
                1.64f

            copy.layoutParams =
                copyParams

            layoutParams =
                ownParams
        }

        val title =
            copy.getChildAt(1) as?
                TextView

        title?.apply {
            textSize =
                30f

            maxLines =
                2

            gravity =
                Gravity.START or
                    Gravity.CENTER_VERTICAL

            setLineSpacing(
                0f,
                0.94f
            )
        }

        card.requestLayout()
    }

    private fun drawBackground(
        canvas: Canvas,
        now: Long,
        topY: Float,
        bottomY: Float,
        palette: Palette,
        motion: Motion
    ) {
        fill.shader =
            LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.parseColor("#02060C"),
                    Color.parseColor("#08111B"),
                    Color.parseColor("#02060C")
                ),
                floatArrayOf(
                    0f,
                    0.52f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha =
            255

        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            fill
        )

        fill.shader =
            null

        // Sparse, brighter engineering grid.
        stroke.shader =
            null

        stroke.color =
            Color.parseColor("#24374D")

        stroke.alpha =
            92

        stroke.strokeWidth =
            dp(0.55f)

        val cols =
            12

        val rows =
            6

        for (
            i in
            1 until cols
        ) {
            val x =
                width *
                    i /
                    cols.toFloat()

            canvas.drawLine(
                x,
                topY,
                x,
                bottomY,
                stroke
            )
        }

        for (
            i in
            1 until rows
        ) {
            val y =
                topY +
                    (
                        bottomY - topY
                    ) *
                    i /
                    rows.toFloat()

            canvas.drawLine(
                width * 0.025f,
                y,
                width * 0.975f,
                y,
                stroke
            )
        }

        // Moving "analysis plane".
        val scan =
            (
                now %
                    motion.scanPeriodMs
                ).toFloat() /
                motion.scanPeriodMs.toFloat()

        val scanX =
            width *
                (
                    0.08f +
                        scan *
                            0.84f
                    )

        fill.shader =
            LinearGradient(
                scanX - dp(36f),
                0f,
                scanX + dp(36f),
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        palette.primary,
                        22
                    ),
                    withAlpha(
                        palette.primary,
                        115
                    ),
                    withAlpha(
                        palette.primary,
                        22
                    ),
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

        fill.alpha =
            motion.scanAlpha

        canvas.drawRect(
            scanX - dp(36f),
            topY,
            scanX + dp(36f),
            bottomY,
            fill
        )

        fill.shader =
            null
    }

    private fun drawInputBus(
        canvas: Canvas,
        now: Long,
        x0: Float,
        x1: Float,
        centerY: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val boost =
            if (
                state ==
                STATE_RECOGNITION
            ) {
                1.70f
            } else {
                1f
            }

        drawWave(
            canvas = canvas,
            now = now,
            left = x0,
            right = x1,
            cy = centerY,
            amplitude =
                min(
                    height * 0.105f,
                    dp(26f)
                ) *
                    boost,
            primary =
                palette.primary,
            secondary =
                palette.secondary,
            period =
                motion.wavePeriodMs,
            frequency =
                7.5,
            alpha =
                motion.waveAlpha
        )

        repeat(
            5
        ) {
            lane ->

            val y =
                centerY +
                    (
                        lane - 2
                    ) *
                    dp(14f)

            stroke.color =
                palette.primary

            stroke.alpha =
                if (
                    lane == 2
                ) {
                    135
                } else {
                    64
                }

            stroke.strokeWidth =
                dp(
                    if (
                        lane == 2
                    ) {
                        1.0f
                    } else {
                        0.60f
                    }
                )

            canvas.drawLine(
                x0,
                y,
                x1,
                y,
                stroke
            )
        }
    }

    private fun drawNeuralFabric(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val columns =
            7

        val rows =
            5

        val columnGap =
            (
                right - left
            ) /
                (
                    columns - 1
                )

        val rowGap =
            (
                bottom - top
            ) /
                (
                    rows - 1
                )

        val phase =
            now /
                motion.nodePeriodMs

        // Adjacent-layer graph connections.
        for (
            col in
            0 until columns - 1
        ) {
            for (
                row in
                0 until rows
            ) {
                val x1 =
                    left +
                        columnGap *
                            col

                val y1 =
                    top +
                        rowGap *
                            row +
                        sin(
                            phase +
                                col *
                                    0.71 +
                                row *
                                    0.49
                        )
                            .toFloat() *
                            dp(5f)

                // Every node fans to two next-layer nodes.
                repeat(
                    2
                ) {
                    branch ->

                    val nextRow =
                        (
                            row +
                                branch +
                                col
                            ) %
                            rows

                    val x2 =
                        left +
                            columnGap *
                                (
                                    col + 1
                                )

                    val y2 =
                        top +
                            rowGap *
                                nextRow +
                            sin(
                                phase *
                                    0.87 +
                                    (
                                        col + 1
                                    ) *
                                        0.63 +
                                    nextRow *
                                        0.43
                            )
                                .toFloat() *
                                dp(5f)

                    val highlight =
                        (
                            (
                                col +
                                    row +
                                    branch
                                ) %
                                5
                            ) ==
                            (
                                now /
                                    420L
                                ) %
                                5L
                            .toInt()

                    val connectionColor =
                        if (highlight) {
                            palette.accent
                        } else {
                            palette.primary
                        }

                    stroke.color =
                        connectionColor

                    stroke.alpha =
                        if (highlight) {
                            150
                        } else {
                            if (
                                state ==
                                STATE_THINKING
                            ) {
                                86
                            } else {
                                54
                            }
                        }

                    stroke.strokeWidth =
                        dp(
                            if (highlight) {
                                1.0f
                            } else {
                                0.55f
                            }
                        )

                    canvas.drawLine(
                        x1,
                        y1,
                        x2,
                        y2,
                        stroke
                    )

                    if (highlight) {
                        glow.color =
                            connectionColor

                        glow.alpha =
                            38

                        glow.strokeWidth =
                            dp(5f)

                        canvas.drawLine(
                            x1,
                            y1,
                            x2,
                            y2,
                            glow
                        )
                    }
                }
            }
        }

        // Nodes.
        for (
            col in
            0 until columns
        ) {
            for (
                row in
                0 until rows
            ) {
                val x =
                    left +
                        columnGap *
                            col

                val y =
                    top +
                        rowGap *
                            row +
                        sin(
                            phase +
                                col *
                                    0.71 +
                                row *
                                    0.49
                        )
                            .toFloat() *
                            dp(5f)

                val nodePulse =
                    (
                        0.5 +
                            0.5 *
                                sin(
                                    phase *
                                        1.5 +
                                        col *
                                            0.83 +
                                        row *
                                            0.68
                                )
                        )
                        .toFloat()

                val special =
                    col == 3 &&
                        row == 2

                val nodeColor =
                    when {
                        special ->
                            Color.WHITE

                        col < 2 ->
                            palette.primary

                        col < 5 ->
                            palette.secondary

                        else ->
                            palette.accent
                    }

                if (special) {
                    fill.shader =
                        RadialGradient(
                            x,
                            y,
                            dp(24f),
                            intArrayOf(
                                Color.WHITE,
                                palette.primary,
                                palette.secondary,
                                Color.TRANSPARENT
                            ),
                            floatArrayOf(
                                0f,
                                0.22f,
                                0.58f,
                                1f
                            ),
                            Shader.TileMode.CLAMP
                        )

                    fill.alpha =
                        245

                    canvas.drawCircle(
                        x,
                        y,
                        dp(
                            15f +
                                nodePulse *
                                    2f
                        ),
                        fill
                    )

                    fill.shader =
                        null
                } else {
                    fill.color =
                        nodeColor

                    fill.alpha =
                        (
                            150 +
                                nodePulse *
                                    90f
                            )
                            .toInt()
                            .coerceAtMost(
                                240
                            )

                    canvas.drawCircle(
                        x,
                        y,
                        dp(
                            1.7f +
                                nodePulse *
                                    1.1f
                        ),
                        fill
                    )

                    glow.color =
                        nodeColor

                    glow.alpha =
                        34

                    glow.strokeWidth =
                        dp(4f)

                    canvas.drawCircle(
                        x,
                        y,
                        dp(
                            3f +
                                nodePulse *
                                    1.4f
                        ),
                        glow
                    )
                }
            }
        }

        drawMemoryToolJunctions(
            canvas = canvas,
            now = now,
            left = left,
            right = right,
            top = top,
            bottom = bottom,
            palette = palette,
            state = state
        )
    }

    private fun drawMemoryToolJunctions(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        palette: Palette,
        state: Int
    ) {
        val centerX =
            (
                left + right
            ) *
                0.50f

        val memoryX =
            centerX -
                (
                    right - left
                ) *
                    0.18f

        val toolX =
            centerX +
                (
                    right - left
                ) *
                    0.22f

        val yTop =
            top +
                (
                    bottom - top
                ) *
                    0.15f

        val yBottom =
            bottom -
                (
                    bottom - top
                ) *
                    0.15f

        // Memory junction.
        drawDiamondNode(
            canvas = canvas,
            x = memoryX,
            y = yTop,
            color =
                if (
                    state ==
                    STATE_THINKING
                ) {
                    palette.accent
                } else {
                    palette.primary
                },
            active =
                state ==
                    STATE_THINKING
        )

        // Tool/action junction.
        drawDiamondNode(
            canvas = canvas,
            x = toolX,
            y = yBottom,
            color =
                if (
                    state ==
                    STATE_EXECUTING
                ) {
                    palette.accent
                } else {
                    palette.secondary
                },
            active =
                state ==
                    STATE_EXECUTING
        )

        stroke.color =
            palette.secondary

        stroke.alpha =
            82

        stroke.strokeWidth =
            dp(0.70f)

        canvas.drawLine(
            memoryX,
            yTop,
            centerX,
            (
                top + bottom
            ) *
                0.50f,
            stroke
        )

        canvas.drawLine(
            toolX,
            yBottom,
            centerX,
            (
                top + bottom
            ) *
                0.50f,
            stroke
        )
    }

    private fun drawDiamondNode(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
        active: Boolean
    ) {
        val r =
            dp(
                if (active) {
                    8f
                } else {
                    6f
                }
            )

        path.reset()

        path.moveTo(
            x,
            y - r
        )

        path.lineTo(
            x + r,
            y
        )

        path.lineTo(
            x,
            y + r
        )

        path.lineTo(
            x - r,
            y
        )

        path.close()

        glow.color =
            color

        glow.alpha =
            if (active) {
                80
            } else {
                30
            }

        glow.strokeWidth =
            dp(
                if (active) {
                    7f
                } else {
                    4f
                }
            )

        canvas.drawPath(
            path,
            glow
        )

        stroke.color =
            color

        stroke.alpha =
            if (active) {
                245
            } else {
                150
            }

        stroke.strokeWidth =
            dp(
                if (active) {
                    1.5f
                } else {
                    0.9f
                }
            )

        canvas.drawPath(
            path,
            stroke
        )
    }

    private fun drawDecisionGateway(
        canvas: Canvas,
        now: Long,
        x: Float,
        top: Float,
        bottom: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val midY =
            (
                top + bottom
            ) *
                0.50f

        val halfH =
            (
                bottom - top
            ) *
                0.40f

        // Bright vertical execution gate.
        stroke.shader =
            LinearGradient(
                x,
                midY - halfH,
                x,
                midY + halfH,
                intArrayOf(
                    Color.TRANSPARENT,
                    palette.primary,
                    Color.WHITE,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.22f,
                    0.50f,
                    0.78f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        stroke.alpha =
            motion.gatewayAlpha

        stroke.strokeWidth =
            dp(1.2f)

        canvas.drawLine(
            x,
            midY - halfH,
            x,
            midY + halfH,
            stroke
        )

        glow.shader =
            stroke.shader

        glow.alpha =
            62

        glow.strokeWidth =
            dp(7f)

        canvas.drawLine(
            x,
            midY - halfH,
            x,
            midY + halfH,
            glow
        )

        stroke.shader =
            null

        glow.shader =
            null

        val travel =
            (
                now %
                    motion.gatewayPulseMs
                ).toFloat() /
                motion.gatewayPulseMs.toFloat()

        val pulseY =
            midY - halfH +
                (
                    halfH * 2f
                ) *
                    travel

        fill.shader =
            RadialGradient(
                x,
                pulseY,
                dp(18f),
                intArrayOf(
                    Color.WHITE,
                    palette.primary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.30f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha =
            if (
                state ==
                STATE_STOP
            ) {
                65
            } else {
                210
            }

        canvas.drawCircle(
            x,
            pulseY,
            dp(18f),
            fill
        )

        fill.shader =
            null
    }

    private fun drawActionBus(
        canvas: Canvas,
        now: Long,
        x0: Float,
        x1: Float,
        centerY: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val boost =
            when(state) {
                STATE_EXECUTING ->
                    1.45f

                STATE_ANSWERING ->
                    1.75f

                else ->
                    1f
            }

        drawWave(
            canvas = canvas,
            now = now,
            left = x0,
            right = x1,
            cy = centerY,
            amplitude =
                min(
                    height * 0.105f,
                    dp(26f)
                ) *
                    boost,
            primary =
                palette.secondary,
            secondary =
                palette.primary,
            period =
                motion.wavePeriodMs *
                    0.94,
            frequency =
                8.4,
            alpha =
                motion.waveAlpha
        )

        // Tool/action rails.
        repeat(
            4
        ) {
            lane ->

            val y =
                centerY +
                    (
                        lane - 1.5f
                    ) *
                    dp(17f)

            stroke.color =
                palette.secondary

            stroke.alpha =
                if (
                    state ==
                    STATE_EXECUTING
                ) {
                    150
                } else {
                    72
                }

            stroke.strokeWidth =
                dp(
                    if (
                        state ==
                        STATE_EXECUTING
                    ) {
                        1.0f
                    } else {
                        0.65f
                    }
                )

            canvas.drawLine(
                x0,
                y,
                x1,
                y,
                stroke
            )
        }
    }

    private fun drawPackets(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val direction =
            when(state) {
                STATE_RECOGNITION ->
                    -1f

                STATE_EXECUTING,
                STATE_ANSWERING ->
                    1f

                else ->
                    motion.direction
            }

        repeat(
            motion.packetCount
        ) {
            i ->

            val raw =
                (
                    now /
                        motion.packetPeriodMs +
                        i /
                            motion.packetCount.toDouble()
                    ) %
                    1.0

            val t =
                if (
                    direction >= 0f
                ) {
                    raw
                } else {
                    1.0 - raw
                }

            val x =
                left +
                    (
                        right - left
                    ) *
                    t.toFloat()

            val lane =
                i % 5

            val y =
                top +
                    (
                        bottom - top
                    ) *
                    (
                        0.12f +
                            lane *
                                0.19f
                        ) +
                    sin(
                        now /
                            430.0 +
                            i *
                                0.74
                    )
                        .toFloat() *
                        dp(3f)

            val color =
                when(
                    i % 3
                ) {
                    0 ->
                        palette.primary

                    1 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            fill.color =
                color

            fill.alpha =
                motion.packetAlpha

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (
                        i % 4 == 0
                    ) {
                        2.4f
                    } else {
                        1.35f
                    }
                ),
                fill
            )
        }
    }

    private fun drawStateSignature(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        top: Float,
        bottom: Float,
        palette: Palette,
        state: Int
    ) {
        val cy =
            (
                top + bottom
            ) *
                0.50f

        when(state) {
            STATE_WAITING -> {
                // Gentle readiness beacon at the center graph node.
                val x =
                    (
                        left + right
                    ) *
                        0.47f

                val pulse =
                    (
                        0.5 +
                            0.5 *
                                sin(
                                    now / 980.0
                                )
                        )
                        .toFloat()

                fill.shader =
                    RadialGradient(
                        x,
                        cy,
                        dp(22f),
                        intArrayOf(
                            Color.WHITE,
                            withAlpha(
                                palette.primary,
                                150
                            ),
                            Color.TRANSPARENT
                        ),
                        floatArrayOf(
                            0f,
                            0.30f,
                            1f
                        ),
                        Shader.TileMode.CLAMP
                    )

                fill.alpha =
                    (
                        90 +
                            pulse *
                                100f
                        )
                        .toInt()

                canvas.drawCircle(
                    x,
                    cy,
                    dp(22f),
                    fill
                )

                fill.shader =
                    null
            }

            STATE_RECOGNITION -> {
                drawEdgeBars(
                    canvas,
                    now,
                    true,
                    palette.primary,
                    1.0f
                )
            }

            STATE_THINKING -> {
                // Dense reasoning cross-link flash.
                stroke.color =
                    palette.accent

                stroke.alpha =
                    145

                stroke.strokeWidth =
                    dp(0.85f)

                repeat(
                    7
                ) {
                    i ->

                    val x1 =
                        width *
                            (
                                0.30f +
                                    i *
                                        0.045f
                                )

                    val y1 =
                        top +
                            (
                                bottom - top
                            ) *
                                (
                                    0.18f +
                                        (
                                            i % 3
                                        ) *
                                            0.28f
                                    )

                    val x2 =
                        width *
                            (
                                0.57f +
                                    (
                                        i % 2
                                    ) *
                                        0.08f
                                )

                    val y2 =
                        top +
                            (
                                bottom - top
                            ) *
                                (
                                    0.25f +
                                        (
                                            (
                                                i + 1
                                            ) % 3
                                        ) *
                                            0.24f
                                    )

                    canvas.drawLine(
                        x1,
                        y1,
                        x2,
                        y2,
                        stroke
                    )
                }
            }

            STATE_EXECUTING -> {
                // Directed execution beam into the tool/action side.
                glow.color =
                    palette.secondary

                glow.alpha =
                    110

                glow.strokeWidth =
                    dp(9f)

                canvas.drawLine(
                    width * 0.68f,
                    cy,
                    right,
                    cy,
                    glow
                )

                stroke.color =
                    Color.WHITE

                stroke.alpha =
                    235

                stroke.strokeWidth =
                    dp(0.95f)

                canvas.drawLine(
                    width * 0.68f,
                    cy,
                    right,
                    cy,
                    stroke
                )
            }

            STATE_ANSWERING -> {
                drawEdgeBars(
                    canvas,
                    now,
                    false,
                    palette.primary,
                    1.18f
                )
            }

            STATE_STOP -> {
                // Whole graph enters a stop barrier.
                val x =
                    width * 0.72f

                stroke.color =
                    Color.WHITE

                stroke.alpha =
                    245

                stroke.strokeWidth =
                    dp(2.0f)

                canvas.drawLine(
                    x - dp(12f),
                    cy - dp(12f),
                    x + dp(12f),
                    cy + dp(12f),
                    stroke
                )

                canvas.drawLine(
                    x + dp(12f),
                    cy - dp(12f),
                    x - dp(12f),
                    cy + dp(12f),
                    stroke
                )

                fill.color =
                    palette.primary

                fill.alpha =
                    72

                canvas.drawRect(
                    x - dp(3f),
                    top,
                    x + dp(3f),
                    bottom,
                    fill
                )
            }
        }
    }

    private fun drawEdgeBars(
        canvas: Canvas,
        now: Long,
        leftSide: Boolean,
        color: Int,
        strength: Float
    ) {
        repeat(
            12
        ) {
            i ->

            val x =
                if (leftSide) {
                    width *
                        (
                            0.030f +
                                i *
                                    0.011f
                            )
                } else {
                    width *
                        (
                            0.849f +
                                i *
                                    0.011f
                            )
                }

            val pulse =
                abs(
                    sin(
                        now /
                            155.0 +
                            i *
                                0.81
                    )
                        .toFloat()
                )

            val half =
                dp(
                    4f +
                        pulse *
                            22f *
                            strength
                )

            stroke.color =
                color

            stroke.alpha =
                (
                    70 +
                        pulse *
                            170f
                    )
                    .toInt()
                    .coerceAtMost(
                        235
                    )

            stroke.strokeWidth =
                dp(1.0f)

            canvas.drawLine(
                x,
                height * 0.43f - half,
                x,
                height * 0.43f + half,
                stroke
            )
        }
    }

    private fun drawWave(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        cy: Float,
        amplitude: Float,
        primary: Int,
        secondary: Int,
        period: Double,
        frequency: Double,
        alpha: Int
    ) {
        if (
            right <= left
        ) {
            return
        }

        path.reset()

        path2.reset()

        val samples =
            66

        val phase =
            now / period

        for (
            i in
            0..samples
        ) {
            val t =
                i /
                    samples.toFloat()

            val x =
                left +
                    (
                        right - left
                    ) *
                    t

            val envelope =
                sin(
                    PI * t
                )
                    .toFloat()
                    .coerceAtLeast(
                        0f
                    )

            val main =
                sin(
                    t *
                        PI *
                        frequency +
                        phase
                )
                    .toFloat()

            val detail =
                sin(
                    t *
                        PI *
                        23.0 -
                        phase * 0.77
                )
                    .toFloat() *
                    0.24f

            val y =
                cy +
                    (
                        main +
                            detail
                    ) *
                    amplitude *
                    envelope

            if (
                i == 0
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

            val fine =
                sin(
                    t *
                        PI *
                        39.0 +
                        phase *
                            1.21
                )
                    .toFloat()

            val fy =
                cy +
                    fine *
                    amplitude *
                    0.18f *
                    envelope

            if (
                i == 0
            ) {
                path2.moveTo(
                    x,
                    fy
                )
            } else {
                path2.lineTo(
                    x,
                    fy
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
                    primary,
                    Color.WHITE,
                    secondary,
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
            92

        glow.strokeWidth =
            dp(7f)

        canvas.drawPath(
            path,
            glow
        )

        stroke.shader =
            gradient

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(1.45f)

        canvas.drawPath(
            path,
            stroke
        )

        stroke.shader =
            null

        stroke.color =
            Color.WHITE

        stroke.alpha =
            70

        stroke.strokeWidth =
            dp(0.55f)

        canvas.drawPath(
            path2,
            stroke
        )
    }

    private fun drawStateRail(
        canvas: Canvas,
        top: Float,
        railHeight: Float,
        active: Int
    ) {
        val bottom =
            (
                top +
                    railHeight
                )
                .coerceAtMost(
                    height.toFloat()
                )

        if (
            bottom <= top
        ) {
            return
        }

        val save =
            canvas.save()

        canvas.clipRect(
            0f,
            top,
            width.toFloat(),
            bottom
        )

        fill.shader =
            null

        fill.color =
            Color.parseColor("#02050A")

        fill.alpha =
            238

        canvas.drawRect(
            0f,
            top,
            width.toFloat(),
            bottom,
            fill
        )

        stroke.color =
            Color.parseColor("#31415A")

        stroke.alpha =
            225

        stroke.strokeWidth =
            dp(0.8f)

        canvas.drawLine(
            width * 0.035f,
            top,
            width * 0.965f,
            top,
            stroke
        )

        val labels =
            arrayOf(
                "Ожидание",
                "Распознавание",
                "Думаю",
                "Выполняю",
                "Отвечаю",
                "Стоп"
            )

        val left =
            width * 0.065f

        val right =
            width * 0.935f

        val step =
            (
                right - left
            ) /
                (
                    labels.size - 1
                )

        val y =
            top +
                railHeight *
                    0.38f

        val labelY =
            top +
                railHeight *
                    0.81f

        stroke.color =
            Color.parseColor("#3A4A62")

        stroke.alpha =
            190

        stroke.strokeWidth =
            dp(0.72f)

        canvas.drawLine(
            left,
            y,
            right,
            y,
            stroke
        )

        labels.indices.forEach {
            i ->

            val x =
                left +
                    step *
                        i

            val selected =
                i == active

            val color =
                paletteFor(i).primary

            fill.color =
                color

            fill.alpha =
                if (selected) {
                    255
                } else {
                    105
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (selected) {
                        3.2f
                    } else {
                        1.9f
                    }
                ),
                fill
            )

            if (selected) {
                glow.color =
                    color

                glow.alpha =
                    78

                glow.strokeWidth =
                    dp(6f)

                canvas.drawCircle(
                    x,
                    y,
                    dp(9.8f),
                    glow
                )

                stroke.color =
                    Color.WHITE

                stroke.alpha =
                    235

                stroke.strokeWidth =
                    dp(1f)

                canvas.drawCircle(
                    x,
                    y,
                    dp(8f),
                    stroke
                )
            }

            text.textSize =
                dp(
                    if (selected) {
                        8.9f
                    } else {
                        8.2f
                    }
                )

            text.typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    if (selected) {
                        Typeface.BOLD
                    } else {
                        Typeface.NORMAL
                    }
                )

            text.color =
                if (selected) {
                    color
                } else {
                    Color.parseColor("#8794A9")
                }

            text.alpha =
                if (selected) {
                    255
                } else {
                    225
                }

            canvas.drawText(
                labels[i],
                x,
                labelY,
                text
            )
        }

        canvas.restoreToCount(
            save
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
                    primary =
                        Color.parseColor("#27E8F4"),
                    secondary =
                        Color.parseColor("#00B8D4"),
                    accent =
                        Color.parseColor("#8AF5FF")
                )

            STATE_RECOGNITION ->
                Palette(
                    primary =
                        Color.parseColor("#2494FF"),
                    secondary =
                        Color.parseColor("#1265FF"),
                    accent =
                        Color.parseColor("#A6E8FF")
                )

            STATE_THINKING ->
                Palette(
                    primary =
                        Color.parseColor("#7654FF"),
                    secondary =
                        Color.parseColor("#A54DFF"),
                    accent =
                        Color.parseColor("#CDBDFF")
                )

            STATE_EXECUTING ->
                Palette(
                    primary =
                        Color.parseColor("#31E978"),
                    secondary =
                        Color.parseColor("#16D6AA"),
                    accent =
                        Color.parseColor("#A1F9D9")
                )

            STATE_ANSWERING ->
                Palette(
                    primary =
                        Color.parseColor("#EF35D0"),
                    secondary =
                        Color.parseColor("#A64DFF"),
                    accent =
                        Color.parseColor("#F3B5FF")
                )

            STATE_STOP ->
                Palette(
                    primary =
                        Color.parseColor("#FF482E"),
                    secondary =
                        Color.parseColor("#FF7A2B"),
                    accent =
                        Color.parseColor("#FFAE8A")
                )

            else ->
                paletteFor(
                    STATE_WAITING
                )
        }
    }

    private fun motionFor(
        state: Int
    ): Motion {
        return when(state) {
            STATE_WAITING ->
                Motion(
                    frameDelayMs = 36L,
                    scanPeriodMs = 4600L,
                    scanAlpha = 88,
                    wavePeriodMs = 700.0,
                    waveAlpha = 208,
                    nodePeriodMs = 1450.0,
                    gatewayAlpha = 220,
                    gatewayPulseMs = 3200L,
                    packetCount = 10,
                    packetPeriodMs = 2250.0,
                    packetAlpha = 188,
                    direction = 1f
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1700L,
                    scanAlpha = 138,
                    wavePeriodMs = 320.0,
                    waveAlpha = 248,
                    nodePeriodMs = 780.0,
                    gatewayAlpha = 245,
                    gatewayPulseMs = 1650L,
                    packetCount = 15,
                    packetPeriodMs = 960.0,
                    packetAlpha = 230,
                    direction = -1f
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 29L,
                    scanPeriodMs = 2600L,
                    scanAlpha = 122,
                    wavePeriodMs = 470.0,
                    waveAlpha = 232,
                    nodePeriodMs = 620.0,
                    gatewayAlpha = 250,
                    gatewayPulseMs = 1380L,
                    packetCount = 17,
                    packetPeriodMs = 1180.0,
                    packetAlpha = 235,
                    direction = -1f
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 26L,
                    scanPeriodMs = 1450L,
                    scanAlpha = 146,
                    wavePeriodMs = 275.0,
                    waveAlpha = 252,
                    nodePeriodMs = 560.0,
                    gatewayAlpha = 255,
                    gatewayPulseMs = 1120L,
                    packetCount = 19,
                    packetPeriodMs = 760.0,
                    packetAlpha = 242,
                    direction = 1f
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 26L,
                    scanPeriodMs = 1550L,
                    scanAlpha = 142,
                    wavePeriodMs = 290.0,
                    waveAlpha = 250,
                    nodePeriodMs = 590.0,
                    gatewayAlpha = 252,
                    gatewayPulseMs = 1240L,
                    packetCount = 18,
                    packetPeriodMs = 820.0,
                    packetAlpha = 240,
                    direction = 1f
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 58L,
                    scanPeriodMs = 7200L,
                    scanAlpha = 62,
                    wavePeriodMs = 980.0,
                    waveAlpha = 165,
                    nodePeriodMs = 2200.0,
                    gatewayAlpha = 170,
                    gatewayPulseMs = 4700L,
                    packetCount = 3,
                    packetPeriodMs = 3400.0,
                    packetAlpha = 125,
                    direction = -1f
                )

            else ->
                motionFor(
                    STATE_WAITING
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
        return value * density
    }

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int
    )

    private data class Motion(
        val frameDelayMs: Long,
        val scanPeriodMs: Long,
        val scanAlpha: Int,
        val wavePeriodMs: Double,
        val waveAlpha: Int,
        val nodePeriodMs: Double,
        val gatewayAlpha: Int,
        val gatewayPulseMs: Long,
        val packetCount: Int,
        val packetPeriodMs: Double,
        val packetAlpha: Int,
        val direction: Float
    )

    companion object {

        private const val STATE_WAITING =
            0

        private const val STATE_RECOGNITION =
            1

        private const val STATE_THINKING =
            2

        private const val STATE_EXECUTING =
            3

        private const val STATE_ANSWERING =
            4

        private const val STATE_STOP =
            5
    }
}
