package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Structured Local Command Router v1.1 — EXECUTION GUARDS.
 *
 * Preserves v1.0 local-first intents and adds fail-closed guards for explicit
 * user constraints that must be honored before any state-changing or generic
 * Agent Core fallback can run.
 *
 * IMPORTANT: v1.1 deliberately reuses the existing Intent surface so it stays
 * binary/source compatible with the v12.11 VoiceService runStructuredLocalCommand
 * dispatcher. Guarded requests return UnknownCapability and therefore terminate
 * through the already-existing UNSUPPORTED path without performing a side effect.
 */
object AyanaStructuredLocalCommandRouter {

    sealed class Intent {
        data class NotificationRead(val limit: Int?, val appFilter: String?) : Intent()
        data class RelativeMediaVolume(val delta: Int) : Intent()
        data class DeviceMetric(val metric: Metric) : Intent()
        data object DeviceDiagnostics : Intent()
        data class AppVersion(val appName: String) : Intent()
        data class History(val limit: Int) : Intent()
        data class MemoryList(val query: String?) : Intent()
        data class MemoryRecall(val query: String) : Intent()
        data class MemoryRemember(val text: String) : Intent()
        data class MemoryForget(val query: String) : Intent()
        data class ReminderList(val query: String?) : Intent()
        data class ReminderCreate(
            val title: String,
            val relativeMinutes: Int? = null,
            val tomorrowHour: Int? = null,
            val tomorrowMinute: Int? = null
        ) : Intent()
        data class ReminderDelete(val query: String) : Intent()
        data class ClipboardCopy(val text: String) : Intent()
        data class InternalPage(val page: Page) : Intent()
        data class BrightnessSet(val percent: Int) : Intent()
        data class SystemToggle(val kind: ToggleKind, val enable: Boolean) : Intent()
        data class UnknownCapability(val label: String) : Intent()
        data class CompositeVolumeAndState(val level: Int, val scale: Int) : Intent()
    }

    enum class Metric {
        BATTERY,
        MEDIA_VOLUME,
        BRIGHTNESS,
        STORAGE,
        DEVICE_MODEL,
        DATE_TIME,
        SCREEN_TITLE
    }

    enum class Page {
        HOME,
        TASKS,
        MEMORY,
        HISTORY,
        SYSTEM,
        SETTINGS
    }

    enum class ToggleKind {
        WIFI,
        BLUETOOTH
    }

    fun parse(raw: String): Intent? {
        val c = normalize(raw)
        if (c.isBlank()) return null

        // v1.1 UNIVERSAL FAIL-CLOSED CONSTRAINT GUARDS.
        // These run before argument extraction so numbers or action verbs inside
        // a conditional/data block can never be rebound as an executable command.
        parseExecutionConstraintGuard(c)?.let { return it }
        parseNotificationSafetyGuard(c)?.let { return it }
        parseHistoryUnsupportedGuard(c)?.let { return it }
        parseUnsupportedStateQueryGuard(c)?.let { return it }

        parseCompositeVolumeAndState(c)?.let { return it }
        parseNotificationRead(c)?.let { return it }
        parseRelativeVolume(c)?.let { return it }
        if (
            (c.contains("продиагностируй") || c.contains("диагностик")) &&
            (c.contains("планшет") || c.contains("устройств"))
        ) {
            return Intent.DeviceDiagnostics
        }
        parseDeviceMetric(c)?.let { return it }
        parseAppVersion(c)?.let { return it }
        parseHistory(c)?.let { return it }
        parseMemory(c, raw)?.let { return it }
        parseReminder(c, raw)?.let { return it }
        parseClipboard(c, raw)?.let { return it }
        parseInternalPage(c)?.let { return it }
        parseBrightnessSet(c)?.let { return it }
        parseSystemToggle(c)?.let { return it }
        parseUnknownCapability(c)?.let { return it }

        return null
    }

