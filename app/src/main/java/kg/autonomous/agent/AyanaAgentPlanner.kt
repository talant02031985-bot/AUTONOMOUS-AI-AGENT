package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA Planner v2.2 — execution contract + bounded recovery.
 *
 * A deterministic planning envelope above Agent Core. It does not click the UI
 * and does not replace the verified Android Goal Compiler. Its job is to keep
 * the user's whole objective, explicit subgoals and terminal evidence visible
 * to the orchestrator so a model/tool classification cannot silently discard
 * part of the request.
 */
class AyanaAgentPlanner(
    private val appResolver: AyanaAppResolver,
    private val capabilityRegistry: AyanaCapabilityRegistry
) {

    fun buildEnvelope(
        command: String
    ): JSONObject {
        val clean = command.trim().replace(Regex("\\s+"), " ")
        val normalized = normalize(clean)
        val domain = inferDomain(normalized)
        val clauses = splitSubgoals(clean)
        val terminal = inferTerminalCriterion(clean, normalized, domain)
        val appHint = inferAppHint(clean, normalized)
        val appResolution =
            if (appHint.isNotBlank()) {
                appResolver.resolve(appHint).toJson()
            } else {
                null
            }

        val subgoalsJson = JSONArray()
        clauses.take(MAX_SUBGOALS).forEachIndexed { index, clause ->
            subgoalsJson.put(
                JSONObject()
                    .put("index", index)
                    .put("text", clause)
                    .put("status", "pending")
                    .put("terminal", index == clauses.lastIndex)
            )
        }

        val complexity = when {
            clauses.size >= 4 -> "high"
            clauses.size >= 2 -> "multi_step"
            isLikelyMultiStep(normalized) -> "multi_step"
            else -> "single_step"
        }

        val risk = inferRisk(normalized)
        val capabilityContext = capabilityRegistry.snapshot()
            .optJSONObject("runtime")
            ?: JSONObject()

        return JSONObject()
            .put("planner_version", "2.2")
            .put("objective", clean.take(MAX_OBJECTIVE_CHARS))
            .put("domain", domain)
            .put("complexity", complexity)
            .put("risk_hint", risk)
            .put("terminal_criterion", terminal)
            .put(
                "execution_policy",
                executionPolicy(
                    domain = domain,
                    risk = risk,
                    complexity = complexity
                )
            )
            .put("subgoals", subgoalsJson)
            .put("app_hint", appHint)
            .put("app_resolution", appResolution ?: JSONObject.NULL)
            .put(
                "runtime_hints",
                JSONObject()
                    .put(
                        "accessibility_connected",
                        capabilityContext.optBoolean("accessibility_connected", false)
                    )
                    .put(
                        "launchable_app_count",
                        capabilityContext.optInt("launchable_app_count", -1)
                    )
                    .put(
                        "recoverable_goal_count",
                        capabilityContext.optInt("recoverable_goal_count", 0)
                    )
            )
            .put("max_subgoals", MAX_SUBGOALS)
            .put("generated_at", System.currentTimeMillis())
    }

    fun compactContext(
        command: String
    ): String {
        val envelope = buildEnvelope(command)
        val subgoals = envelope.optJSONArray("subgoals") ?: JSONArray()
        val list = mutableListOf<String>()
        for (i in 0 until subgoals.length()) {
            val item = subgoals.optJSONObject(i) ?: continue
            list += "${i + 1}) ${item.optString("text")}".take(300)
        }

        val app = envelope.optJSONObject("app_resolution")
        val appText =
            if (app != null && app.optBoolean("success", false)) {
                "; resolved_app=${app.optString("label")}/${app.optString("package")}/${app.optInt("confidence")}%"
            } else {
                ""
            }

        return buildString {
            append("LOCAL PLANNER v2.2: domain=")
            append(envelope.optString("domain"))
            append("; complexity=")
            append(envelope.optString("complexity"))
            append("; risk_hint=")
            append(envelope.optString("risk_hint"))
            append("; terminal=")
            append(envelope.optString("terminal_criterion").take(420))
            val policy = envelope.optJSONObject("execution_policy")
            if (policy != null) {
                append("; verify=")
                append(policy.optString("verification_policy"))
                append("; replan_budget=")
                append(policy.optInt("max_replans", 0))
                append("; cancellation=")
                append(policy.optString("cancellation_policy"))
            }
            if (list.isNotEmpty()) {
                append("; explicit_subgoals=[")
                append(list.joinToString(" | "))
                append("]")
            }
            append(appText)
            append(". Preserve the whole objective; never mark success if only an intermediate subgoal is verified.")
        }.take(MAX_CONTEXT_CHARS)
    }

    private fun executionPolicy(
        domain: String,
        risk: String,
        complexity: String
    ): JSONObject {
        val maxReplans =
            when {
                risk == "prohibited_or_sensitive" -> 0
                risk == "high" -> 1
                complexity == "high" -> 2
                complexity == "multi_step" -> 2
                domain == "android_action" -> 1
                else -> 0
            }

        return JSONObject()
            .put("terminal_policy", "verified_terminal_only")
            .put("verification_policy", if (domain == "android_action") "fresh_evidence_required" else "domain_result_required")
            .put("cancellation_policy", "unified_execution_session")
            .put("max_replans", maxReplans)
            .put("anti_cycle", true)
            .put("fail_closed", true)
            .put("allow_false_success", false)
    }

    private fun splitSubgoals(command: String): List<String> {
        if (command.isBlank()) return emptyList()

        val prepared = command
            .replace(Regex("(?i)\\bа потом\\b"), " <AYANA_SPLIT> ")
            .replace(Regex("(?i)\\bзатем\\b"), " <AYANA_SPLIT> ")
            .replace(Regex("(?i)\\bпосле этого\\b"), " <AYANA_SPLIT> ")
            .replace(Regex("(?i)\\bи после этого\\b"), " <AYANA_SPLIT> ")
            .replace(Regex("(?i)\\bдалее\\b"), " <AYANA_SPLIT> ")

        val explicit = prepared
            .split("<AYANA_SPLIT>")
            .map { it.trim(' ', ',', '.', ';', ':', '-') }
            .filter { it.isNotBlank() }

        if (explicit.size > 1) {
            return explicit.take(MAX_SUBGOALS)
        }

        // For common Android requests, preserve the parent section and final
        // requested target as two conceptual subgoals without inventing clicks.
        val normalized = normalize(command)
        val targetIndex = listOf(" и найди ", " и открой ", " и перейди ")
            .map { marker -> normalized.indexOf(marker) to marker }
            .filter { it.first > 0 }
            .minByOrNull { it.first }

        if (targetIndex != null) {
            val index = targetIndex.first
            val marker = targetIndex.second
            val first = command.take(index).trim(' ', ',', '.', ';', ':', '-')
            val secondStart = index + marker.length
            val second = command.drop(secondStart).trim(' ', ',', '.', ';', ':', '-')
            if (first.length >= 4 && second.length >= 3) {
                return listOf(first, second).take(MAX_SUBGOALS)
            }
        }

        return listOf(command.trim())
    }

    private fun inferDomain(normalized: String): String =
        when {
            Regex("(открой|запусти|перейди|нажми|выбери|прокрут|настройк|разрешен|уведомлен|закрой|сверни|домой|назад|убери .*с экрана)").containsMatchIn(normalized) ->
                "android_action"
            Regex("(запомни|помни|вспомни|забудь|памят)").containsMatchIn(normalized) ->
                "memory"
            Regex("(напомни|задач|напоминан|перенеси|отключи напомин)").containsMatchIn(normalized) ->
                "task_management"
            Regex("(проверь себя|диагност|что не работает|почему .* не|состояние аяны|твое состояние)").containsMatchIn(normalized) ->
                "self_diagnostics"
            else -> "information"
        }

    private fun inferTerminalCriterion(
        original: String,
        normalized: String,
        domain: String
    ): String {
        if (domain != "android_action") {
            return when (domain) {
                "memory" -> "Запрошенное изменение/чтение памяти подтверждено локальным хранилищем"
                "task_management" -> "Изменение задачи сохранено и, если нужно, расписание обновлено"
                "self_diagnostics" -> "Диагностика возвращает наблюдаемые runtime-проверки"
                else -> "Пользователь получил ответ на исходную цель"
            }
        }

        if (isAppCloseLifecycle(normalized)) {
            return "Закрытие приложения подтверждено отдельным lifecycle/close evidence; Home/Back не считаются закрытием процесса"
        }

        if (isAppMinimizeLifecycle(normalized)) {
            return "Свежий Android state подтверждает, что целевое приложение больше не foreground"
        }

        val markers = listOf(
            "найди раздел ",
            "найди пункт ",
            "перейди в ",
            "открой раздел ",
            "открой пункт "
        )
        for (marker in markers) {
            val index = normalized.lastIndexOf(marker)
            if (index >= 0) {
                val target = original
                    .drop(index + marker.length)
                    .trim(' ', ',', '.', ';', ':', '-')
                if (target.isNotBlank()) {
                    return "Свежий Android screen state подтверждает конечный target «${target.take(220)}»"
                }
            }
        }

        return "Свежий Android screen state подтверждает конечное состояние исходной команды"
    }

    private fun inferAppHint(
        original: String,
        normalized: String
    ): String {
        val patterns = listOf(
            Regex("(?i)(?:полностью\\s+закрой|закрой|сверни|заверши\\s+приложение)(?:\\s+приложение)?\\s+([\\p{L}\\p{N} ._-]{2,60})"),
            Regex("(?i)(?:открой|запусти|включи)(?:\\s+приложение)?\\s+([\\p{L}\\p{N} ._-]{2,60})"),
            Regex("(?i)(?:настройки|информация|уведомления|разрешения)\\s+(?:приложения\\s+)?([\\p{L}\\p{N} ._-]{2,60})")
        )

        for (pattern in patterns) {
            val match = pattern.find(original) ?: continue
            val candidate = match.groupValues.getOrNull(1).orEmpty()
                .replace(Regex("(?i)\\s+(?:и|затем|потом|после)\\b.*$"), "")
                .trim(' ', ',', '.', ';', ':', '-')
            if (
                candidate.isNotBlank() &&
                !listOf(
                    "все",
                    "окно",
                    "вкладк",
                    "диалог",
                    "меню",
                    "клавиатур",
                    "аяну",
                    "айану",
                    "ayana"
                ).any { prefix ->
                    normalize(candidate).startsWith(prefix)
                } &&
                !candidate.equals("настройки", ignoreCase = true) &&
                !candidate.equals("специальные возможности", ignoreCase = true)
            ) {
                return candidate
            }
        }

        if (normalized.contains("калькулятор")) return "калькулятор"
        if (normalized.contains("ютуб") || normalized.contains("youtube")) return "YouTube"
        return ""
    }

    private fun inferRisk(normalized: String): String =
        when {
            Regex("(парол|pin|пин|otp|код подтвержден|api[- ]?ключ|token|токен|карта|cvv)").containsMatchIn(normalized) ->
                "prohibited_or_sensitive"
            Regex("(удали|отправь|купи|оплати|переведи деньги|подтверди покуп)").containsMatchIn(normalized) ->
                "high"
            Regex("(введи|нажми|выбери|измени|включи|выключи|закрой|сверни)").containsMatchIn(normalized) ->
                "action"
            else -> "low"
        }

    private fun isAppCloseLifecycle(
        normalized: String
    ): Boolean {
        val excluded =
            listOf(
                "закрой все",
                "закрой окно",
                "закрой вкладк",
                "закрой диалог",
                "закрой меню",
                "закрой клавиатур",
                "закрой аяну",
                "закрой айану",
                "закрой ayana",
                "полностью закрой все",
                "полностью закрой окно"
            )

        if (excluded.any { normalized.startsWith(it) }) {
            return false
        }

        return Regex("^(?:полностью )?закрой(?: приложение)?\\s+.+")
            .containsMatchIn(
                normalized
            ) ||
            Regex("^заверши приложение\\s+.+")
                .containsMatchIn(
                    normalized
                )
    }

    private fun isAppMinimizeLifecycle(
        normalized: String
    ): Boolean {
        if (
            normalized.startsWith("сверни все") ||
            normalized.startsWith("сверни окно")
        ) {
            return false
        }

        return Regex("^сверни(?: приложение)?\\s+.+")
            .containsMatchIn(
                normalized
            )
    }

    private fun isLikelyMultiStep(normalized: String): Boolean {
        val verbs = listOf("открой", "перейди", "найди", "нажми", "выбери", "прокрути")
        return verbs.count { normalized.contains(it) } >= 2
    }

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

    companion object {
        private const val MAX_SUBGOALS = 6
        private const val MAX_OBJECTIVE_CHARS = 1200
        private const val MAX_CONTEXT_CHARS = 2600
    }
}
