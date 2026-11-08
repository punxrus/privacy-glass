package com.punxrus.privacyglass

import android.content.Context

enum class Mode { OVERLAY, PANEL_OFF }

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", null)!!) }.getOrDefault(Mode.OVERLAY)
        set(v) = sp.edit().putString("mode", v.name).apply()

    var autoOnConnect: Boolean
        get() = sp.getBoolean("auto", true)
        set(v) = sp.edit().putBoolean("auto", v).apply()

    var keepAwake: Boolean
        get() = sp.getBoolean("keep_awake", true)
        set(v) = sp.edit().putBoolean("keep_awake", v).apply()

    var forceLandscape: Boolean
        get() = sp.getBoolean("landscape", false)
        set(v) = sp.edit().putBoolean("landscape", v).apply()

    var volumeShortcut: Boolean
        get() = sp.getBoolean("volume_shortcut", true)
        set(v) = sp.edit().putBoolean("volume_shortcut", v).apply()
}
