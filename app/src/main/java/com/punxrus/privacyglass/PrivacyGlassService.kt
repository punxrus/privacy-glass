package com.punxrus.privacyglass

import android.accessibilityservice.AccessibilityService
import android.content.Intent
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

    var active = false
        private set
    var lastError: String? = null
        private set

    private var volumePresses = 0
    private var firstPressAt = 0L
    private var testRevert: Runnable? = null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            DebugLog.log("Display added: $displayId. ${describeDisplays()}")
            if (prefs.autoOnConnect && !active && glasses() != null) {
                // Give the mirror a moment to settle before blacking out.
                handler.postDelayed({ if (!active && glasses() != null) activate() }, 1500)
            }
            notifyChanged()
        }
        override fun onDisplayRemoved(displayId: Int) {
            DebugLog.log("Display removed: $displayId")
            if (glasses() == null && active) {
                deactivate()
                toast("Glasses disconnected - phone screen restored")
            }
            notifyChanged()
        }
        override fun onDisplayChanged(displayId: Int) = Unit
    }

    override fun onServiceConnected() {
        DebugLog.init(this)
        DebugLog.log("Accessibility service connected")
        try {
            prefs = Prefs(this)
            displays = getSystemService(DisplayManager::class.java)
            window = BlackoutWindow(this)
            displays.registerDisplayListener(displayListener, handler)
            instance = this
            DebugLog.log("Service ready. ${describeDisplays()}")
            if (prefs.autoOnConnect && glasses() != null) activate()
        } catch (e: Throwable) {
            DebugLog.error("Service startup failed", e)
            toast("Privacy Glass failed to start: $e")
        }
        notifyChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DebugLog.log("Accessibility service unbound (turned off?)")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        DebugLog.log("Accessibility service destroyed")
        if (instance === this) {
            deactivate()
            runCatching { displays.unregisterDisplayListener(displayListener) }
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
            DebugLog.log("Volume-down x3 shortcut")
            toggle()
        }
        return false
    }

    fun describeDisplays(): String = "Displays: " + displays.displays.joinToString { d ->
        val m = d.mode
        "#${d.displayId} '${d.name}' ${m.physicalWidth}x${m.physicalHeight} flags=0x${Integer.toHexString(d.flags)} state=${d.state}"
    } + " | presentation: " + displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).joinToString { "#${it.displayId}" }

    /** The glasses, or null. Mirrored USB-C displays are flagged as presentation displays. */
    fun glasses(): Display? {
        fun ok(d: Display) = d.displayId != Display.DEFAULT_DISPLAY && (d.flags and Display.FLAG_PRIVATE) == 0
        return displays.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull(::ok)
            ?: displays.displays.firstOrNull(::ok)
    }

    fun toggle() = if (active) deactivate() else activate()

    fun activate() {
        if (active) deactivate()
        lastError = null
        DebugLog.log("Activate. ${describeDisplays()}")
        active = true
        try {
            window.show(keepAwake = prefs.keepAwake, landscape = prefs.forceLandscape, onError = ::onFailure)
        } catch (e: Throwable) {
            DebugLog.error("addView failed", e)
            return onFailure("Couldn't add overlay: $e")
        }
        if (active) toast("Blackout ON")
        notifyChanged()
    }

    fun deactivate() {
        cancelTest()
        if (!active) return
        DebugLog.log("Deactivate")
        window.hide()
        active = false
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
        DebugLog.log("Failure: $msg")
        lastError = msg
        toast(msg)
        deactivate()
        lastError = msg
        notifyChanged()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun notifyChanged() {
        listeners.forEach { it() }
        runCatching {
            TileService.requestListeningState(
                this, android.content.ComponentName(this, BlackoutTileService::class.java)
            )
        }
    }

    companion object {
        var instance: PrivacyGlassService? = null
            private set
        val listeners = mutableSetOf<() -> Unit>()
    }
}
