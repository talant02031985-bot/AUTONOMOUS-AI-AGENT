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
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * AYANA Core Visualizer v13.0.1 — AGENT CAUSAL FIELD
 *
 * Visual direction reset after rejecting panel / radio / dashboard metaphors.
 *
 * This visualizer represents an AI agent as a live causal field:
 * one goal thread enters, branches while reasoning, touches memory/context,
 * selects an action path, passes an evidence gate and converges into a result.
 *
 * It is intentionally NOT:
 * - a radar;
 * - a glowing orb;
 * - a row of buttons;
 * - a Soviet-style control panel;
 * - a static PNG;
 * - a fake CPU/model load indicator.
 *
 * Runtime state comes only from AyanaVoiceService.currentStatusState.
 * Animation is state-reactive UI motion, not claimed as literal hidden model
 * telemetry or measured microphone amplitude.
 *
 * Visual-only component. No ORB, routing, TTS, microphone, Accessibility or
 * Android execution behavior is modified.
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

    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        }

    private val boldText =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

    private val path = Path()
    private var attached = false
    private var hostNormalized = false

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
        post { normalizeHostLayoutOnce() }
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
        val now = SystemClock.uptimeMillis()

        drawBackground(canvas, palette)
        drawCausalField(canvas, now, state, palette)

        if (attached) {
            postInvalidateDelayed(frameDelayFor(state))
        }
    }

    /**
     * Keeps the already proven hero-card proportion correction local to this
     * visual component. No other MainActivity behavior is changed.
     */
    private fun normalizeHostLayoutOnce() {
        if (hostNormalized) return
        hostNormalized = true

        val card = parent as? LinearLayout ?: return
        if (card.orientation != LinearLayout.HORIZONTAL || card.childCount < 2) return

        val copy = card.getChildAt(0) as? LinearLayout ?: return
        val visualParams = layoutParams as? LinearLayout.LayoutParams
        val copyParams = copy.layoutParams as? LinearLayout.LayoutParams

        if (visualParams != null && copyParams != null) {
            copyParams.weight = 0.70f
            visualParams.weight = 1.66f
            copy.layoutParams = copyParams
            layoutParams = visualParams
        }

        (copy.getChildAt(1) as? TextView)?.apply {
            textSize = 30f
            maxLines = 2
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setLineSpacing(0f, 0.94f)
        }

        card.requestLayout()
    }

    private fun drawBackground(
        canvas: Canvas,
        palette: Palette
    ) {
        fill.shader =
            LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.parseColor("#01040A"),
                    Color.parseColor("#06101B"),
                    Color.parseColor("#02050B")
                ),
                floatArrayOf(0f, 0.50f, 1f),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        // Sparse depth grid. It is structural, not a control-panel frame.
        stroke.color = Color.parseColor("#13263A")
        stroke.alpha = 34
        stroke.strokeWidth = dp(0.45f)

        for (column in 1 until 13) {
            val x = width * column / 13f
            canvas.drawLine(
                x,
                height * 0.07f,
                x,
                height * 0.93f,
                stroke
            )
        }

        for (row in 1 until 7) {
            val y = height * row / 7f
            canvas.drawLine(
                width * 0.03f,
                y,
                width * 0.97f,
                y,
                stroke
            )
        }

        // Very subtle active-state bloom behind the working field.
        val cx = width * 0.53f
        val cy = height * 0.50f
        val radius = minOf(width, height) * 0.42f

        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius,
                intArrayOf(
                    withAlpha(palette.primary, 40),
                    withAlpha(palette.secondary, 18),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.48f, 1f),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawCircle(cx, cy, radius, fill)
        fill.shader = null
    }

    private fun drawCausalField(
        canvas: Canvas,
        now: Long,
        state: AgentState,
        palette: Palette
    ) {
        val w = width.toFloat()
        val h = height.toFloat()

        val left = w * 0.035f
        val right = w * 0.965f
        val centerY = h * 0.50f

        val memoryX = w * 0.31f
        val memoryY = h * 0.20f

        val goalX = w * 0.50f
        val goalY = h * 0.44f

        val toolX = w * 0.67f
        val toolY = h * 0.78f

        val evidenceX = w * 0.82f
        val evidenceY = h * 0.50f

        drawInputWave(
            canvas = canvas,
            now = now,
            left = left,
            right = w * 0.24f,
            centerY = centerY,
            state = state,
            palette = palette
        )

        // Quiet support fields. No button boxes.
        drawMemoryField(
            canvas = canvas,
            now = now,
            x = memoryX,
            y = memoryY,
            state = state,
            palette = palette
        )

        drawToolField(
            canvas = canvas,
            now = now,
            x = toolX,
            y = toolY,
            state = state,
            palette = palette
        )

        drawEvidenceGate(
            canvas = canvas,
            now = now,
            x = evidenceX,
            centerY = evidenceY,
            state = state,
            palette = palette
        )

        drawGoalThread(
            canvas = canvas,
            now = now,
            left = w * 0.18f,
            right = w * 0.90f,
            centerY = centerY,
            goalY = goalY,
            memoryY = memoryY,
            toolY = toolY,
            state = state,
            palette = palette
        )

        drawGoalSeed(
            canvas = canvas,
            now = now,
            x = goalX,
            y = goalY,
            state = state,
            palette = palette
        )

        drawOutputWave(
            canvas = canvas,
            now = now,
            left = evidenceX + w * 0.035f,
            right = right,
            centerY = centerY,
            state = state,
            palette = palette
        )

        drawParticles(
            canvas = canvas,
            now = now,
            state = state,
            palette = palette
        )

        drawMicroLegend(
            canvas = canvas,
            state = state,
            palette = palette
        )

        if (state == AgentState.STOP) {
            drawStopCut(
                canvas = canvas,
                x = evidenceX - w * 0.025f,
                palette = palette
            )
        }
    }

    /**
     * Dominant visual: a causal thread with several nearby hypothesis strands.
     * It changes topology by state instead of lighting fixed dashboard buttons.
     */
    private fun drawGoalThread(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        centerY: Float,
        goalY: Float,
        memoryY: Float,
        toolY: Float,
        state: AgentState,
        palette: Palette
    ) {
        val strandCount =
            when(state) {
                AgentState.WAITING -> 3
                AgentState.RECOGNITION -> 5
                AgentState.THINKING -> 8
                AgentState.EXECUTING -> 6
                AgentState.ANSWERING -> 5
                AgentState.STOP -> 2
            }

        for (strand in 0 until strandCount) {
            path.reset()

            val samples = 88
            val offsetIndex = strand - (strandCount - 1) * 0.5f

            for (i in 0..samples) {
                val t = i / samples.toFloat()
                val x = left + (right - left) * t

                val stateAmplitude =
                    when(state) {
                        AgentState.WAITING -> height * 0.018f
                        AgentState.RECOGNITION -> height * 0.034f
                        AgentState.THINKING -> height * 0.082f
                        AgentState.EXECUTING -> height * 0.045f
                        AgentState.ANSWERING -> height * 0.038f
                        AgentState.STOP -> height * 0.012f
                    }

                val branchEnvelope =
                    (
                        sin(PI * t)
                            .toFloat()
                            .coerceAtLeast(0f)
                    )

                val thinkingBurst =
                    if (state == AgentState.THINKING) {
                        sin(t * PI * 3.0 + strand * 0.55).toFloat()
                    } else {
                        sin(t * PI * 1.4 + strand * 0.40).toFloat()
                    }

                var y =
                    centerY +
                        offsetIndex *
                            height *
                            0.018f +
                        thinkingBurst *
                            stateAmplitude *
                            branchEnvelope

                // The selected causal path bends toward real support areas.
                if (state == AgentState.THINKING) {
                    val memoryPull =
                        triangularInfluence(
                            t,
                            0.30f,
                            0.20f
                        )

                    val goalPull =
                        triangularInfluence(
                            t,
                            0.48f,
                            0.18f
                        )

                    y +=
                        (
                            memoryY -
                                centerY
                        ) *
                        memoryPull *
                        (
                            if (strand % 3 == 0) 0.32f else 0.08f
                        )

                    y +=
                        (
                            goalY -
                                centerY
                        ) *
                        goalPull *
                        0.22f
                }

                if (state == AgentState.EXECUTING) {
                    val toolPull =
                        triangularInfluence(
                            t,
                            0.68f,
                            0.23f
                        )

                    y +=
                        (
                            toolY -
                                centerY
                        ) *
                        toolPull *
                        (
                            if (strand == strandCount / 2) 0.58f else 0.16f
                        )
                }

                if (state == AgentState.ANSWERING) {
                    val evidencePull =
                        triangularInfluence(
                            t,
                            0.82f,
                            0.20f
                        )

                    y +=
                        (
                            centerY -
                                y
                        ) *
                        evidencePull *
                        0.76f
                }

                if (i == 0) {
                    path.moveTo(x, y)
                } else {
                    path.lineTo(x, y)
                }
            }

            val selected =
                when(state) {
                    AgentState.WAITING ->
                        strand == strandCount / 2

                    AgentState.RECOGNITION ->
                        strand == strandCount / 2

                    AgentState.THINKING ->
                        strand == strandCount / 2 ||
                            strand == strandCount / 2 - 1

                    AgentState.EXECUTING ->
                        strand == strandCount / 2

                    AgentState.ANSWERING ->
                        strand == strandCount / 2

                    AgentState.STOP ->
                        false
                }

            val strandColor =
                if (selected) {
                    Color.WHITE
                } else if (strand % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            if (selected) {
                glow.color = palette.primary
                glow.alpha = if (state == AgentState.STOP) 18 else 70
                glow.strokeWidth = dp(7f)
                canvas.drawPath(path, glow)
            }

            stroke.color = strandColor
            stroke.alpha =
                when {
                    state == AgentState.STOP -> 55
                    selected -> 235
                    state == AgentState.THINKING -> 125
                    else -> 82
                }

            stroke.strokeWidth =
                dp(
                    if (selected) {
                        1.25f
                    } else {
                        0.62f
                    }
                )

            canvas.drawPath(path, stroke)
        }

        drawCrossLinks(
            canvas = canvas,
            now = now,
            state = state,
            palette = palette
        )

        drawTravelPackets(
            canvas = canvas,
            now = now,
            left = left,
            right = right,
            centerY = centerY,
            state = state,
            palette = palette
        )
    }

    private fun drawCrossLinks(
        canvas: Canvas,
        now: Long,
        state: AgentState,
        palette: Palette
    ) {
        if (
            state != AgentState.THINKING &&
            state != AgentState.EXECUTING
        ) {
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()

        val count =
            if (state == AgentState.THINKING) 12 else 6

        for (i in 0 until count) {
            val x1 =
                w *
                    (
                        0.28f +
                            (i % 6) *
                                0.075f
                    )

            val y1 =
                h *
                    (
                        0.28f +
                            (i % 3) *
                                0.18f
                    )

            val x2 =
                x1 +
                    w *
                        (
                            0.10f +
                                (i % 2) *
                                    0.05f
                        )

            val y2 =
                h *
                    (
                        0.34f +
                            ((i + 1) % 3) *
                                0.16f
                    ) +
                    sin(now / 700.0 + i)
                        .toFloat() *
                        dp(4f)

            stroke.color =
                if (i % 2 == 0) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                if (state == AgentState.THINKING) 70 else 44

            stroke.strokeWidth = dp(0.55f)
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }
    }

    private fun drawTravelPackets(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        centerY: Float,
        state: AgentState,
        palette: Palette
    ) {
        val count =
            when(state) {
                AgentState.WAITING -> 4
                AgentState.RECOGNITION -> 8
                AgentState.THINKING -> 14
                AgentState.EXECUTING -> 10
                AgentState.ANSWERING -> 9
                AgentState.STOP -> 2
            }

        for (i in 0 until count) {
            val t =
                (
                    (
                        now /
                            packetPeriodFor(state) +
                            i /
                                count.toDouble()
                        ) %
                        1.0
                    ).toFloat()

            val x = left + (right - left) * t

            val y =
                centerY +
                    sin(
                        t * PI * 4.0 +
                            i * 0.71 +
                            now / 1100.0
                    )
                        .toFloat() *
                        height *
                        if (state == AgentState.THINKING) 0.075f else 0.035f

            fill.color =
                when {
                    i % 5 == 0 -> Color.WHITE
                    i % 2 == 0 -> palette.primary
                    else -> palette.secondary
                }

            fill.alpha =
                if (state == AgentState.STOP) 80 else 215

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 5 == 0) {
                        2.1f
                    } else {
                        1.15f
                    }
                ),
                fill
            )
        }
    }

    private fun drawGoalSeed(
        canvas: Canvas,
        now: Long,
        x: Float,
        y: Float,
        state: AgentState,
        palette: Palette
    ) {
        val active =
            state == AgentState.THINKING ||
                state == AgentState.EXECUTING

        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(now / 520.0)
                ).toFloat()

        if (active) {
            fill.shader =
                RadialGradient(
                    x,
                    y,
                    dp(38f),
                    intArrayOf(
                        withAlpha(Color.WHITE, 120),
                        withAlpha(palette.primary, 105),
                        withAlpha(palette.secondary, 42),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.25f, 0.58f, 1f),
                    Shader.TileMode.CLAMP
                )

            fill.alpha = 255
            canvas.drawCircle(x, y, dp(38f), fill)
            fill.shader = null
        }

        // Open causal diamond: not a button and not an orb.
        path.reset()
        path.moveTo(x, y - dp(13f))
        path.lineTo(x + dp(20f), y)
        path.lineTo(x, y + dp(13f))
        path.lineTo(x - dp(20f), y)
        path.close()

        stroke.color =
            if (active) {
                palette.primary
            } else {
                Color.parseColor("#4E6177")
            }

        stroke.alpha = if (active) 235 else 130
        stroke.strokeWidth = dp(if (active) 1.4f else 0.8f)
        canvas.drawPath(path, stroke)

        if (active) {
            glow.color = palette.primary
            glow.alpha = 70
            glow.strokeWidth = dp(6f)
            canvas.drawPath(path, glow)
        }

        fill.color =
            if (active) {
                Color.WHITE
            } else {
                palette.primary
            }

        fill.alpha =
            if (state == AgentState.STOP) {
                80
            } else {
                220
            }

        canvas.drawCircle(
            x,
            y,
            dp(
                if (active) {
                    2.5f + pulse * 0.6f
                } else {
                    1.7f
                }
            ),
            fill
        )

        text.textSize = dp(7.5f)
        text.color = Color.parseColor("#8190A4")
        text.alpha = 205
        canvas.drawText("ЦЕЛЬ", x, y + dp(29f), text)
    }

    private fun drawMemoryField(
        canvas: Canvas,
        now: Long,
        x: Float,
        y: Float,
        state: AgentState,
        palette: Palette
    ) {
        val active = state == AgentState.THINKING

        repeat(4) { row ->
            val yy = y + (row - 1.5f) * dp(5f)
            val width =
                dp(
                    20f -
                        row *
                            2f
                )

            stroke.color =
                if (active) {
                    palette.secondary
                } else {
                    Color.parseColor("#405169")
                }

            stroke.alpha = if (active) 190 else 85
            stroke.strokeWidth = dp(if (active) 1f else 0.6f)

            canvas.drawLine(
                x - width,
                yy,
                x + width,
                yy,
                stroke
            )
        }

        if (active) {
            val t =
                (
                    (
                        now / 1050.0
                    ) %
                        1.0
                    ).toFloat()

            fill.color = Color.WHITE
            fill.alpha = 230
            canvas.drawCircle(
                x - dp(18f) + dp(36f) * t,
                y - dp(8f),
                dp(1.7f),
                fill
            )
        }

        text.textSize = dp(7.2f)
        text.color = Color.parseColor("#718198")
        text.alpha = 180
        canvas.drawText("ПАМЯТЬ / КОНТЕКСТ", x, y - dp(20f), text)
    }

    private fun drawToolField(
        canvas: Canvas,
        now: Long,
        x: Float,
        y: Float,
        state: AgentState,
        palette: Palette
    ) {
        val active = state == AgentState.EXECUTING

        val positions =
            arrayOf(
                Pair(-18f, -7f),
                Pair(0f, 0f),
                Pair(18f, 7f)
            )

        positions.forEachIndexed { index, p ->
            val px = x + dp(p.first)
            val py = y + dp(p.second)

            fill.color =
                if (active && index == 1) {
                    Color.WHITE
                } else if (active) {
                    palette.primary
                } else {
                    Color.parseColor("#4A5A70")
                }

            fill.alpha = if (active) 230 else 110
            canvas.drawCircle(
                px,
                py,
                dp(if (active && index == 1) 2.5f else 1.7f),
                fill
            )

            if (index > 0) {
                val prev = positions[index - 1]
                stroke.color =
                    if (active) {
                        palette.primary
                    } else {
                        Color.parseColor("#405169")
                    }
                stroke.alpha = if (active) 155 else 70
                stroke.strokeWidth = dp(0.7f)
                canvas.drawLine(
                    x + dp(prev.first),
                    y + dp(prev.second),
                    px,
                    py,
                    stroke
                )
            }
        }

        if (active) {
            val pulse =
                abs(
                    sin(
                        now / 280.0
                    )
                        .toFloat()
                )

            glow.color = palette.primary
            glow.alpha = (30 + pulse * 55f).toInt()
            glow.strokeWidth = dp(5f)
            canvas.drawCircle(x, y, dp(10f), glow)
        }

        text.textSize = dp(7.2f)
        text.color = Color.parseColor("#718198")
        text.alpha = 180
        canvas.drawText("ИНСТРУМЕНТЫ", x, y + dp(24f), text)
    }

    private fun drawEvidenceGate(
        canvas: Canvas,
        now: Long,
        x: Float,
        centerY: Float,
        state: AgentState,
        palette: Palette
    ) {
        val active =
            state == AgentState.EXECUTING ||
                state == AgentState.ANSWERING

        val half = height * 0.18f

        stroke.color =
            if (active) {
                palette.secondary
            } else {
                Color.parseColor("#405169")
            }

        stroke.alpha = if (active) 180 else 72
        stroke.strokeWidth = dp(if (active) 1f else 0.65f)

        canvas.drawLine(
            x - dp(5f),
            centerY - half,
            x - dp(5f),
            centerY + half,
            stroke
        )

        canvas.drawLine(
            x + dp(5f),
            centerY - half,
            x + dp(5f),
            centerY + half,
            stroke
        )

        if (active) {
            val t =
                (
                    (
                        now / 980.0
                    ) %
                        1.0
                    ).toFloat()

            val py =
                centerY -
                    half +
                    half *
                        2f *
                        t

            fill.shader =
                RadialGradient(
                    x,
                    py,
                    dp(16f),
                    intArrayOf(
                        Color.WHITE,
                        withAlpha(palette.primary, 125),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.32f, 1f),
                    Shader.TileMode.CLAMP
                )
            fill.alpha = 235
            canvas.drawCircle(x, py, dp(16f), fill)
            fill.shader = null
        }

        text.textSize = dp(7.2f)
        text.color = Color.parseColor("#718198")
        text.alpha = 180
        canvas.drawText(
            "EVIDENCE",
            x,
            centerY - half - dp(10f),
            text
        )
    }

    private fun drawInputWave(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        centerY: Float,
        state: AgentState,
        palette: Palette
    ) {
        drawEdgeWave(
            canvas = canvas,
            now = now,
            left = left,
            right = right,
            centerY = centerY,
            state = state,
            palette = palette,
            inputSide = true
        )
    }

    private fun drawOutputWave(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        centerY: Float,
        state: AgentState,
        palette: Palette
    ) {
        drawEdgeWave(
            canvas = canvas,
            now = now,
            left = left,
            right = right,
            centerY = centerY,
            state = state,
            palette = palette,
            inputSide = false
        )
    }

    private fun drawEdgeWave(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        centerY: Float,
        state: AgentState,
        palette: Palette,
        inputSide: Boolean
    ) {
        if (right <= left) return

        path.reset()

        val samples = 52
        val phase = now / 310.0

        val active =
            if (inputSide) {
                state == AgentState.RECOGNITION
            } else {
                state == AgentState.ANSWERING
            }

        val amplitude =
            height *
                if (active) {
                    0.11f
                } else {
                    0.045f
                }

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + (right - left) * t

            val envelope =
                sin(PI * t)
                    .toFloat()
                    .coerceAtLeast(0f)

            val y =
                centerY +
                    (
                        sin(
                            t *
                                PI *
                                10.0 +
                                phase
                        )
                            .toFloat() +
                            sin(
                                t *
                                    PI *
                                    27.0 -
                                    phase *
                                        0.72
                            )
                                .toFloat() *
                                0.24f
                        ) *
                        amplitude *
                        envelope

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        val c1 =
            if (inputSide) {
                palette.primary
            } else {
                palette.secondary
            }

        val c2 =
            if (inputSide) {
                palette.secondary
            } else {
                palette.primary
            }

        val gradient =
            LinearGradient(
                left,
                centerY,
                right,
                centerY,
                intArrayOf(
                    Color.TRANSPARENT,
                    c1,
                    Color.WHITE,
                    c2,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 0.50f, 0.78f, 1f),
                Shader.TileMode.CLAMP
            )

        glow.shader = gradient
        glow.alpha = if (active) 82 else 34
        glow.strokeWidth = dp(if (active) 7f else 4f)
        canvas.drawPath(path, glow)

        stroke.shader = gradient
        stroke.alpha = if (active) 240 else 135
        stroke.strokeWidth = dp(if (active) 1.4f else 0.8f)
        canvas.drawPath(path, stroke)

        glow.shader = null
        stroke.shader = null
    }

    private fun drawParticles(
        canvas: Canvas,
        now: Long,
        state: AgentState,
        palette: Palette
    ) {
        val count =
            when(state) {
                AgentState.WAITING -> 12
                AgentState.RECOGNITION -> 18
                AgentState.THINKING -> 28
                AgentState.EXECUTING -> 22
                AgentState.ANSWERING -> 20
                AgentState.STOP -> 6
            }

        for (i in 0 until count) {
            val phase =
                now /
                    (
                        1300.0 +
                            i *
                                37.0
                        ) +
                    i *
                        0.73

            val x =
                width *
                    (
                        0.12f +
                            (
                                (
                                    i *
                                        0.071f
                                    ) %
                                    0.76f
                                )
                        )

            val y =
                height *
                    0.50f +
                    sin(phase)
                        .toFloat() *
                        height *
                        (
                            0.16f +
                                (
                                    i %
                                        4
                                    ) *
                                    0.025f
                            )

            fill.color =
                when(i % 3) {
                    0 -> palette.primary
                    1 -> palette.secondary
                    else -> Color.WHITE
                }

            fill.alpha =
                if (state == AgentState.STOP) {
                    50
                } else {
                    80 + (i % 4) * 28
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (i % 7 == 0) {
                        1.7f
                    } else {
                        0.9f
                    }
                ),
                fill
            )
        }
    }

    private fun drawMicroLegend(
        canvas: Canvas,
        state: AgentState,
        palette: Palette
    ) {
        boldText.textSize = dp(7.4f)
        boldText.color = palette.primary
        boldText.alpha = 215

        canvas.drawText(
            stateCaption(state),
            width * 0.84f,
            height * 0.10f,
            boldText
        )

        text.textSize = dp(6.8f)
        text.color = Color.parseColor("#6E7D92")
        text.alpha = 160

        canvas.drawText(
            "CAUSAL FIELD",
            width * 0.16f,
            height * 0.10f,
            text
        )
    }

    private fun drawStopCut(
        canvas: Canvas,
        x: Float,
        palette: Palette
    ) {
        fill.color = withAlpha(palette.primary, 24)
        fill.alpha = 255

        canvas.drawRect(
            x - dp(5f),
            height * 0.18f,
            x + dp(5f),
            height * 0.82f,
            fill
        )

        stroke.color = Color.WHITE
        stroke.alpha = 235
        stroke.strokeWidth = dp(1.8f)

        val cy = height * 0.50f

        canvas.drawLine(
            x - dp(9f),
            cy - dp(9f),
            x + dp(9f),
            cy + dp(9f),
            stroke
        )

        canvas.drawLine(
            x + dp(9f),
            cy - dp(9f),
            x - dp(9f),
            cy + dp(9f),
            stroke
        )
    }

    private fun triangularInfluence(
        t: Float,
        center: Float,
        halfWidth: Float
    ): Float {
        val d = abs(t - center)
        return (1f - d / halfWidth).coerceIn(0f, 1f)
    }

    private fun stateFor(
        serviceState: String
    ): AgentState {
        return when(serviceState) {
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

    private fun stateCaption(
        state: AgentState
    ): String {
        return when(state) {
            AgentState.WAITING -> "ОЖИДАНИЕ"
            AgentState.RECOGNITION -> "РАСПОЗНАВАНИЕ"
            AgentState.THINKING -> "РЕШЕНИЕ"
            AgentState.EXECUTING -> "ДЕЙСТВИЕ"
            AgentState.ANSWERING -> "ОТВЕТ"
            AgentState.STOP -> "СТОП"
        }
    }

    private fun paletteFor(
        state: AgentState
    ): Palette {
        return when(state) {
            AgentState.WAITING ->
                Palette(
                    Color.parseColor("#22DDE8"),
                    Color.parseColor("#2D8CFF")
                )

            AgentState.RECOGNITION ->
                Palette(
                    Color.parseColor("#248BFF"),
                    Color.parseColor("#36D5FF")
                )

            AgentState.THINKING ->
                Palette(
                    Color.parseColor("#7354FF"),
                    Color.parseColor("#B45CFF")
                )

            AgentState.EXECUTING ->
                Palette(
                    Color.parseColor("#30E37A"),
                    Color.parseColor("#18CFC1")
                )

            AgentState.ANSWERING ->
                Palette(
                    Color.parseColor("#E83BCB"),
                    Color.parseColor("#9656FF")
                )

            AgentState.STOP ->
                Palette(
                    Color.parseColor("#FF4933"),
                    Color.parseColor("#FF7A2B")
                )
        }
    }

    private fun packetPeriodFor(
        state: AgentState
    ): Double {
        return when(state) {
            AgentState.WAITING -> 3000.0
            AgentState.RECOGNITION -> 1200.0
            AgentState.THINKING -> 900.0
            AgentState.EXECUTING -> 760.0
            AgentState.ANSWERING -> 820.0
            AgentState.STOP -> 4200.0
        }
    }

    private fun frameDelayFor(
        state: AgentState
    ): Long {
        return when(state) {
            AgentState.WAITING -> 48L
            AgentState.RECOGNITION -> 30L
            AgentState.THINKING -> 28L
            AgentState.EXECUTING -> 27L
            AgentState.ANSWERING -> 28L
            AgentState.STOP -> 90L
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

    private fun dp(value: Float): Float =
        value * density

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
        val secondary: Int
    )
}
