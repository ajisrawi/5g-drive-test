package com.drivetest.rf.call

import com.drivetest.rf.core.AudioMetrics
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min

/** Objective, reference-free audio measurements on the Twilio recording (PCM16 / µ-law / A-law WAV). */
object WavAnalyzer {

    fun analyze(wav: ByteArray): AudioMetrics? {
        val (pcm, rate) = decode(wav) ?: return null
        val frame = max(1, rate / 50) // 20 ms
        val nFrames = pcm.size / frame
        if (nFrames < 10) return null

        val db = DoubleArray(nFrames) { f ->
            var sum = 0.0
            for (i in f * frame until (f + 1) * frame) sum += pcm[i] * pcm[i]
            10 * log10(sum / frame + 1e-12)
        }
        val sorted = db.sorted()
        val noise = sorted[(nFrames * 0.10).toInt()]
        val speech = sorted[min(nFrames - 1, (nFrames * 0.95).toInt())]
        val threshold = max(noise + 10, -55.0)
        val active = BooleanArray(nFrames) { db[it] > threshold }
        val speechRatio = active.count { it }.toDouble() / nFrames
        val clipping = pcm.count { abs(it) >= 0.985f } * 100.0 / pcm.size

        // Dropouts: 60 ms – 1 s of near-digital silence with speech on both sides (muting / lost frames).
        var dropouts = 0
        var f = 0
        while (f < nFrames) {
            if (db[f] < -70) {
                val start = f
                while (f < nFrames && db[f] < -70) f++
                if (f - start in 3..50) {
                    val before = (max(0, start - 15) until start).any { active[it] }
                    val after = (f until min(nFrames, f + 15)).any { active[it] }
                    if (before && after) dropouts++
                }
            } else {
                f++
            }
        }
        return AudioMetrics(
            durationSec = pcm.size.toDouble() / rate,
            speechRatio = speechRatio,
            speechLevelDb = speech,
            noiseFloorDb = noise,
            snrDb = speech - noise,
            clippingPct = clipping,
            dropouts = dropouts,
        )
    }

    /** Rough fallback MOS when no AI key is configured. Null when no speech was captured. */
    fun heuristicMos(m: AudioMetrics): Double? {
        if (m.speechRatio < 0.05) return null
        var mos = 4.2
        mos -= min(1.5, m.dropouts * 10.0 / max(m.durationSec, 1.0) * 0.25)
        if (m.snrDb < 25) mos -= (25 - m.snrDb) * 0.06
        if (m.clippingPct > 0.5) mos -= 0.5
        return mos.coerceIn(1.0, 4.5)
    }

    private fun decode(wav: ByteArray): Pair<FloatArray, Int>? {
        if (wav.size < 44 || String(wav, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(wav, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        val bb = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        var format = 1
        var channels = 1
        var rate = 8000
        var bits = 16
        var pos = 12
        while (pos + 8 <= wav.size) {
            val id = String(wav, pos, 4, Charsets.US_ASCII)
            var size = bb.getInt(pos + 4)
            val body = pos + 8
            if (size < 0 || body + size > wav.size) size = wav.size - body
            when (id) {
                "fmt " -> {
                    format = bb.getShort(body).toInt() and 0xFFFF
                    channels = max(1, bb.getShort(body + 2).toInt())
                    rate = bb.getInt(body + 4)
                    bits = bb.getShort(body + 14).toInt()
                }
                "data" -> {
                    val out: FloatArray = when {
                        (format == 1 || format == 0xFFFE) && bits == 16 -> {
                            val stride = 2 * channels
                            FloatArray(size / stride) { i -> bb.getShort(body + i * stride) / 32768f }
                        }
                        format == 7 && bits == 8 ->
                            FloatArray(size / channels) { i -> ulaw(wav[body + i * channels].toInt() and 0xFF) / 32768f }
                        format == 6 && bits == 8 ->
                            FloatArray(size / channels) { i -> alaw(wav[body + i * channels].toInt() and 0xFF) / 32768f }
                        else -> return null
                    }
                    return out to rate
                }
            }
            pos = body + size + (size and 1)
        }
        return null
    }

    private fun ulaw(b: Int): Int {
        val u = b.inv() and 0xFF
        val exp = (u shr 4) and 7
        val s = (((u and 0x0F) shl 3) + 0x84 shl exp) - 0x84
        return if (u and 0x80 != 0) -s else s
    }

    private fun alaw(b: Int): Int {
        val a = b xor 0x55
        val exp = (a shr 4) and 7
        val mant = a and 0x0F
        val s = if (exp == 0) (mant shl 4) + 8 else ((mant shl 4) + 0x108) shl (exp - 1)
        return if (a and 0x80 != 0) s else -s
    }
}
