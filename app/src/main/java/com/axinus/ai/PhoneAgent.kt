package com.axinus.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
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
            c.readTimeout = 40000
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
                if (goal.isNotEmpty()) {
                    val id = j!!.optString("id")
                    val rc = j.optJSONObject("recipe")
                    val out = if (rc != null) try { runRecipe(s, rc) } catch (e: Exception) { null } else null
                    if (out != null) finish(s, id, out) else runGoal(s, id, goal)
                }
            } catch (e: Exception) {
            }
            try { Thread.sleep(300) } catch (e: InterruptedException) { return }
        }
    }

    private fun finish(s: AxAccess, id: String, msg: String) {
        s.showCursor(false)
        try { call(s, "phone_done", JSONArray().put(id).put(msg)) } catch (e: Exception) { }
    }


    @Volatile private var pending = ""
    @Volatile private var pendingT = 0L

    private fun contactNum(c: Context, name: String): String? {
        if (name.isBlank() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val cur = c.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%" + name.trim() + "%"), null
        )
        cur?.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun digits(n: String): String {
        val d = n.filter { it.isDigit() }
        return if (d.length == 10) "91$d" else d
    }

    private fun launch(s: AxAccess, i: Intent): Boolean = try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); s.startActivity(i); true } catch (e: Exception) { false }

    /** returns result text, or null to fall back to the AI planner */
    private fun runRecipe(s: AxAccess, r: JSONObject): String? {
        s.showCursor(true)
        val q = r.optString("q")
        val out: String? = when (r.optString("k")) {
            "open_app" -> {
                val x = s.launchApp(q)
                if (x == "ok") { Thread.sleep(1200); "Done: $q khul gaya." } else if (x.startsWith("BLOCKED")) x else null
            }
            "youtube_search" -> {
                val u = Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
                var ok = launch(s, Intent(Intent.ACTION_VIEW, u).setPackage("com.google.android.youtube"))
                if (!ok) ok = launch(s, Intent(Intent.ACTION_VIEW, u))
                if (ok && s.waitPkg("youtube", 5000)) "Done: YouTube me '$q' ka search khul gaya." else if (ok) "Not completed: YouTube search bheja par screen confirm nahi hui." else null
            }
            "web_search" -> {
                val ok = launch(s, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
                if (ok) { Thread.sleep(1500); "Done: Google par '$q' search khul gaya." } else null
            }
            "whatsapp_message", "call" -> {
                val num = digits(r.optString("num").ifEmpty { contactNum(s, r.optString("name")) ?: "" })
                if (num.length < 10) return null
                if (r.optString("k") == "call") {
                    if (!launch(s, Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$num")))) return "Not completed: dialer nahi khula."
                    Thread.sleep(1200)
                    pending = "call"; pendingT = System.currentTimeMillis()
                    "Done: Dialer me +$num aa gaya. Call lagane ke liye 'haan, call karo' boliye, cancel ke liye 'nahi'."
                } else {
                    val msg = r.optString("text")
                    val u = Uri.parse("https://wa.me/$num?text=" + Uri.encode(msg))
                    var ok = launch(s, Intent(Intent.ACTION_VIEW, u).setPackage("com.whatsapp"))
                    if (!ok) ok = launch(s, Intent(Intent.ACTION_VIEW, u).setPackage("com.whatsapp.w4b"))
                    if (!ok) return "Not completed: WhatsApp install nahi mila."
                    var ready = false
                    for (k in 1..25) { if (s.waitPkg("whatsapp", 300) && s.hasText(listOf("send"))) { ready = true; break }; Thread.sleep(200) }
                    if (!ready) return "Not completed: WhatsApp chat khula par Send button nahi dikha, aap check kar lo."
                    pending = "send"; pendingT = System.currentTimeMillis()
                    "Done: WhatsApp chat khul gaya (+$num), message likha hua hai: '${msg.take(60)}'. Bhejne ke liye 'haan, bhej do' boliye, cancel ke liye 'nahi'."
                }
            }
            "confirm" -> {
                val p = if (System.currentTimeMillis() - pendingT < 180000) pending else ""
                pending = ""
                when (p) {
                    "send" -> { val x = s.clickText(listOf("send"), true); if (x == "ok") "Done: message bhej diya." else "Not completed: Send nahi dab paya ($x). Aap khud dabao." }
                    "call" -> { val x = s.clickText(listOf("call", "dial", "voice call", "audio call"), true); if (x == "ok") "Done: call laga diya." else "Not completed: Call button nahi mila ($x). Aap khud dabao." }
                    else -> "Not completed: koi pending message/call nahi hai."
                }
            }
            "deny" -> { pending = ""; s.act(JSONObject().put("a", "back")); "Done: cancel kar diya, kuch nahi bheja." }
            "scroll" -> { val x = s.act(JSONObject().put("a", "scroll").put("d", r.optString("d", "down"))); if (x == "ok") "Done: scroll ho gaya." else "Not completed: $x" }
            "back" -> { s.act(JSONObject().put("a", "back")); "Done: back ho gaya." }
            "home" -> { s.act(JSONObject().put("a", "home")); "Done: home par aa gaya." }
            "type" -> { val x = s.typeFocused(q, r.optBoolean("submit")); if (x.startsWith("ok")) "Done: '$q' type kar diya." else "Not completed: $x" }
            "click_text" -> { val x = s.clickText(listOf(q), false); if (x == "ok") "Done: '$q' par tap kiya." else if (x.startsWith("BLOCKED")) x else "Not completed: $x" }
            "split_screen" -> if (s.splitScreen()) "Done: split screen toggle kiya." else "Not completed: split screen nahi ho paya."
            else -> null
        }
        return out
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
            val w = when (a.optString("a")) { "open" -> 1100L; "type" -> 250L; "scroll" -> 450L; "click" -> 600L; else -> 450L }
            try { Thread.sleep(w) } catch (e: InterruptedException) { return }
        }
        finish(s, id, "Sir, 18 steps me kaam poora nahi hua, rok diya.")
    }
}
