package com.drivetest.rf.ui

import android.annotation.SuppressLint
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.drivetest.rf.core.AppSettings
import com.drivetest.rf.core.DriveTestState
import com.drivetest.rf.core.Kpi
import com.drivetest.rf.core.fmt
import com.drivetest.rf.video.VideoQoeTracker
import com.drivetest.rf.video.YouTubeProbe
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Plays the configured YouTube video for the whole session and samples its QoE every second. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoPanel(settings: AppSettings) {
    val url = remember(settings) { YouTubeProbe.pageUrl(settings.youtubeUrl, settings.youtubeWatchPage) }
    if (url == null) {
        Text("Video test: invalid YouTube URL in Settings", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    val stats by DriveTestState.video.collectAsState()
    var webView by remember { mutableStateOf<WebView?>(null) }
    val tracker = remember { VideoQoeTracker() }

    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        while (isActive) {
            delay(1000)
            wv.evaluateJavascript(YouTubeProbe.JS) { raw ->
                tracker.update(raw)?.let { DriveTestState.video.value = it }
            }
        }
    }

    val watchPage = settings.youtubeWatchPage
    Row(Modifier.fillMaxWidth().padding(8.dp)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    with(this.settings) {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        if (watchPage) {
                            // Desktop page exposes the full player API incl. getStatsForNerds().
                            userAgentString = YouTubeProbe.DESKTOP_UA
                            useWideViewPort = true
                            loadWithOverviewMode = true
                        }
                    }
                    webChromeClient = WebChromeClient()
                    webViewClient = WebViewClient()
                    loadUrl(url, mapOf("Referer" to "https://www.youtube.com/"))
                    webView = this
                }
            },
            onRelease = { it.stopLoading(); it.destroy() },
            modifier = Modifier.weight(0.5f).aspectRatio(16f / 9f),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(0.5f)) {
            val s = stats
            Text("YouTube QoE", style = MaterialTheme.typography.labelMedium)
            Text(
                s?.let { "${it.resolution} · ${it.state}" } ?: "loading…",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(Kpi.video(s?.height, s?.stalled == true)),
            )
            if (s != null) {
                val lines = listOfNotNull(
                    s.bandwidthKbps?.let { "Est. bandwidth $it kbps" },
                    s.bufferSec?.let { "Buffer ${it.fmt(1)} s" },
                    "Stalls ${s.stalls} (${s.stallSec.fmt(1)} s)",
                    s.startupMs?.let { "Startup ${(it / 1000.0).fmt(1)} s" },
                    s.droppedFrames?.let { "Dropped ${it}/${s.totalFrames ?: "?"} frames" },
                )
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
