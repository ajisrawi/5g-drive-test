package com.drivetest.rf.export

import com.drivetest.rf.core.CallResult
import com.drivetest.rf.core.Kpi
import com.drivetest.rf.core.MapLayer
import com.drivetest.rf.core.MapPoint
import com.drivetest.rf.core.MapRuns
import com.drivetest.rf.core.fmt
import java.io.File

/** Google Earth export: one folder per KPI layer (coloured route) plus call placemarks. */
object KmlWriter {
    fun write(file: File, name: String, points: List<MapPoint>, calls: List<CallResult>) {
        file.bufferedWriter().use { w ->
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            w.write("<kml xmlns=\"http://www.opengis.net/kml/2.2\"><Document><name>Drive test ${esc(name)}</name>\n")
            for (layer in listOf(MapLayer.LEVEL, MapLayer.SINR, MapLayer.TECH, MapLayer.VIDEO)) {
                w.write("<Folder><name>${esc(layer.label)}</name>\n")
                for (run in MapRuns.build(points, layer)) {
                    w.write("<Placemark><Style><LineStyle><color>${kmlColor(run.color)}</color><width>6</width></LineStyle></Style>")
                    w.write("<LineString><tessellate>1</tessellate><coordinates>")
                    run.points.forEach { w.write("${it.longitude},${it.latitude},0 ") }
                    w.write("</coordinates></LineString></Placemark>\n")
                }
                w.write("</Folder>\n")
            }
            w.write("<Folder><name>Voice calls</name>\n")
            for (c in calls) {
                if (c.lat == null || c.lon == null) continue
                val color = if (c.failed) Kpi.BAD else Kpi.mos(c.mos)
                val desc = buildString {
                    append("Status: ${c.status}<br/>")
                    c.mos?.let { append("MOS: ${it.fmt(2)} (${c.mosSource})<br/>") }
                    c.setupSec?.let { append("Setup: ${it.fmt(1)} s<br/>") }
                    c.avgLevel?.let { append("Avg level: ${it.fmt(1)} dBm, min ${c.minLevel} dBm<br/>") }
                    c.techs?.let { append("RAT: $it<br/>") }
                    c.summary?.let { append(it) }
                }
                w.write("<Placemark><name>Call #${c.id} ${esc(c.status)}</name>")
                w.write("<description>${esc(desc)}</description>")
                w.write("<Style><IconStyle><color>${kmlColor(color)}</color></IconStyle></Style>")
                w.write("<Point><coordinates>${c.lon},${c.lat},0</coordinates></Point></Placemark>\n")
            }
            w.write("</Folder>\n</Document></kml>\n")
        }
    }

    /** ARGB → KML aabbggrr. */
    private fun kmlColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "%02x%02x%02x%02x".format(a, b, g, r)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
