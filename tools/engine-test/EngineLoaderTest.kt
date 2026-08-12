import org.yuzu.yuzu_emu.utils.EngineLoader
import org.yuzu.yuzu_emu.utils.EngineDownloader
import android.content.Context
import java.io.File

fun main() {
    var bad = 0
    fun check(n: String, ok: Boolean, extra: String = "") {
        println((if (ok) "ok   " else "FAIL ") + n + (if (extra.isNotEmpty()) "  [$extra]" else ""))
        if (!ok) bad++
    }
    val ctx = Context()
    File("/tmp/engines").deleteRecursively()

    // Eden is compiled in and must never be treated as downloadable.
    check("Eden = Builtin", EngineLoader.state(ctx, EngineLoader.Engine.EDEN) is EngineLoader.State.Builtin)
    check("Eden не грузится повторно", EngineLoader.load(ctx, EngineLoader.Engine.EDEN) == null)
    check("URL для Eden пустой", EngineDownloader.urlFor(EngineLoader.Engine.EDEN).isEmpty())

    // Nothing downloaded yet.
    val s0 = EngineLoader.state(ctx, EngineLoader.Engine.KENJI)
    check("Kenji изначально Missing", s0 is EngineLoader.State.Missing,
          (s0 as? EngineLoader.State.Missing)?.bytes?.toString() ?: "$s0")
    check("размер объявлен честно", (s0 as EngineLoader.State.Missing).bytes == 57_321_040L)
    check("загрузка без файла даёт понятную ошибку",
          EngineLoader.load(ctx, EngineLoader.Engine.KENJI)?.contains("не скачано") == true)

    // A truncated file must be rejected, not loaded.
    val f = EngineLoader.coreFile(ctx, EngineLoader.Engine.KENJI)
    f.parentFile.mkdirs()
    f.writeBytes(ByteArray(500))
    val s1 = EngineLoader.state(ctx, EngineLoader.Engine.KENJI)
    check("обрезанный файл = Broken", s1 is EngineLoader.State.Broken,
          (s1 as? EngineLoader.State.Broken)?.reason ?: "$s1")

    // Right size, wrong content: the hash must catch it.
    f.setWritable(true, true)
    f.writeBytes(ByteArray(2_000_000) { 7 })
    val s2 = EngineLoader.state(ctx, EngineLoader.Engine.KENJI)
    check("подменённое содержимое ловится хешем",
          s2 is EngineLoader.State.Broken && (s2 as EngineLoader.State.Broken).reason.contains("сумма"),
          (s2 as? EngineLoader.State.Broken)?.reason ?: "$s2")

    // The real core, downloaded for this test, must verify.
    val real = File("/tmp/dl.so")
    if (real.exists()) {
        f.setWritable(true, true)
        real.copyTo(f, overwrite = true)
        val s3 = EngineLoader.state(ctx, EngineLoader.Engine.KENJI)
        check("настоящее ядро проходит проверку", s3 is EngineLoader.State.Ready,
              (s3 as? EngineLoader.State.Broken)?.reason ?: "$s3")
        check("markReadOnly ставит только чтение", EngineLoader.markReadOnly(f) && !f.canWrite())
        check("удаление снимает защиту и удаляет", EngineLoader.remove(ctx, EngineLoader.Engine.KENJI))
    } else println("(пропуск: /tmp/dl.so нет)")

    check("URL ядра указывает на релиз этого репо",
          EngineDownloader.urlFor(EngineLoader.Engine.KENJI).startsWith(
            "https://github.com/sj0404-collab/eden-symbiosis/releases/download/engine-kenji/"))

    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
