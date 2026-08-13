// See stubs_android.kt. Environment.getExternalStorageDirectory() is the
// second place CoreFromFolder looks for a core folder, so it has to exist for
// the search-order test to mean anything.

package android.os

object Environment {
    @JvmStatic
    fun getExternalStorageDirectory(): java.io.File = java.io.File("/tmp/cft/sd")
}
