package kg.autonomous.agent

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * R10.28.9.1: bounded, first-cause-preserving GitHub Actions log diagnostics.
 * Pure JVM/Kotlin so the exact parser can be regression-tested without Android.
 * It never extracts archive paths to disk or executes untrusted log content.
 */
internal object AyanaBuildDiagnosticExtractor {
    private const val MAX_ARCHIVE_BYTES = 14 * 1024 * 1024
    private const val MAX_TOTAL_UNCOMPRESSED = 14 * 1024 * 1024
    private const val MAX_SINGLE_ENTRY = 10 * 1024 * 1024
    private const val MAX_DIAGNOSTICS = 130
    private const val MAX_OUTPUT_CHARS = 36_000

    fun summarize(archive: ByteArray): String {
        if (archive.isEmpty()) return "BUILD_LOG_UNAVAILABLE: GitHub Actions log archive is empty."
        if (archive.size > MAX_ARCHIVE_BYTES) {
            return "BUILD_LOG_LIMIT_EXCEEDED: GitHub Actions ZIP exceeds safe archive limit."
        }
        return try {
            val diagnostics = linkedSetOf<String>()
            val fileCounts = linkedMapOf<String, Int>()
            var totalBytes = 0
            var allErrorLines = 0
            var firstError = ""
            var exceeded = false
            var uniqueErrorOverflow = false
            var entryCount = 0
            val buffer = ByteArray(8192)
            ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
                while (entryCount < 70) {
                    val entry = zip.nextEntry ?: break
                    entryCount++
                    if (entry.isDirectory || !entry.name.endsWith(".txt", ignoreCase = true)) {
                        zip.closeEntry()
                        continue
                    }
                    val body = ByteArrayOutputStream()
                    while (true) {
                        val size = zip.read(buffer)
                        if (size < 0) break
                        totalBytes += size
                        if (totalBytes > MAX_TOTAL_UNCOMPRESSED ||
                            body.size() + size > MAX_SINGLE_ENTRY
                        ) {
                            exceeded = true
                            break
                        }
                        body.write(buffer, 0, size)
                    }
                    if (exceeded) break
                    val lines = body.toString(StandardCharsets.UTF_8.name()).lineSequence()
                    for (raw in lines) {
                        val normalized = cleanLine(raw)
                        if (!isDiagnostic(normalized)) continue
                        allErrorLines++
                        if (firstError.isEmpty()) firstError = normalized.take(600)
                        val signature = normalized.take(900)
                        if (!diagnostics.contains(signature)) {
                            if (diagnostics.size < MAX_DIAGNOSTICS) diagnostics.add(signature)
                            else uniqueErrorOverflow = true
                        }
                        val file = sourceFile(normalized)
                        if (file.isNotBlank()) {
                            fileCounts[file] = (fileCounts[file] ?: 0) + 1
                        }
                    }
                    zip.closeEntry()
                }
            }
            if (diagnostics.isEmpty()) {
                "BUILD_LOG_NO_COMPILER_ERRORS_FOUND: ZIP readable; no compiler/error markers detected."
            } else {
                buildString {
                    append("BUILD_DIAGNOSTICS_V2\n")
                    append("archive_sha256=").append(sha256(archive)).append('\n')
                    append("error_lines_total=").append(allErrorLines).append('\n')
                    append("unique_diagnostics_kept=").append(diagnostics.size).append('\n')
                    append("truncated=").append(exceeded || uniqueErrorOverflow).append('\n')
                    append("duplicate_lines_collapsed=").append(allErrorLines - diagnostics.size).append('\n')
                    append("first_error=").append(firstError).append('\n')
                    append("ERROR_COUNTS_BY_SOURCE_FILE:\n")
                    for ((file, count) in fileCounts.entries.sortedByDescending { it.value }.take(40)) {
                        append(file).append('=').append(count).append('\n')
                    }
                    append("EARLIEST_UNIQUE_ERRORS_IN_BUILD_ORDER:\n")
                    for (line in diagnostics) {
                        if (length + line.length + 2 > MAX_OUTPUT_CHARS) {
                            append("... OUTPUT_BUDGET_REACHED; see archive_sha256 for full archive identity.\n")
                            break
                        }
                        append(line).append('\n')
                    }
                    if (exceeded) append("LOG_ZIP_DECOMPRESSION_LIMIT_REACHED: partial diagnostic, fail closed.\n")
                }.take(MAX_OUTPUT_CHARS)
            }
        } catch (error: Exception) {
            "BUILD_LOG_PARSE_ERROR: ${error.javaClass.simpleName}; diagnostic archive not trusted."
        }
    }

    private fun cleanLine(line: String): String {
        var cleaned = line.trim().trimStart('\uFEFF')
        cleaned = cleaned.replace(Regex("^\\d{4}-\\d{2}-\\d{2}T[^ ]+Z\\s+"), "")
        cleaned = cleaned.removePrefix("##[error]").trim()
        return cleaned.take(2000)
    }

    private fun isDiagnostic(line: String): Boolean {
        if (line.isBlank()) return false
        val n = line.lowercase(Locale.ROOT)
        return n.startsWith("e: ") || n.startsWith("error:") ||
            n.contains(": error:") || n.contains("[error]") ||
            n.contains("compilation error") || n.startsWith("failure:") ||
            n.contains("execution failed for task") ||
            n.contains("what went wrong") ||
            n.contains("unresolved reference") ||
            n.contains("cannot infer a type for this parameter")
    }

    private fun sourceFile(line: String): String {
        val match = Regex("([A-Za-z][A-Za-z0-9_+.-]*\\.(?:kt|kts|java|xml|gradle))(?=[:(\\s])")
            .find(line) ?: return ""
        return match.groupValues[1]
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }
}
