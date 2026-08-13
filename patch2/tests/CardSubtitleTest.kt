// The second line of a list card: folder, developer, or both.
//
// WHY THIS IS TESTED SEPARATELY
//   The folder was recorded by the scan and used for ordering, but nothing on
//   screen ever showed it - so a library sorted into folders looked like an
//   arbitrarily ordered pile, and two games with the same title in different
//   folders were indistinguishable. R8 independently confirmed the field was
//   dead and stripped Game.folderName from the release build.
//
//   The rule has four cases and each one is easy to get subtly wrong: an empty
//   developer must not leave a dangling separator, and a top-level game must
//   still show its developer exactly as upstream does. That is cheap to check
//   here and expensive to check on a phone.

/** Mirrors Game.folderName. */
fun folderName(folder: String) = folder.substringAfterLast('/', folder)

/** Mirrors the expression in GameAdapter.bindListView. */
fun subtitle(folder: String, developer: String): String = when {
    folder.isNotEmpty() && developer.isNotEmpty() -> "${folderName(folder)} · $developer"
    folder.isNotEmpty() -> folderName(folder)
    else -> developer
}

fun main() {
    var bad = 0
    fun check(name: String, got: String, want: String) {
        val ok = got == want
        println((if (ok) "ok   " else "FAIL ") + name + "  [\"" + got + "\"]")
        if (!ok) {
            println("      ожидалось: \"" + want + "\"")
            bad++
        }
    }

    check("папка и разработчик", subtitle("RPG/Zelda", "Nintendo"), "Zelda · Nintendo")
    check("только папка", subtitle("RPG/Zelda", ""), "Zelda")
    // The upstream behaviour for a game that is not in a folder, unchanged.
    check("только разработчик", subtitle("", "Nintendo"), "Nintendo")
    check("ничего", subtitle("", ""), "")
    // Cyrillic survives substringAfterLast - the folder names here are Russian.
    check("кириллица", subtitle("Экшен/Blade Chimera", "Team"), "Blade Chimera · Team")
    // A top-level folder has no slash at all; substringAfterLast must return
    // the whole string, not the empty one - that is what the second argument
    // to substringAfterLast is for, and getting it wrong yields a blank line.
    check("папка без вложенности", subtitle("Switch", "Sega"), "Switch · Sega")

    // No dangling separator anywhere.
    for (f in listOf("", "A", "A/B")) for (d in listOf("", "Dev")) {
        val s = subtitle(f, d)
        if (s.startsWith("·") || s.endsWith("·") || s.contains("  ")) {
            println("FAIL висячий разделитель: \"$s\"  (folder=\"$f\" dev=\"$d\")")
            bad++
        }
    }
    println("ok   разделитель нигде не висит")

    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
