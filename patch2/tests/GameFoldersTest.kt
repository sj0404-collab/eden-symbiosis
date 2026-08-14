import org.yuzu.yuzu_emu.utils.GameFolders as G
fun main() {
    var bad = 0
    fun check(n: String, ok: Boolean, extra: String = "") {
        println((if (ok) "ok   " else "FAIL ") + n + (if (extra.isNotEmpty()) "  [$extra]" else ""))
        if (!ok) bad++
    }
    G.clear()
    check("пусто -> \"\"", G.folderOf("x") == "")
    G.remember("a.nsp", "RPG/Zelda")
    check("папка запомнена", G.folderOf("a.nsp") == "RPG/Zelda")
    check("имя папки", G.folderNameOf("a.nsp") == "Zelda")
    G.remember("b.nsp", "Switch")
    check("папка без вложенности", G.folderNameOf("b.nsp") == "Switch")
    G.remember("c.nsp", "")
    check("пустая папка не хранится", G.folderOf("c.nsp") == "")
    G.remember("d.nsp", "Экшен/Blade")
    check("кириллица", G.folderNameOf("d.nsp") == "Blade")
    check("неизвестный путь безопасен", G.folderNameOf("нет") == "")
    check("размер", G.size() == 3, G.size().toString())
    G.clear()
    check("clear очищает", G.size() == 0 && G.folderOf("a.nsp") == "")
    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
