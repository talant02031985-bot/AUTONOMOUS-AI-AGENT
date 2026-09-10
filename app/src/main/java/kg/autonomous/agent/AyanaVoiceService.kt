package kg.autonomous.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.media.ToneGenerator
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread
import kotlin.math.abs

class AyanaVoiceService : Service() {

    // AYANA v12.15.0 COMPLETION INTEGRITY + VERIFIED-FACTS REASONING HANDOFF.
    // Multi-device read-only executors may terminal SUCCESS only for presentation-only
    // metric goals. If the original command still contains an evaluation/condition/decision
    // clause, one verified local snapshot is preserved as trusted facts and handed to Agent
    // Core while the Execution Session stays RUNNING. This closes the v12.14 false-SUCCESS
    // gap without regressing the device-confirmed pure multi-metric fast path.
    // Terminal telemetry also keeps user-visible result separate from machine reason codes.
    // AYANA v12.14.0 WHOLE-GOAL INTEGRITY + ARTIFACT ROUTING + AGENT RECOVERY.
    // Fixes device-proven v12.13 regressions without changing frozen PASS lanes:
    // - app-open + foreground-verification is treated as one verified lifecycle goal;
    // - safe Settings>Apps final targets collapse directly to the requested app detail page;
    // - multi-metric read-only requests are aggregated instead of being swallowed by one metric;
    // - any explicit artifact deliverable keeps whole-goal ownership and reaches create_artifact;
    // - Agent Core machine terminal status can no longer turn an explicit capability refusal into SUCCESS;
    // - one bounded timeout retry is allowed before a recoverable Agent Core error is surfaced.
    // Existing STOP, lifecycle close truth, notifications, exact volume/brightness, Memory,
    // reminders, Settings verification and artifact publish verification stay intact.
    // AYANA v12.13.0 AUTONOMOUS ACCEPTANCE ENGINE + LOCAL TEST ROUTING.
    // Self-test requests are split into QUICK_HEALTH / CAPABILITY_AUDIT / FULL_ACCEPTANCE
    // and execute locally with zero Agent Core turns. Full acceptance combines live
    // read-only probes, pure contract checks and reversible state round-trips whose
    // original volume/brightness/reminder/memory state is restored before terminal.
    // A completed test run is never equated with agent readiness: the acceptance grade
    // (READY / READY_WITH_LIMITATIONS / NOT_READY) is reported separately.
    // AYANA v12.12.0 AUTONOMY + CAPABILITY TRUTH + PERCEPTION FUSION.
    // Self-review/autonomy-gap requests are answered from local runtime truth without
    // an Agent Core round-trip; Agent Core latency is phase-classified; screen reads
    // consume v4.5 effective-foreground fusion so AYANA's own overlay cannot mask a
    // separately verified external foreground package. Existing v12.11.x routing,
    // Completion Contract, Durable Goals, Safety, STOP and Settings truth remain intact.
    // AYANA v12.11.6 MULTI-STEP ROUTING INTEGRITY.
    // A conjunction inside a single Android Settings destination (for example
    // «дата и время») no longer forces an Agent Core/Planner round-trip.
    // Real multi-step goals still require explicit sequencing or two action verbs.
    // AYANA v12.11.5 SETTINGS ROUTING PRECEDENCE + DEVICE CARE.
    // Preserves v12.11.4 Settings truth/OEM recovery while closing two routing gaps:
    // Russian genitive «подключений» now maps to Connections, and Samsung
    // «Обслуживание устройства» is treated as a system Settings destination rather
    // than an application launch. Generic lifecycle/open-app routing is explicitly
    // prevented from owning Device Care phrases; terminal SUCCESS still belongs to
    // AyanaSystemSettingsNavigator verification, never to Intent dispatch alone.
    // AYANA v12.11.4 SYSTEM SETTINGS TRUTH + OEM RECOVERY.
    // Top-level Settings navigation now delegates to AyanaSystemSettingsNavigator:
    // Intent dispatch alone is never SUCCESS; Battery means Battery overview (not
    // Battery Saver); Date & time inflections stay navigation-safe; Connections
    // is first-class; and Samsung/OEM sparse-window failures get bounded semantic
    // recovery before the command is allowed to terminate successfully.
    // AYANA v12.11.3 NOTIFICATION PRIVACY PROJECTION.
    // Notification app-name/title projections are resolved before listener transport;
    // command rendering/history never receive notification bodies for those requests.
    // AYANA v12.11.2 PRE-EXECUTION GOAL INTEGRITY + COMPOSITE ORCHESTRATION.
    // Whole-goal preflight now owns explicit confirmation, supported local
    // conditions, environment constraints and bounded multi-step local goals
    // before any first side effect can dispatch. Partial multi-step completion
    // can never be upgraded to whole-command SUCCESS.
    // AYANA v12.10.2 DEVICE CONTROL TRUTH + NOTIFICATION READ + FOLLOW-UP BOUNDS.
    // v12.10.2 separates read-only notification intent from Settings navigation,
    // adds verified exact media-volume setting, and gives FOLLOW_UP an absolute
    // deadline so recognizer noise cannot keep the microphone armed indefinitely.
    // AYANA v12.10.1 TERMINAL TRUTH + LOCAL EXECUTION + SEMANTIC ACTION HARDENING.
    // v12.10.1 preserves v12.10 Agent Core telemetry/capability truth and adds:
    // - original-goal terminal criterion ownership for durable semantic actions,
    // - local self-diagnostics/device/screen/scroll routing,
    // - text-authored cancellation modality isolation across multimodal/DOCX lanes,
    // - completion-contract scope gating for input-document references,
    // - truthful unsupported video-audio handling and multimodal stage telemetry.
    // v12.10 adds phase-level Agent Core transport telemetry only in this service;
    // routing, STOP ownership, Android executors, artifact truth and Marin remain frozen.
    // AYANA v12.9.0 ROUTING INTEGRITY + MAIN-THREAD RESPONSIVENESS + DIAGNOSTIC TRUTH.
    // v12.9.0 freezes the YouTube/Settings terminal-verification branch and moves forward
    // with systemic behavior fixes: all command/multimodal/durable-goal entrypoints escape
    // the Android main looper before potentially blocking work; app-lifecycle clarification
    // is restricted to short ASR-noise fragments instead of ordinary informational phrases;
    // high-level development/project capability questions are no longer swallowed by the
    // document/artifact canned-response fast path; explicit self-diagnostics runs locally
    // without an Agent Core preflight so it cannot overwrite the telemetry it is measuring,
    // and every non-PASS diagnostic check is included in the final report.
    // AYANA v12.8.14 VERIFIED SETTINGS OWNER HANDOFF + SETTINGS CHAIN NATIVE RECOVERY.
    // v12.8.14 preserves v12.8.11 behavior and closes the remaining cross-step truth gap:
    // once App Info is VERIFIED by a real com.android.settings window/semantic surface, that
    // factual owner is handed to Accessibility v7.0 before the next Settings-row action. This
    // keeps Samsung launcher/SystemUI shells from replacing the verified Settings owner during
    // the same transaction. Intent dispatch alone never performs this handoff.
    // v12.8.11 preserves v12.8.10/v12.8.2 behavior and closes the combined
    // App Info -> subpage gap: canonical Settings row targets are tried before
    // scrolling through Screen Intelligence v4.4, and a short exact-intent-only
    // transition grace handles Samsung launcher/system shells without weakening
    // terminal verification.
    // v12.8.2 preserves universal semantic routing and adds:
    // - local semantic-action terminal truth guard (model final cannot upgrade failed local action),
    // - text-mode Agent Core microphone STOP isolation while keeping button STOP,
    // - bounded local "scroll to end" execution without one cloud turn per swipe.
    // All ordinary semantic click lanes now converge on AyanaScreenIntelligence v4,
    // which resolves one factual Accessibility target before Android dispatch and
    // returns explicit acceptance/verification/terminal evidence. File & Document
    // Engine v12.7.x remains unchanged and protected as a regression baseline.
    // Preserves device-confirmed v12.3 terminal-truth reconciliation, v12.4
    // Russian Marin pronunciation, and v12.5 echo-safe STOP semantics. v12.6
    // strengthens short-name phonetic canonicalization, upgrades Completion Contract
    // evidence from untyped references to typed artifact truth, and lets fully consumed
    // TTS responses return their HTTPS transport to Android's keep-alive pool.
    // Every app candidate is still resolved through the observed launcher map.
    // Preserves v12.1 verified app task removal while hardening STOP/terminal ownership.
    // restores the prior foreground context, and never claims process kill / force-stop.
    // Unified Execution Kernel owns cancellation/terminal/evidence state across long-running lanes.
    // Goal Compiler v2 provides executor/verification contracts and Settings verification can fuse
    // exact package-targeted Android intents with fresh same-window semantic Settings evidence.
    // AYANA v11.7 MULTIMODAL ROUTING + GROUNDED FOLLOW-UP.
    // v11.5 execution-router integrity is preserved. v11.6 attachment intake remains,
    // while v11.7 prevents attachments from hijacking deterministic device commands
    // and links successful multimodal turns into Responses conversation state for
    // grounded follow-up questions.
    // App open/minimize/close now share one deterministic local lifecycle router,
    // short follow-ups retain local app context, and terminal verification is fresh-state based.
    // Voice capture / Marin PCM / STOP acoustics remain inherited from v11.4.
    // AYANA v11.3 PERCEPTION, TRUTH & AUTONOMY CORE.
    // Built on v11.1.2 App Settings Integrity. v11.1.5 adds context-safe
    // terminal verification for multi-window / popup / PiP screens while preserving
    // the existing direct app-settings routes. v11.1.2 adds
    // routing-envelope cleanup plus strict app-settings terminal verification
    // with an OEM-safe App Info + Accessibility fallback. The v9.0/v9.1 audio, STOP and local
    // fast-routing stack is intentionally frozen: streamed 24 kHz Marin PCM,
    // VOICE_COMMUNICATION/AEC/NS, barge-in STOP and Russian local arithmetic
    // remain unchanged. v10.2 keeps durable goals/checkpoints/recovery, bounded
    // replanning and a local fail-closed Safety Engine around device actions.

    private enum class ListenMode {
        WAKE,
        QUICK_COMMAND,
        COMMAND,
        FOLLOW_UP,
        CANCEL,
        BUSY
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    // v12.9.0: deterministic/local command work may contain bounded verification loops,
    // Accessibility IPC and file/network operations. None of that is allowed to monopolize
    // Android's main looper. Serialize main-originated command work off-main so UI/ANR
    // responsiveness is preserved without introducing overlapping command transactions.
    private val commandDispatchLock =
        Any()

    @Volatile
    private var listenMode =
        ListenMode.WAKE

    @Volatile
    private var isRecording =
        false

    @Volatile
    private var shuttingDown =
        false

    @Volatile
    private var modelReady =
        false

    private var recognizer:
        OnlineRecognizer? = null

    private var audioRecord:
        AudioRecord? = null

    private var recordingThread:
        Thread? = null

    private var mediaPlayer:
        MediaPlayer? = null

    @Volatile
    private var audioTrack:
        AudioTrack? = null

    @Volatile
    private var currentTtsConnection:
        HttpsURLConnection? = null

    private var previousAudioMode:
        Int? = null

    private var communicationAudioModeOwned =
        false

    @Volatile
    private var bargeInAudioDiagnosticLogged =
        false

    private var cancelEchoCanceler:
        AcousticEchoCanceler? = null

    private var cancelNoiseSuppressor:
        NoiseSuppressor? = null

    @Volatile
    private var activeTtsTextNormalized =
        ""

    private var audioToken:
        Long = 0L

    @Volatile
    private var cancelRequested =
        false

    @Volatile
    private var micGeneration =
        0L

    // Owns FOLLOW_UP lifetime independently from Sherpa endpoint/noise state.
    // Every new bounded follow-up gets a fresh token so a stale deadline from
    // an older session can never stop a newer microphone session.
    @Volatile
    private var followUpDeadlineToken =
        0L

    @Volatile
    private var commandGeneration =
        0L

    @Volatile
    private var activeCommandToken =
        0L

    @Volatile
    private var currentAgentThread:
        Thread? = null

    @Volatile
    private var currentAgentConnection:
        HttpsURLConnection? = null

    private val executionKernel =
        AyanaExecutionKernel()

    private val cancelListenerWatchdog =
        object : Runnable {

            override fun run() {

                if (
                    shouldKeepCancelListener()
                ) {

                    listenMode =
                        ListenMode.CANCEL

                    if (
                        !isRecording
                    ) {
                        startSherpaListening()
                    }

                    mainHandler.postDelayed(
                        this,
                        CANCEL_LISTENER_WATCHDOG_MS
                    )
                }
            }
        }

    private val conversationHistory =
        mutableListOf<Pair<String, String>>()

    // Persistent command diagnostics. One active command at a time is expected
    // because the voice service enters BUSY mode while a task is running.
    private val commandHistoryStore by lazy {
        AyanaCommandHistoryStore(
            applicationContext
        )
    }

    @Volatile
    private var activeCommandHistoryId:
        String? = null

    // v12.2: semantic execution terminal is committed before optional Marin
    // presentation. These fields let a later voice STOP cancel only speech
    // without rewriting an already proven Android/Agent result.
    @Volatile
    private var pendingPresentationSuccess =
        false

    @Volatile
    private var pendingPresentationResult =
        ""

    @Volatile
    private var pendingPresentationTechnical =
        ""

    @Volatile
    private var pendingPresentationTerminalStatus:
        String? = null

    @Volatile
    private var pendingCancelSource =
        ""

    // v11.5 local lifecycle continuity. A short clarification such as
    // «Что сделать с YouTube?» keeps only the resolved app target locally; the
    // next «открой / сверни / закрой» never needs an Agent Core round-trip.
    @Volatile
    private var pendingLifecycleTarget:
        String? = null

    @Volatile
    private var pendingLifecycleLabel:
        String? = null

    @Volatile
    private var pendingLifecycleExpiresAtMs =
        0L

    // v12.11.2 PRE-EXECUTION CONFIRMATION CONTINUATION.
    // The first command terminates BLOCKED without a side effect and stores only
    // a short-lived executable payload. A later explicit Да/Нет is resolved
    // before ordinary routing; positive confirmation is preflighted again.
    private data class PendingPreExecutionConfirmation(
        val originalCommand: String,
        val executableCommand: String,
        val constraints: AyanaCompositeIntentGate.Constraints,
        val expiresAtMs: Long
    )

    @Volatile
    private var pendingPreExecutionConfirmation:
        PendingPreExecutionConfirmation? = null

    @Volatile
    private var confirmedPreExecutionConstraints:
        AyanaCompositeIntentGate.Constraints? = null

    @Volatile
    private var confirmedPreExecutionOriginal =
        ""

    // AUTONOMOUS CORE v10: persistent state of the currently executing
    // multi-step device goal. v11 keeps multiple recoverable goals instead of
    // destroying an older paused goal when a new goal starts.
    private val durableGoalStore by lazy {
        AyanaDurableGoalStore(
            applicationContext
        )
    }

    // Device Intelligence v11.1: all direct/Agent/Goal app launches use the observed map
    // through AyanaAppResolver; legacy package lists are validated hints only.
    // Device Intelligence v11: the installed-app map is observed dynamically
    // from this tablet. Static aliases are only hints and are device-validated.
    private val appResolver by lazy {
        AyanaAppResolver(
            applicationContext
        )
    }

    private val capabilityRegistry by lazy {
        AyanaCapabilityRegistry(
            applicationContext,
            appResolver
        )
    }

    private val selfDiagnostics by lazy {
        AyanaSelfDiagnostics(
            applicationContext,
            appResolver,
            capabilityRegistry
        )
    }

    private val agentPlannerV2 by lazy {
        AyanaAgentPlanner(
            appResolver,
            capabilityRegistry
        )
    }

    @Volatile
    private var currentDurableGoalId:
        String? = null

    @Volatile
    private var recoveryDispatchPending =
        false

    private val memoryStore by lazy {
        AyanaMemoryStore(
            applicationContext
        )
    }

    private val taskStore by lazy {
        AyanaTaskStore(
            applicationContext
        )
    }

    private val taskScheduler by lazy {
        AyanaTaskScheduler(
            applicationContext
        )
    }

    private val ayanaPreferences by lazy {
        AyanaPreferences(
            applicationContext
        )
    }

    private val miniOrbController by lazy {
        AyanaMiniOrbController(
            applicationContext
        )
    }

    private val screenIntelligence by lazy {
        AyanaScreenIntelligence(
            applicationContext
        )
    }

    private val systemSettingsNavigator by lazy {
        AyanaSystemSettingsNavigator(
            context = applicationContext,
            screenIntelligence = screenIntelligence,
            shouldCancel = {
                cancelRequested ||
                    executionKernel.isCancelled() ||
                    shuttingDown
            }
        )
    }

    // v12.2: Agent Core "final" is not proof that explicitly requested
    // deliverables actually exist.
    private val completionContract by lazy {
        AyanaCompletionContract()
    }

    // v12.7: real local deliverable executor. Artifacts are generated in AYANA's
    // private cache, verified, and only then published to Downloads/AYANA.
    private val artifactEngine by lazy {
        AyanaArtifactEngine(applicationContext)
    }

    // v12.7: DOCX translation does not rebuild Word from plain text. It keeps
    // the original OOXML package and replaces only validated w:t text nodes.
    private val docxTranslationEngine by lazy {
        AyanaDocxTranslationEngine()
    }

    // v12.4 presentation-only Russian pronunciation. Semantic text stored in
    // history/UI is never rewritten; only the text sent to Marin is prepared.
    private val russianSpeechNormalizer by lazy {
        AyanaRussianSpeechNormalizer()
    }

    // v12.5: one echo-aware STOP grammar owns both ordinary cancellation and
    // Marin barge-in. This prevents self-echo from bypassing the speech guard.
    private val cancelPhraseDetector by lazy {
        AyanaCancelPhraseDetector(
            WAKE_VARIANTS
        )
    }

    // v12.5: phonetic matching only ranks known local aliases. Actual package
    // identity remains owned by AyanaAppResolver and the observed launcher map.
    private val localAppPhoneticRouter by lazy {
        AyanaLocalAppPhoneticRouter()
    }

    // AYANA v12.1: user-visible close means verified removal of the app task
    // from Android Recents. This is deliberately separate from Home/minimize and
    // from force-stop/process-kill semantics that a normal third-party app cannot
    // safely promise for arbitrary packages.
    private val appLifecycleExecutor by lazy {
        AyanaAppLifecycleExecutor(
            gateway =
                object : AyanaAppLifecycleExecutor.Gateway {

                    override fun screenSnapshot(): JSONObject =
                        try {
                            screenIntelligence
                                .getScreenState()
                        } catch (_: Exception) {
                            JSONObject()
                                .put(
                                    "success",
                                    false
                                )
                        }

                    override fun removeRecentTaskByLabel(
                        targetLabel: String,
                        sourcePackage: String,
                        shouldCancel: () -> Boolean
                    ): JSONObject {

                        val accessibility =
                            AgentAccessibilityService
                                .instance

                        if (accessibility == null) {
                            return JSONObject()
                                .put("success", false)
                                .put("verified", false)
                                .put("terminal_status", "UNSUPPORTED")
                                .put("reason", "accessibility_unavailable")
                                .put(
                                    "message",
                                    "Служба специальных возможностей AYANA недоступна"
                                )
                        }

                        return accessibility
                            .removeRecentTaskByLabel(
                                targetLabel = targetLabel,
                                sourcePackage = sourcePackage,
                                shouldCancel = shouldCancel,
                                tryBeginIrreversibleDispatch = { detail ->
                                    executionKernel
                                        .tryBeginIrreversibleDispatch(
                                            kind = "recents_task_removal",
                                            detail = detail
                                        )
                                },
                                onIrreversibleDispatchAccepted = { detail ->
                                    executionKernel
                                        .markIrreversibleDispatchAccepted(
                                            detail
                                        )
                                },
                                onReconciliationStarted = { detail ->
                                    executionKernel
                                        .markSideEffectReconciliationStarted(
                                            detail
                                        )
                                },
                                onReconciled = { committed, detail ->
                                    executionKernel
                                        .markSideEffectReconciled(
                                            committed = committed,
                                            detail = detail
                                        )
                                }
                            )
                    }

                    override fun restorePackage(
                        packageName: String
                    ): Boolean {

                        if (packageName.isBlank()) {
                            return false
                        }

                        val intent =
                            try {
                                packageManager
                                    .getLaunchIntentForPackage(
                                        packageName
                                    )
                            } catch (_: Exception) {
                                null
                            }
                                ?: return false

                        return try {
                            intent.addFlags(
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                            )
                            startActivity(
                                intent
                            )
                            true
                        } catch (_: Exception) {
                            false
                        }
                    }

                    override fun pressHome(): Boolean =
                        AgentAccessibilityService
                            .instance
                            ?.pressHome() ==
                            true
                },
            shouldCancel = {
                cancelRequested ||
                    executionKernel.isCancelled() ||
                    shuttingDown
            }
        )
    }

    // Local fail-closed safety layer. This executes on Android before
    // Agent Core device tools, independently from model instructions.
    private val safetyPolicy by lazy {
        AyanaSafetyPolicy()
    }

    // v12.13: local acceptance engine. The runner delegates individual probes
    // back into this service so existing Android executors/stores remain the
    // single implementation owners; the engine only orchestrates and grades.
    private val acceptanceTestEngine by lazy {
        AyanaAcceptanceTestEngine(
            probeRunner = { probeId ->
                runAcceptanceProbe(probeId)
            },
            shouldCancel = {
                cancelRequested ||
                    executionKernel.isCancelled() ||
                    shuttingDown
            }
        )
    }

