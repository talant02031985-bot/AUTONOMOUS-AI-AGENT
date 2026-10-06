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
 * AYANA R10.28.6.8 GitHub Repository + Actions + Development Transaction Executor v1.3.2.
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
 * R10.27.2.1 hardens PREPARE truth:
 * - github_apk_build confirmed=false is explicitly read-only even on failure;
 * - a transient installation-permission read failure cannot be mislabeled as a
 *   dispatched side effect;
 * - PREPARE may use a very recent verified Actions:write readiness snapshot only
 *   to construct the proposal; confirmed dispatch still re-checks live authority.
 *
 * R10.27.3 composes the already verified repository-write and APK-build lanes into
 * one bounded development transaction for exact text replacements in existing files:
 * snapshot -> exact patch -> static integrity checks -> explicit confirmation -> commit
 * -> verified APK build -> explicit accept OR verified rollback. Large repository files
 * are read by immutable Git blob SHA; only a bounded patch is carried in Durable Goal.
 *
 * R10.28.6.7 adds read-only exact-match disambiguation evidence. When a PREPARE
 * find_text is not unique, the executor returns a bounded list of exact unique source
 * contexts from the same immutable repository snapshot. This does not prepare, commit,
 * build, or grant confirmation authority; Agent Core may use one context to retry the
 * same bounded GitHub development transaction without falling into Project Workspace.
 *
 * R10.28.6.8 canonicalizes a bare Android source filename only inside the fixed
 * kg.autonomous.agent source directory before any GitHub read. This prevents a root-level
 * same-name/stale file from being mistaken for the production Android source while keeping
 * explicit repository-relative paths unchanged and preserving all path safety guards.
 *
 * This executor still does not merge branches, delete files, edit workflows, write
 * secrets, install APKs, or broaden authority beyond the fixed repository.
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

    fun developmentTransaction(
        arguments: JSONObject,
        confirmed: Boolean,
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        return if (!confirmed) {
            prepareDevelopmentTransaction(arguments)
        } else {
            confirmDevelopmentTransaction(
                arguments = arguments,
                shouldCancel = shouldCancel
            )
        }
    }

    fun developmentTransactionStatus(): JSONObject {
        val transactionId =
            prefs.getString(KEY_DEV_TX_ID, "")
                .orEmpty()
                .trim()

        val status =
            prefs.getString(KEY_DEV_TX_STATUS, "none")
                .orEmpty()
                .ifBlank { "none" }

        if (
            transactionId.isNotBlank() &&
            status in
                setOf(
                    DEV_TX_STATUS_BUILDING,
                    DEV_TX_STATUS_BUILD_RECONCILIATION_REQUIRED,
                    DEV_TX_STATUS_ROLLBACK_BUILDING,
                    DEV_TX_STATUS_ROLLBACK_BUILD_FAILED
                )
        ) {
            val reconciled = reconcileDevelopmentTransactionStatusReadOnly()
            if (reconciled != null) return reconciled
        }

        if (transactionId.isBlank() || status == "none") {
            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("status", "no_development_transaction")
                .put("active", false)
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("message", "Сейчас нет сохранённой development transaction.")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", status)
            .put("active", status in ACTIVE_DEV_TX_STATUSES)
            .put("transaction_id", transactionId)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("path", prefs.getString(KEY_DEV_TX_PATH, "").orEmpty())
            .put("base_head_sha", prefs.getString(KEY_DEV_TX_BASE_HEAD_SHA, "").orEmpty())
            .put("original_blob_sha", prefs.getString(KEY_DEV_TX_ORIGINAL_BLOB_SHA, "").orEmpty())
            .put("proposed_blob_sha", prefs.getString(KEY_DEV_TX_PROPOSED_BLOB_SHA, "").orEmpty())
            .put("commit_sha", prefs.getString(KEY_DEV_TX_COMMIT_SHA, "").orEmpty())
            .put("workflow_id", prefs.getLong(KEY_DEV_TX_WORKFLOW_ID, 0L))
            .put("build_run_id", prefs.getLong(KEY_DEV_TX_BUILD_RUN_ID, 0L))
            .put("artifact_id", prefs.getLong(KEY_DEV_TX_ARTIFACT_ID, 0L))
            .put("artifact_digest", prefs.getString(KEY_DEV_TX_ARTIFACT_DIGEST, "").orEmpty())
            .put("rollback_commit_sha", prefs.getString(KEY_DEV_TX_ROLLBACK_COMMIT_SHA, "").orEmpty())
            .put("updated_at_ms", prefs.getLong(KEY_DEV_TX_UPDATED_AT, 0L))
            .put(
                "message",
                when (status) {
                    DEV_TX_STATUS_PREPARED ->
                        "Development transaction подготовлена и ждёт отдельного подтверждения."
                    DEV_TX_STATUS_WAITING_ACCEPTANCE ->
                        "Изменение и APK build подтверждены. Нужен выбор: принять или откатить transaction."
                    DEV_TX_STATUS_BUILD_RECONCILIATION_REQUIRED ->
                        "Commit выполнен, но build требует read-only reconciliation. Повторный dispatch запрещён."
                    DEV_TX_STATUS_ACCEPTED ->
                        "Последняя development transaction принята пользователем."
                    DEV_TX_STATUS_ROLLED_BACK ->
                        "Последняя development transaction доказательно откатана."
                    else ->
                        "Development transaction status: $status"
                }
            )
    }

    /**
     * Read-only recovery for a transaction whose commit already exists but whose
     * build proof was interrupted/ambiguous. Never dispatches a workflow and never
     * writes repository content. It can only advance persisted transaction truth
     * after observing the exact push-triggered run for the saved commit SHA.
     */
    private fun reconcileDevelopmentTransactionStatusReadOnly(): JSONObject? {
        val state = loadDevelopmentTransactionState()
        if (!state.optBoolean("success", false)) return null

        val status = state.optString("status")
        val rollbackMode =
            status in
                setOf(
                    DEV_TX_STATUS_ROLLBACK_BUILDING,
                    DEV_TX_STATUS_ROLLBACK_BUILD_FAILED
                )

        val expectedHeadSha =
            if (rollbackMode) {
                state.optString("rollback_commit_sha")
            } else {
                state.optString("commit_sha")
            }
        val workflowId = state.optLong("workflow_id", 0L)

        if (!FULL_GIT_SHA.matches(expectedHeadSha) || workflowId <= 0L) {
            return null
        }

        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return JSONObject(state.toString())
                .put("success", true)
                .put("verified", true)
                .put("active", true)
                .put("reconciliation_attempted", true)
                .put("reconciliation_observation_available", false)
                .put(
                    "message",
                    "Development transaction сохранена, но сейчас не удалось выполнить read-only GitHub build reconciliation. Новый dispatch не выполнялся."
                )
        }

        val accessToken = tokenResult.optString("access_token")
        var runId =
            if (
                prefs.getString(KEY_LAST_BUILD_HEAD_SHA, "").orEmpty() == expectedHeadSha &&
                prefs.getString(KEY_LAST_BUILD_EVENT, "").orEmpty() == "push"
            ) {
                prefs.getLong(KEY_LAST_BUILD_RUN_ID, 0L)
            } else {
                0L
            }

        if (runId <= 0L) {
            val listed =
                listWorkflowRuns(
                    accessToken = accessToken,
                    workflowId = workflowId,
                    headSha = expectedHeadSha,
                    event = "push"
                )

            if (!listed.optBoolean("success", false)) {
                return JSONObject(state.toString())
                    .put("success", true)
                    .put("verified", true)
                    .put("active", true)
                    .put("reconciliation_attempted", true)
                    .put("reconciliation_observation_available", false)
                    .put("message", "Transaction build пока не удалось прочитать; никаких повторных mutation/dispatch не выполнено.")
            }

            val matches = mutableListOf<JSONObject>()
            val runs = listed.optJSONArray("runs") ?: JSONArray()
            for (index in 0 until runs.length()) {
                val item = runs.optJSONObject(index) ?: continue
                if (
                    item.optLong("id", 0L) > 0L &&
                    item.optLong("workflow_id", 0L) == workflowId &&
                    item.optString("event") == "push" &&
                    item.optString("head_branch") == BRANCH &&
                    item.optString("head_sha") == expectedHeadSha &&
                    item.optString("name") == BUILD_WORKFLOW_NAME
                ) {
                    matches += item
                }
            }

            if (matches.size != 1) {
                return JSONObject(state.toString())
                    .put("success", true)
                    .put("verified", true)
                    .put("active", true)
                    .put("reconciliation_attempted", true)
                    .put("reconciliation_observation_available", matches.isNotEmpty())
                    .put("candidate_run_count", matches.size)
                    .put(
                        "message",
                        if (matches.isEmpty()) {
                            "Exact push-triggered build run ещё не виден; AYANA ничего не повторяет."
                        } else {
                            "Найдено несколько подходящих build runs; AYANA не угадывает identity."
                        }
                    )
            }

            runId = matches.first().optLong("id", 0L)
            prefs.edit()
                .putLong(KEY_LAST_BUILD_RUN_ID, runId)
                .putString(KEY_LAST_BUILD_HEAD_SHA, expectedHeadSha)
                .putLong(KEY_LAST_BUILD_WORKFLOW_ID, workflowId)
                .putString(KEY_LAST_BUILD_EVENT, "push")
                .putString(KEY_LAST_BUILD_RUN_URL, matches.first().optString("html_url"))
                .apply()
        }

        val inspected =
            inspectBuildRun(
                accessToken = accessToken,
                runId = runId,
                expectedHeadSha = expectedHeadSha,
                expectedWorkflowId = workflowId,
                expectedEvent = "push"
            )

        if (inspected.optString("status") == "verified_apk_build") {
            if (rollbackMode) {
                prefs.edit()
                    .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_ROLLED_BACK)
                    .putLong(KEY_DEV_TX_BUILD_RUN_ID, inspected.optLong("run_id", 0L))
                    .putLong(KEY_DEV_TX_ARTIFACT_ID, inspected.optLong("artifact_id", 0L))
                    .putString(KEY_DEV_TX_ARTIFACT_DIGEST, inspected.optString("artifact_digest"))
                    .putLong(KEY_DEV_TX_ARTIFACT_SIZE, inspected.optLong("artifact_size_bytes", 0L))
                    .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
                    .putBoolean(KEY_DEVICE_CONFIRMED_TRANSACTION, true)
                    .apply()

                return JSONObject(inspected.toString())
                    .put("success", true)
                    .put("verified", true)
                    .put("terminal_status", "SUCCESS")
                    .put("status", "development_transaction_rolled_back")
                    .put("transaction_id", state.optString("transaction_id"))
                    .put("path", state.optString("path"))
                    .put("original_blob_sha", state.optString("original_blob_sha"))
                    .put("rollback_commit_sha", expectedHeadSha)
                    .put("repository_restored", true)
                    .put("rollback_build_verified", true)
                    .put("action_dispatched", false)
                    .put("action_committed", false)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "NONE")
                    .put("reconciliation_read_only", true)
                    .put("message", "Rollback build доказательно подтверждён read-only reconciliation; transaction полностью откатана.")
            }

            prefs.edit()
                .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_WAITING_ACCEPTANCE)
                .putLong(KEY_DEV_TX_BUILD_RUN_ID, inspected.optLong("run_id", 0L))
                .putLong(KEY_DEV_TX_ARTIFACT_ID, inspected.optLong("artifact_id", 0L))
                .putString(KEY_DEV_TX_ARTIFACT_DIGEST, inspected.optString("artifact_digest"))
                .putLong(KEY_DEV_TX_ARTIFACT_SIZE, inspected.optLong("artifact_size_bytes", 0L))
                .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
                .apply()

            return JSONObject(inspected.toString())
                .put("success", true)
                .put("verified", true)
                .put("terminal_status", "BLOCKED")
                .put("status", "development_transaction_waiting_acceptance")
                .put("transaction_id", state.optString("transaction_id"))
                .put("path", state.optString("path"))
                .put("original_blob_sha", state.optString("original_blob_sha"))
                .put("proposed_blob_sha", state.optString("proposed_blob_sha"))
                .put("commit_sha", expectedHeadSha)
                .put("requires_acceptance", true)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
                .put("reconciliation_read_only", true)
                .put("message", "Commit уже существовал; read-only reconciliation доказала успешный APK build. Transaction ждёт: принять или откатить.")
        }

        if (
            inspected.optString("build_status") == "completed" &&
            inspected.optString("build_conclusion").isNotBlank() &&
            inspected.optString("build_conclusion") != "success"
        ) {
            return JSONObject(inspected.toString())
                .put("success", true)
                .put("verified", true)
                .put("status", if (rollbackMode) DEV_TX_STATUS_ROLLBACK_BUILD_FAILED else DEV_TX_STATUS_BUILD_RECONCILIATION_REQUIRED)
                .put("transaction_id", state.optString("transaction_id"))
                .put("path", state.optString("path"))
                .put("active", true)
                .put("rollback_available", !rollbackMode)
                .put("reconciliation_read_only", true)
                .put("message", "Build завершён неуспешно; read-only reconciliation подтверждена. Новый dispatch не выполнялся.")
        }

        return JSONObject(state.toString())
            .put("success", true)
            .put("verified", true)
            .put("active", true)
            .put("reconciliation_attempted", true)
            .put("reconciliation_read_only", true)
            .put("build_run_id", runId)
            .put("build_status", inspected.optString("build_status"))
            .put("build_conclusion", inspected.optString("build_conclusion"))
            .put("message", "Development transaction build ещё выполняется/ожидается; никаких повторных mutation/dispatch не выполнено.")
    }

    fun acceptDevelopmentTransaction(): JSONObject {
        val state = loadDevelopmentTransactionState()
        if (!state.optBoolean("success", false)) {
            return state
        }

        if (state.optString("status") != DEV_TX_STATUS_WAITING_ACCEPTANCE) {
            return failure(
                "Принять можно только transaction, у которой commit и APK build уже доказательно завершены."
            )
                .put("status", "development_transaction_not_waiting_acceptance")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val accessToken = tokenResult.optString("access_token")
        val path = state.optString("path")
        val current = readRepositoryPath(path, accessToken)
        if (!current.optBoolean("success", false)) {
            return current
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val proposedBlobSha = state.optString("proposed_blob_sha")
        if (
            !current.optBoolean("exists", false) ||
            current.optString("sha") != proposedBlobSha
        ) {
            return failure(
                "Development transaction не принята: целевой файл изменился после проверенной сборки. Сначала нужна фактическая сверка/новая transaction."
            )
                .put("status", "development_transaction_accept_state_changed")
                .put("expected_blob_sha", proposedBlobSha)
                .put("actual_blob_sha", current.optString("sha"))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_ACCEPTED)
            .putLong(KEY_DEV_TX_UPDATED_AT, now)
            .putBoolean(KEY_DEVICE_CONFIRMED_TRANSACTION, true)
            .apply()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "development_transaction_accepted")
            .put("transaction_id", state.optString("transaction_id"))
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("path", path)
            .put("commit_sha", state.optString("commit_sha"))
            .put("build_run_id", state.optLong("build_run_id", 0L))
            .put("artifact_id", state.optLong("artifact_id", 0L))
            .put("artifact_digest", state.optString("artifact_digest"))
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("message", "Development transaction принята. Проверенный commit сохранён в main.")
    }

    fun rollbackDevelopmentTransaction(
        shouldCancel: () -> Boolean = { false }
    ): JSONObject {
        val state = loadDevelopmentTransactionState()
        if (!state.optBoolean("success", false)) {
            return state
        }

        if (
            state.optString("status") !in
            setOf(
                DEV_TX_STATUS_WAITING_ACCEPTANCE,
                DEV_TX_STATUS_BUILD_RECONCILIATION_REQUIRED
            )
        ) {
            return failure(
                "Откат доступен только для transaction с уже выполненным commit, которая ещё не принята."
            )
                .put("status", "development_transaction_rollback_not_available")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        return rollbackDevelopmentTransactionInternal(
            state = state,
            shouldCancel = shouldCancel,
            explicitUserRollback = true,
            trigger = "explicit_user_rollback"
        )
    }

    fun cancelPreparedDevelopmentTransaction(): JSONObject {
        val state = loadDevelopmentTransactionState()
        if (!state.optBoolean("success", false)) {
            return state
        }

        if (state.optString("status") != DEV_TX_STATUS_PREPARED) {
            return failure(
                "Эта development transaction уже вышла из PREPARE. После commit её можно только принять или доказательно откатить."
            )
                .put("status", "development_transaction_cancel_not_available")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        prefs.edit()
            .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_CANCELLED)
            .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
            .apply()

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "development_transaction_cancelled")
            .put("transaction_id", state.optString("transaction_id"))
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("message", "Подготовленная development transaction отменена; GitHub не изменялся.")
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
            expectedWorkflowId = prefs.getLong(KEY_LAST_BUILD_WORKFLOW_ID, 0L),
            expectedEvent =
                prefs.getString(KEY_LAST_BUILD_EVENT, "workflow_dispatch")
                    .orEmpty()
                    .ifBlank { "workflow_dispatch" }
        )
    }

    private fun prepareDevelopmentTransaction(
        arguments: JSONObject
    ): JSONObject {
        val existingStatus =
            prefs.getString(KEY_DEV_TX_STATUS, "none")
                .orEmpty()

        if (existingStatus in ACTIVE_DEV_TX_STATUSES) {
            return failure(
                "Уже есть незавершённая development transaction. Сначала проверьте её статус и примите, откатите или отмените подготовку."
            )
                .put("status", "development_transaction_already_active")
                .put("transaction_id", prefs.getString(KEY_DEV_TX_ID, "").orEmpty())
                .put("current_transaction_status", existingStatus)
                .put("requires_confirmation", false)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
                .put("phase", "prepare_read_only")
        }

        val pathResult = validateDevelopmentTransactionPath(arguments.optString("path"))
        if (!pathResult.optBoolean("success", false)) {
            return developmentPrepareFailure(pathResult)
        }

        val path = pathResult.optString("path")
        val findText = arguments.optString("find_text")
        val replaceText = arguments.optString("replace_text")
        val findBytes = findText.toByteArray(StandardCharsets.UTF_8)
        val replaceBytes = replaceText.toByteArray(StandardCharsets.UTF_8)
        val patchBytes = findBytes.size + replaceBytes.size

        if (
            findText.isBlank() ||
            findBytes.size > MAX_TRANSACTION_FIND_BYTES ||
            replaceBytes.size > MAX_TRANSACTION_REPLACE_BYTES ||
            patchBytes > MAX_TRANSACTION_PATCH_BYTES
        ) {
            return developmentPrepareFailure(
                failure(
                    "Development patch слишком большой или find_text пуст. R10.27.3 хранит только bounded exact replacement в Durable Goal."
                )
                    .put("status", "development_patch_out_of_bounds")
                    .put("find_bytes", findBytes.size)
                    .put("replace_bytes", replaceBytes.size)
                    .put("max_patch_bytes", MAX_TRANSACTION_PATCH_BYTES)
            )
        }

        if (containsSensitiveMaterial(replaceText)) {
            return developmentPrepareFailure(
                failure(
                    "Development transaction заблокирована: replacement похож на секрет/ключ/token material."
                )
                    .put("status", "development_patch_sensitive_material")
            )
        }

        val commitMessage =
            arguments.optString("commit_message")
                .trim()
                .ifBlank { "AYANA development transaction: $path" }
                .take(MAX_COMMIT_MESSAGE_CHARS)

        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return developmentPrepareFailure(tokenResult)
        }

        val accessToken = tokenResult.optString("access_token")
        val authority = installationAuthority(accessToken)
        if (!authority.optBoolean("success", false)) {
            return developmentPrepareFailure(authority)
        }

        val contentsWrite =
            authority.optString("contents_permission", "none")
                .lowercase(Locale.ROOT) == "write"
        val actionsWrite =
            authority.optString("actions_permission", "none")
                .lowercase(Locale.ROOT) == "write"

        if (!contentsWrite || !actionsWrite) {
            return developmentPrepareFailure(
                failure(
                    "Development transaction требует одновременно Contents:write и Actions:write для фиксированного AYANA repository."
                )
                    .put("status", "development_transaction_authority_unavailable")
                    .put("contents_permission", authority.optString("contents_permission", "none"))
                    .put("actions_permission", authority.optString("actions_permission", "none"))
            )
        }

        val workflow = findBuildWorkflow(accessToken)
        if (!workflow.optBoolean("success", false)) {
            return developmentPrepareFailure(workflow)
        }

        val head = readBranchHead(accessToken)
        if (!head.optBoolean("success", false)) {
            return developmentPrepareFailure(head)
        }

        val source = readRepositoryFileText(path, accessToken)
        if (!source.optBoolean("success", false)) {
            return developmentPrepareFailure(source)
        }

        val originalText = source.optString("text")
        val originalBlobSha = source.optString("sha")
        val matchCount = countOccurrences(originalText, findText)

        if (matchCount != 1) {
            val matchCandidates =
                developmentMatchCandidates(
                    text = originalText,
                    needle = findText
                )

            return developmentPrepareFailure(
                failure(
                    "Exact replacement остановлен: find_text должен встречаться ровно один раз, найдено $matchCount."
                )
                    .put("status", "development_exact_match_count_invalid")
                    .put("match_count", matchCount)
                    .put("match_candidates", matchCandidates)
                    .put("candidate_count", matchCandidates.length())
                    .put(
                        "candidates_truncated",
                        matchCount > matchCandidates.length()
                    )
            )
        }

        val start = originalText.indexOf(findText)
        val proposedText =
            originalText.substring(0, start) +
                replaceText +
                originalText.substring(start + findText.length)
        val proposedBytes = proposedText.toByteArray(StandardCharsets.UTF_8)

        val checks =
            developmentStaticChecks(
                path = path,
                originalBytes = source.optString("blob_base64").let {
                    try { Base64.decode(it, Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
                },
                proposedBytes = proposedBytes,
                matchCount = matchCount
            )

        if (!checks.optBoolean("passed", false)) {
            return developmentPrepareFailure(
                failure(
                    "Development static integrity checks не прошли; commit не подготовлен."
                )
                    .put("status", "development_static_checks_failed")
                    .put("static_checks", checks)
            )
        }

        val proposedBlobSha = gitBlobSha(proposedBytes)
        val baseHeadSha = head.optString("head_sha")
        val workflowId = workflow.optLong("workflow_id", 0L)
        val transactionId =
            "devtx-${System.currentTimeMillis()}-${proposedBlobSha.take(10)}"

        persistDevelopmentTransactionPrepared(
            transactionId = transactionId,
            path = path,
            findText = findText,
            replaceText = replaceText,
            commitMessage = commitMessage,
            baseHeadSha = baseHeadSha,
            originalBlobSha = originalBlobSha,
            proposedBlobSha = proposedBlobSha,
            workflowId = workflowId
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "development_transaction_prepared_waiting_confirmation")
            .put("requires_confirmation", true)
            .put("transaction_id", transactionId)
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("path", path)
            .put("base_head_sha", baseHeadSha)
            .put("original_blob_sha", originalBlobSha)
            .put("proposed_blob_sha", proposedBlobSha)
            .put("workflow_id", workflowId)
            .put("workflow_name", BUILD_WORKFLOW_NAME)
            .put("artifact_name", APK_ARTIFACT_NAME)
            .put("static_checks_passed", true)
            .put("static_checks", checks)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("phase", "prepare_read_only")
            .put(
                "message",
                "Development transaction подготовлена для $path на head ${baseHeadSha.take(12)}. GitHub ещё НЕ изменён и workflow НЕ запущен. Для commit+build скажите: «подтверждаю текущую задачу»."
            )
    }

    private fun confirmDevelopmentTransaction(
        arguments: JSONObject,
        shouldCancel: () -> Boolean
    ): JSONObject {
        val requestedTransactionId =
            arguments.optString("_github_dev_transaction_id")
                .trim()
        val state = loadDevelopmentTransactionState()

        if (!state.optBoolean("success", false)) {
            return state
        }

        if (
            requestedTransactionId.isBlank() ||
            requestedTransactionId != state.optString("transaction_id") ||
            state.optString("status") != DEV_TX_STATUS_PREPARED
        ) {
            return failure(
                "Подтверждённая development transaction не совпадает с сохранённым PREPARE state. Нужна новая подготовка."
            )
                .put("status", "development_transaction_prepared_payload_mismatch")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val accessToken = tokenResult.optString("access_token")
        val authority = installationAuthority(accessToken)
        if (
            !authority.optBoolean("success", false) ||
            authority.optString("contents_permission", "none").lowercase(Locale.ROOT) != "write" ||
            authority.optString("actions_permission", "none").lowercase(Locale.ROOT) != "write"
        ) {
            return failure(
                "Development transaction остановлена до mutation: Contents:write/Actions:write больше не подтверждены."
            )
                .put("status", "development_transaction_authority_changed")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val workflowId = state.optLong("workflow_id", 0L)
        val workflow = findBuildWorkflow(accessToken)
        if (
            !workflow.optBoolean("success", false) ||
            workflow.optLong("workflow_id", 0L) != workflowId
        ) {
            return failure(
                "Development transaction остановлена: фиксированный APK workflow изменился после PREPARE."
            )
                .put("status", "development_transaction_workflow_changed")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val baseHeadSha = state.optString("base_head_sha")
        val head = readBranchHead(accessToken)
        if (
            !head.optBoolean("success", false) ||
            head.optString("head_sha") != baseHeadSha
        ) {
            return failure(
                "Development transaction остановлена: main изменился после PREPARE. Нужна новая подготовка и новое подтверждение."
            )
                .put("status", "development_transaction_head_changed")
                .put("expected_head_sha", baseHeadSha)
                .put("actual_head_sha", head.optString("head_sha"))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val path = state.optString("path")
        val currentSource = readRepositoryFileText(path, accessToken)
        if (!currentSource.optBoolean("success", false)) {
            return currentSource
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val originalBlobSha = state.optString("original_blob_sha")
        if (currentSource.optString("sha") != originalBlobSha) {
            return failure(
                "Development transaction остановлена: целевой файл изменился после PREPARE."
            )
                .put("status", "development_transaction_file_changed")
                .put("expected_blob_sha", originalBlobSha)
                .put("actual_blob_sha", currentSource.optString("sha"))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val findText = state.optString("find_text")
        val replaceText = state.optString("replace_text")
        val currentText = currentSource.optString("text")
        if (countOccurrences(currentText, findText) != 1) {
            return failure(
                "Development transaction остановлена: exact replacement больше не однозначен."
            )
                .put("status", "development_transaction_exact_match_changed")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val start = currentText.indexOf(findText)
        val proposedText =
            currentText.substring(0, start) +
                replaceText +
                currentText.substring(start + findText.length)
        val proposedBytes = proposedText.toByteArray(StandardCharsets.UTF_8)
        val proposedBlobSha = gitBlobSha(proposedBytes)

        if (proposedBlobSha != state.optString("proposed_blob_sha")) {
            return failure(
                "Development transaction остановлена: proposed blob больше не совпадает с PREPARE proof."
            )
                .put("status", "development_transaction_proposed_blob_mismatch")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        if (shouldCancel()) {
            return failure("Development transaction отменена до commit.")
                .put("status", "development_transaction_cancelled_before_commit")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val commitResult =
            commitPrepared(
                path = path,
                contentBytes = proposedBytes,
                commitMessage = state.optString("commit_message"),
                expectedSha = originalBlobSha,
                expectedMissing = false,
                proposedBlobSha = proposedBlobSha
            )

        if (!commitResult.optBoolean("success", false)) {
            return JSONObject(commitResult.toString())
                .put("status", "development_transaction_commit_failed")
                .put("transaction_id", state.optString("transaction_id"))
                .put("side_effect_kind", "github_development_transaction")
        }

        val commitSha = commitResult.optString("commit_sha").trim()
        if (!FULL_GIT_SHA.matches(commitSha)) {
            return failure(
                "Development transaction commit response не содержит валидный commit SHA."
            )
                .put("status", "development_transaction_commit_sha_invalid")
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_development_transaction")
        }

        prefs.edit()
            .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_BUILDING)
            .putString(KEY_DEV_TX_COMMIT_SHA, commitSha)
            .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
            .apply()

        // The fixed Build Android APK workflow already triggers on push to main.
        // The verified commit above is therefore the build trigger. Do NOT issue a second
        // workflow_dispatch here: correlate and verify the exact push-triggered run instead.
        val buildResult =
            monitorPushBuildForCommit(
                workflowId = workflowId,
                expectedHeadSha = commitSha,
                shouldCancel = shouldCancel
            )

        val verifiedBuild =
            buildResult.optBoolean("success", false) &&
                buildResult.optBoolean("verified", false) &&
                buildResult.optString("status") == "verified_apk_build" &&
                buildResult.optString("build_status") == "completed" &&
                buildResult.optString("build_conclusion") == "success" &&
                buildResult.optBoolean("artifact_verified", false) &&
                buildResult.optLong("run_id", 0L) > 0L &&
                buildResult.optLong("artifact_id", 0L) > 0L &&
                buildResult.optLong("artifact_size_bytes", 0L) > 0L &&
                ARTIFACT_SHA256.matches(buildResult.optString("artifact_digest"))

        if (verifiedBuild) {
            prefs.edit()
                .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_WAITING_ACCEPTANCE)
                .putLong(KEY_DEV_TX_BUILD_RUN_ID, buildResult.optLong("run_id", 0L))
                .putLong(KEY_DEV_TX_ARTIFACT_ID, buildResult.optLong("artifact_id", 0L))
                .putString(KEY_DEV_TX_ARTIFACT_DIGEST, buildResult.optString("artifact_digest"))
                .putLong(KEY_DEV_TX_ARTIFACT_SIZE, buildResult.optLong("artifact_size_bytes", 0L))
                .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
                .apply()

            return JSONObject(buildResult.toString())
                .put("success", true)
                .put("verified", true)
                .put("terminal_status", "BLOCKED")
                .put("status", "development_transaction_waiting_acceptance")
                .put("transaction_id", state.optString("transaction_id"))
                .put("path", path)
                .put("original_blob_sha", originalBlobSha)
                .put("proposed_blob_sha", proposedBlobSha)
                .put("commit_sha", commitSha)
                .put("requires_acceptance", true)
                .put("requires_confirmation", false)
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_development_transaction")
                .put(
                    "message",
                    "Development transaction commit и APK build подтверждены. Изменение ещё не принято окончательно. После проверки скажите «прими текущую транзакцию разработки» или «откати текущую транзакцию разработки»."
                )
        }

        val buildDefinitivelyFailed =
            buildResult.optString("build_status") == "completed" &&
                buildResult.optString("build_conclusion").isNotBlank() &&
                buildResult.optString("build_conclusion") != "success" &&
                buildResult.optBoolean("reconciliation_complete", false)

        if (buildDefinitivelyFailed) {
            val rollbackState =
                JSONObject(state.toString())
                    .put("status", DEV_TX_STATUS_BUILDING)
                    .put("commit_sha", commitSha)
            return rollbackDevelopmentTransactionInternal(
                state = rollbackState,
                shouldCancel = shouldCancel,
                explicitUserRollback = false,
                trigger = "verified_build_failure"
            )
                .put("original_build_status", buildResult.optString("build_status"))
                .put("original_build_conclusion", buildResult.optString("build_conclusion"))
                .put("original_build_run_id", buildResult.optLong("run_id", 0L))
        }

        prefs.edit()
            .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_BUILD_RECONCILIATION_REQUIRED)
            .putLong(KEY_DEV_TX_BUILD_RUN_ID, buildResult.optLong("run_id", 0L))
            .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
            .apply()

        return JSONObject(buildResult.toString())
            .put("success", false)
            .put("verified", buildResult.optBoolean("verified", false))
            .put("status", "development_transaction_build_reconciliation_required")
            .put("transaction_id", state.optString("transaction_id"))
            .put("path", path)
            .put("commit_sha", commitSha)
            .put("rollback_available", true)
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", false)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "github_development_transaction")
            .put(
                "message",
                "Development commit выполнен, но build proof не завершён. Повторный dispatch запрещён. Проверьте transaction/build status; при необходимости выполните явный rollback."
            )
    }

    private fun rollbackDevelopmentTransactionInternal(
        state: JSONObject,
        shouldCancel: () -> Boolean,
        explicitUserRollback: Boolean,
        trigger: String
    ): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
        }

        val accessToken = tokenResult.optString("access_token")
        val path = state.optString("path")
        val originalBlobSha = state.optString("original_blob_sha")
        val proposedBlobSha = state.optString("proposed_blob_sha")
        val workflowId = state.optLong("workflow_id", 0L)

        val current = readRepositoryPath(path, accessToken)
        if (!current.optBoolean("success", false)) {
            return current
        }

        if (
            !current.optBoolean("exists", false) ||
            current.optString("sha") != proposedBlobSha
        ) {
            prefs.edit()
                .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_ROLLBACK_BLOCKED)
                .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
                .apply()

            return failure(
                "Rollback остановлен: целевой файл уже изменился после transaction commit. AYANA не будет затирать чужое изменение."
            )
                .put("status", "development_transaction_rollback_state_changed")
                .put("expected_blob_sha", proposedBlobSha)
                .put("actual_blob_sha", current.optString("sha"))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val original = readGitBlobText(originalBlobSha, accessToken)
        if (!original.optBoolean("success", false)) {
            return original
        }

        val originalBytes =
            try {
                Base64.decode(original.optString("blob_base64"), Base64.NO_WRAP)
            } catch (_: Exception) {
                ByteArray(0)
            }

        if (
            originalBytes.isEmpty() &&
            original.optLong("size", -1L) != 0L
        ) {
            return failure("Rollback snapshot decode failed.")
                .put("status", "development_transaction_rollback_snapshot_invalid")
        }

        if (shouldCancel() && !explicitUserRollback) {
            return failure("Automatic rollback остановлен до rollback commit.")
                .put("status", "development_transaction_rollback_cancelled_before_commit")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
        }

        val rollbackCommit =
            commitPrepared(
                path = path,
                contentBytes = originalBytes,
                commitMessage =
                    "AYANA rollback: " +
                        state.optString("commit_message")
                            .ifBlank { "development transaction" }
                            .take(120),
                expectedSha = proposedBlobSha,
                expectedMissing = false,
                proposedBlobSha = originalBlobSha
            )

        if (!rollbackCommit.optBoolean("success", false)) {
            prefs.edit()
                .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_ROLLBACK_BLOCKED)
                .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
                .apply()
            return JSONObject(rollbackCommit.toString())
                .put("status", "development_transaction_rollback_commit_failed")
                .put("transaction_id", state.optString("transaction_id"))
                .put("side_effect_kind", "github_development_transaction_rollback")
        }

        val rollbackCommitSha = rollbackCommit.optString("commit_sha").trim()
        if (!FULL_GIT_SHA.matches(rollbackCommitSha)) {
            return failure("Rollback commit SHA не подтверждён.")
                .put("status", "development_transaction_rollback_commit_sha_invalid")
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_development_transaction_rollback")
        }

        prefs.edit()
            .putString(KEY_DEV_TX_ROLLBACK_COMMIT_SHA, rollbackCommitSha)
            .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_ROLLBACK_BUILDING)
            .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
            .apply()

        // Rollback commit also triggers the fixed workflow by push. Never create a
        // duplicate manual dispatch; verify the exact rollback-commit push run.
        val rollbackBuild =
            monitorPushBuildForCommit(
                workflowId = workflowId,
                expectedHeadSha = rollbackCommitSha,
                shouldCancel = shouldCancel
            )

        val rollbackBuildVerified =
            rollbackBuild.optBoolean("success", false) &&
                rollbackBuild.optString("status") == "verified_apk_build" &&
                rollbackBuild.optString("build_conclusion") == "success" &&
                rollbackBuild.optBoolean("artifact_verified", false) &&
                ARTIFACT_SHA256.matches(rollbackBuild.optString("artifact_digest"))

        if (!rollbackBuildVerified) {
            prefs.edit()
                .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_ROLLBACK_BUILD_FAILED)
                .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
                .apply()

            return JSONObject(rollbackBuild.toString())
                .put("success", false)
                .put("status", "development_transaction_rollback_build_unverified")
                .put("transaction_id", state.optString("transaction_id"))
                .put("path", path)
                .put("rollback_commit_sha", rollbackCommitSha)
                .put("repository_restored", true)
                .put("rollback_build_verified", false)
                .put("action_dispatched", true)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_development_transaction_rollback")
                .put(
                    "message",
                    "Repository content откатан к исходному blob, но rollback APK build не получил полного verified proof. Повторный dispatch запрещён."
                )
        }

        prefs.edit()
            .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_ROLLED_BACK)
            .putLong(KEY_DEV_TX_BUILD_RUN_ID, rollbackBuild.optLong("run_id", 0L))
            .putLong(KEY_DEV_TX_ARTIFACT_ID, rollbackBuild.optLong("artifact_id", 0L))
            .putString(KEY_DEV_TX_ARTIFACT_DIGEST, rollbackBuild.optString("artifact_digest"))
            .putLong(KEY_DEV_TX_ARTIFACT_SIZE, rollbackBuild.optLong("artifact_size_bytes", 0L))
            .putLong(KEY_DEV_TX_UPDATED_AT, System.currentTimeMillis())
            .putBoolean(KEY_DEVICE_CONFIRMED_TRANSACTION, true)
            .apply()

        return JSONObject(rollbackBuild.toString())
            .put("success", explicitUserRollback)
            .put("verified", true)
            .put("terminal_status", if (explicitUserRollback) "SUCCESS" else "ERROR")
            .put(
                "status",
                if (explicitUserRollback) {
                    "development_transaction_rolled_back"
                } else {
                    "development_transaction_build_failed_rolled_back"
                }
            )
            .put("transaction_id", state.optString("transaction_id"))
            .put("path", path)
            .put("original_blob_sha", originalBlobSha)
            .put("rollback_commit_sha", rollbackCommitSha)
            .put("repository_restored", true)
            .put("rollback_build_verified", true)
            .put("rollback_trigger", trigger)
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "github_development_transaction_rollback")
            .put(
                "message",
                if (explicitUserRollback) {
                    "Development transaction доказательно откатана: исходный blob восстановлен, rollback commit и APK build подтверждены."
                } else {
                    "APK build изменения завершился неуспешно; AYANA доказательно восстановила исходный blob и подтвердила rollback APK build."
                }
            )
    }

    private fun validateDevelopmentTransactionPath(
        rawPath: String
    ): JSONObject {
        val requestedPath =
            rawPath.trim()
                .replace('\\', '/')
                .removePrefix("/")

        val resolvedPath =
            canonicalDevelopmentTransactionPath(
                requestedPath
            )

        val base = validatePath(resolvedPath)
        if (!base.optBoolean("success", false)) {
            return base
        }

        val path = base.optString("path")
        val lower = path.lowercase(Locale.ROOT)

        if (lower.startsWith(".github/")) {
            return failure(
                "Development transaction не изменяет .github/workflows или другую GitHub control-plane конфигурацию."
            )
                .put("status", "development_transaction_control_plane_path_blocked")
        }

        val extensionAllowed =
            TRANSACTION_ALLOWED_EXTENSIONS.any { lower.endsWith(it) }

        if (!extensionAllowed) {
            return failure(
                "Development transaction R10.27.3 разрешает только текстовые source/config/doc файлы из bounded allow-list."
            )
                .put("status", "development_transaction_extension_blocked")
        }

        return JSONObject(base.toString())
            .put("success", true)
            .put("verified", true)
            .put(
                "path_alias_resolved",
                requestedPath != path
            )
            .put(
                "requested_path",
                requestedPath
            )
    }

    private fun canonicalDevelopmentTransactionPath(
        rawPath: String
    ): String {
        val path =
            rawPath.trim()
                .replace('\\', '/')
                .removePrefix("/")

        if (path.contains('/')) {
            return path
        }

        val lower =
            path.lowercase(Locale.ROOT)

        val isAndroidSourceBasename =
            (lower.endsWith(".kt") || lower.endsWith(".java")) &&
                (
                    lower.startsWith("ayana") ||
                    lower == "mainactivity.kt" ||
                    lower == "mainactivity.java"
                )

        return if (isAndroidSourceBasename) {
            "app/src/main/java/kg/autonomous/agent/$path"
        } else {
            path
        }
    }

    private fun readRepositoryFileText(
        path: String,
        accessToken: String
    ): JSONObject {
        val metadata = readRepositoryPath(path, accessToken)
        if (!metadata.optBoolean("success", false)) {
            return metadata
        }

        if (!metadata.optBoolean("exists", false)) {
            return failure(
                "Development transaction требует существующий файл, чтобы rollback имел immutable snapshot."
            )
                .put("status", "development_transaction_existing_file_required")
        }

        val size = metadata.optLong("size", -1L)
        if (size < 0L || size > MAX_TRANSACTION_FILE_BYTES) {
            return failure(
                "Файл $path слишком большой для bounded R10.27.3 workspace: $size байт."
            )
                .put("status", "development_transaction_file_too_large")
                .put("size_bytes", size)
                .put("max_bytes", MAX_TRANSACTION_FILE_BYTES)
        }

        return readGitBlobText(
            sha = metadata.optString("sha"),
            accessToken = accessToken
        )
            .put("path", path)
    }

    private fun readGitBlobText(
        sha: String,
        accessToken: String
    ): JSONObject {
        if (!FULL_GIT_SHA.matches(sha)) {
            return failure("Git blob SHA invalid.")
                .put("status", "development_transaction_blob_sha_invalid")
        }

        val response =
            githubJsonRequest(
                method = "GET",
                apiPath = "/repos/$OWNER/$REPO/git/blobs/$sha",
                accessToken = accessToken
            )

        if (!response.optBoolean("http_success", false)) {
            return failure(
                "Не удалось прочитать immutable Git blob $sha: HTTP ${response.optInt("http_code", -1)}."
            )
                .put("status", "development_transaction_blob_read_failed")
                .put("http_code", response.optInt("http_code", -1))
        }

        val body = response.optJSONObject("body") ?: JSONObject()
        val encoding = body.optString("encoding")
        val content = body.optString("content")
        val bytes =
            try {
                if (encoding == "base64") {
                    Base64.decode(content, Base64.DEFAULT)
                } else {
                    ByteArray(0)
                }
            } catch (_: Exception) {
                ByteArray(0)
            }

        val declaredSize = body.optLong("size", -1L)
        if (
            encoding != "base64" ||
            declaredSize < 0L ||
            declaredSize > MAX_TRANSACTION_FILE_BYTES ||
            (declaredSize > 0L && bytes.isEmpty()) ||
            bytes.size.toLong() != declaredSize ||
            gitBlobSha(bytes) != sha.lowercase(Locale.ROOT)
        ) {
            return failure(
                "Immutable Git blob не прошёл size/SHA verification."
            )
                .put("status", "development_transaction_blob_verification_failed")
                .put("declared_size", declaredSize)
                .put("decoded_size", bytes.size)
        }

        val text = String(bytes, StandardCharsets.UTF_8)
        if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
            return failure(
                "Development transaction поддерживает только валидный UTF-8 text source."
            )
                .put("status", "development_transaction_non_utf8_file")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("sha", sha.lowercase(Locale.ROOT))
            .put("size", bytes.size)
            .put("text", text)
            .put("blob_base64", Base64.encodeToString(bytes, Base64.NO_WRAP))
    }

    private fun developmentStaticChecks(
        path: String,
        originalBytes: ByteArray,
        proposedBytes: ByteArray,
        matchCount: Int
    ): JSONObject {
        val proposedText = String(proposedBytes, StandardCharsets.UTF_8)
        val utf8RoundTrip = proposedText.toByteArray(StandardCharsets.UTF_8).contentEquals(proposedBytes)
        val originalKnown = originalBytes.isNotEmpty() || proposedBytes.isEmpty()
        val noNul = proposedBytes.none { it == 0.toByte() }
        val sizeOk = proposedBytes.size.toLong() <= MAX_TRANSACTION_FILE_BYTES
        val exactMatch = matchCount == 1
        val changed = !originalBytes.contentEquals(proposedBytes)
        val pathAllowed = validateDevelopmentTransactionPath(path).optBoolean("success", false)

        return JSONObject()
            .put("passed", utf8RoundTrip && noNul && sizeOk && exactMatch && changed && pathAllowed && originalKnown)
            .put("utf8_round_trip", utf8RoundTrip)
            .put("no_nul", noNul)
            .put("size_within_limit", sizeOk)
            .put("exact_match_count", matchCount)
            .put("content_changed", changed)
            .put("path_allowed", pathAllowed)
            .put("original_snapshot_loaded", originalKnown)
            .put("proposed_size_bytes", proposedBytes.size)
    }

    private fun countOccurrences(
        text: String,
        needle: String
    ): Int {
        if (needle.isEmpty()) return 0
        var count = 0
        var from = 0
        while (true) {
            val index = text.indexOf(needle, from)
            if (index < 0) break
            count++
            if (count > 1_000) break
            from = index + needle.length
        }
        return count
    }

    /**
     * Read-only disambiguation evidence for an ambiguous exact replacement.
     *
     * Each returned string:
     * - is copied verbatim from the immutable repository snapshot already read for PREPARE;
     * - contains the requested needle;
     * - occurs exactly once in the file, so it can safely become a refined find_text;
     * - is bounded and skipped if it resembles sensitive material.
     *
     * No state is persisted and no GitHub mutation occurs here.
     */
    private fun developmentMatchCandidates(
        text: String,
        needle: String
    ): JSONArray {
        val result = JSONArray()
        if (needle.isEmpty()) return result

        val positions = ArrayList<Int>()
        var from = 0

        while (
            positions.size < MAX_TRANSACTION_MATCH_CANDIDATES &&
            from <= text.length
        ) {
            val index = text.indexOf(needle, from)
            if (index < 0) break

            positions.add(index)
            from = index + needle.length
        }

        for (index in positions) {
            var radius = MATCH_CONTEXT_INITIAL_RADIUS_CHARS
            var uniqueCandidate: String? = null

            while (
                radius <= MATCH_CONTEXT_MAX_RADIUS_CHARS &&
                uniqueCandidate == null
            ) {
                val start =
                    (index - radius)
                        .coerceAtLeast(0)
                val end =
                    (index + needle.length + radius)
                        .coerceAtMost(text.length)

                val candidate =
                    text.substring(
                        start,
                        end
                    )

                val candidateBytes =
                    candidate.toByteArray(
                        StandardCharsets.UTF_8
                    )

                if (
                    candidateBytes.size <= MAX_TRANSACTION_FIND_BYTES &&
                    candidate.contains(needle) &&
                    countOccurrences(text, candidate) == 1 &&
                    !containsSensitiveMaterial(candidate)
                ) {
                    uniqueCandidate = candidate
                    break
                }

                radius += MATCH_CONTEXT_RADIUS_STEP_CHARS
            }

            if (uniqueCandidate != null) {
                result.put(uniqueCandidate)
            }
        }

        return result
    }

    private fun containsSensitiveMaterial(
        text: String
    ): Boolean =
        SENSITIVE_PATCH_PATTERNS.any { it.containsMatchIn(text) }

    private fun developmentPrepareFailure(
        source: JSONObject
    ): JSONObject =
        JSONObject(source.toString())
            .put("requires_confirmation", false)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("phase", "prepare_read_only")

    private fun persistDevelopmentTransactionPrepared(
        transactionId: String,
        path: String,
        findText: String,
        replaceText: String,
        commitMessage: String,
        baseHeadSha: String,
        originalBlobSha: String,
        proposedBlobSha: String,
        workflowId: Long
    ) {
        val now = System.currentTimeMillis()
        prefs.edit()
            .putString(KEY_DEV_TX_ID, transactionId)
            .putString(KEY_DEV_TX_STATUS, DEV_TX_STATUS_PREPARED)
            .putString(KEY_DEV_TX_PATH, path)
            .putString(KEY_DEV_TX_FIND_TEXT, findText)
            .putString(KEY_DEV_TX_REPLACE_TEXT, replaceText)
            .putString(KEY_DEV_TX_COMMIT_MESSAGE, commitMessage)
            .putString(KEY_DEV_TX_BASE_HEAD_SHA, baseHeadSha)
            .putString(KEY_DEV_TX_ORIGINAL_BLOB_SHA, originalBlobSha)
            .putString(KEY_DEV_TX_PROPOSED_BLOB_SHA, proposedBlobSha)
            .putLong(KEY_DEV_TX_WORKFLOW_ID, workflowId)
            .putString(KEY_DEV_TX_COMMIT_SHA, "")
            .putLong(KEY_DEV_TX_BUILD_RUN_ID, 0L)
            .putLong(KEY_DEV_TX_ARTIFACT_ID, 0L)
            .putString(KEY_DEV_TX_ARTIFACT_DIGEST, "")
            .putLong(KEY_DEV_TX_ARTIFACT_SIZE, 0L)
            .putString(KEY_DEV_TX_ROLLBACK_COMMIT_SHA, "")
            .putLong(KEY_DEV_TX_CREATED_AT, now)
            .putLong(KEY_DEV_TX_UPDATED_AT, now)
            .apply()
    }

    private fun loadDevelopmentTransactionState(): JSONObject {
        val transactionId = prefs.getString(KEY_DEV_TX_ID, "").orEmpty().trim()
        val status = prefs.getString(KEY_DEV_TX_STATUS, "none").orEmpty()
        if (transactionId.isBlank() || status == "none") {
            return failure("Нет сохранённой development transaction.")
                .put("status", "no_development_transaction")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("transaction_id", transactionId)
            .put("status", status)
            .put("path", prefs.getString(KEY_DEV_TX_PATH, "").orEmpty())
            .put("find_text", prefs.getString(KEY_DEV_TX_FIND_TEXT, "").orEmpty())
            .put("replace_text", prefs.getString(KEY_DEV_TX_REPLACE_TEXT, "").orEmpty())
            .put("commit_message", prefs.getString(KEY_DEV_TX_COMMIT_MESSAGE, "").orEmpty())
            .put("base_head_sha", prefs.getString(KEY_DEV_TX_BASE_HEAD_SHA, "").orEmpty())
            .put("original_blob_sha", prefs.getString(KEY_DEV_TX_ORIGINAL_BLOB_SHA, "").orEmpty())
            .put("proposed_blob_sha", prefs.getString(KEY_DEV_TX_PROPOSED_BLOB_SHA, "").orEmpty())
            .put("workflow_id", prefs.getLong(KEY_DEV_TX_WORKFLOW_ID, 0L))
            .put("commit_sha", prefs.getString(KEY_DEV_TX_COMMIT_SHA, "").orEmpty())
            .put("build_run_id", prefs.getLong(KEY_DEV_TX_BUILD_RUN_ID, 0L))
            .put("artifact_id", prefs.getLong(KEY_DEV_TX_ARTIFACT_ID, 0L))
            .put("artifact_digest", prefs.getString(KEY_DEV_TX_ARTIFACT_DIGEST, "").orEmpty())
            .put("artifact_size_bytes", prefs.getLong(KEY_DEV_TX_ARTIFACT_SIZE, 0L))
            .put("rollback_commit_sha", prefs.getString(KEY_DEV_TX_ROLLBACK_COMMIT_SHA, "").orEmpty())
    }

    private fun prepareBuild(): JSONObject {
        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return buildPrepareReadOnlyFailure(tokenResult)
        }

        val accessToken = tokenResult.optString("access_token")

        val cachedAuthority =
            recentVerifiedActionsWriteAuthority()

        val authority =
            if (cachedAuthority != null) {
                cachedAuthority
            } else {
                val liveAuthority =
                    installationAuthority(accessToken)

                if (!liveAuthority.optBoolean("success", false)) {
                    return buildPrepareReadOnlyFailure(liveAuthority)
                        .put("live_authority_status", liveAuthority.optString("status"))
                        .put("live_authority_http_code", liveAuthority.optInt("http_code", -1))
                }

                JSONObject(liveAuthority.toString())
                    .put("authority_source", "live_installation")
                    .put("authority_cache_age_ms", 0L)
            }

        val actionsPermission =
            authority.optString("actions_permission", "none")
                .lowercase(Locale.ROOT)

        if (authority.optString("authority_source") == "live_installation") {
            prefs.edit()
                .putString(KEY_LAST_ACTIONS_PERMISSION, actionsPermission)
                .putLong(KEY_LAST_ACTIONS_VERIFIED_AT, System.currentTimeMillis())
                .apply()
        }

        if (actionsPermission != "write") {
            return buildPrepareReadOnlyFailure(
                failure(
                    "GitHub App не имеет Actions: Read and write для $REPOSITORY_SLUG. Измените Repository permissions → Actions на Read and write и заново выполните Device Flow."
                )
                    .put("status", "actions_write_permission_required")
                    .put("actions_permission", actionsPermission)
            )
        }

        val workflow = findBuildWorkflow(accessToken)
        if (!workflow.optBoolean("success", false)) {
            return buildPrepareReadOnlyFailure(workflow)
        }

        val head = readBranchHead(accessToken)
        if (!head.optBoolean("success", false)) {
            return buildPrepareReadOnlyFailure(head)
        }

        val workflowId = workflow.optLong("workflow_id", 0L)
        val headSha = head.optString("head_sha").trim()

        if (workflowId <= 0L || headSha.isBlank()) {
            return buildPrepareReadOnlyFailure(
                failure(
                    "Не удалось зафиксировать точный workflow/head SHA перед сборкой."
                )
                    .put("status", "build_prepare_incomplete")
            )
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("status", "build_prepared_waiting_confirmation")
            .put("requires_confirmation", true)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("phase", "prepare_read_only")
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("workflow_id", workflowId)
            .put("workflow_name", BUILD_WORKFLOW_NAME)
            .put("workflow_state", workflow.optString("workflow_state"))
            .put("head_sha", headSha)
            .put("artifact_name", APK_ARTIFACT_NAME)
            .put("actions_permission", actionsPermission)
            .put("authority_source", authority.optString("authority_source", "live_installation"))
            .put("authority_cache_age_ms", authority.optLong("authority_cache_age_ms", 0L))
            .put(
                "message",
                "Сборка APK подготовлена для ${headSha.take(12)} через «$BUILD_WORKFLOW_NAME». Workflow ещё НЕ запущен. Для запуска скажите: «подтверждаю текущую задачу»."
            )
    }

    private fun buildPrepareReadOnlyFailure(
        source: JSONObject
    ): JSONObject =
        JSONObject(source.toString())
            .put("requires_confirmation", false)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("phase", "prepare_read_only")

    private fun recentVerifiedActionsWriteAuthority(): JSONObject? {
        val permission =
            prefs.getString(
                KEY_LAST_ACTIONS_PERMISSION,
                ""
            )
                .orEmpty()
                .lowercase(Locale.ROOT)

        val verifiedAt =
            prefs.getLong(
                KEY_LAST_ACTIONS_VERIFIED_AT,
                0L
            )

        val now = System.currentTimeMillis()
        val ageMs =
            if (verifiedAt > 0L) {
                (now - verifiedAt).coerceAtLeast(0L)
            } else {
                Long.MAX_VALUE
            }

        if (
            permission != "write" ||
            verifiedAt <= 0L ||
            ageMs > BUILD_PREPARE_AUTHORITY_CACHE_MS
        ) {
            return null
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("actions_permission", "write")
            .put("authority_source", "recent_verified_actions_status")
            .put("authority_cache_age_ms", ageMs)
    }

    /**
     * R10.27.3 transaction build verifier.
     *
     * The fixed AYANA workflow already has push-to-main CI. The repository commit is
     * therefore the build trigger. This path NEVER calls workflow_dispatch; it only
     * correlates the exact push-triggered run by workflow + branch + commit SHA and
     * verifies the immutable APK artifact. This prevents duplicate CI builds.
     */
    private fun monitorPushBuildForCommit(
        workflowId: Long,
        expectedHeadSha: String,
        shouldCancel: () -> Boolean
    ): JSONObject {
        if (workflowId <= 0L || !FULL_GIT_SHA.matches(expectedHeadSha)) {
            return failure("Push-build correlation payload invalid.")
                .put("status", "transaction_push_build_payload_invalid")
                .put("action_dispatched", false)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_contents_commit")
        }

        val tokenResult = ensureUsableAccessToken()
        if (!tokenResult.optBoolean("success", false)) {
            return tokenResult
                .put("action_dispatched", false)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_contents_commit")
        }

        val accessToken = tokenResult.optString("access_token")
        val authority = installationAuthority(accessToken)
        if (
            !authority.optBoolean("success", false) ||
            authority.optString("actions_permission", "none")
                .lowercase(Locale.ROOT) != "write"
        ) {
            return failure(
                "Commit уже выполнен, но Actions authority больше не подтверждён; build reconciliation остановлена без нового dispatch."
            )
                .put("status", "transaction_push_build_authority_unavailable")
                .put("action_dispatched", false)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_contents_commit")
        }

        val workflow = findBuildWorkflow(accessToken)
        if (
            !workflow.optBoolean("success", false) ||
            workflow.optLong("workflow_id", 0L) != workflowId
        ) {
            return failure(
                "Commit уже выполнен, но фиксированный APK workflow изменился; build reconciliation остановлена."
            )
                .put("status", "transaction_push_build_workflow_changed")
                .put("action_dispatched", false)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_contents_commit")
        }

        val discoveryDeadline =
            System.currentTimeMillis() + BUILD_RUN_DISCOVERY_TIMEOUT_MS
        var run: JSONObject? = null

        while (System.currentTimeMillis() <= discoveryDeadline) {
            if (shouldCancel()) {
                return failure(
                    "Commit уже выполнен; локальное ожидание push-triggered build остановлено. Новый commit или workflow dispatch автоматически не выполняется."
                )
                    .put("status", "transaction_push_build_monitor_cancelled")
                    .put("action_dispatched", false)
                    .put("action_committed", true)
                    .put("reconciliation_complete", false)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_contents_commit")
            }

            val listed =
                listWorkflowRuns(
                    accessToken = accessToken,
                    workflowId = workflowId,
                    headSha = expectedHeadSha,
                    event = "push"
                )

            if (listed.optBoolean("success", false)) {
                val candidates = mutableListOf<JSONObject>()
                val runs = listed.optJSONArray("runs") ?: JSONArray()
                for (index in 0 until runs.length()) {
                    val item = runs.optJSONObject(index) ?: continue
                    if (
                        item.optLong("id", 0L) > 0L &&
                        item.optLong("workflow_id", 0L) == workflowId &&
                        item.optString("event") == "push" &&
                        item.optString("head_branch") == BRANCH &&
                        item.optString("head_sha") == expectedHeadSha &&
                        item.optString("name") == BUILD_WORKFLOW_NAME
                    ) {
                        candidates += item
                    }
                }

                if (candidates.size > 1) {
                    return failure(
                        "Для exact commit SHA найдено несколько push-triggered build runs. AYANA не будет угадывать run identity."
                    )
                        .put("status", "transaction_push_build_run_ambiguous")
                        .put("candidate_count", candidates.size)
                        .put("action_dispatched", false)
                        .put("action_committed", true)
                        .put("reconciliation_complete", false)
                        .put("side_effect_state", "VERIFIED_COMMITTED")
                        .put("side_effect_kind", "github_contents_commit")
                }

                if (candidates.size == 1) {
                    run = candidates.first()
                    break
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
                "Commit подтверждён, но exact push-triggered APK build run не найден в лимит ожидания. AYANA не запускает второй workflow вслепую."
            )
                .put("status", "transaction_push_build_run_not_found")
                .put("repository", REPOSITORY_SLUG)
                .put("branch", BRANCH)
                .put("workflow_id", workflowId)
                .put("head_sha", expectedHeadSha)
                .put("action_dispatched", false)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "github_contents_commit")
        }

        val identifiedRun = run
        val runId = identifiedRun.optLong("id", 0L)
        val runNumber = identifiedRun.optLong("run_number", 0L)
        val runUrl = identifiedRun.optString("html_url")

        prefs.edit()
            .putLong(KEY_LAST_BUILD_RUN_ID, runId)
            .putString(KEY_LAST_BUILD_HEAD_SHA, expectedHeadSha)
            .putLong(KEY_LAST_BUILD_WORKFLOW_ID, workflowId)
            .putString(KEY_LAST_BUILD_EVENT, "push")
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
                    .put("status", "transaction_push_build_monitor_cancelled")
                    .put("repository", REPOSITORY_SLUG)
                    .put("branch", BRANCH)
                    .put("workflow_id", workflowId)
                    .put("workflow_name", BUILD_WORKFLOW_NAME)
                    .put("head_sha", expectedHeadSha)
                    .put("run_id", runId)
                    .put("run_number", runNumber)
                    .put("run_url", runUrl)
                    .put("action_dispatched", false)
                    .put("action_committed", true)
                    .put("reconciliation_complete", false)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_contents_commit")
                    .put(
                        "message",
                        "Commit и push-triggered build уже существуют; локальное ожидание остановлено. Новый dispatch не выполняется."
                    )
            }

            val inspected =
                inspectBuildRun(
                    accessToken = accessToken,
                    runId = runId,
                    expectedHeadSha = expectedHeadSha,
                    expectedWorkflowId = workflowId,
                    expectedEvent = "push"
                )

            val inspectedStatus = inspected.optString("status")
            if (inspectedStatus == "verified_apk_build") {
                return inspected
                    .put("action_dispatched", false)
                    .put("action_committed", true)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_contents_commit")
                    .put("build_trigger", "push")
            }

            if (
                inspected.optString("build_status") == "completed" &&
                inspected.optString("build_conclusion") != "success"
            ) {
                return inspected
                    .put("action_dispatched", false)
                    .put("action_committed", true)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_contents_commit")
                    .put("build_trigger", "push")
            }

            val transientArtifactState =
                inspectedStatus in
                    setOf(
                        "build_artifact_list_failed",
                        "build_artifact_missing",
                        "build_artifact_digest_missing"
                    )

            if (
                !inspected.optBoolean("success", false) &&
                !transientArtifactState
            ) {
                return inspected
                    .put("action_dispatched", false)
                    .put("action_committed", true)
                    .put("reconciliation_complete", false)
                    .put("side_effect_state", "VERIFIED_COMMITTED")
                    .put("side_effect_kind", "github_contents_commit")
                    .put("build_trigger", "push")
            }

            try {
                Thread.sleep(BUILD_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }

        return failure(
            "Push-triggered GitHub Actions build/artifact proof не завершился в лимит ожидания. Новый workflow dispatch запрещён."
        )
            .put("status", "transaction_push_build_timeout")
            .put("repository", REPOSITORY_SLUG)
            .put("branch", BRANCH)
            .put("workflow_id", workflowId)
            .put("workflow_name", BUILD_WORKFLOW_NAME)
            .put("head_sha", expectedHeadSha)
            .put("run_id", runId)
            .put("run_number", runNumber)
            .put("run_url", runUrl)
            .put("action_dispatched", false)
            .put("action_committed", true)
            .put("reconciliation_complete", false)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "github_contents_commit")
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
                .putString(KEY_LAST_BUILD_EVENT, "workflow_dispatch")
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
            .putString(KEY_LAST_BUILD_EVENT, "workflow_dispatch")
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
        expectedWorkflowId: Long,
        expectedEvent: String = "workflow_dispatch"
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
                event == expectedEvent

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
            val httpCode = response.optInt("http_code", -1)
            return failure(
                "Не удалось проверить GitHub App installation permissions."
            )
                .put("status", "installation_permissions_unavailable")
                .put("http_code", httpCode)
                .put("network_failure", httpCode < 0)
                .put("error", response.optString("error").take(320))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
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
        headSha: String,
        event: String = "workflow_dispatch"
    ): JSONObject {
        val response =
            githubJsonRequest(
                method = "GET",
                apiPath =
                    "/repos/$OWNER/$REPO/actions/workflows/$workflowId/runs" +
                        "?event=${urlEncode(event)}" +
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
            .put("last_build_event", prefs.getString(KEY_LAST_BUILD_EVENT, "").orEmpty())
            .put("last_build_run_url", prefs.getString(KEY_LAST_BUILD_RUN_URL, "").orEmpty())
            .put("last_build_status", prefs.getString(KEY_LAST_BUILD_STATUS, "").orEmpty())
            .put("last_build_conclusion", prefs.getString(KEY_LAST_BUILD_CONCLUSION, "").orEmpty())
            .put("last_build_artifact_id", prefs.getLong(KEY_LAST_BUILD_ARTIFACT_ID, 0L))
            .put("last_build_artifact_digest", prefs.getString(KEY_LAST_BUILD_ARTIFACT_DIGEST, "").orEmpty())
            .put("last_build_artifact_size_bytes", prefs.getLong(KEY_LAST_BUILD_ARTIFACT_SIZE, 0L))
            .put("last_build_verified_at_ms", prefs.getLong(KEY_LAST_BUILD_VERIFIED_AT, 0L))
            .put("development_transaction_implemented", true)
            .put("development_transaction_status", prefs.getString(KEY_DEV_TX_STATUS, "none").orEmpty())
            .put("development_transaction_id", prefs.getString(KEY_DEV_TX_ID, "").orEmpty())
            .put("development_transaction_device_confirmed", prefs.getBoolean(KEY_DEVICE_CONFIRMED_TRANSACTION, false))
            .put("development_transaction_last_commit_sha", prefs.getString(KEY_DEV_TX_COMMIT_SHA, "").orEmpty())
            .put("development_transaction_last_rollback_commit_sha", prefs.getString(KEY_DEV_TX_ROLLBACK_COMMIT_SHA, "").orEmpty())
            .put("last_error", prefs.getString(KEY_LAST_ERROR, "").orEmpty())
    }

    fun compactContext(): String {
        val runtime = runtimeSnapshot()
        return buildString {
            append("AYANA R10.27.3 GITHUB/DEVELOPMENT TRUTH: ")
            append("github_repository_write_implemented=true; ")
            append("github_commit_push_implemented=true; ")
            append("android_apk_build_implemented=true; ")
            append("development_agent_transaction_implemented=true; ")
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
            append("; development_transaction_device_confirmed=")
            append(runtime.optBoolean("development_transaction_device_confirmed", false))
            append(". GitHub file mutation remains two-phase. Standalone APK build is a separate two-phase authority: prepare is read-only, confirmed replay dispatches exactly «")
            append(BUILD_WORKFLOW_NAME)
            append("» on main and SUCCESS requires completed conclusion=success plus verified artifact «")
            append(APK_ARTIFACT_NAME)
            append("» with non-zero size and SHA-256 artifact digest. Development transaction composes immutable file snapshot + exact bounded replacement + commit; that commit's existing push-to-main CI run is correlated by exact SHA without a duplicate workflow_dispatch, then the transaction requires explicit accept or verified rollback. direct_apk_delivery=false.")
        }
    }

    fun selfTest(): Boolean {
        val okPath = validatePath("app/src/main/java/kg/autonomous/agent/Test.kt")
        val badTraversal = validatePath("../secret.txt")
        val badSecret = validatePath("app/ayana-release.jks")
        val devPath = validateDevelopmentTransactionPath("app/src/main/java/kg/autonomous/agent/Test.kt")
        val canonicalVoicePath =
            validateDevelopmentTransactionPath("AyanaVoiceService.kt")
        val canonicalVoicePathOk =
            canonicalVoicePath.optBoolean("success", false) &&
                canonicalVoicePath.optBoolean("path_alias_resolved", false) &&
                canonicalVoicePath.optString("path") ==
                    "app/src/main/java/kg/autonomous/agent/AyanaVoiceService.kt"
        val explicitPathUnchanged =
            validateDevelopmentTransactionPath(
                "app/src/main/java/kg/autonomous/agent/AyanaVoiceService.kt"
            )
        val explicitPathUnchangedOk =
            explicitPathUnchanged.optBoolean("success", false) &&
                !explicitPathUnchanged.optBoolean("path_alias_resolved", true) &&
                explicitPathUnchanged.optString("path") ==
                    "app/src/main/java/kg/autonomous/agent/AyanaVoiceService.kt"
        val rootTextPathUnchanged =
            validateDevelopmentTransactionPath("README.md")
        val rootTextPathUnchangedOk =
            rootTextPathUnchanged.optBoolean("success", false) &&
                !rootTextPathUnchanged.optBoolean("path_alias_resolved", true) &&
                rootTextPathUnchanged.optString("path") == "README.md"
        val devWorkflowBlocked = validateDevelopmentTransactionPath(".github/workflows/build-apk.yml")
        val bytes = "hello".toByteArray(StandardCharsets.UTF_8)
        val knownBlob = gitBlobSha(bytes)
        val disambiguationCandidates =
            developmentMatchCandidates(
                text =
                    "header R10.28.5 release\n" +
                        "history R10.28.5 old\n" +
                        "footer",
                needle = "R10.28.5"
            )
        val firstCandidate =
            disambiguationCandidates.optString(0)
        val secondCandidate =
            disambiguationCandidates.optString(1)
        val disambiguationOk =
            disambiguationCandidates.length() == 2 &&
                firstCandidate.contains("R10.28.5") &&
                secondCandidate.contains("R10.28.5") &&
                countOccurrences(
                    "header R10.28.5 release\n" +
                        "history R10.28.5 old\n" +
                        "footer",
                    firstCandidate
                ) == 1 &&
                countOccurrences(
                    "header R10.28.5 release\n" +
                        "history R10.28.5 old\n" +
                        "footer",
                    secondCandidate
                ) == 1
        return okPath.optBoolean("success", false) &&
            !badTraversal.optBoolean("success", true) &&
            !badSecret.optBoolean("success", true) &&
            devPath.optBoolean("success", false) &&
            canonicalVoicePathOk &&
            explicitPathUnchangedOk &&
            rootTextPathUnchangedOk &&
            !devWorkflowBlocked.optBoolean("success", true) &&
            countOccurrences("abc abc", "abc") == 2 &&
            disambiguationOk &&
            !containsSensitiveMaterial("ordinary Kotlin text") &&
            containsSensitiveMaterial("-----BEGIN PRIVATE KEY-----") &&
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
        const val VERSION = "1.3.0"

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
        private const val KEY_LAST_BUILD_EVENT = "last_build_event"
        private const val KEY_LAST_BUILD_RUN_URL = "last_build_run_url"
        private const val KEY_LAST_BUILD_STARTED_AT = "last_build_started_at"
        private const val KEY_LAST_BUILD_STATUS = "last_build_status"
        private const val KEY_LAST_BUILD_CONCLUSION = "last_build_conclusion"
        private const val KEY_LAST_BUILD_ARTIFACT_ID = "last_build_artifact_id"
        private const val KEY_LAST_BUILD_ARTIFACT_DIGEST = "last_build_artifact_digest"
        private const val KEY_LAST_BUILD_ARTIFACT_SIZE = "last_build_artifact_size"
        private const val KEY_LAST_BUILD_VERIFIED_AT = "last_build_verified_at"
        private const val KEY_DEV_TX_ID = "development_transaction_id"
        private const val KEY_DEV_TX_STATUS = "development_transaction_status"
        private const val KEY_DEV_TX_PATH = "development_transaction_path"
        private const val KEY_DEV_TX_FIND_TEXT = "development_transaction_find_text"
        private const val KEY_DEV_TX_REPLACE_TEXT = "development_transaction_replace_text"
        private const val KEY_DEV_TX_COMMIT_MESSAGE = "development_transaction_commit_message"
        private const val KEY_DEV_TX_BASE_HEAD_SHA = "development_transaction_base_head_sha"
        private const val KEY_DEV_TX_ORIGINAL_BLOB_SHA = "development_transaction_original_blob_sha"
        private const val KEY_DEV_TX_PROPOSED_BLOB_SHA = "development_transaction_proposed_blob_sha"
        private const val KEY_DEV_TX_WORKFLOW_ID = "development_transaction_workflow_id"
        private const val KEY_DEV_TX_COMMIT_SHA = "development_transaction_commit_sha"
        private const val KEY_DEV_TX_BUILD_RUN_ID = "development_transaction_build_run_id"
        private const val KEY_DEV_TX_ARTIFACT_ID = "development_transaction_artifact_id"
        private const val KEY_DEV_TX_ARTIFACT_DIGEST = "development_transaction_artifact_digest"
        private const val KEY_DEV_TX_ARTIFACT_SIZE = "development_transaction_artifact_size"
        private const val KEY_DEV_TX_ROLLBACK_COMMIT_SHA = "development_transaction_rollback_commit_sha"
        private const val KEY_DEV_TX_CREATED_AT = "development_transaction_created_at"
        private const val KEY_DEV_TX_UPDATED_AT = "development_transaction_updated_at"
        private const val KEY_DEVICE_CONFIRMED_TRANSACTION = "development_transaction_device_confirmed"


        private const val RUNTIME_FRESHNESS_MS = 15L * 60L * 1000L
        private const val BUILD_PREPARE_AUTHORITY_CACHE_MS = 5L * 60L * 1000L
        private const val TOKEN_REFRESH_SKEW_MS = 2L * 60L * 1000L
        private const val MAX_INLINE_CONTENT_BYTES = 6_000
        private const val MAX_PATH_CHARS = 320
        private const val MAX_COMMIT_MESSAGE_CHARS = 160
        private const val MAX_TRANSACTION_FIND_BYTES = 2_000
        private const val MAX_TRANSACTION_REPLACE_BYTES = 3_500
        private const val MAX_TRANSACTION_PATCH_BYTES = 5_000
        private const val MAX_TRANSACTION_FILE_BYTES = 5_000_000L
        private const val MAX_TRANSACTION_MATCH_CANDIDATES = 6
        private const val MATCH_CONTEXT_INITIAL_RADIUS_CHARS = 40
        private const val MATCH_CONTEXT_RADIUS_STEP_CHARS = 40
        private const val MATCH_CONTEXT_MAX_RADIUS_CHARS = 240

        private const val DEV_TX_STATUS_PREPARED = "prepared"
        private const val DEV_TX_STATUS_BUILDING = "building"
        private const val DEV_TX_STATUS_WAITING_ACCEPTANCE = "waiting_acceptance"
        private const val DEV_TX_STATUS_BUILD_RECONCILIATION_REQUIRED = "build_reconciliation_required"
        private const val DEV_TX_STATUS_ROLLBACK_BUILDING = "rollback_building"
        private const val DEV_TX_STATUS_ROLLBACK_BLOCKED = "rollback_blocked"
        private const val DEV_TX_STATUS_ROLLBACK_BUILD_FAILED = "rollback_build_failed"
        private const val DEV_TX_STATUS_ACCEPTED = "accepted"
        private const val DEV_TX_STATUS_ROLLED_BACK = "rolled_back"
        private const val DEV_TX_STATUS_CANCELLED = "cancelled"

        private val ACTIVE_DEV_TX_STATUSES =
            setOf(
                DEV_TX_STATUS_PREPARED,
                DEV_TX_STATUS_BUILDING,
                DEV_TX_STATUS_WAITING_ACCEPTANCE,
                DEV_TX_STATUS_BUILD_RECONCILIATION_REQUIRED,
                DEV_TX_STATUS_ROLLBACK_BUILDING,
                DEV_TX_STATUS_ROLLBACK_BLOCKED,
                DEV_TX_STATUS_ROLLBACK_BUILD_FAILED
            )

        private val TRANSACTION_ALLOWED_EXTENSIONS =
            setOf(
                ".kt", ".java", ".json", ".xml", ".gradle", ".kts",
                ".properties", ".txt", ".md", ".yml", ".yaml"
            )

        private val SENSITIVE_PATCH_PATTERNS =
            listOf(
                Regex("-----BEGIN\\s+(?:RSA\\s+|EC\\s+|OPENSSH\\s+)?PRIVATE KEY-----", RegexOption.IGNORE_CASE),
                Regex("\\bgithub_pat_[A-Za-z0-9_]{20,}\\b"),
                Regex("\\bgh[pousr]_[A-Za-z0-9]{20,}\\b"),
                Regex("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
                Regex("\\bAKIA[0-9A-Z]{16}\\b")
            )


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
