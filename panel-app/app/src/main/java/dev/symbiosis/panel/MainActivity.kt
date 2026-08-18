// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.symbiosis.panel

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * The Symbiosis panel, served from inside the APK.
 *
 * The previous shell fetched the page from GitHub Pages so that editing
 * docs/index.html updated every install with no reinstall. That traded away
 * the thing the panel is for: it was a blank retry screen with no network,
 * on first run and on every run where Pages was slow or blocked, and the app
 * was useless exactly when a phone is offline. The page now ships in assets/
 * and the APK is self-contained - it opens with no connection at all.
 *
 * WHY A CUSTOM ORIGIN RATHER THAN file://
 *   The panel keeps its GitHub token in localStorage and calls api.github.com.
 *   A file:// document has an opaque origin: Chromium gives it no usable
 *   localStorage and blocks its cross-origin fetches, so the token would be
 *   forgotten between launches and every API call would fail CORS. Serving the
 *   asset through [PanelAssets] under a real https:// origin gives the page a
 *   normal, stable security context, which is what both features need.
 *
 * Only the panel's own documents come from assets. Everything genuinely remote
 * - api.github.com, the session tunnels - still goes to the network as usual.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    // Named errorView, not error: Kotlin's stdlib error() shadows a bare
    // "error" inside a lambda, and the reference silently fails to resolve.
    private lateinit var errorView: LinearLayout

    private val assets_ by lazy { PanelAssets(this) }

    // Kept as a field so the retry button reloads the same address the app
    // started from, including an override supplied for testing.
    private val startUrl: String
        get() = intent?.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: PANEL_URL

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 6)
            visibility = View.GONE
        }

        web = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
            setBackgroundColor(BACKGROUND)
        }

        errorView = buildErrorView()

        root.addView(bar)
        root.addView(web)
        root.addView(errorView)
        setContentView(root)

        configureWebView()
        registerBackHandler()

        if (savedInstanceState == null) web.loadUrl(startUrl)
        else web.restoreState(savedInstanceState)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        web.settings.apply {
            javaScriptEnabled = true
            // The panel keeps the GitHub token in localStorage; without DOM
            // storage it would ask for the token on every launch.
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            // The page is delivered by the interceptor, not read off disk, so
            // the WebView itself never needs file or content access.
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString SymbiosisPanel/$SHELL_VERSION"
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Follow the system theme so the dark panel does not flash white.
            web.setBackgroundColor(BACKGROUND)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                bar.progress = newProgress
                bar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        web.webViewClient = object : WebViewClient() {
            // Serve the panel's own pages out of the APK. Returning null hands
            // the request back to the WebView, so api.github.com and the
            // tunnels are fetched over the network exactly as before.
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = assets_.serve(request.url)

            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host ?: return false
                // Keep the panel itself and the session tunnels inside the
                // app; send anything else - a GitHub run page, gofile - to the
                // browser, where the user is already signed in.
                val internal = host == PANEL_HOST ||
                    host.endsWith("github.io") ||
                    host.endsWith("ngrok-free.app") ||
                    host.endsWith("ngrok.io") ||
                    host.endsWith("ngrok.app")
                if (internal) return false
                return runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                    true
                }.getOrElse {
                    Toast.makeText(
                        this@MainActivity, "Нечем открыть: $url", Toast.LENGTH_SHORT
                    ).show()
                    true
                }
            }

            override fun onReceivedError(
                view: WebView, request: WebResourceRequest, err: WebResourceError
            ) {
                // Sub-resource failures are noise; only a failed main document
                // means the panel is not on screen. With the page bundled this
                // should now be unreachable, but a missing asset would still
                // land here and must not leave a blank screen.
                if (!request.isForMainFrame) return
                showError()
            }

            override fun onPageFinished(view: WebView, url: String) {
                bar.visibility = View.GONE
            }
        }

        // APKs built by the panel are downloaded through the system manager,
        // so they land in Downloads and can be installed from the notification.
        web.setDownloadListener { url, _, _, mime, _ ->
            runCatching {
                val request = DownloadManager.Request(Uri.parse(url)).apply {
                    setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    setMimeType(mime)
                }
                val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Скачивается…", Toast.LENGTH_SHORT).show()
            }.onFailure {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        }
    }

    private fun buildErrorView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(56, 56, 56, 56)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        addView(TextView(context).apply {
            text = "Панель не открылась"
            setTextColor(Color.WHITE)
            textSize = 19f
        })
        addView(TextView(context).apply {
            // The page ships inside the APK, so a failure here is a broken
            // build rather than a network problem. Say that, instead of
            // sending the user to check a connection that is not involved.
            text = "Страница входит в состав приложения, поэтому связь тут ни при чём. " +
                "Похоже, сборка повреждена — переустановите APK."
            setTextColor(Color.parseColor("#8a8a9e"))
            textSize = 14f
            setPadding(0, 16, 0, 24)
        })
        addView(android.widget.Button(context).apply {
            text = "Повторить"
            setOnClickListener {
                errorView.visibility = View.GONE
                web.visibility = View.VISIBLE
                web.loadUrl(startUrl)
            }
        })
    }

    private fun showError() {
        web.visibility = View.GONE
        errorView.visibility = View.VISIBLE
        bar.visibility = View.GONE
    }

    private fun registerBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Back walks the panel's own history first; only then leaves.
                if (web.canGoBack()) web.goBack()
                else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        // Detach before destroying, or the WebView leaks the activity.
        (web.parent as? ViewGroup)?.removeView(web)
        web.destroy()
        super.onDestroy()
    }

    companion object {
        /**
         * The origin the bundled panel is served under.
         *
         * A real https host, not file:// and not localhost: the page needs a
         * secure, non-opaque origin for localStorage to persist and for its
         * fetches to api.github.com to be ordinary CORS requests. Nothing is
         * ever fetched from this address over the network - every request to
         * it is answered from assets/ - but it must look like a normal site to
         * the WebView's security model.
         */
        const val PANEL_HOST = "panel.symbiosis.local"

        /** Where the panel lives, now inside the APK. */
        const val PANEL_URL = "https://$PANEL_HOST/index.html"

        /** Override for a local build or a fork, used by the tests. */
        const val EXTRA_URL = "panel_url"

        /** Bumped when the shell changes; shown in the user agent. */
        const val SHELL_VERSION = "2.0"

        private val BACKGROUND = Color.parseColor("#0d0d12")
    }
}
