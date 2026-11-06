package com.titan.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream

/** Vosk ile internetsiz uyandırma sözcüğü dinleme ("titan", "stop"). */
class WakeWord(private val ctx: Context, private val onHeard: (String) -> Unit) {

    private var model: Model? = null
    private var service: SpeechService? = null
    private var fired = false
    private var words: List<String> = listOf("titan")

    fun prepare(onReady: () -> Unit, onError: (String) -> Unit) {
        val ui = Handler(Looper.getMainLooper())
        Thread {
            try {
                val dir = File(ctx.filesDir, "vosk-model")
                if (!File(dir, "ready").exists()) {
                    dir.deleteRecursively()
                    copyAssets("model", dir)
                    File(dir, "ready").writeText("1")
                }
                model = Model(dir.absolutePath)
                ui.post { onReady() }
            } catch (e: Throwable) {
                ui.post { onError(e.message ?: e.toString()) }
            }
        }.start()
    }

    private fun copyAssets(src: String, dst: File) {
        val list = ctx.assets.list(src) ?: return
        if (list.isEmpty()) {
            dst.parentFile?.mkdirs()
            ctx.assets.open(src).use { i -> FileOutputStream(dst).use { o -> i.copyTo(o) } }
        } else {
            dst.mkdirs()
            for (n in list) copyAssets("$src/$n", File(dst, n))
        }
    }

    fun start(listenFor: List<String> = listOf("titan")) {
        stop()
        val m = model ?: return
        words = listenFor
        fired = false
        try {
            val grammar = (listenFor + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
            val rec = Recognizer(m, 16000.0f, grammar)
            val s = SpeechService(rec, 16000.0f)
            s.startListening(object : RecognitionListener {
                override fun onPartialResult(h: String?) = check(h, "partial")
                override fun onResult(h: String?) = check(h, "text")
                override fun onFinalResult(h: String?) = check(h, "text")
                override fun onError(e: Exception?) {}
                override fun onTimeout() {}
            })
            service = s
        } catch (e: Exception) {
            service = null
        }
    }

    private fun check(h: String?, key: String) {
        if (fired || h == null) return
        val t = try {
            JSONObject(h).optString(key)
        } catch (e: Exception) {
            ""
        }
        val tokens = t.split(" ")
        for (w in words) {
            if (tokens.contains(w)) {
                fired = true
                onHeard(w)
                return
            }
        }
    }

    fun stop() {
        try {
            service?.stop()
            service?.shutdown()
        } catch (e: Exception) {
        }
        service = null
    }

    fun release() {
        stop()
        try {
            model?.close()
        } catch (e: Exception) {
        }
        model = null
    }
}
