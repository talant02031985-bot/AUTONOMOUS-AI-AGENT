package kg.autonomous.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * AYANA Multimodal Attachment Manager v1.3 — VIDEO AUDIO + MULTI-ATTACHMENT COMPATIBILITY.
 *
 * Security / reliability contract:
 * - never exposes arbitrary user filesystem paths to the service/Worker;
 * - immediately stages content into AYANA's private cache;
 * - normalizes images/video frames to bounded JPEGs;
 * - allow-lists document extensions and enforces byte limits;
 * - video v1.3 keeps bounded sampled frames and also stages a bounded lossless audio-track remux when available;
 * - audio extraction never records from the microphone and never exposes an arbitrary filesystem path;
 * - manifests contain only cache paths owned by AYANA and are revalidated by VoiceService.
 * - one picker action may stage a bounded batch of attachments without weakening per-item limits.
 */
class AyanaMultimodalAttachmentManager(
    context: Context
) {

    data class PreparedAttachment(
        val kind: String,
        val displayName: String,
        val mimeType: String,
        val manifest: JSONObject
    )

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val cacheRoot = File(appContext.cacheDir, CACHE_DIR_NAME)

    init {
        ensureCacheRoot()
        cleanupOldFiles()
    }

    fun prepare(uri: Uri): PreparedAttachment {
        val metadata = readMetadata(uri)
        val mime = metadata.mimeType.lowercase(Locale.ROOT)
        val extension = extensionOf(metadata.displayName)

        return when {
            mime.startsWith("image/") -> prepareImage(uri, metadata)
            mime.startsWith("video/") -> prepareVideo(uri, metadata)
            isSupportedDocument(mime, extension) -> prepareDocument(uri, metadata, extension)
            else -> throw IllegalArgumentException(
                "Этот тип файла пока не поддерживается AYANA: ${metadata.displayName}"
            )
        }
    }

    /**
     * R10.27.4.1 compatibility contract inherited from R8.0 multi-attachment:
     * one bounded picker selection is staged either as the original single
     * attachment or as one batch manifest. The batch is all-or-nothing.
     */
    fun prepareBatch(uris: List<Uri>): PreparedAttachment {
        val uniqueUris =
            uris
                .distinctBy { it.toString() }
                .take(MAX_BATCH_ITEMS + 1)

        if (uniqueUris.isEmpty()) {
            throw IllegalArgumentException("Файлы не выбраны.")
        }
        if (uniqueUris.size > MAX_BATCH_ITEMS) {
            throw IllegalArgumentException(
                "За один раз можно выбрать не более $MAX_BATCH_ITEMS файлов."
            )
        }
        if (uniqueUris.size == 1) {
            return prepare(uniqueUris.first())
        }

        val prepared = mutableListOf<PreparedAttachment>()
        try {
            var totalStagedBytes = 0L
            var totalVideoFrames = 0

            uniqueUris.forEach { uri ->
                val item = prepare(uri)
                prepared += item
                totalStagedBytes += stagedBytes(item.manifest)
                totalVideoFrames += stagedFrameCount(item.manifest)

                if (totalStagedBytes > MAX_BATCH_STAGED_BYTES) {
                    throw IllegalArgumentException(
                        "Общий объём подготовленных вложений слишком большой. Максимум 8 МБ за одну отправку."
                    )
                }
                if (totalVideoFrames > MAX_BATCH_VIDEO_FRAMES) {
                    throw IllegalArgumentException(
                        "В одном пакете слишком много видеокадров. Уменьшите количество выбранных видео."
                    )
                }
            }

            val items = JSONArray()
            prepared.forEach { item ->
                items.put(JSONObject(item.manifest.toString()))
            }

            val manifest =
                JSONObject()
                    .put("version", MANIFEST_VERSION)
                    .put("kind", KIND_BATCH)
                    .put("display_name", "Вложения: ${prepared.size}")
                    .put("mime_type", BATCH_MIME_TYPE)
                    .put("item_count", prepared.size)
                    .put("total_staged_bytes", totalStagedBytes)
                    .put("total_video_frames", totalVideoFrames)
                    .put("items", items)

            return PreparedAttachment(
                kind = KIND_BATCH,
                displayName = "Вложения: ${prepared.size}",
                mimeType = BATCH_MIME_TYPE,
                manifest = manifest
            )
        } catch (error: Exception) {
            prepared.forEach { cleanupPrepared(it.manifest) }
            throw error
        }
    }

    fun cleanupPrepared(manifest: JSONObject?) {
        if (manifest == null) return
        try {
            when (manifest.optString("kind")) {
                KIND_IMAGE, KIND_DOCUMENT -> {
                    deleteOwnedPath(manifest.optString("path"))
                }
                KIND_VIDEO_VISUAL -> {
                    val frames = manifest.optJSONArray("frames") ?: JSONArray()
                    for (i in 0 until frames.length()) {
                        deleteOwnedPath(frames.optJSONObject(i)?.optString("path").orEmpty())
                    }
                    deleteOwnedPath(
                        manifest.optJSONObject("audio")?.optString("path").orEmpty()
                    )
                }
                KIND_BATCH -> {
                    val items = manifest.optJSONArray("items") ?: JSONArray()
                    for (i in 0 until items.length()) {
                        cleanupPrepared(items.optJSONObject(i))
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    fun cleanupOldFiles(now: Long = System.currentTimeMillis()) {
        ensureCacheRoot()
        cacheRoot.listFiles()?.forEach { file ->
            try {
                if (file.isFile && now - file.lastModified() > CACHE_TTL_MS) {
                    file.delete()
                }
            } catch (_: Exception) {
            }
        }
    }

    private data class Metadata(
        val displayName: String,
        val sizeBytes: Long,
        val mimeType: String
    )

    private fun readMetadata(uri: Uri): Metadata {
        var name = "attachment"
        var size = -1L

        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) {
                        name = cursor.getString(nameIndex).orEmpty().trim().ifBlank { "attachment" }
                    }
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (_: Exception) {
        }

        name = name
            .replace(Regex("[\\r\\n\\t]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_DISPLAY_NAME_CHARS)
            .ifBlank { "attachment" }

        val rawMime = resolver.getType(uri)
            ?.trim()
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        val derivedMime = mimeFromExtension(extensionOf(name))
        val mime = if (
            rawMime.isBlank() ||
            rawMime == "application/octet-stream"
        ) {
            derivedMime
        } else {
            rawMime
        }

        return Metadata(name, size, mime)
    }

    private fun prepareImage(uri: Uri, metadata: Metadata): PreparedAttachment {
        if (metadata.sizeBytes > MAX_SOURCE_IMAGE_BYTES) {
            throw IllegalArgumentException("Изображение слишком большое. Максимум 30 МБ.")
        }

        val bitmap = resolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input)
        } ?: throw IllegalArgumentException("Не удалось прочитать изображение.")

        val normalized = scaleBitmap(bitmap, MAX_IMAGE_DIMENSION)
        val target = newCacheFile("image", ".jpg")

        try {
            FileOutputStream(target).use { output ->
                if (!normalized.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, output)) {
                    throw IllegalStateException("Не удалось подготовить изображение.")
                }
            }
        } finally {
            if (normalized !== bitmap) normalized.recycle()
            bitmap.recycle()
        }

        if (target.length() <= 0L || target.length() > MAX_STAGED_FILE_BYTES) {
            target.delete()
            throw IllegalArgumentException("Изображение не удалось безопасно подготовить для анализа.")
        }

        val manifest = JSONObject()
            .put("version", MANIFEST_VERSION)
            .put("kind", KIND_IMAGE)
            .put("display_name", metadata.displayName)
            .put("mime_type", "image/jpeg")
            .put("source_mime_type", metadata.mimeType)
            .put("source_size_bytes", metadata.sizeBytes)
            .put("size_bytes", target.length())
            .put("path", target.absolutePath)

        return PreparedAttachment(KIND_IMAGE, metadata.displayName, "image/jpeg", manifest)
    }

    private fun prepareDocument(
        uri: Uri,
        metadata: Metadata,
        extension: String
    ): PreparedAttachment {
        if (metadata.sizeBytes > MAX_STAGED_FILE_BYTES) {
            throw IllegalArgumentException("Файл слишком большой. Для анализа через AYANA максимум 8 МБ.")
        }

        val safeExtension = if (extension.isBlank()) ".bin" else ".${extension.take(12)}"
        val target = newCacheFile("document", safeExtension)
        val copied = copyUriWithLimit(uri, target, MAX_STAGED_FILE_BYTES)

        if (copied <= 0L) {
            target.delete()
            throw IllegalArgumentException("Файл пустой или его не удалось прочитать.")
        }

        val resolvedMime = metadata.mimeType.ifBlank { mimeFromExtension(extension) }
        val manifest = JSONObject()
            .put("version", MANIFEST_VERSION)
            .put("kind", KIND_DOCUMENT)
            .put("display_name", metadata.displayName)
            .put("mime_type", resolvedMime)
            .put("size_bytes", copied)
            .put("path", target.absolutePath)

        return PreparedAttachment(KIND_DOCUMENT, metadata.displayName, resolvedMime, manifest)
    }

    private fun prepareVideo(uri: Uri, metadata: Metadata): PreparedAttachment {
        if (metadata.sizeBytes > MAX_SOURCE_VIDEO_BYTES) {
            throw IllegalArgumentException("Видео слишком большое. Максимум 500 МБ.")
        }

        val retriever = MediaMetadataRetriever()
        val frameFiles = mutableListOf<File>()
        var audioFile: File? = null
        try {
            retriever.setDataSource(appContext, uri)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L

            if (durationMs <= 0L) {
                throw IllegalArgumentException("Не удалось определить длительность видео.")
            }
            if (durationMs > MAX_VIDEO_DURATION_MS) {
                throw IllegalArgumentException("Для анализа видео пока поддерживается длительность до 30 минут.")
            }

            val desiredFrames = if (durationMs <= 60_000L) 6 else 8
            val frames = JSONArray()
            var totalBytes = 0L

            for (index in 0 until desiredFrames) {
                val fraction = (index + 1).toDouble() / (desiredFrames + 1).toDouble()
                val timestampMs = (durationMs * fraction).roundToInt().toLong()
                val frame = try {
                    retriever.getFrameAtTime(
                        timestampMs * 1000L,
                        MediaMetadataRetriever.OPTION_CLOSEST
                    )
                } catch (_: Exception) {
                    null
                } ?: continue

                val normalized = scaleBitmap(frame, MAX_VIDEO_FRAME_DIMENSION)
                val target = newCacheFile("video_frame", ".jpg")
                try {
                    FileOutputStream(target).use { output ->
                        normalized.compress(Bitmap.CompressFormat.JPEG, VIDEO_JPEG_QUALITY, output)
                    }
                } finally {
                    if (normalized !== frame) normalized.recycle()
                    frame.recycle()
                }

                val bytes = target.length()
                if (bytes <= 0L || totalBytes + bytes > MAX_VIDEO_FRAME_TOTAL_BYTES) {
                    target.delete()
                    break
                }

                totalBytes += bytes
                frameFiles.add(target)
                frames.put(
                    JSONObject()
                        .put("path", target.absolutePath)
                        .put("timestamp_ms", timestampMs)
                        .put("mime_type", "image/jpeg")
                        .put("size_bytes", bytes)
                )
            }

            if (frames.length() < MIN_VIDEO_FRAMES) {
                frameFiles.forEach { it.delete() }
                throw IllegalArgumentException("Не удалось извлечь достаточно кадров из этого видео.")
            }

            val audio =
                try {
                    extractAudioTrack(uri)
                } catch (error: Exception) {
                    AudioStageResult(
                        available = false,
                        file = null,
                        mimeType = "",
                        sizeBytes = 0L,
                        sourceMimeType = "",
                        reason = error.message.orEmpty().take(180).ifBlank { "audio_extract_failed" }
                    )
                }

            audioFile = audio.file

            val manifest = JSONObject()
                .put("version", MANIFEST_VERSION)
                .put("kind", KIND_VIDEO_VISUAL)
                .put("display_name", metadata.displayName)
                .put("mime_type", metadata.mimeType.ifBlank { "video/*" })
                .put("source_size_bytes", metadata.sizeBytes)
                .put("duration_ms", durationMs)
                .put(
                    "analysis_scope",
                    if (audio.available) {
                        "visual_sampled_frames_plus_audio_transcription"
                    } else {
                        "visual_sampled_frames_only"
                    }
                )
                .put("audio_analysis", audio.available)
                .put("audio_status", if (audio.available) "staged" else audio.reason.ifBlank { "unavailable" })
                .put("frames", frames)

            if (audio.available && audio.file != null) {
                manifest.put(
                    "audio",
                    JSONObject()
                        .put("path", audio.file.absolutePath)
                        .put("mime_type", audio.mimeType)
                        .put("source_mime_type", audio.sourceMimeType)
                        .put("size_bytes", audio.sizeBytes)
                        .put("container", "mpeg4_audio_track_remux")
                )
            }

            return PreparedAttachment(
                KIND_VIDEO_VISUAL,
                metadata.displayName,
                metadata.mimeType.ifBlank { "video/*" },
                manifest
            )
        } catch (error: Exception) {
            frameFiles.forEach { it.delete() }
            audioFile?.delete()
            throw error
        } finally {
            try { retriever.release() } catch (_: Exception) { }
        }
    }

    private data class AudioStageResult(
        val available: Boolean,
        val file: File?,
        val mimeType: String,
        val sizeBytes: Long,
        val sourceMimeType: String,
        val reason: String
    )

    private fun extractAudioTrack(uri: Uri): AudioStageResult {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var target: File? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(appContext, uri, null)

            var audioTrackIndex = -1
            var sourceMime = ""
            var audioFormat: MediaFormat? = null

            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = index
                    sourceMime = mime
                    audioFormat = format
                    break
                }
            }

            if (audioTrackIndex < 0 || audioFormat == null) {
                return AudioStageResult(
                    available = false,
                    file = null,
                    mimeType = "",
                    sizeBytes = 0L,
                    sourceMimeType = "",
                    reason = "no_audio_track"
                )
            }

            target = newCacheFile("video_audio", ".m4a")
            muxer = MediaMuxer(
                target.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            )

            val outputTrack = muxer.addTrack(audioFormat)
            muxer.start()
            muxerStarted = true
            extractor.selectTrack(audioTrackIndex)

            val declaredMax =
                if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                } else {
                    AUDIO_EXTRACTOR_DEFAULT_BUFFER_BYTES
                }
            val bufferSize =
                declaredMax
                    .coerceAtLeast(AUDIO_EXTRACTOR_DEFAULT_BUFFER_BYTES)
                    .coerceAtMost(AUDIO_EXTRACTOR_MAX_BUFFER_BYTES)
            val buffer = ByteBuffer.allocateDirect(bufferSize)
            val info = MediaCodec.BufferInfo()
            var payloadBytes = 0L

            while (true) {
                buffer.clear()
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                if (sampleSize > buffer.capacity()) {
                    throw IllegalArgumentException("audio_sample_too_large")
                }

                payloadBytes += sampleSize.toLong()
                if (payloadBytes > MAX_VIDEO_AUDIO_BYTES) {
                    throw IllegalArgumentException("audio_track_too_large")
                }

                info.offset = 0
                info.size = sampleSize
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                if (!extractor.advance()) break
            }

            muxer.stop()
            muxerStarted = false
            muxer.release()
            muxer = null

            val size = target.length()
            if (payloadBytes <= 0L || size <= 0L || size > MAX_VIDEO_AUDIO_BYTES) {
                target.delete()
                return AudioStageResult(
                    available = false,
                    file = null,
                    mimeType = "",
                    sizeBytes = 0L,
                    sourceMimeType = sourceMime,
                    reason = "audio_extract_empty_or_oversized"
                )
            }

            return AudioStageResult(
                available = true,
                file = target,
                mimeType = "audio/mp4",
                sizeBytes = size,
                sourceMimeType = sourceMime,
                reason = "staged"
            )
        } catch (error: Exception) {
            try { if (muxerStarted) muxer?.stop() } catch (_: Exception) { }
            try { muxer?.release() } catch (_: Exception) { }
            target?.delete()
            return AudioStageResult(
                available = false,
                file = null,
                mimeType = "",
                sizeBytes = 0L,
                sourceMimeType = "",
                reason = error.message.orEmpty().take(180).ifBlank { "audio_extract_failed" }
            )
        } finally {
            try { extractor.release() } catch (_: Exception) { }
        }
    }

    private fun stagedFrameCount(manifest: JSONObject): Int {
        return when (manifest.optString("kind")) {
            KIND_VIDEO_VISUAL ->
                manifest.optJSONArray("frames")?.length() ?: 0

            KIND_BATCH -> {
                val items = manifest.optJSONArray("items") ?: JSONArray()
                var total = 0
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let { total += stagedFrameCount(it) }
                }
                total
            }

            else -> 0
        }
    }

    private fun stagedBytes(manifest: JSONObject): Long {
        return when (manifest.optString("kind")) {
            KIND_IMAGE, KIND_DOCUMENT ->
                manifest.optLong("size_bytes", 0L).coerceAtLeast(0L)

            KIND_VIDEO_VISUAL -> {
                val frames = manifest.optJSONArray("frames") ?: JSONArray()
                var total = 0L
                for (i in 0 until frames.length()) {
                    total += frames
                        .optJSONObject(i)
                        ?.optLong("size_bytes", 0L)
                        ?.coerceAtLeast(0L)
                        ?: 0L
                }

                // R10.27.4.1: batch accounting includes a staged audio track too.
                total += manifest
                    .optJSONObject("audio")
                    ?.optLong("size_bytes", 0L)
                    ?.coerceAtLeast(0L)
                    ?: 0L

                total
            }

            KIND_BATCH -> {
                val items = manifest.optJSONArray("items") ?: JSONArray()
                var total = 0L
                for (i in 0 until items.length()) {
                    items.optJSONObject(i)?.let { total += stagedBytes(it) }
                }
                total
            }

            else -> 0L
        }
    }

    private fun copyUriWithLimit(uri: Uri, target: File, maxBytes: Long): Long {
        var total = 0L
        resolver.openInputStream(uri)?.use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        throw IllegalArgumentException("Файл слишком большой. Для анализа через AYANA максимум 8 МБ.")
                    }
                    output.write(buffer, 0, read)
                }
            }
        } ?: throw IllegalArgumentException("Не удалось открыть выбранный файл.")
        return total
    }

    private fun scaleBitmap(source: Bitmap, maxDimension: Int): Bitmap {
        val largest = maxOf(source.width, source.height)
        if (largest <= maxDimension || largest <= 0) return source
        val scale = maxDimension.toFloat() / largest.toFloat()
        val width = maxOf(1, (source.width * scale).roundToInt())
        val height = maxOf(1, (source.height * scale).roundToInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun isSupportedDocument(mime: String, extension: String): Boolean {
        if (extension in SUPPORTED_DOCUMENT_EXTENSIONS) return true
        return mime in SUPPORTED_DOCUMENT_MIME_TYPES || mime.startsWith("text/")
    }

    private fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT).trim()

    private fun mimeFromExtension(extension: String): String =
        when (extension.lowercase(Locale.ROOT)) {
            "pdf" -> "application/pdf"
            "txt", "md", "log" -> "text/plain"
            "json" -> "application/json"
            "html", "htm" -> "text/html"
            "xml" -> "application/xml"
            "csv" -> "text/csv"
            "doc" -> "application/msword"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "rtf" -> "application/rtf"
            "odt" -> "application/vnd.oasis.opendocument.text"
            "ppt" -> "application/vnd.ms-powerpoint"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            "xls" -> "application/vnd.ms-excel"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "js" -> "application/javascript"
            "ts" -> "application/typescript"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "css" -> "text/css"
            "java" -> "text/x-java"
            "kt", "kts" -> "text/x-kotlin"
            "py" -> "text/x-python"
            "yaml", "yml" -> "text/x-yaml"
            else -> "text/plain"
        }

    private fun ensureCacheRoot() {
        if (!cacheRoot.exists()) cacheRoot.mkdirs()
    }

    private fun newCacheFile(prefix: String, suffix: String): File {
        ensureCacheRoot()
        return File(cacheRoot, "${prefix}_${UUID.randomUUID()}$suffix")
    }

    private fun deleteOwnedPath(path: String) {
        if (path.isBlank()) return
        try {
            val file = File(path).canonicalFile
            val root = cacheRoot.canonicalFile
            if (file.path.startsWith(root.path + File.separator) && file.isFile) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_DOCUMENT = "document"
        const val KIND_VIDEO_VISUAL = "video_visual"
        const val KIND_BATCH = "batch"
        const val BATCH_MIME_TYPE = "application/x-ayana-attachment-batch"

        /**
         * Decide whether a currently selected attachment belongs to this text command.
         *
         * The default is attachment-aware: once the user explicitly selected a file,
         * ordinary analytical questions should stay grounded in it. Clear deterministic
         * device/control commands are the exception and must continue through AYANA's
         * local execution router instead of being hijacked by the multimodal endpoint.
         * Explicit references to the attachment always win over device-verb heuristics.
         */
        fun shouldUseAttachmentForCommand(command: String): Boolean {
            val normalized = command
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("\\s+"), " ")
                .trim()

            if (normalized.isBlank()) return true

            val mediaTopic = listOf(
                "фото", "фотограф", "изображен", "картин",
                "видео", "ролик", "кадр",
                "файл", "документ", "pdf", "ворд", "word",
                "таблиц", "excel", "эксель", "вложен"
            ).any(normalized::contains)

            val capabilityQuestion = mediaTopic && listOf(
                "ты умеешь", "умеешь ", "умеешь ли", "можешь ", "можешь ли", "ты можешь",
                "способна ли", "поддерживаешь", "можно ли тебе",
                "что ты умеешь", "какие форматы"
            ).any(normalized::contains)

            if (capabilityQuestion) return false

            val explicitAttachmentReference = listOf(
                "это вложение", "этого вложения", "этом вложении",
                "этот файл", "этого файла", "этом файле",
                "этот документ", "этого документа", "этом документе",
                "эту фотограф", "этой фотограф", "на фотографии",
                "это фото", "этом фото", "на фото",
                "это изображение", "этом изображении", "на изображении",
                "это видео", "этого видео", "этом видео", "в видео",
                "этот ролик", "этом ролике", "в ролике",
                "эти кадры", "этих кадрах", "на кадрах"
            ).any(normalized::contains)

            if (explicitAttachmentReference) return true

            val attachmentAnalysisIntent = listOf(
                "проанализ", "опиши", "перескаж", "кратко перескаж",
                "сделай вывод", "выдели главное", "выдели основные",
                "назови три", "назови основные", "извлеки", "прочитай",
                "что изображено", "что видишь", "что на нем", "что на нём",
                "что происходит", "какие объекты", "какие надписи",
                "какие люди", "о чем он", "о чём он", "о чем документ",
                "суммариз", "резюм", "сравни содерж"
            ).any(normalized::contains)

            if (attachmentAnalysisIntent) return true

            val exactLocalControls = setOf(
                "домой", "на главный экран", "главный экран", "назад",
                "громче", "тише", "без звука", "включи звук", "верни звук",
                "стоп", "остановись", "останови команду"
            )
            if (normalized in exactLocalControls) return false

            val deterministicPrefixes = listOf(
                "открой ", "запусти ", "включи ", "сверни ", "закрой ",
                "информация о приложении ", "информацию о приложении ",
                "сведения о приложении ", "уведомления ", "уведомление ",
                "разрешения ", "батарея ", "хранилище ",
                "мобильные данные ", "язык приложения ",
                "открой настройки", "покажи настройки", "настройки ",
                "найди в google ", "найди в гугле ", "поищи в google ",
                "поищи в гугле ", "найди на карте ", "покажи на карте ",
                "напомни ", "создай напоминание ", "удали напоминание ",
                "отмени напоминание ", "покажи напоминания",
                "запомни ", "забудь ", "очисти память",
                "продолжи задачу", "продолжи цель", "отмени текущую задачу"
            )

            if (deterministicPrefixes.any(normalized::startsWith)) return false

            val calculatorLike =
                normalized.startsWith("сколько будет ") ||
                    Regex("^[0-9\\s+\\-*/×÷.,()]+$").matches(normalized)
            if (calculatorLike) return false

            // Selected attachment + an otherwise ordinary question => keep it
            // grounded in the attachment. This is what supports natural follow-ups
            // such as «Какие основные объекты появляются?» without repeating
            // «в этом видео» every turn.
            return true
        }

        private const val MANIFEST_VERSION = 2
        private const val CACHE_DIR_NAME = "ayana_multimodal"
        const val MAX_BATCH_ITEMS = 8
        private const val MAX_BATCH_STAGED_BYTES = 8L * 1024L * 1024L
        private const val MAX_BATCH_VIDEO_FRAMES = 24
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L
        private const val MAX_DISPLAY_NAME_CHARS = 160
        private const val MAX_SOURCE_IMAGE_BYTES = 30L * 1024L * 1024L
        private const val MAX_STAGED_FILE_BYTES = 8L * 1024L * 1024L
        private const val MAX_SOURCE_VIDEO_BYTES = 500L * 1024L * 1024L
        private const val MAX_VIDEO_DURATION_MS = 30L * 60L * 1000L
        private const val MAX_VIDEO_FRAME_TOTAL_BYTES = 6L * 1024L * 1024L
        private const val MAX_VIDEO_AUDIO_BYTES = 5L * 1024L * 1024L
        private const val AUDIO_EXTRACTOR_DEFAULT_BUFFER_BYTES = 256 * 1024
        private const val AUDIO_EXTRACTOR_MAX_BUFFER_BYTES = 2 * 1024 * 1024
        private const val MAX_IMAGE_DIMENSION = 1800
        private const val MAX_VIDEO_FRAME_DIMENSION = 1280
        private const val IMAGE_JPEG_QUALITY = 88
        private const val VIDEO_JPEG_QUALITY = 80
        private const val MIN_VIDEO_FRAMES = 2

        private val SUPPORTED_DOCUMENT_EXTENSIONS = setOf(
            "pdf", "txt", "md", "log", "json", "html", "htm", "xml", "csv",
            "doc", "docx", "rtf", "odt", "ppt", "pptx", "xls", "xlsx",
            "java", "kt", "kts", "js", "ts", "py", "css", "yaml", "yml"
        )

        private val SUPPORTED_DOCUMENT_MIME_TYPES = setOf(
            "application/pdf",
            "application/json",
            "application/xml",
            "application/msword",
            "application/rtf",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.ms-powerpoint",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        )
    }
}
