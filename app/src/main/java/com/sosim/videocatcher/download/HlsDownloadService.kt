package com.sosim.videocatcher.download

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import com.sosim.videocatcher.MainActivity
import com.sosim.videocatcher.R
import com.sosim.videocatcher.VideoCatcherApp
import com.sosim.videocatcher.net.HttpFetcher
import com.sosim.videocatcher.util.Formatters
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Parameters of one HLS download. */
data class HlsDownloadRequest(
    val playlistUrl: String,
    val audioPlaylistUrl: String?,
    val fileBaseName: String,
    val headers: Map<String, String>,
)

/**
 * Foreground service that downloads HLS streams one at a time:
 * segments -> one file -> MP4 (remux, no re-encoding) -> Download/VideoCatcher.
 */
class HlsDownloadService : Service() {

    private class Task(val id: Int, val request: HlsDownloadRequest) {
        @Volatile var cancelled = false
        @Volatile var finished = false
        @Volatile var status = ""
        @Volatile var progress = 0
        @Volatile var indeterminate = true
        @Volatile var lastNotifiedAt = 0L
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tasks = ConcurrentHashMap<Int, Task>()
    @Volatile private var currentTask: Task? = null
    private lateinit var notifications: NotificationManager
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        // Leftovers of downloads interrupted by a process kill.
        executor.execute { tempRoot().deleteRecursively() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> {
                val request = requestFrom(intent)
                val task = request?.let { Task(nextTaskId.incrementAndGet(), it) }
                if (task != null) {
                    task.status = getString(R.string.hls_status_waiting)
                    tasks[task.id] = task
                }
                // startForegroundService() must be followed by startForeground(), even for bad input.
                goForeground(progressNotification(currentTask ?: task))
                if (task == null) stopIfIdle() else executor.execute { run(task) }
            }
            ACTION_CANCEL -> {
                val task = tasks[intent.getIntExtra(EXTRA_TASK_ID, -1)]
                if (task != null) task.cancelled = true else stopIfIdle()
            }
            else -> stopIfIdle()
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits how long a dataSync foreground service may run.
        for (task in tasks.values) task.cancelled = true
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        for (task in tasks.values) task.cancelled = true
        executor.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun run(task: Task) {
        if (task.cancelled) {
            finish(task, null)
            return
        }
        currentTask = task
        val workDir = File(tempRoot(), task.id.toString())
        var result: Notification? = null
        try {
            workDir.deleteRecursively()
            workDir.mkdirs()
            setStatus(task, getString(R.string.hls_status_analyzing))

            val fetcher = HttpFetcher(task.request.headers, cookieProvider = ::cookieFor)
            val downloader = HlsDownloader(fetcher, workDir, PARALLEL_SEGMENTS) { task.cancelled }
            val stream = downloader.resolve(task.request.playlistUrl, task.request.audioPlaylistUrl)

            val total = stream.video.segments.count { !it.isGap } + (stream.audio?.segments?.count { !it.isGap } ?: 0)
            val done = AtomicInteger()
            val bytes = AtomicLong()
            val onSegment: (Long) -> Unit = { size ->
                reportProgress(task, done.incrementAndGet(), total, bytes.addAndGet(size))
            }
            val video = downloader.download(stream.video, "video", onSegment)
            val audio = stream.audio?.let { downloader.download(it, "audio", onSegment) }

            setStatus(task, getString(R.string.hls_status_converting))
            val inputs = listOfNotNull(video, audio)
            val remuxed = File(workDir, "output.mp4")
            val converted = try {
                MediaRemuxer.remuxToMp4(inputs, remuxed) { task.cancelled }
                remuxed.length() > inputs.sumOf { it.length() } * MIN_REMUX_RATIO
            } catch (e: Exception) {
                Log.w(TAG, "Remux to MP4 failed; keeping the original stream", e)
                false
            }
            if (task.cancelled) throw HlsDownloadException(HlsDownloadException.Reason.CANCELLED)

            setStatus(task, getString(R.string.hls_status_saving))
            val baseName = task.request.fileBaseName
            val savedName: String
            val savedUri: Uri?
            val savedMime: String
            if (converted) {
                video.delete()
                audio?.delete()
                savedName = "$baseName.mp4"
                savedMime = "video/mp4"
                savedUri = PublicStorage.saveToDownloads(this, remuxed, savedName, savedMime)
            } else {
                remuxed.delete()
                savedName = "$baseName.${video.extension}"
                savedMime = mimeFor(video.extension)
                savedUri = PublicStorage.saveToDownloads(this, video, savedName, savedMime)
                if (audio != null) {
                    PublicStorage.saveToDownloads(this, audio, "${baseName}_audio.${audio.extension}", mimeFor(audio.extension))
                }
            }
            result = doneNotification(task, savedName, savedUri, savedMime, bytes.get())
        } catch (e: HlsDownloadException) {
            if (e.reason != HlsDownloadException.Reason.CANCELLED && !task.cancelled) {
                Log.w(TAG, "HLS download failed: ${e.reason}", e)
                result = errorNotification(task, messageFor(e))
            }
        } catch (e: Exception) {
            if (!task.cancelled) {
                Log.e(TAG, "HLS download failed", e)
                result = errorNotification(task, e.message ?: e.javaClass.simpleName)
            }
        } finally {
            workDir.deleteRecursively()
            finish(task, result)
        }
    }

    private fun finish(task: Task, result: Notification?) {
        synchronized(task) { task.finished = true }
        if (currentTask === task) currentTask = null
        mainHandler.post {
            tasks.remove(task.id)
            if (result != null) notifications.notify(RESULT_NOTIFICATION_BASE + task.id, result)
            stopIfIdle()
        }
    }

    private fun stopIfIdle() {
        if (tasks.isNotEmpty()) return
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(lastStartId)
    }

    private fun goForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(FOREGROUND_NOTIFICATION_ID, notification)
        }
    }

