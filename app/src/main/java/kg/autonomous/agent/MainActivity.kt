package kg.autonomous.agent

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.URL
import java.util.Locale
import javax.net.ssl.HttpsURLConnection

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech

    private var russianVoices: List<Voice> = emptyList()
    private var currentVoiceIndex = 0

    private val conversationHistory =
        mutableListOf<Pair<String, String>>()

    private val preferences by lazy {
        getSharedPreferences(
            "autonomous_settings",
            MODE_PRIVATE
        )
    }

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

        tts = TextToSpeech(this, this)

        statusText = TextView(this).apply {
            text = "Аяна запускается..."
            textSize = 24f
            setPadding(32, 32, 32, 32)
        }

        val voiceButton = Button(this).apply {
            text = "🎤 ГОВОРИТЬ"

            setOnClickListener {
                startSystemVoiceRecognition()
            }
        }

        val selectVoiceButton = Button(this).apply {
            text = "👩 СЛЕДУЮЩИЙ ГОЛОС"

            setOnClickListener {
                selectNextRussianVoice()
            }
        }

        val testVoiceButton = Button(this).apply {
            text = "🔊 ПРОВЕРИТЬ ГОЛОС"

            setOnClickListener {
                speak(
                    "Здравствуйте. Я Аяна. " +
                        "Это мой текущий голос."
                )
            }
        }

        val stopButton = Button(this).apply {
            text = "⛔ STOP"

            setOnClickListener {
                if (::tts.isInitialized) {
                    tts.stop()
                }

                statusText.text = "Остановлено"
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)

            addView(statusText)
            addView(voiceButton)
            addView(selectVoiceButton)
            addView(testVoiceButton)
            addView(stopButton)
        }

        setContentView(layout)
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
                    "Скажите команду для Аяна"
                )
            }

        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
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

        // ==============================
        // СИСТЕМНЫЕ КОМАНДЫ
        // ==============================

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

                if (::tts.isInitialized) {
                    tts.stop()
                }

                statusText.text = "Остановлено"
                return
            }

            // ==============================
            // ПАМЯТЬ
            // ==============================

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

            // ==============================
            // ГРОМКОСТЬ
            // ==============================

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

            // ==============================
            // ПОИСК В YOUTUBE
            // ==============================

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

            // ==============================
            // ПОИСК GOOGLE
            // ==============================

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

            // ==============================
            // ПОИСК НА КАРТЕ
            // ==============================

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

        // Убираем слова "открой", "запусти", "включи"

        val target = normalized
            .removePrefix("открой ")
            .removePrefix("запусти ")
            .removePrefix("включи ")
            .trim()

        // ==============================
        // ПРИЛОЖЕНИЯ
        // ==============================

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

            // ==============================
            // НАСТРОЙКИ ПЛАНШЕТА
            // ==============================

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

            // Если это не команда планшету —
            // отправляем вопрос в ИИ

            else -> {
                statusText.text =
                    "Аяна думает..."

                askAyana(command)
            }
        }
    }

    // ==============================
    // ACCESSIBILITY
    // ==============================

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

    // ==============================
    // ГРОМКОСТЬ
    // ==============================

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

    // ==============================
    // ОТКРЫТИЕ ПРИЛОЖЕНИЙ
    // ==============================

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
                // Пробуем следующий пакет
            } catch (
                _: SecurityException
            ) {
                // Пробуем следующий пакет
            }
        }

        statusText.text =
            "Приложение $displayName не найдено"

        speak(
            "Приложение $displayName не найдено."
        )
    }

    // ==============================
    // СИСТЕМНЫЕ НАСТРОЙКИ
    // ==============================

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

    // ==============================
    // ПОИСК YOUTUBE
    // ==============================

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

    // ==============================
    // ПОИСК GOOGLE
    // ==============================

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

    // ==============================
    // ПОИСК НА КАРТЕ
    // ==============================

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

    // ==============================
    // AYANA AI / CLOUDFLARE / OPENAI
    // ==============================

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
                e: Exception
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

    // ==============================
    // ВЫБОР ГОЛОСА
    // ==============================

    private fun selectNextRussianVoice() {

        if (
            russianVoices.isEmpty()
        ) {

            statusText.text =
                "Русские голоса не найдены"

            return
        }

        currentVoiceIndex++

        if (
            currentVoiceIndex >=
            russianVoices.size
        ) {

            currentVoiceIndex = 0
        }

        val selectedVoice =
            russianVoices[
                currentVoiceIndex
            ]

        tts.voice =
            selectedVoice

        preferences.edit()
            .putString(
                "selected_voice",
                selectedVoice.name
            )
            .apply()

        statusText.text =
            "Голос ${currentVoiceIndex + 1} " +
                "из ${russianVoices.size}"

        speak(
            "Здравствуйте. Я Аяна. " +
                "Если вам нравится этот голос, " +
                "просто оставьте его."
        )
    }

    // ==============================
    // ПРОИЗНЕСЕНИЕ ОТВЕТА
    // ==============================

    private fun speak(
        text: String
    ) {

        statusText.text =
            text

        if (
            ::tts.isInitialized
        ) {

            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "AUTONOMOUS"
            )
        }
    }

    // ==============================
    // ИНИЦИАЛИЗАЦИЯ ГОЛОСА
    // ==============================

    override fun onInit(
        status: Int
    ) {

        if (
            status !=
            TextToSpeech.SUCCESS
        ) {

            statusText.text =
                "Не удалось запустить голосовой движок"

            return
        }

        val russianLocale =
            Locale(
                "ru",
                "RU"
            )

        tts.language =
            russianLocale

        russianVoices =
            tts.voices
                ?.filter {
                    it.locale.language ==
                        "ru"
                }
                ?.sortedBy {
                    it.name
                }
                ?: emptyList()

        val savedVoiceName =
            preferences.getString(
                "selected_voice",
                null
            )

        if (
            russianVoices.isNotEmpty()
        ) {

            val savedIndex =
                russianVoices
                    .indexOfFirst {
                        it.name ==
                            savedVoiceName
                    }

            currentVoiceIndex =
                if (
                    savedIndex >= 0
                ) {

                    savedIndex

                } else {

                    0
                }

            tts.voice =
                russianVoices[
                    currentVoiceIndex
                ]
        }

        tts.setSpeechRate(
            1.02f
        )

        tts.setPitch(
            1.14f
        )

        statusText.text =
            "Здравствуйте. Я Аяна. Готова к работе."

        speak(
            "Здравствуйте. Я Аяна. Готова к работе."
        )
    }

    override fun onDestroy() {

        if (
            ::tts.isInitialized
        ) {

            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }
}
