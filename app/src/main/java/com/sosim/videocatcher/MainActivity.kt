package com.sosim.videocatcher

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.sosim.videocatcher.download.DirectDownloader
import com.sosim.videocatcher.download.HlsDownloadRequest
import com.sosim.videocatcher.download.HlsDownloadService
import com.sosim.videocatcher.download.MediaProber
import com.sosim.videocatcher.hls.HlsPlaylist
import com.sosim.videocatcher.hls.HlsProbe
import com.sosim.videocatcher.hls.HlsVariant
import com.sosim.videocatcher.net.RequestHeaders
import com.sosim.videocatcher.sniffer.DetectedVideo
import com.sosim.videocatcher.sniffer.MediaKind
import com.sosim.videocatcher.sniffer.MediaReport
import com.sosim.videocatcher.sniffer.MediaUrlClassifier
import com.sosim.videocatcher.sniffer.NetworkReport
import com.sosim.videocatcher.sniffer.SnifferBridge
import com.sosim.videocatcher.sniffer.SnifferInjector
import com.sosim.videocatcher.sniffer.VideoStore
import com.sosim.videocatcher.ui.BackCallbackHolder
import com.sosim.videocatcher.ui.VideoLabels
import com.sosim.videocatcher.ui.VideoListSheet
import com.sosim.videocatcher.util.FileNames
import com.sosim.videocatcher.util.Formatters
import com.sosim.videocatcher.util.UrlInput
import kotlin.math.roundToInt

/**
 * The browser: a WebView with an address bar, plus video detection.
 *
 * Detection works on two levels:
 *  1. assets/video_sniffer.js runs in every frame before the page's scripts and
 *     reports `<video>` playback and streaming requests through [SnifferBridge];
 *  2. [WebViewClient.shouldInterceptRequest] sees every request of the page,
 *     including those made by web workers, and catches media URLs there.
 * Both feed [VideoStore]; when a video starts playing a "detected" pill
 * appears and the video can be downloaded.
 */
class MainActivity : Activity(), SnifferBridge.Listener, VideoListSheet.Callbacks {

    private lateinit var webView: WebView
    private lateinit var urlInput: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var reloadButton: ImageButton
    private lateinit var backButton: ImageButton
    private lateinit var forwardButton: ImageButton
    private lateinit var videoBadge: TextView
    private lateinit var detectPill: View
    private lateinit var detectPillText: TextView
    private lateinit var detectPillAction: View

    private val mainHandler = Handler(Looper.getMainLooper())
    private val store = VideoStore()
    private val prober = MediaProber()
    private lateinit var injector: SnifferInjector
    private var videoSheet: VideoListSheet? = null
    private var backCallback: BackCallbackHolder? = null

    private var defaultUserAgent = ""
    private var desktopMode = false
    private var pageLoading = false
    private var webViewDestroyed = false
    private var injectedDuringProgress = false
    private var badgeCount = 0
    private var pillVideoId = -1L
    private var pendingPermissionAction: (() -> Unit)? = null
    private var blankPoster: Bitmap? = null
    private var guessedHost: GuessedHost? = null

    /** Address-bar text that was opened as "https://<text>" without the user typing a scheme. */
    private class GuessedHost(val host: String, val query: String, val at: Long)

    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var fullscreenContainer: FrameLayout? = null

    private val hidePillRunnable = Runnable { hidePill() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        webView = findViewById(R.id.web_view)
        urlInput = findViewById(R.id.url_input)
        progressBar = findViewById(R.id.progress)
        reloadButton = findViewById(R.id.btn_reload)
        backButton = findViewById(R.id.btn_back)
        forwardButton = findViewById(R.id.btn_forward)
        videoBadge = findViewById(R.id.video_badge)
        detectPill = findViewById(R.id.detect_pill)
        detectPillText = findViewById(R.id.detect_pill_text)
        detectPillAction = findViewById(R.id.detect_pill_action)

        setupEdgeToEdge()
        injector = SnifferInjector(this)
        store.onChanged = { onStoreChanged() }
        setupWebView()
        setupControls()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = BackCallbackHolder(this) { handleBack() }
        }

