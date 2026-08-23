package kg.autonomous.agent

import java.util.Locale

/**
 * AYANA Completion Contract v1.3 — FORMAT TRUTH.
 *
 * A model saying "готово" or merely mentioning a filename is not proof that a
 * requested artifact exists. v1.3 also prevents a real but wrong-type artifact
 * from satisfying a more specific request (for example TXT cannot prove PDF).
 *
 * Textual deliverables may be validated from substantive reply text. File-like
 * deliverables require a structured execution-layer reference. Specific artifact
 * kinds additionally require compatible filename/MIME/declared-kind evidence.
 *
 * This class has no Android dependencies and is intentionally JVM-testable.
 */
class AyanaCompletionContract {

    enum class DeliverableKind {
        ANALYSIS,
        GRAPH,
        TABLE,
        FILE,
        TEXT_FILE,
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

    data class ArtifactEvidence(
        val reference: String,
        val name: String = "",
        val mimeType: String = "",
        val declaredKind: String = ""
    )

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
                " txt ",
                ".txt",
                "txt файл",
                "txt-файл",
                "текстовый файл",
                "plain text file"
            )
        ) {
            expected += DeliverableKind.TEXT_FILE
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
                " picture ",
                " jpeg ",
                " jpg ",
                " png ",
                ".jpeg",
                ".jpg",
                ".png"
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

    /**
     * Backward-compatible entry point for callers that only have references.
     * Such generic evidence can prove FILE, but cannot prove a specific type
     * unless the reference itself carries a recognizable extension.
     */
    fun validate(
        request: String,
        reply: String,
        artifactReferences: List<String> = emptyList()
    ): Validation =
        validateEvidence(
            request = request,
            reply = reply,
            artifactEvidence =
                artifactReferences.map {
                    ArtifactEvidence(
                        reference = it
                    )
                }
        )

    fun validateEvidence(
        request: String,
        reply: String,
        artifactEvidence: List<ArtifactEvidence> = emptyList()
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

        val verifiedEvidence =
            artifactEvidence
                .asSequence()
                .map(::sanitizeEvidence)
                .filter { it.reference.isNotBlank() }
                .filter { isStructuredArtifactReference(it.reference) }
                .distinctBy {
                    listOf(
                        it.reference,
                        it.name,
                        it.mimeType,
                        it.declaredKind
                    ).joinToString("|")
                }
                .take(MAX_ARTIFACT_REFERENCES)
                .toList()

        val verifiedArtifacts =
            verifiedEvidence
                .map { it.reference }
                .distinct()

        val evidenceKinds =
            verifiedEvidence
                .map { evidence ->
                    evidence to classifyArtifact(evidence)
                }

        val hasArtifact =
            verifiedEvidence.isNotEmpty()

        val hasInlineTable =
            looksLikeInlineTable(reply)

        fun hasKind(kind: DeliverableKind): Boolean =
            evidenceKinds.any { (_, kinds) ->
                kind in kinds
            }

        val missing = linkedSetOf<DeliverableKind>()

        for (kind in expected) {
            val satisfied =
                when (kind) {
                    DeliverableKind.ANALYSIS ->
                        substantiveText

                    DeliverableKind.FILE ->
                        hasArtifact

                    DeliverableKind.TEXT_FILE ->
                        hasKind(DeliverableKind.TEXT_FILE)

                    DeliverableKind.DOCUMENT ->
                        hasKind(DeliverableKind.DOCUMENT)

                    DeliverableKind.PDF ->
                        hasKind(DeliverableKind.PDF)

                    DeliverableKind.SPREADSHEET ->
                        hasKind(DeliverableKind.SPREADSHEET)

                    DeliverableKind.PRESENTATION ->
                        hasKind(DeliverableKind.PRESENTATION)

                    DeliverableKind.IMAGE ->
                        hasKind(DeliverableKind.IMAGE)

                    DeliverableKind.GRAPH ->
                        hasKind(DeliverableKind.GRAPH)

                    DeliverableKind.TABLE ->
                        hasInlineTable ||
                            hasKind(DeliverableKind.TABLE) ||
                            hasKind(DeliverableKind.SPREADSHEET)

                    DeliverableKind.REPORT ->
                        substantiveText ||
                            hasKind(DeliverableKind.REPORT) ||
                            hasKind(DeliverableKind.DOCUMENT) ||
                            hasKind(DeliverableKind.PDF)
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

                    missing.any(::requiresTypedArtifactProof) &&
                        hasArtifact ->
                        "requested_artifact_type_not_verified"

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

    private fun sanitizeEvidence(
        evidence: ArtifactEvidence
    ): ArtifactEvidence =
        ArtifactEvidence(
            reference = evidence.reference.trim().take(MAX_ARTIFACT_REFERENCE_CHARS),
            name = evidence.name.trim().take(MAX_ARTIFACT_NAME_CHARS),
            mimeType = evidence.mimeType.trim().lowercase(Locale.ROOT).take(MAX_ARTIFACT_MIME_CHARS),
            declaredKind = evidence.declaredKind.trim().lowercase(Locale.ROOT).take(MAX_DECLARED_KIND_CHARS)
        )

    private fun classifyArtifact(
        evidence: ArtifactEvidence
    ): Set<DeliverableKind> {
        val result = linkedSetOf<DeliverableKind>()
        result += DeliverableKind.FILE

        val extension = artifactExtension(evidence)
        val mime = evidence.mimeType
        val declared = normalizeKindToken(evidence.declaredKind)

        val declaredGraph =
            declared.contains("graph") ||
                declared.contains("chart") ||
                declared.contains("diagram") ||
                declared.contains("график") ||
                declared.contains("диаграм")

        val declaredTable =
            declared.contains("table") ||
                declared.contains("таблиц")

        val declaredReport =
            declared.contains("report") ||
                declared.contains("отчет") ||
                declared.contains("отчёт")

        val declaredTextFile =
            declared == "txt" ||
                declared.contains("text file") ||
                declared.contains("текстовый файл")

        if (
            extension == "txt" ||
            mime == "text/plain" ||
            declaredTextFile
        ) {
            result += DeliverableKind.TEXT_FILE
            result += DeliverableKind.DOCUMENT
        }

        if (
            extension in DOCUMENT_EXTENSIONS ||
            mime in DOCUMENT_MIME_TYPES ||
            declared.contains("document") ||
            declared.contains("документ")
        ) {
            result += DeliverableKind.DOCUMENT
        }

        if (
            extension == "pdf" ||
            mime == "application/pdf" ||
            declared == "pdf" ||
            declared.contains("pdf")
        ) {
            result += DeliverableKind.PDF
            result += DeliverableKind.DOCUMENT
        }

        if (
            extension in SPREADSHEET_EXTENSIONS ||
            mime in SPREADSHEET_MIME_TYPES ||
            declared.contains("spreadsheet") ||
            declared.contains("excel")
        ) {
            result += DeliverableKind.SPREADSHEET
            result += DeliverableKind.TABLE
        }

        if (
            extension in PRESENTATION_EXTENSIONS ||
            mime in PRESENTATION_MIME_TYPES ||
            declared.contains("presentation") ||
            declared.contains("powerpoint") ||
            declared.contains("slide")
        ) {
            result += DeliverableKind.PRESENTATION
        }

        if (
            extension in IMAGE_EXTENSIONS ||
            mime.startsWith("image/") ||
            declared.contains("image") ||
            declared.contains("picture") ||
            declared.contains("изображ")
        ) {
            result += DeliverableKind.IMAGE
        }

        if (declaredGraph) {
            result += DeliverableKind.GRAPH
        }

        if (declaredTable) {
            result += DeliverableKind.TABLE
        }

        if (declaredReport) {
            result += DeliverableKind.REPORT
        }

        return result
    }

    private fun artifactExtension(
        evidence: ArtifactEvidence
    ): String {
        val source =
            evidence.name
                .ifBlank {
                    evidence.reference
                        .substringBefore('?')
                        .substringBefore('#')
                        .substringAfterLast('/')
                }
                .trim()

        val dot = source.lastIndexOf('.')
        if (dot < 0 || dot == source.lastIndex) {
            return ""
        }

        return source
            .substring(dot + 1)
            .lowercase(Locale.ROOT)
            .take(12)
    }

    private fun normalizeKindToken(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun requiresArtifactProof(
        kind: DeliverableKind
    ): Boolean =
        kind in setOf(
            DeliverableKind.GRAPH,
            DeliverableKind.FILE,
            DeliverableKind.TEXT_FILE,
            DeliverableKind.DOCUMENT,
            DeliverableKind.PDF,
            DeliverableKind.SPREADSHEET,
            DeliverableKind.PRESENTATION,
            DeliverableKind.IMAGE
        )

    private fun requiresTypedArtifactProof(
        kind: DeliverableKind
    ): Boolean =
        kind in setOf(
            DeliverableKind.GRAPH,
            DeliverableKind.TEXT_FILE,
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
                    DeliverableKind.TEXT_FILE -> "TXT-файл"
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
                "AYANA не отмечает такую команду как SUCCESS без фактически подтверждённого результата нужного типа."
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
        private const val MAX_ARTIFACT_NAME_CHARS = 512
        private const val MAX_ARTIFACT_MIME_CHARS = 160
        private const val MAX_DECLARED_KIND_CHARS = 160

        private val DOCUMENT_EXTENSIONS =
            setOf(
                "doc",
                "docx",
                "odt",
                "rtf",
                "txt",
                "md",
                "pdf"
            )

        private val SPREADSHEET_EXTENSIONS =
            setOf(
                "xls",
                "xlsx",
                "xlsm",
                "csv",
                "ods"
            )

        private val PRESENTATION_EXTENSIONS =
            setOf(
                "ppt",
                "pptx",
                "odp"
            )

        private val IMAGE_EXTENSIONS =
            setOf(
                "png",
                "jpg",
                "jpeg",
                "webp",
                "gif",
                "bmp",
                "svg"
            )

        private val DOCUMENT_MIME_TYPES =
            setOf(
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.oasis.opendocument.text",
                "application/rtf",
                "text/rtf",
                "text/plain",
                "text/markdown",
                "application/pdf"
            )

        private val SPREADSHEET_MIME_TYPES =
            setOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.oasis.opendocument.spreadsheet",
                "text/csv"
            )

        private val PRESENTATION_MIME_TYPES =
            setOf(
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.oasis.opendocument.presentation"
            )
    }
}
