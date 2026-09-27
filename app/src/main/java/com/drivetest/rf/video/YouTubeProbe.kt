package com.drivetest.rf.video

import com.drivetest.rf.core.VideoStats
import com.drivetest.rf.core.optDoubleOrNull
import com.drivetest.rf.core.optIntOrNull
import org.json.JSONObject
import org.json.JSONTokener

object YouTubeProbe {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    fun videoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|embed/|shorts/|live/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)
            ?: url.trim().takeIf { it.matches(Regex("[A-Za-z0-9_-]{11}")) }

    fun pageUrl(url: String, watchPage: Boolean): String? {
        val id = videoId(url) ?: return null
        return if (watchPage) "https://www.youtube.com/watch?v=$id"
        else "https://www.youtube.com/embed/$id?autoplay=1&mute=1&playsinline=1&loop=1&playlist=$id&controls=1"
    }

    /**
     * Runs in the page every second. Reads the YouTube player's own "Stats for nerds"
     * (getStatsForNerds – the same data as the right-click overlay), player state, and the HTML5
     * <video> element (real decoded resolution, dropped frames, buffer ahead). It also keeps the
     * video muted and looping so the test runs for the whole drive.
     */
    val JS = """
        (function(){
          var r={};
          try{
            var p=document.getElementById('movie_player');
            var v=document.querySelector('video');
            if(v){v.muted=true;}
            if(p){
              try{ if(p.mute) p.mute(); }catch(e){}
              try{ r.state=p.getPlayerState(); }catch(e){}
              try{ r.q=p.getPlaybackQuality(); }catch(e){}
              try{ var s=p.getStatsForNerds&&p.getStatsForNerds(); if(s) r.sfn=s; }catch(e){}
              try{
                if(r.state===0){ p.seekTo(0,true); p.playVideo(); }
                else if(r.state===2||r.state===-1||r.state===5){ p.playVideo(); }
              }catch(e){}
            }
            if(v){
              r.w=v.videoWidth; r.h=v.videoHeight; r.t=v.currentTime;
              if(v.getVideoPlaybackQuality){ var q=v.getVideoPlaybackQuality(); r.drop=q.droppedVideoFrames; r.tot=q.totalVideoFrames; }
              var b=0;
              for(var i=0;i<v.buffered.length;i++){
                if(v.buffered.start(i)<=v.currentTime+0.5 && v.buffered.end(i)>=v.currentTime){ b=v.buffered.end(i)-v.currentTime; }
              }
              r.buf=b;
              if(r.state===undefined){
                r.state = v.ended?0:(v.paused?2:(v.readyState<3?3:1));
                if(v.ended){ v.currentTime=0; v.play(); } else if(v.paused){ v.play(); }
              }
            }
          }catch(e){ r.err=String(e); }
          return JSON.stringify(r);
        })()
    """.trimIndent()
}

/** Turns the raw per-second probe results into QoE stats (startup time, stalls, stall time). */
class VideoQoeTracker {
    private val loadTime = System.currentTimeMillis()
    private var firstPlay: Long? = null
    private var stalls = 0
    private var stallMs = 0L
    private var buffering = false
    private var lastPoll = 0L

    fun update(raw: String?): VideoStats? {
        val o = decode(raw) ?: return null
        val now = System.currentTimeMillis()
        val code = o.optInt("state", -99)
        val state = when (code) {
            -1 -> "unstarted"; 0 -> "ended"; 1 -> "playing"; 2 -> "paused"; 3 -> "buffering"; 5 -> "cued"
            else -> "no player"
        }
        if (code == 1 && firstPlay == null) firstPlay = now
        val isBuffering = code == 3 && firstPlay != null
        if (buffering && lastPoll > 0) stallMs += now - lastPoll
        if (isBuffering && !buffering) stalls++
        buffering = isBuffering
        lastPoll = now

        var bandwidth: Int? = null
        var buffer = o.optDoubleOrNull("buf")
        var codecs: String? = null
        o.optJSONObject("sfn")?.let { sfn ->
            sfn.keys().forEach { k ->
                val v = sfn.optString(k)
                when {
                    "bandwidth" in k -> firstNumber(v)?.let { bandwidth = it.toInt() }
                    "buffer_health" in k -> firstNumber(v)?.let { buffer = it }
                    k == "codecs" -> codecs = v
                }
            }
        }
        return VideoStats(
            time = now,
            state = state,
            width = o.optIntOrNull("w"),
            height = o.optIntOrNull("h"),
            bandwidthKbps = bandwidth,
            bufferSec = buffer,
            droppedFrames = o.optIntOrNull("drop"),
            totalFrames = o.optIntOrNull("tot"),
            stalls = stalls,
            stallSec = stallMs / 1000.0,
            startupMs = firstPlay?.let { it - loadTime },
            codecs = codecs,
            positionSec = o.optDoubleOrNull("t"),
        )
    }

    private fun decode(raw: String?): JSONObject? = runCatching {
        val s = JSONTokener(raw ?: return null).nextValue() as? String ?: return null
        JSONObject(s)
    }.getOrNull()

    private fun firstNumber(v: String): Double? =
        Regex("-?\\d+(\\.\\d+)?").find(v.replace(",", ""))?.value?.toDoubleOrNull()
}
