package com.dukaanpilot.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.WebViewAssetLoader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private val PERMISSION_REQUEST_CODE = 101

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

        checkAndRequestPermissions()
        setupWebView()
        setupBackNavigation()

        swipeRefresh.setOnRefreshListener {
            webView.reload()
        }

        if (savedInstanceState == null) {
            // Load local bundled web application via secure domain (100% Offline Capable)
            webView.loadUrl("https://appassets.androidplatform.net/assets/web/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

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

                // Deep links for WhatsApp, UPI, phone calls
                if (url.startsWith("whatsapp://") || url.startsWith("https://api.whatsapp.com") || url.startsWith("https://wa.me") || url.startsWith("upi://") || url.startsWith("tel:")) {
                    try {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        startActivity(intent)
                        return true
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "App not found on device to handle request", Toast.LENGTH_SHORT).show()
                        return true
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
     * Injects mobile-friendly CSS and optimizations without modifying web codebase
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
}
