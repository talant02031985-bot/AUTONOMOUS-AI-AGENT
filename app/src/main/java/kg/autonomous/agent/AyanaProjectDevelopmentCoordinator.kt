package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Project Development Coordinator v1.2 — R10.28.8.4.
 *
 * Bounded authority for one explicitly requested autonomous Project development objective.
 * The user's explicit "develop/build to GREEN" command is the session authority. It never
 * grants access outside the frozen active Project and it never grants secret/.git/workflow
 * mutation. Workspace and Project Build Bridge keep enforcing their own path/repository guards.
 *
 * Responsibilities:
 * - persist one active development objective per frozen Project;
 * - keep a bounded multi-file working set across stateless Agent Core continuations;
 * - keep the latest compile/build diagnostic;
 * - count repair/build cycles and stop after MAX_REPAIR_CYCLES;
 * - remember workspace transactions created by the session so they can be accepted only after
 *   a verified GREEN APK artifact;
 * - provide a compact trusted context for Agent Core after every tool step.
 */
class AyanaProjectDevelopmentCoordinator(
    context: Context,
    private val boundProjectIdProvider: (() -> String?)? = null
) {
    private val prefs =
        context.applicationContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun maybeStartExplicitSession(command: String): JSONObject? {
        val projectId = boundProjectId().orEmpty()
        if (projectId.isBlank()) return null
        if (!isExplicitAutonomousDevelopmentCommand(command)) return null

        val now = System.currentTimeMillis()
        val current = load()
        if (
            current.optBoolean("active", false) &&
            current.optString("project_id") == projectId &&
            now < current.optLong("expires_at_ms", 0L)
        ) {
            normalizeState(current)
            save(current)
            return publicState(current)
        }

        val objective = command.trim().take(MAX_OBJECTIVE_CHARS)
        val requiresSourceChange = requiresSourceChangeEvidence(objective)
        val session =
            JSONObject()
                .put("version", VERSION)
                .put("active", true)
                .put("session_id", "pds-${now.toString(36)}-${sha256(objective).take(12)}")
                .put("project_id", projectId)
                .put("objective", objective)
                .put("objective_sha256", sha256(objective))
                .put("started_at_ms", now)
                .put("expires_at_ms", now + SESSION_TTL_MS)
                .put("build_attempts", 0)
                .put("repair_cycles", 0)
                .put("max_repair_cycles", MAX_REPAIR_CYCLES)
                .put("requires_source_change", requiresSourceChange)
                .put("source_commit_count", 0)
                .put("green_build_count", 0)
                .put("working_set", JSONObject())
                .put("pending_transactions", JSONArray())
                .put("last_compile_output", "")
                .put("last_build_status", "")
                .put("last_build_conclusion", "")
                .put("last_run_id", 0L)
                .put("terminal_state", "ACTIVE")
        save(session)
        return publicState(session)
    }

    fun isActiveFor(projectId: String?): Boolean {
        val expected = projectId.orEmpty().trim()
        if (expected.isBlank()) return false
        val state = load()
        if (!state.optBoolean("active", false)) return false
        if (state.optString("project_id") != expected) return false
        if (System.currentTimeMillis() >= state.optLong("expires_at_ms", 0L)) {
            state.put("active", false).put("terminal_state", "EXPIRED")
            save(state)
            return false
        }
        return state.optInt("repair_cycles", 0) < state.optInt("max_repair_cycles", MAX_REPAIR_CYCLES)
    }

    fun observeWorkspaceRead(result: JSONObject) {
        if (!isActiveFor(result.optString("project_id"))) return
        if (!result.optBoolean("success", false) || !result.optBoolean("verified", false)) return
        if (result.optString("status") != "project_workspace_file_read") return

        val path = result.optString("path").trim()
        val sha = result.optString("sha256").trim().lowercase(Locale.ROOT)
        val content = result.optString("content")
        if (path.isBlank() || !SHA256.matches(sha) || result.optBoolean("truncated", false)) return

        val state = load()
        val working = state.optJSONObject("working_set") ?: JSONObject()
        working.put(
            path,
            JSONObject()
                .put("path", path)
                .put("sha256", sha)
                .put("content", content.take(MAX_WORKING_FILE_CHARS))
                .put("observed_at_ms", System.currentTimeMillis())
        )
        trimWorkingSet(working)
        state.put("working_set", working)
        save(state)
    }

    fun observeWorkspaceCommit(result: JSONObject) {
        if (!isActiveFor(result.optString("project_id"))) return
        if (
            !result.optBoolean("success", false) ||
            !result.optBoolean("verified", false) ||
            result.optString("status") != AyanaProjectWorkspaceExecutor.TX_COMMITTED
        ) return

        val txId = result.optString("transaction_id").trim()
        if (txId.isBlank()) return
        val state = load()
        normalizeState(state)
        val pending = state.optJSONArray("pending_transactions") ?: JSONArray()
        val alreadyObserved = (0 until pending.length()).any { pending.optString(it) == txId }
        if (!alreadyObserved) {
            pending.put(txId)
            state.put("source_commit_count", state.optInt("source_commit_count", 0) + 1)
        }
        state.put("pending_transactions", pending)
        if (state.optBoolean("requires_source_change", false)) {
            state.put("terminal_state", "IMPLEMENTED_PENDING_BUILD")
        }
        save(state)
    }

    fun observeBuild(result: JSONObject): JSONObject {
        val projectId = result.optString("project_id").trim()
        if (!isActiveFor(projectId)) return publicState(load())

        val state = load()
        normalizeState(state)
        state.put("build_attempts", state.optInt("build_attempts", 0) + 1)
        state.put("last_build_status", result.optString("build_status", result.optString("status")))
        state.put("last_build_conclusion", result.optString("build_conclusion"))
        state.put("last_run_id", result.optLong("run_id", 0L))

        val green =
            result.optBoolean("success", false) &&
                result.optBoolean("verified", false) &&
                result.optString("status") == "verified_apk_build" &&
                result.optString("build_conclusion") == "success" &&
                result.optBoolean("artifact_verified", false)

        if (green) {
            state.put("green_build_count", state.optInt("green_build_count", 0) + 1)
            state.put("last_compile_output", "")

            val requiresSourceChange = state.optBoolean("requires_source_change", false)
            val sourceCommitCount = state.optInt("source_commit_count", 0)
            if (requiresSourceChange && sourceCommitCount <= 0) {
                // A GREEN baseline proves only that the pre-existing project compiles.
                // It is NOT proof that an explicit implementation objective was performed.
                state.put("terminal_state", "IMPLEMENTATION_REQUIRED")
                save(state)
                return publicState(state)
                    .put("green", false)
                    .put("baseline_green", true)
                    .put("implementation_required", true)
                    .put("development_goal_complete", false)
            }

            state.put("terminal_state", "GREEN")
            save(state)
            return publicState(state)
                .put("green", true)
                .put("baseline_green", false)
                .put("implementation_required", false)
                .put("development_goal_complete", true)
        }

        val diagnostic = result.optString("compile_output").trim()
        if (result.optString("status") == "project_apk_build_failed" && diagnostic.isNotBlank()) {
            val cycles = state.optInt("repair_cycles", 0) + 1
            state.put("repair_cycles", cycles)
            state.put("last_compile_output", diagnostic.take(MAX_DIAGNOSTIC_CHARS))
            if (cycles >= state.optInt("max_repair_cycles", MAX_REPAIR_CYCLES)) {
                state.put("active", false)
                state.put("terminal_state", "REPAIR_LIMIT_REACHED")
            } else {
                state.put("terminal_state", "REPAIR_REQUIRED")
            }
        }
        save(state)
        return publicState(state)
    }

    fun pendingTransactionIds(): List<String> {
        val state = load()
        val array = state.optJSONArray("pending_transactions") ?: JSONArray()
        return (0 until array.length())
            .mapNotNull { array.optString(it).trim().takeIf(String::isNotBlank) }
            .distinct()
    }

    fun markTransactionsAccepted(ids: Collection<String>) {
        if (ids.isEmpty()) return
        val state = load()
        val existing = state.optJSONArray("pending_transactions") ?: JSONArray()
        val keep = JSONArray()
        for (i in 0 until existing.length()) {
            val value = existing.optString(i)
            if (value !in ids) keep.put(value)
        }
        state.put("pending_transactions", keep)
        save(state)
    }

    fun finishGreen() {
        val state = load()
        state.put("active", false)
        state.put("terminal_state", "GREEN")
        save(state)
    }

    fun compactContext(): String {
        val state = load()
        if (!state.optBoolean("active", false) && state.optString("terminal_state") != "REPAIR_REQUIRED") {
            return "AYANA PROJECT DEVELOPMENT SESSION: inactive"
        }

        val working = state.optJSONObject("working_set") ?: JSONObject()
        val files = StringBuilder()
        val keys = working.keys().asSequence().toList().sorted()
        for (path in keys) {
            val item = working.optJSONObject(path) ?: continue
            if (files.length >= MAX_CONTEXT_CHARS) break
            files.append("\n--- FILE: ").append(path).append("\n")
            files.append("SHA256: ").append(item.optString("sha256")).append("\n")
            files.append(item.optString("content")).append("\n")
        }

        return buildString {
            append("AYANA PROJECT DEVELOPMENT SESSION v1.2 / R10.28.8.4\n")
            append("session_id=").append(state.optString("session_id")).append('\n')
            append("project_id=").append(state.optString("project_id")).append('\n')
            append("objective=").append(state.optString("objective")).append('\n')
            append("coordinator_version=").append(VERSION).append('\n')
            append("build_attempts=").append(state.optInt("build_attempts", 0)).append('\n')
            append("repair_cycles=").append(state.optInt("repair_cycles", 0)).append('/')
                .append(state.optInt("max_repair_cycles", MAX_REPAIR_CYCLES)).append('\n')
            append("requires_source_change=").append(state.optBoolean("requires_source_change", false)).append('\n')
            append("source_commit_count=").append(state.optInt("source_commit_count", 0)).append('\n')
            append("green_build_count=").append(state.optInt("green_build_count", 0)).append('\n')
            append("terminal_state=").append(state.optString("terminal_state")).append('\n')
            if (state.optString("terminal_state") == "IMPLEMENTATION_REQUIRED") {
                append("COMPLETION GATE: baseline APK is GREEN, but this objective explicitly requires source implementation and no source transaction has been committed in this session. Continue inspecting the requested implementation surface, write the required source changes, then rebuild. Do not return final success.\n")
            }
            val diagnostic = state.optString("last_compile_output")
            if (diagnostic.isNotBlank()) {
                append("LAST VERIFIED BUILD DIAGNOSTIC:\n")
                append(diagnostic).append('\n')
            }
            append("PERSISTENT WORKING SET:")
            append(files.toString().take(MAX_CONTEXT_CHARS))
        }.take(MAX_CONTEXT_CHARS + MAX_DIAGNOSTIC_CHARS + 4000)
    }

    /**
     * Stable development-progress fingerprint used by the outer adaptive loop.
     * Android screen state is irrelevant for Workspace/GitHub development tools;
     * progress is defined by the frozen development session, observed source SHAs,
     * committed source transactions and build/repair counters.
     */
    fun progressFingerprint(): String {
        val state = load()
        normalizeState(state)

        val working = state.optJSONObject("working_set") ?: JSONObject()
        val workingKeys = working.keys().asSequence().toList().sorted()
        val pending = state.optJSONArray("pending_transactions") ?: JSONArray()

        val canonical = buildString {
            append("session=").append(state.optString("session_id")).append('\n')
            append("project=").append(state.optString("project_id")).append('\n')
            append("terminal=").append(state.optString("terminal_state")).append('\n')
            append("source_commits=").append(state.optInt("source_commit_count", 0)).append('\n')
            append("build_attempts=").append(state.optInt("build_attempts", 0)).append('\n')
            append("repair_cycles=").append(state.optInt("repair_cycles", 0)).append('\n')
            append("green_builds=").append(state.optInt("green_build_count", 0)).append('\n')
            append("last_run=").append(state.optLong("last_run_id", 0L)).append('\n')
            append("last_build_status=").append(state.optString("last_build_status")).append('\n')
            append("last_build_conclusion=").append(state.optString("last_build_conclusion")).append('\n')

            for (path in workingKeys) {
                val item = working.optJSONObject(path) ?: continue
                append("file=").append(path).append('|')
                    .append(item.optString("sha256")).append('\n')
            }

            for (index in 0 until pending.length()) {
                append("tx=").append(pending.optString(index)).append('\n')
            }

            val diagnostic = state.optString("last_compile_output")
            if (diagnostic.isNotBlank()) {
                append("diagnostic=").append(sha256(diagnostic)).append('\n')
            }
        }

        return "dev:${sha256(canonical).take(32)}"
    }

    private fun publicState(state: JSONObject): JSONObject =
        JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("session_id", state.optString("session_id"))
            .put("project_id", state.optString("project_id"))
            .put("active", state.optBoolean("active", false))
            .put("build_attempts", state.optInt("build_attempts", 0))
            .put("repair_cycles", state.optInt("repair_cycles", 0))
            .put("max_repair_cycles", state.optInt("max_repair_cycles", MAX_REPAIR_CYCLES))
            .put("requires_source_change", state.optBoolean("requires_source_change", false))
            .put("source_commit_count", state.optInt("source_commit_count", 0))
            .put("green_build_count", state.optInt("green_build_count", 0))
            .put("terminal_state", state.optString("terminal_state"))
            .put("pending_transaction_count", (state.optJSONArray("pending_transactions") ?: JSONArray()).length())

    private fun trimWorkingSet(working: JSONObject) {
        val keys = working.keys().asSequence().toMutableList()
        if (keys.size <= MAX_WORKING_FILES) return
        keys.sortBy { working.optJSONObject(it)?.optLong("observed_at_ms", 0L) ?: 0L }
        keys.take(keys.size - MAX_WORKING_FILES).forEach { working.remove(it) }
    }

    private fun boundProjectId(): String? =
        try {
            boundProjectIdProvider?.invoke()?.trim()?.takeIf(String::isNotBlank)
        } catch (_: Exception) {
            null
        }

    private fun load(): JSONObject =
        try {
            JSONObject(prefs.getString(KEY_STATE, "{}") ?: "{}")
        } catch (_: Exception) {
            JSONObject()
        }

    private fun save(state: JSONObject) {
        prefs.edit().putString(KEY_STATE, state.toString()).commit()
    }

    private fun isExplicitAutonomousDevelopmentCommand(command: String): Boolean {
        val n = command.lowercase(Locale.ROOT).replace('ё', 'е').replace(Regex("\\s+"), " ").trim()
        if (n.startsWith("ayana_development_session_continue")) return false
        val develop = Regex("(?:разработай|разработать|доведи|доделай|реализуй|создай|собери|исправь).*(?:приложени|проект|apk|android|workspace|store accounting)").containsMatchIn(n)
        val autonomous = Regex("(?:до green|до успешн|до рабоч|до готов|сам[ао]? исправ|автоном|самостоятель|по тз|тех(?:ническ)?[а-я ]*задан)").containsMatchIn(n)
        return develop && autonomous
    }


    private fun normalizeState(state: JSONObject) {
        if (!state.has("requires_source_change")) {
            state.put(
                "requires_source_change",
                requiresSourceChangeEvidence(state.optString("objective"))
            )
        }
        if (!state.has("source_commit_count")) {
            state.put(
                "source_commit_count",
                (state.optJSONArray("pending_transactions") ?: JSONArray()).length()
            )
        }
        if (!state.has("green_build_count")) {
            state.put("green_build_count", 0)
        }
    }

    private fun requiresSourceChangeEvidence(command: String): Boolean {
        val n = command
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")
            .trim()

        // Deliberately excludes a pure "доведи/собери до GREEN" request: an already
        // corrected workspace may legitimately need only verification. Explicit
        // implementation verbs, however, require at least one verified source commit
        // in the current development session before GREEN can satisfy the objective.
        return Regex(
            "(?:^|\\s)(?:реализуй|реализовать|разработай|разработать|доделай|доделать|добавь|добавить|создай|создать|исправь|исправить)(?=\\s|$|[?.!,;:—-])"
        ).containsMatchIn(n)
    }

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val VERSION = "1.2"
        const val MAX_REPAIR_CYCLES = 5
        private const val PREFS_NAME = "ayana_project_development_r10_28_8"
        private const val KEY_STATE = "state"
        private const val SESSION_TTL_MS = 2L * 60L * 60L * 1000L
        private const val MAX_WORKING_FILES = 12
        private const val MAX_WORKING_FILE_CHARS = 48_000
        private const val MAX_DIAGNOSTIC_CHARS = 18_000
        private const val MAX_CONTEXT_CHARS = 110_000
        private const val MAX_OBJECTIVE_CHARS = 12_000
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}
