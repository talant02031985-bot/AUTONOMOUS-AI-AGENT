package kg.autonomous.agent

import org.json.JSONObject

/**
 * AYANA Project Context Manager v1.0 — R10.28 PROJECTS.
 *
 * Pure scope / isolation contract layered over AyanaProjectStore.
 * It never reads another project's data by itself and never authorizes
 * cross-project access. Callers must explicitly request ALL_PROJECTS and prove
 * user intent before using another project's namespace.
 */
class AyanaProjectContextManager(
    private val projectStore: AyanaProjectStore
) {

    enum class SearchScope {
        CURRENT_PROJECT,
        GLOBAL,
        ALL_PROJECTS
    }

    data class Context(
        val projectId: String?,
        val projectName: String?,
        val scope: SearchScope,
        val memoryNamespace: String?,
        val historyNamespace: String?,
        val filesNamespace: String?,
        val tasksNamespace: String?,
        val goalsNamespace: String?,
        val checkpointsNamespace: String?,
        val testsNamespace: String?,
        val repoConfigNamespace: String?,
        val mediaNamespace: String?,
        val isProjectScoped: Boolean
    ) {
        fun toJson(): JSONObject =
            JSONObject()
                .put("project_id", projectId ?: JSONObject.NULL)
                .put("project_name", projectName ?: "")
                .put("scope", scope.name)
                .put("memory_namespace", memoryNamespace ?: "")
                .put("history_namespace", historyNamespace ?: "")
                .put("files_namespace", filesNamespace ?: "")
                .put("tasks_namespace", tasksNamespace ?: "")
                .put("goals_namespace", goalsNamespace ?: "")
                .put("checkpoints_namespace", checkpointsNamespace ?: "")
                .put("tests_namespace", testsNamespace ?: "")
                .put("repo_config_namespace", repoConfigNamespace ?: "")
                .put("media_namespace", mediaNamespace ?: "")
                .put("project_scoped", isProjectScoped)
    }

    data class AccessDecision(
        val allowed: Boolean,
        val reason: String,
        val sourceProjectId: String?,
        val targetProjectId: String?,
        val explicitCrossProjectIntent: Boolean
    )

    fun current(): Context {
        val active = projectStore.activeProject()
        return if (active == null) {
            global()
        } else {
            Context(
                projectId = active.projectId,
                projectName = active.name,
                scope = SearchScope.CURRENT_PROJECT,
                memoryNamespace = active.memoryNamespace,
                historyNamespace = active.historyNamespace,
                filesNamespace = active.filesNamespace,
                tasksNamespace = active.tasksNamespace,
                goalsNamespace = active.goalsNamespace,
                checkpointsNamespace = active.checkpointsNamespace,
                testsNamespace = active.testsNamespace,
                repoConfigNamespace = active.repoConfigNamespace,
                mediaNamespace = active.mediaNamespace,
                isProjectScoped = true
            )
        }
    }

    fun global(): Context =
        Context(
            projectId = null,
            projectName = null,
            scope = SearchScope.GLOBAL,
            memoryNamespace = GLOBAL_MEMORY_NAMESPACE,
            historyNamespace = GLOBAL_HISTORY_NAMESPACE,
            filesNamespace = GLOBAL_FILES_NAMESPACE,
            tasksNamespace = GLOBAL_TASKS_NAMESPACE,
            goalsNamespace = GLOBAL_GOALS_NAMESPACE,
            checkpointsNamespace = GLOBAL_CHECKPOINTS_NAMESPACE,
            testsNamespace = GLOBAL_TESTS_NAMESPACE,
            repoConfigNamespace = GLOBAL_REPO_CONFIG_NAMESPACE,
            mediaNamespace = GLOBAL_MEDIA_NAMESPACE,
            isProjectScoped = false
        )

    fun forProject(projectId: String): Context? {
        val project = projectStore.getById(projectId)
            ?.takeIf { !it.archived }
            ?: return null

        return Context(
            projectId = project.projectId,
            projectName = project.name,
            scope = SearchScope.CURRENT_PROJECT,
            memoryNamespace = project.memoryNamespace,
            historyNamespace = project.historyNamespace,
            filesNamespace = project.filesNamespace,
            tasksNamespace = project.tasksNamespace,
            goalsNamespace = project.goalsNamespace,
            checkpointsNamespace = project.checkpointsNamespace,
            testsNamespace = project.testsNamespace,
            repoConfigNamespace = project.repoConfigNamespace,
            mediaNamespace = project.mediaNamespace,
            isProjectScoped = true
        )
    }

    /**
     * Default search behavior:
     * - active project -> CURRENT_PROJECT
     * - no active project -> GLOBAL
     * ALL_PROJECTS is never inferred.
     */
    fun defaultSearchScope(): SearchScope =
        if (projectStore.activeProjectId() != null) {
            SearchScope.CURRENT_PROJECT
        } else {
            SearchScope.GLOBAL
        }

    fun resolveSearchContext(
        requestedScope: SearchScope,
        explicitAllProjectsIntent: Boolean
    ): Context? =
        when (requestedScope) {
            SearchScope.CURRENT_PROJECT -> current()
                .takeIf { it.isProjectScoped }

            SearchScope.GLOBAL -> global()

            SearchScope.ALL_PROJECTS ->
                if (explicitAllProjectsIntent) {
                    Context(
                        projectId = null,
                        projectName = null,
                        scope = SearchScope.ALL_PROJECTS,
                        memoryNamespace = null,
                        historyNamespace = null,
                        filesNamespace = null,
                        tasksNamespace = null,
                        goalsNamespace = null,
                        checkpointsNamespace = null,
                        testsNamespace = null,
                        repoConfigNamespace = null,
                        mediaNamespace = null,
                        isProjectScoped = false
                    )
                } else {
                    null
                }
        }

    /**
     * Cross-project data use is fail-closed.
     *
     * Same project: allowed.
     * Global -> project or project -> global: allowed only for explicitly
     * classified global settings/capabilities by the caller, not project data.
     * Different projects: requires explicit cross-project intent.
     */
    fun canAccessProjectData(
        sourceProjectId: String?,
        targetProjectId: String?,
        explicitCrossProjectIntent: Boolean
    ): AccessDecision {
        if (sourceProjectId == targetProjectId) {
            return AccessDecision(
                allowed = true,
                reason = "same_scope",
                sourceProjectId = sourceProjectId,
                targetProjectId = targetProjectId,
                explicitCrossProjectIntent = explicitCrossProjectIntent
            )
        }

        if (sourceProjectId == null || targetProjectId == null) {
            return AccessDecision(
                allowed = false,
                reason = "global_project_boundary_requires_domain_classification",
                sourceProjectId = sourceProjectId,
                targetProjectId = targetProjectId,
                explicitCrossProjectIntent = explicitCrossProjectIntent
            )
        }

        return if (explicitCrossProjectIntent) {
            AccessDecision(
                allowed = true,
                reason = "explicit_cross_project_intent",
                sourceProjectId = sourceProjectId,
                targetProjectId = targetProjectId,
                explicitCrossProjectIntent = true
            )
        } else {
            AccessDecision(
                allowed = false,
                reason = "cross_project_access_blocked",
                sourceProjectId = sourceProjectId,
                targetProjectId = targetProjectId,
                explicitCrossProjectIntent = false
            )
        }
    }

    fun canResumeGoal(
        goalProjectId: String?
    ): AccessDecision {
        val active = projectStore.activeProjectId()

        if (goalProjectId == null) {
            return AccessDecision(
                allowed = active == null,
                reason = if (active == null) {
                    "global_goal_in_global_scope"
                } else {
                    "global_goal_blocked_inside_project"
                },
                sourceProjectId = goalProjectId,
                targetProjectId = active,
                explicitCrossProjectIntent = false
            )
        }

        return AccessDecision(
            allowed = goalProjectId == active,
            reason = if (goalProjectId == active) {
                "goal_project_matches_active_project"
            } else {
                "goal_project_mismatch"
            },
            sourceProjectId = goalProjectId,
            targetProjectId = active,
            explicitCrossProjectIntent = false
        )
    }

    fun compactContextForAgent(): String {
        val context = current()
        return if (context.isProjectScoped) {
            "AYANA PROJECT CONTEXT v1: active_project_id=${context.projectId}; " +
                "active_project_name=${context.projectName}; " +
                "default_search_scope=CURRENT_PROJECT; " +
                "cross_project_access=explicit_only; " +
                "project_memory_namespace=${context.memoryNamespace}; " +
                "project_files_namespace=${context.filesNamespace}; " +
                "project_tasks_namespace=${context.tasksNamespace}; " +
                "project_goals_namespace=${context.goalsNamespace}; " +
                "project_media_namespace=${context.mediaNamespace}."
        } else {
            "AYANA PROJECT CONTEXT v1: active_project_id=none; " +
                "default_search_scope=GLOBAL; cross_project_access=explicit_only."
        }
    }

    fun selfTest(): Boolean {
        val same = canAccessProjectData("A", "A", false)
        val blocked = canAccessProjectData("A", "B", false)
        val explicit = canAccessProjectData("A", "B", true)
        val allBlocked = resolveSearchContext(
            SearchScope.ALL_PROJECTS,
            explicitAllProjectsIntent = false
        ) == null

        return same.allowed &&
            !blocked.allowed &&
            explicit.allowed &&
            allBlocked
    }

    companion object {
        const val VERSION = "1.0"

        const val GLOBAL_MEMORY_NAMESPACE = "global/memory"
        const val GLOBAL_HISTORY_NAMESPACE = "global/history"
        const val GLOBAL_FILES_NAMESPACE = "global/files"
        const val GLOBAL_TASKS_NAMESPACE = "global/tasks"
        const val GLOBAL_GOALS_NAMESPACE = "global/goals"
        const val GLOBAL_CHECKPOINTS_NAMESPACE = "global/checkpoints"
        const val GLOBAL_TESTS_NAMESPACE = "global/tests"
        const val GLOBAL_REPO_CONFIG_NAMESPACE = "global/repo_config"
        const val GLOBAL_MEDIA_NAMESPACE = "global/media"
    }
}
