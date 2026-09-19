package kg.autonomous.agent

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.InflaterInputStream
import java.util.zip.ZipInputStream

/**
 * AYANA Document Content Index Engine v1.0 — local incremental searchable index.
 *
 * Truth contract:
 * - index is stored only in AYANA app-private filesDir;
 * - plain text / DOCX / XLSX / PPTX / ODT extraction is local;
 * - PDF extraction is local BEST-EFFORT only (literal text + Flate streams);
 * - scanned/image-only PDF and custom-font PDF text can remain unreadable;
 * - no document bytes or extracted text are sent to Worker / Agent Core;
 * - scoped-storage/provider visibility is never described as full-storage coverage;
 * - unchanged files reuse cached extracted text; changed/new files are re-indexed.
 */
class AyanaDocumentContentIndexEngine(
    context: Context
) {

    data class Hit(
        val displayName: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val timestampMs: Long,
        val uri: String,
        val snippet: String,
        val score: Int,
        val extractor: String
    )

    data class SearchResult(
        val hits: List<Hit>,
        val providerRowsScanned: Int,
        val candidateDocuments: Int,
        val indexedDocuments: Int,
        val reusedDocuments: Int,
        val updatedDocuments: Int,
        val failedDocuments: Int,
        val unsupportedDocuments: Int,
        val pdfBestEffortDocuments: Int,
        val detail: String
    )

    private data class Descriptor(
        val key: String,
        val uri: String,
        val displayName: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val modifiedAtMs: Long,
        val extension: String
    )

    private data class Extracted(
        val text: String,
        val extractor: String,
        val reliable: Boolean
    )

    private val appContext =
        context.applicationContext

    private val resolver =
        appContext.contentResolver

    private val indexFile =
        File(
            appContext.filesDir,
            INDEX_FILE_NAME
        )

    private val lock = Any()

    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT
    ): SearchResult {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return SearchResult(
                hits = emptyList(),
                providerRowsScanned = 0,
                candidateDocuments = 0,
                indexedDocuments = 0,
                reusedDocuments = 0,
                updatedDocuments = 0,
                failedDocuments = 0,
                unsupportedDocuments = 0,
                pdfBestEffortDocuments = 0,
                detail = "Пустой запрос: индекс содержимого не запускался."
            )
        }

        synchronized(lock) {
            val enumeration = enumerateDocuments()
            val descriptors = enumeration.first
            val providerRowsScanned = enumeration.second

            val oldIndex = loadIndexUnsafe()
            val nextIndex = linkedMapOf<String, JSONObject>()

            var reused = 0
            var updated = 0
            var failed = 0
            var unsupported = 0
            var pdfBestEffort = 0

            descriptors.forEach { descriptor ->
                val old = oldIndex[descriptor.key]
                val fingerprint = fingerprint(descriptor)

                if (
                    old != null &&
                    old.optString("fingerprint") == fingerprint &&
                    old.optString("status") == STATUS_INDEXED
                ) {
                    nextIndex[descriptor.key] = old
                    reused++
                    if (old.optString("extractor") == EXTRACTOR_PDF_BEST_EFFORT) {
                        pdfBestEffort++
                    }
                    return@forEach
                }

                if (!isSupported(descriptor)) {
                    unsupported++
                    nextIndex[descriptor.key] =
                        baseEntry(descriptor, fingerprint)
                            .put("status", STATUS_UNSUPPORTED)
                            .put("extractor", "unsupported")
                            .put("text", "")
                    return@forEach
                }

                if (
                    descriptor.sizeBytes <= 0L ||
                    descriptor.sizeBytes > MAX_SOURCE_FILE_BYTES
                ) {
                    unsupported++
                    nextIndex[descriptor.key] =
                        baseEntry(descriptor, fingerprint)
                            .put("status", STATUS_UNSUPPORTED)
                            .put("extractor", "size_out_of_bounds")
                            .put("text", "")
                    return@forEach
                }

                val extracted =
                    try {
                        extract(descriptor)
                    } catch (_: Exception) {
                        null
                    }

                if (
                    extracted == null ||
                    extracted.text.isBlank()
                ) {
                    failed++
                    nextIndex[descriptor.key] =
                        baseEntry(descriptor, fingerprint)
                            .put("status", STATUS_FAILED)
                            .put("extractor", extracted?.extractor.orEmpty())
                            .put("text", "")
                    return@forEach
                }

                updated++
                if (extracted.extractor == EXTRACTOR_PDF_BEST_EFFORT) {
                    pdfBestEffort++
                }

                nextIndex[descriptor.key] =
                    baseEntry(descriptor, fingerprint)
                        .put("status", STATUS_INDEXED)
                        .put("extractor", extracted.extractor)
                        .put("reliable", extracted.reliable)
                        .put(
                            "text",
                            compactIndexText(extracted.text)
                        )
                        .put(
                            "indexed_at",
                            System.currentTimeMillis()
                        )
            }

            saveIndexUnsafe(nextIndex)

            val hits =
                nextIndex.values
                    .asSequence()
                    .filter {
                        it.optString("status") == STATUS_INDEXED
                    }
                    .mapNotNull { entry ->
                        scoreEntry(
                            query = query,
                            normalizedQuery = normalizedQuery,
                            entry = entry
                        )
                    }
                    .sortedWith(
                        compareByDescending<Hit> { it.score }
                            .thenByDescending { it.timestampMs }
                    )
                    .take(limit.coerceIn(1, MAX_RETURNED_HITS))
                    .toList()

            val indexed =
                nextIndex.values.count {
                    it.optString("status") == STATUS_INDEXED
                }

            return SearchResult(
                hits = hits,
                providerRowsScanned = providerRowsScanned,
                candidateDocuments = descriptors.size,
                indexedDocuments = indexed,
                reusedDocuments = reused,
                updatedDocuments = updated,
                failedDocuments = failed,
                unsupportedDocuments = unsupported,
                pdfBestEffortDocuments = pdfBestEffort,
                detail =
                    "Локальный инкрементальный индекс: indexed=$indexed; reused=$reused; updated=$updated; " +
                        "failed=$failed; unsupported=$unsupported; pdf_best_effort=$pdfBestEffort. " +
                        "TXT/MD/JSON/CSV/XML/HTML/code, DOCX, XLSX, PPTX и ODT извлекаются локально. " +
                        "PDF — только best-effort text extraction; сканы и custom-font PDF могут не читаться. " +
                        "Индекс охватывает только документы, видимые AYANA через MediaStore/Downloads."
            )
        }
    }

    private fun enumerateDocuments(): Pair<List<Descriptor>, Int> {
        val byPhysicalKey = linkedMapOf<String, Descriptor>()
        var scanned = 0

        fun queryCollection(collection: Uri) {
            if (scanned >= MAX_PROVIDER_ROWS_SCAN) {
                return
            }

            val projection =
                buildList {
                    add(MediaStore.Files.FileColumns._ID)
                    add(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    add(MediaStore.Files.FileColumns.MIME_TYPE)
                    add(MediaStore.Files.FileColumns.SIZE)
                    add(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    add(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    if (Build.VERSION.SDK_INT >= 29) {
                        add(MediaStore.Files.FileColumns.RELATIVE_PATH)
                    }
                }
                    .distinct()
                    .toTypedArray()

            try {
                resolver.query(
                    collection,
                    projection,
                    null,
                    null,
                    "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    val nameIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val mimeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)
                    val sizeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                    val modifiedIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.DATE_MODIFIED)
                    val mediaTypeIndex = cursor.getColumnIndex(MediaStore.Files.FileColumns.MEDIA_TYPE)
                    val pathIndex =
                        if (Build.VERSION.SDK_INT >= 29) {
                            cursor.getColumnIndex(MediaStore.Files.FileColumns.RELATIVE_PATH)
                        } else {
                            -1
                        }

                    while (
                        cursor.moveToNext() &&
                        scanned < MAX_PROVIDER_ROWS_SCAN &&
                        byPhysicalKey.size < MAX_CANDIDATE_DOCUMENTS
                    ) {
                        scanned++

                        val mediaType = cursor.longOrZero(mediaTypeIndex).toInt()
                        if (
                            mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ||
                            mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ||
                            mediaType == MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO
                        ) {
                            continue
                        }

                        val id = cursor.longOrZero(idIndex)
                        if (id <= 0L) {
                            continue
                        }

                        val displayName = cursor.stringOrEmpty(nameIndex)
                        val relativePath = cursor.stringOrEmpty(pathIndex)
                        val mimeType = cursor.stringOrEmpty(mimeIndex)
                        val sizeBytes = cursor.longOrZero(sizeIndex)
                        val modifiedAtMs = cursor.longOrZero(modifiedIndex) * 1000L
                        val extension = extensionOf(displayName)

                        if (!looksLikeDocument(displayName, mimeType, extension)) {
                            continue
                        }

                        val physicalKey =
                            listOf(
                                relativePath.lowercase(Locale.ROOT),
                                displayName.lowercase(Locale.ROOT),
                                sizeBytes.toString(),
                                modifiedAtMs.toString()
                            ).joinToString("|")

                        val uri =
                            ContentUris.withAppendedId(
                                collection,
                                id
                            ).toString()

                        byPhysicalKey.putIfAbsent(
                            physicalKey,
                            Descriptor(
                                key = physicalKey,
                                uri = uri,
                                displayName = displayName,
                                relativePath = relativePath,
                                mimeType = mimeType,
                                sizeBytes = sizeBytes,
                                modifiedAtMs = modifiedAtMs,
                                extension = extension
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            queryCollection(
                MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL
                )
            )
        }

        if (scanned < MAX_PROVIDER_ROWS_SCAN) {
            queryCollection(
                if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Files.getContentUri(
                        MediaStore.VOLUME_EXTERNAL
                    )
                } else {
                    MediaStore.Files.getContentUri("external")
                }
            )
        }

        return byPhysicalKey.values.toList() to scanned
    }

    private fun extract(
        descriptor: Descriptor
    ): Extracted? {
        val uri = Uri.parse(descriptor.uri)
        val ext = descriptor.extension.lowercase(Locale.ROOT)

        return when {
            ext in PLAIN_TEXT_EXTENSIONS ||
                descriptor.mimeType.startsWith("text/") ->
                resolver.openInputStream(uri)?.use { input ->
                    Extracted(
                        text = readPlainText(input),
                        extractor = "plain_text_local",
                        reliable = true
                    )
                }

            ext == "docx" ->
                resolver.openInputStream(uri)?.use { input ->
                    Extracted(
                        text = extractZipXmlText(
                            input = input,
                            accepted = { name ->
                                name == "word/document.xml" ||
                                    name.startsWith("word/header") ||
                                    name.startsWith("word/footer") ||
                                    name == "word/footnotes.xml" ||
                                    name == "word/endnotes.xml" ||
                                    name == "word/comments.xml"
                            }
                        ),
                        extractor = "docx_ooxml_local",
                        reliable = true
                    )
                }

            ext == "xlsx" ->
                resolver.openInputStream(uri)?.use { input ->
                    Extracted(
                        text = extractZipXmlText(
                            input = input,
                            accepted = { name ->
                                name == "xl/sharedStrings.xml" ||
                                    name.startsWith("xl/worksheets/sheet")
                            }
                        ),
                        extractor = "xlsx_ooxml_local",
                        reliable = true
                    )
                }

            ext == "pptx" ->
                resolver.openInputStream(uri)?.use { input ->
                    Extracted(
                        text = extractZipXmlText(
                            input = input,
                            accepted = { name ->
                                name.startsWith("ppt/slides/slide") ||
                                    name.startsWith("ppt/notesSlides/notesSlide")
                            }
                        ),
                        extractor = "pptx_ooxml_local",
                        reliable = true
                    )
                }

            ext == "odt" ->
                resolver.openInputStream(uri)?.use { input ->
                    Extracted(
                        text = extractZipXmlText(
                            input = input,
                            accepted = { name ->
                                name == "content.xml" ||
                                    name == "meta.xml"
                            }
                        ),
                        extractor = "odt_xml_local",
                        reliable = true
                    )
                }

            ext == "pdf" || descriptor.mimeType == "application/pdf" ->
                resolver.openInputStream(uri)?.use { input ->
                    Extracted(
                        text = extractPdfBestEffort(input),
                        extractor = EXTRACTOR_PDF_BEST_EFFORT,
                        reliable = false
                    )
                }

            else -> null
        }
    }

    private fun readPlainText(
        input: InputStream
    ): String {
        val bytes = readBytesLimited(input, MAX_EXTRACT_BYTES)
        if (bytes.isEmpty()) {
            return ""
        }

        val nulCount = bytes.count { it.toInt() == 0 }
        if (nulCount > bytes.size / 20) {
            return ""
        }

        val utf8 =
            bytes.toString(Charsets.UTF_8)

        return normalizeExtractedText(utf8)
    }

    private fun extractZipXmlText(
        input: InputStream,
        accepted: (String) -> Boolean
    ): String {
        val output = StringBuilder()
        var totalXmlBytes = 0

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.orEmpty()
                if (!entry.isDirectory && accepted(name)) {
                    val bytes =
                        readBytesLimited(
                            zip,
                            (MAX_ZIP_XML_BYTES - totalXmlBytes)
                                .coerceAtLeast(0)
                        )
                    totalXmlBytes += bytes.size
                    if (bytes.isNotEmpty()) {
                        val xml = bytes.toString(Charsets.UTF_8)
                        val text = xmlToText(xml)
                        if (text.isNotBlank()) {
                            if (output.isNotEmpty()) {
                                output.append('\n')
                            }
                            output.append(text)
                        }
                    }
                }
                zip.closeEntry()
                if (
                    totalXmlBytes >= MAX_ZIP_XML_BYTES ||
                    output.length >= MAX_INDEXED_TEXT_CHARS
                ) {
                    break
                }
            }
        }

        return normalizeExtractedText(output.toString())
    }

    private fun xmlToText(
        xml: String
    ): String {
        var value =
            xml
                .replace(Regex("(?is)<w:tab[^>]*/>"), "\t")
                .replace(Regex("(?is)<w:br[^>]*/>"), "\n")
                .replace(Regex("(?is)</(?:w:p|a:p|text:p|text:h|row|c)>"), "\n")
                .replace(Regex("(?is)<[^>]+>"), " ")

        value = decodeXmlEntities(value)
        return normalizeExtractedText(value)
    }

    private fun decodeXmlEntities(
        value: String
    ): String {
        return value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace(
                Regex("&#(\\d+);")
            ) { match ->
                match.groupValues[1]
                    .toIntOrNull()
                    ?.takeIf { it in 1..0x10FFFF }
                    ?.let { codePoint ->
                        String(Character.toChars(codePoint))
                    }
                    ?: match.value
            }
            .replace(
                Regex("&#x([0-9A-Fa-f]+);")
            ) { match ->
                match.groupValues[1]
                    .toIntOrNull(16)
                    ?.takeIf { it in 1..0x10FFFF }
                    ?.let { codePoint ->
                        String(Character.toChars(codePoint))
                    }
                    ?: match.value
            }
    }

    private fun extractPdfBestEffort(
        input: InputStream
    ): String {
        val raw = readBytesLimited(input, MAX_EXTRACT_BYTES)
        if (raw.isEmpty()) {
            return ""
        }

        val chunks = mutableListOf<ByteArray>()
        chunks += raw

        val latin = raw.toString(Charset.forName("ISO-8859-1"))
        var searchFrom = 0
        var streamCount = 0

        while (
            searchFrom < latin.length &&
            streamCount < MAX_PDF_STREAMS
        ) {
            val marker = latin.indexOf("stream", searchFrom)
            if (marker < 0) {
                break
            }

            var dataStart = marker + "stream".length
            if (dataStart < raw.size && raw[dataStart].toInt() == '\r'.code) {
                dataStart++
            }
            if (dataStart < raw.size && raw[dataStart].toInt() == '\n'.code) {
                dataStart++
            }

            val end = latin.indexOf("endstream", dataStart)
            if (end < 0 || end <= dataStart || end > raw.size) {
                break
            }

            val dictionaryStart = (marker - 600).coerceAtLeast(0)
            val dictionary = latin.substring(dictionaryStart, marker)
            val streamBytes = raw.copyOfRange(dataStart, end)

            val decoded =
                if (dictionary.contains("/FlateDecode")) {
                    try {
                        InflaterInputStream(
                            ByteArrayInputStream(streamBytes)
                        ).use { inflated ->
                            readBytesLimited(
                                inflated,
                                MAX_PDF_DECOMPRESSED_STREAM_BYTES
                            )
                        }
                    } catch (_: Exception) {
                        ByteArray(0)
                    }
                } else {
                    streamBytes
                        .takeIf {
                            it.size <= MAX_PDF_DECOMPRESSED_STREAM_BYTES
                        }
                        ?: ByteArray(0)
                }

            if (decoded.isNotEmpty()) {
                chunks += decoded
            }

            streamCount++
            searchFrom = end + "endstream".length
        }

        val text = StringBuilder()
        outer@ for (bytes in chunks) {
            val candidate =
                bytes.toString(Charset.forName("ISO-8859-1"))

            for (literal in extractPdfLiteralStrings(candidate)) {
                if (looksHumanReadable(literal)) {
                    if (text.isNotEmpty()) {
                        text.append(' ')
                    }
                    text.append(literal)
                }
                if (text.length >= MAX_INDEXED_TEXT_CHARS) {
                    break@outer
                }
            }
        }

        return normalizeExtractedText(text.toString())
    }

    private fun extractPdfLiteralStrings(
        value: String
    ): List<String> {
        val result = mutableListOf<String>()
        var index = 0

        while (index < value.length) {
            if (value[index] != '(') {
                index++
                continue
            }

            val buffer = StringBuilder()
            var depth = 1
            var i = index + 1

            while (i < value.length && depth > 0) {
                val ch = value[i]
                if (ch == '\\' && i + 1 < value.length) {
                    val next = value[i + 1]
                    when (next) {
                        'n' -> buffer.append('\n')
                        'r' -> buffer.append('\r')
                        't' -> buffer.append('\t')
                        'b' -> buffer.append('\b')
                        'f' -> buffer.append('\u000C')
                        '(', ')', '\\' -> buffer.append(next)
                        '\r', '\n' -> { }
                        in '0'..'7' -> {
                            var octal = ""
                            var j = i + 1
                            while (
                                j < value.length &&
                                j <= i + 3 &&
                                value[j] in '0'..'7'
                            ) {
                                octal += value[j]
                                j++
                            }
                            octal.toIntOrNull(8)?.let { buffer.append(it.toChar()) }
                            i = j - 1
                        }
                        else -> buffer.append(next)
                    }
                    i += 2
                    continue
                }

                if (ch == '(') {
                    depth++
                    if (depth > 1) {
                        buffer.append(ch)
                    }
                } else if (ch == ')') {
                    depth--
                    if (depth > 0) {
                        buffer.append(ch)
                    }
                } else {
                    buffer.append(ch)
                }
                i++
            }

            val literal = buffer.toString().trim()
            if (literal.length >= 2) {
                result += literal
            }
            index = i.coerceAtLeast(index + 1)
        }

        return result
    }

    private fun looksHumanReadable(
        value: String
    ): Boolean {
        if (value.length < 2) {
            return false
        }
        val letters = value.count { it.isLetterOrDigit() }
        val controls = value.count { it.code < 32 && it !in listOf('\n', '\r', '\t') }
        return letters >= 2 && controls <= value.length / 10
    }

    private fun scoreEntry(
        query: String,
        normalizedQuery: String,
        entry: JSONObject
    ): Hit? {
        val text = entry.optString("text")
        val displayName = entry.optString("display_name")
        val relativePath = entry.optString("relative_path")
        val mimeType = entry.optString("mime_type")

        val metadataHaystack =
            listOf(displayName, relativePath, mimeType)
                .joinToString(" ")
        val metadataScore = lexicalScore(query, metadataHaystack)
        val contentScore = lexicalScore(query, text)

        if (metadataScore <= 0 && contentScore <= 0) {
            return null
        }

        val snippet =
            if (contentScore > 0) {
                snippetAroundMatch(
                    text = text,
                    normalizedQuery = normalizedQuery
                )
            } else {
                "Совпадение только в метаданных файла."
            }

        return Hit(
            displayName = displayName.ifBlank { "Документ" },
            relativePath = relativePath,
            mimeType = mimeType,
            sizeBytes = entry.optLong("size_bytes", 0L),
            timestampMs = entry.optLong("modified_at_ms", 0L),
            uri = entry.optString("uri"),
            snippet = snippet,
            score =
                contentScore * 2 +
                    metadataScore +
                    if (contentScore > 0) 120 else 0,
            extractor = entry.optString("extractor")
        )
    }

    private fun snippetAroundMatch(
        text: String,
        normalizedQuery: String
    ): String {
        val normalizedText = normalize(text)
        var matchIndex =
            if (normalizedQuery.isNotBlank()) {
                normalizedText.indexOf(normalizedQuery)
            } else {
                -1
            }

        if (matchIndex < 0) {
            val tokens =
                normalizedQuery
                    .split(" ")
                    .filter { it.length >= 2 }
            matchIndex =
                tokens
                    .asSequence()
                    .map { token -> normalizedText.indexOf(token) }
                    .filter { it >= 0 }
                    .minOrNull()
                    ?: 0
        }

        // Normalization changes whitespace but not enough to justify pretending the
        // exact normalized offset maps perfectly to original text. Use the normalized
        // text itself for a deterministic snippet.
        val start = (matchIndex - SNIPPET_CONTEXT_CHARS).coerceAtLeast(0)
        val end =
            (matchIndex + normalizedQuery.length + SNIPPET_CONTEXT_CHARS)
                .coerceAtMost(normalizedText.length)

        return normalizedText
            .substring(start, end)
            .trim()
            .take(MAX_SNIPPET_CHARS)
    }

    private fun baseEntry(
        descriptor: Descriptor,
        fingerprint: String
    ): JSONObject =
        JSONObject()
            .put("key", descriptor.key)
            .put("uri", descriptor.uri)
            .put("display_name", descriptor.displayName)
            .put("relative_path", descriptor.relativePath)
            .put("mime_type", descriptor.mimeType)
            .put("size_bytes", descriptor.sizeBytes)
            .put("modified_at_ms", descriptor.modifiedAtMs)
            .put("extension", descriptor.extension)
            .put("fingerprint", fingerprint)

    private fun fingerprint(
        descriptor: Descriptor
    ): String =
        "${descriptor.sizeBytes}:${descriptor.modifiedAtMs}:${descriptor.mimeType}:${descriptor.displayName}"

    private fun loadIndexUnsafe(): MutableMap<String, JSONObject> {
        if (!indexFile.exists()) {
            return linkedMapOf()
        }

        return try {
            val root = JSONObject(indexFile.readText(Charsets.UTF_8))
            val items = root.optJSONArray("items") ?: JSONArray()
            val result = linkedMapOf<String, JSONObject>()
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val key = item.optString("key")
                if (key.isNotBlank()) {
                    result[key] = item
                }
            }
            result
        } catch (_: Exception) {
            linkedMapOf()
        }
    }

    private fun saveIndexUnsafe(
        entries: Map<String, JSONObject>
    ) {
        val items = JSONArray()
        entries.values
            .sortedByDescending { it.optLong("modified_at_ms", 0L) }
            .take(MAX_CANDIDATE_DOCUMENTS)
            .forEach { items.put(it) }

        val root =
            JSONObject()
                .put("version", INDEX_VERSION)
                .put("saved_at", System.currentTimeMillis())
                .put("items", items)

        val temp = File(indexFile.parentFile, indexFile.name + ".tmp")
        temp.writeText(root.toString(), Charsets.UTF_8)
        if (indexFile.exists() && !indexFile.delete()) {
            temp.delete()
            return
        }
        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(root.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun isSupported(
        descriptor: Descriptor
    ): Boolean {
        val ext = descriptor.extension.lowercase(Locale.ROOT)
        return ext in SUPPORTED_EXTENSIONS ||
            descriptor.mimeType.startsWith("text/") ||
            descriptor.mimeType in SUPPORTED_MIME_TYPES
    }

    private fun looksLikeDocument(
        displayName: String,
        mimeType: String,
        extension: String
    ): Boolean {
        if (displayName.isBlank() && mimeType.isBlank()) {
            return false
        }
        if (mimeType.startsWith("image/") || mimeType.startsWith("video/") || mimeType.startsWith("audio/")) {
            return false
        }
        return extension in CANDIDATE_EXTENSIONS ||
            mimeType.startsWith("text/") ||
            mimeType in CANDIDATE_MIME_TYPES
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "")
            .lowercase(Locale.ROOT)
            .trim()
            .take(12)

    private fun lexicalScore(
        query: String,
        haystack: String
    ): Int {
        val q = normalize(query)
        val h = normalize(haystack)
        if (q.isBlank() || h.isBlank()) {
            return 0
        }

        val tokens =
            q.split(" ")
                .filter { it.length >= 2 }
                .distinct()

        val phrase = if (h.contains(q)) 100 else 0
        val overlap = tokens.count { token -> h.contains(token) }
        if (phrase == 0 && overlap == 0) {
            return 0
        }

        return phrase + overlap * 20 +
            if (tokens.isNotEmpty() && overlap == tokens.size) 30 else 0
    }

    private fun normalizeExtractedText(
        value: String
    ): String =
        value
            .replace('\u0000', ' ')
            .replace(Regex("[\\t\\x0B\\f\\r ]+"), " ")
            .replace(Regex(" *\\n+ *"), "\n")
            .trim()
            .take(MAX_INDEXED_TEXT_CHARS)

    private fun compactIndexText(
        value: String
    ): String =
        normalizeExtractedText(value)
            .take(MAX_INDEXED_TEXT_CHARS)

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9._/\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun readBytesLimited(
        input: InputStream,
        maxBytes: Int
    ): ByteArray {
        if (maxBytes <= 0) {
            return ByteArray(0)
        }

        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        val buffer = ByteArray(32 * 1024)
        var total = 0
        while (total < maxBytes) {
            val allowed = minOf(buffer.size, maxBytes - total)
            val read = input.read(buffer, 0, allowed)
            if (read <= 0) {
                break
            }
            output.write(buffer, 0, read)
            total += read
        }
        return output.toByteArray()
    }

    private fun android.database.Cursor.stringOrEmpty(index: Int): String =
        if (index >= 0 && !isNull(index)) getString(index).orEmpty() else ""

    private fun android.database.Cursor.longOrZero(index: Int): Long =
        if (index >= 0 && !isNull(index)) getLong(index) else 0L

    companion object {
        private const val INDEX_VERSION = 1
        private const val INDEX_FILE_NAME = "ayana_document_content_index_v1.json"

        private const val STATUS_INDEXED = "indexed"
        private const val STATUS_FAILED = "failed"
        private const val STATUS_UNSUPPORTED = "unsupported"

        private const val EXTRACTOR_PDF_BEST_EFFORT = "pdf_best_effort_text"

        private const val DEFAULT_LIMIT = 6
        private const val MAX_RETURNED_HITS = 12
        private const val MAX_PROVIDER_ROWS_SCAN = 5000
        private const val MAX_CANDIDATE_DOCUMENTS = 500
        private const val MAX_SOURCE_FILE_BYTES = 8L * 1024L * 1024L
        private const val MAX_EXTRACT_BYTES = 8 * 1024 * 1024
        private const val MAX_ZIP_XML_BYTES = 6 * 1024 * 1024
        private const val MAX_INDEXED_TEXT_CHARS = 60_000
        private const val MAX_PDF_STREAMS = 160
        private const val MAX_PDF_DECOMPRESSED_STREAM_BYTES = 512 * 1024
        private const val SNIPPET_CONTEXT_CHARS = 150
        private const val MAX_SNIPPET_CHARS = 420

        private val PLAIN_TEXT_EXTENSIONS =
            setOf(
                "txt", "md", "log", "json", "csv", "xml", "html", "htm",
                "kt", "kts", "java", "py", "js", "ts", "css", "yaml", "yml",
                "sql", "ini", "conf", "properties", "gradle", "sh", "bat"
            )

        private val SUPPORTED_EXTENSIONS =
            PLAIN_TEXT_EXTENSIONS +
                setOf("docx", "xlsx", "pptx", "odt", "pdf")

        private val CANDIDATE_EXTENSIONS =
            SUPPORTED_EXTENSIONS +
                setOf("doc", "xls", "ppt", "rtf")

        private val SUPPORTED_MIME_TYPES =
            setOf(
                "application/pdf",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "application/vnd.oasis.opendocument.text",
                "application/json",
                "application/xml",
                "application/javascript"
            )

        private val CANDIDATE_MIME_TYPES =
            SUPPORTED_MIME_TYPES +
                setOf(
                    "application/msword",
                    "application/vnd.ms-excel",
                    "application/vnd.ms-powerpoint",
                    "application/rtf"
                )
    }
}
