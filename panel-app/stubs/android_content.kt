package android.content
open class Context {
  fun getSystemService(name: String): Any? = null
  companion object { const val DOWNLOAD_SERVICE = "download" }
  val resources: Any? = null }
class Intent { constructor(); constructor(action: String); constructor(action: String, uri: android.net.Uri)
  fun getStringExtra(name: String): String? = null
  companion object { const val ACTION_VIEW = "view" } }
