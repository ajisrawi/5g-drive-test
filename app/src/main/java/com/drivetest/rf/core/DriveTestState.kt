package com.drivetest.rf.core

import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Process-wide live state shared by the service (producer) and the UI (consumer). */
object DriveTestState {
    val running = MutableStateFlow(false)
    val sessionStart = MutableStateFlow(0L)
    val sessionDir = MutableStateFlow<String?>(null)
    val location = MutableStateFlow<Location?>(null)
    val radio = MutableStateFlow<RadioSnapshot?>(null)
    val video = MutableStateFlow<VideoStats?>(null)
    val activeCallId = MutableStateFlow<Int?>(null)
    val sampleCount = MutableStateFlow(0)
    val points = MutableStateFlow<List<MapPoint>>(emptyList())
    val calls = MutableStateFlow<List<CallResult>>(emptyList())
    val events = MutableStateFlow<List<String>>(emptyList())

    fun reset(dir: String) {
        sessionDir.value = dir
        sessionStart.value = System.currentTimeMillis()
        sampleCount.value = 0
        points.value = emptyList()
        calls.value = emptyList()
        video.value = null
        activeCallId.value = null
        running.value = true
    }

    /** Adds a map point, decimated to one every 5 m (or every 10 s when standing still). */
    fun addSample(s: Sample) {
        sampleCount.update { it + 1 }
        val loc = s.location ?: return
        val last = points.value.lastOrNull()
        if (last != null) {
            val d = FloatArray(1)
            Location.distanceBetween(last.lat, last.lon, loc.latitude, loc.longitude, d)
            if (d[0] < 5f && s.time - last.time < 10_000) return
        }
        val p = MapPoint(
            time = s.time, lat = loc.latitude, lon = loc.longitude,
            level = s.radio.level, sinr = s.radio.sinr, tech = s.radio.tech,
            videoHeight = s.video?.height, videoStalled = s.video?.stalled == true,
            callId = s.callId,
        )
        points.update { it + p }
    }

    fun upsertCall(c: CallResult) = calls.update { list ->
        val i = list.indexOfFirst { it.id == c.id }
        if (i < 0) list + c else list.toMutableList().also { it[i] = c }
    }

    fun event(msg: String) {
        val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        events.update { (listOf("$t  $msg") + it).take(300) }
    }
}

/** Survives the session scope so post-call analysis finishes after the user presses Stop. */
object AppScope {
    val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
