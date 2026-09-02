package dev.souchastnik.ime

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.animation.LinearInterpolator
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

    /** Что сейчас в строке -- чтобы не катать заново тот же текст. */
    private var shown: CharSequence = ""
    private var run: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Color.parseColor("#17171A"))
        val pad = dp(10)
        setPadding(pad, dp(7), pad, dp(7))

        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
        // setSingleLine, а не maxLines = 1: он включает горизонтальную прокрутку,
        // и разметка строится во всю ширину текста. С одним maxLines разметка
        // обрезается по ширине вида, и прокатывать было бы нечего.
        label.setSingleLine(true)
        label.ellipsize = null
        label.isHorizontalFadingEdgeEnabled = true
        label.setFadingEdgeLength(dp(14))
        label.typeface = Typeface.DEFAULT
        label.setOnClickListener { scrollOnce() }
        // Ширина 0 с весом 1: вьюпорт не зависит от длины текста. С
        // WRAP_CONTENT в режиме прокрутки label растянулся бы по тексту и
        // выдавил тумблер за край.
        addView(label, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))

        toggle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        toggle.setPadding(dp(12), 0, dp(4), 0)
        toggle.setOnClickListener { onToggle?.invoke() }
        addView(toggle, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    fun renderOff() {
        label.setTextColor(colorClean)
        setLabel(context.getString(R.string.strip_off))
        toggle.text = "○"
        toggle.setTextColor(colorClean)
    }

    fun render(state: EngineClient.State) {
        toggle.text = "◉"
        toggle.setTextColor(colorAdmin)

        when (state) {
            EngineClient.State.NoModel -> {
                label.setTextColor(colorClean)
                setLabel(context.getString(R.string.strip_no_model))
            }
            EngineClient.State.Loading -> {
                label.setTextColor(colorClean)
                setLabel("…")
            }
            EngineClient.State.Clean -> {
                label.setTextColor(colorClean)
                // Пусто должно быть пусто. Если строка всё время что-то
                // показывает, её выключат на второй день.
                setLabel(context.getString(R.string.strip_clean))
            }
            EngineClient.State.Thinking -> {
                label.setTextColor(colorClean)
                setLabel(context.getString(R.string.strip_thinking))
            }
            is EngineClient.State.Verdict -> {
                label.setTextColor(
                    when (state.article.severity) {
                        1 -> colorAdmin
                        2 -> colorCrime
                        else -> colorHeavy
                    }
                )
                setLabel(state.article.strip())
            }
        }
    }

    /**
     * Присвоение текста сбрасывает прокрутку, поэтому каждый НОВЫЙ вердикт
     * катаем руками. На том же тексте не трогаем ничего: EngineClient шлёт
     * Clean на каждое нажатие, пока во фразе меньше MIN_CHARS знаков, и
     * строка дёргалась бы на каждой букве.
     */
    private fun setLabel(text: CharSequence) {
        if (android.text.TextUtils.equals(text, shown)) return
        shown = text
        run?.cancel()
        run = null
        label.scrollTo(0, 0)
        label.text = text
        // Разметка нового текста готова только после раскладки.
        label.post { scrollOnce() }
    }

    /**
     * Один проход: пауза на старте, чтобы пользователь успел прочесть
     * начало, прокат до хвоста, короткая пауза на нём и возврат к началу.
     * Повторный прокат -- по тапу по тексту.
     */
    private fun scrollOnce() {
        run?.cancel()
        run = null
        val layout = label.layout ?: return
        val viewport = label.width - label.compoundPaddingLeft - label.compoundPaddingRight
        val over = (layout.getLineWidth(0) - viewport).toInt()
        label.scrollTo(0, 0)
        // Короткие состояния ("чисто", "…", "Соучастник выключен") влезают
        // целиком -- катать нечего.
        if (over <= 0) return
        // Анимации отключены в настройках телефона: оставляем начало строки,
        // о продолжении говорит градиент у правого края.
        if (!ValueAnimator.areAnimatorsEnabled()) return
        // 40 dp/с: самая длинная статья (вылет ~164 dp) едет ~4 с. У
        // системного marquee скорость 30 dp/с зашита во фреймворк и нет
        // паузы на старте, отсюда своя анимация.
        val travel = (over / (40f * resources.displayMetrics.density) * 1000f).toLong()
            .coerceIn(500L, 8000L)
        run = ValueAnimator.ofInt(0, over).apply {
            startDelay = START_DELAY_MS
            duration = travel
            interpolator = LinearInterpolator()
            addUpdateListener { label.scrollTo(it.animatedValue as Int, 0) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) { cancelled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (cancelled || run !== animation) return
                    // Постояли на хвосте -- и обратно к началу.
                    label.postDelayed({
                        if (run === animation) {
                            run = null
                            label.scrollTo(0, 0)
                        }
                    }, END_HOLD_MS)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        run?.cancel()
        run = null
        super.onDetachedFromWindow()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        /** Пауза перед прокатом: пользователь сначала видит начало строки. */
        const val START_DELAY_MS = 1700L
        /** Пауза на хвосте перед возвратом к началу. */
        const val END_HOLD_MS = 1200L
    }
}
