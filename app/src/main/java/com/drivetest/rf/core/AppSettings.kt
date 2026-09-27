package com.drivetest.rf.core

import android.content.Context

data class AppSettings(
    val twilioNumber: String = "",
    val twilioAccountSid: String = "",
    val twilioAuthToken: String = "",
    val openRouterKey: String = "",
    val openRouterModel: String = "google/gemini-2.5-flash",
    val youtubeUrl: String = "https://www.youtube.com/watch?v=LXb3EKWsInQ",
    val youtubeWatchPage: Boolean = false,
    /** 0 = run until stopped. */
    val sessionMinutes: Int = 120,
    val sampleIntervalMs: Int = 1000,
    val callsEnabled: Boolean = true,
    val videoEnabled: Boolean = true,
    val callIntervalSec: Int = 30,
    /** Must match maxLength of the <Record> verb in the Twilio TwiML Bin. */
    val recordSec: Int = 20,
    val speechStimulus: Boolean = true,
    val deleteRecordings: Boolean = false,
) {
    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("twilioNumber", twilioNumber.trim())
            .putString("twilioAccountSid", twilioAccountSid.trim())
            .putString("twilioAuthToken", twilioAuthToken.trim())
            .putString("openRouterKey", openRouterKey.trim())
            .putString("openRouterModel", openRouterModel.trim())
            .putString("youtubeUrl", youtubeUrl.trim())
            .putBoolean("youtubeWatchPage", youtubeWatchPage)
            .putInt("sessionMinutes", sessionMinutes)
            .putInt("sampleIntervalMs", sampleIntervalMs)
            .putBoolean("callsEnabled", callsEnabled)
            .putBoolean("videoEnabled", videoEnabled)
            .putInt("callIntervalSec", callIntervalSec)
            .putInt("recordSec", recordSec)
            .putBoolean("speechStimulus", speechStimulus)
            .putBoolean("deleteRecordings", deleteRecordings)
            .apply()
    }

    companion object {
        private const val PREFS = "drive_test_settings"

        fun load(ctx: Context): AppSettings {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = AppSettings()
            return AppSettings(
                twilioNumber = p.getString("twilioNumber", d.twilioNumber)!!,
                twilioAccountSid = p.getString("twilioAccountSid", d.twilioAccountSid)!!,
                twilioAuthToken = p.getString("twilioAuthToken", d.twilioAuthToken)!!,
                openRouterKey = p.getString("openRouterKey", d.openRouterKey)!!,
                openRouterModel = p.getString("openRouterModel", d.openRouterModel)!!,
                youtubeUrl = p.getString("youtubeUrl", d.youtubeUrl)!!,
                youtubeWatchPage = p.getBoolean("youtubeWatchPage", d.youtubeWatchPage),
                sessionMinutes = p.getInt("sessionMinutes", d.sessionMinutes),
                sampleIntervalMs = p.getInt("sampleIntervalMs", d.sampleIntervalMs),
                callsEnabled = p.getBoolean("callsEnabled", d.callsEnabled),
                videoEnabled = p.getBoolean("videoEnabled", d.videoEnabled),
                callIntervalSec = p.getInt("callIntervalSec", d.callIntervalSec),
                recordSec = p.getInt("recordSec", d.recordSec),
                speechStimulus = p.getBoolean("speechStimulus", d.speechStimulus),
                deleteRecordings = p.getBoolean("deleteRecordings", d.deleteRecordings),
            )
        }
    }
}
