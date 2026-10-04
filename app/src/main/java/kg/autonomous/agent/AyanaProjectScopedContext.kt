package kg.autonomous.agent

import android.content.Context
import android.content.ContextWrapper
import java.io.File

/**
 * AYANA Project Scoped Context v1.0 — R10.28.1.
 *
 * Existing AYANA stores resolve their durable files through
 * context.applicationContext.filesDir. This wrapper deliberately returns itself
 * as applicationContext and exposes a project-private filesDir, allowing the
 * accepted stores to remain unchanged while their data is physically isolated.
 *
 * Project names are never used as filesystem paths. Only opaque project_id is
 * admitted after strict normalization.
 */
class AyanaProjectScopedContext(
    baseContext: Context,
    projectId: String
) : ContextWrapper(baseContext.applicationContext) {

    val projectId: String =
        normalizeProjectId(projectId)

    private val baseFilesDir: File =
        baseContext.applicationContext.filesDir

    private val scopedFilesDir: File by lazy {
        File(
            File(baseFilesDir, PROJECTS_DIR_NAME),
            this.projectId
        ).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException(
                    "Unable to create project files directory"
                )
            }
        }
    }

    private val scopedCacheDir: File by lazy {
        File(
            File(baseContext.applicationContext.cacheDir, PROJECTS_DIR_NAME),
            this.projectId
        ).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException(
                    "Unable to create project cache directory"
                )
            }
        }
    }

    /**
     * Critical: AYANA stores often call context.applicationContext before
     * resolving filesDir. Returning this wrapper preserves project scoping.
     */
    override fun getApplicationContext(): Context =
        this

    override fun getFilesDir(): File =
        scopedFilesDir

    override fun getCacheDir(): File =
        scopedCacheDir

    fun rootDir(): File =
        scopedFilesDir

    fun cacheRootDir(): File =
        scopedCacheDir

    fun childDir(name: String): File {
        val safeName =
            name
                .trim()
                .lowercase()
                .replace(Regex("[^a-z0-9_-]"), "_")
                .trim('_')
                .take(64)

        require(safeName.isNotBlank()) {
            "Project child directory name is empty"
        }

        return File(scopedFilesDir, safeName).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException(
                    "Unable to create project child directory"
                )
            }
        }
    }

    companion object {
        const val VERSION = "1.0"
        private const val PROJECTS_DIR_NAME = "ayana_project_data"

        fun normalizeProjectId(value: String): String {
            val clean =
                value
                    .trim()
                    .lowercase()

            require(
                clean.matches(
                    Regex("^[a-z0-9][a-z0-9_-]{7,127}$")
                )
            ) {
                "Invalid project_id"
            }

            return clean
        }
    }
}
