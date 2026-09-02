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
     * Включено при первой установке: человек поставил именно эту клавиатуру,
     * и ждать от него ещё одного тумблера незачем -- строка должна работать
     * сразу. Модель поднимается при первом открытии клавиатуры; кому она не
     * нужна, выключает шутку тумблером на экране настройки или в самой строке.
     */
    fun isEnabled(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_ENABLED, true)

    fun setEnabled(ctx: Context, value: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
