package com.drivetest.rf.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import com.drivetest.rf.MainActivity
import com.drivetest.rf.R
import com.drivetest.rf.call.CallTester
import com.drivetest.rf.core.AppSettings
import com.drivetest.rf.core.DriveTestState
import com.drivetest.rf.core.Sample
import com.drivetest.rf.export.SessionLogger
import com.drivetest.rf.radio.RadioCollector
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs a drive-test session: GPS + radio sampling, periodic voice calls, logging. */
class DriveTestService : Service() {

    companion object {
        private const val ACTION_START = "com.drivetest.rf.START"
        private const val ACTION_STOP = "com.drivetest.rf.STOP"
        private const val CHANNEL_ID = "drive_test"
        private const val NOTIFICATION_ID = 7

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, DriveTestService::class.java).setAction(ACTION_START))
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, DriveTestService::class.java).setAction(ACTION_STOP))
        }
    }

    private var scope: CoroutineScope? = null
    private var radio: RadioCollector? = null
    private var logger: SessionLogger? = null
    private var callTester: CallTester? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { DriveTestState.location.value = it }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) stopSession() else startSession()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (scope != null) stopSession()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission")
    private fun startSession() {
        if (scope != null) return
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        val settings = AppSettings.load(this)
        val log = SessionLogger(this)
        logger = log
        DriveTestState.reset(log.dir.absolutePath)
        DriveTestState.event("Session started → ${log.dir.name}")

        val maxHold = if (settings.sessionMinutes > 0) settings.sessionMinutes * 60_000L + 600_000L else 24 * 3_600_000L
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DriveTest:session")
            .apply { acquire(maxHold) }

        val rc = RadioCollector(this).also { it.start() }
        radio = rc
        fused.requestLocationUpdates(
            LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L).setMinUpdateIntervalMillis(500L).build(),
            locationCallback,
            Looper.getMainLooper(),
        )

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        scope = sc
        sc.launch { sampleLoop(rc, log, settings) }

        if (settings.callsEnabled) {
            if (settings.twilioNumber.isBlank()) {
                DriveTestState.event("Voice test skipped: set the Twilio number in Settings")
            } else {
                runCatching { CallTester(this, settings, log) }
                    .onSuccess { ct -> callTester = ct; sc.launch { ct.runLoop() } }
                    .onFailure { DriveTestState.event("Voice test unavailable: ${it.message}") }
            }
        }
        if (settings.sessionMinutes > 0) {
            sc.launch {
                delay(settings.sessionMinutes * 60_000L)
                DriveTestState.event("Planned duration reached")
                withContext(Dispatchers.Main) { stopSession() }
            }
        }
        sc.launch {
            while (isActive) {
                delay(5_000)
                updateNotification()
            }
        }
    }

    private suspend fun sampleLoop(rc: RadioCollector, log: SessionLogger, s: AppSettings) {
        val interval = s.sampleIntervalMs.coerceAtLeast(500).toLong()
        while (currentCoroutineContext().isActive) {
            rc.requestRefresh()
            delay(interval)
            val now = System.currentTimeMillis()
            val snap = rc.snapshot()
            val loc = DriveTestState.location.value?.takeIf { now - it.time < 10_000 }
            val video = if (s.videoEnabled) DriveTestState.video.value?.takeIf { now - it.time < 5_000 } else null
            val sample = Sample(now, loc, snap, video, DriveTestState.activeCallId.value)
            DriveTestState.radio.value = snap
            log.writeSample(sample)
            callTester?.onSample(sample)
            DriveTestState.addSample(sample)
        }
    }

    private fun stopSession() {
        val sc = scope
        if (sc == null) {
            stopSelf()
            return
        }
        scope = null
        sc.cancel()
        callTester?.close()
        callTester = null
        radio?.stop()
        radio = null
        fused.removeLocationUpdates(locationCallback)
        logger?.close(DriveTestState.points.value, DriveTestState.calls.value)
        logger = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        DriveTestState.running.value = false
        DriveTestState.video.value = null
        DriveTestState.activeCallId.value = null
        DriveTestState.event("Session stopped – files saved")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Drive test", NotificationManager.IMPORTANCE_LOW))
    }

    private fun updateNotification() {
        val r = DriveTestState.radio.value
        val calls = DriveTestState.calls.value
        val ok = calls.count { it.status == "OK" }
        val text = buildString {
            append(r?.tech ?: "No radio")
            r?.level?.let { append(" · $it dBm") }
            append(" · ${DriveTestState.sampleCount.value} samples")
            if (calls.isNotEmpty()) append(" · calls $ok/${calls.size} OK")
        }
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Drive test running")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }
}
