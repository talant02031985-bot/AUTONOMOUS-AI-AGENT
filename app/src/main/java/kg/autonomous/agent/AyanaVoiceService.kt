package kg.autonomous.agent

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

class AyanaVoiceService : Service() {

    private enum class ListenMode {
        WAKE,
        COMMAND,
        BUSY
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

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

    private var audioToken:
        Long = 0L

    private val conversationHistory =
        mutableListOf<Pair<String, String>>()

    private val readyFile by lazy {
        File(
            filesDir,
            "ayana_ready_marin.mp3"
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

        isRunning = true
        shuttingDown = false

        createNotificationChannel()

        promoteToForeground(
            "AYANA запускает локальное распознавание"
        )

        broadcastStatus(
            "Загружаю русскую модель…",
            STATE_THINKING
        )

        prefetchReadyVoice()

        thread(
            start = true,
            name = "AyanaModelInit"
        ) {
            try {

                initSherpaModel()

                modelReady = true

                mainHandler.post {
                    if (!shuttingDown) {
                        startWakeListening()
                    }
                }

            } catch (_: Exception) {

                modelReady = false

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

            ACTION_START -> {
                if (
                    !shuttingDown &&
                    modelReady &&
                    !isRecording &&
                    listenMode != ListenMode.BUSY
                ) {
                    startWakeListening()
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

                    stopSherpaListening()

                    mainHandler.postDelayed(
                        {
                            if (!shuttingDown) {
                                executeCommand(
                                    command,
                                    silent = true
                                )
                            }
                        },
                        180L
                    )
                }
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
                101,
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
                "Остановить",
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

        val recorder =
            try {

                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRateInHz,
                    channelConfig,
                    audioFormat,
                    minBufferBytes * 2
                )

            } catch (_: Exception) {

                null
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

        audioRecord =
            recorder

        try {

            recorder.startRecording()

            isRecording =
                true

        } catch (_: Exception) {

            try {
                recorder.release()
            } catch (_: Exception) {
            }

            audioRecord =
                null

            isRecording =
                false

            broadcastStatus(
                "Не удалось начать прослушивание",
                STATE_ERROR
            )

            return
        }

        recordingThread =
            thread(
                start = true,
                name = "AyanaSherpaAudio"
            ) {

                processSherpaAudio(
                    recorder
                )
            }
    }

    private fun processSherpaAudio(
        recorder: AudioRecord
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

        var pendingAction:
            (() -> Unit)? = null

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

                val samples =
                    FloatArray(count) {
                        buffer[it] /
                            32768.0f
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

                        if (isEndpoint) {

                            val finalText =
                                text

                            localRecognizer
                                .reset(stream)

                            val detected =
                                wakeSeen ||
                                    containsWakeWord(
                                        finalText
                                    )

                            wakeSeen =
                                false

                            if (detected) {

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
                                            acknowledgeWakeAndListen()
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
                            }
                        }
                    }

                    ListenMode.COMMAND -> {

                        if (isEndpoint) {

                            val finalText =
                                text

                            localRecognizer
                                .reset(stream)

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
                            }
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
                listenMode !=
                ListenMode.BUSY
            ) {

                pendingAction =
                    {
                        broadcastStatus(
                            "Перезапускаю микрофон…",
                            STATE_THINKING
                        )

                        mainHandler.postDelayed(
                            {
                                resumeCurrentListeningMode()
                            },
                            700L
                        )
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
                !shuttingDown
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

    private fun stopSherpaListening() {

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

            ListenMode.COMMAND ->
                startCommandListening()

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

        return WAKE_VARIANTS
            .any {
                normalized.contains(it)
            }
    }

    private fun extractWakeCommand(
        phrase: String
    ): String {

        var normalized =
            normalizeRecognitionText(
                phrase
            )

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
                    "Слушаю.",
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
                startCommandListening()
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
                    startCommandListening()
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

        stopSherpaListening()

        listenMode =
            ListenMode.BUSY

        val normalized =
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

        broadcastStatus(
            if (silent) {
                "Текст: $originalCommand"
            } else {
                "Выполняю: $originalCommand"
            },
            STATE_THINKING
        )

        when {

            normalized ==
                "назад" ||
                normalized ==
                "вернись назад" -> {

                val ok =
                    AgentAccessibilityService
                        .instance
                        ?.pressBack() == true

                if (ok) {
                    finishLocalCommand(
                        "Назад",
                        silent
                    )
                } else {
                    respondAndResume(
                        "Включите мой доступ в специальных возможностях.",
                        silent
                    )
                }

                return
            }

            normalized ==
                "домой" ||
                normalized ==
                "на главный экран" ||
                normalized ==
                "главный экран" -> {

                val ok =
                    AgentAccessibilityService
                        .instance
                        ?.pressHome() == true

                if (ok) {
                    finishLocalCommand(
                        "Главный экран",
                        silent
                    )
                } else {
                    respondAndResume(
                        "Включите мой доступ в специальных возможностях.",
                        silent
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
                "очисти память" ||
                normalized ==
                "очисти историю" -> {

                conversationHistory
                    .clear()

                respondAndResume(
                    "Хорошо. История разговора очищена.",
                    silent
                )

                return
            }

            normalized ==
                "громче" ||
                normalized ==
                "увеличь громкость" ||
                normalized ==
                "сделай громче" -> {

                changeVolume(
                    AudioManager.ADJUST_RAISE
                )

                finishLocalCommand(
                    "Громкость увеличена",
                    silent
                )

                return
            }

            normalized ==
                "тише" ||
                normalized ==
                "уменьши громкость" ||
                normalized ==
                "сделай тише" -> {

                changeVolume(
                    AudioManager.ADJUST_LOWER
                )

                finishLocalCommand(
                    "Громкость уменьшена",
                    silent
                )

                return
            }

            normalized ==
                "выключи звук" ||
                normalized ==
                "без звука" -> {

                changeVolume(
                    AudioManager.ADJUST_MUTE
                )

                finishLocalCommand(
                    "Звук выключен",
                    silent
                )

                return
            }

            normalized ==
                "включи звук" ||
                normalized ==
                "верни звук" -> {

                changeVolume(
                    AudioManager.ADJUST_UNMUTE
                )

                finishLocalCommand(
                    "Звук включён",
                    silent
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
                        "найди в картах "
                    ) ||
                normalized
                    .startsWith(
                        "покажи на карте "
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
                            "найди в картах "
                        ) ->
                            normalized
                                .removePrefix(
                                    "найди в картах "
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
            normalized
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
            "фото" ->
                openApp(
                    "галерею",
                    silent,
                    "com.sec.android.gallery3d",
                    "com.google.android.apps.photos"
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
            "чат gpt",
            "чатгпт",
            "чат джипити" ->
                openApp(
                    "ChatGPT",
                    silent,
                    "com.openai.chatgpt"
                )

            "telegram",
            "телеграм" ->
                openApp(
                    "Telegram",
                    silent,
                    "org.telegram.messenger"
                )

            "whatsapp",
            "ватсап",
            "вотсап" ->
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

            else ->
                askAyana(
                    originalCommand,
                    silent
                )
        }
    }

    private fun extractYouTubeQuery(
        command: String
    ): String {

        val markers =
            listOf(
                "поищи ",
                "найди ",
                "ищи ",
                "поиск "
            )

        for (
            marker in markers
        ) {

            val index =
                command.indexOf(
                    marker
                )

            if (index >= 0) {

                var query =
                    command
                        .substring(
                            index +
                                marker.length
                        )
                        .trim()

                query =
                    query
                        .removePrefix(
                            "в ютубе "
                        )
                        .removePrefix(
                            "на ютубе "
                        )
                        .removePrefix(
                            "youtube "
                        )
                        .removePrefix(
                            "ютуб "
                        )
                        .removePrefix(
                            "музыку "
                        )
                        .removePrefix(
                            "музыка "
                        )
                        .trim()

                return query
            }
        }

        return ""
    }

    private fun clickByText(
        target: String,
        silent: Boolean
    ) {

        if (target.isBlank()) {

            respondAndResume(
                "Скажите, что именно нажать.",
                silent
            )

            return
        }

        val service =
            AgentAccessibilityService
                .instance

        if (service == null) {

            respondAndResume(
                "Включите мой доступ в специальных возможностях.",
                silent
            )

            return
        }

        val success =
            service.clickByText(
                target
            )

        if (success) {

            finishLocalCommand(
                "Нажимаю: $target",
                silent
            )

        } else {

            respondAndResume(
                "Я не нашла на экране элемент $target.",
                silent
            )
        }
    }

    private fun changeVolume(
        direction: Int
    ) {

        val audioManager =
            getSystemService(
                Context.AUDIO_SERVICE
            ) as AudioManager

        audioManager.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            direction,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun openApp(
        displayName: String,
        silent: Boolean,
        vararg packages: String
    ) {

        for (
            packageName in packages
        ) {

            try {

                val intent =
                    Intent(
                        Intent.ACTION_MAIN
                    ).apply {

                        addCategory(
                            Intent.CATEGORY_LAUNCHER
                        )

                        setPackage(
                            packageName
                        )

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                startActivity(intent)

                finishLocalCommand(
                    "Открываю $displayName",
                    silent
                )

                return

            } catch (
                _: ActivityNotFoundException
            ) {
            } catch (
                _: SecurityException
            ) {
            }
        }

        respondAndResume(
            "Приложение $displayName не найдено.",
            silent
        )
    }

    private fun openSystemScreen(
        action: String,
        displayName: String,
        silent: Boolean
    ) {

        try {

            startActivity(
                Intent(action).apply {

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )

            finishLocalCommand(
                "Открываю $displayName",
                silent
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            respondAndResume(
                "Не удалось открыть $displayName.",
                silent
            )
        }
    }

    private fun openYouTubeSearch(
        query: String,
        silent: Boolean
    ) {

        val uri =
            Uri.parse(
                "https://www.youtube.com/results" +
                    "?search_query=" +
                    Uri.encode(query)
            )

        try {

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).apply {

                    setPackage(
                        "com.google.android.youtube"
                    )

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )

            finishLocalCommand(
                "Ищу в YouTube: $query",
                silent
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            try {

                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        uri
                    ).apply {

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                )

                finishLocalCommand(
                    "Ищу в YouTube: $query",
                    silent
                )

            } catch (
                _: ActivityNotFoundException
            ) {

                respondAndResume(
                    "Не удалось открыть YouTube.",
                    silent
                )
            }
        }
    }

    private fun openGoogleSearch(
        query: String,
        silent: Boolean
    ) {

        val uri =
            Uri.parse(
                "https://www.google.com/search?q=" +
                    Uri.encode(query)
            )

        try {

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).apply {

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )

            finishLocalCommand(
                "Ищу в Google: $query",
                silent
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            respondAndResume(
                "Не удалось открыть поиск.",
                silent
            )
        }
    }

    private fun openMapSearch(
        query: String,
        silent: Boolean
    ) {

        val uri =
            Uri.parse(
                "geo:0,0?q=" +
                    Uri.encode(query)
            )

        try {

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).apply {

                    setPackage(
                        "com.google.android.apps.maps"
                    )

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }
            )

            finishLocalCommand(
                "Ищу на карте: $query",
                silent
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            try {

                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        uri
                    ).apply {

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }
                )

                finishLocalCommand(
                    "Ищу на карте: $query",
                    silent
                )

            } catch (
                _: ActivityNotFoundException
            ) {

                respondAndResume(
                    "Не удалось открыть карты.",
                    silent
                )
            }
        }
    }

    private fun finishLocalCommand(
        text: String,
        silent: Boolean
    ) {

        broadcastStatus(
            text,
            STATE_THINKING
        )

        updateNotification(
            text
        )

        if (silent) {

            mainHandler.postDelayed(
                {
                    startWakeListening()
                },
                300L
            )

        } else {

            mainHandler.postDelayed(
                {
                    startWakeListening()
                },
                650L
            )
        }
    }

    private fun respondAndResume(
        text: String,
        silent: Boolean
    ) {

        if (silent) {
            showTextAndResume(text)
        } else {
            speakAndResume(text)
        }
    }

    private fun showTextAndResume(
        text: String
    ) {

        broadcastStatus(
            text,
            STATE_TEXT
        )

        updateNotification(
            "Текстовый ответ готов"
        )

        mainHandler.postDelayed(
            {
                startWakeListening()
            },
            450L
        )
    }

    // =========================================================
    // AI TEXT
    // =========================================================

    private fun askAyana(
        message: String,
        silent: Boolean
    ) {

        stopSherpaListening()

        listenMode =
            ListenMode.BUSY

        broadcastStatus(
            "Думаю…",
            STATE_THINKING
        )

        updateNotification(
            "AYANA думает…"
        )

        thread(
            start = true,
            name = "AyanaAI"
        ) {

            var connection:
                HttpsURLConnection? = null

            try {

                val url =
                    URL(
                        "$WORKER_URL/"
                    )

                connection =
                    url.openConnection()
                        as HttpsURLConnection

                connection.requestMethod =
                    "POST"

                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )

                connection.connectTimeout =
                    15000

                connection.readTimeout =
                    35000

                connection.doOutput =
                    true

                val contextText =
                    buildString {

                        conversationHistory
                            .takeLast(5)
                            .forEach {

                                append(
                                    "Пользователь: "
                                )

                                append(
                                    it.first
                                )

                                append("\n")

                                append(
                                    "AYANA: "
                                )

                                append(
                                    it.second
                                )

                                append("\n")
                            }

                        append(
                            "Пользователь: "
                        )

                        append(
                            message
                        )
                    }

                val requestJson =
                    JSONObject().apply {

                        put(
                            "message",
                            contextText
                        )
                    }

                connection.outputStream
                    .use { output ->

                        output.write(
                            requestJson
                                .toString()
                                .toByteArray(
                                    Charsets.UTF_8
                                )
                        )
                    }

                val responseCode =
                    connection.responseCode

                val stream =
                    if (
                        responseCode in
                        200..299
                    ) {

                        connection.inputStream

                    } else {

                        connection.errorStream
                    }

                val responseText =
                    stream
                        .bufferedReader()
                        .use {
                            it.readText()
                        }

                if (
                    responseCode !in
                    200..299
                ) {

                    throw IllegalStateException(
                        "AI HTTP $responseCode"
                    )
                }

                val answer =
                    JSONObject(
                        responseText
                    )
                        .optString(
                            "reply",
                            "Я пока не смогла сформировать ответ."
                        )
                        .trim()

                synchronized(
                    conversationHistory
                ) {

                    if (
                        conversationHistory
                            .size >= 10
                    ) {

                        conversationHistory
                            .removeAt(0)
                    }

                    conversationHistory.add(
                        message to answer
                    )
                }

                mainHandler.post {

                    respondAndResume(
                        answer,
                        silent
                    )
                }

            } catch (_: Exception) {

                mainHandler.post {

                    respondAndResume(
                        "Не удалось связаться с сервером. Проверьте интернет.",
                        silent
                    )
                }

            } finally {

                connection
                    ?.disconnect()
            }
        }
    }

    // =========================================================
    // MARIN TTS
    // =========================================================

    private fun speakAndResume(
        text: String
    ) {

        if (text.isBlank()) {

            startWakeListening()

            return
        }

        stopSherpaListening()

        listenMode =
            ListenMode.BUSY

        broadcastStatus(
            "Говорю…",
            STATE_SPEAKING
        )

        updateNotification(
            "AYANA отвечает"
        )

        val token =
            ++audioToken

        stopCurrentAudio(
            keepToken = true
        )

        thread(
            start = true,
            name = "AyanaTTS"
        ) {

            var file:
                File? = null

            try {

                file =
                    File.createTempFile(
                        "ayana_voice_",
                        ".mp3",
                        cacheDir
                    )

                downloadTtsToFile(
                    text,
                    file
                )

                if (
                    token !=
                    audioToken ||
                    shuttingDown
                ) {

                    file.delete()

                    return@thread
                }

                val readyAudio =
                    file

                mainHandler.post {

                    if (
                        token ==
                        audioToken &&
                        !shuttingDown
                    ) {

                        playFile(
                            readyAudio,
                            deleteAfter = true
                        ) {
                            startWakeListening()
                        }

                    } else {

                        readyAudio
                            .delete()
                    }
                }

            } catch (_: Exception) {

                file
                    ?.delete()

                mainHandler.post {

                    broadcastStatus(
                        "Голос временно недоступен",
                        STATE_ERROR
                    )

                    mainHandler.postDelayed(
                        {
                            startWakeListening()
                        },
                        1000L
                    )
                }
            }
        }
    }

    private fun downloadTtsToFile(
        text: String,
        target: File
    ) {

        var connection:
            HttpsURLConnection? = null

        try {

            val url =
                URL(
                    "$WORKER_URL/tts"
                )

            connection =
                url.openConnection()
                    as HttpsURLConnection

            connection.requestMethod =
                "POST"

            connection.setRequestProperty(
                "Content-Type",
                "application/json"
            )

            connection.connectTimeout =
                15000

            connection.readTimeout =
                45000

            connection.doOutput =
                true

            val requestJson =
                JSONObject().apply {

                    put(
                        "text",
                        text
                    )
                }

            connection.outputStream
                .use { output ->

                    output.write(
                        requestJson
                            .toString()
                            .toByteArray(
                                Charsets.UTF_8
                            )
                    )
                }

            val responseCode =
                connection
                    .responseCode

            if (
                responseCode !in
                200..299
            ) {

                throw IllegalStateException(
                    "TTS HTTP $responseCode"
                )
            }

            connection.inputStream
                .use { input ->

                    target.outputStream()
                        .use { output ->

                            input.copyTo(
                                output
                            )
                        }
                }

        } finally {

            connection
                ?.disconnect()
        }
    }

    private fun playFile(
        file: File,
        deleteAfter: Boolean,
        onFinished: () -> Unit
    ) {

        stopCurrentAudio(
            keepToken = true
        )

        val player =
            MediaPlayer()

        mediaPlayer =
            player

        try {

            player.setDataSource(
                file.absolutePath
            )

            player.setOnPreparedListener {
                    prepared ->

                try {

                    prepared.start()

                } catch (_: Exception) {

                    releasePlayer(
                        prepared,
                        file,
                        deleteAfter
                    )

                    onFinished()
                }
            }

            player.setOnCompletionListener {
                    completed ->

                releasePlayer(
                    completed,
                    file,
                    deleteAfter
                )

                onFinished()
            }

            player.setOnErrorListener {
                    failed,
                    _,
                    _ ->

                releasePlayer(
                    failed,
                    file,
                    deleteAfter
                )

                onFinished()

                true
            }

            player.prepareAsync()

        } catch (_: Exception) {

            releasePlayer(
                player,
                file,
                deleteAfter
            )

            onFinished()
        }
    }

    private fun releasePlayer(
        player: MediaPlayer,
        file: File,
        deleteAfter: Boolean
    ) {

        if (
            mediaPlayer === player
        ) {

            mediaPlayer =
                null
        }

        try {
            player.release()
        } catch (_: Exception) {
        }

        if (deleteAfter) {

            file.delete()
        }
    }

    private fun stopCurrentAudio(
        keepToken: Boolean =
            false
    ) {

        if (!keepToken) {

            audioToken++
        }

        val player =
            mediaPlayer

        mediaPlayer =
            null

        if (player != null) {

            try {

                if (
                    player.isPlaying
                ) {

                    player.stop()
                }

            } catch (_: Exception) {
            }

            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    // =========================================================
    // STATUS
    // =========================================================

    private fun broadcastStatus(
        text: String,
        state: String
    ) {

        currentStatusText =
            text

        currentStatusState =
            state

        sendBroadcast(
            Intent(
                ACTION_STATUS
            ).apply {

                setPackage(
                    packageName
                )

                putExtra(
                    EXTRA_STATUS_TEXT,
                    text
                )

                putExtra(
                    EXTRA_STATUS_STATE,
                    state
                )
            }
        )
    }

    // =========================================================
    // STOP / DESTROY
    // =========================================================

    private fun shutdownAyana() {

        if (shuttingDown) {
            return
        }

        shuttingDown =
            true

        isRunning =
            false

        listenMode =
            ListenMode.BUSY

        broadcastStatus(
            "AYANA остановлена",
            STATE_STOPPED
        )

        stopSherpaListening()
        stopCurrentAudio()

        mainHandler
            .removeCallbacksAndMessages(
                null
            )

        val thread =
            recordingThread

        if (
            thread != null &&
            thread !==
            Thread.currentThread()
        ) {

            try {
                thread.join(
                    500L
                )
            } catch (_: Exception) {
            }
        }

        try {
            recognizer
                ?.release()
        } catch (_: Exception) {
        }

        recognizer =
            null

        modelReady =
            false

        if (
            Build.VERSION.SDK_INT >=
            24
        ) {

            stopForeground(
                STOP_FOREGROUND_REMOVE
            )

        } else {

            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        stopSelf()
    }

    override fun onDestroy() {

        shuttingDown =
            true

        isRunning =
            false

        listenMode =
            ListenMode.BUSY

        stopSherpaListening()
        stopCurrentAudio()

        mainHandler
            .removeCallbacksAndMessages(
                null
            )

        val thread =
            recordingThread

        if (
            thread != null &&
            thread !==
            Thread.currentThread()
        ) {

            try {
                thread.join(
                    500L
                )
            } catch (_: Exception) {
            }
        }

        try {
            recognizer
                ?.release()
        } catch (_: Exception) {
        }

        recognizer =
            null

        modelReady =
            false

        super.onDestroy()
    }

    companion object {

        const val ACTION_START =
            "kg.autonomous.agent.action.START_AYANA"

        const val ACTION_STOP =
            "kg.autonomous.agent.action.STOP_AYANA"

        const val ACTION_TEXT_COMMAND =
            "kg.autonomous.agent.action.TEXT_COMMAND"

        const val ACTION_STATUS =
            "kg.autonomous.agent.action.AYANA_STATUS"

        const val EXTRA_TEXT_COMMAND =
            "text_command"

        const val EXTRA_STATUS_TEXT =
            "status_text"

        const val EXTRA_STATUS_STATE =
            "status_state"

        private const val CHANNEL_ID =
            "ayana_voice_service"

        private const val NOTIFICATION_ID =
            2401

        private const val WORKER_URL =
            "https://ayana-ai.talant02031985.workers.dev"

        private val WAKE_VARIANTS =
            listOf(
                "аяна",
                "айана",
                "а яна",
                "а я на",
                "ayana"
            )

        const val STATE_LISTENING =
            "listening"

        const val STATE_COMMAND =
            "command"

        const val STATE_THINKING =
            "thinking"

        const val STATE_SPEAKING =
            "speaking"

        const val STATE_TEXT =
            "text"

        const val STATE_ERROR =
            "error"

        const val STATE_STOPPED =
            "stopped"

        @Volatile
        var isRunning:
            Boolean = false

        @Volatile
        var currentStatusText:
            String = "Жду: «Аяна»"

        @Volatile
        var currentStatusState:
            String = STATE_LISTENING
    }
}
