package kg.autonomous.agent

import java.security.MessageDigest
import java.util.Locale

/**
 * AYANA Voice & Background Coordinator v2.0 — R10.23.
 *
 * Owns only voice-session/control-plane truth:
 * - WAKE / QUICK_COMMAND / COMMAND / FOLLOW_UP / CANCEL / BUSY mode generations;
 * - a 30-second natural FOLLOW_UP window with bounded stalled-speech grace;
 * - stale-session rejection after mode transitions or Service recovery;
 * - bounded duplicate VOICE dispatch suppression;
 * - fail-closed Service recovery: voice sessions return to WAKE and never replay a
 *   previously heard command. Durable autonomous goal recovery remains owned by the
 *   existing Durable Goal / lifecycle recovery stack.
 *
 * This class does not own AudioRecord, Sherpa, TTS, ORB, Android lifecycle, or
 * action authority. It is intentionally deterministic and locally testable.
 */
class AyanaVoiceBackgroundCoordinator {

    companion object {
        const val VERSION = "2.0"
        const val CONTRACT_VERSION = 1

        const val DEFAULT_FOLLOW_UP_WINDOW_MS = 30_000L
        const val DEFAULT_FOLLOW_UP_STALLED_SPEECH_MS = 1_400L
        const val DEFAULT_FOLLOW_UP_HARD_LIMIT_MS = 34_000L
        const val DEFAULT_DUPLICATE_VOICE_DISPATCH_WINDOW_MS = 1_800L
    }

    enum class Mode {
        WAKE,
        QUICK_COMMAND,
        COMMAND,
        FOLLOW_UP,
        CANCEL,
        BUSY
    }

    enum class FollowUpStatus {
        ACTIVE,
        EXPIRED_SILENCE,
        EXPIRED_STALLED_SPEECH,
        EXPIRED_HARD_LIMIT,
        STALE_SESSION
    }

    data class SessionToken(
        val generation: Long,
        val serviceEpoch: Long,
        val mode: Mode,
        val startedAtMs: Long
    )

    data class FollowUpDecision(
        val status: FollowUpStatus,
        val shouldReturnToWake: Boolean,
        val elapsedMs: Long,
        val generation: Long
    )

    data class DispatchDecision(
        val allowed: Boolean,
        val duplicateSuppressed: Boolean,
        val fingerprint: String,
        val reason: String
    )

    private var serviceEpoch = 1L
    private var generation = 0L
    private var currentMode = Mode.WAKE
    private var currentStartedAtMs = 0L

    private var lastVoiceFingerprint = ""
    private var lastVoiceDispatchAtMs = Long.MIN_VALUE

    @Synchronized
    fun enterWake(nowMs: Long): SessionToken =
        enter(Mode.WAKE, nowMs)

    @Synchronized
    fun enterQuickCommand(nowMs: Long): SessionToken =
        enter(Mode.QUICK_COMMAND, nowMs)

    @Synchronized
    fun enterCommand(nowMs: Long): SessionToken =
        enter(Mode.COMMAND, nowMs)

    @Synchronized
    fun enterFollowUp(nowMs: Long): SessionToken =
        enter(Mode.FOLLOW_UP, nowMs)

    @Synchronized
    fun enterCancel(nowMs: Long): SessionToken =
        enter(Mode.CANCEL, nowMs)

    @Synchronized
    fun enterBusy(nowMs: Long): SessionToken =
        enter(Mode.BUSY, nowMs)

    @Synchronized
    fun invalidate(nowMs: Long): SessionToken =
        enter(Mode.BUSY, nowMs)

    @Synchronized
    fun recoverService(nowMs: Long): SessionToken {
        serviceEpoch++
        lastVoiceFingerprint = ""
        lastVoiceDispatchAtMs = Long.MIN_VALUE
        return enter(Mode.WAKE, nowMs)
    }

    @Synchronized
    fun currentToken(): SessionToken =
        SessionToken(
            generation = generation,
            serviceEpoch = serviceEpoch,
            mode = currentMode,
            startedAtMs = currentStartedAtMs
        )

    @Synchronized
    fun isCurrent(
        generation: Long,
        expectedMode: Mode? = null
    ): Boolean =
        generation == this.generation &&
            (expectedMode == null || currentMode == expectedMode)

    @Synchronized
    fun evaluateFollowUp(
        sessionGeneration: Long,
        speechSeen: Boolean,
        recognitionChangedAtMs: Long,
        nowMs: Long
    ): FollowUpDecision {
        if (
            sessionGeneration != generation ||
            currentMode != Mode.FOLLOW_UP
        ) {
            return FollowUpDecision(
                status = FollowUpStatus.STALE_SESSION,
                shouldReturnToWake = false,
                elapsedMs = 0L,
                generation = sessionGeneration
            )
        }

        val elapsed =
            (nowMs - currentStartedAtMs)
                .coerceAtLeast(0L)

        val status =
            when {
                elapsed >= DEFAULT_FOLLOW_UP_HARD_LIMIT_MS ->
                    FollowUpStatus.EXPIRED_HARD_LIMIT

                !speechSeen &&
                    elapsed >= DEFAULT_FOLLOW_UP_WINDOW_MS ->
                    FollowUpStatus.EXPIRED_SILENCE

                speechSeen &&
                    elapsed >= DEFAULT_FOLLOW_UP_WINDOW_MS &&
                    nowMs - recognitionChangedAtMs >=
                    DEFAULT_FOLLOW_UP_STALLED_SPEECH_MS ->
                    FollowUpStatus.EXPIRED_STALLED_SPEECH

                else ->
                    FollowUpStatus.ACTIVE
            }

        return FollowUpDecision(
            status = status,
            shouldReturnToWake =
                status == FollowUpStatus.EXPIRED_SILENCE ||
                    status == FollowUpStatus.EXPIRED_STALLED_SPEECH ||
                    status == FollowUpStatus.EXPIRED_HARD_LIMIT,
            elapsedMs = elapsed,
            generation = sessionGeneration
        )
    }