    // =========================================================
    // ANDROID TASK ENGINE v4 BRIDGE
    // =========================================================
    // Agent Core may produce one short structured Android plan. The plan is
    // executed locally by AyanaAndroidTaskEngine instead of spending one network
    // round-trip for every obvious UI action. Existing direct/local routes remain
    // untouched and continue to work as the stable fast path.
    private val androidTaskEngine by lazy {
        AyanaAndroidTaskEngine(
            screenIntelligence = screenIntelligence,
            gateway =
                object : AyanaAndroidTaskEngine.ActionGateway {

                    override fun openSettings(
                        section: String
                    ): JSONObject =
                        this@AyanaVoiceService
                            .agentOpenSettings(
                                section
                            )

                    override fun openApp(
                        name: String
                    ): JSONObject =
                        this@AyanaVoiceService
                            .agentOpenApp(
                                name
                            )

                    override fun openAppInfo(
                        name: String
                    ): JSONObject =
                        this@AyanaVoiceService
                            .agentOpenAppInfo(
                                name
                            )

                    override fun openAppSettings(
                        name: String,
                        section: String
                    ): JSONObject =
                        this@AyanaVoiceService
                            .agentOpenAppSettings(
                                requestedName = name,
                                section = section
                            )

                    override fun changeVolume(
                        action: String
                    ): JSONObject =
                        this@AyanaVoiceService
                            .agentChangeVolume(
                                action
                            )
                },
            shouldCancel = {
                cancelRequested ||
                    executionKernel.isCancelled() ||
                    shuttingDown
            }
        )
    }

    private val androidGoalCompiler by lazy {
        AyanaAndroidGoalCompiler()
    }

    @Volatile
    private var agentPreviousResponseId:
        String? = null

    private val readyFile by lazy {
        File(
            filesDir,
            "ayana_ready_da_marin_ru_signature_v1.mp3"
        )
    }

    private val sampleRateInHz =
        16000

    private val channelConfig =
        AudioFormat.CHANNEL_IN_MONO

    private val audioFormat =
        AudioFormat.ENCODING_PCM_16BIT

