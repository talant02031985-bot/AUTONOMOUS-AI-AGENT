package kg.autonomous.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
import android.speech.tts.TextToSpeech
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var statusText: TextView
    private lateinit var speechRecognizer: SpeechRecognizer
    private lateinit var tts: TextToSpeech

    private val microphonePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                startListening()
            } else {
                speak("Для голосового управления разрешите доступ к микрофону.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        statusText = TextView(this).apply {
            text = "AUTONOMOUS готов"
            textSize = 24f
            setPadding(32, 32, 32, 32)
        }

        val voiceButton = Button(this).apply {
            text = "🎤 Говорить"
            setOnClickListener { checkMicrophoneAndListen() }
        }

        val accessibilityButton = Button(this).apply {
            text = "Включить доступ к экрану Android"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        val stopButton = Button(this).apply {
            text = "STOP"
            setOnClickListener {
                if (::speechRecognizer.isInitialized) {
                    speechRecognizer.cancel()
                }
                tts.stop()
                statusText.text = "Остановлено"
            }
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 60, 40, 40)
            addView(statusText)
            addView(voiceButton)
            addView(accessibilityButton)
            addView(stopButton)
        }

        setContentView(layout)

        setupSpeechRecognizer()
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            statusText.text = "Распознавание речи недоступно"
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer.setRecognitionListener(object : RecognitionListener {

            override fun onReadyForSpeech(params: Bundle?) {
                statusText.text = "Слушаю..."
            }

            override fun onResults(results: Bundle?) {
                val matches =
                    results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)

                val command = matches?.firstOrNull()

                if (!command.isNullOrBlank()) {
                    statusText.text = "Вы: $command"
                    processCommand(command)
                }
            }

            override fun onError(error: Int) {
                statusText.text = "Нажмите микрофон и повторите команду"
            }

            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
    }

    private fun checkMicrophoneAndListen() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (!::speechRecognizer.isInitialized) return

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

        speechRecognizer.startListening(intent)
    }

    private fun processCommand(command: String) {
        val answer = when {
            command.contains("привет", ignoreCase = true) ->
                "Здравствуйте. Я AUTONOMOUS. Слушаю вас."

            command.contains("кто ты", ignoreCase = true) ->
                "Я ваш голосовой помощник AUTONOMOUS."

            else ->
                "Я услышала: $command"
        }

        speak(answer)
    }

    private fun speak(text: String) {
        statusText.text = text

        if (::tts.isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "AUTONOMOUS")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("ru", "RU")

            // Предпочитаем подходящий русский женский голос,
            // если установленный TTS-движок предоставляет его.
            val russianVoices = tts.voices?.filter {
                it.locale.language == "ru"
            }

            val preferredVoice = russianVoices?.firstOrNull {
                val name = it.name.lowercase()
                "female" in name || "woman" in name
            }

            if (preferredVoice != null) {
                tts.voice = preferredVoice
            }

            tts.setSpeechRate(0.95f)
            tts.setPitch(1.05f)
        }
    }

    override fun onDestroy() {
        if (::speechRecognizer.isInitialized) {
            speechRecognizer.destroy()
        }

        tts.stop()
        tts.shutdown()

        super.onDestroy()
    }
}
