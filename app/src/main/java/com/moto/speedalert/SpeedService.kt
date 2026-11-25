package com.moto.speedalert

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/**
 * Foreground service that keeps reading GPS with the screen off, works out the speed
 * limit and beeps when over limit + tolerance.
 */
class SpeedService : Service(), LocationListener {

    private lateinit var settings: Settings
    private lateinit var beeper: Beeper
    private lateinit var cameras: CameraDb
    private lateinit var floating: FloatingWindow
    @Volatile private var roadDb: RoadDb? = null
    @Volatile private var roadDbError: String? = null
    private val detector = OverspeedDetector()
    private val resolver = LimitResolver()
    private val cameraAlertedAt = HashMap<Camera, Long>()
    private var lastFix: Location? = null
    private var lastHeading: Double? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotifText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        // ~1-2 s to unpack and index all of Taiwan; do it off the main thread.
        Thread {
            try {
                roadDb = assets.open("roads.bin").use { RoadDb.parse(it.readBytes()) }
            } catch (e: Exception) {
                roadDbError = e.message ?: e.javaClass.simpleName
                android.util.Log.e("SpeedService", "roads.bin failed", e)
            }
        }.start()
        beeper = Beeper(this).also { it.volume = settings.volume / 100f }
        cameras = assets.open("cameras.csv").reader(Charsets.UTF_8).use { CameraDb.parse(it) }
        floating = FloatingWindow(this, settings)
        AppState.listen(floating.listener)
        createChannel()
    }

    @SuppressLint("MissingPermission", "WakelockTimeout")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_SETTINGS_CHANGED) {
            beeper.volume = settings.volume / 100f
            AppState.update(AppState.state) // re-evaluate the floating window
            return START_STICKY
        }
        startForeground(NOTIF_ID, buildNotification("準備中，等待 GPS 定位…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)

        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MotoSpeedAlert:ride")
                .also { it.acquire() }
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500L, 0f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            AppState.update(RideState(status = "沒有定位權限"))
            stopSelf()
            return START_NOT_STICKY
        }
        AppState.update(AppState.state.copy(running = true, hasFix = false, status = "等待 GPS 定位…"))
        return START_STICKY
    }

    override fun onLocationChanged(loc: Location) {
        val now = System.currentTimeMillis()
        val speedKmh = if (loc.hasSpeed()) loc.speed * 3.6 else speedFromPrevious(loc)
        val heading = headingOf(loc, speedKmh)
        lastFix = loc

        val cameraHit = cameraZone(loc, heading, now)

        val roadMatch = if (settings.autoLimit) {
            Roads.match(roadDb?.near(loc.latitude, loc.longitude) ?: emptyList(), loc.latitude, loc.longitude, heading)
        } else null

        val resolved = resolver.resolve(
            now = now,
            auto = settings.autoLimit,
            defaultLimit = settings.defaultLimit,
            camera = cameraHit,
            // In a camera zone the camera's posted limit is the one that counts.
            cameraLimitRange = settings.cameraDistance.toDouble() + 50,
            road = roadMatch,
        )
        val threshold = resolved.limit + settings.tolerance
        val over = detector.update(speedKmh, threshold)

        if (cameraHit != null && settings.cameraAlert) {
            val prev = cameraAlertedAt[cameraHit.camera]
            if (prev == null || now - prev > CAMERA_REALERT_MS) {
                cameraAlertedAt[cameraHit.camera] = now
                beeper.cameraAlert()
            }
        }
        val alarming = over && (!settings.cameraOnlyAlarm || cameraHit != null)
        beeper.setOverspeed(alarming)

        val status = when {
            !settings.autoLimit -> "手動限速模式"
            roadDbError != null -> "道路資料讀取失敗（$roadDbError），使用預設限速"
            roadDb == null -> "載入道路資料中…"
            else -> "GPS 精度 ±${loc.accuracy.toInt()} m"
        }
        AppState.update(
            RideState(
                running = true,
                hasFix = true,
                speedKmh = speedKmh,
                limit = resolved.limit,
                limitSource = resolved.source,
                threshold = threshold,
                overspeed = over,
                alarming = alarming,
                cameraOnly = settings.cameraOnlyAlarm,
                roadName = roadMatch?.road?.name,
                camera = cameraHit,
                status = status,
            )
        )
        val text = "時速 ${speedKmh.toInt()}｜限速 ${resolved.limit}" +
            (if (cameraHit != null) "｜📷 測速照相 ${cameraHit.distance.toInt()} m" else "") +
            (if (alarming) "｜超速！" else "")
        if (text != lastNotifText) {
            lastNotifText = text
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private var lastCamera: Camera? = null
    private var lastCameraAt = 0L
    /** Cameras we have just ridden past, so GPS jitter cannot bring them back. */
    private val passedAt = HashMap<Camera, Long>()

    /**
     * The speed camera we are approaching, if any. The zone ends the moment we pass the
     * camera. While still before it, a camera that the noisy GPS heading briefly loses
     * is kept for a few seconds so the zone (and the beeping) does not flicker.
     */
    private fun cameraZone(loc: Location, heading: Double?, now: Long): CameraHit? {
        val range = settings.cameraDistance.toDouble()
        passedAt.entries.removeAll { now - it.value > PASSED_FORGET_MS }
        val hit = heading?.let { h ->
            cameras.nearestAhead(loc.latitude, loc.longitude, h, range) { it in passedAt }
        }
        val prev = lastCamera
        if (prev != null && prev != hit?.camera && heading != null && isPassed(loc, heading, prev)) {
            passedAt[prev] = now // just rode past it: zone over, immediately
            lastCamera = null
        }
        if (hit != null) {
            lastCamera = hit.camera
            lastCameraAt = now
            return hit
        }
        val kept = lastCamera ?: return null
        if (now - lastCameraAt > CAMERA_STICKY_MS) {
            lastCamera = null
            return null
        }
        val d = Geo.distance(loc.latitude, loc.longitude, kept.lat, kept.lon)
        return if (d <= range) CameraHit(kept, d) else null
    }

    /** The camera is behind us (more than 90 degrees off our heading). */
    private fun isPassed(loc: Location, heading: Double, c: Camera): Boolean =
        Geo.angleDiff(Geo.bearing(loc.latitude, loc.longitude, c.lat, c.lon), heading) > 90

    private fun speedFromPrevious(loc: Location): Double {
        val prev = lastFix ?: return 0.0
        val dt = (loc.time - prev.time) / 1000.0
        if (dt <= 0) return 0.0
        return prev.distanceTo(loc) / dt * 3.6
    }

    /** Travel direction; null when too slow for it to mean anything. */
    private fun headingOf(loc: Location, speedKmh: Double): Double? {
        if (speedKmh < MIN_HEADING_SPEED) return lastHeading.takeIf { speedKmh > 1 }
        val h = if (loc.hasBearing()) {
            loc.bearing.toDouble()
        } else {
            val prev = lastFix ?: return lastHeading
            if (prev.distanceTo(loc) < 3) return lastHeading
            Geo.bearing(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
        }
        lastHeading = h
        return h
    }

    override fun onProviderDisabled(provider: String) {
        AppState.update(AppState.state.copy(hasFix = false, status = "GPS 已關閉，請開啟定位"))
    }

    override fun onProviderEnabled(provider: String) {}

    override fun onDestroy() {
        (getSystemService(Context.LOCATION_SERVICE) as LocationManager).removeUpdates(this)
        AppState.unlisten(floating.listener)
        floating.release()
        beeper.release()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        detector.reset()
        resolver.reset()
        AppState.update(RideState(running = false, status = "已停止"))
        super.onDestroy()
    }

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL, "測速提醒", NotificationManager.IMPORTANCE_LOW)
        ch.setSound(null, null)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, SpeedService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("機車超速提醒 運作中")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止", stop).build())
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.moto.speedalert.STOP"
        const val ACTION_SETTINGS_CHANGED = "com.moto.speedalert.SETTINGS"
        private const val CHANNEL = "ride"
        private const val NOTIF_ID = 1
        private const val MIN_HEADING_SPEED = 5.0 // km/h
        private const val CAMERA_STICKY_MS = 3_000L
        private const val PASSED_FORGET_MS = 2 * 60_000L
        private const val CAMERA_REALERT_MS = 5 * 60_000L
    }
}
