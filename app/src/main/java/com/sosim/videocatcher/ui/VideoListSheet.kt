package com.sosim.videocatcher.ui

import android.app.Activity
import android.app.Dialog
import android.os.Build
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.ImageButton
import android.widget.ListView
import android.widget.TextView
import com.sosim.videocatcher.R
import com.sosim.videocatcher.sniffer.DetectedVideo

/** Bottom sheet listing the videos detected on the current page. */
class VideoListSheet(private val activity: Activity, private val callbacks: Callbacks) {

    interface Callbacks {
        fun onVideoSelected(video: DetectedVideo)
        fun onVideoLongPressed(video: DetectedVideo)
        fun onClearVideos()
    }

    private val dialog = Dialog(activity, R.style.Theme_VideoCatcher_Sheet)
    private val adapter = Adapter()
    private val titleView: TextView
    private val emptyView: TextView
    private val listView: ListView

    val isShowing: Boolean get() = dialog.isShowing

    init {
        dialog.setContentView(R.layout.sheet_videos)
        dialog.setCanceledOnTouchOutside(true)
        val root = dialog.findViewById<MaxHeightLinearLayout>(R.id.sheet_root)
        titleView = dialog.findViewById(R.id.sheet_title)
        emptyView = dialog.findViewById(R.id.sheet_empty)
        listView = dialog.findViewById(R.id.sheet_list)
        listView.adapter = adapter
        listView.setOnItemClickListener { _, _, position, _ -> select(adapter.getItem(position)) }
        listView.setOnItemLongClickListener { _, _, position, _ ->
            callbacks.onVideoLongPressed(adapter.getItem(position))
            true
        }
        dialog.findViewById<View>(R.id.sheet_clear).setOnClickListener { callbacks.onClearVideos() }

        root.maxHeightPx = (activity.resources.displayMetrics.heightPixels * 0.72f).toInt()
        val basePadding = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, basePadding + navigationBarInset(insets))
            insets
        }
        dialog.window?.let { window ->
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            window.setGravity(Gravity.BOTTOM)
        }
    }

    fun show(items: List<DetectedVideo>) {
        update(items)
        if (!dialog.isShowing) dialog.show()
    }

    fun update(items: List<DetectedVideo>) {
        adapter.items = items
        adapter.notifyDataSetChanged()
        titleView.text = activity.getString(R.string.sheet_title, items.size)
        emptyView.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        listView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    fun dismiss() {
        if (dialog.isShowing) dialog.dismiss()
    }

    private fun select(video: DetectedVideo) {
        dialog.dismiss()
        callbacks.onVideoSelected(video)
    }

    private fun navigationBarInset(insets: WindowInsets): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.getInsets(WindowInsets.Type.navigationBars()).bottom
        } else {
            legacyBottomInset(insets)
        }

    @Suppress("DEPRECATION")
    private fun legacyBottomInset(insets: WindowInsets): Int = insets.systemWindowInsetBottom

    private inner class Adapter : BaseAdapter() {
        var items: List<DetectedVideo> = emptyList()

        override fun getCount(): Int = items.size

        override fun getItem(position: Int): DetectedVideo = items[position]

        override fun getItemId(position: Int): Long = items[position].id

        override fun hasStableIds(): Boolean = true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: LayoutInflater.from(parent.context).inflate(R.layout.item_video, parent, false)
            val holder = view.tag as? Holder ?: Holder(view).also { view.tag = it }
            val video = items[position]
            holder.bind(video, isPlaying = position == 0 && video.lastPlayedAt > 0)
            return view
        }
    }

    private inner class Holder(view: View) {
        private val type: TextView = view.findViewById(R.id.item_type)
        private val title: TextView = view.findViewById(R.id.item_title)
        private val meta: TextView = view.findViewById(R.id.item_meta)
        private val url: TextView = view.findViewById(R.id.item_url)
        private val download: ImageButton = view.findViewById(R.id.item_download)

        fun bind(video: DetectedVideo, isPlaying: Boolean) {
            type.text = VideoLabels.typeLabel(video)
            title.text = VideoLabels.title(video)
            meta.text = VideoLabels.details(activity, video, isPlaying)
            meta.setTextColor(
                activity.getColor(if (isPlaying) R.color.playing else R.color.text_secondary)
            )
            url.text = if (video.url.startsWith("mse:")) activity.getString(R.string.label_mse_url) else video.url
            val enabled = video.isDownloadable
            download.isEnabled = enabled
            download.alpha = if (enabled) 1f else 0.3f
            download.setOnClickListener { select(video) }
        }
    }
}