        val restored = savedInstanceState != null && webView.restoreState(savedInstanceState) != null
        if (!restored && !openFromIntent(intent)) loadHome()
        updateNavigationButtons()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openFromIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) {
            webView.onResume()
            webView.resumeTimers()
        }
    }

    override fun onPause() {
        if (!webViewDestroyed) {
            webView.onPause()
            webView.pauseTimers()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDestroyed) webView.saveState(outState)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        store.onChanged = null
        prober.shutdown()
        videoSheet?.dismiss()
        if (customView != null) hideCustomView()
        destroyWebView()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs shown over a fullscreen video bring the system bars back.
        if (hasFocus && customView != null) setSystemBarsHidden(true)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Used before Android 13; newer versions go through BackCallbackHolder.
        if (!handleBack()) super.onBackPressed()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STORAGE) {
            val action = pendingPermissionAction
            pendingPermissionAction = null
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                action?.invoke()
            } else {
                toast(R.string.error_storage_permission)
            }
        }
    }

    // ------------------------------------------------------------------
    // Setup
    // ------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
        }
        defaultUserAgent = webView.settings.userAgentString.orEmpty()
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        // The bridge must exist before the first page loads; the script looks for it at document start.
        webView.addJavascriptInterface(SnifferBridge(this), SnifferBridge.NAME)
        injector.install(webView)
        webView.webViewClient = BrowserClient()
        webView.webChromeClient = ChromeClient()
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onPageDownloadRequested(url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun setupControls() {
        urlInput.setOnEditorActionListener { _, actionId, event ->
            when {
                // The keyboard's action key (Go, Search, Done... depending on the keyboard app).
                event == null -> {
                    if (actionId != EditorInfo.IME_ACTION_NONE) submitAddress()
                    actionId != EditorInfo.IME_ACTION_NONE
                }
                // Keyboards that send the Enter key itself (and hardware keyboards).
                event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.action == KeyEvent.ACTION_DOWN) submitAddress()
                    true
                }
                else -> false
            }
        }
        urlInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus && !webViewDestroyed) updateAddressBar(webView.url)
        }
        reloadButton.setOnClickListener { if (pageLoading) webView.stopLoading() else webView.reload() }
        backButton.setOnClickListener { if (webView.canGoBack()) webView.goBack() }
        forwardButton.setOnClickListener { if (webView.canGoForward()) webView.goForward() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btn_home).setOnClickListener { loadHome() }
        findViewById<View>(R.id.btn_videos).setOnClickListener { showVideoSheet() }
        findViewById<View>(R.id.btn_downloads).setOnClickListener { openDownloads() }
        detectPill.setOnClickListener { onPillClicked() }
    }

    private fun setupEdgeToEdge() {
        val root = findViewById<View>(R.id.root)
        drawBehindSystemBars()
        applySystemBarStyle()
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = systemBarInsets(insets)
            view.setPadding(bars[0], bars[1], bars[2], bars[3])
            consumeInsets(insets)
        }
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private fun loadUrl(url: String) {
        if (!webViewDestroyed) webView.loadUrl(url)
    }

    private fun loadHome() = loadUrl(HOME_URL)

    /** Opens what was typed in the address bar: a web address, or a Google search for anything else. */
    private fun submitAddress() {
        val text = urlInput.text.toString().trim()
        val url = UrlInput.toUrl(text) ?: return
        // "node.js" looks like a site; if no such site exists, search Google for it instead.
        guessedHost = if (UrlInput.isGuessedHost(text)) {
            Uri.parse(url).host?.let { GuessedHost(it, text, SystemClock.elapsedRealtime()) }
        } else {
            null
        }
        hideKeyboard()
        urlInput.clearFocus()
        webView.requestFocus()
        loadUrl(url)
    }

    /** Links opened with this app, and text shared to it from other apps ("Share" in another browser). */
    private fun openFromIntent(intent: Intent?): Boolean {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { MediaUrlClassifier.isHttpUrl(it) }
            // EXTRA_TEXT may be a styled CharSequence, which getStringExtra() would drop.
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.let { UrlInput.fromSharedText(it.toString()) }
            else -> null
        } ?: return false
        guessedHost = null
        if (customView != null) hideCustomView()
        loadUrl(url)
        return true
    }

    private fun updateAddressBar(url: String?) {
        if (urlInput.hasFocus()) return
        urlInput.setText(if (url == null || url == HOME_URL || url == "about:blank") "" else url)
    }

    private fun updateNavigationButtons() {
        if (webViewDestroyed) return
        setButtonEnabled(backButton, webView.canGoBack())
        setButtonEnabled(forwardButton, webView.canGoForward())
        reloadButton.setImageResource(if (pageLoading) R.drawable.ic_close else R.drawable.ic_refresh)
        reloadButton.contentDescription = getString(if (pageLoading) R.string.action_stop else R.string.action_reload)
        updateBackCallback()
    }

    private fun setButtonEnabled(button: ImageButton, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.35f
    }

    private fun handleBack(): Boolean {
        if (customView != null) {
            hideCustomView()
            return true
        }
        if (!webViewDestroyed && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return false
    }

    private fun updateBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback?.setEnabled(customView != null || (!webViewDestroyed && webView.canGoBack()))
        }
    }

    private fun destroyWebView() {
        if (webViewDestroyed) return
        webViewDestroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.stopLoading()
        webView.destroy()
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    override fun onMediaReport(report: MediaReport) {
        // Called on WebView's JavaBridge thread.
        mainHandler.post {
            if (webViewDestroyed) return@post
            val video = store.onMediaReport(report, webView.title.orEmpty()) ?: return@post
            if (report.isPlayEvent) {
                showPill(video)
            } else if (video.id == pillVideoId && detectPill.visibility == View.VISIBLE) {
                updatePillText(video) // resolution/duration arrive after playback starts
            }
        }
    }

    override fun onNetworkReport(report: NetworkReport) {
        mainHandler.post {
            if (!webViewDestroyed) {
                store.onNetworkMedia(report.url, report.mime, report.frameUrl, emptyMap(), webView.title.orEmpty())
            }
        }
    }

    private fun onInterceptedMediaRequest(url: String, headers: Map<String, String>) {
        if (webViewDestroyed) return
        val referer = headers.entries.firstOrNull { it.key.equals("Referer", ignoreCase = true) }?.value
        store.onNetworkMedia(url, null, referer, headers, webView.title.orEmpty())
    }

    private fun onStoreChanged() {
        val items = store.visibleItems()
        updateBadge(items.size)
        videoSheet?.let { if (it.isShowing) it.update(items) }
        for (video in store.pendingHlsProbes()) probeHls(video)
    }

    private fun probeHls(video: DetectedVideo) {
        video.probing = true
        val url = video.url
        prober.probeHls(url, headersFor(video)) { result ->
            if (!webViewDestroyed) store.applyHlsProbe(url, result)
        }
    }

    private fun headersFor(video: DetectedVideo): Map<String, String> {
        val referer = video.pageUrl.ifEmpty { if (webViewDestroyed) "" else webView.url.orEmpty() }
        return RequestHeaders.forDownload(currentUserAgent(), referer, video.requestHeaders)
    }

    private fun currentUserAgent(): String =
        if (webViewDestroyed) defaultUserAgent else webView.settings.userAgentString.orEmpty().ifEmpty { defaultUserAgent }

    private fun updateBadge(count: Int) {
        val grew = count > badgeCount
        badgeCount = count
        if (count <= 0) {
            videoBadge.visibility = View.GONE
            return
        }
        videoBadge.text = if (count > 99) "99+" else count.toString()
        videoBadge.visibility = View.VISIBLE
        if (grew) {
            videoBadge.animate().cancel()
            videoBadge.scaleX = 1.4f
            videoBadge.scaleY = 1.4f
            videoBadge.animate().scaleX(1f).scaleY(1f).setDuration(250).start()
        }
    }

    private fun showPill(video: DetectedVideo) {
        if (customView != null) return
        pillVideoId = video.id
        updatePillText(video)
        detectPill.animate().cancel()
        if (detectPill.visibility != View.VISIBLE) {
            detectPill.alpha = 0f
            detectPill.translationY = dp(24).toFloat()
            detectPill.visibility = View.VISIBLE
        }
        detectPill.animate().alpha(1f).translationY(0f).setDuration(200).start()
        mainHandler.removeCallbacks(hidePillRunnable)
        mainHandler.postDelayed(hidePillRunnable, PILL_DURATION_MS)
    }

    private fun updatePillText(video: DetectedVideo) {
        val details = listOf(
            VideoLabels.typeLabel(video),
            Formatters.resolution(video.width, video.height),
            Formatters.duration(video.durationSec),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        detectPillText.text = getString(
            if (video.isDownloadable) R.string.pill_detected else R.string.pill_detected_unavailable,
            details,
        )
        detectPillAction.visibility = if (video.isDownloadable) View.VISIBLE else View.GONE
    }

    private fun hidePill() {
        mainHandler.removeCallbacks(hidePillRunnable)
        if (detectPill.visibility != View.VISIBLE) return
        detectPill.animate().cancel()
        detectPill.animate().alpha(0f).setDuration(150).withEndAction { detectPill.visibility = View.GONE }.start()
    }

    private fun onPillClicked() {
        hidePill()
        val video = store.findById(pillVideoId)
        if (video != null) startDownload(video) else showVideoSheet()
    }

    private fun showVideoSheet() {
        val sheet = videoSheet ?: VideoListSheet(this, this).also { videoSheet = it }
        sheet.show(store.visibleItems())
    }

    override fun onVideoSelected(video: DetectedVideo) = startDownload(video)

    override fun onVideoLongPressed(video: DetectedVideo) {
        val options = arrayOf(
            getString(R.string.action_copy_url),
            getString(R.string.action_open_with),
            getString(R.string.action_share_url),
        )
        AlertDialog.Builder(this)
            .setTitle(VideoLabels.title(video))
            .setItems(options) { _, which ->
                when (which) {
                    0 -> copyToClipboard(video.url)
                    1 -> openWithOtherApp(video)
                    2 -> shareText(video.url)
                }
            }
            .show()
    }

    override fun onClearVideos() = store.clear()

    // ------------------------------------------------------------------
    // Downloading
    // ------------------------------------------------------------------

    private fun startDownload(video: DetectedVideo) {
        when {
            video.drmProtected -> showInfo(getString(R.string.error_drm))
            video.kind == MediaKind.DASH -> showUnsupported(video, R.string.error_dash)
            video.kind == MediaKind.MSE -> showInfo(getString(R.string.error_mse))
            video.kind == MediaKind.HLS -> downloadHls(video)
            // A <video> URL without a recognisable extension may still be a playlist.
            MediaUrlClassifier.classify(video.url, video.mimeType) == null -> sniffThenDownload(video)
            else -> confirmDirectDownload(video, video.mimeType)
        }
    }

    private fun sniffThenDownload(video: DetectedVideo) {
        toast(R.string.status_checking)
        prober.sniff(video.url, headersFor(video)) { result ->
            if (webViewDestroyed) return@sniff
            when (result) {
                is MediaProber.SniffResult.Playlist -> downloadHlsWith(video, result.probe)
                is MediaProber.SniffResult.File ->
                    confirmDirectDownload(video, result.contentType?.substringBefore(';')?.trim() ?: video.mimeType)
                is MediaProber.SniffResult.Failed -> confirmDirectDownload(video, video.mimeType)
            }
        }
    }

    private fun downloadHls(video: DetectedVideo) {
        val probe = video.hlsProbe
        if (probe != null && probe !is HlsProbe.Failed) {
            downloadHlsWith(video, probe)
            return
        }
        toast(R.string.status_checking)
        video.probing = true
        val url = video.url
        prober.probeHls(url, headersFor(video)) { result ->
            if (webViewDestroyed) return@probeHls
            store.applyHlsProbe(url, result)
            downloadHlsWith(video, result)
        }
    }

    private fun downloadHlsWith(video: DetectedVideo, probe: HlsProbe) {
        when (probe) {
            is HlsProbe.Failed -> showInfo(getString(R.string.error_playlist, probe.message))
            is HlsProbe.Media -> when {
                probe.playlist.isEncrypted -> showInfo(getString(R.string.error_encrypted))
                probe.playlist.isLive -> showInfo(getString(R.string.error_live))
                else -> confirmHlsDownload(video, probe.finalUrl, null, Formatters.resolution(video.width, video.height), video.height)
            }
            is HlsProbe.Master -> {
                val master = probe.playlist
                when {
                    master.isEncrypted -> showInfo(getString(R.string.error_encrypted))
                    master.variants.size == 1 -> {
                        val only = master.variants[0]
                        confirmHlsDownload(video, only.url, master.audioFor(only)?.url, variantLabel(only), only.height)
                    }
                    else -> chooseVariant(video, master)
                }
            }
        }
    }

    private fun chooseVariant(video: DetectedVideo, master: HlsPlaylist.Master) {
        val variants = master.sortedVariants
        val labels = variants.map { variantLabel(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_quality)
            .setItems(labels) { _, which ->
                val variant = variants[which]
                confirmHlsDownload(video, variant.url, master.audioFor(variant)?.url, labels[which], variant.height)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun variantLabel(variant: HlsVariant): String {
        val parts = ArrayList<String>()
        when {
            variant.height > 0 -> parts += "${variant.height}p"
            variant.width > 0 -> parts += Formatters.resolution(variant.width, variant.height)
        }
        Formatters.bitrate(variant.bandwidth).takeIf { it.isNotEmpty() }?.let { parts += it }
        if (parts.isEmpty()) parts += getString(R.string.quality_unknown)
        return parts.joinToString(" · ")
    }

    private fun confirmHlsDownload(video: DetectedVideo, playlistUrl: String, audioUrl: String?, quality: String, height: Int) {
        val defaultName = FileNames.baseName(VideoLabels.title(video), height)
        val details = listOf("HLS", quality, Formatters.duration(video.durationSec), Formatters.host(playlistUrl))
            .filter { it.isNotEmpty() }
            .joinToString(" · ")
        askFileName(defaultName, details) { name ->
            ensureStoragePermission {
                requestNotificationPermission()
                try {
                    HlsDownloadService.start(this, HlsDownloadRequest(playlistUrl, audioUrl, name, headersFor(video)))
                    toast(R.string.download_started)
                } catch (e: Exception) {
                    showInfo(getString(R.string.error_start_failed, e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    private fun confirmDirectDownload(video: DetectedVideo, mimeType: String?) {
        val extension = directExtension(video.url, mimeType)
        val defaultName = FileNames.baseName(VideoLabels.title(video), video.height)
        val details = listOf(
            extension.uppercase(),
            Formatters.resolution(video.width, video.height),
            Formatters.duration(video.durationSec),
            Formatters.host(video.url),
        ).filter { it.isNotEmpty() }.joinToString(" · ")
        askFileName(defaultName, details) { name ->
            ensureStoragePermission {
                enqueueDirect(video.url, "$name.$extension", mimeType ?: mimeForExtension(extension), headersFor(video))
            }
        }
    }

    /** Downloads started by the page itself (links to files, Content-Disposition: attachment). */
    private fun onPageDownloadRequested(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        if (!MediaUrlClassifier.isHttpUrl(url)) {
            toast(R.string.error_unsupported_download)
            return
        }
        val fileName = FileNames.sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType), maxLength = 120)
        val referer = if (webViewDestroyed) null else webView.url
        AlertDialog.Builder(this)
            .setTitle(R.string.download_file_title)
            .setMessage(fileName)
            .setPositiveButton(R.string.download) { _, _ ->
                ensureStoragePermission {
                    val headers = RequestHeaders.forDownload(userAgent ?: currentUserAgent(), referer, emptyMap())
                    enqueueDirect(url, fileName, mimeType, headers)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun enqueueDirect(url: String, fileName: String, mimeType: String?, headers: Map<String, String>) {
        try {
            DirectDownloader.enqueue(this, url, fileName, mimeType, headers)
            toast(R.string.download_started)
        } catch (e: Exception) {
            showInfo(getString(R.string.error_start_failed, e.message ?: e.javaClass.simpleName))
        }
    }

    private fun askFileName(defaultName: String, details: String, onConfirm: (String) -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_file_name, null)
        val input = view.findViewById<EditText>(R.id.file_name_input)
        view.findViewById<TextView>(R.id.file_name_details).text = details
        input.setText(defaultName)
        AlertDialog.Builder(this)
            .setTitle(R.string.download_dialog_title)
            .setView(view)
            .setPositiveButton(R.string.download) { _, _ ->
                onConfirm(FileNames.sanitize(FileNames.stripExtension(input.text.toString())))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun directExtension(url: String, mimeType: String?): String {
        MediaUrlClassifier.extensionOf(url)?.let { ext ->
            if (MediaUrlClassifier.classify("https://x/a.$ext") == MediaKind.DIRECT) return ext
        }
        val fromMime = mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        return fromMime?.takeIf { it.isNotBlank() } ?: "mp4"
    }

    private fun mimeForExtension(extension: String): String? =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)

    private fun ensureStoragePermission(action: () -> Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        ) {
            action()
            return
        }
        pendingPermissionAction = action
        requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_STORAGE)
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        }
    }

    private fun downloadPlayingVideo() {
        val items = store.visibleItems()
        val video = items.firstOrNull { it.lastPlayedAt > 0 } ?: items.firstOrNull()
        if (video == null) toast(R.string.no_video_detected) else startDownload(video)
    }

    // ------------------------------------------------------------------
    // Menu & helpers
    // ------------------------------------------------------------------

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.main_menu, popup.menu)
        popup.menu.findItem(R.id.menu_desktop_mode)?.isChecked = desktopMode
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_desktop_mode -> setDesktopMode(!desktopMode)
                R.id.menu_share -> webView.url?.takeIf { MediaUrlClassifier.isHttpUrl(it) }?.let { shareText(it) }
                R.id.menu_clear_videos -> store.clear()
                R.id.menu_downloads -> openDownloads()
                R.id.menu_about -> showAbout()
                else -> return@setOnMenuItemClickListener false
            }
            true
        }
        popup.show()
    }

    private fun setDesktopMode(enabled: Boolean) {
        desktopMode = enabled
        webView.settings.userAgentString = if (enabled) desktopUserAgent() else null
        webView.reload()
    }

    private fun desktopUserAgent(): String {
        val chrome = Regex("Chrome/([\\d.]+)").find(defaultUserAgent)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    private fun openDownloads() {
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_no_app)
        }
    }

    private fun showAbout() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title, packageManager.getPackageInfo(packageName, 0).versionName))
            .setMessage(R.string.about_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun showUnsupported(video: DetectedVideo, messageRes: Int) {
        AlertDialog.Builder(this)
            .setMessage(messageRes)
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(R.string.action_copy_url) { _, _ -> copyToClipboard(video.url) }
            .show()
    }

    private fun showInfo(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("URL", text))
        // Android 13+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) toast(R.string.copied)
    }

    private fun shareText(text: String) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(intent, null))
    }

    private fun openWithOtherApp(video: DetectedVideo) {
        if (!MediaUrlClassifier.isHttpUrl(video.url)) {
            toast(R.string.error_no_app)
            return
        }
        val type = when (video.kind) {
            MediaKind.HLS -> "application/vnd.apple.mpegurl"
            MediaKind.DASH -> "application/dash+xml"
            else -> video.mimeType ?: "video/*"
        }
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(video.url), type)
        try {
            startActivity(Intent.createChooser(intent, null))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.error_no_app)
        }
    }

    /** Opens links with non-web schemes (intent:, market:, tel:, ...) in other apps. */
    private fun openExternalApp(url: String) {
        val intent = try {
            if (url.startsWith("intent:", ignoreCase = true)) {
                Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            } else {
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
            }
        } catch (e: Exception) {
            return
        }
        intent.addCategory(Intent.CATEGORY_BROWSABLE)
        intent.component = null
        intent.selector = null
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            val fallback = intent.getStringExtra("browser_fallback_url")
            if (fallback != null && MediaUrlClassifier.isHttpUrl(fallback)) loadUrl(fallback) else toast(R.string.error_no_app)
        } catch (e: SecurityException) {
            toast(R.string.error_no_app)
        }
    }

    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlInput.windowToken, 0)
    }

    private fun toast(messageRes: Int) = Toast.makeText(this, messageRes, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    // ------------------------------------------------------------------
    // Fullscreen video & system bars
    // ------------------------------------------------------------------

    private fun showCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        hidePill()
        val container = FrameLayout(this)
        container.setBackgroundColor(Color.BLACK)
        container.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val button = createFullscreenDownloadButton()
        container.addView(button)
        container.setOnApplyWindowInsetsListener { _, insets ->
            val cutout = cutoutInsets(insets)
            val params = button.layoutParams as FrameLayout.LayoutParams
            params.setMargins(0, dp(16) + cutout[1], dp(16) + cutout[2], 0)
            button.layoutParams = params
            insets
        }
        (window.decorView as ViewGroup).addView(
            container,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
        fullscreenContainer = container
        customView = view
        customViewCallback = callback
        setSystemBarsHidden(true)
        updateBackCallback()
    }

    private fun hideCustomView() {
        val container = fullscreenContainer ?: return
        fullscreenContainer = null
        customView = null
        (window.decorView as ViewGroup).removeView(container)
        container.removeAllViews()
        setSystemBarsHidden(false)
        val callback = customViewCallback
        customViewCallback = null
        callback?.onCustomViewHidden()
        updateBackCallback()
    }

    private fun createFullscreenDownloadButton(): ImageButton {
        val button = ImageButton(this)
        button.setImageResource(R.drawable.ic_download)
        button.setColorFilter(Color.WHITE)
        button.background = getDrawable(R.drawable.bg_fullscreen_button)
        button.contentDescription = getString(R.string.action_download_playing)
        button.layoutParams = FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END)
        button.setOnClickListener { downloadPlayingVideo() }
        return button
    }

    private fun isLightTheme(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

    private fun applySystemBarStyle() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (isLightTheme()) mask else 0, mask)
        } else {
            setLegacySystemUiFlags(legacyEdgeToEdgeFlags())
        }
    }

    private fun setSystemBarsHidden(hidden: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val controller = window.insetsController ?: return
            if (hidden) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE)
                controller.hide(WindowInsets.Type.systemBars())
            } else {
                controller.show(WindowInsets.Type.systemBars())
            }
        } else {
            setLegacySystemUiFlags(if (hidden) legacyFullscreenFlags() else legacyEdgeToEdgeFlags())
        }
    }

    /**
     * Edge-to-edge layout on every version (Android 15+ enforces it anyway for this
     * targetSdk); the root view pads itself with the system bar insets.
     */
    @Suppress("DEPRECATION")
    private fun drawBehindSystemBars() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
    }

    @Suppress("DEPRECATION")
    private fun setLegacySystemUiFlags(flags: Int) {
        window.decorView.systemUiVisibility = flags
    }

    @Suppress("DEPRECATION")
    private fun legacyEdgeToEdgeFlags(): Int {
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        if (isLightTheme()) {
            flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
        return flags
    }

    @Suppress("DEPRECATION")
    private fun legacyFullscreenFlags(): Int =
        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

    /** left, top, right, bottom insets of the system bars (and the keyboard at the bottom). */
    private fun systemBarInsets(insets: WindowInsets): IntArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            return intArrayOf(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
        }
        return legacySystemWindowInsets(insets)
    }

    @Suppress("DEPRECATION")
    private fun legacySystemWindowInsets(insets: WindowInsets): IntArray = intArrayOf(
        insets.systemWindowInsetLeft,
        insets.systemWindowInsetTop,
        insets.systemWindowInsetRight,
        insets.systemWindowInsetBottom,
    )

    @Suppress("DEPRECATION")
    private fun consumeInsets(insets: WindowInsets): WindowInsets =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WindowInsets.CONSUMED else insets.consumeSystemWindowInsets()

    private fun cutoutInsets(insets: WindowInsets): IntArray {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val cutout = insets.displayCutout
            if (cutout != null) {
                return intArrayOf(cutout.safeInsetLeft, cutout.safeInsetTop, cutout.safeInsetRight, cutout.safeInsetBottom)
            }
        }
        return intArrayOf(0, 0, 0, 0)
    }

    // ------------------------------------------------------------------
    // WebView clients
    // ------------------------------------------------------------------

    private inner class BrowserClient : WebViewClient() {

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            return when (uri.scheme?.lowercase()) {
                null, "http", "https", "about", "data", "blob", "file", "javascript" -> false
                else -> {
                    // Only open other apps when the user actually tapped something.
                    if (request.hasGesture()) openExternalApp(uri.toString())
                    true
                }
            }
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            pageLoading = true
            injectedDuringProgress = false
            store.clear()
            hidePill()
            updateAddressBar(url)
            updateNavigationButtons()
            injector.injectIfNeeded(view)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            pageLoading = false
            progressBar.visibility = View.GONE
            updateNavigationButtons()
            injector.injectIfNeeded(view)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
            updateAddressBar(url)
            updateNavigationButtons()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (!request.isForMainFrame || error.errorCode != WebViewClient.ERROR_HOST_LOOKUP) return
            val guess = guessedHost ?: return
            guessedHost = null
            // The typed text looked like a site name but no such site exists: search for it.
            if (request.url.host.equals(guess.host, ignoreCase = true) &&
                SystemClock.elapsedRealtime() - guess.at < GUESS_TIMEOUT_MS
            ) {
                view.loadUrl(UrlInput.searchUrl(guess.query))
            }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            // Runs on a background thread for every request of the page; only observe.
            val url = request.url.toString()
            if (MediaUrlClassifier.classify(url) != null) {
                val headers: Map<String, String> = request.requestHeaders?.let { HashMap(it) } ?: emptyMap()
                mainHandler.post { onInterceptedMediaRequest(url, headers) }
            }
            return null
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            // The page's renderer crashed or was killed; this WebView cannot be used anymore.
            destroyWebView()
            Toast.makeText(this@MainActivity, R.string.error_renderer_gone, Toast.LENGTH_LONG).show()
            recreate()
            return true
        }
    }

    private inner class ChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progressBar.progress = newProgress
            progressBar.visibility = if (pageLoading && newProgress < 100) View.VISIBLE else View.GONE
            if (newProgress >= 40 && !injectedDuringProgress) {
                injectedDuringProgress = true
                injector.injectIfNeeded(view)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) = showCustomView(view, callback)

        override fun onHideCustomView() = hideCustomView()

        // Without this WebView draws a grey play icon on videos that have no poster.
        override fun getDefaultVideoPoster(): Bitmap? =
            blankPoster ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { blankPoster = it }

        override fun onPermissionRequest(request: PermissionRequest) = request.deny()

        override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
            callback?.invoke(origin, false, false)
        }
    }

    private companion object {
        const val HOME_URL = "file:///android_asset/home.html"
        const val PILL_DURATION_MS = 6_000L
        const val GUESS_TIMEOUT_MS = 30_000L
        const val REQUEST_STORAGE = 10
        const val REQUEST_NOTIFICATIONS = 11
    }
}
