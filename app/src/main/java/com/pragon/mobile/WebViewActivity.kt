package com.pragon.mobile

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Shows the REAL PhoneView web app (the same app.html a desktop browser
 * gets when you scan the QR) inside the phone app, so the UI is exactly
 * Pragon's PhoneView - not a redrawn copy of it. Auth is handled by the PC
 * (see PhoneViewServer's "web_login" message / GET /mobile-login), so
 * nothing here needs its own login.
 */
class WebViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_URL = "url"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "Couldn't open PhoneView.", Toast.LENGTH_LONG).show()
            finish(); return
        }

        val web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true // required: app.html uses sessionStorage/localStorage for its login
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER
            )
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, u: String?) {
                progress.visibility = View.GONE
            }
        }

        val root = FrameLayout(this).apply {
            setBackgroundColor(android.graphics.Color.parseColor("#0A0A0F"))
            addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(progress)
        }
        setContentView(root)
        web.loadUrl(url)
    }
}
