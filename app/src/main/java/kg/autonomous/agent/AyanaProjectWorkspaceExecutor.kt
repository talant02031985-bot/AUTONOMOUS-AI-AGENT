package kg.autonomous.agent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * AYANA Project Workspace Executor v1.0.1 — DEVELOPMENT WORKSPACE 2.0 / local foundation.
 *
 * Purpose:
 * - operate only inside the currently active AYANA Project filesRoot;
 * - read/list UTF-8 project files without crossing project boundaries;
 * - prepare a bounded multi-file write transaction with zero side effects;
 * - commit only a previously prepared transaction after Android-side trusted confirmation;
 * - verify every committed file by SHA-256 and keep rollback material until accept;
 * - support verified cancel / rollback / accept controls.
 *
 * This class intentionally does NOT:
 * - touch AYANA's own source tree outside the active project's filesRoot;
 * - write GitHub, dispatch Actions, build APKs, install APKs, or manage credentials;
 * - delete arbitrary project files outside a verified rollback of its own transaction;
 * - write binary files, keystores, secrets, .git metadata, workflows, or generated build trees.
 *
 * Confirmation authority is not accepted from model-authored arguments. VoiceService must call
 * writeTransaction(..., confirmed=true) only after a fresh local user confirmation and inject
 * _project_workspace_transaction_id from the verified PREPARE result.
 */
