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
 * AYANA Core Visualizer v9.0 — AGENT HEXAFLOW.
 *
 * Device-driven redesign after v8.0 was visibly too dark.
 *
 * Concept:
 * - one large high-contrast agent decision core;
 * - six radial functional branches matching AYANA's six runtime states;
 * - perception waveform enters from the left;
 * - action/answer waveform exits to the right;
 * - bright segmented telemetry halo;
 * - moving data packets pass through the decision core;
 * - three broken AYANA signature arcs retained as a subtle identity element;
 * - no bitmap / PNG / static reference dependency;
 * - no giant wordmark over the visual;
 * - no ORB, Accessibility, routing, TTS, microphone or command logic changes.
 *
 * States:
 * 0 Ожидание
 * 1 Распознавание
 * 2 Думаю
 * 3 Выполняю
 * 4 Отвечаю
 * 5 Стоп
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

        val cx =
            w * 0.50f

        val cy =
            h *
                if (compact) {
                    0.50f
                } else {
                    0.43f
                }

        val radius =
            min(
                w * 0.285f,
                h * 0.405f
            )
                .coerceAtLeast(
                    dp(52f)
                )

        drawBackground(
            canvas = canvas,
            now = now,
            railTop = railTop,
            palette = palette,
            motion = motion
        )

        drawInputWave(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawHexaCore(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawOutputWave(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawDataPackets(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion,
            state = state
        )

        drawStateEffect(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            state = state
        )

        if (
            railHeight > 0f
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
                motion.frameDelayMs
            )
        }
    }

    /**
     * Keeps the proven device-side title fix from v8, but does not let this
     * renderer otherwise control MainActivity.
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
        railTop: Float,
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
                    Color.parseColor("#02050A"),
                    Color.parseColor("#07101A"),
                    Color.parseColor("#02050A")
                ),
                floatArrayOf(
                    0f,
                    0.50f,
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
            Color.parseColor("#20334A")

        stroke.alpha =
            72

        stroke.strokeWidth =
            dp(0.55f)

        val cols =
            14

        val rows =
            7

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
                dp(8f),
                x,
                railTop - dp(5f),
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
                width - dp(8f),
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
                    0.05f +
                        scan * 0.90f
                    )

        fill.shader =
            LinearGradient(
                scanX - dp(42f),
                0f,
                scanX + dp(42f),
                0f,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        palette.primary,
                        35
                    ),
                    withAlpha(
                        palette.primary,
                        150
                    ),
                    withAlpha(
                        palette.primary,
                        35
                    ),
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

        fill.alpha =
            motion.scanAlpha

        canvas.drawRect(
            scanX - dp(42f),
            dp(6f),
            scanX + dp(42f),
            railTop - dp(6f),
            fill
        )

        fill.shader =
            null
    }

    private fun drawInputWave(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val left =
            width * 0.025f

        val right =
            cx - radius * 0.72f

        val activeBoost =
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
                radius *
                    0.15f *
                    activeBoost,
            primary =
                palette.primary,
            secondary =
                palette.secondary,
            period =
                motion.wavePeriodMs,
            frequency =
                8.0,
            alpha =
                motion.waveAlpha
        )
    }

    private fun drawOutputWave(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val left =
            cx + radius * 0.72f

        val right =
            width * 0.975f

        val activeBoost =
            when(state) {
                STATE_EXECUTING ->
                    1.45f

                STATE_ANSWERING ->
                    1.72f

                else ->
                    1f
            }

        drawWave(
            canvas = canvas,
            now = now,
            left = left,
            right = right,
            cy = cy,
            amplitude =
                radius *
                    0.15f *
                    activeBoost,
            primary =
                palette.secondary,
            secondary =
                palette.primary,
            period =
                motion.wavePeriodMs *
                    0.94,
            frequency =
                8.5,
            alpha =
                motion.waveAlpha
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
        frequency: Double,
        alpha: Int
    ) {
        if (
            right <= left
        ) {
            return
        }

        path.reset()

        finePath.reset()

        val samples =
            72

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
                        right -
                            left
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

            val harmonic =
                sin(
                    t *
                        PI *
                        25.0 -
                        phase * 0.79
                )
                    .toFloat() *
                    0.24f

            val y =
                cy +
                    (
                        main +
                            harmonic
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
                        41.0 +
                        phase *
                            1.23
                )
                    .toFloat()

            val fy =
                cy +
                    fine *
                    amplitude *
                    0.20f *
                    envelope

            if (
                i == 0
            ) {
                finePath.moveTo(
                    x,
                    fy
                )
            } else {
                finePath.lineTo(
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
            dp(7.5f)

        canvas.drawPath(
            path,
            glow
        )

        stroke.shader =
            gradient

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(1.65f)

        canvas.drawPath(
            path,
            stroke
        )

        stroke.shader =
            null

        stroke.color =
            Color.WHITE

        stroke.alpha =
            78

        stroke.strokeWidth =
            dp(0.58f)

        canvas.drawPath(
            finePath,
            stroke
        )
    }

    private fun drawHexaCore(
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

        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius *
                    1.08f,
                intArrayOf(
                    withAlpha(
                        Color.WHITE,
                        140
                    ),
                    withAlpha(
                        palette.primary,
                        175
                    ),
                    withAlpha(
                        palette.secondary,
                        82
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
                    1.00f +
                        pulse *
                            0.014f
                    ),
            fill
        )

        fill.shader =
            null

        // Outer bright segmented telemetry halo.
        val haloR =
            radius * 0.98f

        ring.set(
            cx - haloR,
            cy - haloR,
            cx + haloR,
            cy + haloR
        )

        val haloPhase =
            (
                now %
                    motion.ringPeriodMs
                ).toFloat() /
                motion.ringPeriodMs.toFloat() *
                360f

        val segments =
            arrayOf(
                Triple(haloPhase + 8f, 58f, palette.primary),
                Triple(haloPhase + 103f, 34f, palette.accent),
                Triple(haloPhase + 177f, 46f, palette.secondary),
                Triple(haloPhase + 266f, 62f, palette.primary)
            )

        segments.forEach {
            segment ->

            glow.shader =
                null

            glow.color =
                segment.third

            glow.alpha =
                96

            glow.strokeWidth =
                dp(7f)

            canvas.drawArc(
                ring,
                segment.first,
                segment.second,
                false,
                glow
            )

            stroke.color =
                segment.third

            stroke.alpha =
                245

            stroke.strokeWidth =
                dp(1.75f)

            canvas.drawArc(
                ring,
                segment.first,
                segment.second,
                false,
                stroke
            )
        }

        // Three signature broken arcs.
        repeat(
            3
        ) {
            layer ->

            val rr =
                radius *
                    (
                        0.82f -
                            layer *
                                0.12f
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
                            motion.ringPeriodMs +
                                layer *
                                    720L
                            )
                    ).toFloat() /
                    (
                        motion.ringPeriodMs +
                            layer *
                                720L
                        ).toFloat() *
                    360f *
                    (
                        if (
                            layer %
                                2 ==
                                0
                        ) {
                            1f
                        } else {
                            -1f
                        }
                    )

            val color =
                when(layer) {
                    0 ->
                        palette.primary

                    1 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            stroke.color =
                color

            stroke.alpha =
                205 -
                    layer *
                        28

            stroke.strokeWidth =
                dp(
                    1.35f -
                        layer *
                            0.15f
                )

            glow.color =
                color

            glow.alpha =
                50

            glow.strokeWidth =
                dp(5f)

            canvas.drawArc(
                ring,
                phase + 16f,
                70f,
                false,
                glow
            )

            canvas.drawArc(
                ring,
                phase + 16f,
                70f,
                false,
                stroke
            )

            canvas.drawArc(
                ring,
                phase + 136f,
                48f,
                false,
                glow
            )

            canvas.drawArc(
                ring,
                phase + 136f,
                48f,
                false,
                stroke
            )

            canvas.drawArc(
                ring,
                phase + 238f,
                62f,
                false,
                glow
            )

            canvas.drawArc(
                ring,
                phase + 238f,
                62f,
                false,
                stroke
            )
        }

        // Six radial functional branches.
        repeat(
            6
        ) {
            i ->

            val angle =
                -PI / 2.0 +
                    i *
                        (
                            PI * 2.0 / 6.0
                            )

            val inner =
                radius * 0.30f

            val outer =
                radius * 0.73f

            val sx =
                cx +
                    cos(angle)
                        .toFloat() *
                    inner

            val sy =
                cy +
                    sin(angle)
                        .toFloat() *
                    inner

            val ex =
                cx +
                    cos(angle)
                        .toFloat() *
                    outer

            val ey =
                cy +
                    sin(angle)
                        .toFloat() *
                    outer

            val branchPalette =
                paletteFor(i)

            val active =
                i == state

            glow.color =
                branchPalette.primary

            glow.alpha =
                if (active) {
                    120
                } else {
                    22
                }

            glow.strokeWidth =
                dp(
                    if (active) {
                        7f
                    } else {
                        3f
                    }
                )

            canvas.drawLine(
                sx,
                sy,
                ex,
                ey,
                glow
            )

            stroke.color =
                if (active) {
                    Color.WHITE
                } else {
                    branchPalette.primary
                }

            stroke.alpha =
                if (active) {
                    245
                } else {
                    105
                }

            stroke.strokeWidth =
                dp(
                    if (active) {
                        1.45f
                    } else {
                        0.72f
                    }
                )

            canvas.drawLine(
                sx,
                sy,
                ex,
                ey,
                stroke
            )

            fill.color =
                branchPalette.primary

            fill.alpha =
                if (active) {
                    255
                } else {
                    150
                }

            canvas.drawCircle(
                ex,
                ey,
                dp(
                    if (active) {
                        3.8f
                    } else {
                        2.1f
                    }
                ),
                fill
            )
        }

        // Inference hexagons.
        repeat(
            3
        ) {
            layer ->

            drawPolygon(
                canvas = canvas,
                cx = cx,
                cy = cy,
                radius =
                    radius *
                        (
                            0.58f -
                                layer *
                                    0.14f
                            ),
                sides =
                    if (
                        layer == 0
                    ) {
                        8
                    } else {
                        6
                    },
                rotation =
                    now /
                        (
                            1550.0 +
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
                        ),
                color =
                    when(layer) {
                        0 ->
                            palette.primary

                        1 ->
                            palette.secondary

                        else ->
                            palette.accent
                    },
                alpha =
                    230 -
                        layer *
                            34
            )
        }

        // Bright decision nucleus.
        fill.shader =
            RadialGradient(
                cx,
                cy,
                radius *
                    0.24f,
                intArrayOf(
                    Color.WHITE,
                    palette.primary,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.19f,
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
                    0.18f +
                        pulse *
                            0.016f
                    ),
            fill
        )

        fill.shader =
            null

        // Bright decision spine.
        stroke.shader =
            LinearGradient(
                cx,
                cy - radius,
                cx,
                cy + radius,
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
            motion.spineAlpha

        stroke.strokeWidth =
            dp(1.1f)

        canvas.drawLine(
            cx,
            cy - radius * 0.92f,
            cx,
            cy + radius * 0.92f,
            stroke
        )

        glow.shader =
            stroke.shader

        glow.alpha =
            66

        glow.strokeWidth =
            dp(6f)

        canvas.drawLine(
            cx,
            cy - radius * 0.92f,
            cx,
            cy + radius * 0.92f,
            glow
        )

        stroke.shader =
            null

        glow.shader =
            null
    }

    private fun drawDataPackets(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion,
        state: Int
    ) {
        val left =
            width * 0.035f

        val right =
            width * 0.965f

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
                (
                    i % 5 - 2
                    ) *
                    radius *
                    0.13f

            val y =
                cy +
                    lane +
                    sin(
                        now /
                            530.0 +
                            i *
                                0.71
                    )
                        .toFloat() *
                        dp(2f)

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
                        2.5f
                    } else {
                        1.4f
                    }
                ),
                fill
            )
        }
    }

    private fun drawStateEffect(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        state: Int
    ) {
        when(state) {
            STATE_WAITING -> {
                val pulse =
                    (
                        0.5 +
                            0.5 *
                                sin(
                                    now / 960.0
                                )
                        )
                        .toFloat()

                ring.set(
                    cx -
                        radius *
                            (
                                0.24f +
                                    pulse *
                                        0.035f
                                ),
                    cy -
                        radius *
                            (
                                0.24f +
                                    pulse *
                                        0.035f
                                ),
                    cx +
                        radius *
                            (
                                0.24f +
                                    pulse *
                                        0.035f
                                ),
                    cy +
                        radius *
                            (
                                0.24f +
                                    pulse *
                                        0.035f
                                )
                )

                stroke.color =
                    palette.primary

                stroke.alpha =
                    185

                stroke.strokeWidth =
                    dp(1.2f)

                canvas.drawOval(
                    ring,
                    stroke
                )
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
                val pulse =
                    (
                        0.5 +
                            0.5 *
                                sin(
                                    now / 510.0
                                )
                        )
                        .toFloat()

                glow.color =
                    palette.accent

                glow.alpha =
                    (
                        54 +
                            pulse *
                                70f
                        )
                        .toInt()

                glow.strokeWidth =
                    dp(8f)

                ring.set(
                    cx - radius * 0.50f,
                    cy - radius * 0.50f,
                    cx + radius * 0.50f,
                    cy + radius * 0.50f
                )

                canvas.drawOval(
                    ring,
                    glow
                )
            }

            STATE_EXECUTING -> {
                glow.color =
                    palette.secondary

                glow.alpha =
                    110

                glow.strokeWidth =
                    dp(9f)

                canvas.drawLine(
                    cx + radius * 0.46f,
                    cy,
                    width * 0.97f,
                    cy,
                    glow
                )

                stroke.color =
                    Color.WHITE

                stroke.alpha =
                    235

                stroke.strokeWidth =
                    dp(0.9f)

                canvas.drawLine(
                    cx + radius * 0.46f,
                    cy,
                    width * 0.97f,
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
                fill.color =
                    palette.primary

                fill.alpha =
                    58

                canvas.drawCircle(
                    cx,
                    cy,
                    radius * 0.54f,
                    fill
                )

                stroke.color =
                    Color.WHITE

                stroke.alpha =
                    245

                stroke.strokeWidth =
                    dp(2.4f)

                val d =
                    radius * 0.30f

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

    private fun drawStateRail(
        canvas: Canvas,
        top: Float,
        height: Float,
        active: Int
    ) {
        fill.shader =
            null

        fill.color =
            Color.parseColor("#02050A")

        fill.alpha =
            244

        canvas.drawRect(
            0f,
            top,
            width.toFloat(),
            height.toFloat(),
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
                height *
                    0.40f

        val labelY =
            top +
                height *
                    0.82f

        stroke.color =
            Color.parseColor("#384860")

        stroke.alpha =
            195

        stroke.strokeWidth =
            dp(0.75f)

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
                    100
                }

            canvas.drawCircle(
                x,
                y,
                dp(
                    if (selected) {
                        3.5f
                    } else {
                        2.1f
                    }
                ),
                fill
            )

            if (selected) {
                glow.color =
                    color

                glow.alpha =
                    80

                glow.strokeWidth =
                    dp(7f)

                canvas.drawCircle(
                    x,
                    y,
                    dp(10.5f),
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
                    dp(8.5f),
                    stroke
                )
            }

            text.textSize =
                dp(
                    if (selected) {
                        9.2f
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
    }

    private fun drawPolygon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        sides: Int,
        rotation: Double,
        color: Int,
        alpha: Int
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
                            PI * 2.0 /
                                sides
                            ) -
                    PI / 2.0

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
                    0.80f

            if (
                i == 0
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

        glow.color =
            color

        glow.alpha =
            alpha / 4

        glow.strokeWidth =
            dp(6f)

        canvas.drawPath(
            finePath,
            glow
        )

        stroke.color =
            color

        stroke.alpha =
            alpha

        stroke.strokeWidth =
            dp(1.25f)

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
                    155
                ),
                color,
                Color.WHITE,
                color,
                withAlpha(
                    color,
                    155
                ),
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f,
                0.10f,
                0.30f,
                0.50f,
                0.70f,
                0.90f,
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
                    scanPeriodMs = 4300L,
                    scanAlpha = 88,
                    wavePeriodMs = 680.0,
                    waveAlpha = 205,
                    breathePeriodMs = 1080.0,
                    ringPeriodMs = 5700L,
                    spineAlpha = 225,
                    packetCount = 10,
                    packetPeriodMs = 2200.0,
                    packetAlpha = 190,
                    direction = 1f
                )

            STATE_RECOGNITION ->
                Motion(
                    frameDelayMs = 27L,
                    scanPeriodMs = 1750L,
                    scanAlpha = 135,
                    wavePeriodMs = 330.0,
                    waveAlpha = 245,
                    breathePeriodMs = 680.0,
                    ringPeriodMs = 3000L,
                    spineAlpha = 248,
                    packetCount = 15,
                    packetPeriodMs = 980.0,
                    packetAlpha = 230,
                    direction = -1f
                )

            STATE_THINKING ->
                Motion(
                    frameDelayMs = 29L,
                    scanPeriodMs = 2700L,
                    scanAlpha = 120,
                    wavePeriodMs = 470.0,
                    waveAlpha = 232,
                    breathePeriodMs = 760.0,
                    ringPeriodMs = 2700L,
                    spineAlpha = 250,
                    packetCount = 16,
                    packetPeriodMs = 1250.0,
                    packetAlpha = 235,
                    direction = -1f
                )

            STATE_EXECUTING ->
                Motion(
                    frameDelayMs = 26L,
                    scanPeriodMs = 1450L,
                    scanAlpha = 145,
                    wavePeriodMs = 280.0,
                    waveAlpha = 250,
                    breathePeriodMs = 520.0,
                    ringPeriodMs = 2200L,
                    spineAlpha = 255,
                    packetCount = 18,
                    packetPeriodMs = 780.0,
                    packetAlpha = 240,
                    direction = 1f
                )

            STATE_ANSWERING ->
                Motion(
                    frameDelayMs = 26L,
                    scanPeriodMs = 1600L,
                    scanAlpha = 140,
                    wavePeriodMs = 300.0,
                    waveAlpha = 248,
                    breathePeriodMs = 550.0,
                    ringPeriodMs = 2400L,
                    spineAlpha = 252,
                    packetCount = 18,
                    packetPeriodMs = 840.0,
                    packetAlpha = 238,
                    direction = 1f
                )

            STATE_STOP ->
                Motion(
                    frameDelayMs = 58L,
                    scanPeriodMs = 7200L,
                    scanAlpha = 62,
                    wavePeriodMs = 980.0,
                    waveAlpha = 165,
                    breathePeriodMs = 1550.0,
                    ringPeriodMs = 9400L,
                    spineAlpha = 190,
                    packetCount = 3,
                    packetPeriodMs = 3400.0,
                    packetAlpha = 130,
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
        val breathePeriodMs: Double,
        val ringPeriodMs: Long,
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
