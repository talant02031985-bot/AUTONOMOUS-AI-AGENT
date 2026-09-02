package kg.autonomous.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
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
 * AYANA Core Visualizer v5.1 — LIVE REFERENCE SIX-STATE.
 *
 * Fixes the v5.0 regression where the approved artwork was displayed as a
 * completely static photo.
 *
 * Base visuals remain the exact six approved PNG resources. LIVE motion is
 * added without redrawing/recoloring the artwork:
 *
 * - continuous breathing of the approved visual;
 * - animated state-reactive waveform laid over the existing reference waveform;
 * - moving light sweep across the centre;
 * - subtle orbit sparks around the visual;
 * - soft glow pulse;
 * - crossfade between the six factual VoiceService states;
 * - six-state status strip remains bound to factual runtime states.
 *
 * Required resources in app/src/main/res/drawable-nodpi:
 *   ayana_state_waiting.png
 *   ayana_state_recognition.png
 *   ayana_state_thinking.png
 *   ayana_state_executing.png
 *   ayana_state_answering.png
 *   ayana_state_stop.png
 *
 * Public integration contract remains unchanged:
 *   AyanaCoreVisualizer(Context)
 *
 * No ORB, Accessibility, microphone capture, routing, TTS or command logic is
 * changed by this renderer.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density =
        resources.displayMetrics.density

    private val bitmapPaint =
        Paint(
            Paint.ANTI_ALIAS_FLAG or
                Paint.FILTER_BITMAP_FLAG or
                Paint.DITHER_FLAG
        )

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
            typeface =
                Typeface.create(
                    Typeface.DEFAULT,
                    Typeface.NORMAL
                )
        }

    private val sourceRect =
        Rect()

    private val destinationRect =
        RectF()

    private val wavePath =
        Path()

    private val fineWavePath =
        Path()

    private val bitmaps: Array<Bitmap?> =
        arrayOfNulls(STATE_COUNT)

    private var loaded =
        false

    private var attached =
        false

    private var currentIndex =
        -1

    private var previousIndex =
        -1

    private var transitionStartedAt =
        0L

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

        ensureBitmaps()

        invalidate()
    }

    override fun onDetachedFromWindow() {
        attached =
            false

        super.onDetachedFromWindow()
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

        ensureBitmaps()

        val runtimeState =
            AyanaVoiceService.currentStatusState

        val requestedIndex =
            stateIndex(
                runtimeState
            )

        val now =
            SystemClock.uptimeMillis()

        if (
            currentIndex !=
            requestedIndex
        ) {
            previousIndex =
                currentIndex

            currentIndex =
                requestedIndex

            transitionStartedAt =
                now
        }

        val compact =
            height <
                dp(180f)

        val stageHeight =
            if (compact) {
                0f
            } else {
                min(
                    dp(58f),
                    height *
                        0.18f
                )
            }

        val imageBottom =
            height -
                stageHeight

        val motion =
            motionProfile(
                currentIndex
            )

        drawLiveReferenceArtwork(
            canvas = canvas,
            now = now,
            imageBottom = imageBottom,
            motion = motion
        )

        drawLiveWaveform(
            canvas = canvas,
            now = now,
            imageBottom = imageBottom,
            stateIndex = currentIndex,
            motion = motion
        )

        drawOrbitSparks(
            canvas = canvas,
            now = now,
            imageBottom = imageBottom,
            stateIndex = currentIndex,
            motion = motion
        )

        drawTravelingHighlight(
            canvas = canvas,
            now = now,
            imageBottom = imageBottom,
            stateIndex = currentIndex,
            motion = motion
        )

        if (
            stageHeight >
            0f
        ) {
            drawStageStrip(
                canvas = canvas,
                activeIndex = currentIndex,
                top = imageBottom,
                height = stageHeight
            )
        }

        if (attached) {
            postInvalidateDelayed(
                frameDelayMs(
                    currentIndex
                )
            )
        }
    }

    private fun ensureBitmaps() {
        if (loaded) {
            return
        }

        bitmaps[STATE_WAITING] =
            decode(
                R.drawable.ayana_state_waiting
            )

        bitmaps[STATE_RECOGNITION] =
            decode(
                R.drawable.ayana_state_recognition
            )

        bitmaps[STATE_THINKING] =
            decode(
                R.drawable.ayana_state_thinking
            )

        bitmaps[STATE_EXECUTING] =
            decode(
                R.drawable.ayana_state_executing
            )

        bitmaps[STATE_ANSWERING] =
            decode(
                R.drawable.ayana_state_answering
            )

        bitmaps[STATE_STOP] =
            decode(
                R.drawable.ayana_state_stop
            )

        loaded =
            true
    }

    private fun decode(
        drawableId: Int
    ): Bitmap? {
        return try {
            BitmapFactory.decodeResource(
                resources,
                drawableId
            )
        } catch (
            _: Throwable
        ) {
            null
        }
    }

    private fun drawLiveReferenceArtwork(
        canvas: Canvas,
        now: Long,
        imageBottom: Float,
        motion: MotionProfile
    ) {
        val current =
            bitmaps
                .getOrNull(
                    currentIndex
                )

        if (
            current ==
            null
        ) {
            return
        }

        val previous =
            bitmaps
                .getOrNull(
                    previousIndex
                )

        val transition =
            (
                (
                    now -
                        transitionStartedAt
                    ).toFloat() /
                    TRANSITION_MS
                )
                .coerceIn(
                    0f,
                    1f
                )

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

        val baseScale =
            1f +
                motion.scaleAmplitude *
                    breathe

        if (
            previous !=
            null &&
            previous !==
            current &&
            transition <
            1f
        ) {
            drawBitmapFitted(
                canvas = canvas,
                bitmap = previous,
                alpha =
                    (
                        255f *
                            (
                                1f -
                                    transition
                                )
                        )
                        .toInt(),
                imageBottom = imageBottom,
                scale = 1f
            )
        }

        val currentAlpha =
            if (
                previous !=
                null &&
                previous !==
                current &&
                transition <
                1f
            ) {
                (
                    255f *
                        transition
                    )
                    .toInt()
            } else {
                255
            }

        // Exact approved reference is the base layer.
        drawBitmapFitted(
            canvas = canvas,
            bitmap = current,
            alpha = currentAlpha,
            imageBottom = imageBottom,
            scale = baseScale
        )

        // Second very-low-alpha copy produces a live optical glow without
        // altering the approved geometry or palette.
        val glowScale =
            baseScale +
                motion.glowScaleExtra *
                    (
                        0.35f +
                            breathe *
                                0.65f
                        )

        drawBitmapFitted(
            canvas = canvas,
            bitmap = current,
            alpha =
                (
                    motion.glowAlphaBase +
                        breathe *
                            motion.glowAlphaRange
                    )
                    .toInt(),
            imageBottom = imageBottom,
            scale = glowScale
        )
    }

    private fun drawBitmapFitted(
        canvas: Canvas,
        bitmap: Bitmap,
        alpha: Int,
        imageBottom: Float,
        scale: Float
    ) {
        sourceRect.set(
            0,
            0,
            bitmap.width,
            bitmap.height
        )

        val availableWidth =
            width.toFloat()

        val availableHeight =
            imageBottom
                .coerceAtLeast(
                    1f
                )

        val bitmapAspect =
            bitmap.width.toFloat() /
                bitmap.height.toFloat()

        val areaAspect =
            availableWidth /
                availableHeight

        val baseWidth: Float
        val baseHeight: Float

        if (
            areaAspect >
            bitmapAspect
        ) {
            baseHeight =
                availableHeight

            baseWidth =
                baseHeight *
                    bitmapAspect
        } else {
            baseWidth =
                availableWidth

            baseHeight =
                baseWidth /
                    bitmapAspect
        }

        val safeScale =
            scale
                .coerceIn(
                    0.985f,
                    1.035f
                )

        val drawWidth =
            baseWidth *
                safeScale

        val drawHeight =
            baseHeight *
                safeScale

        val left =
            (
                availableWidth -
                    drawWidth
                ) /
                2f

        val top =
            (
                availableHeight -
                    drawHeight
                ) /
                2f

        destinationRect.set(
            left,
            top,
            left +
                drawWidth,
            top +
                drawHeight
        )

        bitmapPaint.alpha =
            alpha
                .coerceIn(
                    0,
                    255
                )

        canvas.drawBitmap(
            bitmap,
            sourceRect,
            destinationRect,
            bitmapPaint
        )
    }

    private fun drawLiveWaveform(
        canvas: Canvas,
        now: Long,
        imageBottom: Float,
        stateIndex: Int,
        motion: MotionProfile
    ) {
        if (
            stateIndex <
            0
        ) {
            return
        }

        val color =
            stateColor(
                stateIndex
            )

        val left =
            width *
                0.035f

        val right =
            width *
                0.965f

        val cy =
            imageBottom *
                0.515f

        val span =
            right -
                left

        val samples =
            112

        val phase =
            now /
                motion.wavePeriodMs

        val amplitude =
            min(
                imageBottom *
                    motion.waveHeightRatio,
                dp(
                    motion.maxWaveHeightDp
                )
            )

        wavePath.reset()
        fineWavePath.reset()

        for (
            index in
            0..samples
        ) {
            val t =
                index /
                    samples.toFloat()

            val x =
                left +
                    span *
                        t

            val edgeEnvelope =
                sin(
                    PI *
                        t
                )
                    .toFloat()
                    .coerceAtLeast(
                        0f
                    )

            val main =
                sin(
                    t *
                        PI *
                        motion.mainFrequency +
                        phase
                )
                    .toFloat()

            val harmonic =
                sin(
                    t *
                        PI *
                        motion.detailFrequency -
                        phase *
                            0.73
                )
                    .toFloat() *
                    0.25f

            val y =
                cy +
                    (
                        main +
                            harmonic
                        ) *
                    amplitude *
                    edgeEnvelope

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
                        39.0 +
                        phase *
                            1.27
                )
                    .toFloat()

            val fineY =
                cy +
                    fine *
                    amplitude *
                    0.22f *
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

        val gradient =
            LinearGradient(
                left,
                cy,
                right,
                cy,
                intArrayOf(
                    Color.TRANSPARENT,
                    withAlpha(
                        color,
                        150
                    ),
                    color,
                    Color.WHITE,
                    color,
                    withAlpha(
                        color,
                        150
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.13f,
                    0.30f,
                    0.50f,
                    0.69f,
                    0.87f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        glowPaint.shader =
            gradient

        glowPaint.alpha =
            motion.waveGlowAlpha

        glowPaint.strokeWidth =
            dp(
                motion.waveGlowWidthDp
            )

        canvas.drawPath(
            wavePath,
            glowPaint
        )

        linePaint.shader =
            gradient

        linePaint.alpha =
            motion.waveAlpha

        linePaint.strokeWidth =
            dp(
                motion.waveWidthDp
            )

        canvas.drawPath(
            wavePath,
            linePaint
        )

        linePaint.shader =
            null

        linePaint.color =
            Color.WHITE

        linePaint.alpha =
            motion.fineWaveAlpha

        linePaint.strokeWidth =
            dp(0.55f)

        canvas.drawPath(
            fineWavePath,
            linePaint
        )
    }

    private fun drawOrbitSparks(
        canvas: Canvas,
        now: Long,
        imageBottom: Float,
        stateIndex: Int,
        motion: MotionProfile
    ) {
        if (
            stateIndex <
            0 ||
            motion.sparkCount <=
            0
        ) {
            return
        }

        val color =
            stateColor(
                stateIndex
            )

        val cx =
            width *
                0.50f

        val cy =
            imageBottom *
                0.50f

        val radius =
            min(
                width *
                    0.235f,
                imageBottom *
                    0.42f
            )

        val phase =
            now /
                motion.sparkPeriodMs

        for (
            index in
            0 until motion.sparkCount
        ) {
            val angle =
                phase *
                    motion.sparkDirection +
                    index *
                        (
                            PI *
                                2.0 /
                                motion.sparkCount
                            )

            val radialPulse =
                0.97f +
                    0.025f *
                        sin(
                            phase *
                                0.71 +
                                index *
                                    1.37
                        )
                            .toFloat()

            val r =
                radius *
                    radialPulse

            val x =
                cx +
                    cos(
                        angle
                    )
                        .toFloat() *
                    r

            val y =
                cy +
                    sin(
                        angle
                    )
                        .toFloat() *
                    r *
                    0.73f

            val pulse =
                (
                    0.5 +
                        0.5 *
                            sin(
                                phase *
                                    1.11 +
                                    index *
                                        1.91
                            )
                    )
                    .toFloat()

            fillPaint.shader =
                null

            fillPaint.color =
                if (
                    index %
                    5 ==
                    0
                ) {
                    Color.WHITE
                } else {
                    color
                }

            fillPaint.alpha =
                (
                    45 +
                        pulse *
                            motion.sparkAlphaRange
                    )
                    .toInt()
                    .coerceIn(
                        0,
                        210
                    )

            canvas.drawCircle(
                x,
                y,
                dp(
                    0.45f +
                        pulse *
                            0.65f
                ),
                fillPaint
            )
        }
    }

    private fun drawTravelingHighlight(
        canvas: Canvas,
        now: Long,
        imageBottom: Float,
        stateIndex: Int,
        motion: MotionProfile
    ) {
        if (
            stateIndex <
            0 ||
            motion.highlightAlpha <=
            0
        ) {
            return
        }

        val color =
            stateColor(
                stateIndex
            )

        val left =
            width *
                0.12f

        val right =
            width *
                0.88f

        val cy =
            imageBottom *
                0.515f

        val travel =
            (
                (
                    now %
                        motion.highlightPeriodMs
                    ).toFloat() /
                    motion.highlightPeriodMs.toFloat()
                )
                .coerceIn(
                    0f,
                    1f
                )

        val x =
            left +
                (
                    right -
                        left
                    ) *
                travel

        fillPaint.shader =
            RadialGradient(
                x,
                cy,
                dp(22f),
                intArrayOf(
                    Color.WHITE,
                    withAlpha(
                        color,
                        155
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.28f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fillPaint.alpha =
            motion.highlightAlpha

        canvas.drawCircle(
            x,
            cy,
            dp(22f),
            fillPaint
        )

        fillPaint.shader =
            null
    }

    private fun drawStageStrip(
        canvas: Canvas,
        activeIndex: Int,
        top: Float,
        height: Float
    ) {
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
                    0.79f

        linePaint.shader =
            null

        linePaint.color =
            Color.parseColor(
                "#2B3548"
            )

        linePaint.alpha =
            190

        linePaint.strokeWidth =
            dp(0.75f)

        canvas.drawLine(
            left,
            nodeY,
            right,
            nodeY,
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

            val active =
                index ==
                    activeIndex

            val color =
                stateColor(
                    index
                )

            if (active) {
                fillPaint.shader =
                    null

                fillPaint.color =
                    color

                fillPaint.alpha =
                    38

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(17.5f),
                    fillPaint
                )

                linePaint.color =
                    color

                linePaint.alpha =
                    255

                linePaint.strokeWidth =
                    dp(1.35f)

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(14.6f),
                    linePaint
                )

                fillPaint.color =
                    color

                fillPaint.alpha =
                    255

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(3.2f),
                    fillPaint
                )
            } else {
                linePaint.color =
                    Color.parseColor(
                        "#3B465A"
                    )

                linePaint.alpha =
                    220

                linePaint.strokeWidth =
                    dp(0.90f)

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(12.8f),
                    linePaint
                )

                fillPaint.color =
                    Color.parseColor(
                        "#647086"
                    )

                fillPaint.alpha =
                    205

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(2.2f),
                    fillPaint
                )
            }

            textPaint.textSize =
                dp(
                    if (active) {
                        9.4f
                    } else {
                        8.8f
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
                        "#7E899C"
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

    private fun stateIndex(
        state: String
    ): Int {
        return when (
            state
        ) {
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

    private fun stateColor(
        index: Int
    ): Int {
        return when (
            index
        ) {
            STATE_WAITING ->
                Color.parseColor(
                    "#19DCE7"
                )

            STATE_RECOGNITION ->
                Color.parseColor(
                    "#1687FF"
                )

            STATE_THINKING ->
                Color.parseColor(
                    "#633BFF"
                )

            STATE_EXECUTING ->
                Color.parseColor(
                    "#28E06E"
                )

            STATE_ANSWERING ->
                Color.parseColor(
                    "#E623C7"
                )

            STATE_STOP ->
                Color.parseColor(
                    "#FF3B22"
                )

            else ->
                Color.parseColor(
                    "#19DCE7"
                )
        }
    }

    private fun motionProfile(
        index: Int
    ): MotionProfile {
        return when (
            index
        ) {
            STATE_WAITING ->
                MotionProfile(
                    breathePeriodMs = 1050.0,
                    scaleAmplitude = 0.008f,
                    glowScaleExtra = 0.008f,
                    glowAlphaBase = 7f,
                    glowAlphaRange = 15f,
                    wavePeriodMs = 620.0,
                    waveHeightRatio = 0.045f,
                    maxWaveHeightDp = 13f,
                    mainFrequency = 7.0,
                    detailFrequency = 21.0,
                    waveGlowAlpha = 48,
                    waveGlowWidthDp = 5.4f,
                    waveAlpha = 122,
                    waveWidthDp = 0.92f,
                    fineWaveAlpha = 38,
                    sparkCount = 12,
                    sparkPeriodMs = 1800.0,
                    sparkDirection = 1.0,
                    sparkAlphaRange = 72f,
                    highlightAlpha = 54,
                    highlightPeriodMs = 3300L
                )

            STATE_RECOGNITION ->
                MotionProfile(
                    breathePeriodMs = 690.0,
                    scaleAmplitude = 0.010f,
                    glowScaleExtra = 0.010f,
                    glowAlphaBase = 10f,
                    glowAlphaRange = 20f,
                    wavePeriodMs = 370.0,
                    waveHeightRatio = 0.072f,
                    maxWaveHeightDp = 21f,
                    mainFrequency = 9.0,
                    detailFrequency = 27.0,
                    waveGlowAlpha = 68,
                    waveGlowWidthDp = 6.2f,
                    waveAlpha = 165,
                    waveWidthDp = 1.12f,
                    fineWaveAlpha = 48,
                    sparkCount = 16,
                    sparkPeriodMs = 1250.0,
                    sparkDirection = 1.0,
                    sparkAlphaRange = 96f,
                    highlightAlpha = 76,
                    highlightPeriodMs = 2200L
                )

            STATE_THINKING ->
                MotionProfile(
                    breathePeriodMs = 860.0,
                    scaleAmplitude = 0.012f,
                    glowScaleExtra = 0.011f,
                    glowAlphaBase = 9f,
                    glowAlphaRange = 18f,
                    wavePeriodMs = 520.0,
                    waveHeightRatio = 0.055f,
                    maxWaveHeightDp = 16f,
                    mainFrequency = 8.0,
                    detailFrequency = 31.0,
                    waveGlowAlpha = 58,
                    waveGlowWidthDp = 5.8f,
                    waveAlpha = 150,
                    waveWidthDp = 1.04f,
                    fineWaveAlpha = 54,
                    sparkCount = 18,
                    sparkPeriodMs = 1450.0,
                    sparkDirection = -1.0,
                    sparkAlphaRange = 92f,
                    highlightAlpha = 66,
                    highlightPeriodMs = 2700L
                )

            STATE_EXECUTING ->
                MotionProfile(
                    breathePeriodMs = 540.0,
                    scaleAmplitude = 0.012f,
                    glowScaleExtra = 0.012f,
                    glowAlphaBase = 11f,
                    glowAlphaRange = 22f,
                    wavePeriodMs = 310.0,
                    waveHeightRatio = 0.075f,
                    maxWaveHeightDp = 23f,
                    mainFrequency = 10.0,
                    detailFrequency = 29.0,
                    waveGlowAlpha = 78,
                    waveGlowWidthDp = 6.8f,
                    waveAlpha = 182,
                    waveWidthDp = 1.20f,
                    fineWaveAlpha = 52,
                    sparkCount = 20,
                    sparkPeriodMs = 980.0,
                    sparkDirection = 1.0,
                    sparkAlphaRange = 108f,
                    highlightAlpha = 86,
                    highlightPeriodMs = 1800L
                )

            STATE_ANSWERING ->
                MotionProfile(
                    breathePeriodMs = 620.0,
                    scaleAmplitude = 0.011f,
                    glowScaleExtra = 0.011f,
                    glowAlphaBase = 10f,
                    glowAlphaRange = 21f,
                    wavePeriodMs = 340.0,
                    waveHeightRatio = 0.068f,
                    maxWaveHeightDp = 20f,
                    mainFrequency = 9.0,
                    detailFrequency = 25.0,
                    waveGlowAlpha = 74,
                    waveGlowWidthDp = 6.4f,
                    waveAlpha = 178,
                    waveWidthDp = 1.18f,
                    fineWaveAlpha = 50,
                    sparkCount = 18,
                    sparkPeriodMs = 1120.0,
                    sparkDirection = -1.0,
                    sparkAlphaRange = 102f,
                    highlightAlpha = 82,
                    highlightPeriodMs = 1950L
                )

            STATE_STOP ->
                MotionProfile(
                    breathePeriodMs = 1550.0,
                    scaleAmplitude = 0.004f,
                    glowScaleExtra = 0.004f,
                    glowAlphaBase = 4f,
                    glowAlphaRange = 8f,
                    wavePeriodMs = 980.0,
                    waveHeightRatio = 0.026f,
                    maxWaveHeightDp = 8f,
                    mainFrequency = 5.0,
                    detailFrequency = 15.0,
                    waveGlowAlpha = 34,
                    waveGlowWidthDp = 4.0f,
                    waveAlpha = 92,
                    waveWidthDp = 0.82f,
                    fineWaveAlpha = 28,
                    sparkCount = 6,
                    sparkPeriodMs = 2500.0,
                    sparkDirection = -1.0,
                    sparkAlphaRange = 48f,
                    highlightAlpha = 32,
                    highlightPeriodMs = 4200L
                )

            else ->
                motionProfile(
                    STATE_WAITING
                )
        }
    }

    private fun frameDelayMs(
        index: Int
    ): Long {
        return when (
            index
        ) {
            STATE_RECOGNITION,
            STATE_EXECUTING,
            STATE_ANSWERING ->
                28L

            STATE_THINKING ->
                31L

            STATE_WAITING ->
                38L

            STATE_STOP ->
                58L

            else ->
                40L
        }
    }

    private fun withAlpha(
        color: Int,
        alpha: Int
    ): Int {
        return Color.argb(
            alpha
                .coerceIn(
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

    private data class MotionProfile(
        val breathePeriodMs: Double,
        val scaleAmplitude: Float,
        val glowScaleExtra: Float,
        val glowAlphaBase: Float,
        val glowAlphaRange: Float,
        val wavePeriodMs: Double,
        val waveHeightRatio: Float,
        val maxWaveHeightDp: Float,
        val mainFrequency: Double,
        val detailFrequency: Double,
        val waveGlowAlpha: Int,
        val waveGlowWidthDp: Float,
        val waveAlpha: Int,
        val waveWidthDp: Float,
        val fineWaveAlpha: Int,
        val sparkCount: Int,
        val sparkPeriodMs: Double,
        val sparkDirection: Double,
        val sparkAlphaRange: Float,
        val highlightAlpha: Int,
        val highlightPeriodMs: Long
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

        private const val STATE_COUNT =
            6

        private const val TRANSITION_MS =
            180f
    }
}
