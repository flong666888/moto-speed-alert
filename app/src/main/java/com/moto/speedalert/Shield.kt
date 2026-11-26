package com.moto.speedalert

/**
 * A warning currently shown by the 神盾 (Shield) app, read from its floating windows by
 * the accessibility service. Observed layout (神盾 3.8.9):
 *   rlayView2Root / txtView2SpeedLimitVal : speed limit number, or a type letter ("科" = 科技執法)
 *   rlayView3Root / txtView3DestVal + txtView3DestUnit : distance to the point ("163" "m")
 * Both windows close once the point is passed.
 */
data class ShieldAlert(
    /** Posted limit if 神盾 shows a number, else null. */
    val limit: Int?,
    /** Human-readable type, e.g. "限速 50", "科技執法". */
    val label: String,
    /** Metres to the point, if shown. */
    val distance: Int?,
) {
    /**
     * True for speed enforcement (a posted limit, or a 測速 type). Other kinds such as
     * 科技執法 (red light, turning…) are shown but never trigger the overspeed beep.
     */
    val speedEnforced: Boolean get() = limit != null || "測速" in label
}

object ShieldState {
    /** A warning not refreshed for this long is considered over. */
    private const val STALE_MS = 4_000L

    @Volatile private var alert: ShieldAlert? = null
    @Volatile private var seenAt = 0L

    fun set(a: ShieldAlert?, now: Long = System.currentTimeMillis()) {
        alert = a
        seenAt = now
    }

    fun current(now: Long = System.currentTimeMillis()): ShieldAlert? =
        alert?.takeIf { now - seenAt <= STALE_MS }

    private val TYPES = mapOf(
        "科" to "科技執法",
        "區" to "區間測速",
        "移" to "移動式測速",
        "闖" to "闖紅燈",
        "固" to "固定式測速",
        "經" to "經常出沒點",
        "自" to "自訂點",
    )

    /**
     * Builds an alert from the raw texts, or null if they do not look like a warning.
     * [limitText] e.g. "50" or "科"; [distText]/[unit] e.g. "163"/"m" or "1.2"/"km".
     */
    fun parse(limitText: String?, distText: String?, unit: String?): ShieldAlert? {
        val lt = limitText?.trim().orEmpty()
        val limit = Regex("""\d{2,3}""").find(lt)?.value?.toInt()?.takeIf { it in 10..130 }
        val meters = distText?.trim()?.toDoubleOrNull()?.let { v ->
            if (unit?.trim()?.lowercase() == "km" || unit?.trim() == "公里") v * 1000 else v
        }?.toInt()
        if (limit == null && lt.isEmpty() && meters == null) return null
        val label = when {
            limit != null -> "限速 $limit"
            lt.isNotEmpty() -> TYPES[lt.take(1)] ?: lt
            else -> "照相點"
        }
        return ShieldAlert(limit, label, meters)
    }
}
