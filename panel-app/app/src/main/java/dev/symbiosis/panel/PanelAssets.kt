// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package dev.symbiosis.panel

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream

/**
 * Answers the panel's own requests out of `assets/panel/`.
 *
 * The WebView asks for every subresource through shouldInterceptRequest. Any
 * URL on [MainActivity.PANEL_HOST] is answered from the APK; everything else
 * returns null, which tells the WebView to fetch it from the network as usual.
 * That split is the whole design: the panel is local, the GitHub API is not.
 *
 * The panel is one inlined HTML file per page - no CSS, JS, or font is fetched
 * separately - so this stays deliberately small.
 */
class PanelAssets(private val context: Context) {

    /**
     * @return a response read from the APK, or null to let the network handle it.
     */
    fun serve(url: Uri?): WebResourceResponse? {
        if (url == null) return null
        // Only this host is ours. A request to api.github.com must fall
        // through untouched, or the panel loses the data it exists to show.
        if (!url.host.equals(MainActivity.PANEL_HOST, ignoreCase = true)) return null

        val name = fileNameFor(url) ?: return notFound()
        return try {
            val stream: InputStream = context.assets.open("$ASSET_DIR/$name")
            WebResourceResponse(mimeFor(name), "utf-8", 200, "OK", headers(), stream)
        } catch (e: IOException) {
            // A page that is genuinely absent from the build. Say so rather
            // than letting the WebView show its own "webpage not available",
            // which blames the network for a packaging mistake.
            notFound()
        }
    }

    /**
     * Maps a request path onto a file in assets, refusing anything that tries
     * to climb out of the directory.
     *
     * Paths come from a page we ship, but the WebView will also hand over
     * whatever a link or a redirect produces, so this is not a formality.
     */
    private fun fileNameFor(url: Uri): String? {
        val path = url.path.orEmpty().trimStart('/')
        if (path.isEmpty()) return "index.html"

        // No traversal, no subdirectories: the panel is a flat set of files.
        if (path.contains("..") || path.contains('/') || path.contains('\\')) return null
        if (!ALLOWED.contains(path)) return null
        return path
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".html") -> "text/html"
        name.endsWith(".js") -> "application/javascript"
        name.endsWith(".css") -> "text/css"
        name.endsWith(".svg") -> "image/svg+xml"
        name.endsWith(".json") || name.endsWith(".webmanifest") -> "application/json"
        name.endsWith(".png") -> "image/png"
        else -> "application/octet-stream"
    }

    /**
     * The page calls api.github.com from JavaScript. Those are cross-origin
     * requests made by the page itself, so they need no headers from us - but
     * the document must not be cached by the WebView across upgrades, or a
     * reinstall could keep showing the previous build's panel.
     */
    private fun headers(): Map<String, String> = mapOf(
        "Cache-Control" to "no-store",
        // The panel is same-origin with itself and talks only to APIs that
        // send their own CORS headers; nothing here needs to be embeddable.
        "X-Content-Type-Options" to "nosniff"
    )

    private fun notFound(): WebResourceResponse = WebResourceResponse(
        "text/html", "utf-8", 404, "Not Found", headers(),
        ByteArrayInputStream(MISSING.toByteArray(Charsets.UTF_8))
    )

    companion object {
        /** Where the pages sit inside the APK. */
        const val ASSET_DIR = "panel"

        /**
         * Everything the panel may serve. An allow-list rather than a bare
         * assets.open(): it keeps a stray request from probing the APK, and it
         * fails loudly in the build if a page is renamed without updating it.
         */
        val ALLOWED = setOf(
            "index.html",
            "library.html",
            "desks.html",
            "panel.html",
            "icon.svg",
            "manifest.webmanifest"
        )

        private const val MISSING =
            "<!doctype html><meta charset=utf-8>" +
                "<body style=\"background:#0d0d12;color:#e8e8f0;font:15px sans-serif;padding:24px\">" +
                "<h3>Страница не входит в сборку</h3>" +
                "<p style=\"color:#8a8a9e\">Этот файл не был упакован в APK. " +
                "Обычно это значит, что сборка собрана не полностью — переустановите приложение.</p>"
    }
}
