package android.webkit

import android.content.Context

/**
 * **There is no WebView in a plugin host, on purpose.** A browser engine would be a far larger
 * capability than anything else a host has. These types exist so that a plugin which *mentions*
 * WebView can load; constructing one fails, loudly and with a reason, and its Cloudflare path fails
 * with it. `WebViewResolver` is separate, and its JVM actual stays a failing `TODO()`.
 */
open class WebView(@Suppress("UNUSED_PARAMETER") context: Context?) {
    init {
        throw UnsupportedOperationException("WebView is not available in the plugin sandbox")
    }
}

open class WebViewClient
open class WebChromeClient
open class WebSettings
open class WebResourceError
interface WebResourceRequest
interface ValueCallback<T>

/** Nothing is stored, so a plugin reading a cookie back sees none, which is the truth here. */
open class CookieManager private constructor() {
    fun setAcceptCookie(accept: Boolean) = Unit
    fun setAcceptThirdPartyCookies(view: WebView?, accept: Boolean) = Unit
    fun getCookie(url: String?): String? = null
    fun setCookie(url: String?, value: String?) = Unit
    fun removeAllCookies(callback: ValueCallback<Boolean>?) = Unit
    fun removeSessionCookies(callback: ValueCallback<Boolean>?) = Unit
    fun flush() = Unit

    companion object {
        private val INSTANCE = CookieManager()
        @JvmStatic fun getInstance(): CookieManager = INSTANCE
    }
}

/** Nothing is stored here either. */
open class WebStorage private constructor() {
    fun deleteAllData() = Unit

    companion object {
        private val INSTANCE = WebStorage()
        @JvmStatic fun getInstance(): WebStorage = INSTANCE
    }
}
