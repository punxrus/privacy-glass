package com.punxrus.privacyglass

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import kotlin.math.max

/**
 * A full-screen accessibility overlay on the phone's own display.
 *
 * The window never takes touches, so taps and swipes reach whatever is
 * underneath. Accessibility overlays are "trusted", so Android doesn't
 * block pass-through touches the way it does for normal app overlays.
 *
 * The black is flagged "skip screenshot". SurfaceFlinger draws such
 * surfaces only on the built-in panel and leaves them out of external
 * displays (the glasses), recordings and screenshots. Two ways to apply it:
 *  - OVERLAY_WINDOW: the window itself is black, and the flag goes on the
 *    window's own surface.
 *  - OVERLAY_LAYER: the window is transparent and hosts a separate black
 *    child surface carrying the flag.
 *
 * It also carries the "keep awake" and "force landscape" options.
 */
class BlackoutWindow(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var black: SurfaceControl? = null
    private var blackSurface: Surface? = null
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    val isShowing get() = view != null

    fun show(mode: Mode, keepAwake: Boolean, landscape: Boolean, onError: (String) -> Unit) {
        hide()
        val v = View(context)
        if (mode == Mode.OVERLAY_WINDOW) v.setBackgroundColor(Color.BLACK)

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
                DebugLog.log("Overlay attached to window (mode=$mode)")
                when (mode) {
                    Mode.OVERLAY_WINDOW -> flagWindowSurface(view, attempts = 40, onError)
                    Mode.OVERLAY_LAYER -> attachBlackLayer(view, attempts = 40, onError)
                    Mode.PANEL_OFF -> Unit
                }
            }
            override fun onViewDetachedFromWindow(view: View) = DebugLog.log("Overlay detached")
        })
        DebugLog.log("Adding overlay window: mode=$mode keepAwake=$keepAwake landscape=$landscape")
        wm.addView(v, lp)
        view = v
    }

    fun hide() {
        releaseBlackLayer()
        view?.let { v ->
            layoutListener?.let { runCatching { v.viewTreeObserver.removeOnGlobalLayoutListener(it) } }
            runCatching { wm.removeView(v) }.onFailure { DebugLog.error("removeView failed", it) }
            DebugLog.log("Overlay window removed")
        }
        layoutListener = null
        view = null
    }

    // ---- Overlay A: flag the window's own surface ----

    private fun flagWindowSurface(v: View, attempts: Int, onError: (String) -> Unit) {
        if (view !== v) return
        val sc = windowSurface(v)
        if (sc == null || !sc.isValid) {
            if (attempts > 0) v.postDelayed({ flagWindowSurface(v, attempts - 1, onError) }, 50)
            else fail("Window surface never became ready", onError)
            return
        }
        if (!applySkip(sc, onError)) return
        DebugLog.log("Overlay A: skip-screenshot set on window surface $sc")
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

    // ---- Overlay B: separate black child surface ----

    /** The window's surface may not exist on the first frame, so retry briefly. */
    private fun attachBlackLayer(v: View, attempts: Int, onError: (String) -> Unit) {
        if (view !== v) return
        val root = v.rootSurfaceControl
        val sc = black ?: try {
            createBlackLayer().also { black = it }
        } catch (e: Throwable) {
            DebugLog.error("createBlackLayer failed", e)
            return fail("Couldn't create black layer: $e", onError)
        }
        val t = root?.buildReparentTransaction(sc)
        if (t == null) {
            if (attempts > 0) v.postDelayed({ attachBlackLayer(v, attempts - 1, onError) }, 50)
            else fail("Window surface never became ready", onError)
            return
        }
        t.setVisibility(sc, true).setLayer(sc, Int.MAX_VALUE)
        if (!skipOn(t, sc, onError)) {
            t.close()
            return
        }
        t.apply()
        DebugLog.log("Overlay B: black layer attached and flagged")
    }

    private fun createBlackLayer(): SurfaceControl {
        // Square, covering the longest side, so rotation never exposes an edge.
        val bounds = wm.maximumWindowMetrics.bounds
        val size = max(bounds.width(), bounds.height())
        val sc = SurfaceControl.Builder()
            .setName("PrivacyGlassBlack")
            .setBufferSize(size, size)
            .setFormat(PixelFormat.OPAQUE)
            .setOpaque(true)
            .build()
        val surface = Surface(sc)
        val canvas = surface.lockHardwareCanvas()
        canvas.drawColor(Color.BLACK)
        surface.unlockCanvasAndPost(canvas)
        blackSurface = surface
        DebugLog.log("Black layer created ${size}x$size")
        return sc
    }

    private fun releaseBlackLayer() {
        black?.let { sc ->
            runCatching { SurfaceControl.Transaction().reparent(sc, null).apply() }
            sc.release()
        }
        blackSurface?.release()
        black = null
        blackSurface = null
    }

    // ---- shared ----

    private fun applySkip(sc: SurfaceControl, onError: (String) -> Unit): Boolean {
        val t = SurfaceControl.Transaction()
        if (!skipOn(t, sc, onError)) {
            t.close()
            return false
        }
        t.apply()
        return true
    }

    private fun skipOn(t: SurfaceControl.Transaction, sc: SurfaceControl, onError: (String) -> Unit): Boolean = try {
        SurfaceControl.Transaction::class.java
            .getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
            .invoke(t, sc, true)
        true
    } catch (e: Throwable) {
        DebugLog.error("setSkipScreenshot failed", e)
        // Without the flag the black would show on the glasses too.
        fail("Can't hide the black from the glasses on this phone (${e.cause ?: e}). Try Panel off.", onError)
        false
    }

    private fun fail(msg: String, onError: (String) -> Unit) {
        DebugLog.log("FAIL: $msg")
        releaseBlackLayer()
        onError(msg)
    }
}
