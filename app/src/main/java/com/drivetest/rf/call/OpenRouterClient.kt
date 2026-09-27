package com.drivetest.rf.call

import android.util.Base64
import com.drivetest.rf.core.AudioMetrics
import com.drivetest.rf.core.fmt
import com.drivetest.rf.core.optDoubleOrNull
import com.drivetest.rf.core.optStringOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Sends the call recording to an audio-capable LLM on OpenRouter and gets a P.800-style rating. */
class OpenRouterClient(private val apiKey: String, private val model: String) {
    data class Rating(
        val mos: Double?,
        val intelligibility: Int?,
        val noise: String?,
        val choppiness: String?,
        val distortion: String?,
        val speechPresent: Boolean?,
        val transcript: String?,
        val summary: String?,
    )

    val configured = apiKey.isNotBlank()
    private val http = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).build()

    suspend fun rateCall(
        wav: ByteArray,
        metrics: AudioMetrics?,
        insights: Map<String, String>,
        references: List<String>,
        radioContext: String,
    ): Rating = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(metrics, insights, references, radioContext)
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "input_audio").put("input_audio",
                JSONObject().put("data", Base64.encodeToString(wav, Base64.NO_WRAP)).put("format", "wav")))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))

        val req = Request.Builder()
            .url("https://openrouter.ai/api/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .header("X-Title", "5G Drive Test")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        val text = http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("OpenRouter HTTP ${r.code}: ${s.take(300)}")
            s
        }
        val root = JSONObject(text)
        root.optJSONObject("error")?.let { throw IOException("OpenRouter: ${it.optString("message")}") }
        val answer = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
        val start = answer.indexOf('{')
        val end = answer.lastIndexOf('}')
        if (start < 0 || end <= start) throw IOException("Model did not return JSON: ${answer.take(200)}")
        val o = JSONObject(answer.substring(start, end + 1))
        Rating(
            mos = o.optDoubleOrNull("mos")?.coerceIn(1.0, 5.0),
            intelligibility = o.optDoubleOrNull("intelligibility_pct")?.toInt(),
            noise = o.optStringOrNull("noise"),
            choppiness = o.optStringOrNull("choppiness"),
            distortion = o.optStringOrNull("distortion"),
            speechPresent = if (o.has("speech_present")) o.optBoolean("speech_present") else null,
            transcript = o.optStringOrNull("transcript"),
            summary = o.optStringOrNull("summary"),
        )
    }

    private fun buildPrompt(
        m: AudioMetrics?,
        insights: Map<String, String>,
        references: List<String>,
        radioContext: String,
    ) = buildString {
        appendLine("You are a telecom voice-quality analyst evaluating a mobile network drive test.")
        appendLine("The attached WAV is the UPLINK of a cellular voice call (mobile -> PSTN), recorded at the far end by Twilio (narrow-band, 8 kHz).")
        if (references.isNotEmpty()) {
            appendLine("During the call the handset played these reference sentences from its loudspeaker (they can be faint or missing if the phone's echo canceller suppressed them):")
            references.forEach { appendLine("- $it") }
        } else {
            appendLine("No reference speech was injected; judge whatever speech or background audio is present.")
        }
        if (m != null) {
            appendLine("Objective measurements of the same audio: duration ${m.durationSec.fmt(1)} s, speech activity ${(m.speechRatio * 100).fmt(0)} %, " +
                "speech level ${m.speechLevelDb.fmt(1)} dBFS, noise floor ${m.noiseFloorDb.fmt(1)} dBFS, SNR ${m.snrDb.fmt(1)} dB, " +
                "clipping ${m.clippingPct.fmt(2)} %, mid-speech dropouts ${m.dropouts}.")
        }
        if (insights.isNotEmpty()) {
            appendLine("Twilio Voice Insights metrics: " + insights.entries.joinToString("; ") { "${it.key}=${it.value}" })
        }
        appendLine("Radio conditions during the call: $radioContext")
        appendLine()
        appendLine("Rate the audio as an ITU-T P.800 ACR listening panel would (1=bad, 2=poor, 3=fair, 4=good, 5=excellent), " +
            "considering choppiness/dropouts, robotic or metallic codec artifacts, muting, noise, clipping and intelligibility. " +
            "Clean narrow-band PSTN speech should score about 4.0-4.2. If there is no speech at all, set speech_present=false " +
            "and rate only channel artifacts (mos null if the audio is pure silence).")
        appendLine("Reply with ONLY a JSON object, no markdown:")
        append("{\"mos\": number, \"intelligibility_pct\": number, \"noise\": \"none|low|medium|high\", " +
            "\"choppiness\": \"none|low|medium|high\", \"distortion\": \"none|low|medium|high\", " +
            "\"speech_present\": boolean, \"transcript\": string, \"summary\": string}")
    }
}
