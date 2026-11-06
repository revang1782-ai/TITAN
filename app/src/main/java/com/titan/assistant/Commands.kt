package com.titan.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

sealed class Cmd {
    object Noop : Cmd()
    object Close : Cmd()
    object Lock : Cmd()
    object Back : Cmd()
    object Home : Cmd()
    object Recents : Cmd()
    object Screenshot : Cmd()
    object Keyboard : Cmd()
    object VoiceMode : Cmd()
    object PowerOff : Cmd()
    object Privacy : Cmd()
    object GameMode : Cmd()
    object LookScreen : Cmd()
    object Forget : Cmd()
    object ShowNumbers : Cmd()
    object HideNumbers : Cmd()
    data class ClickNum(val n: Int) : Cmd()
    data class Scroll(val dir: String) : Cmd()
    data class OpenApp(val name: String) : Cmd()
    data class TypeText(val text: String) : Cmd()
}

object Commands {

    /** Küçük harf, Türkçe karakterleri sadeleştir, noktalama sil. */
    fun fold(s: String): String {
        var r = s.lowercase().replace("̇", "")
        r = r.replace('ı', 'i').replace('ş', 's').replace('ğ', 'g')
            .replace('ü', 'u').replace('ö', 'o').replace('ç', 'c')
        r = r.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        return r
    }

    fun isYes(t: String) = t in setOf("evet", "yes", "tamam", "olur", "ok", "okay", "onayla")

    private fun has(t: String, vararg k: String) = k.any { t.contains(it) }

    private val units = mapOf(
        "bir" to 1, "iki" to 2, "uc" to 3, "dort" to 4, "bes" to 5,
        "alti" to 6, "yedi" to 7, "sekiz" to 8, "dokuz" to 9,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )
    private val tens = mapOf("on" to 10, "yirmi" to 20, "otuz" to 30, "kirk" to 40)

    private fun number(s: String): Int? {
        s.toIntOrNull()?.let { return it }
        units[s]?.let { return it }
        tens[s]?.let { return it }
        val p = s.split(" ")
        if (p.size == 2) {
            val a = tens[p[0]]
            val b = units[p[1]]
            if (a != null && b != null && b < 10) return a + b
        }
        return null
    }

    fun parse(t0: String, raw: String, numbersActive: Boolean): Cmd? {
        // "Titan geri" gibi cümlelerde baştaki "titan"ı at.
        val t = if (t0 == "titan") "" else t0.removePrefix("titan ").trim()
        if (t.isEmpty()) return Cmd.Noop

        // Numara modu açıkken: "5", "tıkla 5", "click five"
        if (numbersActive) {
            val body = t.removePrefix("tikla ").removePrefix("click ").removePrefix("bas ")
                .removePrefix("sec ").removePrefix("select ").removePrefix("numara ")
                .removePrefix("number ").trim()
            number(body)?.let { return Cmd.ClickNum(it) }
        }

        if (has(t, "numaralari gizle", "numaralari kapat", "numarayi kapat", "hide numbers", "numaralar kapat"))
            return Cmd.HideNumbers
        if (t in setOf("numara", "numaralar", "numaralari goster", "numaralari ac", "show numbers", "numbers", "sayilar", "tikla") ||
            has(t, "numaralandir")
        ) return Cmd.ShowNumbers

        if (t in setOf("kapat", "close", "kapan", "tamam kapat", "close it", "kapat titan")) return Cmd.Close
        if (has(t, "close the screen", "ekrani kapat", "ekran kapat", "lock screen", "ekrani kilitle")) return Cmd.Lock
        if (has(t, "shut up", "power off", "telefonu kapat")) return Cmd.PowerOff
        if (has(t, "privacy disorder", "privacy mode", "gizlilik modu")) return Cmd.Privacy
        if (has(t, "game mode", "oyun modu")) return Cmd.GameMode
        if (has(t, "open keyboard", "klavye ac", "klavyeyi ac")) return Cmd.Keyboard
        if (has(t, "voice mode", "sesli mod")) return Cmd.VoiceMode
        if (has(t, "ekrana bak", "look at the screen", "look at screen", "ekranda ne var", "what is on the screen")) return Cmd.LookScreen
        if (has(t, "screenshot", "ekran goruntusu")) return Cmd.Screenshot
        if (has(t, "hafizayi sil", "forget everything")) return Cmd.Forget

        // Ses tanıma bazen İngilizce kelimeleri yanlış duyar; yaygın yanlışları da kabul et.
        when (t) {
            "geri", "back", "berk", "bak", "bek", "beck", "bag", "bec", "bekk" -> return Cmd.Back
            "ana ekran", "ana sayfa", "home", "hom", "hoom", "hum", "houm" -> return Cmd.Home
            "son uygulamalar", "recents", "recent" -> return Cmd.Recents
            "asagi", "down", "dawn", "daun", "scroll down" -> return Cmd.Scroll("down")
            "yukari", "up", "scroll up" -> return Cmd.Scroll("up")
            "saga", "right", "write", "rayt" -> return Cmd.Scroll("right")
            "sola", "left", "lift" -> return Cmd.Scroll("left")
        }

        if (t.startsWith("yaz ") || t.startsWith("type ")) {
            val rest = raw.trim().substringAfter(' ', "").trim()
            if (rest.isNotEmpty()) return Cmd.TypeText(rest)
        }
        if (t.startsWith("open ")) return Cmd.OpenApp(t.removePrefix("open ").trim())
        if (t.endsWith(" ac") && t.length > 3) return Cmd.OpenApp(t.removeSuffix(" ac").trim())
        return null
    }

    /** Yüklü uygulamalar arasında ada göre bul ve başlatma intent'i ver. */
    fun findApp(ctx: Context, name: String): Intent? {
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(main, PackageManager.MATCH_ALL)
        var best: Intent? = null
        var bestScore = 0
        for (ri in apps) {
            val label = fold(ri.loadLabel(pm).toString())
            val score = when {
                label == name -> 3
                label.contains(name) -> 2
                name.contains(label) && label.length > 2 -> 1
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = pm.getLaunchIntentForPackage(ri.activityInfo.packageName)
            }
        }
        return best
    }
}
