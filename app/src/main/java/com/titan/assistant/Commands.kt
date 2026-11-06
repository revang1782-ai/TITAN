package com.titan.assistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

sealed class Cmd {
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

    fun parse(t: String, raw: String): Cmd? {
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

        when (t) {
            "geri", "back" -> return Cmd.Back
            "ana ekran", "home" -> return Cmd.Home
            "son uygulamalar", "recents" -> return Cmd.Recents
            "asagi", "down", "scroll down" -> return Cmd.Scroll("down")
            "yukari", "up", "scroll up" -> return Cmd.Scroll("up")
            "saga", "right" -> return Cmd.Scroll("right")
            "sola", "left" -> return Cmd.Scroll("left")
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
