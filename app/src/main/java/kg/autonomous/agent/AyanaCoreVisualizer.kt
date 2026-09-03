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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v11.0 — AGENT ARCHITECTURE.
 *
 * Device-targeted implementation based on the approved AYANA concept:
 * one central hexagonal Agent Core + five functional satellite modules.
 *
 * Functional topology:
 * - Память и контекст
 * - Планирование целей
 * - Поток данных
 * - Инструменты и действия
 * - Проверка и верификация
 *
 * Visual behavior:
 * - live state-reactive data flow;
 * - central hex core pulses instead of behaving like a decorative orb;
 * - moving packets travel across real visual routes;
 * - modules activate differently for listening / recognition / thinking /
 *   execution / answering / stop;
 * - thin six-state rail remains at the bottom;
 * - no PNG/static artwork dependency;
 * - no touch handlers, Accessibility actions, ORB logic, TTS, routing or
 *   command execution changes.
 *
 * Truth note:
 * visual motion is synthetic/state-reactive UI animation. It is not presented
 * as measured microphone amplitude or literal model internals.
 *
 * Integration contract:
 *   AyanaCoreVisualizer(Context)
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

    private val label =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.NORMAL
                )
        }

    private val strongLabel =
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

    private val wavePath =
        Path()

    private val ring =
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

        val railTop =
            h - railHeight

        val contentTop =
            dp(8f)

        val contentBottom =
            if (compact) {
                h - dp(8f)
            } else {
                railTop - dp(5f)
            }

        drawBackground(
            canvas = canvas,
            now = now,
            top = contentTop,
            bottom = contentBottom,
            palette = palette,
            motion = motion
        )

        val cx =
            w * 0.55f

        val cy =
            contentTop +
                (
                    contentBottom -
                        contentTop
                    ) *
                0.53f

        val coreRadius =
            min(
                w * 0.15f,
                (
                    contentBottom -
                        contentTop
                    ) *
                    0.28f
            )
                .coerceAtLeast(
                    dp(34f)
                )

        val memory =
            Node(
                x = w * 0.37f,
                y =
                    contentTop +
                        (
                            contentBottom -
                                contentTop
                            ) *
                        0.22f,
                type = NodeType.MEMORY,
                line1 = "Память",
                line2 = "и контекст"
            )

        val planning =
            Node(
                x = w * 0.75f,
                y =
                    contentTop +
                        (
                            contentBottom -
                                contentTop
                            ) *
                        0.22f,
                type = NodeType.PLANNING,
                line1 = "Планирование",
                line2 = "целей"
            )

        val input =
            Node(
                x = w * 0.31f,
                y = cy,
                type = NodeType.DATA,
                line1 = "Поток",
                line2 = "данных"
            )

        val tools =
            Node(
                x = w * 0.39f,
                y =
                    contentTop +
                        (
                            contentBottom -
                                contentTop
                            ) *
                        0.80f,
                type = NodeType.TOOLS,
                line1 = "Инструменты",
                line2 = "и действия"
            )

        val verify =
            Node(
                x = w * 0.74f,
                y =
                    contentTop +
                        (
                            contentBottom -
                                contentTop
                            ) *
                        0.80f,
                type = NodeType.VERIFY,
                line1 = "Проверка",
                line2 = "и верификация"
            )

        val nodes =
            listOf(
                memory,
                planning,
                input,
                tools,
                verify
            )

        drawSignalField(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreRadius = coreRadius,
            palette = palette,
            motion = motion,
            state = state
        )

        nodes.forEach { node ->
            drawConnection(
                canvas = canvas,
                now = now,
                fromX = node.x,
                fromY = node.y,
                toX = cx,
                toY = cy,
                color =
                    nodeColor(
                        node.type,
                        state,
                        palette
                    ),
                active =
                    isNodeActive(
                        node.type,
                        state
                    ),
                phaseOffset =
                    node.type.ordinal *
                        0.71
            )
        }

        drawCentralCore(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = coreRadius,
            palette = palette,
            state = state
        )

        nodes.forEach { node ->
            drawModuleNode(
                canvas = canvas,
                now = now,
                node = node,
                radius =
                    min(
                        coreRadius * 0.39f,
                        dp(27f)
                    ),
                palette = palette,
                state = state
            )
        }

        drawFlowPackets(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreRadius = coreRadius,
            nodes = nodes,
            palette = palette,
            state = state,
            motion = motion
        )

        drawOutputStream(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreRadius = coreRadius,
            right = w * 0.965f,
            palette = palette,
            state = state,
            motion = motion
        )

        if (
            state ==
            STATE_STOP
        ) {
            drawStopBarrier(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = coreRadius,
                palette = palette
            )
        }

        if (
            railHeight >
            0f
        ) {
            drawStateRail(
                canvas = canvas,
                top = railTop,
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

    /**
     * Keeps the proven device layout correction local to the hero card.
     * It only adjusts the two existing weights and caps the state title.
     */
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
        top: Float,
        bottom: Float,
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
                    Color.parseColor("#02060D"),
                    Color.parseColor("#07111D"),
                    Color.parseColor("#030711")
                ),
                floatArrayOf(
                    0f,
                    0.54f,
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

        stroke.color =
            Color.parseColor("#17314A")

        stroke.alpha =
            58

        stroke.strokeWidth =
            dp(0.48f)

        for (
            column in
            1 until 14
        ) {
            val x =
                width *
                    column /
                    14f

            canvas.drawLine(
                x,
                top,
                x,
                bottom,
                stroke
            )
        }

        for (
            row in
            1 until 7
        ) {
            val y =
                top +
                    (
                        bottom - top
                    ) *
                    row /
                    7f

            canvas.drawLine(
                dp(8f),
                y,
                width - dp(8f),
                y,
                stroke
            )
        }

        val scanFraction =
            (
                now %
                    motion.scanPeriodMs
                ).toFloat() /
                motion.scanPeriodMs.toFloat()

        val scanX =
            width *
                (
                    0.12f +
                        scanFraction *
                            0.76f
                    )

        fill.shader =
            LinearGradient(
                scanX - dp(30f),
                0f,
                scanX + dp(30f),
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        palette.primary,
                        18
                    ),
                    withAlpha(
                        palette.primary,
                        82
                    ),
                    withAlpha(
                        palette.primary,
                        18
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
            scanX - dp(30f),
            top,
            scanX + dp(30f),
            bottom,
            fill
        )

        fill.shader =
            null
    }

    private fun drawSignalField(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreRadius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val left =
            width * 0.03f

        val right =
            width * 0.965f

        val amplitudeBoost =
            when(state) {
                STATE_RECOGNITION ->
                    1.55f

                STATE_ANSWERING ->
                    1.35f

                else ->
                    1f
            }

        wavePath.reset()

        val samples =
            104

        val phase =
            now /
                motion.wavePeriodMs

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

            val centerWeight =
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
                        8.0 +
                        phase
                )
                    .toFloat()

            val detail =
                sin(
                    t *
                        PI *
                        27.0 -
                        phase *
                            0.77
                )
                    .toFloat() *
                    0.22f

            val normalizedDistance =
                (
                    (x - cx) /
                        (coreRadius * 1.25f)
                    )
                    .coerceIn(
                        -1f,
                        1f
                    )

            val exclusion =
                (
                    0.35f +
                        0.65f *
                            normalizedDistance *
                            normalizedDistance
                    )
                    .coerceIn(
                        0.35f,
                        1f
                    )

            val y =
                cy +
                    (
                        main +
                            detail
                    ) *
                    min(
                        coreRadius * 0.21f,
                        dp(21f)
                    ) *
                    centerWeight *
                    amplitudeBoost *
                    exclusion

            if (
                i == 0
            ) {
                wavePath.moveTo(
                    x,
                    y
                )
            } else {
                wavePath.lineTo(
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
                    0.28f,
                    0.50f,
                    0.72f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        glow.shader =
            gradient

        glow.alpha =
            motion.waveGlowAlpha

        glow.strokeWidth =
            dp(6f)

        canvas.drawPath(
            wavePath,
            glow
        )

        stroke.shader =
            gradient

        stroke.alpha =
            motion.waveAlpha

        stroke.strokeWidth =
            dp(1.2f)

        canvas.drawPath(
            wavePath,
            stroke
        )

        stroke.shader =
            null

        glow.shader =
            null
    }

    private fun drawConnection(
        canvas: Canvas,
        now: Long,
        fromX: Float,
        fromY: Float,
        toX: Float,
        toY: Float,
        color: Int,
        active: Boolean,
        phaseOffset: Double
    ) {
        val midX =
            (
                fromX + toX
            ) *
                0.50f

        val direction =
            if (
                fromX < toX
            ) {
                1f
            } else {
                -1f
            }

        path.reset()

        path.moveTo(
            fromX,
            fromY
        )

        path.cubicTo(
            midX -
                dp(15f) *
                    direction,
            fromY,
            midX +
                dp(15f) *
                    direction,
            toY,
            toX,
            toY
        )

        glow.color =
            color

        glow.alpha =
            if (active) {
                78
            } else {
                18
            }

        glow.strokeWidth =
            dp(
                if (active) {
                    6f
                } else {
                    3f
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
                230
            } else {
                92
            }

        stroke.strokeWidth =
            dp(
                if (active) {
                    1.25f
                } else {
                    0.70f
                }
            )

        canvas.drawPath(
            path,
            stroke
        )

        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                390.0 +
                                phaseOffset
                        )
                )
                .toFloat()

        val px =
            fromX +
                (
                    toX - fromX
                ) *
                pulse

        val py =
            fromY +
                (
                    toY - fromY
                ) *
                pulse

        fill.color =
            if (active) {
                Color.WHITE
            } else {
                color
            }

        fill.alpha =
            if (active) {
                240
            } else {
                120
            }

        canvas.drawCircle(
            px,
            py,
            dp(
                if (active) {
                    2.1f
                } else {
                    1.2f
                }
            ),
            fill
        )
    }

    private fun drawCentralCore(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                680.0
                        )
                )
                .toFloat()

        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius *
                    1.18f,
                intArrayOf(
                    withAlpha(
                        Color.WHITE,
                        150
                    ),
                    withAlpha(
                        palette.primary,
                        175
                    ),
                    withAlpha(
                        palette.secondary,
                        86
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.20f,
                    0.58f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha =
            255

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    1f +
                        pulse *
                            0.02f
                    ),
            fill
        )

        fill.shader =
            null

        repeat(
            5
        ) {
            layer ->

            val rr =
                radius *
                    (
                        1.00f -
                            layer *
                                0.145f
                        )

            val rotation =
                (
                    now /
                        (
                            2200.0 +
                                layer *
                                    460.0
                            )
                    ) *
                    (
                        if (
                            layer %
                                2 ==
                                0
                        ) {
                            1.0
                        } else {
                            -1.0
                        }
                    )

            val color =
                when(
                    layer %
                        3
                ) {
                    0 ->
                        palette.primary

                    1 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            drawHexagon(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = rr,
                rotation = rotation,
                color = color,
                alpha =
                    245 -
                        layer *
                            30,
                strokeWidth =
                    if (
                        layer == 0
                    ) {
                        1.55f
                    } else {
                        0.95f
                    }
            )
        }

        val nucleusRadius =
            radius *
                0.22f

        fill.shader =
            RadialGradient(
                cx,
                cy,
                nucleusRadius,
                intArrayOf(
                    Color.WHITE,
                    palette.primary,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.26f,
                    0.65f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha =
            255

        canvas.drawCircle(
            cx,
            cy,
            nucleusRadius *
                (
                    0.92f +
                        pulse *
                            0.12f
                    ),
            fill
        )

        fill.shader =
            null

        strongLabel.textSize =
            dp(10.5f)

        strongLabel.color =
            Color.WHITE

        strongLabel.alpha =
            if (
                state ==
                STATE_STOP
            ) {
                160
            } else {
                245
            }

        canvas.drawText(
            "AYANA",
            cx,
            cy +
                dp(3.5f),
            strongLabel
        )
    }

    private fun drawModuleNode(
        canvas: Canvas,
        now: Long,
        node: Node,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        val active =
            isNodeActive(
                node.type,
                state
            )

        val color =
            nodeColor(
                node.type,
                state,
                palette
            )

        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                740.0 +
                                node.type.ordinal *
                                    0.83
                        )
                )
                .toFloat()

        if (active) {
            fill.shader =
                RadialGradient(
                    node.x,
                    node.y,
                    radius *
                        1.6f,
                    intArrayOf(
                        withAlpha(
                            color,
                            95
                        ),
                        withAlpha(
                            color,
                            28
                        ),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(
                        0f,
                        0.46f,
                        1f
                    ),
                    Shader.TileMode.CLAMP
                )

            fill.alpha =
                255

            canvas.drawCircle(
                node.x,
                node.y,
                radius *
                    1.6f,
                fill
            )

            fill.shader =
                null
        }

        drawHexagon(
            canvas = canvas,
            cx = node.x,
            cy = node.y,
            radius =
                radius *
                    (
                        if (active) {
                            1f +
                                pulse *
                                    0.025f
                        } else {
                            1f
                        }
                    ),
            rotation =
                PI /
                    6.0,
            color = color,
            alpha =
                if (active) {
                    245
                } else {
                    155
                },
            strokeWidth =
                if (active) {
                    1.25f
                } else {
                    0.85f
                }
        )

        drawNodeIcon(
            canvas = canvas,
            node = node,
            radius = radius,
            color =
                if (active) {
                    Color.WHITE
                } else {
                    color
                },
            alpha =
                if (active) {
                    245
                } else {
                    190
                }
        )

        label.textSize =
            dp(8.4f)

        label.color =
            Color.parseColor("#D7E4F3")

        label.alpha =
            if (active) {
                245
            } else {
                185
            }

        canvas.drawText(
            node.line1,
            node.x,
            node.y -
                radius -
                dp(7f),
            label
        )

        canvas.drawText(
            node.line2,
            node.x,
            node.y -
                radius +
                dp(3f),
            label
        )
    }

    private fun drawNodeIcon(
        canvas: Canvas,
        node: Node,
        radius: Float,
        color: Int,
        alpha: Int
    ) {
        stroke.color =
            color

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(1.05f)

        when(node.type) {
            NodeType.MEMORY -> {
                repeat(
                    3
                ) {
                    i ->

                    val y =
                        node.y +
                            (
                                i - 1
                            ) *
                            radius *
                            0.20f

                    val half =
                        radius *
                            (
                                0.30f -
                                    i *
                                        0.025f
                                )

                    canvas.drawLine(
                        node.x - half,
                        y,
                        node.x + half,
                        y,
                        stroke
                    )
                }
            }

            NodeType.PLANNING -> {
                ring.set(
                    node.x -
                        radius *
                            0.30f,
                    node.y -
                        radius *
                            0.30f,
                    node.x +
                        radius *
                            0.30f,
                    node.y +
                        radius *
                            0.30f
                )

                canvas.drawOval(
                    ring,
                    stroke
                )

                ring.set(
                    node.x -
                        radius *
                            0.14f,
                    node.y -
                        radius *
                            0.14f,
                    node.x +
                        radius *
                            0.14f,
                    node.y +
                        radius *
                            0.14f
                )

                canvas.drawOval(
                    ring,
                    stroke
                )

                canvas.drawLine(
                    node.x,
                    node.y,
                    node.x +
                        radius *
                            0.34f,
                    node.y -
                        radius *
                            0.34f,
                    stroke
                )
            }

            NodeType.DATA -> {
                repeat(
                    3
                ) {
                    i ->

                    val y =
                        node.y +
                            (
                                i - 1
                            ) *
                            radius *
                            0.19f

                    canvas.drawLine(
                        node.x -
                            radius *
                                0.30f,
                        y,
                        node.x +
                            radius *
                                0.30f,
                        y,
                        stroke
                    )

                    fill.color =
                        color

                    fill.alpha =
                        alpha

                    canvas.drawCircle(
                        node.x +
                            (
                                i - 1
                            ) *
                                radius *
                                0.11f,
                        y,
                        dp(1.5f),
                        fill
                    )
                }
            }

            NodeType.TOOLS -> {
                canvas.drawLine(
                    node.x -
                        radius *
                            0.25f,
                    node.y +
                        radius *
                            0.25f,
                    node.x +
                        radius *
                            0.25f,
                    node.y -
                        radius *
                            0.25f,
                    stroke
                )

                canvas.drawLine(
                    node.x -
                        radius *
                            0.25f,
                    node.y -
                        radius *
                            0.25f,
                    node.x +
                        radius *
                            0.25f,
                    node.y +
                        radius *
                            0.25f,
                    stroke
                )
            }

            NodeType.VERIFY -> {
                path.reset()

                path.moveTo(
                    node.x,
                    node.y -
                        radius *
                            0.34f
                )

                path.lineTo(
                    node.x +
                        radius *
                            0.28f,
                    node.y -
                        radius *
                            0.16f
                )

                path.lineTo(
                    node.x +
                        radius *
                            0.22f,
                    node.y +
                        radius *
                            0.22f
                )

                path.lineTo(
                    node.x,
                    node.y +
                        radius *
                            0.36f
                )

                path.lineTo(
                    node.x -
                        radius *
                            0.22f,
                    node.y +
                        radius *
                            0.22f
                )

                path.lineTo(
                    node.x -
                        radius *
                            0.28f,
                    node.y -
                        radius *
                            0.16f
                )

                path.close()

                canvas.drawPath(
                    path,
                    stroke
                )

                canvas.drawLine(
                    node.x -
                        radius *
                            0.11f,
                    node.y,
                    node.x -
                        radius *
                            0.01f,
                    node.y +
                        radius *
                            0.10f,
                    stroke
                )

                canvas.drawLine(
                    node.x -
                        radius *
                            0.01f,
                    node.y +
                        radius *
                            0.10f,
                    node.x +
                        radius *
                            0.15f,
                    node.y -
                        radius *
                            0.11f,
                    stroke
                )
            }
        }
    }

    private fun drawFlowPackets(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreRadius: Float,
        nodes: List<Node>,
        palette: Palette,
        state: Int,
        motion: Motion
    ) {
        nodes.forEachIndexed {
            index,
            node ->

            val active =
                isNodeActive(
                    node.type,
                    state
                )

            val raw =
                (
                    now /
                        motion.packetPeriodMs +
                        index *
                            0.17
                    ) %
                    1.0

            val direction =
                when(node.type) {
                    NodeType.DATA,
                    NodeType.MEMORY,
                    NodeType.PLANNING ->
                        if (
                            state ==
                            STATE_ANSWERING
                        ) {
                            1.0 -
                                raw
                        } else {
                            raw
                        }

                    NodeType.TOOLS,
                    NodeType.VERIFY ->
                        if (
                            state ==
                            STATE_EXECUTING ||
                            state ==
                            STATE_ANSWERING
                        ) {
                            1.0 -
                                raw
                        } else {
                            raw
                        }
                }

            val t =
                direction
                    .toFloat()

            val x =
                node.x +
                    (
                        cx - node.x
                    ) *
                    t

            val y =
                node.y +
                    (
                        cy - node.y
                    ) *
                    t

            val color =
                nodeColor(
                    node.type,
                    state,
                    palette
                )

            fill.color =
                if (active) {
                    Color.WHITE
                } else {
                    color
                }

            fill.alpha =
                if (active) {
                    240
                } else {
                    120
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (active) {
                        2.4f
                    } else {
                        1.3f
                    }
                ),
                fill
            )
        }
    }

    private fun drawOutputStream(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreRadius: Float,
        right: Float,
        palette: Palette,
        state: Int,
        motion: Motion
    ) {
        val left =
            cx +
                coreRadius *
                    1.08f

        val active =
            state ==
                STATE_EXECUTING ||
                state ==
                    STATE_ANSWERING

        stroke.shader =
            LinearGradient(
                left,
                cy,
                right,
                cy,
                intArrayOf(
                    palette.secondary,
                    palette.primary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.40f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        stroke.alpha =
            if (active) {
                205
            } else {
                75
            }

        stroke.strokeWidth =
            dp(
                if (active) {
                    1.15f
                } else {
                    0.65f
                }
            )

        canvas.drawLine(
            left,
            cy,
            right,
            cy,
            stroke
        )

        stroke.shader =
            null

        val packetCount =
            if (active) {
                7
            } else {
                3
            }

        repeat(
            packetCount
        ) {
            index ->

            val t =
                (
                    (
                        now /
                            (
                                motion.packetPeriodMs *
                                    0.78
                                ) +
                            index /
                                packetCount.toDouble()
                        ) %
                        1.0
                    )
                    .toFloat()

            fill.color =
                if (
                    state ==
                    STATE_ANSWERING
                ) {
                    palette.accent
                } else {
                    palette.primary
                }

            fill.alpha =
                if (active) {
                    220
                } else {
                    90
                }

            canvas.drawCircle(
                left +
                    (
                        right - left
                    ) *
                    t,
                cy +
                    sin(
                        now /
                            220.0 +
                            index
                    )
                        .toFloat() *
                        dp(2f),
                dp(
                    if (active) {
                        1.8f
                    } else {
                        1.1f
                    }
                ),
                fill
            )
        }
    }

    private fun drawStopBarrier(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        fill.color =
            palette.primary

        fill.alpha =
            38

        canvas.drawCircle(
            cx,
            cy,
            radius *
                1.18f,
            fill
        )

        stroke.color =
            Color.WHITE

        stroke.alpha =
            245

        stroke.strokeWidth =
            dp(2f)

        val d =
            radius *
                0.30f

        canvas.drawLine(
            cx - d,
            cy - d,
            cx + d,
            cy + d,
            stroke
        )

        canvas.drawLine(
            cx + d,
            cy - d,
            cx - d,
            cy + d,
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
            width *
                0.065f

        val right =
            width *
                0.935f

        val step =
            (
                right - left
            ) /
                (
                    labels.size - 1
                )

        val nodeY =
            top +
                railHeight *
                    0.38f

        val labelY =
            top +
                railHeight *
                    0.82f

        stroke.color =
            Color.parseColor("#34465F")

        stroke.alpha =
            190

        stroke.strokeWidth =
            dp(0.72f)

        canvas.drawLine(
            left,
            nodeY,
            right,
            nodeY,
            stroke
        )

        labels.indices.forEach {
            index ->

            val x =
                left +
                    step *
                        index

            val selected =
                index ==
                    active

            val color =
                paletteFor(index)
                    .primary

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
                nodeY,
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
                    nodeY,
                    dp(9.6f),
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
                    nodeY,
                    dp(7.8f),
                    stroke
                )
            }

            label.textSize =
                dp(
                    if (selected) {
                        8.7f
                    } else {
                        8.0f
                    }
                )

            label.typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    if (selected) {
                        Typeface.BOLD
                    } else {
                        Typeface.NORMAL
                    }
                )

            label.color =
                if (selected) {
                    color
                } else {
                    Color.parseColor("#8593A8")
                }

            label.alpha =
                if (selected) {
                    255
                } else {
                    225
                }

            canvas.drawText(
                labels[index],
                x,
                labelY,
                label
            )
        }

        canvas.restoreToCount(
            save
        )
    }

    private fun drawHexagon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        rotation: Double,
        color: Int,
        alpha: Int,
        strokeWidth: Float
    ) {
        path.reset()

        repeat(
            6
        ) {
            index ->

            val angle =
                rotation +
                    index *
                        (
                            PI *
                                2.0 /
                                6.0
                            ) -
                    PI /
                        2.0

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    radius

            val y =
                cy +
                    sin(angle)
                        .toFloat() *
                    radius

            if (
                index ==
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

        path.close()

        glow.color =
            color

        glow.alpha =
            alpha /
                4

        glow.strokeWidth =
            dp(
                strokeWidth *
                    5f
            )

        canvas.drawPath(
            path,
            glow
        )

        stroke.color =
            color

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(strokeWidth)

        canvas.drawPath(
            path,
            stroke
        )
    }

    private fun isNodeActive(
        type: NodeType,
        state: Int
    ): Boolean {
        return when(state) {
            STATE_WAITING ->
                type ==
                    NodeType.DATA

            STATE_RECOGNITION ->
                type ==
                    NodeType.DATA

            STATE_THINKING ->
                type ==
                    NodeType.MEMORY ||
                    type ==
                        NodeType.PLANNING

            STATE_EXECUTING ->
                type ==
                    NodeType.TOOLS

            STATE_ANSWERING ->
                type ==
                    NodeType.VERIFY

            STATE_STOP ->
                false

            else ->
                false
        }
    }

    private fun nodeColor(
        type: NodeType,
        state: Int,
        palette: Palette
    ): Int {
        if (
            state ==
            STATE_STOP
        ) {
            return palette.primary
        }

        return when(type) {
            NodeType.MEMORY ->
                Color.parseColor("#4D8BFF")

            NodeType.PLANNING ->
                if (
                    state ==
                    STATE_THINKING
                ) {
                    palette.primary
                } else {
                    Color.parseColor("#668CFF")
                }

            NodeType.DATA ->
                if (
                    state ==
                    STATE_RECOGNITION
                ) {
                    palette.primary
                } else {
                    Color.parseColor("#17D7F0")
                }

            NodeType.TOOLS ->
                if (
                    state ==
                    STATE_EXECUTING
                ) {
                    palette.primary
                } else {
                    Color.parseColor("#39D98A")
                }

            NodeType.VERIFY ->
                if (
                    state ==
                    STATE_ANSWERING
                ) {
                    palette.primary
                } else {
                    Color.parseColor("#A66CFF")
                }
        }
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
                        Color.parseColor("#22E5F2"),
                    secondary =
                        Color.parseColor("#2E7BFF"),
                    accent =
                        Color.parseColor("#8B5CFF")
                )

            STATE_RECOGNITION ->
                Palette(
                    primary =
                        Color.parseColor("#248DFF"),
                    secondary =
                        Color.parseColor("#22D9FF"),
                    accent =
                        Color.parseColor("#6A5CFF")
                )

            STATE_THINKING ->
                Palette(
                    primary =
                        Color.parseColor("#7553FF"),
                    secondary =
                        Color.parseColor("#375DFF"),
                    accent =
                        Color.parseColor("#B15CFF")
                )

            STATE_EXECUTING ->
                Palette(
                    primary =
                        Color.parseColor("#30E77B"),
                    secondary =
                        Color.parseColor("#18CFC1"),
                    accent =
                        Color.parseColor("#46B8FF")
                )

            STATE_ANSWERING ->
                Palette(
                    primary =
                        Color.parseColor("#EC36C8"),
                    secondary =
                        Color.parseColor("#9454FF"),
                    accent =
                        Color.parseColor("#48CFFF")
                )

            STATE_STOP ->
                Palette(
                    primary =
                        Color.parseColor("#FF4933"),
                    secondary =
                        Color.parseColor("#FF7A2B"),
                    accent =
                        Color.parseColor("#E13E78")
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
                    frameDelayMs = 38L,
                    scanPeriodMs = 5200L,
                    scanAlpha = 74,
                    wavePeriodMs = 720.0,
                    waveAlpha = 180,
                    waveGlowAlpha = 54,
                    packetPeriodMs = 2600.0
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 28L,
                    scanPeriodMs = 2100L,
                    scanAlpha = 115,
                    wavePeriodMs = 350.0,
                    waveAlpha = 235,
                    waveGlowAlpha = 82,
                    packetPeriodMs = 1150.0
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 30L,
                    scanPeriodMs = 3000L,
                    scanAlpha = 102,
                    wavePeriodMs = 470.0,
                    waveAlpha = 212,
                    waveGlowAlpha = 68,
                    packetPeriodMs = 1380.0
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1750L,
                    scanAlpha = 124,
                    wavePeriodMs = 300.0,
                    waveAlpha = 238,
                    waveGlowAlpha = 88,
                    packetPeriodMs = 900.0
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1900L,
                    scanAlpha = 120,
                    wavePeriodMs = 320.0,
                    waveAlpha = 236,
                    waveGlowAlpha = 86,
                    packetPeriodMs = 940.0
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 58L,
                    scanPeriodMs = 8000L,
                    scanAlpha = 46,
                    wavePeriodMs = 1050.0,
                    waveAlpha = 120,
                    waveGlowAlpha = 34,
                    packetPeriodMs = 3900.0
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
        return value *
            density
    }

    private data class Node(
        val x: Float,
        val y: Float,
        val type: NodeType,
        val line1: String,
        val line2: String
    )

    private enum class NodeType {
        MEMORY,
        PLANNING,
        DATA,
        TOOLS,
        VERIFY
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
        val waveGlowAlpha: Int,
        val packetPeriodMs: Double
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
