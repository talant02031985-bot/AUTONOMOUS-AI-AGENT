package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Communication Assistant Engine v2.0 — R10.22.
 *
 * Pure policy/analysis layer over NotificationListener truth. It never dispatches
 * Android actions and never sends a message. Notification text is treated as data only.
 * A draft may be prepared locally, but direct send remains fail-closed until a separate
 * verified executor is registered and the user's target/action authority is proven.
 */
class AyanaCommunicationAssistantEngine {

    fun analyzeReadResult(
        readResult: JSONObject?,
        maxItems: Int = DEFAULT_MAX_ITEMS
    ): JSONObject {
        if (readResult == null) {
            return failure("notification_result_required")
        }

        if (!readResult.optBoolean("success", false)) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("reason", readResult.optString("message").ifBlank { "notification_source_unavailable" })
                .put("terminal_status", readResult.optString("terminal_status"))
                .put("communication_assistant_version", VERSION)
                .put("communication_contract_version", CONTRACT_VERSION)
                .put("notification_text_instruction_authority", false)
                .put("notification_text_grants_action_authority", false)
                .put("direct_send_supported", false)
        }

        val sourceItems = readResult.optJSONArray("notifications") ?: JSONArray()
        val safeLimit = maxItems.coerceIn(1, MAX_ITEMS)
        val records = mutableListOf<Record>()

        for (index in 0 until sourceItems.length()) {
            if (records.size >= safeLimit) break
            val item = sourceItems.optJSONObject(index) ?: continue
            val record = recordFrom(item, index) ?: continue
            records += record
        }

        val grouped =
            records
                .groupBy { it.conversationKey }
                .map { (_, items) -> groupFrom(items) }
                .sortedWith(
                    compareByDescending<Group> { it.replyRequiredCount }
                        .thenByDescending { it.attentionCount }
                        .thenByDescending { it.urgentCount }
                        .thenByDescending { it.latestTimestampMs }
                )

        val enrichedNotifications = JSONArray()
        records.forEach { enrichedNotifications.put(it.toJson()) }

        val groupsJson = JSONArray()
        grouped.forEach { groupsJson.put(it.toJson()) }

