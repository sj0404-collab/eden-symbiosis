// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

fun resLabel(index: Int): String {
    val labels = listOf("0.25x", "0.5x", "0.75x", "1x", "1.25x", "1.5x", "2x", "3x", "4x")
    return labels.getOrNull(index) ?: if (index < 0) "" else "значение $index"
}

fun allowedBool(key: String, allow: Set<String>): Boolean = key in allow

fun allowedRes(index: Int): Boolean = index in 0..3

data class Card(
    val title: String,
    val lastPlayed: Long = 0,
    val playSeconds: Long = 0,
    val hasSave: Boolean = false,
    val hasMod: Boolean = false
)

fun filterCards(list: List<Card>, filter: String): List<Card> = when (filter) {
    "save" -> list.filter { it.hasSave }
    "mod" -> list.filter { it.hasMod }
    "fresh" -> list.filter { it.playSeconds == 0L && it.lastPlayed == 0L }
    else -> list
}

fun progressOf(hasSave: Boolean, playSeconds: Long, saveSize: String): String = when {
    hasSave -> "есть сейв" + if (saveSize.isNotEmpty()) " · $saveSize" else ""
    playSeconds > 0 -> "играли, сейва нет"
    else -> "не начато"
}

fun cpuLabel(index: Int): String = when (index) {
    1 -> "NCE"
    0 -> "Dynarmic"
    else -> "CPU $index"
}

fun cycleCpu(cur: Int): Int = if (cur == 1) 0 else 1

fun playerFor(selected: String, kenjiReady: Boolean): String =
    if (selected == "kenji" && kenjiReady) "kenji" else "eden"

fun wrapIndex(i: Int, n: Int): Int {
    if (n <= 0) return 0
    return ((i % n) + n) % n
}

fun sortCards(list: List<Card>, sort: String): List<Card> = when (sort) {
    "recent" -> list.sortedWith(compareByDescending<Card> { it.lastPlayed }.thenBy { it.title })
    "play" -> list.sortedWith(compareByDescending<Card> { it.playSeconds }.thenBy { it.title })
    else -> list.sortedBy { it.title }
}

var pass = 0
var fail = 0
fun check(n: String, c: Boolean) {
    if (c) { pass++; println("  ok  $n") } else { fail++; println("  FAIL $n") }
}

fun main() {
    println("\nlauncher ui")
    val allow = setOf("RENDERER_USE_DISK_SHADER_CACHE", "USE_DOCKED_MODE", "AUDIO_MUTED")
    check("known toggle allowed", allowedBool("USE_DOCKED_MODE", allow))
    check("unknown toggle rejected", !allowedBool("FAKE_TURBO_MODE", allow))
    check("0.25x is index 0", resLabel(0) == "0.25x")
    check("1x is index 3", resLabel(3) == "1x")
    check("launcher blocks 2x", !allowedRes(6) && allowedRes(3))
    val cards = listOf(
        Card("Zelda", lastPlayed = 10, playSeconds = 100),
        Card("Blade", lastPlayed = 50, playSeconds = 20, hasSave = true, hasMod = true),
        Card("Fresh")
    )
    check("sort by name", sortCards(cards, "name").map { it.title } == listOf("Blade", "Fresh", "Zelda"))
    check("sort by recent", sortCards(cards, "recent").first().title == "Blade")
    check("sort by play", sortCards(cards, "play").first().title == "Zelda")
    check("filter save", filterCards(cards, "save").map { it.title } == listOf("Blade"))
    check("filter fresh", filterCards(cards, "fresh").map { it.title } == listOf("Fresh"))
    check("filter all keeps three", filterCards(cards, "all").size == 3)
    check("progress with real save", progressOf(true, 10, "4.2 МБ") == "есть сейв · 4.2 МБ")
    check("progress never invents percent", !progressOf(true, 10, "4.2 МБ").contains("%"))
    check("progress unplayed", progressOf(false, 0, "") == "не начато")
    check("wrap left from 0", wrapIndex(-1, 3) == 2)
    check("wrap right from last", wrapIndex(3, 3) == 0)
    check("cpu 0 is Dynarmic", cpuLabel(0) == "Dynarmic")
    check("cpu 1 is NCE", cpuLabel(1) == "NCE")
    check("cycle dynarmic to nce", cycleCpu(0) == 1)
    check("eden always eden player", playerFor("eden", true) == "eden")
    check("kenji without file stays eden", playerFor("kenji", false) == "eden")
    check("kenji ready uses kenji player", playerFor("kenji", true) == "kenji")
    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
