package dev.souchastnik.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import dev.souchastnik.R
import dev.souchastnik.engine.EngineClient

/**
 * Единственная функция приложения: одна строка над клавиатурой.
 *
 * Слева — статья и наказание. Справа — тумблер. Больше здесь ничего нет
 * и быть не должно: ни счётчика накопленного срока, ни ачивок, ни кнопки
 * "переформулировать". Одна функция, сделанная нормально.
 */
class VerdictStrip(context: Context) : LinearLayout(context) {

    var onToggle: (() -> Unit)? = null

    private val label = TextView(context)
    private val toggle = TextView(context)

    private val colorClean = Color.parseColor("#7A7A80")
    private val colorAdmin = Color.parseColor("#D8A200")
    private val colorCrime = Color.parseColor("#D96A4A")
    private val colorHeavy = Color.parseColor("#C0392B")

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#17171A"))
        val pad = dp(10)
        setPadding(pad, dp(7), pad, dp(7))

        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        label.maxLines = 1
        label.ellipsize = android.text.TextUtils.TruncateAt.END
        label.typeface = Typeface.DEFAULT
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        toggle.setPadding(dp(12), 0, dp(4), 0)
        toggle.setOnClickListener { onToggle?.invoke() }
        addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun renderOff() {
        label.setTextColor(colorClean)
        label.text = context.getString(R.string.strip_off)
        toggle.text = "○"
        toggle.setTextColor(colorClean)
    }

    fun render(state: EngineClient.State) {
        toggle.text = "◉"
        toggle.setTextColor(colorAdmin)

        when (state) {
            EngineClient.State.NoModel -> {
                label.setTextColor(colorClean)
                label.text = context.getString(R.string.strip_no_model)
            }
            EngineClient.State.Loading -> {
                label.setTextColor(colorClean)
                label.text = "…"
            }
            EngineClient.State.Clean -> {
                label.setTextColor(colorClean)
                // Пусто должно быть пусто. Если строка всё время что-то
                // показывает, её выключат на второй день.
                label.text = context.getString(R.string.strip_clean)
            }
            EngineClient.State.Thinking -> {
                label.setTextColor(colorClean)
                label.text = context.getString(R.string.strip_thinking)
            }
            is EngineClient.State.Verdict -> {
                label.setTextColor(
                    when (state.article.severity) {
                        1 -> colorAdmin
                        2 -> colorCrime
                        else -> colorHeavy
                    }
                )
                label.text = state.article.strip()
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
