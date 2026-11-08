package com.mobipc.connect

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.PointF
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Turns control events from an approved remote device into taps, swipes and typing.
 * Android only allows this after the user turns the service on in Settings.
 */
class MobiAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: MobiAccessibilityService? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val pts = ArrayList<PointF>()
    private var t0 = 0L
    private var dragging = false

    override fun onServiceConnected() {
        instance = this
        AppState.refresh()
        HostService.engine?.controlChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        AppState.refresh()
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    @Suppress("DEPRECATION")
    private fun screen(): Pair<Float, Float> {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return Pair(dm.widthPixels.toFloat(), dm.heightPixels.toFloat())
    }

    private fun pt(m: JSONObject): PointF? {
        if (!m.has("x") || !m.has("y")) return null
        val (w, h) = screen()
        val x = (m.getDouble("x") * w).toFloat().coerceIn(1f, w - 1f)
        val y = (m.getDouble("y") * h).toFloat().coerceIn(1f, h - 1f)
        return PointF(x, y)
    }

    fun handle(m: JSONObject) {
        main.post { run(m) }
    }

    private fun run(m: JSONObject) {
        when (m.optString("t")) {
            "down" -> {
                val p = pt(m)
                if (p != null) {
                    pts.clear()
                    pts.add(p)
                    t0 = System.currentTimeMillis()
                    dragging = true
                }
            }
            "move" -> {
                if (dragging) {
                    val p = pt(m)
                    if (p != null && pts.size < 120) pts.add(p)
                }
            }
            "up" -> {
                if (dragging) {
                    val p = pt(m)
                    if (p != null) pts.add(p)
                    dragging = false
                    finishDrag()
                }
            }
            "click" -> {
                val p = pt(m)
                if (p != null) tap(p, m.optInt("b") == 2)
            }
            "wheel" -> scroll(m.optDouble("dy", 0.0))
            "key" -> key(m)
            "text" -> typeText(m.optString("s"))
        }
    }

    private fun finishDrag() {
        if (pts.isEmpty()) return
        val elapsed = System.currentTimeMillis() - t0
        val a = pts.first()
        val b = pts.last()
        if (hypot(b.x - a.x, b.y - a.y) < 24f) tap(a, elapsed > 500) else swipe(ArrayList(pts), elapsed.coerceIn(80L, 4000L))
        pts.clear()
    }

    private fun tap(p: PointF, hold: Boolean) = swipe(listOf(p), if (hold) 700L else 40L)

    private fun swipe(points: List<PointF>, dur: Long) {
        val path = Path()
        path.moveTo(points[0].x, points[0].y)
        for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun scroll(dy: Double) {
        if (dy.isNaN() || dy == 0.0) return
        val (w, h) = screen()
        val d = (abs(dy) * 3).coerceIn(150.0, (h * 0.5).toDouble()).toFloat()
        val cx = w / 2
        val from = if (dy > 0) h / 2 + d / 2 else h / 2 - d / 2
        val to = if (dy > 0) h / 2 - d / 2 else h / 2 + d / 2
        swipe(listOf(PointF(cx, from), PointF(cx, to)), 220L)
    }

    private fun key(m: JSONObject) {
        when (m.optString("k")) {
            "Escape" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "Meta" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "Tab" -> if (m.optBoolean("a")) performGlobalAction(GLOBAL_ACTION_RECENTS)
            "Backspace" -> editText { it.dropLast(1) }
            "Enter" -> {
                val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (node != null && Build.VERSION.SDK_INT >= 30) {
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } else {
                    editText { it + "\n" }
                }
            }
        }
    }

    private fun typeText(s: String) {
        if (s.isNotEmpty()) editText { it + s }
    }

    private fun editText(f: (String) -> String) {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return
        val cur = if (node.isShowingHintText) "" else (node.text?.toString() ?: "")
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, f(cur))
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }
}
