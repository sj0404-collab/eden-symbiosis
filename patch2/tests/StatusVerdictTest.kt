// Mirrors the decision in SetupStatus.games() exactly.
fun verdict(games: Int, skipped: Int, scanned: Boolean, imported: Int, noAccess: Boolean): String {
    val imp = if (scanned) imported else -1
    return when {
        noAccess -> "нет доступа"
        games == 0 && skipped > 0 -> "файлы есть, но не игры"
        games == 0 -> "нет файлов игр"
        imp in 0 until games -> "распознано $imp — проверь ключи"
        imp < 0 -> "$games найдено · открой список"
        else -> "$games игр"
    }
}
fun tick(games: Int, scanned: Boolean, imported: Int, noAccess: Boolean) =
    games > 0 && !(scanned && imported == 0) && !noAccess

fun main() {
    var bad = 0
    fun check(n: String, ok: Boolean, got: String = "") {
        println((if (ok) "ok   " else "FAIL ") + n + (if (got.isNotEmpty()) "  [$got]" else ""))
        if (!ok) bad++
    }
    // Точный случай со скриншота: 2 файла, скан ещё не проходил.
    var v = verdict(2, 0, false, 0, false)
    check("до скана не обвиняет ключи", !v.contains("ключи"), v)
    check("до скана галочка стоит", tick(2, false, 0, false))
    // Скан прошёл и правда ничего не принял - вот тогда обвиняем.
    v = verdict(2, 0, true, 0, false)
    check("после скана 0 -> предупреждение", v.contains("ключи"), v)
    check("после скана 0 -> крестик", !tick(2, true, 0, false))
    // Скан прошёл, часть принята.
    v = verdict(3, 0, true, 1, false)
    check("частично распознано", v.contains("распознано 1"), v)
    // Всё хорошо.
    v = verdict(2, 0, true, 2, false)
    check("всё распознано", v == "2 игр", v)
    check("галочка", tick(2, true, 2, false))
    // Нет доступа - главнее всего.
    check("нет доступа важнее", verdict(2, 0, true, 0, true) == "нет доступа")
    check("нет доступа -> крестик", !tick(2, true, 2, true))
    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
