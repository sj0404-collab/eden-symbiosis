// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

import java.util.Locale

fun outName(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    val stem = name.substringAfterLast('/').substringBeforeLast('.').ifBlank { "dump" }
    return when (ext) {
        "nsz" -> "$stem.nsp"
        "xcz" -> "$stem.nsp"
        "ncz" -> "$stem.nca"
        "xci", "nsp", "nca", "nro" -> name.substringAfterLast('/')
        else -> "$stem.bin"
    }
}

fun cardAction(canLaunch: Boolean): String = if (canLaunch) "launch" else "delete"

var pass = 0
var fail = 0
fun check(n: String, c: Boolean) {
    if (c) { pass++; println("  ok  $n") } else { fail++; println("  FAIL $n") }
}

fun main() {
    println("\nconverter tab")
    check("nsz becomes nsp", outName("Blade.nsz") == "Blade.nsp")
    check("xcz becomes nsp (launchable)", outName("cart.xcz") == "cart.nsp")
    check("ncz becomes nca", outName("a.ncz") == "a.nca")
    check("xci stays xci", outName("game.xci") == "game.xci")
    check("openable card shows launch", cardAction(true) == "launch")
    check("failed card shows delete", cardAction(false) == "delete")
    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
