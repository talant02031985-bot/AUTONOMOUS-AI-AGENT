package kg.autonomous.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.View
import kotlin.math.min

/**
 * AYANA Core Visualizer v5.0 — REFERENCE SIX-STATE VISUALS.
 *
 * This renderer intentionally stops approximating the approved design with
 * procedural Canvas geometry. It displays the six exact state artworks extracted
 * from the user-approved AYANA reference sheet:
 *
 * 1. Ожидание
 * 2. Распознавание
 * 3. Думаю
 * 4. Выполняю
 * 5. Отвечаю
 * 6. Стоп
 *
 * Required Android resources (place unchanged in app/src/main/res/drawable-nodpi):
 * - ayana_state_waiting.png
 * - ayana_state_recognition.png
 * - ayana_state_thinking.png
 * - ayana_state_executing.png
 * - ayana_state_answering.png
 * - ayana_state_stop.png
 *
 * Public integration contract stays unchanged:
 *     AyanaCoreVisualizer(Context)
 *
 * No ORB logic, microphone logic, Accessibility actions, routing, TTS or command
 * execution is changed here.
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

    private val linePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
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

    private val bitmaps: Array<Bitmap?> =
        arrayOfNulls(6)

    private var currentIndex =
        -1

    private var previousIndex =
        -1

    private var transitionStartedAt =
        0L

    private var loaded =
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
            Color.BLACK
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        ensureBitmaps()

        invalidate()
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

        val requestedIndex =
            stateIndex(
                AyanaVoiceService.currentStatusState
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

        drawReferenceArtwork(
            canvas = canvas,
            now = now,
            imageBottom = imageBottom
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
    }

    private fun ensureBitmaps() {
        if (loaded) {
            return
        }

        bitmaps[0] =
            decode(
                R.drawable.ayana_state_waiting
            )

        bitmaps[1] =
            decode(
                R.drawable.ayana_state_recognition
            )

        bitmaps[2] =
            decode(
                R.drawable.ayana_state_thinking
            )

        bitmaps[3] =
            decode(
                R.drawable.ayana_state_executing
            )

        bitmaps[4] =
            decode(
                R.drawable.ayana_state_answering
            )

        bitmaps[5] =
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

    private fun drawReferenceArtwork(
        canvas: Canvas,
        now: Long,
        imageBottom: Float
    ) {
        val current =
            bitmaps
                .getOrNull(
                    currentIndex
                )

        val previous =
            bitmaps
                .getOrNull(
                    previousIndex
                )

        if (
            current ==
            null
        ) {
            return
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
                imageBottom = imageBottom
            )
        }

        drawBitmapFitted(
            canvas = canvas,
            bitmap = current,
            alpha =
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
                },
            imageBottom = imageBottom
        )

        if (
            previous !=
            null &&
            previous !==
            current &&
            transition <
            1f
        ) {
            postInvalidateDelayed(
                16L
            )
        }
    }

    private fun drawBitmapFitted(
        canvas: Canvas,
        bitmap: Bitmap,
        alpha: Int,
        imageBottom: Float
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

        val drawWidth: Float
        val drawHeight: Float

        if (
            areaAspect >
            bitmapAspect
        ) {
            drawHeight =
                availableHeight

            drawWidth =
                drawHeight *
                    bitmapAspect
        } else {
            drawWidth =
                availableWidth

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

        val colors =
            intArrayOf(
                Color.parseColor("#19DCE7"),
                Color.parseColor("#1687FF"),
                Color.parseColor("#633BFF"),
                Color.parseColor("#28E06E"),
                Color.parseColor("#E623C7"),
                Color.parseColor("#FF3B22")
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
                colors[index]

            if (active) {
                bitmapPaint.color =
                    color

                bitmapPaint.alpha =
                    36

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(17.5f),
                    bitmapPaint
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

                bitmapPaint.color =
                    color

                bitmapPaint.alpha =
                    255

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(3.2f),
                    bitmapPaint
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

                bitmapPaint.color =
                    Color.parseColor(
                        "#647086"
                    )

                bitmapPaint.alpha =
                    205

                canvas.drawCircle(
                    x,
                    nodeY,
                    dp(2.2f),
                    bitmapPaint
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
                0

            AyanaVoiceService.STATE_COMMAND ->
                1

            AyanaVoiceService.STATE_THINKING ->
                2

            AyanaVoiceService.STATE_EXECUTING ->
                3

            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT,
            AyanaVoiceService.STATE_SUCCESS ->
                4

            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR ->
                5

            else ->
                0
        }
    }

    private fun dp(
        value: Float
    ): Float {
        return value *
            density
    }

    companion object {

        private const val TRANSITION_MS =
            180f
    }
}
