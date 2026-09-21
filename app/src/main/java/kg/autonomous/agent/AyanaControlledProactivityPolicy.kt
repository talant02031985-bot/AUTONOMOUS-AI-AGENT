package kg.autonomous.agent

import org.json.JSONObject

/**
 * AYANA R9.0 Controlled Proactivity Policy v1.0.
 *
 * Foundation only: no background watcher is enabled by this class. It defines the
 * permission/cooldown/rate-limit contract that any future watcher must pass before AYANA
 * may surface or execute a proactive action.
 */
class AyanaControlledProactivityPolicy {

    data class Rule(
        val enabled: Boolean,
        val allowNotification: Boolean,
        val allowDeviceMutation: Boolean,
        val cooldownMs: Long,
        val maxNotificationsPerHour: Int
    )

    data class Event(
        val key: String,
        val risk: String,
        val requiresMutation: Boolean,
        val lastTriggeredAtMs: Long,
        val notificationsInLastHour: Int
    )

    fun evaluate(
        rule: Rule,
        event: Event,
        nowMs: Long = System.currentTimeMillis()
    ): JSONObject {
        val reasons = mutableListOf<String>()

        if (!rule.enabled) reasons += "rule_disabled"
        if (!rule.allowNotification) reasons += "notification_not_allowed"
        if (event.requiresMutation && !rule.allowDeviceMutation) {
            reasons += "device_mutation_not_allowed"
        }

        val cooldownRemaining =
            if (event.lastTriggeredAtMs > 0L) {
                (rule.cooldownMs - (nowMs - event.lastTriggeredAtMs)).coerceAtLeast(0L)
            } else {
                0L
            }

        if (cooldownRemaining > 0L) reasons += "cooldown_active"
        if (event.notificationsInLastHour >= rule.maxNotificationsPerHour.coerceAtLeast(1)) {
            reasons += "hourly_rate_limit"
        }

        val allowed = reasons.isEmpty()

        return JSONObject()
            .put("version", VERSION)
            .put("event_key", event.key)
            .put("risk", event.risk)
            .put("allowed", allowed)
            .put("requires_mutation", event.requiresMutation)
            .put("cooldown_remaining_ms", cooldownRemaining)
            .put("reason", if (allowed) "allowed_by_explicit_rule" else reasons.joinToString(","))
            .put("silent_autonomous_mutation_allowed", allowed && event.requiresMutation && rule.allowDeviceMutation)
    }

    fun selfTest(): Boolean {
        val now = 100_000L
        val defaultClosed =
            evaluate(
                Rule(
                    enabled = false,
                    allowNotification = false,
                    allowDeviceMutation = false,
                    cooldownMs = 60_000L,
                    maxNotificationsPerHour = 2
                ),
                Event(
                    key = "network_lost",
                    risk = "low",
                    requiresMutation = false,
                    lastTriggeredAtMs = 0L,
                    notificationsInLastHour = 0
                ),
                now
            )

        val notifyAllowed =
            evaluate(
                Rule(
                    enabled = true,
                    allowNotification = true,
                    allowDeviceMutation = false,
                    cooldownMs = 60_000L,
                    maxNotificationsPerHour = 2
                ),
                Event(
                    key = "battery_low",
                    risk = "low",
                    requiresMutation = false,
                    lastTriggeredAtMs = 0L,
                    notificationsInLastHour = 0
                ),
                now
            )

        val mutationBlocked =
            evaluate(
                Rule(
                    enabled = true,
                    allowNotification = true,
                    allowDeviceMutation = false,
                    cooldownMs = 0L,
                    maxNotificationsPerHour = 2
                ),
                Event(
                    key = "toggle_network",
                    risk = "medium",
                    requiresMutation = true,
                    lastTriggeredAtMs = 0L,
                    notificationsInLastHour = 0
                ),
                now
            )

        return !defaultClosed.optBoolean("allowed") &&
            notifyAllowed.optBoolean("allowed") &&
            !mutationBlocked.optBoolean("allowed")
    }

    companion object {
        const val VERSION = "1.0"
    }
}