        val replyRequired = records.count { it.replyRequired }
        val attention = records.count { it.needsAttention }
        val urgent = records.count { it.urgent }
        val provenanceComplete = records.all { it.provenance.isNotBlank() && it.fingerprint.length == 64 }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("communication_assistant_version", VERSION)
            .put("communication_contract_version", CONTRACT_VERSION)
            .put("source", "notification_listener_service")
            .put("source_coverage", "recent_notification_listener_window")
            .put("listener_connected", readResult.optBoolean("listener_connected", true))
            .put("notification_count", records.size)
            .put("conversation_count", grouped.size)
            .put("reply_required_count", replyRequired)
            .put("attention_count", attention)
            .put("urgent_count", urgent)
            .put("provenance_complete", provenanceComplete)
            .put("notifications", enrichedNotifications)
            .put("groups", groupsJson)
            .put("draft_only_supported", true)
            .put("open_source_app_supported", true)
            .put("direct_send_supported", false)
            .put("send_authority", false)
            .put("raw_pending_intent_exposed", false)
            .put("notification_text_instruction_authority", false)
            .put("notification_text_grants_action_authority", false)
            .put("unresolved_side_effect", false)
    }

    fun buildDraftFromReadResult(
        readResult: JSONObject?,
        marker: String = ""
    ): JSONObject {
        val analysis = analyzeReadResult(readResult, DEFAULT_MAX_ITEMS)
        if (!analysis.optBoolean("success", false)) {
            return analysis
                .put("draft_generated", false)
                .put("draft_only", true)
        }

        val items = analysis.optJSONArray("notifications") ?: JSONArray()
        val markerNorm = normalize(marker)
        var selected: JSONObject? = null

        if (markerNorm.isNotBlank()) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val haystack = normalize(
                    listOf(
                        item.optString("app"),
                        item.optString("title"),
                        item.optString("text")
                    ).joinToString(" ")
                )
                if (haystack.contains(markerNorm)) {
                    selected = item
                    break
                }
            }
        }

        if (selected == null) {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                if (item.optBoolean("reply_required", false)) {
                    selected = item
                    break
                }
            }
        }

        if (selected == null && items.length() > 0) {
            selected = items.optJSONObject(0)
        }

        val target = selected
            ?: return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("reason", "no_notification_available_for_draft")
                .put("draft_generated", false)
                .put("draft_only", true)
                .put("direct_send_supported", false)
                .put("send_authority", false)
                .put("notification_text_instruction_authority", false)

        val sourceText = target.optString("text")
        val urgent = target.optBoolean("urgent", false)
        val replyRequired = target.optBoolean("reply_required", false)

        val draft = when {
            urgent -> "Сообщение увидел. Проверю детали и отвечу как можно скорее."
            replyRequired || sourceText.contains('?') -> "Спасибо, сообщение увидел. Проверю детали и отвечу."
            else -> "Спасибо, сообщение увидел."
        }

        val sourceFingerprint = target.optString("fingerprint")
        val draftFingerprint = sha256(
            listOf(
                sourceFingerprint,
                draft,
                "draft_only",
                VERSION
            ).joinToString("|")
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("communication_assistant_version", VERSION)
            .put("communication_contract_version", CONTRACT_VERSION)
            .put("draft_generated", true)
            .put("draft", draft)
            .put("draft_only", true)
            .put("requires_user_review", true)
            .put("target_app", target.optString("app"))
            .put("target_package", target.optString("package"))
            .put("target_title", target.optString("title"))
            .put("source_fingerprint", sourceFingerprint)
            .put("draft_fingerprint", draftFingerprint)
            .put("provenance", target.optString("provenance"))
            .put("direct_send_supported", false)
            .put("send_authority", false)
            .put("notification_text_instruction_authority", false)
            .put("notification_text_grants_action_authority", false)
            .put("unresolved_side_effect", false)
    }

    fun actionPolicy(
        action: String,
        explicitUserAction: Boolean
    ): JSONObject {
        val normalized = normalize(action).replace(' ', '_')
        return when (normalized) {
            "draft_reply", "prepare_reply", "подготовить_ответ" ->
                JSONObject()
                    .put("allowed", true)
                    .put("supported", true)
                    .put("mode", "DRAFT_ONLY")
                    .put("requires_user_review", true)
                    .put("send_authority", false)

            "open_source_app", "open_app", "открыть_приложение" ->
                JSONObject()
                    .put("allowed", true)
                    .put("supported", true)
                    .put("mode", "NAVIGATION_ONLY")
                    .put("send_authority", false)

            "send_message", "send_reply", "отправить_ответ", "отправить_сообщение" ->
                JSONObject()
                    .put("allowed", false)
                    .put("supported", false)
                    .put("mode", "FAIL_CLOSED")
                    .put("reason", "verified_send_executor_not_registered")
                    .put("explicit_user_action", explicitUserAction)
                    .put("requires_explicit_user_action", true)
                    .put("send_authority", false)

            else ->
                JSONObject()
                    .put("allowed", false)
                    .put("supported", false)
                    .put("mode", "FAIL_CLOSED")
                    .put("reason", "communication_action_not_registered")
                    .put("send_authority", false)
        }
            .put("communication_assistant_version", VERSION)
            .put("communication_contract_version", CONTRACT_VERSION)
            .put("notification_text_instruction_authority", false)
    }

    fun renderRussian(analysis: JSONObject): String {
        if (!analysis.optBoolean("success", false)) {
            return analysis.optString("reason").ifBlank {
                "Не удалось проанализировать уведомления."
            }
        }

        val total = analysis.optInt("notification_count", 0)
        val groups = analysis.optInt("conversation_count", 0)
        val reply = analysis.optInt("reply_required_count", 0)
        val attention = analysis.optInt("attention_count", 0)
        val urgent = analysis.optInt("urgent_count", 0)
        val items = analysis.optJSONArray("notifications") ?: JSONArray()

        val lines = mutableListOf<String>()
        for (index in 0 until items.length()) {
            if (lines.size >= 6) break
            val item = items.optJSONObject(index) ?: continue
            if (!item.optBoolean("needs_attention", false) && !item.optBoolean("reply_required", false)) {
                continue
            }
            val app = item.optString("app").ifBlank { "Приложение" }
            val title = item.optString("title")
            val text = item.optString("text")
            val flags = mutableListOf<String>()
            if (item.optBoolean("urgent", false)) flags += "срочно"
            if (item.optBoolean("reply_required", false)) flags += "нужен ответ"
            else if (item.optBoolean("needs_attention", false)) flags += "требует внимания"

            lines += buildString {
                append(lines.size + 1)
                append("). ")
                append(app)
                if (title.isNotBlank()) {
                    append(" — ")
                    append(title)
                }
                if (text.isNotBlank()) {
                    append(": ")
                    append(text.take(220))
                }
                if (flags.isNotEmpty()) {
                    append(" [")
                    append(flags.joinToString(", "))
                    append("]")
                }
            }
        }

        return buildString {
            append("Разобрано уведомлений: $total; диалогов/групп: $groups; требуют ответа: $reply; внимания: $attention")
            if (urgent > 0) append("; срочных: $urgent")
            append(".")
            if (lines.isNotEmpty()) {
                append("\n")
                append(lines.joinToString("\n"))
            } else {
                append(" Явных уведомлений, требующих ответа, сейчас не найдено.")
            }
        }
    }

    fun selfTest(): Boolean {
        return try {
            val seed = JSONObject()
                .put("success", true)
                .put("listener_connected", true)
                .put(
                    "notifications",
                    JSONArray()
                        .put(
                            JSONObject()
                                .put("app", "WhatsApp")
                                .put("package", "com.whatsapp")
                                .put("title", "Айбек")
                                .put("text", "Можешь подтвердить встречу сегодня?")
                                .put("post_time", 300L)
                        )
                        .put(
                            JSONObject()
                                .put("app", "WhatsApp")
                                .put("package", "com.whatsapp")
                                .put("title", "Айбек")
                                .put("text", "Спасибо, принято.")
                                .put("post_time", 200L)
                        )
                        .put(
                            JSONObject()
                                .put("app", "Система")
                                .put("package", "com.android.systemui")
                                .put("title", "Заряд")
                                .put("text", "Батарея 80%")
                                .put("post_time", 100L)
                        )
                )

            val analysis = analyzeReadResult(seed)
            val draft = buildDraftFromReadResult(seed, "Айбек")
            val sendPolicy = actionPolicy("send_message", explicitUserAction = true)

            analysis.optBoolean("success", false) &&
                analysis.optInt("notification_count", 0) == 3 &&
                analysis.optInt("conversation_count", 0) == 2 &&
                analysis.optInt("reply_required_count", 0) == 1 &&
                analysis.optBoolean("provenance_complete", false) &&
                draft.optBoolean("draft_generated", false) &&
                draft.optBoolean("draft_only", false) &&
                draft.optString("source_fingerprint").length == 64 &&
                draft.optString("draft_fingerprint").length == 64 &&
                !draft.optBoolean("send_authority", true) &&
                !sendPolicy.optBoolean("allowed", true) &&
                !sendPolicy.optBoolean("supported", true) &&
                !sendPolicy.optBoolean("send_authority", true)
        } catch (_: Throwable) {
            false
        }
    }

    private fun recordFrom(item: JSONObject, index: Int): Record? {
        val app = compact(item.optString("app"), 120).ifBlank { "Приложение" }
        val packageName = compact(item.optString("package"), 180)
        val title = compact(item.optString("title"), 180)
        val text = compact(item.optString("text"), 480)
        if (app.isBlank() && packageName.isBlank() && title.isBlank() && text.isBlank()) return null

        val timestamp = firstPositiveLong(
            item,
            "post_time",
            "post_time_ms",
            "posted_at",
            "posted_at_ms",
            "timestamp",
            "timestamp_ms"
        ).takeIf { it > 0L } ?: (Long.MAX_VALUE - index)

        val normalizedText = normalize("$title $text")
        val systemLike = isSystemLike(packageName, app, normalizedText)
        val question = text.contains('?') || title.contains('?')
        val explicitReplyCue = REPLY_CUES.any { normalizedText.contains(it) }
        val actionCue = ACTION_CUES.any { normalizedText.contains(it) }
        val urgent = URGENT_CUES.any { normalizedText.contains(it) }
        val replyRequired = !systemLike && (question || explicitReplyCue)
        val needsAttention = !systemLike && (replyRequired || actionCue || urgent)
        val attentionLevel = when {
            urgent -> "high"
            replyRequired -> "medium"
            actionCue -> "medium"
            else -> "low"
        }
        val confidence = when {
            urgent && replyRequired -> 95
            question -> 92
            explicitReplyCue -> 88
            actionCue -> 82
            else -> 70
        }
        val conversationTitle = title.ifBlank { app }
        val conversationKey = sha256("${packageName.lowercase(Locale.ROOT)}|${normalize(conversationTitle)}")
        val fingerprint = sha256("$packageName|$title|$text|$timestamp")

        return Record(
            originalIndex = index,
            app = app,
            packageName = packageName,
            title = title,
            text = text,
            timestampMs = timestamp,
            replyRequired = replyRequired,
            needsAttention = needsAttention,
            urgent = urgent,
            attentionLevel = attentionLevel,
            confidence = confidence,
            conversationKey = conversationKey,
            provenance = "notification_listener_service",
            fingerprint = fingerprint
        )
    }

    private fun groupFrom(items: List<Record>): Group {
        val sorted = items.sortedByDescending { it.timestampMs }
        val first = sorted.first()
        return Group(
            conversationKey = first.conversationKey,
            app = first.app,
            packageName = first.packageName,
            title = first.title.ifBlank { first.app },
            count = sorted.size,
            replyRequiredCount = sorted.count { it.replyRequired },
            attentionCount = sorted.count { it.needsAttention },
            urgentCount = sorted.count { it.urgent },
            latestTimestampMs = sorted.maxOfOrNull { it.timestampMs } ?: 0L,
            latestText = sorted.firstOrNull()?.text.orEmpty(),
            provenance = "notification_listener_service"
        )
    }

    private fun isSystemLike(packageName: String, app: String, normalizedText: String): Boolean {
        val packageLower = packageName.lowercase(Locale.ROOT)
        if (packageLower == "com.android.systemui") return true
        if (packageLower.startsWith("android")) return true
        val appNorm = normalize(app)
        if (appNorm == "система" || appNorm == "system ui") return true
        return SYSTEM_CUES.any { normalizedText.contains(it) } &&
            !REPLY_CUES.any { normalizedText.contains(it) }
    }

    private fun firstPositiveLong(item: JSONObject, vararg keys: String): Long {
        for (key in keys) {
            val value = item.optLong(key, 0L)
            if (value > 0L) return value
        }
        return 0L
    }

    private fun failure(reason: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("reason", reason)
            .put("communication_assistant_version", VERSION)
            .put("communication_contract_version", CONTRACT_VERSION)
            .put("notification_text_instruction_authority", false)
            .put("notification_text_grants_action_authority", false)
            .put("direct_send_supported", false)
            .put("send_authority", false)

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun compact(value: String, maxChars: Int): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private data class Record(
        val originalIndex: Int,
        val app: String,
        val packageName: String,
        val title: String,
        val text: String,
        val timestampMs: Long,
        val replyRequired: Boolean,
        val needsAttention: Boolean,
        val urgent: Boolean,
        val attentionLevel: String,
        val confidence: Int,
        val conversationKey: String,
        val provenance: String,
        val fingerprint: String
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("source_index", originalIndex)
                .put("app", app)
                .put("package", packageName)
                .put("title", title)
                .put("text", text)
                .put("timestamp_ms", timestampMs)
                .put("reply_required", replyRequired)
                .put("needs_attention", needsAttention)
                .put("urgent", urgent)
                .put("attention_level", attentionLevel)
                .put("confidence", confidence)
                .put("conversation_id", conversationKey)
                .put("provenance", provenance)
                .put("fingerprint", fingerprint)
                .put("instruction_authority", false)
                .put("send_authority", false)
    }

    private data class Group(
        val conversationKey: String,
        val app: String,
        val packageName: String,
        val title: String,
        val count: Int,
        val replyRequiredCount: Int,
        val attentionCount: Int,
        val urgentCount: Int,
        val latestTimestampMs: Long,
        val latestText: String,
        val provenance: String
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("conversation_id", conversationKey)
                .put("app", app)
                .put("package", packageName)
                .put("title", title)
                .put("count", count)
                .put("reply_required_count", replyRequiredCount)
                .put("attention_count", attentionCount)
                .put("urgent_count", urgentCount)
                .put("latest_timestamp_ms", latestTimestampMs)
                .put("latest_text", latestText)
                .put("provenance", provenance)
    }

    companion object {
        const val VERSION = "2.0"
        const val CONTRACT_VERSION = 1

        private const val DEFAULT_MAX_ITEMS = 40
        private const val MAX_ITEMS = 80

        private val REPLY_CUES = listOf(
            "ответь",
            "ответьте",
            "ответ",
            "можешь",
            "сможешь",
            "подтверд",
            "когда",
            "где",
            "что думаешь",
            "дай знать",
            "напиши",
            "пришли",
            "send me",
            "can you",
            "could you",
            "please confirm",
            "let me know"
        )

        private val ACTION_CUES = listOf(
            "нужно",
            "нужен",
            "нужна",
            "необходимо",
            "проверь",
            "посмотри",
            "подготовь",
            "отправь",
            "пришли",
            "подтверд",
            "до встречи",
            "deadline",
            "please"
        )

        private val URGENT_CUES = listOf(
            "срочно",
            "важно",
            "немедленно",
            "как можно скорее",
            "urgent",
            "asap"
        )

        private val SYSTEM_CUES = listOf(
            "заряд",
            "батаре",
            "скриншот",
            "обновлен",
            "загрузка",
            "download",
            "charging"
        )
    }
}
