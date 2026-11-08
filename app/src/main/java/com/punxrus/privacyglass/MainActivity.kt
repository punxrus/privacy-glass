package com.punxrus.privacyglass

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var status: TextView
    private lateinit var toggleBtn: Button
    private lateinit var logView: TextView
    private val logListener: () -> Unit = { logView.text = DebugLog.text() }
    private val listener: () -> Unit = { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)

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

        header("2. Options")
        check("Black out automatically when glasses connect", { prefs.autoOnConnect }) { prefs.autoOnConnect = it }
        check("Keep phone awake while blacked out", { prefs.keepAwake }) { prefs.keepAwake = it; reapply() }
        check("Force landscape (fills the glasses better)", { prefs.forceLandscape }) { prefs.forceLandscape = it; reapply() }
        check("Volume-down ×3 toggles blackout", { prefs.volumeShortcut }) { prefs.volumeShortcut = it }

        header("3. Try it")
        col.addView(Button(this).apply {
            text = "Test for 10 seconds"
            setOnClickListener { withService("Test") { it.test(10) } }
        })
        toggleBtn = Button(this).apply {
            setOnClickListener { withService("Toggle") { it.toggle() } }
        }
        col.addView(toggleBtn)

        header("Getting the screen back")
        body("• Glasses unplugged → restored automatically\n" +
            "• Press volume-down three times quickly\n" +
            "• Quick Settings tile \"Phone blackout\" (add it via the pencil icon)")


        header("Uninstall")
        body("Turns the blackout off, then asks Android to remove the app.")
        col.addView(Button(this).apply {
            text = "Uninstall Privacy Glass"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("Uninstall Privacy Glass?")
                    .setMessage("The phone screen will come back on and the app will be removed.")
                    .setPositiveButton("Uninstall") { _, _ -> uninstall() }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        header("Diagnostics")
        body("If something doesn't work, tap Copy log and paste it to whoever is helping you.")
        val row = LinearLayout(this)
        row.addView(Button(this).apply {
            text = "Copy log"
            setOnClickListener {
                getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Privacy Glass log", DebugLog.text()))
                Toast.makeText(this@MainActivity, "Log copied", Toast.LENGTH_SHORT).show()
            }
        })
        row.addView(Button(this).apply { text = "Clear log"; setOnClickListener { DebugLog.clear() } })
        col.addView(row)
        logView = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true); alpha = 0.8f
        }
        col.addView(logView)

        setContentView(ScrollView(this).apply { addView(col); isFillViewport = true })
    }

    /** Runs [block] if the accessibility service is live, otherwise explains why nothing can happen. */
    private fun withService(what: String, block: (PrivacyGlassService) -> Unit) {
        val svc = PrivacyGlassService.instance
        DebugLog.log("$what pressed; service running=${svc != null}, enabled in settings=${serviceEnabledInSettings()}")
        if (svc != null) {
            block(svc)
        } else {
            val msg = if (serviceEnabledInSettings())
                "The service is switched on but not running. Turn Privacy Glass off and on again in Accessibility settings."
            else
                "Turn on Privacy Glass in Accessibility settings first (step 1)."
            AlertDialog.Builder(this)
                .setTitle("Service not running")
                .setMessage(msg)
                .setPositiveButton("Open settings") { _, _ -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .setNegativeButton("Cancel", null)
                .show()
        }
        refresh()
    }

    private fun serviceEnabledInSettings(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, PrivacyGlassService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun uninstall() {
        DebugLog.log("Uninstall requested")
        PrivacyGlassService.instance?.deactivate()
        startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")))
    }

    private fun reapply() {
        PrivacyGlassService.instance?.takeIf { it.active }?.activate()
    }

    override fun onResume() {
        super.onResume()
        PrivacyGlassService.listeners += listener
        DebugLog.listeners += logListener
        logListener()
        refresh()
    }

    override fun onPause() {
        PrivacyGlassService.listeners -= listener
        DebugLog.listeners -= logListener
        super.onPause()
    }

    private fun refresh() {
        val svc = PrivacyGlassService.instance
        val glasses = svc?.glasses()
        status.text = buildString {
            append(
                when {
                    svc != null -> "✅ Service running"
                    serviceEnabledInSettings() -> "⚠️ Service switched on but NOT running. Toggle it off and on in Accessibility settings"
                    else -> "⚠️ Service not enabled (step 1)"
                }
            )
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
    }
}
