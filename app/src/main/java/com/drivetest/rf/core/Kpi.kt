package com.drivetest.rf.core

import com.google.android.gms.maps.model.LatLng

enum class MapLayer(val label: String) {
    LEVEL("RSRP"), SINR("SINR"), TECH("RAT"), VIDEO("Video"), CALLS("Voice"),
}

/** Colour bins used on the map, in the KML export and in the UI. */
object Kpi {
    val EXCELLENT = 0xFF00A651.toInt()
    val GOOD = 0xFF8BC34A.toInt()
    val FAIR = 0xFFFFD600.toInt()
    val POOR = 0xFFFF9800.toInt()
    val BAD = 0xFFE53935.toInt()
    val NONE = 0xFF9E9E9E.toInt()
    val IN_CALL = 0xFF1E88E5.toInt()
    private val SA = 0xFF8E24AA.toInt()
    private val NSA = 0xFF1E88E5.toInt()

    fun level(v: Int?) = when {
        v == null -> NONE
        v >= -80 -> EXCELLENT
        v >= -90 -> GOOD
        v >= -100 -> FAIR
        v >= -110 -> POOR
        else -> BAD
    }

    fun sinr(v: Int?) = when {
        v == null -> NONE
        v >= 20 -> EXCELLENT
        v >= 13 -> GOOD
        v >= 0 -> FAIR
        v >= -3 -> POOR
        else -> BAD
    }

    fun video(height: Int?, stalled: Boolean) = when {
        stalled -> BAD
        height == null || height == 0 -> NONE
        height >= 1080 -> EXCELLENT
        height >= 720 -> GOOD
        height >= 480 -> FAIR
        else -> POOR
    }

    fun tech(t: String) = when {
        t == "5G SA" -> SA
        t.startsWith("5G") -> NSA
        t.startsWith("LTE") -> EXCELLENT
        t == "3G" -> POOR
        t == "2G" -> BAD
        else -> NONE
    }

    fun mos(m: Double?) = when {
        m == null -> NONE
        m >= 4.0 -> EXCELLENT
        m >= 3.5 -> GOOD
        m >= 3.0 -> FAIR
        m >= 2.5 -> POOR
        else -> BAD
    }

    fun color(layer: MapLayer, p: MapPoint): Int = when (layer) {
        MapLayer.LEVEL -> level(p.level)
        MapLayer.SINR -> sinr(p.sinr)
        MapLayer.TECH -> tech(p.tech)
        MapLayer.VIDEO -> video(p.videoHeight, p.videoStalled)
        MapLayer.CALLS -> if (p.callId != null) IN_CALL else NONE
    }

    fun legend(layer: MapLayer): List<Pair<String, Int>> = when (layer) {
        MapLayer.LEVEL -> listOf("≥ -80" to EXCELLENT, "-90" to GOOD, "-100" to FAIR, "-110" to POOR, "< -110 dBm" to BAD)
        MapLayer.SINR -> listOf("≥ 20" to EXCELLENT, "13" to GOOD, "0" to FAIR, "-3" to POOR, "< -3 dB" to BAD)
        MapLayer.TECH -> listOf("5G SA" to SA, "5G NSA" to NSA, "LTE" to EXCELLENT, "3G" to POOR, "2G" to BAD)
        MapLayer.VIDEO -> listOf("≥1080p" to EXCELLENT, "720p" to GOOD, "480p" to FAIR, "<480p" to POOR, "Stall" to BAD)
        MapLayer.CALLS -> listOf("MOS ≥4" to EXCELLENT, "3.5" to GOOD, "3.0" to FAIR, "2.5" to POOR, "Fail/drop" to BAD, "In call" to IN_CALL)
    }

    /** Hue for a default Google Maps marker. */
    fun markerHue(c: CallResult): Float = when {
        !c.final -> 210f
        c.failed -> 0f
        c.mos == null -> 180f
        c.mos >= 4.0 -> 120f
        c.mos >= 3.5 -> 90f
        c.mos >= 3.0 -> 60f
        c.mos >= 2.5 -> 30f
        else -> 0f
    }
}

class ColoredRun(val color: Int, val points: List<LatLng>)

object MapRuns {
    /** Splits the route into polylines of constant colour; breaks on gaps > 30 s. */
    fun build(points: List<MapPoint>, layer: MapLayer): List<ColoredRun> {
        val runs = ArrayList<ColoredRun>()
        var cur = ArrayList<LatLng>()
        var curColor = 0
        var lastTime = 0L
        for (p in points) {
            val c = Kpi.color(layer, p)
            val ll = LatLng(p.lat, p.lon)
            when {
                cur.isEmpty() -> { curColor = c; cur.add(ll) }
                p.time - lastTime > 30_000 -> {
                    if (cur.size >= 2) runs += ColoredRun(curColor, cur)
                    cur = arrayListOf(ll); curColor = c
                }
                c == curColor -> cur.add(ll)
                else -> {
                    cur.add(ll)
                    runs += ColoredRun(curColor, cur)
                    cur = arrayListOf(ll); curColor = c
                }
            }
            lastTime = p.time
        }
        if (cur.size >= 2) runs += ColoredRun(curColor, cur)
        return runs
    }
}
