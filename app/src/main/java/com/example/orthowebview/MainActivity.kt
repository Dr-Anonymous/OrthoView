package com.example.orthowebview

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebSettings
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import android.content.Context
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val baseUrl = "https://ortho.life/wa"

    // JavaScript interface for clipboard access
    inner class WebAppInterface {
        @android.webkit.JavascriptInterface
        fun getClipboardText(): String {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                return clip.getItemAt(0).coerceToText(this@MainActivity).toString()
            }
            return ""
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.webView)
        setupWebView()
        // Add the JavaScript interface for clipboard
        webView.addJavascriptInterface(WebAppInterface(), "AndroidClipboard")
        requestCallLogPermission()
    }

    private fun setupWebView() {
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                url?.let { urlString ->
                    when {
                        urlString.startsWith("tel:") -> {
                            val intent = Intent(Intent.ACTION_DIAL, Uri.parse(urlString))
                            startActivity(intent)
                            return true
                        }
                        urlString.startsWith("sms:") -> {
                            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(urlString))
                            startActivity(intent)
                            return true
                        }
                        urlString.startsWith("whatsapp:") -> {
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(urlString))
                                startActivity(intent)
                                return true
                            } catch (e: Exception) {
                                // If WhatsApp is not installed, open in browser
                                webView.loadUrl(urlString)
                                return false
                            }
                        }
                        else -> {
                            // Handle other URL schemes by loading them in the WebView
                            return false
                        }
                    }
                }
                return false
            }
        }

        val settings = webView.settings
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            allowFileAccess = true
            allowContentAccess = true
            // Enable clipboard access
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
        }

        // Enable long press for context menu (paste, etc.)
        webView.isLongClickable = true
        webView.setOnLongClickListener { false } // Return false to allow default context menu
    }

    private fun requestCallLogPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED) {
            loadWebViewWithNumber()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.READ_CALL_LOG)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            loadWebViewWithNumber()
        } else {
            webView.loadUrl(baseUrl)
        }
    }

    private fun loadWebViewWithNumber() {
        val number = getMostRecentPhoneNumber()
        if (number != null) {
            webView.loadUrl("$baseUrl?number=$number")
        } else {
            webView.loadUrl(baseUrl)
        }
    }

    private fun getMostRecentPhoneNumber(): String? {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val cursor = contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER),
            null,
            null,
            "${CallLog.Calls.DATE} DESC"
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val number = it.getString(it.getColumnIndexOrThrow(CallLog.Calls.NUMBER))
                return number
            }
        }
        return null
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            finishAffinity()
            exitProcess(0)
        }
    }
}