    /**
     * Suppress only an immediate duplicate VOICE dispatch. Text mode is never
     * deduplicated here, and a repeated voice command after the bounded window is
     * treated as a fresh user command.
     */
    @Synchronized
    fun claimVoiceDispatch(
        command: String,
        nowMs: Long
    ): DispatchDecision {
        val canonical = canonicalCommand(command)
        if (canonical.isBlank()) {
            return DispatchDecision(
                allowed = false,
                duplicateSuppressed = false,
                fingerprint = "",
                reason = "blank_voice_command"
            )
        }

        val fingerprint = sha256(canonical)
        val elapsed =
            if (lastVoiceDispatchAtMs == Long.MIN_VALUE) {
                Long.MAX_VALUE
            } else {
                (nowMs - lastVoiceDispatchAtMs).coerceAtLeast(0L)
            }

        val duplicate =
            fingerprint == lastVoiceFingerprint &&
                elapsed <= DEFAULT_DUPLICATE_VOICE_DISPATCH_WINDOW_MS

        if (duplicate) {
            return DispatchDecision(
                allowed = false,
                duplicateSuppressed = true,
                fingerprint = fingerprint,
                reason = "duplicate_voice_dispatch_suppressed"
            )
        }

        lastVoiceFingerprint = fingerprint
        lastVoiceDispatchAtMs = nowMs

        return DispatchDecision(
            allowed = true,
            duplicateSuppressed = false,
            fingerprint = fingerprint,
            reason = "voice_dispatch_claimed"
        )
    }

    fun contractSelfTest(): Boolean {
        val test = AyanaVoiceBackgroundCoordinator()

        val follow = test.enterFollowUp(10_000L)
        val activeBeforeDeadline =
            test.evaluateFollowUp(
                sessionGeneration = follow.generation,
                speechSeen = false,
                recognitionChangedAtMs = 10_000L,
                nowMs = 39_999L
            )
        val expiresAtThirtySeconds =
            test.evaluateFollowUp(
                sessionGeneration = follow.generation,
                speechSeen = false,
                recognitionChangedAtMs = 10_000L,
                nowMs = 40_000L
            )

        val speaking = test.enterFollowUp(50_000L)
        val speechGraceActive =
            test.evaluateFollowUp(
                sessionGeneration = speaking.generation,
                speechSeen = true,
                recognitionChangedAtMs = 79_900L,
                nowMs = 80_500L
            )
        val stalledSpeechExpires =
            test.evaluateFollowUp(
                sessionGeneration = speaking.generation,
                speechSeen = true,
                recognitionChangedAtMs = 79_000L,
                nowMs = 80_500L
            )

        val stale = test.enterFollowUp(90_000L)
        test.enterWake(90_100L)
        val staleRejected =
            test.evaluateFollowUp(
                sessionGeneration = stale.generation,
                speechSeen = false,
                recognitionChangedAtMs = 90_000L,
                nowMs = 120_000L
            )

        val preRecovery = test.enterFollowUp(130_000L)
        val recovered = test.recoverService(130_100L)
        val recoveryInvalidatedOldSession =
            !test.isCurrent(preRecovery.generation) &&
                recovered.mode == Mode.WAKE &&
                recovered.serviceEpoch > preRecovery.serviceEpoch

        val first = test.claimVoiceDispatch("Аяна, открой YouTube", 200_000L)
        val duplicate = test.claimVoiceDispatch("аяна открой youtube", 200_900L)
        val later = test.claimVoiceDispatch("аяна открой youtube", 202_500L)
        val different = test.claimVoiceDispatch("аяна открой камера", 202_600L)

        return VERSION == "2.0" &&
            CONTRACT_VERSION == 1 &&
            DEFAULT_FOLLOW_UP_WINDOW_MS == 30_000L &&
            activeBeforeDeadline.status == FollowUpStatus.ACTIVE &&
            expiresAtThirtySeconds.status == FollowUpStatus.EXPIRED_SILENCE &&
            speechGraceActive.status == FollowUpStatus.ACTIVE &&
            stalledSpeechExpires.status == FollowUpStatus.EXPIRED_STALLED_SPEECH &&
            staleRejected.status == FollowUpStatus.STALE_SESSION &&
            recoveryInvalidatedOldSession &&
            first.allowed &&
            !duplicate.allowed &&
            duplicate.duplicateSuppressed &&
            later.allowed &&
            different.allowed
    }

    @Synchronized
    private fun enter(
        mode: Mode,
        nowMs: Long
    ): SessionToken {
        generation++
        currentMode = mode
        currentStartedAtMs = nowMs
        return currentToken()
    }

    private fun canonicalCommand(value: String): String {
        var canonical =
            value
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("[,!?;:.]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        for (wake in listOf("аяна", "айана", "айяна", "ayana")) {
            if (canonical == wake) {
                return ""
            }
            if (canonical.startsWith("$wake ")) {
                canonical = canonical.removePrefix("$wake ").trim()
                break
            }
        }

        return canonical
    }

    private fun sha256(value: String): String {
        val digest =
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte ->
            "%02x".format(Locale.ROOT, byte.toInt() and 0xff)
        }
    }
}
