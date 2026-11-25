package com.moto.speedalert

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * Small draggable bubble drawn over other apps (e.g. Google Maps) while riding:
 * speed, current limit, and the distance to a speed camera ahead.
 * It turns red while over the limit. Tap it to open the app.
 *
 * Shown only while the ride is running, the setting is on, the overlay permission
 * is granted and the app's own screen is not in front.
 */
class FloatingWindow(private val context: Context, private val settings: Settings) {

    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = context.resources.displayMetrics.density
    private var root: LinearLayout? = null
    private lateinit var speedText: TextView
    private lateinit var limitText: TextView
    private lateinit var cameraText: TextView
    private lateinit var background: GradientDrawable
    private lateinit var params: WindowManager.LayoutParams

    val listener: (RideState) -> Unit = { onState(it) }

    fun onState(s: RideState) {
        val show = s.running && settings.floatingWindow && !AppState.uiVisible && canDraw(context)
        if (show && root == null) add()
        if (!show && root != null) remove()
        if (root != null) render(s)
    }

    fun release() = remove()

    private fun render(s: RideState) {
        speedText.text = if (s.hasFix) Math.round(s.speedKmh).toString() else "--"
        limitText.text = s.limit.toString()
        background.setColor(if (s.overspeed) COLOR_DANGER else COLOR_BG)
        speedText.setTextColor(if (s.overspeed || !s.hasFix) COLOR_TEXT else COLOR_OK)
        val cam = s.camera
        if (cam != null) {
            cameraText.visibility = View.VISIBLE
            cameraText.text = "📷 ${cam.distance.toInt()}m"
        } else {
            cameraText.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun add() {
        background = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(COLOR_BG)
            setStroke(dp(1.5f).toInt(), 0x55FFFFFF)
        }
        speedText = TextView(context).apply {
            textSize = 34f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setTextColor(COLOR_TEXT)
        }
        val unit = TextView(context).apply {
            text = "km/h"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(0xFF98A2B3.toInt())
        }
        limitText = TextView(context).apply {
            textSize = 15f
            typeface = Typeface.create("sans-serif-condensed", Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFF101418.toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xFFFFFFFF.toInt())
                setStroke(dp(3f).toInt(), 0xFFE5484D.toInt())
            }
            layoutParams = LinearLayout.LayoutParams(dp(36f).toInt(), dp(36f).toInt()).apply {
                topMargin = dp(4f).toInt()
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        cameraText = TextView(context).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFD54F.toInt())
            visibility = View.GONE
        }
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = dp(76f).toInt()
            val p = dp(8f).toInt()
            setPadding(p, p, p, p)
            background = this@FloatingWindow.background
            addView(speedText)
            addView(unit)
            addView(limitText)
            addView(cameraText)
        }

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = settings.overlayX.takeIf { it >= 0 } ?: dp(12f).toInt()
            y = settings.overlayY.takeIf { it >= 0 } ?: dp(160f).toInt()
        }
        view.setOnTouchListener(DragListener())
        try {
            wm.addView(view, params)
            root = view
        } catch (_: Exception) {
            // Permission revoked between the check and now; try again on the next update.
        }
    }

    private fun remove() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = params.x; startY = params.y
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > dp(8f) || abs(dy) > dp(8f))) dragging = true
                    if (dragging) {
                        params.x = (startX + dx).toInt().coerceAtLeast(0)
                        params.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        settings.overlayX = params.x
                        settings.overlayY = params.y
                    } else {
                        openApp()
                    }
                }
            }
            return true
        }
    }

    private fun openApp() {
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
    }

    private fun dp(v: Float) = v * density

    companion object {
        private const val COLOR_BG = 0xE6101418.toInt()
        private const val COLOR_DANGER = 0xF0F04438.toInt()
        private const val COLOR_TEXT = 0xFFF2F4F7.toInt()
        private const val COLOR_OK = 0xFF32D583.toInt()

        fun canDraw(context: Context) = AndroidSettings.canDrawOverlays(context)
    }
}
