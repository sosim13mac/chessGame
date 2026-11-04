package com.sosim.videocatcher.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.webkit.CookieManager
import com.sosim.videocatcher.net.RequestHeaders

/** Downloads complete files (mp4, webm, ...) with Android's DownloadManager. */
object DirectDownloader {

    /** Enqueues the download into Download/VideoCatcher. Returns the DownloadManager id. */
    fun enqueue(
        context: Context,
        url: String,
        fileName: String,
        mimeType: String?,
        headers: Map<String, String>,
    ): Long {
        val uri = Uri.parse(url)
        val request = DownloadManager.Request(uri)
            .setTitle(fileName)
            .setDescription(uri.host ?: url)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "${PublicStorage.FOLDER_NAME}/$fileName")
        if (!mimeType.isNullOrBlank()) request.setMimeType(mimeType)
        for ((name, value) in headers) {
            if (RequestHeaders.isValidHeader(name, value)) request.addRequestHeader(name, value)
        }
        val cookie = CookieManager.getInstance().getCookie(url)
        if (!cookie.isNullOrBlank()) request.addRequestHeader("Cookie", cookie)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            @Suppress("DEPRECATION")
            request.allowScanningByMediaScanner()
        }
        val manager = context.getSystemService(DownloadManager::class.java)
            ?: throw IllegalStateException("DownloadManager is not available")
        return manager.enqueue(request)
    }
}
