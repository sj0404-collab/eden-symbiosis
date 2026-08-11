// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
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
 * A window onto the panel, which lives on GitHub Pages.
 *
 * The page is fetched from the network rather than bundled in the APK, so a
 * change pushed to docs/index.html is live in the app on the next launch with
 * no reinstall. That is the entire reason this app exists as a shell: the
 * alternative - packaging the HTML as an asset - would mean rebuilding and
 * resideloading the APK for every wording change.
 *
 * The cost of that choice is that the app is useless with no network on first
 * run, since there is nothing local to fall back to. The WebView's own HTTP
 * cache covers later launches: [WebSettings.LOAD_CACHE_ELSE_NETWORK] is not
 * used, because silently serving a stale panel would hide exactly the updates
 * this design is for. Instead a failed load shows a retry screen that says so.
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var bar: ProgressBar
    private lateinit var error: LinearLayout

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

        error = buildErrorView()

        root.addView(bar)
        root.addView(web)
        root.addView(error)
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
            // No file or content access: this shell only ever shows a remote
            // page, so nothing local should be reachable from it.
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            userAgentString = "$userAgentString SymbiosisPanel/1.0"
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
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val host = url.host ?: return false
                // Keep the panel and the session tunnels inside the app; send
                // anything else - a GitHub run page, gofile - to the browser,
                // where the user is already signed in.
                val internal = host.endsWith("github.io") ||
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
                // means the panel is not on screen.
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
            text = "Панель не загрузилась"
            setTextColor(Color.WHITE)
            textSize = 19f
        })
        addView(TextView(context).apply {
            // Say why rather than showing a generic failure: with the page
            // living on the network, "no connection" is the whole explanation.
            text = "Страница живёт в сети, локальной копии нет. " +
                "Проверьте связь и попробуйте снова."
            setTextColor(Color.parseColor("#8a8a9e"))
            textSize = 14f
            setPadding(0, 16, 0, 24)
        })
        addView(android.widget.Button(context).apply {
            text = "Повторить"
            setOnClickListener {
                error.visibility = View.GONE
                web.visibility = View.VISIBLE
                web.loadUrl(startUrl)
            }
        })
    }

    private fun showError() {
        web.visibility = View.GONE
        error.visibility = View.VISIBLE
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
        /** Where the panel lives. Changing the page updates every install. */
        const val PANEL_URL = "https://sj0404-collab.github.io/eden-symbiosis/"

        /** Override for a local build or a fork, used by the tests. */
        const val EXTRA_URL = "panel_url"

        private val BACKGROUND = Color.parseColor("#0d0d12")
    }
}
