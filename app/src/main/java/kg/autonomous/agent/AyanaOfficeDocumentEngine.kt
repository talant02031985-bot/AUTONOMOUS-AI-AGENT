package kg.autonomous.agent

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * AYANA Office Document Engine v2.0 — R10.21 DOCUMENT & OFFICE ENGINE 2.0.
 *
 * Dependency-free Office layer for Android/JVM. It deliberately does not add Apache POI
 * or another large Office dependency to the APK. The engine owns a narrow, verifiable
 * contract:
 *
 * - TXT: create/read/exact replacement;
 * - DOCX: create minimal valid OOXML, inspect text and package-preserving exact replacement;
 * - XLSX: create minimal valid OOXML with real numeric/text cells, inspect and exact text replacement;
 * - PPTX: create real OOXML presentations, inspect slides and package-preserving exact replacement;
 * - PDF: R10.21 keeps production PDF generation on the already accepted ArtifactEngine;
 *   this engine only provides a deterministic scratch PDF structural probe used by acceptance.
 *
 * Truth / safety rules:
 * - existing OOXML is never rewritten from plain text; package entries are copied byte-for-byte
 *   except the exact XML part that contains the requested literal;
 * - if an exact target is absent, edit fails closed and output is not committed;
 * - package entry set must remain identical after an edit;
 * - PPTX published by create_artifact is reopened, hashed and structurally inspected before SUCCESS;
 * - no raw content URI is rendered as prose; it is returned only as structured artifact_reference;
 * - this class never interprets document text as execution instructions.
 */
