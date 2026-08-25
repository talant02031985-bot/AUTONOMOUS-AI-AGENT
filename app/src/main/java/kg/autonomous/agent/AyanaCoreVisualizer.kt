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
 * AYANA Core Visualizer v1.1 — PREMIUM ENERGY FIELD.
 *
 * Visual-only main-card renderer for AYANA v12.10.0.
 *
 * Design goals:
 * 1) dense layered energy sphere instead of a small schematic nucleus;
 * 2) large custom AYANA neon wordmark in the visual center;
 * 3) full-width multi-layer waveform with glow and deterministic spectrum bars;
 * 4) concentric HUD rings, rotating broken arcs, energy filaments and particles;
 * 5) state-reactive color/motion without claiming measured microphone amplitude;
 * 6) no bitmap decoding, no random allocation, no accessibility semantics;
 * 7) shaders rebuilt only on size/state change; reusable Paths/Paints per frame.
 *
 * IMPORTANT TRUTH CONTRACT:
 * The waveform is a state visualization. It is NOT presented as a factual audio
 * meter until AYANA exposes a separately verified amplitude stream.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val ambientPaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val auraPaint =
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

    private val waveGlowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val wavePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val spectrumPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }

    private val particlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG)

    private val wordmarkGlowPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }

    private val wordmarkPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.MITER
        }

    private val primaryWavePath =
        Path()

    private val secondaryWavePath =
        Path()

    private val tertiaryWavePath =
        Path()

    private val orbitBounds =
        RectF()

    private var ambientShader:
        RadialGradient? = null

    private var auraShader:
        RadialGradient? = null

    private var coreShader:
        RadialGradient? = null

    private var waveShader:
        LinearGradient? = null

    private var wordmarkShader:
        LinearGradient? = null

    private var shaderWidth =
        -1

    private var shaderHeight =
        -1

    private var shaderState =
        ""

    private var attached =
        false

    private val scaffoldRadii =
        floatArrayOf(
            0.56f,
            0.68f,
            0.80f,
            0.91f,
            1.00f
        )

    private val wordmarkWidths =
        floatArrayOf(
            0.17f,
            0.15f,
            0.17f,
            0.18f,
            0.17f
        )

    private val wordmarkWidthSum =
        0.84f

    private var activePalette =
        Palette(
            primary = Color.parseColor("#22D3EE"),
            secondary = Color.parseColor("#8B5CF6"),
            deep = Color.parseColor("#2563EB"),
            pale = Color.parseColor("#E6FBFF")
        )

    private var activeMotion =
        Motion(
            outerCycleMs = 9200f,
            middleCycleMs = 11200f,
            innerCycleMs = 7600f,
            waveDivisor = 270.0,
            particleDivisor = 1280.0,
            breatheDivisor = 520.0
        )

    private var activeEnergy =
        0.72f

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

        val palette =
            activePalette

        val motion =
            activeMotion

        val energy =
            activeEnergy

        val outerRadius =
            minSide * 0.455f

        val phaseFast =
            continuousAngle(
                now,
                motion.outerCycleMs,
                1f,
                false
            )

        val phaseReverse =
            continuousAngle(
                now,
                motion.middleCycleMs,
                1f,
                true
            )

        val phaseSlow =
            continuousAngle(
                now,
                motion.innerCycleMs,
                1f,
                false
            )

        val breathe =
            (
                sin(
                    now / motion.breatheDivisor
                ) *
                    0.5 +
                    0.5
                )
                .toFloat()

        // Deep ambient field — fills the visual panel instead of leaving a
        // small isolated symbol floating in a large black rectangle.
        ambientPaint.alpha =
            230
        canvas.drawCircle(
            cx,
            cy,
            minSide * 0.73f,
            ambientPaint
        )

        auraPaint.alpha =
            (
                165 +
                    40 *
                    breathe
                )
                .toInt()
                .coerceIn(0, 255)
        canvas.drawCircle(
            cx,
            cy,
            outerRadius *
                (
                    0.79f +
                        energy *
                        0.04f
                    ),
            auraPaint
        )

        // Faint geometric scaffold.
        drawHudScaffold(
            canvas = canvas,
            cx = cx,
            cy = cy,
            outerRadius = outerRadius,
            phaseFast = phaseFast,
            phaseReverse = phaseReverse,
            palette = palette
        )

        // Dense spherical energy filaments inspired by the approved AYANA
        // references. Ellipses are intentionally layered at different tilts.
        drawEnergyFilaments(
            canvas = canvas,
            cx = cx,
            cy = cy,
            outerRadius = outerRadius,
            phaseFast = phaseFast,
            phaseReverse = phaseReverse,
            phaseSlow = phaseSlow,
            palette = palette,
            energy = energy
        )

        // Full-width luminous sound-energy rail. Drawn before the wordmark so
        // AYANA remains clearly readable above it.
        drawWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            palette = palette,
            motion = motion,
            energy = energy
        )

        drawParticles(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            outerRadius = outerRadius,
            palette = palette,
            motion = motion
        )

        // Large inner luminous nucleus with glass-like boundary rings.
        corePaint.alpha =
            250
        canvas.drawCircle(
            cx,
            cy,
            outerRadius *
                (
                    0.39f +
                        energy *
                        0.018f *
                        breathe
                    ),
            corePaint
        )

        finePaint.shader = null
        finePaint.color =
            withAlpha(
                palette.pale,
                148
            )
        finePaint.strokeWidth =
            dp(0.78f)
        canvas.drawCircle(
            cx,
            cy,
            outerRadius * 0.315f,
            finePaint
        )

        finePaint.color =
            withAlpha(
                palette.primary,
                126
            )
        finePaint.strokeWidth =
            dp(0.66f)
        canvas.drawCircle(
            cx,
            cy,
            outerRadius * 0.455f,
            finePaint
        )

        // Custom vector AYANA wordmark: no font dependency and visually much
        // closer to the approved crossbar-less A reference.
        drawAyanaWordmark(
            canvas = canvas,
            cx = cx,
            cy = cy,
            outerRadius = outerRadius,
            energy = energy
        )

        // Four small star flares give the energy field photographic depth
        // without using blur/shadow software layers.
        drawStarFlare(
            canvas,
            cx - outerRadius * 0.53f,
            cy - outerRadius * 0.52f,
            palette.pale,
            0.95f
        )
        drawStarFlare(
            canvas,
            cx + outerRadius * 0.45f,
            cy - outerRadius * 0.62f,
            palette.secondary,
            0.78f
        )
        drawStarFlare(
            canvas,
            cx - outerRadius * 0.28f,
            cy + outerRadius * 0.72f,
            palette.primary,
            0.68f
        )
        drawStarFlare(
            canvas,
            cx + outerRadius * 0.62f,
            cy + outerRadius * 0.41f,
            palette.pale,
            0.60f
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

        activePalette =
            paletteFor(state)

        activeMotion =
            motionFor(state)

        activeEnergy =
            energyFor(state)

        val palette =
            activePalette

        ambientShader =
            RadialGradient(
                cx,
                cy,
                minSide * 0.75f,
                intArrayOf(
                    withAlpha(palette.primary, 60),
                    withAlpha(palette.deep, 44),
                    withAlpha(palette.secondary, 22),
                    withAlpha(palette.primary, 7),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.27f,
                    0.54f,
                    0.80f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        auraShader =
            RadialGradient(
                cx,
                cy,
                minSide * 0.40f,
                intArrayOf(
                    withAlpha(palette.pale, 40),
                    withAlpha(palette.primary, 54),
                    withAlpha(palette.secondary, 22),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.36f,
                    0.70f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        coreShader =
            RadialGradient(
                cx - minSide * 0.035f,
                cy - minSide * 0.045f,
                minSide * 0.26f,
                intArrayOf(
                    Color.WHITE,
                    withAlpha(palette.pale, 252),
                    withAlpha(palette.primary, 242),
                    withAlpha(palette.deep, 165),
                    withAlpha(palette.secondary, 76),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.10f,
                    0.29f,
                    0.52f,
                    0.78f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        waveShader =
            LinearGradient(
                width * 0.05f,
                cy,
                width * 0.95f,
                cy,
                intArrayOf(
                    withAlpha(palette.primary, 175),
                    palette.pale,
                    palette.primary,
                    palette.secondary,
                    withAlpha(palette.secondary, 180)
                ),
                floatArrayOf(
                    0f,
                    0.36f,
                    0.50f,
                    0.68f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        wordmarkShader =
            LinearGradient(
                cx - minSide * 0.40f,
                cy,
                cx + minSide * 0.40f,
                cy,
                intArrayOf(
                    palette.pale,
                    palette.primary,
                    Color.WHITE,
                    palette.secondary,
                    palette.pale
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

        ambientPaint.shader =
            ambientShader

        auraPaint.shader =
            auraShader

        corePaint.shader =
            coreShader

        waveGlowPaint.shader =
            waveShader

        wavePaint.shader =
            waveShader

        spectrumPaint.shader =
            waveShader

        wordmarkGlowPaint.shader =
            wordmarkShader

        wordmarkPaint.shader =
            wordmarkShader
    }

    private fun drawHudScaffold(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        outerRadius: Float,
        phaseFast: Float,
        phaseReverse: Float,
        palette: Palette
    ) {
        finePaint.shader = null

        for (
            index in
            scaffoldRadii.indices
        ) {
            finePaint.color =
                withAlpha(
                    if (
                        index % 2 == 0
                    ) {
                        palette.primary
                    } else {
                        palette.pale
                    },
                    34 +
                        index *
                        7
                )

            finePaint.strokeWidth =
                dp(
                    if (
                        index ==
                        scaffoldRadii.lastIndex
                    ) {
                        0.72f
                    } else {
                        0.52f
                    }
                )

            canvas.drawCircle(
                cx,
                cy,
                outerRadius *
                    scaffoldRadii[index],
                finePaint
            )
        }

        // Broken outer arcs.
        drawBrokenCircle(
            canvas,
            cx,
            cy,
            outerRadius * 0.985f,
            phaseFast + 8f,
            palette.primary,
            178,
            1.05f
        )

        drawBrokenCircle(
            canvas,
            cx,
            cy,
            outerRadius * 0.885f,
            phaseReverse + 37f,
            palette.secondary,
            128,
            0.82f
        )

        drawBrokenCircle(
            canvas,
            cx,
            cy,
            outerRadius * 0.745f,
            phaseFast * 0.72f + 112f,
            palette.pale,
            96,
            0.64f
        )

        // 48 subtle radial HUD ticks around the outer ring.
        ringPaint.shader = null
        ringPaint.strokeCap =
            Paint.Cap.ROUND

        for (
            i in
            0 until 48
        ) {
            val angle =
                Math.toRadians(
                    (
                        i *
                            7.5f +
                            phaseReverse *
                            0.12f
                        )
                        .toDouble()
                )

            val longTick =
                i % 6 == 0

            val inner =
                outerRadius *
                    if (longTick) 0.955f else 0.972f

            val outer =
                outerRadius * 1.01f

            val x1 =
                cx +
                    cos(angle)
                        .toFloat() *
                    inner

            val y1 =
                cy +
                    sin(angle)
                        .toFloat() *
                    inner

            val x2 =
                cx +
                    cos(angle)
                        .toFloat() *
                    outer

            val y2 =
                cy +
                    sin(angle)
                        .toFloat() *
                    outer

            ringPaint.color =
                withAlpha(
                    if (longTick) {
                        palette.pale
                    } else {
                        palette.primary
                    },
                    if (longTick) 116 else 52
                )

            ringPaint.strokeWidth =
                dp(
                    if (longTick) 0.90f else 0.50f
                )

            canvas.drawLine(
                x1,
                y1,
                x2,
                y2,
                ringPaint
            )
        }
    }

    private fun drawEnergyFilaments(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        outerRadius: Float,
        phaseFast: Float,
        phaseReverse: Float,
        phaseSlow: Float,
        palette: Palette,
        energy: Float
    ) {
        val baseAlpha =
            (
                92 +
                    energy *
                    66
                )
                .toInt()
                .coerceIn(0, 180)

        // Six rotating oval filaments create the spherical/petal energy body.
        drawTiltedFilament(
            canvas,
            cx,
            cy,
            outerRadius * 0.73f,
            outerRadius * 0.91f,
            -54f,
            phaseFast,
            282f,
            palette.primary,
            baseAlpha,
            1.10f
        )

        drawTiltedFilament(
            canvas,
            cx,
            cy,
            outerRadius * 0.70f,
            outerRadius * 0.94f,
            -28f,
            phaseReverse + 44f,
            268f,
            palette.secondary,
            baseAlpha - 20,
            0.90f
        )

        drawTiltedFilament(
            canvas,
            cx,
            cy,
            outerRadius * 0.66f,
            outerRadius * 0.96f,
            -7f,
            phaseSlow + 97f,
            292f,
            palette.pale,
            baseAlpha - 42,
            0.72f
        )

        drawTiltedFilament(
            canvas,
            cx,
            cy,
            outerRadius * 0.69f,
            outerRadius * 0.93f,
            21f,
            phaseFast + 131f,
            255f,
            palette.primary,
            baseAlpha - 14,
            0.96f
        )

        drawTiltedFilament(
            canvas,
            cx,
            cy,
            outerRadius * 0.72f,
            outerRadius * 0.90f,
            47f,
            phaseReverse + 206f,
            276f,
            palette.secondary,
            baseAlpha - 28,
            0.82f
        )

        drawTiltedFilament(
            canvas,
            cx,
            cy,
            outerRadius * 0.63f,
            outerRadius * 0.84f,
            74f,
            phaseSlow + 284f,
            238f,
            palette.pale,
            baseAlpha - 52,
            0.66f
        )

        // Two luminous inner rings make the center feel like a contained core.
        ringPaint.shader = null
        orbitBounds.set(
            cx - outerRadius * 0.54f,
            cy - outerRadius * 0.54f,
            cx + outerRadius * 0.54f,
            cy + outerRadius * 0.54f
        )

        ringPaint.color =
            withAlpha(
                palette.primary,
                142
            )
        ringPaint.strokeWidth =
            dp(1.22f)
        canvas.drawArc(
            orbitBounds,
            phaseFast + 18f,
            126f,
            false,
            ringPaint
        )
        canvas.drawArc(
            orbitBounds,
            phaseFast + 205f,
            78f,
            false,
            ringPaint
        )

        orbitBounds.set(
            cx - outerRadius * 0.43f,
            cy - outerRadius * 0.43f,
            cx + outerRadius * 0.43f,
            cy + outerRadius * 0.43f
        )

        ringPaint.color =
            withAlpha(
                palette.pale,
                116
            )
        ringPaint.strokeWidth =
            dp(0.80f)
        canvas.drawArc(
            orbitBounds,
            phaseReverse + 73f,
            194f,
            false,
            ringPaint
        )
    }

    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        palette: Palette,
        motion: Motion,
        energy: Float
    ) {
        val left =
            width * 0.035f

        val right =
            width * 0.965f

        val span =
            right - left

        val phase =
            now.toDouble() /
                motion.waveDivisor

        val maxAmplitude =
            height *
                (
                    0.066f +
                        energy *
                        0.105f
                    )

        primaryWavePath.reset()
        secondaryWavePath.reset()
        tertiaryWavePath.reset()

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
                    span *
                    p

            val normalized =
                (
                    x -
                        cx
                    ) /
                    (
                        span *
                            0.5f
                        )

            // Keep visible activity across the full rail, with stronger energy
            // around the center and deterministic secondary peaks.
            val centerEnvelope =
                (
                    1f -
                        normalized *
                            normalized
                    )
                    .coerceIn(
                        0f,
                        1f
                    )

            val distributed =
                (
                    0.38f +
                        0.62f *
                        centerEnvelope
                    ) *
                    (
                        0.74f +
                            0.26f *
                            abs(
                                sin(
                                    p *
                                        PI *
                                        4.0 +
                                        phase *
                                        0.16
                                )
                            )
                                .toFloat()
                        )

            val primary =
                (
                    sin(
                        phase +
                            p *
                            PI *
                            11.6
                    ) *
                        0.48 +
                        sin(
                            phase *
                                1.63 +
                                p *
                                PI *
                                23.4
                        ) *
                        0.27 +
                        sin(
                            phase *
                                0.71 -
                                p *
                                PI *
                                6.8
                        ) *
                        0.25
                    )
                    .toFloat()

            val secondary =
                (
                    sin(
                        phase *
                            0.76 +
                            p *
                            PI *
                            8.1
                    ) *
                        0.72 +
                        sin(
                            phase *
                                1.34 +
                                p *
                                PI *
                                16.3
                        ) *
                        0.28
                    )
                    .toFloat()

            val tertiary =
                sin(
                    phase *
                        1.11 -
                        p *
                        PI *
                        13.7
                )
                    .toFloat()

            val y1 =
                cy +
                    primary *
                    maxAmplitude *
                    distributed

            val y2 =
                cy +
                    secondary *
                    maxAmplitude *
                    distributed *
                    0.58f

            val y3 =
                cy +
                    tertiary *
                    maxAmplitude *
                    distributed *
                    0.32f

            if (i == 0) {
                primaryWavePath.moveTo(
                    x,
                    y1
                )
                secondaryWavePath.moveTo(
                    x,
                    y2
                )
                tertiaryWavePath.moveTo(
                    x,
                    y3
                )
            } else {
                primaryWavePath.lineTo(
                    x,
                    y1
                )
                secondaryWavePath.lineTo(
                    x,
                    y2
                )
                tertiaryWavePath.lineTo(
                    x,
                    y3
                )
            }
        }

        // Soft spectrum bars behind the line make the rail feel like a real
        // visualization while remaining explicitly synthetic/state-driven.
        spectrumPaint.shader =
            waveShader
        spectrumPaint.alpha =
            54
        spectrumPaint.strokeWidth =
            dp(0.62f)

        val barCount =
            70

        for (
            i in
            0 until barCount
        ) {
            val p =
                i.toFloat() /
                    (
                        barCount -
                            1
                        )

            val x =
                left +
                    span *
                    p

            val normalized =
                (
                    x -
                        cx
                    ) /
                    (
                        span *
                            0.5f
                        )

            val envelope =
                (
                    0.28f +
                        0.72f *
                        (
                            1f -
                                normalized *
                                    normalized
                            )
                            .coerceIn(0f, 1f)
                    )

            val value =
                abs(
                    sin(
                        phase *
                            1.18 +
                            i *
                            0.73
                    ) *
                        0.64 +
                        sin(
                            phase *
                                0.47 -
                                i *
                                1.37
                        ) *
                        0.36
                )
                    .toFloat()

            val halfHeight =
                maxAmplitude *
                    envelope *
                    (
                        0.10f +
                            value *
                            0.54f
                        )

            canvas.drawLine(
                x,
                cy - halfHeight,
                x,
                cy + halfHeight,
                spectrumPaint
            )
        }

        // Wide low-alpha pass = glow, then crisp colored line.
        waveGlowPaint.shader =
            waveShader
        waveGlowPaint.alpha =
            48
        waveGlowPaint.strokeWidth =
            dp(6.2f)
        canvas.drawPath(
            primaryWavePath,
            waveGlowPaint
        )

        waveGlowPaint.alpha =
            34
        waveGlowPaint.strokeWidth =
            dp(3.8f)
        canvas.drawPath(
            secondaryWavePath,
            waveGlowPaint
        )

        wavePaint.shader =
            waveShader
        wavePaint.alpha =
            238
        wavePaint.strokeWidth =
            dp(1.24f)
        canvas.drawPath(
            primaryWavePath,
            wavePaint
        )

        wavePaint.alpha =
            138
        wavePaint.strokeWidth =
            dp(0.82f)
        canvas.drawPath(
            secondaryWavePath,
            wavePaint
        )

        wavePaint.alpha =
            82
        wavePaint.strokeWidth =
            dp(0.62f)
        canvas.drawPath(
            tertiaryWavePath,
            wavePaint
        )

        // Bright zero-axis rail.
        wavePaint.shader =
            null
        wavePaint.color =
            withAlpha(
                palette.pale,
                112
            )
        wavePaint.alpha =
            112
        wavePaint.strokeWidth =
            dp(0.58f)
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
        outerRadius: Float,
        palette: Palette,
        motion: Motion
    ) {
        val count =
            28

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
                            motion.particleDivisor +
                                i *
                                61.0
                            )

            val radius =
                outerRadius *
                    (
                        0.47f +
                            (
                                i %
                                    7
                                ) *
                            0.078f
                        )

            val x =
                cx +
                    cos(angle)
                        .toFloat() *
                    radius

            val y =
                cy +
                    sin(
                        angle *
                            1.07 +
                            seed *
                            0.37
                    )
                        .toFloat() *
                    radius *
                    0.82f

            val color =
                when (
                    i %
                        4
                    ) {
                    0 ->
                        palette.primary

                    1 ->
                        palette.secondary

                    2 ->
                        palette.pale

                    else ->
                        palette.deep
                }

            val alpha =
                92 +
                    (
                        i %
                            5
                        ) *
                    25

            particlePaint.shader = null
            particlePaint.color =
                color
            particlePaint.alpha =
                alpha.coerceIn(0, 220)

            val particleRadius =
                dp(
                    when {
                        i % 11 == 0 -> 1.55f
                        i % 5 == 0 -> 1.10f
                        else -> 0.68f
                    }
                )

            canvas.drawCircle(
                x,
                y,
                particleRadius,
                particlePaint
            )

            if (
                i %
                    11 ==
                0
            ) {
                particlePaint.alpha =
                    42
                canvas.drawCircle(
                    x,
                    y,
                    particleRadius * 3.6f,
                    particlePaint
                )
            }
        }
    }

    private fun drawAyanaWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        outerRadius: Float,
        energy: Float
    ) {
        val totalWidth =
            min(
                width * 0.54f,
                outerRadius * 1.72f
            )

        val letterHeight =
            min(
                height * 0.205f,
                dp(54f)
            )

        val top =
            cy -
                letterHeight *
                0.50f

        val gap =
            totalWidth * 0.035f

        val usable =
            totalWidth -
                gap *
                4f

        val startX =
            cx -
                totalWidth /
                2f

        wordmarkGlowPaint.shader =
            wordmarkShader
        wordmarkGlowPaint.alpha =
            (
                54 +
                    energy *
                    26
                )
                .toInt()
                .coerceIn(0, 92)
        wordmarkGlowPaint.strokeWidth =
            dp(7.4f)

        wordmarkPaint.shader =
            wordmarkShader
        wordmarkPaint.alpha =
            252
        wordmarkPaint.strokeWidth =
            dp(2.25f)

        var x =
            startX

        for (
            index in
            wordmarkWidths.indices
        ) {
            val w =
                usable *
                    wordmarkWidths[index] /
                    wordmarkWidthSum

            drawLetter(
                canvas,
                index,
                x,
                top,
                w,
                letterHeight,
                wordmarkGlowPaint
            )

            drawLetter(
                canvas,
                index,
                x,
                top,
                w,
                letterHeight,
                wordmarkPaint
            )

            x +=
                w +
                    gap
        }

        // Faint center spark behind the wordmark.
        particlePaint.shader = null
        particlePaint.color =
            Color.WHITE
        particlePaint.alpha =
            185
        canvas.drawCircle(
            cx,
            cy,
            dp(1.15f),
            particlePaint
        )

        particlePaint.alpha =
            34
        canvas.drawCircle(
            cx,
            cy,
            dp(8.5f),
            particlePaint
        )
    }

    private fun drawLetter(
        canvas: Canvas,
        index: Int,
        x: Float,
        top: Float,
        width: Float,
        height: Float,
        paint: Paint
    ) {
        val bottom =
            top +
                height

        val midY =
            top +
                height *
                0.47f

        when (index) {
            // A — approved AYANA style uses a clean Λ-like form without a
            // conventional horizontal crossbar.
            0,
            2,
            4 -> {
                canvas.drawLine(
                    x,
                    bottom,
                    x + width * 0.50f,
                    top,
                    paint
                )
                canvas.drawLine(
                    x + width * 0.50f,
                    top,
                    x + width,
                    bottom,
                    paint
                )
            }

            // Y
            1 -> {
                canvas.drawLine(
                    x,
                    top,
                    x + width * 0.50f,
                    midY,
                    paint
                )
                canvas.drawLine(
                    x + width,
                    top,
                    x + width * 0.50f,
                    midY,
                    paint
                )
                canvas.drawLine(
                    x + width * 0.50f,
                    midY,
                    x + width * 0.50f,
                    bottom,
                    paint
                )
            }

            // N
            else -> {
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
        }
    }

    private fun drawBrokenCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        orbitBounds.set(
            cx - radius,
            cy - radius,
            cx + radius,
            cy + radius
        )

        ringPaint.shader = null
        ringPaint.color =
            color
        ringPaint.alpha =
            alpha.coerceIn(0, 255)
        ringPaint.strokeWidth =
            dp(widthDp)

        canvas.drawArc(
            orbitBounds,
            phase,
            74f,
            false,
            ringPaint
        )

        canvas.drawArc(
            orbitBounds,
            phase + 112f,
            42f,
            false,
            ringPaint
        )

        canvas.drawArc(
            orbitBounds,
            phase + 196f,
            86f,
            false,
            ringPaint
        )

        canvas.drawArc(
            orbitBounds,
            phase + 316f,
            24f,
            false,
            ringPaint
        )
    }

    private fun drawTiltedFilament(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radiusX: Float,
        radiusY: Float,
        tiltDeg: Float,
        startDeg: Float,
        sweepDeg: Float,
        color: Int,
        alpha: Int,
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

        // Glow pass.
        ringPaint.shader = null
        ringPaint.color =
            color
        ringPaint.alpha =
            (
                alpha *
                    0.24f
                )
                .toInt()
                .coerceIn(0, 68)
        ringPaint.strokeWidth =
            dp(widthDp * 4.1f)
        canvas.drawArc(
            orbitBounds,
            startDeg,
            sweepDeg,
            false,
            ringPaint
        )

        // Crisp filament.
        ringPaint.alpha =
            alpha.coerceIn(0, 220)
        ringPaint.strokeWidth =
            dp(widthDp)
        canvas.drawArc(
            orbitBounds,
            startDeg,
            sweepDeg,
            false,
            ringPaint
        )

        // Complementary broken tail.
        ringPaint.alpha =
            (
                alpha *
                    0.58f
                )
                .toInt()
                .coerceIn(0, 150)
        ringPaint.strokeWidth =
            dp(widthDp * 0.72f)
        canvas.drawArc(
            orbitBounds,
            startDeg + 188f,
            sweepDeg * 0.22f,
            false,
            ringPaint
        )

        canvas.restoreToCount(save)
    }

    private fun drawStarFlare(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
        scale: Float
    ) {
        finePaint.shader = null
        finePaint.color =
            color
        finePaint.alpha =
            176
        finePaint.strokeWidth =
            dp(0.70f)

        val long =
            dp(7.5f) *
                scale

        val short =
            dp(3.2f) *
                scale

        canvas.drawLine(
            x - long,
            y,
            x + long,
            y,
            finePaint
        )

        canvas.drawLine(
            x,
            y - long,
            x,
            y + long,
            finePaint
        )

        finePaint.alpha =
            104
        canvas.drawLine(
            x - short,
            y - short,
            x + short,
            y + short,
            finePaint
        )

        canvas.drawLine(
            x - short,
            y + short,
            x + short,
            y - short,
            finePaint
        )

        particlePaint.shader = null
        particlePaint.color =
            Color.WHITE
        particlePaint.alpha =
            220
        canvas.drawCircle(
            x,
            y,
            dp(0.90f) *
                scale,
            particlePaint
        )
    }

    private fun continuousAngle(
        nowMs: Long,
        cycleMs: Float,
        speedMultiplier: Float,
        reverse: Boolean
    ): Float {
        val turns =
            nowMs.toDouble() *
                speedMultiplier.toDouble() /
                cycleMs.toDouble()

        val fractional =
            turns -
                kotlin.math.floor(turns)

        val degrees =
            (
                fractional *
                    360.0
                )
                .toFloat()

        return if (reverse) {
            (
                360f -
                    degrees
                ) %
                360f
        } else {
            degrees
        }
    }

    private fun energyFor(
        state: String
    ): Float =
        when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                1.00f

            AyanaVoiceService.STATE_EXECUTING ->
                1.00f

            AyanaVoiceService.STATE_THINKING ->
                0.86f

            AyanaVoiceService.STATE_SPEAKING ->
                0.96f

            AyanaVoiceService.STATE_SUCCESS ->
                0.70f

            AyanaVoiceService.STATE_ERROR ->
                0.76f

            AyanaVoiceService.STATE_CANCELLED ->
                0.62f

            AyanaVoiceService.STATE_STOPPED ->
                0.34f

            else ->
                0.72f
        }

    private fun motionFor(
        state: String
    ): Motion =
        when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                Motion(
                    outerCycleMs = 5400f,
                    middleCycleMs = 6700f,
                    innerCycleMs = 4300f,
                    waveDivisor = 148.0,
                    particleDivisor = 860.0,
                    breatheDivisor = 330.0
                )

            AyanaVoiceService.STATE_EXECUTING ->
                Motion(
                    outerCycleMs = 4800f,
                    middleCycleMs = 6100f,
                    innerCycleMs = 3900f,
                    waveDivisor = 138.0,
                    particleDivisor = 790.0,
                    breatheDivisor = 300.0
                )

            AyanaVoiceService.STATE_THINKING ->
                Motion(
                    outerCycleMs = 7200f,
                    middleCycleMs = 8800f,
                    innerCycleMs = 5900f,
                    waveDivisor = 210.0,
                    particleDivisor = 1060.0,
                    breatheDivisor = 430.0
                )

            AyanaVoiceService.STATE_SPEAKING ->
                Motion(
                    outerCycleMs = 6100f,
                    middleCycleMs = 7600f,
                    innerCycleMs = 5000f,
                    waveDivisor = 158.0,
                    particleDivisor = 900.0,
                    breatheDivisor = 350.0
                )

            AyanaVoiceService.STATE_STOPPED ->
                Motion(
                    outerCycleMs = 13000f,
                    middleCycleMs = 16000f,
                    innerCycleMs = 10800f,
                    waveDivisor = 430.0,
                    particleDivisor = 1900.0,
                    breatheDivisor = 760.0
                )

            else ->
                Motion(
                    outerCycleMs = 9200f,
                    middleCycleMs = 11200f,
                    innerCycleMs = 7600f,
                    waveDivisor = 270.0,
                    particleDivisor = 1280.0,
                    breatheDivisor = 520.0
                )
        }

    private fun paletteFor(
        state: String
    ): Palette =
        when (state) {
            AyanaVoiceService.STATE_COMMAND ->
                Palette(
                    primary = Color.parseColor("#27E5FF"),
                    secondary = Color.parseColor("#A855F7"),
                    deep = Color.parseColor("#2563EB"),
                    pale = Color.parseColor("#E6FBFF")
                )

            AyanaVoiceService.STATE_THINKING ->
                Palette(
                    primary = Color.parseColor("#7DD3FC"),
                    secondary = Color.parseColor("#C084FC"),
                    deep = Color.parseColor("#4F46E5"),
                    pale = Color.parseColor("#EEF2FF")
                )

            AyanaVoiceService.STATE_EXECUTING ->
                Palette(
                    primary = Color.parseColor("#2DD4BF"),
                    secondary = Color.parseColor("#38BDF8"),
                    deep = Color.parseColor("#0F766E"),
                    pale = Color.parseColor("#E6FFFB")
                )

            AyanaVoiceService.STATE_SUCCESS ->
                Palette(
                    primary = Color.parseColor("#4ADE80"),
                    secondary = Color.parseColor("#22D3EE"),
                    deep = Color.parseColor("#15803D"),
                    pale = Color.parseColor("#ECFDF5")
                )

            AyanaVoiceService.STATE_ERROR ->
                Palette(
                    primary = Color.parseColor("#FB7185"),
                    secondary = Color.parseColor("#F59E0B"),
                    deep = Color.parseColor("#BE123C"),
                    pale = Color.parseColor("#FFF1F2")
                )

            AyanaVoiceService.STATE_CANCELLED ->
                Palette(
                    primary = Color.parseColor("#FBBF24"),
                    secondary = Color.parseColor("#C084FC"),
                    deep = Color.parseColor("#B45309"),
                    pale = Color.parseColor("#FFFBEB")
                )

            AyanaVoiceService.STATE_SPEAKING ->
                Palette(
                    primary = Color.parseColor("#60A5FA"),
                    secondary = Color.parseColor("#A78BFA"),
                    deep = Color.parseColor("#4338CA"),
                    pale = Color.parseColor("#EEF2FF")
                )

            AyanaVoiceService.STATE_STOPPED ->
                Palette(
                    primary = Color.parseColor("#64748B"),
                    secondary = Color.parseColor("#475569"),
                    deep = Color.parseColor("#334155"),
                    pale = Color.parseColor("#CBD5E1")
                )

            else ->
                Palette(
                    primary = Color.parseColor("#22D3EE"),
                    secondary = Color.parseColor("#8B5CF6"),
                    deep = Color.parseColor("#2563EB"),
                    pale = Color.parseColor("#E6FBFF")
                )
        }

    private fun frameDelayMs(
        state: String
    ): Long =
        when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING ->
                24L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SPEAKING ->
                27L

            AyanaVoiceService.STATE_LISTENING ->
                31L

            AyanaVoiceService.STATE_SUCCESS,
            AyanaVoiceService.STATE_ERROR,
            AyanaVoiceService.STATE_CANCELLED ->
                44L

            else ->
                80L
        }

    private fun withAlpha(
        color: Int,
        alpha: Int
    ): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun dp(
        value: Float
    ): Float =
        value *
            density

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val deep: Int,
        val pale: Int
    )

    private data class Motion(
        val outerCycleMs: Float,
        val middleCycleMs: Float,
        val innerCycleMs: Float,
        val waveDivisor: Double,
        val particleDivisor: Double,
        val breatheDivisor: Double
    )
}
