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
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v1.3 — CONTAINED SIGNATURE FIELD.
 *
 * Main-screen visual-only renderer.
 *
 * Design contract:
 * - one dominant circular AYANA energy field;
 * - cyan / blue / violet premium palette for the listening state;
 * - horizontal audio-style waveform through the centre;
 * - AYANA signature inside the field;
 * - all circular glow, particles and flares are strictly contained inside the View;
 * - state-aware palette / motion without changing VoiceService behaviour;
 * - no microphone capture and no additional runtime permissions;
 * - waveform is decorative/state-reactive and must not be treated as measured audio.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val fillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val ringPaint =
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

    private val particlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val wavePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val wordmarkPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }

    private val wordmarkGlowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val mainWavePath = Path()
    private val fineWavePath = Path()
    private val energyPath = Path()
    private val bounds = RectF()
    private val contentRect = RectF()

    private var attached = false

    private var shaderWidth = -1
    private var shaderHeight = -1
    private var shaderState = ""

    private var ambientShader: RadialGradient? = null
    private var nucleusShader: RadialGradient? = null
    private var coreHaloShader: RadialGradient? = null
    private var horizontalShader: LinearGradient? = null

    private var palette =
        Palette(
            cyan = Color.parseColor("#28E9FF"),
            blue = Color.parseColor("#238CFF"),
            violet = Color.parseColor("#8E5BFF"),
            white = Color.parseColor("#F5FDFF"),
            deep = Color.parseColor("#071229")
        )

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

        val edgeInset =
            dp(9.0f)

        contentRect.set(
            edgeInset,
            edgeInset,
            width.toFloat() - edgeInset,
            height.toFloat() - edgeInset
        )

        if (
            contentRect.width() <= 0f ||
            contentRect.height() <= 0f
        ) {
            return
        }

        val clipSave =
            canvas.save()

        // Hard containment guarantee: no glow, particle or waveform can escape
        // the inner visualizer window even on unusual tablet aspect ratios.
        canvas.clipRoundRect(
            contentRect,
            dp(14.0f),
            dp(14.0f)
        )

        val cx =
            contentRect.centerX()

        val cy =
            contentRect.centerY()

        val minSide =
            min(
                contentRect.width(),
                contentRect.height()
            )

        // 0.365 keeps the largest halo (1.26R) below the half-height boundary.
        // This is the key v1.3 change that prevents the circular field from
        // touching or crossing the visualizer frame.
        val radius =
            minSide * 0.365f

        val activity =
            stateActivity(state)

        val breathe =
            (
                0.5 +
                    0.5 *
                    sin(
                        now /
                            (720.0 - activity * 150.0)
                    )
                ).toFloat()

        val outerPhase =
            continuousAngle(
                now,
                cycleMs = 12600f - activity * 2500f,
                reverse = false
            )

        val reversePhase =
            continuousAngle(
                now,
                cycleMs = 16100f - activity * 2700f,
                reverse = true
            )

        val innerPhase =
            continuousAngle(
                now,
                cycleMs = 9200f - activity * 1700f,
                reverse = false
            )

        drawAmbientField(
            canvas,
            cx,
            cy,
            radius,
            breathe
        )

        drawOrbitGrid(
            canvas,
            cx,
            cy,
            radius
        )

        drawSignatureRing(
            canvas,
            cx,
            cy,
            radius * 1.00f,
            outerPhase + 7f,
            palette.cyan,
            232,
            2.05f
        )

        drawSignatureRing(
            canvas,
            cx,
            cy,
            radius * 0.91f,
            reversePhase + 88f,
            palette.violet,
            202,
            1.72f
        )

        drawSignatureRing(
            canvas,
            cx,
            cy,
            radius * 0.80f,
            innerPhase + 176f,
            palette.white,
            148,
            1.12f
        )

        drawPlasmaRibbon(
            canvas,
            cx,
            cy,
            radius * 0.78f,
            outerPhase,
            tilt = -26f,
            color = palette.cyan,
            alpha = 126,
            widthDp = 1.45f
        )

        drawPlasmaRibbon(
            canvas,
            cx,
            cy,
            radius * 0.75f,
            reversePhase + 71f,
            tilt = 28f,
            color = palette.violet,
            alpha = 118,
            widthDp = 1.35f
        )

        drawPlasmaRibbon(
            canvas,
            cx,
            cy,
            radius * 0.69f,
            innerPhase + 139f,
            tilt = 78f,
            color = palette.blue,
            alpha = 104,
            widthDp = 1.08f
        )

        drawParticleHalo(
            canvas,
            now,
            cx,
            cy,
            radius,
            activity
        )

        drawNucleus(
            canvas,
            cx,
            cy,
            radius,
            breathe
        )

        drawInnerLensRings(
            canvas,
            cx,
            cy,
            radius,
            innerPhase,
            reversePhase
        )

        drawWaveform(
            canvas,
            now,
            cx,
            cy,
            radius,
            activity
        )

        drawAyanaWordmark(
            canvas,
            cx,
            cy,
            radius
        )

        drawStarFlare(
            canvas,
            cx - radius * 0.56f,
            cy - radius * 0.43f,
            palette.white,
            0.92f
        )

        drawStarFlare(
            canvas,
            cx + radius * 0.49f,
            cy - radius * 0.55f,
            palette.violet,
            0.77f
        )

        drawStarFlare(
            canvas,
            cx + radius * 0.61f,
            cy + radius * 0.34f,
            palette.cyan,
            0.66f
        )

        canvas.restoreToCount(
            clipSave
        )

        if (attached) {
            postInvalidateDelayed(
                frameDelayMs(state)
            )
        }
    }

    private fun drawAmbientField(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fillPaint.shader = ambientShader
        fillPaint.alpha = 214
        canvas.drawCircle(
            cx,
            cy,
            radius * 1.26f,
            fillPaint
        )

        fillPaint.shader = coreHaloShader
        fillPaint.alpha = 226
        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.98f +
                        breathe * 0.018f
                    ),
            fillPaint
        )
    }

    private fun drawOrbitGrid(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        ringPaint.shader = null
        ringPaint.strokeWidth = dp(0.55f)

        val grid =
            floatArrayOf(
                1.10f,
                1.16f,
                1.22f
            )

        for (
            i in grid.indices
        ) {
            ringPaint.color =
                if (i % 2 == 0) {
                    palette.blue
                } else {
                    palette.violet
                }

            ringPaint.alpha =
                34 + i * 8

            canvas.drawCircle(
                cx,
                cy,
                radius * grid[i],
                ringPaint
            )
        }
    }

    private fun drawNucleus(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fillPaint.shader = nucleusShader
        fillPaint.alpha = 255

        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.43f +
                        breathe * 0.016f
                    ),
            fillPaint
        )
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
        palette = paletteFor(state)

        val cx =
            width / 2f

        val cy =
            height / 2f

        val minSide =
            min(
                width.toFloat(),
                height.toFloat()
            )

        ambientShader =
            RadialGradient(
                cx,
                cy,
                minSide * 0.47f,
                intArrayOf(
                    withAlpha(palette.blue, 80),
                    withAlpha(palette.violet, 54),
                    withAlpha(palette.cyan, 28),
                    withAlpha(palette.deep, 12),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.34f,
                    0.58f,
                    0.82f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        coreHaloShader =
            RadialGradient(
                cx,
                cy,
                minSide * 0.35f,
                intArrayOf(
                    withAlpha(palette.white, 16),
                    withAlpha(palette.cyan, 66),
                    withAlpha(palette.blue, 58),
                    withAlpha(palette.violet, 32),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.30f,
                    0.56f,
                    0.80f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        nucleusShader =
            RadialGradient(
                cx - minSide * 0.024f,
                cy - minSide * 0.038f,
                minSide * 0.19f,
                intArrayOf(
                    Color.WHITE,
                    palette.white,
                    palette.cyan,
                    withAlpha(palette.blue, 232),
                    withAlpha(palette.violet, 150),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.08f,
                    0.23f,
                    0.47f,
                    0.72f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        horizontalShader =
            LinearGradient(
                width * 0.06f,
                cy,
                width * 0.94f,
                cy,
                intArrayOf(
                    withAlpha(palette.cyan, 150),
                    palette.cyan,
                    palette.white,
                    palette.violet,
                    withAlpha(palette.violet, 160)
                ),
                floatArrayOf(
                    0f,
                    0.26f,
                    0.50f,
                    0.77f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )
    }

    private fun drawSignatureRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        bounds.set(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius
        )

        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha =
            (alpha * 0.18f)
                .toInt()
                .coerceIn(0, 255)
        glowPaint.strokeWidth =
            dp(widthDp * 4.8f)

        drawRingSegments(
            canvas,
            bounds,
            phase,
            glowPaint
        )

        ringPaint.shader = null
        ringPaint.color = color
        ringPaint.alpha = alpha
        ringPaint.strokeWidth =
            dp(widthDp)

        drawRingSegments(
            canvas,
            bounds,
            phase,
            ringPaint
        )
    }

    private fun drawRingSegments(
        canvas: Canvas,
        rect: RectF,
        phase: Float,
        paint: Paint
    ) {
        canvas.drawArc(
            rect,
            phase,
            112f,
            false,
            paint
        )

        canvas.drawArc(
            rect,
            phase + 172f,
            68f,
            false,
            paint
        )

        canvas.drawArc(
            rect,
            phase + 278f,
            42f,
            false,
            paint
        )
    }

    private fun drawPlasmaRibbon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        tilt: Float,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        energyPath.reset()

        val points = 104

        val tiltRad =
            Math.toRadians(
                tilt.toDouble()
            )

        val cosTilt =
            cos(tiltRad).toFloat()

        val sinTilt =
            sin(tiltRad).toFloat()

        val phaseRad =
            Math.toRadians(
                phase.toDouble()
            )

        for (
            i in 0..points
        ) {
            val t =
                i.toDouble() /
                    points.toDouble() *
                    PI *
                    2.0

            val radial =
                radius *
                    (
                        0.80f +
                            0.16f *
                            sin(
                                t * 3.0 +
                                    phaseRad
                            ).toFloat()
                        )

            val rawX =
                cos(t).toFloat() *
                    radial

            val rawY =
                sin(t).toFloat() *
                    radial *
                    0.76f

            val x =
                cx +
                    rawX * cosTilt -
                    rawY * sinTilt

            val y =
                cy +
                    rawX * sinTilt +
                    rawY * cosTilt

            if (i == 0) {
                energyPath.moveTo(
                    x,
                    y
                )
            } else {
                energyPath.lineTo(
                    x,
                    y
                )
            }
        }

        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha =
            (alpha * 0.17f)
                .toInt()
                .coerceIn(0, 255)
        glowPaint.strokeWidth =
            dp(widthDp * 4.7f)

        canvas.drawPath(
            energyPath,
            glowPaint
        )

        ringPaint.shader = null
        ringPaint.color = color
        ringPaint.alpha = alpha
        ringPaint.strokeWidth =
            dp(widthDp)

        canvas.drawPath(
            energyPath,
            ringPaint
        )
    }

    private fun drawParticleHalo(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        activity: Float
    ) {
        val count =
            78

        for (
            i in 0 until count
        ) {
            val seed =
                i *
                    2.399963229728653

            val angle =
                seed +
                    now.toDouble() /
                    (
                        6100.0 -
                            activity * 1300.0 +
                            i * 15.0
                        )

            val band =
                when (i % 4) {
                    0 -> 0.91f
                    1 -> 0.98f
                    2 -> 1.04f
                    else -> 1.10f
                }

            val jitter =
                0.014f *
                    sin(
                        seed * 2.1 +
                            now / 1200.0
                    ).toFloat()

            val r =
                radius *
                    (
                        band +
                            jitter
                        )

            val x =
                cx +
                    cos(angle).toFloat() *
                    r

            val y =
                cy +
                    sin(angle).toFloat() *
                    r *
                    0.93f

            val color =
                when (i % 5) {
                    0 -> palette.white
                    1, 2 -> palette.cyan
                    3 -> palette.blue
                    else -> palette.violet
                }

            val alpha =
                54 +
                    (i % 6) * 22

            val dot =
                dp(
                    when {
                        i % 19 == 0 -> 1.45f
                        i % 7 == 0 -> 0.96f
                        else -> 0.56f
                    }
                )

            particlePaint.shader = null
            particlePaint.color = color
            particlePaint.alpha =
                alpha.coerceIn(
                    0,
                    205
                )

            canvas.drawCircle(
                x,
                y,
                dot,
                particlePaint
            )

            if (i % 19 == 0) {
                particlePaint.alpha = 30
                canvas.drawCircle(
                    x,
                    y,
                    dot * 3.8f,
                    particlePaint
                )
            }
        }
    }

    private fun drawInnerLensRings(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        reversePhase: Float
    ) {
        val radii =
            floatArrayOf(
                0.31f,
                0.43f,
                0.55f,
                0.65f
            )

        for (
            i in radii.indices
        ) {
            val r =
                radius * radii[i]

            ringPaint.shader = null
            ringPaint.color =
                when (i % 3) {
                    0 -> palette.white
                    1 -> palette.cyan
                    else -> palette.violet
                }

            ringPaint.alpha =
                56 +
                    i * 18

            ringPaint.strokeWidth =
                dp(
                    if (i == 0) {
                        0.92f
                    } else {
                        0.58f
                    }
                )

            canvas.drawCircle(
                cx,
                cy,
                r,
                ringPaint
            )
        }

        bounds.set(
            cx - radius * 0.59f,
            cy - radius * 0.59f,
            cx + radius * 0.59f,
            cy + radius * 0.59f
        )

        ringPaint.color = palette.cyan
        ringPaint.alpha = 184
        ringPaint.strokeWidth = dp(1.36f)

        canvas.drawArc(
            bounds,
            phase + 20f,
            78f,
            false,
            ringPaint
        )

        canvas.drawArc(
            bounds,
            phase + 205f,
            43f,
            false,
            ringPaint
        )

        bounds.set(
            cx - radius * 0.48f,
            cy - radius * 0.48f,
            cx + radius * 0.48f,
            cy + radius * 0.48f
        )

        ringPaint.color = palette.white
        ringPaint.alpha = 108
        ringPaint.strokeWidth = dp(0.80f)

        canvas.drawArc(
            bounds,
            reversePhase + 65f,
            126f,
            false,
            ringPaint
        )
    }

    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        activity: Float
    ) {
        val left =
            contentRect.left +
                dp(6.0f)

        val right =
            contentRect.right -
                dp(6.0f)

        val span =
            right - left

        if (span <= 0f) {
            return
        }

        val phase =
            now /
                (
                    310.0 -
                        activity * 85.0
                    )

        val maxAmplitude =
            min(
                contentRect.height() * 0.145f,
                radius * 0.40f
            ) *
                (
                    0.74f +
                        activity * 0.26f
                    )

        mainWavePath.reset()
        fineWavePath.reset()

        val points = 132

        for (
            i in 0..points
        ) {
            val p =
                i.toFloat() /
                    points.toFloat()

            val x =
                left +
                    span * p

            val nx =
                (
                    x - cx
                    ) /
                    (
                        span * 0.5f
                        )

            val center =
                (
                    1f -
                        nx * nx
                    ).coerceIn(
                    0f,
                    1f
                )

            val envelope =
                0.22f +
                    0.78f * center

            val wave =
                (
                    sin(
                        phase +
                            p * PI * 12.0
                    ) * 0.50 +
                        sin(
                            phase * 1.69 -
                                p * PI * 25.0
                        ) * 0.30 +
                        sin(
                            phase * 0.62 +
                                p * PI * 5.4
                        ) * 0.20
                    ).toFloat()

            val fine =
                (
                    sin(
                        phase * 1.21 -
                            p * PI * 18.0
                    ) * 0.65 +
                        sin(
                            phase * 0.76 +
                                p * PI * 7.2
                        ) * 0.35
                    ).toFloat()

            val y =
                cy +
                    wave *
                    maxAmplitude *
                    envelope

            val yFine =
                cy +
                    fine *
                    maxAmplitude *
                    envelope *
                    0.39f

            if (i == 0) {
                mainWavePath.moveTo(
                    x,
                    y
                )
                fineWavePath.moveTo(
                    x,
                    yFine
                )
            } else {
                mainWavePath.lineTo(
                    x,
                    y
                )
                fineWavePath.lineTo(
                    x,
                    yFine
                )
            }
        }

        wavePaint.shader = horizontalShader
        wavePaint.alpha = 62
        wavePaint.strokeWidth = dp(0.66f)

        val bars = 96

        for (
            i in 0 until bars
        ) {
            val p =
                i.toFloat() /
                    (bars - 1).toFloat()

            val x =
                left +
                    span * p

            val nx =
                (
                    x - cx
                    ) /
                    (
                        span * 0.5f
                        )

            val envelope =
                0.20f +
                    0.80f *
                    (
                        1f -
                            nx * nx
                        ).coerceIn(
                        0f,
                        1f
                    )

            val energy =
                abs(
                    sin(
                        phase * 1.09 +
                            i * 0.67
                    ) * 0.63 +
                        sin(
                            phase * 0.47 -
                                i * 1.19
                        ) * 0.37
                ).toFloat()

            val half =
                maxAmplitude *
                    envelope *
                    (
                        0.06f +
                            energy *
                            (
                                0.34f +
                                    activity * 0.10f
                                )
                        )

            canvas.drawLine(
                x,
                cy - half,
                x,
                cy + half,
                wavePaint
            )
        }

        wavePaint.shader = null
        wavePaint.color = palette.white
        wavePaint.alpha = 104
        wavePaint.strokeWidth = dp(0.64f)

        canvas.drawLine(
            left,
            cy,
            right,
            cy,
            wavePaint
        )

        glowPaint.shader = horizontalShader
        glowPaint.alpha = 42
        glowPaint.strokeWidth = dp(7.2f)

        canvas.drawPath(
            mainWavePath,
            glowPaint
        )

        glowPaint.alpha = 70
        glowPaint.strokeWidth = dp(3.5f)

        canvas.drawPath(
            mainWavePath,
            glowPaint
        )

        wavePaint.shader = horizontalShader
        wavePaint.alpha = 248
        wavePaint.strokeWidth = dp(1.46f)

        canvas.drawPath(
            mainWavePath,
            wavePaint
        )

        wavePaint.alpha = 116
        wavePaint.strokeWidth = dp(0.72f)

        canvas.drawPath(
            fineWavePath,
            wavePaint
        )
    }

    private fun drawAyanaWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val totalWidth =
            radius * 1.50f

        val letterHeight =
            radius * 0.34f

        val gap =
            totalWidth * 0.045f

        val unit =
            (
                totalWidth -
                    gap * 4f
                ) /
                5f

        val startX =
            cx -
                totalWidth / 2f

        val top =
            cy -
                letterHeight / 2f

        val bottom =
            cy +
                letterHeight / 2f

        wordmarkGlowPaint.shader = horizontalShader
        wordmarkGlowPaint.alpha = 48
        wordmarkGlowPaint.strokeWidth = dp(8.8f)

        wordmarkPaint.shader = horizontalShader
        wordmarkPaint.alpha = 248
        wordmarkPaint.strokeWidth = dp(2.5f)

        drawWordmarkPass(
            canvas,
            startX,
            top,
            bottom,
            unit,
            gap,
            letterHeight,
            wordmarkGlowPaint
        )

        drawWordmarkPass(
            canvas,
            startX,
            top,
            bottom,
            unit,
            gap,
            letterHeight,
            wordmarkPaint
        )
    }

    private fun drawWordmarkPass(
        canvas: Canvas,
        startX: Float,
        top: Float,
        bottom: Float,
        unit: Float,
        gap: Float,
        letterHeight: Float,
        paint: Paint
    ) {
        drawA(
            canvas,
            startX,
            top,
            unit,
            letterHeight,
            paint
        )

        val yX =
            startX +
                (unit + gap)

        drawY(
            canvas,
            yX,
            top,
            unit,
            letterHeight,
            paint
        )

        val a2X =
            startX +
                (unit + gap) * 2f

        drawA(
            canvas,
            a2X,
            top,
            unit,
            letterHeight,
            paint
        )

        val nX =
            startX +
                (unit + gap) * 3f

        drawN(
            canvas,
            nX,
            top,
            bottom,
            unit,
            paint
        )

        val a3X =
            startX +
                (unit + gap) * 4f

        drawA(
            canvas,
            a3X,
            top,
            unit,
            letterHeight,
            paint
        )
    }

    private fun drawA(
        canvas: Canvas,
        x: Float,
        top: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        val bottom =
            top + height

        val center =
            x +
                width / 2f

        canvas.drawLine(
            x,
            bottom,
            center,
            top,
            paint
        )

        canvas.drawLine(
            center,
            top,
            x + width,
            bottom,
            paint
        )
    }

    private fun drawY(
        canvas: Canvas,
        x: Float,
        top: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        val junctionY =
            top +
                height * 0.49f

        val center =
            x +
                width / 2f

        canvas.drawLine(
            x,
            top,
            center,
            junctionY,
            paint
        )

        canvas.drawLine(
            x + width,
            top,
            center,
            junctionY,
            paint
        )

        canvas.drawLine(
            center,
            junctionY,
            center,
            top + height,
            paint
        )
    }

    private fun drawN(
        canvas: Canvas,
        x: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        canvas.drawLine(
            x,
            bottom,
            x,
            top,
            paint
        )

        canvas.drawLine(
            x,
            top,
            x + width,
            bottom,
            paint
        )

        canvas.drawLine(
            x + width,
            bottom,
            x + width,
            top,
            paint
        )
    }

    private fun drawStarFlare(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
        strength: Float
    ) {
        particlePaint.shader = null
        particlePaint.color = color
        particlePaint.alpha =
            (205f * strength)
                .toInt()
                .coerceIn(
                    0,
                    255
                )

        canvas.drawCircle(
            x,
            y,
            dp(1.15f * strength),
            particlePaint
        )

        ringPaint.shader = null
        ringPaint.color = color
        ringPaint.alpha =
            (110f * strength)
                .toInt()
                .coerceIn(
                    0,
                    180
                )
        ringPaint.strokeWidth = dp(0.68f)

        val long =
            dp(7.0f * strength)

        val short =
            dp(3.2f * strength)

        canvas.drawLine(
            x - long,
            y,
            x + long,
            y,
            ringPaint
        )

        canvas.drawLine(
            x,
            y - short,
            x,
            y + short,
            ringPaint
        )
    }

    private fun stateActivity(
        state: String
    ): Float {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND -> 1.00f
            AyanaVoiceService.STATE_THINKING -> 0.82f
            AyanaVoiceService.STATE_EXECUTING -> 0.92f
            AyanaVoiceService.STATE_SPEAKING -> 0.96f
            AyanaVoiceService.STATE_SUCCESS -> 0.48f
            AyanaVoiceService.STATE_ERROR -> 0.72f
            AyanaVoiceService.STATE_CANCELLED -> 0.54f
            AyanaVoiceService.STATE_LISTENING -> 0.64f
            else -> 0.36f
        }
    }

    private fun continuousAngle(
        nowMs: Long,
        cycleMs: Float,
        reverse: Boolean
    ): Float {
        val turns =
            nowMs.toDouble() /
                cycleMs.toDouble()

        val fraction =
            turns -
                kotlin.math.floor(
                    turns
                )

        val degrees =
            (fraction * 360.0)
                .toFloat()

        return if (reverse) {
            (360f - degrees) % 360f
        } else {
            degrees
        }
    }

    private fun paletteFor(
        state: String
    ): Palette {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                Palette(
                    cyan = Color.parseColor("#35F1FF"),
                    blue = Color.parseColor("#1D9FFF"),
                    violet = Color.parseColor("#8D5CFF"),
                    white = Color.parseColor("#F6FEFF"),
                    deep = Color.parseColor("#07162E")
                )

            AyanaVoiceService.STATE_THINKING ->
                Palette(
                    cyan = Color.parseColor("#63D9FF"),
                    blue = Color.parseColor("#536BFF"),
                    violet = Color.parseColor("#B56DFF"),
                    white = Color.parseColor("#FBF7FF"),
                    deep = Color.parseColor("#140B2E")
                )

            AyanaVoiceService.STATE_EXECUTING ->
                Palette(
                    cyan = Color.parseColor("#31FFE0"),
                    blue = Color.parseColor("#21A7D6"),
                    violet = Color.parseColor("#5A77FF"),
                    white = Color.parseColor("#F2FFFC"),
                    deep = Color.parseColor("#072826")
                )

            AyanaVoiceService.STATE_SPEAKING ->
                Palette(
                    cyan = Color.parseColor("#55D9FF"),
                    blue = Color.parseColor("#446FFF"),
                    violet = Color.parseColor("#A957FF"),
                    white = Color.parseColor("#F9FBFF"),
                    deep = Color.parseColor("#111432")
                )

            AyanaVoiceService.STATE_SUCCESS ->
                Palette(
                    cyan = Color.parseColor("#4DF7C5"),
                    blue = Color.parseColor("#2DB9CF"),
                    violet = Color.parseColor("#5CE1A4"),
                    white = Color.parseColor("#F5FFF9"),
                    deep = Color.parseColor("#09241C")
                )

            AyanaVoiceService.STATE_ERROR ->
                Palette(
                    cyan = Color.parseColor("#FF7E9E"),
                    blue = Color.parseColor("#E54878"),
                    violet = Color.parseColor("#FF4CB0"),
                    white = Color.parseColor("#FFF5F7"),
                    deep = Color.parseColor("#2D0815")
                )

            AyanaVoiceService.STATE_CANCELLED ->
                Palette(
                    cyan = Color.parseColor("#FFD66D"),
                    blue = Color.parseColor("#F6A73A"),
                    violet = Color.parseColor("#FFB457"),
                    white = Color.parseColor("#FFFCEF"),
                    deep = Color.parseColor("#2A1C08")
                )

            else ->
                Palette(
                    cyan = Color.parseColor("#28E9FF"),
                    blue = Color.parseColor("#238CFF"),
                    violet = Color.parseColor("#8E5BFF"),
                    white = Color.parseColor("#F5FDFF"),
                    deep = Color.parseColor("#071229")
                )
        }
    }

    private fun frameDelayMs(
        state: String
    ): Long {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING -> 29L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SPEAKING -> 31L

            AyanaVoiceService.STATE_LISTENING -> 35L

            else -> 52L
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
        val cyan: Int,
        val blue: Int,
        val violet: Int,
        val white: Int,
        val deep: Int
    )
}
