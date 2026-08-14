// Mirrors GameFolderScanner.isLaunchable and the my-games line format.
val ROM = setOf("xci","nsp","nca","nro")
val SHOW = ROM + setOf("ncz","nsz","xcz","kip","nso")
fun launchable(n: String) = n.substringAfterLast('.',"").lowercase() in ROM || n.lowercase()=="main"
fun shown(n: String) = n.substringAfterLast('.',"").lowercase() in SHOW || n.lowercase()=="main"
fun line(n: String, size: String) = "• $n  ·  $size" + if (launchable(n)) "" else "  · не запустится"

fun main() {
    var bad=0
    fun check(t:String, ok:Boolean, got:String=""){
        println((if(ok)"ok   " else "FAIL ")+t+(if(got.isNotEmpty())"  [$got]" else "")); if(!ok)bad++ }

    check("nsp показывается", shown("Zelda.nsp"))
    check("xci показывается", shown("Mario.xci"))
    check("ncz показывается", shown("Game.ncz"))
    check("nsz показывается", shown("Game.nsz"))
    check("mp3 не показывается", !shown("song.mp3"))
    check("папка-имя без точки не ломает", !shown("Games"))

    check("nsp запускается", launchable("Zelda.nsp"))
    check("ncz НЕ запускается", !launchable("Game.ncz"))
    check("nsz НЕ запускается", !launchable("Game.nsz"))
    check("main запускается", launchable("main"))

    var l = line("Zelda.nsp","4.2 GB")
    check("обычный файл без пометки", !l.contains("не запустится"), l)
    l = line("Game.ncz","2.1 GB")
    check("сжатый помечен", l.contains("не запустится"), l)
    check("имя и размер на месте", l.contains("Game.ncz") && l.contains("2.1 GB"), l)

    // Регистр не должен решать.
    check("ВЕРХНИЙ регистр", shown("ZELDA.NSP") && launchable("ZELDA.NSP"))
    println(if(bad==0)"ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if(bad>0) kotlin.system.exitProcess(1)
}
