package com.punxrus.privacyglass

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/** Client side of the Shizuku-powered "panel off" mode. */
class PanelPower(private val packageName: String) {

    private var service: IPanelService? = null
    private val pending = mutableListOf<(IPanelService?) -> Unit>()

    private val args = Shizuku.UserServiceArgs(ComponentName(packageName, PanelUserService::class.java.name))
        .daemon(false)
        .processNameSuffix("panel")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IPanelService.Stub.asInterface(binder)
            flush()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** Calls back on the main thread with null on success or an error message. */
    fun setPanelPower(on: Boolean, callback: (String?) -> Unit) {
        val problem = shizukuProblem()
        if (problem != null) return callback(problem)
        withService { s ->
            if (s == null) callback("Couldn't start the Shizuku helper")
            else callback(runCatching { s.setDisplayPower(0, on) }.getOrElse { it.toString() })
        }
    }

    fun release() {
        runCatching { Shizuku.unbindUserService(args, conn, true) }
        service = null
    }

    private fun withService(block: (IPanelService?) -> Unit) {
        service?.let { return block(it) }
        pending += block
        if (pending.size == 1) {
            try {
                Shizuku.bindUserService(args, conn)
            } catch (e: Throwable) {
                flush()
            }
        }
    }

    private fun flush() {
        val waiting = pending.toList()
        pending.clear()
        waiting.forEach { it(service) }
    }

    companion object {
        /** Null when Shizuku is running and has granted us access. */
        fun shizukuProblem(): String? = when {
            !Shizuku.pingBinder() -> "Shizuku isn't running"
            Shizuku.isPreV11() -> "Shizuku is too old"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku permission not granted"
            else -> null
        }
    }
}
