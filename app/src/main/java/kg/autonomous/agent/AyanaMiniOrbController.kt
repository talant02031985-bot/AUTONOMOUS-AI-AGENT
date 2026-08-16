package kg.autonomous.agent

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs

class AyanaMiniOrbController(
    context: Context
) {

    private val appContext =
        context.applicationContext

    private val windowManager =
        appContext.getSystemService(
            WindowManager::class.java
        )

    private var orbView:
        TextView? = null

    private var layoutParams:
        WindowManager.LayoutParams? = null

    fun refresh(
        enabled: Boolean,
        state: String
    ) {

        if (
            !enabled ||
            !canDrawOverlays()
        ) {

            hide()
            return
        }

        if (
            orbView == null
        ) {

            show(
                state
            )

        } else {

            updateState(
                state
            )
        }
    }

    fun updateState(
        state: String
    ) {

        val view =
            orbView
                ?: return

        view.background =
            orbDrawable(
                state
            )
    }

    fun hide() {

        val view =
            orbView
                ?: return

        try {

            windowManager
                .removeView(
                    view
                )

        } catch (_: Exception) {
        }

        orbView =
            null

        layoutParams =
            null
    }

    fun canDrawOverlays():
        Boolean {

        return if (
            Build.VERSION.SDK_INT >= 23
        ) {

            Settings
                .canDrawOverlays(
                    appContext
                )

        } else {

            true
        }
    }

    private fun show(
        state: String
    ) {

        if (
            !canDrawOverlays()
        ) {
            return
        }

        val size =
            dp(
                68
            )

        val params =
            WindowManager
                .LayoutParams(
                    size,
                    size,
                    if (
                        Build.VERSION.SDK_INT >= 26
                    ) {
                        WindowManager
                            .LayoutParams
                            .TYPE_APPLICATION_OVERLAY
                    } else {
                        @Suppress("DEPRECATION")
                        WindowManager
                            .LayoutParams
                            .TYPE_PHONE
                    },
                    WindowManager
                        .LayoutParams
                        .FLAG_NOT_FOCUSABLE or
                        WindowManager
                            .LayoutParams
                            .FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {

                    gravity =
                        Gravity.TOP or
                            Gravity.START

                    x =
                        (
                            appContext
                                .resources
                                .displayMetrics
                                .widthPixels -
                                size -
                                dp(24)
                            )
                            .coerceAtLeast(
                                0
                            )

                    y =
                        dp(
                            190
                        )
                }

        val view =
            TextView(
                appContext
            ).apply {

                text =
                    "A"

                textSize =
                    28f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                setTypeface(
                    typeface,
                    android.graphics
                        .Typeface.BOLD
                )

                elevation =
                    dp(12)
                        .toFloat()

                background =
                    orbDrawable(
                        state
                    )

                contentDescription =
                    "AYANA mini orb"
            }

        attachTouchBehavior(
            view,
            params
        )

        try {

            windowManager
                .addView(
                    view,
                    params
                )

            orbView =
                view

            layoutParams =
                params

        } catch (_: Exception) {

            orbView =
                null

            layoutParams =
                null
        }
    }

    private fun attachTouchBehavior(
        view: View,
        params: WindowManager.LayoutParams
    ) {

        var downRawX =
            0f

        var downRawY =
            0f

        var startX =
            0

        var startY =
            0

        var moved =
            false

        view.setOnTouchListener {
            _,
            event ->

            when (
                event.actionMasked
            ) {

                MotionEvent.ACTION_DOWN -> {

                    downRawX =
                        event.rawX

                    downRawY =
                        event.rawY

                    startX =
                        params.x

                    startY =
                        params.y

                    moved =
                        false

                    true
                }

                MotionEvent.ACTION_MOVE -> {

                    val dx =
                        event.rawX -
                            downRawX

                    val dy =
                        event.rawY -
                            downRawY

                    if (
                        abs(dx) >
                            dp(5) ||
                        abs(dy) >
                            dp(5)
                    ) {

                        moved =
                            true
                    }

                    params.x =
                        (
                            startX +
                                dx
                            )
                            .toInt()
                            .coerceAtLeast(
                                0
                            )

                    params.y =
                        (
                            startY +
                                dy
                            )
                            .toInt()
                            .coerceAtLeast(
                                0
                            )

                    try {

                        windowManager
                            .updateViewLayout(
                                view,
                                params
                            )

                    } catch (_: Exception) {
                    }

                    true
                }

                MotionEvent.ACTION_UP -> {

                    if (
                        !moved
                    ) {

                        openAyana()
                    }

                    true
                }

                else ->
                    false
            }
        }
    }

    private fun openAyana() {

        val intent =
            Intent(
                appContext,
                MainActivity::class.java
            ).apply {

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
            }

        try {

            appContext
                .startActivity(
                    intent
                )

        } catch (_: Exception) {
        }
    }

    private fun orbDrawable(
        state: String
    ): GradientDrawable {

        val colors =
            when (
                state
            ) {

                AyanaVoiceService
                    .STATE_LISTENING ->
                    intArrayOf(
                        Color.parseColor(
                            "#1D4ED8"
                        ),
                        Color.parseColor(
                            "#06B6D4"
                        )
                    )

                AyanaVoiceService
                    .STATE_THINKING ->
                    intArrayOf(
                        Color.parseColor(
                            "#4C1D95"
                        ),
                        Color.parseColor(
                            "#8B5CF6"
                        )
                    )

                AyanaVoiceService
                    .STATE_ERROR ->
                    intArrayOf(
                        Color.parseColor(
                            "#7F1D1D"
                        ),
                        Color.parseColor(
                            "#EF4444"
                        )
                    )

                AyanaVoiceService
                    .STATE_STOPPED ->
                    intArrayOf(
                        Color.parseColor(
                            "#1F2937"
                        ),
                        Color.parseColor(
                            "#475569"
                        )
                    )

                else ->
                    intArrayOf(
                        Color.parseColor(
                            "#4338CA"
                        ),
                        Color.parseColor(
                            "#7C3AED"
                        ),
                        Color.parseColor(
                            "#2563EB"
                        )
                    )
            }

        return GradientDrawable(
            GradientDrawable
                .Orientation
                .TL_BR,
            colors
        ).apply {

            shape =
                GradientDrawable.OVAL

            setStroke(
                dp(3),
                stateStrokeColor(
                    state
                )
            )
        }
    }

    private fun stateStrokeColor(
        state: String
    ): Int {

        return Color.parseColor(
            when (
                state
            ) {

                AyanaVoiceService
                    .STATE_LISTENING ->
                    "#38BDF8"

                AyanaVoiceService
                    .STATE_THINKING ->
                    "#C084FC"

                AyanaVoiceService
                    .STATE_ERROR ->
                    "#F87171"

                AyanaVoiceService
                    .STATE_STOPPED ->
                    "#64748B"

                else ->
                    "#818CF8"
            }
        )
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                appContext
                    .resources
                    .displayMetrics
                    .density
            )
            .toInt()
    }
}
