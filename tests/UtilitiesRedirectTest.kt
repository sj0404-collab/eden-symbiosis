// Reproduces the user's actual sequence: pick a shared folder from the
// Utilities screen, restart the app, and see whether the game list survives.
//
// v18 only guarded the setup wizard's redirectNow(). The Utilities path never
// called it - it just set configuredPath and asked for a restart - so the list
// was still lost on the next cold start. This models all three paths.

data class GameDir(val uriString: String, val deepScan: Boolean = false)

/** config.ini is per data root; SharedPreferences belong to the app. */
class Device {
    val configPerRoot = mutableMapOf<String, MutableList<GameDir>>()
    var prefsGameDirs: String? = null
    var prefsRoot: String? = null

    var activeRoot = "/private"
    var liveDirs = mutableListOf<GameDir>()

    fun loadConfigForActiveRoot() {
        liveDirs = (configPerRoot[activeRoot] ?: mutableListOf()).toMutableList()
    }
    fun saveConfig() { configPerRoot[activeRoot] = liveDirs.toMutableList() }

    fun remember() {
        if (liveDirs.isEmpty()) return
        prefsGameDirs = liveDirs.joinToString("\n") { "${it.uriString}\t${it.deepScan}" }
    }
    fun restore(): Int {
        val enc = prefsGameDirs ?: return 0
        val remembered = enc.split('\n').mapNotNull {
            val p = it.split('\t')
            p.getOrNull(0)?.takeIf { u -> u.isNotBlank() }?.let { u -> GameDir(u, p.getOrNull(1)?.toBoolean() ?: false) }
        }
        val known = liveDirs.map { it.uriString }.toMutableSet()
        var n = 0
        for (d in remembered) if (known.add(d.uriString)) { liveDirs.add(d); n++ }
        if (n > 0) saveConfig()
        return n
    }
}

/** v18: Utilities only set the path, then asked for a restart. */
fun utilitiesPickOld(d: Device, newRoot: String) { d.prefsRoot = newRoot }
/** fixed: snapshot before the root changes. */
fun utilitiesPickNew(d: Device, newRoot: String) { d.remember(); d.prefsRoot = newRoot }

/** Cold start, v18: no restore step. */
fun coldStartOld(d: Device) {
    d.activeRoot = d.prefsRoot ?: "/private"
    d.loadConfigForActiveRoot()
}
/** Cold start, fixed. */
fun coldStartNew(d: Device) {
    d.activeRoot = d.prefsRoot ?: "/private"
    d.loadConfigForActiveRoot()
    d.restore()
    d.remember()
}

var failures = 0
fun check(c: Boolean, w: String) { println(if (c) "  ok    $w" else "  FAIL  $w".also { failures++ }) }

fun main() {
    println("Choosing a shared folder from Utilities, then restarting\n")

    println("v18 behaviour (what the user hit):")
    run {
        val d = Device()
        d.liveDirs = mutableListOf(GameDir("content://Download/ed")); d.saveConfig()
        utilitiesPickOld(d, "/storage/emulated/0/Eden/files")
        coldStartOld(d)
        check(d.liveDirs.isEmpty(), "game list empty after restart (the reported bug)")
    }

    println("\nfixed - shared folder has no config of its own:")
    run {
        val d = Device()
        d.liveDirs = mutableListOf(GameDir("content://Download/ed")); d.saveConfig()
        utilitiesPickNew(d, "/storage/emulated/0/Eden/files")
        coldStartNew(d)
        check(d.liveDirs.size == 1, "the folder came back")
        check(d.liveDirs[0].uriString == "content://Download/ed", "and it is the right one")
    }

    println("\nfixed - survives a SECOND restart:")
    run {
        val d = Device()
        d.liveDirs = mutableListOf(GameDir("content://Download/ed")); d.saveConfig()
        utilitiesPickNew(d, "/shared"); coldStartNew(d); coldStartNew(d)
        check(d.liveDirs.size == 1, "still there, not duplicated")
    }

    println("\nfixed - shared folder brings its own folders:")
    run {
        val d = Device()
        d.configPerRoot["/shared"] = mutableListOf(GameDir("content://shared/games"))
        d.liveDirs = mutableListOf(GameDir("content://private/games")); d.saveConfig()
        utilitiesPickNew(d, "/shared"); coldStartNew(d)
        check(d.liveDirs.size == 2, "merged, not replaced")
    }

    println("\nfixed - switching back to private storage:")
    run {
        val d = Device()
        d.liveDirs = mutableListOf(GameDir("content://Download/ed")); d.saveConfig()
        utilitiesPickNew(d, "/shared"); coldStartNew(d)
        d.remember(); d.prefsRoot = null; coldStartNew(d)
        check(d.liveDirs.size == 1, "folder still listed on private storage")
    }

    println("\nfixed - a genuinely empty install stays empty:")
    run {
        val d = Device()
        utilitiesPickNew(d, "/shared"); coldStartNew(d)
        check(d.liveDirs.isEmpty(), "nothing invented")
    }

    println("\n${if (failures == 0) "ALL TESTS PASSED" else "THERE ARE FAILURES"}")
    if (failures != 0) kotlin.system.exitProcess(1)
}
