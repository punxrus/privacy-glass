package com.punxrus.privacyglass

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager

/**
 * A full-screen black accessibility overlay on the phone's own display.
 *
 * - It never takes touches, so taps and swipes reach whatever is underneath.
 *   Accessibility overlays are "trusted", so Android doesn't block
 *   pass-through touches the way it does for normal app overlays.
 * - Its surface is flagged "skip screenshot" (hidden API). SurfaceFlinger
 *   draws such surfaces only on the built-in panel and leaves them out of
 *   external displays (the glasses), recordings and screenshots.
 * - It also carries the "keep awake" and "force landscape" options.
 */
class BlackoutWindow(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun show(keepAwake: Boolean, landscape: Boolean, onError: (String) -> Unit) {
        hide()
        val v = View(context).apply { setBackgroundColor(Color.BLACK) }

        var flags = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (keepAwake) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            fitInsetsTypes = 0
            screenOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            title = "PrivacyGlassBlackout"
        }

        v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {
                DebugLog.log("Overlay attached to window")
                flagWindowSurface(view, attempts = 40, onError)
            }
            override fun onViewDetachedFromWindow(view: View) = DebugLog.log("Overlay detached")
        })
        DebugLog.log("Adding overlay window: keepAwake=$keepAwake landscape=$landscape")
        wm.addView(v, lp)
        view = v
    }

    fun hide() {
        view?.let { v ->
            layoutListener?.let { runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(it) } }
            runCatching { wm.removeView(v) }.onFailure { DebugLog.error("removeView failed", it) }
            DebugLog.log("Overlay window removed")
        }
        layoutListener = null
        view = null
    }

    /** The window's surface may not exist on the first frame, so retry briefly. */
    private fun flagWindowSurface(v: View, attempts: Int, onError: (String) -> Unit) {
        if (view !== v) return
        val sc = windowSurface(v)
        if (sc == null || !sc.isValid) {
            if (attempts > 0) v.postDelayed({ flagWindowSurface(v, attempts - 1, onError) }, 50)
            else fail("Window surface never became ready", onError)
            return
        }
        if (!applySkip(sc, onError)) return
        DebugLog.log("Skip-screenshot set on window surface $sc")
        // Relayouts (e.g. rotation) can hand us a new surface, so re-flag after each one.
        val l = ViewTreeObserver.OnGlobalLayoutListener {
            windowSurface(v)?.takeIf { it.isValid }?.let { applySkip(it, onError) }
        }
        layoutListener = l
        v.viewTreeObserver.addOnGlobalLayoutListener(l)
    }

    /** ViewRootImpl#getSurfaceControl(): hidden, reached by reflection. */
    private fun windowSurface(v: View): SurfaceControl? = try {
        val root = v.rootSurfaceControl ?: return null // this object is the ViewRootImpl
        root.javaClass.getMethod("getSurfaceControl").invoke(root) as? SurfaceControl
    } catch (e: Throwable) {
        DebugLog.error("getSurfaceControl failed", e)
        null
    }

    private fun applySkip(sc: SurfaceControl, onError: (String) -> Unit): Boolean {
        val t = SurfaceControl.Transaction()
        return try {
            SurfaceControl.Transaction::class.java
                .getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
                .invoke(t, sc, true)
            t.apply()
            true
        } catch (e: Throwable) {
            t.close()
            DebugLog.error("setSkipScreenshot failed", e)
            // Without the flag the black would show on the glasses too.
            fail("Can't hide the black from the glasses on this phone (${e.cause ?: e})", onError)
            false
        }
    }

    private fun fail(msg: String, onError: (String) -> Unit) {
        DebugLog.log("FAIL: $msg")
        onError(msg)
    }
}
