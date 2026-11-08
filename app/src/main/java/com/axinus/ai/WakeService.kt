package com.axinus.ai

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import ai.picovoice.porcupine.PorcupineManagerCallback
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File

class WakeService : Service() {

    companion object {
        @Volatile var inst: WakeService? = null
        private const val CH = "axinus_wake"
    }

    private var pm: PorcupineManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        inst = this
        goForeground()
        startListening()
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "AXINUS wake word", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("AXINUS listening")
            .setContentText("Say \"Hey Axinus\"")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(7, n)
    }

    fun startListening() {
        try {
            if (pm != null) { pm?.start(); return }
            val key = getSharedPreferences("axinus", MODE_PRIVATE).getString("pvkey", "") ?: ""
            if (key.isEmpty()) { stopSelf(); return }
            val b = PorcupineManager.Builder().setAccessKey(key).setSensitivity(0.7f)
            val custom = assets.list("")?.contains("hey_axinus.ppn") == true
            if (custom) {
                val f = File(filesDir, "hey_axinus.ppn")
                assets.open("hey_axinus.ppn").use { ins -> f.outputStream().use { ins.copyTo(it) } }
                b.setKeywordPath(f.absolutePath)
            } else {
                b.setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            }
            pm = b.build(applicationContext, PorcupineManagerCallback { onWake() })
            pm?.start()
        } catch (e: Exception) {
            getSharedPreferences("axinus", MODE_PRIVATE).edit().putString("wake_err", (e.message ?: "").take(120)).apply()
            stopSelf()
        }
    }

    private fun onWake() {
        try { pm?.stop() } catch (e: Exception) { }
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("wake", true)
        try {
            val a = AxAccess.inst
            if (a != null) a.startActivity(i) else startActivity(i)
        } catch (e: Exception) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pi = PendingIntent.getActivity(this, 2, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            nm.notify(8, NotificationCompat.Builder(this, CH).setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("AXINUS heard you").setContentText("Tap to talk").setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setContentIntent(pi).build())
        }
    }

    override fun onDestroy() {
        try { pm?.stop(); pm?.delete() } catch (e: Exception) { }
        pm = null
        inst = null
        super.onDestroy()
    }
}
