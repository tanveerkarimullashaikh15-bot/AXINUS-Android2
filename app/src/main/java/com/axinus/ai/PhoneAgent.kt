package com.axinus.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object PhoneAgent {
    @Volatile private var running = false
    private var th: Thread? = null

    fun start(s: AxAccess) {
        if (running) return
        running = true
        th = Thread { loop(s) }.also { it.isDaemon = true; it.start() }
    }

    fun stop() { running = false }

    private fun call(ctx: Context, name: String, args: JSONArray): JSONObject? {
        val pr = ctx.getSharedPreferences("axinus", Context.MODE_PRIVATE)
        val base = pr.getString("url", "") ?: ""
        val pin = pr.getString("pin", "") ?: ""
        if (!base.startsWith("http") || pin.isEmpty()) return null
        val c = URL(base.trimEnd('/') + "/api/" + name).openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000
            c.readTimeout = 60000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("X-Pin", pin)
            c.outputStream.use { it.write(JSONObject().put("args", args).toString().toByteArray()) }
            if (c.responseCode != 200) return null
            return JSONObject(c.inputStream.bufferedReader().readText())
        } finally {
            c.disconnect()
        }
    }

    private fun loop(s: AxAccess) {
        while (running) {
            try {
                val j = call(s, "phone_poll", JSONArray())
                val goal = j?.optString("goal") ?: ""
                if (goal.isNotEmpty()) runGoal(s, j!!.optString("id"), goal)
            } catch (e: Exception) {
            }
            try { Thread.sleep(4000) } catch (e: InterruptedException) { return }
        }
    }

    private fun finish(s: AxAccess, id: String, msg: String) {
        s.showCursor(false)
        try { call(s, "phone_done", JSONArray().put(id).put(msg)) } catch (e: Exception) { }
    }

    private fun runGoal(s: AxAccess, id: String, goal: String) {
        val hist = ArrayList<String>()
        s.showCursor(true)
        for (step in 1..18) {
            if (!running) return
            val pkg = s.currentPkg().lowercase()
            if (AxAccess.BLOCK_PKG.any { pkg.contains(it) }) {
                finish(s, id, "Sir, payment/bank app screen par hoon, isliye ruk gaya.")
                return
            }
            val screen = s.screen()
            val a = try {
                call(s, "phone_step", JSONArray().put(goal).put(screen).put(hist.takeLast(6).joinToString("\n")))
            } catch (e: Exception) {
                null
            }
            if (a == null) {
                finish(s, id, "Sir, server se jawab nahi aaya, kaam adhura hai.")
                return
            }
            when (a.optString("a")) {
                "done" -> { finish(s, id, a.optString("msg", "Ho gaya, Sir.")); return }
                "fail" -> { finish(s, id, a.optString("msg", "Nahi ho paya, Sir.")); return }
            }
            val r = s.act(a)
            hist.add("step $step: ${a} -> $r")
            if (r.startsWith("BLOCKED")) {
                finish(s, id, "Sir, ruk gaya. " + r.removePrefix("BLOCKED: "))
                return
            }
            try { Thread.sleep(1300) } catch (e: InterruptedException) { return }
        }
        finish(s, id, "Sir, 18 steps me kaam poora nahi hua, rok diya.")
    }
}
