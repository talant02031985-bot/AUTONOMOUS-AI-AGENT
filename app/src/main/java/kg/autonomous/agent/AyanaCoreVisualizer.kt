package kg.autonomous.agent

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * AYANA Core Visualizer v19.0 â€” REFERENCE MATCH
 *
 * Fixed visual geometry based on the approved six-state AYANA reference:
 * luminous braided circular core + outer particle halo + horizontal waveform +
 * geometric AYANA wordmark.
 *
 * The SHAPE stays the same in all states. Only palette, speed and intensity
 * change. No dashboards, no nodes, no buttons, no extra agent-map graphics.
 *
 * Android 13+ uses AGSL RuntimeShader for a dense volumetric live core.
 * The Galaxy Tab S8 / Android 15 uses this path.
 *
 * No PNG/JPEG dependency. No ORB, routing, TTS, mic or Accessibility changes.
 */
class AyanaCoreVisualizer(
    context: Context
) : View(context) {

    private val density = resources.displayMetrics.density

    private val stroke =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val glow =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

    private val path = Path()

    private var attached = false

    private val shaderBridge: ShaderBridge? =
        if (Build.VERSION.SDK_INT >= 33) {
            Api33ShaderBridge()
        } else {
            null
        }

    init {
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        isLongClickable = false
        setBackgroundColor(Color.TRANSPARENT)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        postInvalidateOnAnimation()
    }

    override fun onDetachedFromWindow() {
        attached = false
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (width <= 0 || height <= 0) return

        val state = stateFor(AyanaVoiceService.currentStatusState)
        val palette = paletteFor(state)
        val motion = motionFor(state)
        val now = SystemClock.uptimeMillis()

        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w * 0.50f
        val cy = h * 0.50f
        val margin = dp(4f)

        val radius =
            min(
                (h * 0.50f - margin) / 1.01f,
                min(
                    (w * 0.50f - margin) / 1.01f,
                    min(h * 0.49f, w * 0.43f)
                )
            ).coerceAtLeast(dp(42f))

        shaderBridge?.draw(
            canvas = canvas,
            width = w,
            height = h,
            timeSeconds = now / 1000f * motion.speed,
            primary = palette.primary,
            secondary = palette.secondary,
            accent = palette.accent,
            energy = motion.energy
        ) ?: drawFallback(canvas, cx, cy, radius, palette)

        drawWaveform(
            canvas = canvas,
            now = now,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette,
            motion = motion
        )

        drawWordmark(
            canvas = canvas,
            cx = cx,
            cy = cy,
            radius = radius,
            palette = palette
        )

        if (attached) {
            postInvalidateDelayed(motion.frameDelayMs)
        }
    }

    /**
     * Same waveform geometry in all states, exactly like the reference.
     */
    private fun drawWaveform(
        canvas: Canvas,
        now: Long,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette,
        motion: Motion
    ) {
        path.reset()

        val left = dp(1f)
        val right = width.toFloat() - dp(1f)
        val samples = 196
        val phase = now / motion.wavePeriodMs

        for (i in 0..samples) {
            val t = i / samples.toFloat()
            val x = left + (right - left) * t

            val distance =
                abs((x - cx) / (width * 0.50f))
                    .coerceIn(0f, 1f)

            val envelope =
                (0.08f + distance * 1.02f)
                    .coerceIn(0.08f, 1f)

            val main =
                sin(t * PI * 8.2 + phase).toFloat()

            val detail =
                sin(t * PI * 33.0 - phase * 0.74).toFloat() * 0.31f

            val fine =
                sin(t * PI * 81.0 + phase * 1.17).toFloat() * 0.105f

            val y =
                cy +
                    (main + detail + fine) *
                    radius *
                    0.185f *
                    envelope *
                    motion.waveStrength

            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
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
                    palette.primary,
                    Color.WHITE,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.23f, 0.50f, 0.77f, 1f),
                Shader.TileMode.CLAMP
            )

        glow.shader = gradient
        glow.alpha = 100
        glow.strokeWidth = dp(16f)
        canvas.drawPath(path, glow)

        glow.alpha = 65
        glow.strokeWidth = dp(8f)
        canvas.drawPath(path, glow)

        stroke.shader = gradient
        stroke.alpha = 245
        stroke.strokeWidth = dp(1.75f)
        canvas.drawPath(path, stroke)

        glow.shader = null
        stroke.shader = null
    }

    /**
     * Geometric Î›YÎ›NÎ› signature. No font or bitmap dependency.
     */
    private fun drawWordmark(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val glyphH = radius * 0.34f
        val widths =
            floatArrayOf(
                glyphH * 0.72f,
                glyphH * 0.72f,
                glyphH * 0.72f,
                glyphH * 0.78f,
                glyphH * 0.72f
            )
        val gap = radius * 0.048f
        val total = widths.sum() + gap * 4f
        val startX = cx - total * 0.50f
        val top = cy - glyphH * 0.50f
        val bottom = cy + glyphH * 0.50f

        glow.color = palette.primary
        glow.alpha = 128
        glow.strokeWidth = dp(14f)
        drawWordmarkGeometry(canvas, startX, top, bottom, widths, gap, glow)

        glow.color = palette.secondary
        glow.alpha = 70
        glow.strokeWidth = dp(8f)
        drawWordmarkGeometry(canvas, startX, top, bottom, widths, gap, glow)

        stroke.color = Color.WHITE
        stroke.alpha = 250
        stroke.strokeWidth = dp(2.20f)
        drawWordmarkGeometry(canvas, startX, top, bottom, widths, gap, stroke)

        stroke.color = palette.primary
        stroke.alpha = 185
        stroke.strokeWidth = dp(0.75f)
        drawWordmarkGeometry(canvas, startX, top, bottom, widths, gap, stroke)
    }

    private fun drawWordmarkGeometry(
        canvas: Canvas,
        startX: Float,
        top: Float,
        bottom: Float,
        widths: FloatArray,
        gap: Float,
        paint: Paint
    ) {
        var x = startX
        drawLambda(canvas, x, top, bottom, widths[0], paint)
        x += widths[0] + gap
        drawY(canvas, x, top, bottom, widths[1], paint)
        x += widths[1] + gap
        drawLambda(canvas, x, top, bottom, widths[2], paint)
        x += widths[2] + gap
        drawN(canvas, x, top, bottom, widths[3], paint)
        x += widths[3] + gap
        drawLambda(canvas, x, top, bottom, widths[4], paint)
    }

    private fun drawLambda(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        val center = left + width * 0.50f
        canvas.drawLine(left, bottom, center, top, paint)
        canvas.drawLine(center, top, left + width, bottom, paint)
    }

    private fun drawY(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        val center = left + width * 0.50f
        val fork = top + (bottom - top) * 0.45f
        canvas.drawLine(left, top, center, fork, paint)
        canvas.drawLine(left + width, top, center, fork, paint)
        canvas.drawLine(center, fork, center, bottom, paint)
    }

    private fun drawN(
        canvas: Canvas,
        left: Float,
        top: Float,
        bottom: Float,
        width: Float,
        paint: Paint
    ) {
        canvas.drawLine(left, bottom, left, top, paint)
        canvas.drawLine(left, top, left + width, bottom, paint)
        canvas.drawLine(left + width, bottom, left + width, top, paint)
    }

    private fun drawFallback(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        palette: Palette
    ) {
        val fallback = Paint(Paint.ANTI_ALIAS_FLAG)
        fallback.shader =
            android.graphics.RadialGradient(
                cx,
                cy,
                radius * 0.88f,
                intArrayOf(
                    Color.WHITE,
                    palette.primary,
                    palette.secondary,
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.22f, 0.60f, 1f),
                Shader.TileMode.CLAMP
            )
        fallback.alpha = 245
        canvas.drawCircle(cx, cy, radius * 0.88f, fallback)
    }

    private fun stateFor(state: String): AgentState {
        return when(state) {
            AyanaVoiceService.STATE_LISTENING -> AgentState.WAITING
            AyanaVoiceService.STATE_COMMAND -> AgentState.RECOGNITION
            AyanaVoiceService.STATE_THINKING -> AgentState.THINKING
            AyanaVoiceService.STATE_EXECUTING -> AgentState.EXECUTING
            AyanaVoiceService.STATE_SPEAKING,
            AyanaVoiceService.STATE_TEXT,
            AyanaVoiceService.STATE_SUCCESS -> AgentState.ANSWERING
            AyanaVoiceService.STATE_CANCELLED,
            AyanaVoiceService.STATE_ERROR -> AgentState.STOP
            else -> AgentState.WAITING
        }
    }

    private fun paletteFor(state: AgentState): Palette {
        return when(state) {
            AgentState.WAITING ->
                Palette(
                    Color.parseColor("#10E8ED"),
                    Color.parseColor("#00C8FF"),
                    Color.parseColor("#B9FFFF")
                )
            AgentState.RECOGNITION ->
                Palette(
                    Color.parseColor("#167FFF"),
                    Color.parseColor("#00C8FF"),
                    Color.parseColor("#B4E7FF")
                )
            AgentState.THINKING ->
                Palette(
                    Color.parseColor("#6748FF"),
                    Color.parseColor("#9B48FF"),
                    Color.parseColor("#D8C6FF")
                )
            AgentState.EXECUTING ->
                Palette(
                    Color.parseColor("#20E16C"),
                    Color.parseColor("#00D0A5"),
                    Color.parseColor("#A6FFD4")
                )
            AgentState.ANSWERING ->
                Palette(
                    Color.parseColor("#F32BC4"),
                    Color.parseColor("#AE47FF"),
                    Color.parseColor("#FFB6EB")
                )
            AgentState.STOP ->
                Palette(
                    Color.parseColor("#FF3A22"),
                    Color.parseColor("#FF7817"),
                    Color.parseColor("#FFAF87")
                )
        }
    }

    private fun motionFor(state: AgentState): Motion {
        return when(state) {
            AgentState.WAITING -> Motion(38L, 0.70f, 760.0, 0.88f, 0.90f)
            AgentState.RECOGNITION -> Motion(28L, 1.08f, 410.0, 1.03f, 1.04f)
            AgentState.THINKING -> Motion(27L, 1.18f, 470.0, 1.08f, 1.00f)
            AgentState.EXECUTING -> Motion(26L, 1.22f, 350.0, 1.10f, 1.07f)
            AgentState.ANSWERING -> Motion(27L, 1.14f, 370.0, 1.06f, 1.06f)
            AgentState.STOP -> Motion(82L, 0.18f, 1200.0, 0.68f, 0.52f)
        }
    }

    private fun dp(value: Float): Float = value * density

    private interface ShaderBridge {
        fun draw(
            canvas: Canvas,
            width: Float,
            height: Float,
            timeSeconds: Float,
            primary: Int,
            secondary: Int,
            accent: Int,
            energy: Float
        )
    }

    private class Api33ShaderBridge : ShaderBridge {

        private val shader = RuntimeShader(AGSL_SOURCE)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(
            canvas: Canvas,
            width: Float,
            height: Float,
            timeSeconds: Float,
            primary: Int,
            secondary: Int,
            accent: Int,
            energy: Float
        ) {
            shader.setFloatUniform("uResolution", width, height)
            shader.setFloatUniform("uTime", timeSeconds)
            shader.setFloatUniform("uEnergy", energy)
            shader.setFloatUniform(
                "uPrimary",
                Color.red(primary) / 255f,
                Color.green(primary) / 255f,
                Color.blue(primary) / 255f
            )
            shader.setFloatUniform(
                "uSecondary",
                Color.red(secondary) / 255f,
                Color.green(secondary) / 255f,
                Color.blue(secondary) / 255f
            )
            shader.setFloatUniform(
                "uAccent",
                Color.red(accent) / 255f,
                Color.green(accent) / 255f,
                Color.blue(accent) / 255f
            )

            paint.shader = shader
            canvas.drawRect(0f, 0f, width, height, paint)
            paint.shader = null
        }
    }

    private enum class AgentState {
        WAITING,
        RECOGNITION,
        THINKING,
        EXECUTING,
        ANSWERING,
        STOP
    }

    private data class Palette(
        val primary: Int,
        val secondary: Int,
        val accent: Int
    )

    private data class Motion(
        val frameDelayMs: Long,
        val speed: Float,
        val wavePeriodMs: Double,
        val energy: Float,
        val waveStrength: Float
    )

    companion object {

        /**
         * ONE fixed geometry for all six states.
         * Palette / speed / energy are the only state-dependent shader inputs.
         */
        private const val AGSL_SOURCE = """
            uniform float2 uResolution;
            uniform float uTime;
            uniform float uEnergy;
            uniform float3 uPrimary;
            uniform float3 uSecondary;
            uniform float3 uAccent;

            float hash21(float2 p) {
                p = fract(p * float2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            float ringGlow(float r, float target, float width) {
                return exp(-abs(r - target) / width);
            }

            float field(float2 p, float t) {
                float r = length(p);
                float a = atan(p.y, p.x);
                float v = 0.0;
                v += sin(a * 6.0 + t * 1.25 + sin(r * 13.0 - t));
                v += sin(a * 9.0 - t * 1.10 + cos(r * 18.0 + t * 0.8));
                v += sin(a * 13.0 + t * 0.92 + sin(r * 24.0 - t * 0.9));
                v += sin((p.x + p.y) * 18.0 + t * 1.20);
                v += sin((p.x - p.y) * 22.0 - t * 1.00);
                return v / 5.0;
            }

            half4 main(float2 fragCoord) {
                float2 p =
                    (fragCoord - 0.5 * uResolution) /
                    min(uResolution.x, uResolution.y);

                float r = length(p);
                float a = atan(p.y, p.x);
                float t = uTime;

                float3 color = float3(0.0);

                // Dense luminous center.
                float hot = exp(-r * r * 55.0);
                float core = exp(-r * r * 15.0);
                float f = 0.5 + 0.5 * field(p * 1.45, t);
                f = smoothstep(0.08, 0.92, f);
                float mask = 1.0 - smoothstep(0.15, 0.50, r);

                color += uPrimary * core * 1.60 * uEnergy;
                color += uSecondary * f * mask * 0.95 * uEnergy;
                color += uAccent * pow(f, 3.2) * mask * 0.62 * uEnergy;
                color += float3(1.0) * hot * 1.75 * uEnergy;

                // Smooth braided luminous filaments.
                for (int i = 0; i < 24; i++) {
                    float fi = float(i);
                    float direction = mod(fi, 2.0) < 1.0 ? 1.0 : -0.86;
                    float phase = t * (0.42 + fi * 0.017) * direction + fi * 0.61;

                    float target =
                        0.245 +
                        0.055 * sin(a * (2.0 + mod(fi, 5.0)) + phase) +
                        0.028 * sin(a * (4.0 + mod(fi, 3.0)) - phase * 0.72);

                    float d = abs(r - target);
                    float ribbon = exp(-d * (250.0 + mod(fi, 4.0) * 38.0));
                    float pulse = 0.58 + 0.42 * sin(a * (3.0 + mod(fi, 4.0)) + phase * 1.55);

                    float3 rc =
                        mod(fi, 3.0) < 1.0
                            ? uPrimary
                            : (mod(fi, 3.0) < 2.0 ? uSecondary : uAccent);

                    color += rc * ribbon * (0.48 + 0.38 * pulse) * uEnergy;
                }

                // Concentric luminous structure.
                for (int k = 0; k < 6; k++) {
                    float fk = float(k);
                    float rr = 0.14 + fk * 0.050;
                    float rg = ringGlow(r, rr, 0.0045 + fk * 0.00025);
                    float3 rc = mod(fk, 2.0) < 1.0 ? uPrimary : uSecondary;
                    color += rc * rg * (0.16 + 0.08 * sin(t * 0.6 + fk));
                }

                // Outer luminous ring.
                color += uPrimary * ringGlow(r, 0.39, 0.012) * 0.32;
                color += uSecondary * ringGlow(r, 0.425, 0.006) * 0.20;

                // Particle halo around the outer ring.
                float2 cell = floor(p * 48.0);
                float n = hash21(cell);
                float band = 1.0 - smoothstep(0.05, 0.18, abs(r - 0.43));
                float spark = step(0.91, n) * band *
                    (0.30 + 0.70 * abs(sin(t * 2.1 + n * 13.0)));

                color += mix(uPrimary, float3(1.0), n) * spark * 1.20 * uEnergy;

                float fade = 1.0 - smoothstep(0.50, 0.66, r);
                color *= 0.84 + 0.16 * fade;

                float alpha = clamp(max(max(color.r, color.g), color.b), 0.0, 1.0);
                return half4(half3(color), half(alpha));
            }
        """
    }
}
