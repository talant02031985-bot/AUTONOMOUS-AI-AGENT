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
import kotlin.math.sin

/**
 * AYANA Core Visualizer v12.0 — AGENT EXECUTION TRACE
 *
 * The visualizer represents AYANA as an execution pipeline, not as an orb,
 * radar, decorative neural network or a static picture:
 *
 * ВХОД -> ПОНИМАНИЕ -> ПЛАН -> ДЕЙСТВИЕ -> ПРОВЕРКА -> ОТВЕТ
 *
 * Factual runtime mapping comes only from AyanaVoiceService.currentStatusState.
 * AYANA currently has no separate VERIFYING public state, therefore the
 * verification node is shown as architecture but is never falsely claimed as
 * the current runtime state.
 *
 * Supporting resources:
 * КОНТЕКСТ / ПАМЯТЬ / ЭКРАН / ИНСТРУМЕНТЫ
 *
 * Motion is state-reactive UI animation; it is not claimed to be measured CPU,
 * token, microphone-amplitude or internal-model telemetry.
 *
 * No ORB, routing, TTS, microphone, Accessibility or command logic is changed.
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
    private val rect = RectF()

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

        val serviceState = AyanaVoiceService.currentStatusState
        val stage = stageFor(serviceState)
        val palette = paletteFor(serviceState)
        val now = SystemClock.uptimeMillis()

        val compact = height < dp(180f)

        drawBackground(canvas, palette)

        if (compact) {
            drawCompact(canvas, now, stage, palette)
        } else {
            drawFull(canvas, now, stage, palette)
        }

        if (attached) {
            postInvalidateDelayed(frameDelayFor(stage))
        }
    }

    /**
     * Bounded layout correction for the existing home hero card only.
     * It prevents long factual titles from collapsing into three lines.
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
            copyParams.weight = 0.72f
            visualParams.weight = 1.64f
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
                    Color.parseColor("#02060C"),
                    Color.parseColor("#07101A"),
                    Color.parseColor("#03070D")
                ),
                floatArrayOf(0f, 0.52f, 1f),
                Shader.TileMode.CLAMP
            )

        fill.alpha = 255
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        stroke.color = Color.parseColor("#183149")
        stroke.alpha = 42
        stroke.strokeWidth = dp(0.45f)

        for (column in 1 until 14) {
            val x = width * column / 14f
            canvas.drawLine(x, dp(8f), x, height - dp(8f), stroke)
        }

        for (row in 1 until 7) {
            val y = height * row / 7f
            canvas.drawLine(dp(8f), y, width - dp(8f), y, stroke)
        }

        rect.set(dp(6f), dp(6f), width - dp(6f), height - dp(6f))
        stroke.color = withAlpha(palette.primary, 70)
        stroke.alpha = 145
        stroke.strokeWidth = dp(0.7f)
        canvas.drawRoundRect(rect, dp(12f), dp(12f), stroke)
    }

    private fun drawFull(
        canvas: Canvas,
        now: Long,
        stage: Stage,
        palette: Palette
    ) {
        val w = width.toFloat()
        val h = height.toFloat()

        val left = w * 0.075f
        val right = w * 0.925f
        val busY = h * 0.47f
        val step = (right - left) / 5f

        drawHeader(canvas, stage, palette, h * 0.11f)

        // Main execution bus.
        stroke.color = Color.parseColor("#2A3B50")
        stroke.alpha = 215
        stroke.strokeWidth = dp(1.1f)
        canvas.drawLine(left, busY, right, busY, stroke)

        val activeIndex = activeStageIndex(stage)

        if (activeIndex > 0 && stage != Stage.STOP) {
            val activeX = left + step * activeIndex
            stroke.shader =
                LinearGradient(
                    left,
                    busY,
                    activeX,
                    busY,
                    intArrayOf(
                        withAlpha(palette.primary, 150),
                        palette.primary,
                        palette.accent
                    ),
                    floatArrayOf(0f, 0.72f, 1f),
                    Shader.TileMode.CLAMP
                )
            stroke.alpha = 230
            stroke.strokeWidth = dp(2.2f)
            canvas.drawLine(left, busY, activeX, busY, stroke)
            stroke.shader = null
        }

        val titles =
            arrayOf(
                "ВХОД",
                "ПОНИМАНИЕ",
                "ПЛАН",
                "ДЕЙСТВИЕ",
                "ПРОВЕРКА",
                "ОТВЕТ"
            )

        val subtitles =
            arrayOf(
                "голос / текст",
                "намерение",
                "цель и шаги",
                "инструмент",
                "evidence",
                "результат"
            )

        repeat(6) { index ->
            val x = left + step * index
            drawStageNode(
                canvas = canvas,
                now = now,
                x = x,
                y = busY,
                index = index,
                visualState = stageVisualState(index, stage),
                title = titles[index],
                subtitle = subtitles[index],
                palette = palette
            )
        }

        drawRuntimeResources(
            canvas = canvas,
            now = now,
            y = h * 0.79f,
            stage = stage,
            palette = palette
        )

        drawActivePackets(
            canvas = canvas,
            now = now,
            left = left,
            step = step,
            busY = busY,
            stage = stage,
            palette = palette
        )

        // Explicit architecture truth: no fake VERIFYING state.
        if (stage == Stage.EXECUTING) {
            val verifyX = left + step * 4f
            text.textSize = dp(7.2f)
            text.color = Color.parseColor("#718198")
            text.alpha = 205
            canvas.drawText(
                "следующий архитектурный этап",
                verifyX,
                busY + dp(49f),
                text
            )
        }

        if (stage == Stage.STOP) {
            drawStopBarrier(
                canvas = canvas,
                x = left + step * 3.5f,
                top = h * 0.20f,
                bottom = h * 0.88f,
                palette = palette
            )
        }
    }

    private fun drawCompact(
        canvas: Canvas,
        now: Long,
        stage: Stage,
        palette: Palette
    ) {
        val w = width.toFloat()
        val h = height.toFloat()

        val left = w * 0.06f
        val right = w * 0.94f
        val y = h * 0.58f
        val step = (right - left) / 5f

        boldText.textSize = dp(8.5f)
        boldText.color = palette.primary
        boldText.alpha = 245
        canvas.drawText(stageTitle(stage), w * 0.50f, h * 0.24f, boldText)

        stroke.color = Color.parseColor("#2A3B50")
        stroke.alpha = 205
        stroke.strokeWidth = dp(0.9f)
        canvas.drawLine(left, y, right, y, stroke)

        repeat(6) { index ->
            val x = left + step * index
            val state = stageVisualState(index, stage)
            val color =
                when(state) {
                    NodeState.ACTIVE -> palette.primary
                    NodeState.COMPLETE -> withAlpha(palette.primary, 180)
                    NodeState.FUTURE -> Color.parseColor("#4D5A6D")
                    NodeState.STOPPED -> palette.primary
                }

            if (state == NodeState.ACTIVE) {
                glow.color = color
                glow.alpha = 78
                glow.strokeWidth = dp(6f)
                canvas.drawCircle(x, y, dp(8f), glow)
            }

            fill.color = color
            fill.alpha = if (state == NodeState.FUTURE) 135 else 245
            canvas.drawCircle(
                x,
                y,
                dp(if (state == NodeState.ACTIVE) 3.2f else 2f),
                fill
            )
        }

        drawActivePackets(canvas, now, left, step, y, stage, palette)
    }

    private fun drawHeader(
        canvas: Canvas,
        stage: Stage,
        palette: Palette,
        y: Float
    ) {
        boldText.textSize = dp(9.8f)
        boldText.color = Color.parseColor("#DCE8F5")
        boldText.alpha = 235
        canvas.drawText("AGENT EXECUTION", width * 0.19f, y, boldText)

        val badge = stageTitle(stage)
        boldText.textSize = dp(8.5f)

        val badgeWidth =
            maxOf(
                dp(82f),
                boldText.measureText(badge) + dp(24f)
            )

        rect.set(
            width - dp(16f) - badgeWidth,
            y - dp(14f),
            width - dp(16f),
            y + dp(7f)
        )

        fill.color = withAlpha(palette.primary, 24)
        fill.alpha = 255
        canvas.drawRoundRect(rect, dp(9f), dp(9f), fill)

        stroke.color = withAlpha(palette.primary, 155)
        stroke.alpha = 220
        stroke.strokeWidth = dp(0.75f)
        canvas.drawRoundRect(rect, dp(9f), dp(9f), stroke)

        boldText.color = palette.primary
        boldText.alpha = 250
        canvas.drawText(badge, rect.centerX(), y, boldText)
    }

    private fun drawStageNode(
        canvas: Canvas,
        now: Long,
        x: Float,
        y: Float,
        index: Int,
        visualState: NodeState,
        title: String,
        subtitle: String,
        palette: Palette
    ) {
        val active = visualState == NodeState.ACTIVE
        val complete = visualState == NodeState.COMPLETE

        val color =
            when {
                visualState == NodeState.STOPPED -> palette.primary
                active -> palette.primary
                complete -> withAlpha(palette.primary, 185)
                else -> Color.parseColor("#4A586B")
            }

        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(now / 420.0 + index * 0.85)
                ).toFloat()

        if (active) {
            fill.shader =
                RadialGradient(
                    x,
                    y,
                    dp(25f),
                    intArrayOf(
                        withAlpha(palette.primary, 110),
                        withAlpha(palette.primary, 26),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(0f, 0.46f, 1f),
                    Shader.TileMode.CLAMP
                )
            fill.alpha = 255
            canvas.drawCircle(x, y, dp(25f), fill)
            fill.shader = null
        }

        rect.set(
            x - dp(19f),
            y - dp(18f),
            x + dp(19f),
            y + dp(18f)
        )

        fill.color =
            if (active) {
                withAlpha(palette.primary, 28)
            } else {
                Color.parseColor("#08111B")
            }
        fill.alpha = 255
        canvas.drawRoundRect(rect, dp(8f), dp(8f), fill)

        stroke.color = color
        stroke.alpha =
            when {
                active -> 245
                complete -> 185
                else -> 120
            }
        stroke.strokeWidth = dp(if (active) 1.3f else 0.8f)
        canvas.drawRoundRect(rect, dp(8f), dp(8f), stroke)

        drawStageIcon(
            canvas = canvas,
            index = index,
            x = x,
            y = y,
            radius = dp(if (active) 8.3f + pulse * 0.6f else 7.5f),
            color = if (active) Color.WHITE else color
        )

        boldText.textSize = dp(8.2f)
        boldText.color =
            if (active) {
                Color.WHITE
            } else {
                Color.parseColor("#B4C0D0")
            }
        boldText.alpha = if (visualState == NodeState.FUTURE) 150 else 235
        canvas.drawText(title, x, y + dp(35f), boldText)

        text.textSize = dp(6.9f)
        text.color = Color.parseColor("#758499")
        text.alpha = if (visualState == NodeState.FUTURE) 130 else 205
        canvas.drawText(subtitle, x, y + dp(46f), text)
    }

    private fun drawStageIcon(
        canvas: Canvas,
        index: Int,
        x: Float,
        y: Float,
        radius: Float,
        color: Int
    ) {
        stroke.color = color
        stroke.alpha = 230
        stroke.strokeWidth = dp(1f)

        when(index) {
            0 -> {
                canvas.drawLine(x - radius, y, x + radius * 0.55f, y, stroke)
                canvas.drawLine(
                    x + radius * 0.55f,
                    y,
                    x + radius * 0.10f,
                    y - radius * 0.45f,
                    stroke
                )
                canvas.drawLine(
                    x + radius * 0.55f,
                    y,
                    x + radius * 0.10f,
                    y + radius * 0.45f,
                    stroke
                )
            }

            1 -> {
                canvas.drawLine(x - radius * 0.75f, y, x, y, stroke)
                canvas.drawLine(
                    x,
                    y,
                    x + radius * 0.65f,
                    y - radius * 0.52f,
                    stroke
                )
                canvas.drawLine(
                    x,
                    y,
                    x + radius * 0.65f,
                    y + radius * 0.52f,
                    stroke
                )
                fill.color = color
                fill.alpha = 235
                canvas.drawCircle(x, y, dp(1.5f), fill)
            }

            2 -> {
                repeat(3) { row ->
                    val yy = y + (row - 1) * radius * 0.48f
                    fill.color = color
                    fill.alpha = 235
                    canvas.drawCircle(x - radius * 0.70f, yy, dp(1.3f), fill)
                    canvas.drawLine(
                        x - radius * 0.40f,
                        yy,
                        x + radius * 0.70f,
                        yy,
                        stroke
                    )
                }
            }

            3 -> {
                canvas.drawLine(
                    x - radius * 0.65f,
                    y + radius * 0.65f,
                    x + radius * 0.65f,
                    y - radius * 0.65f,
                    stroke
                )
                canvas.drawCircle(
                    x - radius * 0.58f,
                    y + radius * 0.58f,
                    radius * 0.20f,
                    stroke
                )
            }

            4 -> {
                canvas.drawLine(
                    x - radius * 0.65f,
                    y,
                    x - radius * 0.15f,
                    y + radius * 0.48f,
                    stroke
                )
                canvas.drawLine(
                    x - radius * 0.15f,
                    y + radius * 0.48f,
                    x + radius * 0.70f,
                    y - radius * 0.55f,
                    stroke
                )
            }

            else -> {
                rect.set(
                    x - radius * 0.72f,
                    y - radius * 0.52f,
                    x + radius * 0.72f,
                    y + radius * 0.45f
                )
                canvas.drawRoundRect(
                    rect,
                    radius * 0.28f,
                    radius * 0.28f,
                    stroke
                )
                path.reset()
                path.moveTo(x - radius * 0.25f, y + radius * 0.45f)
                path.lineTo(x - radius * 0.10f, y + radius * 0.78f)
                path.lineTo(x + radius * 0.12f, y + radius * 0.45f)
                canvas.drawPath(path, stroke)
            }
        }
    }

    private fun drawRuntimeResources(
        canvas: Canvas,
        now: Long,
        y: Float,
        stage: Stage,
        palette: Palette
    ) {
        val resources =
            arrayOf(
                ResourceSpec("КОНТЕКСТ", ResourceType.CONTEXT),
                ResourceSpec("ПАМЯТЬ", ResourceType.MEMORY),
                ResourceSpec("ЭКРАН", ResourceType.SCREEN),
                ResourceSpec("ИНСТРУМЕНТЫ", ResourceType.TOOLS)
            )

        val left = width * 0.18f
        val right = width * 0.82f
        val step = (right - left) / 3f

        repeat(resources.size) { index ->
            val spec = resources[index]
            val x = left + step * index
            val active = resourceActive(spec.type, stage)
            val color =
                if (active) {
                    palette.accent
                } else {
                    Color.parseColor("#506075")
                }

            if (active) {
                glow.color = color
                glow.alpha = 48
                glow.strokeWidth = dp(5f)
                canvas.drawCircle(x, y, dp(10f), glow)
            }

            rect.set(x - dp(8f), y - dp(8f), x + dp(8f), y + dp(8f))
            fill.color = Color.parseColor("#07101A")
            fill.alpha = 255
            canvas.drawRoundRect(rect, dp(5f), dp(5f), fill)

            stroke.color = color
            stroke.alpha = if (active) 230 else 120
            stroke.strokeWidth = dp(if (active) 1f else 0.65f)
            canvas.drawRoundRect(rect, dp(5f), dp(5f), stroke)

            drawResourceIcon(canvas, spec.type, x, y, color)

            text.textSize = dp(7.3f)
            text.color =
                if (active) {
                    Color.parseColor("#DCE8F5")
                } else {
                    Color.parseColor("#758499")
                }
            text.alpha = if (active) 235 else 175
            canvas.drawText(spec.title, x, y + dp(21f), text)

            if (active) {
                val pulse =
                    (
                        0.5 +
                            0.5 *
                                sin(now / 360.0 + index)
                        ).toFloat()
                fill.color = color
                fill.alpha = (120 + pulse * 110f).toInt()
                canvas.drawCircle(x + dp(13f), y - dp(8f), dp(1.7f), fill)
            }
        }
    }

    private fun drawResourceIcon(
        canvas: Canvas,
        type: ResourceType,
        x: Float,
        y: Float,
        color: Int
    ) {
        stroke.color = color
        stroke.alpha = 220
        stroke.strokeWidth = dp(0.85f)

        when(type) {
            ResourceType.CONTEXT -> {
                canvas.drawLine(x - dp(4f), y - dp(3f), x + dp(4f), y - dp(3f), stroke)
                canvas.drawLine(x - dp(4f), y, x + dp(2f), y, stroke)
                canvas.drawLine(x - dp(4f), y + dp(3f), x + dp(4f), y + dp(3f), stroke)
            }

            ResourceType.MEMORY -> {
                repeat(3) { row ->
                    val yy = y + (row - 1) * dp(3f)
                    canvas.drawLine(x - dp(4f), yy, x + dp(4f), yy, stroke)
                }
            }

            ResourceType.SCREEN -> {
                rect.set(x - dp(4.5f), y - dp(3.5f), x + dp(4.5f), y + dp(3.5f))
                canvas.drawRoundRect(rect, dp(1f), dp(1f), stroke)
            }

            ResourceType.TOOLS -> {
                canvas.drawLine(x - dp(4f), y + dp(4f), x + dp(4f), y - dp(4f), stroke)
                canvas.drawCircle(x - dp(3f), y + dp(3f), dp(1.5f), stroke)
            }
        }
    }

    private fun drawActivePackets(
        canvas: Canvas,
        now: Long,
        left: Float,
        step: Float,
        busY: Float,
        stage: Stage,
        palette: Palette
    ) {
        val activeIndex = activeStageIndex(stage)

        if (activeIndex <= 0 || stage == Stage.STOP) return

        val segmentStart = left + step * (activeIndex - 1)
        val segmentEnd = left + step * activeIndex

        repeat(3) { packet ->
            val t =
                (
                    (
                        now / 850.0 +
                            packet / 3.0
                        ) %
                        1.0
                    ).toFloat()

            val x =
                segmentStart +
                    (segmentEnd - segmentStart) *
                    t

            fill.color = if (packet == 1) Color.WHITE else palette.primary
            fill.alpha = 230
            canvas.drawCircle(
                x,
                busY,
                dp(if (packet == 1) 2f else 1.3f),
                fill
            )
        }
    }

    private fun drawStopBarrier(
        canvas: Canvas,
        x: Float,
        top: Float,
        bottom: Float,
        palette: Palette
    ) {
        fill.color = withAlpha(palette.primary, 24)
        fill.alpha = 255
        canvas.drawRect(x - dp(4f), top, x + dp(4f), bottom, fill)

        stroke.color = Color.WHITE
        stroke.alpha = 235
        stroke.strokeWidth = dp(1.8f)

        val cy = (top + bottom) * 0.50f
        canvas.drawLine(x - dp(8f), cy - dp(8f), x + dp(8f), cy + dp(8f), stroke)
        canvas.drawLine(x + dp(8f), cy - dp(8f), x - dp(8f), cy + dp(8f), stroke)
    }

    private fun stageVisualState(
        index: Int,
        stage: Stage
    ): NodeState {
        if (stage == Stage.STOP) return NodeState.STOPPED

        val active = activeStageIndex(stage)
        return when {
            index == active -> NodeState.ACTIVE
            index < active -> NodeState.COMPLETE
            else -> NodeState.FUTURE
        }
    }

    private fun activeStageIndex(
        stage: Stage
    ): Int {
        return when(stage) {
            Stage.INPUT -> 0
            Stage.UNDERSTAND -> 1
            Stage.PLAN -> 2
            Stage.EXECUTING -> 3
            Stage.RESPONSE -> 5
            Stage.STOP -> 3
        }
    }

    private fun resourceActive(
        type: ResourceType,
        stage: Stage
    ): Boolean {
        return when(stage) {
            Stage.INPUT ->
                type == ResourceType.CONTEXT

            Stage.UNDERSTAND ->
                type == ResourceType.CONTEXT

            Stage.PLAN ->
                type == ResourceType.CONTEXT ||
                    type == ResourceType.MEMORY

            Stage.EXECUTING ->
                type == ResourceType.SCREEN ||
                    type == ResourceType.TOOLS

            Stage.RESPONSE ->
                type == ResourceType.CONTEXT

            Stage.STOP ->
                false
        }
    }

    private fun stageFor(
        serviceState: String
    ): Stage {
        return when(serviceState) {
            AyanaVoiceService.STATE_LISTENING ->
                Stage.INPUT

            AyanaVoiceService.STATE_COMMAND ->
                Stage.UNDERSTAND

            AyanaVoiceService.STATE_THINKING ->
                Stage.PLAN

            AyanaVoiceService.STATE_EXECUTING ->
                Stage.EXECUTING

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT,
            AyanaVoiceService.STATE_SUCCESS ->
                Stage.RESPONSE

            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR ->
                Stage.STOP

            else ->
                Stage.INPUT
        }
    }

    private fun stageTitle(
        stage: Stage
    ): String {
        return when(stage) {
            Stage.INPUT -> "ОЖИДАНИЕ ВХОДА"
            Stage.UNDERSTAND -> "ПОНИМАНИЕ КОМАНДЫ"
            Stage.PLAN -> "ФОРМИРОВАНИЕ ПЛАНА"
            Stage.EXECUTING -> "ВЫПОЛНЕНИЕ"
            Stage.RESPONSE -> "ФОРМИРОВАНИЕ ОТВЕТА"
            Stage.STOP -> "ОСТАНОВЛЕНО"
        }
    }

    private fun paletteFor(
        serviceState: String
    ): Palette {
        return when(serviceState) {
            AyanaVoiceService.STATE_LISTENING ->
                Palette(
                    Color.parseColor("#24DDE8"),
                    Color.parseColor("#32A7FF")
                )

            AyanaVoiceService.STATE_COMMAND ->
                Palette(
                    Color.parseColor("#2389FF"),
                    Color.parseColor("#56C8FF")
                )

            AyanaVoiceService.STATE_THINKING ->
                Palette(
                    Color.parseColor("#7254FF"),
                    Color.parseColor("#A765FF")
                )

            AyanaVoiceService.STATE_EXECUTING ->
                Palette(
                    Color.parseColor("#31E27A"),
                    Color.parseColor("#18CFC1")
                )

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT,
            AyanaVoiceService.STATE_SUCCESS ->
                Palette(
                    Color.parseColor("#E53BC8"),
                    Color.parseColor("#9B54FF")
                )

            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR ->
                Palette(
                    Color.parseColor("#FF4933"),
                    Color.parseColor("#FF7A2B")
                )

            else ->
                Palette(
                    Color.parseColor("#24DDE8"),
                    Color.parseColor("#32A7FF")
                )
        }
    }

    private fun frameDelayFor(
        stage: Stage
    ): Long {
        return when(stage) {
            Stage.INPUT -> 50L
            Stage.UNDERSTAND -> 32L
            Stage.PLAN -> 34L
            Stage.EXECUTING -> 30L
            Stage.RESPONSE -> 32L
            Stage.STOP -> 90L
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

    private enum class Stage {
        INPUT,
        UNDERSTAND,
        PLAN,
        EXECUTING,
        RESPONSE,
        STOP
    }

    private enum class NodeState {
        ACTIVE,
        COMPLETE,
        FUTURE,
        STOPPED
    }

    private enum class ResourceType {
        CONTEXT,
        MEMORY,
        SCREEN,
        TOOLS
    }

    private data class ResourceSpec(
        val title: String,
        val type: ResourceType
    )

    private data class Palette(
        val primary: Int,
        val accent: Int
    )
}
