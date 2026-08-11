package androidx.activity
abstract class OnBackPressedCallback(enabled: Boolean) {
  var isEnabled: Boolean = enabled
  abstract fun handleOnBackPressed() }
class OnBackPressedDispatcher {
  fun addCallback(owner: Any, cb: OnBackPressedCallback) {}
  fun onBackPressed() {} }
open class ComponentActivity : android.content.Context() {
  val onBackPressedDispatcher = OnBackPressedDispatcher()
  val intent: android.content.Intent? = android.content.Intent()
  open fun onCreate(savedInstanceState: android.os.Bundle?) {}
  open fun onSaveInstanceState(outState: android.os.Bundle) {}
  open fun onDestroy() {}
  fun setContentView(v: android.view.View) {}
  fun startActivity(i: android.content.Intent) {} }