    private fun setStatus(task: Task, status: String) {
        task.status = status
        task.indeterminate = true
        postProgress(task)
    }

    private fun reportProgress(task: Task, done: Int, total: Int, bytes: Long) {
        val now = SystemClock.elapsedRealtime()
        if (done < total && now - task.lastNotifiedAt < PROGRESS_INTERVAL_MS) return
        task.lastNotifiedAt = now
        task.status = getString(R.string.hls_status_downloading, done, total, Formatters.bytes(bytes))
        task.progress = if (total > 0) done * 100 / total else 0
        task.indeterminate = false
        postProgress(task)
    }

    private fun postProgress(task: Task) {
        synchronized(task) {
            if (task.finished) return
            notifications.notify(FOREGROUND_NOTIFICATION_ID, progressNotification(task))
        }
    }

    private fun progressNotification(task: Task?): Notification {
        val builder = Notification.Builder(this, VideoCatcherApp.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(task?.request?.fileBaseName ?: getString(R.string.app_name))
            .setContentText(task?.status ?: getString(R.string.hls_status_waiting))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent())
        if (task != null) {
            builder.setProgress(100, task.progress, task.indeterminate)
            builder.addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.cancel),
                    cancelIntent(task.id),
                ).build()
            )
        }
        val waiting = tasks.size - 1
        if (waiting > 0) builder.setSubText(getString(R.string.hls_queue, waiting))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    private fun doneNotification(task: Task, fileName: String, uri: Uri?, mimeType: String, bytes: Long): Notification {
        val open = if (uri != null) {
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mimeType)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        } else {
            Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return Notification.Builder(this, VideoCatcherApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.hls_done_title))
            .setContentText(fileName)
            .setSubText(Formatters.bytes(bytes))
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(this, task.id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .build()
    }

    private fun errorNotification(task: Task, message: String): Notification =
        Notification.Builder(this, VideoCatcherApp.CHANNEL_DONE)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(getString(R.string.hls_failed_title, task.request.fileBaseName))
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()

    private fun messageFor(e: HlsDownloadException): String = when (e.reason) {
        HlsDownloadException.Reason.ENCRYPTED -> getString(R.string.error_encrypted)
        HlsDownloadException.Reason.LIVE -> getString(R.string.error_live)
        HlsDownloadException.Reason.EMPTY -> getString(R.string.error_empty_playlist)
        HlsDownloadException.Reason.NOT_HLS -> getString(R.string.error_not_playlist)
        HlsDownloadException.Reason.NETWORK -> getString(R.string.error_network, e.message ?: "")
        HlsDownloadException.Reason.CANCELLED -> getString(R.string.cancel)
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun cancelIntent(taskId: Int): PendingIntent = PendingIntent.getService(
        this,
        taskId,
        Intent(this, HlsDownloadService::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_TASK_ID, taskId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun tempRoot() = File(noBackupFilesDir, TEMP_DIR)

    private fun cookieFor(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val TAG = "HlsDownloadService"
        private const val ACTION_START = "com.sosim.videocatcher.action.START_HLS_DOWNLOAD"
        private const val ACTION_CANCEL = "com.sosim.videocatcher.action.CANCEL_HLS_DOWNLOAD"
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUDIO_URL = "audio_url"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_HEADERS = "headers"
        private const val EXTRA_TASK_ID = "task_id"
        private const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val RESULT_NOTIFICATION_BASE = 2000
        private const val PARALLEL_SEGMENTS = 4
        private const val PROGRESS_INTERVAL_MS = 700L
        /** A remuxed MP4 much smaller than its input means tracks were lost. */
        private const val MIN_REMUX_RATIO = 0.6
        private const val TEMP_DIR = "hls-downloads"

        private val nextTaskId = AtomicInteger((System.currentTimeMillis() / 1000 % 100_000).toInt())

        fun start(context: Context, request: HlsDownloadRequest) {
            val headers = Bundle()
            for ((name, value) in request.headers) headers.putString(name, value)
            val intent = Intent(context, HlsDownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_URL, request.playlistUrl)
                .putExtra(EXTRA_AUDIO_URL, request.audioPlaylistUrl)
                .putExtra(EXTRA_NAME, request.fileBaseName)
                .putExtra(EXTRA_HEADERS, headers)
            context.startForegroundService(intent)
        }

        private fun requestFrom(intent: Intent): HlsDownloadRequest? {
            val url = intent.getStringExtra(EXTRA_URL) ?: return null
            val headers = LinkedHashMap<String, String>()
            val bundle = intent.getBundleExtra(EXTRA_HEADERS)
            if (bundle != null) {
                for (key in bundle.keySet()) bundle.getString(key)?.let { headers[key] = it }
            }
            return HlsDownloadRequest(
                playlistUrl = url,
                audioPlaylistUrl = intent.getStringExtra(EXTRA_AUDIO_URL),
                fileBaseName = intent.getStringExtra(EXTRA_NAME) ?: "video",
                headers = headers,
            )
        }

        private fun mimeFor(extension: String): String = when (extension.lowercase()) {
            "mp4" -> "video/mp4"
            "ts" -> "video/mp2t"
            "aac" -> "audio/aac"
            else -> "application/octet-stream"
        }
    }
}
