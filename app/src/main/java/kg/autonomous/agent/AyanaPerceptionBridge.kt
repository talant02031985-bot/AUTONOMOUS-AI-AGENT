package kg.autonomous.agent

import android.app.ActivityManager
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import org.json.JSONObject
import java.util.UUID

/**
 * AYANA Perception Bridge v1.2 — R10.15 GENERALIZED CROSS-PROCESS ROUTE CONTRACT.
 *
 * R10.15 makes the bridge a fail-closed process boundary, not an opportunistic fallback.
 * Every main-process perception/action call must reach a distinct :perception PID using the
 * exact bridge version. Same-process provider routing, version mismatch, or a bridge call
 * originating from :perception is rejected. No action authority is added.
 *
 * Design goals:
 * - AgentAccessibilityService lives in :perception and remains alive when the main AYANA
 *   production process is killed for durable recovery.
 * - Main-process Screen Intelligence calls the same verified perception/action contract
 *   through same-UID ContentProvider IPC.
 * - AYANA's own MainActivity semantic View bridge remains in the main process and is exposed
 *   back to :perception through a second non-exported same-UID provider.
 * - Irreversible Recents dispatch preserves the existing ExecutionKernel atomic gate by
 *   passing a Binder callback; authorization still happens synchronously immediately before
 *   the destructive Android dispatch.
 * - No new action authority is introduced. IPC only transports existing verified operations.
 */
object AyanaPerceptionBridgeContract {
    const val VERSION = "1.2"

    const val PERCEPTION_AUTHORITY = "kg.autonomous.agent.perception.bridge"
    const val OWN_APP_AUTHORITY = "kg.autonomous.agent.ownapp.bridge"

    val PERCEPTION_URI: Uri = Uri.parse("content://$PERCEPTION_AUTHORITY")
    val OWN_APP_URI: Uri = Uri.parse("content://$OWN_APP_AUTHORITY")

    const val METHOD_STATUS = "status"
    const val METHOD_SCREEN_STATE = "screen_state"
    const val METHOD_CAPTURE_VERIFIED_EXTERNAL_WINDOW = "capture_verified_external_window"
    const val METHOD_CLICK = "click"
    const val METHOD_INPUT_TEXT = "input_text"
    const val METHOD_SCROLL = "scroll"
    const val METHOD_TAP = "tap"
    const val METHOD_PRESS_BACK = "press_back"
    const val METHOD_PRESS_HOME = "press_home"
    const val METHOD_RECORD_VISUAL = "record_visual"
    const val METHOD_CLEAR_VISUAL = "clear_visual"
    const val METHOD_ATTEST_FOREGROUND_OWNER = "attest_foreground_owner"
    const val METHOD_REMOVE_RECENT_TASK = "remove_recent_task"

    const val METHOD_OWN_APP_STATUS = "own_app_status"
    const val METHOD_OWN_APP_SNAPSHOT = "own_app_snapshot"
    const val METHOD_OWN_APP_CLICK = "own_app_click"
    const val METHOD_OWN_APP_SET_TEXT = "own_app_set_text"

    const val KEY_JSON = "json"
    const val KEY_CALLBACK_BINDER = "callback_binder"

    const val CALLBACK_DESCRIPTOR = "kg.autonomous.agent.AyanaIrreversibleDispatchCallback"
    const val CALLBACK_SHOULD_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1
    const val CALLBACK_TRY_BEGIN = IBinder.FIRST_CALL_TRANSACTION + 2
    const val CALLBACK_ACCEPTED = IBinder.FIRST_CALL_TRANSACTION + 3
    const val CALLBACK_RECONCILIATION_STARTED = IBinder.FIRST_CALL_TRANSACTION + 4
    const val CALLBACK_RECONCILED = IBinder.FIRST_CALL_TRANSACTION + 5
}

class AyanaPerceptionBridgeProvider : ContentProvider() {

    private val perceptionProcessEpochId: String = UUID.randomUUID().toString()

    private val screenIntelligence by lazy {
        AyanaScreenIntelligence(
            requireNotNull(context).applicationContext
        )
    }

