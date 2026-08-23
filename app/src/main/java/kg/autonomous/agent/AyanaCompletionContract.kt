package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Completion Contract v1.1 — artifact truth + fail-closed deliverables.
 *
 * A model saying "готово" or merely mentioning a filename is not proof that a
 * requested artifact exists. Textual deliverables may be validated from the
 * substantive reply, while file-like deliverables require a structured artifact
 * reference supplied by the execution layer.
 *
 * This class has no Android dependencies and is intentionally JVM-testable.
 */
class AyanaCompletionContract {

    enum class DeliverableKind {
        ANALYSIS,
        GRAPH,
        TABLE,
        FILE,
        DOCUMENT,
        PDF,
        SPREADSHEET,
        PRESENTATION,
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
        val missing: Set<DeliverableKind>,
        val verifiedArtifactReferences: List<String> = emptyList()
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
                "odt",
                "rtf",
                " document "
            )
        ) {
            expected += DeliverableKind.DOCUMENT
        }

        if (
            containsAny(
                text,
                " pdf ",
                ".pdf",
                "пдф"
            )
        ) {
            expected += DeliverableKind.PDF
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
                "презентац",
                "powerpoint",
                "pptx",
                "слайды",
                "slide deck",
                "presentation"
            )
        ) {
            expected += DeliverableKind.PRESENTATION
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

        val verifiedArtifacts =
            artifactReferences
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .filter(::isStructuredArtifactReference)
                .distinct()
                .take(MAX_ARTIFACT_REFERENCES)
                .toList()

        val hasArtifact =
            verifiedArtifacts.isNotEmpty()

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
                    DeliverableKind.PDF,
                    DeliverableKind.SPREADSHEET,
                    DeliverableKind.PRESENTATION,
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
                missing = emptySet(),
                verifiedArtifactReferences = verifiedArtifacts
            )
        }

        return Validation(
            satisfied = false,
            reason =
                when {
                    genericOnly ->
                        "generic_final_without_deliverable"

                    missing.any(::requiresArtifactProof) ->
                        "requested_artifact_not_verified"

                    else ->
                        "requested_deliverable_missing"
                },
            message = buildFailureMessage(missing),
            expected = expected,
            missing = missing,
            verifiedArtifactReferences = verifiedArtifacts
        )
    }

    private fun requiresArtifactProof(
        kind: DeliverableKind
    ): Boolean =
        kind in setOf(
            DeliverableKind.GRAPH,
            DeliverableKind.FILE,
            DeliverableKind.DOCUMENT,
            DeliverableKind.PDF,
            DeliverableKind.SPREADSHEET,
            DeliverableKind.PRESENTATION,
            DeliverableKind.IMAGE
        )

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
                    DeliverableKind.PDF -> "PDF-файл"
                    DeliverableKind.SPREADSHEET -> "таблица Excel"
                    DeliverableKind.PRESENTATION -> "презентация"
                    DeliverableKind.IMAGE -> "изображение"
                    DeliverableKind.REPORT -> "отчёт"
                }
            }

        val artifactMissing =
            missing.any(::requiresArtifactProof)

        return if (artifactMissing) {
            "Запрос выполнен не полностью: не подтверждено создание ${names.joinToString(", ")}. " +
                "AYANA не отмечает такую команду как SUCCESS без фактической ссылки на созданный результат."
        } else {
            "Запрос выполнен не полностью: отсутствует ${names.joinToString(", ")}. " +
                "AYANA не отмечает такую команду как SUCCESS."
        }
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

    /**
     * Only explicit execution-layer references are accepted. A filename typed in
     * natural-language text (for example "report.pdf") is deliberately NOT proof.
     */
    private fun isStructuredArtifactReference(
        reference: String
    ): Boolean {
        val lower =
            reference
                .trim()
                .lowercase(Locale.ROOT)

        if (lower.length !in 4..MAX_ARTIFACT_REFERENCE_CHARS) {
            return false
        }

        return lower.startsWith("sandbox:/") ||
            lower.startsWith("content://") ||
            lower.startsWith("file://") ||
            lower.startsWith("artifact://") ||
            lower.startsWith("attachment://") ||
            lower.startsWith("openai-file://")
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

        return lines.count {
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
        private const val MAX_ARTIFACT_REFERENCES = 24
        private const val MAX_ARTIFACT_REFERENCE_CHARS = 2048
    }
}
