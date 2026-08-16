package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

class AyanaMemoryStore(
    context: Context
) {

    data class MemoryItem(
        val id: String,
        val text: String,
        val category: String,
        val createdAt: Long,
        val updatedAt: Long
    )

    private val memoryFile =
        File(
            context.filesDir,
            FILE_NAME
        )

    private val lock =
        Any()

    fun remember(
        text: String,
        category: String = "general"
    ): MemoryItem? {

        val cleanText =
            text
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        if (cleanText.isBlank()) {
            return null
        }

        synchronized(lock) {

            val items =
                readItemsMutable()

            val normalizedNew =
                normalize(cleanText)

            val existingIndex =
                items.indexOfFirst {
                    normalize(it.text) ==
                        normalizedNew
                }

            val now =
                System.currentTimeMillis()

            val item =
                if (existingIndex >= 0) {

                    val old =
                        items[existingIndex]

                    val updated =
                        old.copy(
                            text = cleanText,
                            category =
                                normalizeCategory(
                                    category
                                ),
                            updatedAt = now
                        )

                    items[existingIndex] =
                        updated

                    updated

                } else {

                    val created =
                        MemoryItem(
                            id =
                                UUID
                                    .randomUUID()
                                    .toString(),
                            text =
                                cleanText,
                            category =
                                normalizeCategory(
                                    category
                                ),
                            createdAt =
                                now,
                            updatedAt =
                                now
                        )

                    items.add(
                        created
                    )

                    created
                }

            val trimmed =
                items
                    .sortedByDescending {
                        it.updatedAt
                    }
                    .take(
                        MAX_MEMORIES
                    )
                    .toMutableList()

            writeItems(
                trimmed
            )

            return item
        }
    }

    fun forget(
        query: String
    ): Int {

        val cleanQuery =
            normalize(
                query
            )

        if (cleanQuery.isBlank()) {
            return 0
        }

        synchronized(lock) {

            val items =
                readItemsMutable()

            val before =
                items.size

            val filtered =
                items
                    .filterNot {

                        val text =
                            normalize(
                                it.text
                            )

                        val category =
                            normalize(
                                it.category
                            )

                        text.contains(
                            cleanQuery
                        ) ||
                            cleanQuery.contains(
                                text
                            ) ||
                            category ==
                            cleanQuery
                    }
                    .toMutableList()

            writeItems(
                filtered
            )

            return before -
                filtered.size
        }
    }

    fun clear(): Int {

        synchronized(lock) {

            val count =
                readItemsMutable()
                    .size

            writeItems(
                mutableListOf()
            )

            return count
        }
    }

    fun getAll(
        limit: Int = 50
    ): List<MemoryItem> {

        synchronized(lock) {

            return readItemsMutable()
                .sortedByDescending {
                    it.updatedAt
                }
                .take(
                    limit.coerceAtLeast(1)
                )
        }
    }

    fun search(
        query: String,
        limit: Int = 12
    ): List<MemoryItem> {

        val items =
            getAll(
                MAX_MEMORIES
            )

        val queryTokens =
            tokens(
                query
            )

        if (queryTokens.isEmpty()) {

            return items
                .take(
                    limit.coerceAtLeast(1)
                )
        }

        return items
            .map { item ->

                val itemTokens =
                    tokens(
                        item.text +
                            " " +
                            item.category
                    )

                val overlap =
                    queryTokens
                        .count {
                            it in
                                itemTokens
                        }

                val exactBonus =
                    if (
                        normalize(
                            item.text
                        ).contains(
                            normalize(
                                query
                            )
                        )
                    ) {
                        5
                    } else {
                        0
                    }

                val score =
                    overlap * 10 +
                        exactBonus

                item to score
            }
            .filter {
                it.second > 0
            }
            .sortedWith(
                compareByDescending<
                    Pair<MemoryItem, Int>
                > {
                    it.second
                }.thenByDescending {
                    it.first.updatedAt
                }
            )
            .map {
                it.first
            }
            .take(
                limit.coerceAtLeast(1)
            )
    }

    fun buildContextForAgent(
        currentRequest: String,
        maxItems: Int = 20,
        maxChars: Int = 5000
    ): String {

        val relevant =
            search(
                currentRequest,
                maxItems
            )

        val selected =
            if (relevant.isNotEmpty()) {

                relevant

            } else {

                getAll(
                    maxItems.coerceAtMost(8)
                )
            }

        if (selected.isEmpty()) {
            return ""
        }

        val builder =
            StringBuilder()

        builder.append(
            "Долговременная память AYANA:\n"
        )

        for (item in selected) {

            val line =
                "- [" +
                    item.category +
                    "] " +
                    item.text +
                    "\n"

            if (
                builder.length +
                    line.length >
                maxChars
            ) {
                break
            }

            builder.append(
                line
            )
        }

        return builder
            .toString()
            .trim()
    }

    fun count(): Int {

        synchronized(lock) {

            return readItemsMutable()
                .size
        }
    }

    private fun readItemsMutable():
        MutableList<MemoryItem> {

        if (
            !memoryFile.exists() ||
            memoryFile.length() == 0L
        ) {
            return mutableListOf()
        }

        return try {

            val root =
                JSONObject(
                    memoryFile
                        .readText(
                            Charsets.UTF_8
                        )
                )

            val array =
                root.optJSONArray(
                    "memories"
                ) ?: JSONArray()

            val result =
                mutableListOf<
                    MemoryItem
                >()

            for (
                i in
                0 until
                array.length()
            ) {

                val obj =
                    array
                        .optJSONObject(i)
                        ?: continue

                val text =
                    obj
                        .optString(
                            "text"
                        )
                        .trim()

                if (text.isBlank()) {
                    continue
                }

                result.add(
                    MemoryItem(
                        id =
                            obj
                                .optString(
                                    "id"
                                )
                                .ifBlank {
                                    UUID
                                        .randomUUID()
                                        .toString()
                                },
                        text =
                            text,
                        category =
                            normalizeCategory(
                                obj.optString(
                                    "category",
                                    "general"
                                )
                            ),
                        createdAt =
                            obj.optLong(
                                "created_at",
                                System
                                    .currentTimeMillis()
                            ),
                        updatedAt =
                            obj.optLong(
                                "updated_at",
                                System
                                    .currentTimeMillis()
                            )
                    )
                )
            }

            result

        } catch (_: Exception) {

            mutableListOf()
        }
    }

    private fun writeItems(
        items: MutableList<MemoryItem>
    ) {

        val array =
            JSONArray()

        items
            .sortedBy {
                it.createdAt
            }
            .forEach { item ->

                array.put(
                    JSONObject()
                        .put(
                            "id",
                            item.id
                        )
                        .put(
                            "text",
                            item.text
                        )
                        .put(
                            "category",
                            item.category
                        )
                        .put(
                            "created_at",
                            item.createdAt
                        )
                        .put(
                            "updated_at",
                            item.updatedAt
                        )
                )
            }

        val root =
            JSONObject()
                .put(
                    "version",
                    1
                )
                .put(
                    "memories",
                    array
                )

        val tempFile =
            File(
                memoryFile.parentFile,
                memoryFile.name +
                    ".tmp"
            )

        tempFile.writeText(
            root.toString(),
            Charsets.UTF_8
        )

        if (memoryFile.exists()) {
            memoryFile.delete()
        }

        if (
            !tempFile.renameTo(
                memoryFile
            )
        ) {

            memoryFile.writeText(
                root.toString(),
                Charsets.UTF_8
            )

            tempFile.delete()
        }
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.getDefault()
            )
            .replace(
                'ё',
                'е'
            )
            .replace(
                Regex(
                    "[^\\p{L}\\p{N}\\s]"
                ),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun tokens(
        value: String
    ): Set<String> {

        return normalize(
            value
        )
            .split(" ")
            .filter {
                it.length >= 3 &&
                    it !in STOP_WORDS
            }
            .toSet()
    }

    private fun normalizeCategory(
        value: String
    ): String {

        val category =
            normalize(
                value
            )

        return when (category) {

            "preference",
            "preferences",
            "предпочтение",
            "предпочтения" ->
                "preference"

            "task",
            "tasks",
            "задача",
            "задачи" ->
                "task"

            "project",
            "projects",
            "проект",
            "проекты" ->
                "project"

            "person",
            "people",
            "человек",
            "люди" ->
                "person"

            "place",
            "location",
            "место",
            "локация" ->
                "place"

            else ->
                "general"
        }
    }

    companion object {

        private const val FILE_NAME =
            "ayana_memory.json"

        private const val MAX_MEMORIES =
            250

        private val STOP_WORDS =
            setOf(
                "это",
                "как",
                "что",
                "для",
                "или",
                "она",
                "они",
                "его",
                "ее",
                "мне",
                "меня",
                "моя",
                "мой",
                "мои",
                "тебе",
                "тебя",
                "аяна",
                "запомни",
                "помни",
                "remember",
                "the",
                "and",
                "for",
                "with"
            )
    }
}
