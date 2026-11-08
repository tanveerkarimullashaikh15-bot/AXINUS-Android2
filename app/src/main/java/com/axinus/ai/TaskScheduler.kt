package com.axinus.ai

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.time.OffsetDateTime

object TaskScheduler {
    private const val PREF = "axinus_tasks"
    private const val KEY = "scheduled"

    private fun reqCode(id: String, kind: String): Int = (id + "|" + kind).hashCode()

    private fun pi(ctx: Context, id: String, title: String, kind: String): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java)
            .putExtra("id", id).putExtra("title", title).putExtra("kind", kind)
        return PendingIntent.getBroadcast(
            ctx, reqCode(id, kind), i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val old = try {
            JSONArray(ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"))
        } catch (e: Exception) {
            JSONArray()
        }
        for (n in 0 until old.length()) {
            val o = old.getJSONObject(n)
            am.cancel(pi(ctx, o.getString("id"), o.getString("title"), o.getString("kind")))
        }
    }

    private fun setAt(ctx: Context, at: Long, id: String, title: String, kind: String) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val p = pi(ctx, id, title, kind)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        }
    }

    /** tasksJson = array of {id,title,due(ISO),status} from the server. Replaces all alarms. */
    fun sync(ctx: Context, tasksJson: String) {
        val app = ctx.applicationContext
        val arr = try { JSONArray(tasksJson) } catch (e: Exception) { return }
        cancelAll(app)
        val now = System.currentTimeMillis()
        val keep = JSONArray()
        for (n in 0 until arr.length()) {
            val t = arr.optJSONObject(n) ?: continue
            if (t.optString("status") != "pending") continue
            val id = t.optString("id")
            val title = t.optString("title")
            val due = try {
                OffsetDateTime.parse(t.optString("due")).toInstant().toEpochMilli()
            } catch (e: Exception) {
                continue
            }
            for ((kind, at) in listOf("soon" to due - 15 * 60 * 1000L, "start" to due)) {
                if (at > now) {
                    setAt(app, at, id, title, kind)
                    keep.put(JSONObject().put("id", id).put("title", title).put("kind", kind).put("at", at))
                }
            }
        }
        app.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, keep.toString()).apply()
    }

    /** After reboot: re-arm the alarms we saved. */
    fun restore(ctx: Context) {
        val app = ctx.applicationContext
        val saved = try {
            JSONArray(app.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]"))
        } catch (e: Exception) {
            return
        }
        val now = System.currentTimeMillis()
        for (n in 0 until saved.length()) {
            val o = saved.getJSONObject(n)
            if (o.getLong("at") > now) setAt(app, o.getLong("at"), o.getString("id"), o.getString("title"), o.getString("kind"))
        }
    }
}
