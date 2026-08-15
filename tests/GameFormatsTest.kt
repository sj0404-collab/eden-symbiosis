// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// loader.cpp GuessFromFilename is the source of truth. The library must
// import every format the core will actually open, including XCI.

import java.util.Locale

val LAUNCHABLE = setOf("xci", "nsp", "nca", "nro", "nso", "kip")
val NAMES = setOf("main", "00")
val COMPRESSED = setOf("nsz", "xcz", "ncz")

fun ext(n: String) = n.substringAfterLast('.', "").lowercase(Locale.ROOT)

fun isLaunchable(name: String): Boolean {
    val lower = name.lowercase(Locale.ROOT)
    if (lower in NAMES) return true
    return ext(lower) in LAUNCHABLE
}

var pass = 0
var fail = 0
fun check(name: String, cond: Boolean) {
    if (cond) { pass++; println("  ok  $name") } else { fail++; println("  FAIL $name") }
}

fun main() {
    println("\ngame formats = loader set")
    check("xci is a game", isLaunchable("Blade.xci"))
    check("XCI uppercase is a game", isLaunchable("CART.XCI"))
    check("nsp is a game", isLaunchable("shop.nsp"))
    check("nca is a game", isLaunchable("app.nca"))
    check("nro homebrew is a game", isLaunchable("hb.nro"))
    check("nso is a game", isLaunchable("mod.nso"))
    check("kip is accepted by the loader", isLaunchable("sys.kip"))
    check("file named main is a deconstructed rom", isLaunchable("main"))
    check("file named 00 is a split nca", isLaunchable("00"))
    check("nsz is not launched", !isLaunchable("dump.nsz"))
    check("xcz is not launched", !isLaunchable("cart.xcz"))
    check("ncz is not launched", !isLaunchable("raw.ncz"))
    check("txt is not a game", !isLaunchable("readme.txt"))
    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
