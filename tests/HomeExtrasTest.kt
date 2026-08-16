// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

fun classify(blob: String): String = when {
    blob.contains("device_lost") || blob.contains("vk_error_device_lost") -> "device_lost"
    blob.contains("out of memory") || blob.contains("lowmemorykiller") -> "oom"
    blob.contains("shader") && blob.contains("fail") -> "shader"
    blob.isBlank() || blob.contains("nothing in the log") -> "unknown"
    else -> "other"
}

fun parsePlay(raw: ByteArray): Long {
    val text = raw.toString(Charsets.UTF_8).trim()
    text.toLongOrNull()?.let { if (it >= 0) return it }
    return 0
}

fun secondsFromRecords(raw: ByteArray, wantIds: Set<Long>): Long {
    if (raw.size < 16 || wantIds.isEmpty()) return 0
    val bb = java.nio.ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val n = raw.size / 16
    var best = 0L
    repeat(n) {
        val id = bb.long
        val sec = bb.long
        if (id in wantIds && sec in 1..(3600L * 24 * 365 * 20) && sec > best) best = sec
    }
    return best
}

fun recordBytes(id: Long, seconds: Long): ByteArray {
    val bb = java.nio.ByteBuffer.allocate(16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    bb.putLong(id)
    bb.putLong(seconds)
    return bb.array()
}

fun formatPlay(seconds: Long): String {
    if (seconds <= 0) return ""
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h} ч ${m} мин" else if (m > 0) "${m} мин" else "${seconds} с"
}

fun listWithoutKeys(keys: Boolean, disk: List<String>): List<String> =
    if (!keys) emptyList() else disk

fun warnRam(leftMb: Long): Boolean = leftMb < 700

var pass = 0
var fail = 0
fun check(n: String, c: Boolean) {
    if (c) { pass++; println("  ok  $n") } else { fail++; println("  FAIL $n") }
}

fun main() {
    println("\nhome extras")
    check("device lost from vk line", classify("vk_error_device_lost in queue") == "device_lost")
    check("oom from lmk", classify("lowmemorykiller killed pid") == "oom")
    check("shader compile fail", classify("shader compile fail pipeline") == "shader")
    check("empty log is unknown not fake", classify("") == "unknown")
    check("play time ascii", parsePlay("3661".toByteArray()) == 3661L)
    check("play label hours", formatPlay(3661) == "1 ч 1 мин")
    check("no play if zero", formatPlay(0) == "")
    val blade = 0x01007FC01CF4E000L
    val bin = recordBytes(blade, 3661) + recordBytes(0x0100AABBCCDDE000L, 99)
    check("play time from uuid.bin record", secondsFromRecords(bin, setOf(blade)) == 3661L)
    check("other title in same bin is ignored", secondsFromRecords(bin, setOf(0x0100000000010000L)) == 0L)
    check("short bin is not a fake play time", secondsFromRecords(ByteArray(8), setOf(blade)) == 0L)
    check("no keys → empty list", listWithoutKeys(false, listOf("a.nsp")).isEmpty())
    check("keys → keep disk", listWithoutKeys(true, listOf("a.nsp")) == listOf("a.nsp"))
    check("700 mb is the warn line", warnRam(500) && !warnRam(900))
    println("\n$pass passed, $fail failed")
    if (fail > 0) kotlin.system.exitProcess(1)
}
