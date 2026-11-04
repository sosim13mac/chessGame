package com.sosim.videocatcher.sniffer

import android.content.Context
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/**
 * Puts the detection script (assets/video_sniffer.js) into every page.
 *
 * On current WebView versions the script is registered once with
 * [WebViewCompat.addDocumentStartJavaScript]: it then runs in every frame,
 * iframes included, before any script of the page. Very old WebViews lack
 * that feature; there the script is injected into the main frame while the
 * page loads ([injectIfNeeded]), which still catches most videos.
 */
class SnifferInjector(context: Context) {

    val script: String = context.assets.open(SCRIPT_ASSET).bufferedReader().use { it.readText() }

    var usesDocumentStartScript: Boolean = false
        private set

    fun install(webView: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, script, setOf("*"))
            usesDocumentStartScript = true
        } else {
            usesDocumentStartScript = false
        }
    }

    /** Fallback injection for WebViews without DOCUMENT_START_SCRIPT. The script ignores repeats. */
    fun injectIfNeeded(webView: WebView) {
        if (!usesDocumentStartScript) webView.evaluateJavascript(script, null)
    }

    private companion object {
        const val SCRIPT_ASSET = "video_sniffer.js"
    }
}
