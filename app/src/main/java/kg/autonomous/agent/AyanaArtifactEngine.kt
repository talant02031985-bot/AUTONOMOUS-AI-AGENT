package kg.autonomous.agent

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * AYANA Artifact Engine v1.4 — TYPED XLSX CELLS + CONTENT-VERIFIED PUBLISH.
 *
 * Reliability contract:
 * - generate into AYANA private cache first;
 * - never overwrite arbitrary user files;
 * - publish only into Downloads/AYANA;
 * - verify a non-empty private artifact before publication;
 * - reopen the published artifact, verify byte count + SHA-256 against the private source;
 * - return a structured content:// reference + typed metadata only after publish verification;
 * - unsupported/invalid requests fail closed and never return fake SUCCESS.
 *
 * Supported formats:
 * TXT, DOCX, PDF, XLSX, JPEG and GRAPH (JPEG chart).
 * XLSX supports explicit per-column cell semantics: text, number, boolean, auto.
 * No external Office/PDF dependency is required.
 */
class AyanaArtifactEngine(
    context: Context
) {

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val tempRoot = File(appContext.cacheDir, CACHE_DIR_NAME)

    init {
        if (!tempRoot.exists()) {
            tempRoot.mkdirs()
        }
        cleanupOldTempFiles()
    }

    fun create(
        arguments: JSONObject,
        tryBeginPublish: (String) -> Boolean = { true },
        onPublishAccepted: (String) -> Unit = {},
        onPublishReconciliationStarted: (String) -> Unit = {},
        onPublishReconciled: (Boolean, String) -> Unit = { _, _ -> }
    ): JSONObject {
        val rawKind = arguments.optString("kind").trim().lowercase(Locale.ROOT)
        val kind = normalizeKind(rawKind)
            ?: return failure(
                "Неподдерживаемый формат файла: ${rawKind.ifBlank { "не указан" }}",
                "unsupported_artifact_kind"
            )

        val spec = specFor(kind)
        val requestedName = arguments.optString("filename").trim()
        val title = arguments.optString("title").trim().take(MAX_TITLE_CHARS)
        val content = arguments.optString("content").take(MAX_CONTENT_CHARS)
        val columns = jsonStringArray(arguments.optJSONArray("columns"), MAX_COLUMNS)
        val rows = jsonRows(arguments.optJSONArray("rows"), MAX_ROWS, MAX_COLUMNS)
        val columnTypes = jsonColumnTypes(arguments.optJSONArray("column_types"), MAX_COLUMNS)
        val chartType = arguments.optString("chart_type", "none")
            .trim()
            .lowercase(Locale.ROOT)
            .let { value -> if (value == "line") "line" else "bar" }

        if (kind == Kind.XLSX && columnTypes.isNotEmpty()) {
            val expectedWidth = max(
                columns.size,
                rows.maxOfOrNull { it.size } ?: 0
            )
            if (columnTypes.size != expectedWidth) {
                return failure(
                    "Типы столбцов XLSX не соответствуют ширине таблицы.",
                    "xlsx_column_types_mismatch"
                )
            }
        }

        if (
            content.isBlank() &&
            rows.isEmpty() &&
            kind !in setOf(Kind.JPEG, Kind.GRAPH)
        ) {
            return failure(
                "Нет содержимого для создания файла.",
                "empty_artifact_content"
            )
        }

        if (kind == Kind.GRAPH && rows.isEmpty()) {
            return failure(
                "Для графика нужны данные в rows.",
                "graph_data_missing"
            )
        }

        val fileName = sanitizeFileName(
            requested = requestedName,
            fallbackBase = title.ifBlank { DEFAULT_BASENAME },
            extension = spec.extension
        )

        val tempFile = File(
            tempRoot,
            "${UUID.randomUUID()}-${fileName.takeLast(120)}"
        )

        return try {
            when (kind) {
                Kind.TXT -> writeTxt(tempFile, title, content, columns, rows)
                Kind.DOCX -> writeDocx(tempFile, title, content, columns, rows)
                Kind.PDF -> writePdf(tempFile, title, content, columns, rows)
                Kind.XLSX -> writeXlsx(tempFile, title, content, columns, rows, columnTypes)
                Kind.JPEG -> writeJpeg(tempFile, title, content, columns, rows)
                Kind.GRAPH -> writeGraphJpeg(tempFile, title, columns, rows, chartType)
            }

            val result = finalizePreparedArtifact(
                sourceFile = tempFile,
                spec = spec,
                fileName = fileName,
                tryBeginPublish = tryBeginPublish,
                onPublishAccepted = onPublishAccepted,
                onPublishReconciliationStarted = onPublishReconciliationStarted,
                onPublishReconciled = onPublishReconciled
            )
            tempFile.delete()
            result
        } catch (error: Exception) {
            tempFile.delete()
            failure(
                "Не удалось создать файл: ${error.message ?: "ошибка генератора"}",
                error.javaClass.simpleName.ifBlank { "artifact_generation_error" }
            )
        }
    }

    /**
     * Publish an already prepared artifact produced by another trusted AYANA
     * local executor (for example DOCX transform). This method never accepts a
     * model-provided path: callers pass an actual File object from local code.
     */
    fun publishPreparedFile(
        sourceFile: File,
        kind: String,
        filename: String,
        declaredKindOverride: String = "",
        tryBeginPublish: (String) -> Boolean = { true },
        onPublishAccepted: (String) -> Unit = {},
        onPublishReconciliationStarted: (String) -> Unit = {},
        onPublishReconciled: (Boolean, String) -> Unit = { _, _ -> }
    ): JSONObject {
        val normalizedKind = normalizeKind(kind.trim().lowercase(Locale.ROOT))
            ?: return failure(
                "Неподдерживаемый формат подготовленного файла: $kind",
                "unsupported_prepared_artifact_kind"
            )
        val baseSpec = specFor(normalizedKind)
        val spec = if (declaredKindOverride.isBlank()) {
            baseSpec
        } else {
            baseSpec.copy(declaredKind = declaredKindOverride.take(80))
        }
        val fileName = sanitizeFileName(
            requested = filename,
            fallbackBase = DEFAULT_BASENAME,
            extension = spec.extension
        )
        return finalizePreparedArtifact(
            sourceFile = sourceFile,
            spec = spec,
            fileName = fileName,
            tryBeginPublish = tryBeginPublish,
            onPublishAccepted = onPublishAccepted,
            onPublishReconciliationStarted = onPublishReconciliationStarted,
            onPublishReconciled = onPublishReconciled
        )
    }

    private fun finalizePreparedArtifact(
        sourceFile: File,
        spec: Spec,
        fileName: String,
        tryBeginPublish: (String) -> Boolean,
        onPublishAccepted: (String) -> Unit,
        onPublishReconciliationStarted: (String) -> Unit,
        onPublishReconciled: (Boolean, String) -> Unit
    ): JSONObject {
        val verification = verifyPrivateArtifact(sourceFile, spec)
        if (!verification.ok) {
            return failure(
                "Подготовленный файл не прошёл локальную проверку.",
                verification.reason
            )
        }

        val sha256 = sha256(sourceFile)
        val publishDetail =
            "name=${fileName.take(160)}; mime=${spec.mimeType}; bytes=${verification.sizeBytes}"

        // Atomic side-effect gate shared with the Execution Kernel. STOP that wins
        // before this point prevents MediaStore publication. STOP after this point
        // may stop presentation, but factual publication must be reconciled first.
        if (!tryBeginPublish(publishDetail)) {
            return failure(
                "Сохранение файла отменено до публикации.",
                "cancelled_before_artifact_publish"
            )
        }

        val published = publishToDownloads(
            source = sourceFile,
            displayName = fileName,
            mimeType = spec.mimeType
        )

        if (!published.success || published.uri == null) {
            published.reconciledCommitted?.let { committed ->
                onPublishReconciled(
                    committed,
                    "publish_failed; $publishDetail; reason=${published.reason.take(160)}; " +
                        "reconciled_committed=$committed"
                )
            }
            return failure(
                published.message.ifBlank { "Не удалось сохранить файл в Downloads/AYANA." },
                published.reason.ifBlank { "artifact_publish_failed" }
            )
        }

        val reference = published.uri.toString()
        onPublishAccepted("uri=${reference.take(240)}; $publishDetail")
        onPublishReconciliationStarted("verify_published_uri; uri=${reference.take(240)}")

        val publishedVerification =
            verifyPublishedArtifact(
                uri = published.uri,
                expectedSize = verification.sizeBytes,
                expectedSha256 = sha256
            )

        if (!publishedVerification.ok) {
            val removed =
                try {
                    resolver.delete(published.uri, null, null) > 0
                } catch (_: Exception) {
                    false
                }
            val presenceAfterCleanup =
                if (removed) {
                    false
                } else {
                    probePublishedPresence(published.uri)
                }

            presenceAfterCleanup?.let { committed ->
                onPublishReconciled(
                    committed,
                    "publish_verification_failed; uri=${reference.take(240)}; " +
                        "reason=${publishedVerification.reason}; cleanup_removed=$removed; " +
                        "reconciled_committed=$committed"
                )
            }
            return failure(
                "Файл был создан, но содержимое в Downloads/AYANA не совпало с подготовленным файлом.",
                publishedVerification.reason
            )
        }

        val finalSize = publishedVerification.sizeBytes

        onPublishReconciled(
            true,
            "published_verified; uri=${reference.take(240)}; bytes=$finalSize; sha256=$sha256"
        )

        val createdFile = JSONObject()
            .put("reference", reference)
            .put("name", fileName)
            .put("mime_type", spec.mimeType)
            .put("declared_kind", spec.declaredKind)
            .put("kind", spec.declaredKind)
            .put("artifact_type", spec.declaredKind)
            .put("size_bytes", finalSize)
            .put("sha256", sha256)
            .put("location", "Downloads/AYANA")

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("message", "Файл создан: $fileName")
            .put("artifact_reference", reference)
            .put("artifact_uri", reference)
            .put("created_file", createdFile)
            .put("output_file", createdFile)
            .put("name", fileName)
            .put("mime_type", spec.mimeType)
            .put("declared_kind", spec.declaredKind)
            .put("kind", spec.declaredKind)
            .put("artifact_type", spec.declaredKind)
            .put("size_bytes", finalSize)
            .put("sha256", sha256)
            .put("location", "Downloads/AYANA")
    }

    private fun writeTxt(
        file: File,
        title: String,
        content: String,
        columns: List<String>,
        rows: List<List<String>>
    ) {
        val text = buildPlainText(title, content, columns, rows)
        file.outputStream().buffered().use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
        }
    }

    private fun writeDocx(
        file: File,
        title: String,
        content: String,
        columns: List<String>,
        rows: List<List<String>>
    ) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            zipText(
                zip,
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>
<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
<Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
</Types>"""
            )
            zipText(
                zip,
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
<Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
</Relationships>"""
            )
            zipText(
                zip,
                "word/_rels/document.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rIdStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
            )
            zipText(
                zip,
                "word/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
<w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/><w:qFormat/></w:style>
<w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/><w:basedOn w:val="Normal"/><w:next w:val="Normal"/><w:qFormat/><w:rPr><w:b/><w:sz w:val="32"/></w:rPr></w:style>
</w:styles>"""
            )

            val body = StringBuilder()
            if (title.isNotBlank()) {
                body.append(wordParagraph(title, "Title"))
            }
            content.lines().forEach { line ->
                body.append(wordParagraph(line))
            }
            if (columns.isNotEmpty() || rows.isNotEmpty()) {
                body.append(wordTable(columns, rows))
            }
            body.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>")

            zipText(
                zip,
                "word/document.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$body</w:body></w:document>"""
            )
            zipText(
                zip,
                "docProps/core.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${xml(title.ifBlank { "AYANA document" })}</dc:title><dc:creator>AYANA AI</dc:creator></cp:coreProperties>"""
            )
            zipText(
                zip,
                "docProps/app.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>AYANA AI</Application></Properties>"""
            )
        }
    }

    private fun writePdf(
        file: File,
        title: String,
        content: String,
        columns: List<String>,
        rows: List<List<String>>
    ) {
        val document = PdfDocument()
        try {
            val lines = buildPlainText(title = "", content = content, columns = columns, rows = rows)
                .lines()
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = 20f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val pageWidth = 595
            val pageHeight = 842
            val left = 48f
            val right = 48f
            val top = 54f
            val bottom = 54f
            val usableWidth = pageWidth - left - right
            var pageNumber = 0
            var page: PdfDocument.Page? = null
            var canvas: Canvas? = null
            var y = top

            fun startPage() {
                page?.let { document.finishPage(it) }
                pageNumber++
                page = document.startPage(
                    PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                )
                canvas = page!!.canvas
                y = top
            }

            startPage()
            if (title.isNotBlank()) {
                wrapText(title, titlePaint, usableWidth).forEach { line ->
                    if (y > pageHeight - bottom - 28f) startPage()
                    canvas!!.drawText(line, left, y, titlePaint)
                    y += 27f
                }
                y += 8f
            }

            for (sourceLine in lines) {
                val wrapped = if (sourceLine.isBlank()) listOf("") else wrapText(sourceLine, bodyPaint, usableWidth)
                for (line in wrapped) {
                    if (y > pageHeight - bottom - 18f) startPage()
                    if (line.isNotBlank()) {
                        canvas!!.drawText(line, left, y, bodyPaint)
                    }
                    y += 18f
                }
            }

            page?.let { document.finishPage(it) }
            FileOutputStream(file).use { out ->
                document.writeTo(out)
            }
        } finally {
            document.close()
        }
    }

    private fun writeXlsx(
        file: File,
        title: String,
        content: String,
        columns: List<String>,
        rows: List<List<String>>,
        columnTypes: List<SpreadsheetCellType>
    ) {
        val sheetRows = mutableListOf<List<String>>()
        if (title.isNotBlank()) sheetRows += listOf(title)
        if (content.isNotBlank()) {
            content.lines().filter { it.isNotBlank() }.forEach { sheetRows += listOf(it) }
        }
        if (columns.isNotEmpty()) sheetRows += columns
        val dataStartIndex = sheetRows.size
        sheetRows += rows

        ZipOutputStream(BufferedOutputStream(FileOutputStream(file))).use { zip ->
            zipText(
                zip,
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""
            )
            zipText(
                zip,
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>"""
            )
            zipText(
                zip,
                "xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="AYANA" sheetId="1" r:id="rId1"/></sheets></workbook>"""
            )
            zipText(
                zip,
                "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
            )

            val sheetXml = StringBuilder()
            sheetXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
            val boundedRows = sheetRows.take(MAX_ROWS + 80)
            val maxSheetColumns = boundedRows.maxOfOrNull { it.size.coerceAtMost(MAX_COLUMNS) } ?: 1
            val dimensionRef = if (boundedRows.isEmpty()) {
                "A1"
            } else {
                "A1:${excelColumn((maxSheetColumns - 1).coerceAtLeast(0))}${boundedRows.size}"
            }
            sheetXml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
            sheetXml.append("<dimension ref=\"").append(dimensionRef).append("\"/><sheetData>")
            boundedRows.forEachIndexed { rowIndex, row ->
                val excelRow = rowIndex + 1
                val isDataRow = rowIndex >= dataStartIndex
                sheetXml.append("<row r=\"").append(excelRow).append("\">")
                row.take(MAX_COLUMNS).forEachIndexed { columnIndex, value ->
                    val ref = excelColumn(columnIndex) + excelRow
                    val type = if (isDataRow) {
                        columnTypes.getOrNull(columnIndex) ?: SpreadsheetCellType.AUTO
                    } else {
                        SpreadsheetCellType.TEXT
                    }
                    sheetXml.append(xlsxCellXml(ref, value.take(MAX_CELL_CHARS), type))
                }
                sheetXml.append("</row>")
            }
            sheetXml.append("</sheetData></worksheet>")
            zipText(zip, "xl/worksheets/sheet1.xml", sheetXml.toString())
        }
    }

    private fun writeJpeg(
        file: File,
        title: String,
        content: String,
        columns: List<String>,
        rows: List<List<String>>
    ) {
        val bitmap = Bitmap.createBitmap(1600, 1000, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(22, 27, 36)
                textSize = 58f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 40, 48)
                textSize = 32f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            var y = 95f
            if (title.isNotBlank()) {
                wrapText(title, titlePaint, 1450f).take(2).forEach { line ->
                    canvas.drawText(line, 75f, y, titlePaint)
                    y += 72f
                }
                y += 15f
            }
            val text = buildPlainText("", content, columns, rows)
            for (sourceLine in text.lines()) {
                val wrapped = if (sourceLine.isBlank()) listOf("") else wrapText(sourceLine, bodyPaint, 1450f)
                for (line in wrapped) {
                    if (y > 925f) break
                    if (line.isNotBlank()) canvas.drawText(line, 75f, y, bodyPaint)
                    y += 44f
                }
                if (y > 925f) break
            }
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)) {
                    throw IllegalStateException("JPEG encoder rejected bitmap")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun writeGraphJpeg(
        file: File,
        title: String,
        columns: List<String>,
        rows: List<List<String>>,
        chartType: String
    ) {
        val series = extractGraphSeries(columns, rows)
            ?: throw IllegalArgumentException("В rows не найден числовой столбец для графика")

        val bitmap = Bitmap.createBitmap(1800, 1100, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)

            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(22, 27, 36)
                textSize = 56f
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
            }
            val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(70, 78, 90)
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }
            val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(220, 224, 230)
                strokeWidth = 2f
            }
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(50, 56, 66)
                textSize = 25f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            }
            val seriesPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(35, 111, 206)
                strokeWidth = 5f
                style = Paint.Style.STROKE
            }
            val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(69, 130, 214)
                style = Paint.Style.FILL
            }

            canvas.drawText(
                title.ifBlank { series.valueName.ifBlank { "График AYANA" } }.take(90),
                90f,
                90f,
                titlePaint
            )

            val left = 145f
            val top = 165f
            val right = 1720f
            val bottom = 910f
            val width = right - left
            val height = bottom - top
            val values = series.values
            var minValue = values.minOrNull() ?: 0.0
            var maxValue = values.maxOrNull() ?: 1.0
            if (minValue > 0.0) minValue = 0.0
            if (maxValue < 0.0) maxValue = 0.0
            if (maxValue == minValue) {
                maxValue += 1.0
                minValue -= 1.0
            }
            val range = maxValue - minValue

            for (i in 0..5) {
                val fraction = i / 5f
                val y = bottom - fraction * height
                canvas.drawLine(left, y, right, y, gridPaint)
                val value = minValue + fraction * range
                canvas.drawText(formatNumber(value), 25f, y + 9f, labelPaint)
            }
            canvas.drawLine(left, top, left, bottom, axisPaint)
            canvas.drawLine(left, bottom, right, bottom, axisPaint)

            val count = values.size
            if (chartType == "line") {
                var previousX: Float? = null
                var previousY: Float? = null
                values.forEachIndexed { index, value ->
                    val x = if (count == 1) (left + right) / 2f else left + index.toFloat() * width / (count - 1)
                    val y = bottom - (((value - minValue) / range).toFloat() * height)
                    if (previousX != null && previousY != null) {
                        canvas.drawLine(previousX!!, previousY!!, x, y, seriesPaint)
                    }
                    canvas.drawCircle(x, y, 8f, fillPaint)
                    previousX = x
                    previousY = y
                }
            } else {
                val slot = width / max(1, count)
                val barWidth = min(90f, slot * 0.62f)
                val zeroY = bottom - (((0.0 - minValue) / range).toFloat() * height)
                values.forEachIndexed { index, value ->
                    val centerX = left + slot * index + slot / 2f
                    val valueY = bottom - (((value - minValue) / range).toFloat() * height)
                    val rectTop = min(zeroY, valueY)
                    val rectBottom = max(zeroY, valueY)
                    canvas.drawRect(centerX - barWidth / 2f, rectTop, centerX + barWidth / 2f, rectBottom, fillPaint)
                }
            }

            val labelEvery = max(1, ceil(count / 10.0).toInt())
            series.labels.forEachIndexed { index, label ->
                if (index % labelEvery == 0 || index == series.labels.lastIndex) {
                    val x = if (chartType == "line" && count > 1) {
                        left + index.toFloat() * width / (count - 1)
                    } else {
                        left + width / max(1, count) * index + width / max(1, count) / 2f
                    }
                    canvas.save()
                    canvas.rotate(-28f, x, bottom + 34f)
                    canvas.drawText(label.take(22), x - 8f, bottom + 34f, labelPaint)
                    canvas.restore()
                }
            }

            if (series.valueName.isNotBlank()) {
                canvas.drawText(series.valueName.take(55), left, 1030f, labelPaint)
            }

            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 94, out)) {
                    throw IllegalStateException("JPEG encoder rejected graph")
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private data class GraphSeries(
        val labels: List<String>,
        val values: List<Double>,
        val valueName: String
    )

    private fun extractGraphSeries(
        columns: List<String>,
        rows: List<List<String>>
    ): GraphSeries? {
        if (rows.isEmpty()) return null
        val maxWidth = rows.maxOfOrNull { it.size } ?: 0
        if (maxWidth == 0) return null

        var valueColumn = -1
        for (columnIndex in 0 until maxWidth) {
            val numeric = rows.count { row ->
                parseNumber(row.getOrNull(columnIndex)) != null
            }
            if (numeric >= max(1, rows.size / 2) && (columnIndex > 0 || maxWidth == 1)) {
                valueColumn = columnIndex
                break
            }
        }
        if (valueColumn < 0) {
            for (columnIndex in 0 until maxWidth) {
                if (rows.any { parseNumber(it.getOrNull(columnIndex)) != null }) {
                    valueColumn = columnIndex
                    break
                }
            }
        }
        if (valueColumn < 0) return null

        val labelColumn = if (valueColumn == 0) -1 else 0
        val labels = mutableListOf<String>()
        val values = mutableListOf<Double>()
        rows.forEachIndexed { index, row ->
            val value = parseNumber(row.getOrNull(valueColumn)) ?: return@forEachIndexed
            values += value
            labels += if (labelColumn >= 0) {
                row.getOrNull(labelColumn).orEmpty().ifBlank { (index + 1).toString() }
            } else {
                (index + 1).toString()
            }
        }
        if (values.isEmpty()) return null

        return GraphSeries(
            labels = labels,
            values = values,
            valueName = columns.getOrNull(valueColumn).orEmpty()
        )
    }

    private fun parseNumber(value: String?): Double? {
        val cleaned = value
            ?.trim()
            ?.replace(" ", "")
            ?.replace("%", "")
            ?.replace(',', '.')
            ?.replace(Regex("[^0-9+\\-.]"), "")
            .orEmpty()
        if (cleaned.isBlank() || cleaned == "." || cleaned == "-" || cleaned == "+") return null
        return cleaned.toDoubleOrNull()
    }

    private fun buildPlainText(
        title: String,
        content: String,
        columns: List<String>,
        rows: List<List<String>>
    ): String = buildString {
        if (title.isNotBlank()) {
            append(title.trim())
            append("\n\n")
        }
        if (content.isNotBlank()) {
            append(content.trim())
        }
        if (columns.isNotEmpty() || rows.isNotEmpty()) {
            if (isNotEmpty() && !endsWith("\n")) append('\n')
            if (isNotEmpty()) append('\n')
            if (columns.isNotEmpty()) {
                append(columns.joinToString("\t"))
                append('\n')
            }
            rows.forEachIndexed { index, row ->
                append(row.joinToString("\t"))
                if (index != rows.lastIndex) append('\n')
            }
        }
    }

    private fun wordParagraph(text: String, style: String = ""): String {
        val pPr = if (style.isNotBlank()) "<w:pPr><w:pStyle w:val=\"${xml(style)}\"/></w:pPr>" else ""
        return "<w:p>$pPr<w:r><w:t xml:space=\"preserve\">${xml(text)}</w:t></w:r></w:p>"
    }

    private fun wordTable(
        columns: List<String>,
        rows: List<List<String>>
    ): String {
        val allRows = mutableListOf<List<String>>()
        if (columns.isNotEmpty()) allRows += columns
        allRows += rows
        if (allRows.isEmpty()) return ""

        val maxColumns = allRows.maxOfOrNull { it.size.coerceAtMost(MAX_COLUMNS) } ?: 1

        return buildString {
            append("<w:tbl><w:tblPr><w:tblBorders>")
            listOf("top", "left", "bottom", "right", "insideH", "insideV").forEach { side ->
                append("<w:").append(side).append(" w:val=\"single\" w:sz=\"4\" w:color=\"B7BDC8\"/>")
            }
            append("</w:tblBorders></w:tblPr><w:tblGrid>")
            repeat(maxColumns) {
                append("<w:gridCol w:w=\"2400\"/>")
            }
            append("</w:tblGrid>")
            allRows.take(MAX_ROWS).forEach { row ->
                append("<w:tr>")
                row.take(MAX_COLUMNS).forEach { cell ->
                    append("<w:tc><w:tcPr/><w:p><w:r><w:t xml:space=\"preserve\">")
                    append(xml(cell.take(MAX_CELL_CHARS)))
                    append("</w:t></w:r></w:p></w:tc>")
                }
                append("</w:tr>")
            }
            append("</w:tbl>")
        }
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        if (text.isBlank()) return listOf("")
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf("")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val candidate = if (current.isBlank()) word else "$current $word"
            if (paint.measureText(candidate) <= maxWidth) {
                current = candidate
            } else {
                if (current.isNotBlank()) lines += current
                if (paint.measureText(word) <= maxWidth) {
                    current = word
                } else {
                    var chunk = ""
                    word.forEach { ch ->
                        val next = chunk + ch
                        if (paint.measureText(next) > maxWidth && chunk.isNotEmpty()) {
                            lines += chunk
                            chunk = ch.toString()
                        } else {
                            chunk = next
                        }
                    }
                    current = chunk
                }
            }
        }
        if (current.isNotBlank()) lines += current
        return lines.ifEmpty { listOf("") }
    }

    private fun publishToDownloads(
        source: File,
        displayName: String,
        mimeType: String
    ): PublishedArtifact {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/AYANA")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return PublishedArtifact(false, null, 0L, "Не удалось создать запись Downloads.", "mediastore_insert_failed", false)
            try {
                val output = resolver.openOutputStream(uri, "w")
                    ?: throw IllegalStateException("MediaStore output stream unavailable")
                output.use { out -> copyFile(source, out) }
                val finish = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
                val finalized = resolver.update(uri, finish, null, null)
                if (finalized <= 0) {
                    throw IllegalStateException("MediaStore did not finalize pending artifact")
                }
                val size = querySize(uri)
                return PublishedArtifact(true, uri, size, "", "", null)
            } catch (error: Exception) {
                val removed =
                    try {
                        resolver.delete(uri, null, null) > 0
                    } catch (_: Exception) {
                        false
                    }
                val reconciledCommitted =
                    if (removed) {
                        false
                    } else {
                        probePublishedPresence(uri)
                    }
                return PublishedArtifact(
                    false,
                    null,
                    0L,
                    error.message ?: "Ошибка MediaStore",
                    "mediastore_write_failed",
                    reconciledCommitted
                )
            }
        }

        @Suppress("DEPRECATION")
        val directory = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AYANA"
        )
        if (!directory.exists() && !directory.mkdirs()) {
            return PublishedArtifact(false, null, 0L, "Не удалось создать Downloads/AYANA.", "downloads_directory_failed", false)
        }
        val target = uniqueLegacyFile(directory, displayName)
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
        return PublishedArtifact(
            true,
            Uri.fromFile(target),
            target.length(),
            "",
            "",
            null
        )
    }

    private data class PublishedArtifact(
        val success: Boolean,
        val uri: Uri?,
        val sizeBytes: Long,
        val message: String,
        val reason: String,
        // null means cleanup/presence could not be reconciled conclusively.
        // In that state the caller must preserve factual ERROR rather than CANCELLED.
        val reconciledCommitted: Boolean?
    )

    private data class Verification(
        val ok: Boolean,
        val sizeBytes: Long,
        val reason: String
    )

    private fun verifyPrivateArtifact(file: File, spec: Spec): Verification {
        if (!file.isFile) return Verification(false, 0L, "artifact_missing")
        val size = file.length()
        if (size < spec.minBytes || size > MAX_ARTIFACT_BYTES) {
            return Verification(false, size, "artifact_size_invalid")
        }
        val header = ByteArray(8)
        val read = FileInputStream(file).use { it.read(header) }
        if (read <= 0) return Verification(false, size, "artifact_unreadable")

        val signatureOk = when (spec.kind) {
            Kind.DOCX, Kind.XLSX -> header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            Kind.PDF -> String(header, 0, min(read, 5), Charsets.ISO_8859_1).startsWith("%PDF-")
            Kind.JPEG, Kind.GRAPH ->
                read >= 2 && header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()
            Kind.TXT -> true
        }
        return if (signatureOk) Verification(true, size, "verified") else Verification(false, size, "artifact_signature_invalid")
    }


    private fun probePublishedPresence(
        uri: Uri
    ): Boolean? {
        return try {
            val input = resolver.openInputStream(uri) ?: return false
            input.use { stream ->
                // Opening a provider-owned stream is enough to prove the published
                // entry still exists; the byte-level content is verified elsewhere.
                stream.read()
            }
            true
        } catch (_: Exception) {
            null
        }
    }

    private fun verifyPublishedArtifact(
        uri: Uri,
        expectedSize: Long,
        expectedSha256: String
    ): Verification {
        if (expectedSize <= 0L || expectedSize > MAX_ARTIFACT_BYTES) {
            return Verification(false, 0L, "published_expected_size_invalid")
        }

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val input = resolver.openInputStream(uri)
                ?: return Verification(false, 0L, "published_artifact_unreadable")

            input.use { stream ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read.toLong()
                    if (total > MAX_ARTIFACT_BYTES) {
                        return Verification(false, total, "published_artifact_too_large")
                    }
                    digest.update(buffer, 0, read)
                }
            }

            if (total <= 0L) {
                return Verification(false, total, "published_artifact_empty")
            }
            if (total != expectedSize) {
                return Verification(false, total, "published_artifact_size_mismatch")
            }

            val actualSha256 =
                digest.digest().joinToString("") { "%02x".format(it) }

            if (!actualSha256.equals(expectedSha256, ignoreCase = true)) {
                return Verification(false, total, "published_artifact_sha256_mismatch")
            }

            val metadataSize = querySize(uri)
            if (metadataSize > 0L && metadataSize != total) {
                return Verification(false, total, "published_artifact_metadata_size_mismatch")
            }

            Verification(true, total, "published_content_verified")
        } catch (_: Exception) {
            Verification(false, 0L, "published_artifact_verification_failed")
        }
    }

    private fun copyFile(source: File, output: OutputStream) {
        BufferedInputStream(FileInputStream(source)).use { input ->
            BufferedOutputStream(output).use { buffered ->
                input.copyTo(buffered, DEFAULT_BUFFER_SIZE)
                buffered.flush()
            }
        }
    }

    private fun querySize(uri: Uri): Long {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else 0L
                } else 0L
            } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun uniqueLegacyFile(directory: File, requestedName: String): File {
        var candidate = File(directory, requestedName)
        if (!candidate.exists()) return candidate
        val dot = requestedName.lastIndexOf('.')
        val base = if (dot > 0) requestedName.substring(0, dot) else requestedName
        val ext = if (dot > 0) requestedName.substring(dot) else ""
        var index = 2
        while (candidate.exists() && index < 1000) {
            candidate = File(directory, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun cleanupOldTempFiles(now: Long = System.currentTimeMillis()) {
        tempRoot.listFiles()?.forEach { file ->
            try {
                if (file.isFile && now - file.lastModified() > CACHE_TTL_MS) file.delete()
            } catch (_: Exception) {
            }
        }
    }

    private fun zipText(zip: ZipOutputStream, path: String, text: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun jsonStringArray(array: JSONArray?, limit: Int): List<String> {
        if (array == null) return emptyList()
        val result = ArrayList<String>(min(array.length(), limit))
        for (i in 0 until min(array.length(), limit)) {
            result += array.optString(i).take(MAX_CELL_CHARS)
        }
        return result
    }

    private fun jsonRows(array: JSONArray?, rowLimit: Int, columnLimit: Int): List<List<String>> {
        if (array == null) return emptyList()
        val result = ArrayList<List<String>>(min(array.length(), rowLimit))
        for (i in 0 until min(array.length(), rowLimit)) {
            val rowArray = array.optJSONArray(i) ?: continue
            result += jsonStringArray(rowArray, columnLimit)
        }
        return result
    }

    private fun jsonColumnTypes(array: JSONArray?, limit: Int): List<SpreadsheetCellType> {
        if (array == null) return emptyList()
        val result = ArrayList<SpreadsheetCellType>(min(array.length(), limit))
        for (i in 0 until min(array.length(), limit)) {
            result += when (array.optString(i).trim().lowercase(Locale.ROOT)) {
                "text" -> SpreadsheetCellType.TEXT
                "number" -> SpreadsheetCellType.NUMBER
                "boolean" -> SpreadsheetCellType.BOOLEAN
                else -> SpreadsheetCellType.AUTO
            }
        }
        return result
    }

    private fun xlsxCellXml(
        ref: String,
        rawValue: String,
        type: SpreadsheetCellType
    ): String {
        if (rawValue.isEmpty()) {
            return "<c r=\"${xml(ref)}\"/>"
        }
        return when (type) {
            SpreadsheetCellType.TEXT -> xlsxTextCell(ref, rawValue)
            SpreadsheetCellType.NUMBER -> {
                if (rawValue.isBlank()) return "<c r=\"${xml(ref)}\"/>"
                val number = strictSpreadsheetNumber(rawValue)
                    ?: throw IllegalArgumentException(
                        "Значение «${rawValue.take(80)}» в числовом столбце не является корректным числом."
                    )
                "<c r=\"${xml(ref)}\"><v>${xml(number)}</v></c>"
            }
            SpreadsheetCellType.BOOLEAN -> {
                if (rawValue.isBlank()) return "<c r=\"${xml(ref)}\"/>"
                val booleanValue = when (rawValue.trim().lowercase(Locale.ROOT)) {
                    "true", "1", "да", "yes" -> "1"
                    "false", "0", "нет", "no" -> "0"
                    else -> throw IllegalArgumentException(
                        "Значение «${rawValue.take(80)}» в логическом столбце не является true/false."
                    )
                }
                "<c r=\"${xml(ref)}\" t=\"b\"><v>$booleanValue</v></c>"
            }
            SpreadsheetCellType.AUTO -> {
                val automaticNumber = safeAutoSpreadsheetNumber(rawValue)
                if (automaticNumber != null) {
                    "<c r=\"${xml(ref)}\"><v>${xml(automaticNumber)}</v></c>"
                } else {
                    xlsxTextCell(ref, rawValue)
                }
            }
        }
    }

    private fun xlsxTextCell(ref: String, value: String): String =
        "<c r=\"${xml(ref)}\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>"

    /**
     * Explicit NUMBER columns are allowed to normalize a decimal comma to '.'
     * because the caller has already declared numeric intent.
     */
    private fun strictSpreadsheetNumber(value: String): String? {
        val trimmed = value.trim().replace(" ", "")
        if (!trimmed.matches(Regex("[+-]?(?:\\d+(?:[.,]\\d+)?|[.,]\\d+)(?:[eE][+-]?\\d+)?"))) {
            return null
        }
        val normalized = trimmed.replace(',', '.')
        val parsed = normalized.toDoubleOrNull() ?: return null
        if (!parsed.isFinite()) return null
        return normalized
    }

    /**
     * AUTO is intentionally conservative so identifiers such as 00125 remain text.
     * Numeric typing that must be guaranteed should be supplied through column_types=number.
     */
    private fun safeAutoSpreadsheetNumber(value: String): String? {
        val trimmed = value.trim()
        if (!trimmed.matches(Regex("[+-]?(?:0|[1-9]\\d*)(?:\\.\\d+)?(?:[eE][+-]?\\d+)?"))) {
            return null
        }
        val unsigned = trimmed.removePrefix("+").removePrefix("-")
        if (unsigned.length > 1 && unsigned.startsWith("0") && !unsigned.startsWith("0.")) {
            return null
        }
        val parsed = trimmed.toDoubleOrNull() ?: return null
        if (!parsed.isFinite()) return null
        return trimmed.removePrefix("+")
    }

    private fun sanitizeFileName(requested: String, fallbackBase: String, extension: String): String {
        val raw = requested.ifBlank { fallbackBase }
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[\\u0000-\\u001F<>:\"/\\\\|?*]"), "_")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.')
            .take(100)
            .ifBlank { DEFAULT_BASENAME }

        val withoutKnownExtension = raw.substringBeforeLast('.', raw)
        val base = withoutKnownExtension.trim().ifBlank { DEFAULT_BASENAME }.take(90)
        return "$base.$extension"
    }

    private fun xml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun excelColumn(index: Int): String {
        var n = index + 1
        val result = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            result.append(('A'.code + remainder).toChar())
            n = (n - 1) / 26
        }
        return result.reverse().toString()
    }

    private fun formatNumber(value: Double): String {
        val rounded = kotlin.math.round(value * 100.0) / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
    }

    private fun normalizeKind(value: String): Kind? = when (value) {
        "txt", "text", "plain_text" -> Kind.TXT
        "docx", "word", "document" -> Kind.DOCX
        "pdf" -> Kind.PDF
        "xlsx", "excel", "spreadsheet" -> Kind.XLSX
        "jpeg", "jpg", "image" -> Kind.JPEG
        "graph", "chart", "diagram" -> Kind.GRAPH
        else -> null
    }

    private fun specFor(kind: Kind): Spec = when (kind) {
        Kind.TXT -> Spec(kind, "txt", "text/plain", "file", 1L)
        Kind.DOCX -> Spec(kind, "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "document", 300L)
        Kind.PDF -> Spec(kind, "pdf", "application/pdf", "pdf", 300L)
        Kind.XLSX -> Spec(kind, "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "spreadsheet", 300L)
        Kind.JPEG -> Spec(kind, "jpg", "image/jpeg", "image", 300L)
        Kind.GRAPH -> Spec(kind, "jpg", "image/jpeg", "graph image", 300L)
    }

    private fun failure(message: String, reason: String): JSONObject = JSONObject()
        .put("success", false)
        .put("verified", false)
        .put("message", message)
        .put("reason", reason)
        .put("terminal_status", "ERROR")

    private enum class Kind {
        TXT,
        DOCX,
        PDF,
        XLSX,
        JPEG,
        GRAPH
    }

    private enum class SpreadsheetCellType {
        TEXT,
        NUMBER,
        BOOLEAN,
        AUTO
    }

    private data class Spec(
        val kind: Kind,
        val extension: String,
        val mimeType: String,
        val declaredKind: String,
        val minBytes: Long
    )

    companion object {
        private const val CACHE_DIR_NAME = "ayana_artifacts"
        private const val DEFAULT_BASENAME = "AYANA"
        private const val MAX_TITLE_CHARS = 300
        private const val MAX_CONTENT_CHARS = 80_000
        private const val MAX_CELL_CHARS = 4_000
        private const val MAX_COLUMNS = 50
        private const val MAX_ROWS = 500
        private const val MAX_ARTIFACT_BYTES = 24L * 1024L * 1024L
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
    }
}