    /**
     * Guard explicit non-execution/confirmation/conditional constraints.
     * Read-only questions are not blocked merely because they contain "если".
     */
    private fun parseExecutionConstraintGuard(c: String): Intent? {
        val actionLike = containsStateChangingAction(c)

        // v1.1 does not pretend to execute a compound goal atomically. If an
        // explicitly sequenced command spans multiple capability families, block
        // it before any first-step executor can claim whole-command SUCCESS.
        if (isUnsupportedCompositeCommand(c)) {
            return Intent.UnknownCapability(
                "многошаговая составная команда без атомарного оркестратора"
            )
        }

        val dataOnly =
            c.contains("только как данные") ||
                c.contains("считай это данными") ||
                c.contains("рассматривай это как данные") ||
                c.contains("не выполняй его как команд") ||
                c.contains("не выполняй это как команд") ||
                c.contains("ничего из него не выполняй") ||
                c.contains("ничего из этого не выполняй")

        if (dataOnly && containsEmbeddedActionLanguage(c)) {
            return Intent.UnknownCapability(
                "исполнение команд внутри блока данных запрещено"
            )
        }

        val requiresConfirmation =
            c.contains("спроси подтвержден") ||
                c.contains("запроси подтвержден") ||
                c.contains("сначала спроси") ||
                c.contains("сначала запроси") ||
                c.contains("до моего подтвержден") ||
                c.contains("пока я не подтвержу") ||
                c.contains("не меняй до") ||
                c.contains("ничего не меняй до") ||
                c.contains("не выполняй до следующ") ||
                c.contains("не делай до следующ")

        if (requiresConfirmation && actionLike) {
            return Intent.UnknownCapability(
                "отложенное Android-действие с обязательным подтверждением"
            )
        }

        // Conditional state changes require a real condition evaluator that binds
        // each numeric value to its own clause. Until that exists, fail closed.
        val conditional =
            c.startsWith("если ") ||
                c.contains("; если ") ||
                c.contains(", если ") ||
                c.contains(" то ") && c.contains("если")

        if (conditional && actionLike) {
            return Intent.UnknownCapability(
                "условное выполнение Android-действий"
            )
        }

        // Explicit global prohibition of Settings/network must not be silently
        // discarded by a later planner. Block only when the same command also
        // asks for an action that could violate the prohibition.
        val forbidsSettings =
            c.contains("не открывай настройки") ||
                c.contains("настройки не открывай")
        val forbidsNetwork =
            c.contains("не используй интернет") ||
                c.contains("без интернета") ||
                c.contains("не выходи в интернет")

        if (
            (forbidsSettings || forbidsNetwork) &&
            !isClearlyLocalRead(c) &&
            !isClearlyLocalOwnedAction(c)
        ) {
            return Intent.UnknownCapability(
                "команда с обязательным ограничением среды выполнения"
            )
        }

        return null
    }

    private fun parseNotificationSafetyGuard(c: String): Intent? {
        if (!c.contains("уведомлен")) return null

        val privacyProjection =
            c.contains("только назван") && c.contains("прилож") ||
                c.contains("только приложения") ||
                c.contains("без текста уведом") ||
                c.contains("не показывай текст") ||
                c.contains("не показывай содерж") ||
                c.contains("не показывай имена") ||
                c.contains("не показывай номер") ||
                c.contains("не показывай адрес")

        if (privacyProjection) {
            return Intent.UnknownCapability(
                "privacy-projected список уведомлений"
            )
        }

        val clearAction =
            c.contains("очист") &&
                (c.contains("все") || c.contains("текущ") || c.contains("уведомлен"))
        if (clearAction) {
            return Intent.UnknownCapability("очистка уведомлений")
        }

        val notificationShade =
            c.contains("панел") && c.contains("уведомлен") ||
                c.contains("шторк") && c.contains("уведомлен") ||
                c.contains("notification shade")
        if (notificationShade) {
            return Intent.UnknownCapability("панель уведомлений")
        }

        return null
    }

    private fun parseHistoryUnsupportedGuard(c: String): Intent? {
        if (!c.contains("истори") || !c.contains("команд")) return null

        val unsupported =
            c.contains("удали") ||
                c.contains("удалить") ||
                c.contains("очисти запись") ||
                c.contains("найди команд") ||
                c.contains("найди в истори") ||
                c.contains("поищи в истори") ||
                c.contains("отфильтр") ||
                c.contains("только error") ||
                c.contains("только ошибки") ||
                c.contains("raw result") ||
                c.contains("сырой result") ||
                c.contains("дословно сохраненн") ||
                c.contains("полностью сохраненн") ||
                c.contains("поле result")

        return if (unsupported) {
            Intent.UnknownCapability(
                "поиск/фильтрация/удаление/RAW-доступ к истории команд"
            )
        } else {
            null
        }
    }

    private fun parseUnsupportedStateQueryGuard(c: String): Intent? {
        // Prevent known state-query collisions with a different supported metric.
        if (
            c.contains("автоматическ") && c.contains("ярк") &&
            (c.contains("включ") || c.contains("выключ") || c.contains("сейчас"))
        ) {
            return Intent.UnknownCapability("состояние автоматической яркости")
        }
        return null
    }

