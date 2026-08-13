// Just enough Android to run CoreFromFolder on a plain JVM.
//
// WHY A STUB AND NOT AN INSTRUMENTED TEST
//   CoreFromFolder decides whether the emulator has a core at all, and every
//   way it can fail is silent: a truncated file that dlopen maps and then dies
//   inside, a copy left writable that Android 14+ refuses to load, a stale
//   copy that is never refreshed. None of that shows up in a green build.
//
//   An instrumented test would need a device. These four members are all the
//   Android surface CoreFromFolder actually touches, so stubbing them lets the
//   real logic run in seconds on the runner.
//
// The paths point at /tmp/cft so a test run cannot touch anything else.

package android.content

class ApplicationInfo {
    /** Deliberately a path that does not exist: the "no core in the APK" case. */
    val nativeLibraryDir: String = "/tmp/cft/nolib"
}

class Context {
    val filesDir: java.io.File get() = java.io.File("/tmp/cft/app")
    val applicationInfo = ApplicationInfo()

    /** One volume, shaped like the real thing: <root>/Android/data/<pkg>/files. */
    fun getExternalFilesDirs(type: String?): Array<java.io.File?> =
        arrayOf(java.io.File("/tmp/cft/sd/Android/data/dev.eden.eden_emulator/files"))
}
