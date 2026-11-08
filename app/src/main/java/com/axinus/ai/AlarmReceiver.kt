package com.axinus.ai

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.Locale

class AlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val ctx = context.applicationContext
        val title = intent.getStringExtra("title") ?: "Task"
        val kind = intent.getStringExtra("kind") ?: "start"
        val id = intent.getStringExtra("id") ?: title
        val text = if (kind == "soon")
            "Sir, 15 minutes remaining. Your task $title is about to start."
        else
            "Sir, your task $title is starting now."

        notify(ctx, id, kind, title, text)

        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var tts: TextToSpeech? = null
        var done = false
        val finish = {
            if (!done) {
                done = true
                try { tts?.shutdown() } catch (e: Exception) { }
                pending.finish()
            }
        }
        handler.postDelayed({ finish() }, 9000)
        tts = TextToSpeech(ctx) { status ->
            if (status != TextToSpeech.SUCCESS) {
                finish()
            } else {
                val t = tts!!
                t.language = Locale("en", "IN")
                t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { handler.post { finish() } }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { handler.post { finish() } }
                })
                t.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ax" + id)
            }
        }
    }

    private fun notify(ctx: Context, id: String, kind: String, title: String, text: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val chId = "axinus_tasks"
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(chId) == null) {
            val ch = NotificationChannel(chId, "Task reminders", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "AXINUS task alerts"
            ch.enableVibration(true)
            ch.setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
            )
            nm.createNotificationChannel(ch)
        }
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(ctx, chId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(if (kind == "soon") "15 min remaining" else "Task starting")
            .setContentText(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify((id + kind).hashCode(), n)
    }
}
