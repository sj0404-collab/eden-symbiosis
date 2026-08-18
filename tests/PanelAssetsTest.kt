// SPDX-FileCopyrightText: Copyright 2026 Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// The panel now ships inside the APK and is served by PanelAssets through
// shouldInterceptRequest. That routing decides three things that are invisible
// until the app is on a phone:
//
//   * a request to api.github.com must NOT be answered from assets, or the
//     panel shows its own HTML where the API response should be;
//   * a request to the panel host must be answered even with no network;
//   * a path must not be able to climb out of assets/panel.
//
// The logic is duplicated here rather than imported because the real class
// needs the Android framework; the copy is kept honest by asserting it against
// the shipped source at the end - if PanelAssets.kt changes shape, this fails.
//
// Run: kotlinc tests/PanelAssetsTest.kt -include-runtime -d /tmp/t.jar && java -jar /tmp/t.jar

import java.io.File

// ── the logic under test, mirrored from PanelAssets.kt ───────────────
val ALLOWED = setOf(
    "index.html", "library.html", "desks.html", "panel.html",
    "icon.svg", "manifest.webmanifest"
)
const val PANEL_HOST = "panel.symbiosis.local"

/** null = let the network handle it; "404" = ours but missing; else the file. */
fun route(host: String?, path: String?): String? {
    if (!host.equals(PANEL_HOST, ignoreCase = true)) return null
    val p = path.orEmpty().trimStart('/')
    if (p.isEmpty()) return "index.html"
    if (p.contains("..") || p.contains('/') || p.contains('\\')) return "404"
    if (!ALLOWED.contains(p)) return "404"
    return p
}

fun mimeFor(name: String): String = when {
    name.endsWith(".html") -> "text/html"
    name.endsWith(".js") -> "application/javascript"
    name.endsWith(".css") -> "text/css"
    name.endsWith(".svg") -> "image/svg+xml"
    name.endsWith(".json") || name.endsWith(".webmanifest") -> "application/json"
    name.endsWith(".png") -> "image/png"
    else -> "application/octet-stream"
}

var failed = 0
fun check(name: String, cond: Boolean, detail: String = "") {
    if (cond) println("  ok   $name")
    else { failed++; println("  FAIL $name" + if (detail.isNotEmpty()) "\n       $detail" else "") }
}

fun main() {
    println("routing — what the APK answers and what the network answers")

    // The whole point of the panel: these must go to the network untouched.
    check("api.github.com falls through to the network",
        route("api.github.com", "/repos/x/y") == null)
    check("a gofile mirror falls through",
        route("store1.gofile.io", "/download/x") == null)
    check("an ngrok tunnel falls through",
        route("abc.ngrok-free.app", "/api/agent/run") == null)
    check("github.io falls through",
        route("sj0404-collab.github.io", "/symbiosis/") == null)

    // These are ours and must work with no connection at all.
    check("the root serves the panel", route(PANEL_HOST, "/") == "index.html")
    check("an empty path serves the panel", route(PANEL_HOST, "") == "index.html")
    check("index.html is served", route(PANEL_HOST, "/index.html") == "index.html")
    check("library.html is served", route(PANEL_HOST, "/library.html") == "library.html")
    check("desks.html is served", route(PANEL_HOST, "/desks.html") == "desks.html")
    check("panel.html is served", route(PANEL_HOST, "/panel.html") == "panel.html")
    check("the manifest is served",
        route(PANEL_HOST, "/manifest.webmanifest") == "manifest.webmanifest")
    check("the host match is case-insensitive",
        route("Panel.Symbiosis.Local", "/index.html") == "index.html")

    // Traversal and probing.
    check("a parent-directory escape is refused",
        route(PANEL_HOST, "/../AndroidManifest.xml") == "404")
    check("a nested path is refused",
        route(PANEL_HOST, "/sub/index.html") == "404")
    check("a backslash path is refused",
        route(PANEL_HOST, "/..\\secrets") == "404")
    check("an unknown file is refused",
        route(PANEL_HOST, "/secrets.txt") == "404")

    println("\nmime types")
    check("html", mimeFor("index.html") == "text/html")
    check("svg", mimeFor("icon.svg") == "image/svg+xml")
    check("webmanifest is JSON",
        mimeFor("manifest.webmanifest") == "application/json")

    // ── the mirror must match the real source ────────────────────────
    println("\nagreement with the shipped code")
    val src = File("panel-app/app/src/main/java/dev/symbiosis/panel/PanelAssets.kt")
    check("PanelAssets.kt is where this test expects it", src.exists(), src.path)
    if (src.exists()) {
        val text = src.readText()
        val declared = Regex("\"([\\w.-]+\\.(?:html|svg|webmanifest))\"")
            .findAll(text).map { it.groupValues[1] }.toSet()
        check("the allow-list here matches the one in PanelAssets.kt",
            declared == ALLOWED,
            "source=$declared test=$ALLOWED")
        check("the host guard is still an equals check on the panel host",
            text.contains("url.host.equals(MainActivity.PANEL_HOST"),
            "if this changed, api.github.com may no longer fall through")
        check("traversal is still refused",
            text.contains("contains(\"..\")") && text.contains("contains('/')"))
        check("a missing asset still returns 404 rather than throwing",
            text.contains("catch (e: IOException)"))
    }

    // The pages themselves have to exist to be packaged.
    println("\npages present in docs/")
    for (page in ALLOWED.sorted()) {
        val f = File("docs/$page")
        check("docs/$page exists and is not empty", f.exists() && f.length() > 0,
            if (f.exists()) "${f.length()} bytes" else "missing")
    }

    // MainActivity must no longer fetch the panel from Pages.
    println("\nthe shell no longer depends on the network")
    val act = File("panel-app/app/src/main/java/dev/symbiosis/panel/MainActivity.kt")
    if (act.exists()) {
        val text = act.readText()
        check("PANEL_URL points at the bundled host",
            text.contains("const val PANEL_URL = \"https://\$PANEL_HOST/index.html\""),
            "still loading from the network?")
        check("the shell installs the interceptor",
            text.contains("shouldInterceptRequest"))
        check("no github.io URL is left in the shell",
            !text.contains("github.io/symbiosis/\""))
    } else {
        check("MainActivity.kt found", false, act.path)
    }

    println()
    if (failed > 0) { println("$failed check(s) failed"); System.exit(1) }
    println("all checks passed")
}
