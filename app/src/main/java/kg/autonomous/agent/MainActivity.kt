package kg.autonomous.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView

    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var speechToken: Long = 0L

    private val conversationHistory =
        mutableListOf<Pair<String, String>>()

    private val voiceLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->

            if (result.resultCode == Activity.RESULT_OK) {

                val results =
                    result.data?.getStringArrayListExtra(
                        RecognizerIntent.EXTRA_RESULTS
                    )

                val command = results?.firstOrNull()

                if (!command.isNullOrBlank()) {
                    statusText.text = "Вы: $command"
                    processCommand(command)
                } else {
                    statusText.text = "Команда не распознана"
                }

            } else {
                statusText.text = "Голосовой ввод отменён"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        statusText = TextView(this).apply {
            text = "AYANA AI готова к работе"
            textSize = 24f
            setPadding(32, 32, 32, 32)
        }

        val voiceButton = Button(this).apply {
            text = "🎤 ГОВОРИТЬ"

            setOnClickListener {
                startSystemVoiceRecognition()
            }
        }

        val testVoiceButton = Button(this).apply {
            text = "🔊 ПРОВЕРИТЬ ГОЛОС AYANA"

            setOnClickListener {
                speak(
                    "Ну привет! Я Аяна. " +
                        "Рада тебя слышать. " +
                        "Что будем делать?"
                )
            }
        }

        val accessibilityButton = Button(this).apply {
            text = "⚙ ДОСТУП К УПРАВЛЕНИЮ"

            setOnClickListener {
                openSystemScreen(
                    Settings.ACTION_ACCESSIBILITY_SETTINGS,
                    "специальные возможности"
                )
            }
        }

        val stopButton = Button(this).apply {
            text = "⛔ STOP"

            setOnClickListener {
                stopSpeech()
                statusText.text = "Остановлено"
            }
        }

        val disclosureText = TextView(this).apply {
            text = "Голос AYANA синтезирован искусственным интеллектом."
            textSize = 13f
            setPadding(10, 24, 10, 10)
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)

            addView(statusText)
            addView(voiceButton)
            addView(testVoiceButton)
            addView(accessibilityButton)
            addView(stopButton)
            addView(disclosureText)
        }

        setContentView(layout)

        // Короткое приветствие нейронным голосом AYANA.
        speak("Здравствуйте. Я Аяна. Готова к работе.")
    }

    private fun startSystemVoiceRecognition() {

        val intent =
            Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    "ru-RU"
                )

                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "Скажите команду для Аяны"
                )
            }

        try {
            voiceLauncher.launch(intent)
        } catch (_: Exception) {
            statusText.text =
                "На устройстве не найден сервис распознавания речи"
        }
    }

    private fun processCommand(command: String) {

        val normalized = command
            .lowercase(Locale.getDefault())
            .replace("аяна", "")
            .replace("айана", "")
            .replace("ayana", "")
            .replace("пожалуйста", "")
            .replace(Regex("[,!?;:]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // =====================================
        // СИСТЕМНЫЕ КОМАНДЫ
        // =====================================

        when {

            normalized == "назад" ||
                normalized == "вернись назад" -> {

                val success =
                    AgentAccessibilityService
                        .instance
                        ?.pressBack() == true

                if (success) {
                    statusText.text = "Назад"
                } else {
                    accessibilityWarning()
                }

                return
            }

            normalized == "домой" ||
                normalized == "на главный экран" ||
                normalized == "главный экран" -> {

                val success =
                    AgentAccessibilityService
                        .instance
                        ?.pressHome() == true

                if (success) {
                    statusText.text = "Главный экран"
                } else {
                    accessibilityWarning()
                }

                return
            }

            normalized == "стоп" ||
                normalized == "замолчи" ||
                normalized == "остановись" -> {

                stopSpeech()
                statusText.text = "Остановлено"
                return
            }

            normalized.startsWith("нажми ") -> {

                val target =
                    normalized.removePrefix("нажми ").trim()

                clickByVoice(target)
                return
            }

            normalized.startsWith("выбери ") -> {

                val target =
                    normalized.removePrefix("выбери ").trim()

                clickByVoice(target)
                return
            }

            // =====================================
            // ПАМЯТЬ ДИАЛОГА
            // =====================================

            normalized == "повтори" ||
                normalized == "повтори ответ" -> {

                val lastAnswer =
                    conversationHistory
                        .lastOrNull()
                        ?.second

                if (lastAnswer != null) {
                    speak(lastAnswer)
                } else {
                    speak("Мне пока нечего повторять.")
                }

                return
            }

            normalized == "забудь разговор" ||
                normalized == "очисти память" ||
                normalized == "очисти историю" -> {

                conversationHistory.clear()

                speak(
                    "Хорошо. История разговора очищена."
                )

                return
            }

            // =====================================
            // ГРОМКОСТЬ
            // =====================================

            normalized == "громче" ||
                normalized == "увеличь громкость" ||
                normalized == "сделай громче" -> {

                changeVolume(
                    AudioManager.ADJUST_RAISE
                )

                statusText.text =
                    "Громкость увеличена"

                return
            }

            normalized == "тише" ||
                normalized == "уменьши громкость" ||
                normalized == "сделай тише" -> {

                changeVolume(
                    AudioManager.ADJUST_LOWER
                )

                statusText.text =
                    "Громкость уменьшена"

                return
            }

            normalized == "выключи звук" ||
                normalized == "без звука" -> {

                changeVolume(
                    AudioManager.ADJUST_MUTE
                )

                statusText.text =
                    "Звук выключен"

                return
            }

            normalized == "включи звук" ||
                normalized == "верни звук" -> {

                changeVolume(
                    AudioManager.ADJUST_UNMUTE
                )

                statusText.text =
                    "Звук включён"

                return
            }

            // =====================================
            // ПОИСК В YOUTUBE
            // =====================================

            normalized.startsWith(
                "найди в ютубе "
            ) ||
                normalized.startsWith(
                    "найди на ютубе "
                ) ||
                normalized.startsWith(
                    "поиск в ютубе "
                ) -> {

                val query =
                    normalized
                        .substringAfter("ютубе")
                        .trim()

                if (query.isNotBlank()) {
                    openYouTubeSearch(query)
                }

                return
            }

            // =====================================
            // ПОИСК GOOGLE
            // =====================================

            normalized.startsWith(
                "найди в google "
            ) ||
                normalized.startsWith(
                    "найди в гугле "
                ) ||
                normalized.startsWith(
                    "поищи в google "
                ) ||
                normalized.startsWith(
                    "поищи в гугле "
                ) -> {

                val query =
                    if (
                        normalized.contains("google")
                    ) {
                        normalized
                            .substringAfter("google")
                            .trim()
                    } else {
                        normalized
                            .substringAfter("гугле")
                            .trim()
                    }

                if (query.isNotBlank()) {
                    openGoogleSearch(query)
                }

                return
            }

            // =====================================
            // ПОИСК НА КАРТЕ
            // =====================================

            normalized.startsWith(
                "найди на карте "
            ) ||
                normalized.startsWith(
                    "найди в картах "
                ) ||
                normalized.startsWith(
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
                }

                return
            }
        }

        // Убираем вводные слова у команд запуска.
        val target = normalized
            .removePrefix("открой ")
            .removePrefix("запусти ")
            .removePrefix("включи ")
            .trim()

        // =====================================
        // ПРИЛОЖЕНИЯ И НАСТРОЙКИ
        // =====================================

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

            else -> {
                statusText.text =
                    "Аяна думает..."

                askAyana(command)
            }
        }
    }

    private fun accessibilityWarning() {

        statusText.text =
            "Включите доступ AYANA " +
                "в специальных возможностях."

        speak(
            "Для управления планшетом " +
                "включите мой доступ " +
                "в специальных возможностях."
        )
    }

    private fun clickByVoice(target: String) {

        if (target.isBlank()) {
            speak("Скажите, что именно нажать.")
            return
        }

        val service =
            AgentAccessibilityService.instance

        if (service == null) {
            accessibilityWarning()
            return
        }

        val success =
            service.clickByText(target)

        if (success) {
            statusText.text =
                "Нажимаю: $target"
        } else {
            statusText.text =
                "Не нашла на экране: $target"

            speak(
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

                        setPackage(packageName)
                    }

                startActivity(intent)

                statusText.text =
                    "Открываю $displayName"

                return

            } catch (
                _: ActivityNotFoundException
            ) {
                // Пробуем следующий пакет.
            } catch (
                _: SecurityException
            ) {
                // Пробуем следующий пакет.
            }
        }

        statusText.text =
            "Приложение $displayName не найдено"

        speak(
            "Приложение $displayName не найдено."
        )
    }

    private fun openSystemScreen(
        action: String,
        displayName: String
    ) {

        try {

            startActivity(
                Intent(action)
            )

            statusText.text =
                "Открываю $displayName"

        } catch (
            _: ActivityNotFoundException
        ) {

            statusText.text =
                "Не удалось открыть $displayName"

            speak(
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

            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).apply {

                    setPackage(
                        "com.google.android.youtube"
                    )
                }

            startActivity(intent)

            statusText.text =
                "Ищу в YouTube: $query"

        } catch (
            _: ActivityNotFoundException
        ) {

            try {

                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        uri
                    )
                )

                statusText.text =
                    "Открываю поиск YouTube"

            } catch (
                _: ActivityNotFoundException
            ) {

                speak(
                    "Не удалось открыть поиск YouTube."
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
                )
            )

            statusText.text =
                "Ищу в Google: $query"

        } catch (
            _: ActivityNotFoundException
        ) {

            speak(
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

            val intent =
                Intent(
                    Intent.ACTION_VIEW,
                    uri
                ).apply {

                    setPackage(
                        "com.google.android.apps.maps"
                    )
                }

            startActivity(intent)

            statusText.text =
                "Ищу на карте: $query"

        } catch (
            _: ActivityNotFoundException
        ) {

            try {

                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        uri
                    )
                )

            } catch (
                _: ActivityNotFoundException
            ) {

                speak(
                    "Не удалось открыть карты."
                )
            }
        }
    }

    // =====================================
    // ТЕКСТОВЫЙ ИИ AYANA
    // =====================================

    private fun askAyana(
        message: String
    ) {

        Thread {

            try {

                val url =
                    URL(
                        "https://ayana-ai.talant02031985.workers.dev/"
                    )

                val connection =
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
                    30000

                connection.doOutput =
                    true

                val conversationContext =
                    buildString {

                        conversationHistory
                            .takeLast(5)
                            .forEach { turn ->

                                append(
                                    "Пользователь: "
                                )

                                append(turn.first)
                                append("\n")

                                append("AYANA: ")
                                append(turn.second)
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
                            conversationContext
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

                connection.disconnect()

                val json =
                    JSONObject(
                        responseText
                    )

                val answer =
                    if (
                        responseCode in 200..299
                    ) {

                        json.optString(
                            "reply",
                            "Я не смогла сформировать ответ."
                        )

                    } else {

                        "Ошибка подключения к ИИ."
                    }

                runOnUiThread {

                    conversationHistory.add(
                        message to answer
                    )

                    if (
                        conversationHistory.size >
                        10
                    ) {

                        conversationHistory
                            .removeAt(0)
                    }

                    statusText.text =
                        answer

                    speak(answer)
                }

            } catch (
                _: Exception
            ) {

                runOnUiThread {

                    statusText.text =
                        "Не удалось связаться с ИИ."

                    speak(
                        "Не удалось связаться " +
                            "с сервером. " +
                            "Проверьте интернет."
                    )
                }
            }

        }.start()
    }

    // =====================================
    // НЕЙРОННЫЙ ГОЛОС AYANA / MARIN
    // =====================================

    private fun speak(
        text: String
    ) {

        statusText.text = text

        if (text.isBlank()) {
            return
        }

        val token =
            speechToken + 1L

        speechToken = token

        releaseMediaPlayer()

        Thread {

            var connection:
                HttpsURLConnection? = null

            var tempFile:
                File? = null

            try {

                val url =
                    URL(
                        "https://ayana-ai.talant02031985.workers.dev/tts"
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

                    val errorMessage =
                        connection.errorStream
                            ?.bufferedReader()
                            ?.use {
                                it.readText()
                            }
                            ?: "Unknown TTS error"

                    throw IllegalStateException(
                        "TTS HTTP $responseCode: $errorMessage"
                    )
                }

                tempFile =
                    File.createTempFile(
                        "ayana_voice_",
                        ".mp3",
                        cacheDir
                    )

                connection.inputStream
                    .use { input ->

                        tempFile.outputStream()
                            .use { output ->

                                input.copyTo(output)
                            }
                    }

                if (
                    token != speechToken
                ) {

                    tempFile.delete()
                    return@Thread
                }

                val audioFile =
                    tempFile

                runOnUiThread {

                    if (
                        token ==
                        speechToken
                    ) {

                        playAudioFile(
                            audioFile,
                            token
                        )

                    } else {

                        audioFile.delete()
                    }
                }

            } catch (
                _: Exception
            ) {

                tempFile?.delete()

                if (
                    token ==
                    speechToken
                ) {

                    runOnUiThread {

                        statusText.text =
                            "$text\n\nГолос временно недоступен."
                    }
                }

            } finally {

                connection?.disconnect()
            }

        }.start()
    }

    private fun playAudioFile(
        file: File,
        token: Long
    ) {

        releaseMediaPlayer()

        val player =
            MediaPlayer()

        mediaPlayer =
            player

        try {

            player.setDataSource(
                file.absolutePath
            )

            player.setOnPreparedListener {
                mediaPlayerPrepared ->

                if (
                    token ==
                    speechToken
                ) {

                    mediaPlayerPrepared.start()

                } else {

                    try {
                        mediaPlayerPrepared.release()
                    } catch (_: Exception) {
                    }

                    if (
                        mediaPlayer ===
                        mediaPlayerPrepared
                    ) {

                        mediaPlayer =
                            null
                    }

                    file.delete()
                }
            }

            player.setOnCompletionListener {
                completedPlayer ->

                if (
                    mediaPlayer ===
                    completedPlayer
                ) {

                    mediaPlayer =
                        null
                }

                try {
                    completedPlayer.release()
                } catch (_: Exception) {
                }

                file.delete()
            }

            player.setOnErrorListener {
                errorPlayer,
                _,
                _ ->

                if (
                    mediaPlayer ===
                    errorPlayer
                ) {

                    mediaPlayer =
                        null
                }

                try {
                    errorPlayer.release()
                } catch (_: Exception) {
                }

                file.delete()

                true
            }

            player.prepareAsync()

        } catch (
            _: Exception
        ) {

            if (
                mediaPlayer ===
                player
            ) {

                mediaPlayer =
                    null
            }

            try {
                player.release()
            } catch (_: Exception) {
            }

            file.delete()

            statusText.text =
                "Не удалось воспроизвести голос AYANA."
        }
    }

    private fun stopSpeech() {

        speechToken =
            speechToken + 1L

        releaseMediaPlayer()
    }

    private fun releaseMediaPlayer() {

        val player =
            mediaPlayer

        mediaPlayer =
            null

        if (
            player != null
        ) {

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

    override fun onDestroy() {

        stopSpeech()

        super.onDestroy()
    }
}
