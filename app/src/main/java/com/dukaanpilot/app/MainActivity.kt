package com.dukaanpilot.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.WindowManager
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val PERMISSION_REQUEST_CODE = 101
    private val CHANNEL_ID = "dukaanpilot_pos_alerts"

    // Google Android WebViewAssetLoader loads local bundled assets without CORS issues
    private val assetLoader by lazy {
        WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefresh)

        createNotificationChannel()
        checkAndRequestPermissions()
        setupWebView()
        setupBackNavigation()

        // Keep POS Screen Active during shop hours (Counter Mode)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        if (savedInstanceState == null) {
            // Load local bundled web application (100% Offline Capable)
            webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "DukaanPilot POS Alerts"
            val descriptionText = "Live payment alerts, billing confirmation, and soundbox triggers"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableVibration(true)
                enableLights(true)
            }
            val notificationManager: NotificationManager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        // Android 13+ Notification Permission
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // Instant audio playback for UPI soundbox and payment chimes
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        settings.userAgentString = "${settings.userAgentString} DukaanPilotNativeApp/1.0"

        // Register Native JavaScript Bridge
        webView.addJavascriptInterface(DukaanPilotNativeBridge(), "DukaanPilotNative")

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                request?.url?.let {
                    val response = assetLoader.shouldInterceptRequest(it)
                    if (response != null) return response
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                swipeRefresh.isRefreshing = true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                swipeRefresh.isRefreshing = false
                injectPhoneFriendlyStyles()
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false

                // 1. Direct Native WhatsApp & WhatsApp Business Launch
                if (url.startsWith("whatsapp://") || url.startsWith("https://api.whatsapp.com") || url.startsWith("https://wa.me")) {
                    return openDirectWhatsApp(url)
                }

                // 2. Direct UPI Payment App Launch (PhonePe, GPay, Paytm, CRED)
                if (url.startsWith("upi://")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "No UPI app found on device", Toast.LENGTH_SHORT).show()
                        return true
                    }
                }

                // 3. Direct Phone Dialer
                if (url.startsWith("tel:")) {
                    try {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        return false
                    }
                }

                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Automatically grant web camera & microphone permissions inside the app
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let {
                    runOnUiThread {
                        it.grant(it.resources)
                    }
                }
            }
        }
    }

    /**
     * Direct WhatsApp & WhatsApp Business Launch without intermediate browser prompt
     */
    private fun openDirectWhatsApp(url: String): Boolean {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse(url)
            }

            // Check if standard WhatsApp or WhatsApp Business is installed
            val isWhatsAppInstalled = isAppInstalled("com.whatsapp")
            val isW4bInstalled = isAppInstalled("com.whatsapp.w4b")

            if (isWhatsAppInstalled) {
                intent.setPackage("com.whatsapp")
            } else if (isW4bInstalled) {
                intent.setPackage("com.whatsapp.w4b")
            }

            startActivity(intent)
            return true
        } catch (e: Exception) {
            // Fallback to opening in external browser or showing toast
            try {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(browserIntent)
                return true
            } catch (ex: Exception) {
                Toast.makeText(this, "कृपया फोन में WhatsApp इंस्टॉल करें", Toast.LENGTH_SHORT).show()
                return true
            }
        }
    }

    private fun isAppInstalled(packageName: String): Boolean {
        return try {
            packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    /**
     * Triggers a native Android System Bell Notification with vibration & sound
     */
    fun showNativeNotification(title: String, message: String, notificationId: Int = (System.currentTimeMillis() % 100000).toInt()) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                return
            }
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setVibrate(longArrayOf(0, 150, 80, 150))

        with(NotificationManagerCompat.from(this)) {
            notify(notificationId, builder.build())
        }

        vibrateDevice(150)
    }

    /**
     * Provides instant physical haptic feedback when adding items or scanning
     */
    fun vibrateDevice(durationMs: Long = 100) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(durationMs)
                }
            }
        } catch (e: Exception) {
            // Ignore if vibration unsupported
        }
    }

    /**
     * Injects phone-friendly mobile UI improvements and native event listeners
     */
    private fun injectPhoneFriendlyStyles() {
        val js = """
            (function() {
                if (document.getElementById('dukaanpilot-mobile-overrides')) return;
                var style = document.createElement('style');
                style.id = 'dukaanpilot-mobile-overrides';
                style.innerHTML = `
                    * {
                        -webkit-tap-highlight-color: transparent !important;
                    }
                    body {
                        user-select: none !important;
                        -webkit-user-select: none !important;
                        overscroll-behavior-y: contain !important;
                        padding-top: env(safe-area-inset-top, 0px) !important;
                        padding-bottom: env(safe-area-inset-bottom, 0px) !important;
                    }
                    input, textarea {
                        user-select: text !important;
                        -webkit-user-select: text !important;
                    }
                `;
                document.head.appendChild(style);

                // Auto-sync UPI payments & bills with native Android Bell Notification & Haptics
                window.addEventListener('dukaanpilot:upi_payment_confirmed', function(e) {
                    if (window.DukaanPilotNative) {
                        var amt = e.detail ? e.detail.amount : '';
                        window.DukaanPilotNative.sendNotification('🎉 UPI पेमेंट प्राप्त!', 'दुकानपायलट पर ₹' + amt + ' सफलतापूर्वक प्राप्त हुए।');
                        window.DukaanPilotNative.vibrate(250);
                    }
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    /**
     * JavaScript Bridge enabling the web POS to trigger native Android actions
     */
    inner class DukaanPilotNativeBridge {
        @JavascriptInterface
        fun sendNotification(title: String, message: String) {
            runOnUiThread {
                showNativeNotification(title, message)
            }
        }

        @JavascriptInterface
        fun vibrate(durationMs: Long) {
            runOnUiThread {
                vibrateDevice(durationMs)
            }
        }

        @JavascriptInterface
        fun openWhatsApp(phone: String, message: String) {
            val cleanPhone = phone.replace("+", "").replace(" ", "").trim()
            val encodedMsg = Uri.encode(message)
            val url = "https://wa.me/$cleanPhone?text=$encodedMsg"
            runOnUiThread {
                openDirectWhatsApp(url)
            }
        }

        @JavascriptInterface
        fun shareText(text: String, title: String = "Share Bill") {
            runOnUiThread {
                val sendIntent: Intent = Intent().apply {
                    action = Intent.ACTION_SEND
                    putExtra(Intent.EXTRA_TEXT, text)
                    type = "text/plain"
                }
                val shareIntent = Intent.createChooser(sendIntent, title)
                startActivity(shareIntent)
            }
        }

        @JavascriptInterface
        fun setKeepScreenOn(enable: Boolean) {
            runOnUiThread {
                if (enable) {
                    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                } else {
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        @JavascriptInterface
        fun isNativeAndroid(): Boolean = true
    }
}