    override fun onCreate() {
        super.onCreate()

        isRunning = false
        shuttingDown = false

        createNotificationChannel()

        promoteToForeground(
            "AYANA запускает локальное распознавание"
        )

        // IMPORTANT: onCreate() may be called for ACTION_STOP or another
        // service intent. Never create the overlay here. The single Orb is
        // created only after a real ACTION_START / active text command.
        ayanaPreferences.miniOrbEnabled = true

        currentStatusText =
            "AYANA запускается"

        currentStatusState =
            STATE_THINKING

        try {
            durableGoalStore
                .markInterruptedGoals(
                    "service_recreated"
                )
        } catch (_: Exception) {
        }

        prefetchReadyVoice()

        thread(
            start = true,
            name = "AyanaModelInit"
        ) {
            try {

                initSherpaModel()

                modelReady = true
                capabilityRegistry
                    .recordRecognitionReady(
                        true
                    )

                mainHandler.post {
                    if (
                        !shuttingDown &&
                        isRunning
                    ) {
                        startWakeListening()
                    }
                }

            } catch (_: Exception) {

                modelReady = false
                capabilityRegistry
                    .recordRecognitionReady(
                        false
                    )

                mainHandler.post {
                    broadcastStatus(
                        "Не удалось загрузить локальную модель",
                        STATE_ERROR
                    )

                    updateNotification(
                        "Ошибка локального распознавания"
                    )
                }
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {

            ACTION_STOP -> {
                shutdownAyana()
                return START_NOT_STICKY
            }

            ACTION_CANCEL_COMMAND -> {
                cancelCurrentCommand(
                    source = "button"
                )
                return START_STICKY
            }

            ACTION_RESUME_GOAL -> {

                isRunning =
                    true

                ensureOrbForActiveService()

                mainHandler.post {
                    resumeDurableGoal(
                        silent = true,
                        explicitConfirmation = false,
                        allowAutoResume = false
                    )
                }

                return START_STICKY
            }

            ACTION_CONFIRM_GOAL -> {

                isRunning =
                    true

                ensureOrbForActiveService()

                mainHandler.post {
                    resumeDurableGoal(
                        silent = true,
                        explicitConfirmation = true,
                        allowAutoResume = false
                    )
                }

                return START_STICKY
            }

            ACTION_CANCEL_GOAL -> {

                isRunning =
                    true

                ensureOrbForActiveService()

                mainHandler.post {
                    cancelDurableGoalFromControl(
                        silent = true
                    )
                }

                return START_STICKY
            }

            ACTION_START -> {

                isRunning =
                    true

                ayanaPreferences.miniOrbEnabled =
                    true

                miniOrbController.refresh(
                    enabled = true,
                    state =
                        if (
                            currentStatusState ==
                            STATE_STOPPED
                        ) {
                            STATE_LISTENING
                        } else {
                            currentStatusState
                        }
                )

                if (
                    !shuttingDown &&
                    modelReady &&
                    !isRecording &&
                    listenMode != ListenMode.BUSY &&
                    listenMode != ListenMode.CANCEL
                ) {
                    startWakeListening()
                }

                maybeAutoResumeDurableGoal()
            }

            ACTION_REFRESH_OVERLAY -> {

                if (
                    isRunning &&
                    !shuttingDown &&
                    currentStatusState !=
                    STATE_STOPPED
                ) {

                    ayanaPreferences.miniOrbEnabled =
                        true

                    miniOrbController.refresh(
                        enabled = true,
                        state =
                            currentStatusState
                    )
                }
            }

            ACTION_MULTIMODAL_COMMAND -> {

                val command =
                    intent.getStringExtra(
                        EXTRA_TEXT_COMMAND
                    )
                        ?.trim()
                        .orEmpty()

                val manifest =
                    intent.getStringExtra(
                        EXTRA_MULTIMODAL_MANIFEST
                    )
                        ?.trim()
                        .orEmpty()

                if (
                    command.isNotBlank() &&
                    manifest.isNotBlank()
                ) {
                    isRunning = true
                    ayanaPreferences.miniOrbEnabled = true
                    miniOrbController.refresh(
                        enabled = true,
                        state = currentStatusState
                    )
                    stopSherpaListening()
                    mainHandler.post {
                        if (!shuttingDown) {
                            executeMultimodalCommand(
                                command,
                                manifest
                            )
                        }
                    }
                }
            }

            ACTION_TEXT_COMMAND -> {

                val command =
                    intent.getStringExtra(
                        EXTRA_TEXT_COMMAND
                    )
                        ?.trim()
                        .orEmpty()

                if (command.isNotBlank()) {

                    isRunning =
                        true

                    ayanaPreferences.miniOrbEnabled =
                        true

                    miniOrbController.refresh(
                        enabled = true,
                        state =
                            currentStatusState
                    )

                    stopSherpaListening()

                    // INSTANT TEXT v2.7.4.4
                    // Text input is already final; unlike voice it does not need an
                    // endpoint/grace delay. Queue it on the main handler immediately.
                    mainHandler.post {
                        if (!shuttingDown) {
                            executeCommand(
                                command,
                                silent = true
                            )
                        }
                    }
                }
            }

            null -> {
                // START_STICKY process recreation. Unlike BOOT_COMPLETED, this
                // is a service lifecycle recovery. Restore only a recent,
                // explicitly low-risk goal and keep the recovery bounded.
                isRunning =
                    true

                ensureOrbForActiveService()

                if (
                    !shuttingDown &&
                    modelReady &&
                    !isRecording
                ) {
                    startWakeListening()
                }

                maybeAutoResumeDurableGoal()
            }
        }

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    // =========================================================
    // FOREGROUND SERVICE
    // =========================================================

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= 26) {

            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    "AYANA Voice",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {

                    description =
                        "Фоновая работа голосового помощника AYANA"

                    setShowBadge(false)
                }

            val manager =
                getSystemService(
                    NotificationManager::class.java
                )

            manager.createNotificationChannel(
                channel
            )
        }
    }

    private fun buildNotification(
        text: String
    ): Notification {

        val openIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                100,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val cancelIntent =
            Intent(
                this,
                AyanaVoiceService::class.java
            ).apply {
                action =
                    ACTION_CANCEL_COMMAND
            }

        val cancelPendingIntent =
            PendingIntent.getService(
                this,
                101,
                cancelIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val stopIntent =
            Intent(
                this,
                AyanaVoiceService::class.java
            ).apply {
                action = ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                102,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

        val builder =
            if (Build.VERSION.SDK_INT >= 26) {

                Notification.Builder(
                    this,
                    CHANNEL_ID
                )

            } else {

                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }

        return builder
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setContentTitle("AYANA AI")
            .setContentText(text)
            .setSubText(
                "Голос AYANA синтезирован искусственным интеллектом"
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Стоп команды",
                cancelPendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                "Остановить AYANA",
                stopPendingIntent
            )
            .build()
    }

    private fun promoteToForeground(
        text: String
    ) {

        val notification =
            buildNotification(text)

        if (Build.VERSION.SDK_INT >= 29) {

            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MICROPHONE
            )

        } else {

            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun updateNotification(
        text: String
    ) {

        val manager =
            getSystemService(
                NotificationManager::class.java
            )

        manager.notify(
            NOTIFICATION_ID,
            buildNotification(text)
        )
    }

    // =========================================================
    // SHERPA-ONNX
    // =========================================================

    private fun initSherpaModel() {

        val modelDir =
            "sherpa_ru"

        val modelConfig =
            OnlineModelConfig(
                transducer =
                    OnlineTransducerModelConfig(
                        encoder =
                            "$modelDir/encoder.int8.onnx",
                        decoder =
                            "$modelDir/decoder.onnx",
                        joiner =
                            "$modelDir/joiner.int8.onnx"
                    ),
                tokens =
                    "$modelDir/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu",
                modelType = "zipformer2"
            )

        val config =
            OnlineRecognizerConfig(
                featConfig =
                    FeatureConfig(
                        sampleRate =
                            sampleRateInHz,
                        featureDim = 80,
                        dither = 0.0f
                    ),
                modelConfig =
                    modelConfig,
                enableEndpoint =
                    true,
                decodingMethod =
                    "greedy_search"
            )

        recognizer =
            OnlineRecognizer(
                assetManager = assets,
                config = config
            )
    }

    private fun startWakeListening() {

        if (
            shuttingDown ||
            !modelReady
        ) {
            return
        }

        listenMode =
            ListenMode.WAKE

        broadcastStatus(
            "Жду: «Аяна»",
            STATE_LISTENING
        )

        updateNotification(
            "Жду голосовую команду «Аяна»"
        )

        startSherpaListening()
    }

    private fun startQuickCommandListening() {

        if (
            shuttingDown ||
            !modelReady
        ) {
            return
        }

        listenMode =
            ListenMode.QUICK_COMMAND

        broadcastStatus(
            "Слушаю…",
            STATE_COMMAND
        )

        updateNotification(
            "Слушаю продолжение команды"
        )

        startSherpaListening()
    }

    private fun startFollowUpOrWake() {

        // A CANCEL listener may still own the microphone while AYANA is
        // speaking. End that stream first so the next wake/follow-up mode starts
        // with a clean recognizer stream rather than inheriting TTS audio.
        stopCancelListenerWatchdog()
        stopSherpaListening()

        mainHandler.postDelayed(
            {
                if (
                    !shuttingDown &&
                    isRunning
                ) {

                    val audioManager =
                        getSystemService(
                            Context.AUDIO_SERVICE
                        ) as? AudioManager

                    if (
                        audioManager?.isMusicActive == true
                    ) {
                        startWakeListening()
                    } else {
                        startFollowUpListening()
                    }
                }
            },
            CANCEL_MODE_TRANSITION_MS
        )
    }

    private fun startFollowUpListening() {

        if (
            shuttingDown ||
            !modelReady
        ) {
            return
        }

        listenMode =
            ListenMode.FOLLOW_UP

        broadcastStatus(
            "Можно продолжить без «Аяна»",
            STATE_COMMAND
        )

        updateNotification(
            "Слушаю продолжение"
        )

        val deadlineToken =
            ++followUpDeadlineToken

        startSherpaListening()

        // FOLLOW-UP OWNERSHIP v12.11
        // Main-looper deadline owns the absolute follow-up lifetime even when
        // recognizer noise or partial hypotheses prevent an endpoint.
        mainHandler.postDelayed(
            {
                if (
                    !shuttingDown &&
                    deadlineToken ==
                    followUpDeadlineToken &&
                    listenMode ==
                    ListenMode.FOLLOW_UP
                ) {
                    followUpDeadlineToken++
                    stopSherpaListening()
                    startWakeListening()
                }
            },
            FOLLOW_UP_HARD_LIMIT_MS
        )
    }

    private fun startCancelListening() {

        if (
            shuttingDown ||
            !modelReady ||
            !isRunning
        ) {
            return
        }

        listenMode =
            ListenMode.CANCEL

        // Do not overwrite THINKING / EXECUTING / SPEAKING visual status.
        // Keep a tiny local listener alive only for STOP/full-shutdown phrases.
        startCancelListenerWatchdog()

        if (
            !isRecording
        ) {
            startSherpaListening()
        }
    }

    private fun startCancelListenerWatchdog() {

        mainHandler.removeCallbacks(
            cancelListenerWatchdog
        )

        mainHandler.post(
            cancelListenerWatchdog
        )
    }

    private fun stopCancelListenerWatchdog() {

        mainHandler.removeCallbacks(
            cancelListenerWatchdog
        )
    }

    private fun shouldKeepCancelListener():
        Boolean {

        return !shuttingDown &&
            isRunning &&
            !cancelRequested &&
            currentStatusState in
            setOf(
                STATE_THINKING,
                STATE_EXECUTING,
                STATE_SPEAKING
            )
    }

    private fun startCommandListening() {

        if (
            shuttingDown ||
            !modelReady
        ) {
            return
        }

        listenMode =
            ListenMode.COMMAND

        broadcastStatus(
            "Слушаю команду…",
            STATE_COMMAND
        )

        updateNotification(
            "Слушаю вашу команду"
        )

        startSherpaListening()
    }

    private fun startSherpaListening() {

        if (
            shuttingDown ||
            !modelReady
        ) {
            return
        }

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {

            broadcastStatus(
                "Нет доступа к микрофону",
                STATE_ERROR
            )

            updateNotification(
                "Нужен доступ к микрофону"
            )

            return
        }

        if (
            recordingThread?.isAlive == true ||
            isRecording
        ) {

            mainHandler.postDelayed(
                {
                    if (
                        !shuttingDown &&
                        !isRecording
                    ) {
                        startSherpaListening()
                    }
                },
                160L
            )

            return
        }

        val minBufferBytes =
            AudioRecord.getMinBufferSize(
                sampleRateInHz,
                channelConfig,
                audioFormat
            )

        if (minBufferBytes <= 0) {

            broadcastStatus(
                "Не удалось открыть микрофон",
                STATE_ERROR
            )

            return
        }

        var recorder: AudioRecord? =
            null

        var selectedAudioSource =
            -1

        val cancelDuringSpeech =
            listenMode ==
                ListenMode.CANCEL &&
                currentStatusState ==
                STATE_SPEAKING

        val audioSources =
            if (
                cancelDuringSpeech
            ) {
                intArrayOf(
                    MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
                )
            } else {
                intArrayOf(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
                )
            }

        for (source in audioSources) {

            val candidate =
                try {
                    AudioRecord(
                        source,
                        sampleRateInHz,
                        channelConfig,
                        audioFormat,
                        minBufferBytes * 2
                    )
                } catch (_: Exception) {
                    null
                }

            if (
                candidate != null &&
                candidate.state ==
                AudioRecord.STATE_INITIALIZED
            ) {
                recorder = candidate
                selectedAudioSource = source
                break
            }

            try {
                candidate?.release()
            } catch (_: Exception) {
            }
        }

        if (
            recorder == null ||
            recorder.state !=
            AudioRecord.STATE_INITIALIZED
        ) {

            try {
                recorder?.release()
            } catch (_: Exception) {
            }

            broadcastStatus(
                "Микрофон недоступен",
                STATE_ERROR
            )

            return
        }

        val activeRecorder =
            recorder
                ?: return

        audioRecord =
            activeRecorder

        configureCancelAudioEffects(
            activeRecorder,
            enabled =
                cancelDuringSpeech
        )

        try {

            activeRecorder.startRecording()

            isRecording =
                true

            if (
                cancelDuringSpeech &&
                !bargeInAudioDiagnosticLogged
            ) {
                bargeInAudioDiagnosticLogged =
                    true

                val audioManager =
                    getSystemService(
                        Context.AUDIO_SERVICE
                    ) as AudioManager

                commandHistoryStore.addEvent(
                    activeCommandHistoryId,
                    state = "barge_in_audio",
                    message = "Аудиоканал STOP активирован",
                    details =
                        "source=${audioSourceName(selectedAudioSource)};" +
                            " mode=${audioManager.mode};" +
                            " aec=${cancelEchoCanceler?.enabled == true};" +
                            " ns=${cancelNoiseSuppressor?.enabled == true}"
                )
            }

        } catch (_: Exception) {

            try {
                activeRecorder.release()
            } catch (_: Exception) {
            }

            audioRecord =
                null

            releaseCancelAudioEffects()

            isRecording =
                false

            broadcastStatus(
                "Не удалось начать прослушивание",
                STATE_ERROR
            )

            return
        }

        val sessionGeneration =
            ++micGeneration

        recordingThread =
            thread(
                start = true,
                name = "AyanaSherpaAudio"
            ) {

                processSherpaAudio(
                    activeRecorder,
                    sessionGeneration
                )
            }
    }

    private fun processSherpaAudio(
        recorder: AudioRecord,
        sessionGeneration: Long
    ) {

        val localRecognizer =
            recognizer
                ?: return

        val stream =
            try {
                localRecognizer
                    .createStream()
            } catch (_: Exception) {
                cleanupRecorder(
                    recorder
                )
                return
            }

        val intervalSeconds =
            0.10

        val sampleCount =
            (
                intervalSeconds *
                    sampleRateInHz
                ).toInt()

        val buffer =
            ShortArray(
                sampleCount
            )

        var wakeSeen =
            false

        val modeStartedAt =
            SystemClock.elapsedRealtime()

        var speechSeen =
            false

        // Sherpa can wait several seconds for an endpoint even after a complete
        // phrase. Track when the partial transcript last changed so selected
        // direct local commands can commit earlier without waiting for endpoint.
        var lastRecognitionText =
            ""

        var recognitionChangedAt =
            modeStartedAt

        var pendingAction:
            (() -> Unit)? = null

        var lastCancelDiagnosticText =
            ""

        var lastCancelDiagnosticAt =
            0L

        try {

            while (
                isRecording &&
                !shuttingDown
            ) {

                val count =
                    try {
                        recorder.read(
                            buffer,
                            0,
                            buffer.size
                        )
                    } catch (_: Exception) {
                        -1
                    }

                if (count <= 0) {
                    continue
                }

                var peak =
                    0.0f

                for (index in 0 until count) {
                    val level =
                        abs(
                            buffer[index] /
                                32768.0f
                        )

                    if (level > peak) {
                        peak = level
                    }
                }

                // Wake sensitivity v2.7.4:
                // Keep true near-silence unamplified, but boost very quiet
                // speech more consistently. This improves distant/soft «Аяна»
                // without changing the recognizer model or microphone source.
                val inputGain =
                    if (
                        listenMode ==
                        ListenMode.CANCEL &&
                        currentStatusState ==
                        STATE_SPEAKING
                    ) {

                        when {
                            peak < 0.00035f ->
                                1.0f

                            peak < 0.008f ->
                                5.8f

                            peak < 0.025f ->
                                4.4f

                            peak < 0.060f ->
                                3.0f

                            peak < 0.120f ->
                                1.9f

                            else ->
                                1.25f
                        }

                    } else {

                        when {
                            peak < 0.00035f ->
                                1.0f

                            peak < 0.0040f ->
                                5.5f

                            peak < 0.012f ->
                                4.8f

                            peak < 0.030f ->
                                3.5f

                            peak < 0.060f ->
                                2.4f

                            peak < 0.100f ->
                                1.7f

                            peak < 0.150f ->
                                1.25f

                            else ->
                                1.0f
                        }
                    }

                val samples =
                    FloatArray(count) {
                        (
                            buffer[it] /
                                32768.0f *
                                inputGain
                            )
                            .coerceIn(
                                -1.0f,
                                1.0f
                            )
                    }

                stream.acceptWaveform(
                    samples,
                    sampleRate =
                        sampleRateInHz
                )

                while (
                    localRecognizer
                        .isReady(stream)
                ) {

                    localRecognizer
                        .decode(stream)
                }

                val text =
                    normalizeRecognitionText(
                        localRecognizer
                            .getResult(stream)
                            .text
                    )

                if (text != lastRecognitionText) {
                    lastRecognitionText =
                        text
                    recognitionChangedAt =
                        SystemClock.elapsedRealtime()
                }

                if (
                    (
                        listenMode ==
                        ListenMode.QUICK_COMMAND ||
                        listenMode ==
                        ListenMode.FOLLOW_UP
                    ) &&
                    text.isNotBlank()
                ) {
                    speechSeen = true
                }

                val isEndpoint =
                    localRecognizer
                        .isEndpoint(stream)

                when (listenMode) {

                    ListenMode.WAKE -> {

                        if (
                            text.isNotBlank() &&
                            containsWakeWord(text)
                        ) {

                            wakeSeen = true

                            broadcastStatus(
                                "Слышу «Аяна»…",
                                STATE_COMMAND
                            )
                        }

                        // Fast local commit: once a complete direct-settings
                        // phrase has been stable briefly, execute it without
                        // waiting for Sherpa's slower endpoint. We deliberately
                        // exclude the generic settings page to avoid cutting off
                        // a user who pauses after «открой настройки…».
                        if (
                            !isEndpoint &&
                            wakeSeen &&
                            text.isNotBlank() &&
                            SystemClock.elapsedRealtime() -
                                recognitionChangedAt >=
                            FAST_LOCAL_PARTIAL_COMMIT_MS
                        ) {

                            val earlyCommand =
                                extractWakeCommand(
                                    text
                                )

                            val earlySection =
                                extractDirectSystemSettingsSection(
                                    earlyCommand
                                )

                            val earlyAppSettingsTarget =
                                extractSettingsAppSearchTarget(
                                    earlyCommand
                                )

                            val earlyMultiStep =
                                isMultiStepAgentCommand(
                                    earlyCommand
                                )

                            // ACCESSIBILITY PARTIAL GUARD v2.7.4.5
                            // Do not commit Accessibility from a partial transcript:
                            // the user may continue with «найди AYANA AI…». Waiting
                            // for the endpoint is slightly slower for the single-step
                            // Accessibility command, but prevents cutting off a real
                            // multi-step goal before Planner receives it.
                            if (
                                !earlyMultiStep &&
                                (
                                    earlyAppSettingsTarget != null ||
                                    (
                                        earlySection != null &&
                                        earlySection != "general" &&
                                        earlySection != "accessibility"
                                    )
                                )
                            ) {

                                wakeSeen =
                                    false

                                isRecording =
                                    false

                                pendingAction =
                                    {
                                        executeCommand(
                                            earlyCommand,
                                            silent = false
                                        )
                                    }

                                break
                            }
                        }

                        if (isEndpoint) {

                            val finalText =
                                text

                            val detected =
                                wakeSeen ||
                                    containsWakeWord(
                                        finalText
                                    )

                            if (detected) {

                                wakeSeen =
                                    false

                                val command =
                                    extractWakeCommand(
                                        finalText
                                    )

                                isRecording =
                                    false

                                pendingAction =
                                    if (
                                        command.isBlank()
                                    ) {

                                        {
                                            startQuickCommandListening()
                                        }

                                    } else {

                                        {
                                            executeCommand(
                                                command,
                                                silent = false
                                            )
                                        }
                                    }

                                break

                            } else {

                                wakeSeen =
                                    false

                                localRecognizer
                                    .reset(stream)
                            }
                        }
                    }

                    ListenMode.QUICK_COMMAND -> {

                        if (isEndpoint) {

                            val finalText =
                                text

                            if (finalText.isNotBlank()) {

                                isRecording =
                                    false

                                pendingAction =
                                    {
                                        executeCommand(
                                            finalText,
                                            silent = false
                                        )
                                    }

                                break

                            } else {

                                localRecognizer
                                    .reset(stream)
                            }
                        }

                        if (
                            !speechSeen &&
                            SystemClock.elapsedRealtime() -
                                modeStartedAt >=
                            QUICK_COMMAND_GRACE_MS
                        ) {

                            isRecording =
                                false

                            pendingAction =
                                {
                                    acknowledgeWakeAndListen()
                                }

                            break
                        }
                    }

                    ListenMode.FOLLOW_UP -> {

                        if (isEndpoint) {

                            val finalText =
                                text

                            val followUpCommand =
                                if (
                                    containsWakeWord(
                                        finalText
                                    )
                                ) {
                                    extractWakeCommand(
                                        finalText
                                    )
                                } else {
                                    finalText
                                }

                            if (followUpCommand.isNotBlank()) {

                                isRecording =
                                    false

                                pendingAction =
                                    {
                                        executeCommand(
                                            followUpCommand,
                                            silent = false
                                        )
                                    }

                                break

                            } else if (
                                finalText.isNotBlank() &&
                                containsWakeWord(
                                    finalText
                                )
                            ) {

                                isRecording =
                                    false

                                pendingAction =
                                    {
                                        acknowledgeWakeAndListen()
                                    }

                                break

                            } else {

                                localRecognizer
                                    .reset(stream)
                            }
                        }

                        val followUpNow =
                            SystemClock.elapsedRealtime()

                        val followUpElapsed =
                            followUpNow -
                                modeStartedAt

                        val followUpSilentExpired =
                            !speechSeen &&
                                followUpElapsed >=
                                FOLLOW_UP_WINDOW_MS

                        val followUpSpeechStalled =
                            speechSeen &&
                                followUpElapsed >=
                                FOLLOW_UP_WINDOW_MS &&
                                followUpNow -
                                    recognitionChangedAt >=
                                FOLLOW_UP_STALLED_SPEECH_MS

                        val followUpHardExpired =
                            followUpElapsed >=
                                FOLLOW_UP_HARD_LIMIT_MS

                        if (
                            followUpSilentExpired ||
                            followUpSpeechStalled ||
                            followUpHardExpired
                        ) {

                            isRecording =
                                false

                            pendingAction =
                                {
                                    startWakeListening()
                                }

                            break
                        }
                    }

                    ListenMode.COMMAND -> {

                        if (isEndpoint) {

                            val finalText =
                                text

                            if (
                                finalText.isNotBlank()
                            ) {

                                isRecording =
                                    false

                                pendingAction =
                                    {
                                        executeCommand(
                                            finalText,
                                            silent = false
                                        )
                                    }

                                break

                            } else {

                                localRecognizer
                                    .reset(stream)
                            }
                        }
                    }

                    ListenMode.CANCEL -> {

                        if (
                            currentStatusState in
                            setOf(
                                STATE_THINKING,
                                STATE_EXECUTING,
                                STATE_SPEAKING
                            ) &&
                            text.isNotBlank() &&
                            text !=
                            lastCancelDiagnosticText &&
                            (
                                isEndpoint ||
                                SystemClock.elapsedRealtime() -
                                    lastCancelDiagnosticAt >=
                                CANCEL_DIAGNOSTIC_INTERVAL_MS
                            )
                        ) {
                            lastCancelDiagnosticText =
                                text

                            lastCancelDiagnosticAt =
                                SystemClock.elapsedRealtime()

                            commandHistoryStore.addEvent(
                                activeCommandHistoryId,
                                state = "cancel_heard",
                                message = text.take(
                                    220
                                )
                            )
                        }

                        if (
                            text.isNotBlank() &&
                            isShutdownAyanaPhrase(
                                text
                            )
                        ) {

                            isRecording =
                                false

                            pendingAction =
                                {
                                    shutdownAyana()
                                }

                            break
                        }

                        val cancelMatch =
                            if (text.isNotBlank()) {
                                cancelPhraseDetector.detect(
                                    value = text,
                                    speaking =
                                        currentStatusState ==
                                            STATE_SPEAKING,
                                    activeSpokenText =
                                        activeTtsTextNormalized
                                )
                            } else {
                                AyanaCancelPhraseDetector.Match(
                                    matched = false
                                )
                            }

                        if (cancelMatch.matched) {

                            commandHistoryStore.addEvent(
                                activeCommandHistoryId,
                                state = "cancel_match",
                                message = "STOP распознан локальным детектором",
                                details =
                                    (
                                        "reason=${cancelMatch.reason}; " +
                                            "token=${cancelMatch.token}; " +
                                            "speaking=${currentStatusState == STATE_SPEAKING}; " +
                                            "heard=${cancelMatch.normalized.take(160)}"
                                        ).take(420)
                            )

                            isRecording =
                                false

                            pendingAction =
                                {
                                    cancelCurrentCommand(
                                        source = "voice"
                                    )
                                }

                            break
                        }

                        if (
                            isEndpoint
                        ) {

                            localRecognizer.reset(
                                stream
                            )
                        }
                    }

                    ListenMode.BUSY -> {

                        isRecording =
                            false

                        break
                    }
                }
            }

        } catch (_: Exception) {

            if (
                !shuttingDown &&
                sessionGeneration ==
                micGeneration
            ) {

                pendingAction =
                    when (listenMode) {

                        ListenMode.WAKE -> {

                            if (wakeSeen) {

                                {
                                    acknowledgeWakeAndListen()
                                }

                            } else {

                                {
                                    broadcastStatus(
                                        "Перезапускаю микрофон…",
                                        STATE_THINKING
                                    )

                                    mainHandler.postDelayed(
                                        {
                                            startWakeListening()
                                        },
                                        900L
                                    )
                                }
                            }
                        }

                        ListenMode.QUICK_COMMAND -> {

                            {
                                acknowledgeWakeAndListen()
                            }
                        }

                        ListenMode.COMMAND -> {

                            {
                                broadcastStatus(
                                    "Перезапускаю микрофон…",
                                    STATE_THINKING
                                )

                                mainHandler.postDelayed(
                                    {
                                        startCommandListening()
                                    },
                                    900L
                                )
                            }
                        }

                        ListenMode.FOLLOW_UP -> {

                            {
                                mainHandler.postDelayed(
                                    {
                                        startWakeListening()
                                    },
                                    500L
                                )
                            }
                        }

                        ListenMode.CANCEL -> {

                            if (
                                activeCommandHistoryId !=
                                null &&
                                !cancelRequested
                            ) {
                                {
                                    mainHandler.postDelayed(
                                        {
                                            startCancelListening()
                                        },
                                        250L
                                    )
                                }
                            } else {
                                null
                            }
                        }

                        ListenMode.BUSY ->
                            null
                    }
            }

        } finally {

            try {
                stream.release()
            } catch (_: Exception) {
            }

            cleanupRecorder(
                recorder
            )

            val action =
                pendingAction

            if (
                action != null &&
                !shuttingDown &&
                sessionGeneration ==
                micGeneration
            ) {

                mainHandler.post(
                    action
                )
            }
        }
    }

    private fun cleanupRecorder(
        recorder: AudioRecord
    ) {

        try {

            if (
                recorder.recordingState ==
                AudioRecord.RECORDSTATE_RECORDING
            ) {
                recorder.stop()
            }

        } catch (_: Exception) {
        }

        try {
            recorder.release()
        } catch (_: Exception) {
        }

        if (
            audioRecord === recorder
        ) {

            releaseCancelAudioEffects()

            audioRecord =
                null
        }

        isRecording =
            false

        if (
            Thread.currentThread() ===
            recordingThread
        ) {
            recordingThread =
                null
        }
    }

    private fun configureCancelAudioEffects(
        recorder: AudioRecord,
        enabled: Boolean
    ) {

        releaseCancelAudioEffects()

        if (
            !enabled
        ) {
            return
        }

        try {

            if (
                AcousticEchoCanceler.isAvailable()
            ) {

                cancelEchoCanceler =
                    AcousticEchoCanceler.create(
                        recorder.audioSessionId
                    )
                        ?.apply {
                            this.enabled =
                                true
                        }
            }

        } catch (_: Exception) {

            cancelEchoCanceler =
                null
        }

        try {

            if (
                NoiseSuppressor.isAvailable()
            ) {

                cancelNoiseSuppressor =
                    NoiseSuppressor.create(
                        recorder.audioSessionId
                    )
                        ?.apply {
                            this.enabled =
                                true
                        }
            }

        } catch (_: Exception) {

            cancelNoiseSuppressor =
                null
        }
    }

    private fun releaseCancelAudioEffects() {

        try {
            cancelEchoCanceler
                ?.release()
        } catch (_: Exception) {
        }

        try {
            cancelNoiseSuppressor
                ?.release()
        } catch (_: Exception) {
        }

        cancelEchoCanceler =
            null

        cancelNoiseSuppressor =
            null
    }

    private fun stopSherpaListening() {

        // Invalidate the old recording loop before stopping AudioRecord. Any
        // catch/finally from that obsolete loop must NOT restart WAKE and steal
        // the microphone from the dedicated CANCEL listener.
        micGeneration++

        isRecording =
            false

        val recorder =
            audioRecord

        if (recorder != null) {

            try {

                if (
                    recorder.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    recorder.stop()
                }

            } catch (_: Exception) {
            }
        }
    }

    private fun resumeCurrentListeningMode() {

        if (
            shuttingDown ||
            !modelReady
        ) {
            return
        }

        when (listenMode) {

            ListenMode.WAKE ->
                startWakeListening()

            ListenMode.QUICK_COMMAND ->
                startQuickCommandListening()

            ListenMode.COMMAND ->
                startCommandListening()

            ListenMode.FOLLOW_UP ->
                startFollowUpListening()

            ListenMode.CANCEL ->
                startCancelListening()

            ListenMode.BUSY ->
                Unit
        }
    }

    private fun normalizeRecognitionText(
        text: String
    ): String {

        return text
            .lowercase(
                Locale.getDefault()
            )
            .replace('ё', 'е')
            .replace(
                Regex("[,!?;:.]"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    private fun containsWakeWord(
        text: String
    ): Boolean {

        val normalized =
            normalizeRecognitionText(
                text
            )

        if (
            WAKE_VARIANTS.any {
                normalized.contains(it)
            }
        ) {
            return true
        }

        // The local STT occasionally returns a one-letter phonetic miss for
        // «Аяна» (for example «айна»/«яна»). Accept a fuzzy match only at
        // the beginning of the utterance, where a wake word belongs.
        return fuzzyWakePrefixLength(
            normalized
        ) > 0
    }

    private fun fuzzyWakePrefixLength(
        normalized: String
    ): Int {

        if (normalized.isBlank()) {
            return 0
        }

        val firstSpace =
            normalized.indexOf(' ')

        val firstToken =
            if (firstSpace >= 0) {
                normalized.substring(0, firstSpace)
            } else {
                normalized
            }

        if (isWakeLikeToken(firstToken)) {
            return firstToken.length
        }

        // Also support split recognition such as «а яна».
        if (firstSpace > 0) {
            val secondSpace =
                normalized.indexOf(
                    ' ',
                    firstSpace + 1
                )

            val prefixEnd =
                if (secondSpace >= 0) {
                    secondSpace
                } else {
                    normalized.length
                }

            val compactTwo =
                normalized
                    .substring(0, prefixEnd)
                    .replace(" ", "")

            if (isWakeLikeToken(compactTwo)) {
                return prefixEnd
            }
        }

        return 0
    }

    private fun isWakeLikeToken(
        token: String
    ): Boolean {

        if (token.length !in 3..6) {
            return false
        }

        val compactTargets =
            listOf(
                "аяна",
                "айана",
                "айяна",
                "ayana"
            )

        return compactTargets.any { target ->
            editDistanceAtMostOne(
                token,
                target
            )
        }
    }

    private fun editDistanceAtMostOne(
        left: String,
        right: String
    ): Boolean {

        if (left == right) {
            return true
        }

        val lengthDiff =
            kotlin.math.abs(
                left.length -
                    right.length
            )

        if (lengthDiff > 1) {
            return false
        }

        var i = 0
        var j = 0
        var edits = 0

        while (
            i < left.length &&
            j < right.length
        ) {

            if (left[i] == right[j]) {
                i++
                j++
                continue
            }

            edits++
            if (edits > 1) {
                return false
            }

            when {
                left.length > right.length ->
                    i++

                right.length > left.length ->
                    j++

                else -> {
                    i++
                    j++
                }
            }
        }

        if (
            i < left.length ||
            j < right.length
        ) {
            edits++
        }

        return edits <= 1
    }

    private fun extractWakeCommand(
        phrase: String
    ): String {

        var normalized =
            normalizeRecognitionText(
                phrase
            )

        // If exact variants did not survive STT but the first token is a
        // one-edit phonetic match, strip that fuzzy wake prefix too.
        val fuzzyPrefixLength =
            fuzzyWakePrefixLength(
                normalized
            )

        if (
            fuzzyPrefixLength > 0 &&
            !WAKE_VARIANTS.any {
                normalized.startsWith(it)
            }
        ) {
            normalized =
                normalized
                    .substring(
                        fuzzyPrefixLength
                    )
                    .trim()
                    .trimStart(
                        '-',
                        '—'
                    )
                    .trim()
        }

        var found =
            false

        do {

            found =
                false

            for (
                wake in
                WAKE_VARIANTS
            ) {

                val index =
                    normalized
                        .indexOf(wake)

                if (index >= 0) {

                    normalized =
                        normalized
                            .substring(
                                index +
                                    wake.length
                            )
                            .trim()
                            .trimStart(
                                '-',
                                '—'
                            )
                            .trim()

                    found =
                        true

                    break
                }
            }

        } while (
            found &&
            WAKE_VARIANTS.any {
                normalized
                    .startsWith(it)
            }
        )

        return normalized
            .trim()
    }

    // =========================================================
    // WAKE ACKNOWLEDGEMENT
    // =========================================================

    private fun prefetchReadyVoice() {

        if (
            readyFile.exists() &&
            readyFile.length() > 1000
        ) {
            return
        }

        thread(
            start = true,
            name = "AyanaReadyVoice"
        ) {
            try {

                downloadTtsToFile(
                    "Да?",
                    readyFile
                )

            } catch (_: Exception) {
            }
        }
    }

    private fun acknowledgeWakeAndListen() {

        stopSherpaListening()

        listenMode =
            ListenMode.BUSY

        broadcastStatus(
            "Аяна услышала вас",
            STATE_COMMAND
        )

        if (
            readyFile.exists() &&
            readyFile.length() > 1000
        ) {

            playFile(
                readyFile,
                deleteAfter = false
            ) {
                startFollowUpListening()
            }

        } else {

            try {

                val tone =
                    ToneGenerator(
                        AudioManager.STREAM_MUSIC,
                        65
                    )

                tone.startTone(
                    ToneGenerator.TONE_PROP_ACK,
                    110
                )

                mainHandler.postDelayed(
                    {
                        try {
                            tone.release()
                        } catch (_: Exception) {
                        }
                    },
                    160L
                )

            } catch (_: Exception) {
            }

            mainHandler.postDelayed(
                {
                    startFollowUpListening()
                },
                190L
            )
        }
    }

    // =========================================================
    // COMMANDS
    // =========================================================

    private fun executeCommand(
        originalCommand: String,
        silent: Boolean
    ) {

        // v12.9.0 MAIN-THREAD EXECUTION ISOLATION.
        // Voice endpoint and text-mode entrypoints both arrive through mainHandler.
        // Re-enter this same method from a serialized worker so deterministic routes
        // containing polling/sleeps/Accessibility IPC cannot trigger an Android ANR.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            val commandSnapshot =
                originalCommand

            thread(
                start = true,
                name = "AyanaCommandRouter"
            ) {
                synchronized(
                    commandDispatchLock
                ) {
                    if (!shuttingDown) {
                        executeCommand(
                            originalCommand = commandSnapshot,
                            silent = silent
                        )
                    }
                }
            }
            return
        }

        stopSherpaListening()

        listenMode =
            ListenMode.BUSY

        val normalized =
            sanitizeRoutingEnvelope(
                normalizeRecognitionText(
                    originalCommand
                )
                    .replace(
                        "пожалуйста",
                        ""
                    )
                    .replace(
                        Regex("\\s+"),
                        " "
                    )
                    .trim()
            )

        if (normalized.isBlank()) {

            if (silent) {
                showTextAndResume(
                    "Команда пустая."
                )
            } else {
                startWakeListening()
            }

            return
        }

        // ROUTING REPAIR v9.0
        // Never rewrite the user's stored/original command. Only the deterministic
        // local router receives a conservative repaired form for known Sherpa
        // distortions observed on the target tablet.
        val routingNormalized =
            sanitizeRoutingEnvelope(
                repairCommonRecognitionForRouting(
                    normalized
                )
            )

        if (
            handlePendingPreExecutionConfirmationInput(
                originalCommand = originalCommand,
                silent = silent
            )
        ) {
            return
        }

        if (
            isShutdownAyanaPhrase(
                normalized
            )
        ) {
            shutdownAyana()
            return
        }

        if (
            isCancelCommandPhrase(
                normalized
            ) &&
            !isDurableGoalCancelPhrase(
                routingNormalized
            )
        ) {

            if (
                activeCommandHistoryId !=
                null
            ) {
                cancelCurrentCommand(
                    source =
                        if (
                            silent
                        ) {
                            "text"
                        } else {
                            "voice"
                        }
                )
            } else {
                startWakeListening()
            }

            return
        }

        if (
            isLocalOrbControlCommand(
                normalized
            )
        ) {

            cancelRequested =
                false

            activeCommandToken =
                ++commandGeneration

            activeCommandHistoryId =
                commandHistoryStore.begin(
                    command =
                        originalCommand,
                    source =
                        if (
                            silent
                        ) {
                            "text"
                        } else {
                            "voice"
                        }
                )

            beginExecutionSession(
                objective = originalCommand,
                source = if (silent) "text" else "voice",
                lane = "local_control",
                executor = "orb_control_executor"
            )

            broadcastStatus(
                "Настраиваю Orb AYANA…",
                STATE_EXECUTING
            )

            ayanaPreferences.miniOrbEnabled =
                true

            if (
                miniOrbController.canDrawOverlays()
            ) {

                miniOrbController.refresh(
                    enabled = true,
                    state =
                        STATE_LISTENING
                )

                finishLocalCommand(
                    "Orb AYANA активен поверх всех окон",
                    silent
                )

            } else {

                respondAndResume(
                    "Для Orb нужно разрешение «Поверх других приложений».",
                    silent,
                    success = false
                )
            }

            return
        }

        cancelRequested =
            false

        activeCommandToken =
            ++commandGeneration

        activeCommandHistoryId =
            commandHistoryStore.begin(
                command = originalCommand,
                source = if (silent) "text" else "voice"
            )

        beginExecutionSession(
            objective = originalCommand,
            source = if (silent) "text" else "voice",
            lane = "command_router",
            executor = "deterministic_router"
        )

        capabilityRegistry
            .recordCommandContext(
                source =
                    if (silent) {
                        "text"
                    } else {
                        "voice"
                    },
                ttsExpected =
                    !silent
            )

        broadcastStatus(
            if (silent) {
                "Текст: $originalCommand"
            } else {
                "Выполняю: $originalCommand"
            },
            STATE_THINKING
        )

        if (
            isDurableGoalStatusPhrase(
                routingNormalized
            )
        ) {
            showDurableGoalStatus(
                silent
            )
            return
        }

        if (
            isDurableGoalCancelPhrase(
                routingNormalized
            )
        ) {
            cancelDurableGoalFromControl(
                silent
            )
            return
        }

        if (
            isDurableGoalConfirmPhrase(
                routingNormalized
            )
        ) {
            resumeDurableGoal(
                silent = silent,
                explicitConfirmation = true,
                allowAutoResume = false
            )
            return
        }

        if (
            isPreviousDurableGoalResumePhrase(
                routingNormalized
            )
        ) {
            val selected =
                durableGoalStore
                    .selectPreviousRecoverable()
            if (selected == null) {
                respondAndResume(
                    "Предыдущей незавершённой цели нет.",
                    silent,
                    success = false
                )
            } else {
                commandHistoryStore.addEvent(
                    activeCommandHistoryId,
                    state = "goal_selected",
                    message = "Выбрана предыдущая сохранённая цель",
                    details = selected.optString("command").take(500)
                )
                resumeDurableGoal(
                    silent = silent,
                    explicitConfirmation = false,
                    allowAutoResume = false
                )
            }
            return
        }

        if (
            isDurableGoalResumePhrase(
                routingNormalized
            )
        ) {
            resumeDurableGoal(
                silent = silent,
                explicitConfirmation = false,
                allowAutoResume = false
            )
            return
        }

        if (confirmedPreExecutionOriginal.isNotBlank()) {
            commandHistoryStore.addEvent(
                activeCommandHistoryId,
                state = "confirmation_granted",
                message = "Пользователь подтвердил ожидающее действие",
                details = confirmedPreExecutionOriginal.take(500)
            )
            confirmedPreExecutionOriginal =
                ""
        }

        // v12.11.2 UNIVERSAL PRE-EXECUTION GOAL GATE.
        // This is evaluated before any local executor. Data-only/quoted commands
        // terminate without dispatch; all other gated goals still pass Safety
        // before their executable clauses are preflighted.
        val rawPreExecutionDecision =
            AyanaCompositeIntentGate
                .analyze(
                    originalCommand
                )

        val constraintOverride =
            confirmedPreExecutionConstraints

        confirmedPreExecutionConstraints =
            null

        val preExecutionDecision =
            if (
                constraintOverride != null &&
                (
                    constraintOverride.forbidSettings ||
                    constraintOverride.forbidNetwork
                ) &&
                rawPreExecutionDecision.type ==
                    AyanaCompositeIntentGate.DecisionType.PASS_THROUGH
            ) {
                AyanaCompositeIntentGate.Decision(
                    type =
                        AyanaCompositeIntentGate.DecisionType.ENVIRONMENT_CONSTRAINED,
                    original = originalCommand,
                    executableCommand = originalCommand,
                    constraints = constraintOverride,
                    reason = "confirmed_constraint_continuation"
                )
            } else {
                rawPreExecutionDecision
            }

        if (
            preExecutionDecision.type ==
            AyanaCompositeIntentGate.DecisionType.DATA_ONLY
        ) {
            commandHistoryStore.addEvent(
                activeCommandHistoryId,
                state = "pre_execution_data_only",
                message = "Исполняемая фраза распознана только как данные",
                details = preExecutionDecision.reason.take(240)
            )
            finishLocalCommand(
                "Фраза принята только как данные. Действия на устройстве не выполнялись.",
                silent
            )
            return
        }

        // AUTONOMOUS CORE v10.2 — command-level local Safety gate.
        // Explicit attempts to type credentials are rejected BEFORE Agent Core,
        // so the protection remains effective even if Worker/model routing changes.
        val commandSafetyDecision =
            try {
                safetyPolicy
                    .evaluateUserCommand(
                        originalCommand
                    )
            } catch (_: Exception) {
                AyanaSafetyPolicy.Decision(
                    allowed = false,
                    requiresConfirmation = false,
                    riskLevel = AyanaSafetyPolicy.RISK_PROHIBITED,
                    riskName = "policy_error",
                    reason = "Локальный Safety Engine не смог надёжно проверить команду ввода."
                )
            }

        if (
            !commandSafetyDecision.allowed
        ) {
            commandHistoryStore.addEvent(
                activeCommandHistoryId,
                state = "safety_gate",
                message = commandSafetyDecision.riskName,
                details = commandSafetyDecision.reason.take(260)
            )

            commandHistoryStore.addEvent(
                activeCommandHistoryId,
                state = "safety_blocked",
                message = "Команда заблокирована локальным Safety Engine",
                details = "risk=${commandSafetyDecision.riskLevel}"
            )

            respondBlockedAndResume(
                text = commandSafetyDecision.reason,
                silent = silent,
                technical =
                    "local_command_safety_blocked:" +
                        commandSafetyDecision.riskName
            )
            return
        }

        // v12.14 NEGATIVE CAPABILITY TERMINAL TRUTH.
        // Known unavailable execution lanes fail locally as UNSUPPORTED instead of
        // spending an Agent Core turn and then recording a natural-language refusal
        // as SUCCESS. Source/patch generation remains supported and is not blocked.
        unsupportedExecutionCapabilityReason(
            originalCommand
        )
            ?.let { reason ->
                commandHistoryStore.addEvent(
                    activeCommandHistoryId,
                    state = "capability_execution_unsupported",
                    message = "Запрошен отсутствующий execution capability",
                    details = originalCommand.take(600)
                )
                respondUnsupportedAndResume(
                    text = reason,
                    silent = silent,
                    technical = "known_execution_capability_unavailable"
                )
                return
            }

        // v12.14 WHOLE-GOAL ROUTING GUARDS.
        // These are evaluated after Safety but before generic composite/local fast paths.
        // Their purpose is not to "special-case" brands; it is to preserve the user's
        // full terminal criterion when one clause would otherwise greedily consume it.
        extractVerifiedAppOpenRequest(
            originalCommand
        )
            ?.let { request ->
                val launchResolution =
                    try {
                        appResolver.resolve(request.first)
                    } catch (_: Exception) {
                        null
                    }

                val verificationResolution =
                    request.second
                        ?.takeIf { it.isNotBlank() }
                        ?.let { verifyName ->
                            try {
                                appResolver.resolve(verifyName)
                            } catch (_: Exception) {
                                null
                            }
                        }

                val sameTarget =
                    verificationResolution == null ||
                        (
                            launchResolution != null &&
                                launchResolution.success &&
                                verificationResolution.success &&
                                launchResolution.packageName.isNotBlank() &&
                                launchResolution.packageName == verificationResolution.packageName
                            )

                val wholeGoalCollapseAllowed =
                    sameTarget &&
                        preExecutionDecision.type !in
                            setOf(
                                AyanaCompositeIntentGate.DecisionType.DATA_ONLY,
                                AyanaCompositeIntentGate.DecisionType.INVALID,
                                AyanaCompositeIntentGate.DecisionType.REQUIRE_CONFIRMATION,
                                AyanaCompositeIntentGate.DecisionType.CONDITIONAL
                            ) &&
                        !preExecutionDecision.constraints.forbidNetwork &&
                        !(
                            preExecutionDecision.constraints.forbidSettings &&
                                launchResolution?.packageName == "com.android.settings"
                            )

                if (wholeGoalCollapseAllowed) {
                    commandHistoryStore.addEvent(
                        activeCommandHistoryId,
                        state = "whole_goal_lifecycle_verification",
                        message = "App launch + foreground verification collapsed to one verified lifecycle goal",
                        details =
                            "app=${request.first.take(160)}; verify=${request.second.orEmpty().take(160)}"
                    )
                    clearPendingLifecycleContext()
                    handleLocalAppLifecycleRequest(
                        action = "open",
                        requestedName = request.first,
                        silent = silent
                    )
                    return
                }
            }

        extractCompositeAppDetailFinalGoal(
            originalCommand
        )
            ?.takeIf {
                preExecutionDecision.type !in
                    setOf(
                        AyanaCompositeIntentGate.DecisionType.DATA_ONLY,
                        AyanaCompositeIntentGate.DecisionType.INVALID,
                        AyanaCompositeIntentGate.DecisionType.REQUIRE_CONFIRMATION,
                        AyanaCompositeIntentGate.DecisionType.CONDITIONAL
                    ) &&
                    !preExecutionDecision.constraints.forbidSettings
            }
            ?.let { goal ->
                commandHistoryStore.addEvent(
                    activeCommandHistoryId,
                    state = "whole_goal_app_detail",
                    message = "Составной Settings-маршрут свёрнут к конечной проверяемой цели",
                    details =
                        "app=${goal.appName.take(180)}; section=${goal.section}"
                )

                val result =
                    agentOpenAppSettings(
                        requestedName = goal.appName,
                        section = goal.section
                    )

                val message =
                    result.optString(
                        "message",
                        if (result.optBoolean("success", false)) {
                            "Открыт запрошенный раздел приложения ${goal.appName}."
                        } else {
                            "Не удалось подтвердить запрошенный раздел приложения ${goal.appName}."
                        }
                    )

                if (
                    result.optBoolean("success", false) &&
                    result.optBoolean("verified", true)
                ) {
                    finishLocalCommand(
                        message,
                        silent
                    )
                } else {
                    respondAndResume(
                        text = message,
                        silent = silent,
                        success = false,
                        terminalStatus =
                            when (result.optString("terminal_status").uppercase(Locale.ROOT)) {
                                "BLOCKED" -> AyanaCommandHistoryStore.STATUS_BLOCKED
                                "UNSUPPORTED" -> AyanaCommandHistoryStore.STATUS_UNSUPPORTED
                                else -> null
                            },
                        technical =
                            "whole_goal_app_detail_unverified:${result.optString("reason", result.optString("status"))}"
                    )
                }
                return
            }

        val requestedAggregateMetrics =
            extractRequestedAggregateMetrics(
                originalCommand
            )

        if (
            requestedAggregateMetrics.size >= 2 &&
            preExecutionDecision.type !in
                setOf(
                    AyanaCompositeIntentGate.DecisionType.DATA_ONLY,
                    AyanaCompositeIntentGate.DecisionType.INVALID,
                    AyanaCompositeIntentGate.DecisionType.REQUIRE_CONFIRMATION,
                    AyanaCompositeIntentGate.DecisionType.CONDITIONAL
                )
        ) {
            runLocalMultiMetricCommand(
                metrics = requestedAggregateMetrics,
                command = originalCommand,
                silent = silent
            )
            return
        }

        val explicitArtifactGoal =
            requestsArtifactDeliverable(
                originalCommand
            )

        if (
            explicitArtifactGoal &&
            shouldDelegateArtifactWholeGoalToAgent(
                preExecutionDecision
            )
        ) {
            commandHistoryStore.addEvent(
                activeCommandHistoryId,
                state = "artifact_whole_goal_handoff",
                message = "Запрошенный артефакт сохраняет владение всей исходной целью",
                details =
                    "pre_execution=${preExecutionDecision.type.name}; command=${originalCommand.take(420)}"
            )
            askAyana(
                originalCommand,
                silent
            )
            return
        }

        if (
            handlePreExecutionDecision(
                decision = preExecutionDecision,
                silent = silent
            )
        ) {
            return
        }

        extractLocalTextInputRequest(
            originalCommand
        )
            ?.let {
                request ->
                runLocalTextInputCommand(
                    request = request,
                    silent = silent
                )
                return
            }

        // v12.14 VOLUME TARGET PRECEDENCE.
        // A phrase such as «уменьши громкость до 2» names an absolute target,
        // not a -1 relative delta. Resolve exact target semantics before the
        // structured relative-volume router so History and post-write truth agree.
        extractExactMediaVolumeRequest(
            routingNormalized
        )
            ?.let { request ->
                runLocalExactMediaVolumeCommand(
                    request = request,
                    silent = silent
                )
                return
            }

        // v12.11 STRUCTURED LOCAL-FIRST ROUTER.
        // Typed arguments are resolved locally before generic lifecycle/Settings
        // or Agent Core fallback. This prevents command parameters such as
        // notification limit/app filter and relative volume delta from being
        // discarded after the top-level intent has been recognised.
        AyanaStructuredLocalCommandRouter
            .parse(
                originalCommand
            )
            ?.let { structuredIntent ->
                if (
                    runStructuredLocalCommand(
                        intent = structuredIntent,
                        silent = silent
                    )
                ) {
                    return
                }
            }

        // LOCAL CONVERSATION FAST-PATH v11.2
        // Trivial acknowledgements should not spend a network round-trip.
        // Durable-goal confirmation phrases were handled above, so «ок» here
        // cannot accidentally approve a waiting sensitive action.
        localAcknowledgementReply(
            routingNormalized
        )
            ?.let {
                reply ->
                respondAndResume(
                    reply,
                    silent,
                    success = true
                )
                return
            }

        // v12.13 LOCAL ACCEPTANCE ROUTER.
        // Requests to test AYANA herself are owned locally before the generic
        // diagnostics/self-review/Agent Core paths. This prevents the 2-3 cloud
        // turns observed in v12.12 for what should be deterministic local evidence.
        localAcceptanceTestMode(
            originalCommand
        )
            ?.let { acceptanceMode ->
                runLocalAcceptanceTestCommand(
                    mode = acceptanceMode,
                    silent = silent
                )
                return
            }

        // v12.9.0 LOCAL SELF-DIAGNOSTICS TRUTH.
        // Diagnostics must observe the previous Agent Core measurement, not create a
        // fresh Agent Core request first and overwrite the latency/error being measured.
        if (
            isExplicitSelfDiagnosticsRequest(
                routingNormalized
            )
        ) {
            runLocalSelfDiagnosticsCommand(
                silent = silent
            )
            return
        }

        // v12.12 LOCAL SELF-REVIEW / AUTONOMY TRUTH.
        // Questions about what AYANA herself still needs must not spend 15–20 s
        // waiting for a generic model answer that can forget already implemented
        // capabilities. Build the review from the live Capability Registry instead.
        if (
            isLocalSelfReviewOrAutonomyRequest(
                routingNormalized
            )
        ) {
            runLocalSelfReviewCommand(
                silent = silent
            )
            return
        }

        if (
            isLocalDeviceStateRequest(
                routingNormalized
            )
        ) {
            runLocalDeviceStateCommand(
                silent = silent
            )
            return
        }

        if (
            isLocalScreenStateRequest(
                routingNormalized
            )
        ) {
            runLocalScreenStateCommand(
                silent = silent
            )
            return
        }

        localSingleScrollDirection(
            routingNormalized
        )
            ?.let {
                direction ->
                runLocalSingleScrollCommand(
                    direction = direction,
                    silent = silent
                )
                return
            }

        // CAPABILITY TRUTH FAST-PATH v11.3 / v12.9.0 scope guard.
        // Only narrow media/document capability questions stay local. Higher-level
        // development/project questions (APK, GitHub, source code, AI agents, etc.)
        // must reach Agent Core for contextual reasoning.
        localCapabilityTruthReply(
            routingNormalized
        )
            ?.let {
                reply ->
                respondAndResume(
                    reply,
                    silent,
                    success = true
                )
                return
            }

        // GOOGLE IMAGES FAST-PATH v11.3
        // Obvious "show/find pictures" commands are deterministic browser
        // actions and must not spend 6-8 seconds on Agent Core + Planner.
        extractLocalImageSearchQuery(
            routingNormalized
        )
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                query ->
                openGoogleImageSearch(
                    query,
                    silent
                )
                return
            }

        // NETWORK TEST FAST-PATH v11.3
        // FAST.com starts measurement automatically. AYANA still does not claim
        // an Mbps result because current screen content is not reliably readable.
        if (
            isInternetSpeedTestRequest(
                routingNormalized
            )
        ) {
            openInternetSpeedTest(
                specificallyMobile =
                    routingNormalized.contains(
                        "мобиль"
                    ),
                silent =
                    silent
            )
            return
        }

        // v12.10.2 READ-ONLY NOTIFICATION ROUTER.
        // Reading recent notifications is a distinct capability from opening the
        // Android notification Settings screen. A missing listener permission is
        // BLOCKED and never substituted with a Settings SUCCESS.
        if (
            isRecentNotificationsReadRequest(
                routingNormalized
            )
        ) {
            runLocalRecentNotificationsCommand(
                silent = silent
            )
            return
        }

        // BASIC LOCAL CALCULATOR v8.9
        // Simple two-number arithmetic must not spend a network round-trip.
        val localCalculation =
            evaluateSimpleCalculation(
                routingNormalized
            )

        if (localCalculation != null) {
            respondAndResume(
                localCalculation,
                silent,
                success = true
            )
            return
        }

        // APP EXECUTION ROUTER v11.5
        // One deterministic path owns simple app open/minimize/close commands.
        // It also consumes a short local clarification context before any Agent Core
        // handoff. No command-specific YouTube patching is used here.
        val pendingLifecycleAction =
            extractPendingLifecycleFollowUpAction(
                routingNormalized
            )

        if (pendingLifecycleAction != null) {
            val pendingTarget =
                consumePendingLifecycleTarget()

            if (!pendingTarget.isNullOrBlank()) {
                handleLocalAppLifecycleRequest(
                    action = pendingLifecycleAction,
                    requestedName = pendingTarget,
                    silent = silent
                )
                return
            }
        } else {
            clearExpiredOrInterruptedLifecycleContext()
        }

        val lifecycleRequest =
            extractLocalAppLifecycleRequest(
                routingNormalized
            )

        if (lifecycleRequest != null) {
            clearPendingLifecycleContext()
            handleLocalAppLifecycleRequest(
                action = lifecycleRequest.first,
                requestedName = lifecycleRequest.second,
                silent = silent
            )
            return
        }

        val lifecycleClarificationTarget =
            extractLifecycleClarificationTarget(
                routingNormalized
            )

        if (!lifecycleClarificationTarget.isNullOrBlank()) {
            beginLocalLifecycleClarification(
                candidate = lifecycleClarificationTarget,
                silent = silent
            )
            return
        }

        // FAST APP DETAIL ROUTER v8.8
        // Common read-only app-settings destinations can be resolved locally even
        // when the user phrases them as two steps, e.g.
        // «открой настройки приложения YouTube и перейди в уведомления».
        // This avoids a network classifier round-trip for an already deterministic
        // Android destination. State-changing phrases are intentionally excluded.
        val fastAppDetailGoal =
            extractFastAppDetailGoal(
                routingNormalized
            )

        if (
            fastAppDetailGoal !=
            null
        ) {
            val appTarget =
                fastAppDetailGoal.first

            val section =
                fastAppDetailGoal.second

            val result =
                agentOpenAppSettings(
                    requestedName = appTarget,
                    section = section
                )

            val resultMessage =
                result.optString(
                    "message",
                    if (
                        result.optBoolean(
                            "success",
                            false
                        )
                    ) {
                        "Открываю параметры приложения $appTarget"
                    } else {
                        "Не удалось открыть параметры приложения $appTarget"
                    }
                )

            if (
                result.optBoolean(
                    "success",
                    false
                )
            ) {
                finishLocalCommand(
                    resultMessage,
                    silent
                )
            } else {
                respondAndResume(
                    resultMessage,
                    silent,
                    success = false
                )
            }

            return
        }

        // PLANNER HANDOFF v2.7.4.1
        // Fast local routes are only allowed to finish SINGLE-STEP commands.
        // Multi-step goals must reach Agent Core so the planner can continue
        // after the first Android screen instead of returning early.
        val multiStepRequest =
            isMultiStepAgentCommand(
                routingNormalized
            )

        // Direct local route for app-specific settings such as
        // «открой уведомления YouTube». This MUST run before the generic
        // «открой <app>» router, otherwise the whole phrase may be treated
        // as an application name (for example «уведомления ютуб»).
        val directAppSettingsTarget =
            extractDirectAppSettingsTarget(
                routingNormalized
            )

        if (
            directAppSettingsTarget != null &&
            !multiStepRequest
        ) {

            val section =
                directAppSettingsTarget.first

            val appTarget =
                directAppSettingsTarget.second

            val result =
                agentOpenAppSettings(
                    requestedName = appTarget,
                    section = section
                )

            val resultMessage =
                result
                    .optString(
                        "message",
                        if (
                            result.optBoolean(
                                "success",
                                false
                            )
                        ) {
                            "Открываю параметры приложения $appTarget"
                        } else {
                            "Не удалось открыть параметры приложения $appTarget"
                        }
                    )

            if (
                result.optBoolean(
                    "success",
                    false
                )
            ) {
                finishLocalCommand(
                    resultMessage,
                    silent
                )
            } else {
                respondAndResume(
                    resultMessage,
                    silent,
                    success = false
                )
            }

            return
        }

        // Direct local route for requests such as
        // «открой информацию о приложении Галерея».
        // This check MUST run before the generic «открой <app>» router,
        // otherwise voice commands may be mistaken for a normal app launch.
        val directAppInfoTarget =
            extractDirectAppInfoTarget(
                routingNormalized
            )

        // HYBRID APP SUBPAGE ROUTER v2.7.4.2
        // Common app-info subpages should not spend one Agent Core round-trip
        // merely deciding the obvious first/second step. Open App info locally,
        // then use semantic Accessibility navigation. If Samsung/Android layout
        // differs, fall back to Planner v2 instead of failing the whole task.
        val appInfoSubpageGoal =
            if (multiStepRequest) {
                extractAppInfoSubpageGoal(
                    routingNormalized
                )
            } else {
                null
            }

        if (
            appInfoSubpageGoal != null
        ) {

            val appTarget =
                appInfoSubpageGoal.first

            val subpage =
                appInfoSubpageGoal.second

            val openResult =
                agentOpenAppInfo(
                    appTarget
                )

            if (
                openResult.optBoolean(
                    "success",
                    false
                )
            ) {

                try {
                    Thread.sleep(
                        UI_SETTLE_DELAY_MS
                    )
                } catch (_: Exception) {
                }

                val subpageResult =
                    tryOpenAppInfoSubpageLocally(
                        subpage = subpage,
                        appTarget =
                            openResult.optString(
                                "label",
                                appTarget
                            )
                    )

                if (
                    subpageResult.optBoolean(
                        "success",
                        false
                    )
                ) {

                    val spokenTarget =
                        when (subpage) {

                            "permissions" ->
                                "разрешения приложения $appTarget"

                            "battery" ->
                                "использование батареи приложения $appTarget"

                            "storage" ->
                                "хранилище приложения $appTarget"

                            else ->
                                "нужный раздел приложения $appTarget"
                        }

                    finishLocalCommand(
                        "Открываю $spokenTarget",
                        silent
                    )

                    return
                }
            }

            // Layout can vary between One UI / Android versions. Planner v2 is
            // the safe fallback for unusual screens; never stop on App info.
            askAyana(
                originalCommand,
                silent
            )

            return
        }

        if (
            !directAppInfoTarget
                .isNullOrBlank() &&
            !multiStepRequest
        ) {

            val result =
                agentOpenAppInfo(
                    directAppInfoTarget
                )

            val resultMessage =
                result
                    .optString(
                        "message",
                        if (
                            result.optBoolean(
                                "success",
                                false
                            )
                        ) {
                            "Открываю информацию о приложении $directAppInfoTarget"
                        } else {
                            "Не удалось открыть информацию о приложении $directAppInfoTarget"
                        }
                    )

            if (
                result.optBoolean(
                    "success",
                    false
                )
            ) {
                finishLocalCommand(
                    resultMessage,
                    silent
                )
            } else {
                respondAndResume(
                    resultMessage,
                    silent,
                    success = false
                )
            }

            return
        }

        // HYBRID SETTINGS TARGET v2.7.4.5
        // Example: «открой настройки, зайди в приложения и найди YouTube».
        // This shortcut is intentionally limited to an EXPLICIT Apps-settings
        // route. A phrase such as «специальные возможности, найди AYANA AI среди
        // установленных приложений» belongs to Accessibility and must reach
        // Agent Core instead of being collapsed to ordinary App info.
        val settingsAppSearchTarget =
            extractSettingsAppSearchTarget(
                routingNormalized
            )

        if (
            !settingsAppSearchTarget
                .isNullOrBlank()
        ) {

            val result =
                agentOpenAppInfo(
                    settingsAppSearchTarget
                )

            if (
                result.optBoolean(
                    "success",
                    false
                )
            ) {
                finishLocalCommand(
                    result.optString(
                        "message",
                        "Открываю $settingsAppSearchTarget в настройках"
                    ),
                    silent
                )
                return
            }
            // If the direct resolver cannot find the app, do not fail early.
            // Fall through to Agent Core so Screen Intelligence can continue.
        }

        // FAST LOCAL ROUTER v2.7.4
        // Common Android settings commands must never wait for Agent Core.
        // Route them directly on-device before the generic command / app router.
        val directSystemSettingsSection =
            extractDirectSystemSettingsSection(
                routingNormalized
            )

        if (
            !directSystemSettingsSection
                .isNullOrBlank() &&
            !multiStepRequest
        ) {

            val result =
                agentOpenSettings(
                    directSystemSettingsSection
                )

            val resultMessage =
                result
                    .optString(
                        "message",
                        if (
                            result.optBoolean(
                                "success",
                                false
                            )
                        ) {
                            "Открываю настройки"
                        } else {
                            "Не удалось открыть настройки"
                        }
                    )

            if (
                result.optBoolean(
                    "success",
                    false
                )
            ) {
                finishLocalCommand(
                    resultMessage,
                    silent
                )
            } else {
                respondAndResume(
                    resultMessage,
                    silent,
                    success = false
                )
            }

            return
        }

        when {

            routingNormalized ==
                "назад" ||
                routingNormalized ==
                "вернись назад" -> {

                val result =
                    screenIntelligence
                        .pressBack()

                result.optJSONObject("screen")?.let { screen ->
                    capabilityRegistry.recordScreenObservation(screen)
                }

                if (result.optBoolean("success", false)) {
                    finishLocalCommand(
                        "Назад",
                        silent
                    )
                } else {
                    respondAndResume(
                        result.optString("message")
                            .ifBlank { "Не удалось выполнить Назад." },
                        silent,
                        success = false,
                        terminalStatus =
                            when (result.optString("terminal_status")) {
                                "BLOCKED" -> AyanaCommandHistoryStore.STATUS_BLOCKED
                                "UNSUPPORTED" -> AyanaCommandHistoryStore.STATUS_UNSUPPORTED
                                else -> null
                            }
                    )
                }

                return
            }

            routingNormalized ==
                "домой" ||
                routingNormalized ==
                "на главный экран" ||
                routingNormalized ==
                "главный экран" -> {

                val result =
                    screenIntelligence
                        .pressHome()

                result.optJSONObject("screen")?.let { screen ->
                    capabilityRegistry.recordScreenObservation(screen)
                }

                if (result.optBoolean("success", false)) {
                    finishLocalCommand(
                        "Главный экран",
                        silent
                    )
                } else {
                    respondAndResume(
                        result.optString("message")
                            .ifBlank { "Не удалось открыть главный экран." },
                        silent,
                        success = false,
                        terminalStatus =
                            when (result.optString("terminal_status")) {
                                "BLOCKED" -> AyanaCommandHistoryStore.STATUS_BLOCKED
                                "UNSUPPORTED" -> AyanaCommandHistoryStore.STATUS_UNSUPPORTED
                                else -> null
                            }
                    )
                }

                return
            }

            normalized ==
                "повтори" ||
                normalized ==
                "повтори ответ" -> {

                val lastAnswer =
                    conversationHistory
                        .lastOrNull()
                        ?.second

                if (
                    lastAnswer != null
                ) {
                    respondAndResume(
                        lastAnswer,
                        silent
                    )
                } else {
                    respondAndResume(
                        "Мне пока нечего повторять.",
                        silent
                    )
                }

                return
            }

            normalized ==
                "забудь разговор" ||
                normalized ==
                "очисти историю" -> {

                conversationHistory
                    .clear()

                agentPreviousResponseId =
                    null

                respondAndResume(
                    "Хорошо. История текущего разговора очищена.",
                    silent
                )

                return
            }

            normalized ==
                "очисти память" ||
                normalized ==
                "забудь все что помнишь" ||
                normalized ==
                "забудь всё что помнишь" ||
                normalized ==
                "очисти долговременную память" -> {

                val removed =
                    memoryStore.clear()

                agentPreviousResponseId =
                    null

                respondAndResume(
                    if (removed > 0) {
                        "Хорошо. Долговременная память очищена."
                    } else {
                        "Долговременная память уже пуста."
                    },
                    silent
                )

                return
            }

            isVolumeUpCommand(
                routingNormalized
            ) -> {

                changeVolume(
                    AudioManager.ADJUST_RAISE
                )

                finishLocalCommand(
                    "Громкость увеличена",
                    silent
                )

                return
            }

            isVolumeDownCommand(
                routingNormalized
            ) -> {

                changeVolume(
                    AudioManager.ADJUST_LOWER
                )

                finishLocalCommand(
                    "Громкость уменьшена",
                    silent
                )

                return
            }

            isMuteCommand(
                routingNormalized
            ) -> {

                changeVolume(
                    AudioManager.ADJUST_MUTE
                )

                finishLocalCommand(
                    "Звук выключен",
                    silent
                )

                return
            }

            isUnmuteCommand(
                routingNormalized
            ) -> {

                changeVolume(
                    AudioManager.ADJUST_UNMUTE
                )

                finishLocalCommand(
                    "Звук включён",
                    silent
                )

                return
            }

            (
                (
                    routingNormalized.contains("прокрути") ||
                        routingNormalized.contains("пролистай")
                    ) &&
                    (
                        routingNormalized.contains("экран") ||
                            routingNormalized.contains("страниц")
                        ) &&
                    routingNormalized.contains("до конца")
                ) -> {

                val direction =
                    if (
                        routingNormalized.contains("вверх") ||
                        routingNormalized.contains("наверх")
                    ) {
                        "up"
                    } else {
                        "down"
                    }

                executeLocalScrollToBoundary(
                    direction = direction,
                    silent = silent
                )

                return
            }

            normalized
                .startsWith(
                    "нажми "
                ) -> {

                val target =
                    normalized
                        .removePrefix(
                            "нажми "
                        )
                        .trim()

                clickByText(
                    target,
                    silent
                )

                return
            }

            normalized
                .startsWith(
                    "выбери "
                ) -> {

                val target =
                    normalized
                        .removePrefix(
                            "выбери "
                        )
                        .trim()

                clickByText(
                    target,
                    silent
                )

                return
            }

            (
                normalized.contains(
                    "ютуб"
                ) ||
                    normalized.contains(
                        "youtube"
                    )
                ) &&
                (
                    normalized.contains(
                        "найди "
                    ) ||
                        normalized.contains(
                            "ищи "
                        ) ||
                        normalized.contains(
                            "поищи "
                        ) ||
                        normalized.contains(
                            "поиск "
                        )
                    ) -> {

                val query =
                    extractYouTubeQuery(
                        normalized
                    )

                if (
                    query.isNotBlank()
                ) {

                    openYouTubeSearch(
                        query,
                        silent
                    )

                } else {

                    openApp(
                        "YouTube",
                        silent,
                        "com.google.android.youtube"
                    )
                }

                return
            }

            normalized
                .startsWith(
                    "найди в google "
                ) ||
                normalized
                    .startsWith(
                        "найди в гугле "
                    ) ||
                normalized
                    .startsWith(
                        "найди мне в google "
                    ) ||
                normalized
                    .startsWith(
                        "найди мне в гугле "
                    ) ||
                normalized
                    .startsWith(
                        "поищи в google "
                    ) ||
                normalized
                    .startsWith(
                        "поищи в гугле "
                    ) -> {

                val query =
                    if (
                        normalized.contains(
                            "google"
                        )
                    ) {

                        normalized
                            .substringAfter(
                                "google"
                            )
                            .trim()

                    } else {

                        normalized
                            .substringAfter(
                                "гугле"
                            )
                            .trim()
                    }

                if (
                    query.isNotBlank()
                ) {
                    openGoogleSearch(
                        query,
                        silent
                    )
                } else {
                    startWakeListening()
                }

                return
            }

            normalized
                .startsWith(
                    "найди на карте "
                ) ||
                normalized
                    .startsWith(
                        "найди мне на карте "
                    ) ||
                normalized
                    .startsWith(
                        "найди в картах "
                    ) ||
                normalized
                    .startsWith(
                        "покажи на карте "
                    ) ||
                normalized
                    .startsWith(
                        "покажи мне на карте "
                    ) -> {

                val query =
                    when {

                        normalized.startsWith(
                            "найди на карте "
                        ) ->
                            normalized
                                .removePrefix(
                                    "найди на карте "
                                )
                                .trim()

                        normalized.startsWith(
                            "найди мне на карте "
                        ) ->
                            normalized
                                .removePrefix(
                                    "найди мне на карте "
                                )
                                .trim()

                        normalized.startsWith(
                            "найди в картах "
                        ) ->
                            normalized
                                .removePrefix(
                                    "найди в картах "
                                )
                                .trim()

                        normalized.startsWith(
                            "покажи мне на карте "
                        ) ->
                            normalized
                                .removePrefix(
                                    "покажи мне на карте "
                                )
                                .trim()

                        else ->
                            normalized
                                .removePrefix(
                                    "покажи на карте "
                                )
                                .trim()
                    }

                if (
                    query.isNotBlank()
                ) {
                    openMapSearch(
                        query,
                        silent
                    )
                } else {
                    startWakeListening()
                }

                return
            }
        }

        val target =
            routingNormalized
                // Longest/natural launch phrases first. The resolver still validates
                // the final app against the real launcher map before any launch.
                .removePrefix(
                    "открой мне приложение "
                )
                .removePrefix(
                    "открой мне программу "
                )
                .removePrefix(
                    "запусти мне приложение "
                )
                .removePrefix(
                    "запусти мне программу "
                )
                .removePrefix(
                    "зайди в приложение "
                )
                .removePrefix(
                    "перейди в приложение "
                )
                .removePrefix(
                    "покажи приложение "
                )
                .removePrefix(
                    "открой приложение "
                )
                .removePrefix(
                    "запусти приложение "
                )
                .removePrefix(
                    "включи приложение "
                )
                .removePrefix(
                    "открой программу "
                )
                .removePrefix(
                    "запусти программу "
                )
                .removePrefix(
                    "включи программу "
                )
                .removePrefix(
                    "открой мне "
                )
                .removePrefix(
                    "запусти мне "
                )
                .removePrefix(
                    "открой "
                )
                .removePrefix(
                    "запусти "
                )
                .removePrefix(
                    "включи "
                )
                .trim()

        when (target) {

            "youtube",
            "ютуб" ->
                openApp(
                    "YouTube",
                    silent,
                    "com.google.android.youtube"
                )

            "chrome",
            "хром",
            "гугл хром" ->
                openApp(
                    "Chrome",
                    silent,
                    "com.android.chrome"
                )

            "браузер",
            "интернет",
            "самсунг интернет" ->
                openApp(
                    "браузер",
                    silent,
                    "com.sec.android.app.sbrowser",
                    "com.android.chrome"
                )

            "gmail",
            "джимейл",
            "почта",
            "электронная почта" ->
                openApp(
                    "почту",
                    silent,
                    "com.google.android.gm",
                    "com.samsung.android.email.provider"
                )

            "карты",
            "google maps",
            "гугл карты" ->
                openApp(
                    "Google Maps",
                    silent,
                    "com.google.android.apps.maps"
                )

            "play market",
            "play store",
            "плей маркет",
            "гугл плей" ->
                openApp(
                    "Google Play",
                    silent,
                    "com.android.vending"
                )

            "камера",
            "камеру" ->
                openApp(
                    "камеру",
                    silent,
                    "com.sec.android.app.camera"
                )

            "галерея",
            "галерею",
            "фото",
            "фотографии" ->
                openApp(
                    "галерею",
                    silent,
                    "com.sec.android.gallery3d",
                    "com.google.android.apps.photos"
                )

            "переводчик",
            "переводчика",
            "google переводчик",
            "гугл переводчик",
            "translate",
            "google translate" ->
                openApp(
                    "переводчик",
                    silent,
                    "com.google.android.apps.translate"
                )

            "google фото",
            "гугл фото" ->
                openApp(
                    "Google Фото",
                    silent,
                    "com.google.android.apps.photos",
                    "com.sec.android.gallery3d"
                )

            "файлы",
            "мои файлы" ->
                openApp(
                    "Мои файлы",
                    silent,
                    "com.sec.android.app.myfiles"
                )

            "калькулятор" ->
                openApp(
                    "калькулятор",
                    silent,
                    "com.sec.android.app.popupcalculator"
                )

            "календарь" ->
                openApp(
                    "календарь",
                    silent,
                    "com.samsung.android.calendar",
                    "com.google.android.calendar"
                )

            "часы",
            "будильник" ->
                openApp(
                    "часы",
                    silent,
                    "com.sec.android.app.clockpackage"
                )

            "сообщения",
            "смс" ->
                openApp(
                    "сообщения",
                    silent,
                    "com.samsung.android.messaging",
                    "com.google.android.apps.messaging"
                )

            "контакты" ->
                openApp(
                    "контакты",
                    silent,
                    "com.samsung.android.app.contacts",
                    "com.google.android.contacts"
                )

            "chatgpt",
            "chat gpt",
            "чат gpt",
            "чат гпт",
            "чат жпт",
            "чатгпт",
            "чатжпт",
            "чат джипити",
            "чат жипити",
            "чат джи пи ти",
            "джипити" ->
                openApp(
                    "ChatGPT",
                    silent,
                    "com.openai.chatgpt"
                )

            "telegram",
            "телеграм",
            "телеграмм",
            "телега",
            "телегу",
            "телеги" ->
                openApp(
                    "Telegram",
                    silent,
                    "org.telegram.messenger"
                )

            "whatsapp",
            "whats app",
            "ватсап",
            "вотсап",
            "вацап",
            "ватс апп",
            "вотс апп" ->
                openApp(
                    "WhatsApp",
                    silent,
                    "com.whatsapp"
                )

            "google",
            "гугл" ->
                openApp(
                    "Google",
                    silent,
                    "com.google.android.googlequicksearchbox"
                )

            "диск",
            "google диск",
            "гугл диск" ->
                openApp(
                    "Google Диск",
                    silent,
                    "com.google.android.apps.docs"
                )

            "заметки",
            "samsung notes",
            "самсунг ноутс" ->
                openApp(
                    "Samsung Notes",
                    silent,
                    "com.samsung.android.app.notes"
                )

            "настройки" ->
                openSystemScreen(
                    Settings.ACTION_SETTINGS,
                    "настройки",
                    silent
                )

            "wifi",
            "wi-fi",
            "вай фай",
            "вайфай" ->
                openSystemScreen(
                    Settings.ACTION_WIFI_SETTINGS,
                    "настройки Wi-Fi",
                    silent
                )

            "bluetooth",
            "блютуз" ->
                openSystemScreen(
                    Settings.ACTION_BLUETOOTH_SETTINGS,
                    "настройки Bluetooth",
                    silent
                )

            "звук",
            "настройки звука" ->
                openSystemScreen(
                    Settings.ACTION_SOUND_SETTINGS,
                    "настройки звука",
                    silent
                )

            "экран",
            "настройки экрана",
            "дисплей" ->
                openSystemScreen(
                    Settings.ACTION_DISPLAY_SETTINGS,
                    "настройки экрана",
                    silent
                )

            "специальные возможности",
            "спец возможности" ->
                openSystemScreen(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    "специальные возможности",
                    silent
                )

            "геолокация",
            "местоположение",
            "локация" ->
                openSystemScreen(
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                    "настройки местоположения",
                    silent
                )

            "безопасность",
            "настройки безопасности" ->
                openSystemScreen(
                    Settings.ACTION_SECURITY_SETTINGS,
                    "настройки безопасности",
                    silent
                )

            "дата и время",
            "время и дата" ->
                openSystemScreen(
                    Settings.ACTION_DATE_SETTINGS,
                    "настройки даты и времени",
                    silent
                )

            else -> {

                if (
                    isMultiStepAgentCommand(
                        normalized
                    )
                ) {

                    askAyana(
                        originalCommand,
                        silent
                    )

                } else if (
                    target in
                        KNOWN_LOCAL_LAUNCH_ALIASES
                ) {

                    // Exact known aliases stay on the zero-fuzz deterministic path.
                    openInstalledAppByName(
                        target,
                        silent
                    )

                } else if (
                    tryOpenPhoneticInstalledApp(
                        target = target,
                        wholeCommand = routingNormalized,
                        silent = silent
                    )
                ) {
                    // v12.5: a short ASR-distorted app name may stay local only
                    // after phonetic ranking AND device-observed package resolution.
                    Unit

                } else if (
                    isAppLaunchCommand(
                        routingNormalized
                    )
                ) {

                    // Explicit launches that are not safe phonetic matches still
                    // use the normal App Resolver and fail honestly if unresolved.
                    openInstalledAppByName(
                        target,
                        silent
                    )

                } else {

                    askAyana(
                        originalCommand,
                        silent
                    )
                }
            }
        }
    }

    private fun extractFastAppDetailGoal(
        command: String
    ): Pair<String, String>? {

        val section =
            detectAppDetailSection(
                command
            )
                ?: return null

        // Goal-integrity guard: this fast path is ONLY for navigation to a
        // read-only app-details destination. If anything actionable follows the
        // requested section (for example «...в разрешения и нажми Камера» or
        // «...в уведомления и выключи их»), the complete command must go to
        // Planner/Task Engine instead of returning a partial local SUCCESS.
        val sectionPattern =
            appDetailSectionRegex(
                section
            )
                ?: return null

        val lastSectionMatch =
            Regex(
                "(?:" +
                    sectionPattern +
                    ")"
            )
                .findAll(
                    command
                )
                .lastOrNull()
                ?: return null

        val trailingAfterSection =
            command
                .substring(
                    lastSectionMatch
                        .range
                        .last +
                        1
                )
                .trim(
                    ' ',
                    '.',
                    ',',
                    '!',
                    '?',
                    ':',
                    ';',
                    '«',
                    '»',
                    '"',
                    '\''
                )

        if (
            trailingAfterSection
                .isNotBlank()
        ) {
            return null
        }

        // Strongest multi-step form:
        // «открой информацию о приложении Chrome и зайди в разрешения».
        // Reuse the app-info extractor because it already removes the trailing
        // navigation clause and preserves the app name exactly.
        val appFromInfo =
            extractDirectAppInfoTarget(
                command
            )

        if (
            !appFromInfo
                .isNullOrBlank()
        ) {
            return appFromInfo to section
        }

        // «открой настройки приложения Chrome и перейди в уведомления»
        // «покажи параметры приложения Gmail, затем открой разрешения»
        val settingsPattern =
            Regex(
                """(?:(?:открой|покажи|зайди\s+в|перейди\s+в)\s+)?(?:настройк\p{L}*|параметр\p{L}*)\s+приложени\p{L}*\s+(.+?)(?:\s+и\s+|\s+(?:потом|затем|после\s+этого)\s+)(?:перейди|зайди|открой|покажи)?\s*(?:в\s+)?"""
            )

        val settingsMatch =
            settingsPattern.find(
                command
            )

        val appFromSettings =
            settingsMatch
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .let(
                    ::cleanExtractedAppTarget
                )

        if (
            appFromSettings
                .isNotBlank()
        ) {
            return appFromSettings to section
        }

        // Natural two-step shorthand:
        // «открой Chrome и зайди в разрешения».
        val launchThenDetail =
            Regex(
                """^(?:(?:открой|запусти|включи|покажи)(?:\s+мне)?\s+)(?:приложени\p{L}*\s+|программ\p{L}*\s+)?(.+?)(?:\s+и\s+|\s+(?:потом|затем|после\s+этого)\s+)(?:перейди|зайди|открой|покажи)\s+(?:в\s+)?"""
            )
                .find(
                    command
                )

        val appFromLaunch =
            launchThenDetail
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()
                .let(
                    ::cleanExtractedAppTarget
                )

        if (
            appFromLaunch
                .isNotBlank()
        ) {
            return appFromLaunch to section
        }

        return null
    }

    private fun extractDirectAppSettingsTarget(
        command: String
    ): Pair<String, String>? {

        val section =
            detectAppDetailSection(
                command
            )
                ?: return null

        val sectionPattern =
            appDetailSectionRegex(
                section
            )
                ?: return null

        val actionPrefix =
            """(?:(?:(?:открой|покажи)(?:\s+мне)?|зайди\s+в|перейди\s+в)\s+)?"""

        val settingsPrefix =
            """(?:(?:настройк\p{L}*|параметр\p{L}*)\s+)?"""

        val patterns =
            listOf(
                // «открой разрешения Chrome»
                // «открой язык приложения Chrome»
                Regex(
                    "^" +
                        actionPrefix +
                        settingsPrefix +
                        "(?:" +
                        sectionPattern +
                        ")" +
                        """(?:\s+(?:для|у|в|во))?(?:\s+приложени\p{L}*)?\s+(.+)$"""
                ),

                // «открой разрешения для приложения Chrome»
                Regex(
                    "^" +
                        actionPrefix +
                        settingsPrefix +
                        "(?:" +
                        sectionPattern +
                        ")" +
                        """\s+(?:для|у|в|во)\s+приложени\p{L}*\s+(.+)$"""
                ),

                // «открой у Chrome разрешения»
                // «перейди в Chrome в разрешения»
                Regex(
                    "^" +
                        actionPrefix +
                        settingsPrefix +
                        """(?:для|у|в|во)\s+(.+?)\s+(?:в\s+)?(?:""" +
                        sectionPattern +
                        ")$"
                ),

                // «открой настройки приложения Chrome разрешения»
                Regex(
                    "^" +
                        actionPrefix +
                        settingsPrefix +
                        """приложени\p{L}*\s+(.+?)\s+(?:""" +
                        sectionPattern +
                        ")$"
                )
            )

        for (pattern in patterns) {

            val match =
                pattern.find(
                    command
                )
                    ?: continue

            val target =
                match
                    .groupValues
                    .getOrNull(1)
                    .orEmpty()
                    .let(
                        ::cleanExtractedAppTarget
                    )

            if (
                target
                    .isNotBlank()
            ) {
                return section to target
            }
        }

        return null
    }

    private fun detectAppDetailSection(
        command: String
    ): String? {

        val c =
            command
                .lowercase(
                    Locale.ROOT
                )
                .replace('ё', 'е')
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        return when {

            c.contains(
                "разрешен"
            ) ->
                "permissions"

            c.contains(
                "мобильные дан"
            ) ||
                c.contains(
                    "мобильный трафик"
                ) ||
                c.contains(
                    "использование дан"
                ) ||
                c.contains(
                    "расход трафика"
                ) ->
                "mobile_data"

            c.contains(
                "батаре"
            ) ||
                c.contains(
                    "аккумулятор"
                ) ||
                c.contains(
                    "энергопотреб"
                ) ->
                "battery"

            c.contains(
                "хранилищ"
            ) ||
                c.contains(
                    "памят"
                ) ||
                c.contains(
                    "кэш"
                ) ||
                c.contains(
                    "кеш"
                ) ->
                "storage"

            c.contains(
                "уведомлен"
            ) ->
                "notifications"

            c.contains(
                "по умолчани"
            ) ||
                c.contains(
                    "открытие ссыл"
                ) ||
                c.contains(
                    "открывать ссыл"
                ) ->
                "open_by_default"

            c.contains(
                "язык"
            ) ||
                c.contains(
                    "локаль прилож"
                ) ->
                "language"

            else ->
                null
        }
    }

    private fun appDetailSectionRegex(
        section: String
    ): String? {

        return when (
            section
        ) {

            "permissions" ->
                """разрешен\p{L}*"""

            "battery" ->
                """(?:(?:(?:использовани|расход)\p{L}*\s+)?батаре\p{L}*|аккумулятор\p{L}*|энергопотреблени\p{L}*)"""

            "storage" ->
                """(?:хранилищ\p{L}*|памят\p{L}*|кэш\p{L}*|кеш\p{L}*)"""

            "mobile_data" ->
                """(?:мобильн\p{L}*\s+данн\p{L}*|мобильн\p{L}*\s+трафик\p{L}*|использовани\p{L}*\s+данн\p{L}*|расход\p{L}*\s+трафик\p{L}*)"""

            "notifications" ->
                """уведомлен\p{L}*"""

            "open_by_default" ->
                """(?:(?:открыти\p{L}*\s+)?по\s+умолчани\p{L}*|открывать\s+по\s+умолчани\p{L}*|открыти\p{L}*\s+ссыл\p{L}*|открывать\s+ссыл\p{L}*)"""

            "language" ->
                """(?:язык\p{L}*|локал\p{L}*)"""

            else ->
                null
        }
    }

    private fun cleanExtractedAppTarget(
        value: String
    ): String {

        var result =
            value
                .trim()
                .removePrefix(
                    "приложения "
                )
                .removePrefix(
                    "приложение "
                )
                .removePrefix(
                    "для "
                )
                .removePrefix(
                    "про "
                )
                .trim()

        val tailMarkers =
            listOf(
                " и зайди ",
                " и перейди ",
                " и открой ",
                " и покажи ",
                " потом ",
                " затем ",
                " после этого ",
                " открой раздел ",
                " перейди в раздел ",
                " зайди в раздел "
            )

        val tailIndex =
            tailMarkers
                .map { marker ->
                    result.indexOf(
                        marker
                    )
                }
                .filter { index ->
                    index > 0
                }
                .minOrNull()

        if (
            tailIndex !=
            null
        ) {
            result =
                result
                    .substring(
                        0,
                        tailIndex
                    )
                    .trim()
        }

        return result
            .trim(
                '"',
                '\'',
                '«',
                '»',
                '“',
                '”',
                '.',
                ',',
                '!',
                '?',
                ':',
                ';'
            )
            .trim()
    }

    private fun extractDirectAppInfoTarget(
        command: String
    ): String? {

        val patterns =
            listOf(
                Regex(
                    """(?:информац\p{L}*|сведени\p{L}*|инфо)\s+(?:(?:о|об|про|и)\s+)?приложени\p{L}*\s+(.+)"""
                ),
                // Natural explicit app-settings form. Requiring the word
                // «приложение» keeps this separate from global settings such as
                // «настройки Wi-Fi» or «параметры батареи».
                Regex(
                    """^(?:(?:открой|покажи)(?:\s+мне)?\s+)?(?:настройк\p{L}*|параметр\p{L}*)\s+приложени\p{L}*\s+(.+)"""
                )
            )

        val match =
            patterns
                .firstNotNullOfOrNull { pattern ->
                    pattern.find(
                        command
                    )
                }
                ?: return null

        var target =
            match
                .groupValues
                .getOrNull(1)
                .orEmpty()
                .trim()
                .removePrefix(
                    "для "
                )
                .removePrefix(
                    "про "
                )
                .trim()

        // In a multi-step phrase the regex above also sees the trailing goal,
        // e.g. «YouTube зайди в использование батареи». Keep only app name.
        val tailMarkers =
            listOf(
                " и зайди ",
                " и перейди ",
                " и открой ",
                " зайди ",
                " перейди ",
                " потом ",
                " затем ",
                " после этого ",
                " остановись ",
                " открой раздел "
            )

        val tailIndex =
            tailMarkers
                .map { marker ->
                    target.indexOf(
                        marker
                    )
                }
                .filter { index ->
                    index > 0
                }
                .minOrNull()

        if (tailIndex != null) {
            target =
                target
                    .substring(
                        0,
                        tailIndex
                    )
                    .trim()
        }

        target =
            target.trim(
                '"',
                '\'',
                '«',
                '»',
                '.',
                ',',
                '!',
                '?'
            )

        return target
            .takeIf {
                it.isNotBlank()
            }
    }

    private fun extractAppInfoSubpageGoal(
        command: String
    ): Pair<String, String>? {

        val appTarget =
            extractDirectAppInfoTarget(
                command
            )
                ?: return null

        val subpage =
            detectAppDetailSection(
                command
            )
                ?: return null

        return appTarget to subpage
    }

    private fun tryOpenAppInfoSubpageLocally(
        subpage: String,
        appTarget: String
    ): JSONObject {

        val targets =
            appDetailClickTargets(
                subpage
            )

        if (
            targets.isEmpty()
        ) {
            return JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "message",
                    "Локальная цель подстраницы не определена"
                )
        }

        var lastResult =
            JSONObject()
                .put(
                    "success",
                    false
                )
                .put(
                    "message",
                    "Подстраница пока не подтверждена"
                )

        repeat(4) { attempt ->

            val screenBefore =
                try {
                    screenIntelligence
                        .getScreenState()
                } catch (_: Exception) {
                    JSONObject()
                }

            val normalizedScreen =
                normalizeVerificationText(
                    screenVerificationTextForInteraction(
                        screenBefore
                    )
                )

            val visibleTarget =
                targets.firstOrNull { target ->
                    normalizedScreen.contains(
                        normalizeVerificationText(
                            target
                        )
                    )
                }

            val candidates =
                linkedSetOf<String>()
                    .apply {
                        if (
                            visibleTarget !=
                            null
                        ) {
                            add(
                                visibleTarget
                            )
                        }

                        // v12.8.11: always try canonical Settings row labels before
                        // scrolling. Screen Intelligence v4.4 remains fail-closed and
                        // can recover an exact visible row through Android's native
                        // Accessibility text provider even when Samsung omits it from
                        // the serialized snapshot. This avoids moving the wrong pane
                        // before giving the factual current viewport a chance.
                        addAll(
                            targets
                        )
                    }

            for (
                target in
                candidates
            ) {

                val clickResult =
                    screenIntelligence
                        .click(
                            target = target,
                            confirmed = false
                        )

                lastResult =
                    clickResult

                if (
                    clickResult.optBoolean(
                        "requires_confirmation",
                        false
                    )
                ) {
                    return clickResult
                }

                val clickAccepted =
                    clickResult.optBoolean(
                        "success",
                        false
                    )

                val screenChanged =
                    clickResult.optBoolean(
                        "screen_changed",
                        false
                    )

                if (
                    clickAccepted ||
                    screenChanged
                ) {

                    val verification =
                        awaitVerifiedAppDetailScreen(
                            appTarget = appTarget,
                            section = subpage,
                            timeoutMs = APP_DETAIL_VERIFY_TIMEOUT_MS
                        )

                    // v12.10.2 Samsung verified continuation. The App Info page
                    // was already strictly proven before this helper is entered.
                    // Some One UI subpages (especially Permissions) omit the app
                    // label from the same accessibility context even though the
                    // destination semantic surface is strongly proven. Accept that
                    // narrow continuation only after a real click/screen change and
                    // a fresh Settings semantic surface; never use it for a direct
                    // unproven Settings launch.
                    val semanticContinuation =
                        if (
                            verification.optBoolean(
                                "success",
                                false
                            )
                        ) {
                            JSONObject()
                                .put(
                                    "success",
                                    false
                                )
                        } else {
                            verifyAppDetailSemanticContinuation(
                                screen =
                                    verification.optJSONObject(
                                        "screen"
                                    )
                                        ?: JSONObject(),
                                section = subpage
                            )
                        }

                    if (
                        verification.optBoolean(
                            "success",
                            false
                        ) ||
                        semanticContinuation.optBoolean(
                            "success",
                            false
                        )
                    ) {
                        val verifiedScreen =
                            verification.optJSONObject(
                                "screen"
                            )
                                ?: semanticContinuation
                                    .optJSONObject(
                                        "screen"
                                    )

                        return JSONObject(
                            clickResult.toString()
                        ).apply {
                            put(
                                "success",
                                true
                            )
                            put(
                                "local_goal_reached",
                                true
                            )
                            put(
                                "verified",
                                true
                            )
                            put(
                                "verification_mode",
                                if (
                                    semanticContinuation.optBoolean(
                                        "success",
                                        false
                                    )
                                ) {
                                    "verified_app_info_semantic_continuation"
                                } else {
                                    "same_context_app_label_and_marker"
                                }
                            )
                            put(
                                "screen",
                                verifiedScreen
                            )
                            put(
                                "message",
                                "Локальная подстраница подтверждена: $target"
                            )
                        }
                    }
                }
            }

            if (
                attempt <
                3
            ) {

                val scrollResult =
                    screenIntelligence
                        .scroll(
                            "down"
                        )

                lastResult =
                    scrollResult

                try {
                    Thread.sleep(
                        120L
                    )
                } catch (_: Exception) {
                }
            }
        }

        return JSONObject(
            lastResult.toString()
        ).apply {
            put(
                "success",
                false
            )
            put(
                "verified",
                false
            )
            put(
                "message",
                "Нужная подстраница не подтверждена по фактическому экрану"
            )
        }
    }

    private fun appDetailClickTargets(
        section: String
    ): List<String> {

        return when (
            section
        ) {

            "permissions" ->
                listOf(
                    "Разрешения",
                    "Permissions"
                )

            "battery" ->
                listOf(
                    "Батарея",
                    "Использование батареи",
                    "Аккумулятор",
                    "Battery"
                )

            "storage" ->
                listOf(
                    "Хранилище",
                    "Память",
                    "Storage"
                )

            "mobile_data" ->
                listOf(
                    "Мобильные данные",
                    "Использование мобильных данных",
                    "Использование данных",
                    "Мобильный трафик",
                    "Mobile data",
                    "Data usage"
                )

            "notifications" ->
                listOf(
                    "Уведомления",
                    "Notifications"
                )

            "open_by_default" ->
                listOf(
                    "Использование по умолчанию",
                    "По умолчанию",
                    "Открытие ссылок",
                    "Open by default",
                    "Opening links"
                )

            "language" ->
                listOf(
                    "Язык",
                    "Languages",
                    "Language"
                )

            else ->
                emptyList()
        }
    }

    private fun appDetailVerificationMarkers(
        section: String
    ): List<String> {

        return when (
            section
        ) {

            "permissions" ->
                listOf(
                    "разрешен",
                    "permission"
                )

            "battery" ->
                listOf(
                    "батаре",
                    "аккумулятор",
                    "battery"
                )

            "storage" ->
                listOf(
                    "хранилищ",
                    "память",
                    "storage"
                )

            "mobile_data" ->
                listOf(
                    "мобильн",
                    "использование дан",
                    "расход трафик",
                    "mobile data",
                    "data usage"
                )

            "notifications" ->
                listOf(
                    "уведомлен",
                    "notification"
                )

            "open_by_default" ->
                listOf(
                    "по умолчани",
                    "открытие ссыл",
                    "поддерживаем",
                    "open by default",
                    "opening links",
                    "supported links"
                )

            "language" ->
                listOf(
                    "язык",
                    "языки прилож",
                    "language",
                    "app languages"
                )

            "info" ->
                listOf(
                    "информация о приложении",
                    "сведения о приложении",
                    "app info"
                )

            else ->
                emptyList()
        }
    }

    private fun normalizeVerificationText(
        value: String
    ): String {

        return value
            .lowercase(
                Locale.ROOT
            )
            .replace('ё', 'е')
            .replace(
                Regex("[^a-zа-я0-9\\s]"),
                " "
            )
            .replace(
                Regex("\\s+"),
                " "
            )
            .trim()
    }

    /**
     * Preserve an already-proven Settings owner across Samsung transient window-list gaps.
     * This helper never infers foreground from an Intent: it is called only after the caller
     * has verified the actual app-detail screen.
     */
    private fun attestVerifiedSettingsOwnerFromScreen(
        screen: JSONObject,
        source: String
    ): Boolean {

        val windows =
            screen.optJSONArray("windows")
                ?: return false

        var best: JSONObject? = null
        var bestScore = Int.MIN_VALUE

        for (index in 0 until windows.length()) {
            val window = windows.optJSONObject(index) ?: continue

            if (window.optString("package") != "com.android.settings") {
                continue
            }

            val evidenceAge =
                window.optLong("evidence_age_ms", -1L)

            val freshEvidence =
                evidenceAge in 0L..SETTINGS_ATTESTATION_EVIDENCE_MAX_AGE_MS

            val provenContext =
                window.optBoolean("interaction_context", false) ||
                    window.optBoolean("active", false) ||
                    window.optBoolean("focused", false) ||
                    freshEvidence

            if (!provenContext) {
                continue
            }

            var score = 0
            if (window.optBoolean("interaction_context", false)) score += 100
            if (window.optBoolean("focused", false)) score += 80
            if (window.optBoolean("active", false)) score += 60
            if (freshEvidence) score += 40
            if (window.optString("semantic_surface").isNotBlank()) score += 20

            if (score > bestScore) {
                best = window
                bestScore = score
            }
        }

        val provenWindow =
            best
                ?: return false

        return AgentAccessibilityService
            .attestVerifiedForegroundOwner(
                ownerPackage = "com.android.settings",
                windowId = provenWindow.optInt("window_id", -1),
                source = source
            )
    }

    private fun verifyAppDetailSemanticContinuation(
        screen: JSONObject,
        section: String
    ): JSONObject {
        if (!screen.optBoolean("success", false)) {
            return JSONObject()
                .put("success", false)
        }

        val expectedSurface =
            when (section) {
                "notifications" -> "app_notifications"
                "permissions" -> "app_permissions"
                "battery" -> "app_battery"
                "storage" -> "app_storage"
                "open_by_default" -> "app_defaults"
                else -> ""
            }

        if (expectedSurface.isBlank()) {
            return JSONObject()
                .put("success", false)
        }

        val windows =
            screen.optJSONArray("windows")
                ?: return JSONObject()
                    .put("success", false)

        for (index in 0 until windows.length()) {
            val window =
                windows.optJSONObject(index)
                    ?: continue

            if (window.optString("package") != "com.android.settings") {
                continue
            }

            val factualContext =
                window.optBoolean("interaction_context", false) ||
                    window.optBoolean("active", false) ||
                    window.optBoolean("focused", false)

            if (!factualContext) {
                continue
            }

            if (window.optString("semantic_surface") != expectedSurface) {
                continue
            }

            val confidence =
                window.optInt(
                    "semantic_surface_confidence",
                    0
                )

            if (confidence < 80) {
                continue
            }

            val evidenceAge =
                window.optLong(
                    "evidence_age_ms",
                    -1L
                )

            if (
                evidenceAge >= 0L &&
                evidenceAge > SETTINGS_ATTESTATION_EVIDENCE_MAX_AGE_MS
            ) {
                continue
            }

            val windowId =
                window.optInt(
                    "window_id",
                    -1
                )

            AgentAccessibilityService
                .attestVerifiedForegroundOwner(
                    ownerPackage = "com.android.settings",
                    windowId = windowId,
                    source = "app_info_semantic_continuation"
                )

            return JSONObject()
                .put("success", true)
                .put("verified", true)
                .put("surface", expectedSurface)
                .put("confidence", confidence)
                .put("evidence_age_ms", evidenceAge)
                .put("settings_window_id", windowId)
                .put("screen", screen)
        }

        // Samsung large-screen Settings can render the factual detail pane as a
        // sibling application window with an empty package while the left pane
        // remains the active/focused com.android.settings owner. This function
        // is called only after verified App Info + an accepted local row action,
        // so a fresh semantic marker in that sibling pane is valid continuation
        // evidence and must stop any fallback scrolling immediately.
        val ownerWindow =
            (0 until windows.length())
                .mapNotNull { index ->
                    windows.optJSONObject(
                        index
                    )
                }
                .firstOrNull { window ->
                    window.optString(
                        "package"
                    ) ==
                        "com.android.settings" &&
                        (
                            window.optBoolean(
                                "interaction_context",
                                false
                            ) ||
                                window.optBoolean(
                                    "active",
                                    false
                                ) ||
                                window.optBoolean(
                                    "focused",
                                    false
                                )
                            )
                }

        if (ownerWindow != null) {
            val markers =
                appDetailVerificationMarkers(
                    section
                )
                    .map {
                        normalizeVerificationText(
                            it
                        )
                    }
                    .filter {
                        it.isNotBlank()
                    }

            for (index in 0 until windows.length()) {
                val window =
                    windows.optJSONObject(
                        index
                    )
                        ?: continue

                if (
                    window.optString(
                        "type_name"
                    ) ==
                    "input_method"
                ) {
                    continue
                }

                val candidateText =
                    buildString {
                        append(
                            window.optString(
                                "title"
                            )
                        )
                        append(
                            " "
                        )
                        append(
                            window.optString(
                                "verification_text"
                            )
                        )

                        val visible =
                            window.optJSONArray(
                                "visible_text"
                            )

                        if (visible != null) {
                            for (
                                itemIndex in
                                0 until visible.length()
                            ) {
                                append(
                                    " "
                                )
                                append(
                                    visible.optString(
                                        itemIndex
                                    )
                                )
                            }
                        }
                    }

                val normalizedCandidate =
                    normalizeVerificationText(
                        candidateText
                    )

                val markerFound =
                    markers.any { marker ->
                        normalizedCandidate.contains(
                            marker
                        )
                    }

                if (!markerFound) {
                    continue
                }

                val ownerWindowId =
                    ownerWindow.optInt(
                        "window_id",
                        -1
                    )

                AgentAccessibilityService
                    .attestVerifiedForegroundOwner(
                        ownerPackage =
                            "com.android.settings",
                        windowId =
                            ownerWindowId,
                        source =
                            "app_info_split_pane_semantic_continuation"
                    )

                return JSONObject()
                    .put(
                        "success",
                        true
                    )
                    .put(
                        "verified",
                        true
                    )
                    .put(
                        "surface",
                        expectedSurface
                    )
                    .put(
                        "confidence",
                        85
                    )
                    .put(
                        "settings_window_id",
                        ownerWindowId
                    )
                    .put(
                        "detail_window_id",
                        window.optInt(
                            "window_id",
                            -1
                        )
                    )
                    .put(
                        "verification_mode",
                        "verified_app_info_split_pane_marker"
                    )
                    .put(
                        "screen",
                        screen
                    )
            }
        }

        return JSONObject()
            .put("success", false)
    }

    private fun isVerifiedAppDetailScreen(
        screen: JSONObject,
        appTarget: String,
        section: String
    ): Boolean {

        if (!screen.optBoolean("success", false)) {
            return false
        }

        val markers =
            appDetailVerificationMarkers(section)

        if (markers.isEmpty()) {
            return false
        }

        val normalizedMarkers =
            markers
                .map { normalizeVerificationText(it) }
                .filter { it.isNotBlank() }

        val normalizedApp =
            normalizeVerificationText(appTarget)

        val contexts =
            appDetailVerificationContexts(screen)

        if (contexts.isEmpty()) {
            return false
        }

        return contexts.any { contextText ->
            val normalizedContext =
                normalizeVerificationText(contextText)

            if (normalizedContext.isBlank()) {
                return@any false
            }

            val markerFound =
                normalizedMarkers.any { marker ->
                    normalizedContext.contains(marker)
                }

            if (!markerFound) {
                return@any false
            }

            val appFound =
                normalizedApp.isBlank() ||
                    normalizedContext.contains(normalizedApp)

            if (!appFound) {
                return@any false
            }

            if (section != "info") {
                val stillOnAppInfo =
                    normalizedContext.contains("информация о приложении") ||
                        normalizedContext.contains("сведения о приложении") ||
                        normalizedContext.contains("app info")

                if (stillOnAppInfo) {
                    return@any false
                }
            }

            true
        }
    }

    private fun screenVerificationTextForInteraction(
        screen: JSONObject
    ): String {

        val windows = screen.optJSONArray("windows")
        if (windows != null) {
            val values = mutableListOf<String>()
            var hasInteractionContext = false

            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                if (window.optBoolean("interaction_context", false)) {
                    hasInteractionContext = true
                    val verification =
                        window.optString("verification_text").trim()
                    if (verification.isNotBlank()) {
                        values.add(verification)
                    }
                }
            }

            if (hasInteractionContext) {
                return values.joinToString(" | ")
            }
        }

        return screen.optString("verification_text")
            .ifBlank {
                val visible = screen.optJSONArray("visible_text")
                buildString {
                    if (visible != null) {
                        for (index in 0 until visible.length()) {
                            val value = visible.optString(index).trim()
                            if (value.isBlank()) continue
                            if (isNotEmpty()) append(" | ")
                            append(value)
                        }
                    }
                }
            }
    }

    /**
     * Never prove a terminal screen by combining words from unrelated visible
     * windows. Window Context Manager v1 exposes one visible_text array per
     * context; marker + app label must coexist inside the same context.
     */
    private fun appDetailVerificationContexts(
        screen: JSONObject
    ): List<String> {

        val result =
            mutableListOf<String>()

        val windows =
            screen.optJSONArray("windows")

        if (windows != null) {
            var hasInteractionContext = false
            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue
                if (window.optBoolean("interaction_context", false)) {
                    hasInteractionContext = true
                    break
                }
            }

            val structuredWindowContext =
                screen.optString("window_context_mode").isNotBlank()

            // New Window Context snapshots fail closed if no interaction window
            // was selected. Never fall back to combining all visible windows.
            if (structuredWindowContext && !hasInteractionContext) {
                return emptyList()
            }

            for (index in 0 until windows.length()) {
                val window = windows.optJSONObject(index) ?: continue

                if (
                    hasInteractionContext &&
                    !window.optBoolean("interaction_context", false)
                ) {
                    continue
                }

                val typeName =
                    window.optString("type_name")

                if (typeName == "input_method") {
                    continue
                }

                val occlusion =
                    window.optDouble("occlusion_ratio", 0.0)

                if (occlusion >= 0.95) {
                    continue
                }

                val text =
                    window.optString("verification_text").trim()

                if (text.isNotBlank()) {
                    result.add(text)
                }
            }
        }

        if (result.isNotEmpty()) {
            return result
        }

        // Backward-compatible fallback for old snapshots. New v3.6 snapshots
        // should normally take the structured branch above.
        val fallback =
            screen.optString("verification_text")
                .ifBlank {
                    val visible = screen.optJSONArray("visible_text")
                    buildString {
                        if (visible != null) {
                            for (index in 0 until visible.length()) {
                                val value = visible.optString(index).trim()
                                if (value.isBlank()) continue
                                if (isNotEmpty()) append(" | ")
                                append(value)
                            }
                        }
                    }
                }

        return if (fallback.isBlank()) emptyList() else listOf(fallback)
    }

    private fun appDetailVerificationDiagnosticSummary(
        screen: JSONObject
    ): String {

        if (!screen.optBoolean("success", false)) {
            return "screen_success=false"
        }

        val windows = screen.optJSONArray("windows")
            ?: return (
                "legacy_snapshot; package=${screen.optString("package")}; " +
                    "visible=${screen.optJSONArray("visible_text")?.toString().orEmpty().take(500)}"
                ).take(700)

        return buildString {
            append("primary=")
            append(screen.optString("primary_context_id"))
            append("; owner=")
            append(screen.optString("foreground_owner_package"))
            append(" owner_wid=")
            append(screen.optInt("foreground_owner_window_id", -1))
            append(" owner_age=")
            append(screen.optLong("foreground_owner_age_ms", -1L))
            append(" owner_source=")
            append(screen.optString("foreground_owner_source"))
            append(" event_pkg=")
            append(screen.optString("event_package"))
            append(" event_wid=")
            append(screen.optInt("event_window_id", -1))
            append("; windows=")
            append(windows.length())

            val limit = minOf(windows.length(), 5)
            for (index in 0 until limit) {
                val window = windows.optJSONObject(index) ?: continue
                append(" || ")
                append(window.optString("context_id"))
                append(" pkg=")
                append(window.optString("package"))
                append(" type=")
                append(window.optString("type_name"))
                append(" layer=")
                append(window.optInt("layer", 0))
                append(" active=")
                append(window.optBoolean("active", false))
                append(" focused=")
                append(window.optBoolean("focused", false))
                append(" evidence_age=")
                append(window.optLong("evidence_age_ms", -1L))
                append(" content=")
                append(window.optString("content_state"))
                append(" live=")
                append(window.optInt("live_readable_text_count", 0))
                append(" evidence=")
                append(window.optInt("evidence_readable_text_count", 0))
                append(" acq=")
                append(window.optString("acquisition_source"))
                append(" surface=")
                append(window.optString("semantic_surface"))
                append(" surface_conf=")
                append(window.optInt("semantic_surface_confidence", 0))
                append(" title=")
                append(window.optString("title").take(120))
                append(" text=")
                append(
                    window.optJSONArray("visible_text")
                        ?.toString()
                        .orEmpty()
                        .take(260)
                )
            }
        }.take(1800)
    }

    private fun awaitVerifiedAppDetailScreen(
        appTarget: String,
        section: String,
        timeoutMs: Long
    ): JSONObject {

        val deadline =
            System.currentTimeMillis() +
                timeoutMs
                    .coerceAtLeast(
                        0L
                    )

        var latest =
            JSONObject()
                .put(
                    "success",
                    false
                )

        do {

            latest =
                try {
                    screenIntelligence
                        .getScreenState()
                } catch (_: Exception) {
                    JSONObject()
                        .put(
                            "success",
                            false
                        )
                }

            if (
                isVerifiedAppDetailScreen(
                    screen = latest,
                    appTarget = appTarget,
                    section = section
                )
            ) {
                attestVerifiedSettingsOwnerFromScreen(
                    screen = latest,
                    source = "verified_app_detail_screen"
                )

                return JSONObject()
                    .put(
                        "success",
                        true
                    )
                    .put(
                        "verified",
                        true
                    )
                    .put(
                        "screen",
                        latest
                    )
            }

            if (
                System.currentTimeMillis() >=
                deadline
            ) {
                break
            }

            try {
                Thread.sleep(
                    APP_DETAIL_VERIFY_POLL_MS
                )
            } catch (_: InterruptedException) {
                Thread.currentThread()
                    .interrupt()
                break
            }

        } while (
            !cancelRequested &&
            !shuttingDown
        )

        return JSONObject()
            .put(
                "success",
                false
            )
            .put(
                "verified",
                false
            )
            .put(
                "screen",
                latest
            )
    }

    private fun verifySettingsIntentAttestation(
        screen: JSONObject,
        targetPackage: String,
        section: String,
        dispatchedAtMs: Long
    ): JSONObject {
        if (targetPackage.isBlank() || !screen.optBoolean("success", false)) {
            return JSONObject().put("success", false)
        }

        val dispatchAge =
            (System.currentTimeMillis() - dispatchedAtMs)
                .coerceAtLeast(0L)

        if (dispatchAge > APP_DETAIL_VERIFY_TIMEOUT_MS + 1500L) {
            return JSONObject()
                .put("success", false)
                .put("reason", "intent_attestation_stale")
        }

        val expectedSurface =
            when (section) {
                "info" -> setOf("app_info", "app_info_structure")
                "notifications" -> setOf("app_notifications")
                "permissions" -> setOf("app_permissions")
                "battery" -> setOf("app_battery")
                "storage" -> setOf("app_storage")
                "open_by_default" -> setOf("app_defaults")
                else -> emptySet()
            }

        if (expectedSurface.isEmpty()) {
            return JSONObject()
                .put("success", false)
                .put("reason", "surface_not_attestable")
        }

        val windows = screen.optJSONArray("windows") ?: JSONArray()

        for (index in 0 until windows.length()) {
            val window = windows.optJSONObject(index) ?: continue
            if (
                window.optString("package") != "com.android.settings" ||
                !(window.optBoolean("active", false) || window.optBoolean("focused", false))
            ) {
                continue
            }

            val surface = window.optString("semantic_surface").trim()
            val confidence = window.optInt("semantic_surface_confidence", 0)
            val evidenceAge = window.optLong("evidence_age_ms", -1L)

            if (surface !in expectedSurface) {
                continue
            }

            val structuralOnly = surface == "app_info_structure"
            val evidenceFreshEnough =
                !structuralOnly ||
                    (evidenceAge in 0L..SETTINGS_ATTESTATION_EVIDENCE_MAX_AGE_MS)

            val threshold = if (structuralOnly) 70 else 80

            if (confidence >= threshold && evidenceFreshEnough) {
                val settingsWindowId =
                    window.optInt("window_id", -1)

                AgentAccessibilityService
                    .attestVerifiedForegroundOwner(
                        ownerPackage = "com.android.settings",
                        windowId = settingsWindowId,
                        source = "settings_intent_attestation"
                    )

                return JSONObject()
                    .put("success", true)
                    .put("verified", true)
                    .put("surface", surface)
                    .put("confidence", confidence)
                    .put("evidence_age_ms", evidenceAge)
                    .put("dispatch_age_ms", dispatchAge)
                    .put("settings_window_id", settingsWindowId)
                    .put("foreground_owner_handoff", true)
                    .put("target_package", targetPackage)
                    .put("section", section)
                    .put("verification_mode", "exact_intent_plus_same_window_semantic_surface")
            }
        }

        return JSONObject()
            .put("success", false)
            .put("reason", "semantic_surface_not_proven")
            .put("target_package", targetPackage)
            .put("section", section)
    }

    private fun extractSettingsAppSearchTarget(
        command: String
    ): String? {

        val c =
            command
                .lowercase(
                    Locale.getDefault()
                )
                .replace('ё', 'е')
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        // ACCESSIBILITY ROUTE GUARD v2.7.4.5
        // The word «приложения» is not enough to mean Settings > Apps.
        // Accessibility screens also contain phrases such as
        // «установленные приложения/службы». Those tasks must stay with
        // Planner + Screen Intelligence so AYANA can reach the service page.
        val accessibilityContext =
            listOf(
                "специальные возможност",
                "спец возможност",
                "accessibility",
                "установленные службы",
                "установленных служб",
                "служба специальных возможностей"
            ).any { marker ->
                c.contains(marker)
            }

        if (accessibilityContext) {
            return null
        }

        // Only collapse a command to App info when the user explicitly
        // describes the normal Settings > Apps route. Merely mentioning an
        // installed application somewhere in a longer goal is not sufficient.
        val explicitAppsRoute =
            listOf(
                "зайди в приложени",
                "зайти в приложени",
                "перейди в приложени",
                "перейти в приложени",
                "открой приложени",
                "открыть приложени",
                "раздел приложени",
                "настройки приложени",
                "список приложени"
            ).any { marker ->
                c.contains(marker)
            }

        if (!explicitAppsRoute) {
            return null
        }

        val markers =
            listOf(
                "найди приложение ",
                "найти приложение ",
                "найди ",
                "найти ",
                "поищи ",
                "выбери "
            )

        var bestIndex =
            -1

        var bestMarker =
            ""

        for (marker in markers) {
            val index =
                c.lastIndexOf(marker)

            if (
                index > bestIndex ||
                (
                    index == bestIndex &&
                    marker.length > bestMarker.length
                )
            ) {
                bestIndex =
                    index
                bestMarker =
                    marker
            }
        }

        if (bestIndex < 0) {
            return null
        }

        var target =
            c
                .substring(
                    bestIndex +
                        bestMarker.length
                )
                .trim()

        // Keep only the app name when the command continues with another goal.
        val tailMarkers =
            listOf(
                " и останов",
                " останов",
                " и зайди ",
                " и перейди ",
                " потом ",
                " затем ",
                " после этого ",
                " и открой "
            )

        val tailIndex =
            tailMarkers
                .map { marker ->
                    target.indexOf(marker)
                }
                .filter { index ->
                    index >= 0
                }
                .minOrNull()
                ?: -1

        if (tailIndex >= 0) {
            target =
                target
                    .substring(
                        0,
                        tailIndex
                    )
                    .trim()
        }

        target =
            target
                .trim(
                    '"',
                    '\'',
                    '«',
                    '»',
                    '.',
                    ',',
                    '!',
                    '?'
                )

        if (
            target.isBlank() ||
            target in
                setOf(
                    "приложение",
                    "приложения",
                    "нужное приложение"
                )
        ) {
            return null
        }

        return target
    }

    private fun unsupportedExecutionCapabilityReason(
        command: String
    ): String? {
        val c =
            command
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("\\s+"), " ")
                .trim()

        if (c.isBlank()) {
            return null
        }

        val informationalPrefix =
            listOf(
                "почему ",
                "зачем ",
                "как ",
                "что ",
                "какие ",
                "расскажи ",
                "объясни ",
                "можешь ли ",
                "умеешь ли "
            ).any { c.startsWith(it) }

        if (informationalPrefix) {
            return null
        }

        val githubWrite =
            (c.contains("github") || c.contains("гитхаб")) &&
                listOf(
                    "измени",
                    "изменить",
                    "запиши",
                    "записать",
                    "обнови",
                    "обновить",
                    "загрузи",
                    "загрузить",
                    "удали",
                    "удалить",
                    "commit",
                    "коммит",
                    "push",
                    "пуш"
                ).any { c.contains(it) }

        val commitPush =
            (
                c.contains("commit") ||
                    c.contains("коммит") ||
                    c.contains("push") ||
                    c.contains("пуш")
                ) &&
                listOf(
                    "сделай",
                    "сделать",
                    "выполни",
                    "выполнить",
                    "запусти",
                    "запустить",
                    "отправь",
                    "отправить"
                ).any { c.contains(it) }

        val apkBuildOrDelivery =
            (c.contains("apk") || c.contains("апк")) &&
                (
                    c.contains("собери") ||
                        c.contains("собрать") ||
                        c.contains("сборк") ||
                        c.contains("подпиши") ||
                        c.contains("подписать") ||
                        c.contains("готовый apk") ||
                        c.contains("готовый апк") ||
                        c.contains("дай мне apk") ||
                        c.contains("дай мне апк") ||
                        c.contains("передай apk") ||
                        c.contains("передай апк")
                    )

        if (!githubWrite && !commitPush && !apkBuildOrDelivery) {
            return null
        }

        val unavailable =
            mutableListOf<String>()

        if (githubWrite) {
            unavailable += "запись изменений в GitHub"
        }
        if (commitPush) {
            unavailable += "commit/push"
        }
        if (apkBuildOrDelivery) {
            unavailable += "сборка/подписание/выдача готового APK"
        }

        return "Эта задача сейчас не может быть выполнена напрямую: в AYANA нет ${unavailable.distinct().joinToString(", ")}. " +
                "Я могу подготовить исходники или патч, но не буду отмечать отсутствующие repository/build действия как выполненные."
    }

    private data class DirectAppDetailFinalGoal(
        val appName: String,
        val section: String
    )

    /**
     * v12.14: A verification suffix does not create a second side effect.
     * "Открой Камеру и проверь, что на переднем плане Камера" is one app-open
     * goal whose existing lifecycle executor already verifies the fresh package.
     */
    private fun extractVerifiedAppOpenRequest(
        command: String
    ): Pair<String, String?>? {
        val c =
            normalizeLifecycleRoutingText(
                command
            )

        val match =
            Regex(
                """^(?:открой|открыть|запусти|запустить|включи|включить)(?:\s+мне)?\s+(?:приложени\p{L}*\s+|программ\p{L}*\s+)?(.+?)\s+и\s+(?:проверь|проверить|убедись|убедиться|подтверди|подтвердить)(?=\s|[,.:;!?—-]|$)(.*)$"""
            )
                .find(c)
                ?: return null

        val target =
            match.groupValues
                .getOrNull(1)
                .orEmpty()
                .trim()

        val verificationTail =
            match.groupValues
                .getOrNull(2)
                .orEmpty()
                .trim()

        if (
            target.isBlank() ||
            verificationTail.isBlank() ||
            target in LIFECYCLE_INVALID_TARGETS
        ) {
            return null
        }

        val foregroundIntent =
            verificationTail.contains("передн") &&
                (
                    verificationTail.contains("план") ||
                        verificationTail.contains("экран")
                    ) ||
                verificationTail.contains("foreground")

        if (!foregroundIntent) {
            return null
        }

        val verifyTarget =
            Regex(
                """(?:^|[,\s])(?:что\s+)?(?:на\s+)?(?:передн\p{L}*\s+(?:план\p{L}*|экран\p{L}*)|foreground)(?:\s+сейчас)?(?:\s+именно)?(?:\s+(?:находится|открыт[ао]?|это))?\s+(.+)$"""
            )
                .find(verificationTail)
                ?.groupValues
                ?.getOrNull(1)
                ?.trim()
                ?.trim(' ', '"', '«', '»', '.', ',', '!', '?')
                ?.takeIf { it.isNotBlank() }

        return target to verifyTarget
    }

    /**
     * Collapses a safe read-only Settings>Apps path to its observable final target.
     * This preserves the user's goal while avoiding a brittle literal navigation route.
     */
    private fun extractCompositeAppDetailFinalGoal(
        command: String
    ): DirectAppDetailFinalGoal? {
        val appTarget =
            extractSettingsAppSearchTarget(
                command
            )
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return null

        val c =
            command
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("\\s+"), " ")
                .trim()

        // Require an explicit continuation to a final app-detail section.
        if (
            !c.contains(" и ") &&
            !c.contains(" затем ") &&
            !c.contains(" потом ") &&
            !c.contains(" после этого ")
        ) {
            return null
        }

        val section =
            when {
                c.contains("разрешен") -> "permissions"
                c.contains("батаре") || c.contains("аккумулятор") -> "battery"
                c.contains("хранилищ") || c.contains("памят") -> "storage"
                c.contains("мобильн") && c.contains("данн") -> "mobile_data"
                c.contains("уведомлен") -> "notifications"
                c.contains("открыт") && c.contains("по умолч") -> "open_by_default"
                c.contains("ссылк") && c.contains("по умолч") -> "open_by_default"
                c.contains("язык") -> "language"
                c.contains("информац") || c.contains("сведени") -> "info"
                else -> null
            }
                ?: return null

        return DirectAppDetailFinalGoal(
            appName = appTarget,
            section = section
        )
    }

