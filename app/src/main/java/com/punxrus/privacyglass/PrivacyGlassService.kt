package com.punxrus.privacyglass

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.view.Display
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * The always-on core. Android keeps an enabled accessibility service alive,
 * so no foreground notification is needed. Responsibilities:
 *  - notice when the glasses (an external presentation display) connect or disconnect
 *  - turn the blackout on/off in the selected mode
 *  - always restore the phone screen when the glasses disconnect
 *  - volume-down x3 escape hatch
 */
class PrivacyGlassService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var displays: DisplayManager
    private lateinit var window: BlackoutWindow
    private lateinit var panel: PanelPower

    var active = false
        private set
    private var activeMode: Mode? = null
    var lastError: String? = null
        private set

    private var volumePresses = 0
    private var firstPressAt = 0L
    private var testRevert: Runnable? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            if (prefs.autoOnConnect && !active && glasses() != null) {
                // Give the mirror a moment to settle before blacking out.
                handler.postDelayed({ if (!active && glasses() != null) activate() }, 1500)
            }
            notifyChanged()
        }
        override fun onDisplayRemoved(displayId: Int) {
            if (glasses() == null && active) {
                deactivate()
                toast("Glasses disconnected - phone screen restored")
            }
            notifyChanged()
        }
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    // Panel-off mode: a power-button wake turns the panel back on, so
    // re-apply after unlock while the glasses are still attached.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT && active && activeMode == Mode.PANEL_OFF) {
                handler.postDelayed({ if (active) panel.setPanelPower(false) { it?.let(::onFailure) } }, 700)
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        prefs = Prefs(this)
        displays = getSystemService(DisplayManager::class.java)
        window = BlackoutWindow(this)
        panel = PanelPower(packageName)
        displays.registerDisplayListener(displayListener, handler)
        registerReceiver(screenReceiver, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_EXPORTED)
        if (prefs.autoOnConnect && glasses() != null) activate()
        notifyChanged()
    }

    override fun onDestroy() {
        if (instance === this) {
            deactivate()
            runCatching { displays.unregisterDisplayListener(displayListener) }
            runCatching { unregisterReceiver(screenReceiver) }
            panel.release()
            instance = null
        }
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    /** Volume-down pressed 3 times within 1.5 s toggles blackout. Never consumes the key. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (!prefs.volumeShortcut || event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.action != KeyEvent.ACTION_UP
        ) return false
        val now = SystemClock.uptimeMillis()
        if (now - firstPressAt > 1500) {
            firstPressAt = now
            volumePresses = 0
        }
        if (++volumePresses >= 3) {
            volumePresses = 0
            firstPressAt = 0
            toggle()
        }
        return false
    }

    /** The glasses, or null. Mirrored USB-C displays are flagged as presentation displays. */
    fun glasses(): Display? = displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
        .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && (it.flags and Display.FLAG_PRIVATE) == 0 }

    fun toggle() = if (active) deactivate() else activate()

    fun activate() {
        if (active) deactivate()
        lastError = null
        val mode = prefs.mode
        active = true
        activeMode = mode
        try {
            window.show(
                drawBlack = mode == Mode.OVERLAY,
                keepAwake = prefs.keepAwake,
                landscape = prefs.forceLandscape,
                onError = ::onFailure,
            )
        } catch (e: Throwable) {
            return onFailure("Couldn't add overlay: $e")
        }
        if (mode == Mode.PANEL_OFF) {
            panel.setPanelPower(false) { err -> err?.let(::onFailure) }
        }
        notifyChanged()
    }

    fun deactivate() {
        cancelTest()
        if (!active) return
        if (activeMode == Mode.PANEL_OFF) panel.setPanelPower(true) { }
        window.hide()
        active = false
        activeMode = null
        notifyChanged()
    }

    /** Blackout for [seconds], then restore no matter what - safe way to try a mode. */
    fun test(seconds: Int) {
        activate()
        val r = Runnable { deactivate(); toast("Test finished - screen restored") }
        testRevert = r
        handler.postDelayed(r, seconds * 1000L)
    }

    private fun cancelTest() {
        testRevert?.let(handler::removeCallbacks)
        testRevert = null
    }

    private fun onFailure(msg: String) {
        lastError = msg
        toast(msg)
        deactivate()
        lastError = msg
        notifyChanged()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun notifyChanged() {
        listeners.forEach { it() }
        TileService.requestListeningState(
            this, android.content.ComponentName(this, BlackoutTileService::class.java)
        )
    }

    companion object {
        var instance: PrivacyGlassService? = null
            private set
        val listeners = mutableSetOf<() -> Unit>()
    }
}
