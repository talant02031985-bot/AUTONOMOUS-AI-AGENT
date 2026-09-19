package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AYANA Personal Search Engine v1.4 — LOCAL GLOBAL SEARCH + DOCUMENT + IMAGE CONTENT INDEX + OPENABLE RESULTS.
 *
 * Scope v1.2:
 * - Memory v2;
 * - Command History;
 * - Tasks / reminders;
 * - currently available notification history from NotificationListener;
 * - file metadata visible through Android MediaStore/Downloads;
 * - photo metadata visible through Android MediaStore.Images.
 *
 * Privacy / truth contract:
 * - search is local; no Agent Core / Worker request is required;
 * - only sources explicitly available on-device are searched;
 * - a source that cannot be read is reported as unavailable, never silently treated as empty;
 * - photo search combines MediaStore metadata with a bounded local ML Kit OCR/label index;
 * - file search combines metadata with the local incremental document-content index;
 * - scoped-storage / selected-photo access is reported honestly and is never described as full-device coverage;
 * - openable file/photo results persist only as bounded local content:// action records;
 * - raw content:// URIs are never rendered in the user-facing answer.
 */
class AyanaPersonalSearchEngine(
    context: Context,
    private val memoryStore: AyanaMemoryStore,
    private val taskStore: AyanaTaskStore,
    private val historyStore: AyanaCommandHistoryStore,
    private val deviceContentSearchEngine: AyanaDeviceContentSearchEngine,
    private val documentContentIndexEngine: AyanaDocumentContentIndexEngine,
    private val imageContentIndexEngine: AyanaImageContentIndexEngine
) {

    enum class Source(
        val wireName: String,
        val label: String
    ) {
        MEMORY("memory", "Память"),
        HISTORY("history", "История"),
        TASKS("tasks", "Задачи"),
        NOTIFICATIONS("notifications", "Уведомления"),
        FILES("files", "Файлы"),
        PHOTOS("photos", "Фото")
    }

    data class Request(
        val query: String,
        val sources: Set<Source>
    )

    data class Hit(
        val source: Source,
        val title: String,
        val snippet: String,
        val timestampMs: Long,
        val score: Int,
        val metadata: String = "",
        val actionUri: String = "",
        val actionMimeType: String = "",
        val actionKind: String = ""
    )

    data class Report(
        val request: Request,
        val hits: List<Hit>,
        val sourceMatchCounts: Map<Source, Int>,
        val sourceErrors: Map<Source, String>,
        val sourceCoverage: Map<Source, String>,
        val scannedHistoryRecords: Int,
        val scannedNotifications: Int,
        val scannedFiles: Int,
        val scannedPhotos: Int,
        val contentProviderRowsScanned: Int,
        val contentCandidateDocuments: Int,
        val contentIndexedDocuments: Int,
        val contentReusedDocuments: Int,
        val contentUpdatedDocuments: Int,
        val contentFailedDocuments: Int,
        val contentUnsupportedDocuments: Int,
        val contentPdfBestEffortDocuments: Int,
        val imageProviderRowsScanned: Int,
        val imageCandidatePhotos: Int,
        val imageIndexedPhotos: Int,
        val imageReusedPhotos: Int,
        val imageUpdatedPhotos: Int,
        val imageFailedPhotos: Int,
        val imagePendingPhotos: Int,
        val imageOcrPhotos: Int,
        val imageLabeledPhotos: Int,
        val imageNewIndexBudget: Int
    )

    private val appContext =
        context.applicationContext

    private val searchResultStore =
        AyanaSearchResultStore(
            appContext
        )

    fun search(
        request: Request,
        perSourceLimit: Int = DEFAULT_PER_SOURCE_LIMIT,
        totalLimit: Int = DEFAULT_TOTAL_LIMIT
    ): Report {
        val safePerSourceLimit =
            perSourceLimit.coerceIn(1, MAX_PER_SOURCE_LIMIT)
        val safeTotalLimit =
            totalLimit.coerceIn(1, MAX_TOTAL_LIMIT)

        val allHits =
            mutableListOf<Hit>()
        val sourceErrors =
            linkedMapOf<Source, String>()
        val sourceMatchCounts =
            linkedMapOf<Source, Int>()
        val sourceCoverage =
            linkedMapOf<Source, String>()

        var scannedHistoryRecords = 0
        var scannedNotifications = 0
        var scannedFiles = 0
        var scannedPhotos = 0
        var contentProviderRowsScanned = 0
        var contentCandidateDocuments = 0
        var contentIndexedDocuments = 0
        var contentReusedDocuments = 0
        var contentUpdatedDocuments = 0
        var contentFailedDocuments = 0
        var contentUnsupportedDocuments = 0
        var contentPdfBestEffortDocuments = 0
        var imageProviderRowsScanned = 0
        var imageCandidatePhotos = 0
        var imageIndexedPhotos = 0
        var imageReusedPhotos = 0
        var imageUpdatedPhotos = 0
        var imageFailedPhotos = 0
        var imagePendingPhotos = 0
        var imageOcrPhotos = 0
        var imageLabeledPhotos = 0
        var imageNewIndexBudget = 0

        fun collect(
            source: Source,
            block: () -> List<Hit>
        ) {
            if (source !in request.sources) {
                return
            }

            try {
                val found =
                    block()
                        .sortedWith(
                            compareByDescending<Hit> {
                                it.score
                            }.thenByDescending {
                                it.timestampMs
                            }
                        )
                        .take(safePerSourceLimit)

                sourceMatchCounts[source] =
                    found.size
                allHits +=
                    found
            } catch (error: Exception) {
                sourceMatchCounts[source] =
                    0
                sourceErrors[source] =
                    error.message
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.take(180)
                        .orEmpty()
                        .ifBlank {
                            error.javaClass.simpleName
                        }
            }
        }

        collect(Source.MEMORY) {
            memoryStore
                .getAll(
                    MAX_MEMORY_SCAN
                )
                .mapIndexedNotNull { index, item ->
                    val haystack =
                        item.text +
                            " " +
                            item.category

                    val lexical =
                        lexicalScore(
                            request.query,
                            haystack
                        )

                    if (lexical <= 0) {
                        return@mapIndexedNotNull null
                    }

                    Hit(
                        source = Source.MEMORY,
                        title =
                            if (item.category.isBlank() || item.category == "general") {
                                "Запись памяти"
                            } else {
                                "Память • ${compact(item.category, 80)}"
                            },
                        snippet = compact(item.text, MAX_SNIPPET_CHARS),
                        timestampMs = item.updatedAt,
                        score =
                            lexical +
                                recencyBonus(index) +
                                20,
                        metadata = "updated_at=${item.updatedAt}"
                    )
                }
        }

        collect(Source.HISTORY) {
            val records =
                historyStore.recent(MAX_HISTORY_SCAN)
            scannedHistoryRecords =
                records.size

            val matches =
                mutableListOf<Hit>()

            records.forEachIndexed { index, record ->
                val status =
                    record.optString("status")
                        .trim()
                        .uppercase(Locale.ROOT)

                // The current Personal Search command is already present as RUNNING
                // in History by the time this engine executes. Never return it as a hit.
                if (status == "RUNNING") {
                    return@forEachIndexed
                }

                val command =
                    record.optString("command")
                        .replace(Regex("\\s+"), " ")
                        .trim()

                // v1.0.1 history self-echo guard:
                // Personal Search commands are observations ABOUT the user's data, not
                // source facts themselves. If we index them, the first search creates a
                // History row that the next identical search finds, producing recursive
                // self-pollution such as «найди всё про YouTube» finding only the previous
                // Personal Search request/result. Exclude those rows from search hits while
                // leaving ordinary historical commands untouched.
                if (isPersonalSearchHistoryRecord(record, command)) {
                    return@forEachIndexed
                }

                val storedResult =
                    record.optString("result")
                        .trim()

                val fullResult =
                    try {
                        historyStore
                            .fullResult(record)
                            .trim()
                    } catch (_: Exception) {
                        storedResult
                    }

                val haystack =
                    listOf(
                        command,
                        fullResult,
                        status,
                        record.optString("source")
                    )
                        .joinToString(" ")

                val lexical =
                    lexicalScore(
                        request.query,
                        haystack
                    )

                if (lexical <= 0) {
                    return@forEachIndexed
                }

                val resultPreview =
                    compact(
                        fullResult.ifBlank { storedResult },
                        MAX_HISTORY_RESULT_SNIPPET_CHARS
                    )

                val snippet =
                    buildString {
                        if (command.isNotBlank()) {
                            append("Команда: ")
                            append(
                                compact(
                                    command,
                                    MAX_HISTORY_COMMAND_SNIPPET_CHARS
                                )
                            )
                        }

                        if (resultPreview.isNotBlank()) {
                            if (isNotEmpty()) {
                                append(" • ")
                            }
                            append("Результат: ")
                            append(resultPreview)
                        }
                    }

                matches +=
                    Hit(
                        source = Source.HISTORY,
                        title =
                            "История • ${status.ifBlank { "запись" }}",
                        snippet = snippet,
                        timestampMs =
                            record.optLong(
                                "started_at",
                                record.optLong("finished_at", 0L)
                            ),
                        score =
                            lexical +
                                recencyBonus(index),
                        metadata =
                            "status=${status.ifBlank { "UNKNOWN" }}"
                    )
            }

            matches
        }

        collect(Source.TASKS) {
            taskStore
                .getAll(
                    includeDisabled = true
                )
                .mapIndexedNotNull { index, task ->
                    val haystack =
                        task.title +
                            " " +
                            task.message +
                            " " +
                            task.recurrence

                    val lexical =
                        lexicalScore(
                            request.query,
                            haystack
                        )

                    if (lexical <= 0) {
                        return@mapIndexedNotNull null
                    }

                    val snippet =
                        buildString {
                            append(compact(task.message, MAX_SNIPPET_CHARS))
                            if (!task.enabled) {
                                if (isNotEmpty()) append(" • ")
                                append("отключено")
                            }
                        }

                    Hit(
                        source = Source.TASKS,
                        title =
                            compact(
                                task.title,
                                MAX_TITLE_CHARS
                            )
                                .ifBlank { "Задача" },
                        snippet = snippet,
                        timestampMs = task.triggerAtMillis,
                        score =
                            lexical +
                                recencyBonus(index) +
                                15,
                        metadata =
                            "enabled=${task.enabled}; recurrence=${task.recurrence}"
                    )
                }
        }

        collect(Source.NOTIFICATIONS) {
            val result =
                AyanaNotificationListenerService
                    .readRecent(
                        context = appContext,
                        limit = MAX_NOTIFICATION_SCAN,
                        appFilter = null,
                        projection =
                            AyanaNotificationListenerService
                                .PROJECTION_FULL
                    )

            if (!result.optBoolean("success", false)) {
                throw IllegalStateException(
                    result.optString("message")
                        .ifBlank {
                            result.optString("terminal_status")
                        }
                        .ifBlank {
                            "notification_source_unavailable"
                        }
                )
            }

            val items =
                result.optJSONArray("notifications")
                    ?: JSONArray()

            scannedNotifications =
                items.length()

            val matches =
                mutableListOf<Hit>()

            for (index in 0 until items.length()) {
                val item =
                    items.optJSONObject(index)
                        ?: continue

                val app =
                    item.optString("app")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                val title =
                    item.optString("title")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                val text =
                    item.optString("text")
                        .replace(Regex("\\s+"), " ")
                        .trim()
                val packageName =
                    item.optString("package")
                        .trim()

                val haystack =
                    listOf(
                        app,
                        title,
                        text,
                        packageName
                    )
                        .joinToString(" ")

                val lexical =
                    lexicalScore(
                        request.query,
                        haystack
                    )

                if (lexical <= 0) {
                    continue
                }

                val content =
                    listOf(title, text)
                        .filter { it.isNotBlank() }
                        .distinct()
                        .joinToString(": ")

                val timestamp =
                    firstPositiveLong(
                        item,
                        "post_time",
                        "post_time_ms",
                        "posted_at",
                        "posted_at_ms",
                        "timestamp",
                        "timestamp_ms"
                    )

                matches +=
                    Hit(
                        source = Source.NOTIFICATIONS,
                        title =
                            app.ifBlank {
                                "Уведомление"
                            },
                        snippet =
                            compact(
                                content,
                                MAX_SNIPPET_CHARS
                            ),
                        timestampMs = timestamp,
                        score =
                            lexical +
                                recencyBonus(index),
                        metadata =
                            packageName
                                .take(100)
                    )
            }

            matches
        }


        collect(Source.FILES) {
            val metadataResult =
                deviceContentSearchEngine.searchFiles(
                    query = request.query,
                    limit = safePerSourceLimit
                )

            scannedFiles = metadataResult.scanned

            if (
                metadataResult.scope ==
                AyanaDeviceContentSearchEngine.AccessScope.NONE
            ) {
                throw IllegalStateException(metadataResult.detail)
            }

            val contentResult =
                documentContentIndexEngine.search(
                    query = request.query,
                    limit = safePerSourceLimit
                )

            contentProviderRowsScanned =
                contentResult.providerRowsScanned
            contentCandidateDocuments =
                contentResult.candidateDocuments
            contentIndexedDocuments =
                contentResult.indexedDocuments
            contentReusedDocuments =
                contentResult.reusedDocuments
            contentUpdatedDocuments =
                contentResult.updatedDocuments
            contentFailedDocuments =
                contentResult.failedDocuments
            contentUnsupportedDocuments =
                contentResult.unsupportedDocuments
            contentPdfBestEffortDocuments =
                contentResult.pdfBestEffortDocuments

            val metadataCoverageDetail =
                metadataResult.detail
                    .replace(
                        "Поиск v1 выполняется по метаданным, не по содержимому файла. ",
                        "Метаданные файлов также проверены. "
                    )
                    .replace(
                        Regex("\\s*collections=[^.;]*"),
                        ""
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()

            sourceCoverage[Source.FILES] =
                (
                    "scope=${metadataResult.scope.wireName}; " +
                        "${metadataCoverageDetail.take(250)} " +
                        "Индекс содержимого документов: локальный, инкрементальный; PDF — best-effort."
                    )
                    .take(620)

            val combined =
                linkedMapOf<String, Hit>()

            contentResult.hits
                .forEachIndexed { index, item ->
                    val location =
                        item.relativePath
                            .trim()
                            .ifBlank { "путь не указан" }

                    val snippet =
                        buildString {
                            append("Совпадение в содержимом")
                            if (item.snippet.isNotBlank()) {
                                append(": ")
                                append(item.snippet)
                            }
                            append(" • ")
                            append(location)
                            if (item.mimeType.isNotBlank()) {
                                append(" • ")
                                append(item.mimeType)
                            }
                            if (item.sizeBytes > 0L) {
                                append(" • ")
                                append(formatBytes(item.sizeBytes))
                            }
                            if (item.extractor.isNotBlank()) {
                                append(" • extractor=")
                                append(item.extractor)
                            }
                        }

                    val hit =
                        Hit(
                            source = Source.FILES,
                            title =
                                compact(
                                    item.displayName,
                                    MAX_TITLE_CHARS
                                )
                                    .ifBlank { "Документ" },
                            snippet =
                                compact(
                                    snippet,
                                    MAX_SNIPPET_CHARS
                                ),
                            timestampMs = item.timestampMs,
                            score =
                                item.score +
                                    140 +
                                    recencyBonus(index),
                            metadata =
                                "content_match=true; uri_present=${item.uri.isNotBlank()}; extractor=${item.extractor.take(80)}",
                            actionUri = item.uri,
                            actionMimeType = item.mimeType,
                            actionKind = "file"
                        )

                    combined[
                        contentDedupeKey(
                            title = hit.title,
                            timestampMs = hit.timestampMs
                        )
                    ] = hit
                }

            metadataResult.hits
                .forEachIndexed { index, item ->
                    val location =
                        item.relativePath
                            .trim()
                            .ifBlank { "путь не указан" }

                    val snippet =
                        buildString {
                            append(location)
                            if (item.mimeType.isNotBlank()) {
                                append(" • ")
                                append(item.mimeType)
                            }
                            if (item.sizeBytes > 0L) {
                                append(" • ")
                                append(formatBytes(item.sizeBytes))
                            }
                            append(" • совпадение в метаданных")
                        }

                    val hit =
                        Hit(
                            source = Source.FILES,
                            title =
                                compact(
                                    item.displayName,
                                    MAX_TITLE_CHARS
                                )
                                    .ifBlank { "Файл" },
                            snippet = compact(snippet, MAX_SNIPPET_CHARS),
                            timestampMs = item.timestampMs,
                            score =
                                item.score +
                                    recencyBonus(index),
                            metadata =
                                "content_match=false; uri_present=${item.uri.isNotBlank()}; ${item.metadata.take(180)}",
                            actionUri = item.uri,
                            actionMimeType = item.mimeType,
                            actionKind = "file"
                        )

                    val key =
                        contentDedupeKey(
                            title = hit.title,
                            timestampMs = hit.timestampMs
                        )

                    if (key !in combined) {
                        combined[key] = hit
                    }
                }

            combined.values.toList()
        }

        collect(Source.PHOTOS) {
            val metadataResult =
                deviceContentSearchEngine.searchPhotos(
                    query = request.query,
                    limit = safePerSourceLimit
                )

            scannedPhotos = metadataResult.scanned

            if (
                metadataResult.scope ==
                AyanaDeviceContentSearchEngine.AccessScope.NONE
            ) {
                throw IllegalStateException(metadataResult.detail)
            }

            val explicitPhotoOnly =
                request.sources.size == 1 &&
                    Source.PHOTOS in request.sources

            val visualResult =
                imageContentIndexEngine.search(
                    query = request.query,
                    limit = safePerSourceLimit,
                    maxNewImages =
                        if (explicitPhotoOnly) {
                            AyanaImageContentIndexEngine.DEFAULT_NEW_IMAGE_BUDGET
                        } else {
                            AyanaImageContentIndexEngine.BROAD_SEARCH_NEW_IMAGE_BUDGET
                        }
                )

            imageProviderRowsScanned =
                visualResult.providerRowsScanned
            imageCandidatePhotos =
                visualResult.candidateImages
            imageIndexedPhotos =
                visualResult.indexedImages
            imageReusedPhotos =
                visualResult.reusedImages
            imageUpdatedPhotos =
                visualResult.updatedImages
            imageFailedPhotos =
                visualResult.failedImages
            imagePendingPhotos =
                visualResult.pendingImages
            imageOcrPhotos =
                visualResult.ocrIndexedImages
            imageLabeledPhotos =
                visualResult.labeledImages
            imageNewIndexBudget =
                visualResult.newIndexBudget

            sourceCoverage[Source.PHOTOS] =
                (
                    "scope=${metadataResult.scope.wireName}; " +
                        metadataResult.detail
                            .replace(
                                "поиск только по метаданным имени/пути/MIME.",
                                "метаданные имени/пути/MIME проверены."
                            )
                            .take(280) +
                        " Визуальный индекс: ${visualResult.indexedImages}/${visualResult.candidateImages}; " +
                        "pending=${visualResult.pendingImages}; OCR/labels локально. " +
                        if (visualResult.pendingImages > 0) {
                            "Поиск по содержимому фото частичный до завершения индекса."
                        } else {
                            "Поиск по содержимому охватывает все доступные текущему MediaStore изображения."
                        }
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .trimEnd('.')
                    .take(700)

            val combined =
                linkedMapOf<String, Hit>()

            visualResult.hits
                .forEachIndexed { index, item ->
                    val location =
                        item.relativePath
                            .trim()
                            .ifBlank { "альбом/путь не указан" }

                    val evidence =
                        when (item.matchType) {
                            AyanaImageContentIndexEngine.MatchType.OCR ->
                                "Совпадение в тексте изображения"

                            AyanaImageContentIndexEngine.MatchType.LABEL ->
                                "Совпадение по визуальным признакам"

                            AyanaImageContentIndexEngine.MatchType.OCR_AND_LABEL ->
                                "Совпадение в тексте и визуальных признаках"
                        }

                    val snippet =
                        buildString {
                            append(evidence)
                            if (item.snippet.isNotBlank()) {
                                append(": ")
                                append(item.snippet)
                            }
                            append(" • ")
                            append(location)
                            if (item.mimeType.isNotBlank()) {
                                append(" • ")
                                append(item.mimeType)
                            }
                            if (item.sizeBytes > 0L) {
                                append(" • ")
                                append(formatBytes(item.sizeBytes))
                            }
                            append(" • локальный ML Kit")
                        }

                    val hit =
                        Hit(
                            source = Source.PHOTOS,
                            title =
                                compact(
                                    item.displayName,
                                    MAX_TITLE_CHARS
                                )
                                    .ifBlank { "Фото" },
                            snippet =
                                compact(
                                    snippet,
                                    MAX_SNIPPET_CHARS
                                ),
                            timestampMs = item.timestampMs,
                            score =
                                item.score +
                                    180 +
                                    recencyBonus(index),
                            metadata =
                                "image_content_match=true; match_type=${item.matchType.wireName}; " +
                                    "labels=${item.labels.joinToString("|").take(160)}; uri_present=${item.uri.isNotBlank()}",
                            actionUri = item.uri,
                            actionMimeType = item.mimeType,
                            actionKind = "photo"
                        )

                    combined[
                        contentDedupeKey(
                            title = hit.title,
                            timestampMs = hit.timestampMs
                        )
                    ] = hit
                }

            metadataResult.hits
                .forEachIndexed { index, item ->
                    val location =
                        item.relativePath
                            .trim()
                            .ifBlank { "альбом/путь не указан" }

                    val snippet =
                        buildString {
                            append(location)
                            if (item.mimeType.isNotBlank()) {
                                append(" • ")
                                append(item.mimeType)
                            }
                            if (item.sizeBytes > 0L) {
                                append(" • ")
                                append(formatBytes(item.sizeBytes))
                            }
                            append(" • совпадение в метаданных")
                        }

                    val hit =
                        Hit(
                            source = Source.PHOTOS,
                            title =
                                compact(
                                    item.displayName,
                                    MAX_TITLE_CHARS
                                )
                                    .ifBlank { "Фото" },
                            snippet =
                                compact(
                                    snippet,
                                    MAX_SNIPPET_CHARS
                                ),
                            timestampMs = item.timestampMs,
                            score =
                                item.score +
                                    recencyBonus(index),
                            metadata =
                                "image_content_match=false; uri_present=${item.uri.isNotBlank()}; ${item.metadata.take(180)}",
                            actionUri = item.uri,
                            actionMimeType = item.mimeType,
                            actionKind = "photo"
                        )

                    val key =
                        contentDedupeKey(
                            title = hit.title,
                            timestampMs = hit.timestampMs
                        )

                    if (key !in combined) {
                        combined[key] = hit
                    }
                }

            combined.values.toList()
        }

        val ranked =
            allHits
                .sortedWith(
                    compareByDescending<Hit> {
                        it.score
                    }.thenByDescending {
                        it.timestampMs
                    }.thenBy {
                        it.source.ordinal
                    }
                )
                .take(safeTotalLimit)

        searchResultStore.replace(
            ranked.mapIndexedNotNull { index, hit ->
                val uri = hit.actionUri.trim()
                if (!uri.startsWith("content://")) {
                    null
                } else {
                    AyanaSearchResultStore.Item(
                        resultNumber = index + 1,
                        source = hit.source.wireName,
                        title = hit.title,
                        uri = uri,
                        mimeType = hit.actionMimeType,
                        kind = hit.actionKind,
                        savedAtMs = System.currentTimeMillis()
                    )
                }
            }
        )

        return Report(
            request = request,
            hits = ranked,
            sourceMatchCounts = sourceMatchCounts,
            sourceErrors = sourceErrors,
            sourceCoverage = sourceCoverage,
            scannedHistoryRecords = scannedHistoryRecords,
            scannedNotifications = scannedNotifications,
            scannedFiles = scannedFiles,
            scannedPhotos = scannedPhotos,
            contentProviderRowsScanned = contentProviderRowsScanned,
            contentCandidateDocuments = contentCandidateDocuments,
            contentIndexedDocuments = contentIndexedDocuments,
            contentReusedDocuments = contentReusedDocuments,
            contentUpdatedDocuments = contentUpdatedDocuments,
            contentFailedDocuments = contentFailedDocuments,
            contentUnsupportedDocuments = contentUnsupportedDocuments,
            contentPdfBestEffortDocuments = contentPdfBestEffortDocuments,
            imageProviderRowsScanned = imageProviderRowsScanned,
            imageCandidatePhotos = imageCandidatePhotos,
            imageIndexedPhotos = imageIndexedPhotos,
            imageReusedPhotos = imageReusedPhotos,
            imageUpdatedPhotos = imageUpdatedPhotos,
            imageFailedPhotos = imageFailedPhotos,
            imagePendingPhotos = imagePendingPhotos,
            imageOcrPhotos = imageOcrPhotos,
            imageLabeledPhotos = imageLabeledPhotos,
            imageNewIndexBudget = imageNewIndexBudget
        )
    }

    fun renderRussian(
        report: Report
    ): String {
        val request =
            report.request

        val sourceLabels =
            request.sources
                .sortedBy { it.ordinal }
                .joinToString(", ") {
                    it.label
                }

        if (report.hits.isEmpty()) {
            return buildString {
                append("Личный поиск AYANA: по запросу «")
                append(request.query)
                append("» совпадений не найдено.")
                append("\nПроверено локально: ")
                append(sourceLabels)
                append(".")

                appendSourceErrors(
                    this,
                    report.sourceErrors
                )
                appendDeviceCoverage(
                    this,
                    report.sourceCoverage
                )
            }
        }

        return buildString {
            append("Личный поиск AYANA: «")
            append(request.query)
            append("». Найдено: ")
            append(report.hits.size)
            append(".")
            append("\nПроверено локально: ")
            append(sourceLabels)
            append(".")

            report.hits.forEachIndexed { index, hit ->
                append("\n")
                append(index + 1)
                append("). [")
                append(hit.source.label)
                append("] ")
                append(hit.title.ifBlank { hit.source.label })

                if (hit.timestampMs > 0L) {
                    append(" • ")
                    append(formatTime(hit.timestampMs))
                }

                if (hit.snippet.isNotBlank()) {
                    append("\n   ")
                    append(hit.snippet)
                }

                if (hit.actionUri.startsWith("content://")) {
                    append("\n   Открыть результат ")
                    append(index + 1)
                }
            }

            appendSourceErrors(
                this,
                report.sourceErrors
            )
            appendDeviceCoverage(
                this,
                report.sourceCoverage
            )
        }
    }

    fun technicalSummary(
        report: Report
    ): String {
        val counts =
            report.request.sources
                .sortedBy { it.ordinal }
                .joinToString(",") { source ->
                    "${source.wireName}=${report.sourceMatchCounts[source] ?: 0}"
                }

        return (
            "personal_search_local; query=${report.request.query.take(140)}; " +
                "sources=${report.request.sources.joinToString(",") { it.wireName }}; " +
                "matches=${report.hits.size}; counts=$counts; " +
                "openable_results=${report.hits.count { it.actionUri.startsWith("content://") }}; " +
                "history_scanned=${report.scannedHistoryRecords}; " +
                "notifications_scanned=${report.scannedNotifications}; " +
                "files_scanned=${report.scannedFiles}; photos_scanned=${report.scannedPhotos}; " +
                "content_provider_rows=${report.contentProviderRowsScanned}; " +
                "content_candidates=${report.contentCandidateDocuments}; " +
                "content_indexed=${report.contentIndexedDocuments}; " +
                "content_reused=${report.contentReusedDocuments}; " +
                "content_updated=${report.contentUpdatedDocuments}; " +
                "content_failed=${report.contentFailedDocuments}; " +
                "content_unsupported=${report.contentUnsupportedDocuments}; " +
                "content_pdf_best_effort=${report.contentPdfBestEffortDocuments}; " +
                "image_provider_rows=${report.imageProviderRowsScanned}; " +
                "image_candidates=${report.imageCandidatePhotos}; " +
                "image_indexed=${report.imageIndexedPhotos}; " +
                "image_reused=${report.imageReusedPhotos}; " +
                "image_updated=${report.imageUpdatedPhotos}; " +
                "image_failed=${report.imageFailedPhotos}; " +
                "image_pending=${report.imagePendingPhotos}; " +
                "image_ocr=${report.imageOcrPhotos}; " +
                "image_labeled=${report.imageLabeledPhotos}; " +
                "image_budget=${report.imageNewIndexBudget}; " +
                "coverage=${report.sourceCoverage.entries.joinToString("|") { (source, detail) -> "${source.wireName}:${detail.substringBefore(';').take(80)}" }}; " +
                "source_errors=${report.sourceErrors.keys.joinToString(",") { it.wireName }}"
            )
            .take(1800)
    }

    private fun appendSourceErrors(
        builder: StringBuilder,
        errors: Map<Source, String>
    ) {
        if (errors.isEmpty()) {
            return
        }

        builder.append("\nНедоступные источники в этом поиске: ")
        builder.append(
            errors.entries
                .joinToString("; ") { (source, detail) ->
                    if (detail.isBlank()) {
                        source.label
                    } else {
                        "${source.label} (${compact(detail, 100)})"
                    }
                }
        )
        builder.append(".")
    }

    private fun appendDeviceCoverage(
        builder: StringBuilder,
        coverage: Map<Source, String>
    ) {
        val deviceEntries =
            listOf(
                Source.FILES,
                Source.PHOTOS
            )
                .mapNotNull { source ->
                    coverage[source]
                        ?.takeIf { it.isNotBlank() }
                        ?.let { detail ->
                            "${source.label}: ${compact(detail, 220)}"
                        }
                }

        if (deviceEntries.isEmpty()) {
            return
        }

        builder.append("\nПокрытие устройства: ")
        builder.append(deviceEntries.joinToString("; "))
        builder.append(".")
    }

    private fun contentDedupeKey(
        title: String,
        timestampMs: Long
    ): String =
        normalizeForSearch(title) +
            "|" +
            timestampMs.toString()

    private fun formatBytes(
        bytes: Long
    ): String {
        if (bytes <= 0L) {
            return "0 Б"
        }

        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return String.format(Locale.ROOT, "%.0f КБ", kb)
        }

        val mb = kb / 1024.0
        if (mb < 1024.0) {
            return String.format(Locale.ROOT, "%.1f МБ", mb)
        }

        return String.format(Locale.ROOT, "%.2f ГБ", mb / 1024.0)
    }

    private fun lexicalScore(
        query: String,
        haystack: String
    ): Int {
        val normalizedQuery =
            normalizeForSearch(query)
        val normalizedHaystack =
            normalizeForSearch(haystack)

        if (
            normalizedQuery.isBlank() ||
            normalizedHaystack.isBlank()
        ) {
            return 0
        }

        val queryTokens =
            searchTokens(normalizedQuery)

        if (queryTokens.isEmpty()) {
            return if (
                normalizedHaystack.contains(normalizedQuery)
            ) {
                80
            } else {
                0
            }
        }

        val haystackTokens =
            searchTokens(normalizedHaystack)
                .toSet()

        val overlap =
            queryTokens.count { token ->
                token in haystackTokens ||
                    normalizedHaystack.contains(token)
            }

        val exactPhraseBonus =
            if (
                normalizedQuery.length >= 3 &&
                normalizedHaystack.contains(normalizedQuery)
            ) {
                80
            } else {
                0
            }

        val allTokenBonus =
            if (
                overlap == queryTokens.size &&
                queryTokens.size > 1
            ) {
                24
            } else {
                0
            }

        return if (
            overlap == 0 &&
            exactPhraseBonus == 0
        ) {
            0
        } else {
            exactPhraseBonus +
                allTokenBonus +
                overlap * 18
        }
    }

    private fun recencyBonus(
        index: Int
    ): Int =
        (18 - index.coerceAtMost(18))
            .coerceAtLeast(0)

    private fun compact(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)

    private fun formatTime(
        timestampMs: Long
    ): String {
        return try {
            SimpleDateFormat(
                "dd.MM.yyyy HH:mm",
                Locale.getDefault()
            ).format(
                Date(timestampMs)
            )
        } catch (_: Exception) {
            timestampMs.toString()
        }
    }

    private fun firstPositiveLong(
        item: JSONObject,
        vararg keys: String
    ): Long {
        keys.forEach { key ->
            val value =
                item.optLong(key, 0L)
            if (value > 0L) {
                return value
            }
        }
        return 0L
    }

    private fun isPersonalSearchHistoryRecord(
        record: JSONObject,
        command: String
    ): Boolean {
        val technical =
            record.optString("technical")
                .lowercase(Locale.ROOT)

        if (technical.contains("personal_search_local")) {
            return true
        }

        if (command.isBlank()) {
            return false
        }

        return parseRequest(command) != null
    }

    companion object {
        private const val DEFAULT_PER_SOURCE_LIMIT = 4
        private const val DEFAULT_TOTAL_LIMIT = 16
        private const val MAX_PER_SOURCE_LIMIT = 8
        private const val MAX_TOTAL_LIMIT = 20
        private const val MAX_MEMORY_SCAN = 200
        private const val MAX_HISTORY_SCAN = 80
        private const val MAX_NOTIFICATION_SCAN = 32
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_SNIPPET_CHARS = 360
        private const val MAX_HISTORY_COMMAND_SNIPPET_CHARS = 220
        private const val MAX_HISTORY_RESULT_SNIPPET_CHARS = 360

        private val ALL_SOURCES =
            linkedSetOf(
                Source.MEMORY,
                Source.HISTORY,
                Source.TASKS,
                Source.NOTIFICATIONS,
                Source.FILES,
                Source.PHOTOS
            )

        /**
         * Conservative routing parser.
         *
         * Generic web/app/map search must remain outside this engine. Personal Search
         * is claimed only for explicit personal/local formulations or explicit source
         * scopes such as «в памяти», «в истории», «мои задачи», «в уведомлениях».
         */
        fun parseRequest(
            command: String
        ): Request? {
            val raw =
                command
                    .replace(Regex("\\s+"), " ")
                    .trim()

            if (raw.isBlank()) {
                return null
            }

            val normalized =
                normalizeForSearch(raw)

            val routingNormalized =
                normalized
                    .removePrefix("аяна ")
                    .trim()

            if (isExternalSearchRoute(routingNormalized)) {
                return null
            }

            val routingScopeText =
                routingScopeText(
                    routingNormalized
                )

            val memoryScoped =
                listOf(
                    "в памяти",
                    "из памяти",
                    "по памяти",
                    "и памяти"
                ).any(routingScopeText::contains)

            val historyScoped =
                listOf(
                    "в истории",
                    "из истории",
                    "по истории",
                    "и истории"
                ).any(routingScopeText::contains) ||
                    listOf(
                        "где я говорил",
                        "что я говорил",
                        "где я спрашивал",
                        "что я спрашивал"
                    ).any(routingNormalized::contains)

            val tasksScoped =
                listOf(
                    "в задачах",
                    "из задач",
                    "по задачам",
                    "и задачах",
                    "мои задачи",
                    "в напоминаниях",
                    "из напоминаний",
                    "по напоминаниям",
                    "и напоминаниях",
                    "мои напоминания"
                ).any(routingScopeText::contains)

            val notificationsScoped =
                listOf(
                    "в уведомлениях",
                    "из уведомлений",
                    "по уведомлениям",
                    "и уведомлениях",
                    "мои уведомления"
                ).any(routingScopeText::contains)

            val filesScoped =
                listOf(
                    "в файлах",
                    "из файлов",
                    "по файлам",
                    "и файлах",
                    "мои файлы",
                    "файл на планшете",
                    "файлы на планшете",
                    "документ на планшете",
                    "документы на планшете",
                    "в документах",
                    "из документов",
                    "по документам",
                    "в загрузках",
                    "из загрузок",
                    "в downloads"
                ).any(routingScopeText::contains)

            val photosScoped =
                listOf(
                    "в фото",
                    "из фото",
                    "по фото",
                    "и фото",
                    "мои фото",
                    "фото на планшете",
                    "фотографии на планшете",
                    "в фотографиях",
                    "из фотографий",
                    "по фотографиям",
                    "среди фотографий",
                    "среди фото",
                    "в галерее",
                    "из галереи"
                ).any(routingScopeText::contains)

            val hasExplicitSource =
                memoryScoped ||
                    historyScoped ||
                    tasksScoped ||
                    notificationsScoped ||
                    filesScoped ||
                    photosScoped

            val broadPersonalSearch =
                listOf(
                    "найди все про ",
                    "найди все что есть про ",
                    "поищи все про ",
                    "поищи все что есть про ",
                    "найди у меня ",
                    "поищи у меня ",
                    "что у меня есть про ",
                    "по всему у меня ",
                    "по всем моим данным ",
                    "личный поиск "
                ).any(routingNormalized::startsWith) ||
                    routingNormalized.contains("где я говорил про ") ||
                    routingNormalized.contains("что я говорил про ") ||
                    routingNormalized.contains("где я спрашивал про ") ||
                    routingNormalized.contains("что я спрашивал про ")

            val hasSearchVerb =
                routingNormalized.startsWith("найди ") ||
                    routingNormalized.startsWith("поищи ") ||
                    routingNormalized.startsWith("отыщи ") ||
                    routingNormalized.startsWith("покажи ")

            if (
                !broadPersonalSearch &&
                !(hasExplicitSource && hasSearchVerb)
            ) {
                return null
            }

            val sources =
                linkedSetOf<Source>()

            if (memoryScoped) {
                sources += Source.MEMORY
            }
            if (historyScoped) {
                sources += Source.HISTORY
            }
            if (tasksScoped) {
                sources += Source.TASKS
            }
            if (notificationsScoped) {
                sources += Source.NOTIFICATIONS
            }
            if (filesScoped) {
                sources += Source.FILES
            }
            if (photosScoped) {
                sources += Source.PHOTOS
            }

            if (sources.isEmpty()) {
                sources += ALL_SOURCES
            }

            val query =
                extractQuery(
                    raw = raw,
                    normalized = routingNormalized
                )

            if (query.isBlank()) {
                return null
            }

            return Request(
                query = query,
                sources = sources
            )
        }

        private fun routingScopeText(
            normalized: String
        ): String {
            val markers =
                listOf(
                    " про ",
                    " по теме ",
                    " об ",
                    " о "
                )

            val indexes =
                markers
                    .map { marker ->
                        normalized.indexOf(marker)
                    }
                    .filter { index ->
                        index >= 0
                    }

            val firstMarker =
                indexes.minOrNull()

            return if (firstMarker == null) {
                normalized
            } else {
                normalized
                    .substring(0, firstMarker)
                    .trim()
            }
        }

        private fun isExternalSearchRoute(
            normalized: String
        ): Boolean {
            return listOf(
                "найди в google ",
                "найди в гугле ",
                "поищи в google ",
                "поищи в гугле ",
                "найди в интернете ",
                "поищи в интернете ",
                "найди в сети ",
                "поищи в сети ",
                "найди на youtube ",
                "найди в youtube ",
                "найди на ютуб ",
                "найди в ютуб ",
                "найди на карте ",
                "покажи на карте ",
                "найди приложение ",
                "найди настройку ",
                "найди в настройках "
            ).any { route ->
                normalized.startsWith(route) ||
                    normalized.startsWith("аяна $route")
            }
        }

        private fun extractQuery(
            raw: String,
            normalized: String
        ): String {
            val markerMatch =
                Regex(
                    "(?i)\\s+(?:про|по\\s+теме|об|о)\\s+"
                ).find(
                    raw
                )

            if (markerMatch != null) {
                val candidate =
                    raw
                        .substring(
                            (markerMatch.range.last + 1)
                                .coerceAtMost(raw.length)
                        )
                        .trim(' ', '.', ',', ':', ';', '-', '—', '«', '»', '"', '\'')

                if (candidate.isNotBlank()) {
                    return candidate
                }
            }

            var cleaned =
                normalized

            val prefixes =
                listOf(
                    "аяна ",
                    "найди ",
                    "поищи ",
                    "отыщи ",
                    "покажи ",
                    "личный поиск ",
                    "найди у меня ",
                    "поищи у меня ",
                    "что у меня есть ",
                    "где я говорил ",
                    "что я говорил ",
                    "где я спрашивал ",
                    "что я спрашивал "
                )

            var changed =
                true

            while (changed) {
                changed =
                    false

                prefixes.forEach { prefix ->
                    if (cleaned.startsWith(prefix)) {
                        cleaned =
                            cleaned
                                .removePrefix(prefix)
                                .trim()
                        changed =
                            true
                    }
                }
            }

            val scaffolding =
                listOf(
                    "в памяти",
                    "из памяти",
                    "по памяти",
                    "в истории",
                    "из истории",
                    "по истории",
                    "в задачах",
                    "из задач",
                    "мои задачи",
                    "в напоминаниях",
                    "из напоминаний",
                    "мои напоминания",
                    "в уведомлениях",
                    "из уведомлений",
                    "мои уведомления",
                    "в файлах",
                    "из файлов",
                    "по файлам",
                    "мои файлы",
                    "файл на планшете",
                    "файлы на планшете",
                    "документ на планшете",
                    "документы на планшете",
                    "в документах",
                    "из документов",
                    "в загрузках",
                    "в downloads",
                    "в фотографиях",
                    "из фотографий",
                    "по фотографиям",
                    "среди фотографий",
                    "в галерее",
                    "из галереи",
                    "в фото",
                    "из фото",
                    "по фото",
                    "мои фото",
                    "фото на планшете",
                    "фотографии на планшете",
                    "среди фото",
                    "у меня",
                    "все что есть",
                    "все"
                )

            scaffolding.forEach { phrase ->
                cleaned =
                    cleaned
                        .replace(
                            phrase,
                            " "
                        )
            }

            return cleaned
                .replace(Regex("\\s+"), " ")
                .trim(' ', '.', ',', ':', ';', '-', '—', '«', '»', '"', '\'')
        }

        private fun normalizeForSearch(
            value: String
        ): String =
            value
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(
                    Regex("[^a-zа-я0-9+#._\\-\\s]"),
                    " "
                )
                .replace(Regex("\\s+"), " ")
                .trim()

        private fun searchTokens(
            value: String
        ): List<String> =
            normalizeForSearch(value)
                .split(" ")
                .map { it.trim() }
                .filter {
                    it.length >= 2 &&
                        it !in STOP_TOKENS
                }
                .distinct()

        private val STOP_TOKENS =
            setOf(
                "про",
                "что",
                "где",
                "как",
                "это",
                "мне",
                "мой",
                "моя",
                "мои",
                "есть",
                "было",
                "были",
                "найди",
                "поищи",
                "покажи"
            )
    }
}
