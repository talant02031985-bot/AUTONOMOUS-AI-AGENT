package kg.autonomous.agent

import android.content.Context
import android.content.SharedPreferences

/**
 * Durable rule store for R10.27.6 Controlled Proactivity 2.0.
 *
 * Stores only bounded technical rule/runtime state. It does not store arbitrary
 * notification text, screen text, conversations, credentials or secrets.
 */
class AyanaProactivityRuleStore(
    context: Context
) : AyanaControlledProactivityEngine.RuleRepository {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    override fun isGloballyEnabled(): Boolean =
        prefs.getBoolean(KEY_GLOBAL_ENABLED, false)

    override fun setGloballyEnabled(enabled: Boolean) {
        prefs.edit()
            .putBoolean(KEY_GLOBAL_ENABLED, enabled)
            .putLong(KEY_GLOBAL_UPDATED_AT, System.currentTimeMillis())
            .apply()
    }

    override fun get(ruleId: String): AyanaControlledProactivityEngine.Rule? {
        val id = normalizeId(ruleId) ?: return null
        if (!ids().contains(id)) return null
        return readRule(id)
    }

    override fun list(): List<AyanaControlledProactivityEngine.Rule> =
        ids()
            .mapNotNull { readRule(it) }
            .sortedWith(
                compareBy<AyanaControlledProactivityEngine.Rule> { it.createdAtMs }
                    .thenBy { it.ruleId }
            )

    override fun upsert(
        rule: AyanaControlledProactivityEngine.Rule
    ): AyanaControlledProactivityEngine.Rule {
        require(rule.ruleId.isNotBlank())
        require(rule.explicitUserOptIn)

        val id = normalizeId(rule.ruleId)
            ?: throw IllegalArgumentException("invalid_rule_id")

        val currentIds = ids().toMutableList()
        if (!currentIds.contains(id)) {
            require(currentIds.size < AyanaControlledProactivityEngine.MAX_RULES) {
                "proactivity_rule_limit_reached"
            }
            currentIds.add(id)
        }

        val p = prefix(id)
        prefs.edit()
            .putString(KEY_RULE_IDS, currentIds.joinToString(","))
            .putBoolean(p + "enabled", rule.enabled)
            .putString(p + "trigger", rule.triggerType.name)
            .putInt(p + "threshold", rule.threshold ?: NO_THRESHOLD)
            .putString(p + "action", rule.actionType.name)
            .putString(p + "authority", rule.authorityLevel.name)
            .putString(p + "title", rule.title.take(AyanaControlledProactivityEngine.MAX_TITLE_CHARS))
            .putString(p + "message", rule.message.take(AyanaControlledProactivityEngine.MAX_MESSAGE_CHARS))
            .putLong(p + "cooldown_ms", rule.cooldownMs)
            .putBoolean(p + "explicit_opt_in", rule.explicitUserOptIn)
            .putLong(p + "created_at_ms", rule.createdAtMs)
            .putLong(p + "updated_at_ms", rule.updatedAtMs)
            .putLong(p + "last_fired_at_ms", rule.lastFiredAtMs)
            .putInt(
                p + "last_condition",
                when (rule.lastConditionMatched) {
                    true -> 1
                    false -> 0
                    null -> -1
                }
            )
            .putString(
                p + "last_observation_fingerprint",
                rule.lastObservationFingerprint.take(MAX_FINGERPRINT_CHARS)
            )
            .putString(p + "last_state", rule.lastState.name)
            .putLong(p + "fire_count", rule.fireCount)
            .apply()

        return readRule(id)
            ?: throw IllegalStateException("proactivity_rule_persist_failed")
    }

    override fun delete(ruleId: String): Boolean {
        val id = normalizeId(ruleId) ?: return false
        val currentIds = ids().toMutableList()
        if (!currentIds.remove(id)) return false

        val p = prefix(id)
        val editor = prefs.edit()
            .putString(KEY_RULE_IDS, currentIds.joinToString(","))

        RULE_FIELDS.forEach { field ->
            editor.remove(p + field)
        }
        editor.apply()
        return true
    }

    fun clearAllForTestOnly() {
        val existing = ids()
        val editor = prefs.edit()
        existing.forEach { id ->
            val p = prefix(id)
            RULE_FIELDS.forEach { field -> editor.remove(p + field) }
        }
        editor
            .remove(KEY_RULE_IDS)
            .remove(KEY_GLOBAL_ENABLED)
            .remove(KEY_GLOBAL_UPDATED_AT)
            .apply()
    }

    fun snapshotSummary(): String {
        val rules = list()
        val enabled = rules.count { it.enabled }
        val fired = rules.sumOf { it.fireCount }
        return "proactivity_store_version=$VERSION; global_enabled=${isGloballyEnabled()}; rules=${rules.size}; enabled_rules=$enabled; total_fire_count=$fired"
    }

    private fun readRule(id: String): AyanaControlledProactivityEngine.Rule? {
        val p = prefix(id)
        if (!prefs.contains(p + "trigger")) return null

        val trigger = enumValueOrNull<AyanaControlledProactivityEngine.TriggerType>(
            prefs.getString(p + "trigger", null)
        ) ?: return null

        val action = enumValueOrNull<AyanaControlledProactivityEngine.ActionType>(
            prefs.getString(p + "action", null)
        ) ?: AyanaControlledProactivityEngine.ActionType.NOTIFY

        val authority = enumValueOrNull<AyanaControlledProactivityEngine.AuthorityLevel>(
            prefs.getString(p + "authority", null)
        ) ?: when (action) {
            AyanaControlledProactivityEngine.ActionType.NOTIFY ->
                AyanaControlledProactivityEngine.AuthorityLevel.INFORMATIONAL
            AyanaControlledProactivityEngine.ActionType.REQUIRE_CONFIRMATION ->
                AyanaControlledProactivityEngine.AuthorityLevel.CONFIRMATION_REQUIRED
        }

        val state = enumValueOrNull<AyanaControlledProactivityEngine.RuleState>(
            prefs.getString(p + "last_state", null)
        ) ?: if (prefs.getBoolean(p + "enabled", false)) {
            AyanaControlledProactivityEngine.RuleState.ARMED
        } else {
            AyanaControlledProactivityEngine.RuleState.DISABLED
        }

        val thresholdStored = prefs.getInt(p + "threshold", NO_THRESHOLD)
        val threshold = if (thresholdStored == NO_THRESHOLD) null else thresholdStored
        val lastConditionStored = prefs.getInt(p + "last_condition", -1)
        val lastCondition = when (lastConditionStored) {
            1 -> true
            0 -> false
            else -> null
        }

        return AyanaControlledProactivityEngine.Rule(
            ruleId = id,
            enabled = prefs.getBoolean(p + "enabled", false),
            triggerType = trigger,
            threshold = threshold,
            actionType = action,
            authorityLevel = authority,
            title = prefs.getString(p + "title", "AYANA")
                .orEmpty()
                .take(AyanaControlledProactivityEngine.MAX_TITLE_CHARS),
            message = prefs.getString(p + "message", "")
                .orEmpty()
                .take(AyanaControlledProactivityEngine.MAX_MESSAGE_CHARS),
            cooldownMs = prefs.getLong(
                p + "cooldown_ms",
                AyanaControlledProactivityEngine.DEFAULT_COOLDOWN_MS
            ),
            explicitUserOptIn = prefs.getBoolean(p + "explicit_opt_in", false),
            createdAtMs = prefs.getLong(p + "created_at_ms", 0L),
            updatedAtMs = prefs.getLong(p + "updated_at_ms", 0L),
            lastFiredAtMs = prefs.getLong(p + "last_fired_at_ms", 0L),
            lastConditionMatched = lastCondition,
            lastObservationFingerprint = prefs.getString(
                p + "last_observation_fingerprint",
                ""
            ).orEmpty().take(MAX_FINGERPRINT_CHARS),
            lastState = state,
            fireCount = prefs.getLong(p + "fire_count", 0L)
        )
    }

    private fun ids(): List<String> =
        prefs.getString(KEY_RULE_IDS, "")
            .orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotBlank() && normalizeId(it) != null }
            .distinct()
            .take(AyanaControlledProactivityEngine.MAX_RULES)

    private fun prefix(id: String): String = "rule_${id}_"

    private fun normalizeId(value: String): String? {
        val id = value.trim().lowercase()
        return if (RULE_ID_REGEX.matches(id)) id else null
    }

    private inline fun <reified T : Enum<T>> enumValueOrNull(value: String?): T? =
        value?.let {
            try {
                enumValueOf<T>(it)
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        const val VERSION = "1.0"
        private const val PREFS_NAME = "ayana_controlled_proactivity_v2"
        private const val KEY_GLOBAL_ENABLED = "global_enabled"
        private const val KEY_GLOBAL_UPDATED_AT = "global_updated_at_ms"
        private const val KEY_RULE_IDS = "rule_ids"
        private const val NO_THRESHOLD = -1000
        private const val MAX_FINGERPRINT_CHARS = 180
        private val RULE_ID_REGEX = Regex("^[a-z0-9_\\-]{1,64}$")

        private val RULE_FIELDS = listOf(
            "enabled",
            "trigger",
            "threshold",
            "action",
            "authority",
            "title",
            "message",
            "cooldown_ms",
            "explicit_opt_in",
            "created_at_ms",
            "updated_at_ms",
            "last_fired_at_ms",
            "last_condition",
            "last_observation_fingerprint",
            "last_state",
            "fire_count"
        )
    }
}
