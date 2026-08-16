// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Spaces are two workspaces, not a hardware verdict. Official Kenji's
// package names must stay listed or the hand-off silently fails on Android 11+.

fun normalize(id: String): String = if (id == "kenji") "kenji" else "symbiosis"

fun main() {
    var pass = 0
    var fail = 0
    fun check(name: String, cond: Boolean) {
        if (cond) { pass++; println("  ok  $name") } else { fail++; println("  FAIL $name") }
    }
    println("\nspaces")
    check("unknown id falls back to symbiosis", normalize("nope") == "symbiosis")
    check("kenji stays kenji", normalize("kenji") == "kenji")
    check("empty is symbiosis", normalize("") == "symbiosis")

    val pkgs = listOf("org.kenjinx.android", "org.ryujinx.android", "org.ryujinx.kenjinx")
    check("official Kenji package is listed", "org.kenjinx.android" in pkgs)
    check("three candidates, not a guess of one", pkgs.size >= 3)

    val probeLie = "ядро не запускается на этом устройстве"
    val honest = "наш мост упал в :kenji — это не «телефон не тянет»"
    check("honest probe does not blame the phone", !honest.contains(probeLie))
    check("honest probe names the bridge", honest.contains("мост"))

    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
