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
 * AYANA Core Visualizer v1.3 — FINAL SIGNATURE CORE R4.
 *
 * Visual-only renderer for the main AYANA card.
 *
 * R4 goals:
 * - one dominant glass/energy sphere with three clean broken HUD rings;
 * - AYANA is the primary visual identity, wider and thicker than R3;
 * - the synthetic waveform remains behind the wordmark and is attenuated through its center;
 * - remove plasma-petal / wireframe clutter while keeping subtle particle depth;
 * - cyan/blue is primary and violet remains an accent;
 * - keep the renderer deterministic, allocation-light and independent from Accessibility;
 * - never present the synthetic waveform as measured microphone amplitude.
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

    private val mainWavePath =
        Path()

    private val fineWavePath =
        Path()

    private val energyPath =
        Path()

    private val bounds =
        RectF()

    private var attached =
        false

    private var shaderWidth =
        -1

    private var shaderHeight =
        -1

    private var shaderState =
        ""

    private var ambientShader:
        RadialGradient? = null

    private var nucleusShader:
        RadialGradient? = null

    private var coreHaloShader:
        RadialGradient? = null

    private var horizontalShader:
        LinearGradient? = null

    private var palette =
        Palette(
            cyan = Color.parseColor("#27E8FF"),
            blue = Color.parseColor("#2388FF"),
            violet = Color.parseColor("#A14CFF"),
            white = Color.parseColor("#F3FDFF"),
            deep = Color.parseColor("#091331")
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

        val cx =
            width / 2f

        val cy =
            height / 2f

        val minSide =
            min(
                width.toFloat(),
                height.toFloat()
            )

        val radius =
            minSide * 0.455f

        val breathe =
            (
                0.5 +
                    0.5 *
                    sin(
                        now / 680.0
                    )
                )
                .toFloat()

        val outerPhase =
            continuousAngle(
                now,
                cycleMs = 12600f,
                reverse = false
            )

        val reversePhase =
            continuousAngle(
                now,
                cycleMs = 16100f,
                reverse = true
            )

        val innerPhase =
            continuousAngle(
                now,
                cycleMs = 9600f,
                reverse = false
            )

        // 1) Quiet ambient field. The center is one object, not a stack of
        // competing wireframes.
        fillPaint.shader =
            ambientShader
        fillPaint.alpha =
            220
        canvas.drawCircle(
            cx,
            cy,
            radius * 1.22f,
            fillPaint
        )

        fillPaint.shader =
            coreHaloShader
        fillPaint.alpha =
            214
        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.90f +
                        breathe * 0.018f
                    ),
            fillPaint
        )

        // 2) Three broken circular HUD rings. R4 deliberately removes the R3
        // plasma petals and dense inner lens stack.
        drawSignatureRing(
            canvas,
            cx,
            cy,
            radius * 0.985f,
            outerPhase + 8f,
            palette.cyan,
            196,
            1.62f
        )

        drawSignatureRing(
            canvas,
            cx,
            cy,
            radius * 0.855f,
            reversePhase + 94f,
            palette.violet,
            146,
            1.08f
        )

        drawSignatureRing(
            canvas,
            cx,
            cy,
            radius * 0.705f,
            innerPhase + 176f,
            palette.white,
            100,
            0.72f
        )

        // 3) Subtle halo particles only; they support depth instead of becoming
        // a second focal object.
        drawParticleHalo(
            canvas,
            now,
            cx,
            cy,
            radius
        )

        // 4) One luminous nucleus behind both waveform and signature.
        fillPaint.shader =
            nucleusShader
        fillPaint.alpha =
            255
        canvas.drawCircle(
            cx,
            cy,
            radius *
                (
                    0.50f +
                        breathe * 0.012f
                    ),
            fillPaint
        )

        // A single quiet inner glass boundary keeps the sphere readable without
        // the old lens-stack clutter.
        ringPaint.shader =
            null
        ringPaint.color =
            palette.cyan
        ringPaint.alpha =
            76
        ringPaint.strokeWidth =
            dp(0.72f)
        canvas.drawCircle(
            cx,
            cy,
            radius * 0.565f,
            ringPaint
        )

        // 5) Synthetic energy waveform. It is deliberately reduced through the
        // center so AYANA remains visually unobstructed.
        drawWaveform(
            canvas,
            now,
            cx,
            cy,
            radius
        )

        // 6) Dominant signature.
        drawAyanaWordmark(
            canvas,
            cx,
            cy,
            radius
        )

        // 7) Only two optical accents.
        drawStarFlare(
            canvas,
            cx - radius * 0.62f,
            cy - radius * 0.45f,
            palette.white,
            0.88f
        )

        drawStarFlare(
            canvas,
            cx + radius * 0.61f,
            cy - radius * 0.58f,
            palette.violet,
            0.64f
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
                minSide * 0.71f,
                intArrayOf(
                    withAlpha(palette.blue, 74),
                    withAlpha(palette.violet, 48),
                    withAlpha(palette.cyan, 24),
                    withAlpha(palette.deep, 14),
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

        coreHaloShader =
            RadialGradient(
                cx,
                cy,
                minSide * 0.43f,
                intArrayOf(
                    withAlpha(palette.white, 18),
                    withAlpha(palette.cyan, 60),
                    withAlpha(palette.blue, 52),
                    withAlpha(palette.violet, 30),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.32f,
                    0.58f,
                    0.80f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        nucleusShader =
            RadialGradient(
                cx - minSide * 0.035f,
                cy - minSide * 0.055f,
                minSide * 0.27f,
                intArrayOf(
                    Color.WHITE,
                    palette.white,
                    palette.cyan,
                    withAlpha(palette.blue, 226),
                    withAlpha(palette.violet, 150),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.08f,
                    0.24f,
                    0.47f,
                    0.72f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        horizontalShader =
            LinearGradient(
                width * 0.035f,
                cy,
                width * 0.965f,
                cy,
                intArrayOf(
                    withAlpha(palette.cyan, 170),
                    palette.cyan,
                    palette.white,
                    palette.violet,
                    withAlpha(palette.violet, 175)
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

        // Broad glow pass.
        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha =
            (alpha * 0.20f)
                .toInt()
                .coerceIn(0, 255)
        glowPaint.strokeWidth =
            dp(widthDp * 5.4f)

        canvas.drawArc(
            bounds,
            phase,
            116f,
            false,
            glowPaint
        )
        canvas.drawArc(
            bounds,
            phase + 176f,
            72f,
            false,
            glowPaint
        )
        canvas.drawArc(
            bounds,
            phase + 282f,
            38f,
            false,
            glowPaint
        )

        ringPaint.shader = null
        ringPaint.color = color
        ringPaint.alpha = alpha
        ringPaint.strokeWidth =
            dp(widthDp)

        canvas.drawArc(
            bounds,
            phase,
            116f,
            false,
            ringPaint
        )
        canvas.drawArc(
            bounds,
            phase + 176f,
            72f,
            false,
            ringPaint
        )
        canvas.drawArc(
            bounds,
            phase + 282f,
            38f,
            false,
            ringPaint
        )
    }

    private fun drawPlasmaPetal(
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

        val points =
            96

        val tiltRad =
            Math.toRadians(
                tilt.toDouble()
            )

        val cosTilt =
            cos(tiltRad)
                .toFloat()

        val sinTilt =
            sin(tiltRad)
                .toFloat()

        val phaseRad =
            Math.toRadians(
                phase.toDouble()
            )

        for (
            i in
            0..points
        ) {
            val t =
                i.toDouble() /
                    points.toDouble() *
                    PI *
                    2.0

            val radial =
                radius *
                    (
                        0.78f +
                            0.18f *
                            sin(
                                t * 3.0 +
                                    phaseRad
                            )
                                .toFloat()
                        )

            val rawX =
                cos(t)
                    .toFloat() *
                    radial

            val rawY =
                sin(t)
                    .toFloat() *
                    radial *
                    0.78f

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
            (alpha * 0.18f)
                .toInt()
                .coerceIn(0, 255)
        glowPaint.strokeWidth =
            dp(widthDp * 5.0f)
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
        radius: Float
    ) {
        val count =
            52

        for (
            i in
            0 until count
        ) {
            val seed =
                i *
                    2.399963229728653

            val angle =
                seed +
                    now.toDouble() /
                        (
                            5200.0 +
                                i * 17.0
                            )

            val band =
                when (
                    i % 4
                ) {
                    0 -> 0.88f
                    1 -> 0.94f
                    2 -> 1.01f
                    else -> 1.07f
                }

            val jitter =
                0.018f *
                    sin(
                        seed * 2.1 +
                            now / 1100.0
                    )
                        .toFloat()

            val r =
                radius *
                    (
                        band +
                            jitter
                        )

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    r

            val y =
                cy +
                    sin(angle)
                        .toFloat() *
                    r * 0.92f

            val color =
                when (
                    i % 5
                ) {
                    0 -> palette.white
                    1, 2 -> palette.cyan
                    3 -> palette.blue
                    else -> palette.violet
                }

            val alpha =
                42 +
                    (i % 6) * 16

            val dot =
                dp(
                    when {
                        i % 17 == 0 -> 1.35f
                        i % 7 == 0 -> 0.90f
                        else -> 0.52f
                    }
                )

            particlePaint.shader = null
            particlePaint.color = color
            particlePaint.alpha =
                alpha.coerceIn(0, 210)

            canvas.drawCircle(
                x,
                y,
                dot,
                particlePaint
            )

            if (
                i % 17 == 0
            ) {
                particlePaint.alpha = 34
                canvas.drawCircle(
                    x,
                    y,
                    dot * 3.6f,
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
                0.33f,
                0.45f,
                0.57f,
                0.66f
            )

        for (
            i in
            radii.indices
        ) {
            val r =
                radius *
                    radii[i]

            ringPaint.shader = null
            ringPaint.color =
                when (
                    i % 3
                ) {
                    0 -> palette.white
                    1 -> palette.cyan
                    else -> palette.violet
                }

            ringPaint.alpha =
                66 +
                    i * 18

            ringPaint.strokeWidth =
                dp(
                    if (
                        i == 0
                    ) {
                        1.0f
                    } else {
                        0.62f
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
            cx - radius * 0.60f,
            cy - radius * 0.60f,
            cx + radius * 0.60f,
            cy + radius * 0.60f
        )

        ringPaint.color =
            palette.cyan
        ringPaint.alpha = 184
        ringPaint.strokeWidth =
            dp(1.45f)

        canvas.drawArc(
            bounds,
            phase + 20f,
            78f,
            false,
            ringPaint
        )

        canvas.drawArc(
            bounds,
            phase + 204f,
            45f,
            false,
            ringPaint
        )

        bounds.set(
            cx - radius * 0.49f,
            cy - radius * 0.49f,
            cx + radius * 0.49f,
            cy + radius * 0.49f
        )

        ringPaint.color =
            palette.white
        ringPaint.alpha = 112
        ringPaint.strokeWidth =
            dp(0.85f)

        canvas.drawArc(
            bounds,
            reversePhase + 65f,
            128f,
            false,
            ringPaint
        )
    }

    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float
    ) {
        val left =
            width * 0.035f

        val right =
            width * 0.965f

        val span =
            right - left

        val phase =
            now / 300.0

        val maxAmplitude =
            min(
                height * 0.145f,
                radius * 0.34f
            )

        mainWavePath.reset()
        fineWavePath.reset()

        val points =
            112

        for (
            i in
            0..points
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

            val radialEnvelope =
                (
                    0.34f +
                        0.66f *
                        (
                            1f -
                                nx * nx
                            )
                            .coerceIn(
                                0f,
                                1f
                            )
                    )

            // Central wordmark clearance. The wave remains continuous but almost
            // flat under AYANA instead of cutting through the letter strokes.
            val logoClearance =
                when {
                    kotlin.math.abs(nx) <= 0.60f ->
                        0.10f

                    kotlin.math.abs(nx) >= 0.82f ->
                        1f

                    else ->
                        0.10f +
                            0.90f *
                            (
                                (kotlin.math.abs(nx) - 0.60f) /
                                    0.22f
                                )
                }

            val envelope =
                radialEnvelope *
                    logoClearance

            val wave =
                (
                    sin(
                        phase +
                            p * PI * 10.0
                    ) * 0.56 +
                        sin(
                            phase * 1.58 -
                                p * PI * 20.0
                        ) * 0.27 +
                        sin(
                            phase * 0.56 +
                                p * PI * 4.6
                        ) * 0.17
                    )
                    .toFloat()

            val fine =
                (
                    sin(
                        phase * 1.16 -
                            p * PI * 16.5
                    ) * 0.68 +
                        sin(
                            phase * 0.72 +
                                p * PI * 6.2
                        ) * 0.32
                    )
                    .toFloat()

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
                    0.34f

            if (
                i ==
                0
            ) {
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

        // Sparse side spectrum only. No bars through the AYANA wordmark.
        wavePaint.shader =
            horizontalShader
        wavePaint.alpha =
            38
        wavePaint.strokeWidth =
            dp(0.62f)

        val bars =
            52

        for (
            i in
            0 until bars
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

            if (
                kotlin.math.abs(nx) <
                0.72f
            ) {
                continue
            }

            val edgeEnvelope =
                (
                    0.25f +
                        0.75f *
                        (
                            1f -
                                nx * nx
                            )
                            .coerceIn(
                                0f,
                                1f
                            )
                    )

            val energy =
                abs(
                    sin(
                        phase * 1.04 +
                            i * 0.71
                    ) * 0.64 +
                        sin(
                            phase * 0.46 -
                                i * 1.17
                        ) * 0.36
                )
                    .toFloat()

            val half =
                maxAmplitude *
                    edgeEnvelope *
                    (
                        0.055f +
                            energy * 0.18f
                        )

            canvas.drawLine(
                x,
                cy - half,
                x,
                cy + half,
                wavePaint
            )
        }

        wavePaint.shader =
            horizontalShader
        wavePaint.alpha =
            48
        wavePaint.strokeWidth =
            dp(5.4f)
        canvas.drawPath(
            mainWavePath,
            wavePaint
        )

        wavePaint.alpha =
            68
        wavePaint.strokeWidth =
            dp(2.45f)
        canvas.drawPath(
            mainWavePath,
            wavePaint
        )

        wavePaint.alpha =
            192
        wavePaint.strokeWidth =
            dp(1.32f)
        canvas.drawPath(
            mainWavePath,
            wavePaint
        )

        wavePaint.alpha =
            66
        wavePaint.strokeWidth =
            dp(0.70f)
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
        // Wide signature: more like the previous good UI and the references.
        val totalWidth =
            min(
                width * 0.72f,
                radius * 2.34f
            )

        val letterHeight =
            min(
                height * 0.300f,
                radius * 0.66f
            )

        val gap =
            totalWidth * 0.034f

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

        wordmarkGlowPaint.shader =
            horizontalShader
        wordmarkGlowPaint.alpha = 46
        wordmarkGlowPaint.strokeWidth =
            dp(9.4f)

        wordmarkPaint.shader =
            horizontalShader
        wordmarkPaint.alpha = 255
        wordmarkPaint.strokeWidth =
            dp(4.65f)

        // A
        drawA(
            canvas,
            startX,
            top,
            unit,
            letterHeight,
            wordmarkGlowPaint
        )
        drawA(
            canvas,
            startX,
            top,
            unit,
            letterHeight,
            wordmarkPaint
        )

        // Y
        val yX =
            startX +
                (unit + gap)
        drawY(
            canvas,
            yX,
            top,
            unit,
            letterHeight,
            wordmarkGlowPaint
        )
        drawY(
            canvas,
            yX,
            top,
            unit,
            letterHeight,
            wordmarkPaint
        )

        // A
        val a2X =
            startX +
                (unit + gap) * 2f
        drawA(
            canvas,
            a2X,
            top,
            unit,
            letterHeight,
            wordmarkGlowPaint
        )
        drawA(
            canvas,
            a2X,
            top,
            unit,
            letterHeight,
            wordmarkPaint
        )

        // N
        val nX =
            startX +
                (unit + gap) * 3f
        drawN(
            canvas,
            nX,
            top,
            bottom,
            unit,
            wordmarkGlowPaint
        )
        drawN(
            canvas,
            nX,
            top,
            bottom,
            unit,
            wordmarkPaint
        )

        // A
        val a3X =
            startX +
                (unit + gap) * 4f
        drawA(
            canvas,
            a3X,
            top,
            unit,
            letterHeight,
            wordmarkGlowPaint
        )
        drawA(
            canvas,
            a3X,
            top,
            unit,
            letterHeight,
            wordmarkPaint
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
            x + width / 2f

        // Signature crossbar-less A used by AYANA branding.
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
            top + height * 0.49f

        val center =
            x + width / 2f

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
            (220f * strength)
                .toInt()
                .coerceIn(0, 255)

        canvas.drawCircle(
            x,
            y,
            dp(1.35f * strength),
            particlePaint
        )

        ringPaint.shader = null
        ringPaint.color = color
        ringPaint.alpha =
            (120f * strength)
                .toInt()
                .coerceIn(0, 180)
        ringPaint.strokeWidth =
            dp(0.76f)

        val long =
            dp(9.5f * strength)

        val short =
            dp(4.0f * strength)

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
                kotlin.math.floor(turns)

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
                    blue = Color.parseColor("#1DA2FF"),
                    violet = Color.parseColor("#8D5CFF"),
                    white = Color.parseColor("#F6FEFF"),
                    deep = Color.parseColor("#07162E")
                )

            AyanaVoiceService.STATE_THINKING ->
                Palette(
                    cyan = Color.parseColor("#60D9FF"),
                    blue = Color.parseColor("#4B68FF"),
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
                    cyan = Color.parseColor("#27E8FF"),
                    blue = Color.parseColor("#2388FF"),
                    violet = Color.parseColor("#A14CFF"),
                    white = Color.parseColor("#F3FDFF"),
                    deep = Color.parseColor("#091331")
                )
        }
    }

    private fun frameDelayMs(
        state: String
    ): Long {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING -> 28L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SPEAKING -> 30L

            AyanaVoiceService.STATE_LISTENING -> 32L

            else -> 55L
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
