package com.axinus.ai

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
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.util.zip.ZipInputStream

class WakeService : Service() {

    companion object {
        @Volatile var inst: WakeService? = null
        private const val CH = "axinus_wake"
    }

    private var model: Model? = null
    private var sp: SpeechService? = null
    @Volatile private var loading = false
    @Volatile var listening = false
    private val WAKE = Regex("\\b(hey|hay|hi|ok|okay)\\s+(axis|access|actions|axe|exes|axinus|axis us|jarvis)\\b|\\bjarvis\\b|\\baxinus\\b")

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

    private fun err(m: String) = getSharedPreferences("axinus", MODE_PRIVATE).edit().putString("wake_err", m.take(120)).apply()

    private fun ensureModel(): File? {
        val dir = File(filesDir, "vosk")
        val ok = File(dir, "model/am/final.mdl")
        if (ok.exists()) return File(dir, "model")
        dir.mkdirs()
        try {
            URL("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").openStream().use { ins ->
                ZipInputStream(ins).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val name = e.name.substringAfter("/")
                        if (name.isNotEmpty()) {
                            val f = File(dir, "model/$name")
                            if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { o -> z.copyTo(o) } }
                        }
                        e = z.nextEntry
                    }
                }
            }
        } catch (ex: Exception) { err("model download: " + (ex.message ?: "")); return null }
        return if (ok.exists()) File(dir, "model") else null
    }

    fun startListening() {
        if (sp != null) { listening = true; return }
        if (loading) return
        loading = true
        Thread {
            try {
                if (model == null) {
                    val d = ensureModel()
                    if (d == null) { loading = false; listening = false; stopSelf(); return@Thread }
                    model = Model(d.absolutePath)
                }
                val rec = Recognizer(model, 16000f, "[\"hey\", \"hi\", \"ok\", \"okay\", \"axis\", \"access\", \"actions\", \"axe\", \"exes\", \"jarvis\", \"[unk]\"]")
                val s = SpeechService(rec, 16000f)
                s.startListening(object : RecognitionListener {
                    private fun chk(h: String?) {
                        if (h == null) return
                        val t = try { val j = JSONObject(h); j.optString("partial", j.optString("text")) } catch (e: Exception) { "" }
                        if (WAKE.containsMatchIn(t)) onWake()
                    }
                    override fun onPartialResult(h: String?) = chk(h)
                    override fun onResult(h: String?) = chk(h)
                    override fun onFinalResult(h: String?) = chk(h)
                    override fun onError(e: Exception?) { err("mic: " + (e?.message ?: "")) }
                    override fun onTimeout() {}
                })
                sp = s
                listening = true
            } catch (e: Exception) {
                err(e.message ?: "vosk error"); listening = false; stopSelf()
            }
            loading = false
        }.start()
    }

    private fun onWake() {
        try { sp?.stop(); sp?.shutdown() } catch (e: Exception) { }
        sp = null
        listening = false
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
        try { sp?.stop(); sp?.shutdown() } catch (e: Exception) { }
        sp = null
        inst = null
        listening = false
        super.onDestroy()
    }
}
