package kg.autonomous.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AYANA R10.27.2 GitHub Repository + Actions Executor v1.2.
 *
 * Security model:
 * - GitHub App Device Flow only. No PAT/client secret is embedded in the APK.
 * - user/refresh tokens are encrypted with an Android Keystore AES/GCM key;
 * - repository/branch are fixed to the AYANA project scope;
 * - prepare is read-only and returns the exact expected blob SHA;
 * - commit is possible only on the explicit confirmed replay from VoiceService;
 * - commit re-reads the target SHA immediately before PUT and fails closed on drift;
 * - SUCCESS requires GitHub response content.sha == locally computed Git blob SHA
 *   plus a non-empty commit SHA; transport ambiguity is reconciled by re-reading
 *   repository state and commit provenance instead of blind retry.
 *
 * R10.27.2 adds a separately confirmed GitHub Actions build lane:
 * - fixed workflow name and fixed main branch only;
 * - prepare is read-only and proves the installed GitHub App has Actions:write;
 * - dispatch is possible only on the explicit confirmed durable replay;
 * - the exact workflow run is identified without blind re-dispatch;
 * - SUCCESS requires conclusion=success plus one non-expired APK artifact with
 *   an immutable SHA-256 artifact digest.
 *
 * This executor still does not merge branches, delete files, write secrets,
 * install APKs, or broaden authority beyond the fixed repository.
 */
