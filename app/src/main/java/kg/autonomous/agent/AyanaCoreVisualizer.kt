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
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v4.0 — DOMINANT ENERGY CORE.
 *
 * Visual-only full replacement for the main AYANA visualization window.
 *
 * Device target: Samsung Galaxy Tab S8 landscape.
 *
 * v4 direction:
 * - large circular core that visually dominates the window;
 * - dense luminous cyan / electric-blue / violet energy field;
 * - dark inner core instead of a small bright "ball";
 * - strong horizontal synthetic waveform through the centre;
 * - outer spark / telemetry halo inspired by the approved reference;
 * - six-stage execution strip remains inside this View, but no longer shrinks
 *   the core: the strip is overlaid at the bottom;
 * - no AYANA wordmark drawn across the core;
 * - all circular effects remain mathematically inside the View;
 * - no touch handling, Accessibility actions, permissions or ORB changes.
 *
 * Truth note:
 * waveform/energy motion is decorative and state-reactive. It is not presented
 * as measured microphone amplitude.
 *
 * Integration contract is unchanged:
 *   AyanaCoreVisualizer(Context)
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val linePaint =
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
        }

    private val wavePath =
        Path()

    private val fineWavePath =
        Path()

    private val ribbonPath =
        Path()

    private val arcBounds =
        RectF()

    private var attached =
        false

    private var cachedWidth =
        -1

    private var cachedHeight =
        -1

    private var cachedState =
        ""

    private var palette =
        Palette.default()

    private var ambientShader:
        RadialGradient? = null

    private var ringShader:
        RadialGradient? = null

    private var coreShader:
        RadialGradient? = null

    private var waveShader:
        LinearGradient? = null

    init {
        importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO

        isFocusable =
            false

        isClickable =
            false

        isLongClickable =
            false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        attached =
            true

        rebuildShaders(
            AyanaVoiceService.currentStatusState
        )

        postInvalidateDelayed(
            frameDelayMs(
                AyanaVoiceService.currentStatusState
            )
        )
    }

    override fun onDetachedFromWindow() {
        attached =
            false

        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(
        w: Int,
        h: Int,
        oldw: Int,
        oldh: Int
    ) {
        super.onSizeChanged(
            w,
            h,
            oldw,
            oldh
        )

        cachedWidth =
            -1

        cachedHeight =
            -1

        rebuildShaders(
            AyanaVoiceService.currentStatusState
        )
    }

    override fun onDraw(
        canvas: Canvas
    ) {
        super.onDraw(
            canvas
        )

        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        val state =
            AyanaVoiceService.currentStatusState

        if (
            state != cachedState ||
            width != cachedWidth ||
            height != cachedHeight
        ) {
            rebuildShaders(
                state
            )
        }

        val now =
            SystemClock.uptimeMillis()

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val compact =
            h < dp(180f)

        val stageHeight =
            if (compact) {
                0f
            } else {
                min(
                    dp(62f),
                    h * 0.205f
                )
            }

        // v4: the stage strip is an overlay, not reserved layout space.
        // This fixes the v3 regression where the circular body was squeezed
        // down simply to make room for stage labels.
        val stageTop =
            h -
                stageHeight

        val cx =
            w * 0.50f

        val cy =
            h *
                if (compact) {
                    0.50f
                } else {
                    0.415f
                }

        val edgeInset =
            dp(
                if (compact) {
                    8f
                } else {
                    10f
                }
            )

        // Outermost circular decoration is <= 1.095R.
        val safeByHeight =
            min(
                cy -
                    edgeInset,
                h -
                    cy -
                    edgeInset
            ) /
                1.095f

        val safeByWidth =
            (
                w * 0.50f -
                    edgeInset
                ) /
                1.095f

        val preferred =
            min(
                h *
                    if (compact) {
                        0.395f
                    } else {
                        0.395f
                    },
                w *
                    if (compact) {
                        0.235f
                    } else {
                        0.235f
                    }
            )

        val radius =
            min(
                preferred,
                min(
                    safeByHeight,
                    safeByWidth
                )
            )
                .coerceAtLeast(
                    dp(24f)
                )

        val energy =
            stateEnergy(
                state
            )

        val breathe =
            (
                0.5 +
                    0.5 *
                    sin(
                        now /
                            (
                                820.0 -
                                    energy * 180.0
                                )
                    )
                )
                .toFloat()

        drawAmbient(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            breathe = breathe
        )

        drawWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        drawWaveSpikes(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        drawOuterHalo(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        drawEnergyField(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        drawSignatureArcs(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            energy = energy
        )

        drawInnerCore(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            breathe = breathe,
            energy = energy
        )

        if (
            stageHeight > 0f
        ) {
            drawStageOverlay(
                canvas = canvas,
                state = state,
                top = stageTop,
                height = stageHeight
            )
        }

        if (attached) {
            postInvalidateDelayed(
                frameDelayMs(
                    state
                )
            )
        }
    }

    private fun drawAmbient(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fillPaint.shader =
            ambientShader

        fillPaint.alpha =
            255

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    1.075f +
                        breathe * 0.010f
                    ),
            fillPaint
        )

        linePaint.shader =
            null

        linePaint.color =
            palette.secondary

        linePaint.alpha =
            28

        linePaint.strokeWidth =
            dp(0.72f)

        canvas.drawCircle(
            cx,
            cy,
            radius * 1.075f,
            linePaint
        )
    }

    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        val left =
            width * 0.035f

        val right =
            width * 0.965f

        val span =
            right -
                left

        val points =
            126

        val phase =
            now /
                (
                    540.0 -
                        energy * 125.0
                    )

        val maxAmplitude =
            min(
                height * 0.145f,
                radius * 0.46f
            ) *
                (
                    0.54f +
                        energy * 0.55f
                    )

        wavePath.reset()
        fineWavePath.reset()

        for (
            index in
            0..points
        ) {
            val t =
                index /
                    points.toFloat()

            val x =
                left +
                    span * t

            val centerDistance =
                abs(
                    x -
                        cx
                ) /
                    (
                        radius *
                            1.22f
                        )

            val centerShape =
                centerDistance
                    .coerceIn(
                        0f,
                        1f
                    )

            val edgeEnvelope =
                sin(
                    PI *
                        t
                )
                    .toFloat()
                    .coerceAtLeast(
                        0f
                    )

            val carrier =
                sin(
                    t *
                        PI *
                        8.0 +
                        phase
                )
                    .toFloat()

            val harmonic =
                sin(
                    t *
                        PI *
                        21.0 -
                        phase *
                            0.73
                )
                    .toFloat() *
                    0.22f

            val low =
                sin(
                    t *
                        PI *
                        3.0 +
                        phase *
                            0.31
                )
                    .toFloat() *
                    0.18f

            val amplitudeShape =
                (
                    0.30f +
                        centerShape *
                            0.70f
                    )

            val y =
                cy +
                    (
                        carrier +
                            harmonic +
                            low
                        ) *
                    maxAmplitude *
                    edgeEnvelope *
                    amplitudeShape

            if (index == 0) {
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

            val fine =
                sin(
                    t *
                        PI *
                        45.0 +
                        phase *
                            1.19
                )
                    .toFloat()

            val fineY =
                cy +
                    fine *
                    maxAmplitude *
                    0.18f *
                    edgeEnvelope

            if (index == 0) {
                fineWavePath.moveTo(
                    x,
                    fineY
                )
            } else {
                fineWavePath.lineTo(
                    x,
                    fineY
                )
            }
        }

        glowPaint.shader =
            waveShader

        glowPaint.alpha =
            (
                56 +
                    energy * 58f
                )
                .toInt()
                .coerceIn(
                    0,
                    120
                )

        glowPaint.strokeWidth =
            dp(8.2f)

        canvas.drawPath(
            wavePath,
            glowPaint
        )

        linePaint.shader =
            waveShader

        linePaint.alpha =
            (
                176 +
                    energy * 70f
                )
                .toInt()
                .coerceAtMost(
                    242
                )

        linePaint.strokeWidth =
            dp(1.55f)

        canvas.drawPath(
            wavePath,
            linePaint
        )

        linePaint.shader =
            null

        linePaint.color =
            palette.white

        linePaint.alpha =
            72

        linePaint.strokeWidth =
            dp(0.62f)

        canvas.drawPath(
            fineWavePath,
            linePaint
        )
    }

    private fun drawWaveSpikes(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        val left =
            width * 0.115f

        val right =
            width * 0.885f

        val bars =
            72

        val phase =
            now /
                (
                    390.0 -
                        energy * 65.0
                    )

        glowPaint.shader =
            waveShader

        glowPaint.alpha =
            62

        glowPaint.strokeWidth =
            dp(1.05f)

        for (
            index in
            0 until bars
        ) {
            val t =
                index /
                    (
                        bars -
                            1
                        ).toFloat()

            val x =
                left +
                    (
                        right -
                            left
                        ) *
                    t

            val fromCenter =
                abs(
                    x -
                        cx
                )

            // Peaks gather around the circular body and taper at the far edges.
            val nearCore =
                (
                    1f -
                        (
                            abs(
                                fromCenter -
                                    radius *
                                        0.92f
                            ) /
                                (
                                    radius *
                                        1.35f
                                    )
                            )
                            .coerceIn(
                                0f,
                                1f
                            )
                    )

            val rhythm =
                abs(
                    sin(
                        phase +
                            index *
                                0.77
                    )
                        .toFloat()
                )

            val half =
                dp(1.2f) +
                    radius *
                    0.18f *
                    nearCore *
                    rhythm *
                    (
                        0.42f +
                            energy * 0.58f
                        )

            canvas.drawLine(
                x,
                cy -
                    half,
                x,
                cy +
                    half,
                glowPaint
            )
        }
    }

    private fun drawOuterHalo(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        val outer =
            radius *
                1.025f

        linePaint.shader =
            null

        linePaint.color =
            palette.primary

        linePaint.alpha =
            74

        linePaint.strokeWidth =
            dp(0.80f)

        canvas.drawCircle(
            cx,
            cy,
            outer,
            linePaint
        )

        val phase =
            continuousAngle(
                now,
                21600f -
                    energy * 2800f,
                false
            )

        // Dense deterministic spark ring.
        val particles =
            104

        for (
            index in
            0 until particles
        ) {
            val angle =
                phase +
                    index *
                        (
                            360f /
                                particles
                            )

            val radians =
                angle *
                    PI /
                    180.0

            val band =
                when (
                    index %
                        4
                ) {
                    0 ->
                        0.995f

                    1 ->
                        1.025f

                    2 ->
                        1.048f

                    else ->
                        1.067f
                }

            val pulse =
                (
                    0.5 +
                        0.5 *
                        sin(
                            now /
                                520.0 +
                                index *
                                    0.91
                        )
                    )
                    .toFloat()

            val r =
                radius *
                    (
                        band +
                            0.006f *
                                sin(
                                    index *
                                        2.17 +
                                        now /
                                            1200.0
                                )
                                    .toFloat()
                        )

            val x =
                cx +
                    cos(
                        radians
                    )
                        .toFloat() *
                    r

            val y =
                cy +
                    sin(
                        radians
                    )
                        .toFloat() *
                    r

            fillPaint.shader =
                null

            fillPaint.color =
                when (
                    index %
                        7
                ) {
                    0 ->
                        palette.white

                    1,
                    2,
                    3 ->
                        palette.primary

                    4,
                    5 ->
                        palette.secondary

                    else ->
                        palette.accent
                }

            fillPaint.alpha =
                (
                    68 +
                        pulse *
                            158f
                    )
                    .toInt()
                    .coerceIn(
                        0,
                        225
                    )

            canvas.drawCircle(
                x,
                y,
                dp(
                    0.42f +
                        pulse *
                            0.62f
                ),
                fillPaint
            )
        }

        // Four rotating high-energy arc fragments.
        arcBounds.set(
            cx -
                radius *
                    1.045f,
            cy -
                radius *
                    1.045f,
            cx +
                radius *
                    1.045f,
            cy +
                radius *
                    1.045f
        )

        linePaint.color =
            palette.primary

        linePaint.alpha =
            178

        linePaint.strokeWidth =
            dp(1.15f)

        canvas.drawArc(
            arcBounds,
            phase + 12f,
            34f,
            false,
            linePaint
        )

        canvas.drawArc(
            arcBounds,
            phase + 102f,
            19f,
            false,
            linePaint
        )

        linePaint.color =
            palette.accent

        canvas.drawArc(
            arcBounds,
            phase + 194f,
            42f,
            false,
            linePaint
        )

        canvas.drawArc(
            arcBounds,
            phase + 302f,
            23f,
            false,
            linePaint
        )
    }

    private fun drawEnergyField(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        drawRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.79f,
            phase = now / 1120.0,
            tiltDegrees = -34f,
            turns = 2.55f,
            color = palette.primary,
            alpha = 196,
            widthDp = 1.65f
        )

        drawRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.78f,
            phase = -now / 1280.0,
            tiltDegrees = 26f,
            turns = 2.85f,
            color = palette.secondary,
            alpha = 178,
            widthDp = 1.52f
        )

        drawRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.75f,
            phase = now / 1450.0,
            tiltDegrees = 66f,
            turns = 2.40f,
            color = palette.accent,
            alpha = 170,
            widthDp = 1.38f
        )

        drawRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.70f,
            phase = -now / 1690.0,
            tiltDegrees = 104f,
            turns = 2.20f,
            color = palette.primary,
            alpha = 132,
            widthDp = 1.10f
        )

        drawRibbon(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.66f,
            phase = now / 1910.0,
            tiltDegrees = 151f,
            turns = 2.70f,
            color = palette.white,
            alpha =
                if (
                    energy >
                        0.70f
                ) {
                    98
                } else {
                    66
                },
            widthDp = 0.82f
        )
    }

    private fun drawRibbon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Double,
        tiltDegrees: Float,
        turns: Float,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        ribbonPath.reset()

        val tilt =
            tiltDegrees *
                PI /
                180.0

        val steps =
            128

        for (
            index in
            0..steps
        ) {
            val t =
                index /
                    steps.toFloat()

            val theta =
                t *
                    PI *
                    2.0 *
                    turns +
                    phase

            val ripple =
                0.82f +
                    0.18f *
                        sin(
                            theta *
                                2.2
                        )
                            .toFloat()

            val rx =
                radius *
                    ripple

            val ry =
                radius *
                    (
                        0.48f +
                            0.16f *
                                sin(
                                    theta *
                                        1.55
                                )
                                    .toFloat()
                        )

            val rawX =
                cos(
                    theta
                )
                    .toFloat() *
                    rx

            val rawY =
                sin(
                    theta
                )
                    .toFloat() *
                    ry

            val x =
                cx +
                    (
                        rawX *
                            cos(
                                tilt
                            ) -
                            rawY *
                                sin(
                                    tilt
                                )
                        )
                        .toFloat()

            val y =
                cy +
                    (
                        rawX *
                            sin(
                                tilt
                            ) +
                            rawY *
                                cos(
                                    tilt
                                )
                        )
                        .toFloat()

            if (index == 0) {
                ribbonPath.moveTo(
                    x,
                    y
                )
            } else {
                ribbonPath.lineTo(
                    x,
                    y
                )
            }
        }

        glowPaint.shader =
            null

        glowPaint.color =
            color

        glowPaint.alpha =
            (
                alpha *
                    0.24f
                )
                .toInt()

        glowPaint.strokeWidth =
            dp(
                widthDp *
                    5.6f
            )

        canvas.drawPath(
            ribbonPath,
            glowPaint
        )

        linePaint.shader =
            null

        linePaint.color =
            color

        linePaint.alpha =
            alpha

        linePaint.strokeWidth =
            dp(
                widthDp
            )

        canvas.drawPath(
            ribbonPath,
            linePaint
        )
    }

    private fun drawSignatureArcs(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.91f,
            phase =
                continuousAngle(
                    now,
                    13200f -
                        energy *
                            2100f,
                    false
                ) +
                    12f,
            sweeps =
                floatArrayOf(
                    76f,
                    48f,
                    64f
                ),
            gaps =
                floatArrayOf(
                    22f,
                    39f,
                    31f
                ),
            color = palette.primary,
            alpha = 228,
            widthDp = 1.90f
        )

        drawBrokenArc(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius * 0.83f,
            phase =
                continuousAngle(
                    now,
                    16900f -
                        energy *
                            2400f,
                    true
                ) +
                    126f,
            sweeps =
                floatArrayOf(
                    62f,
                    71f,
                    41f
                ),
            gaps =
                floatArrayOf(
                    31f,
                    37f,
                    44f
                ),
            color = palette.accent,
            alpha = 176,
            widthDp = 1.34f
        )
    }

    private fun drawBrokenArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        sweeps: FloatArray,
        gaps: FloatArray,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        arcBounds.set(
            cx -
                radius,
            cy -
                radius,
            cx +
                radius,
            cy +
                radius
        )

        var start =
            phase

        val count =
            min(
                sweeps.size,
                gaps.size
            )

        for (
            index in
            0 until count
        ) {
            glowPaint.shader =
                null

            glowPaint.color =
                color

            glowPaint.alpha =
                (
                    alpha *
                        0.19f
                    )
                    .toInt()

            glowPaint.strokeWidth =
                dp(
                    widthDp *
                        4.8f
                )

            canvas.drawArc(
                arcBounds,
                start,
                sweeps[index],
                false,
                glowPaint
            )

            linePaint.shader =
                null

            linePaint.color =
                color

            linePaint.alpha =
                alpha

            linePaint.strokeWidth =
                dp(
                    widthDp
                )

            canvas.drawArc(
                arcBounds,
                start,
                sweeps[index],
                false,
                linePaint
            )

            start +=
                sweeps[index] +
                    gaps[index]
        }
    }

    private fun drawInnerCore(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float,
        energy: Float
    ) {
        fillPaint.shader =
            ringShader

        fillPaint.alpha =
            245

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.53f +
                        breathe *
                            0.012f
                    ),
            fillPaint
        )

        // Dark central chamber: removes the v3 "glowing ball" look.
        fillPaint.shader =
            coreShader

        fillPaint.alpha =
            255

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.29f +
                        breathe *
                            0.008f
                    ),
            fillPaint
        )

        linePaint.shader =
            null

        linePaint.color =
            palette.primary

        linePaint.alpha =
            142

        linePaint.strokeWidth =
            dp(1.0f)

        canvas.drawCircle(
            cx,
            cy,
            radius * 0.31f,
            linePaint
        )

        val phase =
            now /
                (
                    720.0 -
                        energy *
                            120.0
                    )

        // Tiny living nucleus, not a dominant ball.
        val dotX =
            cx +
                sin(
                    phase
                )
                    .toFloat() *
                    radius *
                    0.022f

        val dotY =
            cy +
                cos(
                    phase *
                        0.83
                )
                    .toFloat() *
                    radius *
                    0.018f

        fillPaint.shader =
            null

        fillPaint.color =
            palette.white

        fillPaint.alpha =
            224

        canvas.drawCircle(
            dotX,
            dotY,
            dp(1.9f),
            fillPaint
        )

        fillPaint.color =
            palette.primary

        fillPaint.alpha =
            52

        canvas.drawCircle(
            dotX,
            dotY,
            dp(8.5f),
            fillPaint
        )
    }

    private fun drawStageOverlay(
        canvas: Canvas,
        state: String,
        top: Float,
        height: Float
    ) {
        // The background visually separates the execution strip while still
        // allowing the circular halo to remain large behind the upper edge.
        fillPaint.shader =
            null

        fillPaint.color =
            Color.parseColor(
                "#050913"
            )

        fillPaint.alpha =
            226

        canvas.drawLine(
            0f,
            top,
            width.toFloat(),
            top,
            fillPaint
        )

        linePaint.shader =
            null

        linePaint.color =
            Color.parseColor(
                "#23314A"
            )

        linePaint.alpha =
            210

        linePaint.strokeWidth =
            dp(0.85f)

        canvas.drawLine(
            width *
                0.045f,
            top,
            width *
                0.955f,
            top,
            linePaint
        )

        val labels =
            arrayOf(
                "Слушаю",
                "Распознаю",
                "Думаю",
                "Выполняю",
                "Проверяю",
                "Отвечаю"
            )

        val glyphs =
            arrayOf(
                "●",
                "≋",
                "◈",
                "ϟ",
                "✓",
                "•••"
            )

        val active =
            stageIndexFor(
                state
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

        val iconY =
            top +
                height *
                    0.40f

        val labelY =
            top +
                height *
                    0.84f

        linePaint.color =
            Color.parseColor(
                "#28344B"
            )

        linePaint.alpha =
            170

        linePaint.strokeWidth =
            dp(0.75f)

        canvas.drawLine(
            left,
            iconY,
            right,
            iconY,
            linePaint
        )

        for (
            index in
            labels.indices
        ) {
            val x =
                left +
                    step *
                        index

            val selected =
                index ==
                    active

            val completed =
                active >= 0 &&
                    index <
                    active

            val nodeColor =
                when {
                    selected ->
                        palette.primary

                    completed ->
                        palette.secondary

                    else ->
                        Color.parseColor(
                            "#53627A"
                        )
                }

            if (selected) {
                fillPaint.color =
                    palette.primary

                fillPaint.alpha =
                    34

                canvas.drawCircle(
                    x,
                    iconY,
                    dp(19.5f),
                    fillPaint
                )

                linePaint.color =
                    palette.primary

                linePaint.alpha =
                    230

                linePaint.strokeWidth =
                    dp(1.35f)

                canvas.drawCircle(
                    x,
                    iconY,
                    dp(17.0f),
                    linePaint
                )
            } else {
                linePaint.color =
                    Color.parseColor(
                        "#3A465D"
                    )

                linePaint.alpha =
                    205

                linePaint.strokeWidth =
                    dp(0.95f)

                canvas.drawCircle(
                    x,
                    iconY,
                    dp(15.2f),
                    linePaint
                )
            }

            textPaint.color =
                nodeColor

            textPaint.alpha =
                255

            textPaint.textSize =
                dp(
                    if (
                        glyphs[index] ==
                            "•••"
                    ) {
                        10.5f
                    } else {
                        13.2f
                    }
                )

            textPaint.typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.BOLD
                )

            val metrics =
                textPaint.fontMetrics

            val glyphBaseline =
                iconY -
                    (
                        metrics.ascent +
                            metrics.descent
                        ) /
                    2f

            canvas.drawText(
                glyphs[index],
                x,
                glyphBaseline,
                textPaint
            )

            textPaint.textSize =
                dp(10.6f)

            textPaint.typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    if (selected) {
                        Typeface.BOLD
                    } else {
                        Typeface.NORMAL
                    }
                )

            textPaint.color =
                if (selected) {
                    palette.primary
                } else {
                    Color.parseColor(
                        "#8795AA"
                    )
                }

            textPaint.alpha =
                if (selected) {
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

    private fun stageIndexFor(
        state: String
    ): Int {
        return when (
            state
        ) {
            AyanaVoiceService.STATE_LISTENING ->
                0

            AyanaVoiceService.STATE_COMMAND ->
                1

            AyanaVoiceService.STATE_THINKING ->
                2

            AyanaVoiceService.STATE_EXECUTING ->
                3

            AyanaVoiceService.STATE_SUCCESS,
            AyanaVoiceService.STATE_ERROR ->
                4

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT ->
                5

            else ->
                -1
        }
    }

    private fun rebuildShaders(
        state: String
    ) {
        if (
            width <= 0 ||
            height <= 0
        ) {
            return
        }

        cachedWidth =
            width

        cachedHeight =
            height

        cachedState =
            state

        palette =
            paletteFor(
                state
            )

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        val cx =
            w * 0.50f

        val cy =
            h *
                if (
                    h <
                        dp(180f)
                ) {
                    0.50f
                } else {
                    0.415f
                }

        val minSide =
            min(
                w,
                h
            )

        ambientShader =
            RadialGradient(
                cx,
                cy,
                minSide *
                    0.48f,
                intArrayOf(
                    withAlpha(
                        palette.primary,
                        58
                    ),
                    withAlpha(
                        palette.secondary,
                        50
                    ),
                    withAlpha(
                        palette.accent,
                        34
                    ),
                    withAlpha(
                        palette.deep,
                        14
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.28f,
                    0.52f,
                    0.78f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        ringShader =
            RadialGradient(
                cx,
                cy,
                minSide *
                    0.265f,
                intArrayOf(
                    withAlpha(
                        palette.white,
                        16
                    ),
                    withAlpha(
                        palette.primary,
                        68
                    ),
                    withAlpha(
                        palette.secondary,
                        44
                    ),
                    withAlpha(
                        palette.accent,
                        30
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.30f,
                    0.54f,
                    0.76f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        coreShader =
            RadialGradient(
                cx -
                    minSide *
                        0.012f,
                cy -
                    minSide *
                        0.015f,
                minSide *
                    0.15f,
                intArrayOf(
                    withAlpha(
                        palette.primary,
                        42
                    ),
                    Color.parseColor(
                        "#08111E"
                    ),
                    Color.parseColor(
                        "#03070E"
                    )
                ),
                floatArrayOf(
                    0f,
                    0.42f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        waveShader =
            LinearGradient(
                w *
                    0.03f,
                cy,
                w *
                    0.97f,
                cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        palette.primary,
                        120
                    ),
                    palette.primary,
                    palette.white,
                    palette.secondary,
                    palette.accent,
                    withAlpha(
                        palette.accent,
                        112
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.12f,
                    0.31f,
                    0.50f,
                    0.66f,
                    0.82f,
                    0.91f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )
    }

    private fun stateEnergy(
        state: String
    ): Float {
        return when (
            state
        ) {
            AyanaVoiceService.STATE_COMMAND ->
                0.82f

            AyanaVoiceService.STATE_THINKING ->
                0.79f

            AyanaVoiceService.STATE_EXECUTING ->
                0.94f

            AyanaVoiceService.STATE_SPEAKING ->
                0.86f

            AyanaVoiceService.STATE_LISTENING ->
                0.72f

            AyanaVoiceService.STATE_SUCCESS ->
                0.48f

            AyanaVoiceService.STATE_ERROR ->
                0.58f

            AyanaVoiceService.STATE_CANCELLED ->
                0.36f

            else ->
                0.34f
        }
    }

    private fun paletteFor(
        state: String
    ): Palette {
        return when (
            state
        ) {
            AyanaVoiceService.STATE_THINKING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#55CAFF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#536FFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#9F55FF"
                        ),
                    white =
                        Color.parseColor(
                            "#F8FCFF"
                        ),
                    deep =
                        Color.parseColor(
                            "#07101A"
                        )
                )

            AyanaVoiceService.STATE_EXECUTING ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#35F1D1"
                        ),
                    secondary =
                        Color.parseColor(
                            "#209EFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#6B63FF"
                        ),
                    white =
                        Color.parseColor(
                            "#F3FFFC"
                        ),
                    deep =
                        Color.parseColor(
                            "#061815"
                        )
                )

            AyanaVoiceService.STATE_SUCCESS ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#4DE6B0"
                        ),
                    secondary =
                        Color.parseColor(
                            "#33C6D1"
                        ),
                    accent =
                        Color.parseColor(
                            "#66DFA4"
                        ),
                    white =
                        Color.parseColor(
                            "#F5FFF9"
                        ),
                    deep =
                        Color.parseColor(
                            "#061812"
                        )
                )

            AyanaVoiceService.STATE_ERROR ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#FF6686"
                        ),
                    secondary =
                        Color.parseColor(
                            "#DB4670"
                        ),
                    accent =
                        Color.parseColor(
                            "#F15BA6"
                        ),
                    white =
                        Color.parseColor(
                            "#FFF5F8"
                        ),
                    deep =
                        Color.parseColor(
                            "#23070F"
                        )
                )

            AyanaVoiceService.STATE_CANCELLED ->
                Palette(
                    primary =
                        Color.parseColor(
                            "#FFD169"
                        ),
                    secondary =
                        Color.parseColor(
                            "#EDA13A"
                        ),
                    accent =
                        Color.parseColor(
                            "#FFB267"
                        ),
                    white =
                        Color.parseColor(
                            "#FFFCEE"
                        ),
                    deep =
                        Color.parseColor(
                            "#241707"
                        )
                )

            else ->
                Palette.default()
        }
    }

    private fun frameDelayMs(
        state: String
    ): Long {
        return when (
            state
        ) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING ->
                27L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SPEAKING ->
                30L

            AyanaVoiceService.STATE_LISTENING ->
                32L

            else ->
                50L
        }
    }

    private fun continuousAngle(
        now: Long,
        cycleMs: Float,
        reverse: Boolean
    ): Float {
        val safe =
            max(
                800f,
                cycleMs
            )

        val fraction =
            (
                now %
                    safe.toLong()
                ).toFloat() /
                safe

        val angle =
            fraction *
                360f

        return if (reverse) {
            -angle
        } else {
            angle
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
        val white: Int,
        val deep: Int
    ) {
        companion object {

            fun default():
                Palette {
                return Palette(
                    primary =
                        Color.parseColor(
                            "#27E4FF"
                        ),
                    secondary =
                        Color.parseColor(
                            "#1E8DFF"
                        ),
                    accent =
                        Color.parseColor(
                            "#9455FF"
                        ),
                    white =
                        Color.parseColor(
                            "#F5FDFF"
                        ),
                    deep =
                        Color.parseColor(
                            "#06101A"
                        )
                )
            }
        }
    }
}
