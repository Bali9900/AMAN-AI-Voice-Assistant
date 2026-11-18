
package com.aman.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val blue = Color.rgb(18, 91, 205)
    private val dark = Color.rgb(17, 45, 89)
    private val handler = Handler(Looper.getMainLooper())

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var listening = false
    private var wakeMode = false
    private var active = false
    private var speaking = false

    private var language = "bn-IN"

    private lateinit var status: TextView
    private lateinit var transcript: TextView
    private lateinit var input: EditText
    private lateinit var wakeButton: Button
    private lateinit var listenButton: Button
    private lateinit var languageSpinner: Spinner
    private lateinit var endpoint: EditText
    private lateinit var apiKey: EditText

    private val prefs by lazy {
        getSharedPreferences("aman_settings", MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = dark
        tts = TextToSpeech(this, this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.rgb(242, 247, 255))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(22, 32, 22, 28)
        }

        scroll.addView(root)

        fun makeText(
            s: String,
            size: Float,
            bold: Boolean = false
        ): TextView = TextView(this).apply {
            text = s
            textSize = size
            setTextColor(dark)
            if (bold) setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER_HORIZONTAL
        }

        fun makeButton(s: String): Button =
            Button(this).apply {
                text = s
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(blue)
                    cornerRadius = 24f
                }
                setPadding(12, 12, 12, 12)
            }

        fun add(v: View) {
            root.addView(
                v,
                LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = 16
                }
            )
        }

        add(makeText("✦ AMAN AI", 32f, true))
        add(makeText("Online + Offline Voice Assistant", 15f))

        status = makeText("Ready • Tap Speak", 17f, true)
        add(status)

        transcript = makeText("বলুন: Hey Aman", 18f)
        add(transcript)

        listenButton = makeButton("🎙 Speak Now")
        add(listenButton)

        wakeButton = makeButton(
            "Enable Hey Aman (App Open)"
        )
        add(wakeButton)

        add(makeText("Voice Language", 16f, true))

        languageSpinner = Spinner(this)

        val languages = listOf(
            "বাংলা (Bengali)",
            "हिन्दी (Hindi)",
            "English"
        )

        languageSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            languages
        )

        add(languageSpinner)

        languageSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {

                override fun onNothingSelected(
                    parent: android.widget.AdapterView<*>?
                ) {}

                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    language = listOf(
                        "bn-IN",
                        "hi-IN",
                        "en-IN"
                    )[position]

                    tts?.language =
                        Locale.forLanguageTag(language)

                    status.text = when (language) {
                        "bn-IN" -> "বাংলা ভাষা নির্বাচিত"
                        "hi-IN" -> "हिन्दी भाषा चुनी गई"
                        else -> "English selected"
                    }

                    if (listening) {
                        stopRecognition()
                        handler.postDelayed({
                            startRecognition()
                        }, 350)
                    }
                }
            }

        add(makeText("Online AI (Optional)", 17f, true))

        add(
            makeText(
                "Connect an OpenAI-compatible HTTPS endpoint for AI answers.",
                13f
            )
        )

        endpoint = EditText(this).apply {
            hint = "HTTPS chat completions URL"
            setSingleLine(true)
            setText(prefs.getString("endpoint", ""))
        }
        add(endpoint)

        apiKey = EditText(this).apply {
            hint = "API token (not saved)"
            setSingleLine(true)
            inputType = 129
        }
        add(apiKey)

        input = EditText(this).apply {
            hint = "Type a message or command"
            setSingleLine(false)
            minLines = 2
        }
        add(input)

        add(makeButton("Send Message").apply {
            setOnClickListener {
                val message =
                    input.text.toString().trim()

                if (message.isNotEmpty()) {
                    input.setText("")
                    handleCommand(message)
                }
            }
        })

        add(makeButton("Save Endpoint").apply {
            setOnClickListener {
                val url = endpoint.text.toString().trim()

                if (
                    url.isNotEmpty() &&
                    !url.startsWith("https://")
                ) {
                    status.text = "HTTPS URL required"
                } else {
                    prefs.edit()
                        .putString("endpoint", url)
                        .apply()

                    status.text = "Endpoint saved"
                }
            }
        })

        add(
            makeText(
                "Commands: time, date, camera, settings, alarm, torch settings. Voice recognition needs a supported phone speech service.",
                13f
            )
        )

        listenButton.setOnClickListener {
            wakeMode = false

            wakeButton.text =
                "Enable Hey Aman (App Open)"

            if (listening) {
                stopRecognition()
            } else {
                startRecognition()
            }
        }

        wakeButton.setOnClickListener {
            wakeMode = !wakeMode

            wakeButton.text = if (wakeMode) {
                "Disable Hey Aman"
            } else {
                "Enable Hey Aman (App Open)"
            }

            if (wakeMode) {
                startRecognition()
            } else {
                stopRecognition()
            }
        }

        setContentView(scroll)

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                10
            )
        }
    }

    override fun onInit(code: Int) {
        if (code == TextToSpeech.SUCCESS) {
            tts?.language =
                Locale.forLanguageTag(language)
        }
    }

    private fun say(message: String) {
        speaking = true

        tts?.language =
            Locale.forLanguageTag(language)

        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "aman_reply"
        )

        handler.postDelayed({
            speaking = false

            if (
                wakeMode &&
                active &&
                !listening
            ) {
                startRecognition()
            }
        }, 2400)
    }

    private fun stopRecognition() {
        listening = false
        recognizer?.cancel()
        status.text = "Ready"
    }

    private fun startRecognition() {

        if (!active || speaking || listening) return

        if (
            checkSelfPermission(
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                10
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text =
                "Speech recognition unavailable on this phone"
            return
        }

        recognizer?.destroy()

        recognizer =
            SpeechRecognizer.createSpeechRecognizer(this)

        recognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(
                    params: Bundle?
                ) {
                    status.text = if (wakeMode) {
                        "Listening for Hey Aman..."
                    } else {
                        when (language) {
                            "bn-IN" -> "বাংলায় বলুন..."
                            "hi-IN" -> "हिन्दी में बोलिए..."
                            else -> "Listening..."
                        }
                    }
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(
                    buffer: ByteArray?
                ) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {

                    listening = false

                    status.text = when (error) {
                        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
                            "Language not supported. Update Google Speech Services."

                        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                            "Language unavailable. Check speech language settings."

                        SpeechRecognizer.ERROR_NETWORK ->
                            "Network error. Check internet."

                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                            "Network timeout. Try again."

                        SpeechRecognizer.ERROR_NO_MATCH ->
                            "কথা বোঝা যায়নি। আবার বলুন।"

                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                            "কোনো কথা শোনা যায়নি। আবার বলুন।"

                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                            "Microphone permission required."

                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                            "Microphone busy. Try again."

                        SpeechRecognizer.ERROR_SERVER ->
                            "Speech server error. Try again."

                        else ->
                            "Voice error ($error). Tap Speak to retry."
                    }

                    if (wakeMode && active) {
                        handler.postDelayed({
                            if (
                                wakeMode &&
                                active &&
                                !listening
                            ) {
                                startRecognition()
                            }
                        }, 1500)
                    }
                }

                override fun onResults(results: Bundle?) {
                    listening = false

                    val heard = results
                        ?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        ?.firstOrNull()
                        .orEmpty()

                    transcript.text = "You: $heard"

                    if (wakeMode) {

                        val lower =
                            heard.lowercase(Locale.ROOT)

                        val wakeWords = listOf(
                            "hey aman",
                            "হে আমান",
                            "হেই আমান",
                            "हे अमन",
                            "hey amon"
                        )

                        val matched =
                            wakeWords.any {
                                lower.contains(it)
                            }

                        if (matched) {

                            val command = lower.replace(
                                Regex(
                                    "hey aman|হে আমান|হেই আমান|हे अमन|hey amon"
                                ),
                                ""
                            ).trim()

                            if (command.isNotEmpty()) {
                                handleCommand(command)
                            } else {
                                status.text = "Yes, tell me"

                                say(
                                    when (language) {
                                        "bn-IN" -> "হ্যাঁ, বলুন"
                                        "hi-IN" -> "जी, बोलिए"
                                        else -> "Yes, tell me"
                                    }
                                )
                            }

                        } else {
                            handler.postDelayed({
                                if (wakeMode && active) {
                                    startRecognition()
                                }
                            }, 450)
                        }

                    } else if (heard.isNotBlank()) {
                        handleCommand(heard)
                    } else {
                        status.text =
                            "No speech recognized. Try again."
                    }
                }

                override fun onPartialResults(
                    partialResults: Bundle?
                ) {}

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?
                ) {}
            }
        )

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                language
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                language
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )

            // Allow network recognition if supported.
            // This helps when offline Bengali models
            // are not installed on the device.
            putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                false
            )
        }

        listening = true

        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            listening = false
            status.text =
                "Microphone error: ${e.message}"
        }
    }

    private fun handleCommand(command: String) {

        transcript.text = "You: $command"

        val c = command.lowercase(Locale.ROOT)

        fun reply(
            bn: String,
            hi: String,
            en: String
        ): String = when (language) {
            "bn-IN" -> bn
            "hi-IN" -> hi
            else -> en
        }

        val answer: String? = when {

            listOf(
                "time", "সময়", "সময়", "समय"
            ).any { c.contains(it) } -> {

                val time = java.text.SimpleDateFormat(
                    "hh:mm a",
                    Locale.US
                ).format(java.util.Date())

                reply(
                    "এখন সময় $time",
                    "अभी समय $time",
                    "The time is $time"
                )
            }

            listOf(
                "date", "তারিখ", "तारीख"
            ).any { c.contains(it) } -> {

                java.text.SimpleDateFormat(
                    "EEEE, dd MMMM yyyy",
                    Locale.forLanguageTag(language)
                ).format(java.util.Date())
            }

            listOf(
                "camera", "ক্যামেরা", "कैमरा"
            ).any { c.contains(it) } -> {

                try {
                    startActivity(
                        Intent(
                            "android.media.action.IMAGE_CAPTURE"
                        )
                    )
                } catch (e: Exception) {
                    status.text = "Camera unavailable"
                }

                reply(
                    "ক্যামেরা খুলছি",
                    "कैमरा खोल रहा हूँ",
                    "Opening camera"
                )
            }

            listOf(
                "settings", "সেটিংস", "सेटिंग"
            ).any { c.contains(it) } -> {

                startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                )

                reply(
                    "সেটিংস খুলছি",
                    "सेटिंग्स खोल रहा हूँ",
                    "Opening settings"
                )
            }

            listOf(
                "alarm", "অ্যালার্ম", "अलार्म"
            ).any { c.contains(it) } -> {

                try {
                    startActivity(
                        Intent(AlarmClock.ACTION_SET_ALARM)
                    )
                } catch (e: Exception) {
                    status.text = "Alarm unavailable"
                }

                reply(
                    "অ্যালার্ম সেট করুন",
                    "अलार्म सेट करें",
                    "Set your alarm"
                )
            }

            listOf(
                "torch",
                "flashlight",
                "টর্চ",
                "टॉर्च"
            ).any { c.contains(it) } -> {

                startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                )

                reply(
                    "টর্চ চালু করতে কুইক সেটিংস ব্যবহার করুন",
                    "टॉर्च के लिए क्विक सेटिंग्स खोलें",
                    "Use Quick Settings for the torch"
                )
            }

            else -> null
        }

        if (answer != null) {
            status.text = answer
            say(answer)
        } else {
            onlineReply(command)
        }
    }

    private fun onlineReply(message: String) {

        val url =
            endpoint.text.toString().trim()

        if (!url.startsWith("https://")) {

            val reply = when (language) {
                "bn-IN" ->
                    "এই প্রশ্নের জন্য অনলাইন AI সংযোগ দরকার। Endpoint সেট করুন।"

                "hi-IN" ->
                    "इस सवाल के लिए ऑनलाइन AI कनेक्शन चाहिए।"

                else ->
                    "Set an online AI endpoint for this question."
            }

            status.text = reply
            say(reply)
            return
        }

        status.text = "Asking online AI..."

        val token = apiKey.text.toString()

        thread {

            val response = try {

                val conn = (
                    URL(url).openConnection()
                        as HttpURLConnection
                ).apply {

                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 25000
                    doOutput = true

                    setRequestProperty(
                        "Content-Type",
                        "application/json"
                    )

                    if (token.isNotEmpty()) {
                        setRequestProperty(
                            "Authorization",
                            "Bearer $token"
                        )
                    }
                }

                val body = JSONObject()
                    .put("model", "gpt-4o-mini")
                    .put(
                        "messages",
                        org.json.JSONArray()
                            .put(
                                JSONObject()
                                    .put("role", "system")
                                    .put(
                                        "content",
                                        "You are Aman, a concise helpful mobile voice assistant. Reply in $language."
                                    )
                            )
                            .put(
                                JSONObject()
                                    .put("role", "user")
                                    .put("content", message)
                            )
                    ).toString()

                conn.outputStream.use {
                    it.write(
                        body.toByteArray(Charsets.UTF_8)
                    )
                }

                val stream =
                    if (conn.responseCode in 200..299) {
                        conn.inputStream
                    } else {
                        conn.errorStream
                    }

                val raw = stream.bufferedReader().use {
                    it.readText()
                }

                if (conn.responseCode !in 200..299) {
                    "AI HTTP ${conn.responseCode}: ${raw.take(150)}"
                } else {
                    JSONObject(raw)
                        .getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")
                }

            } catch (e: Exception) {
                "Online connection failed: ${e.message}"
            }

            runOnUiThread {
                status.text = response
                transcript.text = "AMAN: $response"
                say(response.take(600))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        active = true
    }

    override fun onPause() {
        active = false
        stopRecognition()
        super.onPause()
    }

    override fun onDestroy() {
        stopRecognition()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
