package com.punxrus.privacyglass

import android.app.Application
import org.lsposed.hiddenapibypass.HiddenApiBypass

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // Lets us call SurfaceControl.Transaction#setSkipScreenshot, a hidden API.
        HiddenApiBypass.addHiddenApiExemptions("Landroid/view/SurfaceControl")
    }
}
