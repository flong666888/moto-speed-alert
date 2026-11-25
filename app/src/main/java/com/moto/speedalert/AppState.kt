package com.moto.speedalert

import android.os.Handler
import android.os.Looper

/** Snapshot of what the service knows, shown on screen. */
data class RideState(
    val running: Boolean = false,
    val hasFix: Boolean = false,
    val speedKmh: Double = 0.0,
    val limit: Int = 50,
    val limitSource: LimitSource = LimitSource.DEFAULT,
    val threshold: Int = 57,
    /** At or above the threshold (shown on screen even when silent). */
    val overspeed: Boolean = false,
    /** Beeping right now (over the threshold, and in a camera zone if camera-only mode). */
    val alarming: Boolean = false,
    val cameraOnly: Boolean = true,
    val roadName: String? = null,
    val camera: CameraHit? = null,
    val status: String = "",
)

/** Tiny in-process bus between the service and the activity. */
object AppState {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<(RideState) -> Unit>()

    @Volatile var state = RideState()
        private set

    /** True while MainActivity is on screen (the floating window hides then). */
    @Volatile var uiVisible = false
        private set

    fun setUiVisible(v: Boolean) {
        uiVisible = v
        update(state)
    }

    fun update(s: RideState) {
        state = s
        main.post { listeners.toList().forEach { it(s) } }
    }

    fun listen(l: (RideState) -> Unit) {
        listeners += l
        l(state)
    }

    fun unlisten(l: (RideState) -> Unit) {
        listeners -= l
    }
}
