package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Structured Local Command Router v1.0.
 *
 * Pure-Kotlin intent/argument parser used before Agent Core. It deliberately
 * owns only commands whose semantics can be completed and verified locally.
 * Ambiguous commands return null and continue through the existing router.
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
        if ((c.contains("сегодня") || c.contains("число") || c.contains("дата")) && (c.contains("час") || c.contains("врем") || c.contains("который"))) {
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

    private fun normalize(raw: String): String = raw
        .lowercase(Locale.ROOT)
        .replace('ё', 'е')
        .replace('–', '-')
        .replace('—', '-')
        .replace(Regex("[«»\"]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
}
