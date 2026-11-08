package com.mobipc.connect

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject

/** Shows the MobiPC website inside the app and connects it to the native screen-hosting engine. */
class MainActivity : AppCompatActivity() {
    private lateinit var web: WebView
    private var camRequest: PermissionRequest? = null

    private val listener: (String) -> Unit = { j ->
        runOnUiThread { web.evaluateJavascript("window.mobiNative&&window.mobiNative.state($j)", null) }
    }

    private val projection = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val d = r.data
        if (r.resultCode == RESULT_OK && d != null) {
            HostService.start(this, d)
        } else {
            AppState.note = "Screen permission was not given."
            AppState.refresh()
        }
    }

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val req = camRequest
        camRequest = null
        if (req != null) {
            if (res[Manifest.permission.CAMERA] == true) req.grant(req.resources) else req.deny()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(Color.parseColor("#0B1220"))

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            override fun onPageFinished(view: WebView, url: String) {
                view.evaluateJavascript("window.mobiNative&&window.mobiNative.state(" + AppState.json() + ")", null)
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        camRequest = request
                        perms.launch(arrayOf(Manifest.permission.CAMERA))
                    }
                }
            }

            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity).setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }.show()
                return true
            }
        }
        web.addJavascriptInterface(Bridge(), "MobiBridge")
        web.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 11)
        }
    }

    override fun onResume() {
        super.onResume()
        AppState.addListener(listener)
        web.evaluateJavascript("window.mobiNative&&window.mobiNative.resume&&window.mobiNative.resume()", null)
        AppState.refresh()
    }

    override fun onPause() {
        AppState.removeListener(listener)
        super.onPause()
    }

    /** Functions the website can call (window.MobiBridge). */
    inner class Bridge {
        @JavascriptInterface
        fun saveSession(url: String, key: String, access: String, refresh: String) {
            Store.url = url
            Store.anon = key
            Store.access = access
            Store.refresh = refresh
        }

        @JavascriptInterface
        fun getSession(): String =
            if (Store.access.isEmpty()) "" else JSONObject().put("a", Store.access).put("r", Store.refresh).toString()

        @JavascriptInterface
        fun clearSession() {
            Store.access = ""
            Store.refresh = ""
        }

        @JavascriptInterface
        fun startHost(deviceId: String, control: Boolean) {
            Store.deviceId = deviceId
            Store.control = control
            runOnUiThread {
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                projection.launch(mpm.createScreenCaptureIntent())
            }
        }

        @JavascriptInterface
        fun stopHost() {
            runOnUiThread { stopService(Intent(this@MainActivity, HostService::class.java)) }
        }

        @JavascriptInterface
        fun setControl(on: Boolean) {
            Store.control = on
            HostService.engine?.controlChanged()
            AppState.refresh()
        }

        @JavascriptInterface
        fun serve(id: String, peerId: String, name: String) {
            HostService.engine?.serveApproved(id, peerId, name)
        }

        @JavascriptInterface
        fun openAccessibility() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }

        @JavascriptInterface
        fun state(): String = AppState.json()
    }
}
