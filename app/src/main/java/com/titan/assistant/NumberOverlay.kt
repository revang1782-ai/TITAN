package com.titan.assistant

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.WindowManager

/** Ekrandaki tıklanabilir şeylerin köşesine sarı numara rozetleri çizer. Dokunuşları engellemez. */
class NumberOverlay(private val ctx: Context) {

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val d = ctx.resources.displayMetrics.density
    private var view: View? = null

    fun show(rects: List<Rect>) {
        hide()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD600.toInt() }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2 * d
        }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            textSize = 13 * d
            typeface = Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val v = object : View(ctx) {
            override fun onDraw(c: Canvas) {
                val loc = IntArray(2)
                getLocationOnScreen(loc)
                val r = 13 * d
                rects.forEachIndexed { i, rc ->
                    val cx = (rc.left - loc[0]) + r
                    val cy = (rc.top - loc[1]) + r
                    c.drawCircle(cx, cy, r, fill)
                    c.drawCircle(cx, cy, r, stroke)
                    c.drawText("${i + 1}", cx, cy - (txt.descent() + txt.ascent()) / 2, txt)
                }
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        try {
            wm.addView(v, p)
            view = v
        } catch (e: Exception) {
            view = null
        }
    }

    fun hide() {
        view?.let {
            try {
                wm.removeView(it)
            } catch (e: Exception) {
            }
        }
        view = null
    }
}
