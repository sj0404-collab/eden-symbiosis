package android.widget
open class LinearLayout(c: android.content.Context) : android.view.ViewGroup() {
  var orientation: Int = 0
  class LayoutParams : android.view.ViewGroup.LayoutParams {
    constructor(w: Int, h: Int) : super(w, h)
    constructor(w: Int, h: Int, weight: Float) : super(w, h) }
  companion object { const val VERTICAL = 1 } }
open class ProgressBar(c: android.content.Context, a: Any?, style: Int) : android.view.View() {
  var max: Int = 0; var progress: Int = 0 }
open class TextView(c: android.content.Context) : android.view.View() {
  var text: CharSequence = ""; var textSize: Float = 0f
  var error: CharSequence? = null
  fun setTextColor(c: Int) {} }
open class Button(c: android.content.Context) : TextView(c) {
  fun setOnClickListener(l: () -> Unit) {} }
object Toast { const val LENGTH_SHORT = 0; const val LENGTH_LONG = 1
  fun makeText(c: android.content.Context, t: CharSequence, d: Int): Toast2 = Toast2() }
class Toast2 { fun show() {} }
