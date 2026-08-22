package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Completion Contract v1.0 — fail-closed deliverable integrity.
 *
 * Agent Core "final" is not proof that every requested output exists.
 * This validator derives explicit expected outputs from the user's request and
 * blocks SUCCESS when the final reply does not contain the requested result.
 *
 * The class has no Android dependencies and is intentionally JVM-testable.
 */
class AyanaCompletionContract {

    enum class DeliverableKind {
        ANALYSIS,
        GRAPH,
        TABLE,
        FILE,
        DOCUMENT,
        SPREADSHEET,
        IMAGE,
        REPORT
    }

    data class ExpectedOutputs(
        val kinds: Set<DeliverableKind>
    ) {
        val requiresValidation: Boolean
            get() = kinds.isNotEmpty()
    }

    data class Validation(
        val satisfied: Boolean,
        val reason: String,
        val message: String,
        val expected: Set<DeliverableKind>,
        val missing: Set<DeliverableKind>
    )

    fun inspectRequest(request: String): ExpectedOutputs {
        val text = normalize(request)
        val expected = linkedSetOf<DeliverableKind>()

        if (
            containsAny(
                text,
                "анализ",
                "проанализ",
                "разбери",
                "разбор",
                "analysis",
                "analyze",
                "analyse"
            )
        ) {
            expected += DeliverableKind.ANALYSIS
        }

        if (
            containsAny(
                text,
                "график",
                "диаграмм",
                "chart",
                "graph"
            )
        ) {
            expected += DeliverableKind.GRAPH
        }

        if (
            containsAny(
                text,
                "таблиц",
                " table ",
                "таблица"
            )
        ) {
            expected += DeliverableKind.TABLE
        }

        if (
            containsAny(
                text,
                "файл",
                " file ",
                "скачать файл"
            )
        ) {
            expected += DeliverableKind.FILE
        }

        if (
            containsAny(
                text,
                "документ",
                "docx",
                "word",
                " document "
            )
        ) {
            expected += DeliverableKind.DOCUMENT
        }

        if (
            containsAny(
                text,
                "excel",
                "xlsx",
                "spreadsheet",
                "электронн",
                "табличный файл"
            )
        ) {
            expected += DeliverableKind.SPREADSHEET
        }

        if (
            containsAny(
                text,
                "изображен",
                "картинк",
                "рисунок",
                " image ",
                " picture "
            )
        ) {
            expected += DeliverableKind.IMAGE
        }

        if (
            containsAny(
                text,
                "отчет",
                "отчёт",
                " report "
            )
        ) {
            expected += DeliverableKind.REPORT
        }

        return ExpectedOutputs(expected)
    }

    fun validate(
        request: String,
        reply: String,
        artifactReferences: List<String> = emptyList()
    ): Validation {
        val expected = inspectRequest(request).kinds

        if (expected.isEmpty()) {
            return Validation(
                satisfied = true,
                reason = "no_explicit_deliverable",
                message = reply,
                expected = emptySet(),
                missing = emptySet()
            )
        }

        val normalizedReply = normalize(reply)
        val genericOnly = isGenericCompletion(normalizedReply)
        val substantiveText =
            !genericOnly &&
                normalizedReply.length >= MIN_SUBSTANTIVE_TEXT_CHARS

        val validArtifacts =
            artifactReferences
                .map { it.trim() }
                .filter { it.isNotBlank() }

        val hasArtifact =
            validArtifacts.isNotEmpty() ||
                containsArtifactReference(reply)

        val hasInlineTable =
            looksLikeInlineTable(reply)

        val missing = linkedSetOf<DeliverableKind>()

        for (kind in expected) {
            val satisfied =
                when (kind) {
                    DeliverableKind.ANALYSIS ->
                        substantiveText

                    DeliverableKind.GRAPH,
                    DeliverableKind.FILE,
                    DeliverableKind.DOCUMENT,
                    DeliverableKind.SPREADSHEET,
                    DeliverableKind.IMAGE ->
                        hasArtifact

                    DeliverableKind.TABLE ->
                        hasInlineTable ||
                            hasArtifact

                    DeliverableKind.REPORT ->
                        substantiveText ||
                            hasArtifact
                }

            if (!satisfied) {
                missing += kind
            }
        }

        if (missing.isEmpty()) {
            return Validation(
                satisfied = true,
                reason = "deliverables_verified",
                message = reply,
                expected = expected,
                missing = emptySet()
            )
        }

        return Validation(
            satisfied = false,
            reason =
                if (genericOnly) {
                    "generic_final_without_deliverable"
                } else {
                    "requested_deliverable_missing"
                },
            message = buildFailureMessage(missing),
            expected = expected,
            missing = missing
        )
    }

    private fun buildFailureMessage(
        missing: Set<DeliverableKind>
    ): String {
        val names =
            missing.map {
                when (it) {
                    DeliverableKind.ANALYSIS -> "анализ"
                    DeliverableKind.GRAPH -> "график"
                    DeliverableKind.TABLE -> "таблица"
                    DeliverableKind.FILE -> "файл"
                    DeliverableKind.DOCUMENT -> "документ"
                    DeliverableKind.SPREADSHEET -> "таблица Excel"
                    DeliverableKind.IMAGE -> "изображение"
                    DeliverableKind.REPORT -> "отчёт"
                }
            }

        return "Запрос выполнен не полностью: отсутствует ${names.joinToString(", ")}. " +
            "AYANA не отмечает такую команду как SUCCESS."
    }

    private fun isGenericCompletion(
        normalizedReply: String
    ): Boolean {
        if (normalizedReply.isBlank()) {
            return true
        }

        val compact =
            normalizedReply
                .trim()
                .trim('.', '!', '?', ' ')
                .replace(Regex("\\s+"), " ")

        return compact in
            setOf(
                "готово",
                "сделано",
                "выполнено",
                "готов",
                "done",
                "completed",
                "complete"
            )
    }

    private fun containsArtifactReference(
        reply: String
    ): Boolean {
        val lower =
            reply.lowercase(Locale.ROOT)

        return listOf(
            "sandbox:/",
            "content://",
            "file://",
            ".png",
            ".jpg",
            ".jpeg",
            ".webp",
            ".pdf",
            ".docx",
            ".xlsx",
            ".csv"
        ).any {
            lower.contains(it)
        }
    }

    private fun looksLikeInlineTable(
        reply: String
    ): Boolean {
        val lines =
            reply.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }

        if (lines.size < 2) {
            return false
        }

        val pipeRows =
            lines.count {
                it.count { ch -> ch == '|' } >= 2
            }

        if (pipeRows >= 2) {
            return true
        }

        return lines.any {
            it.contains('\t')
        } &&
            lines.count {
                it.contains('\t')
            } >= 2
    }

    private fun containsAny(
        text: String,
        vararg needles: String
    ): Boolean =
        needles.any {
            text.contains(it)
        }

    private fun normalize(
        value: String
    ): String =
        " " +
            value
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("\\s+"), " ")
                .trim() +
            " "

    companion object {
        private const val MIN_SUBSTANTIVE_TEXT_CHARS = 80
    }
}
