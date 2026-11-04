package com.sosim.videocatcher

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class VideoCatcherApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val progress = NotificationChannel(
            CHANNEL_PROGRESS,
            getString(R.string.channel_progress),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_progress_description)
            setShowBadge(false)
        }
        val done = NotificationChannel(
            CHANNEL_DONE,
            getString(R.string.channel_done),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.channel_done_description)
        }
        manager.createNotificationChannels(listOf(progress, done))
    }

    companion object {
        const val CHANNEL_PROGRESS = "download_progress"
        const val CHANNEL_DONE = "download_done"
    }
}
