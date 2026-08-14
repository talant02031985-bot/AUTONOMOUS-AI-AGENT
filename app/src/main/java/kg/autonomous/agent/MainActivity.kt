package kg.autonomous.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech

    private var russianVoices: List<Voice> = emptyList()
    private var currentVoiceIndex = 0

    private val preferences by lazy {
        getSharedPreferences("autonomous_settings", MODE_PRIVATE)
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
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {

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

        val normalized =
            command.lowercase(Locale.getDefault())

        val answer = when {

            "привет" in normalized ->
                "Здравствуйте. Я Аяна. Слушаю вас."

            "кто ты" in normalized ->
                "Я ваш голосовой помощник Аяна."

            "как дела" in normalized ->
                "Всё работает. Я готова выполнять ваши команды."

            "стоп" in normalized -> {
                tts.stop()
                "Остановлено."
            }

            else ->
                "Я услышала: $command"
        }

        speak(answer)
    }

    private fun selectNextRussianVoice() {

        if (russianVoices.isEmpty()) {
            statusText.text =
                "Русские голоса не найдены"
            return
        }

        currentVoiceIndex++

        if (currentVoiceIndex >= russianVoices.size) {
            currentVoiceIndex = 0
        }

        val selectedVoice =
            russianVoices[currentVoiceIndex]

        tts.voice = selectedVoice

        preferences.edit()
            .putString(
                "selected_voice",
                selectedVoice.name
            )
            .apply()

        statusText.text =
            "Голос ${currentVoiceIndex + 1} из ${russianVoices.size}"

        speak(
            "Здравствуйте. Я Аяна. " +
            "Если вам нравится этот голос, " +
            "просто оставьте его."
        )
    }

    private fun speak(text: String) {

        statusText.text = text

        if (::tts.isInitialized) {

            tts.speak(
                text,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "AUTONOMOUS"
            )
        }
    }

    override fun onInit(status: Int) {

        if (status != TextToSpeech.SUCCESS) {
            statusText.text =
                "Не удалось запустить голосовой движок"
            return
        }

        val russianLocale =
            Locale("ru", "RU")

        tts.language = russianLocale

        russianVoices =
            tts.voices
                ?.filter {
                    it.locale.language == "ru"
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

        if (russianVoices.isNotEmpty()) {

            val savedIndex =
                russianVoices.indexOfFirst {
                    it.name == savedVoiceName
                }

            currentVoiceIndex =
                if (savedIndex >= 0) {
                    savedIndex
                } else {
                    0
                }

            tts.voice =
                russianVoices[currentVoiceIndex]
        }

        tts.setSpeechRate(1.02f)
        tts.setPitch(1.14f)
        

        statusText.text =
            "Здравствуйте. Я Аяна. Готова к работе."

        speak(
            "Здравствуйте. Я Аяна. Готова к работе."
        )
    }

    override fun onDestroy() {

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }
}
