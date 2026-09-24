package kg.autonomous.agent

import org.json.JSONObject

/**
 * AYANA R9.2 Controlled Proactivity Policy v1.1.
 *
 * Still default-closed: this class does not create background watchers. v1.1 adds a
 * typed event contract for R9.2 recovery notices so a caller can safely decide whether
 * a low-risk read-only notification may be surfaced. Device mutation remains separately
 * gated and is never implied by permission to notify.
 */
class AyanaControlledProactivityPolicy {

    enum class EventClass {
        NETWORK_STATE,
        AGENT_CORE_HEALTH,
        DURABLE_GOAL_RECOVERY,
        BATTERY_STATE,
        REMINDER_DUE,
        GENERIC
    }

    data class Rule(
        val enabled: Boolean,
        val allowNotification: Boolean,
        val allowDeviceMutation: Boolean,
        val cooldownMs: Long,
        val maxNotificationsPerHour: Int,
        val allowedEventClasses: Set<EventClass> = emptySet()
    )

    data class Event(
        val key: String,
        val risk: String,
        val requiresMutation: Boolean,
        val lastTriggeredAtMs: Long,
        val notificationsInLastHour: Int,
        val eventClass: EventClass = EventClass.GENERIC,
        val evidenceVerified: Boolean = false
    )

    fun evaluate(
        rule: Rule,
        event: Event,
        nowMs: Long = System.currentTimeMillis()
    ): JSONObject {
        val reasons = mutableListOf<String>()

        if (!rule.enabled) reasons += "rule_disabled"
        if (!rule.allowNotification) reasons += "notification_not_allowed"
        if (!event.evidenceVerified) reasons += "event_evidence_not_verified"

        if (
            rule.allowedEventClasses.isNotEmpty() &&
            event.eventClass !in rule.allowedEventClasses
        ) {
            reasons += "event_class_not_allowed"
        }

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

        if (
            event.notificationsInLastHour >=
            rule.maxNotificationsPerHour.coerceAtLeast(1)
        ) {
            reasons += "hourly_rate_limit"
        }

        val allowed = reasons.isEmpty()

        return JSONObject()
            .put("version", VERSION)
            .put("event_key", event.key)
            .put("event_class", event.eventClass.name)
            .put("risk", event.risk)
            .put("evidence_verified", event.evidenceVerified)
            .put("allowed", allowed)
            .put("requires_mutation", event.requiresMutation)
            .put("cooldown_remaining_ms", cooldownRemaining)
            .put("reason", if (allowed) "allowed_by_explicit_rule" else reasons.joinToString(","))
            .put(
                "silent_autonomous_mutation_allowed",
                allowed && event.requiresMutation && rule.allowDeviceMutation
            )
            .put(
                "notification_only",
                allowed && !event.requiresMutation
            )
    }

    /**
     * Conservative built-in policy template for recovery status notices. A future
     * watcher still has to be explicitly enabled by user/application settings before
     * using this rule. It never authorizes a device mutation.
     */
    fun recoveryNoticeRule(
        explicitlyEnabled: Boolean
    ): Rule =
        Rule(
            enabled = explicitlyEnabled,
            allowNotification = explicitlyEnabled,
            allowDeviceMutation = false,
            cooldownMs = 15L * 60L * 1000L,
            maxNotificationsPerHour = 3,
            allowedEventClasses =
                setOf(
                    EventClass.NETWORK_STATE,
                    EventClass.AGENT_CORE_HEALTH,
                    EventClass.DURABLE_GOAL_RECOVERY
                )
        )

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
                    notificationsInLastHour = 0,
                    eventClass = EventClass.NETWORK_STATE,
                    evidenceVerified = true
                ),
                now
            )

        val notifyAllowed =
            evaluate(
                recoveryNoticeRule(explicitlyEnabled = true),
                Event(
                    key = "goal_recovery_paused",
                    risk = "low",
                    requiresMutation = false,
                    lastTriggeredAtMs = 0L,
                    notificationsInLastHour = 0,
                    eventClass = EventClass.DURABLE_GOAL_RECOVERY,
                    evidenceVerified = true
                ),
                now
            )

        val unverifiedBlocked =
            evaluate(
                recoveryNoticeRule(explicitlyEnabled = true),
                Event(
                    key = "network_lost",
                    risk = "low",
                    requiresMutation = false,
                    lastTriggeredAtMs = 0L,
                    notificationsInLastHour = 0,
                    eventClass = EventClass.NETWORK_STATE,
                    evidenceVerified = false
                ),
                now
            )

        val mutationBlocked =
            evaluate(
                recoveryNoticeRule(explicitlyEnabled = true),
                Event(
                    key = "toggle_network",
                    risk = "medium",
                    requiresMutation = true,
                    lastTriggeredAtMs = 0L,
                    notificationsInLastHour = 0,
                    eventClass = EventClass.NETWORK_STATE,
                    evidenceVerified = true
                ),
                now
            )

        return !defaultClosed.optBoolean("allowed") &&
            notifyAllowed.optBoolean("allowed") &&
            notifyAllowed.optBoolean("notification_only") &&
            !unverifiedBlocked.optBoolean("allowed") &&
            !mutationBlocked.optBoolean("allowed") &&
            !mutationBlocked.optBoolean("silent_autonomous_mutation_allowed", true)
    }

    companion object {
        const val VERSION = "1.1"
    }
}
