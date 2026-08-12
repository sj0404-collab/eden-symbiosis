// Mirrors addGamesRecursive's folder bookkeeping exactly, so the path-building
// can be exercised without the Android SDK.
data class Doc(val filename: String, val isDirectory: Boolean, val children: List<Doc> = emptyList())
data class Found(val name: String, val folder: String)

fun walk(files: List<Doc>, depth: Int, out: MutableList<Found>, folder: String = "") {
    if (depth <= 0) return
    files.forEach {
        if (it.isDirectory) {
            val childFolder = if (folder.isEmpty()) it.filename else "$folder/${it.filename}"
            walk(it.children, depth - 1, out, childFolder)
        } else if (it.filename.endsWith(".nsp") || it.filename.endsWith(".xci")) {
            out.add(Found(it.filename, folder))
        }
    }
}

fun folderName(folder: String) = folder.substringAfterLast('/', folder)

fun main() {
    var bad = 0
    fun check(n: String, ok: Boolean, extra: String = "") {
        println((if (ok) "ok   " else "FAIL ") + n + (if (extra.isNotEmpty()) "  [$extra]" else ""))
        if (!ok) bad++
    }

    val tree = listOf(
        Doc("top.nsp", false),
        Doc("RPG", true, listOf(
            Doc("Zelda", true, listOf(
                Doc("zelda.nsp", false),
                Doc("zelda-update.nsp", false)
            )),
            Doc("Chrono", true, listOf(Doc("chrono.xci", false)))
        )),
        Doc("Экшен", true, listOf(
            Doc("Blade Chimera", true, listOf(Doc("blade.nsp", false)))
        )),
        Doc("Switch", true, listOf(
            Doc("Платформеры", true, listOf(
                Doc("Mario", true, listOf(Doc("mario.nsp", false)))
            ))
        ))
    )

    val flat = mutableListOf<Found>()
    walk(tree, 24, flat)

    check("найдены все 6 игр", flat.size == 6, flat.size.toString())
    check("игра сверху без папки", flat.first { it.name == "top.nsp" }.folder == "")
    check("вложенность сохранена", flat.first { it.name == "zelda.nsp" }.folder == "RPG/Zelda",
          flat.first { it.name == "zelda.nsp" }.folder)
    check("кириллица в пути", flat.first { it.name == "blade.nsp" }.folder == "Экшен/Blade Chimera",
          flat.first { it.name == "blade.nsp" }.folder)
    check("имя папки для заголовка", folderName("RPG/Zelda") == "Zelda", folderName("RPG/Zelda"))
    check("имя папки верхнего уровня", folderName("") == "")

    // Grouping: a folder on disk becomes a group in the list.
    val groups = flat.groupBy { it.folder }
    check("групп ровно 5", groups.size == 5, groups.keys.sorted().toString())
    check("в Zelda две записи", groups["RPG/Zelda"]?.size == 2)

    // The old limit of 3 would have lost the deepest game.
    val shallow = mutableListOf<Found>()
    walk(tree, 3, shallow)
    check("старый лимит терял игру на 4 уровне", shallow.size < flat.size,
          "${shallow.size} против ${flat.size}")
    check("именно Mario терялся", shallow.none { it.name == "mario.nsp" } &&
          flat.any { it.name == "mario.nsp" })

    // depth 1 = only the top level, as upstream intends when deepScan is off
    val one = mutableListOf<Found>()
    walk(tree, 1, one)
    check("глубина 1 = только верх", one.size == 1 && one[0].name == "top.nsp")

    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
