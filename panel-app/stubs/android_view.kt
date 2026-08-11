package android.view
open class View { var visibility: Int = 0
  open fun setBackgroundColor(c: Int) {}
  open fun setPadding(l: Int, t: Int, r: Int, b: Int) {}
  var layoutParams: Any? = null
  val parent: Any? = null
  val context: android.content.Context = android.content.Context()
  companion object { const val GONE = 8; const val VISIBLE = 0 } }
open class ViewGroup : View() { open fun addView(v: View) {}
  open fun removeView(v: View) {}
  open class LayoutParams(w: Int, h: Int) {
    companion object { const val MATCH_PARENT = -1; const val WRAP_CONTENT = -2 } } }
