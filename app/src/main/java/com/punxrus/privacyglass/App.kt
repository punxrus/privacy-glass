package com.punxrus.privacyglass

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugLog.init(this)
        // Lets us call hidden APIs such as SurfaceControl.Transaction#setSkipScreenshot.
        val ok = runCatching { HiddenApiBypass.addHiddenApiExemptions("L") }
        DebugLog.log("Hidden API exemption: ${ok.getOrNull() ?: ok.exceptionOrNull()}")
    }
}
