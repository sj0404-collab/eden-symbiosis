package android.webkit
open class WebSettings { var javaScriptEnabled = false; var domStorageEnabled = false
  var useWideViewPort = false; var loadWithOverviewMode = false; var cacheMode = 0
  var mediaPlaybackRequiresUserGesture = false; var allowFileAccess = false
  var allowContentAccess = false; var javaScriptCanOpenWindowsAutomatically = false
  var userAgentString: String = ""
  fun setSupportMultipleWindows(b: Boolean) {}
  companion object { const val LOAD_DEFAULT = -1 } }
open class WebResourceRequest { val url: android.net.Uri = android.net.Uri()
  val isForMainFrame: Boolean = true }
open class WebResourceError
open class WebResourceResponse(
  mime: String?, encoding: String?, statusCode: Int, reasonPhrase: String,
  headers: Map<String, String>?, data: java.io.InputStream?)
open class WebViewClient {
  open fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? = null
  open fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false
  open fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {}
  open fun onPageFinished(view: WebView, url: String) {} }
open class WebChromeClient { open fun onProgressChanged(view: WebView?, p: Int) {} }
open class WebView(c: android.content.Context) : android.view.ViewGroup() {
  val settings = WebSettings()
  var webViewClient: WebViewClient = WebViewClient()
  var webChromeClient: WebChromeClient = WebChromeClient()
  fun loadUrl(u: String) {}
  fun canGoBack(): Boolean = false
  fun goBack() {}
  fun saveState(b: android.os.Bundle) {}
  fun restoreState(b: android.os.Bundle) {}
  fun destroy() {}
  fun setDownloadListener(l: (String, String, String, String, Long) -> Unit) {} }
object CookieManager { fun getInstance(): CookieManager2 = CookieManager2() }
class CookieManager2 { fun setAcceptCookie(b: Boolean) {}
  fun setAcceptThirdPartyCookies(w: WebView, b: Boolean) {} }
