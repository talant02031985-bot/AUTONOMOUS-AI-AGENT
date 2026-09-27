package kg.autonomous.agent

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * AYANA R9.6 Verified Semantic Observation v1.0.
 *
 * Pure fail-closed verifier for semantic evidence produced from a captured
 * external-app screenshot. This class never captures the screen, never performs
 * Android actions and never trusts model prose as evidence.
 *
 * Contract:
 * - screenshot evidence must already be success=true + verified=true;
 * - screenshot package provenance must match the expected source package;
 * - a non-empty screenshot SHA-256 fingerprint is mandatory;
 * - v1.0 supports exact SCREEN_MARKER verification only;
 * - model output must be one JSON object with observed=true, the exact requested
 *   marker as value, and confidence=high;
 * - only the expected marker is promoted into the synthetic screen observation;
 *   arbitrary model text is never copied into visible_text;
 * - no mutation/replay/auto-resume authority is granted.
 */
class AyanaVerifiedSemanticObservation {

    fun promptForExactMarker(
        marker: String
    ): String {
        val clean = marker.trim().take(MAX_MARKER_CHARS)

        return """
Ты проверяешь ТОЛЬКО фактический текст на приложенном снимке экрана Android.
Не используй URL, название сайта, внешние знания или догадки о том, что должно быть на странице.
Плавающий интерфейс/ORB AYANA, если он присутствует, не является содержимым целевого приложения и должен игнорироваться.

Нужно проверить, виден ли на снимке ТОЧНЫЙ текстовый маркер:
«$clean»

Верни только ОДИН JSON-объект без markdown и пояснений:
{"observed":true|false,"value":"точно увиденный маркер или пустая строка","confidence":"high|medium|low"}

Правила:
- observed=true только если маркер реально и отчётливо читается на изображении;
- value при observed=true должен быть ровно тем маркером, который реально виден;
- если текст закрыт, размыт, обрезан, двусмыслен или не виден — observed=false;
- не восстанавливай и не угадывай отсутствующий текст.
        """.trimIndent()
    }

    fun verifyExactMarker(
        expectedPackage: String,
        marker: String,
        screenshotEvidence: JSONObject,
        modelReply: String
    ): JSONObject {
        val cleanPackage = expectedPackage.trim()
        val cleanMarker = marker.trim().take(MAX_MARKER_CHARS)

        if (cleanPackage.isBlank()) {
            return failure("expected_package_missing")
        }

        if (cleanMarker.isBlank()) {
            return failure("expected_marker_missing")
        }

        if (
            !screenshotEvidence.optBoolean("success", false) ||
            !screenshotEvidence.optBoolean("verified", false)
        ) {
            return failure("screenshot_not_verified")
        }

        val capturedPackage =
            screenshotEvidence
                .optString("captured_package")
                .trim()
                .ifBlank {
                    screenshotEvidence
                        .optString("expected_package")
                        .trim()
                }

        if (
            capturedPackage.isBlank() ||
            capturedPackage != cleanPackage ||
            !screenshotEvidence.optBoolean("package_match", false)
        ) {
            return failure("screenshot_package_mismatch")
        }

        val screenshotSha =
            screenshotEvidence
                .optString("screenshot_sha256")
                .trim()
                .lowercase(Locale.ROOT)

        if (!HEX_SHA256.matches(screenshotSha)) {
            return failure("screenshot_fingerprint_missing")
        }

        val parsed =
            parseStrictJsonObject(modelReply)
                ?: return failure("semantic_reply_not_json")

        if (!parsed.optBoolean("observed", false)) {
            return failure("semantic_marker_not_observed")
        }

        val confidence =
            parsed
                .optString("confidence")
                .trim()
                .lowercase(Locale.ROOT)

        if (confidence != "high") {
            return failure("semantic_confidence_not_high")
        }

        val observedValue =
            parsed
                .optString("value")
                .trim()

        if (
            normalize(observedValue) !=
            normalize(cleanMarker)
        ) {
            return failure("semantic_marker_value_mismatch")
        }

        val capturedAt =
            screenshotEvidence.optLong(
                "captured_at_ms",
                0L
            )

        val windowId =
            screenshotEvidence.optInt(
                "window_id",
                -1
            )

        val captureMode =
            screenshotEvidence
                .optString("capture_mode")
                .ifBlank { "unknown" }

        return JSONObject()
            .put("success", true)
            .put("snapshot_success", true)
            .put("understanding_success", true)
            .put("verified", true)
            .put("content_contract_version", 3)
            .put("content_status", "readable")
            .put("package", cleanPackage)
            .put("interaction_package", cleanPackage)
            .put("effective_foreground_package", cleanPackage)
            .put("primary_content_state", "readable")
            .put("primary_content_available", true)
            .put("primary_failure_reason", "")
            .put(
                "primary_acquisition_source",
                "visual_screenshot_multimodal_verified"
            )
            .put(
                "window_context_mode",
                "r9_6_visual_screenshot_fallback"
            )
            .put("verification_text", cleanMarker)
            .put(
                "visible_text",
                JSONArray().put(cleanMarker)
            )
            .put("semantic_evidence_source", EVIDENCE_SOURCE)
            .put("semantic_fallback_version", VERSION)
            .put("visual_marker_verified", true)
            .put("visual_marker", cleanMarker)
            .put("visual_model_confidence", confidence)
            .put("visual_screenshot_sha256", screenshotSha)
            .put("visual_capture_mode", captureMode)
            .put("visual_window_id", windowId)
            .put("visual_captured_at_ms", capturedAt)
            .put(
                "visual_screenshot_width",
                screenshotEvidence.optInt("width", 0)
            )
            .put(
                "visual_screenshot_height",
                screenshotEvidence.optInt("height", 0)
            )
            .put(
                "visual_screenshot_bytes",
                screenshotEvidence.optLong("size_bytes", 0L)
            )
            .put("visual_external_knowledge_allowed", false)
            .put("visual_arbitrary_model_text_promoted", false)
    }

