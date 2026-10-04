package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * AYANA Project Store v1.0 — R10.28 PROJECTS.
 *
 * Durable source of truth for isolated named project workspaces.
 * This class owns only project metadata + active_project_id.
 * Domain stores (memory/history/tasks/files/goals/media) remain separate and
 * consume projectId as their namespace in later R10.28 integration slices.
 *
 * Safety / isolation invariants:
 * - project IDs are opaque UUIDs;
 * - project names are unique case-insensitively among non-archived projects;
 * - switching active project never copies data between namespaces;
 * - archive/delete cannot silently migrate data;
 * - active project survives process/app restart;
 * - project metadata commits atomically using tmp + backup.
 */
class AyanaProjectStore(
    context: Context
) {

    data class Project(
        val projectId: String,
        val name: String,
        val description: String = "",
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val archived: Boolean = false,
        val pinned: Boolean = false,
        val rootNamespace: String,
        val memoryNamespace: String,
        val historyNamespace: String,
        val filesNamespace: String,
        val tasksNamespace: String,
        val goalsNamespace: String,
        val checkpointsNamespace: String,
        val testsNamespace: String,
        val repoConfigNamespace: String,
        val mediaNamespace: String,
        val metadataVersion: Int = METADATA_VERSION
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("project_id", projectId)
                .put("name", name)
                .put("description", description)
                .put("created_at_ms", createdAtMs)
                .put("updated_at_ms", updatedAtMs)
                .put("archived", archived)
                .put("pinned", pinned)
                .put("root_namespace", rootNamespace)
                .put("memory_namespace", memoryNamespace)
                .put("history_namespace", historyNamespace)
                .put("files_namespace", filesNamespace)
                .put("tasks_namespace", tasksNamespace)
                .put("goals_namespace", goalsNamespace)
                .put("checkpoints_namespace", checkpointsNamespace)
                .put("tests_namespace", testsNamespace)
                .put("repo_config_namespace", repoConfigNamespace)
                .put("media_namespace", mediaNamespace)
                .put("metadata_version", metadataVersion)
    }

    data class MutationResult(
        val success: Boolean,
        val project: Project? = null,
        val reason: String,
        val activeProjectId: String? = null
    )

    private val appContext = context.applicationContext
    private val file = File(appContext.filesDir, FILE_NAME)
    private val tempFile = File(appContext.filesDir, "$FILE_NAME.tmp")
    private val backupFile = File(appContext.filesDir, "$FILE_NAME.bak")
    private val lock = Any()

    fun create(
        name: String,
        description: String = "",
        makeActive: Boolean = true
    ): MutationResult =
        synchronized(lock) {
            val cleanName = cleanName(name)
            if (cleanName.isBlank()) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_name_empty",
                    activeProjectId = readStateUnsafe().activeProjectId
                )
            }

            val state = readStateUnsafe()
            val duplicate = state.projects.firstOrNull {
                !it.archived &&
                    it.name.equals(cleanName, ignoreCase = true)
            }
            if (duplicate != null) {
                return@synchronized MutationResult(
                    success = false,
                    project = duplicate,
                    reason = "project_name_already_exists",
                    activeProjectId = state.activeProjectId
                )
            }

            val now = System.currentTimeMillis()
            val id = UUID.randomUUID().toString()
            val root = "project/$id"

            val project = Project(
                projectId = id,
                name = cleanName,
                description = description.trim().take(MAX_DESCRIPTION_CHARS),
                createdAtMs = now,
                updatedAtMs = now,
                rootNamespace = root,
                memoryNamespace = "$root/memory",
                historyNamespace = "$root/history",
                filesNamespace = "$root/files",
                tasksNamespace = "$root/tasks",
                goalsNamespace = "$root/goals",
                checkpointsNamespace = "$root/checkpoints",
                testsNamespace = "$root/tests",
                repoConfigNamespace = "$root/repo_config",
                mediaNamespace = "$root/media"
            )

            state.projects.add(project)
            if (makeActive) {
                state.activeProjectId = id
            }
            state.updatedAtMs = now
            writeStateUnsafe(state)

            MutationResult(
                success = true,
                project = project,
                reason = "project_created",
                activeProjectId = state.activeProjectId
            )
        }

    fun list(
        includeArchived: Boolean = false
    ): List<Project> =
        synchronized(lock) {
            readStateUnsafe()
                .projects
                .filter { includeArchived || !it.archived }
                .sortedWith(
                    compareByDescending<Project> { it.pinned }
                        .thenByDescending { it.updatedAtMs }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )
        }

    fun activeProject(): Project? =
        synchronized(lock) {
            val state = readStateUnsafe()
            val id = state.activeProjectId ?: return@synchronized null
            state.projects.firstOrNull {
                it.projectId == id && !it.archived
            }
        }

    fun activeProjectId(): String? =
        synchronized(lock) {
            val state = readStateUnsafe()
            val id = state.activeProjectId ?: return@synchronized null
            if (state.projects.any { it.projectId == id && !it.archived }) {
                id
            } else {
                null
            }
        }

    fun getById(projectId: String): Project? =
        synchronized(lock) {
            readStateUnsafe()
                .projects
                .firstOrNull { it.projectId == projectId }
        }

    fun findByName(name: String): Project? {
        val normalized = cleanName(name)
        if (normalized.isBlank()) return null
        return synchronized(lock) {
            readStateUnsafe()
                .projects
                .firstOrNull {
                    !it.archived &&
                        it.name.equals(normalized, ignoreCase = true)
                }
        }
    }

    fun switchActive(projectId: String): MutationResult =
        synchronized(lock) {
            val state = readStateUnsafe()
            val index = state.projects.indexOfFirst {
                it.projectId == projectId
            }
            if (index < 0) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_not_found",
                    activeProjectId = state.activeProjectId
                )
            }

            val project = state.projects[index]
            if (project.archived) {
                return@synchronized MutationResult(
                    success = false,
                    project = project,
                    reason = "project_archived",
                    activeProjectId = state.activeProjectId
                )
            }

            state.activeProjectId = project.projectId
            state.projects[index] = project.copy(
                updatedAtMs = System.currentTimeMillis()
            )
            state.updatedAtMs = System.currentTimeMillis()
            writeStateUnsafe(state)

            MutationResult(
                success = true,
                project = state.projects[index],
                reason = "active_project_switched",
                activeProjectId = state.activeProjectId
            )
        }

    fun switchActiveByName(name: String): MutationResult {
        val project = findByName(name)
            ?: return MutationResult(
                success = false,
                reason = "project_not_found",
                activeProjectId = activeProjectId()
            )
        return switchActive(project.projectId)
    }

    fun clearActive(): MutationResult =
        synchronized(lock) {
            val state = readStateUnsafe()
            state.activeProjectId = null
            state.updatedAtMs = System.currentTimeMillis()
            writeStateUnsafe(state)
            MutationResult(
                success = true,
                reason = "active_project_cleared",
                activeProjectId = null
            )
        }

    fun rename(
        projectId: String,
        newName: String
    ): MutationResult =
        synchronized(lock) {
            val clean = cleanName(newName)
            if (clean.isBlank()) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_name_empty",
                    activeProjectId = readStateUnsafe().activeProjectId
                )
            }

            val state = readStateUnsafe()
            if (state.projects.any {
                    it.projectId != projectId &&
                        !it.archived &&
                        it.name.equals(clean, ignoreCase = true)
                }
            ) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_name_already_exists",
                    activeProjectId = state.activeProjectId
                )
            }

            val index = state.projects.indexOfFirst {
                it.projectId == projectId
            }
            if (index < 0) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_not_found",
                    activeProjectId = state.activeProjectId
                )
            }

            val updated = state.projects[index].copy(
                name = clean,
                updatedAtMs = System.currentTimeMillis()
            )
            state.projects[index] = updated
            state.updatedAtMs = updated.updatedAtMs
            writeStateUnsafe(state)

            MutationResult(
                success = true,
                project = updated,
                reason = "project_renamed",
                activeProjectId = state.activeProjectId
            )
        }

    fun setPinned(
        projectId: String,
        pinned: Boolean
    ): MutationResult =
        synchronized(lock) {
            val state = readStateUnsafe()
            val index = state.projects.indexOfFirst {
                it.projectId == projectId
            }
            if (index < 0) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_not_found",
                    activeProjectId = state.activeProjectId
                )
            }

            val updated = state.projects[index].copy(
                pinned = pinned,
                updatedAtMs = System.currentTimeMillis()
            )
            state.projects[index] = updated
            state.updatedAtMs = updated.updatedAtMs
            writeStateUnsafe(state)

            MutationResult(
                success = true,
                project = updated,
                reason = if (pinned) "project_pinned" else "project_unpinned",
                activeProjectId = state.activeProjectId
            )
        }

    fun archive(projectId: String): MutationResult =
        synchronized(lock) {
            val state = readStateUnsafe()
            val index = state.projects.indexOfFirst {
                it.projectId == projectId
            }
            if (index < 0) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_not_found",
                    activeProjectId = state.activeProjectId
                )
            }

            val current = state.projects[index]
            if (current.archived) {
                return@synchronized MutationResult(
                    success = true,
                    project = current,
                    reason = "project_already_archived",
                    activeProjectId = state.activeProjectId
                )
            }

            val updated = current.copy(
                archived = true,
                pinned = false,
                updatedAtMs = System.currentTimeMillis()
            )
            state.projects[index] = updated
            if (state.activeProjectId == projectId) {
                state.activeProjectId = null
            }
            state.updatedAtMs = updated.updatedAtMs
            writeStateUnsafe(state)

            MutationResult(
                success = true,
                project = updated,
                reason = "project_archived",
                activeProjectId = state.activeProjectId
            )
        }

    fun restore(projectId: String): MutationResult =
        synchronized(lock) {
            val state = readStateUnsafe()
            val index = state.projects.indexOfFirst {
                it.projectId == projectId
            }
            if (index < 0) {
                return@synchronized MutationResult(
                    success = false,
                    reason = "project_not_found",
                    activeProjectId = state.activeProjectId
                )
            }

            val updated = state.projects[index].copy(
                archived = false,
                updatedAtMs = System.currentTimeMillis()
            )
            state.projects[index] = updated
            state.updatedAtMs = updated.updatedAtMs
            writeStateUnsafe(state)

            MutationResult(
                success = true,
                project = updated,
                reason = "project_restored",
                activeProjectId = state.activeProjectId
            )
        }

    /**
     * Metadata deletion is intentionally explicit. Domain namespace deletion is
     * not performed here; caller must coordinate the separate project-data purge
     * after explicit user confirmation.
     */
    fun deleteMetadata(
        projectId: String,
        explicitConfirmation: Boolean
    ): MutationResult =
        synchronized(lock) {
            val state = readStateUnsafe()
            val project = state.projects.firstOrNull {
                it.projectId == projectId
            } ?: return@synchronized MutationResult(
                success = false,
                reason = "project_not_found",
                activeProjectId = state.activeProjectId
            )

            if (!explicitConfirmation) {
                return@synchronized MutationResult(
                    success = false,
                    project = project,
                    reason = "explicit_confirmation_required",
                    activeProjectId = state.activeProjectId
                )
            }

            state.projects.removeAll {
                it.projectId == projectId
            }
            if (state.activeProjectId == projectId) {
                state.activeProjectId = null
            }
            state.updatedAtMs = System.currentTimeMillis()
            writeStateUnsafe(state)

            MutationResult(
                success = true,
                project = project,
                reason = "project_metadata_deleted",
                activeProjectId = state.activeProjectId
            )
        }

    fun snapshot(): JSONObject =
        synchronized(lock) {
            val state = readStateUnsafe()
            val active = state.activeProjectId
                ?.let { id ->
                    state.projects.firstOrNull {
                        it.projectId == id && !it.archived
                    }
                }

            JSONObject()
                .put("project_store_version", VERSION)
                .put("schema_version", SCHEMA_VERSION)
                .put("active_project_id", active?.projectId ?: JSONObject.NULL)
                .put("active_project_name", active?.name ?: "")
                .put(
                    "projects_total",
                    state.projects.size
                )
                .put(
                    "projects_active_count",
                    state.projects.count { !it.archived }
                )
                .put(
                    "projects_archived_count",
                    state.projects.count { it.archived }
                )
                .put(
                    "projects",
                    JSONArray().also { array ->
                        list(includeArchived = true)
                            .forEach { array.put(it.toJson()) }
                    }
                )
                .put("updated_at_ms", state.updatedAtMs)
        }

    fun selfTest(): Boolean =
        try {
            val id = UUID.randomUUID().toString()
            val root = "project/$id"
            val project = Project(
                projectId = id,
                name = "R10.28 Self Test",
                createdAtMs = 1L,
                updatedAtMs = 1L,
                rootNamespace = root,
                memoryNamespace = "$root/memory",
                historyNamespace = "$root/history",
                filesNamespace = "$root/files",
                tasksNamespace = "$root/tasks",
                goalsNamespace = "$root/goals",
                checkpointsNamespace = "$root/checkpoints",
                testsNamespace = "$root/tests",
                repoConfigNamespace = "$root/repo_config",
                mediaNamespace = "$root/media"
            )
            project.projectId.isNotBlank() &&
                project.rootNamespace.endsWith(id) &&
                project.memoryNamespace != project.historyNamespace &&
                project.filesNamespace != project.mediaNamespace &&
                project.metadataVersion == METADATA_VERSION
        } catch (_: Exception) {
            false
        }

    private data class State(
        val projects: MutableList<Project> = mutableListOf(),
        var activeProjectId: String? = null,
        var updatedAtMs: Long = 0L
    )

    private fun readStateUnsafe(): State {
        val source = when {
            file.exists() -> file
            backupFile.exists() -> backupFile
            else -> null
        } ?: return State()

        return try {
            val root = JSONObject(source.readText(Charsets.UTF_8))
            val projects = mutableListOf<Project>()
            val array = root.optJSONArray("projects") ?: JSONArray()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                parseProject(obj)?.let { projects.add(it) }
            }

            val active = root
                .optString("active_project_id", "")
                .trim()
                .takeIf { it.isNotBlank() }
                ?.takeIf { id ->
                    projects.any {
                        it.projectId == id && !it.archived
                    }
                }

            State(
                projects = projects,
                activeProjectId = active,
                updatedAtMs = root.optLong("updated_at_ms", 0L)
            )
        } catch (_: Exception) {
            State()
        }
    }

    private fun writeStateUnsafe(state: State) {
        val root = JSONObject()
            .put("schema_version", SCHEMA_VERSION)
            .put(
                "active_project_id",
                state.activeProjectId ?: JSONObject.NULL
            )
            .put("updated_at_ms", state.updatedAtMs)
            .put(
                "projects",
                JSONArray().also { array ->
                    state.projects.forEach {
                        array.put(it.toJson())
                    }
                }
            )

        val data = root.toString(2)
        tempFile.writeText(data, Charsets.UTF_8)

        if (file.exists()) {
            try {
                file.copyTo(backupFile, overwrite = true)
            } catch (_: Exception) {
            }
        }

        if (!tempFile.renameTo(file)) {
            file.writeText(data, Charsets.UTF_8)
            tempFile.delete()
        }
    }

    private fun parseProject(obj: JSONObject): Project? {
        val id = obj.optString("project_id", "").trim()
        val name = cleanName(obj.optString("name", ""))
        if (id.isBlank() || name.isBlank()) return null

        val root = obj
            .optString("root_namespace", "project/$id")
            .ifBlank { "project/$id" }

        return Project(
            projectId = id,
            name = name,
            description = obj.optString("description", "")
                .take(MAX_DESCRIPTION_CHARS),
            createdAtMs = obj.optLong("created_at_ms", 0L),
            updatedAtMs = obj.optLong("updated_at_ms", 0L),
            archived = obj.optBoolean("archived", false),
            pinned = obj.optBoolean("pinned", false),
            rootNamespace = root,
            memoryNamespace = obj.optString(
                "memory_namespace",
                "$root/memory"
            ),
            historyNamespace = obj.optString(
                "history_namespace",
                "$root/history"
            ),
            filesNamespace = obj.optString(
                "files_namespace",
                "$root/files"
            ),
            tasksNamespace = obj.optString(
                "tasks_namespace",
                "$root/tasks"
            ),
            goalsNamespace = obj.optString(
                "goals_namespace",
                "$root/goals"
            ),
            checkpointsNamespace = obj.optString(
                "checkpoints_namespace",
                "$root/checkpoints"
            ),
            testsNamespace = obj.optString(
                "tests_namespace",
                "$root/tests"
            ),
            repoConfigNamespace = obj.optString(
                "repo_config_namespace",
                "$root/repo_config"
            ),
            mediaNamespace = obj.optString(
                "media_namespace",
                "$root/media"
            ),
            metadataVersion = obj.optInt(
                "metadata_version",
                METADATA_VERSION
            )
        )
    }

    private fun cleanName(value: String): String =
        value
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_NAME_CHARS)

    companion object {
        const val VERSION = "1.0"
        const val SCHEMA_VERSION = 1
        const val METADATA_VERSION = 1

        private const val FILE_NAME = "ayana_projects.json"
        private const val MAX_NAME_CHARS = 120
        private const val MAX_DESCRIPTION_CHARS = 1200
    }
}