    private fun parseCompositeVolumeAndState(c: String): Intent? {
        if (!c.contains("громк") || !c.contains("состояни") || !c.contains("планшет")) {
            return null
        }
        val m = Regex("""(?:установ[\p{L}]*|постав[\p{L}]*|выстав[\p{L}]*|задай|сделай)\s+громк[\p{L}]*(?:\s+мультимедиа)?\s+на\s+(\d{1,3})\s*(?:из|/)\s*(\d{1,3})""")
            .find(c) ?: return null
        val level = m.groupValues[1].toIntOrNull() ?: return null
        val scale = m.groupValues[2].toIntOrNull() ?: return null
        return Intent.CompositeVolumeAndState(level, scale)
    }

    private fun parseNotificationRead(c: String): Intent? {
        if (!c.contains("уведомлен")) return null
        if (
            c.contains("настрой") || c.contains("разреш") ||
            c.contains("доступ к уведом") || c.contains("категор") ||
            c.startsWith("включ") || c.startsWith("выключ") || c.startsWith("открой")
        ) return null

        val readIntent =
            c.contains("последн") || c.contains("недавн") || c.contains("текущ") ||
                c.contains("новые уведом") || c.contains("какие уведом") ||
                c.contains("что пришло") || c.contains("прочитай уведом") ||
                c.contains("прочти уведом") || c.contains("перечисли уведом") ||
                c.contains("покажи")
        if (!readIntent) return null

        val singular = Regex("""последнее\s+уведомлен""").containsMatchIn(c)
        val numeric = Regex("""последн[\p{L}]*\s+(\d{1,2})\s+уведомлен""")
            .find(c)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val limit = when {
            singular -> 1
            numeric != null -> numeric.coerceIn(1, 20)
            else -> null
        }

        val filter = Regex("""(?:^|\s)(?:от|из)\s+([\p{L}0-9._ -]{2,60})$""")
            .find(c)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }

