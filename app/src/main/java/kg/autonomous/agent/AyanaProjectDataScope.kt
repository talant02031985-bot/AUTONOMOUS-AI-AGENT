package kg.autonomous.agent

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AYANA Project Data Scope v1.0 — R10.28.1.
 *
 * Creates physically isolated instances of existing accepted AYANA stores
 * without changing their implementations.
 *
 * Covered in this slice:
 * - memory
 * - command history
 * - tasks
 * - durable goals
 * - generic project files root
 *
 * Not covered here:
 * - alarm/reminder dispatch binding to project_id
 * - Personal Search scoping
 * - multimodal attachment ownership
 * - VoiceService routing
 * Those are integrated in subsequent R10.28 slices.
 */
class AyanaProjectDataScope(
    context: Context,
    private val projectStore: AyanaProjectStore
) {

    data class Stores(
        val projectId: String,
        val context: AyanaProjectScopedContext,
        val memory: AyanaMemoryStore,
        val history: AyanaCommandHistoryStore,
        val tasks: AyanaTaskStore,
        val durableGoals: AyanaDurableGoalStore,
        val filesRoot: File
    )

    private val appContext =
        context.applicationContext

    private val cache =
        ConcurrentHashMap<String, Stores>()

    fun currentProjectStores(): Stores? {
        val project =
            projectStore.activeProject()
                ?: return null

        return forProject(
            project.projectId
        )
    }

    fun forProject(
        projectId: String
    ): Stores? {
        val project =
            projectStore.getById(
                projectId
            )
                ?.takeIf {
                    !it.archived
                }
                ?: return null

        return cache.getOrPut(
            project.projectId
        ) {
            createStores(
                project.projectId
            )
        }
    }

    /**
     * Global stores remain exactly the legacy accepted stores and legacy paths.
     * This preserves backward compatibility for users who have no active project.
     */
    fun globalMemory(): AyanaMemoryStore =
        AyanaMemoryStore(
            appContext
        )

    fun globalHistory(): AyanaCommandHistoryStore =
        AyanaCommandHistoryStore(
            appContext
        )

    fun globalTasks(): AyanaTaskStore =
        AyanaTaskStore(
            appContext
        )

    fun globalDurableGoals(): AyanaDurableGoalStore =
        AyanaDurableGoalStore(
            appContext
        )

    fun projectFilesRoot(
        projectId: String
    ): File? =
        forProject(
            projectId
        )
            ?.filesRoot

    fun clearCachedProject(
        projectId: String
    ) {
        cache.remove(
            projectId
        )
    }

    fun clearAllCachedProjects() {
        cache.clear()
    }

    /**
     * Filesystem isolation proof only. No user data is mutated.
     */
    fun selfTest(): Boolean =
        try {
            val projects =
                projectStore
                    .list(
                        includeArchived = false
                    )

            if (projects.size < 2) {
                true
            } else {
                val a =
                    AyanaProjectScopedContext(
                        appContext,
                        projects[0].projectId
                    )

                val b =
                    AyanaProjectScopedContext(
                        appContext,
                        projects[1].projectId
                    )

                a.filesDir.canonicalPath !=
                    b.filesDir.canonicalPath &&
                    a.filesDir.canonicalPath.startsWith(
                        appContext.filesDir.canonicalPath
                    ) &&
                    b.filesDir.canonicalPath.startsWith(
                        appContext.filesDir.canonicalPath
                    )
            }
        } catch (_: Exception) {
            false
        }

    private fun createStores(
        projectId: String
    ): Stores {
        val scoped =
            AyanaProjectScopedContext(
                appContext,
                projectId
            )

        return Stores(
            projectId = projectId,
            context = scoped,
            memory =
                AyanaMemoryStore(
                    scoped
                ),
            history =
                AyanaCommandHistoryStore(
                    scoped
                ),
            tasks =
                AyanaTaskStore(
                    scoped
                ),
            durableGoals =
                AyanaDurableGoalStore(
                    scoped
                ),
            filesRoot =
                scoped.childDir(
                    "files"
                )
        )
    }

    companion object {
        const val VERSION = "1.0"
    }
}
