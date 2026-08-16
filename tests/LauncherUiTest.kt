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
    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
