package com.punxrus.privacyglass

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggleBtn: Button
    private lateinit var shizukuRow: LinearLayout
    private lateinit var shizukuStatus: TextView
    private val listener: () -> Unit = { runOnUiThread { refresh() } }
    private val shizukuListener = Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Shizuku.addRequestPermissionResultListener(shizukuListener)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad * 2)
        }
        fun header(t: String) = col.addView(TextView(this).apply {
            text = t; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setPadding(0, pad, 0, pad / 3)
        })
        fun body(t: String) = col.addView(TextView(this).apply { text = t; textSize = 14f; alpha = 0.85f })
        fun check(label: String, get: () -> Boolean, set: (Boolean) -> Unit) = col.addView(CheckBox(this).apply {
            text = label; isChecked = get(); setOnCheckedChangeListener { _, c -> set(c) }
        })

        col.addView(TextView(this).apply { text = getString(R.string.app_name); textSize = 26f; setTypeface(typeface, Typeface.BOLD) })
        body("Keeps your phone screen black while your XREAL glasses show it. Touch and swipe on the phone as usual.")

        status = TextView(this).apply { textSize = 15f; setPadding(0, pad, 0, 0) }
        col.addView(status)

        header("1. Enable the service")
        body("Turn on Privacy Glass under Accessibility. If Android says the setting is restricted: App info → ⋮ → Allow restricted settings, then try again.")
        col.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        col.addView(Button(this).apply {
            text = "Open App info"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        })

        header("2. Blackout method")
        val group = RadioGroup(this)
        val overlay = RadioButton(this).apply { id = View.generateViewId(); text = "Black overlay (try first, no extra apps)" }
        val panelOff = RadioButton(this).apply { id = View.generateViewId(); text = "Panel off (needs Shizuku)" }
        group.addView(overlay); group.addView(panelOff)
        group.check(if (prefs.mode == Mode.OVERLAY) overlay.id else panelOff.id)
        group.setOnCheckedChangeListener { _, id ->
            prefs.mode = if (id == overlay.id) Mode.OVERLAY else Mode.PANEL_OFF
            PrivacyGlassService.instance?.takeIf { it.active }?.activate() // re-apply in new mode
            refresh()
        }
        col.addView(group)
        body("Overlay: panel stays lit but draws black, and the glasses skip that layer. Touch always works.\n" +
            "Panel off: turns the panel off completely. Fallback if the overlay also shows up on the glasses. On some phones touch stops working while the panel is off. Test it first.")

        shizukuRow = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        shizukuStatus = TextView(this).apply { textSize = 14f; setPadding(0, pad / 2, 0, 0) }
        shizukuRow.addView(shizukuStatus)
        shizukuRow.addView(Button(this).apply {
            text = "Grant Shizuku permission"
            setOnClickListener {
                if (Shizuku.pingBinder()) Shizuku.requestPermission(1) else refresh()
            }
        })
        col.addView(shizukuRow)

        header("3. Options")
        check("Black out automatically when glasses connect", { prefs.autoOnConnect }) { prefs.autoOnConnect = it }
        check("Keep phone awake while blacked out", { prefs.keepAwake }) { prefs.keepAwake = it; reapply() }
        check("Force landscape (fills the glasses better)", { prefs.forceLandscape }) { prefs.forceLandscape = it; reapply() }
        check("Volume-down ×3 toggles blackout", { prefs.volumeShortcut }) { prefs.volumeShortcut = it }

        header("4. Try it")
        col.addView(Button(this).apply {
            text = "Test for 10 seconds"
            setOnClickListener { PrivacyGlassService.instance?.test(10) ?: refresh() }
        })
        toggleBtn = Button(this).apply {
            setOnClickListener { PrivacyGlassService.instance?.toggle(); refresh() }
        }
        col.addView(toggleBtn)

        header("Getting the screen back")
        body("• Glasses unplugged → restored automatically\n" +
            "• Press volume-down three times quickly\n" +
            "• Quick Settings tile \"Phone blackout\" (add it via the pencil icon)\n" +
            "• Panel-off mode: the power button also wakes the panel")

        setContentView(ScrollView(this).apply { addView(col); isFillViewport = true })
    }

    private fun reapply() {
        PrivacyGlassService.instance?.takeIf { it.active }?.activate()
    }

    override fun onResume() {
        super.onResume()
        PrivacyGlassService.listeners += listener
        refresh()
    }

    override fun onPause() {
        PrivacyGlassService.listeners -= listener
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuListener)
        super.onDestroy()
    }

    private fun refresh() {
        val svc = PrivacyGlassService.instance
        val glasses = svc?.glasses()
        status.text = buildString {
            append(if (svc != null) "✅ Service enabled" else "⚠️ Service not enabled (step 1)")
            append('\n')
            append(
                when {
                    svc == null -> "• Glasses: unknown"
                    glasses != null -> "✅ Glasses connected: ${glasses.name} (${glasses.mode.physicalWidth}×${glasses.mode.physicalHeight})"
                    else -> "• Glasses not detected (plug in, and use mirroring, not desktop mode)"
                }
            )
            append('\n')
            append(if (svc?.active == true) "🌑 Blackout ON" else "☀️ Blackout off")
            svc?.lastError?.let { append("\n❗ $it") }
        }
        toggleBtn.text = if (svc?.active == true) "Turn blackout off" else "Turn blackout on"
        toggleBtn.isEnabled = svc != null

        shizukuRow.visibility = if (prefs.mode == Mode.PANEL_OFF) View.VISIBLE else View.GONE
        shizukuStatus.text = PanelPower.shizukuProblem()?.let { "⚠️ $it" } ?: "✅ Shizuku ready"
    }
}
