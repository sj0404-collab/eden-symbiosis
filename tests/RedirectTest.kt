// Models the data-root redirect so the "my games vanished" bug can be shown
// and then shown fixed, without a device.
//
// The mechanism, from the Eden source:
//   NativeConfig.reloadGlobalConfig()
//     -> AndroidConfig::ReloadAllValues()      android_config.cpp:21
//     -> ReadAndroidValues() -> ReadPathValues()
//     -> AndroidSettings::values.game_dirs.clear()   android_config.cpp:70
//   then repopulates game_dirs from the config.ini found under the NEW root.

data class GameDir(val uriString: String, val deepScan: Boolean = false)

/** Config store, keyed by data root, exactly as config.ini is per-root. */
class Config {
    private val perRoot = mutableMapOf<String, MutableList<GameDir>>()
    var root: String = "/private"
    var gameDirs: MutableList<GameDir> = mutableListOf()

    fun seed(root: String, dirs: List<GameDir>) { perRoot[root] = dirs.toMutableList() }

    /** reloadGlobalConfig(): clears, then reads whatever the new root holds. */
    fun reload() {
        gameDirs = (perRoot[root] ?: mutableListOf()).toMutableList()
    }
    fun save() { perRoot[root] = gameDirs.toMutableList() }
}

/** The v17 implementation: redirect, then reload. */
fun redirectOld(cfg: Config, newRoot: String) {
    cfg.root = newRoot
    cfg.reload()
}

/** The fix: carry over folders the new root does not already list. */
fun redirectNew(cfg: Config, newRoot: String) {
    val previous = cfg.gameDirs.toList()
    cfg.root = newRoot
    cfg.reload()
    if (previous.isNotEmpty()) {
        val merged = cfg.gameDirs.toMutableList()
        val known = merged.map { it.uriString }.toMutableSet()
        for (d in previous) if (known.add(d.uriString)) merged.add(d)
        if (merged.size != cfg.gameDirs.size) { cfg.gameDirs = merged; cfg.save() }
    }
}

var failures = 0
fun check(c: Boolean, what: String) {
    println(if (c) "  ok    $what" else "  FAIL  $what".also { failures++ })
}

fun main() {
    println("Data-root redirect must not lose the game list\n")

    println("v17 behaviour (the reported bug):")
    run {
        val cfg = Config()
        cfg.gameDirs = mutableListOf(GameDir("content://.../Download/ed"))
        cfg.save()
        redirectOld(cfg, "/storage/emulated/0/Eden/files")   // fresh shared folder
        check(cfg.gameDirs.isEmpty(), "game folders are gone after redirect (this is the bug)")
    }

    println("\nfixed - shared folder has no config of its own:")
    run {
        val cfg = Config()
        cfg.gameDirs = mutableListOf(GameDir("content://.../Download/ed"))
        cfg.save()
        redirectNew(cfg, "/storage/emulated/0/Eden/files")
        check(cfg.gameDirs.size == 1, "the folder was carried over")
        check(cfg.gameDirs[0].uriString.endsWith("Download/ed"), "and it is the right one")
    }

    println("\nfixed - shared folder lists its own folders:")
    run {
        val cfg = Config()
        cfg.seed("/shared", listOf(GameDir("content://shared/games")))
        cfg.gameDirs = mutableListOf(GameDir("content://private/games"))
        cfg.save()
        redirectNew(cfg, "/shared")
        check(cfg.gameDirs.size == 2, "both folders present - merged, not replaced")
    }

    println("\nfixed - no duplicates when both roots list the same folder:")
    run {
        val cfg = Config()
        cfg.seed("/shared", listOf(GameDir("content://same/games")))
        cfg.gameDirs = mutableListOf(GameDir("content://same/games"))
        cfg.save()
        redirectNew(cfg, "/shared")
        check(cfg.gameDirs.size == 1, "listed once, not twice")
    }

    println("\nfixed - redirect with nothing configured stays empty:")
    run {
        val cfg = Config()
        redirectNew(cfg, "/shared")
        check(cfg.gameDirs.isEmpty(), "nothing invented out of thin air")
    }

    println("\n${if (failures == 0) "ALL TESTS PASSED" else "THERE ARE FAILURES"}")
    if (failures != 0) kotlin.system.exitProcess(1)
}
