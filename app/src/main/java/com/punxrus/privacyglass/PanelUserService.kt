package com.punxrus.privacyglass

import android.os.IBinder
import kotlin.system.exitProcess

/**
 * Runs in a separate process that Shizuku starts with shell (adb) rights.
 * Shell may call DisplayManager's hidden requestDisplayPower(), the API
 * scrcpy uses for "turn screen off". It powers down only the phone panel
 * while Android stays awake and keeps feeding the glasses.
 */
class PanelUserService : IPanelService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun setDisplayPower(displayId: Int, on: Boolean): String? = try {
        val binder = Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, "display") as IBinder
        val dm = Class.forName("android.hardware.display.IDisplayManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)!!
        val method = dm.javaClass.methods.firstOrNull { it.name == "requestDisplayPower" }
            ?: return "requestDisplayPower() not found (needs Android 15+)"
        val second = method.parameterTypes.getOrNull(1)
        val arg: Any = when (second) {
            java.lang.Boolean.TYPE -> on
            Integer.TYPE -> if (on) STATE_ON else STATE_OFF // newer signature takes a Display.STATE_*
            else -> return "Unexpected requestDisplayPower signature: ${method.parameterTypes.toList()}"
        }
        val result = method.invoke(dm, displayId, arg)
        if (result == false) "System refused the display power request" else null
    } catch (e: Throwable) {
        "${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}"
    }

    private companion object {
        const val STATE_OFF = 1 // android.view.Display.STATE_OFF
        const val STATE_ON = 2  // android.view.Display.STATE_ON
    }
}
