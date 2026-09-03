package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
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
 * AYANA Core Visualizer v7.0 — COGNITIVE MATRIX.
 *
 * New direction after rejecting static-reference and "glowing orb" approaches.
 *
 * Design:
 * - no bitmap / PNG dependency;
 * - no giant decorative sphere;
 * - no AYANA wordmark inside the visualizer;
 * - serious agent-oriented "perception -> reasoning -> action" data flow;
 * - central cognitive matrix built from thin technical geometry;
 * - animated data lanes, packets, scan field, decision spine and neural nodes;
 * - six factual runtime states with six distinct approved palettes;
 * - bottom state rail:
 *   Ожидание / Распознавание / Думаю / Выполняю / Отвечаю / Стоп.
 *
 * State palette:
 * - Ожидание      cyan
 * - Распознавание electric blue
 * - Думаю         indigo/violet
 * - Выполняю      teal/green
 * - Отвечаю       magenta/violet
 * - Стоп          red/orange
 *
 * Motion is synthetic/state-reactive UI animation. It is not presented as
 * measured microphone amplitude or model telemetry.
 *
 * Public integration contract remains unchanged:
 *   AyanaCoreVisualizer(Context)
 *
 * No ORB, Accessibility, routing, TTS, microphone capture or command execution
 * logic is changed here.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val strokePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val glowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.NORMAL
                )
        }

    private val dataPath =
        Path()

    private val finePath =
        Path()

    private val corePath =
        Path()

    private val ringRect =
        RectF()

    private val pathMeasure =
        PathMeasure()

    private val packetPos =
        FloatArray(2)

    private val packetTan =
        FloatArray(2)

    private var attached =
        false

    private var palette =
        Palette.waiting()

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
            Color.BLACK
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        attached =
            true

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

        val runtimeState =
            AyanaVoiceService.currentStatusState

        val stateIndex =
            stateIndex(runtimeState)

        val motion =
            motionFor(stateIndex)

        palette =
            paletteFor(stateIndex)

        val now =
            SystemClock.uptimeMillis()

        val compact =
            height <
                dp(180f)

        val stageHeight =
            if (compact) {
                0f
            } else {
                min(
                    dp(54f),
                    height *
                        0.18f
                )
            }

        val contentBottom =
            height -
                stageHeight

        val cx =
            width *
                0.50f

        val cy =
            contentBottom *
                0.49f

        val contentTop =
            dp(8f)

        val contentHeight =
            (
                contentBottom -
                    contentTop
                )
                .coerceAtLeast(
                    dp(60f)
                )

        val coreWidth =
            min(
                width *
                    0.42f,
                contentHeight *
                    1.12f
            )

        val coreHeight =
            min(
                contentHeight *
                    0.72f,
                coreWidth *
                    0.72f
            )

        drawTechnicalBackground(
            canvas = canvas,
            now = now,
            contentBottom = contentBottom,
            color = palette.primary,
            motion = motion
        )

        drawDataCorridors(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreWidth = coreWidth,
            coreHeight = coreHeight,
            contentBottom = contentBottom,
            stateIndex = stateIndex,
            motion = motion
        )

        drawCognitiveMatrix(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreWidth = coreWidth,
            coreHeight = coreHeight,
            stateIndex = stateIndex,
            motion = motion
        )

        drawDecisionSpine(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreWidth = coreWidth,
            coreHeight = coreHeight,
            stateIndex = stateIndex,
            motion = motion
        )

        drawFlowPackets(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreWidth = coreWidth,
            coreHeight = coreHeight,
            contentBottom = contentBottom,
            stateIndex = stateIndex,
            motion = motion
        )

        drawStateSignature(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            coreWidth = coreWidth,
            coreHeight = coreHeight,
            stateIndex = stateIndex,
            motion = motion
        )

        if (
            stageHeight >
            0f
        ) {
            drawStageRail(
                canvas = canvas,
                activeIndex = stateIndex,
                top = contentBottom,
                height = stageHeight
            )
        }

        if (attached) {
            postInvalidateDelayed(
                motion.frameDelayMs
            )
        }
    }

    private fun drawTechnicalBackground(
        canvas: Canvas,
        now: Long,
        contentBottom: Float,
        color: Int,
        motion: Motion
    ) {
        val w =
            width.toFloat()

        val h =
            contentBottom

        // Very restrained matrix grid.
        strokePaint.shader =
            null

        strokePaint.color =
            Color.parseColor(
                "#132033"
            )

        strokePaint.alpha =
            72

        strokePaint.strokeWidth =
            dp(0.45f)

        val columns =
            18

        val rows =
            9

        for (
            index in
            1 until columns
        ) {
            val x =
                w *
                    index /
                    columns.toFloat()

            canvas.drawLine(
                x,
                h *
                    0.08f,
                x,
                h *
                    0.92f,
                strokePaint
            )
        }

        for (
            index in
            1 until rows
        ) {
            val y =
                h *
                    index /
                    rows.toFloat()

            canvas.drawLine(
                w *
                    0.03f,
                y,
                w *
                    0.97f,
                y,
                strokePaint
            )
        }

        // Moving vertical analysis scan.
        val scanFraction =
            (
                (
                    now %
                        motion.scanPeriodMs
                    ).toFloat() /
                    motion.scanPeriodMs.toFloat()
                )
                .coerceIn(
                    0f,
                    1f
                )

        val scanX =
            w *
                (
                    0.07f +
                        0.86f *
                            scanFraction
                    )

        val scanShader =
            LinearGradient(
                scanX -
                    dp(28f),
                0f,
                scanX +
                    dp(28f),
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        color,
                        18
                    ),
                    withAlpha(
                        color,
                        72
                    ),
                    withAlpha(
                        color,
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

        fillPaint.shader =
            scanShader

        fillPaint.alpha =
            motion.scanAlpha

        canvas.drawRect(
            scanX -
                dp(28f),
            h *
                0.06f,
            scanX +
                dp(28f),
            h *
                0.94f,
            fillPaint
        )

        fillPaint.shader =
            null
    }

    private fun drawDataCorridors(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreWidth: Float,
        coreHeight: Float,
        contentBottom: Float,
        stateIndex: Int,
        motion: Motion
    ) {
        val left =
            width *
                0.035f

        val right =
            width *
                0.965f

        val laneOffsets =
            floatArrayOf(
                -0.29f,
                -0.145f,
                0f,
                0.145f,
                0.29f
            )

        val color =
            palette.primary

        val phase =
            now /
                motion.flowPeriodMs

        laneOffsets.forEachIndexed {
            index,
            lane ->

            val y =
                cy +
                    coreHeight *
                        lane

            dataPath.reset()

            dataPath.moveTo(
                left,
                y +
                    sin(
                        phase +
                            index *
                                0.77
                    )
                        .toFloat() *
                        dp(2.5f)
            )

            dataPath.cubicTo(
                cx -
                    coreWidth *
                        0.62f,
                y -
                    coreHeight *
                        (
                            0.07f +
                                index *
                                    0.005f
                            ),
                cx -
                    coreWidth *
                        0.33f,
                y +
                    coreHeight *
                        0.04f,
                cx,
                y
            )

            dataPath.cubicTo(
                cx +
                    coreWidth *
                        0.33f,
                y -
                    coreHeight *
                        0.04f,
                cx +
                    coreWidth *
                        0.62f,
                y +
                    coreHeight *
                        (
                            0.07f +
                                index *
                                    0.005f
                            ),
                right,
                y +
                    sin(
                        phase *
                            0.91 +
                            index *
                                0.83
                    )
                        .toFloat() *
                        dp(2.5f)
            )

            val laneAlpha =
                if (
                    index ==
                    2
                ) {
                    motion.centerLaneAlpha
                } else {
                    motion.sideLaneAlpha
                }

            glowPaint.shader =
                corridorGradient(
                    left,
                    right,
                    color
                )

            glowPaint.alpha =
                laneAlpha /
                    3

            glowPaint.strokeWidth =
                dp(
                    if (
                        index ==
                        2
                    ) {
                        5.6f
                    } else {
                        3.2f
                    }
                )

            canvas.drawPath(
                dataPath,
                glowPaint
            )

            strokePaint.shader =
                corridorGradient(
                    left,
                    right,
                    color
                )

            strokePaint.alpha =
                laneAlpha

            strokePaint.strokeWidth =
                dp(
                    if (
                        index ==
                        2
                    ) {
                        1.15f
                    } else {
                        0.72f
                    }
                )

            canvas.drawPath(
                dataPath,
                strokePaint
            )
        }

        strokePaint.shader =
            null

        // Tiny fixed "ports" on left/right reinforce input/action semantics.
        repeat(4) {
            index ->

            val y =
                contentBottom *
                    (
                        0.22f +
                            index *
                                0.18f
                        )

            strokePaint.color =
                Color.parseColor(
                    "#30425B"
                )

            strokePaint.alpha =
                170

            strokePaint.strokeWidth =
                dp(0.85f)

            canvas.drawLine(
                width *
                    0.025f,
                y,
                width *
                    0.065f,
                y,
                strokePaint
            )

            canvas.drawLine(
                width *
                    0.935f,
                y,
                width *
                    0.975f,
                y,
                strokePaint
            )
        }

        if (
            stateIndex ==
            STATE_STOP
        ) {
            strokePaint.color =
                palette.secondary

            strokePaint.alpha =
                210

            strokePaint.strokeWidth =
                dp(1.25f)

            canvas.drawLine(
                width *
                    0.10f,
                cy,
                width *
                    0.90f,
                cy,
                strokePaint
            )
        }
    }

    private fun drawCognitiveMatrix(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreWidth: Float,
        coreHeight: Float,
        stateIndex: Int,
        motion: Motion
    ) {
        val breathe =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                motion.breathePeriodMs
                        )
                )
                .toFloat()

        val outerW =
            coreWidth *
                (
                    0.88f +
                        breathe *
                            0.012f
                    )

        val outerH =
            coreHeight *
                (
                    0.88f +
                        breathe *
                            0.012f
                    )

        // Central ambient field.
        fillPaint.shader =
            RadialGradient(
                cx,
                cy,
                min(
                    outerW,
                    outerH
                ) *
                    0.55f,
                intArrayOf(
                    withAlpha(
                        palette.primary,
                        motion.coreGlowAlpha
                    ),
                    withAlpha(
                        palette.secondary,
                        motion.coreGlowAlpha /
                            2
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

        fillPaint.alpha =
            255

        canvas.drawOval(
            RectF(
                cx -
                    outerW *
                        0.48f,
                cy -
                    outerH *
                        0.48f,
                cx +
                    outerW *
                        0.48f,
                cy +
                    outerH *
                        0.48f
            ),
            fillPaint
        )

        fillPaint.shader =
            null

        // Three nested technical polygons.
        val scales =
            floatArrayOf(
                1.0f,
                0.72f,
                0.44f
            )

        scales.forEachIndexed {
            index,
            scale ->

            val rotation =
                (
                    now /
                        (
                            motion.ringRotationPeriodMs *
                                (
                                    1.0 +
                                        index *
                                            0.28
                                    )
                            )
                    ) *
                    motion.direction

            drawHexLattice(
                canvas = canvas,
                cx = cx,
                cy = cy,
                rx =
                    outerW *
                        0.42f *
                        scale,
                ry =
                    outerH *
                        0.42f *
                        scale,
                rotation = rotation,
                color =
                    when(index) {
                        0 ->
                            palette.primary

                        1 ->
                            palette.secondary

                        else ->
                            palette.accent
                    },
                alpha =
                    when(index) {
                        0 ->
                            170

                        1 ->
                            126

                        else ->
                            94
                    },
                widthDp =
                    when(index) {
                        0 ->
                            1.25f

                        1 ->
                            0.92f

                        else ->
                            0.70f
                    }
            )
        }

        drawSegmentRings(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            outerW = outerW,
            outerH = outerH,
            motion = motion
        )

        drawNeuralNodes(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            outerW = outerW,
            outerH = outerH,
            stateIndex = stateIndex,
            motion = motion
        )
    }

    private fun drawHexLattice(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        rx: Float,
        ry: Float,
        rotation: Double,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        corePath.reset()

        val vertices =
            6

        for (
            index in
            0 until vertices
        ) {
            val angle =
                rotation +
                    index *
                        (
                            PI *
                                2.0 /
                                vertices
                            ) -
                    PI /
                        2.0

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    rx

            val y =
                cy +
                    sin(angle)
                        .toFloat() *
                    ry

            if (
                index ==
                0
            ) {
                corePath.moveTo(
                    x,
                    y
                )
            } else {
                corePath.lineTo(
                    x,
                    y
                )
            }
        }

        corePath.close()

        glowPaint.shader =
            null

        glowPaint.color =
            color

        glowPaint.alpha =
            alpha /
                5

        glowPaint.strokeWidth =
            dp(
                widthDp *
                    5f
            )

        canvas.drawPath(
            corePath,
            glowPaint
        )

        strokePaint.shader =
            null

        strokePaint.color =
            color

        strokePaint.alpha =
            alpha

        strokePaint.strokeWidth =
            dp(
                widthDp
            )

        canvas.drawPath(
            corePath,
            strokePaint
        )
    }

    private fun drawSegmentRings(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        outerW: Float,
        outerH: Float,
        motion: Motion
    ) {
        val phase =
            (
                now %
                    motion.segmentPeriodMs
                ).toFloat() /
                motion.segmentPeriodMs.toFloat() *
                360f *
                motion.direction.toFloat()

        val layers =
            4

        repeat(
            layers
        ) {
            layer ->

            val factor =
                1f -
                    layer *
                        0.115f

            ringRect.set(
                cx -
                    outerW *
                        0.47f *
                        factor,
                cy -
                    outerH *
                        0.47f *
                        factor,
                cx +
                    outerW *
                        0.47f *
                        factor,
                cy +
                    outerH *
                        0.47f *
                        factor
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

            strokePaint.shader =
                null

            strokePaint.color =
                color

            strokePaint.alpha =
                74 -
                    layer *
                        9

            strokePaint.strokeWidth =
                dp(
                    0.68f +
                        layer *
                            0.08f
                )

            canvas.drawArc(
                ringRect,
                phase +
                    layer *
                        41f,
                42f +
                    layer *
                        5f,
                false,
                strokePaint
            )

            canvas.drawArc(
                ringRect,
                phase +
                    122f +
                    layer *
                        27f,
                28f +
                    layer *
                        4f,
                false,
                strokePaint
            )

            canvas.drawArc(
                ringRect,
                phase +
                    226f +
                    layer *
                        19f,
                54f -
                    layer *
                        3f,
                false,
                strokePaint
            )
        }
    }

    private fun drawNeuralNodes(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        outerW: Float,
        outerH: Float,
        stateIndex: Int,
        motion: Motion
    ) {
        val count =
            14

        val phase =
            now /
                motion.nodePeriodMs

        repeat(
            count
        ) {
            index ->

            val angle =
                index *
                    (
                        PI *
                            2.0 /
                            count
                        ) +
                    phase *
                        motion.direction

            val rx =
                outerW *
                    (
                        0.18f +
                            (
                                index %
                                    3
                                ) *
                                0.095f
                        )

            val ry =
                outerH *
                    (
                        0.18f +
                            (
                                index %
                                    3
                                ) *
                                0.095f
                        )

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    rx

            val y =
                cy +
                    sin(angle)
                        .toFloat() *
                    ry

            val pulse =
                (
                    0.5 +
                        0.5 *
                            sin(
                                phase *
                                    1.3 +
                                    index *
                                        0.89
                            )
                    )
                    .toFloat()

            val nodeColor =
                when(
                    index %
                        3
                ) {
                    0 ->
                        palette.primary

                    1 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            fillPaint.shader =
                null

            fillPaint.color =
                nodeColor

            fillPaint.alpha =
                (
                    72 +
                        pulse *
                            motion.nodeAlphaRange
                    )
                    .toInt()
                    .coerceIn(
                        0,
                        220
                    )

            canvas.drawCircle(
                x,
                y,
                dp(
                    0.8f +
                        pulse *
                            0.9f
                ),
                fillPaint
            )

            if (
                stateIndex ==
                STATE_THINKING ||
                stateIndex ==
                STATE_EXECUTING
            ) {
                strokePaint.color =
                    nodeColor

                strokePaint.alpha =
                    34

                strokePaint.strokeWidth =
                    dp(0.55f)

                canvas.drawLine(
                    cx,
                    cy,
                    x,
                    y,
                    strokePaint
                )
            }
        }
    }

    private fun drawDecisionSpine(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreWidth: Float,
        coreHeight: Float,
        stateIndex: Int,
        motion: Motion
    ) {
        val top =
            cy -
                coreHeight *
                    0.47f

        val bottom =
            cy +
                coreHeight *
                    0.47f

        strokePaint.shader =
            LinearGradient(
                cx,
                top,
                cx,
                bottom,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        palette.primary,
                        120
                    ),
                    palette.white,
                    withAlpha(
                        palette.secondary,
                        120
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.20f,
                    0.50f,
                    0.80f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        strokePaint.alpha =
            motion.spineAlpha

        strokePaint.strokeWidth =
            dp(0.85f)

        canvas.drawLine(
            cx,
            top,
            cx,
            bottom,
            strokePaint
        )

        glowPaint.shader =
            strokePaint.shader

        glowPaint.alpha =
            motion.spineGlowAlpha

        glowPaint.strokeWidth =
            dp(5.0f)

        canvas.drawLine(
            cx,
            top,
            cx,
            bottom,
            glowPaint
        )

        strokePaint.shader =
            null

        glowPaint.shader =
            null

        val travel =
            (
                (
                    now %
                        motion.spinePulsePeriodMs
                    ).toFloat() /
                    motion.spinePulsePeriodMs.toFloat()
                )
                .coerceIn(
                    0f,
                    1f
                )

        val y =
            top +
                (
                    bottom -
                        top
                    ) *
                travel

        fillPaint.shader =
            RadialGradient(
                cx,
                y,
                dp(14f),
                intArrayOf(
                    Color.WHITE,
                    withAlpha(
                        palette.primary,
                        165
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.35f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fillPaint.alpha =
            if (
                stateIndex ==
                STATE_STOP
            ) {
                55
            } else {
                180
            }

        canvas.drawCircle(
            cx,
            y,
            dp(14f),
            fillPaint
        )

        fillPaint.shader =
            null
    }

    private fun drawFlowPackets(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreWidth: Float,
        coreHeight: Float,
        contentBottom: Float,
        stateIndex: Int,
        motion: Motion
    ) {
        val left =
            width *
                0.035f

        val right =
            width *
                0.965f

        val count =
            motion.packetCount

        if (
            count <=
            0
        ) {
            return
        }

        repeat(
            count
        ) {
            index ->

            val lane =
                (
                    index %
                        5 -
                        2
                    ) *
                    0.145f

            val y =
                cy +
                    coreHeight *
                        lane

            dataPath.reset()

            dataPath.moveTo(
                left,
                y
            )

            dataPath.cubicTo(
                cx -
                    coreWidth *
                        0.65f,
                y -
                    coreHeight *
                        0.09f,
                cx -
                    coreWidth *
                        0.25f,
                y +
                    coreHeight *
                        0.06f,
                cx,
                y
            )

            dataPath.cubicTo(
                cx +
                    coreWidth *
                        0.25f,
                y -
                    coreHeight *
                        0.06f,
                cx +
                    coreWidth *
                        0.65f,
                y +
                    coreHeight *
                        0.09f,
                right,
                y
            )

            pathMeasure.setPath(
                dataPath,
                false
            )

            val length =
                pathMeasure.length

            val direction =
                if (
                    stateIndex ==
                    STATE_ANSWERING ||
                    stateIndex ==
                    STATE_EXECUTING
                ) {
                    1f
                } else if (
                    stateIndex ==
                    STATE_RECOGNITION
                ) {
                    -1f
                } else {
                    motion.direction.toFloat()
                }

            var fraction =
                (
                    now /
                        motion.packetPeriodMs +
                        index /
                            count.toDouble()
                    ) %
                    1.0

            if (
                direction <
                0f
            ) {
                fraction =
                    1.0 -
                        fraction
            }

            pathMeasure.getPosTan(
                (
                    length *
                        fraction
                    )
                    .toFloat(),
                packetPos,
                packetTan
            )

            val packetColor =
                when(
                    index %
                        3
                ) {
                    0 ->
                        palette.primary

                    1 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            fillPaint.shader =
                null

            fillPaint.color =
                packetColor

            fillPaint.alpha =
                motion.packetAlpha

            canvas.drawCircle(
                packetPos[0],
                packetPos[1],
                dp(
                    if (
                        index %
                            4 ==
                            0
                    ) {
                        2.0f
                    } else {
                        1.25f
                    }
                ),
                fillPaint
            )
        }
    }

    private fun drawStateSignature(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        coreWidth: Float,
        coreHeight: Float,
        stateIndex: Int,
        motion: Motion
    ) {
        when(
            stateIndex
        ) {
            STATE_WAITING -> {
                // Quiet central readiness pulse.
                drawCorePulse(
                    canvas,
                    now,
                    cx,
                    cy,
                    min(
                        coreWidth,
                        coreHeight
                    ) *
                        0.10f,
                    palette.primary,
                    72,
                    motion.breathePeriodMs
                )
            }

            STATE_RECOGNITION -> {
                // Input emphasis: left-edge scan bars.
                val bars =
                    14

                repeat(
                    bars
                ) {
                    index ->

                    val x =
                        width *
                            (
                                0.055f +
                                    index *
                                        0.010f
                                )

                    val pulse =
                        abs(
                            sin(
                                now /
                                    180.0 +
                                    index *
                                        0.74
                            )
                                .toFloat()
                        )

                    strokePaint.color =
                        palette.primary

                    strokePaint.alpha =
                        (
                            48 +
                                pulse *
                                    120f
                            )
                            .toInt()

                    strokePaint.strokeWidth =
                        dp(0.8f)

                    val half =
                        dp(
                            3f +
                                pulse *
                                    18f
                        )

                    canvas.drawLine(
                        x,
                        cy -
                            half,
                        x,
                        cy +
                            half,
                        strokePaint
                    )
                }
            }

            STATE_THINKING -> {
                drawCorePulse(
                    canvas,
                    now,
                    cx,
                    cy,
                    min(
                        coreWidth,
                        coreHeight
                    ) *
                        0.15f,
                    palette.accent,
                    92,
                    620.0
                )
            }

            STATE_EXECUTING -> {
                // Right-side action rail.
                val y =
                    cy

                strokePaint.color =
                    palette.secondary

                strokePaint.alpha =
                    205

                strokePaint.strokeWidth =
                    dp(1.1f)

                canvas.drawLine(
                    cx +
                        coreWidth *
                            0.38f,
                    y,
                    width *
                        0.95f,
                    y,
                    strokePaint
                )
            }

            STATE_ANSWERING -> {
                // Output emphasis: right-side wave bursts.
                repeat(
                    10
                ) {
                    index ->

                    val x =
                        width *
                            (
                                0.83f +
                                    index *
                                        0.011f
                                )

                    val pulse =
                        abs(
                            sin(
                                now /
                                    165.0 +
                                    index *
                                        0.83
                            )
                                .toFloat()
                        )

                    strokePaint.color =
                        palette.primary

                    strokePaint.alpha =
                        (
                            50 +
                                pulse *
                                    135f
                            )
                            .toInt()

                    strokePaint.strokeWidth =
                        dp(0.85f)

                    val half =
                        dp(
                            3f +
                                pulse *
                                    20f
                        )

                    canvas.drawLine(
                        x,
                        cy -
                            half,
                        x,
                        cy +
                            half,
                        strokePaint
                    )
                }
            }

            STATE_STOP -> {
                // Deliberate interruption mark.
                strokePaint.color =
                    palette.primary

                strokePaint.alpha =
                    225

                strokePaint.strokeWidth =
                    dp(1.4f)

                val halfW =
                    coreWidth *
                        0.20f

                val halfH =
                    coreHeight *
                        0.20f

                canvas.drawLine(
                    cx -
                        halfW,
                    cy -
                        halfH,
                    cx +
                        halfW,
                    cy +
                        halfH,
                    strokePaint
                )

                canvas.drawLine(
                    cx +
                        halfW,
                    cy -
                        halfH,
                    cx -
                        halfW,
                    cy +
                        halfH,
                    strokePaint
                )
            }
        }
    }

    private fun drawCorePulse(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        color: Int,
        alpha: Int,
        periodMs: Double
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                periodMs
                        )
                )
                .toFloat()

        fillPaint.shader =
            RadialGradient(
                cx,
                cy,
                radius *
                    (
                        0.85f +
                            pulse *
                                0.20f
                        ),
                intArrayOf(
                    withAlpha(
                        Color.WHITE,
                        alpha
                    ),
                    withAlpha(
                        color,
                        alpha
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.32f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fillPaint.alpha =
            255

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.85f +
                        pulse *
                            0.20f
                    ),
            fillPaint
        )

        fillPaint.shader =
            null
    }

    private fun drawStageRail(
        canvas: Canvas,
        activeIndex: Int,
        top: Float,
        height: Float
    ) {
        fillPaint.shader =
            null

        fillPaint.color =
            Color.parseColor(
                "#02050A"
            )

        fillPaint.alpha =
            244

        canvas.drawRect(
            0f,
            top,
            width.toFloat(),
            height.toFloat(),
            fillPaint
        )

        strokePaint.shader =
            null

        strokePaint.color =
            Color.parseColor(
                "#243147"
            )

        strokePaint.alpha =
            220

        strokePaint.strokeWidth =
            dp(0.75f)

        canvas.drawLine(
            width *
                0.035f,
            top,
            width *
                0.965f,
            top,
            strokePaint
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
                0.070f

        val right =
            width *
                0.930f

        val step =
            (
                right -
                    left
                ) /
                (
                    labels.size -
                        1
                    )

        val nodeY =
            top +
                height *
                    0.36f

        val labelY =
            top +
                height *
                    0.81f

        strokePaint.color =
            Color.parseColor(
                "#2C384D"
            )

        strokePaint.alpha =
            175

        strokePaint.strokeWidth =
            dp(0.70f)

        canvas.drawLine(
            left,
            nodeY,
            right,
            nodeY,
            strokePaint
        )

        labels.indices.forEach {
            index ->

            val x =
                left +
                    step *
                        index

            val active =
                index ==
                    activeIndex

            val color =
                paletteFor(
                    index
                ).primary

            if (active) {
                fillPaint.color =
                    color

                fillPaint.alpha =
                    34

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(16f),
                    fillPaint
                )

                strokePaint.color =
                    color

                strokePaint.alpha =
                    255

                strokePaint.strokeWidth =
                    dp(1.20f)

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(13.5f),
                    strokePaint
                )

                fillPaint.color =
                    color

                fillPaint.alpha =
                    255

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(2.7f),
                    fillPaint
                )
            } else {
                strokePaint.color =
                    Color.parseColor(
                        "#445065"
                    )

                strokePaint.alpha =
                    205

                strokePaint.strokeWidth =
                    dp(0.85f)

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(11.8f),
                    strokePaint
                )

                fillPaint.color =
                    Color.parseColor(
                        "#69758A"
                    )

                fillPaint.alpha =
                    200

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(1.9f),
                    fillPaint
                )
            }

            textPaint.textSize =
                dp(
                    if (active) {
                        9.2f
                    } else {
                        8.6f
                    }
                )

            textPaint.typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    if (active) {
                        Typeface.BOLD
                    } else {
                        Typeface.NORMAL
                    }
                )

            textPaint.color =
                if (active) {
                    color
                } else {
                    Color.parseColor(
                        "#8490A3"
                    )
                }

            textPaint.alpha =
                if (active) {
                    255
                } else {
                    225
                }

            canvas.drawText(
                labels[index],
                x,
                labelY,
                textPaint
            )
        }
    }

    private fun corridorGradient(
        left: Float,
        right: Float,
        color: Int
    ): LinearGradient {
        return LinearGradient(
            left,
            0f,
            right,
            0f,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(
                    color,
                    110
                ),
                color,
                Color.WHITE,
                color,
                withAlpha(
                    color,
                    110
                ),
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f,
                0.12f,
                0.33f,
                0.50f,
                0.67f,
                0.88f,
                1f
            ),
            Shader.TileMode.CLAMP
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
        stateIndex: Int
    ): Palette {
        return when(stateIndex) {
            STATE_WAITING ->
                Palette.waiting()

            STATE_RECOGNITION ->
                Palette.recognition()

            STATE_THINKING ->
                Palette.thinking()

            STATE_EXECUTING ->
                Palette.executing()

            STATE_ANSWERING ->
                Palette.answering()

            STATE_STOP ->
                Palette.stop()

            else ->
                Palette.waiting()
        }
    }

    private fun motionFor(
        stateIndex: Int
    ): Motion {
        return when(stateIndex) {
            STATE_WAITING ->
                Motion(
                    frameDelayMs = 40L,
                    scanPeriodMs = 5200L,
                    scanAlpha = 80,
                    flowPeriodMs = 840.0,
                    centerLaneAlpha = 142,
                    sideLaneAlpha = 68,
                    breathePeriodMs = 1180.0,
                    coreGlowAlpha = 54,
                    ringRotationPeriodMs = 8600.0,
                    segmentPeriodMs = 7600L,
                    nodePeriodMs = 1550.0,
                    nodeAlphaRange = 74f,
                    direction = 1.0,
                    spineAlpha = 112,
                    spineGlowAlpha = 28,
                    spinePulsePeriodMs = 3300L,
                    packetCount = 7,
                    packetPeriodMs = 2500.0,
                    packetAlpha = 150
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 30L,
                    scanPeriodMs = 2400L,
                    scanAlpha = 118,
                    flowPeriodMs = 510.0,
                    centerLaneAlpha = 190,
                    sideLaneAlpha = 104,
                    breathePeriodMs = 760.0,
                    coreGlowAlpha = 76,
                    ringRotationPeriodMs = 5600.0,
                    segmentPeriodMs = 4200L,
                    nodePeriodMs = 980.0,
                    nodeAlphaRange = 110f,
                    direction = -1.0,
                    spineAlpha = 158,
                    spineGlowAlpha = 42,
                    spinePulsePeriodMs = 1800L,
                    packetCount = 12,
                    packetPeriodMs = 1350.0,
                    packetAlpha = 190
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 32L,
                    scanPeriodMs = 3600L,
                    scanAlpha = 96,
                    flowPeriodMs = 660.0,
                    centerLaneAlpha = 168,
                    sideLaneAlpha = 90,
                    breathePeriodMs = 860.0,
                    coreGlowAlpha = 88,
                    ringRotationPeriodMs = 4700.0,
                    segmentPeriodMs = 3900L,
                    nodePeriodMs = 820.0,
                    nodeAlphaRange = 126f,
                    direction = -1.0,
                    spineAlpha = 174,
                    spineGlowAlpha = 48,
                    spinePulsePeriodMs = 1550L,
                    packetCount = 14,
                    packetPeriodMs = 1600.0,
                    packetAlpha = 202
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 28L,
                    scanPeriodMs = 1900L,
                    scanAlpha = 126,
                    flowPeriodMs = 430.0,
                    centerLaneAlpha = 212,
                    sideLaneAlpha = 116,
                    breathePeriodMs = 610.0,
                    coreGlowAlpha = 78,
                    ringRotationPeriodMs = 3400.0,
                    segmentPeriodMs = 3100L,
                    nodePeriodMs = 720.0,
                    nodeAlphaRange = 132f,
                    direction = 1.0,
                    spineAlpha = 190,
                    spineGlowAlpha = 54,
                    spinePulsePeriodMs = 1250L,
                    packetCount = 16,
                    packetPeriodMs = 1050.0,
                    packetAlpha = 215
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 29L,
                    scanPeriodMs = 2200L,
                    scanAlpha = 112,
                    flowPeriodMs = 470.0,
                    centerLaneAlpha = 202,
                    sideLaneAlpha = 108,
                    breathePeriodMs = 660.0,
                    coreGlowAlpha = 84,
                    ringRotationPeriodMs = 3900.0,
                    segmentPeriodMs = 3300L,
                    nodePeriodMs = 760.0,
                    nodeAlphaRange = 128f,
                    direction = 1.0,
                    spineAlpha = 182,
                    spineGlowAlpha = 50,
                    spinePulsePeriodMs = 1380L,
                    packetCount = 15,
                    packetPeriodMs = 1120.0,
                    packetAlpha = 210
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 62L,
                    scanPeriodMs = 8200L,
                    scanAlpha = 42,
                    flowPeriodMs = 1200.0,
                    centerLaneAlpha = 86,
                    sideLaneAlpha = 38,
                    breathePeriodMs = 1800.0,
                    coreGlowAlpha = 42,
                    ringRotationPeriodMs = 13200.0,
                    segmentPeriodMs = 11800L,
                    nodePeriodMs = 2300.0,
                    nodeAlphaRange = 44f,
                    direction = -1.0,
                    spineAlpha = 82,
                    spineGlowAlpha = 18,
                    spinePulsePeriodMs = 4800L,
                    packetCount = 3,
                    packetPeriodMs = 3800.0,
                    packetAlpha = 98
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
            Color.red(
                color
            ),
            Color.green(
                color
            ),
            Color.blue(
                color
            )
        )
    }

    private fun dp(
        value: Float
    ): Float {
        return value *
            density
    }

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val white: Int
    ) {
        companion object {

            fun waiting():
                Palette =
                Palette(
                    primary =
                        Color.parseColor(
                            "#19DCE7"
                        ),
                    secondary =
                        Color.parseColor(
                            "#00AFC4"
                        ),
                    accent =
                        Color.parseColor(
                            "#8AF5FF"
                        ),
                    white =
                        Color.parseColor(
                            "#F4FEFF"
                        )
                )

            fun recognition():
                Palette =
                Palette(
                    primary =
                        Color.parseColor(
                            "#1687FF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#2E8BFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#A6E8FF"
                        ),
                    white =
                        Color.parseColor(
                            "#F6FBFF"
                        )
                )

            fun thinking():
                Palette =
                Palette(
                    primary =
                        Color.parseColor(
                            "#633BFF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#8A4DFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#C2B0FF"
                        ),
                    white =
                        Color.parseColor(
                            "#FBF9FF"
                        )
                )

            fun executing():
                Palette =
                Palette(
                    primary =
                        Color.parseColor(
                            "#28E06E"
                        ),
                    secondary =
                        Color.parseColor(
                            "#20E6C8"
                        ),
                    accent =
                        Color.parseColor(
                            "#9AF8D8"
                        ),
                    white =
                        Color.parseColor(
                            "#F5FFF9"
                        )
                )

            fun answering():
                Palette =
                Palette(
                    primary =
                        Color.parseColor(
                            "#E623C7"
                        ),
                    secondary =
                        Color.parseColor(
                            "#8B4CFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#F0B1FF"
                        ),
                    white =
                        Color.parseColor(
                            "#FFF7FF"
                        )
                )

            fun stop():
                Palette =
                Palette(
                    primary =
                        Color.parseColor(
                            "#FF3B22"
                        ),
                    secondary =
                        Color.parseColor(
                            "#FF6A2A"
                        ),
                    accent =
                        Color.parseColor(
                            "#FF9C7A"
                        ),
                    white =
                        Color.parseColor(
                            "#FFF8F5"
                        )
                )
        }
    }

    private data class Motion(
        val frameDelayMs: Long,
        val scanPeriodMs: Long,
        val scanAlpha: Int,
        val flowPeriodMs: Double,
        val centerLaneAlpha: Int,
        val sideLaneAlpha: Int,
        val breathePeriodMs: Double,
        val coreGlowAlpha: Int,
        val ringRotationPeriodMs: Double,
        val segmentPeriodMs: Long,
        val nodePeriodMs: Double,
        val nodeAlphaRange: Float,
        val direction: Double,
        val spineAlpha: Int,
        val spineGlowAlpha: Int,
        val spinePulsePeriodMs: Long,
        val packetCount: Int,
        val packetPeriodMs: Double,
        val packetAlpha: Int
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
