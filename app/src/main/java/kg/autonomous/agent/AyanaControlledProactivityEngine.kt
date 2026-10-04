package kg.autonomous.agent

/**
 * AYANA Controlled Proactivity Engine v2.0.1 — R10.27.6.
 *
 * Pure decision/state machine. It does not register Android receivers, post
 * notifications, speak, launch apps, mutate device settings or call Agent Core.
 * Android execution remains outside this class.
 *
 * Truth contract:
 * - broad proactivity is OFF by default;
 * - every executable rule must carry explicitUserOptIn=true;
 * - edge-trigger semantics suppress repeated firing while a condition stays true;
 * - transition rules seed the first observation and never fire merely because
 *   AYANA restarted;
 * - cooldown is durable through the repository;
 * - only NOTIFY and REQUIRE_CONFIRMATION actions are accepted in v2.0;
 * - device mutation authority is always false.
 */
class AyanaControlledProactivityEngine(
    private val repository: RuleRepository,
    private val nowMs: () -> Long = { System.currentTimeMillis() }
) {

    enum class TriggerType {
        BATTERY_BELOW_PERCENT,
        BATTERY_ABOVE_PERCENT,
        CHARGING_STARTED,
        CHARGING_STOPPED,
        NETWORK_LOST,
        NETWORK_RESTORED,
        WIFI_CONNECTED,
        CELLULAR_ACTIVE,
        REMINDER_DUE
    }

    enum class ActionType {
        NOTIFY,
        REQUIRE_CONFIRMATION
    }

    enum class AuthorityLevel {
        INFORMATIONAL,
        CONFIRMATION_REQUIRED
    }

    enum class RuleState {
        DISABLED,
        ARMED,
        CONDITION_MET,
        ACTION_PENDING_CONFIRMATION,
        VERIFIED_FIRED,
        COOLDOWN,
        ERROR
    }

    data class Rule(
        val ruleId: String,
        val enabled: Boolean,
        val triggerType: TriggerType,
        val threshold: Int? = null,
        val actionType: ActionType = ActionType.NOTIFY,
        val authorityLevel: AuthorityLevel = AuthorityLevel.INFORMATIONAL,
        val title: String,
        val message: String,
        val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        val explicitUserOptIn: Boolean,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val lastFiredAtMs: Long = 0L,
        val lastConditionMatched: Boolean? = null,
        val lastObservationFingerprint: String = "",
        val lastState: RuleState = RuleState.DISABLED,
        val fireCount: Long = 0L
    )

    sealed class Event {
        data class Battery(
            val percent: Int,
            val charging: Boolean,
            val observedAtMs: Long
        ) : Event()

        data class Network(
            val connected: Boolean,
            val validated: Boolean,
            val transport: String,
            val observedAtMs: Long
        ) : Event()

        data class Reminder(
            val ruleId: String,
            val dueAtMs: Long,
            val occurrenceKey: String,
            val observedAtMs: Long
        ) : Event()
    }

    data class Decision(
        val ruleId: String,
        val allowed: Boolean,
        val shouldExecute: Boolean,
        val terminalState: RuleState,
        val actionType: ActionType,
        val authorityLevel: AuthorityLevel,
        val title: String,
        val message: String,
        val triggerType: TriggerType,
        val matchedValue: String,
        val reason: String,
        val explicitUserOptIn: Boolean,
        val cooldownRemainingMs: Long,
        val deviceMutationAuthority: Boolean = false
    )

    interface RuleRepository {
        fun isGloballyEnabled(): Boolean
        fun setGloballyEnabled(enabled: Boolean)
        fun get(ruleId: String): Rule?
        fun list(): List<Rule>
        fun upsert(rule: Rule): Rule
        fun delete(ruleId: String): Boolean
    }

    fun createRule(
        ruleId: String,
        triggerType: TriggerType,
        threshold: Int? = null,
        actionType: ActionType = ActionType.NOTIFY,
        title: String,
        message: String,
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
        enabled: Boolean = true,
        explicitUserOptIn: Boolean
    ): Rule? {
        val id = normalizeRuleId(ruleId) ?: return null
        if (!explicitUserOptIn) return null
        if (!validateTrigger(triggerType, threshold)) return null
        if (cooldownMs !in MIN_COOLDOWN_MS..MAX_COOLDOWN_MS) return null
        if (title.isBlank() || message.isBlank()) return null

        val authority =
            when (actionType) {
                ActionType.NOTIFY -> AuthorityLevel.INFORMATIONAL
                ActionType.REQUIRE_CONFIRMATION -> AuthorityLevel.CONFIRMATION_REQUIRED
            }

        val now = nowMs().coerceAtLeast(0L)
        val previous = repository.get(id)

        val rule = Rule(
            ruleId = id,
            enabled = enabled,
            triggerType = triggerType,
            threshold = threshold,
            actionType = actionType,
            authorityLevel = authority,
            title = title.trim().take(MAX_TITLE_CHARS),
            message = message.trim().take(MAX_MESSAGE_CHARS),
            cooldownMs = cooldownMs,
            explicitUserOptIn = true,
            createdAtMs = previous?.createdAtMs ?: now,
            updatedAtMs = now,
            lastFiredAtMs = previous?.lastFiredAtMs ?: 0L,
            lastConditionMatched = null,
            lastObservationFingerprint = "",
            lastState = if (enabled) RuleState.ARMED else RuleState.DISABLED,
            fireCount = previous?.fireCount ?: 0L
        )

        return repository.upsert(rule)
    }

    fun setRuleEnabled(
        ruleId: String,
        enabled: Boolean
    ): Rule? {
        val id = normalizeRuleId(ruleId) ?: return null
        val current = repository.get(id) ?: return null
        if (!current.explicitUserOptIn) return null

        return repository.upsert(
            current.copy(
                enabled = enabled,
                updatedAtMs = nowMs().coerceAtLeast(0L),
                lastConditionMatched = null,
                lastObservationFingerprint = "",
                lastState = if (enabled) RuleState.ARMED else RuleState.DISABLED
            )
        )
    }

    /**
     * Records the current physical state for one already-created rule without
     * firing it. Transition rules must be primed immediately after explicit
     * opt-in so the first future transition is not consumed as a baseline.
     */
    fun primeRule(
        ruleId: String,
        event: Event
    ): Rule? {
        val id = normalizeRuleId(ruleId) ?: return null
        val rule = repository.get(id) ?: return null
        if (!rule.enabled || !rule.explicitUserOptIn) return null
        if (!ruleAppliesToEvent(rule, event)) return null

        val match = match(rule, event)
        val now = eventObservedAt(event).coerceAtLeast(0L)

        return repository.upsert(
            rule.copy(
                lastConditionMatched = match.condition,
                lastObservationFingerprint = match.fingerprint,
                lastState = RuleState.ARMED,
                updatedAtMs = now
            )
        )
    }

    fun evaluate(event: Event): List<Decision> {
        if (!repository.isGloballyEnabled()) return emptyList()

        return repository.list()
            .asSequence()
            .filter { it.enabled && it.explicitUserOptIn }
            .filter { ruleAppliesToEvent(it, event) }
            .map { evaluateRule(it, event) }
            .filterNotNull()
            .toList()
    }

    fun simulate(
        ruleId: String,
        event: Event
    ): Decision? {
        val id = normalizeRuleId(ruleId) ?: return null
        val rule = repository.get(id) ?: return null
        if (!rule.enabled || !rule.explicitUserOptIn) {
            return blockedDecision(
                rule = rule,
                reason = "rule_disabled_or_not_opted_in"
            )
        }
        if (!ruleAppliesToEvent(rule, event)) {
            return blockedDecision(
                rule = rule,
                reason = "event_not_applicable"
            )
        }

        val match = match(rule, event)
        return Decision(
            ruleId = rule.ruleId,
            allowed = match.condition,
            shouldExecute = match.condition,
            terminalState =
                if (!match.condition) RuleState.ARMED
                else if (rule.actionType == ActionType.REQUIRE_CONFIRMATION) {
                    RuleState.ACTION_PENDING_CONFIRMATION
                } else {
                    RuleState.VERIFIED_FIRED
                },
            actionType = rule.actionType,
            authorityLevel = rule.authorityLevel,
            title = rule.title,
            message = rule.message,
            triggerType = rule.triggerType,
            matchedValue = match.matchedValue,
            reason = if (match.condition) "simulation_condition_matched" else "simulation_condition_not_met",
            explicitUserOptIn = true,
            cooldownRemainingMs = 0L,
            deviceMutationAuthority = false
        )
    }

    fun contractSelfTest(): Boolean {
        val repo = InMemoryRuleRepository()
        var clock = 1_000_000L
        val engine = AyanaControlledProactivityEngine(repo) { clock }

        if (repo.isGloballyEnabled()) return false
        if (engine.createRule(
                ruleId = "no_opt_in",
                triggerType = TriggerType.BATTERY_BELOW_PERCENT,
                threshold = 20,
                title = "x",
                message = "x",
                explicitUserOptIn = false
            ) != null
        ) return false

        repo.setGloballyEnabled(true)

        val low = engine.createRule(
            ruleId = "battery_low",
            triggerType = TriggerType.BATTERY_BELOW_PERCENT,
            threshold = 20,
            title = "Низкий заряд",
            message = "Подключите зарядное устройство.",
            cooldownMs = 60_000L,
            explicitUserOptIn = true
        ) ?: return false

        if (!low.enabled || !low.explicitUserOptIn) return false

        val first = engine.evaluate(Event.Battery(19, false, clock))
        if (first.size != 1 || !first[0].shouldExecute) return false

        val duplicate = engine.evaluate(Event.Battery(18, false, clock + 1_000L))
        if (duplicate.isNotEmpty()) return false

        val recovered = engine.evaluate(Event.Battery(30, false, clock + 2_000L))
        if (recovered.any { it.shouldExecute }) return false

        clock += 61_000L
        val reenter = engine.evaluate(Event.Battery(19, false, clock))
        if (reenter.size != 1 || !reenter[0].shouldExecute) return false

        val charging = engine.createRule(
            ruleId = "charging_started",
            triggerType = TriggerType.CHARGING_STARTED,
            title = "Зарядка подключена",
            message = "Питание подключено.",
            explicitUserOptIn = true
        ) ?: return false

        if (!charging.enabled) return false
        val seed = engine.evaluate(Event.Battery(55, false, clock + 10L))
        if (seed.any { it.ruleId == "charging_started" && it.shouldExecute }) return false

        val transition = engine.evaluate(Event.Battery(55, true, clock + 20L))
        if (transition.none { it.ruleId == "charging_started" && it.shouldExecute }) return false

        val noRepeat = engine.evaluate(Event.Battery(56, true, clock + 30L))
        if (noRepeat.any { it.ruleId == "charging_started" && it.shouldExecute }) return false

        val networkRestored = engine.createRule(
            ruleId = "network_restored",
            triggerType = TriggerType.NETWORK_RESTORED,
            title = "Интернет восстановлен",
            message = "Сеть снова доступна.",
            explicitUserOptIn = true
        ) ?: return false

        if (!networkRestored.enabled) return false
        val networkSeed = engine.evaluate(
            Event.Network(false, false, "none", clock + 40L)
        )
        if (networkSeed.any { it.ruleId == "network_restored" && it.shouldExecute }) return false

        val networkFire = engine.evaluate(
            Event.Network(true, true, "wifi", clock + 50L)
        )
        if (networkFire.none { it.ruleId == "network_restored" && it.shouldExecute }) return false

        if (networkFire.any { it.deviceMutationAuthority }) return false

        val networkLost = engine.createRule(
            ruleId = "network_lost_prime_regression",
            triggerType = TriggerType.NETWORK_LOST,
            title = "Интернет потерян",
            message = "Сеть недоступна.",
            explicitUserOptIn = true
        ) ?: return false

        val primed = engine.primeRule(
            networkLost.ruleId,
            Event.Network(true, true, "wifi", clock + 55L)
        ) ?: return false
        if (primed.lastConditionMatched != false || primed.fireCount != 0L) return false

        val firstRealLoss = engine.evaluate(
            Event.Network(false, false, "none", clock + 60L)
        )
        if (firstRealLoss.none {
                it.ruleId == networkLost.ruleId &&
                    it.shouldExecute &&
                    it.reason == "verified_edge_condition_met"
            }
        ) return false

        repo.setGloballyEnabled(false)
        val disabled = engine.evaluate(Event.Battery(10, false, clock + 70_000L))
        if (disabled.isNotEmpty()) return false

        return true
    }

    private fun evaluateRule(
        rule: Rule,
        event: Event
    ): Decision? {
        val match = match(rule, event)
        val now = eventObservedAt(event).coerceAtLeast(0L)
        val previous = rule.lastConditionMatched
        val transitionOnly = isTransitionOnly(rule.triggerType)

        // On the first observation after create/re-enable/restart, transition-only
        // triggers establish a baseline. They do not fire from a static state.
        if (previous == null && transitionOnly) {
            repository.upsert(
                rule.copy(
                    lastConditionMatched = match.condition,
                    lastObservationFingerprint = match.fingerprint,
                    lastState = RuleState.ARMED,
                    updatedAtMs = now
                )
            )
            return null
        }

        val edgeEntered =
            if (rule.triggerType == TriggerType.REMINDER_DUE) {
                match.condition &&
                    rule.lastObservationFingerprint != match.fingerprint
            } else {
                when {
                    previous == null -> match.condition
                    else -> !previous && match.condition
                }
            }

        if (!match.condition || !edgeEntered) {
            val newState =
                if (match.condition) RuleState.CONDITION_MET else RuleState.ARMED

            if (
                previous != match.condition ||
                rule.lastObservationFingerprint != match.fingerprint ||
                rule.lastState != newState
            ) {
                repository.upsert(
                    rule.copy(
                        lastConditionMatched = match.condition,
                        lastObservationFingerprint = match.fingerprint,
                        lastState = newState,
                        updatedAtMs = now
                    )
                )
            }
            return null
        }

        val elapsedSinceFire =
            if (rule.lastFiredAtMs <= 0L) Long.MAX_VALUE
            else (now - rule.lastFiredAtMs).coerceAtLeast(0L)

        if (elapsedSinceFire < rule.cooldownMs) {
            val remaining = (rule.cooldownMs - elapsedSinceFire).coerceAtLeast(0L)
            repository.upsert(
                rule.copy(
                    lastConditionMatched = true,
                    lastObservationFingerprint = match.fingerprint,
                    lastState = RuleState.COOLDOWN,
                    updatedAtMs = now
                )
            )
            return Decision(
                ruleId = rule.ruleId,
                allowed = false,
                shouldExecute = false,
                terminalState = RuleState.COOLDOWN,
                actionType = rule.actionType,
                authorityLevel = rule.authorityLevel,
                title = rule.title,
                message = rule.message,
                triggerType = rule.triggerType,
                matchedValue = match.matchedValue,
                reason = "cooldown_active",
                explicitUserOptIn = true,
                cooldownRemainingMs = remaining,
                deviceMutationAuthority = false
            )
        }

        val state =
            if (rule.actionType == ActionType.REQUIRE_CONFIRMATION) {
                RuleState.ACTION_PENDING_CONFIRMATION
            } else {
                RuleState.VERIFIED_FIRED
            }

        repository.upsert(
            rule.copy(
                lastFiredAtMs = now,
                lastConditionMatched = true,
                lastObservationFingerprint = match.fingerprint,
                lastState = state,
                fireCount = rule.fireCount + 1L,
                updatedAtMs = now
            )
        )

        return Decision(
            ruleId = rule.ruleId,
            allowed = true,
            shouldExecute = true,
            terminalState = state,
            actionType = rule.actionType,
            authorityLevel = rule.authorityLevel,
            title = rule.title,
            message = rule.message,
            triggerType = rule.triggerType,
            matchedValue = match.matchedValue,
            reason = "verified_edge_condition_met",
            explicitUserOptIn = true,
            cooldownRemainingMs = 0L,
            deviceMutationAuthority = false
        )
    }

    private data class Match(
        val condition: Boolean,
        val matchedValue: String,
        val fingerprint: String
    )

    private fun match(
        rule: Rule,
        event: Event
    ): Match {
        return when (rule.triggerType) {
            TriggerType.BATTERY_BELOW_PERCENT -> {
                val e = event as Event.Battery
                val threshold = rule.threshold ?: return Match(false, "invalid_threshold", "")
                Match(
                    condition = e.percent <= threshold && !e.charging,
                    matchedValue = "battery_percent=${e.percent}; charging=${e.charging}",
                    fingerprint = "battery:${e.percent}:${e.charging}"
                )
            }

            TriggerType.BATTERY_ABOVE_PERCENT -> {
                val e = event as Event.Battery
                val threshold = rule.threshold ?: return Match(false, "invalid_threshold", "")
                Match(
                    condition = e.percent >= threshold,
                    matchedValue = "battery_percent=${e.percent}; charging=${e.charging}",
                    fingerprint = "battery:${e.percent}:${e.charging}"
                )
            }

            TriggerType.CHARGING_STARTED -> {
                val e = event as Event.Battery
                Match(
                    condition = e.charging,
                    matchedValue = "charging=${e.charging}; battery_percent=${e.percent}",
                    fingerprint = "charging:${e.charging}"
                )
            }

            TriggerType.CHARGING_STOPPED -> {
                val e = event as Event.Battery
                Match(
                    condition = !e.charging,
                    matchedValue = "charging=${e.charging}; battery_percent=${e.percent}",
                    fingerprint = "charging:${e.charging}"
                )
            }

            TriggerType.NETWORK_LOST -> {
                val e = event as Event.Network
                Match(
                    condition = !e.connected,
                    matchedValue = "connected=${e.connected}; validated=${e.validated}; transport=${e.transport}",
                    fingerprint = "network:${e.connected}:${e.validated}:${e.transport}"
                )
            }

            TriggerType.NETWORK_RESTORED -> {
                val e = event as Event.Network
                Match(
                    condition = e.connected && e.validated,
                    matchedValue = "connected=${e.connected}; validated=${e.validated}; transport=${e.transport}",
                    fingerprint = "network:${e.connected}:${e.validated}:${e.transport}"
                )
            }

            TriggerType.WIFI_CONNECTED -> {
                val e = event as Event.Network
                Match(
                    condition = e.connected && e.validated && e.transport == "wifi",
                    matchedValue = "connected=${e.connected}; validated=${e.validated}; transport=${e.transport}",
                    fingerprint = "network:${e.connected}:${e.validated}:${e.transport}"
                )
            }

            TriggerType.CELLULAR_ACTIVE -> {
                val e = event as Event.Network
                Match(
                    condition = e.connected && e.validated && e.transport == "cellular",
                    matchedValue = "connected=${e.connected}; validated=${e.validated}; transport=${e.transport}",
                    fingerprint = "network:${e.connected}:${e.validated}:${e.transport}"
                )
            }

            TriggerType.REMINDER_DUE -> {
                val e = event as Event.Reminder
                val sameRule = e.ruleId == rule.ruleId
                val due = e.observedAtMs >= e.dueAtMs
                val occurrenceNew = e.occurrenceKey.isNotBlank() &&
                    rule.lastObservationFingerprint != "reminder:${e.occurrenceKey}"
                Match(
                    condition = sameRule && due && occurrenceNew,
                    matchedValue = "due_at_ms=${e.dueAtMs}; occurrence_key=${e.occurrenceKey.take(80)}",
                    fingerprint = "reminder:${e.occurrenceKey.take(120)}"
                )
            }
        }
    }

    private fun ruleAppliesToEvent(
        rule: Rule,
        event: Event
    ): Boolean =
        when (event) {
            is Event.Battery ->
                rule.triggerType in setOf(
                    TriggerType.BATTERY_BELOW_PERCENT,
                    TriggerType.BATTERY_ABOVE_PERCENT,
                    TriggerType.CHARGING_STARTED,
                    TriggerType.CHARGING_STOPPED
                )

            is Event.Network ->
                rule.triggerType in setOf(
                    TriggerType.NETWORK_LOST,
                    TriggerType.NETWORK_RESTORED,
                    TriggerType.WIFI_CONNECTED,
                    TriggerType.CELLULAR_ACTIVE
                )

            is Event.Reminder ->
                rule.triggerType == TriggerType.REMINDER_DUE &&
                    rule.ruleId == event.ruleId
        }

    private fun validateTrigger(
        triggerType: TriggerType,
        threshold: Int?
    ): Boolean =
        when (triggerType) {
            TriggerType.BATTERY_BELOW_PERCENT,
            TriggerType.BATTERY_ABOVE_PERCENT ->
                threshold != null && threshold in MIN_BATTERY_THRESHOLD..MAX_BATTERY_THRESHOLD

            else -> threshold == null
        }

    private fun isTransitionOnly(
        triggerType: TriggerType
    ): Boolean =
        triggerType in setOf(
            TriggerType.CHARGING_STARTED,
            TriggerType.CHARGING_STOPPED,
            TriggerType.NETWORK_LOST,
            TriggerType.NETWORK_RESTORED,
            TriggerType.WIFI_CONNECTED,
            TriggerType.CELLULAR_ACTIVE
        )

    private fun eventObservedAt(event: Event): Long =
        when (event) {
            is Event.Battery -> event.observedAtMs
            is Event.Network -> event.observedAtMs
            is Event.Reminder -> event.observedAtMs
        }

    private fun normalizeRuleId(value: String): String? {
        val normalized = value.trim().lowercase()
        if (!RULE_ID_REGEX.matches(normalized)) return null
        return normalized
    }

    private fun blockedDecision(
        rule: Rule,
        reason: String
    ): Decision =
        Decision(
            ruleId = rule.ruleId,
            allowed = false,
            shouldExecute = false,
            terminalState = if (rule.enabled) RuleState.ARMED else RuleState.DISABLED,
            actionType = rule.actionType,
            authorityLevel = rule.authorityLevel,
            title = rule.title,
            message = rule.message,
            triggerType = rule.triggerType,
            matchedValue = "",
            reason = reason,
            explicitUserOptIn = rule.explicitUserOptIn,
            cooldownRemainingMs = 0L,
            deviceMutationAuthority = false
        )

    private class InMemoryRuleRepository : RuleRepository {
        private val items = linkedMapOf<String, Rule>()
        private var enabled = false

        override fun isGloballyEnabled(): Boolean = enabled
        override fun setGloballyEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun get(ruleId: String): Rule? = items[ruleId]
        override fun list(): List<Rule> = items.values.toList()
        override fun upsert(rule: Rule): Rule {
            items[rule.ruleId] = rule
            return rule
        }
        override fun delete(ruleId: String): Boolean = items.remove(ruleId) != null
    }

    companion object {
        const val VERSION = "2.0.1"
        const val DEFAULT_COOLDOWN_MS = 30L * 60L * 1000L
        const val MIN_COOLDOWN_MS = 60_000L
        const val MAX_COOLDOWN_MS = 24L * 60L * 60L * 1000L
        const val MIN_BATTERY_THRESHOLD = 1
        const val MAX_BATTERY_THRESHOLD = 99
        const val MAX_RULES = 32
        const val MAX_TITLE_CHARS = 100
        const val MAX_MESSAGE_CHARS = 300

        private val RULE_ID_REGEX = Regex("^[a-z0-9_\\-]{1,64}$")
    }
}
