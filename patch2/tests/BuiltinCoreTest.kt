// The case that shipped broken: a core that IS inside the APK.
//
// WHAT WENT WRONG
//   CoreFromFolder.load() began with
//
//       if (builtIn(context)) return null
//
//   Returning null means "no problem" to the caller, so NativeLibrary's
//   init skipped its own System.loadLibrary as well. Nothing anywhere
//   actually loaded the core. The first external fun threw
//   UnsatisfiedLinkError out of a static initialiser and the process died
//   before it drew a frame: black screen, gone in a second - on the full
//   APK, where the core had been present all along.
//
// WHY THE EXISTING TEST MISSED IT
//   CoreFromFolderTest only ever ran with nativeLibraryDir pointing at a
//   path that does not exist, so builtIn() was false every time and this
//   branch was never entered. The one configuration real users install was
//   the one configuration never exercised.
//
//   This file forces builtIn() true and asserts a load was ATTEMPTED. On a
//   JVM System.loadLibrary cannot succeed, so the check is that the code
//   reaches the loader and reports the failure honestly - never that it
//   quietly claims success.

import org.yuzu.yuzu_emu.utils.CoreFromFolder as C
import android.content.Context
import java.io.File

fun main() {
    var bad = 0
    fun check(name: String, ok: Boolean, extra: String = "") {
        println((if (ok) "ok   " else "FAIL ") + name + (if (extra.isNotEmpty()) "  [$extra]" else ""))
        if (!ok) bad++
    }

    File("/tmp/cft").deleteRecursively()

    // Make the "core is inside the APK" case real: nativeLibraryDir exists
    // and holds a file with the right name.
    val libDir = File("/tmp/cft/nolib").apply { mkdirs() }
    File(libDir, C.CORE_NAME).writeBytes(ByteArray(1024))

    val ctx = Context()

    check("builtIn видит ядро в приложении", C.builtIn(ctx))
    check("состояние = Builtin", C.locate(ctx) is C.State.Builtin)

    // The heart of it. Before the fix this returned null - "everything is
    // fine" - having loaded nothing, and the caller then skipped loading too.
    val result = C.load(ctx)

    check(
        "load() не рапортует об успехе, не загрузив ядро",
        result != null,
        result ?: "вернул null — это и был баг"
    )
    check(
        "сообщение объясняет, что дело во встроенном ядре",
        result != null && result.contains("внутри приложения"),
        result ?: "null"
    )

    // A folder must not be required when the core is already in the APK:
    // the full APK has to work with no EdenCore anywhere.
    check(
        "папка не требуется",
        result != null && !result.contains("папка"),
        result ?: "null"
    )

    println(if (bad == 0) "ВСЁ ПРОШЛО" else "ПРОВАЛОВ: $bad")
    if (bad > 0) kotlin.system.exitProcess(1)
}
