package ru.czcheck.scanner

import android.content.Context
import android.content.SharedPreferences

class Settings(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var apiMode: ApiMode
        get() = try { ApiMode.valueOf(p.getString("apiMode", ApiMode.AUTO.name)!!) } catch (e: Exception) { ApiMode.AUTO }
        set(v) = p.edit().putString("apiMode", v.name).apply()

    /** Вариант API, который последним ответил успешно (для режима AUTO). */
    var workingVariant: ApiMode?
        get() = p.getString("workingVariant", null)?.let { n -> ApiMode.values().firstOrNull { it.name == n } }
        set(v) = p.edit().putString("workingVariant", v?.name).apply()

    var baseUrl: String
        get() = p.getString("baseUrl", DEFAULT_BASE_URL)!!.ifBlank { DEFAULT_BASE_URL }
        set(v) = p.edit().putString("baseUrl", v.trim().trimEnd('/')).apply()

    var restoreGs: Boolean
        get() = p.getBoolean("restoreGs", true)
        set(v) = p.edit().putBoolean("restoreGs", v).apply()

    var fixLayout: Boolean
        get() = p.getBoolean("fixLayout", true)
        set(v) = p.edit().putBoolean("fixLayout", v).apply()

    var autoSubmit: Boolean
        get() = p.getBoolean("autoSubmit", true)
        set(v) = p.edit().putBoolean("autoSubmit", v).apply()

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    var vibrate: Boolean
        get() = p.getBoolean("vibrate", true)
        set(v) = p.edit().putBoolean("vibrate", v).apply()

    var trustAllSsl: Boolean
        get() = p.getBoolean("trustAllSsl", false)
        set(v) = p.edit().putBoolean("trustAllSsl", v).apply()

    companion object {
        const val DEFAULT_BASE_URL = "https://mobile.api.crpt.ru"
    }
}
