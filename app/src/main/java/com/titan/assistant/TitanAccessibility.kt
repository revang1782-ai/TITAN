package com.titan.assistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream

/** Telefonu kontrol eden servis: kaydırma, geri, ana ekran, kilit, yazı, tıklama, ekran görüntüsü. */
class TitanAccessibility : AccessibilityService() {

    companion object {
        @Volatile
        var instance: TitanAccessibility? = null
    }

    private val ui = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)
    fun lock() = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
    fun screenshot() = performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)

    fun swipe(dir: String) {
        val m = resources.displayMetrics
        val w = m.widthPixels.toFloat()
        val h = m.heightPixels.toFloat()
        val p = Path()
        when (dir) {
            "down" -> { p.moveTo(w / 2, h * 0.7f); p.lineTo(w / 2, h * 0.3f) }
            "up" -> { p.moveTo(w / 2, h * 0.3f); p.lineTo(w / 2, h * 0.7f) }
            "right" -> { p.moveTo(w * 0.8f, h / 2); p.lineTo(w * 0.2f, h / 2) }
            "left" -> { p.moveTo(w * 0.2f, h / 2); p.lineTo(w * 0.8f, h / 2) }
            else -> return
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 300))
            .build()
        dispatchGesture(g, null, null)
    }

    fun typeText(txt: String): Boolean {
        val n = findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val cur = if (n.isShowingHintText) "" else (n.text?.toString() ?: "")
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cur + txt)
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun clickByText(vararg labels: String): Boolean {
        val root = rootInActiveWindow ?: return false
        for (l in labels) {
            val nodes = root.findAccessibilityNodeInfosByText(l) ?: continue
            for (n in nodes) {
                var c: AccessibilityNodeInfo? = n
                while (c != null && !c.isClickable) c = c.parent
                if (c != null && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
        }
        return false
    }

    /** Güç menüsünü açıp "Kapat" düğmesine dokunur (rootsuz yol). */
    fun powerOff() {
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
        ui.postDelayed({
            clickByText("Power off", "Switch off", "Kapat", "Kapatın", "Telefonu kapat")
        }, 1200)
    }

    /** Son uygulamaları temizle, ekranı kilitle, sonra onDone çağır. */
    fun privacy(onDone: () -> Unit) {
        recents()
        ui.postDelayed({
            clickByText("Clear all", "Close all", "Tümünü temizle", "Hepsini temizle", "Tümünü kapat", "Temizle")
            ui.postDelayed({
                lock()
                onDone()
            }, 900)
        }, 1300)
    }

    /** Ekran görüntüsünü JPEG/base64 olarak verir (Android 11+). */
    fun screenshotBase64(cb: (String?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) {
            cb(null)
            return
        }
        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    try {
                        val hb = r.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        val bmp = hw!!.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                        val maxW = 1024
                        val scaled = if (bmp.width > maxW)
                            Bitmap.createScaledBitmap(bmp, maxW, bmp.height * maxW / bmp.width, true)
                        else bmp
                        val bos = ByteArrayOutputStream()
                        scaled.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                        cb(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP))
                    } catch (e: Exception) {
                        cb(null)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    cb(null)
                }
            }
        )
    }
}
