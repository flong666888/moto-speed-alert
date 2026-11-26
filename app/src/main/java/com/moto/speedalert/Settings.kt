package com.moto.speedalert

import android.content.Context

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** km/h above the limit at which beeping starts (50 + 7 = 57). */
    var tolerance: Int
        get() = prefs.getInt("tolerance", 7)
        set(v) = prefs.edit().putInt("tolerance", v).apply()

    /** Used when no road data is available, or always when [autoLimit] is off. */
    var defaultLimit: Int
        get() = prefs.getInt("defaultLimit", 50)
        set(v) = prefs.edit().putInt("defaultLimit", v).apply()

    var autoLimit: Boolean
        get() = prefs.getBoolean("autoLimit", true)
        set(v) = prefs.edit().putBoolean("autoLimit", v).apply()

    /** Beep for overspeed only while approaching a speed camera. */
    var cameraOnlyAlarm: Boolean
        get() = prefs.getBoolean("cameraOnlyAlarm", true)
        set(v) = prefs.edit().putBoolean("cameraOnlyAlarm", v).apply()

    var cameraAlert: Boolean
        get() = prefs.getBoolean("cameraAlert", true)
        set(v) = prefs.edit().putBoolean("cameraAlert", v).apply()

    /** Metres before a speed camera at which to chime. */
    var cameraDistance: Int
        get() = prefs.getInt("cameraDistance", 400)
        set(v) = prefs.edit().putInt("cameraDistance", v).apply()

    /** Floating speed bubble over other apps while riding. */
    var floatingWindow: Boolean
        get() = prefs.getBoolean("floatingWindow", true)
        set(v) = prefs.edit().putBoolean("floatingWindow", v).apply()

    /** Last dragged bubble position in pixels; -1 = default. */
    var overlayX: Int
        get() = prefs.getInt("overlayX", -1)
        set(v) = prefs.edit().putInt("overlayX", v).apply()

    var overlayY: Int
        get() = prefs.getInt("overlayY", -1)
        set(v) = prefs.edit().putInt("overlayY", v).apply()

    /** Package of the app watched by the accessibility probe (test tool). */
    var probePackage: String?
        get() = prefs.getString("probePackage", null)
        set(v) = prefs.edit().putString("probePackage", v).apply()

    /** Treat 神盾's on-screen warnings as camera zones (needs the accessibility service). */
    var shieldLink: Boolean
        get() = prefs.getBoolean("shieldLink", true)
        set(v) = prefs.edit().putBoolean("shieldLink", v).apply()

    /** Keep the detailed probe log (for debugging the 神盾 link). */
    var probeLogging: Boolean
        get() = prefs.getBoolean("probeLogging", false)
        set(v) = prefs.edit().putBoolean("probeLogging", v).apply()

    /** 0..100 */
    var volume: Int
        get() = prefs.getInt("volume", 100)
        set(v) = prefs.edit().putInt("volume", v).apply()
}
