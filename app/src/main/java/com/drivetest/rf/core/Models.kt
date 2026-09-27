package com.drivetest.rf.core

import android.location.Location

data class NrCell(
    val registered: Boolean,
    val nci: Long?, val pci: Int?, val tac: Int?, val arfcn: Int?, val bands: String?,
    val ssRsrp: Int?, val ssRsrq: Int?, val ssSinr: Int?,
    val csiRsrp: Int?, val csiRsrq: Int?, val csiSinr: Int?,
)

data class LteCell(
    val registered: Boolean,
    val eci: Int?, val pci: Int?, val tac: Int?, val earfcn: Int?, val bands: String?, val bandwidthKhz: Int?,
    val rsrp: Int?, val rsrq: Int?, val sinr: Int?, val rssi: Int?, val cqi: Int?, val ta: Int?,
)

data class WcdmaCell(
    val registered: Boolean,
    val cid: Int?, val lac: Int?, val uarfcn: Int?, val psc: Int?, val rscp: Int?, val ecno: Int?,
)

data class GsmCell(
    val registered: Boolean,
    val cid: Int?, val lac: Int?, val arfcn: Int?, val bsic: Int?, val rssi: Int?, val ber: Int?,
)

/** A non-serving cell. [id] is PCI (NR/LTE), PSC (WCDMA) or BSIC (GSM). */
data class Neighbor(val rat: String, val arfcn: Int?, val id: Int?, val level: Int?, val quality: Int?) {
    override fun toString() = "$rat:${arfcn ?: "-"}/${id ?: "-"}/${level ?: "-"}"
}

data class RadioSnapshot(
    val time: Long,
    /** Summary technology: "5G SA", "5G NSA", "LTE", "LTE-A", "3G", "2G", "No service". */
    val tech: String,
    val dataNetwork: String,
    val overrideNetwork: String,
    val operator: String,
    val mccMnc: String,
    val nr: NrCell?,
    val lte: LteCell?,
    val wcdma: WcdmaCell?,
    val gsm: GsmCell?,
    val neighbors: List<Neighbor>,
) {
    /** Best coverage level in dBm: NR SS-RSRP, else LTE RSRP, else WCDMA RSCP, else GSM RSSI. */
    val level: Int? get() = nr?.ssRsrp ?: lte?.rsrp ?: wcdma?.rscp ?: gsm?.rssi
    val sinr: Int? get() = nr?.ssSinr ?: lte?.sinr
}

data class VideoStats(
    val time: Long,
    val state: String,
    val width: Int?,
    val height: Int?,
    /** YouTube's own bandwidth estimate from "Stats for nerds", when available. */
    val bandwidthKbps: Int?,
    val bufferSec: Double?,
    val droppedFrames: Int?,
    val totalFrames: Int?,
    val stalls: Int,
    val stallSec: Double,
    val startupMs: Long?,
    val codecs: String?,
    val positionSec: Double?,
) {
    val resolution: String get() = if (width != null && height != null && height > 0) "${width}x$height" else "-"
    val stalled: Boolean get() = state == "buffering"
}

data class Sample(
    val time: Long,
    val location: Location?,
    val radio: RadioSnapshot,
    val video: VideoStats?,
    val callId: Int?,
)

data class MapPoint(
    val time: Long,
    val lat: Double,
    val lon: Double,
    val level: Int?,
    val sinr: Int?,
    val tech: String,
    val videoHeight: Int?,
    val videoStalled: Boolean,
    val callId: Int?,
)

data class AudioMetrics(
    val durationSec: Double,
    val speechRatio: Double,
    val speechLevelDb: Double,
    val noiseFloorDb: Double,
    val snrDb: Double,
    val clippingPct: Double,
    val dropouts: Int,
)

data class CallResult(
    val id: Int,
    val dialTime: Long,
    val lat: Double?,
    val lon: Double?,
    val status: String,
    val place: String? = null,
    val deviceDurationSec: Double? = null,
    val setupSec: Double? = null,
    val twilioSid: String? = null,
    val recordSec: Int? = null,
    val avgLevel: Double? = null,
    val minLevel: Int? = null,
    val avgSinr: Double? = null,
    val techs: String? = null,
    val audio: AudioMetrics? = null,
    val insights: Map<String, String> = emptyMap(),
    val mos: Double? = null,
    val mosSource: String? = null,
    val intelligibility: Int? = null,
    val noise: String? = null,
    val choppiness: String? = null,
    val distortion: String? = null,
    val transcript: String? = null,
    val summary: String? = null,
    val note: String? = null,
) {
    val final: Boolean get() = status !in IN_PROGRESS
    val failed: Boolean get() = status in FAILED

    companion object {
        val IN_PROGRESS = setOf("DIALING", "IN_CALL", "ANALYZING")
        val FAILED = setOf("SETUP_FAIL", "NO_DIAL", "DIAL_ERROR", "DROPPED")
    }
}
