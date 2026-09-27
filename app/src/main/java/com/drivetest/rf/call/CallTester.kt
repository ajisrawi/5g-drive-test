package com.drivetest.rf.call

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import com.drivetest.rf.core.AppScope
import com.drivetest.rf.core.AppSettings
import com.drivetest.rf.core.CallResult
import com.drivetest.rf.core.DriveTestState
import com.drivetest.rf.core.Sample
import com.drivetest.rf.core.fmt
import com.drivetest.rf.export.SessionLogger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Repeatedly calls the Twilio test number over the cellular network, then (asynchronously)
 * pulls the Twilio-side recording + metrics and scores the call.
 *
 * Twilio number's TwiML must answer, record for [AppSettings.recordSec] seconds and hang up
 * (see README). A recording shorter than expected means the call dropped.
 */
@SuppressLint("MissingPermission")
class CallTester(
    private val ctx: Context,
    private val settings: AppSettings,
    private val logger: SessionLogger,
) {
    private val tm = ctx.getSystemService(TelephonyManager::class.java)
    private val telecom = ctx.getSystemService(TelecomManager::class.java)
    private val executor = Executors.newSingleThreadExecutor()
    private val callState = MutableStateFlow(TelephonyManager.CALL_STATE_IDLE)
    private val stateListener = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) { callState.value = state }
    }
    private val twilio = TwilioClient(settings.twilioAccountSid, settings.twilioAuthToken)
    private val ai = OpenRouterClient(settings.openRouterKey, settings.openRouterModel)
    private val stimulus = if (settings.speechStimulus) SpeechStimulus(ctx) else null

    /** Radio statistics accumulated while a call is active. */
    private class Acc(val id: Int) {
        var levelSum = 0.0; var levelN = 0; var levelMin: Int? = null
        var sinrSum = 0.0; var sinrN = 0
        val techs = linkedSetOf<String>()
    }
    @Volatile private var acc: Acc? = null

    init {
        tm.registerTelephonyCallback(executor, stateListener)
    }

    fun close() {
        runCatching { tm.unregisterTelephonyCallback(stateListener) }
        stimulus?.shutdown()
        executor.shutdown()
    }

    fun onSample(s: Sample) {
        val a = acc ?: return
        if (s.callId != a.id) return
        synchronized(a) {
            s.radio.level?.let { a.levelSum += it; a.levelN++; a.levelMin = minOf(a.levelMin ?: it, it) }
            s.radio.sinr?.let { a.sinrSum += it; a.sinrN++ }
            a.techs += s.radio.tech
        }
    }

    suspend fun runLoop() {
        delay(3_000)
        var id = 0
        while (currentCoroutineContext().isActive) {
            id++
            runOne(id)
            delay(settings.callIntervalSec * 1000L)
        }
    }

    private suspend fun runOne(id: Int) {
        // Don't interfere with a real call the driver is on.
        callState.first { it == TelephonyManager.CALL_STATE_IDLE }

        val loc = DriveTestState.location.value
        val dialTime = System.currentTimeMillis()
        var r = CallResult(id = id, dialTime = dialTime, lat = loc?.latitude, lon = loc?.longitude, status = "DIALING")
        DriveTestState.upsertCall(r)
        DriveTestState.event("Call #$id dialing ${settings.twilioNumber}")
        acc = Acc(id)
        DriveTestState.activeCallId.value = id

        val spoken = mutableListOf<String>()
        var endedNormally = true
        var offhookAt = 0L
        try {
            try {
                telecom.placeCall(Uri.fromParts("tel", settings.twilioNumber, null), Bundle())
            } catch (e: Exception) {
                finish(r.copy(status = "DIAL_ERROR", note = e.message))
                return
            }
            val off = withTimeoutOrNull(20_000) { callState.first { it == TelephonyManager.CALL_STATE_OFFHOOK } }
            if (off == null) {
                finish(r.copy(status = "NO_DIAL", note = "Phone never went off-hook (no SIM/service or dialer blocked)"))
                return
            }
            offhookAt = System.currentTimeMillis()
            r = r.copy(status = "IN_CALL")
            DriveTestState.upsertCall(r)

            endedNormally = coroutineScope {
                val stim = stimulus?.let { st -> launch { delay(5_000); st.speakLoop(spoken) } }
                val idle = withTimeoutOrNull((settings.recordSec + 60) * 1000L) {
                    callState.first { it == TelephonyManager.CALL_STATE_IDLE }
                }
                stim?.cancel()
                idle != null
            }
            if (!endedNormally) {
                @Suppress("DEPRECATION")
                runCatching { telecom.endCall() }
                withTimeoutOrNull(10_000) { callState.first { it == TelephonyManager.CALL_STATE_IDLE } }
            }
        } finally {
            stimulus?.restoreRouting()
            DriveTestState.activeCallId.value = null
        }

        val a = acc
        acc = null
        if (a != null) synchronized(a) {
            r = r.copy(
                avgLevel = if (a.levelN > 0) a.levelSum / a.levelN else null,
                minLevel = a.levelMin,
                avgSinr = if (a.sinrN > 0) a.sinrSum / a.sinrN else null,
                techs = a.techs.joinToString("/"),
            )
        }
        r = r.copy(
            status = "ANALYZING",
            deviceDurationSec = (System.currentTimeMillis() - offhookAt) / 1000.0,
            note = if (endedNormally) null else "Twilio did not hang up in time – call force-ended",
        )
        DriveTestState.upsertCall(r)
        val refs = synchronized(spoken) { spoken.toList() }
        AppScope.io.launch { analyze(r, refs) }
    }

    private suspend fun analyze(start: CallResult, refs: List<String>) {
        var r = start.copy(place = geocode(start.lat, start.lon))
        try {
            if (!twilio.configured) {
                val ok = (r.deviceDurationSec ?: 0.0) >= settings.recordSec
                finish(r.copy(status = if (ok) "OK" else "DROPPED",
                    note = listOfNotNull(r.note, "Twilio API not configured – device-side result only").joinToString("; ")))
                return
            }
            delay(5_000)
            val call = twilio.findCall(settings.twilioNumber, r.dialTime)
            if (call == null) {
                finish(r.copy(status = "SETUP_FAIL", note = listOfNotNull(r.note, "Call never reached Twilio").joinToString("; ")))
                return
            }
            r = r.copy(
                twilioSid = call.sid,
                setupSec = call.startTime?.let { max(0.0, (it - r.dialTime) / 1000.0) },
            )
            DriveTestState.upsertCall(r)

            val rec = twilio.waitForRecording(call.sid)
            if (rec == null) {
                val shortCall = (call.durationSec ?: 0) < settings.recordSec
                finish(r.copy(status = if (shortCall) "DROPPED" else "NO_RECORDING",
                    note = listOfNotNull(r.note, "No recording (Twilio status ${call.status})").joinToString("; ")))
                return
            }
            val wav = twilio.downloadWav(rec.sid)
            val metrics = WavAnalyzer.analyze(wav)
            val dropped = rec.durationSec < settings.recordSec - 3
            r = r.copy(status = if (dropped) "DROPPED" else "OK", recordSec = rec.durationSec, audio = metrics)
            DriveTestState.upsertCall(r)

            val insights = twilio.insightsSummary(call.sid)
            r = r.copy(insights = insights)

            if (ai.configured) {
                val radioCtx = "tech ${r.techs ?: "?"}, avg level ${r.avgLevel?.fmt(1) ?: "?"} dBm, " +
                    "min ${r.minLevel ?: "?"} dBm, avg SINR ${r.avgSinr?.fmt(1) ?: "?"} dB"
                val q = ai.rateCall(wav, metrics, insights, refs, radioCtx)
                r = r.copy(
                    mos = q.mos, mosSource = "AI:${settings.openRouterModel}",
                    intelligibility = q.intelligibility, noise = q.noise, choppiness = q.choppiness,
                    distortion = q.distortion, transcript = q.transcript, summary = q.summary,
                )
            } else if (metrics != null) {
                r = r.copy(mos = WavAnalyzer.heuristicMos(metrics), mosSource = "heuristic")
            }
            if (settings.deleteRecordings) twilio.deleteRecording(rec.sid)
        } catch (e: Exception) {
            r = r.copy(
                status = if (r.status == "ANALYZING") "ANALYSIS_ERROR" else r.status,
                note = listOfNotNull(r.note, "Analysis error: ${e.message}").joinToString("; "),
            )
        }
        finish(r)
    }

    private fun finish(r: CallResult) {
        DriveTestState.upsertCall(r)
        logger.writeCall(r)
        DriveTestState.event("Call #${r.id} ${r.status}" + (r.mos?.let { " MOS ${it.fmt(1)}" } ?: ""))
    }

    @Suppress("DEPRECATION")
    private fun geocode(lat: Double?, lon: Double?): String? {
        if (lat == null || lon == null || !Geocoder.isPresent()) return null
        return runCatching {
            Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()?.let { a ->
                listOfNotNull(a.thoroughfare, a.subLocality ?: a.locality).joinToString(", ")
                    .ifBlank { a.getAddressLine(0) }
            }
        }.getOrNull()
    }
}