class AyanaOfficeDocumentEngine(
    context: Context
) {
    private val appContext = context.applicationContext

    data class Inspection(
        val format: String,
        val success: Boolean,
        val text: String,
        val entryCount: Int,
        val slideCount: Int = 0,
        val sheetCount: Int = 0,
        val reason: String = ""
    )

    data class EditResult(
        val success: Boolean,
        val verified: Boolean,
        val format: String,
        val replacements: Int,
        val entrySetPreserved: Boolean,
        val untouchedEntriesPreserved: Boolean,
        val changedEntries: List<String>,
        val reason: String = ""
    )

    data class Slide(
        val title: String,
        val body: String
    )

    fun createPresentationArtifact(
        arguments: JSONObject,
        tryBeginPublish: (String) -> Boolean = { true },
        onPublishAccepted: (String) -> Unit = {},
        onPublishReconciliationStarted: (String) -> Unit = {},
        onPublishReconciled: (Boolean, String) -> Unit = { _, _ -> }
    ): JSONObject {
        val requestedName = arguments.optString("filename").trim()
        val filename = enforceExtension(
            sanitizeFilename(requestedName.ifBlank { "AYANA_presentation.pptx" }),
            "pptx"
        )
        val title = arguments.optString("title").trim().ifBlank { "AYANA Presentation" }
        val content = arguments.optString("content").trim()
        val slides = slidesFromArguments(arguments, title, content)

        if (slides.isEmpty()) {
            return failure("pptx_content_required")
                .put("kind", "pptx")
                .put("name", filename)
        }
        if (slides.size > MAX_PRESENTATION_SLIDES) {
            return failure("pptx_slide_limit_exceeded")
                .put("kind", "pptx")
                .put("name", filename)
                .put("slide_count", slides.size)
        }

        val workDir = File(appContext.cacheDir, "ayana_office_r10_21_publish")
        if (!workDir.exists() && !workDir.mkdirs()) {
            return failure("pptx_cache_directory_unavailable")
        }
        val temp = File(workDir, "${System.currentTimeMillis()}_${filename}")

        return try {
            createPptx(temp, slides)
            val inspection = inspect(temp, FORMAT_PPTX)
            val expectedText = slides.flatMap { listOf(it.title, it.body) }
                .filter { it.isNotBlank() }
            val semanticVerified = inspection.success &&
                inspection.slideCount == slides.size &&
                expectedText.all { inspection.text.contains(it) }

            if (!semanticVerified) {
                temp.delete()
                return failure("pptx_pre_publish_semantic_verification_failed")
                    .put("kind", "pptx")
                    .put("name", filename)
                    .put("slide_count", slides.size)
            }

            val detail = "pptx_publish:$filename;slides=${slides.size}"
            if (!tryBeginPublish(detail)) {
                temp.delete()
                return failure("pptx_publish_not_authorized")
                    .put("kind", "pptx")
                    .put("name", filename)
            }
            onPublishAccepted(detail)

            val published = publishToDownloads(temp, filename, PPTX_MIME)
            onPublishReconciliationStarted(detail)

            val uri = published.first
            val publishedBytes = published.second
            val localHash = sha256(temp)
            val publishedHash = sha256(uri)
            val reopened = reopenUriToTemp(uri, filename)
            val reopenedInspection = reopened?.let { inspect(it, FORMAT_PPTX) }
            val reopenedSemanticVerified = reopenedInspection?.success == true &&
                reopenedInspection.slideCount == slides.size &&
                expectedText.all { reopenedInspection.text.contains(it) }
            val verified = publishedBytes == temp.length() &&
                localHash.isNotBlank() &&
                localHash == publishedHash &&
                reopenedSemanticVerified

            onPublishReconciled(verified, detail)
            reopened?.delete()
            temp.delete()

            if (!verified) {
                try {
                    appContext.contentResolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                return failure("pptx_publish_reopen_verification_failed")
                    .put("kind", "pptx")
                    .put("name", filename)
                    .put("slide_count", slides.size)
            }

            JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("terminal_status", "SUCCESS")
                .put("kind", "pptx")
                .put("declared_kind", "presentation")
                .put("name", filename)
                .put("mime_type", PPTX_MIME)
                .put("artifact_reference", uri.toString())
                .put("bytes", publishedBytes)
                .put("sha256", publishedHash)
                .put("slide_count", slides.size)
                .put("office_document_engine_version", VERSION)
                .put("office_contract_version", CONTRACT_VERSION)
                .put("package_reopened_verified", true)
                .put("semantic_content_verified", true)
                .put("message", "PowerPoint PPTX создан, повторно открыт и проверен: $filename")
        } catch (error: Throwable) {
            temp.delete()
            onPublishReconciled(false, "pptx_exception:${error.javaClass.simpleName}")
            failure("pptx_exception:${error.javaClass.simpleName}")
                .put("kind", "pptx")
                .put("name", filename)
                .put("message", error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Exact literal edit for an already available local Office file.
     * Output is written to a new file; source bytes are never modified in place.
     */
    fun replaceExactText(
        source: File,
        output: File,
        format: String,
        from: String,
        to: String
    ): EditResult {
        val normalizedFormat = normalizeFormat(format)
        if (!source.isFile || from.isBlank()) {
            return EditResult(
                false, false, normalizedFormat, 0,
                entrySetPreserved = false,
                untouchedEntriesPreserved = false,
                changedEntries = emptyList(),
                reason = "source_or_target_invalid"
            )
        }

        return when (normalizedFormat) {
            FORMAT_TXT -> replaceTxt(source, output, from, to)
            FORMAT_DOCX, FORMAT_XLSX, FORMAT_PPTX ->
                replaceInOoxmlPackage(source, output, normalizedFormat, from, to)
            else -> EditResult(
                false, false, normalizedFormat, 0,
                entrySetPreserved = false,
                untouchedEntriesPreserved = false,
                changedEntries = emptyList(),
                reason = "format_not_editable_by_office_engine"
            )
        }
    }

    fun inspect(file: File, format: String): Inspection {
        val normalized = normalizeFormat(format)
        return try {
            when (normalized) {
                FORMAT_TXT -> {
                    val text = file.readText(Charsets.UTF_8)
                    Inspection(FORMAT_TXT, true, text, 1)
                }
                FORMAT_DOCX -> inspectZipText(
                    file = file,
                    format = FORMAT_DOCX,
                    include = { it == "word/document.xml" },
                    textRegex = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)
                )
                FORMAT_XLSX -> inspectXlsx(file)
                FORMAT_PPTX -> inspectPptx(file)
                FORMAT_PDF -> inspectScratchPdf(file)
                else -> Inspection(normalized, false, "", 0, reason = "unsupported_format")
            }
        } catch (error: Throwable) {
            Inspection(normalized, false, "", 0, reason = error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Fully local, reversible acceptance. Nothing is published and the scratch directory
     * is removed before the report is returned.
     */
    fun runScratchRoundTripAcceptance(): JSONObject {
        val root = File(
            appContext.cacheDir,
            "ayana_r10_21_acceptance_${System.currentTimeMillis()}"
        )
        root.mkdirs()

        val markerBefore = "AYANA_R10_21_VALUE_100"
        val markerAfter = "AYANA_R10_21_VALUE_125"

        var txtOk = false
        var docxOk = false
        var xlsxOk = false
        var pptxOk = false
        var pdfOk = false
        var docxStructure = false
        var xlsxStructure = false
        var pptxStructure = false
        var pptxSlides = 0
        var cleanup = false

        try {
            val txtSource = File(root, "source.txt")
            val txtEdited = File(root, "edited.txt")
            txtSource.writeText("Office 2.0 $markerBefore", Charsets.UTF_8)
            val txtEdit = replaceExactText(txtSource, txtEdited, FORMAT_TXT, markerBefore, markerAfter)
            val txtInspection = inspect(txtEdited, FORMAT_TXT)
            txtOk = txtEdit.verified && txtInspection.success && markerAfter in txtInspection.text && markerBefore !in txtInspection.text

            val docxSource = File(root, "source.docx")
            val docxEdited = File(root, "edited.docx")
            createDocx(docxSource, "AYANA Office 2.0", "DOCX $markerBefore")
            val docxEdit = replaceExactText(docxSource, docxEdited, FORMAT_DOCX, markerBefore, markerAfter)
            val docxInspection = inspect(docxEdited, FORMAT_DOCX)
            docxStructure = docxEdit.entrySetPreserved && docxEdit.untouchedEntriesPreserved
            docxOk = docxEdit.verified && docxStructure && docxInspection.success && markerAfter in docxInspection.text

            val xlsxSource = File(root, "source.xlsx")
            val xlsxEdited = File(root, "edited.xlsx")
            createXlsx(
                xlsxSource,
                headers = listOf("Параметр", "Значение"),
                rows = listOf(listOf("Маркер", markerBefore), listOf("Число", "125")),
                numericColumns = setOf(1),
                forceTextCells = setOf(1 to 1)
            )
            val xlsxEdit = replaceExactText(xlsxSource, xlsxEdited, FORMAT_XLSX, markerBefore, markerAfter)
            val xlsxInspection = inspect(xlsxEdited, FORMAT_XLSX)
            xlsxStructure = xlsxEdit.entrySetPreserved && xlsxEdit.untouchedEntriesPreserved
            xlsxOk = xlsxEdit.verified && xlsxStructure && xlsxInspection.success && markerAfter in xlsxInspection.text

            val pptxSource = File(root, "source.pptx")
            val pptxEdited = File(root, "edited.pptx")
            createPptx(
                pptxSource,
                listOf(
                    Slide("AYANA Office 2.0", "PowerPoint round-trip"),
                    Slide("Проверка PPTX", markerBefore)
                )
            )
            val pptxEdit = replaceExactText(pptxSource, pptxEdited, FORMAT_PPTX, markerBefore, markerAfter)
            val pptxInspection = inspect(pptxEdited, FORMAT_PPTX)
            pptxSlides = pptxInspection.slideCount
            pptxStructure = pptxEdit.entrySetPreserved && pptxEdit.untouchedEntriesPreserved
            pptxOk = pptxEdit.verified && pptxStructure && pptxInspection.success &&
                pptxInspection.slideCount == 2 && markerAfter in pptxInspection.text

            val pdf = File(root, "probe.pdf")
            createScratchPdf(pdf, "AYANA_R10_21_PDF_VERIFIED")
            val pdfInspection = inspect(pdf, FORMAT_PDF)
            pdfOk = pdfInspection.success && "AYANA_R10_21_PDF_VERIFIED" in pdfInspection.text
        } finally {
            cleanup = deleteRecursivelyVerified(root)
        }

        val accepted = txtOk && docxOk && xlsxOk && pptxOk && pdfOk && cleanup
        return JSONObject()
            .put("success", accepted)
            .put("verified", accepted)
            .put("office_document_engine_version", VERSION)
            .put("office_contract_version", CONTRACT_VERSION)
            .put("txt_roundtrip_verified", txtOk)
            .put("docx_roundtrip_verified", docxOk)
            .put("docx_package_structure_preserved", docxStructure)
            .put("xlsx_roundtrip_verified", xlsxOk)
            .put("xlsx_package_structure_preserved", xlsxStructure)
            .put("pptx_roundtrip_verified", pptxOk)
            .put("pptx_package_structure_preserved", pptxStructure)
            .put("pptx_slide_count", pptxSlides)
            .put("pdf_structural_probe_verified", pdfOk)
            .put("scratch_cleanup_verified", cleanup)
            .put("source_modified_in_place", false)
            .put("document_text_grants_action_authority", false)
            .put("network_required", false)
    }

    fun selfTest(): Boolean {
        val escaped = xmlEscape("A&B <C> \"D\"")
        if (escaped != "A&amp;B &lt;C&gt; &quot;D&quot;") return false
        if (xmlUnescape(escaped) != "A&B <C> \"D\"") return false
        if (enforceExtension("test", "pptx") != "test.pptx") return false
        if (enforceExtension("test.PPTX", "pptx") != "test.PPTX") return false
        if (normalizeFormat("PowerPoint") != FORMAT_PPTX) return false
        return true
    }

    private fun slidesFromArguments(
        arguments: JSONObject,
        fallbackTitle: String,
        fallbackContent: String
    ): List<Slide> {
        val rows = arguments.optJSONArray("rows") ?: JSONArray()
        val result = mutableListOf<Slide>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONArray(index) ?: continue
            val title = row.optString(0).trim()
            val body = row.optString(1).trim()
            if (title.isBlank() && body.isBlank()) continue
            result += Slide(
                title = title.ifBlank { "Слайд ${index + 1}" },
                body = body
            )
        }
        if (result.isNotEmpty()) return result.take(MAX_PRESENTATION_SLIDES)
        if (fallbackTitle.isBlank() && fallbackContent.isBlank()) return emptyList()
        return listOf(Slide(fallbackTitle.ifBlank { "AYANA Presentation" }, fallbackContent))
    }

    private fun replaceTxt(
        source: File,
        output: File,
        from: String,
        to: String
    ): EditResult {
        val original = source.readText(Charsets.UTF_8)
        val count = countOccurrences(original, from)
        if (count <= 0) {
            return EditResult(false, false, FORMAT_TXT, 0, true, true, emptyList(), "target_not_found")
        }
        output.parentFile?.mkdirs()
        output.writeText(original.replace(from, to), Charsets.UTF_8)
        val inspected = inspect(output, FORMAT_TXT)
        val verified = inspected.success && to in inspected.text && from !in inspected.text
        return EditResult(verified, verified, FORMAT_TXT, count, true, true, listOf(output.name), if (verified) "" else "postcondition_failed")
    }

    private fun replaceInOoxmlPackage(
        source: File,
        output: File,
        format: String,
        from: String,
        to: String
    ): EditResult {
        val allowed = when (format) {
            FORMAT_DOCX -> { name: String -> name == "word/document.xml" }
            FORMAT_XLSX -> { name: String -> name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/") && name.endsWith(".xml") }
            FORMAT_PPTX -> { name: String -> name.startsWith("ppt/slides/slide") && name.endsWith(".xml") }
            else -> { _: String -> false }
        }

        val beforeHashes = zipEntryHashes(source)
        val fromEscaped = xmlEscape(from)
        val toEscaped = xmlEscape(to)
        var replacements = 0
        val changed = mutableListOf<String>()

        output.parentFile?.mkdirs()
        ZipFile(source).use { inputZip ->
            ZipOutputStream(FileOutputStream(output)).use { out ->
                val entries = inputZip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val bytes = inputZip.getInputStream(entry).use { it.readBytes() }
                    var outputBytes = bytes
                    if (!entry.isDirectory && allowed(entry.name)) {
                        val xml = bytes.toString(Charsets.UTF_8)
                        val localCount = countOccurrences(xml, fromEscaped)
                        if (localCount > 0) {
                            outputBytes = xml.replace(fromEscaped, toEscaped).toByteArray(Charsets.UTF_8)
                            replacements += localCount
                            changed += entry.name
                        }
                    }
                    val newEntry = ZipEntry(entry.name)
                    newEntry.time = entry.time
                    out.putNextEntry(newEntry)
                    if (!entry.isDirectory) out.write(outputBytes)
                    out.closeEntry()
                }
            }
        }

        if (replacements <= 0) {
            output.delete()
            return EditResult(false, false, format, 0, true, true, emptyList(), "target_not_found")
        }

        val afterHashes = zipEntryHashes(output)
        val entrySetPreserved = beforeHashes.keys == afterHashes.keys
        val changedSet = changed.toSet()
        val untouchedPreserved = entrySetPreserved && beforeHashes.keys
            .filter { it !in changedSet }
            .all { beforeHashes[it] == afterHashes[it] }
        val inspection = inspect(output, format)
        val verified = entrySetPreserved && untouchedPreserved && inspection.success &&
            to in inspection.text && from !in inspection.text

        return EditResult(
            success = verified,
            verified = verified,
            format = format,
            replacements = replacements,
            entrySetPreserved = entrySetPreserved,
            untouchedEntriesPreserved = untouchedPreserved,
            changedEntries = changed.distinct(),
            reason = if (verified) "" else "ooxml_postcondition_failed"
        )
    }

    private fun inspectZipText(
        file: File,
        format: String,
        include: (String) -> Boolean,
        textRegex: Regex
    ): Inspection {
        val collected = mutableListOf<String>()
        var count = 0
        ZipFile(file).use { zip ->
            count = zip.size()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory || !include(entry.name)) continue
                val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                textRegex.findAll(xml).forEach { match ->
                    collected += xmlUnescape(match.groupValues[1])
                }
            }
        }
        val text = collected.joinToString("\n").trim()
        return Inspection(format, text.isNotBlank(), text, count, reason = if (text.isBlank()) "readable_text_missing" else "")
    }

    private fun inspectXlsx(file: File): Inspection {
        val collected = mutableListOf<String>()
        var entriesCount = 0
        var sheets = 0
        ZipFile(file).use { zip ->
            entriesCount = zip.size()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (entry.name.startsWith("xl/worksheets/sheet") && entry.name.endsWith(".xml")) sheets++
                if (
                    entry.name == "xl/sharedStrings.xml" ||
                    entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml")
                ) {
                    val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml).forEach { collected += xmlUnescape(it.groupValues[1]) }
                    Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml).forEach { collected += xmlUnescape(it.groupValues[1]) }
                }
            }
        }
        val text = collected.joinToString("\n").trim()
        return Inspection(FORMAT_XLSX, sheets > 0 && text.isNotBlank(), text, entriesCount, sheetCount = sheets, reason = if (sheets <= 0) "worksheet_missing" else "")
    }

    private fun inspectPptx(file: File): Inspection {
        val collected = mutableListOf<String>()
        var entriesCount = 0
        var slides = 0
        ZipFile(file).use { zip ->
            entriesCount = zip.size()
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) continue
                if (entry.name.matches(Regex("ppt/slides/slide\\d+\\.xml"))) {
                    slides++
                    val xml = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(xml).forEach { collected += xmlUnescape(it.groupValues[1]) }
                }
            }
        }
        val text = collected.joinToString("\n").trim()
        return Inspection(FORMAT_PPTX, slides > 0 && text.isNotBlank(), text, entriesCount, slideCount = slides, reason = if (slides <= 0) "slide_missing" else "")
    }

    private fun inspectScratchPdf(file: File): Inspection {
        val bytes = file.readBytes()
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val marker = Regex("%AYANA_TEXT_BEGIN:(.*?):AYANA_TEXT_END%")
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()
        val ok = raw.startsWith("%PDF-1.4") && marker.isNotBlank() && raw.contains("%%EOF")
        return Inspection(FORMAT_PDF, ok, marker, 1, reason = if (ok) "" else "pdf_probe_invalid")
    }

    private fun createDocx(file: File, title: String, body: String) {
        val entries = linkedMapOf<String, String>()
        entries["[Content_Types].xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>"""
        entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>"""
        entries["word/document.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:r><w:t>${xmlEscape(title)}</w:t></w:r></w:p>
<w:p><w:r><w:t>${xmlEscape(body)}</w:t></w:r></w:p>
<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/></w:sectPr>
</w:body></w:document>"""
        writeZip(file, entries)
    }

    private fun createXlsx(
        file: File,
        headers: List<String>,
        rows: List<List<String>>,
        numericColumns: Set<Int> = emptySet(),
        forceTextCells: Set<Pair<Int, Int>> = emptySet()
    ) {
        val sheetXml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            fun appendRow(rowIndex: Int, values: List<String>, dataRowIndex: Int) {
                append("<row r=\"").append(rowIndex).append("\">")
                values.forEachIndexed { col, value ->
                    val ref = excelColumnName(col + 1) + rowIndex
                    val forceText = dataRowIndex >= 0 && (dataRowIndex to col) in forceTextCells
                    val numeric = dataRowIndex >= 0 && col in numericColumns && !forceText && value.toDoubleOrNull() != null
                    if (numeric) {
                        append("<c r=\"").append(ref).append("\"><v>")
                            .append(xmlEscape(value)).append("</v></c>")
                    } else {
                        append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>")
                            .append(xmlEscape(value)).append("</t></is></c>")
                    }
                }
                append("</row>")
            }
            appendRow(1, headers, -1)
            rows.forEachIndexed { index, row -> appendRow(index + 2, row, index) }
            append("</sheetData></worksheet>")
        }

        val entries = linkedMapOf<String, String>()
        entries["[Content_Types].xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""
        entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""
        entries["xl/workbook.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets><sheet name="AYANA" sheetId="1" r:id="rId1"/></sheets></workbook>"""
        entries["xl/_rels/workbook.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
</Relationships>"""
        entries["xl/worksheets/sheet1.xml"] = sheetXml
        writeZip(file, entries)
    }

    private fun createPptx(file: File, slides: List<Slide>) {
        require(slides.isNotEmpty()) { "pptx_slides_required" }
        val entries = linkedMapOf<String, String>()

        val slideOverrides = slides.indices.joinToString("") { index ->
            "<Override PartName=\"/ppt/slides/slide${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
        }
        entries["[Content_Types].xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
<Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
<Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
<Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
$slideOverrides
</Types>"""
        entries["_rels/.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""

        val slideIds = slides.indices.joinToString("") { index ->
            "<p:sldId id=\"${256 + index}\" r:id=\"rId${index + 2}\"/>"
        }
        entries["ppt/presentation.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>
<p:sldIdLst>$slideIds</p:sldIdLst>
<p:sldSz cx="12192000" cy="6858000" type="screen16x9"/><p:notesSz cx="6858000" cy="9144000"/>
</p:presentation>"""

        val presentationRels = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
            append("<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster\" Target=\"slideMasters/slideMaster1.xml\"/>")
            slides.indices.forEach { index ->
                append("<Relationship Id=\"rId${index + 2}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide${index + 1}.xml\"/>")
            }
            append("</Relationships>")
        }
        entries["ppt/_rels/presentation.xml.rels"] = presentationRels

        entries["ppt/slideMasters/slideMaster1.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld>
<p:sldLayoutIdLst><p:sldLayoutId id="1" r:id="rId1"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles>
</p:sldMaster>"""
        entries["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>"""
        entries["ppt/slideLayouts/slideLayout1.xml"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank" preserve="1">
<p:cSld name="Blank"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>"""
        entries["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>"""
        entries["ppt/theme/theme1.xml"] = minimalThemeXml()

        slides.forEachIndexed { index, slide ->
            entries["ppt/slides/slide${index + 1}.xml"] = slideXml(index + 1, slide)
            entries["ppt/slides/_rels/slide${index + 1}.xml.rels"] = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>"""
        }

        writeZip(file, entries)
    }

    private fun slideXml(index: Int, slide: Slide): String {
        val title = xmlEscape(slide.title)
        val body = xmlEscape(slide.body)
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
<p:cSld><p:spTree>
<p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>
<p:sp><p:nvSpPr><p:cNvPr id="2" name="Title $index"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="762000" y="457200"/><a:ext cx="10668000" cy="1143000"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="ru-RU" sz="2800" b="1"/><a:t>$title</a:t></a:r><a:endParaRPr lang="ru-RU"/></a:p></p:txBody></p:sp>
<p:sp><p:nvSpPr><p:cNvPr id="3" name="Body $index"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x="762000" y="1905000"/><a:ext cx="10668000" cy="3962400"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom><a:noFill/><a:ln><a:noFill/></a:ln></p:spPr><p:txBody><a:bodyPr wrap="square"/><a:lstStyle/><a:p><a:r><a:rPr lang="ru-RU" sz="1800"/><a:t>$body</a:t></a:r><a:endParaRPr lang="ru-RU"/></a:p></p:txBody></p:sp>
</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    private fun minimalThemeXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="AYANA">
<a:themeElements><a:clrScheme name="AYANA"><a:dk1><a:sysClr val="windowText" lastClr="000000"/></a:dk1><a:lt1><a:sysClr val="window" lastClr="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F1F1F"/></a:dk2><a:lt2><a:srgbClr val="F3F3F3"/></a:lt2><a:accent1><a:srgbClr val="4472C4"/></a:accent1><a:accent2><a:srgbClr val="ED7D31"/></a:accent2><a:accent3><a:srgbClr val="A5A5A5"/></a:accent3><a:accent4><a:srgbClr val="FFC000"/></a:accent4><a:accent5><a:srgbClr val="5B9BD5"/></a:accent5><a:accent6><a:srgbClr val="70AD47"/></a:accent6><a:hlink><a:srgbClr val="0563C1"/></a:hlink><a:folHlink><a:srgbClr val="954F72"/></a:folHlink></a:clrScheme>
<a:fontScheme name="AYANA"><a:majorFont><a:latin typeface="Aptos Display"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont><a:minorFont><a:latin typeface="Aptos"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont></a:fontScheme>
<a:fmtScheme name="AYANA"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill><a:prstDash val="solid"/></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements><a:objectDefaults/><a:extraClrSchemeLst/></a:theme>"""

    private fun createScratchPdf(file: File, marker: String) {
        val safeMarker = marker.replace(Regex("[^A-Za-z0-9_.:-]"), "_").take(200)
        val stream = "BT /F1 18 Tf 72 720 Td (${pdfEscape(safeMarker)}) Tj ET"
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>",
            "<< /Length ${stream.toByteArray(Charsets.ISO_8859_1).size} >>\nstream\n$stream\nendstream",
            "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        )
        val out = ByteArrayOutputStream()
        fun write(value: String) = out.write(value.toByteArray(Charsets.ISO_8859_1))
        write("%PDF-1.4\n%AYANA_TEXT_BEGIN:$safeMarker:AYANA_TEXT_END%\n")
        val offsets = mutableListOf<Int>()
        objects.forEachIndexed { index, obj ->
            offsets += out.size()
            write("${index + 1} 0 obj\n$obj\nendobj\n")
        }
        val xref = out.size()
        write("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { write(String.format(Locale.US, "%010d 00000 n \n", it)) }
        write("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        file.writeBytes(out.toByteArray())
    }

    private fun publishToDownloads(source: File, filename: String, mimeType: String): Pair<Uri, Long> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw IllegalStateException("downloads_publish_requires_android_q")
        }
        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AYANA")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("downloads_insert_failed")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } ?: throw IllegalStateException("downloads_output_stream_unavailable")
            val done = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, done, null, null)
            var size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            if (size < 0L) {
                size = resolver.openInputStream(uri)?.use { input ->
                    var total = 0L
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        total += count
                    }
                    total
                } ?: -1L
            }
            return uri to size
        } catch (error: Throwable) {
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            throw error
        }
    }

    private fun reopenUriToTemp(uri: Uri, filename: String): File? {
        return try {
            val target = File(appContext.cacheDir, "reopen_${System.nanoTime()}_${sanitizeFilename(filename)}")
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            } ?: return null
            target
        } catch (_: Throwable) {
            null
        }
    }

    private fun sha256(uri: Uri): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val stream = appContext.contentResolver.openInputStream(uri) ?: return ""
            stream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    digest.update(buffer, 0, count)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        } catch (_: Throwable) {
            ""
        }
    }

    private fun sha256(file: File): String = try {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count <= 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    } catch (_: Throwable) {
        ""
    }

    private fun zipEntryHashes(file: File): LinkedHashMap<String, String> {
        val result = linkedMapOf<String, String>()
        ZipFile(file).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (entry.isDirectory) {
                    result[entry.name] = "DIR"
                } else {
                    val digest = MessageDigest.getInstance("SHA-256")
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = input.read(buffer)
                            if (count <= 0) break
                            digest.update(buffer, 0, count)
                        }
                    }
                    result[entry.name] = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                }
            }
        }
        return result
    }

    private fun writeZip(file: File, entries: Map<String, String>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(file)).use { out ->
            entries.forEach { (name, content) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
        }
    }

    private fun deleteRecursivelyVerified(root: File): Boolean {
        try {
            if (root.exists()) root.deleteRecursively()
        } catch (_: Throwable) {
        }
        return !root.exists()
    }

    private fun excelColumnName(index: Int): String {
        var n = index
        val builder = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            builder.append(('A'.code + remainder).toChar())
            n = (n - 1) / 26
        }
        return builder.reverse().toString()
    }

    private fun normalizeFormat(value: String): String = when (
        value.trim().lowercase(Locale.ROOT).replace(".", "")
    ) {
        "txt", "text" -> FORMAT_TXT
        "docx", "word", "microsoft word" -> FORMAT_DOCX
        "xlsx", "excel", "microsoft excel" -> FORMAT_XLSX
        "pptx", "ppt", "powerpoint", "power point", "microsoft powerpoint" -> FORMAT_PPTX
        "pdf" -> FORMAT_PDF
        else -> value.trim().lowercase(Locale.ROOT)
    }

    private fun sanitizeFilename(value: String): String {
        val cleaned = value
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]+"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(140)
        return cleaned.ifBlank { "AYANA_document" }
    }

    private fun enforceExtension(filename: String, extension: String): String =
        if (filename.lowercase(Locale.ROOT).endsWith(".$extension")) filename else "$filename.$extension"

    private fun countOccurrences(text: String, target: String): Int {
        if (target.isEmpty()) return 0
        var count = 0
        var index = 0
        while (true) {
            val found = text.indexOf(target, index)
            if (found < 0) break
            count++
            index = found + target.length
        }
        return count
    }

    private fun xmlEscape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun xmlUnescape(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun pdfEscape(value: String): String = value
        .replace("\\", "\\\\")
        .replace("(", "\\(")
        .replace(")", "\\)")

    private fun failure(reason: String): JSONObject = JSONObject()
        .put("success", false)
        .put("verified", false)
        .put("terminal_status", "ERROR")
        .put("reason", reason)
        .put("office_document_engine_version", VERSION)
        .put("office_contract_version", CONTRACT_VERSION)

    companion object {
        const val VERSION = "2.0"
        const val CONTRACT_VERSION = 1

        const val FORMAT_TXT = "txt"
        const val FORMAT_DOCX = "docx"
        const val FORMAT_XLSX = "xlsx"
        const val FORMAT_PPTX = "pptx"
        const val FORMAT_PDF = "pdf"

        const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        private const val MAX_PRESENTATION_SLIDES = 30
    }
}
