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
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v2.3 — REFERENCE ENERGY ORB.
 *
 * Visual-only replacement for the main AYANA visualization window.
 *
 * Target appearance:
 * - large luminous circular energy body like the approved reference;
 * - dense curved energy fibres instead of a simple decorative sphere;
 * - horizontal reactive signal passing through the centre;
 * - AYANA wordmark inside the core;
 * - state-specific cyan / blue / violet / green / magenta / red palettes;
 * - every glow, fibre, flare and waveform remains inside this View.
 *
 * Integration contract:
 * - package/class/constructor are unchanged;
 * - no MainActivity changes;
 * - no AyanaVoiceService changes;
 * - no global ORB changes;
 * - no permissions or external libraries.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int,
        val white: Int,
        val deep: Int
    )

    private val density = resources.displayMetrics.density

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }

    private val signalPath = Path()
    private val fineSignalPath = Path()
    private val arcBounds = RectF()

    private var attached = false
    private var shaderWidth = -1
    private var shaderHeight = -1
    private var shaderState = ""

    private var ambientShader: RadialGradient? = null
    private var coreShader: RadialGradient? = null
    private var centreShader: RadialGradient? = null
    private var signalShader: LinearGradient? = null

    private var palette = defaultPalette()

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        rebuildShaders(AyanaVoiceService.currentStatusState)
        postInvalidateDelayed(frameDelayMs(AyanaVoiceService.currentStatusState))
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
        super.onSizeChanged(w, h, oldw, oldh)
        shaderWidth = -1
        shaderHeight = -1
        rebuildShaders(AyanaVoiceService.currentStatusState)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val state = AyanaVoiceService.currentStatusState
        if (state != shaderState || width != shaderWidth || height != shaderHeight) {
            rebuildShaders(state)
        }

        val now = SystemClock.uptimeMillis()
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * 0.50f
        val compact = h < dp(180f)

        // The reference requires a large orb, but no element may cross the card.
        // 1.16R is the maximum visual reach (ambient halo / outer sparks).
        val edgeInset = dp(if (compact) 8f else 12f)
        val safeHalfW = (w * 0.50f - edgeInset).coerceAtLeast(dp(16f))
        val safeHalfH = (h * 0.50f - edgeInset).coerceAtLeast(dp(16f))
        val containedRadius = min(safeHalfW, safeHalfH) / 1.16f
        val preferredRadius = h * if (compact) 0.385f else 0.405f
        val radius = min(preferredRadius, containedRadius).coerceAtLeast(dp(20f))

        val energy = stateEnergy(state)
        val phase = now / 1000.0
        val breathe = (0.5 + 0.5 * sin(phase * (0.92 + energy * 0.28))).toFloat()

        drawAmbient(canvas, cx, cy, radius, breathe)
        drawSignal(canvas, now, cx, cy, radius, energy, compact)
        drawEnergyShell(canvas, now, cx, cy, radius, energy, compact)
        drawInnerCore(canvas, cx, cy, radius, breathe)
        drawOrbitalArcs(canvas, now, cx, cy, radius, energy, compact)
        drawAyanaLabel(canvas, cx, cy, radius, compact)
        drawFlares(canvas, now, cx, cy, radius, energy, compact)

        if (attached) {
            postInvalidateDelayed(frameDelayMs(state))
        }
    }

    private fun drawAmbient(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fillPaint.shader = ambientShader
        fillPaint.alpha = (205 + breathe * 32f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, radius * 1.16f, fillPaint)
        fillPaint.shader = null
    }

    private fun drawSignal(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val left = width * 0.035f
        val right = width * 0.965f
        val span = right - left
        val samples = if (compact) 112 else 164
        val maxAmp = min(height * 0.20f, radius * 0.52f)
        val tPhase = now / (455.0 - energy * 105.0)

        signalPath.reset()
        fineSignalPath.reset()

        for (i in 0..samples) {
            val u = i.toFloat() / samples.toFloat()
            val x = left + span * u
            val centred = 1f - abs(u - 0.5f) * 2f
            val centreEnvelope = centred.coerceIn(0f, 1f)
            val envelope = 0.14f + 0.86f * centreEnvelope * centreEnvelope

            val harmonic =
                sin(u * PI * 19.0 + tPhase) * 0.48 +
                    sin(u * PI * 41.0 - tPhase * 1.35) * 0.27 +
                    sin(u * PI * 73.0 + tPhase * 0.72) * 0.16

            val needle =
                sin(u * PI * 113.0 - tPhase * 1.85) *
                    (0.10 + 0.18 * centreEnvelope)

            val y = cy +
                (harmonic + needle).toFloat() *
                maxAmp * envelope * (0.52f + energy * 0.48f)

            if (i == 0) signalPath.moveTo(x, y) else signalPath.lineTo(x, y)

            val fine =
                sin(u * PI * 57.0 + tPhase * 1.42) * 0.36 +
                    sin(u * PI * 97.0 - tPhase * 0.66) * 0.16
            val fy = cy + fine.toFloat() * maxAmp * envelope * 0.62f
            if (i == 0) fineSignalPath.moveTo(x, fy) else fineSignalPath.lineTo(x, fy)
        }

        glowPaint.shader = signalShader
        glowPaint.alpha = 54
        glowPaint.strokeWidth = dp(if (compact) 7.2f else 9.0f)
        canvas.drawPath(signalPath, glowPaint)

        strokePaint.shader = signalShader
        strokePaint.alpha = 232
        strokePaint.strokeWidth = dp(if (compact) 1.28f else 1.56f)
        canvas.drawPath(signalPath, strokePaint)

        strokePaint.alpha = 88
        strokePaint.strokeWidth = dp(if (compact) 0.62f else 0.78f)
        canvas.drawPath(fineSignalPath, strokePaint)

        // Thin baseline exactly like the reference panel.
        strokePaint.shader = signalShader
        strokePaint.alpha = 86
        strokePaint.strokeWidth = dp(0.55f)
        canvas.drawLine(left, cy, right, cy, strokePaint)

        strokePaint.shader = null
        glowPaint.shader = null

        // A small travelling bright sample keeps the line alive.
        val travel = ((now % 3600L).toFloat() / 3600f)
        val tx = left + span * travel
        pointPaint.color = palette.white
        pointPaint.alpha = 190
        canvas.drawCircle(tx, cy, dp(1.15f), pointPaint)
    }

    private fun drawEnergyShell(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val phase = now / (1550.0 - energy * 280.0)

        // Broad transparent shell underneath the fibres.
        fillPaint.shader = coreShader
        fillPaint.alpha = 228
        canvas.drawCircle(cx, cy, radius * (0.985f + 0.012f * sin(phase).toFloat()), fillPaint)
        fillPaint.shader = null

        // Dense woven fibres. End points are bounded to <= 1.08R.
        val count = if (compact) 104 else 148
        val golden = 2.399963229728653 // golden angle in radians

        for (i in 0 until count) {
            val fi = i.toFloat()
            val angle = phase * (0.17 + (i % 5) * 0.013) + i * golden
            val waveA = sin(fi * 1.713 + phase * 0.74).toFloat()
            val waveB = cos(fi * 0.923 - phase * 0.59).toFloat()
            val innerR = radius * (0.54f + 0.14f * ((waveA + 1f) * 0.5f))
            val outerR = radius * (0.88f + 0.16f * ((waveB + 1f) * 0.5f))
            val twist = 0.30 + 0.20 * sin(fi * 0.51 + phase * 0.42)

            val x1 = cx + cos(angle).toFloat() * innerR
            val y1 = cy + sin(angle).toFloat() * innerR
            val x2 = cx + cos(angle + twist).toFloat() * outerR
            val y2 = cy + sin(angle + twist).toFloat() * outerR

            strokePaint.shader = null
            strokePaint.color = when (i % 6) {
                0, 1, 2 -> palette.primary
                3, 4 -> palette.secondary
                else -> palette.accent
            }
            strokePaint.alpha = (54 + ((waveA + 1f) * 0.5f) * 92f).toInt().coerceIn(0, 180)
            strokePaint.strokeWidth = dp(if (compact) 0.58f else 0.76f)
            canvas.drawLine(x1, y1, x2, y2, strokePaint)

            // Short luminous tip, producing the fuzzy filament halo seen in the reference.
            if (i % 3 == 0) {
                val tipR = min(radius * 1.08f, outerR + radius * 0.055f)
                val tx = cx + cos(angle + twist + 0.045).toFloat() * tipR
                val ty = cy + sin(angle + twist + 0.045).toFloat() * tipR
                strokePaint.alpha = 54
                strokePaint.strokeWidth = dp(if (compact) 0.44f else 0.58f)
                canvas.drawLine(x2, y2, tx, ty, strokePaint)
            }
        }

        // Three soft elliptical sweeps make the body read as a spherical vortex.
        drawSweep(canvas, cx, cy, radius * 0.92f, (phase * 31.0).toFloat(), palette.primary, 116, compact)
        drawSweep(canvas, cx, cy, radius * 0.78f, (-phase * 27.0 + 118.0).toFloat(), palette.secondary, 98, compact)
        drawSweep(canvas, cx, cy, radius * 0.66f, (phase * 23.0 + 236.0).toFloat(), palette.accent, 84, compact)
    }

    private fun drawSweep(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phaseDeg: Float,
        color: Int,
        alpha: Int,
        compact: Boolean
    ) {
        arcBounds.set(
            cx - radius,
            cy - radius * 0.72f,
            cx + radius,
            cy + radius * 0.72f
        )

        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha = alpha / 4
        glowPaint.strokeWidth = dp(if (compact) 5.6f else 7.2f)
        canvas.drawArc(arcBounds, phaseDeg, 128f, false, glowPaint)

        strokePaint.shader = null
        strokePaint.color = color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = dp(if (compact) 0.78f else 1.02f)
        canvas.drawArc(arcBounds, phaseDeg, 128f, false, strokePaint)
    }

    private fun drawInnerCore(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        breathe: Float
    ) {
        fillPaint.shader = centreShader
        fillPaint.alpha = 244
        canvas.drawCircle(cx, cy, radius * (0.48f + breathe * 0.012f), fillPaint)
        fillPaint.shader = null

        // Crisp luminous centre rim around the text cavity.
        strokePaint.shader = null
        strokePaint.color = palette.primary
        strokePaint.alpha = 78
        strokePaint.strokeWidth = dp(0.72f)
        canvas.drawCircle(cx, cy, radius * 0.50f, strokePaint)
    }

    private fun drawOrbitalArcs(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val a = continuousAngle(now, 12800f - energy * 1800f, false)
        val b = continuousAngle(now, 16600f - energy * 2100f, true)
        val c = continuousAngle(now, 10800f - energy * 1300f, false)

        drawBrokenArc(canvas, cx, cy, radius * 1.03f, a + 16f, 102f, 31f, palette.primary, 190, compact)
        drawBrokenArc(canvas, cx, cy, radius * 0.89f, b + 124f, 82f, 39f, palette.secondary, 156, compact)
        drawBrokenArc(canvas, cx, cy, radius * 0.73f, c + 238f, 68f, 47f, palette.accent, 128, compact)
    }

    private fun drawBrokenArc(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        phase: Float,
        sweep: Float,
        gap: Float,
        color: Int,
        alpha: Int,
        compact: Boolean
    ) {
        arcBounds.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val secondSweep = (360f - sweep - gap * 2f) * 0.46f

        glowPaint.shader = null
        glowPaint.color = color
        glowPaint.alpha = alpha / 5
        glowPaint.strokeWidth = dp(if (compact) 5.2f else 6.8f)
        canvas.drawArc(arcBounds, phase, sweep, false, glowPaint)
        canvas.drawArc(arcBounds, phase + sweep + gap, secondSweep, false, glowPaint)

        strokePaint.shader = null
        strokePaint.color = color
        strokePaint.alpha = alpha
        strokePaint.strokeWidth = dp(if (compact) 1.18f else 1.48f)
        canvas.drawArc(arcBounds, phase, sweep, false, strokePaint)
        canvas.drawArc(arcBounds, phase + sweep + gap, secondSweep, false, strokePaint)
    }

    private fun drawAyanaLabel(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        compact: Boolean
    ) {
        val textSize = min(
            radius * if (compact) 0.34f else 0.30f,
            dp(if (compact) 17f else 24f)
        )
        textPaint.textSize = textSize
        textPaint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        val baseline = cy - (textPaint.ascent() + textPaint.descent()) * 0.5f

        textPaint.color = palette.primary
        textPaint.alpha = 54
        textPaint.textSize = textSize * 1.08f
        canvas.drawText("AYANA", cx, baseline, textPaint)

        textPaint.color = palette.white
        textPaint.alpha = 246
        textPaint.textSize = textSize
        canvas.drawText("AYANA", cx, baseline, textPaint)
    }

    private fun drawFlares(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        energy: Float,
        compact: Boolean
    ) {
        val phase = now / (1450.0 - energy * 240.0)
        val count = if (compact) 12 else 16

        for (i in 0 until count) {
            val a = phase * 0.13 + i * (PI * 2.0 / count)
            val pulse = (0.5 + 0.5 * sin(phase + i * 1.37)).toFloat()
            val rr = radius * (1.035f + 0.075f * pulse)
            val x = cx + cos(a).toFloat() * rr
            val y = cy + sin(a).toFloat() * rr

            pointPaint.color = when (i % 4) {
                0 -> palette.white
                1, 2 -> palette.primary
                else -> palette.accent
            }
            pointPaint.alpha = (54 + pulse * 128f).toInt().coerceIn(0, 210)
            canvas.drawCircle(x, y, dp(0.62f + pulse * 0.62f), pointPaint)
        }

        // Three fixed star-like accents, all under the 1.16R containment budget.
        drawFlare(canvas, cx - radius * 0.56f, cy - radius * 0.57f, palette.white, compact)
        drawFlare(canvas, cx + radius * 0.58f, cy - radius * 0.62f, palette.accent, compact)
        drawFlare(canvas, cx + radius * 0.67f, cy + radius * 0.42f, palette.primary, compact)
    }

    private fun drawFlare(
        canvas: Canvas,
        x: Float,
        y: Float,
        color: Int,
        compact: Boolean
    ) {
        strokePaint.shader = null
        strokePaint.color = color
        strokePaint.alpha = 116
        strokePaint.strokeWidth = dp(0.56f)
        val arm = dp(if (compact) 4.2f else 5.8f)
        canvas.drawLine(x - arm, y, x + arm, y, strokePaint)
        canvas.drawLine(x, y - arm, x, y + arm, strokePaint)

        pointPaint.color = color
        pointPaint.alpha = 230
        canvas.drawCircle(x, y, dp(0.85f), pointPaint)
    }

    private fun rebuildShaders(state: String) {
        if (width <= 0 || height <= 0) return

        shaderWidth = width
        shaderHeight = height
        shaderState = state
        palette = paletteFor(state)

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * 0.50f
        val minSide = min(w, h)

        ambientShader = RadialGradient(
            cx,
            cy,
            minSide * 0.49f,
            intArrayOf(
                withAlpha(palette.primary, 70),
                withAlpha(palette.secondary, 52),
                withAlpha(palette.accent, 28),
                withAlpha(palette.deep, 8),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.34f, 0.60f, 0.82f, 1f),
            Shader.TileMode.CLAMP
        )

        coreShader = RadialGradient(
            cx - minSide * 0.025f,
            cy - minSide * 0.035f,
            minSide * 0.37f,
            intArrayOf(
                withAlpha(palette.white, 64),
                withAlpha(palette.primary, 82),
                withAlpha(palette.secondary, 58),
                withAlpha(palette.accent, 35),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.24f, 0.51f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )

        centreShader = RadialGradient(
            cx - minSide * 0.012f,
            cy - minSide * 0.018f,
            minSide * 0.20f,
            intArrayOf(
                withAlpha(palette.white, 105),
                withAlpha(palette.primary, 104),
                withAlpha(palette.secondary, 82),
                withAlpha(palette.deep, 194),
                withAlpha(palette.deep, 236)
            ),
            floatArrayOf(0f, 0.18f, 0.44f, 0.73f, 1f),
            Shader.TileMode.CLAMP
        )

        signalShader = LinearGradient(
            w * 0.03f,
            cy,
            w * 0.97f,
            cy,
            intArrayOf(
                Color.TRANSPARENT,
                withAlpha(palette.primary, 178),
                palette.primary,
                palette.white,
                palette.secondary,
                withAlpha(palette.accent, 190),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.10f, 0.31f, 0.50f, 0.69f, 0.90f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun paletteFor(state: String): Palette {
        return when (state) {
            // RECOGNIZING in the visual reference.
            AyanaVoiceService.STATE_COMMAND -> Palette(
                primary = Color.parseColor("#73C8FF"),
                secondary = Color.parseColor("#6688FF"),
                accent = Color.parseColor("#A5D9FF"),
                white = Color.parseColor("#F9FDFF"),
                deep = Color.parseColor("#07101D")
            )

            // THINKING — violet / indigo.
            AyanaVoiceService.STATE_THINKING -> Palette(
                primary = Color.parseColor("#A886FF"),
                secondary = Color.parseColor("#766BFF"),
                accent = Color.parseColor("#D3B4FF"),
                white = Color.parseColor("#FCFAFF"),
                deep = Color.parseColor("#100B25")
            )

            // EXECUTING — mint / cyan.
            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SUCCESS -> Palette(
                primary = Color.parseColor("#72FFD7"),
                secondary = Color.parseColor("#45D7C6"),
                accent = Color.parseColor("#9DFFE7"),
                white = Color.parseColor("#F7FFFC"),
                deep = Color.parseColor("#061B18")
            )

            // RESPONDING — magenta / violet.
            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT -> Palette(
                primary = Color.parseColor("#FF73E9"),
                secondary = Color.parseColor("#C667FF"),
                accent = Color.parseColor("#FFA9F0"),
                white = Color.parseColor("#FFF8FE"),
                deep = Color.parseColor("#1C0821")
            )

            // STOPPED / ERROR — red with a warm orange edge.
            AyanaVoiceService.STATE_ERROR,
            AyanaVoiceService.STATE_BLOCKED,
            AyanaVoiceService.STATE_STOPPED -> Palette(
                primary = Color.parseColor("#FF6E5E"),
                secondary = Color.parseColor("#E84A62"),
                accent = Color.parseColor("#FF9A52"),
                white = Color.parseColor("#FFF8F4"),
                deep = Color.parseColor("#230A0A")
            )

            AyanaVoiceService.STATE_CANCELLED -> Palette(
                primary = Color.parseColor("#FFB85A"),
                secondary = Color.parseColor("#F07854"),
                accent = Color.parseColor("#FFD07A"),
                white = Color.parseColor("#FFF9EE"),
                deep = Color.parseColor("#241307")
            )

            // LISTENING — cyan / turquoise like the top-left reference.
            else -> defaultPalette()
        }
    }

    private fun defaultPalette(): Palette = Palette(
        primary = Color.parseColor("#79F4EE"),
        secondary = Color.parseColor("#55C9E9"),
        accent = Color.parseColor("#A1FFF6"),
        white = Color.parseColor("#F7FFFF"),
        deep = Color.parseColor("#06171B")
    )

    private fun stateEnergy(state: String): Float {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND -> 0.72f
            AyanaVoiceService.STATE_THINKING -> 0.78f
            AyanaVoiceService.STATE_EXECUTING -> 0.92f
            AyanaVoiceService.STATE_SPEAKING -> 0.84f
            AyanaVoiceService.STATE_TEXT -> 0.68f
            AyanaVoiceService.STATE_LISTENING -> 0.60f
            AyanaVoiceService.STATE_SUCCESS -> 0.48f
            AyanaVoiceService.STATE_ERROR -> 0.56f
            AyanaVoiceService.STATE_BLOCKED -> 0.46f
            AyanaVoiceService.STATE_CANCELLED -> 0.36f
            AyanaVoiceService.STATE_STOPPED -> 0.28f
            else -> 0.52f
        }
    }

    private fun continuousAngle(
        now: Long,
        cycleMs: Float,
        reverse: Boolean
    ): Float {
        val safeCycle = cycleMs.coerceAtLeast(800f)
        val fraction = (now % safeCycle.toLong()).toFloat() / safeCycle
        val angle = fraction * 360f
        return if (reverse) -angle else angle
    }

    private fun frameDelayMs(state: String): Long {
        return when (state) {
            AyanaVoiceService.STATE_COMMAND,
            AyanaVoiceService.STATE_THINKING,
            AyanaVoiceService.STATE_EXECUTING,
            AyanaVoiceService.STATE_SPEAKING -> 30L

            AyanaVoiceService.STATE_LISTENING -> 34L
            else -> 42L
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return Color.argb(
            alpha.coerceIn(0, 255),
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun dp(value: Float): Float = value * density
}
