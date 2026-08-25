package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v1.0 — VISUAL CORE REFRESH.
 *
 * Programmatic, allocation-conscious visual layer for the main AYANA card.
 * It intentionally has NO accessibility semantics: MainActivity's factual
 * in-process semantic bridge must expose controls/text, not decorative frames.
 *
 * This view is state-reactive. It does not claim microphone-amplitude truth;
 * the waveform is a live state visualization until a separately verified
 * audio-meter contract is added.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val ambientPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val corePaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    private val finePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    private val wavePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val particlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val textPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )
            letterSpacing = 0.12f
        }

    private val wavePath =
        Path()

    private val secondaryWavePath =
        Path()

    private val orbitBounds =
        RectF()

    private var ambientShader:
        RadialGradient? = null

    private var coreShader:
        RadialGradient? = null

    private var shaderWidth =
        -1

    private var shaderHeight =
        -1

    private var shaderState =
        ""

    private var attached =
        false

    init {
        importantForAccessibility =
            View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
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
        attached = false
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
        shaderWidth = -1
        shaderHeight = -1
        rebuildShaders(
            AyanaVoiceService.currentStatusState
        )
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
            AyanaVoiceService.currentStatusState

        if (
            state != shaderState ||
            width != shaderWidth ||
            height != shaderHeight
        ) {
            rebuildShaders(state)
        }

        val now =
            SystemClock.uptimeMillis()

        val cx =
            width / 2f

        val cy =
            height / 2f

        val minSide =
            min(
                width.toFloat(),
                height.toFloat()
            )

        val accent =
            accentFor(state)

        val pale =
            Color.rgb(
                224,
                247,
                255
            )

        val violet =
            Color.rgb(
                167,
                139,
                250
            )

        val motion =
            motionFor(state)

        val amplitude =
            amplitudeFor(state)

        val phase =
            now.toDouble() /
                motion.phaseDivisor

        // Ambient energy field.
        ambientPaint.alpha =
            if (
                state ==
                AyanaVoiceService.STATE_THINKING
            ) {
                228
            } else {
                202
            }

        canvas.drawCircle(
            cx,
            cy,
            minSide * 0.66f,
            ambientPaint
        )

        // Four concentric/tilted energy shells.
        drawEnergyRing(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radiusX = width * 0.205f,
            radiusY = height * 0.345f,
            tiltDeg = -8f,
            startDeg = ((phase * 15.0) % 360.0).toFloat(),
            sweepDeg = 244f,
            color = withAlpha(pale, 74),
            widthDp = 0.75f
        )

        drawEnergyRing(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radiusX = width * 0.165f,
            radiusY = height * 0.285f,
            tiltDeg = 14f,
            startDeg = ((phase * -20.0) % 360.0).toFloat(),
            sweepDeg = 214f,
            color = withAlpha(accent, 126),
            widthDp = 1.0f
        )

        drawEnergyRing(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radiusX = width * 0.125f,
            radiusY = height * 0.225f,
            tiltDeg = -22f,
            startDeg = ((phase * 27.0) % 360.0).toFloat(),
            sweepDeg = 178f,
            color = withAlpha(violet, 90),
            widthDp = 0.85f
        )

        drawEnergyRing(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radiusX = width * 0.087f,
            radiusY = height * 0.165f,
            tiltDeg = 26f,
            startDeg = ((phase * -34.0) % 360.0).toFloat(),
            sweepDeg = 156f,
            color = withAlpha(accent, 184),
            widthDp = 1.15f
        )

        // Fine concentric nucleus boundaries.
        finePaint.color =
            withAlpha(
                pale,
                108
            )
        finePaint.strokeWidth =
            dp(0.75f)

        canvas.drawCircle(
            cx,
            cy,
            minSide * 0.145f,
            finePaint
        )

        finePaint.color =
            withAlpha(
                accent,
                86
            )

        canvas.drawCircle(
            cx,
            cy,
            minSide * 0.205f,
            finePaint
        )

        // State waveform. This is deliberately synthetic/state-reactive rather
        // than falsely presented as measured microphone amplitude.
        drawWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            accent = accent,
            pale = pale,
            amplitude = amplitude,
            motion = motion
        )

        // Deterministic particles — no Random/allocation per frame.
        drawParticles(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            accent = accent,
            pale = pale,
            violet = violet,
            motion = motion
        )

        // Central luminous core.
        corePaint.alpha =
            248

        canvas.drawCircle(
            cx,
            cy,
            minSide *
                (
                    0.080f +
                        amplitude *
                        0.035f
                    ),
            corePaint
        )

        finePaint.color =
            withAlpha(
                Color.WHITE,
                190
            )
        finePaint.strokeWidth =
            dp(0.85f)

        canvas.drawCircle(
            cx,
            cy,
            minSide * 0.092f,
            finePaint
        )

        // AYANA wordmark in the exact visual center.
        textPaint.textSize =
            min(
                height * 0.175f,
                dp(22f)
            )
        textPaint.color =
            Color.WHITE
        textPaint.alpha =
            245

        val baseline =
            cy -
                (
                    textPaint.ascent() +
                        textPaint.descent()
                    ) /
                2f

        canvas.drawText(
            "AYANA",
            cx,
            baseline,
            textPaint
        )

        // Short bright energy line under the wordmark.
        wavePaint.color =
            withAlpha(
                accent,
                225
            )
        wavePaint.strokeWidth =
            dp(1.2f)

        canvas.drawLine(
            cx - width * 0.055f,
            cy + height * 0.135f,
            cx + width * 0.055f,
            cy + height * 0.135f,
            wavePaint
        )

        if (attached) {
            postInvalidateDelayed(
                frameDelayMs(state)
            )
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

        shaderWidth = width
        shaderHeight = height
        shaderState = state

        val cx =
            width / 2f

        val cy =
            height / 2f

        val minSide =
            min(
                width.toFloat(),
                height.toFloat()
            )

        val accent =
            accentFor(state)

        val pale =
            Color.rgb(
                224,
                247,
                255
            )

        ambientShader =
            RadialGradient(
                cx,
                cy,
                minSide * 0.72f,
                intArrayOf(
                    withAlpha(accent, 52),
                    withAlpha(accent, 26),
                    withAlpha(Color.rgb(99, 102, 241), 13),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.35f,
                    0.70f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        coreShader =
            RadialGradient(
                cx - minSide * 0.018f,
                cy - minSide * 0.025f,
                minSide * 0.155f,
                intArrayOf(
                    Color.WHITE,
                    withAlpha(pale, 252),
                    withAlpha(accent, 242),
                    withAlpha(accent, 92),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.16f,
                    0.42f,
                    0.72f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        ambientPaint.shader =
            ambientShader

        corePaint.shader =
            coreShader
    }

    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        accent: Int,
        pale: Int,
        amplitude: Float,
        motion: Motion
    ) {
        val left =
            width * 0.055f

        val right =
            width * 0.945f

        val span =
            right - left

        val centerX =
            cx

        val phase =
            now.toDouble() /
                motion.waveDivisor

        val maxAmplitude =
            height *
                (
                    0.075f +
                        amplitude *
                        0.125f
                    )

        wavePath.reset()
        secondaryWavePath.reset()

        val points =
            72

        for (
            i in
            0..points
        ) {
            val p =
                i.toFloat() /
                    points.toFloat()

            val x =
                left +
                    span *
                    p

            val normalizedX =
                (
                    x -
                        centerX
                    ) /
                    (
                        span *
                            0.5f
                        )

            val envelope =
                (
                    1f -
                        normalizedX *
                            normalizedX
                    )
                    .coerceIn(
                        0f,
                        1f
                    )

            val carrier =
                sin(
                    phase +
                        p *
                            PI *
                            8.4
                )
                    .toFloat()

            val detail =
                sin(
                    phase *
                        1.72 +
                        p *
                            PI *
                            17.0
                )
                    .toFloat()

            val y =
                cy +
                    (
                        carrier *
                            0.72f +
                            detail *
                                0.28f
                        ) *
                    maxAmplitude *
                    envelope

            val y2 =
                cy +
                    sin(
                        phase *
                            0.73 +
                            p *
                                PI *
                                6.2
                    )
                        .toFloat() *
                    maxAmplitude *
                    0.46f *
                    envelope

            if (i == 0) {
                wavePath.moveTo(
                    x,
                    y
                )
                secondaryWavePath.moveTo(
                    x,
                    y2
                )
            } else {
                wavePath.lineTo(
                    x,
                    y
                )
                secondaryWavePath.lineTo(
                    x,
                    y2
                )
            }
        }

        wavePaint.color =
            withAlpha(
                accent,
                215
            )
        wavePaint.strokeWidth =
            dp(1.25f)

        canvas.drawPath(
            wavePath,
            wavePaint
        )

        wavePaint.color =
            withAlpha(
                pale,
                92
            )
        wavePaint.strokeWidth =
            dp(0.75f)

        canvas.drawPath(
            secondaryWavePath,
            wavePaint
        )

        wavePaint.color =
            withAlpha(
                accent,
                60
            )
        wavePaint.strokeWidth =
            dp(0.65f)

        canvas.drawLine(
            left,
            cy,
            right,
            cy,
            wavePaint
        )
    }

    private fun drawParticles(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        accent: Int,
        pale: Int,
        violet: Int,
        motion: Motion
    ) {
        val particleCount =
            14

        for (
            i in
            0 until particleCount
        ) {
            val seed =
                i *
                    2.39996323

            val angle =
                seed +
                    now.toDouble() /
                        (
                            motion.particleDivisor +
                                i *
                                    83.0
                            )

            val rx =
                width *
                    (
                        0.10f +
                            (
                                i %
                                    5
                                ) *
                                0.027f
                        )

            val ry =
                height *
                    (
                        0.17f +
                            (
                                i %
                                    4
                                ) *
                                0.055f
                        )

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    rx

            val y =
                cy +
                    sin(
                        angle *
                            1.13 +
                            seed
                    )
                        .toFloat() *
                    ry

            particlePaint.color =
                when (
                    i %
                        3
                    ) {
                    0 ->
                        accent

                    1 ->
                        pale

                    else ->
                        violet
                }

            particlePaint.alpha =
                92 +
                    (
                        i %
                            4
                        ) *
                        28

            val radius =
                dp(
                    if (
                        i %
                            5 ==
                            0
                    ) {
                        1.25f
                    } else {
                        0.75f
                    }
                )

            canvas.drawCircle(
                x,
                y,
                radius,
                particlePaint
            )
        }
    }

    private fun drawEnergyRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radiusX: Float,
        radiusY: Float,
        tiltDeg: Float,
        startDeg: Float,
        sweepDeg: Float,
        color: Int,
        widthDp: Float
    ) {
        val save =
            canvas.save()

        canvas.rotate(
            tiltDeg,
            cx,
            cy
        )

        orbitBounds.set(
            cx - radiusX,
            cy - radiusY,
            cx + radiusX,
            cy + radiusY
        )

        ringPaint.color =
            color
        ringPaint.alpha =
            Color.alpha(color)
        ringPaint.strokeWidth =
            dp(widthDp)

        canvas.drawArc(
            orbitBounds,
            startDeg,
            sweepDeg,
            false,
            ringPaint
        )

        ringPaint.alpha =
            (
                Color.alpha(color) *
                    0.55f
                )
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        canvas.drawArc(
            orbitBounds,
            startDeg + 184f,
            sweepDeg * 0.38f,
            false,
            ringPaint
        )

        canvas.restoreToCount(save)
    }

    private fun amplitudeFor(
        state: String
    ): Float =
        when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                0.90f

            AyanaVoiceService.STATE_EXECUTING ->
                1.00f

            AyanaVoiceService.STATE_THINKING ->
                0.72f

            AyanaVoiceService.STATE_SPEAKING ->
                0.86f

            AyanaVoiceService.STATE_SUCCESS ->
                0.48f

            AyanaVoiceService.STATE_ERROR ->
                0.52f

            AyanaVoiceService.STATE_CANCELLED ->
                0.42f

            AyanaVoiceService.STATE_STOPPED ->
                0.20f

            else ->
                0.58f
        }

    private fun motionFor(
        state: String
    ): Motion =
        when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                Motion(
                    phaseDivisor = 132.0,
                    waveDivisor = 165.0,
                    particleDivisor = 920.0
                )

            AyanaVoiceService.STATE_EXECUTING ->
                Motion(
                    phaseDivisor = 116.0,
                    waveDivisor = 145.0,
                    particleDivisor = 820.0
                )

            AyanaVoiceService.STATE_THINKING ->
                Motion(
                    phaseDivisor = 180.0,
                    waveDivisor = 220.0,
                    particleDivisor = 1120.0
                )

            AyanaVoiceService.STATE_SPEAKING ->
                Motion(
                    phaseDivisor = 148.0,
                    waveDivisor = 178.0,
                    particleDivisor = 960.0
                )

            AyanaVoiceService.STATE_STOPPED ->
                Motion(
                    phaseDivisor = 390.0,
                    waveDivisor = 460.0,
                    particleDivisor = 1900.0
                )

            else ->
                Motion(
                    phaseDivisor = 245.0,
                    waveDivisor = 285.0,
                    particleDivisor = 1320.0
                )
        }

    private fun accentFor(
        state: String
    ): Int =
        Color.parseColor(
            when (state) {
                AyanaVoiceService.STATE_COMMAND ->
                    "#22D3EE"

                AyanaVoiceService.STATE_THINKING ->
                    "#A78BFA"

                AyanaVoiceService.STATE_EXECUTING ->
                    "#2DD4BF"

                AyanaVoiceService.STATE_SUCCESS ->
                    "#4ADE80"

                AyanaVoiceService.STATE_ERROR ->
                    "#F87171"

                AyanaVoiceService.STATE_CANCELLED ->
                    "#FBBF24"

                AyanaVoiceService.STATE_SPEAKING ->
                    "#818CF8"

                AyanaVoiceService.STATE_STOPPED ->
                    "#64748B"

                else ->
                    "#38BDF8"
            }
        )

    private fun frameDelayMs(
        state: String
    ): Long =
        when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING ->
                28L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_LISTENING ->
                32L

            else ->
                55L
        }

    private fun withAlpha(
        color: Int,
        alpha: Int
    ): Int =
        Color.argb(
            alpha.coerceIn(
                0,
                255
            ),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun dp(
        value: Float
    ): Float =
        value *
            density

    private data class Motion(
        val phaseDivisor: Double,
        val waveDivisor: Double,
        val particleDivisor: Double
    )
}
