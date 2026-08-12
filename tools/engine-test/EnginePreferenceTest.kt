import org.yuzu.yuzu_emu.utils.*
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

    check("по умолчанию Eden", EnginePreference.selected(ctx) == EngineLoader.Engine.EDEN)

    // Choosing an engine that is not downloaded must NOT strand the app.
    EnginePreference.select(ctx, EngineLoader.Engine.KENJI)
    check("выбор сохранён как есть", EnginePreference.selectedRaw(ctx) == EngineLoader.Engine.KENJI)
    check("но запустится Eden, раз ядра нет",
          EnginePreference.selected(ctx) == EngineLoader.Engine.EDEN)
    check("откат виден интерфейсу", EnginePreference.fellBack(ctx))

    // With the real core present the choice must hold.
    val real = File("/tmp/dl.so")
    if (real.exists()) {
        val f = EngineLoader.coreFile(ctx, EngineLoader.Engine.KENJI)
        f.parentFile.mkdirs(); real.copyTo(f, overwrite = true)
        check("с настоящим ядром выбор действует",
              EnginePreference.selected(ctx) == EngineLoader.Engine.KENJI)
        check("отката больше нет", !EnginePreference.fellBack(ctx))

        // Simulate the system reclaiming the file mid-life.
        f.setWritable(true, true); f.delete()
        check("ядро пропало -> снова Eden, без падения",
              EnginePreference.selected(ctx) == EngineLoader.Engine.EDEN)
    } else println("(пропуск: нет /tmp/dl.so)")

    // An unknown id in storage (downgrade, hand-edited prefs) must not crash.
    EnginePreference.select(ctx, EngineLoader.Engine.EDEN)
    check("возврат к Eden сохраняется", EnginePreference.selectedRaw(ctx) == EngineLoader.Engine.EDEN)

    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
