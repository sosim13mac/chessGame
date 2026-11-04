# The detection script calls these methods from JavaScript; keep them when minifying.
-keepclassmembers class com.sosim.videocatcher.sniffer.SnifferBridge {
    @android.webkit.JavascriptInterface <methods>;
}