    private val visualScreenEvidence by lazy {
        AyanaVisualScreenEvidence(
            requireNotNull(context).applicationContext
        )
    }

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        return try {
            val args =
                extras
                    ?.getString(AyanaPerceptionBridgeContract.KEY_JSON)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { JSONObject(it) }
                    ?: JSONObject()

            val result =
                when (method) {
                    AyanaPerceptionBridgeContract.METHOD_STATUS ->
                        bridgeStatus()

                    AyanaPerceptionBridgeContract.METHOD_SCREEN_STATE ->
                        screenIntelligence.getScreenState()

                    AyanaPerceptionBridgeContract.METHOD_CAPTURE_VERIFIED_EXTERNAL_WINDOW ->
                        visualScreenEvidence.captureVerifiedExternalWindow(
                            expectedPackage = args.optString("expected_package"),
                            timeoutMs = args.optLong("timeout_ms", 2_500L)
                        )
                            .put("cross_process_visual_capture", true)
                            .put("visual_capture_process_id", Process.myPid())
                            .put("visual_capture_process_epoch_id", perceptionProcessEpochId)

                    AyanaPerceptionBridgeContract.METHOD_CLICK ->
                        screenIntelligence.click(
                            target = args.optString("target"),
                            confirmed = args.optBoolean("confirmed", false)
                        )

                    AyanaPerceptionBridgeContract.METHOD_INPUT_TEXT ->
                        screenIntelligence.inputText(
                            target =
                                args.optString("target")
                                    .takeIf { it.isNotBlank() },
                            text = args.optString("text")
                        )

                    AyanaPerceptionBridgeContract.METHOD_SCROLL ->
                        screenIntelligence.scroll(
                            direction = args.optString("direction")
                        )

                    AyanaPerceptionBridgeContract.METHOD_TAP ->
                        screenIntelligence.tap(
                            x = args.optInt("x"),
                            y = args.optInt("y"),
                            confirmed = args.optBoolean("confirmed", false)
                        )

                    AyanaPerceptionBridgeContract.METHOD_PRESS_BACK ->
                        screenIntelligence.pressBack()

                    AyanaPerceptionBridgeContract.METHOD_PRESS_HOME ->
                        screenIntelligence.pressHome()

                    AyanaPerceptionBridgeContract.METHOD_RECORD_VISUAL -> {
                        val observation =
                            args.optJSONObject("observation")
                                ?: JSONObject()
                        val accepted =
                            screenIntelligence.recordVerifiedVisualObservation(
                                observation
                            )
                        JSONObject()
                            .put("success", accepted)
                            .put("verified", accepted)
                            .put("bridge_version", AyanaPerceptionBridgeContract.VERSION)
                    }

                    AyanaPerceptionBridgeContract.METHOD_CLEAR_VISUAL -> {
                        screenIntelligence.clearVerifiedVisualObservation()
                        JSONObject()
                            .put("success", true)
                            .put("verified", true)
                            .put("bridge_version", AyanaPerceptionBridgeContract.VERSION)
                    }

                    AyanaPerceptionBridgeContract.METHOD_ATTEST_FOREGROUND_OWNER -> {
                        val accepted =
                            AgentAccessibilityService.attestVerifiedForegroundOwner(
                                ownerPackage = args.optString("owner_package"),
                                windowId = args.optInt("window_id", -1),
                                source = args.optString("source", "cross_process_verified_external_proof")
                            )
                        JSONObject()
                            .put("success", accepted)
                            .put("verified", accepted)
                            .put("bridge_version", AyanaPerceptionBridgeContract.VERSION)
                    }

                    AyanaPerceptionBridgeContract.METHOD_REMOVE_RECENT_TASK ->
                        removeRecentTask(
                            args = args,
                            callbackBinder =
                                extras?.getBinder(
                                    AyanaPerceptionBridgeContract.KEY_CALLBACK_BINDER
                                )
                        )

                    else ->
                        errorResult(
                            reason = "unknown_bridge_method",
                            message = "Unknown perception bridge method: $method"
                        )
                }

            Bundle().apply {
                putString(
                    AyanaPerceptionBridgeContract.KEY_JSON,
                    JSONObject(result.toString())
                        .put("perception_bridge_version", AyanaPerceptionBridgeContract.VERSION)
                        .put("perception_process_id", Process.myPid())
                        .put("perception_process_name", currentProcessName())
                        .put("perception_process_epoch_id", perceptionProcessEpochId)
                        .put("main_process_direct_accessibility_allowed", false)
                        .put("perception_local_accessibility_allowed", true)
                        .toString()
                )
            }
        } catch (error: Throwable) {
            Bundle().apply {
                putString(
                    AyanaPerceptionBridgeContract.KEY_JSON,
                    errorResult(
                        reason = "bridge_provider_exception",
                        message = error.message ?: error.javaClass.simpleName
                    )
                        .put("perception_bridge_version", AyanaPerceptionBridgeContract.VERSION)
                        .put("perception_process_id", Process.myPid())
                        .put("perception_process_name", currentProcessName())
                        .put("perception_process_epoch_id", perceptionProcessEpochId)
                        .put("main_process_direct_accessibility_allowed", false)
                        .put("perception_local_accessibility_allowed", true)
                        .toString()
                )
            }
        }
    }

    private fun bridgeStatus(): JSONObject {
        val accessibilityConnected =
            AgentAccessibilityService.instance != null

        val screen =
            if (accessibilityConnected) {
                try {
                    screenIntelligence.getScreenState()
                } catch (_: Throwable) {
                    JSONObject()
                }
            } else {
                JSONObject()
            }

        val effectivePackage =
            screen.optString("effective_foreground_package").trim()
                .ifBlank { screen.optString("interaction_package").trim() }
                .ifBlank { screen.optString("package").trim() }

        return JSONObject()
            .put("success", true)
            .put("verified", accessibilityConnected)
            .put("accessibility_connected", accessibilityConnected)
            .put("screen_evidence_available", screen.optBoolean("success", false))
            .put("effective_foreground_package", effectivePackage)
            .put("content_status", screen.optString("content_status"))
            .put("perception_process_id", Process.myPid())
            .put("perception_process_name", currentProcessName())
            .put("perception_process_epoch_id", perceptionProcessEpochId)
            .put("perception_bridge_version", AyanaPerceptionBridgeContract.VERSION)
            .put("route_contract", "cross_process_bridge_only_outside_perception")
            .put("main_process_direct_accessibility_allowed", false)
            .put("perception_local_accessibility_allowed", true)
            .put("provider_is_perception_process", currentProcessName().endsWith(":perception"))
            .put(
                "route_contract_verified",
                currentProcessName().endsWith(":perception") && accessibilityConnected
            )
    }

    private fun currentProcessName(): String =
        AyanaPerceptionBridgeClient.currentProcessName(
            requireNotNull(context).applicationContext
        )

    private fun removeRecentTask(
        args: JSONObject,
        callbackBinder: IBinder?
    ): JSONObject {
        val accessibility =
            AgentAccessibilityService.instance
                ?: return errorResult(
                    reason = "accessibility_unavailable",
                    message = "Служба специальных возможностей AYANA недоступна"
                )
                    .put("terminal_status", "UNSUPPORTED")

        val callback =
            callbackBinder
                ?.let { AyanaRemoteIrreversibleDispatchCallback(it) }
                ?: return errorResult(
                    reason = "irreversible_dispatch_callback_missing",
                    message = "Cross-process destructive-dispatch kernel callback is unavailable"
                )
                    .put("terminal_status", "BLOCKED")

        return accessibility.removeRecentTaskByLabel(
            targetLabel = args.optString("target_label"),
            sourcePackage = args.optString("source_package"),
            shouldCancel = { callback.shouldCancel() },
            tryBeginIrreversibleDispatch = { detail ->
                callback.tryBegin(detail)
            },
            onIrreversibleDispatchAccepted = { detail ->
                callback.accepted(detail)
            },
            onReconciliationStarted = { detail ->
                callback.reconciliationStarted(detail)
            },
            onReconciled = { committed, detail ->
                callback.reconciled(
                    committed = committed,
                    detail = detail
                )
            }
        )
            .put("cross_process_perception", true)
            .put("perception_bridge_version", AyanaPerceptionBridgeContract.VERSION)
    }

    private fun errorResult(
        reason: String,
        message: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("reason", reason)
            .put("message", message)

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

class AyanaOwnAppBridgeProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(
        method: String,
        arg: String?,
        extras: Bundle?
    ): Bundle {
        val args =
            extras
                ?.getString(AyanaPerceptionBridgeContract.KEY_JSON)
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    try {
                        JSONObject(it)
                    } catch (_: Throwable) {
                        JSONObject()
                    }
                }
                ?: JSONObject()

        val result =
            try {
                when (method) {
                    AyanaPerceptionBridgeContract.METHOD_OWN_APP_STATUS ->
                        JSONObject()
                            .put("success", true)
                            .put(
                                "active",
                                MainActivity.isOwnAppSemanticBridgeActive()
                            )

                    AyanaPerceptionBridgeContract.METHOD_OWN_APP_SNAPSHOT ->
                        MainActivity.buildOwnAppSemanticSnapshot(
                            maxNodes = args.optInt("max_nodes", 140).coerceIn(1, 300),
                            maxChars = args.optInt("max_chars", 14000).coerceIn(1000, 30000)
                        )
                            ?: JSONObject()
                                .put("success", false)
                                .put("snapshot_success", false)
                                .put("reason", "own_app_snapshot_unavailable")

                    AyanaPerceptionBridgeContract.METHOD_OWN_APP_CLICK -> {
                        val accepted =
                            MainActivity.performOwnAppSemanticClick(
                                args.optString("target")
                            )
                        JSONObject()
                            .put("success", accepted)
                            .put("accepted", accepted)
                    }

                    AyanaPerceptionBridgeContract.METHOD_OWN_APP_SET_TEXT -> {
                        val target =
                            args.optString("target")
                                .takeIf { it.isNotBlank() }
                        val accepted =
                            MainActivity.performOwnAppSemanticSetText(
                                target = target,
                                text = args.optString("text")
                            )
                        JSONObject()
                            .put("success", accepted)
                            .put("accepted", accepted)
                    }

                    else ->
                        JSONObject()
                            .put("success", false)
                            .put("reason", "unknown_own_app_bridge_method")
                }
            } catch (error: Throwable) {
                JSONObject()
                    .put("success", false)
                    .put("reason", "own_app_bridge_exception")
                    .put("error", error.message ?: error.javaClass.simpleName)
            }

        return Bundle().apply {
            putString(
                AyanaPerceptionBridgeContract.KEY_JSON,
                result.toString()
            )
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

class AyanaPerceptionBridgeClient(
    context: Context
) {
    private val appContext = context.applicationContext

    fun isLocalPerceptionProcess(): Boolean =
        currentProcessName(appContext).endsWith(":perception")

    fun status(): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_STATUS,
            JSONObject()
        )

    fun getScreenState(): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_SCREEN_STATE,
            JSONObject()
        )

    fun captureVerifiedExternalWindow(
        expectedPackage: String,
        timeoutMs: Long
    ): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_CAPTURE_VERIFIED_EXTERNAL_WINDOW,
            JSONObject()
                .put("expected_package", expectedPackage)
                .put("timeout_ms", timeoutMs)
        )

    fun click(
        target: String,
        confirmed: Boolean
    ): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_CLICK,
            JSONObject()
                .put("target", target)
                .put("confirmed", confirmed)
        )

    fun inputText(
        target: String?,
        text: String
    ): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_INPUT_TEXT,
            JSONObject()
                .put("target", target.orEmpty())
                .put("text", text)
        )

    fun scroll(direction: String): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_SCROLL,
            JSONObject().put("direction", direction)
        )

    fun tap(
        x: Int,
        y: Int,
        confirmed: Boolean
    ): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_TAP,
            JSONObject()
                .put("x", x)
                .put("y", y)
                .put("confirmed", confirmed)
        )

    fun pressBack(): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_PRESS_BACK,
            JSONObject()
        )

    fun pressHome(): JSONObject =
        call(
            AyanaPerceptionBridgeContract.METHOD_PRESS_HOME,
            JSONObject()
        )

    fun recordVerifiedVisualObservation(
        observation: JSONObject
    ): Boolean =
        call(
            AyanaPerceptionBridgeContract.METHOD_RECORD_VISUAL,
            JSONObject().put(
                "observation",
                JSONObject(observation.toString())
            )
        ).optBoolean("success", false)

    fun clearVerifiedVisualObservation(): Boolean =
        call(
            AyanaPerceptionBridgeContract.METHOD_CLEAR_VISUAL,
            JSONObject()
        ).optBoolean("success", false)

    fun attestVerifiedForegroundOwner(
        ownerPackage: String,
        windowId: Int = -1,
        source: String = "cross_process_verified_external_proof"
    ): Boolean =
        call(
            AyanaPerceptionBridgeContract.METHOD_ATTEST_FOREGROUND_OWNER,
            JSONObject()
                .put("owner_package", ownerPackage)
                .put("window_id", windowId)
                .put("source", source)
        ).optBoolean("success", false)

    fun removeRecentTaskByLabel(
        targetLabel: String,
        sourcePackage: String,
        shouldCancel: () -> Boolean,
        tryBeginIrreversibleDispatch: (String) -> Boolean,
        onIrreversibleDispatchAccepted: (String) -> Unit,
        onReconciliationStarted: (String) -> Unit,
        onReconciled: (Boolean, String) -> Unit
    ): JSONObject {
        val callbackBinder =
            AyanaIrreversibleDispatchCallbackBinder(
                shouldCancel = shouldCancel,
                tryBegin = tryBeginIrreversibleDispatch,
                accepted = onIrreversibleDispatchAccepted,
                reconciliationStarted = onReconciliationStarted,
                reconciled = onReconciled
            )

        return call(
            method = AyanaPerceptionBridgeContract.METHOD_REMOVE_RECENT_TASK,
            args =
                JSONObject()
                    .put("target_label", targetLabel)
                    .put("source_package", sourcePackage),
            callbackBinder = callbackBinder
        )
    }

    private fun call(
        method: String,
        args: JSONObject,
        callbackBinder: IBinder? = null
    ): JSONObject {
        if (isLocalPerceptionProcess()) {
            return unavailable(
                reason = "bridge_call_from_perception_process_blocked"
            )
                .put("perception_route", "local_perception_required")
                .put("perception_route_verified", false)
        }

        return try {
            val extras =
                Bundle().apply {
                    putString(
                        AyanaPerceptionBridgeContract.KEY_JSON,
                        args.toString()
                    )
                    if (callbackBinder != null) {
                        putBinder(
                            AyanaPerceptionBridgeContract.KEY_CALLBACK_BINDER,
                            callbackBinder
                        )
                    }
                }

            val result =
                appContext.contentResolver.call(
                    AyanaPerceptionBridgeContract.PERCEPTION_URI,
                    method,
                    null,
                    extras
                )

            val json =
                result
                    ?.getString(AyanaPerceptionBridgeContract.KEY_JSON)
                    .orEmpty()

            if (json.isBlank()) {
                unavailable("empty_perception_bridge_response")
            } else {
                val parsed = JSONObject(json)
                val providerVersion =
                    parsed.optString("perception_bridge_version").trim()
                val providerPid =
                    parsed.optInt("perception_process_id", -1)
                val providerProcessName =
                    parsed.optString("perception_process_name").trim()
                val clientPid = Process.myPid()

                if (providerVersion != AyanaPerceptionBridgeContract.VERSION) {
                    unavailable(
                        reason = "perception_bridge_version_mismatch",
                        error = "provider=$providerVersion expected=${AyanaPerceptionBridgeContract.VERSION}"
                    )
                        .put("provider_process_id", providerPid)
                        .put("provider_process_name", providerProcessName)
                } else if (providerPid <= 0 || providerPid == clientPid) {
                    unavailable(
                        reason = "perception_bridge_not_cross_process",
                        error = "client_pid=$clientPid provider_pid=$providerPid"
                    )
                        .put("provider_process_id", providerPid)
                        .put("provider_process_name", providerProcessName)
                } else if (!providerProcessName.endsWith(":perception")) {
                    unavailable(
                        reason = "perception_provider_process_name_invalid",
                        error = providerProcessName.take(240)
                    )
                        .put("provider_process_id", providerPid)
                        .put("provider_process_name", providerProcessName)
                } else {
                    parsed
                        .put("cross_process_perception", true)
                        .put("perception_route", "cross_process_bridge")
                        .put("perception_route_verified", true)
                        .put("client_process_id", clientPid)
                        .put("provider_process_separated", true)
                        .put("bridge_version_match", true)
                }
            }
        } catch (error: Throwable) {
            unavailable(
                "perception_bridge_call_failed",
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    private fun unavailable(
        reason: String,
        error: String = ""
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("terminal_status", "UNSUPPORTED")
            .put("reason", reason)
            .put("error", error.take(300))
            .put("perception_bridge_version", AyanaPerceptionBridgeContract.VERSION)
            .put("cross_process_perception", true)
            .put("perception_route", "cross_process_bridge")
            .put("perception_route_verified", false)
            .put("client_process_id", Process.myPid())
            .put("provider_process_separated", false)
            .put("bridge_version_match", false)

    companion object {
        fun currentProcessName(context: Context): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return try {
                    Application.getProcessName().orEmpty()
                } catch (_: Throwable) {
                    ""
                }
            }

            val pid = Process.myPid()
            return try {
                val manager =
                    context.applicationContext
                        .getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                manager.runningAppProcesses
                    ?.firstOrNull { it.pid == pid }
                    ?.processName
                    .orEmpty()
            } catch (_: Throwable) {
                ""
            }
        }
    }
}

class AyanaOwnAppBridgeClient(
    context: Context
) {
    private val appContext = context.applicationContext

    fun isActive(): Boolean =
        call(
            AyanaPerceptionBridgeContract.METHOD_OWN_APP_STATUS,
            JSONObject()
        ).optBoolean("active", false)

    fun buildSnapshot(
        maxNodes: Int,
        maxChars: Int
    ): JSONObject? {
        val result =
            call(
                AyanaPerceptionBridgeContract.METHOD_OWN_APP_SNAPSHOT,
                JSONObject()
                    .put("max_nodes", maxNodes)
                    .put("max_chars", maxChars)
            )
        return result.takeIf {
            it.optBoolean("snapshot_success", it.optBoolean("success", false))
        }
    }

    fun performClick(target: String): Boolean =
        call(
            AyanaPerceptionBridgeContract.METHOD_OWN_APP_CLICK,
            JSONObject().put("target", target)
        ).optBoolean("accepted", false)

    fun performSetText(
        target: String?,
        text: String
    ): Boolean =
        call(
            AyanaPerceptionBridgeContract.METHOD_OWN_APP_SET_TEXT,
            JSONObject()
                .put("target", target.orEmpty())
                .put("text", text)
        ).optBoolean("accepted", false)

    private fun call(
        method: String,
        args: JSONObject
    ): JSONObject {
        return try {
            val extras =
                Bundle().apply {
                    putString(
                        AyanaPerceptionBridgeContract.KEY_JSON,
                        args.toString()
                    )
                }
            val result =
                appContext.contentResolver.call(
                    AyanaPerceptionBridgeContract.OWN_APP_URI,
                    method,
                    null,
                    extras
                )
            val json =
                result
                    ?.getString(AyanaPerceptionBridgeContract.KEY_JSON)
                    .orEmpty()
            if (json.isBlank()) {
                JSONObject()
                    .put("success", false)
                    .put("reason", "empty_own_app_bridge_response")
            } else {
                JSONObject(json)
            }
        } catch (error: Throwable) {
            JSONObject()
                .put("success", false)
                .put("reason", "own_app_bridge_call_failed")
                .put("error", (error.message ?: error.javaClass.simpleName).take(300))
        }
    }
}

private class AyanaIrreversibleDispatchCallbackBinder(
    private val shouldCancel: () -> Boolean,
    private val tryBegin: (String) -> Boolean,
    private val accepted: (String) -> Unit,
    private val reconciliationStarted: (String) -> Unit,
    private val reconciled: (Boolean, String) -> Unit
) : Binder() {

    override fun onTransact(
        code: Int,
        data: Parcel,
        reply: Parcel?,
        flags: Int
    ): Boolean {
        data.enforceInterface(
            AyanaPerceptionBridgeContract.CALLBACK_DESCRIPTOR
        )

        return when (code) {
            AyanaPerceptionBridgeContract.CALLBACK_SHOULD_CANCEL -> {
                val value =
                    try {
                        shouldCancel()
                    } catch (_: Throwable) {
                        true
                    }
                reply?.writeNoException()
                reply?.writeInt(if (value) 1 else 0)
                true
            }

            AyanaPerceptionBridgeContract.CALLBACK_TRY_BEGIN -> {
                val detail = data.readString().orEmpty()
                val allowed =
                    try {
                        tryBegin(detail)
                    } catch (_: Throwable) {
                        false
                    }
                reply?.writeNoException()
                reply?.writeInt(if (allowed) 1 else 0)
                true
            }

            AyanaPerceptionBridgeContract.CALLBACK_ACCEPTED -> {
                val detail = data.readString().orEmpty()
                try {
                    accepted(detail)
                } catch (_: Throwable) {
                }
                reply?.writeNoException()
                true
            }

            AyanaPerceptionBridgeContract.CALLBACK_RECONCILIATION_STARTED -> {
                val detail = data.readString().orEmpty()
                try {
                    reconciliationStarted(detail)
                } catch (_: Throwable) {
                }
                reply?.writeNoException()
                true
            }

            AyanaPerceptionBridgeContract.CALLBACK_RECONCILED -> {
                val committed = data.readInt() != 0
                val detail = data.readString().orEmpty()
                try {
                    reconciled(committed, detail)
                } catch (_: Throwable) {
                }
                reply?.writeNoException()
                true
            }

            else -> super.onTransact(code, data, reply, flags)
        }
    }
}

private class AyanaRemoteIrreversibleDispatchCallback(
    private val binder: IBinder
) {
    fun shouldCancel(): Boolean =
        transactBoolean(
            AyanaPerceptionBridgeContract.CALLBACK_SHOULD_CANCEL,
            defaultValue = true
        )

    fun tryBegin(detail: String): Boolean =
        transactBoolean(
            AyanaPerceptionBridgeContract.CALLBACK_TRY_BEGIN,
            detail = detail,
            defaultValue = false
        )

    fun accepted(detail: String) {
        transactVoid(
            AyanaPerceptionBridgeContract.CALLBACK_ACCEPTED,
            detail = detail
        )
    }

    fun reconciliationStarted(detail: String) {
        transactVoid(
            AyanaPerceptionBridgeContract.CALLBACK_RECONCILIATION_STARTED,
            detail = detail
        )
    }

    fun reconciled(
        committed: Boolean,
        detail: String
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(
                AyanaPerceptionBridgeContract.CALLBACK_DESCRIPTOR
            )
            data.writeInt(if (committed) 1 else 0)
            data.writeString(detail)
            binder.transact(
                AyanaPerceptionBridgeContract.CALLBACK_RECONCILED,
                data,
                reply,
                0
            )
            reply.readException()
        } catch (_: Throwable) {
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactBoolean(
        code: Int,
        detail: String? = null,
        defaultValue: Boolean
    ): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(
                AyanaPerceptionBridgeContract.CALLBACK_DESCRIPTOR
            )
            if (detail != null) {
                data.writeString(detail)
            }
            val accepted =
                binder.transact(
                    code,
                    data,
                    reply,
                    0
                )
            if (!accepted) {
                defaultValue
            } else {
                reply.readException()
                reply.readInt() != 0
            }
        } catch (_: Throwable) {
            defaultValue
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    private fun transactVoid(
        code: Int,
        detail: String
    ) {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(
                AyanaPerceptionBridgeContract.CALLBACK_DESCRIPTOR
            )
            data.writeString(detail)
            binder.transact(
                code,
                data,
                reply,
                0
            )
            reply.readException()
        } catch (_: Throwable) {
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
