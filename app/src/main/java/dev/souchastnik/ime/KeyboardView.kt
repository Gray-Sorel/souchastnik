package dev.souchastnik.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

/**
 * Клавиатура. Намеренно скучная: ЙЦУКЕН, латиница, символы, и ничего больше.
 * Вся ценность приложения в строке над ней, а клавиатура обязана просто
 * не мешать — человек ставит её основной в системе.
 */
class KeyboardView(context: Context) : View(context) {

    interface Listener {
        fun onChar(c: Char)
        fun onBackspace()
        fun onBackspaceWord()
        fun onEnter()
        fun onSpace()
    }

    var listener: Listener? = null

    private companion object {
        /** Сколько держать ⌫, прежде чем это станет удалением по словам. */
        const val HOLD_MS = 350L

        /** Интервал между словами, пока палец не отпустили. */
        const val REPEAT_MS = 120L
    }

    private enum class Layout { RU, EN, SYM }

    private data class Key(
        val label: String,
        val out: Char? = null,
        val action: Action? = null,
        val weight: Float = 1f,
    )

    private enum class Action { SHIFT, BACKSPACE, LAYOUT, LANG, SPACE, ENTER }

    private val rowsRu = listOf(
        "йцукенгшщзхъ",
        "фывапролджэ",
        "ячсмитьбю",
    )
    private val rowsEn = listOf(
        "qwertyuiop",
        "asdfghjkl",
        "zxcvbnm",
    )
    private val rowsSym = listOf(
        "1234567890",
        "-/:;()₽&@\"",
        ".,?!'",
    )

    private var layout = Layout.RU
    private var shift = false

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private val keyBg = Color.parseColor("#2B2B2E")
    private val keyBgSpecial = Color.parseColor("#1E1E20")
    private val keyBgPressed = Color.parseColor("#4A4A50")
    private val keyText = Color.parseColor("#F2F2F2")

    private var pressed: Pair<Int, Int>? = null
    private val bounds = RectF()

    private val repeat = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Успело ли зажатие ⌫ превратиться в удаление по словам. Если да, то на
     * ACTION_UP обычный посимвольный бэкспейс уже не отправляем: иначе после
     * каждого зажатия отгрызался бы лишний символ.
     */
    private var repeatingWords = false

    private val deleteWord = object : Runnable {
        override fun run() {
            repeatingWords = true
            listener?.onBackspaceWord()
            repeat.postDelayed(this, REPEAT_MS)
        }
    }

    private fun stopRepeat() {
        repeat.removeCallbacks(deleteWord)
    }

    private fun rows(): List<List<Key>> {
        val letters = when (layout) {
            Layout.RU -> rowsRu
            Layout.EN -> rowsEn
            Layout.SYM -> rowsSym
        }
        val out = ArrayList<List<Key>>(4)
        letters.forEachIndexed { i, row ->
            val keys = row.map { ch ->
                val c = if (shift) ch.uppercaseChar() else ch
                Key(c.toString(), out = c)
            }
            // Третий ряд: шифт слева, бэкспейс справа.
            out += if (i == letters.size - 1) {
                buildList {
                    if (layout != Layout.SYM) {
                        add(Key(if (shift) "⇪" else "⇧", action = Action.SHIFT, weight = 1.5f))
                    }
                    addAll(keys)
                    add(Key("⌫", action = Action.BACKSPACE, weight = 1.5f))
                }
            } else {
                keys
            }
        }
        out += listOf(
            Key(if (layout == Layout.SYM) "АБВ" else "?123", action = Action.LAYOUT, weight = 1.5f),
            Key(if (layout == Layout.EN) "EN" else "RU", action = Action.LANG, weight = 1.2f),
            Key("", action = Action.SPACE, weight = 4.5f),
            Key(".", out = '.'),
            Key("⏎", action = Action.ENTER, weight = 1.5f),
        )
        return out
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        // Пропорция под 4 ряда, как у системных клавиатур.
        val h = (w * 0.62f).toInt().coerceIn(dp(180), dp(340))
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val rows = rows()
        val rowH = height.toFloat() / rows.size
        val gap = dp(3).toFloat()

        textPaint.textSize = max(dp(15).toFloat(), rowH * 0.36f)

        rows.forEachIndexed { r, row ->
            val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
            var x = 0f
            row.forEachIndexed { c, key ->
                val kw = width * (key.weight / totalWeight)
                bounds.set(x + gap, r * rowH + gap, x + kw - gap, (r + 1) * rowH - gap)

                keyPaint.color = when {
                    pressed?.first == r && pressed?.second == c -> keyBgPressed
                    key.action != null && key.action != Action.SPACE -> keyBgSpecial
                    else -> keyBg
                }
                canvas.drawRoundRect(bounds, dp(6).toFloat(), dp(6).toFloat(), keyPaint)

                if (key.label.isNotEmpty()) {
                    textPaint.color = keyText
                    canvas.drawText(
                        key.label,
                        bounds.centerX(),
                        bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2f,
                        textPaint,
                    )
                }
                x += kw
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rows = rows()
        val rowH = height.toFloat() / rows.size
        val r = (event.y / rowH).toInt().coerceIn(0, rows.size - 1)
        val row = rows[r]

        val totalWeight = row.sumOf { it.weight.toDouble() }.toFloat()
        var x = 0f
        var hit = -1
        for ((i, key) in row.withIndex()) {
            val kw = width * (key.weight / totalWeight)
            if (event.x >= x && event.x < x + kw) { hit = i; break }
            x += kw
        }
        if (hit < 0) hit = row.size - 1

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = r to hit
                invalidate()
                repeatingWords = false
                if (row[hit].action == Action.BACKSPACE) {
                    repeat.postDelayed(deleteWord, HOLD_MS)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val now = r to hit
                if (pressed != now) {
                    // Палец ушёл с ⌫ на другую клавишу — зажатие больше не в силе.
                    stopRepeat()
                    pressed = now
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                pressed = null
                invalidate()
                stopRepeat()
                if (!(repeatingWords && row[hit].action == Action.BACKSPACE)) {
                    fire(row[hit])
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = null
                invalidate()
                stopRepeat()
            }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        stopRepeat()
        super.onDetachedFromWindow()
    }

    private fun fire(key: Key) {
        val l = listener ?: return
        when (key.action) {
            Action.SHIFT -> { shift = !shift; invalidate() }
            Action.BACKSPACE -> l.onBackspace()
            Action.ENTER -> l.onEnter()
            Action.SPACE -> l.onSpace()
            Action.LAYOUT -> {
                layout = if (layout == Layout.SYM) Layout.RU else Layout.SYM
                shift = false
                invalidate()
            }
            Action.LANG -> {
                layout = if (layout == Layout.EN) Layout.RU else Layout.EN
                shift = false
                invalidate()
            }
            null -> key.out?.let {
                l.onChar(it)
                if (shift) { shift = false; invalidate() }
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
