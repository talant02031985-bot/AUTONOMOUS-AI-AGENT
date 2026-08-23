package kg.autonomous.agent

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * AYANA DOCX Translation Engine v1.0 â€” STYLE-PRESERVING OOXML TRANSFORM.
 *
 * Architecture / safety contract:
 * - never reconstructs a Word document from plain text;
 * - never extracts ZIP entries to arbitrary filesystem paths;
 * - reads the staged DOCX from AYANA private cache only (caller validates path);
 * - preserves every OOXML package entry that is not an explicitly translatable
 *   Word text part;
 * - inside translatable parts, changes ONLY the text content of <w:t> nodes;
 * - paragraph/run styles, tables, headers/footers, drawings, images, page setup,
 *   relationships and embedded assets therefore remain in the original package;
 * - validates the source as a real DOCX package and caps inflated bytes to reduce
 *   ZIP-bomb risk;
 * - every translated segment is keyed by a deterministic id; missing or duplicate
 *   translations fail closed instead of silently producing a partial document.
 *
 * NOTE: preserving the same <w:t> node boundaries keeps run-level formatting.
 * Translation quality is owned by the remote translation batch endpoint, which
 * receives neighboring segments in document order and MUST return the same ids.
 */
class AyanaDocxTranslationEngine {

    data class Segment(
        val id: String,
        val partName: String,
        val nodeIndex: Int,
        val text: String,
        val leadingWhitespace: String,
        val trailingWhitespace: String
    ) {
        val translatableText: String
            get() = text
                .removePrefix(leadingWhitespace)
                .removeSuffix(trailingWhitespace)
    }

    data class Inspection(
        val valid: Boolean,
        val reason: String,
        val segments: List<Segment>,
        val inflatedBytes: Long,
        val sourceEntries: Int
    )

    data class TransformResult(
        val success: Boolean,
        val reason: String,
        val replacedSegments: Int,
        val outputBytes: Long
    )

