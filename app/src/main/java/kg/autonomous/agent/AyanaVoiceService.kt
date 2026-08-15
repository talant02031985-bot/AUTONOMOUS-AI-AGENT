package kg.autonomous.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class AyanaVoiceService :
    Service(),
    RecognitionListener {

    private enum class ListenMode {
        WAKE,
        COMMAND,
        BUSY
    }

    private val mainHandler =
        Handler(Looper.getMainLooper())

    private var speechRecognizer:
        SpeechRecognizer? = null

    private var recognitionIntent:
        Intent? = null

    private var listenMode =
        ListenMode.WAKE

    private var listeningNow =
        false

    private var shuttingDown =
        false

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

    override fun onCreate() {
        super.onCreate()

        isRunning = true
        shuttingDown = false

        createNotificationChannel()
        promoteToForeground(
            "AYANA активна • жду «Аяна»"
        )

        setupSpeechRecognizer()
        prefetchReadyVoice()

        mainHandler.postDelayed(
            {
                startWakeListening()
            },
            500L
        )
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
                    speechRecognizer != null &&
                    !listeningNow &&
                    listenMode != ListenMode.BUSY
                ) {
                    startWakeListening()
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? = null

    // =========================================================
    // FOREGROUND SERVICE / NOTIFICATION
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
                "Голос синтезирован искусственным интеллектом"
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
    // SPEECH RECOGNITION
    // =========================================================

    private fun setupSpeechRecognizer() {

        if (
            !SpeechRecognizer
                .isRecognitionAvailable(this)
        ) {
            broadcastStatus(
                "Распознавание речи недоступно",
                STATE_ERROR
            )
            return
        }

        speechRecognizer =
            try {
                if (
                    Build.VERSION.SDK_INT >= 31 &&
                    SpeechRecognizer
                        .isOnDeviceRecognitionAvailable(
                            this
                        )
                ) {
                    SpeechRecognizer
                        .createOnDeviceSpeechRecognizer(
                            this
                        )
                } else {
                    SpeechRecognizer
                        .createSpeechRecognizer(
                            this
                        )
                }
            } catch (_: Exception) {
                SpeechRecognizer
                    .createSpeechRecognizer(this)
            }

        speechRecognizer
            ?.setRecognitionListener(this)

        recognitionIntent =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent
                        .LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "ru-RU"
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )

                putExtra(
                    RecognizerIntent
                        .EXTRA_MAX_RESULTS,
                    5
                )

                putExtra(
                    RecognizerIntent
                        .EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                    900L
                )

                putExtra(
                    RecognizerIntent
                        .EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                    650L
                )
            }
    }

    private fun startWakeListening() {

        if (
            shuttingDown ||
            listenMode == ListenMode.BUSY
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

        startListeningInternal()
    }

    private fun startCommandListening() {

        if (shuttingDown) {
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

        startListeningInternal()
    }

    private fun startListeningInternal() {

        if (
            shuttingDown ||
            speechRecognizer == null ||
            recognitionIntent == null
        ) {
            return
        }

        try {
            speechRecognizer?.cancel()

            mainHandler.postDelayed(
                {
                    if (
                        shuttingDown ||
                        listenMode == ListenMode.BUSY
                    ) {
                        return@postDelayed
                    }

                    try {
                        listeningNow = true

                        speechRecognizer
                            ?.startListening(
                                recognitionIntent
                            )
                    } catch (_: Exception) {
                        listeningNow = false
                        scheduleRecognitionRestart(
                            900L
                        )
                    }
                },
                150L
            )

        } catch (_: Exception) {
            listeningNow = false

            scheduleRecognitionRestart(
                900L
            )
        }
    }

    private fun pauseRecognition() {

        listeningNow = false

        try {
            speechRecognizer?.cancel()
        } catch (_: Exception) {
        }
    }

    private fun scheduleRecognitionRestart(
        delay: Long
    ) {

        if (shuttingDown) {
            return
        }

        mainHandler.postDelayed(
            {
                if (
                    !shuttingDown &&
                    listenMode != ListenMode.BUSY
                ) {
                    when (listenMode) {
                        ListenMode.WAKE ->
                            startWakeListening()

                        ListenMode.COMMAND ->
                            startCommandListening()

                        ListenMode.BUSY -> Unit
                    }
                }
            },
            delay
        )
    }

    override fun onReadyForSpeech(
        params: android.os.Bundle?
    ) {
        listeningNow = true
    }

    override fun onBeginningOfSpeech() {
    }

    override fun onRmsChanged(
        rmsdB: Float
    ) {
    }

    override fun onBufferReceived(
        buffer: ByteArray?
    ) {
    }

    override fun onEndOfSpeech() {
        listeningNow = false
    }

    override fun onError(
        error: Int
    ) {

        listeningNow = false

        if (shuttingDown) {
            return
        }

        if (
            error ==
            SpeechRecognizer
                .ERROR_INSUFFICIENT_PERMISSIONS
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

        val delay =
            when (error) {

                SpeechRecognizer
                    .ERROR_RECOGNIZER_BUSY ->
                    1200L

                SpeechRecognizer
                    .ERROR_CLIENT ->
                    800L

                SpeechRecognizer
                    .ERROR_NETWORK,
                SpeechRecognizer
                    .ERROR_NETWORK_TIMEOUT ->
                    1500L

                else ->
                    450L
            }

        scheduleRecognitionRestart(delay)
    }

    override fun onResults(
        results: android.os.Bundle?
    ) {

        listeningNow = false

        val phrases =
            results
                ?.getStringArrayList(
                    SpeechRecognizer
                        .RESULTS_RECOGNITION
                )
                ?.filter {
                    it.isNotBlank()
                }
                ?: emptyList()

        if (phrases.isEmpty()) {
            scheduleRecognitionRestart(
                350L
            )
            return
        }

        when (listenMode) {

            ListenMode.WAKE -> {

                val match =
                    phrases
                        .mapNotNull {
                            extractWakeCommand(it)
                        }
                        .firstOrNull()

                if (match == null) {
                    scheduleRecognitionRestart(
                        250L
                    )
                    return
                }

                pauseRecognition()

                if (match.isBlank()) {
                    acknowledgeWakeAndListen()
                } else {
                    executeCommand(match)
                }
            }

            ListenMode.COMMAND -> {

                val command =
                    phrases.firstOrNull()
                        ?.trim()
                        .orEmpty()

                if (command.isBlank()) {
                    startWakeListening()
                } else {
                    pauseRecognition()
                    executeCommand(command)
                }
            }

            ListenMode.BUSY -> Unit
        }
    }

    override fun onPartialResults(
        partialResults:
            android.os.Bundle?
    ) {
        // Намеренно не запускаем команду по partial result:
        // ждём полный результат, чтобы не отрезать фразу
        // после слова «Аяна».
    }

    override fun onEvent(
        eventType: Int,
        params: android.os.Bundle?
    ) {
    }

    private fun extractWakeCommand(
        phrase: String
    ): String? {

        val normalized =
            phrase
                .lowercase(Locale.getDefault())
                .replace('ё', 'е')
                .trim()

        val wakeWords =
            listOf(
                "аяна",
                "айана",
                "ayana"
            )

        for (wake in wakeWords) {

            val index =
                normalized.indexOf(wake)

            if (index >= 0) {

                val after =
                    normalized
                        .substring(
                            index + wake.length
                        )
                        .trim()
                        .trimStart(
                            ',',
                            '.',
                            '!',
                            '?',
                            ':',
                            ';',
                            '-',
                            '—'
                        )
                        .trim()

                return after
            }
        }

        return null
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

        Thread {
            try {
                downloadTtsToFile(
                    "Слушаю.",
                    readyFile
                )
            } catch (_: Exception) {
            }
        }.start()
    }

    private fun acknowledgeWakeAndListen() {

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
    // COMMAND PROCESSING
    // =========================================================

    private fun executeCommand(
        originalCommand: String
    ) {

        listenMode =
            ListenMode.BUSY

        val normalized =
            originalCommand
                .lowercase(
                    Locale.getDefault()
                )
                .replace('ё', 'е')
                .replace("пожалуйста", "")
                .replace(
                    Regex("[,!?;:]"),
                    " "
                )
                .replace(
                    Regex("\\s+"),
                    " "
                )
                .trim()

        if (normalized.isBlank()) {
            startWakeListening()
            return
        }

        broadcastStatus(
            "Выполняю: $originalCommand",
            STATE_THINKING
        )

        when {

            normalized == "назад" ||
                normalized ==
                "вернись назад" -> {

                val ok =
                    AgentAccessibilityService
                        .instance
                        ?.pressBack() == true

                if (ok) {
                    finishLocalCommand(
                        "Назад"
                    )
                } else {
                    speakAndResume(
                        "Включите мой доступ " +
                            "в специальных возможностях."
                    )
                }

                return
            }

            normalized == "домой" ||
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
                        "Главный экран"
                    )
                } else {
                    speakAndResume(
                        "Включите мой доступ " +
                            "в специальных возможностях."
                    )
                }

                return
            }

            normalized == "замолчи" ||
                normalized ==
                "прекрати говорить" -> {

                stopCurrentAudio()
                startWakeListening()
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

                if (lastAnswer != null) {
                    speakAndResume(
                        lastAnswer
                    )
                } else {
                    speakAndResume(
                        "Мне пока нечего повторять."
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

                conversationHistory.clear()

                speakAndResume(
                    "Хорошо. История разговора очищена."
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
                    "Громкость увеличена"
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
                    "Громкость уменьшена"
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
                    "Звук выключен"
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
                    "Звук включён"
                )

                return
            }

            normalized
                .startsWith("нажми ") -> {

                val target =
                    normalized
                        .removePrefix(
                            "нажми "
                        )
                        .trim()

                clickByText(target)
                return
            }

            normalized
                .startsWith("выбери ") -> {

                val target =
                    normalized
                        .removePrefix(
                            "выбери "
                        )
                        .trim()

                clickByText(target)
                return
            }

            (
                normalized.contains("ютуб") &&
                (
                    normalized.contains("найди ") ||
                    normalized.contains("ищи ") ||
                    normalized.contains("поищи ") ||
                    normalized.contains("поиск ")
                )
            ) -> {

                val query =
                    extractYouTubeQuery(
                        normalized
                    )

                if (query.isNotBlank()) {
                    openYouTubeSearch(query)
                } else {
                    openApp(
                        "YouTube",
                        "com.google.android.youtube"
                    )
                }

                return
            }

            normalized
                .startsWith(
                    "найди в ютубе "
                ) ||
                normalized
                    .startsWith(
                        "найди на ютубе "
                    ) ||
                normalized
                    .startsWith(
                        "поиск в ютубе "
                    ) -> {

                val query =
                    normalized
                        .substringAfter(
                            "ютубе"
                        )
                        .trim()

                if (query.isNotBlank()) {
                    openYouTubeSearch(query)
                } else {
                    startWakeListening()
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
                        normalized
                            .contains("google")
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

                if (query.isNotBlank()) {
                    openGoogleSearch(query)
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

                if (query.isNotBlank()) {
                    openMapSearch(query)
                } else {
                    startWakeListening()
                }

                return
            }
        }

        val target =
            normalized
                .removePrefix("открой ")
                .removePrefix("запусти ")
                .removePrefix("включи ")
                .trim()

        when (target) {

            "youtube",
            "ютуб" ->
                openApp(
                    "YouTube",
                    "com.google.android.youtube"
                )

            "chrome",
            "хром",
            "гугл хром" ->
                openApp(
                    "Chrome",
                    "com.android.chrome"
                )

            "браузер",
            "интернет",
            "самсунг интернет" ->
                openApp(
                    "браузер",
                    "com.sec.android.app.sbrowser",
                    "com.android.chrome"
                )

            "gmail",
            "джимейл",
            "почта",
            "электронная почта" ->
                openApp(
                    "почту",
                    "com.google.android.gm",
                    "com.samsung.android.email.provider"
                )

            "карты",
            "google maps",
            "гугл карты" ->
                openApp(
                    "Google Maps",
                    "com.google.android.apps.maps"
                )

            "play market",
            "play store",
            "плей маркет",
            "гугл плей" ->
                openApp(
                    "Google Play",
                    "com.android.vending"
                )

            "камера",
            "камеру" ->
                openApp(
                    "камеру",
                    "com.sec.android.app.camera"
                )

            "галерея",
            "фото" ->
                openApp(
                    "галерею",
                    "com.sec.android.gallery3d",
                    "com.google.android.apps.photos"
                )

            "google фото",
            "гугл фото" ->
                openApp(
                    "Google Фото",
                    "com.google.android.apps.photos",
                    "com.sec.android.gallery3d"
                )

            "файлы",
            "мои файлы" ->
                openApp(
                    "Мои файлы",
                    "com.sec.android.app.myfiles"
                )

            "калькулятор" ->
                openApp(
                    "калькулятор",
                    "com.sec.android.app.popupcalculator"
                )

            "календарь" ->
                openApp(
                    "календарь",
                    "com.samsung.android.calendar",
                    "com.google.android.calendar"
                )

            "часы",
            "будильник" ->
                openApp(
                    "часы",
                    "com.sec.android.app.clockpackage"
                )

            "сообщения",
            "смс" ->
                openApp(
                    "сообщения",
                    "com.samsung.android.messaging",
                    "com.google.android.apps.messaging"
                )

            "контакты" ->
                openApp(
                    "контакты",
                    "com.samsung.android.app.contacts",
                    "com.google.android.contacts"
                )

            "chatgpt",
            "чат gpt",
            "чатгпт",
            "чат джипити" ->
                openApp(
                    "ChatGPT",
                    "com.openai.chatgpt"
                )

            "telegram",
            "телеграм" ->
                openApp(
                    "Telegram",
                    "org.telegram.messenger"
                )

            "whatsapp",
            "ватсап",
            "вотсап" ->
                openApp(
                    "WhatsApp",
                    "com.whatsapp"
                )

            "google",
            "гугл" ->
                openApp(
                    "Google",
                    "com.google.android.googlequicksearchbox"
                )

            "диск",
            "google диск",
            "гугл диск" ->
                openApp(
                    "Google Диск",
                    "com.google.android.apps.docs"
                )

            "заметки",
            "samsung notes",
            "самсунг ноутс" ->
                openApp(
                    "Samsung Notes",
                    "com.samsung.android.app.notes"
                )

            "настройки" ->
                openSystemScreen(
                    Settings.ACTION_SETTINGS,
                    "настройки"
                )

            "wifi",
            "wi-fi",
            "вай фай",
            "вайфай" ->
                openSystemScreen(
                    Settings.ACTION_WIFI_SETTINGS,
                    "настройки Wi-Fi"
                )

            "bluetooth",
            "блютуз" ->
                openSystemScreen(
                    Settings.ACTION_BLUETOOTH_SETTINGS,
                    "настройки Bluetooth"
                )

            "звук",
            "настройки звука" ->
                openSystemScreen(
                    Settings.ACTION_SOUND_SETTINGS,
                    "настройки звука"
                )

            "экран",
            "настройки экрана",
            "дисплей" ->
                openSystemScreen(
                    Settings.ACTION_DISPLAY_SETTINGS,
                    "настройки экрана"
                )

            "специальные возможности",
            "спец возможности" ->
                openSystemScreen(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    "специальные возможности"
                )

            "геолокация",
            "местоположение",
            "локация" ->
                openSystemScreen(
                    Settings.ACTION_LOCATION_SOURCE_SETTINGS,
                    "настройки местоположения"
                )

            "безопасность",
            "настройки безопасности" ->
                openSystemScreen(
                    Settings.ACTION_SECURITY_SETTINGS,
                    "настройки безопасности"
                )

            "дата и время",
            "время и дата" ->
                openSystemScreen(
                    Settings.ACTION_DATE_SETTINGS,
                    "настройки даты и времени"
                )

            else ->
                askAyana(
                    originalCommand
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

        for (marker in markers) {

            val index =
                command.indexOf(marker)

            if (index >= 0) {

                var query =
                    command
                        .substring(
                            index + marker.length
                        )
                        .trim()

                query =
                    query
                        .removePrefix("в ютубе ")
                        .removePrefix("на ютубе ")
                        .removePrefix("youtube ")
                        .removePrefix("ютуб ")
                        .removePrefix("музыку ")
                        .removePrefix("музыка ")
                        .trim()

                return query
            }
        }

        return ""
    }

    private fun clickByText(
        target: String
    ) {

        if (target.isBlank()) {
            speakAndResume(
                "Скажите, что именно нажать."
            )
            return
        }

        val service =
            AgentAccessibilityService.instance

        if (service == null) {
            speakAndResume(
                "Включите мой доступ " +
                    "в специальных возможностях."
            )
            return
        }

        val success =
            service.clickByText(target)

        if (success) {
            finishLocalCommand(
                "Нажимаю: $target"
            )
        } else {
            speakAndResume(
                "Я не нашла на экране элемент $target."
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
        vararg packages: String
    ) {

        for (packageName in packages) {

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
                    "Открываю $displayName"
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

        speakAndResume(
            "Приложение $displayName не найдено."
        )
    }

    private fun openSystemScreen(
        action: String,
        displayName: String
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
                "Открываю $displayName"
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            speakAndResume(
                "Не удалось открыть $displayName."
            )
        }
    }

    private fun openYouTubeSearch(
        query: String
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
                "Ищу в YouTube: $query"
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
                    "Ищу в YouTube: $query"
                )

            } catch (
                _: ActivityNotFoundException
            ) {

                speakAndResume(
                    "Не удалось открыть YouTube."
                )
            }
        }
    }

    private fun openGoogleSearch(
        query: String
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
                "Ищу в Google: $query"
            )

        } catch (
            _: ActivityNotFoundException
        ) {

            speakAndResume(
                "Не удалось открыть поиск."
            )
        }
    }

    private fun openMapSearch(
        query: String
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
                "Ищу на карте: $query"
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
                    "Ищу на карте: $query"
                )

            } catch (
                _: ActivityNotFoundException
            ) {

                speakAndResume(
                    "Не удалось открыть карты."
                )
            }
        }
    }

    private fun finishLocalCommand(
        text: String
    ) {

        broadcastStatus(
            text,
            STATE_THINKING
        )

        updateNotification(text)

        mainHandler.postDelayed(
            {
                startWakeListening()
            },
            650L
        )
    }

    // =========================================================
    // AI TEXT
    // =========================================================

    private fun askAyana(
        message: String
    ) {

        pauseRecognition()
        listenMode =
            ListenMode.BUSY

        broadcastStatus(
            "Думаю…",
            STATE_THINKING
        )

        updateNotification(
            "AYANA думает…"
        )

        Thread {

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
                                append(it.first)
                                append("\n")

                                append("AYANA: ")
                                append(it.second)
                                append("\n")
                            }

                        append(
                            "Пользователь: "
                        )
                        append(message)
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
                        responseCode in 200..299
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
                    responseCode !in 200..299
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

                if (
                    conversationHistory.size >= 10
                ) {
                    conversationHistory
                        .removeAt(0)
                }

                conversationHistory.add(
                    message to answer
                )

                mainHandler.post {
                    speakAndResume(answer)
                }

            } catch (_: Exception) {

                mainHandler.post {
                    speakAndResume(
                        "Не удалось связаться с сервером. " +
                            "Проверьте интернет."
                    )
                }

            } finally {
                connection?.disconnect()
            }

        }.start()
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

        pauseRecognition()

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

        Thread {

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
                    token != audioToken ||
                    shuttingDown
                ) {
                    file.delete()
                    return@Thread
                }

                val readyAudio =
                    file

                mainHandler.post {

                    if (
                        token == audioToken &&
                        !shuttingDown
                    ) {

                        playFile(
                            readyAudio,
                            deleteAfter = true
                        ) {
                            startWakeListening()
                        }

                    } else {
                        readyAudio.delete()
                    }
                }

            } catch (_: Exception) {

                file?.delete()

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
        }.start()
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
                    put("text", text)
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

            if (
                responseCode !in 200..299
            ) {
                throw IllegalStateException(
                    "TTS HTTP $responseCode"
                )
            }

            connection.inputStream
                .use { input ->

                    target.outputStream()
                        .use { output ->

                            input.copyTo(output)
                        }
                }

        } finally {
            connection?.disconnect()
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
            mediaPlayer = null
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
        keepToken: Boolean = false
    ) {

        if (!keepToken) {
            audioToken++
        }

        val player =
            mediaPlayer

        mediaPlayer = null

        if (player != null) {

            try {
                if (player.isPlaying) {
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

        currentStatusText = text
        currentStatusState = state

        sendBroadcast(
            Intent(
                ACTION_STATUS
            ).apply {

                setPackage(packageName)

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
    // STOP
    // =========================================================

    private fun shutdownAyana() {

        if (shuttingDown) {
            return
        }

        shuttingDown = true
        isRunning = false

        broadcastStatus(
            "AYANA остановлена",
            STATE_STOPPED
        )

        pauseRecognition()
        stopCurrentAudio()

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        mainHandler.removeCallbacksAndMessages(
            null
        )

        if (Build.VERSION.SDK_INT >= 24) {
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

        shuttingDown = true
        isRunning = false

        pauseRecognition()
        stopCurrentAudio()

        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        speechRecognizer = null

        mainHandler.removeCallbacksAndMessages(
            null
        )

        super.onDestroy()
    }

    companion object {

        const val ACTION_START =
            "kg.autonomous.agent.action.START_AYANA"

        const val ACTION_STOP =
            "kg.autonomous.agent.action.STOP_AYANA"

        const val ACTION_STATUS =
            "kg.autonomous.agent.action.AYANA_STATUS"

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

        const val STATE_LISTENING =
            "listening"

        const val STATE_COMMAND =
            "command"

        const val STATE_THINKING =
            "thinking"

        const val STATE_SPEAKING =
            "speaking"

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
