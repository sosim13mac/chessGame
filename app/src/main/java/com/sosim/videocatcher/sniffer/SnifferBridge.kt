package com.sosim.videocatcher.sniffer

import android.webkit.JavascriptInterface
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Exposed to the detection script as `window.VideoCatcherBridge`.
 *
 * WebView calls [postMessage] on its JavaBridge thread, so [Listener]
 * implementations must move to the main thread themselves.
 */
class SnifferBridge(private val listener: Listener) {

    interface Listener {
        fun onMediaReport(report: MediaReport)
        fun onNetworkReport(report: NetworkReport)
    }

    @JavascriptInterface
    fun postMessage(message: String?) {
        if (message.isNullOrEmpty() || message.length > MAX_MESSAGE_LENGTH) return
        val json = try {
            JSONObject(message)
        } catch (e: JSONException) {
            return
        }
        when (json.optString("type")) {
            "media" -> listener.onMediaReport(parseMediaReport(json))
            "network" -> parseNetworkReport(json)?.let { listener.onNetworkReport(it) }
        }
    }

    companion object {
        /** Name of the JavaScript object; must match `window.VideoCatcherBridge` in video_sniffer.js. */
        const val NAME = "VideoCatcherBridge"
        private const val MAX_MESSAGE_LENGTH = 64 * 1024

        fun parseMediaReport(json: JSONObject): MediaReport = MediaReport(
            event = if (json.optString("event") == MediaReport.EVENT_PLAY) MediaReport.EVENT_PLAY else MediaReport.EVENT_META,
            url = json.optString("url"),
            isBlob = json.optBoolean("blob"),
            sources = parseSources(json.optJSONArray("sources")),
            width = json.optInt("width").coerceAtLeast(0),
            height = json.optInt("height").coerceAtLeast(0),
            durationSec = json.optDouble("duration", 0.0).let { if (it.isNaN()) 0.0 else it },
            posterUrl = json.optString("poster"),
            drm = json.optBoolean("drm"),
            manifests = parseStrings(json.optJSONArray("manifests")),
            frameUrl = json.optString("frameUrl"),
            isTopFrame = json.optBoolean("isTop"),
            title = json.optString("title").take(300),
        )

        fun parseNetworkReport(json: JSONObject): NetworkReport? {
            val url = json.optString("url")
            if (!MediaUrlClassifier.isHttpUrl(url)) return null
            return NetworkReport(
                url = url,
                mime = json.optString("mime"),
                frameUrl = json.optString("frameUrl"),
                title = json.optString("title").take(300),
            )
        }

        private fun parseSources(array: JSONArray?): List<MediaSourceInfo> {
            if (array == null) return emptyList()
            val list = ArrayList<MediaSourceInfo>()
            for (i in 0 until minOf(array.length(), 10)) {
                val item = array.optJSONObject(i) ?: continue
                val url = item.optString("url")
                if (url.isNotEmpty()) list += MediaSourceInfo(url, item.optString("mime"))
            }
            return list
        }

        private fun parseStrings(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            val list = ArrayList<String>()
            for (i in 0 until minOf(array.length(), 10)) {
                val value = array.optString(i)
                if (MediaUrlClassifier.isHttpUrl(value)) list += value
            }
            return list
        }
    }
}
