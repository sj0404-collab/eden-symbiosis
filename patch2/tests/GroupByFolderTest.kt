import java.util.Locale
data class G(val title: String, val folder: String)

fun groupByFolder(list: List<G>): List<G> =
    list.sortedWith(compareBy({ it.folder.lowercase(Locale.getDefault()) },
                              { it.title.lowercase(Locale.getDefault()) }))

fun main() {
    var bad = 0
    fun check(n: String, ok: Boolean, extra: String = "") {
        println((if (ok) "ok   " else "FAIL ") + n + (if (extra.isNotEmpty()) "  [$extra]" else ""))
        if (!ok) bad++
    }
    // Deliberately shuffled, as a recursive scan would deliver it.
    val input = listOf(
        G("Zelda TOTK", "RPG/Zelda"),
        G("Одинокая", ""),
        G("Chrono Trigger", "RPG/Chrono"),
        G("Zelda BOTW", "RPG/Zelda"),
        G("Blade Chimera", "Экшен/Blade"),
        G("Another Top", "")
    )
    val out = groupByFolder(input)
    check("верхний уровень идёт первым",
          out.take(2).all { it.folder == "" }, out.take(2).map { it.title }.toString())
    val zeldaIdx = out.withIndex().filter { it.value.folder == "RPG/Zelda" }.map { it.index }
    check("игры одной папки рядом", zeldaIdx == listOf(zeldaIdx.first(), zeldaIdx.first() + 1),
          zeldaIdx.toString())
    check("внутри папки по алфавиту",
          out.first { it.folder == "RPG/Zelda" }.title == "Zelda BOTW")
    check("кириллическая папка не теряется", out.any { it.folder == "Экшен/Blade" })
    check("ничего не потеряно", out.size == input.size, "${out.size} из ${input.size}")
    check("состав тот же", out.map { it.title }.toSet() == input.map { it.title }.toSet())
    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
