package com.moto.speedalert

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * TEST TOOL. Records what an accessibility service can see of ONE chosen app (the speed
 * camera app picked in settings) so we can tell whether its warnings are readable.
 * Events from every other app are ignored and never stored. Nothing is sent anywhere;
 * the log stays in this app's private storage until the user clears it.
 */
class ProbeService : AccessibilityService() {

    private lateinit var settings: Settings
    private var lastDump = ""
    private var lastWindows = ""

    override fun onServiceConnected() {
        settings = Settings(this)
        ProbeLog.append(this, "偵測服務已啟動，目標：${settings.probePackage ?: "（尚未選擇）"}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val target = settings.probePackage ?: return
        val isWindows = event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
        if (!isWindows && event.packageName?.toString() != target) return
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (settings.probeLogging) logNotification(event)
            return
        }
        if (settings.shieldLink) readShield(target)
        if (!settings.probeLogging) return
        if (isWindows) {
            logTargetWindows(target)
            return
        }

        val sb = StringBuilder()
        sb.append("事件 ").append(AccessibilityEvent.eventTypeToString(event.eventType))
        event.className?.let { sb.append("｜class=").append(it) }
        if (event.text.isNotEmpty()) sb.append("｜text=").append(event.text.joinToString(" / "))
        event.contentDescription?.let { sb.append("｜desc=").append(it) }
        sb.append("｜").append(ourContext())
        val root = event.source?.let { src -> src.window?.root ?: src }
        if (root != null) {
            sb.append("\n").append(summary(root)).append(dumpTree(root))
        }
        val dump = sb.toString()
        // Content-changed events repeat a lot; store only when something actually changed.
        val key = dump.substringAfter("\n", dump)
        if (key == lastDump) return
        lastDump = key
        ProbeLog.append(this, dump)
        root?.let { compact("事件", it) }
    }

