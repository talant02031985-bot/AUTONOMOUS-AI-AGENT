package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AYANA Personal Search Engine v2.0 — R10.20 PERSONAL SEARCH 2.0.
 *
 * Builds on the accepted R8.4/R10.2 local search stack and the v1.5.1 image-coverage truth rules.
 * The same six on-device source families remain authoritative; v2.0 adds one unified result
 * contract above them instead of inventing a second index.
 *
 * R10.20 additions:
 * - bounded deterministic semantic expansion for common RU/EN document/media terms;
 * - lightweight morphology-aware token matching for local text stores;
 * - per-hit confidence, match kind, provenance and stable SHA-256 fingerprint;
 * - unified cross-source ranking that keeps source diversity without hiding stronger evidence;
 * - explicit coverage truth for every requested source;
 * - verified-result envelope for safe use by later autonomous tasks;
 * - session persistence remains backward compatible with v1.x records.
 *
 * Privacy / truth contract:
 * - search is local; no Agent Core / Worker request is required;
 * - only sources explicitly available on-device are searched;
 * - unavailable sources are reported, never silently treated as empty;
 * - visual/photo evidence is OCR/label data only and grants no action authority;
 * - Android scoped/selected-photo access is never described as full-device coverage;
 * - raw content:// URIs are never rendered or exported in verified-result envelopes;
 * - confidence is evidence quality, not identity certainty or biometric matching;
 * - a hit without local provenance/fingerprint cannot become a verified autonomous input.
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

    enum class FollowUpKind {
        NEXT_PAGE,
        PREVIOUS_PAGE,
        FILTER_SOURCES
    }

    data class FollowUpRequest(
        val kind: FollowUpKind,
        val sources: Set<Source> = emptySet()
    )

    data class PageResult(
        val text: String,
        val technical: String,
        val pageIndex: Int,
        val totalPages: Int,
        val totalHits: Int,
        val boundary: Boolean
    )

    private data class SearchSession(
        val report: Report,
        val pageIndex: Int,
        val savedAtMs: Long
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
        val actionKind: String = "",
        val matchKind: String = "lexical",
        val confidence: Int = 0,
        val verified: Boolean = true,
        val provenance: String = "",
        val fingerprint: String = ""
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
        val imageNewIndexBudget: Int,
        val searchContractVersion: Int = SEARCH_CONTRACT_VERSION,
        val queryTerms: List<String> = emptyList(),
        val expandedQueryTerms: List<String> = emptyList(),
        val verifiedHitCount: Int = 0,
        val highConfidenceHitCount: Int = 0,
        val provenanceComplete: Boolean = false
    )

    private val appContext =
        context.applicationContext

    private val searchResultStore =
        AyanaSearchResultStore(
            appContext
        )

    private val sessionPreferences =
        appContext.getSharedPreferences(
            SESSION_PREFS_NAME,
            Context.MODE_PRIVATE
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

        val queryTerms =
            searchTokens(request.query)
        val expandedQueryTerms =
            expandedSearchTokens(request.query)
                .filterNot { it in queryTerms }

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

                if (source !in sourceCoverage) {
                    sourceCoverage[source] =
                        defaultCoverageFor(source)
                }
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
                sourceCoverage[source] =
                    "unavailable; reason=${sourceErrors[source].orEmpty().take(160)}"
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
                        "Индекс содержимого документов: локальный, инкрементальный; PDF — best-effort. " +
                        "Coverage относится только к файлам, доступным текущему Android storage scope; полный доступ ко всем данным устройства не предполагается."
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(760)

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
                            "Поиск по содержимому фото частичный: часть изображений, доступных текущему Android MediaStore scope, ещё не проиндексирована."
                        } else {
                            "Визуальный индекс завершён для набора изображений, который Android сейчас предоставляет приложению; это не утверждение о полном доступе ко всем фото устройства."
                        }
                    )
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .trimEnd('.')
                    .take(860)

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

        val enrichedHits =
            allHits
                .map { hit ->
                    enrichHit(
                        query = request.query,
                        hit = hit
                    )
                }
                .distinctBy { hit ->
                    hit.fingerprint.ifBlank {
                        "${hit.source.wireName}|${hit.title}|${hit.timestampMs}|${hit.score}"
                    }
                }

        val ranked =
            rankHitsForUnifiedPool(
                hits = enrichedHits,
                requestedSources = request.sources,
                limit = safeTotalLimit
            )

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

        val report =
            Report(
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
            imageNewIndexBudget = imageNewIndexBudget,
            searchContractVersion = SEARCH_CONTRACT_VERSION,
            queryTerms = queryTerms,
            expandedQueryTerms = expandedQueryTerms,
            verifiedHitCount = ranked.count { it.verified },
            highConfidenceHitCount = ranked.count { it.verified && it.confidence >= HIGH_CONFIDENCE_THRESHOLD },
            provenanceComplete = ranked.all { it.provenance.isNotBlank() && it.fingerprint.isNotBlank() }
        )

        saveSession(
            report = report,
            pageIndex = 0
        )

        return report
    }
    fun renderRussian(
        report: Report,
        pageIndex: Int = 0,
        pageSize: Int = DEFAULT_PAGE_SIZE
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

        val safePageSize =
            pageSize.coerceIn(
                1,
                MAX_PAGE_SIZE
            )

        val totalPages =
            pageCount(
                hitCount = report.hits.size,
                pageSize = safePageSize
            )

        val safePageIndex =
            pageIndex.coerceIn(
                0,
                totalPages - 1
            )

        val fromIndex =
            safePageIndex * safePageSize

        val toExclusive =
            (fromIndex + safePageSize)
                .coerceAtMost(
                    report.hits.size
                )

        val pageHits =
            report.hits.subList(
                fromIndex,
                toExclusive
            )

        val sourceSummary =
            report.hits
                .groupingBy {
                    it.source
                }
                .eachCount()
                .entries
                .sortedBy {
                    it.key.ordinal
                }
                .joinToString("; ") { (source, count) ->
                    "${source.label} — $count"
                }

        return buildString {
            append("Личный поиск AYANA: «")
            append(request.query)
            append("». В текущей выдаче: ")
            append(report.hits.size)
            append(".")

            if (safePageIndex == 0) {
                append("\nПроверено локально: ")
                append(sourceLabels)
                append(".")

                if (sourceSummary.isNotBlank()) {
                    append("\nВ выдаче по источникам: ")
                    append(sourceSummary)
                    append(".")
                }
            }

            append("\nПоказано ")
            append(fromIndex + 1)
            append("–")
            append(toExclusive)
            append(" из ")
            append(report.hits.size)
            append(".")

            pageHits.forEachIndexed { localIndex, hit ->
                val resultNumber =
                    fromIndex + localIndex + 1

                append("\n")
                append(resultNumber)
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
                    append(resultNumber)
                }
            }

            if (safePageIndex == 0) {
                appendSourceErrors(
                    this,
                    report.sourceErrors
                )
                appendDeviceCoverage(
                    this,
                    report.sourceCoverage
                )
            }

            if (toExclusive < report.hits.size) {
                append("\nЕсть ещё результаты. Скажи: «покажи следующие результаты».")
            } else if (safePageIndex > 0) {
                append("\nЭто последние результаты текущей выдачи.")
            }
        }
    }

    fun clearLatestSession() {
        clearSession()
    }

    fun latestRequestForSources(
        sources: Set<Source>
    ): Request? {
        if (sources.isEmpty()) {
            return null
        }

        val session =
            loadSession()
                ?: return null

        return Request(
            query = session.report.request.query,
            sources = LinkedHashSet(sources)
        )
    }

    fun renderLatestPage(
        delta: Int
    ): PageResult? {
        val session =
            loadSession()
                ?: return null

        val report =
            session.report

        if (report.hits.isEmpty()) {
            return PageResult(
                text = renderRussian(report),
                technical =
                    "personal_search_local; followup=page; query=${report.request.query.take(140)}; " +
                        "page=0/0; matches=0; session_reused=true",
                pageIndex = 0,
                totalPages = 0,
                totalHits = 0,
                boundary = true
            )
        }

        val totalPages =
            pageCount(
                hitCount = report.hits.size,
                pageSize = DEFAULT_PAGE_SIZE
            )

        val currentPage =
            session.pageIndex
                .coerceIn(
                    0,
                    totalPages - 1
                )

        val requestedPage =
            currentPage + delta

        if (
            requestedPage < 0 ||
            requestedPage >= totalPages
        ) {
            val boundaryText =
                if (requestedPage < 0) {
                    "Это первая страница текущей выдачи. Предыдущих результатов нет."
                } else {
                    "Больше результатов в текущей выдаче нет. Всего сохранено: ${report.hits.size}."
                }

            return PageResult(
                text = boundaryText,
                technical =
                    "personal_search_local; followup=page_boundary; query=${report.request.query.take(140)}; " +
                        "page=${currentPage + 1}/$totalPages; matches=${report.hits.size}; session_reused=true",
                pageIndex = currentPage,
                totalPages = totalPages,
                totalHits = report.hits.size,
                boundary = true
            )
        }

        saveSession(
            report = report,
            pageIndex = requestedPage
        )

        return PageResult(
            text =
                renderRussian(
                    report = report,
                    pageIndex = requestedPage
                ),
            technical =
                "personal_search_local; followup=page; query=${report.request.query.take(140)}; " +
                    "sources=${report.request.sources.joinToString(",") { it.wireName }}; " +
                    "page=${requestedPage + 1}/$totalPages; matches=${report.hits.size}; session_reused=true",
            pageIndex = requestedPage,
            totalPages = totalPages,
            totalHits = report.hits.size,
            boundary = false
        )
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
            "personal_search_local; personal_search_version=$VERSION; search_contract=${report.searchContractVersion}; " +
                "query=${report.request.query.take(140)}; " +
                "sources=${report.request.sources.joinToString(",") { it.wireName }}; " +
                "matches=${report.hits.size}; verified_hits=${report.verifiedHitCount}; " +
                "high_confidence_hits=${report.highConfidenceHitCount}; provenance_complete=${report.provenanceComplete}; " +
                "expanded_terms=${report.expandedQueryTerms.joinToString("|").take(220)}; counts=$counts; " +
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
            .take(2400)
    }

    fun verifiedResultEnvelope(
        report: Report,
        resultNumber: Int = 1
    ): JSONObject {
        val index =
            resultNumber - 1

        if (
            index !in report.hits.indices
        ) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("reason", "personal_search_result_out_of_range")
                .put("personal_search_version", VERSION)
                .put("search_contract_version", SEARCH_CONTRACT_VERSION)
                .put("result_number", resultNumber)
        }

        val hit =
            report.hits[index]

        val verified =
            hit.verified &&
                hit.provenance.isNotBlank() &&
                hit.fingerprint.length == 64 &&
                report.searchContractVersion == SEARCH_CONTRACT_VERSION

        if (!verified) {
            return JSONObject()
                .put("success", false)
                .put("verified", false)
                .put("reason", "personal_search_result_provenance_not_verified")
                .put("personal_search_version", VERSION)
                .put("search_contract_version", report.searchContractVersion)
                .put("result_number", resultNumber)
                .put("source", hit.source.wireName)
                .put("confidence", hit.confidence)
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("read_only", true)
            .put("personal_search_version", VERSION)
            .put("search_contract_version", SEARCH_CONTRACT_VERSION)
            .put("query", report.request.query.take(MAX_SESSION_QUERY_CHARS))
            .put("result_number", resultNumber)
            .put("source", hit.source.wireName)
            .put("title", hit.title.take(MAX_TITLE_CHARS))
            .put("snippet", hit.snippet.take(MAX_SNIPPET_CHARS))
            .put("timestamp_ms", hit.timestampMs)
            .put("match_kind", hit.matchKind)
            .put("confidence", hit.confidence)
            .put("provenance", hit.provenance)
            .put("fingerprint", hit.fingerprint)
            .put("action_available", hit.actionUri.startsWith("content://"))
            .put("raw_action_uri_exposed", false)
            .put("visual_grants_action_authority", false)
            .put(
                "source_coverage",
                report.sourceCoverage[hit.source]
                    .orEmpty()
                    .take(MAX_SESSION_DETAIL_CHARS)
            )
    }

    /** Pure R10.20 contract regression. No Android mutation is dispatched. */
    fun contractSelfTest(): Boolean {
        return try {
            val semantic =
                lexicalEvidence(
                    query = "ворд",
                    haystack = "Отчёт подготовлен в Word DOCX"
                )

            val morphology =
                lexicalEvidence(
                    query = "новостями",
                    haystack = "архив новостей"
                )

            val semanticTerms =
                expandedSearchTokens(
                    "ворд"
                )

            val sample =
                enrichHit(
                    query = "ворд",
                    hit =
                        Hit(
                            source = Source.HISTORY,
                            title = "История • SUCCESS",
                            snippet = "Результат: Word DOCX",
                            timestampMs = 1L,
                            score = 40,
                            metadata = "status=SUCCESS"
                        )
                )

            val parserOk =
                parseRequest(
                    "найди в документах отчёт"
                )
                    ?.let { request ->
                        request.sources == setOf(Source.FILES) &&
                            request.query.contains(
                                "отч",
                                ignoreCase = true
                            )
                    } == true

            val externalGuard =
                parseRequest(
                    "найди в Google новости AYANA"
                ) == null

            semantic.score > 0 &&
                semantic.semanticExpansionUsed &&
                semantic.confidence >= 80 &&
                morphology.score > 0 &&
                morphology.morphologyUsed &&
                "word" in semanticTerms &&
                "docx" in semanticTerms &&
                sample.verified &&
                sample.provenance == "command_history_store" &&
                sample.fingerprint.length == 64 &&
                sample.confidence >= HIGH_CONFIDENCE_THRESHOLD &&
                parserOk &&
                externalGuard
        } catch (_: Throwable) {
            false
        }
    }

    private data class MatchEvidence(
        val score: Int,
        val confidence: Int,
        val exactPhrase: Boolean,
        val directMatchedTokens: Int,
        val totalQueryTokens: Int,
        val semanticExpansionUsed: Boolean,
        val morphologyUsed: Boolean,
        val kind: String
    )

    private fun enrichHit(
        query: String,
        hit: Hit
    ): Hit {
        val evidence =
            lexicalEvidence(
                query = query,
                haystack = listOf(
                    hit.title,
                    hit.snippet,
                    hit.metadata
                ).joinToString(" ")
            )

        val provenance =
            provenanceFor(hit)

        val matchKind =
            nativeMatchKind(
                hit = hit,
                evidence = evidence
            )

        val confidenceFloor =
            nativeConfidenceFloor(
                hit = hit,
                matchKind = matchKind
            )

        val confidence =
            maxOf(
                evidence.confidence,
                confidenceFloor
            )
                .coerceIn(0, 100)

        val verified =
            provenance.isNotBlank()

        val fingerprint =
            if (verified) {
                hitFingerprint(
                    source = hit.source,
                    title = hit.title,
                    snippet = hit.snippet,
                    timestampMs = hit.timestampMs,
                    provenance = provenance,
                    matchKind = matchKind
                )
            } else {
                ""
            }

        val qualityBoost =
            confidence / 8 +
                provenanceReliabilityBoost(
                    provenance
                )

        return hit.copy(
            score = hit.score + qualityBoost,
            matchKind = matchKind,
            confidence = confidence,
            verified = verified,
            provenance = provenance,
            fingerprint = fingerprint
        )
    }

    private fun provenanceFor(
        hit: Hit
    ): String =
        when (hit.source) {
            Source.MEMORY ->
                "memory_store"

            Source.HISTORY ->
                "command_history_store"

            Source.TASKS ->
                "task_store"

            Source.NOTIFICATIONS ->
                "notification_listener"

            Source.FILES ->
                if (
                    hit.metadata.contains(
                        "content_match=true",
                        ignoreCase = true
                    )
                ) {
                    "document_content_index"
                } else {
                    "android_media_store_file_metadata"
                }

            Source.PHOTOS ->
                if (
                    hit.metadata.contains(
                        "image_content_match=true",
                        ignoreCase = true
                    )
                ) {
                    "image_content_index_local_mlkit"
                } else {
                    "android_media_store_photo_metadata"
                }
        }

    private fun nativeMatchKind(
        hit: Hit,
        evidence: MatchEvidence
    ): String =
        when (hit.source) {
            Source.FILES ->
                if (
                    hit.metadata.contains(
                        "content_match=true",
                        ignoreCase = true
                    )
                ) {
                    "document_content"
                } else {
                    "file_metadata"
                }

            Source.PHOTOS -> {
                val metadata =
                    hit.metadata.lowercase(Locale.ROOT)

                when {
                    "image_content_match=true" !in metadata ->
                        "photo_metadata"
                    "match_type=ocr_and_label" in metadata ->
                        "photo_ocr_and_label"
                    "match_type=ocr" in metadata ->
                        "photo_ocr"
                    "match_type=label" in metadata ->
                        "photo_label"
                    else ->
                        "photo_visual_index"
                }
            }

            else ->
                evidence.kind
        }

    private fun nativeConfidenceFloor(
        hit: Hit,
        matchKind: String
    ): Int =
        when (matchKind) {
            "document_content" -> 88
            "file_metadata" -> 68
            "photo_ocr_and_label" -> 90
            "photo_ocr" -> 86
            "photo_label" -> 74
            "photo_visual_index" -> 72
            "photo_metadata" -> 66
            else ->
                when (hit.source) {
                    Source.MEMORY -> 82
                    Source.HISTORY -> 82
                    Source.TASKS -> 80
                    Source.NOTIFICATIONS -> 78
                    Source.FILES -> 68
                    Source.PHOTOS -> 66
                }
        }

    private fun provenanceReliabilityBoost(
        provenance: String
    ): Int =
        when (provenance) {
            "document_content_index" -> 20
            "image_content_index_local_mlkit" -> 18
            "memory_store" -> 14
            "command_history_store" -> 14
            "task_store" -> 12
            "notification_listener" -> 10
            "android_media_store_file_metadata" -> 6
            "android_media_store_photo_metadata" -> 6
            else -> 0
        }

    private fun hitFingerprint(
        source: Source,
        title: String,
        snippet: String,
        timestampMs: Long,
        provenance: String,
        matchKind: String
    ): String {
        val canonical =
            buildString {
                append(source.wireName)
                append('|')
                append(normalizeForSearch(title))
                append('|')
                append(normalizeForSearch(snippet))
                append('|')
                append(timestampMs)
                append('|')
                append(provenance)
                append('|')
                append(matchKind)
            }

        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(
                    canonical.toByteArray(
                        Charsets.UTF_8
                    )
                )

        return digest.joinToString("") { byte ->
            "%02x".format(
                Locale.ROOT,
                byte.toInt() and 0xff
            )
        }
    }

    private fun defaultCoverageFor(
        source: Source
    ): String =
        when (source) {
            Source.MEMORY ->
                "local_memory_store; bounded_scan=$MAX_MEMORY_SCAN"

            Source.HISTORY ->
                "local_command_history; bounded_scan=$MAX_HISTORY_SCAN; personal_search_self_echo_excluded=true"

            Source.TASKS ->
                "local_task_store; include_disabled=true"

            Source.NOTIFICATIONS ->
                "notification_listener_current_history; bounded_scan=$MAX_NOTIFICATION_SCAN"

            Source.FILES ->
                "android_storage_scope; detailed_coverage_pending_source_probe"

            Source.PHOTOS ->
                "android_media_store_scope; detailed_coverage_pending_source_probe"
        }

    private fun rankHitsForUnifiedPool(
        hits: List<Hit>,
        requestedSources: Set<Source>,
        limit: Int
    ): List<Hit> {
        val safeLimit =
            limit.coerceIn(
                1,
                MAX_TOTAL_LIMIT
            )

        val comparator =
            compareByDescending<Hit> {
                if (it.verified) 1 else 0
            }.thenByDescending {
                it.score
            }.thenByDescending {
                it.confidence
            }.thenByDescending {
                it.timestampMs
            }.thenBy {
                it.source.ordinal
            }

        val globallySorted =
            hits.sortedWith(
                comparator
            )

        if (
            requestedSources.size <= 1 ||
            globallySorted.size <= safeLimit
        ) {
            return globallySorted
                .take(
                    safeLimit
                )
        }

        val selected =
            mutableListOf<Hit>()

        requestedSources
            .sortedBy {
                it.ordinal
            }
            .forEach { source ->
                if (selected.size >= safeLimit) {
                    return@forEach
                }

                globallySorted
                    .asSequence()
                    .filter {
                        it.source == source
                    }
                    .take(
                        MIN_PER_SOURCE_IN_GLOBAL_POOL
                    )
                    .forEach { hit ->
                        if (
                            selected.size < safeLimit &&
                            hit !in selected
                        ) {
                            selected += hit
                        }
                    }
            }

        globallySorted
            .forEach { hit ->
                if (
                    selected.size < safeLimit &&
                    hit !in selected
                ) {
                    selected += hit
                }
            }

        return selected
            .take(
                safeLimit
            )
    }


    private fun saveSession(
        report: Report,
        pageIndex: Int
    ) {
        val now =
            System.currentTimeMillis()

        val root =
            JSONObject()
                .put(
                    "saved_at_ms",
                    now
                )
                .put(
                    "page_index",
                    pageIndex.coerceAtLeast(0)
                )
                .put(
                    "personal_search_version",
                    VERSION
                )
                .put(
                    "search_contract_version",
                    report.searchContractVersion
                )

        val requestJson =
            JSONObject()
                .put(
                    "query",
                    report.request.query.take(MAX_SESSION_QUERY_CHARS)
                )

        val sourceArray =
            JSONArray()

        report.request.sources
            .sortedBy {
                it.ordinal
            }
            .forEach { source ->
                sourceArray.put(
                    source.wireName
                )
            }

        requestJson.put(
            "sources",
            sourceArray
        )

        root.put(
            "request",
            requestJson
        )

        val hitsArray =
            JSONArray()

        report.hits
            .take(MAX_TOTAL_LIMIT)
            .forEach { hit ->
                hitsArray.put(
                    JSONObject()
                        .put(
                            "source",
                            hit.source.wireName
                        )
                        .put(
                            "title",
                            hit.title.take(MAX_TITLE_CHARS)
                        )
                        .put(
                            "snippet",
                            hit.snippet.take(MAX_SNIPPET_CHARS)
                        )
                        .put(
                            "timestamp_ms",
                            hit.timestampMs
                        )
                        .put(
                            "score",
                            hit.score
                        )
                        .put(
                            "metadata",
                            hit.metadata.take(MAX_SESSION_METADATA_CHARS)
                        )
                        .put(
                            "action_uri",
                            hit.actionUri.take(MAX_SESSION_URI_CHARS)
                        )
                        .put(
                            "action_mime",
                            hit.actionMimeType.take(160)
                        )
                        .put(
                            "action_kind",
                            hit.actionKind.take(40)
                        )
                        .put(
                            "match_kind",
                            hit.matchKind.take(80)
                        )
                        .put(
                            "confidence",
                            hit.confidence
                        )
                        .put(
                            "verified",
                            hit.verified
                        )
                        .put(
                            "provenance",
                            hit.provenance.take(120)
                        )
                        .put(
                            "fingerprint",
                            hit.fingerprint.take(64)
                        )
                )
            }

        root.put(
            "hits",
            hitsArray
        )

        root.put(
            "source_match_counts",
            sourceIntMapToJson(
                report.sourceMatchCounts
            )
        )
        root.put(
            "source_errors",
            sourceStringMapToJson(
                report.sourceErrors
            )
        )
        root.put(
            "source_coverage",
            sourceStringMapToJson(
                report.sourceCoverage
            )
        )

        root
            .put(
                "scanned_history",
                report.scannedHistoryRecords
            )
            .put(
                "scanned_notifications",
                report.scannedNotifications
            )
            .put(
                "scanned_files",
                report.scannedFiles
            )
            .put(
                "scanned_photos",
                report.scannedPhotos
            )
            .put(
                "content_provider_rows",
                report.contentProviderRowsScanned
            )
            .put(
                "content_candidates",
                report.contentCandidateDocuments
            )
            .put(
                "content_indexed",
                report.contentIndexedDocuments
            )
            .put(
                "content_reused",
                report.contentReusedDocuments
            )
            .put(
                "content_updated",
                report.contentUpdatedDocuments
            )
            .put(
                "content_failed",
                report.contentFailedDocuments
            )
            .put(
                "content_unsupported",
                report.contentUnsupportedDocuments
            )
            .put(
                "content_pdf_best_effort",
                report.contentPdfBestEffortDocuments
            )
            .put(
                "image_provider_rows",
                report.imageProviderRowsScanned
            )
            .put(
                "image_candidates",
                report.imageCandidatePhotos
            )
            .put(
                "image_indexed",
                report.imageIndexedPhotos
            )
            .put(
                "image_reused",
                report.imageReusedPhotos
            )
            .put(
                "image_updated",
                report.imageUpdatedPhotos
            )
            .put(
                "image_failed",
                report.imageFailedPhotos
            )
            .put(
                "image_pending",
                report.imagePendingPhotos
            )
            .put(
                "image_ocr",
                report.imageOcrPhotos
            )
            .put(
                "image_labeled",
                report.imageLabeledPhotos
            )
            .put(
                "image_budget",
                report.imageNewIndexBudget
            )

        sessionPreferences
            .edit()
            .putString(
                SESSION_KEY_JSON,
                root.toString()
            )
            .apply()
    }

    private fun loadSession(): SearchSession? {
        val raw =
            sessionPreferences.getString(
                SESSION_KEY_JSON,
                null
            )
                ?: return null

        val root =
            try {
                JSONObject(raw)
            } catch (_: Exception) {
                clearSession()
                return null
            }

        val savedAtMs =
            root.optLong(
                "saved_at_ms",
                0L
            )

        val now =
            System.currentTimeMillis()

        if (
            savedAtMs <= 0L ||
            now - savedAtMs >
            SESSION_TTL_MS
        ) {
            clearSession()
            return null
        }

        val requestJson =
            root.optJSONObject(
                "request"
            )
                ?: run {
                    clearSession()
                    return null
                }

        val query =
            requestJson
                .optString(
                    "query"
                )
                .trim()

        if (query.isBlank()) {
            clearSession()
            return null
        }

        val requestedSources =
            linkedSetOf<Source>()

        val requestSourcesJson =
            requestJson.optJSONArray(
                "sources"
            )

        if (requestSourcesJson != null) {
            for (
                index in
                0 until requestSourcesJson.length()
            ) {
                sourceFromWire(
                    requestSourcesJson
                        .optString(index)
                )
                    ?.let(
                        requestedSources::add
                    )
            }
        }

        if (requestedSources.isEmpty()) {
            clearSession()
            return null
        }

        val hits =
            mutableListOf<Hit>()

        val hitsJson =
            root.optJSONArray(
                "hits"
            )

        if (hitsJson != null) {
            for (
                index in
                0 until hitsJson.length()
            ) {
                val row =
                    hitsJson.optJSONObject(index)
                        ?: continue

                val source =
                    sourceFromWire(
                        row.optString(
                            "source"
                        )
                    )
                        ?: continue

                hits +=
                    Hit(
                        source = source,
                        title =
                            row.optString(
                                "title"
                            ),
                        snippet =
                            row.optString(
                                "snippet"
                            ),
                        timestampMs =
                            row.optLong(
                                "timestamp_ms",
                                0L
                            ),
                        score =
                            row.optInt(
                                "score",
                                0
                            ),
                        metadata =
                            row.optString(
                                "metadata"
                            ),
                        actionUri =
                            row.optString(
                                "action_uri"
                            ),
                        actionMimeType =
                            row.optString(
                                "action_mime"
                            ),
                        actionKind =
                            row.optString(
                                "action_kind"
                            ),
                        matchKind =
                            row.optString(
                                "match_kind",
                                "lexical"
                            ),
                        confidence =
                            row.optInt(
                                "confidence",
                                0
                            ),
                        verified =
                            row.optBoolean(
                                "verified",
                                true
                            ),
                        provenance =
                            row.optString(
                                "provenance"
                            ),
                        fingerprint =
                            row.optString(
                                "fingerprint"
                            )
                    )
            }
        }

        val normalizedHits =
            hits.map { hit ->
                if (
                    hit.provenance.isBlank() ||
                    hit.fingerprint.length != 64 ||
                    hit.confidence <= 0
                ) {
                    enrichHit(
                        query = query,
                        hit = hit
                    )
                } else {
                    hit
                }
            }

        val report =
            Report(
                request =
                    Request(
                        query = query,
                        sources = requestedSources
                    ),
                hits = normalizedHits,
                sourceMatchCounts =
                    sourceIntMapFromJson(
                        root.optJSONObject(
                            "source_match_counts"
                        )
                    ),
                sourceErrors =
                    sourceStringMapFromJson(
                        root.optJSONObject(
                            "source_errors"
                        )
                    ),
                sourceCoverage =
                    sourceStringMapFromJson(
                        root.optJSONObject(
                            "source_coverage"
                        )
                    ),
                scannedHistoryRecords =
                    root.optInt(
                        "scanned_history",
                        0
                    ),
                scannedNotifications =
                    root.optInt(
                        "scanned_notifications",
                        0
                    ),
                scannedFiles =
                    root.optInt(
                        "scanned_files",
                        0
                    ),
                scannedPhotos =
                    root.optInt(
                        "scanned_photos",
                        0
                    ),
                contentProviderRowsScanned =
                    root.optInt(
                        "content_provider_rows",
                        0
                    ),
                contentCandidateDocuments =
                    root.optInt(
                        "content_candidates",
                        0
                    ),
                contentIndexedDocuments =
                    root.optInt(
                        "content_indexed",
                        0
                    ),
                contentReusedDocuments =
                    root.optInt(
                        "content_reused",
                        0
                    ),
                contentUpdatedDocuments =
                    root.optInt(
                        "content_updated",
                        0
                    ),
                contentFailedDocuments =
                    root.optInt(
                        "content_failed",
                        0
                    ),
                contentUnsupportedDocuments =
                    root.optInt(
                        "content_unsupported",
                        0
                    ),
                contentPdfBestEffortDocuments =
                    root.optInt(
                        "content_pdf_best_effort",
                        0
                    ),
                imageProviderRowsScanned =
                    root.optInt(
                        "image_provider_rows",
                        0
                    ),
                imageCandidatePhotos =
                    root.optInt(
                        "image_candidates",
                        0
                    ),
                imageIndexedPhotos =
                    root.optInt(
                        "image_indexed",
                        0
                    ),
                imageReusedPhotos =
                    root.optInt(
                        "image_reused",
                        0
                    ),
                imageUpdatedPhotos =
                    root.optInt(
                        "image_updated",
                        0
                    ),
                imageFailedPhotos =
                    root.optInt(
                        "image_failed",
                        0
                    ),
                imagePendingPhotos =
                    root.optInt(
                        "image_pending",
                        0
                    ),
                imageOcrPhotos =
                    root.optInt(
                        "image_ocr",
                        0
                    ),
                imageLabeledPhotos =
                    root.optInt(
                        "image_labeled",
                        0
                    ),
                imageNewIndexBudget =
                    root.optInt(
                        "image_budget",
                        0
                    ),
                searchContractVersion = SEARCH_CONTRACT_VERSION,
                queryTerms = searchTokens(query),
                expandedQueryTerms =
                    expandedSearchTokens(query)
                        .filterNot { it in searchTokens(query) },
                verifiedHitCount = normalizedHits.count { it.verified },
                highConfidenceHitCount = normalizedHits.count { it.verified && it.confidence >= HIGH_CONFIDENCE_THRESHOLD },
                provenanceComplete = normalizedHits.all { it.provenance.isNotBlank() && it.fingerprint.length == 64 }
            )

        return SearchSession(
            report = report,
            pageIndex =
                root.optInt(
                    "page_index",
                    0
                ),
            savedAtMs = savedAtMs
        )
    }

    private fun clearSession() {
        sessionPreferences
            .edit()
            .remove(
                SESSION_KEY_JSON
            )
            .apply()

        searchResultStore.clear()
    }

    private fun sourceIntMapToJson(
        values: Map<Source, Int>
    ): JSONObject {
        val result =
            JSONObject()

        values.forEach { (source, value) ->
            result.put(
                source.wireName,
                value
            )
        }

        return result
    }

    private fun sourceStringMapToJson(
        values: Map<Source, String>
    ): JSONObject {
        val result =
            JSONObject()

        values.forEach { (source, value) ->
            result.put(
                source.wireName,
                value.take(MAX_SESSION_DETAIL_CHARS)
            )
        }

        return result
    }

    private fun sourceIntMapFromJson(
        value: JSONObject?
    ): Map<Source, Int> {
        if (value == null) {
            return emptyMap()
        }

        val result =
            linkedMapOf<Source, Int>()

        Source.values()
            .forEach { source ->
                if (value.has(source.wireName)) {
                    result[source] =
                        value.optInt(
                            source.wireName,
                            0
                        )
                }
            }

        return result
    }

    private fun sourceStringMapFromJson(
        value: JSONObject?
    ): Map<Source, String> {
        if (value == null) {
            return emptyMap()
        }

        val result =
            linkedMapOf<Source, String>()

        Source.values()
            .forEach { source ->
                if (value.has(source.wireName)) {
                    result[source] =
                        value.optString(
                            source.wireName
                        )
                }
            }

        return result
    }

    private fun sourceFromWire(
        wireName: String
    ): Source? =
        Source.values()
            .firstOrNull {
                it.wireName ==
                    wireName.trim()
            }

    private fun pageCount(
        hitCount: Int,
        pageSize: Int
    ): Int {
        if (hitCount <= 0) {
            return 0
        }

        val safePageSize =
            pageSize.coerceAtLeast(1)

        return (
            (
                hitCount +
                    safePageSize -
                    1
                ) /
                safePageSize
            )
            .coerceAtLeast(1)
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
    ): Int =
        lexicalEvidence(
            query = query,
            haystack = haystack
        ).score

    private fun lexicalEvidence(
        query: String,
        haystack: String
    ): MatchEvidence {
        val normalizedQuery =
            normalizeForSearch(query)
        val normalizedHaystack =
            normalizeForSearch(haystack)

        if (
            normalizedQuery.isBlank() ||
            normalizedHaystack.isBlank()
        ) {
            return MatchEvidence(
                score = 0,
                confidence = 0,
                exactPhrase = false,
                directMatchedTokens = 0,
                totalQueryTokens = 0,
                semanticExpansionUsed = false,
                morphologyUsed = false,
                kind = "no_match"
            )
        }

        val queryTokens =
            searchTokens(normalizedQuery)

        val exactPhrase =
            normalizedQuery.length >= 3 &&
                normalizedHaystack.contains(
                    normalizedQuery
                )

        if (queryTokens.isEmpty()) {
            return if (exactPhrase) {
                MatchEvidence(
                    score = 80,
                    confidence = 94,
                    exactPhrase = true,
                    directMatchedTokens = 1,
                    totalQueryTokens = 1,
                    semanticExpansionUsed = false,
                    morphologyUsed = false,
                    kind = "exact_phrase"
                )
            } else {
                MatchEvidence(
                    score = 0,
                    confidence = 0,
                    exactPhrase = false,
                    directMatchedTokens = 0,
                    totalQueryTokens = 0,
                    semanticExpansionUsed = false,
                    morphologyUsed = false,
                    kind = "no_match"
                )
            }
        }

        val haystackTokens =
            searchTokens(normalizedHaystack)

        var directMatches = 0
        var morphologyMatches = 0
        var semanticMatches = 0

        queryTokens.forEach { queryToken ->
            val direct =
                haystackTokens.any { hayToken ->
                    hayToken == queryToken ||
                        (
                            queryToken.length >= 3 &&
                            hayToken.contains(queryToken)
                            ) ||
                        (
                            hayToken.length >= 3 &&
                            queryToken.contains(hayToken)
                            )
                } ||
                    normalizedHaystack.contains(
                        queryToken
                    )

            if (direct) {
                directMatches++
                return@forEach
            }

            val queryStem =
                tokenStem(queryToken)

            val morphology =
                queryStem.length >= MIN_STEM_LENGTH &&
                    haystackTokens.any { hayToken ->
                        val hayStem = tokenStem(hayToken)
                        hayStem.length >= MIN_STEM_LENGTH &&
                            hayStem == queryStem
                    }

            if (morphology) {
                morphologyMatches++
                return@forEach
            }

            val aliases =
                semanticAliasesFor(
                    queryToken
                )

            val semantic =
                aliases.any { alias ->
                    haystackTokens.any { hayToken ->
                        hayToken == alias ||
                            (
                                alias.length >= 3 &&
                                hayToken.contains(alias)
                                ) ||
                            (
                                tokenStem(alias).length >= MIN_STEM_LENGTH &&
                                tokenStem(alias) == tokenStem(hayToken)
                                )
                    }
                }

            if (semantic) {
                semanticMatches++
            }
        }

        val totalMatched =
            directMatches +
                morphologyMatches +
                semanticMatches

        if (
            totalMatched == 0 &&
            !exactPhrase
        ) {
            return MatchEvidence(
                score = 0,
                confidence = 0,
                exactPhrase = false,
                directMatchedTokens = 0,
                totalQueryTokens = queryTokens.size,
                semanticExpansionUsed = false,
                morphologyUsed = false,
                kind = "no_match"
            )
        }

        val allMatched =
            totalMatched == queryTokens.size
        val allDirect =
            directMatches == queryTokens.size
        val semanticUsed =
            semanticMatches > 0
        val morphologyUsed =
            morphologyMatches > 0

        val score =
            (if (exactPhrase) 80 else 0) +
                directMatches * 22 +
                morphologyMatches * 19 +
                semanticMatches * 16 +
                if (
                    allMatched &&
                    queryTokens.size > 1
                ) {
                    24
                } else {
                    0
                }

        val confidence =
            when {
                exactPhrase && allDirect -> 98
                exactPhrase -> 94
                allDirect -> 90
                allMatched && !semanticUsed -> 86
                allMatched -> 82
                totalMatched * 3 >= queryTokens.size * 2 -> 76
                else -> 64
            }

        val kind =
            when {
                exactPhrase -> "exact_phrase"
                semanticUsed -> "semantic_lexical"
                morphologyUsed -> "morphology_lexical"
                else -> "lexical"
            }

        return MatchEvidence(
            score = score,
            confidence = confidence,
            exactPhrase = exactPhrase,
            directMatchedTokens = directMatches,
            totalQueryTokens = queryTokens.size,
            semanticExpansionUsed = semanticUsed,
            morphologyUsed = morphologyUsed,
            kind = kind
        )
    }

    private fun expandedSearchTokens(
        value: String
    ): List<String> {
        val result =
            linkedSetOf<String>()

        searchTokens(value)
            .forEach { token ->
                result += token
                semanticAliasesFor(token)
                    .forEach(
                        result::add
                    )
            }

        return result
            .take(
                MAX_EXPANDED_QUERY_TERMS
            )
    }

    private fun semanticAliasesFor(
        token: String
    ): Set<String> {
        val normalized =
            normalizeForSearch(token)

        return SEMANTIC_ALIAS_GROUPS
            .firstOrNull { group ->
                normalized in group
            }
            ?.filterNot { it == normalized }
            ?.toSet()
            ?: emptySet()
    }

    private fun tokenStem(
        token: String
    ): String {
        var value =
            normalizeForSearch(token)
                .replace(" ", "")

        if (value.length < MIN_STEM_LENGTH + 1) {
            return value
        }

        val russianEndings =
            listOf(
                "иями", "ями", "ами",
                "ого", "ему", "ому",
                "ыми", "ими",
                "иях", "ах", "ях",
                "ией", "ой", "ей",
                "ом", "ем",
                "ов", "ев",
                "ия", "ие", "ий",
                "ы", "и", "а", "я", "у", "ю", "е", "о"
            )

        russianEndings
            .firstOrNull { ending ->
                value.length - ending.length >= MIN_STEM_LENGTH &&
                    value.endsWith(ending)
            }
            ?.let { ending ->
                value = value.dropLast(ending.length)
            }

        if (value.length >= MIN_STEM_LENGTH + 2) {
            val englishEnding =
                listOf(
                    "ing",
                    "ed",
                    "es",
                    "s"
                )
                    .firstOrNull { ending ->
                        value.length - ending.length >= MIN_STEM_LENGTH &&
                            value.endsWith(ending)
                    }

            if (englishEnding != null) {
                value =
                    value.dropLast(
                        englishEnding.length
                    )
            }
        }

        return value
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

        if (
            technical.contains("personal_search_local") ||
            technical.contains("personal_search_followup")
        ) {
            return true
        }

        if (command.isBlank()) {
            return false
        }

        return parseRequest(command) != null
    }

    companion object {
        const val VERSION = "2.0"
        const val SEARCH_CONTRACT_VERSION = 2

        private const val HIGH_CONFIDENCE_THRESHOLD = 80
        private const val MIN_STEM_LENGTH = 4
        private const val MAX_EXPANDED_QUERY_TERMS = 24
        private const val DEFAULT_PER_SOURCE_LIMIT = 8
        private const val DEFAULT_TOTAL_LIMIT = 20
        private const val MAX_PER_SOURCE_LIMIT = 12
        private const val MAX_TOTAL_LIMIT = 20
        private const val MIN_PER_SOURCE_IN_GLOBAL_POOL = 2
        private const val DEFAULT_PAGE_SIZE = 6
        private const val MAX_PAGE_SIZE = 10
        private const val SESSION_PREFS_NAME = "ayana_personal_search_session_v1"
        private const val SESSION_KEY_JSON = "latest_session_json"
        private const val SESSION_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_SESSION_QUERY_CHARS = 320
        private const val MAX_SESSION_METADATA_CHARS = 420
        private const val MAX_SESSION_DETAIL_CHARS = 900
        private const val MAX_SESSION_URI_CHARS = 1800
        private const val MAX_MEMORY_SCAN = 200
        private const val MAX_HISTORY_SCAN = 80
        private const val MAX_NOTIFICATION_SCAN = 32
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_SNIPPET_CHARS = 360
        private const val MAX_HISTORY_COMMAND_SNIPPET_CHARS = 220
        private const val MAX_HISTORY_RESULT_SNIPPET_CHARS = 360

        private val SEMANTIC_ALIAS_GROUPS =
            listOf(
                setOf("ворд", "word", "docx"),
                setOf("эксель", "excel", "xlsx"),
                setOf("пдф", "pdf"),
                setOf("скрин", "скриншот", "screenshot"),
                setOf("фото", "фотография", "фотографии", "изображение", "image", "photo"),
                setOf("ютуб", "youtube"),
                setOf("браузер", "browser"),
                setOf("календарь", "calendar"),
                setOf("уведомление", "уведомления", "notification", "notifications"),
                setOf("напоминание", "напоминания", "reminder", "reminders"),
                setOf("задача", "задачи", "task", "tasks")
            )

        private val ALL_SOURCES =
            linkedSetOf(
                Source.MEMORY,
                Source.HISTORY,
                Source.TASKS,
                Source.NOTIFICATIONS,
                Source.FILES,
                Source.PHOTOS
            )

        fun parseFollowUp(
            command: String
        ): FollowUpRequest? {
            val normalized =
                normalizeForSearch(
                    command
                )
                    .removePrefix(
                        "аяна "
                    )
                    .trim()

            if (normalized.isBlank()) {
                return null
            }

            val nextPagePhrases =
                setOf(
                    "покажи следующие результаты",
                    "следующие результаты",
                    "покажи следующие",
                    "следующая страница результатов",
                    "покажи дальше результаты",
                    "дальше результаты"
                )

            if (normalized in nextPagePhrases) {
                return FollowUpRequest(
                    kind =
                        FollowUpKind.NEXT_PAGE
                )
            }

            val previousPagePhrases =
                setOf(
                    "покажи предыдущие результаты",
                    "предыдущие результаты",
                    "покажи предыдущие",
                    "предыдущая страница результатов"
                )

            if (normalized in previousPagePhrases) {
                return FollowUpRequest(
                    kind =
                        FollowUpKind.PREVIOUS_PAGE
                )
            }

            val allSourcePhrases =
                setOf(
                    "покажи все источники",
                    "повтори по всем источникам",
                    "повтори поиск по всем источникам",
                    "покажи снова все источники"
                )

            if (normalized in allSourcePhrases) {
                return FollowUpRequest(
                    kind =
                        FollowUpKind.FILTER_SOURCES,
                    sources =
                        LinkedHashSet(
                            ALL_SOURCES
                        )
                )
            }

            val filterPrefixes =
                listOf(
                    "покажи только ",
                    "повтори только ",
                    "повтори поиск только ",
                    "оставь только ",
                    "покажи результаты только из ",
                    "покажи результаты только по "
                )

            val tail =
                filterPrefixes
                    .firstOrNull {
                        normalized.startsWith(it)
                    }
                    ?.let { prefix ->
                        normalized
                            .removePrefix(prefix)
                            .removePrefix("по ")
                            .removePrefix("из ")
                            .trim()
                    }
                    ?: return null

            val sources =
                parseFollowUpSources(
                    tail
                )

            if (sources.isEmpty()) {
                return null
            }

            return FollowUpRequest(
                kind =
                    FollowUpKind.FILTER_SOURCES,
                sources = sources
            )
        }

        private fun parseFollowUpSources(
            value: String
        ): Set<Source> {
            val normalized =
                normalizeForSearch(
                    value
                )

            val sources =
                linkedSetOf<Source>()

            if (
                listOf(
                    "память",
                    "памяти"
                ).any(normalized::contains)
            ) {
                sources +=
                    Source.MEMORY
            }

            if (
                listOf(
                    "история",
                    "истории"
                ).any(normalized::contains)
            ) {
                sources +=
                    Source.HISTORY
            }

            if (
                listOf(
                    "задачи",
                    "задач",
                    "напоминания",
                    "напоминаний"
                ).any(normalized::contains)
            ) {
                sources +=
                    Source.TASKS
            }

            if (
                listOf(
                    "уведомления",
                    "уведомлений"
                ).any(normalized::contains)
            ) {
                sources +=
                    Source.NOTIFICATIONS
            }

            if (
                listOf(
                    "файлы",
                    "файлов",
                    "файл",
                    "документы",
                    "документов",
                    "документ",
                    "загрузки"
                ).any(normalized::contains)
            ) {
                sources +=
                    Source.FILES
            }

            if (
                listOf(
                    "фото",
                    "фотографии",
                    "фотографий",
                    "галерея",
                    "галереи",
                    "изображения",
                    "изображений"
                ).any(normalized::contains)
            ) {
                sources +=
                    Source.PHOTOS
            }

            return sources
        }

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