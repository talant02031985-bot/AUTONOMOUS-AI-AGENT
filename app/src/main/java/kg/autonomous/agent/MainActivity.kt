package kg.autonomous.agent

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var tts: TextToSpeech

    private val voiceLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->

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
            text = "AUTONOMOUS Voice Lite готов"
            textSize = 24f
            setPadding(32, 32, 32, 32)
        }

        val voiceButton = Button(this).apply {
            text = "🎤 Говорить"
            setOnClickListener {
                startSystemVoiceRecognition()
            }
        }

        val stopButton = Button(this).apply {
            text = "⛔ STOP"
            setOnClickListener {
                tts.stop()
                statusText.text = "Остановлено"
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)

            addView(statusText)
            addView(voiceButton)
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
                    "Скажите команду для AUTONOMOUS"
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

        val normalized = command.lowercase(Locale.getDefault())

        val answer = when {

            "привет" in normalized ->
                "Здравствуйте. Я AUTONOMOUS. Слушаю вас."

            "кто ты" in normalized ->
                "Я ваш голосовой помощник AUTONOMOUS."

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

        if (status == TextToSpeech.SUCCESS) {

            val russianLocale = Locale("ru", "RU")
            tts.language = russianLocale

            val russianVoices =
                tts.voices?.filter {
                    it.locale.language == "ru"
                }

            val preferredVoice =
                russianVoices?.firstOrNull {

                    val name = it.name.lowercase()

                    "female" in name ||
                    "woman" in name ||
                    "fem" in name
                }
                    ?: russianVoices?.firstOrNull()

            if (preferredVoice != null) {
                tts.voice = preferredVoice
            }

            tts.setSpeechRate(0.95f)
            tts.setPitch(1.05f)

            speak("AUTONOMOUS готова к работе.")
        }
    }

    override fun onDestroy() {

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }
}
