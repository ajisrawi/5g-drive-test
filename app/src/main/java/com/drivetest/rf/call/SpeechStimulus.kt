package com.drivetest.rf.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.Locale

/**
 * Experimental uplink stimulus: switches the call to speakerphone and reads Harvard sentences
 * with TTS so the phone's microphone picks them up and sends them to Twilio.
 * Android does not let apps inject audio into a cellular call directly, and on some phones the
 * echo canceller removes this audio — in that case speak the sentences yourself or use a
 * second speaker.
 */
class SpeechStimulus(ctx: Context) {
    private val audio = ctx.getSystemService(AudioManager::class.java)
    @Volatile private var ready = false
    private val tts = TextToSpeech(ctx.applicationContext) { status -> ready = status == TextToSpeech.SUCCESS }
    private var configured = false

    suspend fun speakLoop(spoken: MutableList<String>) {
        routeToSpeaker()
        var i = 0
        try {
            while (currentCoroutineContext().isActive) {
                if (!ready) { delay(300); continue }
                if (!configured) {
                    tts.language = Locale.US
                    tts.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    configured = true
                }
                val sentence = SENTENCES[i % SENTENCES.size]
                i++
                synchronized(spoken) { spoken += sentence }
                tts.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "ref$i")
                delay(600L + sentence.length * 75L)
            }
        } finally {
            tts.stop()
        }
    }

    private fun routeToSpeaker() {
        runCatching {
            audio.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audio.setCommunicationDevice(it) }
        }
    }

    fun restoreRouting() {
        runCatching { audio.clearCommunicationDevice() }
    }

    fun shutdown() {
        restoreRouting()
        tts.shutdown()
    }

    private companion object {
        // Harvard sentences, list 1 (IEEE 1969) — standard speech-quality test material.
        val SENTENCES = listOf(
            "The birch canoe slid on the smooth planks.",
            "Glue the sheet to the dark blue background.",
            "It's easy to tell the depth of a well.",
            "These days a chicken leg is a rare dish.",
            "Rice is often served in round bowls.",
            "The juice of lemons makes fine punch.",
            "The box was thrown beside the parked truck.",
            "The hogs were fed chopped corn and garbage.",
            "Four hours of steady work faced us.",
            "A large size in stockings is hard to sell.",
        )
    }
}
