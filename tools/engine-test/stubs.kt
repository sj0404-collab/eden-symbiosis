package android.content
class SharedPreferences {
    private val map = HashMap<String, String?>()
    fun getString(k: String, d: String?): String? = map[k] ?: d
    fun edit(): Editor = Editor(map)
    class Editor(private val map: HashMap<String, String?>) {
        fun putString(k: String, v: String?): Editor { map[k] = v; return this }
        fun apply() {}
    }
}
class Context {
    val filesDir: java.io.File get() = java.io.File("/tmp")
    companion object { const val MODE_PRIVATE = 0 }
    private val stores = HashMap<String, SharedPreferences>()
    fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        stores.getOrPut(name) { SharedPreferences() }
}
