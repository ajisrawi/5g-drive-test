package com.drivetest.rf.export

import android.content.Context
import com.drivetest.rf.core.CallResult
import com.drivetest.rf.core.MapPoint
import com.drivetest.rf.core.Sample
import com.drivetest.rf.core.fmt
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes one session folder: samples.csv (every sample), calls.csv (one row per call), route.kml. */
class SessionLogger(ctx: Context) {
    val dir: File = File(
        ctx.getExternalFilesDir(null),
        "sessions/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()),
    ).apply { mkdirs() }

    private val samples = File(dir, "samples.csv").bufferedWriter()
    private val callsFile = File(dir, "calls.csv")
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
    private var closed = false
    private var count = 0

    init {
        samples.write(SAMPLE_HEADER)
        samples.newLine()
        samples.flush()
        callsFile.writeText(CALL_HEADER + "\n")
    }

    @Synchronized
    fun writeSample(s: Sample) {
        if (closed) return
        val l = s.location
        val r = s.radio
        val v = s.video
        val nr = r.nr
        val lte = r.lte
        val w = r.wcdma
        val g = r.gsm
        samples.write(csv(
            iso.format(Date(s.time)), s.time,
            l?.latitude, l?.longitude, l?.altitude?.fmt(1), l?.speed?.let { it * 3.6f }?.fmt(1), l?.accuracy?.fmt(1),
            r.tech, r.dataNetwork, r.overrideNetwork, r.operator, r.mccMnc,
            nr?.registered, nr?.nci, nr?.pci, nr?.tac, nr?.arfcn, nr?.bands,
            nr?.ssRsrp, nr?.ssRsrq, nr?.ssSinr, nr?.csiRsrp, nr?.csiRsrq, nr?.csiSinr,
            lte?.eci, lte?.pci, lte?.tac, lte?.earfcn, lte?.bands, lte?.bandwidthKhz,
            lte?.rsrp, lte?.rsrq, lte?.sinr, lte?.rssi, lte?.cqi, lte?.ta,
            w?.cid, w?.lac, w?.uarfcn, w?.psc, w?.rscp, w?.ecno,
            g?.cid, g?.lac, g?.arfcn, g?.bsic, g?.rssi, g?.ber,
            r.neighbors.size, r.neighbors.joinToString(";"),
            v?.state, v?.resolution, v?.bandwidthKbps, v?.bufferSec?.fmt(2), v?.droppedFrames, v?.stalls, v?.stallSec?.fmt(1),
            s.callId,
        ))
        samples.newLine()
        if (++count % 5 == 0) samples.flush()
    }

    /** Appends a finished call. Works after [close] too, so late AI results still get saved. */
    @Synchronized
    fun writeCall(c: CallResult) {
        val a = c.audio
        callsFile.appendText(csv(
            c.id, iso.format(Date(c.dialTime)), c.lat, c.lon, c.place, c.status,
            c.setupSec?.fmt(1), c.deviceDurationSec?.fmt(1), c.recordSec, c.twilioSid,
            c.techs, c.avgLevel?.fmt(1), c.minLevel, c.avgSinr?.fmt(1),
            c.mos?.fmt(2), c.mosSource, c.intelligibility, c.noise, c.choppiness, c.distortion,
            a?.speechRatio?.fmt(2), a?.snrDb?.fmt(1), a?.clippingPct?.fmt(2), a?.dropouts,
            c.insights.entries.joinToString(";") { "${it.key}=${it.value}" },
            c.summary, c.transcript, c.note,
        ) + "\n")
    }

    @Synchronized
    fun close(points: List<MapPoint>, calls: List<CallResult>) {
        if (closed) return
        closed = true
        samples.close()
        runCatching { KmlWriter.write(File(dir, "route.kml"), dir.name, points, calls) }
    }

    private fun csv(vararg values: Any?): String = values.joinToString(",") { v ->
        when (v) {
            null -> ""
            is String -> if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
            else -> v.toString()
        }
    }

    private companion object {
        val SAMPLE_HEADER = listOf(
            "time", "epoch_ms", "lat", "lon", "alt_m", "speed_kmh", "gps_acc_m",
            "tech", "data_rat", "override_rat", "operator", "mcc_mnc",
            "nr_registered", "nr_nci", "nr_pci", "nr_tac", "nr_arfcn", "nr_band",
            "nr_ss_rsrp", "nr_ss_rsrq", "nr_ss_sinr", "nr_csi_rsrp", "nr_csi_rsrq", "nr_csi_sinr",
            "lte_eci", "lte_pci", "lte_tac", "lte_earfcn", "lte_band", "lte_bw_khz",
            "lte_rsrp", "lte_rsrq", "lte_sinr", "lte_rssi", "lte_cqi", "lte_ta",
            "wcdma_cid", "wcdma_lac", "wcdma_uarfcn", "wcdma_psc", "wcdma_rscp", "wcdma_ecno",
            "gsm_cid", "gsm_lac", "gsm_arfcn", "gsm_bsic", "gsm_rssi", "gsm_ber",
            "neighbor_count", "neighbors(rat:arfcn/pci/level)",
            "video_state", "video_res", "video_bw_kbps", "video_buffer_s", "video_dropped_frames", "video_stalls", "video_stall_s",
            "call_id",
        ).joinToString(",")

        val CALL_HEADER = listOf(
            "call_id", "dial_time", "lat", "lon", "place", "status",
            "setup_s", "device_duration_s", "record_s", "twilio_sid",
            "techs", "avg_level_dbm", "min_level_dbm", "avg_sinr_db",
            "mos", "mos_source", "intelligibility_pct", "noise", "choppiness", "distortion",
            "speech_ratio", "snr_db", "clipping_pct", "dropouts",
            "twilio_insights", "ai_summary", "transcript", "note",
        ).joinToString(",")
    }
}