    /**
     * Looks for 神盾's warning windows (limit/type + distance) and publishes what they say.
     * No warning window = no warning.
     */
    private fun readShield(target: String) {
        val list = try { windows } catch (_: Exception) { return }
        var limitText: String? = null
        var distText: String? = null
        var unit: String? = null
        var found = false
        for (w in list) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != target) continue
            fun text(id: String) = root.findAccessibilityNodeInfosByViewId("$target:id/$id")
                .firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
            when (root.viewIdResourceName?.substringAfter(":id/")) {
                "rlayView2Root" -> { found = true; limitText = text("txtView2SpeedLimitVal") ?: limitText }
                "rlayView3Root" -> {
                    found = true
                    distText = text("txtView3DestVal") ?: distText
                    unit = text("txtView3DestUnit") ?: unit
                }
            }
        }
        ShieldState.set(if (found) ShieldState.parse(limitText, distText, unit) else null)
    }

    /** Records a notification (or toast) posted by the target app, with all its texts. */
    private fun logNotification(event: AccessibilityEvent) {
        val n = event.parcelableData as? android.app.Notification
        val parts = mutableListOf<String>()
        if (event.text.isNotEmpty()) parts += "text=" + event.text.joinToString(" / ")
        if (n != null) {
            val ex = n.extras
            for (key in listOf(
                android.app.Notification.EXTRA_TITLE, android.app.Notification.EXTRA_TEXT,
                android.app.Notification.EXTRA_BIG_TEXT, android.app.Notification.EXTRA_SUB_TEXT,
                android.app.Notification.EXTRA_INFO_TEXT, android.app.Notification.EXTRA_SUMMARY_TEXT,
            )) {
                ex.getCharSequence(key)?.takeIf { it.isNotBlank() }?.let { parts += key.removePrefix("android.") + "=" + it }
            }
            n.tickerText?.let { parts += "ticker=$it" }
            parts += "channel=${n.channelId} ongoing=${n.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0}"
        } else {
            parts += "（不是通知，可能是 Toast 提示）"
        }
        val line = "通知｜" + parts.joinToString(" ")
        ProbeLog.append(this, line + "｜" + ourContext())
        ProbeLog.appendCompact(this, line + "｜" + ourContext())
    }

    /** Lists the target app's windows (overlays included, where Android reports them). */
    private fun logTargetWindows(target: String) {
        val list = try { windows } catch (_: Exception) { emptyList<AccessibilityWindowInfo>() }
        val sb = StringBuilder()
        for (w in list) {
            val root = w.root ?: continue
            if (root.packageName?.toString() != target) continue
            val r = Rect().also { w.getBoundsInScreen(it) }
            compact("視窗 " + windowType(w.type), root)
            sb.append("視窗 type=").append(windowType(w.type))
                .append(" title=").append(w.title)
                .append(" bounds=").append(r.toShortString())
                .append("\n").append(summary(root)).append(dumpTree(root))
        }
        val text = sb.toString()
        if (text == lastWindows) return
        lastWindows = text
        ProbeLog.append(this, if (text.isEmpty()) "（目標 App 目前沒有可見視窗）｜${ourContext()}" else text + "｜${ourContext()}")
    }

    private var lastCompact = ""

    /** One short line per change, for the "copy short version" button. */
    private fun compact(kind: String, root: AccessibilityNodeInfo) {
        val sum = summary(root).trim().removePrefix("重點 ")
        val rootId = root.viewIdResourceName?.substringAfter(":id/") ?: ""
        val line = "$kind $rootId｜${sum.ifEmpty { "（無文字）" }}"
        if (line == lastCompact) return
        lastCompact = line
        ProbeLog.appendCompact(this, "$line｜${ourContext()}")
    }

    private fun dumpTree(root: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        var count = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= MAX_NODES || depth > 25) return
            if (depth > 0 && !onScreen(n)) return
            count++
            val r = Rect().also { n.getBoundsInScreen(it) }
            sb.append("  ".repeat(depth)).append("- ").append(n.className?.toString()?.substringAfterLast('.'))
            n.viewIdResourceName?.let { sb.append(" id=").append(it.substringAfter(":id/")) }
            n.text?.let { if (it.isNotBlank()) sb.append(" text=\"").append(it).append('"') }
            n.contentDescription?.let { if (it.isNotBlank()) sb.append(" desc=\"").append(it).append('"') }
            sb.append(" ").append(r.toShortString()).append('\n')
            // Ads are web views with many nodes and nothing useful.
            if (n.className?.contains("WebView") == true) return
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        if (count >= MAX_NODES) sb.append("  …（節點太多，已截斷）\n")
        return sb.toString()
    }

    /**
     * One line with the fields that look camera/speed related and currently hold a value,
     * e.g. "重點 labLockCamDistance=320 labSpeed=48". Easy to read even if the tree is cut.
     */
    private fun summary(root: AccessibilityNodeInfo): String {
        val hits = mutableListOf<String>()
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || visited > 600 || depth > 30) return
            visited++
            if (!onScreen(n)) return // e.g. the settings page parked off-screen
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: n.className?.toString()?.substringAfterLast('.')
            val value = n.text?.takeIf { it.isNotBlank() } ?: n.contentDescription?.takeIf { it.isNotBlank() && it != "na" }
            if (value != null) hits += "$id=${value.toString().replace('\n', ' ')}"
            if (n.className?.contains("WebView") == true) return
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        return if (hits.isEmpty()) "" else "重點 " + hits.joinToString(" ") + "\n"
    }

    private val screen by lazy {
        val m = resources.displayMetrics
        Rect(0, 0, m.widthPixels, m.heightPixels + 400)
    }

    private fun onScreen(n: AccessibilityNodeInfo): Boolean {
        val r = Rect().also { n.getBoundsInScreen(it) }
        return r.width() > 0 && r.height() > 0 && Rect.intersects(r, screen)
    }

    /** What our own app knew at that moment, to line the two apps' warnings up. */
    private fun ourContext(): String {
        val s = AppState.state
        if (!s.running || !s.hasFix) return "本App：未在測速"
        val cam = s.camera?.let { "照相點 ${it.distance.toInt()}m 限${it.camera.limit}" } ?: "無照相點"
        return "本App：時速${s.speedKmh.toInt()} 限速${s.limit} $cam"
    }

    private fun windowType(t: Int) = when (t) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "APPLICATION"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "SYSTEM"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "IME"
        AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "A11Y_OVERLAY"
        else -> t.toString()
    }

    override fun onInterrupt() {}

    companion object {
        private const val MAX_NODES = 220
    }
}

/** Private, size-capped log file for the probe. */
object ProbeLog {
    private const val MAX_BYTES = 400_000
    private val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.TAIWAN)

    private fun file(c: Context) = File(c.filesDir, "probe.log")
    private fun compactFile(c: Context) = File(c.filesDir, "probe_short.log")

    @Synchronized
    fun appendCompact(c: Context, text: String) {
        val f = compactFile(c)
        if (f.length() > MAX_BYTES / 4) f.writeText(f.readText().takeLast(MAX_BYTES / 8))
        f.appendText("[${fmt.format(Date())}] $text\n")
    }

    @Synchronized
    fun readCompact(c: Context): String = compactFile(c).takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun append(c: Context, text: String) {
        val f = file(c)
        if (f.length() > MAX_BYTES) {
            // Keep the newer half.
            val keep = f.readText().takeLast(MAX_BYTES / 2)
            f.writeText(keep)
        }
        f.appendText("[${fmt.format(Date())}] $text\n\n")
    }

    @Synchronized
    fun read(c: Context): String = file(c).takeIf { it.exists() }?.readText().orEmpty()

    @Synchronized
    fun clear(c: Context) {
        file(c).delete()
        compactFile(c).delete()
    }
}
