package com.moto.speedalert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var settings: Settings
    private lateinit var speedView: TextView
    private lateinit var limitView: TextView
    private lateinit var thresholdView: TextView
    private lateinit var sourceView: TextView
    private lateinit var cameraBanner: View
    private lateinit var cameraTitle: TextView
    private lateinit var cameraDetail: TextView
    private var bannerPulse: android.animation.ObjectAnimator? = null
    private lateinit var statusView: TextView
    private lateinit var startStop: Button
    private lateinit var speedPanel: View
    private var testBeeper: Beeper? = null

    private val listener: (RideState) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        speedView = findViewById(R.id.speed)
        limitView = findViewById(R.id.limit)
        thresholdView = findViewById(R.id.threshold)
        sourceView = findViewById(R.id.source)
        cameraBanner = findViewById(R.id.cameraBanner)
        cameraTitle = findViewById(R.id.cameraTitle)
        cameraDetail = findViewById(R.id.cameraDetail)
        statusView = findViewById(R.id.status)
        startStop = findViewById(R.id.startStop)
        speedPanel = findViewById(R.id.speedPanel)

        startStop.setOnClickListener {
            if (AppState.state.running) stopRide() else startRideWithPermissions()
        }
        buildSettings(findViewById(R.id.settings))
    }

    override fun onStart() {
        super.onStart()
        AppState.listen(listener)
        AppState.setUiVisible(true)
    }

    override fun onStop() {
        AppState.setUiVisible(false)
        AppState.unlisten(listener)
        super.onStop()
    }

    override fun onDestroy() {
        testBeeper?.release()
        super.onDestroy()
    }

    // ---- ride control -------------------------------------------------------

    private fun startRideWithPermissions() {
        val needed = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            needed += Manifest.permission.ACCESS_FINE_LOCATION
            needed += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (needed.isEmpty()) startRide() else requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRide()
        } else {
            Toast.makeText(this, "需要「精確位置」權限才能測速", Toast.LENGTH_LONG).show()
        }
    }

    private fun startRide() {
        startForegroundService(Intent(this, SpeedService::class.java))
        if (settings.floatingWindow && !FloatingWindow.canDraw(this)) requestOverlayPermission()
    }

    private fun requestOverlayPermission() {
        android.app.AlertDialog.Builder(this)
            .setTitle("開啟浮動視窗權限")
            .setMessage(
                "請在接下來的頁面把「機車超速提醒」的「顯示在其他應用程式上層」打開。\n\n" +
                    "如果開關是灰色、按了跳出「受限制的設定」：\n" +
                    "1. 按下方「應用程式資訊」\n" +
                    "2. 點右上角「⋮」→「允許受限制的設定」（需驗證指紋或密碼）\n" +
                    "3. 再回來按「前往設定」打開權限\n\n" +
                    "小米／紅米：在應用程式資訊 →「其他權限」→「顯示懸浮窗」。"
            )
            .setPositiveButton("前往設定") { _, _ -> openOverlaySettings() }
            .setNeutralButton("應用程式資訊") { _, _ -> openAppInfo() }
            .setNegativeButton("取消", null)
            .show()
    }

    /** Different phone makers handle these intents differently; try them in order. */
    private fun openOverlaySettings() {
        val pkg = Uri.parse("package:$packageName")
        val candidates = listOf(
            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg),
            Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION),
            Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", packageName),
            Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
        )
        for (i in candidates) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        Toast.makeText(this, "找不到權限設定頁，請到 設定 → 應用程式 → 機車超速提醒 手動開啟", Toast.LENGTH_LONG).show()
    }

    private fun openAppInfo() {
        try {
            startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            Toast.makeText(this, "請到 設定 → 應用程式 → 機車超速提醒", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        renderProbeStatus()
        overlayStatus?.let { renderOverlayStatus(it) }
    }

    private var overlayStatus: TextView? = null

    private fun renderOverlayStatus(v: TextView) {
        val ok = FloatingWindow.canDraw(this)
        v.text = if (ok) "浮動視窗權限：已允許 ✓" else "浮動視窗權限：尚未允許（點這裡設定）"
        v.setTextColor(getColor(if (ok) R.color.ok else R.color.danger))
        v.isClickable = !ok
    }

    private fun stopRide() {
        startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_STOP))
    }

    private fun notifyServiceSettingsChanged() {
        if (AppState.state.running) {
            startService(Intent(this, SpeedService::class.java).setAction(SpeedService.ACTION_SETTINGS_CHANGED))
        }
    }

    // ---- rendering ----------------------------------------------------------

    private fun render(s: RideState) {
        statusView.text = s.status
        startStop.text = if (s.running) "停止" else "開始"
        startStop.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (s.running) R.color.danger else R.color.accent)
        )
        // Keep the screen on while riding with the app open (phone on the handlebar).
        if (s.running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val inCameraZone = s.running && s.camera != null
        if (!s.running || !s.hasFix) {
            speedView.text = "--"
            speedView.setTextColor(getColor(R.color.text))
            speedPanel.setBackgroundResource(R.drawable.card)
        } else {
            speedView.text = Math.round(s.speedKmh).toString()
            speedView.setTextColor(
                getColor(
                    when {
                        s.alarming -> R.color.text
                        s.overspeed -> R.color.warn // over, but silent (no camera)
                        else -> R.color.ok
                    }
                )
            )
            when {
                s.alarming -> speedPanel.setBackgroundColor(getColor(R.color.danger))
                inCameraZone -> speedPanel.setBackgroundResource(R.drawable.card_camera)
                else -> speedPanel.setBackgroundResource(R.drawable.card)
            }
        }

        val limit = if (s.running) s.limit else settings.defaultLimit
        val threshold = if (s.running) s.threshold else settings.defaultLimit + settings.tolerance
        limitView.text = limit.toString()
        val cameraOnly = if (s.running) s.cameraOnly else settings.cameraOnlyAlarm
        thresholdView.text = if (cameraOnly) "測速照相路段達到 $threshold km/h 立即嗶嗶"
        else "達到 $threshold km/h 立即嗶嗶"
        sourceView.text = if (s.running && s.hasFix) {
            "限速來源：${s.limitSource.label}" + (s.roadName?.let { "｜$it" } ?: "")
        } else ""

        val cam = s.camera
        if (inCameraZone && cam != null) {
            val kind = if (cam.camera.section) "區間測速" else "測速照相"
            cameraTitle.text = "📷 $kind  前方 ${cam.distance.toInt()} m"
            cameraDetail.text = "限速 ${cam.camera.limit}｜${cam.camera.address}"
            if (cameraBanner.visibility != View.VISIBLE) {
                cameraBanner.visibility = View.VISIBLE
                bannerPulse = android.animation.ObjectAnimator.ofFloat(cameraBanner, "alpha", 1f, 0.45f).apply {
                    duration = 600
                    repeatMode = android.animation.ValueAnimator.REVERSE
                    repeatCount = android.animation.ValueAnimator.INFINITE
                    start()
                }
            }
        } else if (cameraBanner.visibility == View.VISIBLE) {
            bannerPulse?.cancel()
            bannerPulse = null
            cameraBanner.alpha = 1f
            cameraBanner.visibility = View.GONE
        }
    }

    // ---- settings UI --------------------------------------------------------

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun buildSettings(root: LinearLayout) {
        fun switchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
            root.addView(Switch(this).apply {
                text = label
                textSize = 17f
                setTextColor(getColor(R.color.text))
                isChecked = value
                setPadding(0, dp(10), 0, dp(10))
                setOnCheckedChangeListener { _, c -> onChange(c); render(AppState.state) }
            })
        }

        fun stepperRow(label: String, unit: String, get: () -> Int, set: (Int) -> Unit, step: Int, min: Int, max: Int) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val name = TextView(this).apply {
                text = label
                textSize = 17f
                setTextColor(getColor(R.color.text))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val value = TextView(this).apply {
                textSize = 18f
                setTextColor(getColor(R.color.text))
                gravity = Gravity.CENTER
                minWidth = dp(84)
                text = "${get()} $unit"
            }
            fun button(t: String, delta: Int) = Button(this).apply {
                text = t
                textSize = 20f
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(52))
                setOnClickListener {
                    val v = (get() + delta).coerceIn(min, max)
                    set(v)
                    value.text = "$v $unit"
                    render(AppState.state)
                }
            }
            row.addView(name)
            row.addView(button("−", -step))
            row.addView(value)
            row.addView(button("+", step))
            root.addView(row)
        }

        switchRow("依道路自動切換限速", settings.autoLimit) { settings.autoLimit = it }
        switchRow("只在測速照相路段警告超速", settings.cameraOnlyAlarm) {
            settings.cameraOnlyAlarm = it
            notifyServiceSettingsChanged()
        }
        stepperRow("超過限速多少開始警告", "km/h", { settings.tolerance }, { settings.tolerance = it }, 1, 0, 30)
        stepperRow("預設／手動限速", "km/h", { settings.defaultLimit }, { settings.defaultLimit = it }, 10, 20, 110)
        switchRow("浮動視窗（切到其他 App 時顯示車速）", settings.floatingWindow) {
            settings.floatingWindow = it
            if (it && !FloatingWindow.canDraw(this)) requestOverlayPermission()
            notifyServiceSettingsChanged()
        }
        root.addView(TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, dp(8))
            setOnClickListener { if (!FloatingWindow.canDraw(this@MainActivity)) requestOverlayPermission() }
            overlayStatus = this
            renderOverlayStatus(this)
        })
        switchRow("測速照相提醒", settings.cameraAlert) { settings.cameraAlert = it }
        stepperRow("測速照相路段範圍（照相點前）", "m", { settings.cameraDistance }, { settings.cameraDistance = it }, 100, 100, 1000)

        root.addView(TextView(this).apply {
            text = "警報音量"
            textSize = 17f
            setTextColor(getColor(R.color.text))
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(SeekBar(this).apply {
            max = 100
            progress = settings.volume
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                    if (fromUser) settings.volume = p
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {
                    notifyServiceSettingsChanged()
                    testSound()
                }
            })
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "測試警報聲"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { testSound() }
        })
        buttons.addView(Button(this).apply {
            text = "關閉省電限制"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        })
        root.addView(buttons)
        buildProbeSection(root)
    }

    private fun testSound() {
        val b = testBeeper ?: Beeper(this).also { testBeeper = it }
        b.volume = settings.volume / 100f
        b.testBeep()
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimizations() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "已經關閉省電限制", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        )
    }

    // ---- probe (test tool) ---------------------------------------------------

    private var probeStatus: TextView? = null

    private fun buildProbeSection(root: LinearLayout) {
        root.addView(TextView(this).apply {
            text = "偵測神盾（測試）"
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(getColor(R.color.text))
            setPadding(0, dp(20), 0, dp(4))
        })
        probeStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(getColor(R.color.muted))
        }
        root.addView(probeStatus)
        renderProbeStatus()

        fun row(vararg items: Pair<String, () -> Unit>) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((label, action) in items) {
                r.addView(Button(this).apply {
                    text = label
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener { action() }
                })
            }
            root.addView(r)
        }
        row("1. 選擇神盾" to { pickProbeApp() }, "2. 開啟偵測權限" to { openAccessibilitySettings() })
        row("3. 查看偵測紀錄" to { showProbeLog() })
    }

    private fun renderProbeStatus() {
        val v = probeStatus ?: return
        val pkg = settings.probePackage
        val label = pkg?.let { appLabel(it) ?: it } ?: "尚未選擇"
        val on = isProbeEnabled()
        v.text = "目標 App：$label\n偵測權限：" + (if (on) "已開啟 ✓" else "未開啟") +
            "\n用法：選好神盾、開啟權限，同時開著神盾和本 App 騎車；遇到神盾警告後回來按「查看偵測紀錄」→「複製」貼給 Claude。"
    }

    private fun isProbeEnabled(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.startsWith("$packageName/") && it.endsWith("ProbeService") }
    }

    private fun appLabel(pkg: String): String? = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        null
    }

    private fun pickProbeApp() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = packageManager.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedWith(compareByDescending<Pair<String, String>> { "神盾" in it.second || "測速" in it.second }.thenBy { it.second })
        val labels = apps.map { (pkg, label) -> if ("神盾" in label || "測速" in label) "★ $label" else label }
        android.app.AlertDialog.Builder(this)
            .setTitle("選擇要偵測的 App（神盾）")
            .setItems(labels.toTypedArray()) { _, i ->
                settings.probePackage = apps[i].first
                ProbeLog.append(this, "目標改為 ${apps[i].second}（${apps[i].first}）")
                renderProbeStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openAccessibilitySettings() {
        android.app.AlertDialog.Builder(this)
            .setTitle("開啟偵測權限")
            .setMessage(
                "在「無障礙」設定裡找到「機車超速提醒：偵測測試」並開啟。\n\n" +
                    "如果是灰色、顯示「受限制的設定」：先到 設定 → 應用程式 → 機車超速提醒 → 右上角「⋮」→「允許受限制的設定」，再回來開啟。\n\n" +
                    "這個權限只用來記錄你選的那一個 App，測試完可以關掉。"
            )
            .setPositiveButton("前往設定") { _, _ ->
                try {
                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                    Toast.makeText(this, "請到 設定 → 無障礙 手動開啟", Toast.LENGTH_LONG).show()
                }
            }
            .setNeutralButton("應用程式資訊") { _, _ -> openAppInfo() }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showProbeLog() {
        val short = ProbeLog.readCompact(this).ifEmpty { "（還沒有紀錄）" }
        val tv = TextView(this).apply {
            text = short
            textSize = 11f
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(tv) }
        fun copy(label: String, text: String) {
            val cm = getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("probe", text))
            Toast.makeText(this, "已複製$label，可以貼給 Claude", Toast.LENGTH_SHORT).show()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("偵測紀錄（精簡版）")
            .setView(scroll)
            .setPositiveButton("複製精簡版") { _, _ -> copy("精簡版", short) }
            .setNegativeButton("複製完整版") { _, _ ->
                // Full dumps can be huge; the newest part matters most.
                copy("完整版（最後一段）", ProbeLog.read(this).takeLast(60_000))
            }
            .setNeutralButton("清除") { _, _ -> ProbeLog.clear(this) }
            .show()
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_PERMS = 1
    }
}
