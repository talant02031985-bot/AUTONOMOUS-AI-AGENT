package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Audio Visualizer v1.0
 *
 * Отдельный графический модуль главного экрана.
 * Визуальный принцип: яркое центральное энергетическое ядро, плотная
 * горизонтальная звуковая волна, концентрические орбиты, плазменные
 * контуры и световые частицы. Фоновые bitmap/PNG не используются.
 *
 * Важно для производительности:
 * - все Paint/Path переиспользуются;
 * - LinearGradient/RadialGradient создаются только при изменении размера
 *   или состояния AYANA;
 * - нет Random на каждом кадре;
 * - частота кадров ограничена по состоянию.
 */
class AyanaAudioVisualizerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val ribbonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val flarePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val waveGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val baselinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }

    private val wordGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.SQUARE
        strokeJoin = Paint.Join.MITER
    }

    private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            "sans-serif",
            android.graphics.Typeface.NORMAL
        )
    }

    private val ribbonPath = Path()
    private val wordPath = Path()

    private val wordWeights = floatArrayOf(0.18f, 0.17f, 0.18f, 0.22f, 0.18f)
    private val wordWeightSum = wordWeights.sum()

    private var attached = false

    private var shaderState = ""
    private var shaderWidth = 0
    private var shaderHeight = 0

    private var waveShader: Shader? = null
    private var wordShader: Shader? = null
    private var haloShader: Shader? = null
    private var coreShader: Shader? = null

    private val waiting = intArrayOf(
        Color.rgb(0, 207, 255),
        Color.rgb(45, 112, 255),
        Color.rgb(101, 76, 255),
        Color.rgb(183, 61, 255)
    )

    private val listening = intArrayOf(
        Color.rgb(0, 255, 236),
        Color.rgb(0, 222, 208),
        Color.rgb(0, 219, 147),
        Color.rgb(73, 255, 164)
    )

    private val thinking = intArrayOf(
        Color.rgb(31, 194, 255),
        Color.rgb(67, 111, 255),
        Color.rgb(126, 83, 255),
        Color.rgb(198, 66, 255)
    )

    private val executing = intArrayOf(
        Color.rgb(31, 208, 255),
        Color.rgb(70, 145, 255),
        Color.rgb(255, 204, 42),
        Color.rgb(255, 118, 18)
    )

    private val speaking = intArrayOf(
        Color.rgb(255, 205, 47),
        Color.rgb(255, 119, 22),
        Color.rgb(255, 45, 98),
        Color.rgb(239, 39, 178)
    )

    private val success = intArrayOf(
        Color.rgb(38, 240, 175),
        Color.rgb(17, 214, 109),
        Color.rgb(75, 238, 140),
        Color.rgb(185, 255, 201)
    )

    private val error = intArrayOf(
        Color.rgb(255, 124, 30),
        Color.rgb(255, 61, 43),
        Color.rgb(245, 32, 82),
        Color.rgb(255, 155, 48)
    )

    private val cancelled = intArrayOf(
        Color.rgb(255, 227, 102),
        Color.rgb(245, 158, 11),
        Color.rgb(255, 122, 26),
        Color.rgb(249, 115, 22)
    )

    private val stopped = intArrayOf(
        Color.rgb(100, 116, 139),
        Color.rgb(124, 138, 160),
        Color.rgb(148, 163, 184),
        Color.rgb(71, 85, 105)
    )

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postInvalidateDelayed(60L)
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderState = ""
        shaderWidth = 0
        shaderHeight = 0
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 2f || h <= 2f) return

        val state = AyanaVoiceService.currentStatusState
        val palette = paletteFor(state)
        ensureShaders(state, palette)

        val now = SystemClock.uptimeMillis()
        val t = now / 1000.0

        val cx = w * 0.5f
        val cy = h * 0.5f

        // Сфера должна визуально доминировать, как в выбранном референсе.
        val sphereRadius = min(h * 0.46f, w * 0.31f)
        val particleRadius = sphereRadius * 1.06f
        val outerRadius = sphereRadius * 1.20f

        drawCore(canvas, state, sphereRadius, cx, cy)
        drawRings(canvas, state, palette, t, sphereRadius, cx, cy)
        drawPlasma(canvas, state, palette, t, sphereRadius, cx, cy)
        drawParticles(canvas, palette, t, particleRadius, outerRadius, cx, cy)
        drawWave(canvas, state, palette, t, w, h, cx, cy)
        drawWordmark(canvas, sphereRadius, cx, cy)

        if (attached && isShown) {
            postInvalidateDelayed(frameDelay(state))
        }
    }

    private fun drawCore(
        canvas: Canvas,
        state: String,
        radius: Float,
        cx: Float,
        cy: Float
    ) {
        haloPaint.shader = haloShader
        haloPaint.alpha = when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_SPEAKING -> 242
            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_THINKING -> 220
            AyanaVoiceService.STATE_STOPPED -> 95
            else -> 195
        }
        canvas.drawCircle(cx, cy, radius * 1.13f, haloPaint)

        corePaint.shader = coreShader
        corePaint.alpha = when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_SPEAKING -> 255
            AyanaVoiceService.STATE_EXECUTING -> 245
            AyanaVoiceService.STATE_STOPPED -> 135
            else -> 225
        }
        canvas.drawCircle(cx, cy, radius * 0.70f, corePaint)
    }

    private fun drawRings(
        canvas: Canvas,
        state: String,
        palette: IntArray,
        t: Double,
        radius: Float,
        cx: Float,
        cy: Float
    ) {
        val speed = when (state) {
            AyanaVoiceService.STATE_COMMAND -> 31.0
            AyanaVoiceService.STATE_THINKING -> 21.0
            AyanaVoiceService.STATE_EXECUTING -> 38.0
            AyanaVoiceService.STATE_SPEAKING -> 43.0
            else -> 12.0
        }

        // Плотная глубина из тонких концентрических окружностей.
        for (i in 0 until 13) {
            val r = radius * (0.34f + i * 0.056f)
            ringPaint.color = palette[i % palette.size]
            ringPaint.alpha = 20 + i * 6
            ringPaint.strokeWidth = if (i % 4 == 0) dp(1.25f) else dp(0.72f)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }

        // Внешние сегментированные орбиты — не сплошные круги.
        for (i in 0 until 8) {
            val r = radius * (0.68f + i * 0.071f)
            val direction = if (i % 2 == 0) 1.0 else -0.67
            val phase = (t * speed * direction + i * 37.0).toFloat()

            ringPaint.color = palette[(i + 1) % palette.size]
            ringPaint.alpha = 58 + i * 10
            ringPaint.strokeWidth = if (i % 2 == 0) dp(1.2f) else dp(1.8f)

            canvas.drawArc(
                cx - r, cy - r, cx + r, cy + r,
                phase, 46f + i * 3f, false, ringPaint
            )
            canvas.drawArc(
                cx - r, cy - r, cx + r, cy + r,
                phase + 117f, 17f + i * 2f, false, ringPaint
            )
            canvas.drawArc(
                cx - r, cy - r, cx + r, cy + r,
                phase + 225f, 29f + i * 2f, false, ringPaint
            )
        }
    }

    private fun drawPlasma(
        canvas: Canvas,
        state: String,
        palette: IntArray,
        t: Double,
        radius: Float,
        cx: Float,
        cy: Float
    ) {
        val speed = when (state) {
            AyanaVoiceService.STATE_COMMAND -> 2.65
            AyanaVoiceService.STATE_THINKING -> 1.70
            AyanaVoiceService.STATE_EXECUTING -> 3.00
            AyanaVoiceService.STATE_SPEAKING -> 3.45
            else -> 1.10
        }

        // Восемь вложенных плазменных контуров создают объёмную сферу.
        for (layer in 0 until 8) {
            ribbonPath.reset()
            val samples = 92

            for (i in 0..samples) {
                val a = i.toDouble() / samples.toDouble() * PI * 2.0
                val harmonic = 3.0 + (layer % 4)
                val wobble =
                    0.72 +
                        0.075 * sin(a * harmonic + t * speed + layer * 0.64) +
                        0.048 * sin(a * (7.2 + layer * 0.31) - t * 1.17 + layer)

                val rr = radius * wobble.toFloat() * (0.95f + layer * 0.014f)
                val rotation = layer * 0.43 + t * if (layer % 2 == 0) 0.042 else -0.034
                val angle = a + rotation
                val x = cx + cos(angle).toFloat() * rr
                val y = cy + sin(angle).toFloat() * rr * (0.88f + layer * 0.006f)

                if (i == 0) ribbonPath.moveTo(x, y) else ribbonPath.lineTo(x, y)
            }

            ribbonPath.close()
            ribbonPaint.color = palette[layer % palette.size]
            ribbonPaint.alpha = 28 + layer * 10
            ribbonPaint.strokeWidth = if (layer % 3 == 1) dp(1.9f) else dp(1.0f)
            canvas.drawPath(ribbonPath, ribbonPaint)
        }
    }

    private fun drawParticles(
        canvas: Canvas,
        palette: IntArray,
        t: Double,
        innerRadius: Float,
        outerRadius: Float,
        cx: Float,
        cy: Float
    ) {
        // Световая пыль внутри кольца.
        val innerCount = 68
        for (i in 0 until innerCount) {
            val base = i.toDouble() / innerCount.toDouble() * PI * 2.0
            val angle = base + t * 0.075
            val pulse = ((sin(t * 2.15 + i * 0.73) + 1.0) * 0.5).toFloat()
            val r = innerRadius * (0.968f + pulse * 0.032f)
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r

            particlePaint.color = palette[i % palette.size]
            particlePaint.alpha = (58 + pulse * 170f).toInt()
            canvas.drawCircle(
                x, y,
                if (i % 13 == 0) dp(2.0f) else dp(0.85f),
                particlePaint
            )
        }

        // Плотный внешний пояс частиц, как у референса Depositphotos.
        val outerCount = 112
        for (i in 0 until outerCount) {
            val angle = i.toDouble() / outerCount.toDouble() * PI * 2.0 - t * 0.038
            val twinkle = ((sin(t * 1.65 + i * 1.09) + 1.0) * 0.5).toFloat()
            val r = outerRadius * (0.983f + twinkle * 0.018f)
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r
            val color = palette[(i + 2) % palette.size]

            particlePaint.color = color
            particlePaint.alpha = (60 + twinkle * 180f).toInt()
            canvas.drawCircle(
                x, y,
                if (i % 17 == 0) dp(2.0f) else dp(0.78f),
                particlePaint
            )

            if (i % 14 == 0) {
                flarePaint.color = color
                flarePaint.alpha = (90 + twinkle * 130f).toInt()
                flarePaint.strokeWidth = dp(0.9f)
                val arm = dp(3.5f) * (0.72f + twinkle * 0.45f)
                canvas.drawLine(x - arm, y, x + arm, y, flarePaint)
                canvas.drawLine(x, y - arm, x, y + arm, flarePaint)
            }
        }

        // Небольшие ноты по внешней орбите — деталь из выбранных референсов.
        notePaint.textSize = dp(9.0f)
        notePaint.alpha = 118
        for (i in 0 until 8) {
            val angle = (i / 8.0) * PI * 2.0 + t * 0.025
            val r = outerRadius * 0.93f
            val x = cx + cos(angle).toFloat() * r
            val y = cy + sin(angle).toFloat() * r
            notePaint.color = palette[(i + 1) % palette.size]
            canvas.drawText(if (i % 2 == 0) "♪" else "♫", x, y, notePaint)
        }
    }

    private fun drawWave(
        canvas: Canvas,
        state: String,
        palette: IntArray,
        t: Double,
        w: Float,
        h: Float,
        cx: Float,
        cy: Float
    ) {
        val strength = when (state) {
            AyanaVoiceService.STATE_COMMAND -> 0.94f
            AyanaVoiceService.STATE_THINKING -> 0.57f
            AyanaVoiceService.STATE_EXECUTING -> 0.77f
            AyanaVoiceService.STATE_SPEAKING -> 1.00f
            AyanaVoiceService.STATE_SUCCESS -> 0.38f
            AyanaVoiceService.STATE_ERROR -> 0.53f
            AyanaVoiceService.STATE_CANCELLED -> 0.36f
            AyanaVoiceService.STATE_STOPPED -> 0.12f
            else -> 0.32f
        }

        val speed = when (state) {
            AyanaVoiceService.STATE_COMMAND -> 5.25
            AyanaVoiceService.STATE_THINKING -> 2.20
            AyanaVoiceService.STATE_EXECUTING -> 4.05
            AyanaVoiceService.STATE_SPEAKING -> 6.10
            AyanaVoiceService.STATE_ERROR -> 3.20
            else -> 1.42
        }

        baselinePaint.color = palette[1]
        baselinePaint.alpha = 95
        baselinePaint.strokeWidth = dp(0.85f)
        canvas.drawLine(0f, cy, w, cy, baselinePaint)

        waveGlowPaint.shader = waveShader
        wavePaint.shader = waveShader

        // 256 столбцов дают плотность, близкую к референсу, но остаются лёгкими.
        val count = 256
        val maxSpike = h * 0.315f * strength

        for (i in 0 until count) {
            val xNorm = i.toFloat() / (count - 1).toFloat()
            val signed = xNorm * 2f - 1f
            val x = xNorm * w

            val center = 1f - abs(signed)
            val envelope = (0.24f + 0.76f * center).coerceIn(0.24f, 1f)
            val sideBurst = (0.60 + 0.40 * abs(sin(i * 0.17 + t * 0.30))).toFloat()

            val signal =
                0.34 * sin(i * 0.29 + t * speed) +
                    0.25 * sin(i * 0.73 - t * speed * 0.71) +
                    0.19 * sin(i * 1.39 + t * speed * 0.38) +
                    0.13 * sin(i * 2.19 - t * speed * 0.20) +
                    0.09 * sin(i * 3.07 + t * 0.43)

            val spike =
                dp(0.9f) +
                    abs(signal).toFloat() *
                    maxSpike *
                    envelope *
                    sideBurst

            // Два прохода одного импульса создают яркую бело-неоновую сердцевину.
            waveGlowPaint.strokeWidth = dp(5.0f)
            waveGlowPaint.alpha = 34
            canvas.drawLine(x, cy - spike, x, cy + spike, waveGlowPaint)

            wavePaint.strokeWidth = dp(0.92f)
            wavePaint.alpha = 245
            canvas.drawLine(x, cy - spike, x, cy + spike, wavePaint)
        }

        // Бело-горячий центр звуковой линии, как на исходном изображении.
        baselinePaint.color = Color.WHITE
        baselinePaint.alpha = 150
        baselinePaint.strokeWidth = dp(1.15f)
        canvas.drawLine(cx - w * 0.19f, cy, cx + w * 0.19f, cy, baselinePaint)
    }

    private fun drawWordmark(
        canvas: Canvas,
        radius: Float,
        cx: Float,
        cy: Float
    ) {
        val totalWidth = radius * 2.04f
        val height = radius * 0.48f
        val gap = totalWidth * 0.032f
        val usable = totalWidth - gap * 4f

        var left = cx - totalWidth * 0.5f
        val top = cy - height * 0.50f
        val bottom = cy + height * 0.50f

        // Намеренно толще предыдущей версии: на референсе AYANA — массивный логотип.
        wordGlowPaint.shader = wordShader
        wordGlowPaint.alpha = 78
        wordGlowPaint.strokeWidth = max(dp(11f), height * 0.19f)

        wordPaint.shader = wordShader
        wordPaint.alpha = 255
        wordPaint.strokeWidth = max(dp(5.4f), height * 0.105f)

        for (index in 0 until 5) {
            val letterWidth = usable * (wordWeights[index] / wordWeightSum)
            wordPath.reset()

            when (index) {
                0, 2, 4 -> {
                    // Открытая A без перекладины — как на трёх утверждённых изображениях.
                    wordPath.moveTo(left, bottom)
                    wordPath.lineTo(left + letterWidth * 0.50f, top)
                    wordPath.lineTo(left + letterWidth, bottom)
                }

                1 -> {
                    wordPath.moveTo(left, top)
                    wordPath.lineTo(left + letterWidth * 0.50f, cy - height * 0.04f)
                    wordPath.moveTo(left + letterWidth, top)
                    wordPath.lineTo(left + letterWidth * 0.50f, cy - height * 0.04f)
                    wordPath.lineTo(left + letterWidth * 0.50f, bottom)
                }

                3 -> {
                    wordPath.moveTo(left, bottom)
                    wordPath.lineTo(left, top)
                    wordPath.lineTo(left + letterWidth, bottom)
                    wordPath.lineTo(left + letterWidth, top)
                }
            }

            canvas.drawPath(wordPath, wordGlowPaint)
            canvas.drawPath(wordPath, wordPaint)
            left += letterWidth + gap
        }
    }

    private fun paletteFor(state: String): IntArray {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND -> listening
            AyanaVoiceService.STATE_THINKING -> thinking
            AyanaVoiceService.STATE_EXECUTING -> executing
            AyanaVoiceService.STATE_SPEAKING -> speaking
            AyanaVoiceService.STATE_SUCCESS -> success
            AyanaVoiceService.STATE_ERROR -> error
            AyanaVoiceService.STATE_CANCELLED -> cancelled
            AyanaVoiceService.STATE_STOPPED -> stopped
            else -> waiting
        }
    }

    private fun frameDelay(state: String): Long {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND -> 32L
            AyanaVoiceService.STATE_SPEAKING -> 32L
            AyanaVoiceService.STATE_EXECUTING -> 34L
            AyanaVoiceService.STATE_THINKING -> 40L
            AyanaVoiceService.STATE_LISTENING -> 55L
            AyanaVoiceService.STATE_SUCCESS,
            AyanaVoiceService.STATE_ERROR -> 48L
            else -> 70L
        }
    }

    private fun ensureShaders(state: String, palette: IntArray) {
        if (
            shaderState == state &&
            shaderWidth == width &&
            shaderHeight == height &&
            waveShader != null &&
            wordShader != null &&
            haloShader != null &&
            coreShader != null
        ) {
            return
        }

        shaderState = state
        shaderWidth = width
        shaderHeight = height

        val w = width.toFloat().coerceAtLeast(1f)
        val h = height.toFloat().coerceAtLeast(1f)
        val cx = w * 0.5f
        val cy = h * 0.5f
        val radius = min(h * 0.46f, w * 0.31f).coerceAtLeast(1f)

        waveShader = LinearGradient(
            0f, cy, w, cy,
            intArrayOf(
                palette[0],
                palette[1],
                Color.WHITE,
                palette[2],
                palette[3]
            ),
            floatArrayOf(0f, 0.25f, 0.50f, 0.75f, 1f),
            Shader.TileMode.CLAMP
        )

        wordShader = LinearGradient(
            cx - radius, cy,
            cx + radius, cy,
            intArrayOf(
                palette[0],
                palette[1],
                palette[2],
                palette[3]
            ),
            floatArrayOf(0f, 0.34f, 0.68f, 1f),
            Shader.TileMode.CLAMP
        )

        haloShader = RadialGradient(
            cx, cy, radius * 1.13f,
            intArrayOf(
                Color.argb(248, 255, 255, 255),
                Color.argb(
                    190,
                    Color.red(palette[1]),
                    Color.green(palette[1]),
                    Color.blue(palette[1])
                ),
                Color.argb(
                    86,
                    Color.red(palette[2]),
                    Color.green(palette[2]),
                    Color.blue(palette[2])
                ),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.18f, 0.61f, 1f),
            Shader.TileMode.CLAMP
        )

        coreShader = RadialGradient(
            cx, cy, radius * 0.72f,
            intArrayOf(
                Color.argb(255, 255, 255, 255),
                Color.argb(
                    205,
                    Color.red(palette[0]),
                    Color.green(palette[0]),
                    Color.blue(palette[0])
                ),
                Color.argb(
                    110,
                    Color.red(palette[2]),
                    Color.green(palette[2]),
                    Color.blue(palette[2])
                ),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.21f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun dp(value: Float): Float = value * density
}
