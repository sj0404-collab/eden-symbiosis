package android.app
class DownloadManager { class Request(uri: android.net.Uri) {
    fun setNotificationVisibility(v: Int) {}
    fun setMimeType(m: String?) {}
    companion object { const val VISIBILITY_VISIBLE_NOTIFY_COMPLETED = 1 } }
  fun enqueue(r: Request): Long = 0 }