    fun selfTest(): Boolean {
        val screenshot =
            JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("expected_package", "com.sec.android.app.sbrowser")
                .put("captured_package", "com.sec.android.app.sbrowser")
                .put("package_match", true)
                .put(
                    "screenshot_sha256",
                    "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                )
                .put("capture_mode", "window")
                .put("window_id", 42)
                .put("captured_at_ms", 12345L)
                .put("width", 1600)
                .put("height", 1000)
                .put("size_bytes", 123456L)

        val ok =
            verifyExactMarker(
                expectedPackage = "com.sec.android.app.sbrowser",
                marker = "Example Domain",
                screenshotEvidence = screenshot,
                modelReply =
                    "{\"observed\":true,\"value\":\"Example Domain\",\"confidence\":\"high\"}"
            )

        if (!ok.optBoolean("verified", false)) return false
        if (ok.optString("verification_text") != "Example Domain") return false
        if (ok.optJSONArray("visible_text")?.length() != 1) return false
        if (ok.optString("window_context_mode") != "r9_6_visual_screenshot_fallback") {
            return false
        }

        val wrongPackage =
            verifyExactMarker(
                expectedPackage = "com.google.android.youtube",
                marker = "Example Domain",
                screenshotEvidence = screenshot,
                modelReply =
                    "{\"observed\":true,\"value\":\"Example Domain\",\"confidence\":\"high\"}"
            )
        if (wrongPackage.optBoolean("success", true)) return false

        val wrongValue =
            verifyExactMarker(
                expectedPackage = "com.sec.android.app.sbrowser",
                marker = "Example Domain",
                screenshotEvidence = screenshot,
                modelReply =
                    "{\"observed\":true,\"value\":\"Example\",\"confidence\":\"high\"}"
            )
        if (wrongValue.optBoolean("success", true)) return false

        val lowConfidence =
            verifyExactMarker(
                expectedPackage = "com.sec.android.app.sbrowser",
                marker = "Example Domain",
                screenshotEvidence = screenshot,
                modelReply =
                    "{\"observed\":true,\"value\":\"Example Domain\",\"confidence\":\"medium\"}"
            )
        if (lowConfidence.optBoolean("success", true)) return false

        val prose =
            verifyExactMarker(
                expectedPackage = "com.sec.android.app.sbrowser",
                marker = "Example Domain",
                screenshotEvidence = screenshot,
                modelReply = "На экране написано Example Domain"
            )
        if (prose.optBoolean("success", true)) return false

        val missingHash =
            verifyExactMarker(
                expectedPackage = "com.sec.android.app.sbrowser",
                marker = "Example Domain",
                screenshotEvidence =
                    JSONObject(screenshot.toString())
                        .put("screenshot_sha256", ""),
                modelReply =
                    "{\"observed\":true,\"value\":\"Example Domain\",\"confidence\":\"high\"}"
            )
        if (missingHash.optBoolean("success", true)) return false

        return true
    }

    private fun parseStrictJsonObject(
        raw: String
    ): JSONObject? {
        val clean = raw.trim()
        if (
            clean.isBlank() ||
            !clean.startsWith('{') ||
            !clean.endsWith('}')
        ) {
            return null
        }

        return try {
            JSONObject(clean)
        } catch (_: Exception) {
            null
        }
    }

    private fun failure(
        reason: String
    ): JSONObject =
        JSONObject()
            .put("success", false)
            .put("verified", false)
            .put("reason", reason)
            .put("semantic_fallback_version", VERSION)

    private fun normalize(
        value: String
    ): String =
        value
            .trim()
            .lowercase(Locale.ROOT)
            .replace('ё', 'е')
            .replace(Regex("\\s+"), " ")

    companion object {
        const val VERSION = "1.0"
        const val EVIDENCE_SOURCE =
            "accessibility_window_screenshot+multimodal_vision"

        private const val MAX_MARKER_CHARS = 240

        private val HEX_SHA256 =
            Regex("^[0-9a-f]{64}$")
    }
}
