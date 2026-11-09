package com.axinus.ai

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.content.pm.PackageManager
import android.provider.Settings
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.webkit.JavascriptInterface
import android.widget.Toast
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null

    private val micLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private var pendingWake = false

    private fun fireVoice(delay: Long) {
        Handler(Looper.getMainLooper()).postDelayed({
            web.evaluateJavascript("var b=document.getElementById('bgo');if(b)b.click();", null)
        }, delay)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent?.getBooleanExtra("wake", false) == true) fireVoice(300)
    }

    inner class Bridge {
        @JavascriptInterface
        fun wakeOn(): Boolean = WakeService.inst?.listening == true

        @JavascriptInterface
        fun keepAlive() {
            runOnUiThread {
                if (WakeService.inst == null && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    try { ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeService::class.java)) } catch (e: Exception) { }
                }
            }
        }

        @JavascriptInterface
        fun voiceOff() {
            WakeService.inst?.startListening()
        }

        @JavascriptInterface
        fun wakeSetup() {
            runOnUiThread {
                val pr = getSharedPreferences("axinus", MODE_PRIVATE)
                if (WakeService.inst?.listening == true) {
                    stopService(Intent(this@MainActivity, WakeService::class.java))
                    return@runOnUiThread
                }
                val err = pr.getString("wake_err", "") ?: ""
                if (err.isNotEmpty()) Toast.makeText(this@MainActivity, "Last wake error: $err", Toast.LENGTH_LONG).show()
                pr.edit().remove("wake_err").apply()
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    val pg = pr.getString("wake_prog", "") ?: ""
                    Toast.makeText(this@MainActivity, if (pg.isNotEmpty()) "Wake word: $pg" else "Wake word start ho raha hai (pehli baar ~40MB download, status dobara tap karke dekho)", Toast.LENGTH_LONG).show()
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, WakeService::class.java))
                }
            }
        }

        @JavascriptInterface
        fun setPin(pin: String) {
            getSharedPreferences("axinus", MODE_PRIVATE).edit().putString("pin", pin).apply()
        }

        @JavascriptInterface
        fun accessEnabled(): Boolean = AxAccess.inst != null

        @JavascriptInterface
        fun openAccessSettings() {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        @JavascriptInterface
        fun sync(json: String) {
            TaskScheduler.sync(this@MainActivity, json)
        }
    }

    private val fileLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            fileCb?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data)
            )
            fileCb = null
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        web = WebView(this)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 21)
        setContentView(web)

        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        web.addJavascriptInterface(Bridge(), "AXAND")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (pendingWake) { pendingWake = false; fireVoice(1500) }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread { request.grant(request.resources) }
            }

            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: WebChromeClient.FileChooserParams
            ): Boolean {
                fileCb?.onReceiveValue(null)
                fileCb = callback
                return try {
                    fileLauncher.launch(params.createIntent())
                    true
                } catch (e: Exception) {
                    fileCb = null
                    false
                }
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val saved = getSharedPreferences("axinus", MODE_PRIVATE).getString("url", "") ?: ""
        if (intent?.getBooleanExtra("wake", false) == true && saved.startsWith("http")) {
            pendingWake = true
            web.loadUrl(saved)
        } else {
            askUrl()
        }
    }

    private fun askUrl() {
        val prefs = getSharedPreferences("axinus", MODE_PRIVATE)
        val input = EditText(this)
        input.setText(prefs.getString("url", "https://"))
        AlertDialog.Builder(this)
            .setTitle("AXINUS server URL")
            .setView(input)
            .setCancelable(false)
            .setPositiveButton("Open") { _, _ ->
                val u = input.text.toString().trim()
                prefs.edit().putString("url", u).apply()
                web.loadUrl(u)
            }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
