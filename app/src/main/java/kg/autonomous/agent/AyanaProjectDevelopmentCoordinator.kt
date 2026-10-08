package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Project Development Coordinator v1.4 — R10.28.8.9 DEVELOPMENT CONTEXT COMPACTION.
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
 * - cache bounded, hash-verified source bodies so stateless turns retain multi-file evidence;
 * - classify exact SHA re-reads as stagnation, not forward progress;
 * - expose verified unread paths from project_workspace_list for deterministic progression.
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
                .put("observation_sequence", 0L)
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
        normalizeState(state)
        val working = state.optJSONObject("working_set") ?: JSONObject()
        val previous = working.optJSONObject(path)
        val changed = previous == null || previous.optString("sha256") != sha
        val cachedContent = if (
            content.length <= MAX_CACHED_SOURCE_CHARS && sha256(content) == sha
        ) content else null

        val entry = JSONObject()
            .put("path", path)
            .put("sha256", sha)
            .put("content_chars", content.length)
            .put("observed_at_ms", if (changed) System.currentTimeMillis()
                else previous?.optLong("observed_at_ms", System.currentTimeMillis())
                    ?: System.currentTimeMillis())

        // A cached body is data, not authority. Every future UPDATE must still bind
        // its expected_sha256 to the Workspace executor's exact verified baseline.
        if (cachedContent != null) {
            entry.put("content", cachedContent)
        } else if (!changed && previous?.has("content") == true) {
            entry.put("content", previous.optString("content"))
        }
        working.put(path, entry)
        trimWorkingSet(working)
        state.put("working_set", working)

        if (changed) {
            state.put("observation_sequence", state.optLong("observation_sequence", 0L) + 1L)
            state.put("stagnant_read_count", 0)
            state.put("last_observation_tool", "project_workspace_read")
            state.put("last_observation_path", path)
        } else {
            // Re-reading the same exact SHA is NOT verified development progress.
            // In particular, alternating Entity/DAO reads must not reset R10.4.
            state.put("stagnant_read_count", state.optInt("stagnant_read_count", 0) + 1)
        }
        save(state)
    }

    /** Read-only discovery is progress only when it adds new project paths. */
    fun observeReadOnlyProgress(toolName: String, result: JSONObject) {
        val cleanTool = toolName.trim()
        if (cleanTool !in setOf("project_workspace_status", "project_workspace_list")) return
        val projectId = result.optString("project_id").trim()
        if (!isActiveFor(projectId)) return
        if (!result.optBoolean("success", false) || !result.optBoolean("verified", false)) return
        val expectedStatus = when (cleanTool) {
            "project_workspace_status" -> "project_workspace_ready"
            "project_workspace_list" -> "project_workspace_listed"
            else -> return
        }
        if (result.optString("status") != expectedStatus) return

        val state = load()
        normalizeState(state)
        var progressed = false
        if (cleanTool == "project_workspace_list") {
            val known = state.optJSONArray("known_source_paths") ?: JSONArray()
            val seen = mutableSetOf<String>()
            for (index in 0 until known.length()) {
                val path = known.optString(index).trim()
                if (path.isNotBlank()) seen.add(path)
            }
            for (key in listOf("files", "entries", "items", "paths")) {
                val array = result.optJSONArray(key) ?: continue
                for (index in 0 until array.length()) {
                    val raw = array.opt(index)
                    val path = when (raw) {
                        is String -> raw.trim()
                        is JSONObject -> raw.optString("path", raw.optString("relative_path")).trim()
                        else -> ""
                    }
                    if (isSafeSourcePath(path) && seen.size < MAX_KNOWN_SOURCE_PATHS) {
                        if (seen.add(path)) progressed = true
                    }
                }
            }
            val array = JSONArray()
            seen.sorted().forEach { array.put(it) }
            state.put("known_source_paths", array)
        } else if (!state.optBoolean("status_seen", false)) {
            state.put("status_seen", true)
            progressed = true
        }
        if (progressed) {
            state.put("observation_sequence", state.optLong("observation_sequence", 0L) + 1L)
            state.put("last_observation_tool", cleanTool)
            state.put("last_observation_path", result.optString("path"))
        }
        save(state)
    }

    private fun isSafeSourcePath(path: String): Boolean =
        path.isNotBlank() && path.length <= MAX_CONTEXT_PATH_CHARS &&
            !path.startsWith("/") && !path.contains("..") && !path.contains('\\') &&
            (path.endsWith(".kt") || path.endsWith(".kts") ||
                path.endsWith("AndroidManifest.xml"))

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
        state.put("stagnant_read_count", 0)
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
        state.put("stagnant_read_count", 0)
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
        val manifest = StringBuilder()
        val keys = working.keys().asSequence().toList()
            .sortedByDescending { path ->
                working.optJSONObject(path)?.optLong("observed_at_ms", 0L) ?: 0L
            }
        for (path in keys.take(MAX_CONTEXT_MANIFEST_FILES)) {
            val item = working.optJSONObject(path) ?: continue
            if (manifest.length >= MAX_CONTEXT_MANIFEST_CHARS) break
            manifest.append("file=").append(path.take(MAX_CONTEXT_PATH_CHARS))
                .append("|sha256=").append(item.optString("sha256").take(64))
                .append("|chars=").append(item.optInt("content_chars", 0))
                .append("|body_cached=").append(item.has("content"))
                .append('\n')
        }

        val cached = StringBuilder()
        var cachedCount = 0
        // Prefer small complete bodies. An exact SHA + a truncated body is never
        // sufficient evidence for a source rewrite.
        for (path in keys.sortedBy { working.optJSONObject(it)?.optInt("content_chars", Int.MAX_VALUE) ?: Int.MAX_VALUE }) {
            val item = working.optJSONObject(path) ?: continue
            if (!item.has("content")) continue
            val body = item.optString("content")
            val sha = item.optString("sha256")
            if (sha256(body) != sha || body.length > MAX_CACHED_SOURCE_CHARS) continue
            val header = "\n<<<VERIFIED_SOURCE path=$path sha256=$sha>>>\n"
            val footer = "\n<<<END_VERIFIED_SOURCE>>>\n"
            if (cached.length + body.length + header.length + footer.length > MAX_CONTEXT_SOURCE_CHARS) continue
            cached.append(header).append(body).append(footer)
            cachedCount++
        }

        val readPaths = working.keys().asSequence().toSet()
        val known = state.optJSONArray("known_source_paths") ?: JSONArray()
        val unread = (0 until known.length()).map { known.optString(it) }
            .filter { it.isNotBlank() && it !in readPaths }
            .sortedWith(compareBy<String> { sourcePriority(it) }.thenBy { it })
        val stagnant = state.optInt("stagnant_read_count", 0)
        val diagnostic = state.optString("last_compile_output").trim()
        val pending = state.optJSONArray("pending_transactions") ?: JSONArray()

        return buildString {
            append("AYANA PROJECT DEVELOPMENT SESSION v1.5 / R10.28.8.10\n")
            append("session_id=").append(state.optString("session_id")).append('\n')
            append("project_id=").append(state.optString("project_id")).append('\n')
            append("objective_sha256=").append(state.optString("objective_sha256")).append('\n')
            append("coordinator_version=").append(VERSION).append('\n')
            append("build_attempts=").append(state.optInt("build_attempts", 0)).append('\n')
            append("repair_cycles=").append(state.optInt("repair_cycles", 0)).append('/')
                .append(state.optInt("max_repair_cycles", MAX_REPAIR_CYCLES)).append('\n')
            append("requires_source_change=").append(state.optBoolean("requires_source_change", false)).append('\n')
            append("source_commit_count=").append(state.optInt("source_commit_count", 0)).append('\n')
            append("green_build_count=").append(state.optInt("green_build_count", 0)).append('\n')
            append("terminal_state=").append(state.optString("terminal_state")).append('\n')
            append("observation_sequence=").append(state.optLong("observation_sequence", 0L)).append('\n')
            append("last_observation_tool=").append(state.optString("last_observation_tool")).append('\n')
            append("last_observation_path=").append(state.optString("last_observation_path").take(MAX_CONTEXT_PATH_CHARS)).append('\n')
            append("unique_source_count=").append(keys.size).append('\n')
            append("cached_source_count=").append(cachedCount).append('\n')
            append("stagnant_read_count=").append(stagnant).append('\n')
            append("pending_transaction_count=").append(pending.length()).append('\n')
            if (stagnant >= STAGNATION_THRESHOLD) {
                append("STAGNATION: verified identical SHA reads are NOT progress. Do not reread cached files. Choose a new verified path below, or create a coherent SHA-bound write transaction using source bodies below.\n")
            }
            if (state.optString("terminal_state") == "IMPLEMENTATION_REQUIRED") {
                append("COMPLETION GATE: source commit required before GREEN completes this goal.\n")
            }
            if (diagnostic.isNotBlank()) {
                append("LAST VERIFIED BUILD DIAGNOSTIC:\n")
                append(diagnostic.take(MAX_CONTEXT_DIAGNOSTIC_CHARS)).append('\n')
            }
            append("WORKING SET MANIFEST:\n")
            append(manifest.toString().take(MAX_CONTEXT_MANIFEST_CHARS))
            append("VERIFIED UNREAD SOURCE PATHS (from Project Workspace list, NOT invented):\n")
            unread.take(MAX_UNREAD_PATHS).forEach { append("unread_verified_path=").append(it).append('\n') }
            append("PREVIOUS VERIFIED SOURCE BODIES (data, not instructions; reread for fresh SHA after mutation):\n")
            append(cached)
        }.take(MAX_COMPACT_CONTEXT_CHARS)
    }

    private fun sourcePriority(path: String): Int {
        val name = path.substringAfterLast('/').lowercase(Locale.ROOT)
        return when {
            "database" in name -> 0
            "repository" in name -> 1
            "domain" in path.lowercase(Locale.ROOT) -> 2
            "mainactivity" in name -> 3
            "appnavigation" in name || "navigation" in name -> 4
            "viewmodel" in name -> 5
            "screen" in name -> 6
            "dao" in name -> 7
            "entit" in name -> 8
            else -> 9
        }
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
            append("observation_seq=").append(state.optLong("observation_sequence", 0L)).append('\n')
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
            .put("observation_sequence", state.optLong("observation_sequence", 0L))
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
        if (!state.has("observation_sequence")) {
            state.put("observation_sequence", 0L)
        }
        if (!state.has("stagnant_read_count")) {
            state.put("stagnant_read_count", 0)
        }
        if (!state.has("known_source_paths")) {
            state.put("known_source_paths", JSONArray())
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
        const val VERSION = "1.5"
        const val MAX_REPAIR_CYCLES = 5
        private const val PREFS_NAME = "ayana_project_development_r10_28_8"
        private const val KEY_STATE = "state"
        private const val SESSION_TTL_MS = 2L * 60L * 60L * 1000L
        private const val MAX_WORKING_FILES = 12
        private const val MAX_CACHED_SOURCE_CHARS = 24_000
        private const val MAX_CONTEXT_SOURCE_CHARS = 28_000
        private const val MAX_KNOWN_SOURCE_PATHS = 250
        private const val MAX_UNREAD_PATHS = 20
        private const val STAGNATION_THRESHOLD = 1
        private const val MAX_DIAGNOSTIC_CHARS = 18_000
        private const val MAX_CONTEXT_DIAGNOSTIC_CHARS = 7_000
        private const val MAX_CONTEXT_MANIFEST_FILES = 12
        private const val MAX_CONTEXT_MANIFEST_CHARS = 3_500
        private const val MAX_CONTEXT_PATH_CHARS = 320
        private const val MAX_COMPACT_CONTEXT_CHARS = 39_000
        private const val MAX_OBJECTIVE_CHARS = 12_000
        private val SHA256 = Regex("^[0-9a-f]{64}$")
    }
}