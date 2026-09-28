package kg.autonomous.agent

import android.content.Context

/**
 * AYANA R10.0 bounded controlled proactivity: opt-in low-battery monitor.
 *
 * Scope is intentionally narrow:
 * - user must explicitly enable the rule;
 * - the rule may emit a notification only;
 * - it has no device-mutation authority;
 * - cooldown + hourly rate limit prevent notification spam;
 * - all persisted state is technical rule/runtime metadata only.
 *
 * This class does not claim broad/general controlled proactivity.
 */
class AyanaBatteryProactivityController(
    context: Context
) {

    data class Rule(
        val enabled: Boolean,
        val thresholdPercent: Int,
        val cooldownMs: Long,
        val maxNotificationsPerHour: Int
    )

    data class RuntimeState(
        val lastNotificationAtMs: Long,
        val hourWindowStartMs: Long,
        val notificationsInWindow: Int
    )

    data class Decision(
        val allowed: Boolean,
        val reason: String,
        val batteryPercent: Int,
        val charging: Boolean,
        val thresholdPercent: Int,
        val cooldownRemainingMs: Long,
        val notificationsInWindow: Int,
        val requiresDeviceMutation: Boolean = false
    )

    private val appContext =
        context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun currentRule(): Rule =
        Rule(
            enabled =
                prefs.getBoolean(
                    KEY_ENABLED,
                    false
                ),
            thresholdPercent =
                prefs.getInt(
                    KEY_THRESHOLD_PERCENT,
                    DEFAULT_THRESHOLD_PERCENT
                )
                    .coerceIn(
                        MIN_THRESHOLD_PERCENT,
                        MAX_THRESHOLD_PERCENT
                    ),
            cooldownMs =
                prefs.getLong(
                    KEY_COOLDOWN_MS,
                    DEFAULT_COOLDOWN_MS
                )
                    .coerceAtLeast(
                        MIN_COOLDOWN_MS
                    ),
            maxNotificationsPerHour =
                prefs.getInt(
                    KEY_MAX_NOTIFICATIONS_PER_HOUR,
                    DEFAULT_MAX_NOTIFICATIONS_PER_HOUR
                )
                    .coerceIn(
                        1,
                        MAX_NOTIFICATIONS_PER_HOUR_LIMIT
                    )
        )

    fun runtimeState(
        nowMs: Long = System.currentTimeMillis()
    ): RuntimeState {
        val storedWindowStart =
            prefs.getLong(
                KEY_HOUR_WINDOW_START_MS,
                0L
            )

        val windowExpired =
            storedWindowStart <= 0L ||
                nowMs < storedWindowStart ||
                nowMs - storedWindowStart >= HOUR_MS

        return RuntimeState(
            lastNotificationAtMs =
                prefs.getLong(
                    KEY_LAST_NOTIFICATION_AT_MS,
                    0L
                ),
            hourWindowStartMs =
                if (windowExpired) {
                    nowMs
                } else {
                    storedWindowStart
                },
            notificationsInWindow =
                if (windowExpired) {
                    0
                } else {
                    prefs.getInt(
                        KEY_NOTIFICATIONS_IN_WINDOW,
                        0
                    )
                        .coerceAtLeast(0)
                }
        )
    }

    fun enable(
        thresholdPercent: Int
    ): Rule? {
        if (
            thresholdPercent !in
            MIN_THRESHOLD_PERCENT..MAX_THRESHOLD_PERCENT
        ) {
            return null
        }

        prefs.edit()
            .putBoolean(
                KEY_ENABLED,
                true
            )
            .putInt(
                KEY_THRESHOLD_PERCENT,
                thresholdPercent
            )
            // Explicit re-enable starts a fresh alert window so the current
            // battery condition can be evaluated immediately.
            .putLong(
                KEY_LAST_NOTIFICATION_AT_MS,
                0L
            )
            .putLong(
                KEY_HOUR_WINDOW_START_MS,
                0L
            )
            .putInt(
                KEY_NOTIFICATIONS_IN_WINDOW,
                0
            )
            .apply()

        return currentRule()
    }

    fun disable(): Rule {
        prefs.edit()
            .putBoolean(
                KEY_ENABLED,
                false
            )
            .apply()

        return currentRule()
    }

    fun evaluate(
        batteryPercent: Int,
        charging: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): Decision =
        evaluatePure(
            rule = currentRule(),
            runtime = runtimeState(nowMs),
            batteryPercent = batteryPercent,
            charging = charging,
            nowMs = nowMs
        )

    fun markNotificationDelivered(
        nowMs: Long = System.currentTimeMillis()
    ) {
        val runtime =
            runtimeState(nowMs)

        prefs.edit()
            .putLong(
                KEY_LAST_NOTIFICATION_AT_MS,
                nowMs
            )
            .putLong(
                KEY_HOUR_WINDOW_START_MS,
                runtime.hourWindowStartMs
            )
            .putInt(
                KEY_NOTIFICATIONS_IN_WINDOW,
                runtime.notificationsInWindow + 1
            )
            .apply()
    }

    fun selfTest(): Boolean {
        val now =
            1_000_000L

        val enabledRule =
            Rule(
                enabled = true,
                thresholdPercent = 20,
                cooldownMs = DEFAULT_COOLDOWN_MS,
                maxNotificationsPerHour = 1
            )

        val cleanRuntime =
            RuntimeState(
                lastNotificationAtMs = 0L,
                hourWindowStartMs = now,
                notificationsInWindow = 0
            )

        val lowAllowed =
            evaluatePure(
                rule = enabledRule,
                runtime = cleanRuntime,
                batteryPercent = 15,
                charging = false,
                nowMs = now
            )

        val chargingBlocked =
            evaluatePure(
                rule = enabledRule,
                runtime = cleanRuntime,
                batteryPercent = 15,
                charging = true,
                nowMs = now
            )

        val highBlocked =
            evaluatePure(
                rule = enabledRule,
                runtime = cleanRuntime,
                batteryPercent = 70,
                charging = false,
                nowMs = now
            )

        val disabledBlocked =
            evaluatePure(
                rule = enabledRule.copy(enabled = false),
                runtime = cleanRuntime,
                batteryPercent = 15,
                charging = false,
                nowMs = now
            )

        val cooldownBlocked =
            evaluatePure(
                rule = enabledRule,
                runtime =
                    cleanRuntime.copy(
                        lastNotificationAtMs =
                            now - 1_000L
                    ),
                batteryPercent = 15,
                charging = false,
                nowMs = now
            )

        val rateBlocked =
            evaluatePure(
                rule = enabledRule.copy(cooldownMs = MIN_COOLDOWN_MS),
                runtime =
                    cleanRuntime.copy(
                        notificationsInWindow = 1
                    ),
                batteryPercent = 15,
                charging = false,
                nowMs = now
            )

        return lowAllowed.allowed &&
            lowAllowed.reason == "allowed_low_battery_notification" &&
            !lowAllowed.requiresDeviceMutation &&
            !chargingBlocked.allowed &&
            chargingBlocked.reason == "charging" &&
            !highBlocked.allowed &&
            highBlocked.reason == "battery_above_threshold" &&
            !disabledBlocked.allowed &&
            disabledBlocked.reason == "rule_disabled" &&
            !cooldownBlocked.allowed &&
            cooldownBlocked.reason == "cooldown_active" &&
            !rateBlocked.allowed &&
            rateBlocked.reason == "hourly_rate_limit"
    }

    companion object {

        const val VERSION =
            "1.0"

        const val DEFAULT_THRESHOLD_PERCENT =
            20

        const val MIN_THRESHOLD_PERCENT =
            5

        const val MAX_THRESHOLD_PERCENT =
            50

        const val DEFAULT_COOLDOWN_MS =
            4L * 60L * 60L * 1000L

        const val DEFAULT_MAX_NOTIFICATIONS_PER_HOUR =
            1

        private const val MIN_COOLDOWN_MS =
            60_000L

        private const val MAX_NOTIFICATIONS_PER_HOUR_LIMIT =
            4

        private const val HOUR_MS =
            60L * 60L * 1000L

        private const val PREFS_NAME =
            "ayana_battery_proactivity_v1"

        private const val KEY_ENABLED =
            "enabled"

        private const val KEY_THRESHOLD_PERCENT =
            "threshold_percent"

        private const val KEY_COOLDOWN_MS =
            "cooldown_ms"

        private const val KEY_MAX_NOTIFICATIONS_PER_HOUR =
            "max_notifications_per_hour"

        private const val KEY_LAST_NOTIFICATION_AT_MS =
            "last_notification_at_ms"

        private const val KEY_HOUR_WINDOW_START_MS =
            "hour_window_start_ms"

        private const val KEY_NOTIFICATIONS_IN_WINDOW =
            "notifications_in_window"

        fun evaluatePure(
            rule: Rule,
            runtime: RuntimeState,
            batteryPercent: Int,
            charging: Boolean,
            nowMs: Long
        ): Decision {
            val percent =
                batteryPercent.coerceIn(0, 100)

            if (!rule.enabled) {
                return Decision(
                    allowed = false,
                    reason = "rule_disabled",
                    batteryPercent = percent,
                    charging = charging,
                    thresholdPercent = rule.thresholdPercent,
                    cooldownRemainingMs = 0L,
                    notificationsInWindow = runtime.notificationsInWindow
                )
            }

            if (charging) {
                return Decision(
                    allowed = false,
                    reason = "charging",
                    batteryPercent = percent,
                    charging = true,
                    thresholdPercent = rule.thresholdPercent,
                    cooldownRemainingMs = 0L,
                    notificationsInWindow = runtime.notificationsInWindow
                )
            }

            if (percent > rule.thresholdPercent) {
                return Decision(
                    allowed = false,
                    reason = "battery_above_threshold",
                    batteryPercent = percent,
                    charging = false,
                    thresholdPercent = rule.thresholdPercent,
                    cooldownRemainingMs = 0L,
                    notificationsInWindow = runtime.notificationsInWindow
                )
            }

            val cooldownRemaining =
                if (
                    runtime.lastNotificationAtMs > 0L &&
                    nowMs >= runtime.lastNotificationAtMs
                ) {
                    (
                        rule.cooldownMs -
                            (nowMs - runtime.lastNotificationAtMs)
                        )
                        .coerceAtLeast(0L)
                } else {
                    0L
                }

            if (cooldownRemaining > 0L) {
                return Decision(
                    allowed = false,
                    reason = "cooldown_active",
                    batteryPercent = percent,
                    charging = false,
                    thresholdPercent = rule.thresholdPercent,
                    cooldownRemainingMs = cooldownRemaining,
                    notificationsInWindow = runtime.notificationsInWindow
                )
            }

            if (
                runtime.notificationsInWindow >=
                rule.maxNotificationsPerHour.coerceAtLeast(1)
            ) {
                return Decision(
                    allowed = false,
                    reason = "hourly_rate_limit",
                    batteryPercent = percent,
                    charging = false,
                    thresholdPercent = rule.thresholdPercent,
                    cooldownRemainingMs = 0L,
                    notificationsInWindow = runtime.notificationsInWindow
                )
            }

            return Decision(
                allowed = true,
                reason = "allowed_low_battery_notification",
                batteryPercent = percent,
                charging = false,
                thresholdPercent = rule.thresholdPercent,
                cooldownRemainingMs = 0L,
                notificationsInWindow = runtime.notificationsInWindow
            )
        }
    }
}
