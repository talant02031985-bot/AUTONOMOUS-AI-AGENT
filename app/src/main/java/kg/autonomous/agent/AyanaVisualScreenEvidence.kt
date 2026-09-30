package kg.autonomous.agent

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * AYANA R10.14.1 Visual Screen Evidence v1.1 — CROSS-PROCESS CAPTURE.
 *
 * Read-only screenshot bridge for verified semantic fallback.
 * It does not interpret pixels and does not authorize actions.
 *
 * Provenance contract:
 * - Accessibility service must be connected;
 * - a pre-capture semantic snapshot must identify the expected foreground package;
 * - on API 34+ capture only the selected package-owned application window using
 *   takeScreenshotOfWindow(), preventing AYANA overlay content from covering it;
 * - API 30..33 uses display screenshot as a compatibility fallback;
 * - after capture, the same expected package must still own the interaction surface;
 * - image bytes are staged only under AYANA private cache/ayana_multimodal;
 * - a SHA-256 fingerprint is returned; file is ephemeral and can be deleted after analysis;
 * - secure/failed/ambiguous screenshots fail closed.
 */
class AyanaVisualScreenEvidence(
    context: android.content.Context
) {

    private val appContext =
        context.applicationContext

    private val perceptionBridge by lazy {
        AyanaPerceptionBridgeClient(appContext)
    }

    fun captureVerifiedExternalWindow(
        expectedPackage: String,
        timeoutMs: Long = DEFAULT_CAPTURE_TIMEOUT_MS
    ): JSONObject {
        val cleanPackage = expectedPackage.trim()

        if (cleanPackage.isBlank()) {
            return failure("expected_package_missing")
        }

        if (cleanPackage == appContext.packageName) {
            return failure("own_app_visual_fallback_not_required")
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return failure("screenshot_api_unavailable")
                .put("sdk_int", Build.VERSION.SDK_INT)
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            return failure("visual_capture_main_thread_blocked")
        }

        val service =
            AgentAccessibilityService.instance

        if (service == null) {
            if (perceptionBridge.isLocalPerceptionProcess()) {
                return failure("accessibility_service_unavailable")
                    .put("cross_process_visual_capture", false)
            }

            return try {
                perceptionBridge
                    .captureVerifiedExternalWindow(
                        expectedPackage = cleanPackage,
                        timeoutMs = timeoutMs
                    )
                    .put("visual_screen_evidence_version", VERSION)
                    .put("cross_process_visual_capture", true)
            } catch (error: Throwable) {
                failure("cross_process_visual_capture_failed")
                    .put(
                        "detail",
                        (error.message ?: error.javaClass.simpleName).take(240)
                    )
                    .put("cross_process_visual_capture", true)
            }
        }

        val before =
            try {
                service.buildScreenSnapshot(
                    maxNodes = 90,
                    maxChars = 8_000
                )
            } catch (error: Exception) {
                return failure("pre_capture_snapshot_failed")
                    .put(
                        "detail",
                        (error.message ?: error.javaClass.simpleName)
                            .take(240)
                    )
            }

        val beforePackage =
            effectivePackage(before)

        if (
            !before.optBoolean("success", false) ||
            beforePackage != cleanPackage
        ) {
            return failure("pre_capture_package_mismatch")
                .put("expected_package", cleanPackage)
                .put("captured_package", beforePackage)
        }

        val candidate =
            selectTargetWindow(
                service = service,
                expectedPackage = cleanPackage
            )

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            candidate == null
        ) {
            return failure("target_application_window_not_found")
                .put("expected_package", cleanPackage)
        }

        val cacheRoot =
            File(
                appContext.cacheDir,
                MULTIMODAL_CACHE_DIR
            )
                .canonicalFile

        if (
            !cacheRoot.exists() &&
            !cacheRoot.mkdirs()
        ) {
            return failure("visual_cache_create_failed")
        }

        val outputFile =
            File(
                cacheRoot,
                "ayana_screen_fallback_${UUID.randomUUID()}.jpg"
            )
                .canonicalFile

        if (!outputFile.path.startsWith(cacheRoot.path + File.separator)) {
            return failure("visual_cache_path_rejected")
        }

        val executor =
            Executors.newSingleThreadExecutor { runnable ->
                Thread(
                    runnable,
                    "AyanaVisualScreenCapture"
                ).apply {
                    isDaemon = true
                }
            }

        val latch = CountDownLatch(1)

        var callbackResult: JSONObject? = null

        val captureStartedAt =
            SystemClock.elapsedRealtime()

        val callback =
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(
                    screenshot: AccessibilityService.ScreenshotResult
                ) {
                    callbackResult =
                        writeScreenshot(
                            screenshot = screenshot,
                            outputFile = outputFile
                        )
                    latch.countDown()
                }

                override fun onFailure(
                    errorCode: Int
                ) {
                    callbackResult =
                        failure("android_screenshot_failed")
                            .put("screenshot_error_code", errorCode)
                            .put(
                                "screenshot_error",
                                screenshotErrorName(errorCode)
                            )
                    latch.countDown()
                }
            }

        val captureMode: String
        val windowId: Int

        try {
            if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
                candidate != null
            ) {
                captureMode = "window"
                windowId = candidate.windowId
                service.takeScreenshotOfWindow(
                    candidate.windowId,
                    executor,
                    callback
                )
            } else {
                captureMode = "display"
                windowId = candidate?.windowId ?: -1
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    executor,
                    callback
                )
            }
        } catch (error: Exception) {
            executor.shutdownNow()
            safeDelete(outputFile)
            return failure("android_screenshot_dispatch_failed")
                .put(
                    "detail",
                    (error.message ?: error.javaClass.simpleName)
                        .take(240)
                )
        }

        val completed =
            try {
                latch.await(
                    timeoutMs
                        .coerceIn(
                            MIN_CAPTURE_TIMEOUT_MS,
                            MAX_CAPTURE_TIMEOUT_MS
                        ),
                    TimeUnit.MILLISECONDS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }

        executor.shutdownNow()

        if (!completed) {
            safeDelete(outputFile)
            return failure("android_screenshot_timeout")
                .put("capture_mode", captureMode)
                .put("window_id", windowId)
        }

        val image =
            callbackResult
                ?: run {
                    safeDelete(outputFile)
                    return failure("android_screenshot_callback_missing")
                }

        if (!image.optBoolean("success", false)) {
            safeDelete(outputFile)
            return JSONObject(image.toString())
                .put("capture_mode", captureMode)
                .put("window_id", windowId)
        }

        val after =
            try {
                service.buildScreenSnapshot(
                    maxNodes = 70,
                    maxChars = 6_000
                )
            } catch (_: Exception) {
                JSONObject()
            }

        val afterPackage =
            effectivePackage(after)

        val packageStable =
            after.optBoolean("success", false) &&
                afterPackage == cleanPackage

        if (!packageStable) {
            safeDelete(outputFile)
            return failure("post_capture_package_mismatch")
                .put("expected_package", cleanPackage)
                .put("pre_capture_package", beforePackage)
                .put("post_capture_package", afterPackage)
                .put("capture_mode", captureMode)
                .put("window_id", windowId)
        }

        return JSONObject(image.toString())
            .put("verified", true)
            .put("expected_package", cleanPackage)
            .put("captured_package", cleanPackage)
            .put("package_match", true)
            .put("pre_capture_package", beforePackage)
            .put("post_capture_package", afterPackage)
            .put("pre_capture_content_state", contentState(before))
            .put("post_capture_content_state", contentState(after))
            .put("capture_mode", captureMode)
            .put("window_id", windowId)
            .put(
                "capture_duration_ms",
                (
                    SystemClock.elapsedRealtime() -
                        captureStartedAt
                    )
                    .coerceAtLeast(0L)
            )
            .put("source", "android_accessibility_screenshot")
            .put("visual_screen_evidence_version", VERSION)
    }

    fun deleteEvidenceFile(
        evidence: JSONObject?
    ) {
        if (evidence == null) return

        val rawPath =
            evidence
                .optString("path")
                .trim()

        if (rawPath.isBlank()) return

        try {
            val root =
                File(
                    appContext.cacheDir,
                    MULTIMODAL_CACHE_DIR
                )
                    .canonicalFile

            val file =
                File(rawPath)
                    .canonicalFile

            if (
                file.path.startsWith(root.path + File.separator) &&
                file.isFile
            ) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    fun selfTestPolicy(): Boolean {
        if (VERSION.isBlank()) return false
        if (MULTIMODAL_CACHE_DIR != "ayana_multimodal") return false
        if (MAX_LONG_SIDE_PX !in 1200..2000) return false
        if (JPEG_QUALITY !in 70..95) return false
        if (MAX_SCREENSHOT_BYTES <= 0L) return false
        return true
    }

    private data class TargetWindow(
        val windowId: Int,
        val focused: Boolean,
        val active: Boolean,
        val layer: Int
    )

    private fun selectTargetWindow(
        service: AgentAccessibilityService,
        expectedPackage: String
    ): TargetWindow? {
        val candidates =
            try {
                service.windows
                    .mapNotNull { window ->
                        val type =
                            try {
                                window.type
                            } catch (_: Exception) {
                                -1
                            }

                        if (type != AccessibilityWindowInfo.TYPE_APPLICATION) {
                            return@mapNotNull null
                        }

                        val root =
                            try {
                                window.root
                            } catch (_: Exception) {
                                null
                            }

                        val packageName =
                            root
                                ?.packageName
                                ?.toString()
                                ?.trim()
                                .orEmpty()

                        if (packageName != expectedPackage) {
                            return@mapNotNull null
                        }

                        val focused =
                            try {
                                window.isFocused
                            } catch (_: Exception) {
                                false
                            }

                        val active =
                            try {
                                window.isActive
                            } catch (_: Exception) {
                                false
                            }

                        if (!focused && !active) {
                            return@mapNotNull null
                        }

                        TargetWindow(
                            windowId = window.id,
                            focused = focused,
                            active = active,
                            layer = window.layer
                        )
                    }
            } catch (_: Exception) {
                emptyList()
            }

        return candidates
            .sortedWith(
                compareByDescending<TargetWindow> { it.focused }
                    .thenByDescending { it.active }
                    .thenByDescending { it.layer }
            )
            .firstOrNull()
    }

    private fun writeScreenshot(
        screenshot: AccessibilityService.ScreenshotResult,
        outputFile: File
    ): JSONObject {
        var software: Bitmap? = null
        var scaled: Bitmap? = null

        return try {
            val hardwareBuffer =
                screenshot.hardwareBuffer

            val hardwareBitmap =
                try {
                    Bitmap.wrapHardwareBuffer(
                        hardwareBuffer,
                        screenshot.colorSpace
                    )
                } catch (_: Exception) {
                    null
                }

            if (hardwareBitmap == null) {
                try {
                    hardwareBuffer.close()
                } catch (_: Exception) {
                }
                return failure("hardware_bitmap_unavailable")
            }

            try {
                software =
                    hardwareBitmap.copy(
                        Bitmap.Config.ARGB_8888,
                        false
                    )
            } finally {
                try {
                    hardwareBitmap.recycle()
                } catch (_: Exception) {
                }

                try {
                    hardwareBuffer.close()
                } catch (_: Exception) {
                }
            }

            val source =
                software
                    ?: return failure("software_bitmap_copy_failed")

            val sourceWidth = source.width
            val sourceHeight = source.height

            if (
                sourceWidth <= 0 ||
                sourceHeight <= 0
            ) {
                return failure("screenshot_dimensions_invalid")
            }

            val longSide =
                max(
                    sourceWidth,
                    sourceHeight
                )

            val output =
                if (longSide > MAX_LONG_SIDE_PX) {
                    val scale =
                        MAX_LONG_SIDE_PX.toFloat() /
                            longSide.toFloat()

                    val width =
                        (sourceWidth * scale)
                            .roundToInt()
                            .coerceAtLeast(1)

                    val height =
                        (sourceHeight * scale)
                            .roundToInt()
                            .coerceAtLeast(1)

                    Bitmap.createScaledBitmap(
                        source,
                        width,
                        height,
                        true
                    ).also {
                        scaled = it
                    }
                } else {
                    source
                }

            val encoded =
                FileOutputStream(outputFile).use { stream ->
                    output.compress(
                        Bitmap.CompressFormat.JPEG,
                        JPEG_QUALITY,
                        stream
                    )
                }

            if (!encoded || !outputFile.isFile) {
                safeDelete(outputFile)
                return failure("screenshot_encode_failed")
            }

            val bytes =
                outputFile.readBytes()

            if (
                bytes.isEmpty() ||
                bytes.size.toLong() > MAX_SCREENSHOT_BYTES
            ) {
                safeDelete(outputFile)
                return failure("screenshot_size_invalid")
                    .put("size_bytes", bytes.size)
            }

            JSONObject()
                .put("success", true)
                .put("verified", false)
                .put("path", outputFile.canonicalPath)
                .put("mime_type", "image/jpeg")
                .put("width", output.width)
                .put("height", output.height)
                .put("size_bytes", bytes.size)
                .put(
                    "screenshot_sha256",
                    sha256(bytes)
                )
                .put(
                    "captured_at_ms",
                    System.currentTimeMillis()
                )
        } catch (error: Exception) {
            safeDelete(outputFile)
            failure("screenshot_processing_failed")
                .put(
                    "detail",
                    (error.message ?: error.javaClass.simpleName)
                        .take(240)
                )
        } finally {
            try {
                if (
                    scaled != null &&
                    scaled !== software
                ) {
                    scaled?.recycle()
                }
            } catch (_: Exception) {
            }

            try {
                software?.recycle()
            } catch (_: Exception) {
            }
        }
    }

    private fun effectivePackage(
        screen: JSONObject
    ): String =
        screen
            .optString("effective_foreground_package")
            .trim()
            .ifBlank {
                screen
                    .optString("interaction_package")
                    .trim()
            }
            .ifBlank {
                screen
                    .optString("package")
                    .trim()
            }

    private fun contentState(
        screen: JSONObject
    ): String =
        screen
            .optString("primary_content_state")
            .trim()
            .ifBlank {
                screen
                    .optString("content_state")
                    .trim()
            }
            .ifBlank {
                screen
                    .optString("content_status")
                    .trim()
            }
            .ifBlank { "unknown" }

    private fun screenshotErrorName(
        code: Int
    ): String =
        when (code) {
            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
                "internal_error"

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
                "interval_too_short"

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
                "invalid_display"

            AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW ->
                "invalid_window"

            AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
                "no_accessibility_access"

            AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ->
                "secure_window"

            else ->
                "unknown_$code"
        }

    private fun sha256(
        bytes: ByteArray
    ): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte ->
                "%02x".format(byte)
            }

    private fun safeDelete(
        file: File
    ) {
        try {
            if (file.isFile) {
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun failure(
        reason: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("reason", reason)
            .put("visual_screen_evidence_version", VERSION)

    companion object {
        const val VERSION = "1.1"

        private const val MULTIMODAL_CACHE_DIR =
            "ayana_multimodal"

        private const val DEFAULT_CAPTURE_TIMEOUT_MS =
            2_500L

        private const val MIN_CAPTURE_TIMEOUT_MS =
            800L

        private const val MAX_CAPTURE_TIMEOUT_MS =
            5_000L

        private const val MAX_LONG_SIDE_PX =
            1600

        private const val JPEG_QUALITY =
            86

        private const val MAX_SCREENSHOT_BYTES =
            4_500_000L
    }
}
