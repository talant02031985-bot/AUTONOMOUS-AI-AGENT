package kg.autonomous.agent

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AYANA Project Data Scope v1.1 — R10.28.2.
 *
 * Physically isolates accepted AYANA stores by giving each project a dedicated
 * Android Context/filesDir. Global stores keep their original legacy paths.
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

    private val globalMemoryStore by lazy {
        AyanaMemoryStore(
            appContext
        )
    }

    private val globalHistoryStore by lazy {
        AyanaCommandHistoryStore(
            appContext
        )
    }

    private val globalTaskStore by lazy {
        AyanaTaskStore(
            appContext
        )
    }

    private val globalDurableGoalStore by lazy {
        AyanaDurableGoalStore(
            appContext
        )
    }

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

    fun globalMemory(): AyanaMemoryStore =
        globalMemoryStore

    fun globalHistory(): AyanaCommandHistoryStore =
        globalHistoryStore

    fun globalTasks(): AyanaTaskStore =
        globalTaskStore

    fun globalDurableGoals(): AyanaDurableGoalStore =
        globalDurableGoalStore

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
        const val VERSION = "1.1"
    }
}
