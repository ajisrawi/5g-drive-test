# 5G Drive Test (Android)

A drive-test app that logs radio, voice and video quality along your route. It plots the results on Google Maps and exports CSV and KML.

**Download:** [`apk/DriveTest5G-v1.0-debug.apk`](apk/DriveTest5G-v1.0-debug.apk) or the [latest release](../../releases/latest). This is a debug build with no Maps key, so to see the map, build it yourself with your own key (see below). Status: v1.0 compiles but has not been field-tested yet.

| Test | How it works |
|---|---|
| **Radio** (every 1 s) | Reads `TelephonyManager` cell info for **5G NR** (SA and NSA): SS-RSRP/RSRQ/SINR, CSI-RSRP/RSRQ/SINR, NCI, PCI, TAC, NR-ARFCN and band. For **LTE** it reads RSRP, RSRQ, SINR, RSSI, CQI, TA, ECI, PCI, EARFCN, band and bandwidth. It also reads WCDMA and GSM cells, neighbours, the RAT and the 5G icon state (NR_NSA / NR_ADVANCED). |
| **Voice** (loop) | The app places a real cellular call to your Twilio number. Twilio records the uplink for N seconds and then hangs up. Afterwards the app pulls the recording and Voice Insights from the Twilio API, measures SNR, dropouts and clipping locally, and sends the audio to an audio-capable LLM on **OpenRouter** for a P.800-style MOS. |
| **Video** (continuous) | Plays a YouTube video muted and looping in a WebView. Every second it reads the player's **Stats for nerds** (`getStatsForNerds()`), the player state and the HTML5 video element. It records resolution, estimated bandwidth, buffer health, stalls, stall time, startup time and dropped frames. |
| **Map** | Google Maps route coloured by RSRP, SINR, RAT, video resolution or voice. Call markers are coloured by MOS or failure. |

Only Android can do this, because iOS gives apps no access to cell or signal data. You need Android 12 or later with a SIM, and Location must be ON.

## 1. Build

1. Get a Google Maps key: Google Cloud console → enable **Maps SDK for Android** → create an API key.
2. Put it in `local.properties`: `MAPS_API_KEY=AIza...`
3. Run `gradlew assembleDebug`, or open the folder in Android Studio and press Run. The APK is `app/build/outputs/apk/debug/app-debug.apk`.

## 2. Twilio setup (voice test)

Create two **TwiML Bins** (Console → Develop → TwiML Bins).

**Bin A: `drive-test-hangup`**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response><Hangup/></Response>
```

**Bin B: `drive-test-record`**. Replace the `action` URL with Bin A's URL. `maxLength` must equal *Record length* in the app settings (default 20).
```xml
<?xml version="1.0" encoding="UTF-8"?>
<Response>
  <Pause length="1"/>
  <Record maxLength="20" timeout="60" playBeep="false" trim="do-not-trim"
          action="https://handler.twilio.com/twiml/EHxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"/>
  <Hangup/>
</Response>
```
Then go to Phone Numbers → your number → **A call comes in** → TwiML Bin → `drive-test-record`.

The `action` URL is required. Without it, Twilio re-runs the same bin after `<Record>` and the call never ends.

Optional: turn on **Voice Insights** to get jitter, packet loss, PDD and "disconnected by" in the results.

In the app's Settings, enter the Twilio number, Account SID and Auth Token. Optionally add an OpenRouter key and model. The default model is `google/gemini-2.5-flash`, and any model that accepts `input_audio` works.

### How each call is classified
| Status | Meaning |
|---|---|
| `OK` | The call connected, and Twilio recorded at least `recordSec - 3` seconds. |
| `DROPPED` | The recording or call ended early: the network dropped the call. |
| `SETUP_FAIL` | The phone dialed but Twilio never saw the call. |
| `NO_DIAL` / `DIAL_ERROR` | The phone could not start the call (no service, or a permission problem). |
| `ANALYSIS_ERROR` | The call was fine, but fetching from Twilio or OpenRouter failed. See the note. |

The Voice tab shows CSSR, DCR, average MOS and average setup time. Setup time is approximate: it is Twilio's start time minus the phone's dial time, at 1-second resolution.

## 3. Running a drive
1. Grant all permissions, and on a dual-SIM phone set a default SIM for calls.
2. Mount the phone with the app in the foreground. The screen stays on.
3. Press **START**. The session runs for the set duration (default 120 min; `0` means until you stop it).
4. During each call the system in-call screen appears for about 25 s. Most phones return to the app afterwards.
5. Press **STOP**, then use **Share** to export `samples.csv`, `calls.csv` and `route.kml` (which opens in Google Earth).

Files are saved in `Android/data/com.drivetest.rf/files/sessions/<timestamp>/`.

## Limitations (Android platform limits)
- **Only uplink voice is scored.** Android blocks apps from recording call audio, so the phone cannot measure downlink. Twilio records what the phone sends.
- **The speech stimulus is experimental.** The app switches to speakerphone and reads Harvard test sentences with TTS so the microphone picks them up. On some phones the echo canceller suppresses this audio. If the AI reports `speech_present=false`, read the sentences aloud yourself or play them from a second device.
- **The AI MOS is an estimate**, not a calibrated POLQA/PESQ score. It is useful for comparing locations on a route.
- **Some NSA phones don't report the NR cell's identity** (PCI/NCI). In that case the app still logs NR signal strength from `SignalStrength`.
- **Video player mode:** if the embed player shows "Error 153" or "video unavailable", pick another video or switch Settings to *desktop watch page*.
