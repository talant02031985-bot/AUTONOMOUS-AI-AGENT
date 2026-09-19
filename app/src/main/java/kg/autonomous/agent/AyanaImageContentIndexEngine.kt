package kg.autonomous.agent

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * AYANA Image Content Index Engine v1.0 — LOCAL ML KIT progressive index.
 *
 * Truth / privacy contract:
 * - image bytes stay on the Android device; this engine never calls Worker / Agent Core;
 * - OCR uses bundled ML Kit Text Recognition;
 * - visual categories use bundled ML Kit default Image Labeling (400+ generic labels);
 * - the index lives only in AYANA app-private filesDir;
 * - unchanged images reuse cached OCR/labels;
 * - indexing is deliberately bounded per search so a 700+ photo library cannot freeze AYANA;
 * - a partial index is explicitly reported as partial and is never described as full visual coverage;
 * - Android MediaStore visibility remains the outer boundary of what can be indexed;
 * - labels describe generic visual categories only; this is NOT biometric identification.
 */
class AyanaImageContentIndexEngine(
    context: Context
) {

    enum class MatchType(
        val wireName: String
    ) {
        OCR("ocr"),
        LABEL("label"),
        OCR_AND_LABEL("ocr_and_label")
    }

    data class Hit(
        val displayName: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val timestampMs: Long,
        val uri: String,
        val snippet: String,
        val score: Int,
        val matchType: MatchType,
        val labels: List<String>
    )

    data class SearchResult(
        val hits: List<Hit>,
        val providerRowsScanned: Int,
        val candidateImages: Int,
        val indexedImages: Int,
        val reusedImages: Int,
        val updatedImages: Int,
        val failedImages: Int,
        val pendingImages: Int,
        val ocrIndexedImages: Int,
        val labeledImages: Int,
        val newIndexBudget: Int,
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
        val takenAtMs: Long,
        val width: Int,
        val height: Int
    )

    private data class Analysis(
        val ocrText: String,
        val labels: List<Pair<String, Float>>,
        val textOk: Boolean,
        val labelsOk: Boolean
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

    private val textRecognizer by lazy {
        TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )
    }

    private val imageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder()
                .setConfidenceThreshold(MIN_LABEL_CONFIDENCE)
                .build()
        )
    }

    fun search(
        query: String,
        limit: Int = DEFAULT_LIMIT,
        maxNewImages: Int = DEFAULT_NEW_IMAGE_BUDGET
    ): SearchResult {
        val normalizedQuery = normalize(query)
        if (normalizedQuery.isBlank()) {
            return emptyResult(
                detail = "Пустой запрос: визуальный индекс не запускался.",
                budget = 0
            )
        }

        val safeBudget =
            maxNewImages.coerceIn(0, MAX_NEW_IMAGE_BUDGET)

        synchronized(lock) {
            val enumeration = enumerateImages()
            val descriptors = enumeration.first
            val providerRowsScanned = enumeration.second

            val oldIndex = loadIndexUnsafe()
            val nextIndex = linkedMapOf<String, JSONObject>()

            var reused = 0
            var updated = 0
            var failed = 0
            var pending = 0
            var ocrIndexed = 0
            var labeled = 0
            var remainingBudget = safeBudget

            descriptors.forEach { descriptor ->
                val fingerprint = fingerprint(descriptor)
                val old = oldIndex[descriptor.key]

                if (
                    old != null &&
                    old.optString("fingerprint") == fingerprint &&
                    old.optString("status") == STATUS_INDEXED
                ) {
                    nextIndex[descriptor.key] = old
                    reused++
                    if (old.optString("ocr_text").isNotBlank()) {
                        ocrIndexed++
                    }
                    if (old.optJSONArray("labels")?.length()?.let { it > 0 } == true) {
                        labeled++
                    }
                    return@forEach
                }

                if (
                    old != null &&
                    old.optString("fingerprint") == fingerprint &&
                    old.optString("status") == STATUS_FAILED &&
                    !shouldRetryFailed(old)
                ) {
                    nextIndex[descriptor.key] = old
                    failed++
                    return@forEach
                }

                if (remainingBudget <= 0) {
                    pending++
                    return@forEach
                }

                remainingBudget--

                val analysis =
                    try {
                        analyze(descriptor)
                    } catch (_: Exception) {
                        null
                    }

                if (
                    analysis == null ||
                    (!analysis.textOk && !analysis.labelsOk)
                ) {
                    failed++
                    nextIndex[descriptor.key] =
                        baseEntry(descriptor, fingerprint)
                            .put("status", STATUS_FAILED)
                            .put("failed_at", System.currentTimeMillis())
                            .put("ocr_text", "")
                            .put("labels", JSONArray())
                    return@forEach
                }

                updated++

                val compactOcr =
                    compactIndexText(analysis.ocrText)

                if (compactOcr.isNotBlank()) {
                    ocrIndexed++
                }
                if (analysis.labels.isNotEmpty()) {
                    labeled++
                }

                val labelsJson = JSONArray()
                analysis.labels
                    .take(MAX_STORED_LABELS)
                    .forEach { (label, confidence) ->
                        labelsJson.put(
                            JSONObject()
                                .put("text", label.take(120))
                                .put("confidence", confidence.toDouble())
                        )
                    }

                nextIndex[descriptor.key] =
                    baseEntry(descriptor, fingerprint)
                        .put("status", STATUS_INDEXED)
                        .put("ocr_text", compactOcr)
                        .put("labels", labelsJson)
                        .put("text_ok", analysis.textOk)
                        .put("labels_ok", analysis.labelsOk)
                        .put("indexed_at", System.currentTimeMillis())
            }

            // Preserve a current-fingerprint failed entry even when its descriptor was not
            // re-written above. Never preserve removed photos or stale fingerprints.
            descriptors.forEach { descriptor ->
                if (descriptor.key in nextIndex) {
                    return@forEach
                }
                val old = oldIndex[descriptor.key]
                if (
                    old != null &&
                    old.optString("fingerprint") == fingerprint(descriptor) &&
                    old.optString("status") == STATUS_FAILED
                ) {
                    nextIndex[descriptor.key] = old
                }
            }

            saveIndexUnsafe(nextIndex)

            val expandedQueryTokens =
                expandedQueryTokens(query)

            val hits =
                nextIndex.values
                    .asSequence()
                    .filter {
                        it.optString("status") == STATUS_INDEXED
                    }
                    .mapNotNull { entry ->
                        scoreEntry(
                            normalizedQuery = normalizedQuery,
                            expandedQueryTokens = expandedQueryTokens,
                            entry = entry
                        )
                    }
                    .sortedWith(
                        compareByDescending<Hit> { it.score }
                            .thenByDescending { it.timestampMs }
                    )
                    .take(limit.coerceIn(1, MAX_RETURNED_HITS))
                    .toList()

            val indexedCount =
                nextIndex.values.count {
                    it.optString("status") == STATUS_INDEXED
                }

            val currentFailedCount =
                nextIndex.values.count {
                    it.optString("status") == STATUS_FAILED
                }

            val currentPending =
                (descriptors.size - indexedCount - currentFailedCount)
                    .coerceAtLeast(0)

            val complete =
                currentPending == 0 &&
                    indexedCount + currentFailedCount >= descriptors.size

            return SearchResult(
                hits = hits,
                providerRowsScanned = providerRowsScanned,
                candidateImages = descriptors.size,
                indexedImages = indexedCount,
                reusedImages = reused,
                updatedImages = updated,
                failedImages = currentFailedCount,
                pendingImages = currentPending,
                ocrIndexedImages = ocrIndexed,
                labeledImages = labeled,
                newIndexBudget = safeBudget,
                detail =
                    buildString {
                        append("Локальный визуальный индекс ML Kit: indexed=")
                        append(indexedCount)
                        append("/")
                        append(descriptors.size)
                        append("; reused=")
                        append(reused)
                        append("; updated=")
                        append(updated)
                        append("; failed=")
                        append(currentFailedCount)
                        append("; pending=")
                        append(currentPending)
                        append("; ocr=")
                        append(ocrIndexed)
                        append("; labeled=")
                        append(labeled)
                        append(". ")
                        if (complete) {
                            append("Все доступные через MediaStore изображения имеют текущее состояние индекса. ")
                        } else {
                            append("Визуальный охват частичный: непроиндексированные изображения не считаются проверенными по содержимому. ")
                        }
                        append("OCR и generic image labels выполняются локально на планшете; изображения не отправляются в Worker/Agent Core.")
                    }
                    .take(MAX_DETAIL_CHARS)
            )
        }
    }

    private fun analyze(
        descriptor: Descriptor
    ): Analysis? {
        val uri =
            Uri.parse(descriptor.uri)

        val inputImage =
            try {
                InputImage.fromFilePath(
                    appContext,
                    uri
                )
            } catch (_: Exception) {
                return null
            }

        val textTask =
            try {
                textRecognizer.process(inputImage)
            } catch (_: Exception) {
                null
            }

        val labelTask =
            try {
                imageLabeler.process(inputImage)
            } catch (_: Exception) {
                null
            }

        val textResult =
            textTask?.let {
                awaitTask(
                    task = it,
                    timeoutMs = PER_ANALYZER_TIMEOUT_MS
                )
            }

        val labelResult =
            labelTask?.let {
                awaitTask(
                    task = it,
                    timeoutMs = PER_ANALYZER_TIMEOUT_MS
                )
            }

        val ocrText =
            textResult
                ?.text
                .orEmpty()

        val labels =
            labelResult
                .orEmpty()
                .asSequence()
                .filter {
                    it.confidence >= MIN_LABEL_CONFIDENCE
                }
                .sortedByDescending {
                    it.confidence
                }
                .take(MAX_STORED_LABELS)
                .map {
                    it.text.trim() to it.confidence
                }
                .filter {
                    it.first.isNotBlank()
                }
                .toList()

        return Analysis(
            ocrText = ocrText,
            labels = labels,
            textOk = textResult != null,
            labelsOk = labelResult != null
        )
    }

    private fun enumerateImages(): Pair<List<Descriptor>, Int> {
        val result = mutableListOf<Descriptor>()
        var scanned = 0

        val collection =
            if (Build.VERSION.SDK_INT >= 29) {
                MediaStore.Images.Media.getContentUri(
                    MediaStore.VOLUME_EXTERNAL
                )
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

        val projection =
            buildList {
                add(MediaStore.Images.Media._ID)
                add(MediaStore.Images.Media.DISPLAY_NAME)
                add(MediaStore.Images.Media.MIME_TYPE)
                add(MediaStore.Images.Media.SIZE)
                add(MediaStore.Images.Media.DATE_MODIFIED)
                add(MediaStore.Images.Media.DATE_TAKEN)
                add(MediaStore.Images.Media.WIDTH)
                add(MediaStore.Images.Media.HEIGHT)
                if (Build.VERSION.SDK_INT >= 29) {
                    add(MediaStore.Images.Media.RELATIVE_PATH)
                }
            }
                .toTypedArray()

        resolver.query(
            collection,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameIndex = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)
            val sizeIndex = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
            val modifiedIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val takenIndex = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val widthIndex = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightIndex = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val pathIndex =
                if (Build.VERSION.SDK_INT >= 29) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else {
                    -1
                }

            while (
                cursor.moveToNext() &&
                scanned < MAX_PROVIDER_ROWS_SCAN
            ) {
                scanned++

                val id = cursor.getLong(idIndex)
                val uri =
                    ContentUris.withAppendedId(
                        collection,
                        id
                    )

                val displayName =
                    if (nameIndex >= 0) cursor.getString(nameIndex).orEmpty() else ""
                val mimeType =
                    if (mimeIndex >= 0) cursor.getString(mimeIndex).orEmpty() else ""
                val sizeBytes =
                    if (sizeIndex >= 0) cursor.getLong(sizeIndex) else 0L
                val modifiedAtMs =
                    if (modifiedIndex >= 0) cursor.getLong(modifiedIndex) * 1000L else 0L
                val takenAtMs =
                    if (takenIndex >= 0) cursor.getLong(takenIndex) else 0L
                val width =
                    if (widthIndex >= 0) cursor.getInt(widthIndex) else 0
                val height =
                    if (heightIndex >= 0) cursor.getInt(heightIndex) else 0
                val relativePath =
                    if (pathIndex >= 0) cursor.getString(pathIndex).orEmpty() else ""

                if (
                    mimeType.startsWith("image/", ignoreCase = true) &&
                    sizeBytes in 1..MAX_SOURCE_IMAGE_BYTES
                ) {
                    result +=
                        Descriptor(
                            key = uri.toString(),
                            uri = uri.toString(),
                            displayName = displayName,
                            relativePath = relativePath,
                            mimeType = mimeType,
                            sizeBytes = sizeBytes,
                            modifiedAtMs = modifiedAtMs,
                            takenAtMs = takenAtMs,
                            width = width,
                            height = height
                        )
                }
            }
        }

        return result to scanned
    }

    private fun scoreEntry(
        normalizedQuery: String,
        expandedQueryTokens: Set<String>,
        entry: JSONObject
    ): Hit? {
        val ocrText =
            entry.optString("ocr_text")
        val normalizedOcr =
            normalize(ocrText)

        val labelRows =
            mutableListOf<Pair<String, Float>>()
        val labelsArray =
            entry.optJSONArray("labels")
                ?: JSONArray()

        for (index in 0 until labelsArray.length()) {
            val row = labelsArray.optJSONObject(index) ?: continue
            val text = row.optString("text").trim()
            val confidence = row.optDouble("confidence", 0.0).toFloat()
            if (text.isNotBlank()) {
                labelRows += text to confidence
            }
        }

        val normalizedLabels =
            normalize(
                labelRows.joinToString(" ") { it.first }
            )

        val ocrScore =
            lexicalScore(
                query = normalizedQuery,
                haystack = normalizedOcr
            )

        val labelScore =
            labelMatchScore(
                expandedQueryTokens = expandedQueryTokens,
                normalizedLabels = normalizedLabels,
                labels = labelRows
            )

        if (ocrScore <= 0 && labelScore <= 0) {
            return null
        }

        val matchType =
            when {
                ocrScore > 0 && labelScore > 0 -> MatchType.OCR_AND_LABEL
                ocrScore > 0 -> MatchType.OCR
                else -> MatchType.LABEL
            }

        val snippet =
            when (matchType) {
                MatchType.OCR,
                MatchType.OCR_AND_LABEL ->
                    contextualSnippet(
                        text = ocrText,
                        query = normalizedQuery
                    )
                        .ifBlank {
                            labelRows
                                .take(5)
                                .joinToString(", ") { (label, confidence) ->
                                    "$label ${formatConfidence(confidence)}"
                                }
                        }

                MatchType.LABEL ->
                    labelRows
                        .take(7)
                        .joinToString(", ") { (label, confidence) ->
                            "$label ${formatConfidence(confidence)}"
                        }
            }

        val timestampMs =
            entry.optLong("taken_at_ms", 0L)
                .takeIf { it > 0L }
                ?: entry.optLong("modified_at_ms", 0L)

        return Hit(
            displayName = entry.optString("display_name").ifBlank { "Фото" },
            relativePath = entry.optString("relative_path"),
            mimeType = entry.optString("mime_type"),
            sizeBytes = entry.optLong("size_bytes", 0L),
            timestampMs = timestampMs,
            uri = entry.optString("uri"),
            snippet = snippet.take(MAX_SNIPPET_CHARS),
            score =
                when (matchType) {
                    MatchType.OCR_AND_LABEL -> 420 + ocrScore + labelScore
                    MatchType.OCR -> 360 + ocrScore
                    MatchType.LABEL -> 260 + labelScore
                },
            matchType = matchType,
            labels = labelRows.map { it.first }.take(MAX_RETURNED_LABELS)
        )
    }

    private fun lexicalScore(
        query: String,
        haystack: String
    ): Int {
        if (query.isBlank() || haystack.isBlank()) {
            return 0
        }

        val tokens =
            query.split(" ")
                .filter { it.length >= 2 }
                .distinct()

        if (tokens.isEmpty()) {
            return 0
        }

        if (tokens.size > 1 && haystack.contains(query)) {
            return 170 + query.length.coerceAtMost(80)
        }

        val haystackTokens =
            lexicalTokens(
                haystack
            )

        val matched =
            tokens.count { token ->
                tokenMatchesLexically(
                    queryToken = token,
                    haystackTokens = haystackTokens
                )
            }

        if (matched == 0) {
            return 0
        }

        if (tokens.size > 1 && matched < 2) {
            return 0
        }

        return 60 + 120 * matched / tokens.size
    }

    private fun labelMatchScore(
        expandedQueryTokens: Set<String>,
        normalizedLabels: String,
        labels: List<Pair<String, Float>>
    ): Int {
        if (
            expandedQueryTokens.isEmpty() ||
            normalizedLabels.isBlank()
        ) {
            return 0
        }

        val normalizedLabelTokens =
            lexicalTokens(
                normalizedLabels
            )

        val matchedTokens =
            expandedQueryTokens
                .filter { token ->
                    token.length >= 2 &&
                        tokenMatchesLexically(
                            queryToken = token,
                            haystackTokens = normalizedLabelTokens
                        )
                }

        if (matchedTokens.isEmpty()) {
            return 0
        }

        val maxConfidence =
            labels
                .filter { (label, _) ->
                    val labelTokens =
                        lexicalTokens(
                            normalize(label)
                        )
                    matchedTokens.any { token ->
                        tokenMatchesLexically(
                            queryToken = token,
                            haystackTokens = labelTokens
                        )
                    }
                }
                .maxOfOrNull { it.second }
                ?: 0f

        return 90 +
            (matchedTokens.size.coerceAtMost(4) * 25) +
            (maxConfidence * 60f).toInt()
    }

    private fun lexicalTokens(
        value: String
    ): Set<String> =
        Regex("[a-zа-я0-9]+")
            .findAll(
                normalize(value)
            )
            .map { match ->
                match.value
            }
            .filter { token ->
                token.isNotBlank()
            }
            .toSet()

    private fun tokenMatchesLexically(
        queryToken: String,
        haystackTokens: Set<String>
    ): Boolean {
        if (queryToken.isBlank() || haystackTokens.isEmpty()) {
            return false
        }

        if (queryToken.length <= 2) {
            // Short tokens such as "AI" must be real OCR/label words.
            // Substring matching here caused false hits such as "MainActivity" -> "ai".
            return queryToken in haystackTokens
        }

        if (queryToken in haystackTokens) {
            return true
        }

        if (queryToken.length < 4) {
            return false
        }

        return haystackTokens.any { candidate ->
            candidate.length >= 4 &&
                (
                    candidate.startsWith(queryToken) ||
                        queryToken.startsWith(candidate)
                    )
        }
    }

    private fun expandedQueryTokens(
        query: String
    ): Set<String> {
        val base =
            normalize(query)
                .split(" ")
                .filter { it.length >= 2 }
                .toMutableSet()

        val snapshot = base.toList()
        snapshot.forEach { token ->
            VISUAL_QUERY_ALIASES[token]
                ?.forEach { alias ->
                    base += normalize(alias)
                }
        }

        return base
    }

    private fun contextualSnippet(
        text: String,
        query: String
    ): String {
        val compact =
            text
                .replace(Regex("\\s+"), " ")
                .trim()

        if (compact.isBlank()) {
            return ""
        }

        val lower = compact.lowercase(Locale.ROOT).replace('ё', 'е')
        val queryTokens =
            query.split(" ")
                .filter { it.length >= 2 }

        val index =
            queryTokens
                .asSequence()
                .map { lower.indexOf(it) }
                .filter { it >= 0 }
                .minOrNull()
                ?: 0

        val start = (index - 100).coerceAtLeast(0)
        val end = (index + 260).coerceAtMost(compact.length)

        return compact
            .substring(start, end)
            .trim()
    }

    private fun fingerprint(
        descriptor: Descriptor
    ): String =
        listOf(
            descriptor.uri,
            descriptor.modifiedAtMs.toString(),
            descriptor.sizeBytes.toString(),
            descriptor.width.toString(),
            descriptor.height.toString()
        ).joinToString("|")

    private fun baseEntry(
        descriptor: Descriptor,
        fingerprint: String
    ): JSONObject =
        JSONObject()
            .put("key", descriptor.key)
            .put("fingerprint", fingerprint)
            .put("uri", descriptor.uri)
            .put("display_name", descriptor.displayName)
            .put("relative_path", descriptor.relativePath)
            .put("mime_type", descriptor.mimeType)
            .put("size_bytes", descriptor.sizeBytes)
            .put("modified_at_ms", descriptor.modifiedAtMs)
            .put("taken_at_ms", descriptor.takenAtMs)
            .put("width", descriptor.width)
            .put("height", descriptor.height)

    private fun shouldRetryFailed(
        entry: JSONObject
    ): Boolean {
        val failedAt = entry.optLong("failed_at", 0L)
        if (failedAt <= 0L) {
            return true
        }
        return System.currentTimeMillis() - failedAt >= FAILED_RETRY_AFTER_MS
    }

    private fun loadIndexUnsafe(): LinkedHashMap<String, JSONObject> {
        if (!indexFile.isFile) {
            return linkedMapOf()
        }

        val root =
            try {
                JSONObject(indexFile.readText(Charsets.UTF_8))
            } catch (_: Exception) {
                return linkedMapOf()
            }

        if (root.optInt("version", -1) != INDEX_VERSION) {
            return linkedMapOf()
        }

        val rows =
            root.optJSONArray("items")
                ?: return linkedMapOf()

        val result = linkedMapOf<String, JSONObject>()
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val key = row.optString("key").trim()
            if (key.isNotBlank()) {
                result[key] = row
            }
        }
        return result
    }

    private fun saveIndexUnsafe(
        items: Map<String, JSONObject>
    ) {
        indexFile.parentFile?.mkdirs()

        val array = JSONArray()
        items.values
            .take(MAX_INDEX_ITEMS)
            .forEach(array::put)

        val root =
            JSONObject()
                .put("version", INDEX_VERSION)
                .put("updated_at", System.currentTimeMillis())
                .put("items", array)

        val temp =
            File(
                indexFile.parentFile,
                indexFile.name + ".tmp"
            )

        temp.writeText(
            root.toString(),
            Charsets.UTF_8
        )

        if (!temp.renameTo(indexFile)) {
            indexFile.writeText(
                root.toString(),
                Charsets.UTF_8
            )
            temp.delete()
        }
    }

    private fun compactIndexText(
        value: String
    ): String =
        value
            .replace('\u0000', ' ')
            .replace(Regex("[\\t\\r]+"), " ")
            .replace(Regex(" +"), " ")
            .trim()
            .take(MAX_OCR_TEXT_CHARS)

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9+#._\\-\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun formatConfidence(
        confidence: Float
    ): String =
        "${(confidence.coerceIn(0f, 1f) * 100f).toInt()}%"

    private fun emptyResult(
        detail: String,
        budget: Int
    ): SearchResult =
        SearchResult(
            hits = emptyList(),
            providerRowsScanned = 0,
            candidateImages = 0,
            indexedImages = 0,
            reusedImages = 0,
            updatedImages = 0,
            failedImages = 0,
            pendingImages = 0,
            ocrIndexedImages = 0,
            labeledImages = 0,
            newIndexBudget = budget,
            detail = detail
        )

    private fun <T> awaitTask(
        task: Task<T>,
        timeoutMs: Long
    ): T? {
        val latch = CountDownLatch(1)
        var value: T? = null

        task
            .addOnSuccessListener {
                value = it
                latch.countDown()
            }
            .addOnFailureListener {
                latch.countDown()
            }
            .addOnCanceledListener {
                latch.countDown()
            }

        return try {
            if (
                latch.await(
                    timeoutMs,
                    TimeUnit.MILLISECONDS
                )
            ) {
                value
            } else {
                null
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    companion object {
        private const val INDEX_FILE_NAME =
            "ayana_image_content_index_v1.json"

        private const val INDEX_VERSION = 1

        private const val STATUS_INDEXED = "indexed"
        private const val STATUS_FAILED = "failed"

        private const val DEFAULT_LIMIT = 8
        const val DEFAULT_NEW_IMAGE_BUDGET = 32
        const val BROAD_SEARCH_NEW_IMAGE_BUDGET = 8
        private const val MAX_NEW_IMAGE_BUDGET = 64
        private const val MAX_PROVIDER_ROWS_SCAN = 1400
        private const val MAX_INDEX_ITEMS = 1600
        private const val MAX_RETURNED_HITS = 20
        private const val MAX_RETURNED_LABELS = 10
        private const val MAX_STORED_LABELS = 18
        private const val MAX_OCR_TEXT_CHARS = 7000
        private const val MAX_SNIPPET_CHARS = 360
        private const val MAX_DETAIL_CHARS = 900
        private const val MAX_SOURCE_IMAGE_BYTES = 40L * 1024L * 1024L
        private const val PER_ANALYZER_TIMEOUT_MS = 4500L
        private const val FAILED_RETRY_AFTER_MS = 24L * 60L * 60L * 1000L
        private const val MIN_LABEL_CONFIDENCE = 0.62f

        /**
         * Query-side bilingual aliases for the most common user-visible categories.
         * ML Kit's default image label model returns generic labels (typically English),
         * while AYANA's user commands are usually Russian. This dictionary expands only
         * the query; the actual stored evidence remains the ML Kit label text/confidence.
         */
        private val VISUAL_QUERY_ALIASES =
            mapOf(
                "человек" to setOf("person", "people", "human", "portrait", "face"),
                "люди" to setOf("person", "people", "human", "group"),
                "лицо" to setOf("face", "person", "portrait"),
                "портрет" to setOf("portrait", "face", "person"),
                "кот" to setOf("cat", "kitten"),
                "кошка" to setOf("cat", "kitten"),
                "собака" to setOf("dog", "puppy"),
                "пес" to setOf("dog", "puppy"),
                "машина" to setOf("car", "vehicle", "automobile"),
                "автомобиль" to setOf("car", "vehicle", "automobile"),
                "транспорт" to setOf("vehicle", "car", "transport"),
                "дорога" to setOf("road", "street", "highway"),
                "улица" to setOf("street", "road", "city"),
                "дом" to setOf("house", "home", "building"),
                "здание" to setOf("building", "architecture"),
                "город" to setOf("city", "urban", "building"),
                "небо" to setOf("sky", "cloud"),
                "облако" to setOf("cloud", "sky"),
                "вода" to setOf("water", "river", "lake", "sea", "ocean"),
                "река" to setOf("river", "water"),
                "озеро" to setOf("lake", "water"),
                "море" to setOf("sea", "ocean", "water"),
                "гора" to setOf("mountain", "landscape"),
                "горы" to setOf("mountain", "landscape"),
                "дерево" to setOf("tree", "plant", "forest"),
                "лес" to setOf("forest", "tree", "nature"),
                "цветок" to setOf("flower", "plant"),
                "природа" to setOf("nature", "landscape", "outdoor"),
                "еда" to setOf("food", "meal", "dish"),
                "напиток" to setOf("drink", "beverage"),
                "кофе" to setOf("coffee", "drink", "beverage"),
                "телефон" to setOf("mobile phone", "phone", "smartphone"),
                "планшет" to setOf("tablet", "computer", "screen"),
                "ноутбук" to setOf("laptop", "computer"),
                "компьютер" to setOf("computer", "laptop", "desktop"),
                "экран" to setOf("screen", "display", "monitor"),
                "скриншот" to setOf("screenshot", "screen", "display"),
                "документ" to setOf("document", "paper", "text"),
                "бумага" to setOf("paper", "document"),
                "книга" to setOf("book", "text", "document"),
                "текст" to setOf("text", "document", "writing"),
                "фото" to setOf("photo", "photograph", "image"),
                "фотография" to setOf("photo", "photograph", "image")
            )
    }
}
