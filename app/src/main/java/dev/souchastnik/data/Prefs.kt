package dev.souchastnik.data

import android.content.Context

/**
 * Настройки читаются ТОЛЬКО в процессе IME. Межпроцессный доступ к
 * SharedPreferences на Android не работает надёжно (MODE_MULTI_PROCESS
 * давно сломан и deprecated), поэтому :engine о настройках ничего не знает:
 * клавиатура сама решает, когда сказать движку load() или unload().
 */
object Prefs {

    private const val FILE = "souchastnik"
    private const val KEY_ENABLED = "enabled"

    /**
     * Выключено при первой установке. Намеренно: человек сначала видит,
     * что клавиатура просто работает как клавиатура, и включает шутку сам.
     */
    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
