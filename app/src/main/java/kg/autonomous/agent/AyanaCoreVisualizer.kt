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
 * AYANA Core Visualizer v3.0 — COMMAND CORE.
 *
 * Visual-only replacement for the main AYANA visualization surface.
 * Large contained cyan/blue/violet core, no AYANA wordmark, waveform through
 * the centre, and a six-stage state strip bound to factual VoiceService states.
 * No touch handling, no Accessibility actions, no permissions, no ORB changes.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density = resources.displayMetrics.density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val wave = Path()
    private val fineWave = Path()
    private val ribbon = Path()
    private val arc = RectF()

    private var attached = false
    private var shaderW = -1
    private var shaderH = -1
    private var shaderState = ""

    private var palette = Palette.default()
    private var ambient: RadialGradient? = null
    private var glass: RadialGradient? = null
    private var nucleus: RadialGradient? = null
    private var waveGradient: LinearGradient? = null

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        isLongClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        rebuildShaders(AyanaVoiceService.currentStatusState)
        postInvalidateDelayed(frameDelay(AyanaVoiceService.currentStatusState))
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderW = -1
        shaderH = -1
        rebuildShaders(AyanaVoiceService.currentStatusState)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val state = AyanaVoiceService.currentStatusState
        if (state != shaderState || width != shaderW || height != shaderH) {
            rebuildShaders(state)
        }

        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val compact = h < dp(190f)

        val stageHeight = if (compact) 0f else min(dp(68f), h * 0.22f)
        val stageTop = h - stageHeight
        val visualTop = dp(if (compact) 8f else 10f)
        val visualBottom = if (compact) h - dp(8f) else stageTop - dp(8f)
        val visualHeight = (visualBottom - visualTop).coerceAtLeast(dp(48f))

        val cx = w * 0.50f
        val cy = visualTop + visualHeight * 0.49f
        val edge = dp(if (compact) 8f else 12f)

        val safeRadius = min(
            (visualHeight * 0.5f - edge) / 1.18f,
            (w * 0.5f - edge) / 1.18f
        ).coerceAtLeast(dp(20f))

        val preferredRadius = min(
            visualHeight * if (compact) 0.39f else 0.43f,
            w * if (compact) 0.20f else 0.23f
        )

        val radius = min(safeRadius, preferredRadius).coerceAtLeast(dp(22f))
        val energy = stateEnergy(state)
        val breathe = (0.5 + 0.5 * sin(now / (780.0 - energy * 170.0))).toFloat()

        drawAmbient(canvas, cx, cy, radius, breathe)
        drawWave(canvas, now, cx, cy, radius, energy, visualTop, visualBottom)
        drawTelemetry(canvas, now, cx, cy, radius, energy)
        drawRibbons(canvas, now, cx, cy, radius, energy)
        drawLens(canvas, cx, cy, radius, breathe)
        drawArcs(canvas, now, cx, cy, radius, energy)
        drawCenterPulse(canvas, cx, cy, radius, breathe)

        if (stageHeight > 0f) {
            drawStages(canvas, state, stageTop, stageHeight)
        }

        if (attached) {
            postInvalidateDelayed(frameDelay(state))
        }
    }

    private fun drawAmbient(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fill.shader = ambient
        fill.alpha = 235
        canvas.drawCircle(cx, cy, radius * (1.14f + breathe * 0.018f), fill)

        fill.shader = null
        fill.color = withAlpha(palette.secondary, 16)
        canvas.drawCircle(cx, cy, radius * 1.16f, fill)
    }

    private fun drawWave(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        visualTop: Float,
        visualBottom: Float
    ) {
        val left = width * 0.055f
        val right = width * 0.945f
        val span = right - left
        val samples = 96
        val phase = now / (530.0 - energy * 130.0)

        val maxAmp = min(
            (visualBottom - visualTop) * 0.15f,
            radius * 0.42f
        ) * (0.48f + energy * 0.56f)

        wave.reset()
        fineWave.reset()

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + span * t
            val centerDistance =
                (abs(x - cx) / (radius * 1.22f)).coerceIn(0f, 1f)
            val centerGain = 0.42f + centerDistance * 0.58f
            val envelope = sin(PI * t).toFloat().coerceAtLeast(0f)

            val carrier = sin(t * PI * 8.0 + phase).toFloat()
            val detail = sin(t * PI * 23.0 - phase * 0.78).toFloat() * 0.24f
            val y = cy + (carrier + detail) * maxAmp * envelope * centerGain

            if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)

            val fine = sin(t * PI * 43.0 + phase * 1.16).toFloat()
            val fy = cy + fine * maxAmp * 0.19f * envelope
            if (i == 0) fineWave.moveTo(x, fy) else fineWave.lineTo(x, fy)
        }

        glow.shader = waveGradient
        glow.alpha = (40 + energy * 42f).toInt()
        glow.strokeWidth = dp(7f)
        canvas.drawPath(wave, glow)

        stroke.shader = waveGradient
        stroke.alpha = (160 + energy * 74f).toInt().coerceAtMost(238)
        stroke.strokeWidth = dp(1.55f)
        canvas.drawPath(wave, stroke)

        stroke.shader = null
        stroke.color = palette.white
        stroke.alpha = 62
        stroke.strokeWidth = dp(0.62f)
        canvas.drawPath(fineWave, stroke)

        val flareHalf = radius * (0.22f + energy * 0.08f)
        glow.shader = null
        glow.color = palette.primary
        glow.alpha = 38
        glow.strokeWidth = dp(5.5f)
        canvas.drawLine(cx, cy - flareHalf, cx, cy + flareHalf, glow)

        stroke.color = palette.white
        stroke.alpha = 132
        stroke.strokeWidth = dp(0.72f)
        canvas.drawLine(
            cx,
            cy - flareHalf * 0.74f,
            cx,
            cy + flareHalf * 0.74f,
            stroke
        )
    }

    private fun drawTelemetry(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        val outer = radius * 1.08f
        arc.set(cx - outer, cy - outer, cx + outer, cy + outer)

        val phase = continuousAngle(
            now,
            20800f - energy * 3200f,
            false
        )

        stroke.shader = null
        stroke.color = palette.secondary
        stroke.alpha = 44
        stroke.strokeWidth = dp(0.75f)
        canvas.drawCircle(cx, cy, outer, stroke)

        stroke.color = palette.primary
        stroke.alpha = 72
        stroke.strokeWidth = dp(1.1f)
        canvas.drawArc(arc, phase, 38f, false, stroke)
        canvas.drawArc(arc, phase + 104f, 22f, false, stroke)
        canvas.drawArc(arc, phase + 188f, 48f, false, stroke)
        canvas.drawArc(arc, phase + 298f, 25f, false, stroke)

        repeat(14) { i ->
            val angle = phase + i * (360f / 14f)
            val rad = angle * PI / 180.0
            val orbit = radius * (1.04f + if (i % 3 == 0) 0.035f else 0f)
            val x = cx + cos(rad).toFloat() * orbit
            val y = cy + sin(rad).toFloat() * orbit

            fill.shader = null
            fill.color = if (i % 4 == 0) palette.accent else palette.primary
            fill.alpha = if (i % 3 == 0) 150 else 78
            canvas.drawCircle(
                x,
                y,
                dp(if (i % 3 == 0) 1.25f else 0.72f),
                fill
            )
        }
    }

    private fun drawRibbons(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        drawRibbon(
            canvas, cx, cy, radius * 0.72f,
            now / 1260.0, -20f, 2.15f,
            palette.primary, 170, 1.55f
        )
        drawRibbon(
            canvas, cx, cy, radius * 0.68f,
            -now / 1510.0, 27f, 2.55f,
            palette.accent, 142, 1.35f
        )
        drawRibbon(
            canvas, cx, cy, radius * 0.61f,
            now / 1780.0, 77f, 2.35f,
            palette.secondary, 118, 1.05f
        )

        if (energy > 0.70f) {
            drawRibbon(
                canvas, cx, cy, radius * 0.77f,
                -now / 1040.0, 132f, 1.85f,
                palette.white, 52, 0.74f
            )
        }
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
        ribbon.reset()
        val tilt = tiltDegrees * PI / 180.0
        val steps = 92

        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val theta = t * PI * 2.0 * turns + phase
            val pulse = 0.80f + 0.20f * sin(theta * 2.15).toFloat()
            val rx = radius * pulse
            val ry = radius * (0.52f + 0.12f * sin(theta * 1.7).toFloat())

            val rawX = cos(theta).toFloat() * rx
            val rawY = sin(theta).toFloat() * ry

            val x = cx + (
                rawX * cos(tilt) -
                    rawY * sin(tilt)
                ).toFloat()

            val y = cy + (
                rawX * sin(tilt) +
                    rawY * cos(tilt)
                ).toFloat()

            if (i == 0) ribbon.moveTo(x, y) else ribbon.lineTo(x, y)
        }

        glow.shader = null
        glow.color = color
        glow.alpha = (alpha * 0.20f).toInt()
        glow.strokeWidth = dp(widthDp * 5f)
        canvas.drawPath(ribbon, glow)

        stroke.shader = null
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = dp(widthDp)
        canvas.drawPath(ribbon, stroke)
    }

    private fun drawLens(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fill.shader = glass
        fill.alpha = 250
        canvas.drawCircle(
            cx,
            cy,
            radius * (0.58f + breathe * 0.018f),
            fill
        )

        fill.shader = nucleus
        fill.alpha = 255
        canvas.drawCircle(
            cx,
            cy,
            radius * (0.30f + breathe * 0.012f),
            fill
        )
    }

    private fun drawArcs(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float
    ) {
        drawArcSet(
            canvas, cx, cy, radius * 0.90f,
            continuousAngle(now, 12200f - energy * 2200f, false) + 14f,
            listOf(72f to 18f, 48f to 35f, 84f to 28f),
            palette.primary, 238, 2.10f
        )

        drawArcSet(
            canvas, cx, cy, radius * 0.78f,
            continuousAngle(now, 15700f - energy * 2400f, true) + 104f,
            listOf(62f to 26f, 76f to 31f, 42f to 40f),
            palette.accent, 188, 1.48f
        )

        drawArcSet(
            canvas, cx, cy, radius * 0.48f,
            continuousAngle(now, 9800f - energy * 1500f, false) + 238f,
            listOf(54f to 38f, 46f to 49f, 38f to 56f),
            palette.white, 94, 0.82f
        )
    }

    private fun drawArcSet(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        segments: List<Pair<Float, Float>>,
        color: Int,
        alpha: Int,
        widthDp: Float
    ) {
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius)
        var start = phase

        segments.forEach { segment ->
            val sweep = segment.first
            val gap = segment.second

            glow.shader = null
            glow.color = color
            glow.alpha = (alpha * 0.18f).toInt()
            glow.strokeWidth = dp(widthDp * 4.5f)
            canvas.drawArc(arc, start, sweep, false, glow)

            stroke.shader = null
            stroke.color = color
            stroke.alpha = alpha
            stroke.strokeWidth = dp(widthDp)
            canvas.drawArc(arc, start, sweep, false, stroke)

            start += sweep + gap
        }
    }

    private fun drawCenterPulse(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fill.shader = null
        fill.color = palette.white
        fill.alpha = (208 + breathe * 42f).toInt().coerceAtMost(250)
        canvas.drawCircle(
            cx - radius * 0.055f,
            cy - radius * 0.070f,
            dp(2.15f),
            fill
        )

        fill.color = palette.primary
        fill.alpha = 46
        canvas.drawCircle(cx, cy, radius * 0.23f, fill)
    }

    private fun drawStages(
        canvas: Canvas,
        state: String,
        top: Float,
        height: Float
    ) {
        val separatorY = top + dp(1f)

        stroke.shader = null
        stroke.color = Color.parseColor("#28364D")
        stroke.alpha = 178
        stroke.strokeWidth = dp(0.75f)
        canvas.drawLine(
            width * 0.055f,
            separatorY,
            width * 0.945f,
            separatorY,
            stroke
        )

        val labels = arrayOf(
            "Слушаю",
            "Распознаю",
            "Думаю",
            "Выполняю",
            "Проверяю",
            "Отвечаю"
        )

        val glyphs = arrayOf("●", "≋", "◈", "ϟ", "✓", "•••")
        val active = stageIndex(state)
        val left = width * 0.07f
        val right = width * 0.93f
        val step = (right - left) / (labels.size - 1)
        val iconY = top + height * 0.37f
        val labelY = top + height * 0.82f

        stroke.color = Color.parseColor("#2A3449")
        stroke.alpha = 150
        stroke.strokeWidth = dp(0.75f)
        canvas.drawLine(left, iconY, right, iconY, stroke)

        for (i in labels.indices) {
            val x = left + step * i
            val selected = i == active
            val completed = active >= 0 && i < active

            val nodeColor = when {
                selected -> palette.primary
                completed -> withAlpha(palette.secondary, 220)
                else -> Color.parseColor("#51627A")
            }

            if (selected) {
                fill.shader = null
                fill.color = palette.primary
                fill.alpha = 30
                canvas.drawCircle(x, iconY, dp(18f), fill)

                stroke.color = palette.primary
                stroke.alpha = 205
                stroke.strokeWidth = dp(1.1f)
                canvas.drawCircle(x, iconY, dp(15.5f), stroke)
            } else {
                stroke.color = Color.parseColor("#33435B")
                stroke.alpha = 190
                stroke.strokeWidth = dp(0.9f)
                canvas.drawCircle(x, iconY, dp(13.8f), stroke)
            }

            text.color = nodeColor
            text.alpha = 255
            text.textAlign = Paint.Align.CENTER
            text.textSize = dp(if (glyphs[i] == "•••") 10f else 12.5f)
            text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

            val fm = text.fontMetrics
            val baseline = iconY - (fm.ascent + fm.descent) / 2f
            canvas.drawText(glyphs[i], x, baseline, text)

            text.textSize = dp(9.2f)
            text.typeface = Typeface.create(
                Typeface.DEFAULT,
                if (selected) Typeface.BOLD else Typeface.NORMAL
            )
            text.color = if (selected) {
                palette.primary
            } else {
                Color.parseColor("#7F8DA2")
            }
            text.alpha = if (selected) 245 else 220
            canvas.drawText(labels[i], x, labelY, text)
        }
    }

    private fun stageIndex(state: String): Int =
        when (state) {
            AyanaVoiceService.STATE_LISTENING -> 0
            AyanaVoiceService.STATE_COMMAND -> 1
            AyanaVoiceService.STATE_THINKING -> 2
            AyanaVoiceService.STATE_EXECUTING -> 3
            AyanaVoiceService.STATE_SUCCESS -> 4
            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT -> 5
            else -> -1
        }

    private fun rebuildShaders(state: String) {
        if (width <= 0 || height <= 0) return

        shaderW = width
        shaderH = height
        shaderState = state
        palette = paletteFor(state)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * if (h < dp(190f)) 0.50f else 0.40f
        val minSide = min(w, h)

        ambient = RadialGradient(
            cx,
            cy,
            minSide * 0.46f,
            intArrayOf(
                withAlpha(palette.primary, 76),
                withAlpha(palette.secondary, 52),
                withAlpha(palette.accent, 34),
                withAlpha(palette.deep, 18),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 0.48f, 0.74f, 1f),
            Shader.TileMode.CLAMP
        )

        glass = RadialGradient(
            cx - minSide * 0.025f,
            cy - minSide * 0.030f,
            minSide * 0.25f,
            intArrayOf(
                withAlpha(palette.white, 46),
                withAlpha(palette.primary, 54),
                withAlpha(palette.secondary, 42),
                withAlpha(palette.accent, 28),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.25f, 0.50f, 0.74f, 1f),
            Shader.TileMode.CLAMP
        )

        nucleus = RadialGradient(
            cx - minSide * 0.018f,
            cy - minSide * 0.022f,
            minSide * 0.15f,
            intArrayOf(
                Color.WHITE,
                palette.white,
                palette.primary,
                palette.secondary,
                withAlpha(palette.accent, 142),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.10f, 0.28f, 0.51f, 0.74f, 1f),
            Shader.TileMode.CLAMP
        )

        waveGradient = LinearGradient(
            w * 0.05f,
            cy,
            w * 0.95f,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(palette.primary, 122),
                palette.primary,
                palette.white,
                palette.secondary,
                palette.accent,
                withAlpha(palette.accent, 116),
                Color.TRANSPARENT
            ),
            floatArrayOf(
                0f, 0.12f, 0.30f, 0.49f,
                0.63f, 0.80f, 0.90f, 1f
            ),
            Shader.TileMode.CLAMP
        )
    }

    private fun paletteFor(state: String): Palette =
        when (state) {
            AyanaVoiceService.STATE_THINKING ->
                Palette("#55C7FF", "#5572FF", "#A15CFF", "#F8FBFF", "#07101B")

            AyanaVoiceService.STATE_EXECUTING ->
                Palette("#42F0D0", "#2D9EDC", "#6075FF", "#F4FFFC", "#061917")

            AyanaVoiceService.STATE_SUCCESS ->
                Palette("#56E7B8", "#36BFC8", "#67DCA0", "#F5FFF9", "#061812")

            AyanaVoiceService.STATE_ERROR ->
                Palette("#FF6887", "#D84A72", "#F15DA8", "#FFF5F8", "#220710")

            AyanaVoiceService.STATE_CANCELLED ->
                Palette("#FFD066", "#EAA33A", "#FFB46B", "#FFFCEE", "#241707")

            else -> Palette.default()
        }

    private fun stateEnergy(state: String): Float =
        when (state) {
            AyanaVoiceService.STATE_COMMAND -> 0.82f
            AyanaVoiceService.STATE_THINKING -> 0.76f
            AyanaVoiceService.STATE_EXECUTING -> 0.92f
            AyanaVoiceService.STATE_SPEAKING -> 0.84f
            AyanaVoiceService.STATE_LISTENING -> 0.66f
            AyanaVoiceService.STATE_SUCCESS -> 0.44f
            AyanaVoiceService.STATE_ERROR -> 0.54f
            AyanaVoiceService.STATE_CANCELLED -> 0.34f
            else -> 0.32f
        }

    private fun frameDelay(state: String): Long =
        when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_EXECUTING -> 28L

            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_SPEAKING -> 31L

            AyanaVoiceService.STATE_LISTENING -> 34L
            else -> 52L
        }

    private fun continuousAngle(
        now: Long,
        cycleMs: Float,
        reverse: Boolean
    ): Float {
        val safe = max(800f, cycleMs)
        val fraction = (now % safe.toLong()).toFloat() / safe
        val angle = fraction * 360f
        return if (reverse) -angle else angle
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )

    private fun dp(value: Float): Float =
        value * density

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val white: Int,
        val deep: Int
    ) {
        constructor(
            primary: String,
            secondary: String,
            accent: String,
            white: String,
            deep: String
        ) : this(
            Color.parseColor(primary),
            Color.parseColor(secondary),
            Color.parseColor(accent),
            Color.parseColor(white),
            Color.parseColor(deep)
        )

        companion object {
            fun default(): Palette =
                Palette(
                    "#28E4FF",
                    "#238CFF",
                    "#9A56FF",
                    "#F4FDFF",
                    "#06101A"
                )
        }
    }
}
