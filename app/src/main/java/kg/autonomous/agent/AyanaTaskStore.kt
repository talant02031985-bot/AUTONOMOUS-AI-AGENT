package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class AyanaTaskStore(
    context: Context
) {

    data class TaskItem(
        val id: String,
        val title: String,
        val message: String,
        val triggerAtMillis: Long,
        val recurrence: String,
        val enabled: Boolean,
        val createdAt: Long,
        val updatedAt: Long
    )

    private val taskFile =
        File(
            context.filesDir,
            FILE_NAME
        )

    private val lock =
        Any()

    fun addTask(
        title: String,
        message: String,
        triggerAtMillis: Long,
        recurrence: String = RECURRENCE_NONE
    ): TaskItem? {

        val cleanTitle =
            title
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        val cleanMessage =
            message
                .trim()
                .replace(
                    Regex("\\s+"),
                    " "
                )

        if (
            cleanTitle.isBlank() ||
            triggerAtMillis <= 0L
        ) {
            return null
        }

        val now =
            System.currentTimeMillis()

        val item =
            TaskItem(
                id =
                    UUID
                        .randomUUID()
                        .toString(),
                title =
                    cleanTitle,
                message =
                    cleanMessage
                        .ifBlank {
                            cleanTitle
                        },
                triggerAtMillis =
                    triggerAtMillis,
                recurrence =
                    normalizeRecurrence(
                        recurrence
                    ),
                enabled =
                    true,
                createdAt =
                    now,
                updatedAt =
                    now
            )

        synchronized(lock) {

            val items =
                readItemsMutable()

            items.add(
                item
            )

            writeItems(
                items
                    .sortedByDescending {
                        it.updatedAt
                    }
                    .take(
                        MAX_TASKS
                    )
                    .toMutableList()
            )
        }

        return item
    }

    fun updateTask(
        id: String,
        title: String? = null,
        message: String? = null,
        triggerAtMillis: Long? = null,
        recurrence: String? = null,
        enabled: Boolean? = null
    ): TaskItem? {

        synchronized(lock) {

            val items =
                readItemsMutable()

            val index =
                items.indexOfFirst {
                    it.id == id
                }

            if (index < 0) {
                return null
            }

            val old =
                items[index]

            val updated =
                old.copy(
                    title =
                        title
                            ?.trim()
                            ?.replace(
                                Regex("\\s+"),
                                " "
                            )
                            ?.ifBlank {
                                old.title
                            }
                            ?: old.title,
                    message =
                        message
                            ?.trim()
                            ?.replace(
                                Regex("\\s+"),
                                " "
                            )
                            ?.ifBlank {
                                old.message
                            }
                            ?: old.message,
                    triggerAtMillis =
                        triggerAtMillis
                            ?.takeIf {
                                it > 0L
                            }
                            ?: old.triggerAtMillis,
                    recurrence =
                        recurrence
                            ?.let {
                                normalizeRecurrence(
                                    it
                                )
                            }
                            ?: old.recurrence,
                    enabled =
                        enabled
                            ?: old.enabled,
                    updatedAt =
                        System
                            .currentTimeMillis()
                )

            items[index] =
                updated

            writeItems(
                items
            )

            return updated
        }
    }

    fun deleteTask(
        id: String
    ): Boolean {

        synchronized(lock) {

            val items =
                readItemsMutable()

            val removed =
                items.removeAll {
                    it.id == id
                }

            if (removed) {
                writeItems(
                    items
                )
            }

            return removed
        }
    }

    fun deleteByQuery(
        query: String
    ): List<TaskItem> {

        val cleanQuery =
            normalize(
                query
            )

        if (cleanQuery.isBlank()) {
            return emptyList()
        }

        synchronized(lock) {

            val items =
                readItemsMutable()

            val removed =
                items.filter {

                    val haystack =
                        normalize(
                            it.title +
                                " " +
                                it.message
                        )

                    haystack.contains(
                        cleanQuery
                    ) ||
                        cleanQuery.contains(
                            normalize(
                                it.title
                            )
                        )
                }

            if (removed.isEmpty()) {
                return emptyList()
            }

            val removedIds =
                removed
                    .map {
                        it.id
                    }
                    .toSet()

            val remaining =
                items
                    .filterNot {
                        it.id in removedIds
                    }
                    .toMutableList()

            writeItems(
                remaining
            )

            return removed
        }
    }

    fun getTask(
        id: String
    ): TaskItem? {

        synchronized(lock) {

            return readItemsMutable()
                .firstOrNull {
                    it.id == id
                }
        }
    }

    fun getAll(
        includeDisabled: Boolean = false
    ): List<TaskItem> {

        synchronized(lock) {

            return readItemsMutable()
                .filter {
                    includeDisabled ||
                        it.enabled
                }
                .sortedBy {
                    it.triggerAtMillis
                }
        }
    }

    fun getFutureTasks(
        nowMillis: Long =
            System.currentTimeMillis()
    ): List<TaskItem> {

        return getAll(
            includeDisabled = false
        )
            .filter {
                it.triggerAtMillis >=
                    nowMillis
            }
            .sortedBy {
                it.triggerAtMillis
            }
    }

    fun getDueTasks(
        nowMillis: Long =
            System.currentTimeMillis()
    ): List<TaskItem> {

        return getAll(
            includeDisabled = false
        )
            .filter {
                it.triggerAtMillis <=
                    nowMillis
            }
            .sortedBy {
                it.triggerAtMillis
            }
    }

    fun count(): Int {

        synchronized(lock) {

            return readItemsMutable()
                .size
        }
    }

    private fun readItemsMutable():
        MutableList<TaskItem> {

        if (
            !taskFile.exists() ||
            taskFile.length() == 0L
        ) {
            return mutableListOf()
        }

        return try {

            val root =
                JSONObject(
                    taskFile.readText(
                        Charsets.UTF_8
                    )
                )

            val array =
                root.optJSONArray(
                    "tasks"
                ) ?: JSONArray()

            val result =
                mutableListOf<TaskItem>()

            for (
                i in
                0 until
                array.length()
            ) {

                val obj =
                    array
                        .optJSONObject(i)
                        ?: continue

                val id =
                    obj
                        .optString(
                            "id"
                        )
                        .trim()

                val title =
                    obj
                        .optString(
                            "title"
                        )
                        .trim()

                val triggerAt =
                    obj
                        .optLong(
                            "trigger_at_millis",
                            0L
                        )

                if (
                    id.isBlank() ||
                    title.isBlank() ||
                    triggerAt <= 0L
                ) {
                    continue
                }

                result.add(
                    TaskItem(
                        id =
                            id,
                        title =
                            title,
                        message =
                            obj
                                .optString(
                                    "message",
                                    title
                                )
                                .trim()
                                .ifBlank {
                                    title
                                },
                        triggerAtMillis =
                            triggerAt,
                        recurrence =
                            normalizeRecurrence(
                                obj.optString(
                                    "recurrence",
                                    RECURRENCE_NONE
                                )
                            ),
                        enabled =
                            obj.optBoolean(
                                "enabled",
                                true
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
        items: MutableList<TaskItem>
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
                            "title",
                            item.title
                        )
                        .put(
                            "message",
                            item.message
                        )
                        .put(
                            "trigger_at_millis",
                            item.triggerAtMillis
                        )
                        .put(
                            "recurrence",
                            item.recurrence
                        )
                        .put(
                            "enabled",
                            item.enabled
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
                    "tasks",
                    array
                )

        val tempFile =
            File(
                taskFile.parentFile,
                taskFile.name +
                    ".tmp"
            )

        tempFile.writeText(
            root.toString(),
            Charsets.UTF_8
        )

        if (taskFile.exists()) {
            taskFile.delete()
        }

        if (
            !tempFile.renameTo(
                taskFile
            )
        ) {

            taskFile.writeText(
                root.toString(),
                Charsets.UTF_8
            )

            tempFile.delete()
        }
    }

    private fun normalizeRecurrence(
        value: String
    ): String {

        return when (
            normalize(
                value
            )
        ) {

            "daily",
            "every day",
            "ежедневно",
            "каждый день" ->
                RECURRENCE_DAILY

            "weekly",
            "every week",
            "еженедельно",
            "каждую неделю" ->
                RECURRENCE_WEEKLY

            "monthly",
            "every month",
            "ежемесячно",
            "каждый месяц" ->
                RECURRENCE_MONTHLY

            else ->
                RECURRENCE_NONE
        }
    }

    private fun normalize(
        value: String
    ): String {

        return value
            .lowercase()
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

    companion object {

        private const val FILE_NAME =
            "ayana_tasks.json"

        private const val MAX_TASKS =
            200

        const val RECURRENCE_NONE =
            "none"

        const val RECURRENCE_DAILY =
            "daily"

        const val RECURRENCE_WEEKLY =
            "weekly"

        const val RECURRENCE_MONTHLY =
            "monthly"
    }
}