class AyanaProjectWorkspaceExecutor(
    context: Context,
    private val boundProjectIdProvider: (() -> String?)? = null
) {

    private val appContext =
        context.applicationContext

    private val projectStore by lazy {
        AyanaProjectStore(
            appContext
        )
    }

    private val projectDataScope by lazy {
        AyanaProjectDataScope(
            appContext,
            projectStore
        )
    }

    fun status(): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val root =
            File(
                scope.optString("root_path")
            )

        val counts =
            countWorkspaceFiles(
                root = root,
                limit = STATUS_FILE_COUNT_SCAN_LIMIT
            )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "project_workspace_ready")
            .put("version", VERSION)
            .put("project_id", scope.optString("project_id"))
            .put("project_name", scope.optString("project_name"))
            .put("workspace_path", scope.optString("workspace_path"))
            .put("workspace_root_verified", true)
            .put("text_file_read", true)
            .put("text_file_create", true)
            .put("text_file_update", true)
            .put("multi_file_transaction", true)
            .put("transaction_requires_confirmation", true)
            .put("rollback_supported", true)
            .put("binary_write_supported", false)
            .put("arbitrary_delete_supported", false)
            .put("github_mutation", false)
            .put("apk_build", false)
            .put("file_count_observed", counts.first)
            .put("file_count_scan_truncated", counts.second)
            .put("max_files_per_transaction", MAX_FILES_PER_TRANSACTION)
            .put("max_file_bytes", MAX_FILE_BYTES)
            .put("max_transaction_bytes", MAX_TRANSACTION_BYTES)
            .put(
                "message",
                "Локальный workspace проекта готов: чтение и bounded multi-file UTF-8 transaction доступны только внутри текущего проекта."
            )
    }

    fun listFiles(
        arguments: JSONObject
    ): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val root = File(scope.optString("root_path"))
        val relativeDir =
            arguments
                .optString("path")
                .trim()
                .replace('\\', '/')
                .removePrefix("/")

        val recursive =
            arguments.optBoolean(
                "recursive",
                false
            )

        val limit =
            arguments
                .optInt(
                    "limit",
                    DEFAULT_LIST_LIMIT
                )
                .coerceIn(
                    1,
                    MAX_LIST_LIMIT
                )

        val directoryResult =
            resolveWorkspacePath(
                root = root,
                rawPath = relativeDir,
                allowRoot = true
            )

        if (!directoryResult.optBoolean("success", false)) {
            return directoryResult
        }

        val directory = File(directoryResult.optString("absolute_path"))
        if (!directory.exists()) {
            return failure(
                "Каталог проекта не найден."
            )
                .put("status", "workspace_directory_not_found")
                .put("path", relativeDir)
        }

        if (!directory.isDirectory) {
            return failure(
                "Указанный путь не является каталогом проекта."
            )
                .put("status", "workspace_path_not_directory")
                .put("path", relativeDir)
        }

        val items = JSONArray()
        var truncated = false

        fun addDirectory(current: File) {
            if (items.length() >= limit) {
                truncated = true
                return
            }

            val children =
                current
                    .listFiles()
                    ?.sortedWith(
                        compareBy<File>(
                            { !it.isDirectory },
                            { it.name.lowercase(Locale.ROOT) }
                        )
                    )
                    ?: emptyList()

            for (child in children) {
                if (items.length() >= limit) {
                    truncated = true
                    return
                }

                if (isWorkspaceMetadataPath(root, child)) {
                    continue
                }

                val relative =
                    relativePath(
                        root,
                        child
                    )
                        ?: continue

                val item =
                    JSONObject()
                        .put("path", relative)
                        .put("type", if (child.isDirectory) "directory" else "file")
                        .put("size_bytes", if (child.isFile) child.length() else 0L)
                        .put("last_modified_ms", child.lastModified())

                if (child.isFile && child.length() <= MAX_HASH_READ_BYTES) {
                    item.put(
                        "sha256",
                        sha256File(child)
                    )
                }

                items.put(item)

                if (recursive && child.isDirectory) {
                    addDirectory(child)
                }
            }
        }

        addDirectory(directory)

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "project_workspace_listed")
            .put("project_id", scope.optString("project_id"))
            .put("project_name", scope.optString("project_name"))
            .put("path", relativeDir)
            .put("recursive", recursive)
            .put("items", items)
            .put("count", items.length())
            .put("truncated", truncated)
            .put("side_effect_state", "NONE")
    }

    fun readTextFile(
        arguments: JSONObject
    ): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val root = File(scope.optString("root_path"))
        val pathResult =
            resolveWorkspacePath(
                root = root,
                rawPath = arguments.optString("path"),
                allowRoot = false
            )

        if (!pathResult.optBoolean("success", false)) {
            return pathResult
        }

        val path = pathResult.optString("relative_path")
        val file = File(pathResult.optString("absolute_path"))

        if (!file.exists()) {
            return failure(
                "Файл проекта не найден."
            )
                .put("status", "workspace_file_not_found")
                .put("path", path)
        }

        if (!file.isFile) {
            return failure(
                "Указанный путь проекта не является файлом."
            )
                .put("status", "workspace_path_not_file")
                .put("path", path)
        }

        val size = file.length()
        val maxBytes =
            arguments
                .optInt(
                    "max_bytes",
                    DEFAULT_READ_BYTES
                )
                .coerceIn(
                    1,
                    MAX_READ_BYTES
                )

        if (size > MAX_READ_BYTES) {
            return failure(
                "Файл слишком большой для bounded workspace read: $size байт."
            )
                .put("status", "workspace_file_too_large")
                .put("path", path)
                .put("size_bytes", size)
                .put("max_bytes", MAX_READ_BYTES)
        }

        val bytes = file.readBytes()
        if (!isLikelyUtf8Text(bytes)) {
            return failure(
                "Workspace v1.0 читает только UTF-8 текстовые файлы."
            )
                .put("status", "workspace_binary_read_blocked")
                .put("path", path)
        }

        val clipped =
            if (bytes.size <= maxBytes) {
                bytes
            } else {
                bytes.copyOfRange(0, maxBytes)
            }

        val content =
            String(
                clipped,
                StandardCharsets.UTF_8
            )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", "project_workspace_file_read")
            .put("project_id", scope.optString("project_id"))
            .put("project_name", scope.optString("project_name"))
            .put("path", path)
            .put("size_bytes", size)
            .put("sha256", sha256(bytes))
            .put("content", content)
            .put("truncated", bytes.size > clipped.size)
            .put("returned_bytes", clipped.size)
            .put("side_effect_state", "NONE")
    }

    /**
     * Two-phase local workspace transaction.
     *
     * confirmed=false -> PREPARE only; validates all paths/content/baselines and persists a private plan.
     * confirmed=true  -> COMMIT only; requires _project_workspace_transaction_id injected by Android.
     */
    fun writeTransaction(
        arguments: JSONObject,
        confirmed: Boolean
    ): JSONObject =
        if (!confirmed) {
            prepareWriteTransaction(arguments)
        } else {
            commitPreparedTransaction(
                arguments
                    .optString("_project_workspace_transaction_id")
                    .trim()
            )
        }

    fun transactionStatus(
        transactionId: String
    ): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val txId = transactionId.trim()
        if (!VALID_TRANSACTION_ID.matches(txId)) {
            return failure(
                "Некорректный идентификатор workspace transaction."
            )
                .put("status", "workspace_transaction_id_invalid")
        }

        val planFile =
            transactionPlanFile(
                File(scope.optString("root_path")),
                txId
            )

        if (!planFile.exists()) {
            return failure(
                "Workspace transaction не найдена."
            )
                .put("status", "workspace_transaction_not_found")
                .put("transaction_id", txId)
        }

        val plan = readJsonFile(planFile)
            ?: return failure(
                "Workspace transaction повреждена и не может быть подтверждена."
            )
                .put("status", "workspace_transaction_state_invalid")
                .put("transaction_id", txId)

        return projectTransactionView(
            plan,
            includeFiles = true
        )
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("side_effect_state", "NONE")
    }

    fun cancelPreparedTransaction(
        transactionId: String
    ): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val txId = transactionId.trim()
        if (!VALID_TRANSACTION_ID.matches(txId)) {
            return failure("Некорректный идентификатор workspace transaction.")
                .put("status", "workspace_transaction_id_invalid")
        }

        val root = File(scope.optString("root_path"))
        val planFile = transactionPlanFile(root, txId)
        val plan = readJsonFile(planFile)
            ?: return failure("Workspace transaction не найдена.")
                .put("status", "workspace_transaction_not_found")
                .put("transaction_id", txId)

        if (plan.optString("status") != TX_PREPARED) {
            return failure(
                "Отменить можно только подготовленную, ещё не выполненную workspace transaction."
            )
                .put("status", "workspace_transaction_not_prepared")
                .put("transaction_id", txId)
                .put("current_status", plan.optString("status"))
        }

        plan
            .put("status", TX_CANCELLED)
            .put("updated_at_ms", System.currentTimeMillis())

        if (!writeJsonFileAtomic(planFile, plan)) {
            return failure(
                "Не удалось надёжно сохранить отмену workspace transaction."
            )
                .put("status", "workspace_transaction_cancel_persist_failed")
                .put("transaction_id", txId)
        }

        cleanupTransactionPayload(
            root,
            txId,
            keepPlan = true
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", TX_CANCELLED)
            .put("transaction_id", txId)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("message", "Подготовленная workspace transaction отменена; файлы проекта не изменялись.")
    }

    /**
     * Roll back only a transaction created by this executor.
     * VoiceService must gate this control behind fresh explicit user confirmation.
     */
    fun rollbackCommittedTransaction(
        transactionId: String
    ): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val txId = transactionId.trim()
        if (!VALID_TRANSACTION_ID.matches(txId)) {
            return failure("Некорректный идентификатор workspace transaction.")
                .put("status", "workspace_transaction_id_invalid")
        }

        val root = File(scope.optString("root_path"))
        val planFile = transactionPlanFile(root, txId)
        val plan = readJsonFile(planFile)
            ?: return failure("Workspace transaction не найдена.")
                .put("status", "workspace_transaction_not_found")
                .put("transaction_id", txId)

        if (plan.optString("project_id") != scope.optString("project_id")) {
            return failure(
                "Workspace transaction принадлежит другому проекту."
            )
                .put("status", "workspace_transaction_project_mismatch")
                .put("transaction_id", txId)
        }

        if (plan.optString("status") != TX_COMMITTED) {
            return failure(
                "Rollback доступен только для подтверждённой committed workspace transaction."
            )
                .put("status", "workspace_transaction_not_committed")
                .put("transaction_id", txId)
                .put("current_status", plan.optString("status"))
        }

        val rollback =
            restoreBaseline(
                root = root,
                plan = plan,
                txId = txId
            )

        if (!rollback.optBoolean("success", false)) {
            plan
                .put("status", TX_ROLLBACK_INCOMPLETE)
                .put("updated_at_ms", System.currentTimeMillis())
            writeJsonFileAtomic(planFile, plan)

            return JSONObject(rollback.toString())
                .put("verified", false)
                .put("terminal_status", "ERROR")
                .put("status", TX_ROLLBACK_INCOMPLETE)
                .put("transaction_id", txId)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "UNKNOWN")
        }

        plan
            .put("status", TX_ROLLED_BACK)
            .put("updated_at_ms", System.currentTimeMillis())

        if (!writeJsonFileAtomic(planFile, plan)) {
            return failure(
                "Файлы восстановлены, но состояние rollback не удалось надёжно сохранить."
            )
                .put("verified", false)
                .put("terminal_status", "ERROR")
                .put("status", "workspace_rollback_state_persist_failed")
                .put("transaction_id", txId)
                .put("repository_restored", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "UNKNOWN")
        }

        cleanupTransactionPayload(
            root,
            txId,
            keepPlan = true
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", TX_ROLLED_BACK)
            .put("transaction_id", txId)
            .put("workspace_restored", true)
            .put("action_dispatched", true)
            .put("action_committed", true)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "project_workspace_rollback")
            .put("message", "Workspace transaction доказательно откатана до исходного состояния.")
    }

    fun acceptCommittedTransaction(
        transactionId: String
    ): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val txId = transactionId.trim()
        if (!VALID_TRANSACTION_ID.matches(txId)) {
            return failure("Некорректный идентификатор workspace transaction.")
                .put("status", "workspace_transaction_id_invalid")
        }

        val root = File(scope.optString("root_path"))
        val planFile = transactionPlanFile(root, txId)
        val plan = readJsonFile(planFile)
            ?: return failure("Workspace transaction не найдена.")
                .put("status", "workspace_transaction_not_found")
                .put("transaction_id", txId)

        if (plan.optString("project_id") != scope.optString("project_id")) {
            return failure("Workspace transaction принадлежит другому проекту.")
                .put("status", "workspace_transaction_project_mismatch")
                .put("transaction_id", txId)
        }

        if (plan.optString("status") != TX_COMMITTED) {
            return failure(
                "Принять можно только подтверждённую committed workspace transaction."
            )
                .put("status", "workspace_transaction_not_committed")
                .put("transaction_id", txId)
                .put("current_status", plan.optString("status"))
        }

        plan
            .put("status", TX_ACCEPTED)
            .put("updated_at_ms", System.currentTimeMillis())

        if (!writeJsonFileAtomic(planFile, plan)) {
            return failure("Не удалось сохранить принятие workspace transaction.")
                .put("status", "workspace_transaction_accept_persist_failed")
                .put("transaction_id", txId)
        }

        cleanupTransactionPayload(
            root,
            txId,
            keepPlan = true
        )

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", TX_ACCEPTED)
            .put("transaction_id", txId)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put("message", "Workspace transaction принята; rollback payload очищен.")
    }

    fun selfTest(): JSONObject {
        val traversalRejected =
            validateRelativePath("../escape.kt") == null &&
                validateRelativePath("a/../../escape.kt") == null &&
                validateRelativePath("/absolute.kt") == null

        val protectedRejected =
            isBlockedPath(".git/config") &&
                isBlockedPath(".github/workflows/build.yml") &&
                isBlockedPath("local.properties") &&
                isBlockedPath("release.jks") &&
                !isBlockedPath("app/src/main/java/kg/store/accounting/MainActivity.kt") &&
                !isBlockedPath("settings.gradle.kts") &&
                !isBlockedPath("gradle.properties")

        val sensitiveRejected =
            containsSensitiveMaterial("-----BEGIN PRIVATE KEY-----\nabc") &&
                containsSensitiveMaterial("github_pat_012345678901234567890123456789") &&
                !containsSensitiveMaterial("val passwordFieldLabel = \"Пароль\"")

        val hashKnown =
            sha256("abc".toByteArray(StandardCharsets.UTF_8)) ==
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        val success =
            traversalRejected &&
                protectedRejected &&
                sensitiveRejected &&
                hashKnown

        return JSONObject()
            .put("success", success)
            .put("verified", success)
            .put("version", VERSION)
            .put("path_guard", traversalRejected)
            .put("protected_path_guard", protectedRejected)
            .put("sensitive_material_guard", sensitiveRejected)
            .put("sha256_known_vector", hashKnown)
    }

    private fun prepareWriteTransaction(
        arguments: JSONObject
    ): JSONObject {
        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val projectId = scope.optString("project_id")
        val projectName = scope.optString("project_name")
        val root = File(scope.optString("root_path"))

        val requestedFiles =
            arguments.optJSONArray("files")
                ?: return failure(
                    "Workspace transaction требует массив files."
                )
                    .put("status", "workspace_files_missing")

        if (requestedFiles.length() !in 1..MAX_FILES_PER_TRANSACTION) {
            return failure(
                "Workspace transaction поддерживает от 1 до $MAX_FILES_PER_TRANSACTION файлов."
            )
                .put("status", "workspace_file_count_out_of_range")
                .put("file_count", requestedFiles.length())
        }

        val normalizedFiles = JSONArray()
        val seen = HashSet<String>()
        var totalBytes = 0L

        for (index in 0 until requestedFiles.length()) {
            val item =
                requestedFiles.optJSONObject(index)
                    ?: return failure(
                        "Элемент files[$index] должен быть объектом."
                    )
                        .put("status", "workspace_file_entry_invalid")
                        .put("index", index)

            val path =
                validateRelativePath(
                    item.optString("path")
                )
                    ?: return failure(
                        "Некорректный путь files[$index]."
                    )
                        .put("status", "workspace_file_path_invalid")
                        .put("index", index)

            if (!seen.add(path)) {
                return failure(
                    "Один и тот же путь указан в transaction несколько раз: $path"
                )
                    .put("status", "workspace_duplicate_path")
                    .put("path", path)
            }

            if (isBlockedPath(path)) {
                return failure(
                    "Путь заблокирован политикой локального project workspace: $path"
                )
                    .put("status", "workspace_protected_path")
                    .put("path", path)
            }

            val content = item.optString("content")
            if (containsSensitiveMaterial(content)) {
                return failure(
                    "Содержимое похоже на ключ/токен/credential material и не будет записано в project workspace."
                )
                    .put("status", "workspace_sensitive_content_blocked")
                    .put("path", path)
            }

            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            if (bytes.size > MAX_FILE_BYTES) {
                return failure(
                    "Файл $path превышает bounded limit $MAX_FILE_BYTES байт."
                )
                    .put("status", "workspace_file_too_large")
                    .put("path", path)
                    .put("size_bytes", bytes.size)
                    .put("max_bytes", MAX_FILE_BYTES)
            }

            if (!isLikelyUtf8Text(bytes)) {
                return failure(
                    "Workspace v1.0 записывает только UTF-8 текстовые файлы."
                )
                    .put("status", "workspace_binary_write_blocked")
                    .put("path", path)
            }

            totalBytes += bytes.size.toLong()
            if (totalBytes > MAX_TRANSACTION_BYTES) {
                return failure(
                    "Workspace transaction превышает общий limit $MAX_TRANSACTION_BYTES байт."
                )
                    .put("status", "workspace_transaction_too_large")
                    .put("total_bytes", totalBytes)
                    .put("max_bytes", MAX_TRANSACTION_BYTES)
            }

            val pathResult =
                resolveWorkspacePath(
                    root = root,
                    rawPath = path,
                    allowRoot = false
                )

            if (!pathResult.optBoolean("success", false)) {
                return pathResult
            }

            val target = File(pathResult.optString("absolute_path"))
            val exists = target.exists()

            if (exists && !target.isFile) {
                return failure(
                    "Нельзя заменить каталог текстовым файлом: $path"
                )
                    .put("status", "workspace_target_not_file")
                    .put("path", path)
            }

            val baselineBytes =
                if (exists) {
                    if (target.length() > MAX_FILE_BYTES) {
                        return failure(
                            "Существующий файл $path слишком большой для безопасной transaction."
                        )
                            .put("status", "workspace_existing_file_too_large")
                            .put("path", path)
                            .put("size_bytes", target.length())
                    }
                    target.readBytes()
                } else {
                    ByteArray(0)
                }

            if (exists && !isLikelyUtf8Text(baselineBytes)) {
                return failure(
                    "Существующий файл $path не подтверждён как UTF-8 текст."
                )
                    .put("status", "workspace_existing_binary_blocked")
                    .put("path", path)
            }

            val baselineSha =
                if (exists) sha256(baselineBytes) else ""

            val expectedSha =
                item
                    .optString("expected_sha256")
                    .trim()
                    .lowercase(Locale.ROOT)

            if (exists) {
                if (!SHA256_HEX.matches(expectedSha)) {
                    return failure(
                        "Для изменения существующего файла нужен его exact expected_sha256: $path"
                    )
                        .put("status", "workspace_expected_sha_required")
                        .put("path", path)
                        .put("current_sha256", baselineSha)
                }

                if (expectedSha != baselineSha) {
                    return failure(
                        "Файл $path изменился: expected_sha256 не совпадает с текущим содержимым."
                    )
                        .put("status", "workspace_baseline_sha_mismatch")
                        .put("path", path)
                        .put("expected_sha256", expectedSha)
                        .put("current_sha256", baselineSha)
                }
            } else if (expectedSha.isNotBlank()) {
                return failure(
                    "Файл $path ещё не существует, поэтому expected_sha256 должен быть пустым."
                )
                    .put("status", "workspace_new_file_expected_sha_not_empty")
                    .put("path", path)
            }

            val proposedSha = sha256(bytes)
            val action =
                when {
                    !exists -> "create"
                    proposedSha == baselineSha -> "noop"
                    else -> "update"
                }

            normalizedFiles.put(
                JSONObject()
                    .put("path", path)
                    .put("content", content)
                    .put("content_bytes", bytes.size)
                    .put("baseline_exists", exists)
                    .put("baseline_sha256", baselineSha)
                    .put("baseline_size_bytes", baselineBytes.size)
                    .put("proposed_sha256", proposedSha)
                    .put("action", action)
            )
        }

        val txId =
            "pws-" +
                System.currentTimeMillis().toString(36) +
                "-" +
                UUID.randomUUID()
                    .toString()
                    .replace("-", "")
                    .take(12)

        val note =
            arguments
                .optString("note")
                .trim()
                .take(MAX_NOTE_CHARS)

        val now = System.currentTimeMillis()
        val plan =
            JSONObject()
                .put("schema", PLAN_SCHEMA)
                .put("version", VERSION)
                .put("transaction_id", txId)
                .put("status", TX_PREPARED)
                .put("project_id", projectId)
                .put("project_name", projectName)
                .put("workspace_path", scope.optString("workspace_path"))
                .put("note", note)
                .put("created_at_ms", now)
                .put("updated_at_ms", now)
                .put("total_bytes", totalBytes)
                .put("files", normalizedFiles)

        val manifestSha =
            manifestSha256(plan)

        plan.put("manifest_sha256", manifestSha)

        val planFile = transactionPlanFile(root, txId)
        if (!writeJsonFileAtomic(planFile, plan)) {
            return failure(
                "Не удалось надёжно сохранить подготовленную workspace transaction."
            )
                .put("status", "workspace_prepare_persist_failed")
        }

        val publicFiles = publicFileSummary(normalizedFiles)

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "BLOCKED")
            .put("status", "project_workspace_transaction_prepared")
            .put("phase", "prepare_read_only")
            .put("requires_confirmation", true)
            .put("transaction_id", txId)
            .put("manifest_sha256", manifestSha)
            .put("project_id", projectId)
            .put("project_name", projectName)
            .put("workspace_path", scope.optString("workspace_path"))
            .put("file_count", normalizedFiles.length())
            .put("total_bytes", totalBytes)
            .put("files", publicFiles)
            .put("action_dispatched", false)
            .put("action_committed", false)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "NONE")
            .put(
                "message",
                "Workspace transaction подготовлена для ${normalizedFiles.length()} файлов. Файлы проекта ещё НЕ изменены; требуется отдельное подтверждение пользователя."
            )
    }

    private fun commitPreparedTransaction(
        transactionId: String
    ): JSONObject {
        if (!VALID_TRANSACTION_ID.matches(transactionId)) {
            return failure(
                "Подтверждённая workspace transaction не содержит валидный prepared transaction_id."
            )
                .put("status", "workspace_confirm_payload_invalid")
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        val scope = currentScope()
        if (!scope.optBoolean("success", false)) {
            return scope
        }

        val root = File(scope.optString("root_path"))
        val planFile = transactionPlanFile(root, transactionId)
        val plan = readJsonFile(planFile)
            ?: return failure(
                "Prepared workspace transaction не найдена или повреждена."
            )
                .put("status", "workspace_prepared_transaction_missing")
                .put("transaction_id", transactionId)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")

        if (plan.optString("status") != TX_PREPARED) {
            return failure(
                "Workspace transaction уже не находится в PREPARED состоянии."
            )
                .put("status", "workspace_transaction_not_prepared")
                .put("transaction_id", transactionId)
                .put("current_status", plan.optString("status"))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        if (plan.optString("project_id") != scope.optString("project_id")) {
            return failure(
                "Активный проект изменился после PREPARE. Workspace transaction остановлена."
            )
                .put("status", "workspace_active_project_changed")
                .put("transaction_id", transactionId)
                .put("prepared_project_id", plan.optString("project_id"))
                .put("active_project_id", scope.optString("project_id"))
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        val storedManifest = plan.optString("manifest_sha256")
        val recomputedManifest = manifestSha256(plan)
        if (
            !SHA256_HEX.matches(storedManifest) ||
            storedManifest != recomputedManifest
        ) {
            return failure(
                "Prepared workspace manifest не прошёл integrity check."
            )
                .put("status", "workspace_manifest_integrity_failed")
                .put("transaction_id", transactionId)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        val files = plan.optJSONArray("files") ?: JSONArray()
        if (files.length() !in 1..MAX_FILES_PER_TRANSACTION) {
            return failure(
                "Prepared workspace transaction имеет недопустимый file_count."
            )
                .put("status", "workspace_manifest_file_count_invalid")
                .put("transaction_id", transactionId)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        // Reconcile every baseline BEFORE the first write.
        for (index in 0 until files.length()) {
            val item = files.optJSONObject(index) ?: continue
            val path = item.optString("path")
            val pathResult = resolveWorkspacePath(root, path, allowRoot = false)
            if (!pathResult.optBoolean("success", false)) {
                return JSONObject(pathResult.toString())
                    .put("transaction_id", transactionId)
                    .put("action_dispatched", false)
                    .put("action_committed", false)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "NONE")
            }

            val target = File(pathResult.optString("absolute_path"))
            val expectedExists = item.optBoolean("baseline_exists", false)
            val existsNow = target.exists()

            if (existsNow != expectedExists) {
                return failure(
                    "Workspace изменился после PREPARE: existence drift для $path."
                )
                    .put("status", "workspace_baseline_drift")
                    .put("transaction_id", transactionId)
                    .put("path", path)
                    .put("action_dispatched", false)
                    .put("action_committed", false)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "NONE")
            }

            if (existsNow) {
                if (!target.isFile || target.length() > MAX_FILE_BYTES) {
                    return failure(
                        "Workspace изменился после PREPARE: target для $path больше не является допустимым text file."
                    )
                        .put("status", "workspace_baseline_drift")
                        .put("transaction_id", transactionId)
                        .put("path", path)
                        .put("action_dispatched", false)
                        .put("action_committed", false)
                        .put("reconciliation_complete", true)
                        .put("side_effect_state", "NONE")
                }

                val currentSha = sha256File(target)
                if (currentSha != item.optString("baseline_sha256")) {
                    return failure(
                        "Workspace изменился после PREPARE: SHA drift для $path."
                    )
                        .put("status", "workspace_baseline_drift")
                        .put("transaction_id", transactionId)
                        .put("path", path)
                        .put("expected_sha256", item.optString("baseline_sha256"))
                        .put("current_sha256", currentSha)
                        .put("action_dispatched", false)
                        .put("action_committed", false)
                        .put("reconciliation_complete", true)
                        .put("side_effect_state", "NONE")
                }
            }
        }

        val txDir = transactionDirectory(root, transactionId)
        val backupDir = File(txDir, "backup")
        val stagingDir = File(txDir, "staging")
        if (!backupDir.mkdirs() && !backupDir.isDirectory) {
            return failure("Не удалось подготовить rollback storage workspace transaction.")
                .put("status", "workspace_backup_storage_failed")
                .put("transaction_id", transactionId)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }
        if (!stagingDir.mkdirs() && !stagingDir.isDirectory) {
            return failure("Не удалось подготовить staging storage workspace transaction.")
                .put("status", "workspace_staging_storage_failed")
                .put("transaction_id", transactionId)
                .put("action_dispatched", false)
                .put("action_committed", false)
                .put("reconciliation_complete", true)
                .put("side_effect_state", "NONE")
        }

        // Capture all baselines and stage all proposed bytes before first mutation.
        for (index in 0 until files.length()) {
            val item = files.optJSONObject(index) ?: continue
            val pathResult = resolveWorkspacePath(root, item.optString("path"), allowRoot = false)
            val target = File(pathResult.optString("absolute_path"))

            if (item.optBoolean("baseline_exists", false)) {
                val backupFile = File(backupDir, "$index.bin")
                if (!writeBytesSynced(backupFile, target.readBytes())) {
                    return failure("Не удалось сохранить rollback backup до изменения workspace.")
                        .put("status", "workspace_backup_write_failed")
                        .put("transaction_id", transactionId)
                        .put("path", item.optString("path"))
                        .put("action_dispatched", false)
                        .put("action_committed", false)
                        .put("reconciliation_complete", true)
                        .put("side_effect_state", "NONE")
                }
            }

            val stagedFile = File(stagingDir, "$index.bin")
            val proposedBytes =
                item.optString("content")
                    .toByteArray(StandardCharsets.UTF_8)

            if (!writeBytesSynced(stagedFile, proposedBytes)) {
                return failure("Не удалось полностью подготовить staging workspace transaction.")
                    .put("status", "workspace_staging_write_failed")
                    .put("transaction_id", transactionId)
                    .put("path", item.optString("path"))
                    .put("action_dispatched", false)
                    .put("action_committed", false)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "NONE")
            }

            if (sha256File(stagedFile) != item.optString("proposed_sha256")) {
                return failure("Staging SHA verification failed for ${item.optString("path")}")
                    .put("status", "workspace_staging_sha_failed")
                    .put("transaction_id", transactionId)
                    .put("action_dispatched", false)
                    .put("action_committed", false)
                    .put("reconciliation_complete", true)
                    .put("side_effect_state", "NONE")
            }
        }

        var mutationStarted = false

        try {
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                if (item.optString("action") == "noop") continue

                val pathResult = resolveWorkspacePath(root, item.optString("path"), allowRoot = false)
                val target = File(pathResult.optString("absolute_path"))
                val stagedFile = File(stagingDir, "$index.bin")

                mutationStarted = true

                val parent = target.parentFile
                if (parent == null || (!parent.mkdirs() && !parent.isDirectory)) {
                    throw IllegalStateException("parent_create_failed:${item.optString("path")}")
                }

                val canonicalParent = parent.canonicalFile
                val canonicalRoot = root.canonicalFile
                if (!isInsideRoot(canonicalRoot, canonicalParent)) {
                    throw IllegalStateException("parent_scope_escape:${item.optString("path")}")
                }

                val temp =
                    File(
                        parent,
                        ".${target.name}.ayana-${transactionId}.tmp"
                    )

                if (!writeBytesSynced(temp, stagedFile.readBytes())) {
                    throw IllegalStateException("temp_write_failed:${item.optString("path")}")
                }

                if (temp.canonicalFile.parentFile?.canonicalPath != canonicalParent.canonicalPath) {
                    temp.delete()
                    throw IllegalStateException("temp_scope_escape:${item.optString("path")}")
                }

                if (target.exists() && !target.delete()) {
                    temp.delete()
                    throw IllegalStateException("target_replace_delete_failed:${item.optString("path")}")
                }

                if (!temp.renameTo(target)) {
                    val bytes = temp.readBytes()
                    if (!writeBytesSynced(target, bytes)) {
                        temp.delete()
                        throw IllegalStateException("target_write_failed:${item.optString("path")}")
                    }
                    temp.delete()
                }

                if (
                    !target.exists() ||
                    !target.isFile ||
                    sha256File(target) != item.optString("proposed_sha256")
                ) {
                    throw IllegalStateException("post_write_verify_failed:${item.optString("path")}")
                }
            }
        } catch (error: Exception) {
            val rollback = restoreBaseline(root, plan, transactionId)

            plan
                .put(
                    "status",
                    if (rollback.optBoolean("success", false)) {
                        TX_FAILED_ROLLED_BACK
                    } else {
                        TX_ROLLBACK_INCOMPLETE
                    }
                )
                .put("error", error.message.orEmpty().take(300))
                .put("updated_at_ms", System.currentTimeMillis())

            writeJsonFileAtomic(planFile, plan)

            return JSONObject()
                .put("success", false)
                .put("verified", rollback.optBoolean("success", false))
                .put("terminal_status", "ERROR")
                .put(
                    "status",
                    if (rollback.optBoolean("success", false)) {
                        TX_FAILED_ROLLED_BACK
                    } else {
                        TX_ROLLBACK_INCOMPLETE
                    }
                )
                .put("transaction_id", transactionId)
                .put("action_dispatched", mutationStarted)
                .put("action_committed", false)
                .put("workspace_restored", rollback.optBoolean("success", false))
                .put("reconciliation_complete", rollback.optBoolean("success", false))
                .put(
                    "side_effect_state",
                    if (rollback.optBoolean("success", false)) {
                        "NONE"
                    } else {
                        "UNKNOWN"
                    }
                )
                .put(
                    "message",
                    if (rollback.optBoolean("success", false)) {
                        "Workspace transaction не завершена; исходное состояние доказательно восстановлено."
                    } else {
                        "Workspace transaction прервана, а полный rollback не удалось подтвердить. Нужна ручная reconciliation."
                    }
                )
        }

        // Final verification over ALL files, including no-op files.
        val verification = JSONArray()
        for (index in 0 until files.length()) {
            val item = files.optJSONObject(index) ?: continue
            val pathResult = resolveWorkspacePath(root, item.optString("path"), allowRoot = false)
            val target = File(pathResult.optString("absolute_path"))
            val expectedSha = item.optString("proposed_sha256")
            val actualSha =
                if (target.exists() && target.isFile) {
                    sha256File(target)
                } else {
                    ""
                }

            val ok = expectedSha.isNotBlank() && actualSha == expectedSha
            verification.put(
                JSONObject()
                    .put("path", item.optString("path"))
                    .put("expected_sha256", expectedSha)
                    .put("actual_sha256", actualSha)
                    .put("verified", ok)
            )

            if (!ok) {
                val rollback = restoreBaseline(root, plan, transactionId)
                plan
                    .put(
                        "status",
                        if (rollback.optBoolean("success", false)) {
                            TX_FAILED_ROLLED_BACK
                        } else {
                            TX_ROLLBACK_INCOMPLETE
                        }
                    )
                    .put("updated_at_ms", System.currentTimeMillis())
                writeJsonFileAtomic(planFile, plan)

                return failure(
                    "Финальная SHA verification workspace transaction не прошла."
                )
                    .put("verified", rollback.optBoolean("success", false))
                    .put("terminal_status", "ERROR")
                    .put(
                        "status",
                        if (rollback.optBoolean("success", false)) {
                            TX_FAILED_ROLLED_BACK
                        } else {
                            TX_ROLLBACK_INCOMPLETE
                        }
                    )
                    .put("transaction_id", transactionId)
                    .put("verification", verification)
                    .put("action_dispatched", mutationStarted)
                    .put("action_committed", false)
                    .put("workspace_restored", rollback.optBoolean("success", false))
                    .put("reconciliation_complete", rollback.optBoolean("success", false))
                    .put(
                        "side_effect_state",
                        if (rollback.optBoolean("success", false)) "NONE" else "UNKNOWN"
                    )
            }
        }

        plan
            .put("status", TX_COMMITTED)
            .put("committed_at_ms", System.currentTimeMillis())
            .put("updated_at_ms", System.currentTimeMillis())

        if (!writeJsonFileAtomic(planFile, plan)) {
            // File content is already verified committed. Never lie that nothing happened.
            return JSONObject()
                .put("success", false)
                .put("verified", true)
                .put("terminal_status", "ERROR")
                .put("status", "workspace_commit_state_persist_failed")
                .put("transaction_id", transactionId)
                .put("verification", verification)
                .put("action_dispatched", mutationStarted)
                .put("action_committed", true)
                .put("reconciliation_complete", false)
                .put("side_effect_state", "VERIFIED_COMMITTED")
                .put("side_effect_kind", "project_workspace_write")
                .put("message", "Файлы записаны и SHA подтверждён, но состояние transaction не удалось надёжно сохранить.")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("terminal_status", "SUCCESS")
            .put("status", TX_COMMITTED)
            .put("transaction_id", transactionId)
            .put("project_id", scope.optString("project_id"))
            .put("project_name", scope.optString("project_name"))
            .put("file_count", files.length())
            .put("verification", verification)
            .put("requires_acceptance", true)
            .put("action_dispatched", mutationStarted)
            .put("action_committed", true)
            .put("reconciliation_complete", true)
            .put("side_effect_state", "VERIFIED_COMMITTED")
            .put("side_effect_kind", "project_workspace_write")
            .put(
                "message",
                "Workspace transaction записана и SHA-проверена. Для очистки rollback backup примите transaction; до этого её можно доказательно откатить."
            )
    }

    private fun restoreBaseline(
        root: File,
        plan: JSONObject,
        txId: String
    ): JSONObject {
        val files = plan.optJSONArray("files") ?: JSONArray()
        val backupDir = File(transactionDirectory(root, txId), "backup")
        val results = JSONArray()
        var allVerified = true

        for (index in files.length() - 1 downTo 0) {
            val item = files.optJSONObject(index) ?: continue
            val path = item.optString("path")
            val pathResult = resolveWorkspacePath(root, path, allowRoot = false)
            if (!pathResult.optBoolean("success", false)) {
                allVerified = false
                results.put(
                    JSONObject()
                        .put("path", path)
                        .put("restored", false)
                        .put("reason", "path_resolution_failed")
                )
                continue
            }

            val target = File(pathResult.optString("absolute_path"))
            val baselineExists = item.optBoolean("baseline_exists", false)
            var restored = false

            try {
                if (baselineExists) {
                    val backup = File(backupDir, "$index.bin")
                    if (!backup.exists()) {
                        restored = false
                    } else {
                        val parent = target.parentFile
                        if (parent != null && (parent.isDirectory || parent.mkdirs())) {
                            restored = writeBytesSynced(target, backup.readBytes()) &&
                                target.exists() &&
                                sha256File(target) == item.optString("baseline_sha256")
                        }
                    }
                } else {
                    restored =
                        if (!target.exists()) {
                            true
                        } else {
                            target.isFile && target.delete() && !target.exists()
                        }
                }
            } catch (_: Exception) {
                restored = false
            }

            allVerified = allVerified && restored
            results.put(
                JSONObject()
                    .put("path", path)
                    .put("restored", restored)
                    .put("baseline_exists", baselineExists)
            )
        }

        return JSONObject()
            .put("success", allVerified)
            .put("verified", allVerified)
            .put("results", results)
    }

    private fun currentScope(): JSONObject {
        // VoiceService supplies a command-lifetime project-id provider so one
        // execution turn cannot silently jump to another project if the UI
        // changes active_project_id while a tool is running. Standalone callers
        // may omit the provider and use the currently active project.
        val boundProviderPresent =
            boundProjectIdProvider != null

        val boundProjectId =
            try {
                boundProjectIdProvider
                    ?.invoke()
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }

        val project =
            try {
                if (boundProviderPresent) {
                    boundProjectId
                        ?.let { projectStore.getById(it) }
                } else {
                    projectStore.activeProject()
                }
            } catch (_: Exception) {
                null
            }
                ?: return failure(
                    "Project workspace требует проект, привязанный к текущей команде AYANA. В глобальном контексте запись проекта запрещена."
                )
                    .put("status", "project_workspace_no_active_project")
                    .put("scope", "GLOBAL")

        if (project.archived) {
            return failure(
                "Активный проект архивирован; workspace недоступен."
            )
                .put("status", "project_workspace_archived")
                .put("project_id", project.projectId)
        }

        val stores =
            try {
                projectDataScope.forProject(project.projectId)
            } catch (_: Exception) {
                null
            }
                ?: return failure(
                    "Не удалось разрешить изолированный project workspace."
                )
                    .put("status", "project_workspace_scope_unavailable")
                    .put("project_id", project.projectId)

        val root =
            try {
                stores.filesRoot.canonicalFile
            } catch (_: Exception) {
                return failure(
                    "Не удалось канонизировать project workspace path."
                )
                    .put("status", "project_workspace_path_invalid")
            }

        val appFiles =
            try {
                appContext.filesDir.canonicalFile
            } catch (_: Exception) {
                return failure(
                    "Не удалось подтвердить app-private storage boundary."
                )
                    .put("status", "project_workspace_storage_boundary_unknown")
            }

        if (!isInsideRoot(appFiles, root)) {
            return failure(
                "Project workspace path вышел за app-private storage boundary."
            )
                .put("status", "project_workspace_scope_escape")
        }

        if (!root.exists() && !root.mkdirs()) {
            return failure(
                "Не удалось создать изолированный filesRoot активного проекта."
            )
                .put("status", "project_workspace_root_create_failed")
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("project_id", project.projectId)
            .put("project_name", project.name)
            .put("workspace_path", "project/${project.projectId}/files")
            // Internal only; never expose this field directly to model/user responses.
            .put("root_path", root.absolutePath)
    }

    private fun resolveWorkspacePath(
        root: File,
        rawPath: String,
        allowRoot: Boolean
    ): JSONObject {
        val normalized =
            if (allowRoot && rawPath.trim().isBlank()) {
                ""
            } else {
                validateRelativePath(rawPath)
                    ?: return failure(
                        "Некорректный project workspace path."
                    )
                        .put("status", "workspace_path_invalid")
            }

        val canonicalRoot =
            try {
                root.canonicalFile
            } catch (_: Exception) {
                return failure("Не удалось канонизировать workspace root.")
                    .put("status", "workspace_root_invalid")
            }

        val target =
            try {
                if (normalized.isBlank()) {
                    canonicalRoot
                } else {
                    File(canonicalRoot, normalized).canonicalFile
                }
            } catch (_: Exception) {
                return failure("Не удалось канонизировать workspace path.")
                    .put("status", "workspace_path_invalid")
            }

        if (!isInsideRoot(canonicalRoot, target)) {
            return failure(
                "Project workspace path traversal заблокирован."
            )
                .put("status", "workspace_path_scope_escape")
                .put("path", normalized)
        }

        return JSONObject()
            .put("success", true)
            .put("verified", true)
            .put("relative_path", normalized)
            .put("absolute_path", target.absolutePath)
    }

    private fun validateRelativePath(
        rawPath: String
    ): String? {
        val trimmed = rawPath.trim()
        if (trimmed.isBlank() || trimmed.length > MAX_PATH_CHARS) return null

        if (trimmed.startsWith("/") || trimmed.startsWith("\\")) return null

        val path =
            trimmed
                .replace('\\', '/')

        if (
            path.isBlank() ||
            path.contains("//") ||
            path.contains('\u0000') ||
            WINDOWS_DRIVE_PREFIX.containsMatchIn(path) ||
            path.split('/').any {
                it.isBlank() ||
                    it == "." ||
                    it == ".."
            }
        ) {
            return null
        }

        return path
    }

    private fun isBlockedPath(
        path: String
    ): Boolean {
        val lower =
            path.lowercase(Locale.ROOT)

        if (
            lower == ".git" ||
            lower.startsWith(".git/") ||
            lower == ".github" ||
            lower.startsWith(".github/") ||
            lower == ".gradle" ||
            lower.startsWith(".gradle/") ||
            lower == ".idea" ||
            lower.startsWith(".idea/") ||
            lower == "build" ||
            lower.startsWith("build/") ||
            lower.contains("/build/") ||
            lower == "local.properties" ||
            lower.endsWith("/local.properties") ||
            lower == ".env" ||
            lower.endsWith("/.env") ||
            lower.endsWith("/secrets.properties") ||
            lower == "secrets.properties" ||
            lower.endsWith("/google-services.json") ||
            lower == "google-services.json"
        ) {
            return true
        }

        return BLOCKED_EXTENSIONS.any {
            lower.endsWith(it)
        }
    }

    private fun containsSensitiveMaterial(
        content: String
    ): Boolean {
        if (content.isBlank()) return false

        return PRIVATE_KEY_PATTERN.containsMatchIn(content) ||
            GITHUB_PAT_PATTERN.containsMatchIn(content) ||
            OPENAI_KEY_PATTERN.containsMatchIn(content) ||
            GOOGLE_API_KEY_PATTERN.containsMatchIn(content) ||
            AWS_ACCESS_KEY_PATTERN.containsMatchIn(content)
    }

    private fun isLikelyUtf8Text(
        bytes: ByteArray
    ): Boolean {
        if (bytes.any { it == 0.toByte() }) return false

        return try {
            val text = String(bytes, StandardCharsets.UTF_8)
            val roundTrip = text.toByteArray(StandardCharsets.UTF_8)
            roundTrip.contentEquals(bytes)
        } catch (_: Exception) {
            false
        }
    }

    private fun transactionDirectory(
        root: File,
        transactionId: String
    ): File =
        File(
            workspaceMetadataRoot(root),
            transactionId
        )

    private fun transactionPlanFile(
        root: File,
        transactionId: String
    ): File =
        File(
            transactionDirectory(root, transactionId),
            "plan.json"
        )

    private fun workspaceMetadataRoot(
        root: File
    ): File {
        val parent =
            root.parentFile
                ?: root

        return File(
            parent,
            WORKSPACE_META_DIRECTORY
        )
    }

    private fun isWorkspaceMetadataPath(
        root: File,
        file: File
    ): Boolean =
        try {
            val meta = workspaceMetadataRoot(root).canonicalFile
            val target = file.canonicalFile
            isInsideRoot(meta, target)
        } catch (_: Exception) {
            false
        }

    private fun publicFileSummary(
        files: JSONArray
    ): JSONArray {
        val result = JSONArray()
        for (index in 0 until files.length()) {
            val item = files.optJSONObject(index) ?: continue
            result.put(
                JSONObject()
                    .put("path", item.optString("path"))
                    .put("action", item.optString("action"))
                    .put("baseline_exists", item.optBoolean("baseline_exists", false))
                    .put("baseline_sha256", item.optString("baseline_sha256"))
                    .put("proposed_sha256", item.optString("proposed_sha256"))
                    .put("content_bytes", item.optInt("content_bytes", 0))
            )
        }
        return result
    }

    private fun projectTransactionView(
        plan: JSONObject,
        includeFiles: Boolean
    ): JSONObject {
        val result =
            JSONObject()
                .put("status", plan.optString("status"))
                .put("transaction_id", plan.optString("transaction_id"))
                .put("project_id", plan.optString("project_id"))
                .put("project_name", plan.optString("project_name"))
                .put("workspace_path", plan.optString("workspace_path"))
                .put("manifest_sha256", plan.optString("manifest_sha256"))
                .put("file_count", plan.optJSONArray("files")?.length() ?: 0)
                .put("total_bytes", plan.optLong("total_bytes", 0L))
                .put("created_at_ms", plan.optLong("created_at_ms", 0L))
                .put("updated_at_ms", plan.optLong("updated_at_ms", 0L))

        if (includeFiles) {
            result.put(
                "files",
                publicFileSummary(
                    plan.optJSONArray("files") ?: JSONArray()
                )
            )
        }

        return result
    }

    private fun manifestSha256(
        plan: JSONObject
    ): String {
        val files = plan.optJSONArray("files") ?: JSONArray()
        val canonical = StringBuilder()
            .append(plan.optString("schema"))
            .append('|')
            .append(plan.optString("version"))
            .append('|')
            .append(plan.optString("transaction_id"))
            .append('|')
            .append(plan.optString("project_id"))
            .append('|')
            .append(plan.optString("note"))
            .append('|')
            .append(plan.optLong("total_bytes", 0L))

        for (index in 0 until files.length()) {
            val item = files.optJSONObject(index) ?: continue
            canonical
                .append('\n')
                .append(item.optString("path"))
                .append('|')
                .append(item.optBoolean("baseline_exists", false))
                .append('|')
                .append(item.optString("baseline_sha256"))
                .append('|')
                .append(item.optString("proposed_sha256"))
                .append('|')
                .append(item.optInt("content_bytes", 0))
                .append('|')
                .append(item.optString("action"))
        }

        return sha256(
            canonical
                .toString()
                .toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun writeJsonFileAtomic(
        file: File,
        json: JSONObject
    ): Boolean {
        return try {
            val parent = file.parentFile ?: return false
            if (!parent.isDirectory && !parent.mkdirs()) return false

            val temp = File(parent, ".${file.name}.tmp")
            val bytes =
                json
                    .toString()
                    .toByteArray(StandardCharsets.UTF_8)

            if (!writeBytesSynced(temp, bytes)) return false

            if (file.exists() && !file.delete()) {
                temp.delete()
                return false
            }

            if (!temp.renameTo(file)) {
                val copied = writeBytesSynced(file, temp.readBytes())
                temp.delete()
                if (!copied) return false
            }

            readJsonFile(file) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun readJsonFile(
        file: File
    ): JSONObject? =
        try {
            if (!file.exists() || !file.isFile || file.length() > MAX_PLAN_BYTES) {
                null
            } else {
                JSONObject(
                    file.readText(StandardCharsets.UTF_8)
                )
            }
        } catch (_: Exception) {
            null
        }

    private fun writeBytesSynced(
        file: File,
        bytes: ByteArray
    ): Boolean =
        try {
            val parent = file.parentFile
            if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                false
            } else {
                FileOutputStream(file, false).use {
                    it.write(bytes)
                    it.flush()
                    try {
                        it.fd.sync()
                    } catch (_: Exception) {
                    }
                }
                file.exists() &&
                    file.isFile &&
                    file.length() == bytes.size.toLong()
            }
        } catch (_: Exception) {
            false
        }

    private fun cleanupTransactionPayload(
        root: File,
        txId: String,
        keepPlan: Boolean
    ) {
        try {
            val dir = transactionDirectory(root, txId)
            dir.listFiles()?.forEach { child ->
                if (keepPlan && child.name == "plan.json") {
                    return@forEach
                }
                deleteRecursivelySafe(child)
            }
        } catch (_: Exception) {
        }
    }

    private fun deleteRecursivelySafe(
        file: File
    ): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach {
                if (!deleteRecursivelySafe(it)) return false
            }
        }
        return !file.exists() || file.delete()
    }

    private fun countWorkspaceFiles(
        root: File,
        limit: Int
    ): Pair<Int, Boolean> {
        var count = 0
        var truncated = false

        fun walk(directory: File) {
            if (truncated) return
            val children = directory.listFiles() ?: return
            for (child in children) {
                if (isWorkspaceMetadataPath(root, child)) continue
                if (child.isFile) {
                    count++
                    if (count >= limit) {
                        truncated = true
                        return
                    }
                } else if (child.isDirectory) {
                    walk(child)
                }
            }
        }

        if (root.exists() && root.isDirectory) {
            walk(root)
        }

        return count to truncated
    }

    private fun relativePath(
        root: File,
        file: File
    ): String? =
        try {
            val rootPath = root.canonicalFile.toPath()
            val filePath = file.canonicalFile.toPath()
            if (!filePath.startsWith(rootPath)) {
                null
            } else {
                rootPath
                    .relativize(filePath)
                    .toString()
                    .replace('\\', '/')
            }
        } catch (_: Exception) {
            null
        }

    private fun isInsideRoot(
        root: File,
        target: File
    ): Boolean {
        val rootPath = root.canonicalPath
        val targetPath = target.canonicalPath

        return targetPath == rootPath ||
            targetPath.startsWith(
                rootPath + File.separator
            )
    }

    private fun sha256File(
        file: File
    ): String =
        sha256(
            file.readBytes()
        )

    private fun sha256(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") {
                "%02x".format(it)
            }

    private fun failure(
        message: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", true)
            .put("terminal_status", "ERROR")
            .put("message", message)

    companion object {
        const val VERSION = "1.0.1"
        const val PLAN_SCHEMA = "ayana_project_workspace_tx_v1"

        const val MAX_FILES_PER_TRANSACTION = 32
        const val MAX_FILE_BYTES = 96 * 1024
        const val MAX_TRANSACTION_BYTES = 512 * 1024
        const val MAX_READ_BYTES = 128 * 1024
        const val DEFAULT_READ_BYTES = 64 * 1024
        const val MAX_PATH_CHARS = 320
        const val MAX_NOTE_CHARS = 240
        const val MAX_PLAN_BYTES = 1024 * 1024
        const val MAX_HASH_READ_BYTES = 1024 * 1024
        const val DEFAULT_LIST_LIMIT = 120
        const val MAX_LIST_LIMIT = 500
        const val STATUS_FILE_COUNT_SCAN_LIMIT = 5000

        const val WORKSPACE_META_DIRECTORY = "ayana_project_workspace_meta"

        const val TX_PREPARED = "project_workspace_transaction_prepared"
        const val TX_COMMITTED = "project_workspace_transaction_committed"
        const val TX_ACCEPTED = "project_workspace_transaction_accepted"
        const val TX_CANCELLED = "project_workspace_transaction_cancelled"
        const val TX_ROLLED_BACK = "project_workspace_transaction_rolled_back"
        const val TX_FAILED_ROLLED_BACK = "project_workspace_transaction_failed_rolled_back"
        const val TX_ROLLBACK_INCOMPLETE = "project_workspace_transaction_rollback_incomplete"

        private val VALID_TRANSACTION_ID =
            Regex("^pws-[a-z0-9]+-[a-f0-9]{12}$")

        private val SHA256_HEX =
            Regex("^[a-f0-9]{64}$")

        private val WINDOWS_DRIVE_PREFIX =
            Regex("^[A-Za-z]:")

        private val BLOCKED_EXTENSIONS =
            setOf(
                ".jks",
                ".keystore",
                ".p12",
                ".pfx",
                ".pem",
                ".key",
                ".der",
                ".cer",
                ".crt",
                ".apk",
                ".aab",
                ".so",
                ".dex",
                ".jar",
                ".zip"
            )

        private val PRIVATE_KEY_PATTERN =
            Regex(
                "-----BEGIN(?: [A-Z0-9]+)? PRIVATE KEY-----",
                RegexOption.IGNORE_CASE
            )

        private val GITHUB_PAT_PATTERN =
            Regex(
                "(?:github_pat_[A-Za-z0-9_]{20,}|gh[pousr]_[A-Za-z0-9]{20,})"
            )

        private val OPENAI_KEY_PATTERN =
            Regex(
                "(?:^|[^A-Za-z0-9])sk-[A-Za-z0-9_-]{20,}(?:$|[^A-Za-z0-9_-])"
            )

        private val GOOGLE_API_KEY_PATTERN =
            Regex("AIza[0-9A-Za-z_-]{30,}")

        private val AWS_ACCESS_KEY_PATTERN =
            Regex("(?:AKIA|ASIA)[A-Z0-9]{16}")
    }
}