    fun inspect(sourceFile: File): Inspection {
        if (!sourceFile.isFile || sourceFile.length() <= 0L) {
            return Inspection(
                valid = false,
                reason = "source_missing",
                segments = emptyList(),
                inflatedBytes = 0L,
                sourceEntries = 0
            )
        }

        if (sourceFile.length() > MAX_COMPRESSED_DOCX_BYTES) {
            return Inspection(
                valid = false,
                reason = "source_too_large",
                segments = emptyList(),
                inflatedBytes = 0L,
                sourceEntries = 0
            )
        }

        val segments = ArrayList<Segment>()
        var inflatedBytes = 0L
        var entryCount = 0
        var hasContentTypes = false
        var hasRootRels = false
        var hasDocument = false

        try {
            ZipInputStream(
                BufferedInputStream(
                    FileInputStream(sourceFile)
                )
            ).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entryCount > MAX_ZIP_ENTRIES) {
                        return Inspection(
                            valid = false,
                            reason = "too_many_zip_entries",
                            segments = emptyList(),
                            inflatedBytes = inflatedBytes,
                            sourceEntries = entryCount
                        )
                    }

                    val name = normalizeEntryName(entry.name)
                        ?: return Inspection(
                            valid = false,
                            reason = "unsafe_zip_entry",
                            segments = emptyList(),
                            inflatedBytes = inflatedBytes,
                            sourceEntries = entryCount
                        )

                    if (entry.isDirectory) {
                        zip.closeEntry()
                        continue
                    }

                    val bytes = readEntryBytes(
                        zip = zip,
                        maxEntryBytes = maxEntryBytes(name)
                    ) ?: return Inspection(
                        valid = false,
                        reason = "inflated_entry_too_large",
                        segments = emptyList(),
                        inflatedBytes = inflatedBytes,
                        sourceEntries = entryCount
                    )

                    inflatedBytes += bytes.size.toLong()
                    if (inflatedBytes > MAX_TOTAL_INFLATED_BYTES) {
                        return Inspection(
                            valid = false,
                            reason = "inflated_package_too_large",
                            segments = emptyList(),
                            inflatedBytes = inflatedBytes,
                            sourceEntries = entryCount
                        )
                    }

                    when (name) {
                        "[Content_Types].xml" -> hasContentTypes = true
                        "_rels/.rels" -> hasRootRels = true
                        "word/document.xml" -> hasDocument = true
                    }

                    if (isTranslatablePart(name)) {
                        val xml = bytes.toString(Charsets.UTF_8)
                        collectTextSegments(
                            partName = name,
                            xml = xml,
                            destination = segments
                        )
                        if (segments.size > MAX_TEXT_SEGMENTS) {
                            return Inspection(
                                valid = false,
                                reason = "too_many_text_segments",
                                segments = emptyList(),
                                inflatedBytes = inflatedBytes,
                                sourceEntries = entryCount
                            )
                        }
                    }

                    zip.closeEntry()
                }
            }
        } catch (_: Exception) {
            return Inspection(
                valid = false,
                reason = "invalid_docx_zip",
                segments = emptyList(),
                inflatedBytes = inflatedBytes,
                sourceEntries = entryCount
            )
        }

        if (!hasContentTypes || !hasRootRels || !hasDocument) {
            return Inspection(
                valid = false,
                reason = "missing_docx_core_parts",
                segments = emptyList(),
                inflatedBytes = inflatedBytes,
                sourceEntries = entryCount
            )
        }

        if (segments.isEmpty()) {
            return Inspection(
                valid = false,
                reason = "docx_has_no_translatable_text",
                segments = emptyList(),
                inflatedBytes = inflatedBytes,
                sourceEntries = entryCount
            )
        }

        val totalChars = segments.fold(0L) { acc, item ->
            acc + item.translatableText.length.toLong()
        }
        if (totalChars <= 0) {
            return Inspection(
                valid = false,
                reason = "docx_has_no_translatable_text",
                segments = emptyList(),
                inflatedBytes = inflatedBytes,
                sourceEntries = entryCount
            )
        }
        if (totalChars > MAX_TRANSLATABLE_CHARS.toLong()) {
            return Inspection(
                valid = false,
                reason = "docx_text_too_large",
                segments = emptyList(),
                inflatedBytes = inflatedBytes,
                sourceEntries = entryCount
            )
        }

        return Inspection(
            valid = true,
            reason = "ok",
            segments = segments,
            inflatedBytes = inflatedBytes,
            sourceEntries = entryCount
        )
    }

    fun transform(
        sourceFile: File,
        outputFile: File,
        segments: List<Segment>,
        translatedById: Map<String, String>
    ): TransformResult {
        if (segments.isEmpty()) {
            return TransformResult(false, "no_segments", 0, 0L)
        }

        val expectedIds = segments.map { it.id }.toSet()
        if (expectedIds.size != segments.size) {
            return TransformResult(false, "duplicate_source_segment_ids", 0, 0L)
        }
        if (!translatedById.keys.containsAll(expectedIds)) {
            return TransformResult(false, "missing_translated_segments", 0, 0L)
        }

        val byPart = segments.groupBy { it.partName }
        var replaced = 0
        var entryCount = 0
        var inflatedBytes = 0L

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        try {
            ZipInputStream(
                BufferedInputStream(
                    FileInputStream(sourceFile)
                )
            ).use { input ->
                ZipOutputStream(
                    BufferedOutputStream(
                        FileOutputStream(outputFile)
                    )
                ).use { output ->
                    while (true) {
                        val sourceEntry = input.nextEntry ?: break
                        entryCount++
                        if (entryCount > MAX_ZIP_ENTRIES) {
                            throw IllegalStateException("too_many_zip_entries")
                        }

                        val name = normalizeEntryName(sourceEntry.name)
                            ?: throw SecurityException("unsafe_zip_entry")

                        val newEntry = ZipEntry(name).apply {
                            sourceEntry.comment?.let { comment = it }
                            sourceEntry.extra?.let { extra = it }
                            if (sourceEntry.time >= 0L) {
                                time = sourceEntry.time
                            }
                        }
                        output.putNextEntry(newEntry)

                        if (!sourceEntry.isDirectory) {
                            val bytes = readEntryBytes(
                                zip = input,
                                maxEntryBytes = maxEntryBytes(name)
                            ) ?: throw IllegalStateException("inflated_entry_too_large")

                            inflatedBytes += bytes.size.toLong()
                            if (inflatedBytes > MAX_TOTAL_INFLATED_BYTES) {
                                throw IllegalStateException("inflated_package_too_large")
                            }

                            val partSegments = byPart[name]
                            val finalBytes =
                                if (partSegments.isNullOrEmpty()) {
                                    bytes
                                } else {
                                    val result = replaceTextSegments(
                                        xml = bytes.toString(Charsets.UTF_8),
                                        partName = name,
                                        expectedSegments = partSegments,
                                        translatedById = translatedById
                                    )
                                    if (!result.success) {
                                        throw IllegalStateException(result.reason)
                                    }
                                    replaced += result.replacedSegments
                                    result.xml.toByteArray(Charsets.UTF_8)
                                }

                            output.write(finalBytes)
                        }

                        output.closeEntry()
                        input.closeEntry()
                    }
                }
            }
        } catch (error: Exception) {
            outputFile.delete()
            return TransformResult(
                success = false,
                reason = error.message ?: "docx_transform_failed",
                replacedSegments = replaced,
                outputBytes = 0L
            )
        }

        if (!outputFile.isFile || outputFile.length() <= MIN_VALID_DOCX_BYTES) {
            outputFile.delete()
            return TransformResult(false, "translated_docx_empty", replaced, 0L)
        }

        if (replaced != segments.size) {
            outputFile.delete()
            return TransformResult(false, "translated_segment_count_mismatch", replaced, 0L)
        }

        // Re-open the generated package so a write/ZIP corruption cannot be
        // reported as success.
        val verification = inspect(outputFile)
        if (!verification.valid) {
            outputFile.delete()
            return TransformResult(
                false,
                "translated_docx_verification_${verification.reason}",
                replaced,
                0L
            )
        }

        return TransformResult(
            success = true,
            reason = "ok",
            replacedSegments = replaced,
            outputBytes = outputFile.length()
        )
    }

    fun translatedFileName(
        sourceName: String,
        targetLanguageCode: String
    ): String {
        val base = sourceName
            .trim()
            .ifBlank { "document.docx" }
            .substringBeforeLast('.', sourceName)
            .replace(Regex("[\\/:*?\"<>|\\r\\n\\t]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(90)
            .ifBlank { "document" }

        val suffix = targetLanguageCode
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9_-]"), "")
            .take(12)
            .ifBlank { "translated" }

        return "${base}_${suffix}.docx"
    }

    private data class ReplacementResult(
        val success: Boolean,
        val reason: String,
        val replacedSegments: Int,
        val xml: String
    )

    private fun collectTextSegments(
        partName: String,
        xml: String,
        destination: MutableList<Segment>
    ) {
        var nodeIndex = 0
        TEXT_NODE_REGEX.findAll(xml).forEach { match ->
            val encoded = match.groupValues[2]
            val decoded = xmlDecode(encoded)
            val leading = decoded.takeWhile { it.isWhitespace() }
            val trailing = decoded.takeLastWhile { it.isWhitespace() }
            val core = decoded
                .removePrefix(leading)
                .removeSuffix(trailing)

            if (shouldTranslate(core)) {
                destination += Segment(
                    id = segmentId(partName, nodeIndex),
                    partName = partName,
                    nodeIndex = nodeIndex,
                    text = decoded,
                    leadingWhitespace = leading,
                    trailingWhitespace = trailing
                )
            }
            nodeIndex++
        }
    }

    private fun replaceTextSegments(
        xml: String,
        partName: String,
        expectedSegments: List<Segment>,
        translatedById: Map<String, String>
    ): ReplacementResult {
        val expectedByIndex = expectedSegments.associateBy { it.nodeIndex }
        var nodeIndex = 0
        var replaced = 0
        var cursor = 0
        val out = StringBuilder(xml.length + 2048)

        TEXT_NODE_REGEX.findAll(xml).forEach { match ->
            out.append(xml, cursor, match.range.first)
            out.append(match.groupValues[1])

            val expected = expectedByIndex[nodeIndex]
            if (expected == null) {
                out.append(match.groupValues[2])
            } else {
                if (expected.partName != partName) {
                    return ReplacementResult(false, "segment_part_mismatch", replaced, "")
                }
                val translated = translatedById[expected.id]
                    ?: return ReplacementResult(false, "missing_translation_${expected.id}", replaced, "")

                val normalized = translated
                    .replace('\u0000', ' ')
                    .replace(Regex("[\\r\\n]+"), " ")
                    .trim()

                val combined = expected.leadingWhitespace + normalized + expected.trailingWhitespace
                out.append(xmlEncode(combined))
                replaced++
            }

            out.append(match.groupValues[3])
            cursor = match.range.last + 1
            nodeIndex++
        }
        out.append(xml, cursor, xml.length)

        if (replaced != expectedSegments.size) {
            return ReplacementResult(false, "part_replacement_count_mismatch", replaced, "")
        }

        return ReplacementResult(true, "ok", replaced, out.toString())
    }

    private fun shouldTranslate(value: String): Boolean {
        if (value.isBlank()) return false
        if (value.length > MAX_SINGLE_SEGMENT_CHARS) return false
        if (URL_OR_EMAIL.matches(value.trim())) return false
        if (ONLY_NON_LETTERS.matches(value.trim())) return false
        return value.any { it.isLetter() }
    }

    private fun isTranslatablePart(name: String): Boolean {
        if (name == "word/document.xml") return true
        return TRANSLATABLE_PART_REGEX.matches(name)
    }

    private fun normalizeEntryName(raw: String?): String? {
        val name = raw?.replace('\\', '/')?.trim().orEmpty()
        if (name.isBlank() || name.startsWith('/') || name.contains("../") || name == "..") {
            return null
        }
        return name
    }

    private fun maxEntryBytes(name: String): Int =
        if (isTranslatablePart(name)) {
            MAX_TRANSLATABLE_PART_BYTES
        } else {
            MAX_GENERIC_ENTRY_BYTES
        }

    private fun readEntryBytes(
        zip: ZipInputStream,
        maxEntryBytes: Int
    ): ByteArray? {
        val buffer = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxEntryBytes) {
                return null
            }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun segmentId(
        partName: String,
        nodeIndex: Int
    ): String {
        val partHash = Integer.toHexString(partName.hashCode())
        return "${partHash}_$nodeIndex"
    }

    private fun xmlEncode(value: String): String =
        buildString(value.length + 16) {
            value.forEach { ch ->
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }

    private fun xmlDecode(value: String): String {
        if (!value.contains('&')) return value
        return ENTITY_REGEX.replace(value) { match ->
            when (val token = match.groupValues[1]) {
                "amp" -> "&"
                "lt" -> "<"
                "gt" -> ">"
                "quot" -> "\""
                "apos" -> "'"
                else -> {
                    try {
                        val codePoint =
                            if (token.startsWith("#x", ignoreCase = true)) {
                                token.substring(2).toInt(16)
                            } else if (token.startsWith('#')) {
                                token.substring(1).toInt(10)
                            } else {
                                return@replace match.value
                            }
                        String(Character.toChars(codePoint))
                    } catch (_: Exception) {
                        match.value
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_COMPRESSED_DOCX_BYTES = 12L * 1024L * 1024L
        private const val MAX_TOTAL_INFLATED_BYTES = 80L * 1024L * 1024L
        private const val MAX_GENERIC_ENTRY_BYTES = 32 * 1024 * 1024
        private const val MAX_TRANSLATABLE_PART_BYTES = 12 * 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 4096
        private const val MAX_TEXT_SEGMENTS = 4000
        private const val MAX_TRANSLATABLE_CHARS = 120_000
        private const val MAX_SINGLE_SEGMENT_CHARS = 8000
        private const val MIN_VALID_DOCX_BYTES = 500L

        private val TEXT_NODE_REGEX =
            Regex(
                "(<w:t\\b[^>]*>)(.*?)(</w:t>)",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
            )

        private val TRANSLATABLE_PART_REGEX =
            Regex(
                "word/(?:header\\d+|footer\\d+|footnotes|endnotes|comments)\\.xml",
                RegexOption.IGNORE_CASE
            )

        private val ENTITY_REGEX =
            Regex("&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);")

        private val URL_OR_EMAIL =
            Regex("(?i)^(?:https?://|www\\.|mailto:|[^\\s@]+@[^\\s@]+\\.[^\\s@]+).*$")

        private val ONLY_NON_LETTERS =
            Regex("^[^\\p{L}]+$")
    }
}
