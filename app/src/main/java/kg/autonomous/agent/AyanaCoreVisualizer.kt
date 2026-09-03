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
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v8.0 — AGENT OPERATIONS CORE.
 *
 * Strong redesign after v7.0 device rejection.
 *
 * Visual direction:
 * - bright professional operations HUD, not a decorative orb;
 * - large central decision core;
 * - perception lanes enter from the left;
 * - action/result lanes leave to the right;
 * - visible rotating segmented rings;
 * - live inference lattice and routing packets;
 * - high-contrast scan / decision spine;
 * - six factual runtime states:
 *   Ожидание / Распознавание / Думаю / Выполняю / Отвечаю / Стоп.
 *
 * Device layout hardening:
 * - the current MainActivity v7.8 gives too little width to the left status block
 *   for long state names such as «Распознаю команду»;
 * - this component performs one bounded host-layout normalization on attach:
 *   it gives the status column a little more weight and caps the large state
 *   title to 2 lines / 31sp;
 * - no navigation, command, accessibility, ORB or service behavior is changed.
 *
 * No bitmap/PNG resources are used.
 *
 * Public integration contract remains unchanged:
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

    private val text =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }

    private val path =
        Path()

    private val finePath =
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

        val p =
            paletteFor(state)

        val m =
            motionFor(state)

        val now =
            SystemClock.uptimeMillis()

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val compact =
            h <
                dp(180f)

        val railHeight =
            if (compact) {
                0f
            } else {
                min(
                    dp(48f),
                    h *
                        0.16f
                )
            }

        // The rail overlays the bottom; it does not reduce the central core.
        val railTop =
            h -
                railHeight

        val cx =
            w *
                0.50f

        val cy =
            h *
                if (compact) {
                    0.50f
                } else {
                    0.43f
                }

        val coreRadius =
            min(
                w *
                    0.265f,
                h *
                    0.39f
            )
                .coerceAtLeast(
                    dp(46f)
                )

        drawPanelBackground(
            canvas = canvas,
            now = now,
            p = p,
            m = m,
            railTop = railTop
        )

        drawPerceptionInput(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = coreRadius,
            p = p,
            m = m,
            state = state
        )

        drawCoreField(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = coreRadius,
            p = p,
            m = m,
            state = state
        )

        drawActionOutput(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = coreRadius,
            p = p,
            m = m,
            state = state
        )

        drawPackets(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = coreRadius,
            p = p,
            m = m,
            state = state
        )

        drawStateOverlay(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = coreRadius,
            p = p,
            m = m,
            state = state
        )

        if (
            railHeight >
            0f
        ) {
            drawStateRail(
                canvas = canvas,
                top = railTop,
                height = railHeight,
                active = state
            )
        }

        if (attached) {
            postInvalidateDelayed(
                m.frameDelayMs
            )
        }
    }

    /**
     * Device screenshot showed a genuine MainActivity layout issue:
     * the status title can wrap to three lines while visualizer gets most weight.
     *
     * This compatibility normalization is intentionally narrow and bounded to
     * the parent card that already owns this visualizer.
     */
    private fun normalizeHostLayoutOnce() {
        if (
            hostNormalized
        ) {
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
            card.childCount <
            2
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
            ownParams !=
            null &&
            copyParams !=
            null
        ) {
            // v7.8 was approximately 0.58 : 1.78.
            // v8 gives long factual state names enough room without collapsing
            // the core into a small right-side box.
            copyParams.weight =
                0.78f

            ownParams.weight =
                1.58f

            copy.layoutParams =
                copyParams

            layoutParams =
                ownParams
        }

        // In v7.8 the large title is the second child of the copy column.
        val title =
            copy
                .getChildAt(
                    1
                ) as?
                TextView

        title?.apply {
            textSize =
                31f

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

    private fun drawPanelBackground(
        canvas: Canvas,
        now: Long,
        p: Palette,
        m: Motion,
        railTop: Float
    ) {
        // Deep matte panel.
        fill.shader =
            LinearGradient(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                intArrayOf(
                    Color.parseColor(
                        "#02050A"
                    ),
                    Color.parseColor(
                        "#050B14"
                    ),
                    Color.parseColor(
                        "#02050A"
                    )
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

        // High-visibility technical grid.
        stroke.shader =
            null

        stroke.color =
            Color.parseColor(
                "#1B2C42"
            )

        stroke.alpha =
            118

        stroke.strokeWidth =
            dp(0.48f)

        val columns =
            16

        val rows =
            8

        for (
            i in
            1 until columns
        ) {
            val x =
                width *
                    i /
                    columns.toFloat()

            canvas.drawLine(
                x,
                dp(8f),
                x,
                railTop -
                    dp(6f),
                stroke
            )
        }

        for (
            i in
            1 until rows
        ) {
            val y =
                railTop *
                    i /
                    rows.toFloat()

            canvas.drawLine(
                dp(8f),
                y,
                width -
                    dp(8f),
                y,
                stroke
            )
        }

        val scan =
            (
                now %
                    m.scanPeriodMs
                ).toFloat() /
                m.scanPeriodMs.toFloat()

        val scanX =
            width *
                (
                    0.05f +
                        scan *
                            0.90f
                    )

        fill.shader =
            LinearGradient(
                scanX -
                    dp(34f),
                0f,
                scanX +
                    dp(34f),
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        p.primary,
                        28
                    ),
                    withAlpha(
                        p.primary,
                        120
                    ),
                    withAlpha(
                        p.primary,
                        28
                    ),
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

        fill.alpha =
            m.scanAlpha

        canvas.drawRect(
            scanX -
                dp(34f),
            dp(6f),
            scanX +
                dp(34f),
            railTop -
                dp(6f),
            fill
        )

        fill.shader =
            null

        // Corner registration marks.
        stroke.color =
            p.primary

        stroke.alpha =
            115

        stroke.strokeWidth =
            dp(0.9f)

        val l =
            dp(18f)

        canvas.drawLine(
            dp(10f),
            dp(10f),
            dp(10f) + l,
            dp(10f),
            stroke
        )

        canvas.drawLine(
            dp(10f),
            dp(10f),
            dp(10f),
            dp(10f) + l,
            stroke
        )

        canvas.drawLine(
            width - dp(10f) - l,
            dp(10f),
            width - dp(10f),
            dp(10f),
            stroke
        )

        canvas.drawLine(
            width - dp(10f),
            dp(10f),
            width - dp(10f),
            dp(10f) + l,
            stroke
        )
    }

    private fun drawPerceptionInput(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        p: Palette,
        m: Motion,
        state: Int
    ) {
        val left =
            width *
                0.035f

        val coreLeft =
            cx -
                radius *
                    0.88f

        val phase =
            now /
                m.flowPeriodMs

        // Perception wave - intentionally strong enough to remain visible.
        path.reset()

        val samples =
            72

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
                        coreLeft -
                            left
                        ) *
                    t

            val envelope =
                sin(
                    PI *
                        t
                )
                    .toFloat()
                    .coerceAtLeast(
                        0f
                    )

            val ampMultiplier =
                if (
                    state ==
                    STATE_RECOGNITION
                ) {
                    1.65f
                } else {
                    1f
                }

            val y =
                cy +
                    (
                        sin(
                            t *
                                PI *
                                10.0 +
                                phase
                        )
                            .toFloat() *
                            0.76f +
                            sin(
                                t *
                                    PI *
                                    27.0 -
                                    phase *
                                        0.81
                            )
                                .toFloat() *
                                0.24f
                        ) *
                    radius *
                    0.13f *
                    envelope *
                    ampMultiplier

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

        glow.shader =
            corridorGradient(
                left,
                coreLeft,
                p.primary
            )

        glow.alpha =
            m.waveGlowAlpha

        glow.strokeWidth =
            dp(7.0f)

        canvas.drawPath(
            path,
            glow
        )

        stroke.shader =
            corridorGradient(
                left,
                coreLeft,
                p.primary
            )

        stroke.alpha =
            m.waveAlpha

        stroke.strokeWidth =
            dp(1.55f)

        canvas.drawPath(
            path,
            stroke
        )

        stroke.shader =
            null

        // Input bus rails.
        repeat(
            4
        ) {
            lane ->

            val y =
                cy +
                    (
                        lane -
                            1.5f
                        ) *
                    radius *
                    0.22f

            stroke.color =
                p.secondary

            stroke.alpha =
                96

            stroke.strokeWidth =
                dp(0.72f)

            canvas.drawLine(
                left,
                y,
                coreLeft -
                    dp(8f),
                y,
                stroke
            )
        }
    }

    private fun drawCoreField(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        p: Palette,
        m: Motion,
        state: Int
    ) {
        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                m.breatheMs
                        )
                )
                .toFloat()

        // Bright central ambient lens.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius *
                    1.08f,
                intArrayOf(
                    withAlpha(
                        p.white,
                        125
                    ),
                    withAlpha(
                        p.primary,
                        m.coreGlowAlpha
                    ),
                    withAlpha(
                        p.secondary,
                        m.coreGlowAlpha /
                            2
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.18f,
                    0.55f,
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
                    0.98f +
                        pulse *
                            0.015f
                    ),
            fill
        )

        fill.shader =
            null

        // Six segmented rings.
        repeat(
            6
        ) {
            layer ->

            val rr =
                radius *
                    (
                        0.97f -
                            layer *
                                0.105f
                        )

            ring.set(
                cx -
                    rr,
                cy -
                    rr,
                cx +
                    rr,
                cy +
                    rr
            )

            val direction =
                if (
                    layer %
                    2 ==
                    0
                ) {
                    1f
                } else {
                    -1f
                }

            val phase =
                (
                    now %
                        (
                            m.ringPeriodMs +
                                layer *
                                    540L
                            )
                    ).toFloat() /
                    (
                        m.ringPeriodMs +
                            layer *
                                540L
                        ).toFloat() *
                    360f *
                    direction

            val layerColor =
                when(
                    layer %
                        3
                ) {
                    0 ->
                        p.primary

                    1 ->
                        p.secondary

                    else ->
                        p.accent
                }

            glow.shader =
                null

            glow.color =
                layerColor

            glow.alpha =
                42

            glow.strokeWidth =
                dp(
                    4.2f -
                        layer *
                            0.38f
                )

            canvas.drawArc(
                ring,
                phase +
                    12f,
                58f,
                false,
                glow
            )

            canvas.drawArc(
                ring,
                phase +
                    118f,
                37f,
                false,
                glow
            )

            canvas.drawArc(
                ring,
                phase +
                    216f,
                74f,
                false,
                glow
            )

            stroke.color =
                layerColor

            stroke.alpha =
                225 -
                    layer *
                        22

            stroke.strokeWidth =
                dp(
                    1.35f -
                        layer *
                            0.10f
                )

            canvas.drawArc(
                ring,
                phase +
                    12f,
                58f,
                false,
                stroke
            )

            canvas.drawArc(
                ring,
                phase +
                    118f,
                37f,
                false,
                stroke
            )

            canvas.drawArc(
                ring,
                phase +
                    216f,
                74f,
                false,
                stroke
            )
        }

        // Angular inference lattice.
        repeat(
            3
        ) {
            layer ->

            val rr =
                radius *
                    (
                        0.64f -
                            layer *
                                0.13f
                        )

            val rotation =
                now /
                    (
                        1800.0 +
                            layer *
                                430.0
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

            drawPolygon(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius = rr,
                sides =
                    if (
                        layer ==
                        0
                    ) {
                        8
                    } else {
                        6
                    },
                rotation = rotation,
                color =
                    when(layer) {
                        0 ->
                            p.primary

                        1 ->
                            p.secondary

                        else ->
                            p.accent
                    },
                alpha =
                    198 -
                        layer *
                            36,
                widthDp =
                    1.15f -
                        layer *
                            0.17f
            )
        }

        // Central decision nucleus.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius *
                    0.23f,
                intArrayOf(
                    Color.WHITE,
                    p.primary,
                    withAlpha(
                        p.secondary,
                        155
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.20f,
                    0.55f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fill.alpha =
            245

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.18f +
                        pulse *
                            0.015f
                    ),
            fill
        )

        fill.shader =
            null

        // Neural nodes and inference edges.
        val nodes =
            18

        repeat(
            nodes
        ) {
            i ->

            val ringIndex =
                1 +
                    (
                        i %
                            3
                        )

            val rr =
                radius *
                    (
                        0.27f +
                            ringIndex *
                                0.14f
                        )

            val angle =
                i *
                    (
                        PI *
                            2.0 /
                            nodes
                        ) +
                    now /
                        m.nodePeriodMs *
                        (
                            if (
                                i %
                                2 ==
                                0
                            ) {
                                1.0
                            } else {
                                -1.0
                            }
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
                    0.74f

            val nodeColor =
                when(
                    i %
                        3
                ) {
                    0 ->
                        p.primary

                    1 ->
                        p.secondary

                    else ->
                        p.accent
                }

            stroke.color =
                nodeColor

            stroke.alpha =
                if (
                    state ==
                    STATE_THINKING ||
                    state ==
                    STATE_EXECUTING
                ) {
                    88
                } else {
                    42
                }

            stroke.strokeWidth =
                dp(0.55f)

            canvas.drawLine(
                cx,
                cy,
                x,
                y,
                stroke
            )

            fill.color =
                nodeColor

            fill.alpha =
                215

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (
                        i %
                        4 ==
                        0
                    ) {
                        2.0f
                    } else {
                        1.25f
                    }
                ),
                fill
            )
        }

        // High-contrast decision spine.
        stroke.shader =
            LinearGradient(
                cx,
                cy -
                    radius,
                cx,
                cy +
                    radius,
                intArrayOf(
                    Color.TRANSPARENT,
                    p.primary,
                    Color.WHITE,
                    p.secondary,
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

        stroke.alpha =
            m.spineAlpha

        stroke.strokeWidth =
            dp(0.95f)

        canvas.drawLine(
            cx,
            cy -
                radius *
                    0.93f,
            cx,
            cy +
                radius *
                    0.93f,
            stroke
        )

        glow.shader =
            stroke.shader

        glow.alpha =
            46

        glow.strokeWidth =
            dp(5f)

        canvas.drawLine(
            cx,
            cy -
                radius *
                    0.93f,
            cx,
            cy +
                radius *
                    0.93f,
            glow
        )

        stroke.shader =
            null

        glow.shader =
            null
    }

    private fun drawActionOutput(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        p: Palette,
        m: Motion,
        state: Int
    ) {
        val coreRight =
            cx +
                radius *
                    0.88f

        val right =
            width *
                0.965f

        val phase =
            now /
                m.flowPeriodMs

        path.reset()

        val samples =
            72

        for (
            i in
            0..samples
        ) {
            val t =
                i /
                    samples.toFloat()

            val x =
                coreRight +
                    (
                        right -
                            coreRight
                        ) *
                    t

            val envelope =
                sin(
                    PI *
                        t
                )
                    .toFloat()
                    .coerceAtLeast(
                        0f
                    )

            val ampMultiplier =
                when(state) {
                    STATE_EXECUTING ->
                        1.55f

                    STATE_ANSWERING ->
                        1.78f

                    else ->
                        1f
                }

            val y =
                cy +
                    (
                        sin(
                            t *
                                PI *
                                9.0 +
                                phase *
                                    1.07
                        )
                            .toFloat() *
                            0.72f +
                            sin(
                                t *
                                    PI *
                                    24.0 -
                                    phase *
                                        0.76
                            )
                                .toFloat() *
                                0.28f
                        ) *
                    radius *
                    0.13f *
                    envelope *
                    ampMultiplier

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

        glow.shader =
            corridorGradient(
                coreRight,
                right,
                p.secondary
            )

        glow.alpha =
            m.waveGlowAlpha

        glow.strokeWidth =
            dp(7f)

        canvas.drawPath(
            path,
            glow
        )

        stroke.shader =
            corridorGradient(
                coreRight,
                right,
                p.secondary
            )

        stroke.alpha =
            m.waveAlpha

        stroke.strokeWidth =
            dp(1.55f)

        canvas.drawPath(
            path,
            stroke
        )

        stroke.shader =
            null

        repeat(
            4
        ) {
            lane ->

            val y =
                cy +
                    (
                        lane -
                            1.5f
                        ) *
                    radius *
                    0.22f

            stroke.color =
                p.primary

            stroke.alpha =
                92

            stroke.strokeWidth =
                dp(0.72f)

            canvas.drawLine(
                coreRight +
                    dp(8f),
                y,
                right,
                y,
                stroke
            )
        }
    }

    private fun drawPackets(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        p: Palette,
        m: Motion,
        state: Int
    ) {
        val left =
            width *
                0.045f

        val right =
            width *
                0.955f

        val direction =
            when(state) {
                STATE_RECOGNITION ->
                    -1f

                STATE_EXECUTING,
                STATE_ANSWERING ->
                    1f

                else ->
                    m.direction
            }

        repeat(
            m.packetCount
        ) {
            i ->

            val lane =
                (
                    i %
                        5 -
                        2
                    ) *
                    0.15f

            val y =
                cy +
                    radius *
                        lane

            val raw =
                (
                    now /
                        m.packetPeriodMs +
                        i /
                            m.packetCount.toDouble()
                    ) %
                    1.0

            val t =
                if (
                    direction >=
                    0f
                ) {
                    raw
                } else {
                    1.0 -
                        raw
                }

            val x =
                left +
                    (
                        right -
                            left
                        ) *
                    t.toFloat()

            val color =
                when(
                    i %
                        3
                ) {
                    0 ->
                        p.primary

                    1 ->
                        p.secondary

                    else ->
                        p.accent
                }

            fill.shader =
                null

            fill.color =
                color

            fill.alpha =
                m.packetAlpha

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
                        1.35f
                    }
                ),
                fill
            )
        }
    }

    private fun drawStateOverlay(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        p: Palette,
        m: Motion,
        state: Int
    ) {
        when(state) {
            STATE_WAITING -> {
                drawPulseRing(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius *
                        0.25f,
                    p.primary,
                    105,
                    1050.0
                )
            }

            STATE_RECOGNITION -> {
                drawEdgeBars(
                    canvas = canvas,
                    now = now,
                    leftSide = true,
                    color = p.primary,
                    strength = 1.0f
                )
            }

            STATE_THINKING -> {
                drawPulseRing(
                    canvas,
                    now,
                    cx,
                    cy,
                    radius *
                        0.42f,
                    p.accent,
                    135,
                    620.0
                )
            }

            STATE_EXECUTING -> {
                // Directed action beam.
                glow.color =
                    p.secondary

                glow.alpha =
                    72

                glow.strokeWidth =
                    dp(8f)

                canvas.drawLine(
                    cx +
                        radius *
                            0.55f,
                    cy,
                    width *
                        0.96f,
                    cy,
                    glow
                )

                stroke.color =
                    Color.WHITE

                stroke.alpha =
                    200

                stroke.strokeWidth =
                    dp(0.8f)

                canvas.drawLine(
                    cx +
                        radius *
                            0.55f,
                    cy,
                    width *
                        0.96f,
                    cy,
                    stroke
                )
            }

            STATE_ANSWERING -> {
                drawEdgeBars(
                    canvas = canvas,
                    now = now,
                    leftSide = false,
                    color = p.primary,
                    strength = 1.15f
                )
            }

            STATE_STOP -> {
                // Strong unambiguous stop signature.
                stroke.shader =
                    null

                stroke.color =
                    p.primary

                stroke.alpha =
                    245

                stroke.strokeWidth =
                    dp(2.2f)

                val d =
                    radius *
                        0.32f

                canvas.drawLine(
                    cx -
                        d,
                    cy -
                        d,
                    cx +
                        d,
                    cy +
                        d,
                    stroke
                )

                canvas.drawLine(
                    cx +
                        d,
                    cy -
                        d,
                    cx -
                        d,
                    cy +
                        d,
                    stroke
                )

                fill.color =
                    p.primary

                fill.alpha =
                    42

                canvas.drawCircle(
                    cx,
                    cy,
                    radius *
                        0.48f,
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
                            0.035f +
                                i *
                                    0.011f
                            )
                } else {
                    width *
                        (
                            0.845f +
                                i *
                                    0.011f
                            )
                }

            val pulse =
                abs(
                    sin(
                        now /
                            170.0 +
                            i *
                                0.82
                    )
                        .toFloat()
                )

            val half =
                dp(
                    3f +
                        pulse *
                            19f *
                            strength
                )

            stroke.color =
                color

            stroke.alpha =
                (
                    60 +
                        pulse *
                            160f
                    )
                    .toInt()
                    .coerceAtMost(
                        220
                    )

            stroke.strokeWidth =
                dp(0.85f)

            canvas.drawLine(
                x,
                height *
                    0.43f -
                    half,
                x,
                height *
                    0.43f +
                    half,
                stroke
            )
        }
    }

    private fun drawPulseRing(
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

        val rr =
            radius *
                (
                    0.88f +
                        pulse *
                            0.18f
                    )

        ring.set(
            cx -
                rr,
            cy -
                rr,
            cx +
                rr,
            cy +
                rr
        )

        glow.shader =
            null

        glow.color =
            color

        glow.alpha =
            (
                alpha *
                    0.30f
                )
                .toInt()

        glow.strokeWidth =
            dp(7f)

        canvas.drawOval(
            ring,
            glow
        )

        stroke.color =
            color

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(1.2f)

        canvas.drawOval(
            ring,
            stroke
        )
    }

    private fun drawStateRail(
        canvas: Canvas,
        top: Float,
        height: Float,
        active: Int
    ) {
        fill.shader =
            null

        fill.color =
            Color.parseColor(
                "#02050A"
            )

        fill.alpha =
            244

        canvas.drawRect(
            0f,
            top,
            width.toFloat(),
            height.toFloat(),
            fill
        )

        stroke.shader =
            null

        stroke.color =
            Color.parseColor(
                "#29374D"
            )

        stroke.alpha =
            215

        stroke.strokeWidth =
            dp(0.75f)

        canvas.drawLine(
            width *
                0.035f,
            top,
            width *
                0.965f,
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
            width *
                0.065f

        val right =
            width *
                0.935f

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
                    0.34f

        val labelY =
            top +
                height *
                    0.80f

        stroke.color =
            Color.parseColor(
                "#334158"
            )

        stroke.alpha =
            185

        stroke.strokeWidth =
            dp(0.70f)

        canvas.drawLine(
            left,
            nodeY,
            right,
            nodeY,
            stroke
        )

        labels.indices.forEach {
            i ->

            val x =
                left +
                    step *
                        i

            val selected =
                i ==
                    active

            val color =
                paletteFor(
                    i
                ).primary

            if (selected) {
                fill.color =
                    color

                fill.alpha =
                    44

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(15.5f),
                    fill
                )

                stroke.color =
                    color

                stroke.alpha =
                    255

                stroke.strokeWidth =
                    dp(1.3f)

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(13.2f),
                    stroke
                )

                fill.color =
                    Color.WHITE

                fill.alpha =
                    255

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(2.6f),
                    fill
                )
            } else {
                stroke.color =
                    Color.parseColor(
                        "#46546A"
                    )

                stroke.alpha =
                    215

                stroke.strokeWidth =
                    dp(0.85f)

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(11.5f),
                    stroke
                )

                fill.color =
                    Color.parseColor(
                        "#748096"
                    )

                fill.alpha =
                    210

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(1.8f),
                    fill
                )
            }

            text.textSize =
                dp(
                    if (selected) {
                        9.0f
                    } else {
                        8.4f
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
                    Color.parseColor(
                        "#8A96A8"
                    )
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
    }

    private fun drawPolygon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        sides: Int,
        rotation: Double,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        finePath.reset()

        for (
            i in
            0 until sides
        ) {
            val angle =
                rotation +
                    i *
                        (
                            PI *
                                2.0 /
                                sides
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
                    radius *
                    0.78f

            if (
                i ==
                0
            ) {
                finePath.moveTo(
                    x,
                    y
                )
            } else {
                finePath.lineTo(
                    x,
                    y
                )
            }
        }

        finePath.close()

        glow.shader =
            null

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
            finePath,
            glow
        )

        stroke.shader =
            null

        stroke.color =
            color

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(
                widthDp
            )

        canvas.drawPath(
            finePath,
            stroke
        )
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
                    130
                ),
                color,
                Color.WHITE,
                color,
                withAlpha(
                    color,
                    130
                ),
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f,
                0.11f,
                0.31f,
                0.50f,
                0.69f,
                0.89f,
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
        state: Int
    ): Palette {
        return when(state) {
            STATE_WAITING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#27E8F4"
                        ),
                    secondary =
                        Color.parseColor(
                            "#00B8D4"
                        ),
                    accent =
                        Color.parseColor(
                            "#8AF5FF"
                        ),
                    white =
                        Color.parseColor(
                            "#F5FEFF"
                        )
                )

            STATE_RECOGNITION ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#2494FF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#1265FF"
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

            STATE_THINKING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#7654FF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#A54DFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#CDBDFF"
                        ),
                    white =
                        Color.parseColor(
                            "#FBF9FF"
                        )
                )

            STATE_EXECUTING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#31E978"
                        ),
                    secondary =
                        Color.parseColor(
                            "#16D6AA"
                        ),
                    accent =
                        Color.parseColor(
                            "#A1F9D9"
                        ),
                    white =
                        Color.parseColor(
                            "#F6FFF9"
                        )
                )

            STATE_ANSWERING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#EF35D0"
                        ),
                    secondary =
                        Color.parseColor(
                            "#A64DFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#F3B5FF"
                        ),
                    white =
                        Color.parseColor(
                            "#FFF8FF"
                        )
                )

            STATE_STOP ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#FF482E"
                        ),
                    secondary =
                        Color.parseColor(
                            "#FF7A2B"
                        ),
                    accent =
                        Color.parseColor(
                            "#FFAE8A"
                        ),
                    white =
                        Color.parseColor(
                            "#FFF8F5"
                        )
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
                    scanPeriodMs = 4700L,
                    scanAlpha = 82,
                    flowPeriodMs = 760.0,
                    waveGlowAlpha = 66,
                    waveAlpha = 188,
                    breatheMs = 1050.0,
                    coreGlowAlpha = 105,
                    ringPeriodMs = 6500L,
                    nodePeriodMs = 1550.0,
                    spineAlpha = 205,
                    packetCount = 9,
                    packetPeriodMs = 2450.0,
                    packetAlpha = 178,
                    direction = 1f
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 28L,
                    scanPeriodMs = 1900L,
                    scanAlpha = 128,
                    flowPeriodMs = 360.0,
                    waveGlowAlpha = 92,
                    waveAlpha = 230,
                    breatheMs = 690.0,
                    coreGlowAlpha = 142,
                    ringPeriodMs = 3600L,
                    nodePeriodMs = 850.0,
                    spineAlpha = 232,
                    packetCount = 14,
                    packetPeriodMs = 1120.0,
                    packetAlpha = 220,
                    direction = -1f
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 30L,
                    scanPeriodMs = 2900L,
                    scanAlpha = 112,
                    flowPeriodMs = 510.0,
                    waveGlowAlpha = 82,
                    waveAlpha = 214,
                    breatheMs = 790.0,
                    coreGlowAlpha = 158,
                    ringPeriodMs = 3100L,
                    nodePeriodMs = 690.0,
                    spineAlpha = 238,
                    packetCount = 16,
                    packetPeriodMs = 1380.0,
                    packetAlpha = 225,
                    direction = -1f
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1550L,
                    scanAlpha = 138,
                    flowPeriodMs = 300.0,
                    waveGlowAlpha = 104,
                    waveAlpha = 242,
                    breatheMs = 540.0,
                    coreGlowAlpha = 148,
                    ringPeriodMs = 2500L,
                    nodePeriodMs = 590.0,
                    spineAlpha = 245,
                    packetCount = 18,
                    packetPeriodMs = 860.0,
                    packetAlpha = 232,
                    direction = 1f
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1750L,
                    scanAlpha = 132,
                    flowPeriodMs = 320.0,
                    waveGlowAlpha = 102,
                    waveAlpha = 238,
                    breatheMs = 570.0,
                    coreGlowAlpha = 154,
                    ringPeriodMs = 2750L,
                    nodePeriodMs = 620.0,
                    spineAlpha = 242,
                    packetCount = 17,
                    packetPeriodMs = 920.0,
                    packetAlpha = 230,
                    direction = 1f
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 58L,
                    scanPeriodMs = 7600L,
                    scanAlpha = 58,
                    flowPeriodMs = 1100.0,
                    waveGlowAlpha = 48,
                    waveAlpha = 145,
                    breatheMs = 1650.0,
                    coreGlowAlpha = 98,
                    ringPeriodMs = 10400L,
                    nodePeriodMs = 2200.0,
                    spineAlpha = 170,
                    packetCount = 3,
                    packetPeriodMs = 3600.0,
                    packetAlpha = 118,
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
    )

    private data class Motion(
        val frameDelayMs: Long,
        val scanPeriodMs: Long,
        val scanAlpha: Int,
        val flowPeriodMs: Double,
        val waveGlowAlpha: Int,
        val waveAlpha: Int,
        val breatheMs: Double,
        val coreGlowAlpha: Int,
        val ringPeriodMs: Long,
        val nodePeriodMs: Double,
        val spineAlpha: Int,
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
