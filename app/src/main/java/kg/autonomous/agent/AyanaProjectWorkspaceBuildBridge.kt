package kg.autonomous.agent

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AYANA Project Workspace Build Bridge v1.1 — R10.28.9.1.
 *
 * Builds ONLY the project bound to the current command. The project snapshot is
 * pushed to a dedicated GitHub repository derived from the project name
 * (for example STORE ACCOUNTING -> talant02031985-bot/STORE-ACCOUNTING).
 *
 * The fixed AYANA repository is explicitly denied.
 *
 * Flow:
 * PREPARE (read-only):
 *   frozen project -> exact local snapshot SHA -> dedicated repository truth
 *   -> requires_confirmation=true
 *
 * CONFIRMED:
 *   re-attest project + snapshot + repository head -> create exact Git tree
 *   -> commit to dedicated repo/main -> wait for exact push-triggered workflow
 *   -> require completed success + verified APK artifact digest.
 *
 * No GitHub token is exposed to Worker, model, History or result payloads.
 */
class AyanaProjectWorkspaceBuildBridge(
    context: Context,
    private val githubRepositoryExecutor: AyanaGitHubRepositoryExecutor,
    private val boundProjectIdProvider: () -> String?
) {
    private val appContext = context.applicationContext

    private val projectStore by lazy {
        AyanaProjectStore(appContext)
    }

    private val projectDataScope by lazy {
        AyanaProjectDataScope(appContext, projectStore)
    }

    private val githubPrefs by lazy {
        appContext.getSharedPreferences(
            GITHUB_PREFS_NAME,
            Context.MODE_PRIVATE
        )
    }

    data class SnapshotFile(
        val path: String,
        val content: String,
        val sha256: String,
        val sizeBytes: Int
    )

    data class Snapshot(
        val projectId: String,
        val projectName: String,
        val repository: String,
        val files: List<SnapshotFile>,
        val manifestSha256: String,
        val totalBytes: Long
    )

    fun build(
        arguments: JSONObject,
        confirmed: Boolean,
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        return if (!confirmed) {
            prepareBuild()
        } else {
            confirmBuild(
                arguments = arguments,
                shouldCancel = shouldCancel
            )
        }
    }

    private fun prepareBuild(): JSONObject {
        val scope = resolveBoundProject()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val projectId = scope.optString("project_id").trim()
        val projectName = scope.optString("project_name").trim()
        val root = File(scope.optString("root_path"))
        val repository = repositoryForProject(projectName)

        if (!isDedicatedRepository(repository)) {
            return failure(
                "Project build остановлен: dedicated repository пересекается с репозиторием AYANA."
            )
                .put("status", "project_build_repository_scope_rejected")
                .put("project_id", projectId)
                .put("repository", repository)
        }

        val snapshotResult = createSnapshot(
            projectId = projectId,
            projectName = projectName,
            repository = repository,
            root = root
        )

        if (!snapshotResult.optBoolean("success", false)) {
            return snapshotResult
        }

        val snapshot = snapshotFromResult(snapshotResult)
            ?: return failure("Не удалось восстановить локальный project snapshot.")
                .put("status", "project_build_snapshot_invalid")

        val tokenResult = usableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
                .put("project_id", projectId)
                .put("repository", repository)
        }

        val accessToken = tokenResult.optString("access_token")
        val repoState = repositoryState(
            accessToken = accessToken,
            repository = repository
        )

        if (!repoState.optBoolean("success", false)) {
            return repoState
                .put("project_id", projectId)
                .put("project_name", projectName)
                .put("workspace_manifest_sha256", snapshot.manifestSha256)
        }

        if (!repoState.optBoolean("push", false)) {
            return failure(
                "GitHub App видит $repository, но не имеет Contents write для project build."
            )
                .put("status", "project_build_contents_write_required")
                .put("project_id", projectId)
                .put("repository", repository)
                .put("requires_confirmation", false)
        }

        val headSha = repoState.optString("head_sha").trim()
        val headToken =
            if (headSha.isBlank()) {
                EMPTY_HEAD_TOKEN
            } else {
                headSha
            }

        val buildId =
            "pwb-" +
                System.currentTimeMillis().toString(36) +
                "-" +
                UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "BLOCKED")
            .put("status", "build_prepared_waiting_confirmation")
            .put("project_workspace_build", true)
            .put("project_id", projectId)
            .put("project_name", projectName)
            .put("repository", repository)
            .put("branch", BRANCH)
            .put("build_id", buildId)
            // Compatibility fields consumed by the existing durable confirmation path.
            .put("workflow_id", PROJECT_WORKFLOW_BINDING_ID)
            .put("workflow_name", AyanaGitHubRepositoryExecutor.BUILD_WORKFLOW_NAME)
            .put("head_sha", headToken)
            .put("repository_head_sha", headSha)
            .put("workspace_manifest_sha256", snapshot.manifestSha256)
            .put("workspace_file_count", snapshot.files.size)
            .put("workspace_total_bytes", snapshot.totalBytes)
            .put("requires_confirmation", true)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put(
                "message",
                "Project Workspace build подготовлен для $projectName: " +
                    "${snapshot.files.size} файлов, repository=$repository. " +
                    "GitHub mutation и сборка ещё НЕ запущены; требуется отдельное подтверждение."
            )
    }

    private fun confirmBuild(
        arguments: JSONObject,
        shouldCancel: () -> Boolean
    ): JSONObject {
        if (shouldCancel()) {
            return cancelled("Project build отменён до GitHub mutation.")
        }

        val expectedProjectId =
            arguments.optString("_project_workspace_project_id").trim()
        val expectedRepository =
            arguments.optString("_project_workspace_repository").trim()
        val expectedManifest =
            arguments.optString("_project_workspace_manifest_sha256")
                .trim()
                .lowercase(Locale.ROOT)
        val expectedBuildId =
            arguments.optString("_project_workspace_build_id").trim()
        val expectedHeadToken =
            arguments.optString("_github_build_head_sha").trim()

        if (
            expectedProjectId.isBlank() ||
            expectedRepository.isBlank() ||
            expectedBuildId.isBlank() ||
            !SHA256_HEX.matches(expectedManifest) ||
            expectedHeadToken.isBlank()
        ) {
            return failure(
                "Prepared Project Workspace build proof повреждён. Нужен новый PREPARE."
            )
                .put("status", "project_build_prepared_proof_invalid")
        }

        val scope = resolveBoundProject()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val projectId = scope.optString("project_id").trim()
        val projectName = scope.optString("project_name").trim()
        val root = File(scope.optString("root_path"))
        val repository = repositoryForProject(projectName)

        if (
            projectId != expectedProjectId ||
            repository != expectedRepository ||
            !isDedicatedRepository(repository)
        ) {
            return failure(
                "Project build scope изменился после PREPARE; GitHub mutation не выполнялась."
            )
                .put("status", "project_build_scope_drift")
                .put("project_id", projectId)
                .put("repository", repository)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        val snapshotResult = createSnapshot(
            projectId = projectId,
            projectName = projectName,
            repository = repository,
            root = root
        )

        if (!snapshotResult.optBoolean("success", false)) {
            return snapshotResult
        }

        val snapshot = snapshotFromResult(snapshotResult)
            ?: return failure("Не удалось восстановить project snapshot перед commit.")
                .put("status", "project_build_snapshot_invalid")

        if (!snapshot.manifestSha256.equals(expectedManifest, ignoreCase = true)) {
            return failure(
                "Project Workspace изменился после PREPARE. Сборка не запущена; подготовьте build заново."
            )
                .put("status", "project_build_workspace_drift")
                .put("project_id", projectId)
                .put("repository", repository)
                .put("expected_manifest_sha256", expectedManifest)
                .put("actual_manifest_sha256", snapshot.manifestSha256)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        val tokenResult = usableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
        }
        val accessToken = tokenResult.optString("access_token")

        val repoState = repositoryState(
            accessToken = accessToken,
            repository = repository
        )
        if (!repoState.optBoolean("success", false)) {
            return repoState
        }

        if (!repoState.optBoolean("push", false)) {
            return failure(
                "Contents write authority для $repository больше не подтверждена."
            )
                .put("status", "project_build_contents_write_required")
        }

        val currentHeadSha = repoState.optString("head_sha").trim()
        val currentHeadToken =
            if (currentHeadSha.isBlank()) EMPTY_HEAD_TOKEN else currentHeadSha

        if (currentHeadToken != expectedHeadToken) {
            return failure(
                "Dedicated project repository изменился после PREPARE. Build остановлен до mutation."
            )
                .put("status", "project_build_repository_head_drift")
                .put("repository", repository)
                .put("expected_head_sha", expectedHeadToken)
                .put("actual_head_sha", currentHeadToken)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        if (shouldCancel()) {
            return cancelled("Project build отменён до commit.")
        }

        val commitResult = pushExactSnapshot(
            accessToken = accessToken,
            snapshot = snapshot,
            parentHeadSha = currentHeadSha,
            buildId = expectedBuildId
        )

        if (!commitResult.optBoolean("success", false)) {
            return commitResult
                .put("project_workspace_build", true)
                .put("project_id", projectId)
                .put("repository", repository)
                .put("build_id", expectedBuildId)
                .put("workspace_manifest_sha256", snapshot.manifestSha256)
        }

        val commitSha = commitResult.optString("commit_sha").trim()
        if (commitSha.isBlank()) {
            return failure("GitHub commit не вернул SHA.")
                .put("status", "project_build_commit_sha_missing")
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "UNKNOWN")
        }

        val buildResult = waitForBuild(
            accessToken = accessToken,
            repository = repository,
            commitSha = commitSha,
            shouldCancel = shouldCancel
        )

        val common =
            JSONObject(buildResult.toString())
                .put("project_workspace_build", true)
                .put("project_id", projectId)
                .put("project_name", projectName)
                .put("repository", repository)
                .put("branch", BRANCH)
                .put("build_id", expectedBuildId)
                .put("workspace_manifest_sha256", snapshot.manifestSha256)
                .put("source_commit_sha", commitSha)
                .put("workflow_id", PROJECT_WORKFLOW_BINDING_ID)
                .put("workflow_name", AyanaGitHubRepositoryExecutor.BUILD_WORKFLOW_NAME)
                .put("head_sha", expectedHeadToken)

        if (!buildResult.optBoolean("success", false)) {
            return common
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put(
                    "reconciliation_complete",
                    buildResult.optBoolean("reconciliation_complete", true)
                )
                .put(
                    "side_effect_state",
                    if (buildResult.optBoolean("reconciliation_complete", true)) {
                        "VERIFIED_COMMITTED"
                    } else {
                        "UNKNOWN"
                    }
                )
                .put("side_effect_kind", SIDE_EFFECT_KIND)
        }

        return common
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "verified_apk_build")
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", SIDE_EFFECT_KIND)
            .put(
                "message",
                "Project Workspace APK build подтверждён: project=$projectName; " +
                    "repository=$repository; run=${buildResult.optLong("run_id")}; " +
                    "artifact=$PROJECT_ARTIFACT_NAME."
            )
    }

    private fun resolveBoundProject(): JSONObject {
        val projectId =
            try {
                boundProjectIdProvider()
                    ?.trim()
                    .orEmpty()
            } catch (_: Exception) {
                ""
            }

        if (projectId.isBlank()) {
            return failure(
                "Project Workspace build требует frozen project scope текущей команды."
            )
                .put("status", "project_build_no_bound_project")
                .put("scope", "GLOBAL")
        }

        val project =
            try {
                projectStore.getById(projectId)
            } catch (_: Exception) {
                null
            }
                ?: return failure("Проект build scope не найден.")
                    .put("status", "project_build_project_not_found")
                    .put("project_id", projectId)

        if (project.archived) {
            return failure("Архивированный проект нельзя собирать.")
                .put("status", "project_build_project_archived")
                .put("project_id", projectId)
        }

        val stores =
            projectDataScope.forProject(projectId)
                ?: return failure("Project Workspace filesRoot недоступен.")
                    .put("status", "project_build_workspace_unavailable")
                    .put("project_id", projectId)

        val root =
            try {
                stores.filesRoot.canonicalFile
            } catch (_: Exception) {
                return failure("Project Workspace root не канонизирован.")
                    .put("status", "project_build_workspace_path_invalid")
            }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("project_id", project.projectId)
            .put("project_name", project.name)
            .put("root_path", root.absolutePath)
    }

    private fun createSnapshot(
        projectId: String,
        projectName: String,
        repository: String,
        root: File
    ): JSONObject {
        if (!root.exists() || !root.isDirectory) {
            return failure("Project Workspace пуст или не существует.")
                .put("status", "project_build_workspace_missing")
        }

        val files = mutableListOf<SnapshotFile>()
        var totalBytes = 0L

        val candidates =
            root.walkTopDown()
                .onEnter { directory ->
                    val relative =
                        directory.relativeTo(root)
                            .invariantSeparatorsPath
                    relative.isBlank() ||
                        relative.split('/').none {
                            it.lowercase(Locale.ROOT) in BLOCKED_DIRECTORIES
                        }
                }
                .filter { it.isFile }
                .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                .toList()

        if (candidates.size > MAX_FILES) {
            return failure(
                "Project build превышает лимит файлов: ${candidates.size} > $MAX_FILES."
            )
                .put("status", "project_build_too_many_files")
                .put("file_count", candidates.size)
        }

        for (file in candidates) {
            val relative =
                file.relativeTo(root)
                    .invariantSeparatorsPath

            if (!isAllowedSourcePath(relative)) {
                continue
            }

            val bytes =
                try {
                    file.readBytes()
                } catch (_: Exception) {
                    return failure("Не удалось прочитать $relative.")
                        .put("status", "project_build_file_read_failed")
                        .put("path", relative)
                }

            if (bytes.size > MAX_FILE_BYTES) {
                return failure(
                    "Файл $relative слишком большой для bounded project build."
                )
                    .put("status", "project_build_file_too_large")
                    .put("path", relative)
                    .put("size_bytes", bytes.size)
            }

            if (!isUtf8Text(bytes)) {
                return failure(
                    "Project build пока принимает только UTF-8 source/config files; бинарный файл: $relative."
                )
                    .put("status", "project_build_binary_source_unsupported")
                    .put("path", relative)
            }

            totalBytes += bytes.size.toLong()
            if (totalBytes > MAX_TOTAL_BYTES) {
                return failure("Project build snapshot превышает bounded размер.")
                    .put("status", "project_build_snapshot_too_large")
                    .put("total_bytes", totalBytes)
            }

            val content = String(bytes, StandardCharsets.UTF_8)
            files +=
                SnapshotFile(
                    path = relative,
                    content = content,
                    sha256 = sha256(bytes),
                    sizeBytes = bytes.size
                )
        }

        if (files.none { it.path == "settings.gradle.kts" || it.path == "settings.gradle" }) {
            return failure(
                "В Project Workspace нет settings.gradle(.kts); Android project build не запускается."
            )
                .put("status", "project_build_settings_missing")
        }

        if (files.none { it.path == "app/build.gradle.kts" || it.path == "app/build.gradle" }) {
            return failure(
                "В Project Workspace нет app/build.gradle(.kts); Android app module не подтверждён."
            )
                .put("status", "project_build_app_gradle_missing")
        }

        val workflow = projectWorkflow()
        val manifestInput =
            buildString {
                append("project_id=")
                append(projectId)
                append('\n')
                append("repository=")
                append(repository)
                append('\n')
                for (item in files) {
                    append(item.path)
                    append('\t')
                    append(item.sizeBytes)
                    append('\t')
                    append(item.sha256)
                    append('\n')
                }
                append(WORKFLOW_PATH)
                append('\t')
                append(workflow.toByteArray(StandardCharsets.UTF_8).size)
                append('\t')
                append(sha256(workflow.toByteArray(StandardCharsets.UTF_8)))
                append('\n')
            }

        val manifestSha = sha256(manifestInput.toByteArray(StandardCharsets.UTF_8))
        val array = JSONArray()
        files.forEach {
            array.put(
                JSONObject()
                    .put("path", it.path)
                    .put("content", it.content)
                    .put("sha256", it.sha256)
                    .put("size_bytes", it.sizeBytes)
            )
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("project_id", projectId)
            .put("project_name", projectName)
            .put("repository", repository)
            .put("manifest_sha256", manifestSha)
            .put("total_bytes", totalBytes)
            .put("files", array)
    }

    private fun snapshotFromResult(result: JSONObject): Snapshot? {
        if (!result.optBoolean("success", false)) return null
        val projectId = result.optString("project_id").trim()
        val projectName = result.optString("project_name").trim()
        val repository = result.optString("repository").trim()
        val manifest = result.optString("manifest_sha256").trim()
        val array = result.optJSONArray("files") ?: return null
        if (
            projectId.isBlank() ||
            projectName.isBlank() ||
            repository.isBlank() ||
            !SHA256_HEX.matches(manifest)
        ) {
            return null
        }

        val files = ArrayList<SnapshotFile>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            files +=
                SnapshotFile(
                    path = item.optString("path"),
                    content = item.optString("content"),
                    sha256 = item.optString("sha256"),
                    sizeBytes = item.optInt("size_bytes", 0)
                )
        }

        return Snapshot(
            projectId = projectId,
            projectName = projectName,
            repository = repository,
            files = files,
            manifestSha256 = manifest,
            totalBytes = result.optLong("total_bytes", 0L)
        )
    }

    private fun pushExactSnapshot(
        accessToken: String,
        snapshot: Snapshot,
        parentHeadSha: String,
        buildId: String
    ): JSONObject {
        val repository = snapshot.repository

        val treeEntries = JSONArray()
        for (file in snapshot.files) {
            treeEntries.put(
                JSONObject()
                    .put("path", file.path)
                    .put("mode", "100644")
                    .put("type", "blob")
                    .put("content", file.content)
            )
        }

        treeEntries.put(
            JSONObject()
                .put("path", WORKFLOW_PATH)
                .put("mode", "100644")
                .put("type", "blob")
                .put("content", projectWorkflow())
        )

        val treeResponse =
            githubJsonRequest(
                method = "POST",
                repository = repository,
                apiPath = "/git/trees",
                accessToken = accessToken,
                body = JSONObject().put("tree", treeEntries)
            )

        if (!treeResponse.optBoolean("http_success", false)) {
            return githubFailure(
                status = "project_build_tree_create_failed",
                response = treeResponse,
                message =
                    "Не удалось создать exact Git tree в $repository. " +
                        "Проверьте Contents:write и Workflows:write для GitHub App."
            )
        }

        val treeSha =
            treeResponse.optJSONObject("body")
                ?.optString("sha")
                .orEmpty()
                .trim()

        if (treeSha.isBlank()) {
            return failure("GitHub не вернул tree SHA.")
                .put("status", "project_build_tree_sha_missing")
        }

        val commitBody =
            JSONObject()
                .put(
                    "message",
                    "AYANA project build $buildId\n\n" +
                        "project=${snapshot.projectName}\n" +
                        "workspace_manifest=${snapshot.manifestSha256}"
                )
                .put("tree", treeSha)

        if (parentHeadSha.isNotBlank()) {
            commitBody.put("parents", JSONArray().put(parentHeadSha))
        } else {
            commitBody.put("parents", JSONArray())
        }

        val commitResponse =
            githubJsonRequest(
                method = "POST",
                repository = repository,
                apiPath = "/git/commits",
                accessToken = accessToken,
                body = commitBody
            )

        if (!commitResponse.optBoolean("http_success", false)) {
            return githubFailure(
                status = "project_build_commit_create_failed",
                response = commitResponse,
                message = "Не удалось создать project build commit."
            )
        }

        val commitSha =
            commitResponse.optJSONObject("body")
                ?.optString("sha")
                .orEmpty()
                .trim()

        if (commitSha.isBlank()) {
            return failure("GitHub не вернул commit SHA.")
                .put("status", "project_build_commit_sha_missing")
        }

        val refResponse =
            if (parentHeadSha.isBlank()) {
                githubJsonRequest(
                    method = "POST",
                    repository = repository,
                    apiPath = "/git/refs",
                    accessToken = accessToken,
                    body =
                        JSONObject()
                            .put("ref", "refs/heads/$BRANCH")
                            .put("sha", commitSha)
                )
            } else {
                githubJsonRequest(
                    method = "PATCH",
                    repository = repository,
                    apiPath = "/git/refs/heads/$BRANCH",
                    accessToken = accessToken,
                    body =
                        JSONObject()
                            .put("sha", commitSha)
                            .put("force", false)
                )
            }

        if (!refResponse.optBoolean("http_success", false)) {
            return githubFailure(
                status = "project_build_ref_update_failed",
                response = refResponse,
                message =
                    "Project snapshot commit создан, но main ref не обновлён доказательно."
            )
                .put("commit_sha", commitSha)
                .put("action_dispatched", true)
                .put("action_committed", false)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "UNKNOWN")
        }

        val verify = repositoryState(accessToken, repository)
        val verifiedHead = verify.optString("head_sha").trim()

        if (verifiedHead != commitSha) {
            return failure(
                "Project repository head после push не совпал с exact commit."
            )
                .put("status", "project_build_commit_verification_failed")
                .put("commit_sha", commitSha)
                .put("verified_head_sha", verifiedHead)
                .put("action_dispatched", true)
                .put("action_committed", false)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "UNKNOWN")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "project_build_snapshot_committed")
            .put("commit_sha", commitSha)
            .put("tree_sha", treeSha)
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "VERIFIED_COMMITTED")
    }

    private fun waitForBuild(
        accessToken: String,
        repository: String,
        commitSha: String,
        shouldCancel: () -> Boolean
    ): JSONObject {
        val startedAt = System.currentTimeMillis()
        var runId = 0L
        var runUrl = ""
        var workflowId = 0L
        var status = ""
        var conclusion = ""

        while (System.currentTimeMillis() - startedAt < BUILD_TIMEOUT_MS) {
            if (shouldCancel()) {
                return cancelled(
                    "Project build STOP после push: commit уже записан; workflow не перезапускался."
                )
                    .put("status", "project_build_cancelled_after_commit")
                    .put("reconciliation_complete", false)
            }

            val runs =
                githubJsonRequest(
                    method = "GET",
                    repository = repository,
                    apiPath =
                        "/actions/runs?head_sha=$commitSha&event=push&per_page=20",
                    accessToken = accessToken
                )

            if (runs.optBoolean("http_success", false)) {
                val items =
                    runs.optJSONObject("body")
                        ?.optJSONArray("workflow_runs")
                        ?: JSONArray()

                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (
                        item.optString("head_sha") == commitSha &&
                        item.optString("event") == "push"
                    ) {
                        runId = item.optLong("id", 0L)
                        workflowId = item.optLong("workflow_id", 0L)
                        runUrl = item.optString("html_url")
                        status = item.optString("status")
                        conclusion = item.optString("conclusion")
                        break
                    }
                }
            }

            if (runId > 0L && status == "completed") {
                if (conclusion != "success") {
                    val diagnostic =
                        fetchFailureDiagnostic(
                            accessToken = accessToken,
                            repository = repository,
                            runId = runId
                        )

                    return failure(
                        "Project APK build завершился ошибкой: conclusion=$conclusion."
                    )
                        .put("status", "project_apk_build_failed")
                        .put("run_id", runId)
                        .put("run_url", runUrl)
                        .put("build_status", status)
                        .put("build_conclusion", conclusion)
                        .put("workflow_runtime_id", workflowId)
                        .put("compile_output", diagnostic)
                        .put("reconciliation_complete", true)
                }

                return verifyArtifact(
                    accessToken = accessToken,
                    repository = repository,
                    runId = runId,
                    runUrl = runUrl,
                    workflowRuntimeId = workflowId,
                    commitSha = commitSha
                )
            }

            Thread.sleep(BUILD_POLL_MS)
        }

        return failure(
            "Project build не достиг terminal состояния в bounded timeout; повторный dispatch запрещён."
        )
            .put("status", "project_build_reconciliation_required")
            .put("run_id", runId)
            .put("run_url", runUrl)
            .put("build_status", status)
            .put("build_conclusion", conclusion)
            .put("reconciliation_complete", false)
    }

    private fun verifyArtifact(
        accessToken: String,
        repository: String,
        runId: Long,
        runUrl: String,
        workflowRuntimeId: Long,
        commitSha: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                repository = repository,
                apiPath = "/actions/runs/$runId/artifacts?per_page=100",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return githubFailure(
                status = "project_build_artifact_list_failed",
                response = response,
                message = "Build успешен, но список APK artifacts не подтверждён."
            )
                .put("run_id", runId)
                .put("run_url", runUrl)
                .put("build_status", "completed")
                .put("build_conclusion", "success")
                .put("reconciliation_complete", true)
        }

        val artifacts =
            response.optJSONObject("body")
                ?.optJSONArray("artifacts")
                ?: JSONArray()

        var chosen: JSONObject? = null
        for (index in 0 until artifacts.length()) {
            val item = artifacts.optJSONObject(index) ?: continue
            if (
                item.optString("name") == PROJECT_ARTIFACT_NAME &&
                !item.optBoolean("expired", false) &&
                item.optLong("size_in_bytes", 0L) > 0L
            ) {
                chosen = item
                break
            }
        }

        if (chosen == null) {
            return failure(
                "Workflow завершился SUCCESS, но artifact $PROJECT_ARTIFACT_NAME не найден."
            )
                .put("status", "project_build_artifact_missing")
                .put("run_id", runId)
                .put("run_url", runUrl)
                .put("build_status", "completed")
                .put("build_conclusion", "success")
                .put("artifact_verified", false)
                .put("reconciliation_complete", true)
        }

        val artifactId = chosen.optLong("id", 0L)
        val size = chosen.optLong("size_in_bytes", 0L)
        var digest = chosen.optString("digest").trim()

        if (!ARTIFACT_DIGEST.matches(digest)) {
            // Older GitHub API payloads may omit digest. Do not invent one.
            return failure(
                "APK artifact найден, но immutable sha256 digest отсутствует."
            )
                .put("status", "project_build_artifact_digest_missing")
                .put("run_id", runId)
                .put("run_url", runUrl)
                .put("artifact_id", artifactId)
                .put("artifact_size_bytes", size)
                .put("artifact_verified", false)
                .put("reconciliation_complete", true)
        }

        digest = digest.lowercase(Locale.ROOT)

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "verified_apk_build")
            .put("run_id", runId)
            .put("run_url", runUrl)
            .put("workflow_runtime_id", workflowRuntimeId)
            .put("source_commit_sha", commitSha)
            .put("build_status", "completed")
            .put("build_conclusion", "success")
            .put("artifact_verified", true)
            .put("artifact_id", artifactId)
            .put("artifact_name", PROJECT_ARTIFACT_NAME)
            .put("artifact_size_bytes", size)
            .put("artifact_digest", digest)
            .put("reconciliation_complete", true)
    }

    private fun fetchFailureDiagnostic(
        accessToken: String,
        repository: String,
        runId: Long
    ): String {
        // R10.28.9.1: preserve first compiler errors and unique error groups;
        // never trim the first cause away with takeLast(60).
        return try {
            val bytes = githubBytesRequest(
                repository = repository,
                apiPath = "/actions/runs/$runId/logs",
                accessToken = accessToken
            )
            AyanaBuildDiagnosticExtractor.summarize(bytes)
        } catch (error: Exception) {
            "BUILD_LOG_UNAVAILABLE: ${error.javaClass.simpleName}"
        }
    }

    private fun repositoryState(
        accessToken: String,
        repository: String
    ): JSONObject {
        val repo =
            githubJsonRequest(
                method = "GET",
                repository = repository,
                apiPath = "",
                accessToken = accessToken
            )

        if (!repo.optBoolean("http_success", false)) {
            val code = repo.optInt("http_code", -1)
            return failure(
                if (code == 404) {
                    "Dedicated repository $repository не найден. Создайте его один раз в GitHub; AYANA repository не используется."
                } else {
                    "Dedicated repository $repository недоступен."
                }
            )
                .put(
                    "status",
                    if (code == 404) {
                        "project_build_repository_missing"
                    } else {
                        "project_build_repository_unavailable"
                    }
                )
                .put("repository", repository)
                .put("http_code", code)
                .put("setup_required", code == 404)
        }

        val body = repo.optJSONObject("body") ?: JSONObject()
        val permissions = body.optJSONObject("permissions") ?: JSONObject()
        val push = permissions.optBoolean("push", false)
        val defaultBranch =
            body.optString("default_branch", BRANCH)
                .ifBlank { BRANCH }

        if (defaultBranch != BRANCH && body.optLong("size", 0L) > 0L) {
            return failure(
                "Dedicated repository должен использовать default branch $BRANCH."
            )
                .put("status", "project_build_branch_mismatch")
                .put("repository", repository)
                .put("default_branch", defaultBranch)
        }

        val ref =
            githubJsonRequest(
                method = "GET",
                repository = repository,
                apiPath = "/git/ref/heads/$BRANCH",
                accessToken = accessToken
            )

        val headSha =
            if (ref.optBoolean("http_success", false)) {
                ref.optJSONObject("body")
                    ?.optJSONObject("object")
                    ?.optString("sha")
                    .orEmpty()
                    .trim()
            } else {
                ""
            }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("repository", repository)
            .put("push", push)
            .put("default_branch", defaultBranch)
            .put("head_sha", headSha)
            .put("empty_repository", headSha.isBlank())
    }

    private fun usableAccessToken(): JSONObject {
        // Existing executor remains the token/refresh authority. Calling status
        // refreshes an expiring token without exposing it.
        val status =
            try {
                githubRepositoryExecutor.status(
                    allowPendingPoll = false
                )
            } catch (error: Exception) {
                return failure(
                    "GitHub credential authority недоступна: ${error.message.orEmpty().take(160)}"
                )
                    .put("status", "project_build_github_authority_unavailable")
            }

        if (
            !status.optBoolean("success", false) &&
            status.optBoolean("requires_user_authorization", false)
        ) {
            return failure("GitHub требует повторной авторизации.")
                .put("status", "project_build_github_authorization_required")
                .put("requires_user_authorization", true)
        }

        val token = secureGet(GITHUB_KEY_ACCESS_TOKEN)
        if (token.isBlank()) {
            return failure(
                "GitHub access token не удалось получить из Android Keystore."
            )
                .put("status", "project_build_github_token_unavailable")
                .put("requires_user_authorization", true)
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("access_token", token)
    }

    private fun secureGet(key: String): String {
        val payload =
            githubPrefs.getString(key, "")
                .orEmpty()
        if (payload.isBlank()) return ""

        return try {
            val parts = payload.split('.', limit = 2)
            if (parts.size != 2) return ""
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateSecretKey(),
                GCMParameterSpec(128, iv)
            )
            String(
                cipher.doFinal(encrypted),
                StandardCharsets.UTF_8
            )
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(GITHUB_KEYSTORE_ALIAS, null)
        if (existing is SecretKey) {
            return existing
        }

        val generator =
            KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )

        generator.init(
            KeyGenParameterSpec.Builder(
                GITHUB_KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )

        return generator.generateKey()
    }

    private fun githubJsonRequest(
        method: String,
        repository: String,
        apiPath: String,
        accessToken: String,
        body: JSONObject? = null
    ): JSONObject {
        val url =
            URL(
                "https://api.github.com/repos/" +
                    repository +
                    apiPath
            )

        var connection: HttpURLConnection? = null

        return try {
            connection =
                (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = method
                    connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                    readTimeout = HTTP_READ_TIMEOUT_MS
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    setRequestProperty("User-Agent", "AYANA-AI")
                    doInput = true

                    if (body != null) {
                        doOutput = true
                        setRequestProperty(
                            "Content-Type",
                            "application/json; charset=utf-8"
                        )
                        outputStream.use {
                            it.write(
                                body.toString()
                                    .toByteArray(StandardCharsets.UTF_8)
                            )
                        }
                    }
                }

            val code = connection.responseCode
            val stream =
                if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

            val text =
                stream
                    ?.bufferedReader(StandardCharsets.UTF_8)
                    ?.use { it.readText() }
                    .orEmpty()

            val parsed =
                if (text.isBlank()) {
                    JSONObject()
                } else {
                    try {
                        JSONObject(text)
                    } catch (_: Exception) {
                        JSONObject().put("raw", text.take(2000))
                    }
                }

            JSONObject()
                .put("http_success", code in 200..299)
                .put("http_code", code)
                .put("body", parsed)
                .put(
                    "error",
                    if (code in 200..299) {
                        ""
                    } else {
                        parsed.optString("message", "HTTP $code")
                    }
                )
        } catch (error: Exception) {
            JSONObject()
                .put("http_success", false)
                .put("http_code", -1)
                .put("error", error.message.orEmpty().take(240))
                .put("body", JSONObject())
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun githubBytesRequest(
        repository: String,
        apiPath: String,
        accessToken: String
    ): ByteArray {
        var connection: HttpURLConnection? = null

        return try {
            connection =
                (URL(
                    "https://api.github.com/repos/" +
                        repository +
                        apiPath
                ).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = HTTP_CONNECT_TIMEOUT_MS
                    readTimeout = HTTP_LOG_READ_TIMEOUT_MS
                    instanceFollowRedirects = true
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("Authorization", "Bearer $accessToken")
                    setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
                    setRequestProperty("User-Agent", "AYANA-AI")
                }

            val code = connection.responseCode
            if (code !in 200..299) {
                ByteArray(0)
            } else {
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    val output = java.io.ByteArrayOutputStream()
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_LOG_ARCHIVE_BYTES) return ByteArray(0)
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }
        } catch (_: Exception) {
            ByteArray(0)
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    private fun githubFailure(
        status: String,
        response: JSONObject,
        message: String
    ): JSONObject =
        failure(message)
            .put("status", status)
            .put("http_code", response.optInt("http_code", -1))
            .put("github_error", response.optString("error").take(300))

    private fun repositoryForProject(projectName: String): String {
        val repoName =
            projectName
                .trim()
                .uppercase(Locale.ROOT)
                .replace(Regex("[^A-Z0-9._-]+"), "-")
                .replace(Regex("-+"), "-")
                .trim('-', '.', '_')
                .take(80)
                .ifBlank { "AYANA-PROJECT" }

        return "$OWNER/$repoName"
    }

    private fun isDedicatedRepository(repository: String): Boolean =
        repository.isNotBlank() &&
            !repository.equals(
                AyanaGitHubRepositoryExecutor.REPOSITORY_SLUG,
                ignoreCase = true
            )

    private fun isAllowedSourcePath(path: String): Boolean {
        val clean = path.replace('\\', '/').trim('/')
        if (clean.isBlank()) return false
        val parts = clean.split('/')
        if (
            parts.any {
                it.lowercase(Locale.ROOT) in BLOCKED_DIRECTORIES
            }
        ) {
            return false
        }

        if (clean.startsWith(".github/", ignoreCase = true)) {
            // Bridge owns the remote build workflow; local project cannot replace it.
            return false
        }

        return true
    }

    private fun isUtf8Text(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        return try {
            val text = String(bytes, StandardCharsets.UTF_8)
            text.toByteArray(StandardCharsets.UTF_8)
                .contentEquals(bytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun projectWorkflow(): String =
        """
        name: ${AyanaGitHubRepositoryExecutor.BUILD_WORKFLOW_NAME}

        on:
          push:
            branches: [ main ]
          workflow_dispatch:

        permissions:
          contents: read

        jobs:
          build:
            runs-on: ubuntu-latest
            timeout-minutes: 20
            steps:
              - name: Checkout exact project snapshot
                uses: actions/checkout@v4

              - name: Set up JDK 17
                uses: actions/setup-java@v4
                with:
                  distribution: temurin
                  java-version: '17'

              - name: Set up Gradle
                uses: gradle/actions/setup-gradle@v4
                with:
                  gradle-version: '8.9'

              - name: Build debug APK
                shell: bash
                run: |
                  set -o pipefail
                  gradle --no-daemon --stacktrace :app:assembleDebug 2>&1 | tee build-log.txt

              - name: Upload build log
                if: always()
                uses: actions/upload-artifact@v4
                with:
                  name: PROJECT-BUILD-LOG
                  path: build-log.txt
                  if-no-files-found: ignore
                  retention-days: 7

              - name: Upload APK
                if: success()
                uses: actions/upload-artifact@v4
                with:
                  name: $PROJECT_ARTIFACT_NAME
                  path: app/build/outputs/apk/debug/*.apk
                  if-no-files-found: error
                  retention-days: 14
        """.trimIndent() + "\n"

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it)
            }

    private fun failure(message: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", true)
            .put("terminal_status", "ERROR")
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("message", message)

    private fun cancelled(message: String): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", true)
            .put("terminal_status", "CANCELLED")
            .put("status", "project_build_cancelled")
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("message", message)

    companion object {
        const val VERSION = "1.1"
        const val BRANCH = "main"
        const val PROJECT_ARTIFACT_NAME = "PROJECT-DEBUG-APK"
        const val SIDE_EFFECT_KIND = "project_workspace_apk_build"
        const val PROJECT_WORKFLOW_BINDING_ID = 1L

        private const val OWNER = "talant02031985-bot"
        private const val WORKFLOW_PATH = ".github/workflows/ayana-project-build.yml"
        private const val EMPTY_HEAD_TOKEN = "EMPTY_REPOSITORY"

        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val GITHUB_PREFS_NAME = "ayana_github_repository_executor_v1"
        private const val GITHUB_KEYSTORE_ALIAS = "ayana_github_repository_tokens_v1"
        private const val GITHUB_KEY_ACCESS_TOKEN = "secure_access_token"

        private const val MAX_FILES = 240
        private const val MAX_FILE_BYTES = 384 * 1024
        private const val MAX_TOTAL_BYTES = 6L * 1024L * 1024L

        private const val HTTP_CONNECT_TIMEOUT_MS = 15_000
        private const val HTTP_READ_TIMEOUT_MS = 45_000
        private const val HTTP_LOG_READ_TIMEOUT_MS = 90_000

        private const val BUILD_TIMEOUT_MS = 8L * 60L * 1000L
        private const val BUILD_POLL_MS = 3_000L

        private const val MAX_LOG_ARCHIVE_BYTES = 14 * 1024 * 1024

        private val SHA256_HEX =
            Regex("^[0-9a-f]{64}$")

        private val ARTIFACT_DIGEST =
            Regex("^sha256:[0-9a-fA-F]{64}$")

        private val BLOCKED_DIRECTORIES =
            setOf(
                ".git",
                ".gradle",
                ".idea",
                "build",
                "captures",
                "node_modules",
                "ayana_project_workspace_meta"
            )
    }
}
