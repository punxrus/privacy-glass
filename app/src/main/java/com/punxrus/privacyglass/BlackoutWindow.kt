package com.punxrus.privacyglass

import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import kotlin.math.max

/**
 * A full-screen accessibility overlay on the phone's own display.
 *
 * - The window itself is transparent and never takes touches, so taps and
 *   swipes go to whatever is underneath, as if the overlay weren't there.
 *   (Accessibility overlays are "trusted", so Android doesn't block
 *   pass-through touches the way it does for normal app overlays.)
 * - In OVERLAY mode it hosts an opaque black layer flagged "skip screenshot".
 *   SurfaceFlinger renders such layers only on the built-in panel. External
 *   displays (the glasses), screen recordings and screenshots leave it out.
 *   The phone goes black while the glasses still see the normal mirror.
 * - It also carries the "keep awake" and "force landscape" options.
 */
class BlackoutWindow(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var black: SurfaceControl? = null
    private var blackSurface: Surface? = null

    /** Set when the black layer couldn't be created or hidden from the glasses. */
    var error: String? = null
        private set

    val isShowing get() = view != null

    fun show(drawBlack: Boolean, keepAwake: Boolean, landscape: Boolean, onError: (String) -> Unit) {
        hide()
        error = null
        val v = View(context)
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

        if (drawBlack) {
            v.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) {
                    attachBlackLayer(view, attempts = 20, onError)
                }
                override fun onViewDetachedFromWindow(view: View) = Unit
            })
        }
        wm.addView(v, lp)
        view = v
    }

    fun hide() {
        releaseBlackLayer()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    /** The window's surface may not exist on the first frame, so retry briefly. */
    private fun attachBlackLayer(v: View, attempts: Int, onError: (String) -> Unit) {
        if (view !== v) return
        val root = v.rootSurfaceControl
        val sc = black ?: createBlackLayer().also { black = it }
        val t = root?.buildReparentTransaction(sc)
        if (t == null) {
            if (attempts > 0) v.postDelayed({ attachBlackLayer(v, attempts - 1, onError) }, 50)
            else fail("Window surface never became ready", onError)
            return
        }
        t.setVisibility(sc, true).setLayer(sc, Int.MAX_VALUE)
        try {
            SurfaceControl.Transaction::class.java
                .getMethod("setSkipScreenshot", SurfaceControl::class.java, Boolean::class.javaPrimitiveType)
                .invoke(t, sc, true)
        } catch (e: Throwable) {
            // Without this flag the black layer would show on the glasses too.
            t.close()
            fail("This Android build won't hide the black layer from the glasses (${e.cause ?: e}). Try Panel off mode.", onError)
            return
        }
        t.apply()
        Log.i(TAG, "Black layer attached")
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

    private fun fail(msg: String, onError: (String) -> Unit) {
        Log.w(TAG, msg)
        error = msg
        releaseBlackLayer()
        onError(msg)
    }

    companion object { private const val TAG = "PrivacyGlass" }
}
