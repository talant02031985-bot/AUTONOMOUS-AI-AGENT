package kg.autonomous.agent

import java.util.Locale

/**
 * Deterministic Russian command router for R10.27.6 Controlled Proactivity 2.0.
 * No Agent Core is required for the bounded rule-management commands below.
 */
class AyanaProactivityCommandRouter(
    private val runtime: RuntimeApi
) {
    interface RuntimeApi {
        fun selfTest(): Boolean
        fun compactStatus(): String
        fun setGlobalEnabled(enabled: Boolean): Boolean
        fun isGlobalEnabled(): Boolean
        fun listRules(): List<AyanaControlledProactivityEngine.Rule>
        fun configureBatteryBelow(
            thresholdPercent: Int,
            cooldownMs: Long = AyanaControlledProactivityEngine.DEFAULT_COOLDOWN_MS
        ): AyanaControlledProactivityEngine.Rule?
        fun configureBatteryAbove(
            thresholdPercent: Int,
            cooldownMs: Long = AyanaControlledProactivityEngine.DEFAULT_COOLDOWN_MS
        ): AyanaControlledProactivityEngine.Rule?
        fun configureChargingStarted(): AyanaControlledProactivityEngine.Rule?
        fun configureChargingStopped(): AyanaControlledProactivityEngine.Rule?
        fun configureNetworkLost(): AyanaControlledProactivityEngine.Rule?
        fun configureNetworkRestored(): AyanaControlledProactivityEngine.Rule?
        fun configureWifiConnected(): AyanaControlledProactivityEngine.Rule?
        fun configureCellularActive(): AyanaControlledProactivityEngine.Rule?
        fun setRuleEnabled(ruleId: String, enabled: Boolean): Boolean
        fun deleteRule(ruleId: String): Boolean
    }
    data class Result(
        val handled: Boolean,
        val success: Boolean,
        val terminalStatus: String,
        val message: String,
        val technical: String
    )

    fun isCandidate(command: String): Boolean {
        val n = normalize(command)
        if (n.isBlank()) return false
        if (n.contains("проактив")) return true
        if (isSpecificRuleManagementRequest(n)) return true
        if (
            hasNotifyIntent(n) &&
            hasConditionConnector(n)
        ) {
            return n.contains("заряд") ||
                n.contains("батар") ||
                n.contains("интернет") ||
                n.contains("сеть") ||
                n.contains("wi-fi") ||
                n.contains("wifi") ||
                n.contains("вайф")
        }
        return false
    }

    fun handle(command: String): Result {
        val n = normalize(command)
        if (!isCandidate(n)) return notHandled()

        if (isAcceptance(n)) {
            val ok = runtime.selfTest()
            return Result(
                handled = true,
                success = ok,
                terminalStatus = if (ok) "SUCCESS" else "ERROR",
                message = if (ok) {
                    "Controlled Proactivity 2.0 прошла локальный contract self-test. ${statusText()}"
                } else {
                    "Controlled Proactivity 2.0 не прошла локальный contract self-test."
                },
                technical =
                    "controlled_proactivity_v2_self_test=$ok; ${runtime.compactStatus()}; " +
                        "explicit_opt_in_required=true; mutation_authority=false"
            )
        }

        if (isStatus(n)) {
            return Result(
                handled = true,
                success = true,
                terminalStatus = "SUCCESS",
                message = statusText(),
                technical = "controlled_proactivity_status; ${runtime.compactStatus()}"
            )
        }

        if (isGlobalDisable(n)) {
            val ok = runtime.setGlobalEnabled(false)
            return Result(
                handled = true,
                success = ok,
                terminalStatus = if (ok) "SUCCESS" else "ERROR",
                message = if (ok) {
                    "Проактивность выключена. Сохранённые правила не удалены и не будут срабатывать, пока вы снова её не включите."
                } else {
                    "Не удалось выключить проактивность."
                },
                technical = "controlled_proactivity_global_enabled=false; mutation_authority=false"
            )
        }

        if (isGlobalEnable(n)) {
            val ok = runtime.setGlobalEnabled(true)
            return Result(
                handled = true,
                success = ok,
                terminalStatus = if (ok) "SUCCESS" else "ERROR",
                message = if (ok) {
                    "Проактивность включена. Срабатывать будут только явно добавленные правила."
                } else {
                    "Не удалось включить проактивность."
                },
                technical = "controlled_proactivity_global_enabled=true; explicit_user_opt_in=true; mutation_authority=false"
            )
        }

        resolveManagedRuleId(n)?.let { ruleId ->
            if (isRuleDeleteRequest(n)) {
                val ok = runtime.deleteRule(ruleId)
                return Result(
                    handled = true,
                    success = ok,
                    terminalStatus = if (ok) "SUCCESS" else "ERROR",
                    message = if (ok) {
                        "Проактивное правило $ruleId удалено."
                    } else {
                        "Не удалось удалить правило $ruleId: правило не найдено."
                    },
                    technical =
                        "controlled_proactivity_rule_deleted=$ok; rule_id=$ruleId; " +
                            "mutation_authority=false"
                )
            }

            if (isRuleDisableRequest(n)) {
                val ok = runtime.setRuleEnabled(ruleId, false)
                return Result(
                    handled = true,
                    success = ok,
                    terminalStatus = if (ok) "SUCCESS" else "ERROR",
                    message = if (ok) {
                        "Проактивное правило $ruleId выключено."
                    } else {
                        "Не удалось выключить правило $ruleId: правило не найдено."
                    },
                    technical =
                        "controlled_proactivity_rule_enabled=false; rule_id=$ruleId; " +
                            "mutation_authority=false"
                )
            }

            if (isRuleEnableRequest(n)) {
                val ok = runtime.setRuleEnabled(ruleId, true)
                if (ok) {
                    runtime.setGlobalEnabled(true)
                }
                return Result(
                    handled = true,
                    success = ok,
                    terminalStatus = if (ok) "SUCCESS" else "ERROR",
                    message = if (ok) {
                        "Проактивное правило $ruleId включено."
                    } else {
                        "Не удалось включить правило $ruleId: правило не найдено."
                    },
                    technical =
                        "controlled_proactivity_rule_enabled=true; rule_id=$ruleId; " +
                            "global_enabled=${runtime.isGlobalEnabled()}; mutation_authority=false"
                )
            }
        }

        val configured = when {
            wantsBatteryBelow(n) -> {
                val threshold = extractPercent(n) ?: DEFAULT_LOW_BATTERY_PERCENT
                runtime.configureBatteryBelow(threshold)
            }

            wantsBatteryAbove(n) -> {
                val threshold = extractPercent(n) ?: DEFAULT_HIGH_BATTERY_PERCENT
                runtime.configureBatteryAbove(threshold)
            }

            wantsChargingStarted(n) -> runtime.configureChargingStarted()
            wantsChargingStopped(n) -> runtime.configureChargingStopped()
            wantsNetworkLost(n) -> runtime.configureNetworkLost()
            wantsNetworkRestored(n) -> runtime.configureNetworkRestored()
            wantsWifiConnected(n) -> runtime.configureWifiConnected()
            wantsCellularActive(n) -> runtime.configureCellularActive()
            else -> null
        }

        if (configured != null) {
            runtime.setGlobalEnabled(true)
            return Result(
                handled = true,
                success = true,
                terminalStatus = "SUCCESS",
                message = configuredMessage(configured),
                technical =
                    "controlled_proactivity_rule_configured; rule_id=${configured.ruleId}; " +
                        "trigger=${configured.triggerType}; cooldown_ms=${configured.cooldownMs}; " +
                        "explicit_user_opt_in=true; global_enabled=true; mutation_authority=false"
            )
        }

        return Result(
            handled = true,
            success = false,
            terminalStatus = "UNSUPPORTED",
            message =
                "Такое проактивное условие пока не поддерживается. Доступны заряд батареи, подключение/отключение зарядки и состояние интернет-сети.",
            technical = "controlled_proactivity_rule_unsupported; mutation_authority=false"
        )
    }

    private fun statusText(): String {
        val rules = runtime.listRules()
        if (rules.isEmpty()) {
            return if (runtime.isGlobalEnabled()) {
                "Проактивность включена, но правил пока нет."
            } else {
                "Проактивность выключена. Сохранённых правил нет."
            }
        }

        val enabledRules = rules.filter { it.enabled }
        val compact = enabledRules.take(8).joinToString("; ") { rule ->
            "${rule.ruleId}=${rule.triggerType.name.lowercase(Locale.ROOT)}"
        }

        return "Проактивность ${if (runtime.isGlobalEnabled()) "включена" else "выключена"}. " +
            "Правил: ${rules.size}, активных: ${enabledRules.size}. " +
            if (compact.isBlank()) "Активных правил нет." else "Активные: $compact."
    }

    private fun configuredMessage(rule: AyanaControlledProactivityEngine.Rule): String =
        when (rule.triggerType) {
            AyanaControlledProactivityEngine.TriggerType.BATTERY_BELOW_PERCENT ->
                "Правило включено: предупредить, когда заряд станет ${rule.threshold}% или ниже."
            AyanaControlledProactivityEngine.TriggerType.BATTERY_ABOVE_PERCENT ->
                "Правило включено: предупредить, когда заряд станет ${rule.threshold}% или выше."
            AyanaControlledProactivityEngine.TriggerType.CHARGING_STARTED ->
                "Правило включено: сообщить о подключении зарядки."
            AyanaControlledProactivityEngine.TriggerType.CHARGING_STOPPED ->
                "Правило включено: сообщить об отключении зарядки."
            AyanaControlledProactivityEngine.TriggerType.NETWORK_LOST ->
                "Правило включено: сообщить, если интернет пропадёт."
            AyanaControlledProactivityEngine.TriggerType.NETWORK_RESTORED ->
                "Правило включено: сообщить, когда интернет восстановится."
            AyanaControlledProactivityEngine.TriggerType.WIFI_CONNECTED ->
                "Правило включено: сообщить о подтверждённом подключении Wi-Fi."
            AyanaControlledProactivityEngine.TriggerType.CELLULAR_ACTIVE ->
                "Правило включено: сообщить, когда активной станет мобильная сеть."
            AyanaControlledProactivityEngine.TriggerType.REMINDER_DUE ->
                "Проактивное напоминание включено."
        }

    private fun isAcceptance(n: String): Boolean =
        n in setOf(
            "проверь проактивность 2.0",
            "протестируй проактивность 2.0",
            "проверь контролируемую проактивность 2.0",
            "протестируй controlled proactivity 2.0"
        )

    private fun isStatus(n: String): Boolean =
        n in setOf(
            "покажи проактивность",
            "покажи проактивные правила",
            "какие проактивные правила включены",
            "статус проактивности",
            "какая проактивность включена"
        )

    private fun isGlobalEnable(n: String): Boolean =
        n in setOf(
            "включи проактивность",
            "включить проактивность",
            "включи контролируемую проактивность"
        )

    private fun isGlobalDisable(n: String): Boolean =
        n in setOf(
            "выключи проактивность",
            "отключи проактивность",
            "выключить проактивность",
            "выключи контролируемую проактивность"
        )

    private fun isSpecificRuleManagementRequest(n: String): Boolean =
        (isRuleDisableRequest(n) || isRuleEnableRequest(n) || isRuleDeleteRequest(n)) &&
            resolveManagedRuleId(n) != null

    private fun isRuleDisableRequest(n: String): Boolean =
        n.contains("выключ") ||
            n.contains("отключ") ||
            n.contains("деактив")

    private fun isRuleEnableRequest(n: String): Boolean =
        n.contains("включ") ||
            n.contains("активиру")

    private fun isRuleDeleteRequest(n: String): Boolean =
        n.contains("удал") ||
            n.contains("убери правило")

    private fun resolveManagedRuleId(n: String): String? {
        val rules = runtime.listRules()

        fun existing(id: String): String? =
            rules.firstOrNull { it.ruleId == id }?.ruleId

        if (n.contains("network_lost") ||
            ((n.contains("интернет") || n.contains("сеть")) &&
                (n.contains("потер") || n.contains("пропад") || n.contains("отключ")))
        ) {
            return existing("network_lost")
        }

        if (n.contains("network_restored") ||
            ((n.contains("интернет") || n.contains("сеть")) &&
                (n.contains("восстанов") || n.contains("появ") || n.contains("вернет")))
        ) {
            return existing("network_restored")
        }

        if (n.contains("charging_started") ||
            (n.contains("заряд") && n.contains("подключ"))
        ) {
            return existing("charging_started")
        }

        if (n.contains("charging_stopped") ||
            (n.contains("заряд") &&
                (n.contains("отключ") || n.contains("перестан") || n.contains("прекрат")))
        ) {
            return existing("charging_stopped")
        }

        if (n.contains("wifi_connected") ||
            ((n.contains("wi-fi") || n.contains("wifi") || n.contains("вайф")) &&
                n.contains("подключ"))
        ) {
            return existing("wifi_connected")
        }

        if (n.contains("cellular_active") ||
            (n.contains("мобиль") && (n.contains("сеть") || n.contains("интернет")))
        ) {
            return existing("cellular_active")
        }

        val percent = extractPercent(n)
        if (percent != null && (n.contains("заряд") || n.contains("батар"))) {
            val belowId = "battery_below_$percent"
            val aboveId = "battery_above_$percent"
            if (rules.any { it.ruleId == belowId }) return belowId
            if (rules.any { it.ruleId == aboveId }) return aboveId
        }

        return null
    }

    private fun wantsBatteryBelow(n: String): Boolean =
        hasNotifyIntent(n) &&
            (n.contains("заряд") || n.contains("батар")) &&
            (n.contains("ниже") || n.contains("меньше") || n.contains("низк") || n.contains("останется"))

    private fun wantsBatteryAbove(n: String): Boolean =
        hasNotifyIntent(n) &&
            (n.contains("заряд") || n.contains("батар")) &&
            (n.contains("выше") || n.contains("больше") || n.contains("достигнет") || n.contains("зарядится"))

    private fun wantsChargingStarted(n: String): Boolean =
        hasNotifyIntent(n) &&
            n.contains("заряд") &&
            (n.contains("подключ") || n.contains("начн") || n.contains("начал"))

    private fun wantsChargingStopped(n: String): Boolean =
        hasNotifyIntent(n) &&
            n.contains("заряд") &&
            (n.contains("отключ") || n.contains("перестан") || n.contains("прекрат"))

    private fun wantsNetworkLost(n: String): Boolean =
        hasNotifyIntent(n) &&
            (n.contains("интернет") || n.contains("сеть")) &&
            (n.contains("пропад") || n.contains("отключ") || n.contains("потер"))

    private fun wantsNetworkRestored(n: String): Boolean =
        hasNotifyIntent(n) &&
            (n.contains("интернет") || n.contains("сеть")) &&
            (n.contains("восстанов") || n.contains("появит") || n.contains("вернет") || n.contains("вернёт"))

    private fun wantsWifiConnected(n: String): Boolean =
        hasNotifyIntent(n) &&
            (n.contains("wi-fi") || n.contains("wifi") || n.contains("вайф")) &&
            (n.contains("подключ") || n.contains("появит"))

    private fun wantsCellularActive(n: String): Boolean =
        hasNotifyIntent(n) &&
            n.contains("мобиль") &&
            (n.contains("сеть") || n.contains("интернет")) &&
            (n.contains("актив") || n.contains("переключ") || n.contains("подключ"))

    private fun hasNotifyIntent(n: String): Boolean =
        n.contains("предупреди") ||
            n.contains("сообщи") ||
            n.contains("скажи") ||
            n.contains("уведоми") ||
            n.contains("следи") ||
            n.contains("отслеживай")

    private fun hasConditionConnector(n: String): Boolean =
        n.contains("когда") ||
            n.contains("если") ||
            n.contains("как только") ||
            n.contains("при ") ||
            n.startsWith("при ")

    private fun extractPercent(n: String): Int? {
        val percent = Regex("(\\d{1,3})\\s*(?:%|процент(?:а|ов|ы)?)")
            .find(n)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: Regex("\\b(\\d{1,3})\\b")
                .find(n)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

        return percent?.takeIf { it in 1..99 }
    }

    private fun normalize(value: String): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            // R10.27.6 router v1.0.2:
            // ignore sentence-final punctuation without touching internal 2.0, %, Wi-Fi, etc.
            .replace(Regex("[.!?,;:…]+$"), "")
            .trim()
            .replace(Regex("\\s+"), " ")

    private fun notHandled(): Result =
        Result(
            handled = false,
            success = false,
            terminalStatus = "",
            message = "",
            technical = ""
        )

    companion object {
        const val VERSION = "1.0.3"
        private const val DEFAULT_LOW_BATTERY_PERCENT = 20
        private const val DEFAULT_HIGH_BATTERY_PERCENT = 80
    }
}
