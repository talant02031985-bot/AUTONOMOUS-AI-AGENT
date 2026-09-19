package kg.autonomous.agent

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

/**
 * AYANA Device Content Search Engine v1.0 — MediaStore metadata truth.
 *
 * Purpose:
 * - search photos that Android currently exposes to AYANA;
 * - search non-media/shared-file metadata exposed by MediaStore/Downloads;
 * - never claim full-storage coverage when Android scoped storage or a partial
 *   photo grant limits visibility;
 * - never send filenames, paths or search results to Worker/Agent Core.
 *
 * v1 searches METADATA only (name/path/MIME). It does not inspect document text,
 * image pixels, faces or semantic visual content.
 */
class AyanaDeviceContentSearchEngine(
    context: Context
) {

    enum class AccessScope(
        val wireName: String
    ) {
        FULL("full"),
        PARTIAL("partial"),
        PROVIDER_VISIBLE("provider_visible"),
        NONE("none")
    }

    enum class Kind(
        val wireName: String
    ) {
        PHOTO("photo"),
        FILE("file")
    }

    data class Hit(
        val kind: Kind,
        val displayName: String,
        val relativePath: String,
        val mimeType: String,
        val sizeBytes: Long,
        val timestampMs: Long,
        val uri: String,
        val score: Int,
        val metadata: String = ""
    )

    data class Result(
        val hits: List<Hit>,
        val scanned: Int,
        val scope: AccessScope,
        val detail: String
    )

    private val appContext =
        context.applicationContext

    private val resolver =
        appContext.contentResolver

    fun searchPhotos(
        query: String,
        limit: Int = DEFAULT_LIMIT
    ): Result {
        val access =
            currentPhotoAccessScope()

        if (access == AccessScope.NONE) {
            return Result(
                hits = emptyList(),
                scanned = 0,
                scope = AccessScope.NONE,
                detail =
                    "Нет разрешения на чтение фотографий. " +
                        "AYANA не утверждает, что проверила галерею."
            )
        }

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

        val hits =
            mutableListOf<Hit>()
        var scanned = 0

        try {
            resolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndex(MediaStore.Images.Media._ID)
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
                    scanned < MAX_PHOTO_SCAN
                ) {
                    scanned++

                    val id = cursor.longOrZero(idIndex)
                    if (id <= 0L) {
                        continue
                    }

                    val displayName = cursor.stringOrEmpty(nameIndex)
                    val relativePath = cursor.stringOrEmpty(pathIndex)
                    val mimeType = cursor.stringOrEmpty(mimeIndex)
                    val sizeBytes = cursor.longOrZero(sizeIndex)
                    val modifiedAt = cursor.longOrZero(modifiedIndex) * 1000L
                    val takenAt = cursor.longOrZero(takenIndex)
                    val width = cursor.longOrZero(widthIndex)
                    val height = cursor.longOrZero(heightIndex)

                    val searchable =
                        listOf(
                            displayName,
                            relativePath,
                            mimeType
                        )
                            .joinToString(" ")

                    val lexical = lexicalScore(query, searchable)
                    if (lexical <= 0) {
                        continue
                    }

                    val uri =
                        ContentUris.withAppendedId(
                            collection,
                            id
                        )

                    hits +=
                        Hit(
                            kind = Kind.PHOTO,
                            displayName =
                                displayName.ifBlank {
                                    "Фото $id"
                                },
                            relativePath = relativePath,
                            mimeType = mimeType,
                            sizeBytes = sizeBytes,
                            timestampMs =
                                if (takenAt > 0L) takenAt else modifiedAt,
                            uri = uri.toString(),
                            score = lexical,
                            metadata =
                                "scope=${access.wireName}; width=$width; height=$height"
                        )
                }
            }
        } catch (error: SecurityException) {
            return Result(
                hits = emptyList(),
                scanned = scanned,
                scope = AccessScope.NONE,
                detail =
                    "Android запретил чтение MediaStore.Images: " +
                        compactError(error)
            )
        } catch (error: Exception) {
            return Result(
                hits = emptyList(),
                scanned = scanned,
                scope = access,
                detail =
                    "Ошибка чтения MediaStore.Images: " +
                        compactError(error)
            )
        }

        return Result(
            hits = hits
                .sortedWith(
                    compareByDescending<Hit> { it.score }
                        .thenByDescending { it.timestampMs }
                )
                .take(limit.coerceIn(1, MAX_RETURNED_HITS)),
            scanned = scanned,
            scope = access,
            detail =
                when (access) {
                    AccessScope.FULL ->
                        "Фото: полный MediaStore-доступ, поиск только по метаданным имени/пути/MIME."
                    AccessScope.PARTIAL ->
                        "Фото: Android дал доступ только к выбранным пользователем изображениям; поиск не охватывает всю галерею."
                    else ->
                        "Фото: доступ ограничен Android."
                }
        )
    }

    fun searchFiles(
        query: String,
        limit: Int = DEFAULT_LIMIT
    ): Result {
        val aggregate =
            linkedMapOf<String, Hit>()
        var scanned = 0
        val notes =
            mutableListOf<String>()
        var successfulCollections = 0

        fun merge(
            label: String,
            collection: Uri
        ) {
            try {
                val part =
                    queryFileCollection(
                        query = query,
                        collection = collection,
                        maxScan =
                            (MAX_FILE_SCAN - scanned)
                                .coerceAtLeast(0)
                    )
                scanned += part.scanned
                successfulCollections++
                notes += "$label=ok"

                part.hits.forEach { hit ->
                    val key =
                        listOf(
                            hit.displayName,
                            hit.relativePath,
                            hit.sizeBytes.toString()
                        )
                            .joinToString("|")
                            .lowercase(Locale.ROOT)
                    val old = aggregate[key]
                    if (old == null || hit.score > old.score) {
                        aggregate[key] = hit
                    }
                }
            } catch (error: Exception) {
                notes += "$label=${error.javaClass.simpleName}"
            }
        }

        if (Build.VERSION.SDK_INT >= 29) {
            merge(
                "downloads",
                MediaStore.Downloads.getContentUri(
                    MediaStore.VOLUME_EXTERNAL
                )
            )
        }

        if (scanned < MAX_FILE_SCAN) {
            val filesCollection =
                if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Files.getContentUri(
                        MediaStore.VOLUME_EXTERNAL
                    )
                } else {
                    MediaStore.Files.getContentUri(
                        "external"
                    )
                }
            merge("files", filesCollection)
        }

        if (successfulCollections == 0) {
            return Result(
                hits = emptyList(),
                scanned = scanned,
                scope = AccessScope.NONE,
                detail =
                    "MediaStore не дал доступ к индексам файлов (${notes.joinToString(",")})."
            )
        }

        return Result(
            hits = aggregate
                .values
                .sortedWith(
                    compareByDescending<Hit> { it.score }
                        .thenByDescending { it.timestampMs }
                )
                .take(limit.coerceIn(1, MAX_RETURNED_HITS)),
            scanned = scanned,
            scope = AccessScope.PROVIDER_VISIBLE,
            detail =
                "Файлы: проверены только записи, видимые AYANA через MediaStore/Downloads; " +
                    "на scoped-storage Android это не доказательство полного охвата хранилища. " +
                    "Поиск v1 выполняется по метаданным, не по содержимому файла. " +
                    "collections=${notes.joinToString(",")}"
        )
    }

    private data class FileQueryPart(
        val hits: List<Hit>,
        val scanned: Int
    )

    private fun queryFileCollection(
        query: String,
        collection: Uri,
        maxScan: Int
    ): FileQueryPart {
        if (maxScan <= 0) {
            return FileQueryPart(emptyList(), 0)
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

        val hits =
            mutableListOf<Hit>()
        var scanned = 0

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
                scanned < maxScan
            ) {
                scanned++

                val mediaType =
                    cursor.longOrZero(mediaTypeIndex).toInt()
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
                val modifiedAt = cursor.longOrZero(modifiedIndex) * 1000L

                val searchable =
                    listOf(
                        displayName,
                        relativePath,
                        mimeType
                    )
                        .joinToString(" ")

                val lexical = lexicalScore(query, searchable)
                if (lexical <= 0) {
                    continue
                }

                hits +=
                    Hit(
                        kind = Kind.FILE,
                        displayName =
                            displayName.ifBlank {
                                "Файл $id"
                            },
                        relativePath = relativePath,
                        mimeType = mimeType,
                        sizeBytes = sizeBytes,
                        timestampMs = modifiedAt,
                        uri =
                            ContentUris.withAppendedId(
                                collection,
                                id
                            ).toString(),
                        score = lexical,
                        metadata =
                            "media_type=$mediaType"
                    )
            }
        }

        return FileQueryPart(
            hits = hits,
            scanned = scanned
        )
    }

    private fun currentPhotoAccessScope(): AccessScope {
        if (Build.VERSION.SDK_INT >= 33) {
            if (
                appContext.checkSelfPermission(
                    Manifest.permission.READ_MEDIA_IMAGES
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return AccessScope.FULL
            }

            if (
                Build.VERSION.SDK_INT >= 34 &&
                appContext.checkSelfPermission(
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                return AccessScope.PARTIAL
            }

            return AccessScope.NONE
        }

        if (Build.VERSION.SDK_INT >= 23) {
            return if (
                appContext.checkSelfPermission(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                AccessScope.FULL
            } else {
                AccessScope.NONE
            }
        }

        return AccessScope.FULL
    }

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
                .map { it.trim() }
                .filter { it.length >= 2 }
                .distinct()

        val exact =
            if (h.contains(q)) 90 else 0
        val overlap =
            tokens.count { token ->
                h.contains(token)
            }

        if (exact == 0 && overlap == 0) {
            return 0
        }

        return exact + overlap * 18
    }

    private fun normalize(
        value: String
    ): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^a-zа-я0-9._/\\-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun compactError(
        error: Throwable
    ): String =
        error.message
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.take(160)
            .orEmpty()
            .ifBlank {
                error.javaClass.simpleName
            }

    private fun android.database.Cursor.stringOrEmpty(
        index: Int
    ): String =
        if (index >= 0 && !isNull(index)) {
            getString(index).orEmpty()
        } else {
            ""
        }

    private fun android.database.Cursor.longOrZero(
        index: Int
    ): Long =
        if (index >= 0 && !isNull(index)) {
            getLong(index)
        } else {
            0L
        }

    companion object {
        private const val DEFAULT_LIMIT = 6
        private const val MAX_RETURNED_HITS = 12
        private const val MAX_PHOTO_SCAN = 3000
        private const val MAX_FILE_SCAN = 5000
    }
}
