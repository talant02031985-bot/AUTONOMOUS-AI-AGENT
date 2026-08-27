package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID
import kotlin.math.max

/**
 * AYANA Memory v2.0.
 *
 * Backward-compatible with ayana_memory.json v1. Adds provenance, confidence,
 * access metadata, editing by query and conservative conflict candidates.
 */
class AyanaMemoryStore(
    context: Context
) {

    data class MemoryItem(
        val id: String,
        val text: String,
        val category: String,
        val createdAt: Long,
        val updatedAt: Long,
        val source: String = "user",
        val provenance: String = "explicit",
        val confidence: Double = 1.0,
        val lastAccessedAt: Long = 0L,
        val accessCount: Int = 0,
        val supersedesId: String = ""
    )

    data class UpdateResult(
        val success: Boolean,
        val item: MemoryItem?,
        val matched: Int,
        val message: String
    )

    private val appContext = context.applicationContext
    private val memoryFile = File(appContext.filesDir, FILE_NAME)
    private val tempFile = File(appContext.filesDir, "$FILE_NAME.tmp")
    private val backupFile = File(appContext.filesDir, "$FILE_NAME.bak")
    private val lock = Any()

    fun remember(
        text: String,
        category: String = "general",
        source: String = "user",
        provenance: String = "explicit",
        confidence: Double = 1.0
    ): MemoryItem? {
        val cleanText = cleanText(text)
        if (cleanText.isBlank()) return null

        synchronized(lock) {
            val items = readItemsMutable()
            val normalizedNew = normalize(cleanText)
            val existingIndex = items.indexOfFirst {
                normalize(it.text) == normalizedNew
            }
            val now = System.currentTimeMillis()
            val item = if (existingIndex >= 0) {
                val old = items[existingIndex]
                old.copy(
                    text = cleanText,
                    category = normalizeCategory(category),
                    updatedAt = now,
                    source = normalizeSource(source),
                    provenance = normalizeProvenance(provenance),
                    confidence = confidence.coerceIn(0.0, 1.0)
                ).also { items[existingIndex] = it }
            } else {
                MemoryItem(
                    id = UUID.randomUUID().toString(),
                    text = cleanText,
                    category = normalizeCategory(category),
                    createdAt = now,
                    updatedAt = now,
                    source = normalizeSource(source),
                    provenance = normalizeProvenance(provenance),
                    confidence = confidence.coerceIn(0.0, 1.0)
                ).also { items.add(it) }
            }

            writeItems(
                items.sortedByDescending { it.updatedAt }
                    .take(MAX_MEMORIES)
                    .toMutableList()
            )
            return item
        }
    }

    fun updateByQuery(
        query: String,
        newText: String,
        newCategory: String? = null,
        source: String = "user"
    ): UpdateResult {
        val cleanQuery = normalize(query)
        val cleanNewText = cleanText(newText)
        if (cleanQuery.isBlank() || cleanNewText.isBlank()) {
            return UpdateResult(false, null, 0, "Не указана память для изменения или новый текст")
        }

        synchronized(lock) {
            val items = readItemsMutable()
            val ranked = rank(items, query)
            val best = ranked.firstOrNull()
                ?: return UpdateResult(false, null, 0, "Подходящая запись памяти не найдена")
            val second = ranked.getOrNull(1)
            if (best.second < 20) {
                return UpdateResult(false, null, 0, "Подходящая запись памяти не найдена")
            }
            if (second != null && best.second < 80 && best.second - second.second < 8) {
                return UpdateResult(false, null, ranked.count { it.second >= 20 }, "Найдено несколько похожих записей; уточните, какую изменить")
            }

            val index = items.indexOfFirst { it.id == best.first.id }
            if (index < 0) {
                return UpdateResult(false, null, 0, "Запись памяти исчезла во время обновления")
            }

            val old = items[index]
            val updated = old.copy(
                text = cleanNewText,
                category = newCategory?.let(::normalizeCategory) ?: old.category,
                updatedAt = System.currentTimeMillis(),
                source = normalizeSource(source),
                provenance = "edited",
                confidence = 1.0,
                supersedesId = old.id
            )
            items[index] = updated
            writeItems(items)
            return UpdateResult(true, updated, 1, "Память обновлена")
        }
    }

    fun forget(
        query: String
    ): Int {
        val cleanQuery = normalize(query)
        if (cleanQuery.isBlank()) return 0

        synchronized(lock) {
            val items = readItemsMutable()
            val before = items.size
            val filtered = items.filterNot { item ->
                val text = normalize(item.text)
                val category = normalize(item.category)
                text.contains(cleanQuery) ||
                    cleanQuery.contains(text) ||
                    category == cleanQuery
            }.toMutableList()
            if (filtered.size != before) writeItems(filtered)
            return before - filtered.size
        }
    }

    fun clear(): Int {
        synchronized(lock) {
            val count = readItemsMutable().size
            writeItems(mutableListOf())
            return count
        }
    }

    fun getAll(
        limit: Int = 50
    ): List<MemoryItem> {
        synchronized(lock) {
            return readItemsMutable()
                .sortedByDescending { it.updatedAt }
                .take(limit.coerceIn(1, MAX_MEMORIES))
        }
    }

    fun search(
        query: String,
        limit: Int = 12
    ): List<MemoryItem> {
        synchronized(lock) {
            val items = readItemsMutable()
            if (normalize(query).isBlank()) {
                return items.sortedByDescending { it.updatedAt }
                    .take(limit.coerceIn(1, MAX_MEMORIES))
            }

            val normalizedQuery =
                normalize(
                    query
                )

            val queryTokens =
                tokens(
                    query
                )

            val identifierLike =
                NUMBER.containsMatchIn(
                    normalizedQuery
                ) ||
                    normalizedQuery.contains(
                        "test"
                    ) ||
                    normalizedQuery.contains(
                        "mem"
                    )

            val ranked =
                rank(
                    items,
                    query
                )
                    .filter { pair ->
                        if (
                            identifierLike &&
                            queryTokens.size >= 2
                        ) {
                            val itemText =
                                normalize(
                                    pair.first.text +
                                        " " +
                                        pair.first.category
                                )

                            val itemTokens =
                                tokens(
                                    pair.first.text +
                                        " " +
                                        pair.first.category
                                )

                            itemText.contains(
                                normalizedQuery
                            ) ||
                                queryTokens.all {
                                    it in itemTokens
                                }
                        } else {
                            pair.second >=
                                MIN_SEARCH_SCORE
                        }
                    }
                    .take(
                        limit.coerceIn(
                            1,
                            MAX_MEMORIES
                        )
                    )

            if (ranked.isNotEmpty()) {
                val now = System.currentTimeMillis()
                val accessedIds = ranked.map { it.first.id }.toSet()
                var changed = false
                for (index in items.indices) {
                    if (items[index].id in accessedIds) {
                        val old = items[index]
                        items[index] = old.copy(
                            lastAccessedAt = now,
                            accessCount = old.accessCount + 1
                        )
                        changed = true
                    }
                }
                if (changed) {
                    try { writeItems(items) } catch (_: Exception) {}
                }
            }

            return ranked.map { pair ->
                items.firstOrNull { it.id == pair.first.id } ?: pair.first
            }
        }
    }

    fun findPotentialConflicts(
        candidateText: String,
        limit: Int = 6
    ): List<MemoryItem> {
        val clean = normalize(candidateText)
        if (clean.isBlank()) return emptyList()
        val candidateTokens = tokens(candidateText)
        val candidateNumbers = NUMBER.findAll(clean).map { it.value }.toSet()
        val candidateNegated = containsNegation(clean)

        return getAll(MAX_MEMORIES)
            .mapNotNull { item ->
                val itemTokens = tokens(item.text)
                val common = candidateTokens.intersect(itemTokens).size
                val base = max(1, minOf(candidateTokens.size, itemTokens.size))
                val semanticOverlap = common.toDouble() / base.toDouble()
                if (semanticOverlap < 0.55 || common < 2) return@mapNotNull null

                val itemNormalized = normalize(item.text)
                val itemNumbers = NUMBER.findAll(itemNormalized).map { it.value }.toSet()
                val numbersConflict = candidateNumbers.isNotEmpty() &&
                    itemNumbers.isNotEmpty() &&
                    candidateNumbers != itemNumbers
                val negationConflict = candidateNegated != containsNegation(itemNormalized)

                if (numbersConflict || negationConflict) item else null
            }
            .sortedByDescending { it.updatedAt }
            .take(limit.coerceIn(1, 20))
    }

    fun buildContextForAgent(
        currentRequest: String,
        maxItems: Int = 20,
        maxChars: Int = 5000
    ): String {
        val relevant = search(currentRequest, maxItems)
        val selected = if (relevant.isNotEmpty()) relevant else getAll(maxItems.coerceAtMost(8))
        if (selected.isEmpty()) return ""

        val builder = StringBuilder("Долговременная память AYANA v2:\n")
        for (item in selected) {
            val line = "- [${item.category}; source=${item.source}; confidence=${"%.2f".format(Locale.US, item.confidence)}] ${item.text}\n"
            if (builder.length + line.length > maxChars) break
            builder.append(line)
        }
        return builder.toString().trim()
    }

    fun count(): Int {
        synchronized(lock) {
            return readItemsMutable().size
        }
    }

    fun asJson(
        query: String = "",
        limit: Int = 50
    ): JSONObject {
        val items = if (query.isBlank()) getAll(limit) else search(query, limit)
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("category", item.category)
                    .put("source", item.source)
                    .put("provenance", item.provenance)
                    .put("confidence", item.confidence)
                    .put("created_at", item.createdAt)
                    .put("updated_at", item.updatedAt)
                    .put("last_accessed_at", item.lastAccessedAt)
                    .put("access_count", item.accessCount)
            )
        }
        return JSONObject()
            .put("success", true)
            .put("count", items.size)
            .put("memories", array)
    }

    private fun rank(
        items: List<MemoryItem>,
        query: String
    ): List<Pair<MemoryItem, Int>> {
        val queryTokens = tokens(query)
        val normalizedQuery = normalize(query)
        return items.map { item ->
            val itemTokens = tokens(item.text + " " + item.category)
            val overlap = queryTokens.count { it in itemTokens }
            val exactBonus = if (normalize(item.text).contains(normalizedQuery)) 25 else 0
            val categoryBonus = if (normalize(item.category) == normalizedQuery) 12 else 0
            val sourceBonus = if (item.source == "user") 2 else 0
            val score = overlap * 12 + exactBonus + categoryBonus + sourceBonus
            item to score
        }.sortedWith(
            compareByDescending<Pair<MemoryItem, Int>> { it.second }
                .thenByDescending { it.first.updatedAt }
        )
    }

    private fun readItemsMutable(): MutableList<MemoryItem> {
        val candidates = listOf(memoryFile, tempFile, backupFile)
        var best: JSONObject? = null
        var bestSavedAt = Long.MIN_VALUE

        for (candidate in candidates) {
            if (!candidate.exists() || candidate.length() == 0L) continue
            try {
                val root = JSONObject(candidate.readText(Charsets.UTF_8))
                val savedAt = root.optLong("saved_at", candidate.lastModified())
                if (best == null || savedAt > bestSavedAt) {
                    best = root
                    bestSavedAt = savedAt
                }
            } catch (_: Exception) {}
        }

        val root = best ?: return mutableListOf()
        val array = root.optJSONArray("memories") ?: JSONArray()
        val result = mutableListOf<MemoryItem>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val text = obj.optString("text").trim()
            if (text.isBlank()) continue
            val created = obj.optLong("created_at", System.currentTimeMillis())
            result.add(
                MemoryItem(
                    id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                    text = text,
                    category = normalizeCategory(obj.optString("category", "general")),
                    createdAt = created,
                    updatedAt = obj.optLong("updated_at", created),
                    source = normalizeSource(obj.optString("source", "user")),
                    provenance = normalizeProvenance(obj.optString("provenance", "legacy")),
                    confidence = obj.optDouble("confidence", 1.0).coerceIn(0.0, 1.0),
                    lastAccessedAt = obj.optLong("last_accessed_at", 0L),
                    accessCount = obj.optInt("access_count", 0).coerceAtLeast(0),
                    supersedesId = obj.optString("supersedes_id")
                )
            )
        }
        return result
    }

    private fun writeItems(
        items: MutableList<MemoryItem>
    ) {
        val array = JSONArray()
        items.sortedBy { it.createdAt }.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("text", item.text)
                    .put("category", item.category)
                    .put("source", item.source)
                    .put("provenance", item.provenance)
                    .put("confidence", item.confidence)
                    .put("created_at", item.createdAt)
                    .put("updated_at", item.updatedAt)
                    .put("last_accessed_at", item.lastAccessedAt)
                    .put("access_count", item.accessCount)
                    .put("supersedes_id", item.supersedesId)
            )
        }

        val root = JSONObject()
            .put("version", 2)
            .put("saved_at", System.currentTimeMillis())
            .put("memories", array)

        tempFile.writeText(root.toString(), Charsets.UTF_8)
        if (memoryFile.exists()) {
            try {
                if (backupFile.exists()) backupFile.delete()
                if (!memoryFile.renameTo(backupFile)) {
                    memoryFile.copyTo(backupFile, overwrite = true)
                }
            } catch (_: Exception) {}
        }

        var committed = tempFile.renameTo(memoryFile)
        if (!committed) {
            try {
                tempFile.copyTo(memoryFile, overwrite = true)
                committed = true
            } catch (_: Exception) {}
        }
        if (committed) {
            tempFile.delete()
            return
        }
        if (!memoryFile.exists() && backupFile.exists()) {
            try { backupFile.copyTo(memoryFile, overwrite = true) } catch (_: Exception) {}
        }
        throw IllegalStateException("Не удалось сохранить память AYANA")
    }

    private fun cleanText(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").take(MAX_TEXT_CHARS)

    private fun normalize(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun tokens(value: String): Set<String> =
        normalize(value)
            .split(" ")
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()

    private fun normalizeCategory(value: String): String =
        when (normalize(value)) {
            "preference", "preferences", "предпочтение", "предпочтения" -> "preference"
            "task", "tasks", "задача", "задачи" -> "task"
            "project", "projects", "проект", "проекты" -> "project"
            "person", "people", "человек", "люди" -> "person"
            "place", "location", "место", "локация" -> "place"
            "decision", "решение", "решения" -> "decision"
            "fact", "факт", "факты" -> "fact"
            else -> "general"
        }

    private fun normalizeSource(value: String): String =
        when (normalize(value)) {
            "user", "пользователь" -> "user"
            "agent", "ayana", "аяна" -> "agent"
            "device", "устройство" -> "device"
            "import", "импорт" -> "import"
            else -> "user"
        }

    private fun normalizeProvenance(value: String): String =
        when (normalize(value)) {
            "explicit", "явно" -> "explicit"
            "edited", "изменено" -> "edited"
            "inferred", "выведено" -> "inferred"
            "legacy" -> "legacy"
            else -> "explicit"
        }

    private fun containsNegation(value: String): Boolean =
        NEGATION.containsMatchIn(value)

    companion object {
        private const val FILE_NAME = "ayana_memory.json"
        private const val MAX_MEMORIES = 350
        private const val MAX_TEXT_CHARS = 1800
        // A user-authored record receives a +2 source bonus. Search must never
        // treat that bonus alone as relevance; one real token overlap scores 12.
        private const val MIN_SEARCH_SCORE = 12
        private val NUMBER = Regex("\\b\\d+(?:[.,]\\d+)?\\b")
        private val NEGATION = Regex("\\b(не|нет|никогда|без)\\b")
        private val STOP_WORDS = setOf(
            "это", "как", "что", "для", "или", "она", "они", "его", "ее", "мне",
            "меня", "моя", "мой", "мои", "тебе", "тебя", "аяна", "запомни", "помни",
            "remember", "the", "and", "for", "with", "есть", "был", "была", "будет"
        )
    }
}