        return Intent.NotificationRead(limit, filter)
    }

    private fun parseRelativeVolume(c: String): Intent? {
        if (!c.contains("громк") && !c.contains("звук")) return null
        val up = c.contains("увелич") || c.contains("прибав") || c.contains("громче")
        val down = c.contains("уменьш") || c.contains("убав") || c.contains("тише")
        if (up == down) return null
        if (c.contains("установ") || c.contains("постав") || c.contains("выстав") || c.contains("задай")) return null

        val amount = Regex("""(?:^|\s)на\s+(\d{1,2})(?:\s|$)""")
            .find(c)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
        if (amount <= 0) return null
        return Intent.RelativeMediaVolume(if (up) amount else -amount)
    }

    private fun parseDeviceMetric(c: String): Intent? {
        val actionVerb = containsActionVerb(c)

        if (c.contains("громк") && (c.startsWith("какая") || c.startsWith("какой") || c.contains("сейчас громк"))) {
            return Intent.DeviceMetric(Metric.MEDIA_VOLUME)
        }
        if ((c.contains("заряд") || c.contains("батаре")) && (c.startsWith("какой") || c.startsWith("сколько") || c.contains("сейчас"))) {
            return Intent.DeviceMetric(Metric.BATTERY)
        }
        if (c.contains("ярк") && (c.startsWith("какая") || c.startsWith("какой") || c.contains("сейчас"))) {
            return Intent.DeviceMetric(Metric.BRIGHTNESS)
        }
        if (c.contains("свобод") && (c.contains("мест") || c.contains("памят") || c.contains("хранилищ"))) {
            return Intent.DeviceMetric(Metric.STORAGE)
        }
        if (c.contains("модель") && (c.contains("планшет") || c.contains("устройств"))) {
            return Intent.DeviceMetric(Metric.DEVICE_MODEL)
        }

        // v1.1: a Settings navigation request such as "открой настройки Дата и
        // время" must never be swallowed by the current-date metric parser.
        val dateTimeQuery =
            !actionVerb &&
                (c.contains("сегодня") || c.contains("число") || c.contains("дат") || c.contains("текущее время")) &&
                (c.contains("час") || c.contains("врем") || c.contains("который") || c.contains("дат")) &&
                (c.startsWith("какая") || c.startsWith("какой") || c.startsWith("сколько") ||
                    c.startsWith("скажи") || c.contains("сейчас") || c.contains("текущ"))
        if (dateTimeQuery) {
            return Intent.DeviceMetric(Metric.DATE_TIME)
        }

        if (c.contains("заголовок") && c.contains("экран")) {
            return Intent.DeviceMetric(Metric.SCREEN_TITLE)
        }
        if (c == "что сейчас открыто на экране" || c == "что открыто на экране") {
            return Intent.DeviceMetric(Metric.SCREEN_TITLE)
        }
        return null
    }

    private fun parseAppVersion(c: String): Intent? {
        if (!c.contains("верси") || !c.contains("приложен")) return null
        val patterns = listOf(
            Regex("""какая\s+версия\s+приложения\s+(.+?)(?:\s+установлена.*)?$"""),
            Regex("""версия\s+приложения\s+(.+)$""")
        )
        for (p in patterns) {
            val app = p.find(c)?.groupValues?.getOrNull(1)?.trim()?.removeSuffix(" на планшете")?.trim()
            if (!app.isNullOrBlank()) return Intent.AppVersion(app)
        }
        return null
    }

    private fun parseHistory(c: String): Intent? {
        if (!c.contains("истори") || !c.contains("команд")) return null
        if (!c.contains("покажи") && !c.contains("последн") && !c.contains("прочитай")) return null
        val limit = Regex("""(?:последн[\p{L}]*\s+)?(\d{1,2})\s+команд""")
            .find(c)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 10
        return Intent.History(limit.coerceIn(1, 20))
    }

    private fun parseMemory(c: String, raw: String): Intent? {
        Regex("""^(?:запомни|сохрани в память)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val text = it.groupValues[1].trim()
                if (text.isNotBlank()) return Intent.MemoryRemember(text)
            }
        Regex("""^(?:удали из памяти запись|забудь запись|забудь из памяти)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val q = it.groupValues[1].trim()
                if (q.isNotBlank()) return Intent.MemoryForget(q)
            }
        Regex("""^что ты помнишь про\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val q = it.groupValues[1].trim()
                if (q.isNotBlank()) return Intent.MemoryRecall(q)
            }
        if (c.contains("покажи") && c.contains("памят") && (c.contains("сохран") || c.contains("ayana"))) {
            return Intent.MemoryList(null)
        }
        return null
    }

    private fun parseReminder(c: String, raw: String): Intent? {
        Regex("""^создай\s+напоминание\s+(.+?)\s+через\s+(\d{1,4})\s+минут(?:у|ы)?$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val title = it.groupValues[1].trim()
                val minutes = it.groupValues[2].toIntOrNull()
                if (title.isNotBlank() && minutes != null && minutes > 0) {
                    return Intent.ReminderCreate(title = title, relativeMinutes = minutes)
                }
            }
        Regex("""^создай\s+напоминание\s+(.+?)\s+на\s+завтра\s+в\s+(\d{1,2}):(\d{2})$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val title = it.groupValues[1].trim()
                val hour = it.groupValues[2].toIntOrNull()
                val minute = it.groupValues[3].toIntOrNull()
                if (
                    title.isNotBlank() &&
                    hour != null && hour in 0..23 &&
                    minute != null && minute in 0..59
                ) {
                    return Intent.ReminderCreate(
                        title = title,
                        tomorrowHour = hour,
                        tomorrowMinute = minute
                    )
                }
            }
        Regex("""^(?:удали|удалить)\s+(?:напоминание|задачу)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val q = it.groupValues[1].trim()
                if (q.isNotBlank()) return Intent.ReminderDelete(q)
            }
        Regex("""^покажи\s+(?:задачу|напоминание)\s+(.+)$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val q = it.groupValues[1].trim()
                if (q.isNotBlank()) return Intent.ReminderList(q)
            }
        if (
            c == "покажи мои задачи" || c == "покажи задачи" ||
            c == "покажи напоминания" || c == "покажи мои напоминания"
        ) {
            return Intent.ReminderList(null)
        }
        return null
    }

    private fun parseClipboard(c: String, raw: String): Intent? {
        if (!c.contains("буфер") || !c.contains("копир")) return null
        Regex("""^(?:скопируй|копируй)\s+(.+?)\s+в\s+буфер\s+обмена$""", RegexOption.IGNORE_CASE)
            .find(raw.trim())?.let {
                val text = it.groupValues[1].trim()
                if (text.isNotBlank()) return Intent.ClipboardCopy(text)
            }
        return null
    }

    private fun parseInternalPage(c: String): Intent? {
        if (!c.contains("ayana") || (!c.contains("раздел") && !c.contains("вклад"))) return null
        return when {
            c.contains("памят") -> Intent.InternalPage(Page.MEMORY)
            c.contains("задач") -> Intent.InternalPage(Page.TASKS)
            c.contains("истори") -> Intent.InternalPage(Page.HISTORY)
            c.contains("систем") -> Intent.InternalPage(Page.SYSTEM)
            c.contains("настрой") -> Intent.InternalPage(Page.SETTINGS)
            c.contains("главн") -> Intent.InternalPage(Page.HOME)
            else -> null
        }
    }

    private fun parseBrightnessSet(c: String): Intent? {
        if (!c.contains("ярк")) return null
        if (!(c.contains("установ") || c.contains("постав") || c.contains("выстав") || c.contains("задай") || c.contains("сделай"))) return null
        val p = Regex("""(\d{1,3})\s*(?:%|процент(?:а|ов)?)""")
            .find(c)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return null
        return Intent.BrightnessSet(p)
    }

    private fun parseSystemToggle(c: String): Intent? {
        val enable = when {
            c.startsWith("включ") -> true
            c.startsWith("выключ") -> false
            else -> return null
        }
        return when {
            c.contains("wi-fi") || c.contains("wifi") || c.contains("вай фай") || c.contains("вайфай") -> Intent.SystemToggle(ToggleKind.WIFI, enable)
            c.contains("bluetooth") || c.contains("блютуз") -> Intent.SystemToggle(ToggleKind.BLUETOOTH, enable)
            else -> null
        }
    }

    private fun parseUnknownCapability(c: String): Intent? {
        val m = Regex("""^(?:включи|выключи)\s+функци[\p{L}]*\s+(.+)$""").find(c) ?: return null
        val label = m.groupValues[1].trim()
        return if (label.isBlank()) null else Intent.UnknownCapability(label)
    }

    private fun containsActionVerb(c: String): Boolean =
        ACTION_VERB_STEMS.any { stem ->
            c.split(' ').any { token -> token.startsWith(stem) }
        }

    private fun containsStateChangingAction(c: String): Boolean {
        if (containsActionVerb(c)) return true
        return c.contains("громче") ||
            c.contains("тише") ||
            c.contains("без звука") ||
            c.contains("домой") ||
            c.contains("на главный экран")
    }

    private fun containsEmbeddedActionLanguage(c: String): Boolean =
        containsStateChangingAction(c) ||
            c.contains("нажми ") ||
            c.contains("выбери ") ||
            c.contains("открой ") ||
            c.contains("закрой ")


    private fun isUnsupportedCompositeCommand(c: String): Boolean {
        val sequenced =
            c.contains(" затем ") ||
                c.contains(" потом ") ||
                c.contains(" после этого ") ||
                c.contains(" после чего ") ||
                c.contains("; и ") ||
                c.contains(" и ")
        if (!sequenced) return false

        var families = 0
        if (c.contains("громк") || c.contains("звук")) families++
        if (c.contains("ярк")) families++
        if (c.contains("уведомлен")) families++
        if (c.contains("батаре") || c.contains("заряд")) families++
        if (c.contains("приложен") || c.contains("youtube") || c.contains("ютуб") || c.contains("камер")) families++
        if (c.contains("настрой")) families++
        if (c.contains("памят")) families++
        if (c.contains("напомин") || c.contains("задач")) families++
        if (c.contains("экран") && (c.contains("домой") || c.contains("главн"))) families++
        return families >= 2
    }

    private fun isClearlyLocalRead(c: String): Boolean {
        if (c.contains("громк") && (c.startsWith("какая") || c.startsWith("какой") || c.contains("сейчас громк"))) return true
        if ((c.contains("заряд") || c.contains("батаре")) && (c.startsWith("какой") || c.startsWith("сколько") || c.contains("сейчас"))) return true
        if (c.contains("ярк") && !c.contains("автоматическ") && (c.startsWith("какая") || c.startsWith("какой") || c.contains("сейчас"))) return true
        if (c.contains("свобод") && (c.contains("мест") || c.contains("хранилищ"))) return true
        if (c.contains("модель") && (c.contains("планшет") || c.contains("устройств"))) return true
        if (c.contains("истори") && c.contains("команд") && (c.contains("покажи") || c.contains("последн") || c.contains("прочитай"))) return true
        return false
    }

    private fun isClearlyLocalOwnedAction(c: String): Boolean =
        (c.contains("ярк") && (c.contains("установ") || c.contains("постав") || c.contains("выстав") || c.contains("задай"))) ||
            (c.contains("громк") && (c.contains("увелич") || c.contains("уменьш") || c.contains("установ") || c.contains("постав"))) ||
            c.startsWith("запомни ") ||
            c.startsWith("создай напоминание ")

    private fun normalize(raw: String): String = raw
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("[«»\"]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val ACTION_VERB_STEMS = listOf(
        "открой", "запуст", "включ", "выключ", "закрой", "сверн",
        "установ", "постав", "выстав", "задай", "сделай", "измени",
        "увелич", "уменьш", "прибав", "убав", "нажми", "выбери",
        "удали", "очист", "скопир", "введи", "напиши", "перейди"
    )
}
