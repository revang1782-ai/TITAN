package com.titan.assistant

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Oyunu durdurmayan yüzen arayüz.
 * Ses modunda dokunulmaz ve odaksızdır: dokunuşlar alttaki oyuna geçer.
 * Yazı modunda klavye için odaklanabilir olur.
 */
class Overlay(private val ctx: Context) {

    var onSend: ((String) -> Unit)? = null
    var onClose: (() -> Unit)? = null

    private val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val dp = ctx.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private var root: LinearLayout? = null
    private var dot: View? = null
    private var status: TextView? = null
    private var body: TextView? = null
    private var inputRow: LinearLayout? = null
    private var input: EditText? = null
    private var shown = false
    private var textMode = false
    private var pulse: ObjectAnimator? = null

    private fun params(): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (!textMode) {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val p = WindowManager.LayoutParams(
            ctx.resources.displayMetrics.widthPixels - px(32),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT
        )
        p.gravity = if (textMode) Gravity.TOP or Gravity.CENTER_HORIZONTAL
        else Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = px(if (textMode) 56 else 64)
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN
        return p
    }

    private fun label(size: Float, color: Long, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            textSize = size
            setTextColor(color.toInt())
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun build() {
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(18), px(14), px(18), px(16))
            background = GradientDrawable().apply {
                setColor(0xEE0B1220.toInt())
                cornerRadius = px(24).toFloat()
                setStroke(px(1), 0xFF00E5FF.toInt())
            }
        }

        val header = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val d = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFF00E5FF.toInt())
            }
        }
        header.addView(d, LinearLayout.LayoutParams(px(10), px(10)))
        val title = label(12f, 0xFF00E5FF, true).apply {
            text = "TITAN"
            letterSpacing = 0.35f
            setPadding(px(10), 0, px(10), 0)
        }
        header.addView(title)
        val st = label(12f, 0xFF8FA3B8)
        header.addView(st)
        card.addView(header)

        val b = label(16f, 0xFFF2F6FA).apply {
            setPadding(0, px(10), 0, 0)
            maxLines = 8
        }
        card.addView(b)

        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(0, px(12), 0, 0)
        }
        val et = EditText(ctx).apply {
            hint = "Titan'a yaz…"
            setHintTextColor(0xFF5E7186.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 16f
            maxLines = 3
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send()
                    true
                } else false
            }
        }
        row.addView(et, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sendBtn = label(22f, 0xFF00E5FF, true).apply {
            text = "➤"
            setPadding(px(12), px(4), px(8), px(4))
            setOnClickListener { send() }
        }
        row.addView(sendBtn)
        val closeBtn = label(20f, 0xFF8FA3B8, true).apply {
            text = "✕"
            setPadding(px(8), px(4), px(4), px(4))
            setOnClickListener { onClose?.invoke() }
        }
        row.addView(closeBtn)
        card.addView(row)

        root = card
        dot = d
        status = st
        body = b
        inputRow = row
        input = et
        pulse = ObjectAnimator.ofFloat(d, "alpha", 1f, 0.2f).apply {
            duration = 700
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
        }
    }

    private fun send() {
        val t = input?.text?.toString()?.trim() ?: ""
        if (t.isNotEmpty()) {
            input?.setText("")
            onSend?.invoke(t)
        }
    }

    fun show() {
        if (shown) return
        if (root == null) build()
        try {
            wm.addView(root, params())
            shown = true
            pulse?.start()
        } catch (e: Exception) {
            shown = false
        }
    }

    fun hide() {
        if (shown) {
            try {
                hideKeyboard()
                wm.removeView(root)
            } catch (e: Exception) {
            }
            shown = false
            pulse?.cancel()
        }
        textMode = false
        inputRow?.visibility = View.GONE
        body?.text = ""
    }

    fun setStatus(s: String, color: Long) {
        status?.text = s
        (dot?.background as? GradientDrawable)?.setColor(color.toInt())
    }

    fun setText(s: String) {
        body?.text = s
    }

    fun enterTextMode() {
        textMode = true
        inputRow?.visibility = View.VISIBLE
        if (shown) wm.updateViewLayout(root, params())
        input?.postDelayed({
            input?.requestFocus()
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 250)
    }

    fun exitTextMode() {
        hideKeyboard()
        textMode = false
        inputRow?.visibility = View.GONE
        if (shown) wm.updateViewLayout(root, params())
    }

    private fun hideKeyboard() {
        try {
            val imm = ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(input?.windowToken, 0)
        } catch (e: Exception) {
        }
    }
}
