package com.punxrus.privacyglass

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log kept on disk so it survives crashes. Shown in the app with a
 * "Copy log" button, which makes on-device problems reportable.
 */
object DebugLog {
    private const val MAX_LINES = 300
    private var file: File? = null
    private val lines = ArrayDeque<String>()
    private val main = Handler(Looper.getMainLooper())
    val listeners = mutableSetOf<() -> Unit>()

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, "debug.log")
        file = f
        runCatching { f.readLines().takeLast(MAX_LINES).forEach(lines::addLast) }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            log("CRASH in ${t.name}: ${Log.getStackTraceString(e)}")
            previous?.uncaughtException(t, e)
        }
        log("--- process start: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}, app ${BuildConfig.VERSION_NAME}")
    }

    @Synchronized
    fun log(msg: String) {
        Log.i("PrivacyGlass", msg)
        val line = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + "  " + msg
        lines.addLast(line)
        while (lines.size > MAX_LINES) lines.removeFirst()
        runCatching { file?.writeText(lines.joinToString("\n")) }
        main.post { listeners.forEach { it() } }
    }

    fun error(msg: String, e: Throwable) = log("$msg: ${Log.getStackTraceString(e)}")

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
        main.post { listeners.forEach { it() } }
    }
}
