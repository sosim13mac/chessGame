package com.sosim.videocatcher.download

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Saves finished files into the public Download/VideoCatcher folder. */
object PublicStorage {

    const val FOLDER_NAME = "VideoCatcher"

    /**
     * Copies [source] to Download/VideoCatcher/[displayName]. Returns a content:// URI
     * that other apps can open, or null if none is available. Call from a worker thread.
     */
    fun saveToDownloads(context: Context, source: File, displayName: String, mimeType: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, source, displayName, mimeType)
        } else {
            saveLegacy(context, source, displayName, mimeType)
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveWithMediaStore(context: Context, source: File, displayName: String, mimeType: String): Uri {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER_NAME)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create $displayName")
        try {
            val output = resolver.openOutputStream(uri) ?: throw IOException("Could not open $displayName")
            output.use { out -> FileInputStream(source).use { it.copyTo(out, COPY_BUFFER) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return uri
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }

    private fun saveLegacy(context: Context, source: File, displayName: String, mimeType: String): Uri? {
        @Suppress("DEPRECATION")
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, FOLDER_NAME)
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create $dir")
        val target = uniqueFile(dir, displayName)
        source.copyTo(target, overwrite = false, bufferSize = COPY_BUFFER)

        val latch = CountDownLatch(1)
        var scanned: Uri? = null
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType)) { _, uri ->
            scanned = uri
            latch.countDown()
        }
        latch.await(10, TimeUnit.SECONDS)
        return scanned
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        if (!file.exists()) return file
        val base = name.substringBeforeLast('.')
        val ext = name.substringAfterLast('.', "")
        var i = 1
        while (file.exists()) {
            file = File(dir, if (ext.isEmpty()) "$base ($i)" else "$base ($i).$ext")
            i++
        }
        return file
    }

    private const val COPY_BUFFER = 256 * 1024
}