class AyanaGitHubRepositoryExecutor(
    context: Context
) {

    private val appContext = context.applicationContext

    private val prefs =
        appContext.getSharedPreferences(
            PREFS_NAME,
            Context.MODE_PRIVATE
        )

    fun configureClientId(
        clientId: String
    ): JSONObject {
        val normalized = clientId.trim()

        if (!VALID_CLIENT_ID.matches(normalized)) {
            return failure(
                "Некорректный GitHub App client_id."
            )
                .put("status", "invalid_client_id")
        }

        prefs.edit()
            .putString(KEY_CLIENT_ID, normalized)
            .apply()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "client_id_configured")
            .put("client_id_configured", true)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("message", "GitHub App client_id сохранён локально. Секреты не сохранялись.")
    }

    fun startDeviceFlow(): JSONObject {
        val clientId = configuredClientId()

        if (clientId.isBlank()) {
            return failure(
                "GitHub App ещё не настроен: сначала укажите client_id."
            )
                .put("status", "client_id_missing")
        }

        val response =
            postForm(
                url = "https://github.com/login/device/code",
                form =
                    linkedMapOf(
                        "client_id" to clientId
                    )
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "GitHub не выдал код подключения: ${response.optString("error", "HTTP error")}"
            )
                .put("status", "device_flow_start_failed")
                .put("http_code", response.optInt("http_code", -1))
        }

        val body = response.optJSONObject("body") ?: JSONObject()
        val deviceCode = body.optString("device_code").trim()
        val userCode = body.optString("user_code").trim()
        val verificationUri =
            body.optString(
                "verification_uri",
                "https://github.com/login/device"
            ).trim()
        val expiresIn = body.optLong("expires_in", 900L).coerceAtLeast(60L)
        val interval = body.optLong("interval", 5L).coerceAtLeast(5L)

        if (
            deviceCode.isBlank() ||
            userCode.isBlank() ||
            verificationUri.isBlank()
        ) {
            return failure(
                "GitHub вернул неполный Device Flow ответ."
            )
                .put("status", "device_flow_response_incomplete")
        }

        val now = System.currentTimeMillis()

        securePut(KEY_PENDING_DEVICE_CODE, deviceCode)
        prefs.edit()
            .putString(KEY_PENDING_USER_CODE, userCode)
            .putString(KEY_PENDING_VERIFICATION_URI, verificationUri)
            .putLong(KEY_PENDING_EXPIRES_AT, now + expiresIn * 1000L)
            .putLong(KEY_PENDING_INTERVAL_MS, interval * 1000L)
            .putLong(KEY_PENDING_LAST_POLL_AT, 0L)
            .apply()

        val browserOpened =
            try {
                appContext.startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(verificationUri)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                true
            } catch (_: Exception) {
                false
            }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "authorization_pending")
            .put("authorization_pending", true)
            .put("user_code", userCode)
            .put("verification_uri", verificationUri)
            .put("expires_in_seconds", expiresIn)
            .put("poll_interval_seconds", interval)
            .put("browser_opened", browserOpened)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put(
                "message",
                "Откройте GitHub Device Flow и подтвердите код $userCode. После авторизации скажите: «проверь GitHub»."
            )
    }

    fun status(
        allowPendingPoll: Boolean = true
    ): JSONObject {
        if (allowPendingPoll && hasPendingAuthorization()) {
            // R10.27.2: allow a fresh Device Flow to replace an older still-valid
            // token after GitHub App permissions were expanded (for example,
            // adding Actions:write). This keeps re-authorization explicit.
            pollPendingAuthorization()
        }

        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            recordRuntime(
                connected = false,
                writeAvailable = false,
                error = tokenResult.optString("message")
            )

            return JSONObject(tokenResult.toString())
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("client_id_configured", configuredClientId().isNotBlank())
                .put("authorization_pending", hasPendingAuthorization())
        }

        val accessToken = tokenResult.optString("access_token")
        val repo =
            githubJsonRequest(
                method = "GET",
                apiPath = "/repos/$OWNER/$REPO",
                accessToken = accessToken
            )

        if (!repo.optBoolean("http_success", false)) {
            recordRuntime(
                connected = false,
                writeAvailable = false,
                error = repo.optString("error")
            )

            return failure(
                "GitHub авторизация есть, но репозиторий $REPOSITORY_SLUG недоступен."
            )
                .put("status", "repository_unavailable")
                .put("http_code", repo.optInt("http_code", -1))
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
        }

        val body = repo.optJSONObject("body") ?: JSONObject()
        val permissions = body.optJSONObject("permissions") ?: JSONObject()
        val push = permissions.optBoolean("push", false)

        val installationAuthority =
            installationAuthority(accessToken)

        val actionsPermission =
            if (installationAuthority.optBoolean("success", false)) {
                installationAuthority.optString("actions_permission", "none")
                    .lowercase(Locale.ROOT)
            } else {
                "unknown"
            }

        recordRuntime(
            connected = true,
            writeAvailable = push,
            error = if (push) "" else "repository_push_permission_missing",
            actionsPermission = actionsPermission
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", if (push) "connected_write_ready" else "connected_read_only")
            .put("connected", true)
            .put("repository_access_verified", true)
            .put("repository_write_available", push)
            .put("actions_permission", actionsPermission)
            .put("actions_write_available", actionsPermission == "write")
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("login", body.optJSONObject("owner")?.optString("login").orEmpty())
            .put("private", body.optBoolean("private", false))
            .put("message", if (push) {
                "GitHub подключён; запись в $REPOSITORY_SLUG/$BRANCH доступна."
            } else {
                "GitHub подключён, но у текущего GitHub App/token нет подтверждённого права записи в репозиторий."
            })
    }

    fun writeCommit(
        arguments: JSONObject,
        confirmed: Boolean
    ): JSONObject {
        val pathResult = validatePath(arguments.optString("path"))
        if (!pathResult.optBoolean("success", false)) {
            return pathResult
        }

        val path = pathResult.optString("path")
        val content = arguments.optString("content")
        val bytes = content.toByteArray(StandardCharsets.UTF_8)

        if (bytes.size > MAX_INLINE_CONTENT_BYTES) {
            return failure(
                "Файл слишком большой для inline GitHub executor R10.27.1: ${bytes.size} байт."
            )
                .put("status", "content_too_large")
                .put("max_bytes", MAX_INLINE_CONTENT_BYTES)
        }

        val commitMessage =
            arguments.optString("commit_message")
                .trim()
                .ifBlank {
                    "AYANA: update $path"
                }
                .take(MAX_COMMIT_MESSAGE_CHARS)

        if (!confirmed) {
            return prepareWrite(
                path = path,
                contentBytes = bytes,
                commitMessage = commitMessage
            )
        }

        val expectedSha = arguments.optString("_github_expected_sha").trim()
        val expectedMissing = arguments.optBoolean("_github_expected_missing", false)
        val preparedBlobSha = arguments.optString("_github_proposed_blob_sha").trim()
        val preparedPath = arguments.optString("_github_prepared_path").trim()
        val preparedMessage = arguments.optString("_github_prepared_commit_message").trim()

        val actualBlobSha = gitBlobSha(bytes)

        if (
            preparedPath != path ||
            preparedMessage != commitMessage ||
            preparedBlobSha.isBlank() ||
            preparedBlobSha != actualBlobSha
        ) {
            return failure(
                "Подтверждённый GitHub commit остановлен: подготовленный payload не совпадает с подтверждённым."
            )
                .put("status", "prepared_payload_mismatch")
                .put("verified", false)
        }

        return commitPrepared(
            path = path,
            contentBytes = bytes,
            commitMessage = commitMessage,
            expectedSha = expectedSha,
            expectedMissing = expectedMissing,
            proposedBlobSha = preparedBlobSha
        )
    }


    fun actionsStatus(): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return JSONObject(tokenResult.toString())
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_name", BUILD_WORKFLOW_NAME)
        }

        val accessToken = tokenResult.optString("access_token")
        val authority = installationAuthority(accessToken)

        if (!authority.optBoolean("success", false)) {
            return authority
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_name", BUILD_WORKFLOW_NAME)
        }

        val workflow = findBuildWorkflow(accessToken)
        val actionsPermission =
            authority.optString("actions_permission", "none")
                .lowercase(Locale.ROOT)
        val actionsWrite = actionsPermission == "write"
        val workflowReady = workflow.optBoolean("success", false)

        prefs.edit()
            .putString(KEY_LAST_ACTIONS_PERMISSION, actionsPermission)
            .putLong(KEY_LAST_ACTIONS_VERIFIED_AT, System.currentTimeMillis())
            .apply()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put(
                "status",
                when {
                    !workflowReady -> "build_workflow_unavailable"
                    !actionsWrite -> "actions_write_permission_required"
                    else -> "build_dispatch_ready"
                }
            )
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("repository_selected", authority.optBoolean("repository_selected", false))
            .put("installation_id", authority.optLong("installation_id", 0L))
            .put("contents_permission", authority.optString("contents_permission", "none"))
            .put("actions_permission", actionsPermission)
            .put("actions_write_available", actionsWrite)
            .put("workflow_ready", workflowReady)
            .put("workflow_id", workflow.optLong("workflow_id", 0L))
            .put("workflow_name", workflow.optString("workflow_name", BUILD_WORKFLOW_NAME))
            .put("workflow_state", workflow.optString("workflow_state"))
            .put("workflow_path", workflow.optString("workflow_path"))
            .put("artifact_name", APK_ARTIFACT_NAME)
            .put(
                "message",
                when {
                    !workflowReady ->
                        "GitHub Actions подключён, но workflow «$BUILD_WORKFLOW_NAME» не удалось однозначно подтвердить."
                    !actionsWrite ->
                        "Для запуска сборки AYANA GitHub App требуется Repository permission: Actions → Read and write. После изменения разрешения переподключите GitHub через Device Flow."
                    else ->
                        "GitHub Actions готов: AYANA может подготовить запуск «$BUILD_WORKFLOW_NAME» для $REPOSITORY_SLUG/$BRANCH."
                }
            )
    }

    fun buildApk(
        arguments: JSONObject,
        confirmed: Boolean,
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        if (!confirmed) {
            return prepareBuild()
        }

        val preparedWorkflowId =
            arguments.optLong("_github_build_workflow_id", 0L)
        val preparedHeadSha =
            arguments.optString("_github_build_head_sha").trim()
        val preparedWorkflowName =
            arguments.optString("_github_build_workflow_name").trim()

        if (
            preparedWorkflowId <= 0L ||
            preparedHeadSha.isBlank() ||
            preparedWorkflowName != BUILD_WORKFLOW_NAME
        ) {
            return failure(
                "GitHub Actions build payload повреждён или неполон. Нужна новая подготовка и новое подтверждение."
            )
                .put("status", "prepared_build_payload_invalid")
                .put("requires_confirmation", false)
        }

        return dispatchAndMonitorBuild(
            workflowId = preparedWorkflowId,
            expectedHeadSha = preparedHeadSha,
            shouldCancel = shouldCancel
        )
    }

    fun buildStatus(): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
        }

        val runId = prefs.getLong(KEY_LAST_BUILD_RUN_ID, 0L)
        if (runId <= 0L) {
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("status", "no_recorded_build")
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_name", BUILD_WORKFLOW_NAME)
                .put("message", "AYANA ещё не сохранила идентификатор GitHub Actions build run.")
        }

        val accessToken = tokenResult.optString("access_token")
        val expectedHeadSha = prefs.getString(KEY_LAST_BUILD_HEAD_SHA, "").orEmpty()
        return inspectBuildRun(
            accessToken = accessToken,
            runId = runId,
            expectedHeadSha = expectedHeadSha,
            expectedWorkflowId = prefs.getLong(KEY_LAST_BUILD_WORKFLOW_ID, 0L)
        )
    }

    private fun prepareBuild(): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
        }

        val accessToken = tokenResult.optString("access_token")
        val authority = installationAuthority(accessToken)

        if (!authority.optBoolean("success", false)) {
            return authority
        }

        val actionsPermission =
            authority.optString("actions_permission", "none")
                .lowercase(Locale.ROOT)

        prefs.edit()
            .putString(KEY_LAST_ACTIONS_PERMISSION, actionsPermission)
            .putLong(KEY_LAST_ACTIONS_VERIFIED_AT, System.currentTimeMillis())
            .apply()

        if (actionsPermission != "write") {
            return failure(
                "GitHub App не имеет Actions: Read and write для $REPOSITORY_SLUG. Измените Repository permissions → Actions на Read and write и заново выполните Device Flow."
            )
                .put("status", "actions_write_permission_required")
                .put("actions_permission", actionsPermission)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val workflow = findBuildWorkflow(accessToken)
        if (!workflow.optBoolean("success", false)) {
            return workflow
        }

        val head = readBranchHead(accessToken)
        if (!head.optBoolean("success", false)) {
            return head
        }

        val workflowId = workflow.optLong("workflow_id", 0L)
        val headSha = head.optString("head_sha").trim()

        if (workflowId <= 0L || headSha.isBlank()) {
            return failure(
                "Не удалось зафиксировать точный workflow/head SHA перед сборкой."
            )
                .put("status", "build_prepare_incomplete")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "build_prepared_waiting_confirmation")
            .put("requires_confirmation", true)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("workflow_id", workflowId)
            .put("workflow_name", BUILD_WORKFLOW_NAME)
            .put("workflow_state", workflow.optString("workflow_state"))
            .put("head_sha", headSha)
            .put("artifact_name", APK_ARTIFACT_NAME)
            .put(
                "message",
                "Сборка APK подготовлена для ${headSha.take(12)} через «$BUILD_WORKFLOW_NAME». Workflow ещё НЕ запущен. Для запуска скажите: «подтверждаю текущую задачу»."
            )
    }

    private fun dispatchAndMonitorBuild(
        workflowId: Long,
        expectedHeadSha: String,
        shouldCancel: () -> Boolean
    ): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
        }

        val accessToken = tokenResult.optString("access_token")
        val authority = installationAuthority(accessToken)

        if (
            !authority.optBoolean("success", false) ||
            authority.optString("actions_permission", "none")
                .lowercase(Locale.ROOT) != "write"
        ) {
            return failure(
                "GitHub Actions build остановлен до dispatch: Actions:write больше не подтверждён."
            )
                .put("status", "actions_authority_changed")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val workflow = findBuildWorkflow(accessToken)
        if (
            !workflow.optBoolean("success", false) ||
            workflow.optLong("workflow_id", 0L) != workflowId
        ) {
            return failure(
                "GitHub Actions build остановлен: workflow изменился после подготовки."
            )
                .put("status", "workflow_state_changed")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val head = readBranchHead(accessToken)
        if (
            !head.optBoolean("success", false) ||
            head.optString("head_sha").trim() != expectedHeadSha
        ) {
            return failure(
                "GitHub Actions build остановлен: main изменился после подготовки. Нужна новая подготовка и новое подтверждение."
            )
                .put("status", "build_head_changed")
                .put("expected_head_sha", expectedHeadSha)
                .put("actual_head_sha", head.optString("head_sha"))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val baseline =
            listWorkflowRuns(
                accessToken = accessToken,
                workflowId = workflowId,
                headSha = expectedHeadSha
            )

        if (!baseline.optBoolean("success", false)) {
            return baseline
        }

        val baselineIds = mutableSetOf<Long>()
        val baselineRuns = baseline.optJSONArray("runs") ?: JSONArray()
        for (index in 0 until baselineRuns.length()) {
            val id = baselineRuns.optJSONObject(index)?.optLong("id", 0L) ?: 0L
            if (id > 0L) baselineIds += id
        }

        if (shouldCancel()) {
            return failure("Сборка отменена до GitHub dispatch.")
                .put("status", "build_cancelled_before_dispatch")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val dispatch =
            githubJsonRequest(
                method = "POST",
                apiPath =
                    "/repos/$OWNER/$REPO/actions/workflows/$workflowId/dispatches",
                accessToken = accessToken,
                body = JSONObject().put("ref", BRANCH)
            )

        val dispatched =
            dispatch.optBoolean("request_dispatched", false)

        if (!dispatch.optBoolean("http_success", false)) {
            return failure(
                "GitHub Actions workflow dispatch не подтверждён: HTTP ${dispatch.optInt("http_code", -1)} ${dispatch.optString("error").take(240)}"
            )
                .put("status", "workflow_dispatch_failed")
                .put("http_code", dispatch.optInt("http_code", -1))
                .put("action_dispatched", dispatched)
                .put("action_committed", dispatched)
                .put("reconciliation_complete", !dispatched)
                .put(
                    "side_effect_state",
                    if (dispatched) "DISPATCHED_UNVERIFIED" else "NONE"
                )
                .put("side_effect_kind", "github_actions_build_dispatch")
        }

        val dispatchBody =
            dispatch.optJSONObject("body")
                ?: JSONObject()

        val directRunId =
            dispatchBody.optLong("workflow_run_id", 0L)

        if (directRunId > 0L) {
            // GitHub API 2026-03-10 returns the created workflow run identity
            // directly from workflow_dispatch. Persist it immediately so STOP or
            // process loss can reconcile by read-only run lookup without re-dispatch.
            prefs.edit()
                .putLong(KEY_LAST_BUILD_RUN_ID, directRunId)
                .putString(KEY_LAST_BUILD_HEAD_SHA, expectedHeadSha)
                .putLong(KEY_LAST_BUILD_WORKFLOW_ID, workflowId)
                .putString(KEY_LAST_BUILD_RUN_URL, dispatchBody.optString("html_url"))
                .putLong(KEY_LAST_BUILD_STARTED_AT, System.currentTimeMillis())
                .putString(KEY_LAST_BUILD_STATUS, "dispatched")
                .putString(KEY_LAST_BUILD_CONCLUSION, "")
                .apply()
        }

        val runLookupDeadline =
            System.currentTimeMillis() + BUILD_RUN_DISCOVERY_TIMEOUT_MS

        var run: JSONObject? = null

        while (System.currentTimeMillis() <= runLookupDeadline) {
            if (shouldCancel()) {
                return failure(
                    "GitHub Actions workflow уже отправлен, но локальное ожидание остановлено. Повторять dispatch автоматически нельзя; используйте «проверь сборку GitHub»."
                )
                    .put("status", "build_monitor_cancelled_after_dispatch")
                    .put("run_id", directRunId)
                    .put("action_dispatched", true)
                    .put("action_committed", true)
                    .put("reconciliation_complete", false)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_actions_build_dispatch")
            }

            if (directRunId > 0L) {
                val direct =
                    githubJsonRequest(
                        method = "GET",
                        apiPath = "/repos/$OWNER/$REPO/actions/runs/$directRunId",
                        accessToken = accessToken
                    )

                if (direct.optBoolean("http_success", false)) {
                    val item = direct.optJSONObject("body") ?: JSONObject()
                    val exactIdentity =
                        item.optLong("id", 0L) == directRunId &&
                            item.optLong("workflow_id", 0L) == workflowId &&
                            item.optString("event") == "workflow_dispatch" &&
                            item.optString("head_branch") == BRANCH &&
                            item.optString("head_sha") == expectedHeadSha &&
                            item.optString("name") == BUILD_WORKFLOW_NAME

                    if (!exactIdentity) {
                        return failure(
                            "GitHub workflow_dispatch вернул run id, но его workflow/branch/head identity не совпала с подготовленным build."
                        )
                            .put("status", "build_run_identity_mismatch")
                            .put("run_id", directRunId)
                            .put("action_dispatched", true)
                            .put("action_committed", true)
                            .put("reconciliation_complete", true)
                            .put("side_effect_state", "VERIFIED_COMMITTED")
                            .put("side_effect_kind", "github_actions_build_dispatch")
                    }

                    run = item
                    break
                }
            } else {
                // Compatibility fallback for a GitHub deployment that acknowledges
                // workflow_dispatch without returning the run id. Correlate only by
                // exact new run + workflow + event + branch + head SHA. Never guess.
                val listed =
                    listWorkflowRuns(
                        accessToken = accessToken,
                        workflowId = workflowId,
                        headSha = expectedHeadSha
                    )

                if (listed.optBoolean("success", false)) {
                    val candidates = mutableListOf<JSONObject>()
                    val runs = listed.optJSONArray("runs") ?: JSONArray()
                    for (index in 0 until runs.length()) {
                        val item = runs.optJSONObject(index) ?: continue
                        val id = item.optLong("id", 0L)
                        if (
                            id > 0L &&
                            id !in baselineIds &&
                            item.optLong("workflow_id", 0L) == workflowId &&
                            item.optString("event") == "workflow_dispatch" &&
                            item.optString("head_branch") == BRANCH &&
                            item.optString("head_sha") == expectedHeadSha &&
                            item.optString("name") == BUILD_WORKFLOW_NAME
                        ) {
                            candidates += item
                        }
                    }

                    if (candidates.size > 1) {
                        return failure(
                            "После workflow dispatch найдено несколько новых подходящих build runs. AYANA не будет угадывать, какой из них её."
                        )
                            .put("status", "build_run_ambiguous")
                            .put("candidate_count", candidates.size)
                            .put("action_dispatched", true)
                            .put("action_committed", true)
                            .put("reconciliation_complete", false)
                            .put("side_effect_state", "VERIFIED_COMMITTED")
                            .put("side_effect_kind", "github_actions_build_dispatch")
                    }

                    if (candidates.size == 1) {
                        run = candidates.first()
                        break
                    }
                }
            }

            try {
                Thread.sleep(BUILD_RUN_DISCOVERY_POLL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        if (run == null) {
            return failure(
                "Workflow dispatch принят GitHub, но точный build run не удалось доказательно прочитать. Повторный dispatch запрещён; используйте «проверь сборку GitHub»."
            )
                .put("status", "build_run_not_identified")
                .put("run_id", directRunId)
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_actions_build_dispatch")
        }

        val identifiedRun = run

        val runId = identifiedRun.optLong("id", 0L)
        val runUrl = identifiedRun.optString("html_url")
        val runNumber = identifiedRun.optLong("run_number", 0L)

        prefs.edit()
            .putLong(KEY_LAST_BUILD_RUN_ID, runId)
            .putString(KEY_LAST_BUILD_HEAD_SHA, expectedHeadSha)
            .putLong(KEY_LAST_BUILD_WORKFLOW_ID, workflowId)
            .putString(KEY_LAST_BUILD_RUN_URL, runUrl)
            .putLong(KEY_LAST_BUILD_STARTED_AT, System.currentTimeMillis())
            .putString(KEY_LAST_BUILD_STATUS, identifiedRun.optString("status"))
            .putString(KEY_LAST_BUILD_CONCLUSION, identifiedRun.optString("conclusion"))
            .apply()

        val buildDeadline =
            System.currentTimeMillis() + BUILD_COMPLETION_TIMEOUT_MS

        while (System.currentTimeMillis() <= buildDeadline) {
            if (shouldCancel()) {
                return JSONObject()
                    .put("success", false)
                    .put("verified", true)
                    .put("status", "build_monitor_cancelled")
                    .put("action_dispatched", true)
                    .put("action_committed", true)
                    .put("reconciliation_complete", false)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_actions_build_dispatch")
                    .put("repository", REPOSITORY_SLUG)
                    .put("branch", BRANCH)
                    .put("workflow_id", workflowId)
                    .put("workflow_name", BUILD_WORKFLOW_NAME)
                    .put("head_sha", expectedHeadSha)
                    .put("run_id", runId)
                    .put("run_number", runNumber)
                    .put("run_url", runUrl)
                    .put(
                        "message",
                        "Локальное ожидание остановлено, но GitHub Actions run уже запущен. Повторять dispatch нельзя; позже скажите «проверь сборку GitHub»."
                    )
            }

            val inspected =
                inspectBuildRun(
                    accessToken = accessToken,
                    runId = runId,
                    expectedHeadSha = expectedHeadSha,
                    expectedWorkflowId = workflowId
                )

            val inspectedStatus =
                inspected.optString("status")

            if (inspectedStatus == "verified_apk_build") {
                return inspected
                    .put("action_dispatched", true)
                    .put("action_committed", true)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_actions_build_dispatch")
            }

            if (
                inspected.optString("build_status") == "completed" &&
                inspected.optString("build_conclusion") != "success"
            ) {
                return inspected
                    .put("action_dispatched", true)
                    .put("action_committed", true)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_actions_build_dispatch")
            }

            val transientArtifactState =
                inspectedStatus in
                    setOf(
                        "apk_artifact_pending",
                        "apk_artifact_digest_pending"
                    )

            if (
                !inspected.optBoolean("success", false) &&
                inspectedStatus !in setOf("build_running", "build_queued") &&
                !transientArtifactState
            ) {
                val definitiveFailure =
                    inspectedStatus in
                        setOf(
                            "build_failed",
                            "apk_artifact_ambiguous",
                            "apk_artifact_provenance_mismatch",
                            "build_run_identity_mismatch"
                        )

                return inspected
                    .put("action_dispatched", true)
                    .put("action_committed", true)
                    .put("reconciliation_complete", definitiveFailure)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_actions_build_dispatch")
            }

            try {
                Thread.sleep(BUILD_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return JSONObject()
            .put("success", false)
            .put("verified", true)
            .put("status", "build_reconciliation_timeout")
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", false)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "github_actions_build_dispatch")
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("workflow_id", workflowId)
            .put("workflow_name", BUILD_WORKFLOW_NAME)
            .put("head_sha", expectedHeadSha)
            .put("run_id", runId)
            .put("run_number", runNumber)
            .put("run_url", runUrl)
            .put(
                "message",
                "GitHub Actions build/artifact proof не завершился в лимит ожидания. Повторять dispatch нельзя; используйте «проверь сборку GitHub»."
            )
    }

    private fun inspectBuildRun(
        accessToken: String,
        runId: Long,
        expectedHeadSha: String,
        expectedWorkflowId: Long
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath = "/repos/$OWNER/$REPO/actions/runs/$runId",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Не удалось прочитать GitHub Actions run $runId: HTTP ${response.optInt("http_code", -1)}."
            )
                .put("status", "build_run_read_failed")
                .put("run_id", runId)
        }

        val run = response.optJSONObject("body") ?: JSONObject()
        val status = run.optString("status")
        val conclusion = run.optString("conclusion")
        val headSha = run.optString("head_sha")
        val workflowName = run.optString("name")
        val workflowId = run.optLong("workflow_id", 0L)
        val event = run.optString("event")
        val branch = run.optString("head_branch")
        val identityVerified =
            headSha.isNotBlank() &&
                (expectedHeadSha.isBlank() || headSha == expectedHeadSha) &&
                workflowName == BUILD_WORKFLOW_NAME &&
                workflowId > 0L &&
                (expectedWorkflowId <= 0L || workflowId == expectedWorkflowId) &&
                branch == BRANCH &&
                event == "workflow_dispatch"

        prefs.edit()
            .putLong(KEY_LAST_BUILD_RUN_ID, runId)
            .putString(KEY_LAST_BUILD_HEAD_SHA, headSha)
            .putString(KEY_LAST_BUILD_RUN_URL, run.optString("html_url"))
            .putString(KEY_LAST_BUILD_STATUS, status)
            .putString(KEY_LAST_BUILD_CONCLUSION, conclusion)
            .apply()

        if (!identityVerified) {
            return failure(
                "GitHub Actions run не соответствует ожидаемому workflow/branch/head SHA."
            )
                .put("status", "build_run_identity_mismatch")
                .put("run_id", runId)
                .put("workflow_name", workflowName)
                .put("workflow_id", workflowId)
                .put("expected_workflow_id", expectedWorkflowId)
                .put("head_sha", headSha)
                .put("branch", branch)
                .put("event", event)
        }

        if (status != "completed") {
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put(
                    "status",
                    if (status == "queued" || status == "waiting" || status == "pending") {
                        "build_queued"
                    } else {
                        "build_running"
                    }
                )
                .put("build_status", status)
                .put("build_conclusion", conclusion)
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_name", BUILD_WORKFLOW_NAME)
                .put("workflow_id", workflowId)
                .put("head_sha", headSha)
                .put("run_id", runId)
                .put("run_number", run.optLong("run_number", 0L))
                .put("run_url", run.optString("html_url"))
                .put(
                    "message",
                    "GitHub Actions сборка ${run.optLong("run_number", 0L)}: $status."
                )
        }

        if (conclusion != "success") {
            prefs.edit()
                .putBoolean(KEY_DEVICE_CONFIRMED_BUILD, false)
                .apply()

            return JSONObject()
                .put("success", false)
                .put("verified", true)
                .put("terminal_status", "ERROR")
                .put("status", "build_failed")
                .put("build_status", status)
                .put("build_conclusion", conclusion)
                .put("artifact_verified", false)
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_name", BUILD_WORKFLOW_NAME)
                .put("workflow_id", workflowId)
                .put("head_sha", headSha)
                .put("run_id", runId)
                .put("run_number", run.optLong("run_number", 0L))
                .put("run_url", run.optString("html_url"))
                .put(
                    "message",
                    "GitHub Actions сборка завершилась неуспешно: conclusion=${conclusion.ifBlank { "unknown" }}."
                )
        }

        val artifact = verifyBuildArtifact(
            accessToken = accessToken,
            runId = runId,
            expectedHeadSha = headSha
        )

        if (!artifact.optBoolean("success", false)) {
            return artifact
                .put("terminal_status", "ERROR")
                .put("build_status", status)
                .put("build_conclusion", conclusion)
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_name", BUILD_WORKFLOW_NAME)
                .put("workflow_id", workflowId)
                .put("run_id", runId)
                .put("run_number", run.optLong("run_number", 0L))
                .put("run_url", run.optString("html_url"))
                .put("head_sha", headSha)
        }

        if (!artifact.optBoolean("artifact_verified", false)) {
            return artifact
                .put("build_status", status)
                .put("build_conclusion", conclusion)
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_name", BUILD_WORKFLOW_NAME)
                .put("workflow_id", workflowId)
                .put("head_sha", headSha)
                .put("run_id", runId)
                .put("run_number", run.optLong("run_number", 0L))
                .put("run_url", run.optString("html_url"))
        }

        val artifactId = artifact.optLong("artifact_id", 0L)
        val artifactDigest = artifact.optString("artifact_digest")
        val artifactSize = artifact.optLong("artifact_size_bytes", 0L)

        prefs.edit()
            .putBoolean(KEY_DEVICE_CONFIRMED_BUILD, true)
            .putLong(KEY_LAST_BUILD_ARTIFACT_ID, artifactId)
            .putString(KEY_LAST_BUILD_ARTIFACT_DIGEST, artifactDigest)
            .putLong(KEY_LAST_BUILD_ARTIFACT_SIZE, artifactSize)
            .putLong(KEY_LAST_BUILD_VERIFIED_AT, System.currentTimeMillis())
            .apply()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "verified_apk_build")
            .put("build_status", status)
            .put("build_conclusion", conclusion)
            .put("build_completed", true)
            .put("artifact_verified", true)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("workflow_name", BUILD_WORKFLOW_NAME)
            .put("workflow_id", workflowId)
            .put("head_sha", headSha)
            .put("run_id", runId)
            .put("run_number", run.optLong("run_number", 0L))
            .put("run_url", run.optString("html_url"))
            .put("artifact_id", artifactId)
            .put("artifact_name", APK_ARTIFACT_NAME)
            .put("artifact_size_bytes", artifactSize)
            .put("artifact_digest", artifactDigest)
            .put("artifact_url", artifact.optString("artifact_url"))
            .put(
                "message",
                "GitHub Actions APK build подтверждён: run=${run.optLong("run_number", 0L)}, artifact=$APK_ARTIFACT_NAME, size=$artifactSize, digest=${artifactDigest.take(20)}…"
            )
    }

    private fun installationAuthority(
        accessToken: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath = "/user/installations?per_page=100",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Не удалось проверить GitHub App installation permissions."
            )
                .put("status", "installation_permissions_unavailable")
                .put("http_code", response.optInt("http_code", -1))
        }

        val body = response.optJSONObject("body") ?: JSONObject()
        val installations = body.optJSONArray("installations") ?: JSONArray()

        for (index in 0 until installations.length()) {
            val installation = installations.optJSONObject(index) ?: continue
            val account =
                installation.optJSONObject("account")
                    ?.optString("login")
                    .orEmpty()

            if (!account.equals(OWNER, ignoreCase = true)) continue

            val installationId = installation.optLong("id", 0L)
            if (installationId <= 0L) continue

            val repos =
                githubJsonRequest(
                    method = "GET",
                    apiPath =
                        "/user/installations/$installationId/repositories?per_page=100",
                    accessToken = accessToken
                )

            if (!repos.optBoolean("http_success", false)) continue

            val repoArray =
                repos.optJSONObject("body")
                    ?.optJSONArray("repositories")
                    ?: JSONArray()

            var selected = false
            for (repoIndex in 0 until repoArray.length()) {
                val repo = repoArray.optJSONObject(repoIndex) ?: continue
                if (
                    repo.optString("full_name")
                        .equals(REPOSITORY_SLUG, ignoreCase = true)
                ) {
                    selected = true
                    break
                }
            }

            if (!selected) continue

            val permissions =
                installation.optJSONObject("permissions")
                    ?: JSONObject()

            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("installation_id", installationId)
                .put("repository_selected", true)
                .put("contents_permission", permissions.optString("contents", "none"))
                .put("actions_permission", permissions.optString("actions", "none"))
                .put("repository_selection", installation.optString("repository_selection"))
                .put("app_slug", installation.optString("app_slug"))
        }

        return failure(
            "Не найдена GitHub App installation, которая одновременно охватывает $REPOSITORY_SLUG."
        )
            .put("status", "target_installation_not_found")
    }

    private fun findBuildWorkflow(
        accessToken: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath = "/repos/$OWNER/$REPO/actions/workflows?per_page=100",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Не удалось прочитать GitHub Actions workflows: HTTP ${response.optInt("http_code", -1)}."
            )
                .put("status", "workflow_list_failed")
                .put("http_code", response.optInt("http_code", -1))
        }

        val workflows =
            response.optJSONObject("body")
                ?.optJSONArray("workflows")
                ?: JSONArray()

        val matches = mutableListOf<JSONObject>()

        for (index in 0 until workflows.length()) {
            val workflow = workflows.optJSONObject(index) ?: continue
            if (workflow.optString("name") == BUILD_WORKFLOW_NAME) {
                matches += workflow
            }
        }

        if (matches.size != 1) {
            return failure(
                if (matches.isEmpty()) {
                    "Workflow «$BUILD_WORKFLOW_NAME» не найден."
                } else {
                    "Найдено несколько workflows с именем «$BUILD_WORKFLOW_NAME»; выбор неоднозначен."
                }
            )
                .put(
                    "status",
                    if (matches.isEmpty()) "build_workflow_missing" else "build_workflow_ambiguous"
                )
                .put("match_count", matches.size)
        }

        val workflow = matches.first()
        val workflowId = workflow.optLong("id", 0L)
        val state = workflow.optString("state")
        if (workflowId <= 0L || state != "active") {
            return failure(
                "Workflow «$BUILD_WORKFLOW_NAME» найден, но не active."
            )
                .put("status", "build_workflow_inactive")
                .put("workflow_id", workflowId)
                .put("workflow_state", state)
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("workflow_id", workflowId)
            .put("workflow_name", workflow.optString("name"))
            .put("workflow_state", state)
            .put("workflow_path", workflow.optString("path"))
            .put("workflow_html_url", workflow.optString("html_url"))
    }

    private fun readBranchHead(
        accessToken: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath = "/repos/$OWNER/$REPO/commits/${urlEncode(BRANCH)}",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Не удалось прочитать текущий head $BRANCH перед сборкой."
            )
                .put("status", "branch_head_read_failed")
                .put("http_code", response.optInt("http_code", -1))
        }

        val sha =
            response.optJSONObject("body")
                ?.optString("sha")
                .orEmpty()
                .trim()

        if (!FULL_GIT_SHA.matches(sha)) {
            return failure(
                "GitHub вернул некорректный head SHA для $BRANCH."
            )
                .put("status", "invalid_branch_head_sha")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("head_sha", sha)
    }

    private fun listWorkflowRuns(
        accessToken: String,
        workflowId: Long,
        headSha: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath =
                    "/repos/$OWNER/$REPO/actions/workflows/$workflowId/runs" +
                        "?event=workflow_dispatch" +
                        "&branch=${urlEncode(BRANCH)}" +
                        "&head_sha=${urlEncode(headSha)}" +
                        "&per_page=20",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Не удалось прочитать workflow runs: HTTP ${response.optInt("http_code", -1)}."
            )
                .put("status", "workflow_runs_read_failed")
                .put("http_code", response.optInt("http_code", -1))
        }

        val runs =
            response.optJSONObject("body")
                ?.optJSONArray("workflow_runs")
                ?: JSONArray()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("runs", runs)
    }

    private fun verifyBuildArtifact(
        accessToken: String,
        runId: Long,
        expectedHeadSha: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath = "/repos/$OWNER/$REPO/actions/runs/$runId/artifacts?per_page=100",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Build завершён, но список GitHub Actions artifacts недоступен."
            )
                .put("status", "build_artifact_list_failed")
                .put("artifact_verified", false)
        }

        val artifacts =
            response.optJSONObject("body")
                ?.optJSONArray("artifacts")
                ?: JSONArray()

        val matches = mutableListOf<JSONObject>()
        for (index in 0 until artifacts.length()) {
            val artifact = artifacts.optJSONObject(index) ?: continue
            if (
                artifact.optString("name") == APK_ARTIFACT_NAME &&
                !artifact.optBoolean("expired", true)
            ) {
                matches += artifact
            }
        }

        if (matches.isEmpty()) {
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("status", "apk_artifact_pending")
                .put("artifact_verified", false)
                .put("artifact_match_count", 0)
                .put(
                    "message",
                    "GitHub Actions run уже успешен; ожидаю публикацию artifact «$APK_ARTIFACT_NAME»."
                )
        }

        if (matches.size > 1) {
            return failure(
                "В build run найдено несколько artifacts «$APK_ARTIFACT_NAME»; результат неоднозначен."
            )
                .put("status", "apk_artifact_ambiguous")
                .put("artifact_verified", false)
                .put("artifact_match_count", matches.size)
        }

        val artifact = matches.first()
        val artifactId = artifact.optLong("id", 0L)
        val size = artifact.optLong("size_in_bytes", 0L)
        val digest = artifact.optString("digest").trim()
        val workflowRun = artifact.optJSONObject("workflow_run") ?: JSONObject()

        val provenanceVerified =
            artifactId > 0L &&
                workflowRun.optLong("id", 0L) == runId &&
                workflowRun.optString("head_branch") == BRANCH &&
                workflowRun.optString("head_sha") == expectedHeadSha

        if (!provenanceVerified) {
            return failure(
                "APK artifact найден, но его run/head provenance не совпадает с подтверждённой сборкой."
            )
                .put("status", "apk_artifact_provenance_mismatch")
                .put("artifact_verified", false)
                .put("artifact_id", artifactId)
                .put("artifact_size_bytes", size)
                .put("artifact_digest", digest)
        }

        if (size <= 0L || !ARTIFACT_SHA256.matches(digest)) {
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("status", "apk_artifact_digest_pending")
                .put("artifact_verified", false)
                .put("artifact_id", artifactId)
                .put("artifact_size_bytes", size)
                .put("artifact_digest", digest)
                .put(
                    "message",
                    "APK artifact уже виден, но immutable size/digest proof ещё не готов."
                )
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("artifact_verified", true)
            .put("artifact_id", artifactId)
            .put("artifact_name", APK_ARTIFACT_NAME)
            .put("artifact_size_bytes", size)
            .put("artifact_digest", digest)
            .put("artifact_url", artifact.optString("url"))
            .put("archive_download_url", artifact.optString("archive_download_url"))
    }

    fun runtimeSnapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val lastVerifiedAt = prefs.getLong(KEY_LAST_VERIFIED_AT, 0L)
        val fresh =
            lastVerifiedAt > 0L &&
                now - lastVerifiedAt <= RUNTIME_FRESHNESS_MS

        return JSONObject()
            .put("version", VERSION)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("client_id_configured", configuredClientId().isNotBlank())
            .put("token_present", secureGet(KEY_ACCESS_TOKEN).isNotBlank())
            .put("authorization_pending", hasPendingAuthorization())
            .put("connected", prefs.getBoolean(KEY_LAST_CONNECTED, false) && fresh)
            .put("repository_write_available", prefs.getBoolean(KEY_LAST_WRITE_AVAILABLE, false) && fresh)
            .put("last_verified_at_ms", lastVerifiedAt)
            .put("runtime_fresh", fresh)
            .put("device_confirmed_write", prefs.getBoolean(KEY_DEVICE_CONFIRMED_WRITE, false))
            .put("last_commit_sha", prefs.getString(KEY_LAST_COMMIT_SHA, "").orEmpty())
            .put("actions_permission", prefs.getString(KEY_LAST_ACTIONS_PERMISSION, "unknown").orEmpty())
            .put("actions_write_available", prefs.getString(KEY_LAST_ACTIONS_PERMISSION, "") == "write")
            .put("actions_last_verified_at_ms", prefs.getLong(KEY_LAST_ACTIONS_VERIFIED_AT, 0L))
            .put("android_apk_build_implemented", true)
            .put("device_confirmed_build", prefs.getBoolean(KEY_DEVICE_CONFIRMED_BUILD, false))
            .put("last_build_run_id", prefs.getLong(KEY_LAST_BUILD_RUN_ID, 0L))
            .put("last_build_head_sha", prefs.getString(KEY_LAST_BUILD_HEAD_SHA, "").orEmpty())
            .put("last_build_run_url", prefs.getString(KEY_LAST_BUILD_RUN_URL, "").orEmpty())
            .put("last_build_status", prefs.getString(KEY_LAST_BUILD_STATUS, "").orEmpty())
            .put("last_build_conclusion", prefs.getString(KEY_LAST_BUILD_CONCLUSION, "").orEmpty())
            .put("last_build_artifact_id", prefs.getLong(KEY_LAST_BUILD_ARTIFACT_ID, 0L))
            .put("last_build_artifact_digest", prefs.getString(KEY_LAST_BUILD_ARTIFACT_DIGEST, "").orEmpty())
            .put("last_build_artifact_size_bytes", prefs.getLong(KEY_LAST_BUILD_ARTIFACT_SIZE, 0L))
            .put("last_build_verified_at_ms", prefs.getLong(KEY_LAST_BUILD_VERIFIED_AT, 0L))
            .put("last_error", prefs.getString(KEY_LAST_ERROR, "").orEmpty())
    }

    fun compactContext(): String {
        val runtime = runtimeSnapshot()
        return buildString {
            append("AYANA R10.27.2 GITHUB/ACTIONS TRUTH: ")
            append("github_repository_write_implemented=true; ")
            append("github_commit_push_implemented=true; ")
            append("android_apk_build_implemented=true; ")
            append("repository=")
            append(REPOSITORY_SLUG)
            append("; branch=")
            append(BRANCH)
            append("; connected=")
            append(runtime.optBoolean("connected", false))
            append("; write_available=")
            append(runtime.optBoolean("repository_write_available", false))
            append("; device_confirmed_write=")
            append(runtime.optBoolean("device_confirmed_write", false))
            append("; actions_permission=")
            append(runtime.optString("actions_permission", "unknown"))
            append("; actions_write_available=")
            append(runtime.optBoolean("actions_write_available", false))
            append("; device_confirmed_build=")
            append(runtime.optBoolean("device_confirmed_build", false))
            append(". GitHub file mutation remains two-phase. APK build is a separate two-phase authority: prepare is read-only, confirmed replay dispatches exactly «")
            append(BUILD_WORKFLOW_NAME)
            append("» on main and SUCCESS requires completed conclusion=success plus verified artifact «")
            append(APK_ARTIFACT_NAME)
            append("» with non-zero size and SHA-256 artifact digest. direct_apk_delivery=false; development_agent_transaction=false.")
        }
    }

    fun selfTest(): Boolean {
        val okPath = validatePath("app/src/main/java/kg/autonomous/agent/Test.kt")
        val badTraversal = validatePath("../secret.txt")
        val badSecret = validatePath("app/ayana-release.jks")
        val bytes = "hello".toByteArray(StandardCharsets.UTF_8)
        val knownBlob = gitBlobSha(bytes)
        return okPath.optBoolean("success", false) &&
            !badTraversal.optBoolean("success", true) &&
            !badSecret.optBoolean("success", true) &&
            knownBlob == "b6fc4c620b67d95f953a5c1c1230aaab5db5a1b0" &&
            FULL_GIT_SHA.matches("d9021dd8d9fff77fec938ccc2119e1393baf85ce") &&
            ARTIFACT_SHA256.matches(
                "sha256:cfc3236bdad15b5898bca8408945c9e19e1917da8704adc20eaa618444290a8c"
            )
    }

    private fun prepareWrite(
        path: String,
        contentBytes: ByteArray,
        commitMessage: String
    ): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
        }

        val accessToken = tokenResult.optString("access_token")
        val current = readRepositoryPath(path, accessToken)

        if (!current.optBoolean("success", false)) {
            return current
        }

        val exists = current.optBoolean("exists", false)
        val currentSha = current.optString("sha")
        val proposedBlobSha = gitBlobSha(contentBytes)

        if (exists && currentSha == proposedBlobSha) {
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("status", "no_change")
                .put("no_change", true)
                .put("requires_confirmation", false)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("path", path)
                .put("current_sha", currentSha)
                .put("proposed_blob_sha", proposedBlobSha)
                .put("message", "GitHub файл уже содержит точно такой же контент; commit не нужен.")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "prepared_waiting_confirmation")
            .put("requires_confirmation", true)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("path", path)
            .put("expected_sha", currentSha)
            .put("expected_missing", !exists)
            .put("proposed_blob_sha", proposedBlobSha)
            .put("prepared_path", path)
            .put("prepared_commit_message", commitMessage)
            .put("content_bytes", contentBytes.size)
            .put(
                "message",
                if (exists) {
                    "GitHub изменение подготовлено для $path. Commit ещё НЕ выполнен. Для записи в $BRANCH скажите: «подтверждаю текущую задачу»."
                } else {
                    "Создание GitHub файла $path подготовлено. Commit ещё НЕ выполнен. Для записи в $BRANCH скажите: «подтверждаю текущую задачу»."
                }
            )
    }

    private fun commitPrepared(
        path: String,
        contentBytes: ByteArray,
        commitMessage: String,
        expectedSha: String,
        expectedMissing: Boolean,
        proposedBlobSha: String
    ): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
        }

        val accessToken = tokenResult.optString("access_token")
        val current = readRepositoryPath(path, accessToken)

        if (!current.optBoolean("success", false)) {
            return current
        }

        val existsNow = current.optBoolean("exists", false)
        val shaNow = current.optString("sha")

        val stateMatches =
            if (expectedMissing) {
                !existsNow
            } else {
                existsNow && expectedSha.isNotBlank() && shaNow == expectedSha
            }

        if (!stateMatches) {
            return failure(
                "GitHub commit остановлен: файл изменился после подготовки. Нужна новая подготовка и новое подтверждение."
            )
                .put("status", "repository_state_changed")
                .put("requires_confirmation", false)
                .put("expected_sha", expectedSha)
                .put("actual_sha", shaNow)
                .put("expected_missing", expectedMissing)
                .put("actual_exists", existsNow)
        }

        val body =
            JSONObject()
                .put("message", commitMessage)
                .put("content", Base64.encodeToString(contentBytes, Base64.NO_WRAP))
                .put("branch", BRANCH)

        if (!expectedMissing) {
            body.put("sha", expectedSha)
        }

        val response =
            githubJsonRequest(
                method = "PUT",
                apiPath = "/repos/$OWNER/$REPO/contents/${encodePath(path)}",
                accessToken = accessToken,
                body = body
            )

        if (!response.optBoolean("http_success", false)) {
            val code = response.optInt("http_code", -1)
            val requestDispatched = response.optBoolean("request_dispatched", false)

            // A transport error after request bytes were written is an uncertain
            // side-effect outcome. Never blind-retry a PUT. Re-read repository
            // state and reconcile against the exact proposed Git blob SHA.
            if (requestDispatched) {
                val reconciled =
                    reconcileCommitAfterDispatch(
                        path = path,
                        accessToken = accessToken,
                        commitMessage = commitMessage,
                        proposedBlobSha = proposedBlobSha
                    )

                if (
                    reconciled.optBoolean("success", false) ||
                    reconciled.optBoolean("action_committed", false)
                ) {
                    return reconciled
                        .put("original_http_code", code)
                        .put("original_error", response.optString("error").take(240))
                }
            }

            return failure(
                "GitHub commit не подтверждён: HTTP $code ${response.optString("error").take(240)}"
            )
                .put("status", if (code == 409) "repository_conflict" else "github_commit_failed")
                .put("http_code", code)
                .put("action_dispatched", requestDispatched)
                .put("action_committed", false)
                .put("reconciliation_complete", !requestDispatched)
                .put(
                    "side_effect_state",
                    if (requestDispatched) "DISPATCHED_UNVERIFIED" else "NONE"
                )
        }

        val responseBody = response.optJSONObject("body") ?: JSONObject()
        val content = responseBody.optJSONObject("content") ?: JSONObject()
        val commit = responseBody.optJSONObject("commit") ?: JSONObject()
        val contentSha = content.optString("sha").trim()
        val commitSha = commit.optString("sha").trim()
        val verified =
            contentSha.isNotBlank() &&
                contentSha == proposedBlobSha &&
                commitSha.isNotBlank()

        if (!verified) {
            return failure(
                "GitHub ответил на commit, но итоговый blob/commit SHA не удалось подтвердить. Повторять запись автоматически нельзя."
            )
                .put("status", "commit_outcome_unverified")
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("content_sha", contentSha)
                .put("expected_blob_sha", proposedBlobSha)
                .put("commit_sha", commitSha)
                .put("side_effect_state", "COMMITTED_UNVERIFIED")
        }

        prefs.edit()
            .putBoolean(KEY_DEVICE_CONFIRMED_WRITE, true)
            .putString(KEY_LAST_COMMIT_SHA, commitSha)
            .apply()

        recordRuntime(
            connected = true,
            writeAvailable = true,
            error = ""
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "verified_committed")
            .put("requires_confirmation", false)
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "github_contents_commit")
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("path", path)
            .put("content_sha", contentSha)
            .put("commit_sha", commitSha)
            .put("commit_url", commit.optString("html_url"))
            .put("message", "GitHub commit подтверждён: $path → $BRANCH; commit=${commitSha.take(12)}.")
    }

    private fun reconcileCommitAfterDispatch(
        path: String,
        accessToken: String,
        commitMessage: String,
        proposedBlobSha: String
    ): JSONObject {
        val current = readRepositoryPath(path, accessToken)

        if (
            !current.optBoolean("success", false) ||
            !current.optBoolean("exists", false) ||
            current.optString("sha") != proposedBlobSha
        ) {
            return failure(
                "GitHub PUT был отправлен, но новое состояние репозитория не подтверждено. Автоматический повтор запрещён."
            )
                .put("status", "commit_dispatch_outcome_uncertain")
                .put("action_dispatched", true)
                .put("action_committed", false)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "DISPATCHED_UNVERIFIED")
        }

        val latest = readLatestCommitForPath(path, accessToken)
        val commitSha = latest.optString("commit_sha").trim()
        val latestMessage = latest.optString("commit_message").trim()
        val provenanceVerified =
            latest.optBoolean("success", false) &&
                commitSha.isNotBlank() &&
                latestMessage == commitMessage

        if (!provenanceVerified) {
            return failure(
                "GitHub файл уже содержит подтверждённый новый blob, но происхождение commit после транспортной ошибки не удалось однозначно доказать. Повторять PUT нельзя."
            )
                .put("status", "committed_state_verified_provenance_uncertain")
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "COMMITTED_UNVERIFIED")
                .put("content_sha", proposedBlobSha)
                .put("commit_sha", commitSha)
        }

        prefs.edit()
            .putBoolean(KEY_DEVICE_CONFIRMED_WRITE, true)
            .putString(KEY_LAST_COMMIT_SHA, commitSha)
            .apply()

        recordRuntime(
            connected = true,
            writeAvailable = true,
            error = ""
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "verified_committed_after_reconciliation")
            .put("requires_confirmation", false)
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "github_contents_commit")
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("path", path)
            .put("content_sha", proposedBlobSha)
            .put("commit_sha", commitSha)
            .put("message", "GitHub commit подтверждён повторным чтением после транспортной неопределённости: $path; commit=${commitSha.take(12)}.")
    }

    private fun readLatestCommitForPath(
        path: String,
        accessToken: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath =
                    "/repos/$OWNER/$REPO/commits?sha=" +
                        urlEncode(BRANCH) +
                        "&path=" +
                        urlEncode(path) +
                        "&per_page=1",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure("Не удалось прочитать последний GitHub commit для reconciliation.")
                .put("status", "commit_reconciliation_read_failed")
        }

        val array = response.optJSONArray("body_array")
        val first = array?.optJSONObject(0)
        if (first == null) {
            return failure("GitHub не вернул commit для изменённого path.")
                .put("status", "commit_reconciliation_missing")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("commit_sha", first.optString("sha"))
            .put(
                "commit_message",
                first.optJSONObject("commit")?.optString("message").orEmpty()
            )
    }

    private fun readRepositoryPath(
        path: String,
        accessToken: String
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath =
                    "/repos/$OWNER/$REPO/contents/${encodePath(path)}?ref=" +
                        urlEncode(BRANCH),
                accessToken = accessToken
            )

        val code = response.optInt("http_code", -1)

        if (code == 404) {
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("exists", false)
                .put("sha", "")
        }

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Не удалось прочитать GitHub path перед commit: HTTP $code."
            )
                .put("status", "github_read_failed")
                .put("http_code", code)
        }

        val body = response.optJSONObject("body") ?: JSONObject()
        val type = body.optString("type")
        if (type != "file") {
            return failure(
                "GitHub path не является обычным файлом: $path."
            )
                .put("status", "path_not_file")
        }

        val sha = body.optString("sha").trim()
        if (sha.isBlank()) {
            return failure(
                "GitHub не вернул SHA существующего файла."
            )
                .put("status", "missing_existing_sha")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("exists", true)
            .put("sha", sha)
            .put("size", body.optLong("size", -1L))
    }

    private fun pollPendingAuthorization(): JSONObject {
        if (!hasPendingAuthorization()) {
            return failure("Нет ожидающей GitHub Device Flow авторизации.")
                .put("status", "no_pending_authorization")
        }

        val now = System.currentTimeMillis()
        val expiresAt = prefs.getLong(KEY_PENDING_EXPIRES_AT, 0L)
        if (expiresAt <= now) {
            clearPendingAuthorization()
            return failure("Код GitHub Device Flow истёк. Запустите подключение заново.")
                .put("status", "device_code_expired")
        }

        val intervalMs = prefs.getLong(KEY_PENDING_INTERVAL_MS, 5000L).coerceAtLeast(5000L)
        val lastPollAt = prefs.getLong(KEY_PENDING_LAST_POLL_AT, 0L)
        if (lastPollAt > 0L && now - lastPollAt < intervalMs) {
            return JSONObject()
                .put("success", true)
                .put("verified", false)
                .put("status", "authorization_pending")
                .put("authorization_pending", true)
                .put("retry_after_ms", intervalMs - (now - lastPollAt))
        }

        val clientId = configuredClientId()
        val deviceCode = secureGet(KEY_PENDING_DEVICE_CODE)
        if (clientId.isBlank() || deviceCode.isBlank()) {
            clearPendingAuthorization()
            return failure("GitHub Device Flow state повреждён.")
                .put("status", "device_flow_state_invalid")
        }

        prefs.edit()
            .putLong(KEY_PENDING_LAST_POLL_AT, now)
            .apply()

        val response =
            postForm(
                url = "https://github.com/login/oauth/access_token",
                form =
                    linkedMapOf(
                        "client_id" to clientId,
                        "device_code" to deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
                    )
            )

        if (!response.optBoolean("http_success", false)) {
            return failure("GitHub Device Flow poll завершился HTTP ошибкой.")
                .put("status", "device_flow_poll_failed")
        }

        val body = response.optJSONObject("body") ?: JSONObject()
        val error = body.optString("error").trim()

        if (error.isNotBlank()) {
            if (error == "authorization_pending") {
                return JSONObject()
                    .put("success", true)
                    .put("verified", false)
                    .put("status", "authorization_pending")
                    .put("authorization_pending", true)
            }

            if (error == "slow_down") {
                prefs.edit()
                    .putLong(KEY_PENDING_INTERVAL_MS, intervalMs + 5000L)
                    .apply()
                return JSONObject()
                    .put("success", true)
                    .put("verified", false)
                    .put("status", "authorization_pending")
                    .put("authorization_pending", true)
                    .put("slow_down", true)
            }

            if (error in setOf("expired_token", "access_denied")) {
                clearPendingAuthorization()
            }

            return failure(
                "GitHub Device Flow: ${body.optString("error_description", error)}"
            )
                .put("status", error)
        }

        val accessToken = body.optString("access_token").trim()
        if (accessToken.isBlank()) {
            return failure("GitHub не вернул access token после авторизации.")
                .put("status", "access_token_missing")
        }

        val refreshToken = body.optString("refresh_token").trim()
        val accessExpiresIn = body.optLong("expires_in", 0L)
        val refreshExpiresIn = body.optLong("refresh_token_expires_in", 0L)

        securePut(KEY_ACCESS_TOKEN, accessToken)
        if (refreshToken.isNotBlank()) {
            securePut(KEY_REFRESH_TOKEN, refreshToken)
        } else {
            secureRemove(KEY_REFRESH_TOKEN)
        }

        val storedAt = System.currentTimeMillis()
        prefs.edit()
            .putLong(
                KEY_ACCESS_EXPIRES_AT,
                if (accessExpiresIn > 0L) storedAt + accessExpiresIn * 1000L else 0L
            )
            .putLong(
                KEY_REFRESH_EXPIRES_AT,
                if (refreshExpiresIn > 0L) storedAt + refreshExpiresIn * 1000L else 0L
            )
            .apply()

        clearPendingAuthorization()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "authorized")
            .put("authorized", true)
    }

    private fun ensureUsableAccessToken(): JSONObject {
        var token = secureGet(KEY_ACCESS_TOKEN)
        if (token.isBlank()) {
            return failure(
                if (hasPendingAuthorization()) {
                    "GitHub ожидает подтверждения Device Flow."
                } else {
                    "GitHub ещё не подключён."
                }
            )
                .put("status", if (hasPendingAuthorization()) "authorization_pending" else "not_authorized")
                .put("requires_user_authorization", true)
        }

        val expiresAt = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L)
        val now = System.currentTimeMillis()

        if (expiresAt > 0L && now + TOKEN_REFRESH_SKEW_MS >= expiresAt) {
            val refreshed = refreshAccessToken()
            if (!refreshed.optBoolean("success", false)) {
                return refreshed
            }
            token = secureGet(KEY_ACCESS_TOKEN)
        }

        if (token.isBlank()) {
            return failure("GitHub access token недоступен после refresh.")
                .put("status", "token_unavailable")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("access_token", token)
    }

    private fun hasUsableAccessToken(): Boolean {
        val token = secureGet(KEY_ACCESS_TOKEN)
        if (token.isBlank()) return false
        val expiresAt = prefs.getLong(KEY_ACCESS_EXPIRES_AT, 0L)
        return expiresAt <= 0L || System.currentTimeMillis() < expiresAt
    }

    private fun refreshAccessToken(): JSONObject {
        val clientId = configuredClientId()
        val refreshToken = secureGet(KEY_REFRESH_TOKEN)
        val refreshExpiresAt = prefs.getLong(KEY_REFRESH_EXPIRES_AT, 0L)

        if (
            clientId.isBlank() ||
            refreshToken.isBlank() ||
            (refreshExpiresAt > 0L && System.currentTimeMillis() >= refreshExpiresAt)
        ) {
            clearAuthorizationTokens()
            return failure(
                "GitHub авторизация истекла; требуется повторный Device Flow."
            )
                .put("status", "reauthorization_required")
                .put("requires_user_authorization", true)
        }

        val response =
            postForm(
                url = "https://github.com/login/oauth/access_token",
                form =
                    linkedMapOf(
                        "client_id" to clientId,
                        "grant_type" to "refresh_token",
                        "refresh_token" to refreshToken
                    )
            )

        if (!response.optBoolean("http_success", false)) {
            return failure("GitHub refresh token request завершился HTTP ошибкой.")
                .put("status", "refresh_http_failed")
        }

        val body = response.optJSONObject("body") ?: JSONObject()
        val error = body.optString("error").trim()
        if (error.isNotBlank()) {
            clearAuthorizationTokens()
            return failure(
                "GitHub token refresh отклонён: ${body.optString("error_description", error)}"
            )
                .put("status", error)
                .put("requires_user_authorization", true)
        }

        val accessToken = body.optString("access_token").trim()
        if (accessToken.isBlank()) {
            return failure("GitHub refresh не вернул access token.")
                .put("status", "refresh_token_missing_access")
        }

        val newRefresh = body.optString("refresh_token").trim()
        val accessExpiresIn = body.optLong("expires_in", 0L)
        val refreshExpiresIn = body.optLong("refresh_token_expires_in", 0L)
        val now = System.currentTimeMillis()

        securePut(KEY_ACCESS_TOKEN, accessToken)
        if (newRefresh.isNotBlank()) {
            securePut(KEY_REFRESH_TOKEN, newRefresh)
        }

        prefs.edit()
            .putLong(KEY_ACCESS_EXPIRES_AT, if (accessExpiresIn > 0L) now + accessExpiresIn * 1000L else 0L)
            .putLong(KEY_REFRESH_EXPIRES_AT, if (refreshExpiresIn > 0L) now + refreshExpiresIn * 1000L else refreshExpiresAt)
            .apply()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "token_refreshed")
    }

    private fun githubJsonRequest(
        method: String,
        apiPath: String,
        accessToken: String,
        body: JSONObject? = null
    ): JSONObject {
        var connection: HttpURLConnection? = null
        var requestDispatched = false

        return try {
            connection =
                URL("https://api.github.com$apiPath")
                    .openConnection() as HttpURLConnection

            connection.requestMethod = method
            connection.connectTimeout = 15_000
            connection.readTimeout = 35_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("Authorization", "Bearer $accessToken")
            connection.setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            connection.setRequestProperty("User-Agent", "AYANA-AI-Android/$VERSION")

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                val bytes = body.toString().toByteArray(StandardCharsets.UTF_8)
                connection.outputStream.use { it.write(bytes) }
                requestDispatched = true
            }

            val code = connection.responseCode
            val stream =
                if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsedObject =
                try {
                    if (text.isBlank() || text.trimStart().startsWith("[")) null else JSONObject(text)
                } catch (_: Exception) {
                    null
                }
            val parsedArray =
                try {
                    if (text.trimStart().startsWith("[")) org.json.JSONArray(text) else null
                } catch (_: Exception) {
                    null
                }
            val parsed =
                parsedObject ?: JSONObject().put("raw", text.take(2000))

            JSONObject()
                .put("http_success", code in 200..299)
                .put("http_code", code)
                .put("request_dispatched", requestDispatched)
                .put("body", parsed)
                .apply {
                    if (parsedArray != null) {
                        put("body_array", parsedArray)
                    }
                }
                .put(
                    "error",
                    if (code in 200..299) "" else parsed.optString("message", text.take(500))
                )
        } catch (error: Exception) {
            JSONObject()
                .put("http_success", false)
                .put("http_code", -1)
                .put("request_dispatched", requestDispatched)
                .put("body", JSONObject())
                .put("error", error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun postForm(
        url: String,
        form: LinkedHashMap<String, String>
    ): JSONObject {
        var connection: HttpURLConnection? = null

        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.doOutput = true
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            connection.setRequestProperty("User-Agent", "AYANA-AI-Android/$VERSION")

            val payload =
                form.entries.joinToString("&") { (key, value) ->
                    "${urlEncode(key)}=${urlEncode(value)}"
                }.toByteArray(StandardCharsets.UTF_8)

            connection.outputStream.use { it.write(payload) }

            val code = connection.responseCode
            val stream =
                if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed =
                try {
                    if (text.isBlank()) JSONObject() else JSONObject(text)
                } catch (_: Exception) {
                    JSONObject().put("raw", text.take(2000))
                }

            JSONObject()
                .put("http_success", code in 200..299)
                .put("http_code", code)
                .put("body", parsed)
                .put(
                    "error",
                    if (code in 200..299) "" else parsed.optString("error_description", text.take(500))
                )
        } catch (error: Exception) {
            JSONObject()
                .put("http_success", false)
                .put("http_code", -1)
                .put("body", JSONObject())
                .put("error", error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private fun validatePath(
        rawPath: String
    ): JSONObject {
        val path =
            rawPath.trim()
                .replace('\\', '/')
                .removePrefix("/")

        if (
            path.isBlank() ||
            path.length > MAX_PATH_CHARS ||
            path.contains("//") ||
            path.split('/').any { it == ".." || it == "." || it.isBlank() }
        ) {
            return failure("Некорректный путь GitHub файла.")
                .put("status", "invalid_path")
        }

        val lower = path.lowercase(Locale.ROOT)
        if (
            lower.endsWith(".jks") ||
            lower.endsWith(".keystore") ||
            lower.endsWith(".p12") ||
            lower.endsWith(".pfx") ||
            lower.endsWith(".pem") ||
            lower.endsWith(".key") ||
            lower.endsWith(".env") ||
            lower.contains("/secrets/") ||
            lower.contains("/credentials/")
        ) {
            return failure("Запись секретов/ключей в GitHub через AYANA запрещена.")
                .put("status", "sensitive_path_blocked")
        }

        return JSONObject()
            .put("success", true)
            .put("path", path)
    }

    private fun configuredClientId(): String =
        prefs.getString(KEY_CLIENT_ID, "").orEmpty().trim()

    private fun hasPendingAuthorization(): Boolean =
        secureGet(KEY_PENDING_DEVICE_CODE).isNotBlank() &&
            prefs.getLong(KEY_PENDING_EXPIRES_AT, 0L) > System.currentTimeMillis()

    private fun clearPendingAuthorization() {
        secureRemove(KEY_PENDING_DEVICE_CODE)
        prefs.edit()
            .remove(KEY_PENDING_USER_CODE)
            .remove(KEY_PENDING_VERIFICATION_URI)
            .remove(KEY_PENDING_EXPIRES_AT)
            .remove(KEY_PENDING_INTERVAL_MS)
            .remove(KEY_PENDING_LAST_POLL_AT)
            .apply()
    }

    private fun clearAuthorizationTokens() {
        secureRemove(KEY_ACCESS_TOKEN)
        secureRemove(KEY_REFRESH_TOKEN)
        prefs.edit()
            .remove(KEY_ACCESS_EXPIRES_AT)
            .remove(KEY_REFRESH_EXPIRES_AT)
            .putBoolean(KEY_LAST_CONNECTED, false)
            .putBoolean(KEY_LAST_WRITE_AVAILABLE, false)
            .apply()
    }

    private fun recordRuntime(
        connected: Boolean,
        writeAvailable: Boolean,
        error: String,
        actionsPermission: String? = null
    ) {
        val editor =
            prefs.edit()
                .putBoolean(KEY_LAST_CONNECTED, connected)
                .putBoolean(KEY_LAST_WRITE_AVAILABLE, writeAvailable)
                .putLong(KEY_LAST_VERIFIED_AT, System.currentTimeMillis())
                .putString(KEY_LAST_ERROR, error.take(400))

        if (actionsPermission != null) {
            editor
                .putString(KEY_LAST_ACTIONS_PERMISSION, actionsPermission)
                .putLong(KEY_LAST_ACTIONS_VERIFIED_AT, System.currentTimeMillis())
        }

        editor.apply()
    }

    private fun securePut(
        key: String,
        value: String
    ) {
        if (value.isBlank()) {
            secureRemove(key)
            return
        }

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload =
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + "." +
                Base64.encodeToString(encrypted, Base64.NO_WRAP)
        prefs.edit().putString(key, payload).apply()
    }

    private fun secureGet(
        key: String
    ): String {
        val payload = prefs.getString(key, "").orEmpty()
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
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun secureRemove(
        key: String
    ) {
        prefs.edit().remove(key).apply()
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore")
        keyStore.load(null)

        val existing = keyStore.getKey(KEYSTORE_ALIAS, null)
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
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )

        return generator.generateKey()
    }

    private fun gitBlobSha(
        bytes: ByteArray
    ): String {
        val header =
            "blob ${bytes.size}\u0000"
                .toByteArray(StandardCharsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(header)
        digest.update(bytes)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun encodePath(
        path: String
    ): String =
        path.split('/').joinToString("/") { urlEncode(it) }

    private fun urlEncode(
        value: String
    ): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")

    private fun failure(
        message: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("message", message)

    companion object {
        const val VERSION = "1.2"

        const val OWNER = "talant02031985-bot"
        const val REPO = "AUTONOMOUS-AI-AGENT"
        const val BRANCH = "main"
        const val REPOSITORY_SLUG = "$OWNER/$REPO"
        const val BUILD_WORKFLOW_NAME = "Build Android APK"
        const val APK_ARTIFACT_NAME = "AYANA-AI-signed-debug"

        private const val GITHUB_API_VERSION = "2026-03-10"
        private const val PREFS_NAME = "ayana_github_repository_executor_v1"
        private const val KEYSTORE_ALIAS = "ayana_github_repository_tokens_v1"

        private const val KEY_CLIENT_ID = "github_app_client_id"
        private const val KEY_ACCESS_TOKEN = "secure_access_token"
        private const val KEY_REFRESH_TOKEN = "secure_refresh_token"
        private const val KEY_ACCESS_EXPIRES_AT = "access_expires_at"
        private const val KEY_REFRESH_EXPIRES_AT = "refresh_expires_at"

        private const val KEY_PENDING_DEVICE_CODE = "secure_pending_device_code"
        private const val KEY_PENDING_USER_CODE = "pending_user_code"
        private const val KEY_PENDING_VERIFICATION_URI = "pending_verification_uri"
        private const val KEY_PENDING_EXPIRES_AT = "pending_expires_at"
        private const val KEY_PENDING_INTERVAL_MS = "pending_interval_ms"
        private const val KEY_PENDING_LAST_POLL_AT = "pending_last_poll_at"

        private const val KEY_LAST_CONNECTED = "last_connected"
        private const val KEY_LAST_WRITE_AVAILABLE = "last_write_available"
        private const val KEY_LAST_VERIFIED_AT = "last_verified_at"
        private const val KEY_LAST_ERROR = "last_error"
        private const val KEY_DEVICE_CONFIRMED_WRITE = "device_confirmed_write"
        private const val KEY_LAST_COMMIT_SHA = "last_commit_sha"

        private const val KEY_LAST_ACTIONS_PERMISSION = "last_actions_permission"
        private const val KEY_LAST_ACTIONS_VERIFIED_AT = "last_actions_verified_at"

        private const val KEY_DEVICE_CONFIRMED_BUILD = "device_confirmed_build"
        private const val KEY_LAST_BUILD_RUN_ID = "last_build_run_id"
        private const val KEY_LAST_BUILD_HEAD_SHA = "last_build_head_sha"
        private const val KEY_LAST_BUILD_WORKFLOW_ID = "last_build_workflow_id"
        private const val KEY_LAST_BUILD_RUN_URL = "last_build_run_url"
        private const val KEY_LAST_BUILD_STARTED_AT = "last_build_started_at"
        private const val KEY_LAST_BUILD_STATUS = "last_build_status"
        private const val KEY_LAST_BUILD_CONCLUSION = "last_build_conclusion"
        private const val KEY_LAST_BUILD_ARTIFACT_ID = "last_build_artifact_id"
        private const val KEY_LAST_BUILD_ARTIFACT_DIGEST = "last_build_artifact_digest"
        private const val KEY_LAST_BUILD_ARTIFACT_SIZE = "last_build_artifact_size"
        private const val KEY_LAST_BUILD_VERIFIED_AT = "last_build_verified_at"

        private const val RUNTIME_FRESHNESS_MS = 15L * 60L * 1000L
        private const val TOKEN_REFRESH_SKEW_MS = 2L * 60L * 1000L
        private const val MAX_INLINE_CONTENT_BYTES = 6_000
        private const val MAX_PATH_CHARS = 320
        private const val MAX_COMMIT_MESSAGE_CHARS = 160

        private const val BUILD_RUN_DISCOVERY_TIMEOUT_MS = 90_000L
        private const val BUILD_RUN_DISCOVERY_POLL_MS = 2_500L
        private const val BUILD_COMPLETION_TIMEOUT_MS = 20L * 60L * 1000L
        private const val BUILD_POLL_INTERVAL_MS = 5_000L

        private val VALID_CLIENT_ID =
            Regex("^[A-Za-z0-9_.-]{8,160}$")

        private val FULL_GIT_SHA =
            Regex("^[0-9a-fA-F]{40}$")

        private val ARTIFACT_SHA256 =
            Regex("^sha256:[0-9a-fA-F]{64}$")
    }
}
