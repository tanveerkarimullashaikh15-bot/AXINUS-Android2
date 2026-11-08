package com.axinus.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

class AxAccess : AccessibilityService() {

    companion object {
        @Volatile var inst: AxAccess? = null
        val BLOCK_PKG = listOf("paisa", "phonepe", "paytm", "bhim", "bank", "wallet", "upi")
        val BLOCK_TEXT = Regex("(?i)(\\bpay\\b|send money|transfer|buy now|place order|confirm order|delete|uninstall|\\bupi\\b|\\botp\\b|factory reset|\\bsend\\b)")
    }

    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private val main = Handler(Looper.getMainLooper())
    private var cursor: CursorView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null

    override fun onServiceConnected() {
        inst = this
        main.post { addCursor() }
        PhoneAgent.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        inst = null
        PhoneAgent.stop()
        main.post { removeCursor() }
        super.onDestroy()
    }

    // ---------- cursor overlay ----------
    private fun addCursor() {
        try {
            wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val v = CursorView(this)
            v.visibility = View.GONE
            val p = WindowManager.LayoutParams(
                120, 140,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = 300; p.y = 600
            wm?.addView(v, p)
            cursor = v; lp = p
        } catch (e: Exception) {
        }
    }

    private fun removeCursor() {
        try { cursor?.let { wm?.removeView(it) } } catch (e: Exception) { }
        cursor = null
    }

    fun showCursor(on: Boolean) {
        main.post { cursor?.visibility = if (on) View.VISIBLE else View.GONE }
    }

    private fun moveCursor(x: Int, y: Int) {
        main.post {
            val v = cursor ?: return@post
            val p = lp ?: return@post
            v.visibility = View.VISIBLE
            val sx = p.x; val sy = p.y
            val tx = x - 60; val ty = y - 70
            val a = ValueAnimator.ofFloat(0f, 1f)
            a.duration = 180
            a.addUpdateListener {
                val f = it.animatedValue as Float
                p.x = (sx + (tx - sx) * f).toInt()
                p.y = (sy + (ty - sy) * f).toInt()
                try { wm?.updateViewLayout(v, p) } catch (e: Exception) { }
            }
            a.start()
        }
        Thread.sleep(210)
    }

    // ---------- reading the screen ----------
    fun screen(): String {
        val root = rootInActiveWindow ?: return JSONObject().put("pkg", "").put("items", JSONArray()).toString()
        val list = ArrayList<AccessibilityNodeInfo>()
        val arr = JSONArray()
        fun walk(n: AccessibilityNodeInfo, d: Int) {
            if (d > 40 || list.size >= 70) return
            if (n.isVisibleToUser && !n.isPassword) {
                val t = (n.text?.toString() ?: n.contentDescription?.toString() ?: "").take(60)
                if (t.isNotEmpty() || n.isClickable || n.isEditable) {
                    val r = Rect()
                    n.getBoundsInScreen(r)
                    list.add(n)
                    val o = JSONObject().put("i", list.size - 1).put("t", t)
                    if (n.isClickable) o.put("c", 1)
                    if (n.isEditable) o.put("e", 1)
                    if (n.isScrollable) o.put("s", 1)
                    arr.put(o)
                }
            }
            for (k in 0 until n.childCount) {
                val c = n.getChild(k)
                if (c != null) walk(c, d + 1)
            }
        }
        walk(root, 0)
        nodes = list
        return JSONObject().put("pkg", root.packageName?.toString() ?: "").put("items", arr).toString()
    }

    fun currentPkg(): String = rootInActiveWindow?.packageName?.toString() ?: ""

    // ---------- acting ----------
    private fun tapXY(x: Int, y: Int): Boolean {
        val p = Path()
        p.moveTo(x.toFloat(), y.toFloat())
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 80)).build()
        return dispatchGesture(g, null, null)
    }

    private fun scrollNode(down: Boolean): Boolean {
        val n = nodes.firstOrNull { it.isScrollable } ?: return false
        val act = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return n.performAction(act)
    }

    private fun swipe(down: Boolean): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val a = dm.heightPixels * (if (down) 0.70f else 0.30f)
        val b = dm.heightPixels * (if (down) 0.30f else 0.70f)
        val p = Path()
        p.moveTo(x, a); p.lineTo(x, b)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, 250)).build()
        return dispatchGesture(g, null, null)
    }

    private fun node(i: Int): AccessibilityNodeInfo? = nodes.getOrNull(i)

    private fun center(n: AccessibilityNodeInfo): Pair<Int, Int> {
        val r = Rect()
        n.getBoundsInScreen(r)
        return Pair(r.centerX(), r.centerY())
    }

    /** returns "ok", "BLOCKED:...", or an error text */
    fun act(o: JSONObject): String {
        return try {
            when (o.optString("a")) {
                "open" -> openApp(o.optString("app"))
                "click" -> {
                    val n = node(o.optInt("i", -1)) ?: return "no such item"
                    val label = (n.text?.toString() ?: n.contentDescription?.toString() ?: "")
                    if (BLOCK_TEXT.containsMatchIn(label)) return "BLOCKED: '" + label.take(30) + "' sensitive hai, aap khud tap karo"
                    val (x, y) = center(n)
                    moveCursor(x, y)
                    var t: AccessibilityNodeInfo? = n
                    var done = false
                    var hop = 0
                    while (t != null && hop < 5 && !done) {
                        if (t.isClickable) done = t.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        t = t.parent
                        hop++
                    }
                    if (!done) done = tapXY(x, y)
                    if (done) "ok" else "click failed"
                }
                "type" -> {
                    val n = node(o.optInt("i", -1)) ?: return "no such item"
                    if (!n.isEditable) return "item editable nahi hai"
                    val (x, y) = center(n)
                    moveCursor(x, y)
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val b = Bundle()
                    b.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, o.optString("t"))
                    if (n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, b)) "ok" else "type failed"
                }
                "scroll" -> {
                    val down = o.optString("d") != "up"
                    if (scrollNode(down) || swipe(down)) "ok" else "scroll failed"
                }
                "back" -> if (performGlobalAction(GLOBAL_ACTION_BACK)) "ok" else "back failed"
                "home" -> if (performGlobalAction(GLOBAL_ACTION_HOME)) "ok" else "home failed"
                "wait" -> "ok"
                else -> "unknown action"
            }
        } catch (e: Exception) {
            "error: " + (e.message ?: "").take(60)
        }
    }

    private fun openApp(name: String): String {
        val pm = packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val want = name.trim().lowercase()
        if (want.isEmpty()) return "app naam khaali"
        val hit = pm.queryIntentActivities(q, 0).firstOrNull { it.loadLabel(pm).toString().lowercase().contains(want) }
            ?: return "app nahi mila: " + name
        val pkg = hit.activityInfo.packageName
        if (BLOCK_PKG.any { pkg.lowercase().contains(it) }) return "BLOCKED: payment/bank app nahi khol sakta"
        val i = pm.getLaunchIntentForPackage(pkg) ?: return "launch intent nahi mila"
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        return "ok"
    }
}
