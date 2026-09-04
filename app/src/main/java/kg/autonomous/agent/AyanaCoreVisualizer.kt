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
 * AYANA Core Visualizer v11.1 — APPROVED AGENT CORE MAP.
 *
 * Rebuilt specifically around the user-approved composition:
 *
 *                   MEMORY            PLANNING
 *                        \            /
 *                         \          /
 *   INPUT WAVE  --------  CENTRAL CORE  -------- OUTPUT
 *                         /     |      \
 *                        /      |       \
 *                    TOOLS   VERIFY    GOALS
 *
 * Key decisions:
 * - one large central hexagonal Agent Core;
 * - five large functional modules, not tiny decorative satellites;
 * - no separate "Поток данных" hexagon: the input waveform itself is the data flow;
 * - dedicated "Цели и приоритеты" module restored;
 * - labels are readable on Galaxy Tab S8;
 * - no giant orb/radar;
 * - no PNG/static image dependency;
 * - no fake numeric Agent Core load;
 * - thin six-state rail retained;
 * - visual motion is state-reactive, not claimed as literal model telemetry.
 *
 * Runtime states:
 * 0 Ожидание
 * 1 Распознавание
 * 2 Думаю
 * 3 Выполняю
 * 4 Отвечаю
 * 5 Стоп
 *
 * Visual-only component. Does not alter ORB, routing, TTS, microphone,
 * Accessibility or Android execution logic.
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

    private val path =
        Path()

    private val wave =
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
                    dp(40f),
                    h * 0.14f
                )
            }

        val railTop =
            h - railHeight

        val top =
            dp(9f)

        val bottom =
            if (compact) {
                h - dp(9f)
            } else {
                railTop - dp(5f)
            }

        drawBackground(
            canvas = canvas,
            now = now,
            top = top,
            bottom = bottom,
            palette = palette,
            motion = motion
        )

        val cx =
            w * 0.53f

        val cy =
            top +
                (
                    bottom - top
                ) *
                0.51f

        val coreRadius =
            min(
                w * 0.205f,
                (
                    bottom - top
                ) *
                    0.355f
            )
                .coerceAtLeast(
                    dp(46f)
                )

        val nodeRadius =
            min(
                coreRadius * 0.40f,
                dp(34f)
            )

        val nodes =
            listOf(
                ModuleNode(
                    x = w * 0.29f,
                    y =
                        top +
                            (
                                bottom - top
                            ) *
                            0.22f,
                    type = ModuleType.MEMORY,
                    line1 = "Память",
                    line2 = "и контекст"
                ),
                ModuleNode(
                    x = w * 0.78f,
                    y =
                        top +
                            (
                                bottom - top
                            ) *
                            0.22f,
                    type = ModuleType.PLANNING,
                    line1 = "Планирование",
                    line2 = "целей"
                ),
                ModuleNode(
                    x = w * 0.28f,
                    y =
                        top +
                            (
                                bottom - top
                            ) *
                            0.78f,
                    type = ModuleType.TOOLS,
                    line1 = "Инструменты",
                    line2 = "и действия"
                ),
                ModuleNode(
                    x = w * 0.53f,
                    y =
                        top +
                            (
                                bottom - top
                            ) *
                            0.86f,
                    type = ModuleType.VERIFY,
                    line1 = "Проверка",
                    line2 = "и верификация"
                ),
                ModuleNode(
                    x = w * 0.80f,
                    y =
                        top +
                            (
                                bottom - top
                            ) *
                            0.76f,
                    type = ModuleType.GOALS,
                    line1 = "Цели",
                    line2 = "и приоритеты"
                )
            )

        drawInputStream(
            canvas = canvas,
            now = now,
            left = w * 0.015f,
            right = cx - coreRadius * 0.92f,
            cy = cy,
            palette = palette,
            motion = motion,
            state = state
        )

        drawAmbientRoutes(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreRadius = coreRadius,
            nodes = nodes,
            palette = palette,
            state = state
        )

        nodes.forEachIndexed { index, node ->
            drawModuleConnection(
                canvas = canvas,
                now = now,
                node = node,
                cx = cx,
                cy = cy,
                coreRadius = coreRadius,
                color =
                    moduleColor(
                        node.type,
                        state,
                        palette
                    ),
                active =
                    isModuleActive(
                        node.type,
                        state
                    ),
                phaseOffset =
                    index * 0.93
            )
        }

        drawAgentCore(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = coreRadius,
            palette = palette,
            state = state
        )

        nodes.forEach { node ->
            drawModule(
                canvas = canvas,
                now = now,
                node = node,
                radius = nodeRadius,
                palette = palette,
                state = state
            )
        }

        drawRoutePackets(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            nodes = nodes,
            palette = palette,
            state = state,
            motion = motion
        )

        drawOutputStream(
            canvas = canvas,
            now = now,
            left = cx + coreRadius * 0.92f,
            right = w * 0.985f,
            cy = cy,
            palette = palette,
            motion = motion,
            state = state
        )

        if (
            state ==
            STATE_STOP
        ) {
            drawStopOverlay(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = coreRadius,
                palette = palette
            )
        }

        if (
            railHeight > 0f
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
                0.70f

            ownParams.weight =
                1.66f

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
                    Color.parseColor("#07101B"),
                    Color.parseColor("#030711")
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

        stroke.shader =
            null

        stroke.color =
            Color.parseColor("#183149")

        stroke.alpha =
            52

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
                dp(7f),
                y,
                width - dp(7f),
                y,
                stroke
            )
        }

        val scan =
            (
                now %
                    motion.scanPeriodMs
                ).toFloat() /
                motion.scanPeriodMs.toFloat()

        val scanX =
            width *
                (
                    0.12f +
                        scan * 0.76f
                    )

        fill.shader =
            LinearGradient(
                scanX - dp(26f),
                0f,
                scanX + dp(26f),
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        palette.primary,
                        14
                    ),
                    withAlpha(
                        palette.primary,
                        68
                    ),
                    withAlpha(
                        palette.primary,
                        14
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
            scanX - dp(26f),
            top,
            scanX + dp(26f),
            bottom,
            fill
        )

        fill.shader =
            null
    }

    private fun drawInputStream(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        cy: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val boost =
            if (
                state ==
                STATE_RECOGNITION
            ) {
                1.65f
            } else {
                1f
            }

        drawWave(
            canvas = canvas,
            now = now,
            left = left,
            right = right,
            cy = cy,
            amplitude =
                min(
                    height * 0.095f,
                    dp(24f)
                ) *
                    boost,
            primary = palette.primary,
            secondary = palette.secondary,
            period = motion.wavePeriodMs,
            alpha = motion.waveAlpha
        )
    }

    private fun drawOutputStream(
        canvas: Canvas,
        now: Long,
        left: Float,
        right: Float,
        cy: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val boost =
            when(state) {
                STATE_EXECUTING ->
                    1.35f

                STATE_ANSWERING ->
                    1.65f

                else ->
                    0.88f
            }

        drawWave(
            canvas = canvas,
            now = now,
            left = left,
            right = right,
            cy = cy,
            amplitude =
                min(
                    height * 0.082f,
                    dp(21f)
                ) *
                    boost,
            primary = palette.secondary,
            secondary = palette.accent,
            period =
                motion.wavePeriodMs *
                    0.92,
            alpha =
                if (
                    state ==
                    STATE_EXECUTING ||
                    state ==
                        STATE_ANSWERING
                ) {
                    motion.waveAlpha
                } else {
                    motion.waveAlpha /
                        2
                }
        )
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
        alpha: Int
    ) {
        if (
            right <= left
        ) {
            return
        }

        wave.reset()

        val samples =
            70

        val phase =
            now /
                period

        for (
            index in
            0..samples
        ) {
            val t =
                index /
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
                        8.0 +
                        phase
                )
                    .toFloat()

            val detail =
                sin(
                    t *
                        PI *
                        25.0 -
                        phase *
                            0.76
                )
                    .toFloat() *
                    0.23f

            val y =
                cy +
                    (
                        main +
                            detail
                    ) *
                    amplitude *
                    envelope

            if (
                index ==
                0
            ) {
                wave.moveTo(
                    x,
                    y
                )
            } else {
                wave.lineTo(
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
                    primary,
                    Color.WHITE,
                    secondary,
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

        glow.shader =
            gradient

        glow.alpha =
            66

        glow.strokeWidth =
            dp(6f)

        canvas.drawPath(
            wave,
            glow
        )

        stroke.shader =
            gradient

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(1.25f)

        canvas.drawPath(
            wave,
            stroke
        )

        stroke.shader =
            null

        glow.shader =
            null
    }

    private fun drawAmbientRoutes(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreRadius: Float,
        nodes: List<ModuleNode>,
        palette: Palette,
        state: Int
    ) {
        stroke.shader =
            null

        stroke.strokeWidth =
            dp(0.55f)

        nodes.forEachIndexed {
            index,
            a ->

            val b =
                nodes[
                    (
                        index + 1
                    ) %
                        nodes.size
                ]

            stroke.color =
                if (
                    index %
                        2 ==
                        0
                ) {
                    palette.primary
                } else {
                    palette.secondary
                }

            stroke.alpha =
                if (
                    state ==
                    STATE_THINKING
                ) {
                    72
                } else {
                    34
                }

            path.reset()

            path.moveTo(
                a.x,
                a.y
            )

            path.cubicTo(
                cx +
                    sin(
                        now /
                            1600.0 +
                            index
                    )
                        .toFloat() *
                        coreRadius *
                        0.35f,
                a.y,
                cx -
                    sin(
                        now /
                            1400.0 +
                            index *
                                0.67
                    )
                        .toFloat() *
                        coreRadius *
                        0.35f,
                b.y,
                b.x,
                b.y
            )

            canvas.drawPath(
                path,
                stroke
            )
        }
    }

    private fun drawModuleConnection(
        canvas: Canvas,
        now: Long,
        node: ModuleNode,
        cx: Float,
        cy: Float,
        coreRadius: Float,
        color: Int,
        active: Boolean,
        phaseOffset: Double
    ) {
        val dx =
            cx - node.x

        val dy =
            cy - node.y

        val bendX =
            node.x +
                dx *
                    0.52f

        val bendY =
            node.y +
                dy *
                    0.52f

        path.reset()

        path.moveTo(
            node.x,
            node.y
        )

        path.cubicTo(
            bendX,
            node.y,
            bendX,
            cy,
            cx,
            cy
        )

        glow.color =
            color

        glow.alpha =
            if (active) {
                82
            } else {
                17
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
                85
            }

        stroke.strokeWidth =
            dp(
                if (active) {
                    1.25f
                } else {
                    0.68f
                }
            )

        canvas.drawPath(
            path,
            stroke
        )

        val t =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                430.0 +
                                phaseOffset
                        )
                )
                .toFloat()

        val px =
            node.x +
                dx *
                    t

        val py =
            node.y +
                dy *
                    t

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
                115
            }

        canvas.drawCircle(
            px,
            py,
            dp(
                if (active) {
                    2.2f
                } else {
                    1.1f
                }
            ),
            fill
        )
    }

    private fun drawAgentCore(
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
                                690.0
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
                        145
                    ),
                    withAlpha(
                        palette.primary,
                        175
                    ),
                    withAlpha(
                        palette.secondary,
                        88
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
                            0.018f
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
                now /
                    (
                        2350.0 +
                            layer *
                                470.0
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
                widthDp =
                    if (
                        layer == 0
                    ) {
                        1.6f
                    } else {
                        0.95f
                    }
            )
        }

        val nucleus =
            radius * 0.18f

        fill.shader =
            RadialGradient(
                cx,
                cy,
                nucleus *
                    1.7f,
                intArrayOf(
                    Color.WHITE,
                    palette.primary,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.23f,
                    0.61f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha =
            if (
                state ==
                STATE_STOP
            ) {
                120
            } else {
                255
            }

        canvas.drawCircle(
            cx,
            cy,
            nucleus *
                (
                    0.94f +
                        pulse *
                            0.10f
                    ),
            fill
        )

        fill.shader =
            null

        // AYANA signature: three broken arcs, not a wordmark.
        repeat(
            3
        ) {
            arc ->

            val rr =
                radius *
                    (
                        0.31f +
                            arc *
                                0.085f
                        )

            ring.set(
                cx - rr,
                cy - rr,
                cx + rr,
                cy + rr
            )

            val phase =
                (
                    now %
                        (
                            5200L +
                                arc *
                                    820L
                            )
                    ).toFloat() /
                    (
                        5200L +
                            arc *
                                820L
                        ).toFloat() *
                    360f *
                    (
                        if (
                            arc %
                                2 ==
                                0
                        ) {
                            1f
                        } else {
                            -1f
                        }
                    )

            stroke.color =
                if (
                    arc ==
                    1
                ) {
                    palette.accent
                } else {
                    palette.primary
                }

            stroke.alpha =
                175 -
                    arc *
                        22

            stroke.strokeWidth =
                dp(
                    0.95f -
                        arc *
                            0.10f
                )

            canvas.drawArc(
                ring,
                phase + 10f,
                54f,
                false,
                stroke
            )

            canvas.drawArc(
                ring,
                phase + 142f,
                42f,
                false,
                stroke
            )

            canvas.drawArc(
                ring,
                phase + 248f,
                60f,
                false,
                stroke
            )
        }
    }

    private fun drawModule(
        canvas: Canvas,
        now: Long,
        node: ModuleNode,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        val active =
            isModuleActive(
                node.type,
                state
            )

        val color =
            moduleColor(
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
                                780.0 +
                                node.type.ordinal *
                                    0.91
                        )
                )
                .toFloat()

        if (active) {
            fill.shader =
                RadialGradient(
                    node.x,
                    node.y,
                    radius *
                        1.65f,
                    intArrayOf(
                        withAlpha(
                            color,
                            95
                        ),
                        withAlpha(
                            color,
                            24
                        ),
                        Color.TRANSPARENT
                    ),
                    floatArrayOf(
                        0f,
                        0.48f,
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
                    1.65f,
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
            rotation = PI / 6.0,
            color = color,
            alpha =
                if (active) {
                    245
                } else {
                    170
                },
            widthDp =
                if (active) {
                    1.35f
                } else {
                    0.90f
                }
        )

        drawModuleIcon(
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
                    195
                }
        )

        val textAbove =
            node.type ==
                ModuleType.MEMORY ||
                node.type ==
                    ModuleType.PLANNING

        val baseY =
            if (textAbove) {
                node.y -
                    radius -
                    dp(9f)
            } else {
                node.y +
                    radius +
                    dp(13f)
            }

        label.textSize =
            dp(9.4f)

        label.color =
            Color.parseColor("#DCE8F5")

        label.alpha =
            if (active) {
                250
            } else {
                205
            }

        canvas.drawText(
            node.line1,
            node.x,
            baseY,
            label
        )

        canvas.drawText(
            node.line2,
            node.x,
            baseY +
                dp(10.5f),
            label
        )
    }

    private fun drawModuleIcon(
        canvas: Canvas,
        node: ModuleNode,
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
            ModuleType.MEMORY -> {
                repeat(
                    3
                ) {
                    index ->

                    val y =
                        node.y +
                            (
                                index - 1
                            ) *
                            radius *
                            0.18f

                    val half =
                        radius *
                            (
                                0.30f -
                                    index *
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

            ModuleType.PLANNING -> {
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
                            0.35f,
                    node.y -
                        radius *
                            0.35f,
                    stroke
                )
            }

            ModuleType.TOOLS -> {
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

            ModuleType.VERIFY -> {
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

            ModuleType.GOALS -> {
                ring.set(
                    node.x -
                        radius *
                            0.31f,
                    node.y -
                        radius *
                            0.31f,
                    node.x +
                        radius *
                            0.31f,
                    node.y +
                        radius *
                            0.31f
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

                fill.color =
                    color

                fill.alpha =
                    alpha

                canvas.drawCircle(
                    node.x,
                    node.y,
                    dp(1.8f),
                    fill
                )
            }
        }
    }

    private fun drawRoutePackets(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        nodes: List<ModuleNode>,
        palette: Palette,
        state: Int,
        motion: Motion
    ) {
        nodes.forEachIndexed {
            index,
            node ->

            val active =
                isModuleActive(
                    node.type,
                    state
                )

            var t =
                (
                    now /
                        motion.packetPeriodMs +
                        index *
                            0.19
                    ) %
                    1.0

            if (
                node.type ==
                    ModuleType.TOOLS ||
                node.type ==
                    ModuleType.VERIFY ||
                node.type ==
                    ModuleType.GOALS
            ) {
                t =
                    1.0 -
                        t
            }

            val tf =
                t.toFloat()

            val x =
                node.x +
                    (
                        cx - node.x
                    ) *
                    tf

            val y =
                node.y +
                    (
                        cy - node.y
                    ) *
                    tf

            fill.color =
                if (active) {
                    Color.WHITE
                } else {
                    moduleColor(
                        node.type,
                        state,
                        palette
                    )
                }

            fill.alpha =
                if (active) {
                    245
                } else {
                    120
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (active) {
                        2.3f
                    } else {
                        1.2f
                    }
                ),
                fill
            )
        }
    }

    private fun drawStopOverlay(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        fill.color =
            palette.primary

        fill.alpha =
            36

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
            dp(2.1f)

        val d =
            radius *
                0.29f

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
            235

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
                    0.36f

        val textY =
            top +
                railHeight *
                    0.81f

        stroke.color =
            Color.parseColor("#34465F")

        stroke.alpha =
            180

        stroke.strokeWidth =
            dp(0.70f)

        canvas.drawLine(
            left,
            y,
            right,
            y,
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
                    100
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (selected) {
                        3.2f
                    } else {
                        1.8f
                    }
                ),
                fill
            )

            if (selected) {
                glow.color =
                    color

                glow.alpha =
                    72

                glow.strokeWidth =
                    dp(5.5f)

                canvas.drawCircle(
                    x,
                    y,
                    dp(9.4f),
                    glow
                )

                stroke.color =
                    Color.WHITE

                stroke.alpha =
                    235

                stroke.strokeWidth =
                    dp(0.95f)

                canvas.drawCircle(
                    x,
                    y,
                    dp(7.7f),
                    stroke
                )
            }

            label.textSize =
                dp(
                    if (selected) {
                        8.6f
                    } else {
                        7.9f
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
                    Color.parseColor("#8694A8")
                }

            label.alpha =
                if (selected) {
                    255
                } else {
                    220
                }

            canvas.drawText(
                labels[index],
                x,
                textY,
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
        widthDp: Float
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
                widthDp *
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
            dp(widthDp)

        canvas.drawPath(
            path,
            stroke
        )
    }

    private fun isModuleActive(
        type: ModuleType,
        state: Int
    ): Boolean {
        return when(state) {
            STATE_WAITING ->
                false

            STATE_RECOGNITION ->
                false

            STATE_THINKING ->
                type ==
                    ModuleType.MEMORY ||
                    type ==
                        ModuleType.PLANNING ||
                    type ==
                        ModuleType.GOALS

            STATE_EXECUTING ->
                type ==
                    ModuleType.TOOLS

            STATE_ANSWERING ->
                type ==
                    ModuleType.VERIFY

            STATE_STOP ->
                false

            else ->
                false
        }
    }

    private fun moduleColor(
        type: ModuleType,
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
            ModuleType.MEMORY ->
                if (
                    state ==
                    STATE_THINKING
                ) {
                    palette.primary
                } else {
                    Color.parseColor("#4E83FF")
                }

            ModuleType.PLANNING ->
                if (
                    state ==
                    STATE_THINKING
                ) {
                    palette.accent
                } else {
                    Color.parseColor("#657CFF")
                }

            ModuleType.TOOLS ->
                if (
                    state ==
                    STATE_EXECUTING
                ) {
                    palette.primary
                } else {
                    Color.parseColor("#31D88A")
                }

            ModuleType.VERIFY ->
                if (
                    state ==
                    STATE_ANSWERING
                ) {
                    palette.primary
                } else {
                    Color.parseColor("#8E66FF")
                }

            ModuleType.GOALS ->
                if (
                    state ==
                    STATE_THINKING
                ) {
                    palette.secondary
                } else {
                    Color.parseColor("#36C8EE")
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
                    scanAlpha = 70,
                    wavePeriodMs = 720.0,
                    waveAlpha = 180,
                    packetPeriodMs = 2500.0
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 28L,
                    scanPeriodMs = 2100L,
                    scanAlpha = 112,
                    wavePeriodMs = 350.0,
                    waveAlpha = 235,
                    packetPeriodMs = 1120.0
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 30L,
                    scanPeriodMs = 3000L,
                    scanAlpha = 100,
                    wavePeriodMs = 470.0,
                    waveAlpha = 210,
                    packetPeriodMs = 1350.0
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1750L,
                    scanAlpha = 120,
                    wavePeriodMs = 300.0,
                    waveAlpha = 238,
                    packetPeriodMs = 880.0
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1900L,
                    scanAlpha = 118,
                    wavePeriodMs = 320.0,
                    waveAlpha = 235,
                    packetPeriodMs = 920.0
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 58L,
                    scanPeriodMs = 8000L,
                    scanAlpha = 44,
                    wavePeriodMs = 1050.0,
                    waveAlpha = 120,
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
        return value * density
    }

    private data class ModuleNode(
        val x: Float,
        val y: Float,
        val type: ModuleType,
        val line1: String,
        val line2: String
    )

    private enum class ModuleType {
        MEMORY,
        PLANNING,
        TOOLS,
        VERIFY,
        GOALS
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