    private enum class AggregateMetric {
        BATTERY,
        NETWORK,
        STORAGE,
        MEDIA_VOLUME,
        BRIGHTNESS,
        ORIENTATION
    }

    /**
     * Detect read-only multi-metric requests before the single-metric structured router.
     * A state-changing or artifact-producing goal is deliberately excluded.
     */
    private fun extractRequestedAggregateMetrics(
        command: String
    ): Set<AggregateMetric> {
        if (requestsArtifactDeliverable(command)) {
            return emptySet()
        }

        val c =
            command
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("\\s+"), " ")
                .trim()

        val readIntent =
            listOf(
                "проверь",
                "покажи",
                "скажи",
                "какой",
                "какая",
                "каково",
                "сколько",
                "состояние",
                "статус"
            ).any { c.contains(it) }

        if (!readIntent) {
            return emptySet()
        }

        val stateChangingMarkers =
            listOf(
                "установи",
                "установить",
                "открой",
                "открыть",
                "запусти",
                "запустить",
                "зайди",
                "зайти",
                "перейди",
                "перейти",
                "нажми",
                "нажать",
                "выбери",
                "выбрать",
                "найди",
                "найти",
                "создай",
                "создать",
                "сделай",
                "сделать",
                "измени",
                "изменить",
                "увелич",
                "уменьш",
                "включи",
                "выключи",
                "закрой",
                "сверни",
                "удали",
                "очисти"
            )

