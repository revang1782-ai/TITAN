package com.titan.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TitanService : Service() {

    private enum class State { IDLE, LISTENING, THINKING, SPEAKING }

    private val cyan = 0xFF00E5FFL
    private val green = 0xFF3DDC84L
    private val amber = 0xFFFFB300L
    private val violet = 0xFFB388FFL

    private val sessionMs = 3 * 60 * 1000L
    private val main = Handler(Looper.getMainLooper())

    private lateinit var wake: WakeWord
    private lateinit var overlay: Overlay
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null
    private var state = State.IDLE
    private var sessionEnd = 0L
    private var textMode = false
    private var wakeReady = false
    private var pending: (() -> Unit)? = null
    private val history = ArrayList<Pair<String, String>>()
    private lateinit var numbers: NumberOverlay
    private var numbersActive = false
    private val hideNumbersRunnable = Runnable { hideNumbers() }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startFg()

        overlay = Overlay(this)
        overlay.onSend = { t -> main.post { handleInput(t) } }
        overlay.onClose = { main.post { endSession() } }
        numbers = NumberOverlay(this)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val t = tts
                if (t != null) {
                    val r = t.setLanguage(Locale("tr", "TR"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        t.setLanguage(Locale.getDefault())
                    }
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(id: String?) {}
                        override fun onDone(id: String?) {
                            main.post { onSpeakDone() }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(id: String?) {
                            main.post { onSpeakDone() }
                        }
                    })
                    ttsReady = true
                }
            }
        }

        wake = WakeWord(this) { w -> main.post { onHeard(w) } }
        wake.prepare(
            onReady = {
                wakeReady = true
                startIdle()
            },
            onError = { e ->
                Toast.makeText(this, "Uyandırma motoru başlamadı: $e", Toast.LENGTH_LONG).show()
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        wake.release()
        recognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        overlay.hide()
        numbers.hide()
        super.onDestroy()
    }

    // ---------- Bildirim ----------

    private fun startFg() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("titan", "Titan", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "titan")
            .setContentTitle("Titan dinliyor")
            .setContentText("\"Titan\" diyerek uyandır")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buzz(ms: Long = 50) {
        try {
            val v = getSystemService(Vibrator::class.java)
            v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    // ---------- Durum makinesi ----------

    private fun startIdle() {
        state = State.IDLE
        if (wakeReady) wake.start(listOf("titan"))
    }

    private fun onHeard(w: String) {
        when (state) {
            State.IDLE -> if (w == "titan") beginSession()
            State.SPEAKING -> {
                // Araya girme: Titan konuşurken "titan" ya da "stop" denirse sus ve dinle.
                tts?.stop()
                wake.stop()
                buzz(30)
                listen()
            }
            else -> {}
        }
    }

    private fun beginSession() {
        wake.stop()
        buzz(60)
        sessionEnd = System.currentTimeMillis() + sessionMs
        overlay.show()
        overlay.setText("")
        main.removeCallbacks(tick)
        main.postDelayed(tick, 3000)
        listen()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state == State.IDLE) return
            if (System.currentTimeMillis() > sessionEnd && state != State.THINKING) {
                endSession()
            } else {
                main.postDelayed(this, 3000)
            }
        }
    }

    private fun endSession() {
        main.removeCallbacks(tick)
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        textMode = false
        pending = null
        hideNumbers()
        overlay.hide()
        wake.stop()
        state = State.IDLE
        main.postDelayed({ if (state == State.IDLE) startIdle() }, 400)
    }

    private fun listen() {
        state = State.LISTENING
        if (textMode) {
            overlay.setStatus("yazı modu", violet)
            return
        }
        overlay.setStatus("dinliyorum", cyan)
        main.postDelayed({ if (state == State.LISTENING && !textMode) startRecognizer() }, 350)
    }

    private fun relisten() {
        if (System.currentTimeMillis() > sessionEnd) endSession() else listen()
    }

    private fun startRecognizer() {
        recognizer?.destroy()
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            overlay.setText("Ses tanıma servisi bulunamadı (Google uygulaması gerekli).")
            main.postDelayed({ endSession() }, 2500)
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (!t.isNullOrBlank()) overlay.setText(t)
            }

            override fun onResults(b: Bundle?) {
                val t = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                if (t.isNullOrBlank()) relisten() else handleInput(t)
            }

            override fun onError(code: Int) {
                if (code == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                    overlay.setText("Mikrofon izni yok.")
                    main.postDelayed({ endSession() }, 2000)
                } else {
                    main.postDelayed({ if (state == State.LISTENING) relisten() }, 400)
                }
            }
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            if (Build.VERSION.SDK_INT >= 33) {
                putStringArrayListExtra(
                    RecognizerIntent.EXTRA_BIASING_STRINGS,
                    arrayListOf(
                        "back", "home", "open keyboard", "screenshot", "scroll down",
                        "privacy disorder", "game mode", "close the screen", "shut up",
                        "show numbers", "click"
                    )
                )
            }
        }
        recognizer = r
        r.startListening(i)
    }

    // ---------- Girdi işleme ----------

    private fun handleInput(raw: String) {
        sessionEnd = System.currentTimeMillis() + sessionMs
        overlay.setText("» $raw")
        val t = Commands.fold(raw)

        pending?.let { act ->
            pending = null
            if (Commands.isYes(t)) act() else say("Tamam, vazgeçtim.")
            return
        }

        val cmd = Commands.parse(t, raw, numbersActive)
        if (cmd != null) {
            runCommand(cmd, raw)
        } else {
            askGemini(raw, null)
        }
    }

    private fun acc(): TitanAccessibility? = TitanAccessibility.instance

    private fun hideNumbers() {
        main.removeCallbacks(hideNumbersRunnable)
        numbers.hide()
        numbersActive = false
    }

    private fun noAcc() = say("Erişilebilirlik izni kapalı. Titan uygulamasından aç.")

    private fun done() {
        buzz(35)
        relisten()
    }

    private fun runCommand(cmd: Cmd, raw: String) {
        when (cmd) {
            Cmd.Close -> endSession()
            Cmd.Noop -> relisten()
            Cmd.HideNumbers -> {
                hideNumbers()
                done()
            }
            Cmd.Keyboard -> {
                textMode = true
                recognizer?.destroy()
                recognizer = null
                overlay.enterTextMode()
                listen()
            }
            Cmd.VoiceMode -> {
                textMode = false
                overlay.exitTextMode()
                listen()
            }
            Cmd.Forget -> ask("Tüm hafızayı silmemi onaylıyor musun?") {
                Memory.clear(this)
                say("Hafızayı sildim.")
            }
            Cmd.PowerOff -> ask("Telefonu kapatayım mı?") {
                val a = acc()
                if (a == null) noAcc() else {
                    endSession()
                    a.powerOff()
                }
            }
            Cmd.GameMode -> {
                val i = packageManager.getLaunchIntentForPackage("com.miui.gamebooster")
                if (i == null) say("Game Turbo bulunamadı.") else {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    done()
                }
            }
            is Cmd.OpenApp -> {
                val i = Commands.findApp(this, cmd.name)
                if (i == null) say("${cmd.name} uygulamasını bulamadım.") else {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(i)
                    done()
                }
            }
            else -> {
                val a = acc()
                if (a == null) {
                    noAcc()
                    return
                }
                when (cmd) {
                    Cmd.Lock -> {
                        endSession()
                        a.lock()
                        return
                    }
                    Cmd.Privacy -> {
                        overlay.hide()
                        a.privacy { stopSelf() }
                        return
                    }
                    Cmd.LookScreen -> {
                        state = State.THINKING
                        overlay.setStatus("ekrana bakıyorum", amber)
                        a.screenshotBase64 { b64 ->
                            if (b64 == null) say("Ekran görüntüsü alamadım.") else askGemini(raw, b64)
                        }
                        return
                    }
                    Cmd.Back -> a.back()
                    Cmd.Home -> a.home()
                    Cmd.Recents -> a.recents()
                    Cmd.Screenshot -> a.screenshot()
                    Cmd.ShowNumbers -> {
                        val ts = a.collectTargets()
                        if (ts.isEmpty()) {
                            say("Ekranda tıklanabilir bir şey bulamadım.")
                            return
                        }
                        numbers.show(ts.map { it.rect })
                        numbersActive = true
                        main.removeCallbacks(hideNumbersRunnable)
                        main.postDelayed(hideNumbersRunnable, 45000)
                    }
                    is Cmd.ClickNum -> {
                        if (a.clickTarget(cmd.n - 1)) {
                            hideNumbers()
                        } else {
                            say("Bu numara yok.")
                            return
                        }
                    }
                    is Cmd.Scroll -> a.swipe(cmd.dir)
                    is Cmd.TypeText -> if (!a.typeText(cmd.text)) {
                        say("Yazı alanı seçili değil.")
                        return
                    }
                    else -> {}
                }
                done()
            }
        }
    }

    private fun ask(question: String, action: () -> Unit) {
        pending = action
        say(question)
    }

    // ---------- Gemini ----------

    private fun systemPrompt(): String {
        val mem = Memory.all(this).takeLast(300)
        val date = SimpleDateFormat("d MMMM yyyy EEEE HH:mm", Locale("tr")).format(Date())
        val sb = StringBuilder()
        sb.append("Sen Titan'sın, kullanıcının telefonundaki sesli asistan. ")
        sb.append("Türkçe konuş; cevapların kısa ve konuşma diline uygun olsun (en fazla 2-3 cümle, kullanıcı detay istemedikçe). ")
        sb.append("Markdown, madde işareti, emoji ve özel karakter kullanma, çünkü cevap sesli okunacak. ")
        sb.append("Kullanıcı kalıcı bir bilgi söylerse (adı, tercihleri, planları, önemli kişiler vb.) ")
        sb.append("cevabının SONUNA ayrı bir satırda [[HATIRLA: kısa bilgi]] ekle. Gereksiz yere ekleme. ")
        sb.append("Şu anki tarih ve saat: $date.")
        if (mem.isNotEmpty()) {
            sb.append("\nKullanıcı hakkında hatırladıkların:\n")
            sb.append(mem.joinToString("\n") { "- $it" })
        }
        return sb.toString()
    }

    private fun askGemini(text: String, img: String?) {
        val key = Prefs.apiKey(this)
        if (key.isBlank()) {
            say("API anahtarı girilmemiş. Titan uygulamasını aç.")
            return
        }
        state = State.THINKING
        overlay.setStatus("düşünüyorum", amber)
        val model = Prefs.model(this)
        val system = systemPrompt()
        val hist = ArrayList(history)
        Thread {
            try {
                val reply = Gemini.chat(key, model, system, hist, text, img)
                main.post { onReply(text, reply) }
            } catch (e: Exception) {
                main.post { say("Hata: ${e.message}", speak = "Bir hata oluştu.") }
            }
        }.start()
    }

    private val factRegex = Regex("\\[\\[HATIRLA:(.*?)\\]\\]", RegexOption.DOT_MATCHES_ALL)

    private fun onReply(user: String, reply: String) {
        for (m in factRegex.findAll(reply)) Memory.add(this, m.groupValues[1])
        val clean = reply.replace(factRegex, "").trim()
        history.add(Pair(user, clean))
        while (history.size > 12) history.removeAt(0)
        say(clean)
    }

    // ---------- Konuşma ----------

    private fun say(text: String, speak: String = text) {
        sessionEnd = System.currentTimeMillis() + sessionMs
        overlay.setText(text)
        if (textMode) {
            state = State.LISTENING
            return
        }
        if (!ttsReady) {
            state = State.LISTENING
            main.postDelayed({ if (state == State.LISTENING) relisten() }, 2500)
            return
        }
        state = State.SPEAKING
        overlay.setStatus("konuşuyor", green)
        wake.start(listOf("titan", "stop"))
        tts?.speak(speak, TextToSpeech.QUEUE_FLUSH, null, "titan")
    }

    private fun onSpeakDone() {
        if (state != State.SPEAKING) return
        wake.stop()
        relisten()
    }
}
