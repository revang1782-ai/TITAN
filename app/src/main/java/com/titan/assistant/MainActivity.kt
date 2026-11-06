package com.titan.assistant

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var keyInput: EditText
    private lateinit var modelInput: EditText

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun tv(s: String, size: Float, color: String, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = size
            setTextColor(Color.parseColor(color))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun btn(s: String, accent: Boolean = false, onClick: () -> Unit) =
        Button(this).apply {
            text = s
            isAllCaps = false
            setTextColor(Color.parseColor(if (accent) "#04121A" else "#D6E2F0"))
            background = GradientDrawable().apply {
                cornerRadius = dp(14).toFloat()
                setColor(Color.parseColor(if (accent) "#00E5FF" else "#15202E"))
            }
            setOnClickListener { onClick() }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(8)
            layoutParams = lp
        }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.parseColor("#05080F")
        window.navigationBarColor = Color.parseColor("#05080F")

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#05080F")) }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(36), dp(20), dp(40))
        }
        scroll.addView(col)

        col.addView(tv("TITAN", 32f, "#00E5FF", true).apply { letterSpacing = 0.4f })
        col.addView(tv("Sesli asistanın", 14f, "#8FA3B8"))

        statusView = tv("", 14f, "#D6E2F0").apply { setPadding(0, dp(20), 0, dp(8)) }
        col.addView(statusView)

        col.addView(tv("1) Gemini API anahtarı (bir kez gir)", 13f, "#8FA3B8").apply { setPadding(0, dp(12), 0, 0) })
        keyInput = EditText(this).apply {
            hint = "API anahtarını yapıştır"
            setHintTextColor(Color.parseColor("#5E7186"))
            setTextColor(Color.WHITE)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        col.addView(keyInput)
        col.addView(tv("Model (boş bırakırsan otomatik)", 13f, "#8FA3B8").apply { setPadding(0, dp(8), 0, 0) })
        modelInput = EditText(this).apply {
            hint = "gemini-flash-latest"
            setHintTextColor(Color.parseColor("#5E7186"))
            setTextColor(Color.WHITE)
            isSingleLine = true
            setText(Prefs.model(this@MainActivity).let { if (it == "gemini-flash-latest") "" else it })
        }
        col.addView(modelInput)
        col.addView(btn("Kaydet", true) {
            val k = keyInput.text.toString().trim()
            if (k.isNotEmpty()) {
                Prefs.setApiKey(this, k)
                keyInput.setText("")
            }
            Prefs.setModel(this, modelInput.text.toString().trim())
            Toast.makeText(this, "Kaydedildi", Toast.LENGTH_SHORT).show()
            refresh()
        })

        col.addView(tv("2) İzinler", 13f, "#8FA3B8").apply { setPadding(0, dp(20), 0, 0) })
        col.addView(btn("Mikrofon izni") {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        })
        col.addView(btn("Başka uygulamaların üstünde göster") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        })
        col.addView(btn("Bildirim izni") {
            if (Build.VERSION.SDK_INT >= 33) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
            }
        })
        col.addView(btn("Erişilebilirlik (Titan'ı aç)") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        col.addView(btn("Pil kısıtlamasını kaldır") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
                )
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        })
        col.addView(btn("Redmi: otomatik başlatma") {
            try {
                startActivity(
                    Intent().setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    )
                )
            } catch (e: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                )
            }
        })

        col.addView(tv("3) Çalıştır", 13f, "#8FA3B8").apply { setPadding(0, dp(20), 0, 0) })
        col.addView(btn("Titan'ı başlat", true) {
            if (Prefs.apiKey(this).isBlank()) {
                Toast.makeText(this, "Önce API anahtarını kaydet", Toast.LENGTH_LONG).show()
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Önce mikrofon izni ver", Toast.LENGTH_LONG).show()
            } else if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Önce üstte gösterme iznini ver", Toast.LENGTH_LONG).show()
            } else {
                ContextCompat.startForegroundService(this, Intent(this, TitanService::class.java))
                Toast.makeText(this, "Titan başladı. \"Titan\" de!", Toast.LENGTH_LONG).show()
            }
        })
        col.addView(btn("Titan'ı durdur") {
            stopService(Intent(this, TitanService::class.java))
            Toast.makeText(this, "Durduruldu", Toast.LENGTH_SHORT).show()
        })

        col.addView(
            tv(
                "Komutlar: kapat · geri · ana ekran · aşağı/yukarı/sağa/sola · open keyboard · " +
                    "sesli mod · ekrana bak · screenshot · close the screen · shut up · " +
                    "privacy disorder · game mode · yaz … · YouTube aç · hafızayı sil · " +
                    "numaralar (sonra \"5\" ya da \"tıkla 5\") · numaraları gizle",
                12f, "#5E7186"
            ).apply { setPadding(0, dp(24), 0, 0) }
        )

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        refresh()
    }

    private fun mark(ok: Boolean, s: String) = (if (ok) "✓ " else "✗ ") + s

    private fun refresh() {
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val notif = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val acc = (Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .contains("$packageName/")
        val pm = getSystemService(PowerManager::class.java)
        val bat = pm.isIgnoringBatteryOptimizations(packageName)
        statusView.text = listOf(
            mark(Prefs.apiKey(this).isNotBlank(), "API anahtarı"),
            mark(mic, "Mikrofon"),
            mark(overlay, "Üstte gösterme"),
            mark(notif, "Bildirim"),
            mark(acc, "Erişilebilirlik"),
            mark(bat, "Pil kısıtlaması yok")
        ).joinToString("\n")
    }
}