        if (stateChangingMarkers.any { c.contains(it) }) {
            return emptySet()
        }

        val result =
            linkedSetOf<AggregateMetric>()

        if (c.contains("батар") || c.contains("заряд") || c.contains("аккумулятор")) {
            result += AggregateMetric.BATTERY
        }

        if (
            c.contains("интернет") ||
            c.contains("подключен") ||
            c.contains("сеть") ||
            c.contains("wi-fi") ||
            c.contains("wifi")
        ) {
            result += AggregateMetric.NETWORK
        }

        if (c.contains("хранилищ") || c.contains("свободн") && c.contains("мест")) {
            result += AggregateMetric.STORAGE
        }

        if (c.contains("громкост")) {
            result += AggregateMetric.MEDIA_VOLUME
        }

        if (c.contains("яркост")) {
            result += AggregateMetric.BRIGHTNESS
        }

        if (c.contains("ориентац") || c.contains("альбомн") || c.contains("портретн")) {
            result += AggregateMetric.ORIENTATION
        }

        return result
    }

    /**
     * Completion ownership gate for aggregate device reads.
     *
     * The local executor is intentionally conservative: it terminal-completes only a
     * presentation-only request whose meaningful tokens are covered by read/metric/summary
     * vocabulary. Any other semantic residue is treated as an unfinished goal and is handed
     * to Agent Core with the already verified snapshot. This is safer than maintaining a
     * growing list of special phrases such as "если" or "сделай вывод".
     */
    private fun aggregateMetricHasRemainingSemanticGoal(
        command: String
    ): Boolean {
        val normalized =
            command
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("[^\\p{L}\\p{N}%]+"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        if (normalized.isBlank()) {
            return false
        }

        val neutralWords =
            setOf(
                "и", "или", "а", "но", "в", "во", "на", "из", "к", "ко", "с", "со",
                "по", "о", "об", "для", "мне", "мой", "моем", "моём", "этом", "этого",
                "сейчас", "теперь", "ли", "есть", "между", "все", "всё"
            )

        val presentationRoots =
            listOf(
                "пров", "покаж", "ска", "како", "сколь", "состоя", "статус",
                "устрой", "планш", "парамет", "показател",
                "заряд", "батар", "аккумуля",
                "интернет", "подключ", "сет", "wifi", "вайф",
                "хранил", "свобод", "мест", "памят",
                "громк", "мультимед", "ярк", "ориент", "альбом", "портрет",
                "три", "трех", "трём", "трем", "два", "двум", "перв", "втор", "один",
                "затем", "потом", "после", "дай", "общ", "еди", "результ", "итог",
                "текущ", "параметр"
            )

        return normalized
            .split(' ')
            .filter { it.isNotBlank() }
            .any { token ->
                if (
                    token in neutralWords ||
                    token.toDoubleOrNull() != null ||
                    token.endsWith("%") && token.dropLast(1).toDoubleOrNull() != null
                ) {
                    false
                } else {
                    presentationRoots.none { root ->
                        token.startsWith(root)
                    }
                }
            }
    }

    private fun verifiedAggregateFactsPayload(
        metrics: Set<AggregateMetric>,
        state: JSONObject,
        summary: String
    ): String {
        val facts = JSONObject()
            .put("provenance", "android_local_verified_snapshot")
            .put("summary", summary)
            .put("requested_metrics", JSONArray(metrics.map { it.name }))

        fun copyIfPresent(key: String) {
            if (state.has(key)) {
                facts.put(key, state.opt(key))
            }
        }

        listOf(
            "battery_percent",
            "charging",
            "network_connected",
            "network_validated",
            "network_transport",
            "storage_free_bytes",
            "storage_total_bytes",
            "media_volume",
            "media_volume_max",
            "brightness_percent",
            "orientation"
        ).forEach(::copyIfPresent)

        return facts.toString()
    }

    private fun runLocalMultiMetricCommand(
        metrics: Set<AggregateMetric>,
        command: String,
        silent: Boolean
    ) {
        executionPhase(
            phase = "local_multi_device_metric",
            executor = "multi_device_metric_executor"
        )

        val state =
            try {
                agentGetDeviceState()
            } catch (_: Exception) {
                JSONObject()
            }

        val parts =
            mutableListOf<String>()

        val missing =
            mutableListOf<String>()

        for (metric in metrics) {
            when (metric) {
                AggregateMetric.BATTERY -> {
                    val battery = state.optInt("battery_percent", -1)
                    if (battery >= 0) {
                        parts +=
                            "заряд батареи $battery%" +
                                if (state.optBoolean("charging", false)) " (заряжается)" else ""
                    } else {
                        missing += "battery"
                    }
                }

                AggregateMetric.NETWORK -> {
                    if (state.has("network_connected")) {
                        val connected = state.optBoolean("network_connected", false)
                        val validated = state.optBoolean("network_validated", false)
                        val transport = state.optString("network_transport", "unknown")
                        parts +=
                            if (connected) {
                                "интернет подключён" +
                                    (
                                        if (validated) {
                                            " и подтверждён Android"
                                        } else {
                                            " (доступ в интернет не подтверждён)"
                                        }
                                    ) +
                                    (
                                        if (
                                            transport.isNotBlank() &&
                                            transport != "unknown" &&
                                            transport != "none"
                                        ) {
                                            " через $transport"
                                        } else {
                                            ""
                                        }
                                    )
                            } else {
                                "интернет не подключён"
                            }
                    } else {
                        missing += "network"
                    }
                }

                AggregateMetric.STORAGE -> {
                    val free = state.optLong("storage_free_bytes", -1L)
                    val total = state.optLong("storage_total_bytes", -1L)
                    if (free >= 0L && total > 0L) {
                        parts += "хранилище: свободно ${formatStorageGiB(free)} ГБ из ${formatStorageGiB(total)} ГБ"
                    } else {
                        missing += "storage"
                    }
                }

                AggregateMetric.MEDIA_VOLUME -> {
                    val current = state.optInt("media_volume", -1)
                    val max = state.optInt("media_volume_max", -1)
                    if (current >= 0 && max > 0) {
                        parts += "громкость мультимедиа $current из $max"
                    } else {
                        missing += "media_volume"
                    }
                }

                AggregateMetric.BRIGHTNESS -> {
                    val brightness = state.optInt("brightness_percent", -1)
                    if (brightness >= 0) {
                        parts += "яркость примерно $brightness%"
                    } else {
                        missing += "brightness"
                    }
                }

                AggregateMetric.ORIENTATION -> {
                    val orientation = state.optString("orientation")
                    if (orientation.isNotBlank() && orientation != "unknown") {
                        parts +=
                            "ориентация " +
                                when (orientation) {
                                    "landscape" -> "альбомная"
                                    "portrait" -> "портретная"
                                    else -> orientation
                                }
                    } else {
                        missing += "orientation"
                    }
                }
            }
        }

        commandHistoryStore.addEvent(
            activeCommandHistoryId,
            state = if (missing.isEmpty()) "multi_metric_verified" else "multi_metric_incomplete",
            message =
                if (missing.isEmpty()) {
                    "Все запрошенные метрики устройства получены"
                } else {
                    "Часть запрошенных метрик устройства недоступна"
                },
            details =
                "requested=${metrics.joinToString(",")}; missing=${missing.joinToString(",")}; state=${state.toString().take(1200)}"
        )

        val answer =
            if (parts.isEmpty()) {
                "Не удалось получить запрошенные параметры устройства."
            } else {
                "Состояние планшета: ${parts.joinToString("; ")}."
            }

        if (missing.isNotEmpty()) {
            respondAndResume(
                text = answer + " Не получены: ${missing.joinToString(", ")}.",
                silent = silent,
                success = false,
                technical = "multi_metric_incomplete:${missing.joinToString(",")}"
            )
            return
        }

        if (aggregateMetricHasRemainingSemanticGoal(command)) {
            val verifiedFacts =
                verifiedAggregateFactsPayload(
                    metrics = metrics,
                    state = state,
                    summary = answer
                )

            commandHistoryStore.addEvent(
                activeCommandHistoryId,
                state = "multi_metric_remaining_goal_handoff",
                message = "Метрики подтверждены; исходная цель требует дальнейшего смыслового завершения",
                details =
                    "requested=${metrics.joinToString(",")}; terminal=RUNNING; facts=${verifiedFacts.take(1200)}"
            )

            askAyana(
                message = command,
                silent = silent,
                verifiedDeviceFacts = verifiedFacts
            )
            return
        }

        finishLocalCommand(
            answer,
            silent
        )
    }

    private fun requestsArtifactDeliverable(
        command: String
    ): Boolean {
        val c =
            command
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(Regex("\\s+"), " ")
                .trim()

        val action =
            Regex(
                """(?:^|\s)(?:создай|создать|сделай|сделать|сгенерируй|сгенерировать|сохрани|сохранить|экспортируй|экспортировать|подготовь|подготовить|сформируй|сформировать|выгрузи|выгрузить)(?=\s|$)"""
            )
                .containsMatchIn(c) ||
                Regex("""(?:^|\s)дай\s+(?:мне\s+)?(?:готовый\s+)?[^.!?]{0,80}\bфайл\b""")
                    .containsMatchIn(c)

        if (!action) {
            return false
        }

        return listOf(
            "файл",
            "документ",
            "txt",
            "docx",
            "word",
            "ворд",
            "pdf",
            "пдф",
            "xlsx",
            "excel",
            "эксел",
            "jpeg",
            "jpg",
            "изображен",
            "график",
            "диаграмм"
        ).any { marker ->
            c.contains(marker)
        }
    }

    private fun shouldDelegateArtifactWholeGoalToAgent(
        decision: AyanaCompositeIntentGate.Decision
    ): Boolean {
        if (
            decision.constraints.forbidNetwork ||
            decision.type == AyanaCompositeIntentGate.DecisionType.DATA_ONLY ||
            decision.type == AyanaCompositeIntentGate.DecisionType.INVALID ||
            decision.type == AyanaCompositeIntentGate.DecisionType.REQUIRE_CONFIRMATION ||
            decision.type == AyanaCompositeIntentGate.DecisionType.CONDITIONAL
        ) {
            return false
        }

        return decision.type == AyanaCompositeIntentGate.DecisionType.PASS_THROUGH ||
            decision.type == AyanaCompositeIntentGate.DecisionType.COMPOSITE ||
            decision.type == AyanaCompositeIntentGate.DecisionType.ENVIRONMENT_CONSTRAINED
    }

    private fun extractDirectSystemSettingsSection(
        command: String
    ): String? {

        val c =
            command
                .lowercase(
                    Locale.getDefault()
                )
                .replace('ё', 'е')
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        // App-specific routes (for example «уведомления YouTube») are
        // handled earlier and must not be mistaken for global settings.
        fun hasAny(
            vararg parts: String
        ): Boolean =
            parts.any {
                c.contains(it)
            }

        fun opensSettingsTopic(): Boolean =
            c.startsWith("открой ") ||
                c.startsWith("открой мне ") ||
                c.startsWith("покажи ") ||
                c.startsWith("покажи мне ") ||
                c.startsWith("зайди ") ||
                c.startsWith("перейди ") ||
                c.startsWith("настройки ") ||
                c.startsWith("открой настройки ") ||
                c.startsWith("покажи настройки ")

        if (!opensSettingsTopic()) {
            return null
        }

        // v12.10.2 semantic separation: read-only requests such as
        // «покажи последние уведомления на планшете» must be handled by the
        // notification reader, never by Settings navigation.
        if (isRecentNotificationsReadRequest(c)) {
            return null
        }

        return when {

            hasAny(
                "подключения",
                "подключение",
                "подключений",
                "connections",
                "сеть и интернет",
                "сети и интернет",
                "network and internet",
                "network & internet"
            ) ->
                "connections"

            hasAny(
                "обслуживание устройства",
                "обслуживания устройства",
                "уход за устройством",
                "battery and device care",
                "device care"
            ) ->
                "device_care"

            (
                (c.contains("оптимизац") && c.contains("батар")) ||
                    c.contains("экономия батареи для прилож") ||
                    c.contains("игнорирование оптимизац")
                ) ->
                "battery_optimization"

            hasAny(
                "батаре",
                "аккумулятор",
                "энергосбереж",
                "экономия энергии"
            ) ->
                "battery"

            hasAny(
                "хранилищ",
                "память устройства",
                "внутренняя память"
            ) ->
                "storage"

            hasAny(
                "уведомлен"
            ) ->
                "notifications"

            hasAny(
                "мобильные данные",
                "использование данных",
                "расход трафика",
                "трафик"
            ) ->
                "data_usage"

            hasAny(
                "vpn",
                "впн"
            ) ->
                "vpn"

            hasAny(
                "nfc",
                "нфс"
            ) ->
                "nfc"

            hasAny(
                "клавиатур",
                "метод ввода"
            ) ->
                "keyboard"

            hasAny(
                "приложения по умолчанию",
                "приложение по умолчанию"
            ) ->
                "default_apps"

            hasAny(
                "для разработчиков",
                "параметры разработчика",
                "режим разработчика"
            ) ->
                "developer_options"

            hasAny(
                "сведения об устройстве",
                "информация об устройстве",
                "о планшете",
                "об устройстве"
            ) ->
                "device_info"

            hasAny(
                "конфиденциальност",
                "приватност"
            ) ->
                "privacy"

            hasAny(
                "специальные возможности",
                "спец возможности",
                "accessibility"
            ) ->
                "accessibility"

            hasAny(
                "местополож",
                "геолокац",
                "локац"
            ) ->
                "location"

            hasAny(
                "безопасност"
            ) ->
                "security"

            (
                hasAny(
                    "дата и время",
                    "дату и время",
                    "даты и времени",
                    "время и дата",
                    "время и дату",
                    "date and time"
                ) ||
                    (
                        c.contains("дат") &&
                            c.contains("врем")
                        )
                ) ->
                "date_time"

            hasAny(
                "bluetooth",
                "блютуз"
            ) ->
                "bluetooth"

            hasAny(
                "wi-fi",
                "wifi",
                "вай фай",
                "вайфай"
            ) ->
                "wifi"

            hasAny(
                "звук",
                "громкост"
            ) ->
                "sound"

            hasAny(
                "экран",
                "диспле"
            ) ->
                "display"

            hasAny(
                "язык",
                "локаль"
            ) ->
                "language"

            hasAny(
                "приложени"
            ) &&
                !hasAny(
                    "информация о приложении",
                    "сведения о приложении",
                    "уведомления приложения",
                    "язык приложения"
                ) ->
                "apps"

            c == "открой настройки" ||
                c == "покажи настройки" ||
                c == "настройки" ->
                "general"

            else ->
                null
        }
    }

    private fun isMultiStepAgentCommand(
        command: String
    ): Boolean {

        val c =
            command
                .lowercase(Locale.ROOT)
                .replace('ё', 'е')
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        // v12.11.6 ROUTING INTEGRITY.
        // A bare conjunction is not proof of a multi-step goal:
        // «дата и время», «звуки и вибрация», «сеть и интернет» are names of
        // single Settings destinations. Multi-step ownership requires either
        // an explicit sequencing connector or at least two actual action verbs.
        val strongSequenceConnectors =
            listOf(
                " потом ",
                " затем ",
                " после этого ",
                " а потом "
            )

        if (
            strongSequenceConnectors.any {
                c.contains(it)
            }
        ) {
            return true
        }

        val actionPattern =
            Regex(
                """(?:^|\s)(?:открой|открыть|запусти|запустить|включи|включить|найди|найти|поищи|поискать|нажми|нажать|выбери|выбрать|зайди|зайти|перейди|перейти|проверь|проверить|убедись|убедиться|подтверди|подтвердить|остановись|остановиться)(?=\s|$)"""
            )

        val actionMatches =
            actionPattern
                .findAll(c)
                .count()

        if (actionMatches >= 2) {
            return true
        }

        // «и» counts as a step separator only when there is a real action on
        // both sides. This keeps ordinary compound Settings labels local while
        // preserving goals such as «открой YouTube и найди музыку».
        val conjunctionIndex =
            c.indexOf(" и ")

        if (conjunctionIndex > 0) {
            val left =
                c.substring(
                    0,
                    conjunctionIndex
                )
            val right =
                c.substring(
                    conjunctionIndex + 3
                )

            val leftHasAction =
                actionPattern.containsMatchIn(left)

            val rightHasAction =
                actionPattern.containsMatchIn(right)

            if (
                leftHasAction &&
                rightHasAction
            ) {
                return true
            }
        }

        return false
    }

    private fun isAppLaunchCommand(
        command: String
    ): Boolean {

        return APP_LAUNCH_PREFIXES
            .any {
                command.startsWith(it)
            }
    }

    // =========================================================
    // APP EXECUTION ROUTER — v11.5
    // =========================================================

    private fun extractPendingLifecycleFollowUpAction(
        command: String
    ): String? {

        val clean =
            normalizeLifecycleRoutingText(
                command
            )

        return when (clean) {
            "сверни",
            "свернуть",
            "просто сверни",
            "сверни его",
            "сверни ее",
            "сверни приложение" ->
                "minimize"

            "закрой",
            "закрыть",
            "просто закрой",
            "полностью закрой",
            "закрой его",
            "закрой ее",
            "заверши",
            "заверши приложение" ->
                "close"

            "открой",
            "открыть",
            "просто открой",
            "открой его",
            "открой ее",
            "запусти",
            "включи" ->
                "open"

            else ->
                null
        }
    }

    private fun extractLocalAppLifecycleRequest(
        command: String
    ): Pair<String, String>? {

        val clean =
            normalizeLifecycleRoutingText(
                command
            )

        if (clean.isBlank()) {
            return null
        }

        // Short action-only commands are deterministic device actions. With no
        // pending clarification, minimize/close refer to the currently foreground
        // app. This is what prevents a bare «сверни» from reaching Agent Core.
        when (clean) {
            "сверни",
            "свернуть",
            "просто сверни",
            "сверни приложение" ->
                return "minimize" to FOREGROUND_APP_SENTINEL

            "закрой",
            "закрыть",
            "просто закрой",
            "полностью закрой",
            "заверши",
            "заверши приложение" ->
                return "close" to FOREGROUND_APP_SENTINEL
        }

        // Windows/dialogs/Recents are not named application lifecycle requests.
        val excludedPrefixes =
            listOf(
                "закрой все",
                "закрой окно",
                "закрой вкладку",
                "закрой диалог",
                "закрой меню",
                "закрой клавиатуру",
                "сверни все",
                "сверни окно"
            )

        if (
            excludedPrefixes.any {
                clean.startsWith(it)
            }
        ) {
            return null
        }

        val minimizePrefixes =
            listOf(
                "сверни приложение ",
                "сверни "
            )

        for (prefix in minimizePrefixes) {
            if (clean.startsWith(prefix)) {
                val target =
                    lifecycleTargetAfterPrefix(
                        clean,
                        prefix
                    )

                if (
                    target.isBlank() ||
                    target in LIFECYCLE_INVALID_TARGETS
                ) {
                    return null
                }

                return "minimize" to target
            }
        }

        val closePrefixes =
            listOf(
                "полностью закрой приложение ",
                "полностью закрой ",
                "закрой приложение ",
                "заверши приложение ",
                "закрой "
            )

        for (prefix in closePrefixes) {
            if (clean.startsWith(prefix)) {
                val target =
                    lifecycleTargetAfterPrefix(
                        clean,
                        prefix
                    )

                if (
                    target.isBlank() ||
                    target in LIFECYCLE_INVALID_CLOSE_TARGETS
                ) {
                    return null
                }

                return "close" to target
            }
        }

        // Simple app launches now use the same resolver + verification contract as
        // minimize. Complex/settings/search commands intentionally remain with their
        // dedicated routers below.
        val openPrefix =
            APP_LAUNCH_PREFIXES
                .firstOrNull {
                    clean.startsWith(it)
                }

        if (openPrefix != null) {
            val target =
                lifecycleTargetAfterPrefix(
                    clean,
                    openPrefix
                )

            if (
                target.isBlank() ||
                !isSafeSimpleLifecycleOpenTarget(
                    target = target,
                    wholeCommand = clean
                )
            ) {
                return null
            }

            return "open" to target
        }

        return null
    }

    private fun normalizeLifecycleRoutingText(
        value: String
    ): String =
        value
            .lowercase(
                Locale.ROOT
            )
            .replace(
                'ё',
                'е'
            )
            .trim()
            .tri