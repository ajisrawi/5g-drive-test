package com.drivetest.rf.call

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Minimal Twilio REST client: find the inbound test call, fetch its recording and Voice Insights. */
class TwilioClient(private val accountSid: String, authToken: String) {
    data class TwCall(val sid: String, val status: String, val startTime: Long?, val durationSec: Int?)
    data class TwRecording(val sid: String, val status: String, val durationSec: Int)

    val configured = accountSid.isNotBlank() && authToken.isNotBlank()

    private val auth = Credentials.basic(accountSid, authToken)
    private val base = "https://api.twilio.com/2010-04-01/Accounts/$accountSid"
    private val http = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    private fun execute(url: HttpUrl, delete: Boolean = false): ByteArray {
        val req = Request.Builder().url(url).header("Authorization", auth).apply { if (delete) delete() }.build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.bytes() ?: ByteArray(0)
            if (!r.isSuccessful) throw IOException("Twilio HTTP ${r.code}: ${String(body).take(200)}")
            return body
        }
    }

    private fun getJson(url: HttpUrl) = JSONObject(String(execute(url)))

    private fun parseDate(s: String?): Long? = if (s.isNullOrBlank() || s == "null") null else runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(s)?.time
    }.getOrNull()

    /**
     * Finds the inbound call to [to] that started closest after [dialTime] and waits for it to
     * finish. Returns null if Twilio never saw the call (i.e. setup failure on the network).
     */
    suspend fun findCall(to: String, dialTime: Long): TwCall? = withContext(Dispatchers.IO) {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(dialTime - 86_400_000L))
        var last: TwCall? = null
        repeat(12) { attempt ->
            val url = "$base/Calls.json".toHttpUrl().newBuilder()
                .addQueryParameter("To", to)
                .addQueryParameter("StartTime>", day)
                .addQueryParameter("PageSize", "20")
                .build()
            val arr = getJson(url).optJSONArray("calls") ?: JSONArray()
            val match = (0 until arr.length()).map { arr.getJSONObject(it) }
                .filter { it.optString("direction") == "inbound" }
                .map {
                    TwCall(it.getString("sid"), it.optString("status"),
                        parseDate(it.optString("start_time")), it.optString("duration").toIntOrNull())
                }
                .filter { it.startTime != null && it.startTime >= dialTime - 20_000 && it.startTime <= dialTime + 120_000 }
                .minByOrNull { it.startTime!! }
            if (match != null) {
                last = match
                if (match.status !in setOf("queued", "ringing", "in-progress")) return@withContext match
            } else if (attempt >= 5) {
                return@withContext null
            }
            delay(5_000)
        }
        last
    }

    /** Polls until the call's recording has finished processing (or ~2 minutes pass). */
    suspend fun waitForRecording(callSid: String): TwRecording? = withContext(Dispatchers.IO) {
        repeat(24) { attempt ->
            val url = "$base/Recordings.json".toHttpUrl().newBuilder().addQueryParameter("CallSid", callSid).build()
            val arr = getJson(url).optJSONArray("recordings")
            if (arr != null && arr.length() > 0) {
                val o = arr.getJSONObject(0)
                val rec = TwRecording(o.getString("sid"), o.optString("status"), o.optString("duration").toIntOrNull() ?: 0)
                if (rec.status == "completed") return@withContext rec
                if (rec.status in setOf("failed", "absent", "deleted")) return@withContext null
            } else if (attempt >= 6) {
                return@withContext null
            }
            delay(5_000)
        }
        null
    }

    suspend fun downloadWav(recordingSid: String): ByteArray = withContext(Dispatchers.IO) {
        execute("$base/Recordings/$recordingSid.wav".toHttpUrl())
    }

    suspend fun deleteRecording(recordingSid: String) = withContext(Dispatchers.IO) {
        runCatching { execute("$base/Recordings/$recordingSid.json".toHttpUrl(), delete = true) }
    }

    /**
     * Voice Insights call summary (jitter, packet loss, PDD, who hung up, quality tags).
     * Needs Voice Insights enabled on the Twilio account; returns an empty map otherwise.
     */
    suspend fun insightsSummary(callSid: String): Map<String, String> = withContext(Dispatchers.IO) {
        runCatching {
            val o = getJson("https://insights.twilio.com/v1/Voice/$callSid/Summary".toHttpUrl())
            val flat = linkedMapOf<String, String>()
            flatten("", o, flat)
            flat.filterKeys { INSIGHT_KEY.containsMatchIn(it) && !it.contains("url", ignoreCase = true) }
                .entries.take(30).associate { it.key to it.value }
        }.getOrDefault(emptyMap())
    }

    private fun flatten(prefix: String, v: Any?, out: MutableMap<String, String>) {
        when (v) {
            is JSONObject -> v.keys().forEach { k -> flatten(if (prefix.isEmpty()) k else "$prefix.$k", v.opt(k), out) }
            is JSONArray -> {
                val scalar = v.length() > 0 && v.opt(0) !is JSONObject && v.opt(0) !is JSONArray
                if (scalar) out[prefix] = (0 until v.length()).joinToString("|") { v.opt(it).toString() }
                else (0 until v.length()).forEach { flatten("$prefix[$it]", v.opt(it), out) }
            }
            null, JSONObject.NULL -> Unit
            else -> out[prefix] = v.toString()
        }
    }

    private companion object {
        val INSIGHT_KEY = Regex(
            "jitter|packet_loss|mos|latency|rtt|pdd|disconnected_by|last_sip_response|tags|codec|call_state",
            RegexOption.IGNORE_CASE,
        )
    }
}
