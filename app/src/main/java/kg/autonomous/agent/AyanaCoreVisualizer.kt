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
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v6.0 — FULL REFERENCE LIVE CORE.
 *
 * Strong correction after v5.0/v5.1:
 *
 * - Uses six FULL 1536×1152 state references instead of cropped 512×273 strips.
 * - Preserves the full circular AYANA artwork and the original wordmark.
 * - The execution strip is overlaid on the lower black area and no longer
 *   reduces/crops the core.
 * - Adds restrained live motion over the approved artwork:
 *     • breathing light intensity;
 *     • moving waveform energy;
 *     • rotating telemetry arc highlights;
 *     • moving centre pulse;
 *     • crossfade between factual runtime states.
 * - No procedural replacement of the approved sphere.
 * - No ORB, routing, TTS, microphone, Accessibility or command logic changes.
 *
 * Required resources in app/src/main/res/drawable-nodpi:
 *   ayana_state_waiting.png
 *   ayana_state_recognition.png
 *   ayana_state_thinking.png
 *   ayana_state_executing.png
 *   ayana_state_answering.png
 *   ayana_state_stop.png
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
        }

    private val srcRect =
        Rect()

    private val dstRect =
        RectF()

    private val ringRect =
        RectF()

    private val wavePath =
        Path()

    private val fineWavePath =
        Path()

    private val bitmaps: Array<Bitmap?> =
        arrayOfNulls(6)

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
        super.onDraw(canvas)

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
            stateIndex(runtimeState)

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

        val motion =
            motionFor(currentIndex)

        val imageBounds =
            drawReferenceLayer(
                canvas = canvas,
                now = now,
                motion = motion
            )

        drawTelemetryRings(
            canvas = canvas,
            now = now,
            bounds = imageBounds,
            stateIndex = currentIndex,
            motion = motion
        )

        drawLiveWaveform(
            canvas = canvas,
            now = now,
            bounds = imageBounds,
            stateIndex = currentIndex,
            motion = motion
        )

        drawCenterTravelPulse(
            canvas = canvas,
            now = now,
            bounds = imageBounds,
            stateIndex = currentIndex,
            motion = motion
        )

        drawStageOverlay(
            canvas = canvas,
            activeIndex = currentIndex
        )

        if (attached) {
            postInvalidateDelayed(
                frameDelayFor(currentIndex)
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

    private fun drawReferenceLayer(
        canvas: Canvas,
        now: Long,
        motion: Motion
    ): RectF {
        val current =
            bitmaps
                .getOrNull(currentIndex)

        if (current == null) {
            return RectF()
        }

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

        val previous =
            bitmaps
                .getOrNull(previousIndex)

        val pulse =
            (
                0.5 +
                    0.5 *
                        sin(
                            now /
                                motion.breatheMs
                        )
                )
                .toFloat()

        // Keep geometry practically unchanged. Only a tiny scale breath is used.
        val scale =
            0.988f +
                pulse *
                    motion.scaleRange

        val bounds =
            calculateReferenceBounds(
                current,
                scale
            )

        if (
            previous != null &&
            previous !== current &&
            transition < 1f
        ) {
            val previousBounds =
                calculateReferenceBounds(
                    previous,
                    0.988f
                )

            drawBitmap(
                canvas,
                previous,
                previousBounds,
                (
                    255f *
                        (
                            1f -
                                transition
                            )
                    )
                    .toInt()
            )
        }

        drawBitmap(
            canvas,
            current,
            bounds,
            if (
                previous != null &&
                previous !== current &&
                transition < 1f
            ) {
                (
                    255f *
                        transition
                    )
                    .toInt()
            } else {
                255
            }
        )

        // Optical breathing glow: same reference, very low alpha.
        val glowBounds =
            RectF(
                bounds.left -
                    bounds.width() *
                        0.006f,
                bounds.top -
                    bounds.height() *
                        0.006f,
                bounds.right +
                    bounds.width() *
                        0.006f,
                bounds.bottom +
                    bounds.height() *
                        0.006f
            )

        drawBitmap(
            canvas,
            current,
            glowBounds,
            (
                5f +
                    pulse *
                        motion.glowAlpha
                )
                .toInt()
        )

        return bounds
    }

    private fun calculateReferenceBounds(
        bitmap: Bitmap,
        scale: Float
    ): RectF {
        val availableWidth =
            width.toFloat()

        val availableHeight =
            height.toFloat()

        val bitmapAspect =
            bitmap.width.toFloat() /
                bitmap.height.toFloat()

        // Full reference is 4:3. Fit by height so the entire circular artwork
        // remains visible; black side space blends into the visualizer background.
        var drawHeight =
            availableHeight *
                scale

        var drawWidth =
            drawHeight *
                bitmapAspect

        if (
            drawWidth >
            availableWidth *
                0.96f
        ) {
            drawWidth =
                availableWidth *
                    0.96f

            drawHeight =
                drawWidth /
                    bitmapAspect
        }

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
                2f -
                dp(3f)

        return RectF(
            left,
            top,
            left +
                drawWidth,
            top +
                drawHeight
        )
    }

    private fun drawBitmap(
        canvas: Canvas,
        bitmap: Bitmap,
        bounds: RectF,
        alpha: Int
    ) {
        srcRect.set(
            0,
            0,
            bitmap.width,
            bitmap.height
        )

        bitmapPaint.alpha =
            alpha.coerceIn(
                0,
                255
            )

        canvas.drawBitmap(
            bitmap,
            srcRect,
            bounds,
            bitmapPaint
        )
    }

    private fun drawTelemetryRings(
        canvas: Canvas,
        now: Long,
        bounds: RectF,
        stateIndex: Int,
        motion: Motion
    ) {
        if (bounds.isEmpty) {
            return
        }

        val color =
            stateColor(stateIndex)

        val cx =
            bounds.centerX()

        val cy =
            bounds.centerY()

        // Ring radius derived from the full approved reference geometry.
        val r =
            min(
                bounds.width(),
                bounds.height()
            ) *
                0.355f

        val phase =
            (
                now %
                    motion.ringPeriodMs
                ).toFloat() /
                motion.ringPeriodMs.toFloat() *
                360f *
                motion.direction

        ringRect.set(
            cx - r,
            cy - r,
            cx + r,
            cy + r
        )

        glowPaint.shader =
            null

        glowPaint.color =
            color

        glowPaint.alpha =
            motion.ringGlowAlpha

        glowPaint.strokeWidth =
            dp(4.5f)

        canvas.drawArc(
            ringRect,
            phase,
            48f,
            false,
            glowPaint
        )

        canvas.drawArc(
            ringRect,
            phase + 126f,
            34f,
            false,
            glowPaint
        )

        canvas.drawArc(
            ringRect,
            phase + 232f,
            58f,
            false,
            glowPaint
        )

        linePaint.shader =
            null

        linePaint.color =
            Color.WHITE

        linePaint.alpha =
            motion.ringLineAlpha

        linePaint.strokeWidth =
            dp(0.75f)

        canvas.drawArc(
            ringRect,
            phase,
            48f,
            false,
            linePaint
        )

        canvas.drawArc(
            ringRect,
            phase + 126f,
            34f,
            false,
            linePaint
        )

        canvas.drawArc(
            ringRect,
            phase + 232f,
            58f,
            false,
            linePaint
        )
    }

    private fun drawLiveWaveform(
        canvas: Canvas,
        now: Long,
        bounds: RectF,
        stateIndex: Int,
        motion: Motion
    ) {
        if (bounds.isEmpty) {
            return
        }

        val color =
            stateColor(stateIndex)

        val left =
            width *
                0.025f

        val right =
            width *
                0.975f

        val span =
            right -
                left

        val cy =
            bounds.centerY()

        val samples =
            126

        val phase =
            now /
                motion.wavePeriodMs

        val amplitude =
            min(
                height *
                    motion.waveHeightRatio,
                dp(
                    motion.waveMaxDp
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

            val envelope =
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

            val detail =
                sin(
                    t *
                        PI *
                        motion.detailFrequency -
                        phase *
                            0.79
                )
                    .toFloat() *
                    0.22f

            val y =
                cy +
                    (
                        main +
                            detail
                        ) *
                    amplitude *
                    envelope

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
                        43.0 +
                        phase *
                            1.31
                )
                    .toFloat()

            val fineY =
                cy +
                    fine *
                    amplitude *
                    0.18f *
                    envelope

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
                        140
                    ),
                    color,
                    Color.WHITE,
                    color,
                    withAlpha(
                        color,
                        140
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.12f,
                    0.31f,
                    0.50f,
                    0.69f,
                    0.88f,
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

    private fun drawCenterTravelPulse(
        canvas: Canvas,
        now: Long,
        bounds: RectF,
        stateIndex: Int,
        motion: Motion
    ) {
        if (bounds.isEmpty) {
            return
        }

        val color =
            stateColor(stateIndex)

        val left =
            width *
                0.08f

        val right =
            width *
                0.92f

        val travel =
            (
                (
                    now %
                        motion.pulsePeriodMs
                    ).toFloat() /
                    motion.pulsePeriodMs.toFloat()
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

        val y =
            bounds.centerY()

        fillPaint.shader =
            RadialGradient(
                x,
                y,
                dp(19f),
                intArrayOf(
                    Color.WHITE,
                    withAlpha(
                        color,
                        145
                    ),
                    Color.TRANSPARENT
                ),
                floatArrayOf(
                    0f,
                    0.30f,
                    1f
                ),
                Shader.TileMode.CLAMP
            )

        fillPaint.alpha =
            motion.pulseAlpha

        canvas.drawCircle(
            x,
            y,
            dp(19f),
            fillPaint
        )

        fillPaint.shader =
            null
    }

    private fun drawStageOverlay(
        canvas: Canvas,
        activeIndex: Int
    ) {
        val stripHeight =
            min(
                dp(55f),
                height *
                    0.19f
            )

        val top =
            height -
                stripHeight

        // Transparent-black overlay only at the bottom. It does not resize image.
        fillPaint.shader =
            null

        fillPaint.color =
            Color.parseColor(
                "#03060D"
            )

        fillPaint.alpha =
            218

        canvas.drawRect(
            0f,
            top,
            width.toFloat(),
            height.toFloat(),
            fillPaint
        )

        linePaint.shader =
            null

        linePaint.color =
            Color.parseColor(
                "#26334A"
            )

        linePaint.alpha =
            220

        linePaint.strokeWidth =
            dp(0.75f)

        canvas.drawLine(
            width *
                0.035f,
            top,
            width *
                0.965f,
            top,
            linePaint
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
                stripHeight *
                    0.35f

        val labelY =
            top +
                stripHeight *
                    0.80f

        linePaint.color =
            Color.parseColor(
                "#2A354B"
            )

        linePaint.alpha =
            185

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
                stateColor(index)

            if (active) {
                fillPaint.color =
                    color

                fillPaint.alpha =
                    35

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(17f),
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
                    dp(14.5f),
                    linePaint
                )

                fillPaint.color =
                    color

                fillPaint.alpha =
                    255

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(3.0f),
                    fillPaint
                )
            } else {
                linePaint.color =
                    Color.parseColor(
                        "#414A5D"
                    )

                linePaint.alpha =
                    220

                linePaint.strokeWidth =
                    dp(0.90f)

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(12.5f),
                    linePaint
                )

                fillPaint.color =
                    Color.parseColor(
                        "#657084"
                    )

                fillPaint.alpha =
                    205

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(2.1f),
                    fillPaint
                )
            }

            textPaint.textSize =
                dp(
                    if (active) {
                        9.5f
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
                        "#7D899D"
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
        return when (state) {
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
        return when(index) {
            STATE_WAITING ->
                Color.parseColor("#19DCE7")

            STATE_RECOGNITION ->
                Color.parseColor("#1687FF")

            STATE_THINKING ->
                Color.parseColor("#633BFF")

            STATE_EXECUTING ->
                Color.parseColor("#28E06E")

            STATE_ANSWERING ->
                Color.parseColor("#E623C7")

            STATE_STOP ->
                Color.parseColor("#FF3B22")

            else ->
                Color.parseColor("#19DCE7")
        }
    }

    private fun motionFor(
        index: Int
    ): Motion {
        return when(index) {
            STATE_WAITING ->
                Motion(
                    breatheMs = 1150.0,
                    scaleRange = 0.007f,
                    glowAlpha = 12f,
                    wavePeriodMs = 650.0,
                    waveHeightRatio = 0.030f,
                    waveMaxDp = 10f,
                    mainFrequency = 7.0,
                    detailFrequency = 21.0,
                    waveGlowAlpha = 46,
                    waveGlowWidthDp = 5.0f,
                    waveAlpha = 118,
                    waveWidthDp = 0.92f,
                    fineWaveAlpha = 35,
                    ringPeriodMs = 7200L,
                    direction = 1f,
                    ringGlowAlpha = 24,
                    ringLineAlpha = 70,
                    pulsePeriodMs = 3400L,
                    pulseAlpha = 56
                )

            STATE_RECOGNITION ->
                Motion(
                    breatheMs = 720.0,
                    scaleRange = 0.009f,
                    glowAlpha = 17f,
                    wavePeriodMs = 390.0,
                    waveHeightRatio = 0.055f,
                    waveMaxDp = 18f,
                    mainFrequency = 9.0,
                    detailFrequency = 27.0,
                    waveGlowAlpha = 64,
                    waveGlowWidthDp = 6.0f,
                    waveAlpha = 155,
                    waveWidthDp = 1.08f,
                    fineWaveAlpha = 45,
                    ringPeriodMs = 4800L,
                    direction = 1f,
                    ringGlowAlpha = 34,
                    ringLineAlpha = 90,
                    pulsePeriodMs = 2200L,
                    pulseAlpha = 72
                )

            STATE_THINKING ->
                Motion(
                    breatheMs = 900.0,
                    scaleRange = 0.010f,
                    glowAlpha = 16f,
                    wavePeriodMs = 520.0,
                    waveHeightRatio = 0.044f,
                    waveMaxDp = 14f,
                    mainFrequency = 8.0,
                    detailFrequency = 31.0,
                    waveGlowAlpha = 58,
                    waveGlowWidthDp = 5.7f,
                    waveAlpha = 145,
                    waveWidthDp = 1.02f,
                    fineWaveAlpha = 50,
                    ringPeriodMs = 5600L,
                    direction = -1f,
                    ringGlowAlpha = 32,
                    ringLineAlpha = 86,
                    pulsePeriodMs = 2700L,
                    pulseAlpha = 66
                )

            STATE_EXECUTING ->
                Motion(
                    breatheMs = 560.0,
                    scaleRange = 0.010f,
                    glowAlpha = 20f,
                    wavePeriodMs = 320.0,
                    waveHeightRatio = 0.060f,
                    waveMaxDp = 20f,
                    mainFrequency = 10.0,
                    detailFrequency = 29.0,
                    waveGlowAlpha = 76,
                    waveGlowWidthDp = 6.5f,
                    waveAlpha = 180,
                    waveWidthDp = 1.18f,
                    fineWaveAlpha = 50,
                    ringPeriodMs = 3600L,
                    direction = 1f,
                    ringGlowAlpha = 42,
                    ringLineAlpha = 100,
                    pulsePeriodMs = 1750L,
                    pulseAlpha = 82
                )

            STATE_ANSWERING ->
                Motion(
                    breatheMs = 650.0,
                    scaleRange = 0.009f,
                    glowAlpha = 18f,
                    wavePeriodMs = 350.0,
                    waveHeightRatio = 0.054f,
                    waveMaxDp = 18f,
                    mainFrequency = 9.0,
                    detailFrequency = 25.0,
                    waveGlowAlpha = 70,
                    waveGlowWidthDp = 6.2f,
                    waveAlpha = 172,
                    waveWidthDp = 1.14f,
                    fineWaveAlpha = 48,
                    ringPeriodMs = 4100L,
                    direction = -1f,
                    ringGlowAlpha = 38,
                    ringLineAlpha = 96,
                    pulsePeriodMs = 1950L,
                    pulseAlpha = 78
                )

            STATE_STOP ->
                Motion(
                    breatheMs = 1700.0,
                    scaleRange = 0.003f,
                    glowAlpha = 7f,
                    wavePeriodMs = 1050.0,
                    waveHeightRatio = 0.018f,
                    waveMaxDp = 6f,
                    mainFrequency = 5.0,
                    detailFrequency = 15.0,
                    waveGlowAlpha = 26,
                    waveGlowWidthDp = 3.5f,
                    waveAlpha = 74,
                    waveWidthDp = 0.78f,
                    fineWaveAlpha = 24,
                    ringPeriodMs = 12000L,
                    direction = -1f,
                    ringGlowAlpha = 14,
                    ringLineAlpha = 52,
                    pulsePeriodMs = 4800L,
                    pulseAlpha = 28
                )

            else ->
                motionFor(STATE_WAITING)
        }
    }

    private fun frameDelayFor(
        index: Int
    ): Long {
        return when(index) {
            STATE_RECOGNITION,
            STATE_EXECUTING,
            STATE_ANSWERING ->
                28L

            STATE_THINKING ->
                31L

            STATE_WAITING ->
                38L

            STATE_STOP ->
                60L

            else ->
                40L
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
        return value *
            density
    }

    private data class Motion(
        val breatheMs: Double,
        val scaleRange: Float,
        val glowAlpha: Float,
        val wavePeriodMs: Double,
        val waveHeightRatio: Float,
        val waveMaxDp: Float,
        val mainFrequency: Double,
        val detailFrequency: Double,
        val waveGlowAlpha: Int,
        val waveGlowWidthDp: Float,
        val waveAlpha: Int,
        val waveWidthDp: Float,
        val fineWaveAlpha: Int,
        val ringPeriodMs: Long,
        val direction: Float,
        val ringGlowAlpha: Int,
        val ringLineAlpha: Int,
        val pulsePeriodMs: Long,
        val pulseAlpha: Int
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

        private const val TRANSITION_MS =
            180f
    }
}
