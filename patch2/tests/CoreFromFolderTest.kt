import org.yuzu.yuzu_emu.utils.CoreFromFolder as C
import android.content.Context
import java.io.File

fun main() {
    var bad = 0
    fun check(n: String, ok: Boolean, extra: String = "") {
        println((if (ok) "ok   " else "FAIL ") + n + (if (extra.isNotEmpty()) "  [$extra]" else ""))
        if (!ok) bad++
    }
    File("/tmp/cft/app").deleteRecursively(); File("/tmp/cft/sd").deleteRecursively()
    val ctx = Context()

    // Ничего нет.
    val s0 = C.locate(ctx)
    check("без папки -> Missing", s0 is C.State.Missing)
    check("перечислены места поиска", (s0 as C.State.Missing).looked.size >= 3,
          s0.looked.size.toString() + " путей")
    check("сообщение объясняет, что делать",
          C.describe(ctx).contains("EdenCore") && C.describe(ctx).contains("libyuzu-android.so"))

    // Обрезанный файл - не грузить.
    val folder = File("/tmp/cft/sd/EdenCore").apply { mkdirs() }
    File(folder, C.CORE_NAME).writeBytes(ByteArray(5000))
    check("обрезанный файл -> Broken", C.locate(ctx) is C.State.Broken,
          (C.locate(ctx) as? C.State.Broken)?.reason ?: "")

    // Настоящий по размеру core.
    val core = File(folder, C.CORE_NAME)
    core.writeBytes(ByteArray(3_000_000) { (it % 251).toByte() })
    val s1 = C.locate(ctx)
    check("нормальный файл -> Found", s1 is C.State.Found, (s1 as? C.State.Found)?.folder ?: "")
    check("размер сообщается", (s1 as C.State.Found).bytes == 3_000_000L)

    // Спутники рядом.
    for (n in C.COMPANIONS.take(2)) File(folder, n).writeBytes(ByteArray(4096))

    // Само копирование - System.load на JVM упадёт, это ожидаемо;
    // проверяем, что до него файл скопирован и закрыт от записи.
    val err = C.load(ctx)
    val staged = C.stagedCore(ctx)
    check("файл скопирован в память приложения", staged.isFile, staged.absolutePath)
    check("размер совпал", staged.length() == 3_000_000L)
    check("копия закрыта от записи", !staged.canWrite())
    check("спутники скопированы",
          C.COMPANIONS.take(2).all { File(C.stageDir(ctx), it).isFile })
    check("ошибка про загрузку, а не про копирование",
          err != null && !err.contains("не найдена"), err ?: "null")

    // Повторный запуск не должен копировать заново.
    check("штамп записан", File(C.stageDir(ctx), C.CORE_NAME + ".stamp").isFile)
    check("upToDate видит, что копия свежая", C.upToDate(core, staged))

    // Подменили ядро в папке - копия должна обновиться.
    Thread.sleep(1100)
    core.writeBytes(ByteArray(3_100_000) { 7 })
    check("подмена файла замечена", !C.upToDate(core, staged))
    C.load(ctx)
    check("копия обновилась", C.stagedCore(ctx).length() == 3_100_000L,
          C.stagedCore(ctx).length().toString())

    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
