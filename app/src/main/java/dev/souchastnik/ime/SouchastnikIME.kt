package dev.souchastnik.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import dev.souchastnik.data.Agents
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Prefs
import dev.souchastnik.engine.EngineClient

/**
 * Клавиатура «Соучастник».
 *
 * Работает в любом поле ввода в системе — мессенджеры, браузер, заметки.

 *
 * Текст НИКУДА не уходит и НИГДЕ не логируется. У приложения нет
 * разрешения INTERNET (см. AndroidManifest), а сюда специально не
 * добавлено ни одного Log-вызова с содержимым поля ввода.
 */
class SouchastnikIME : InputMethodService(), KeyboardView.Listener {

    companion object {
        /**
         * Сколько держать модель в памяти после того, как клавиатуру спрятали.
         * Меньше — каждое появление клавиатуры в переписке начинается с
         * загрузки модели. Больше — полгига RSS висят в фоне у человека,
         * который уже переключился на другое.
         */
        private const val IDLE_UNLOAD_MS = 90_000L

        /** Знаки, после которых слово считается дописанным — для пометки иноагентов. */
        private const val WORD_ENDS = ",.!?;:)"

        /** Сколько текста тянуть из поля, чтобы найти границу последнего слова. */
        private const val WORD_LOOKBEHIND = 64
    }

    private lateinit var strip: VerdictStrip
    private lateinit var keyboard: KeyboardView
    private var engine: EngineClient? = null

    /** Пароли и прочее чувствительное не разбираем вообще. */
    private var suppressed = false

    override fun onCreate() {
        super.onCreate()
        Articles.load(this)
        Agents.load(this)
    }

    override fun onCreateInputView(): View {
        strip = VerdictStrip(this)
        keyboard = KeyboardView(this).also { it.listener = this }

        strip.onToggle = {
            val now = !Prefs.isEnabled(this)
            Prefs.setEnabled(this, now)
            applyEnabled(now)
        }

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(strip, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            addView(keyboard, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        suppressed = isSensitive(info)
        applyEnabled(Prefs.isEnabled(this) && !suppressed)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Клавиатуру убрали. Выгружать модель сразу нельзя: в переписке
        // клавиатура прячется и появляется каждые несколько секунд, а
        // загрузка полугигабайта из mmap — это секунды, и первый разбор
        // после каждого появления опаздывал бы на них. Держим движок ещё
        // немного и выгружаем, только если человек действительно ушёл.
        main.removeCallbacks(idleUnload)
        main.postDelayed(idleUnload, IDLE_UNLOAD_MS)
    }

    override fun onDestroy() {
        main.removeCallbacks(idleUnload)
        engine?.disconnect()
        engine = null
        super.onDestroy()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private val idleUnload = Runnable {
        engine?.disconnect()
        engine = null
    }

    private fun applyEnabled(enabled: Boolean) {
        main.removeCallbacks(idleUnload)
        if (enabled) {
            if (engine == null) {
                engine = EngineClient(this).also { client ->
                    client.onState = { strip.render(it) }
                    client.connect()
                }
            }
            analyzeCurrent()
        } else {
            engine?.disconnect()
            engine = null
            strip.renderOff()
        }
    }

    /**
     * Поля с паролями, ПИНами и номерами карт не трогаем ни при каких
     * настройках. Шутка не стоит того, чтобы прогонять чей-то пароль
     * через модель.
     */
    private fun isSensitive(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return true
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        return false
    }

    // --- ввод ---

    override fun onChar(c: Char) {
        if (c in WORD_ENDS) markAgent()
        currentInputConnection?.commitText(c.toString(), 1)
        analyzeCurrent()
    }

    override fun onSpace() {
        markAgent()
        currentInputConnection?.commitText(" ", 1)
        analyzeCurrent()
    }

    /**
     * Детерминированная пометка иноагента или сервиса. Человек дописал
     * фамилию из assets/agents.json (или «инсту», «фейсбук», «тг») и ставит
     * пробел или знак препинания — сразу за словом появляется канцелярская
     * пометка о статусе названного лица или сервиса (см. templates в
     * assets/agents.json). Без модели, без задержки, всегда одинаково: это
     * подстановка по словарю, как автозамена.
     *
     * Работает только при включённом тумблере и не в чувствительных полях —
     * там же, где и разбор. Пометка ставится один раз: если хвост текста уже
     * заканчивается на «)», второй раз не лезем.
     */
    private fun markAgent() {
        if (suppressed || engine == null) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(96, 0) ?: return
        if (before.isEmpty() || before.last() == ')') return
        val marker = Agents.lastWord(before)?.let { Agents.markerFor(it) }
            ?: Agents.markerForTail(before)
            ?: return
        ic.commitText(" ($marker)", 1)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        if (!deleteSelection(ic)) {
            // ...InCodePoints, а не deleteSurroundingText: последний считает
            // в code units и на эмодзи оставлял бы половину суррогатной пары.
            ic.deleteSurroundingTextInCodePoints(1, 0)
        }
        analyzeCurrent()
    }

    /**
     * Зажатый ⌫: удаляем последнее слово. Сначала съедаем пробелы прямо
     * перед курсором, потом само слово до пробела — то есть одно нажатие
     * держания убирает ровно одно слово вместе с отступом за ним.
     */
    override fun onBackspaceWord() {
        val ic = currentInputConnection ?: return
        if (deleteSelection(ic)) {
            analyzeCurrent()
            return
        }
        val before = ic.getTextBeforeCursor(WORD_LOOKBEHIND, 0) ?: return
        if (before.isEmpty()) return
        var i = before.length
        while (i > 0 && before[i - 1].isWhitespace()) i--
        while (i > 0 && !before[i - 1].isWhitespace()) i--
        // Слово длиннее окна — сотрём его за несколько тиков зажатия.
        ic.deleteSurroundingText(before.length - i, 0)
        analyzeCurrent()
    }

    /**
     * Если есть выделение, стираем его и говорим об этом вызывающему.
     *
     * Так и выглядел баг «выделил текст, нажал ⌫, ничего не удалилось»:
     * deleteSurroundingText удаляет текст ВОКРУГ выделения, а само выделение
     * оставляет на месте. Выделение убирает commitText("") — оно заменяет
     * выделенный кусок на пустую строку.
     */
    private fun deleteSelection(ic: InputConnection): Boolean {
        val selected = ic.getSelectedText(0)
        if (selected.isNullOrEmpty()) return false
        ic.commitText("", 1)
        return true
    }

    override fun onEnter() {
        markAgent()
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION)
        if (action != null && action != EditorInfo.IME_ACTION_NONE) {
            ic.performEditorAction(action)
        } else {
            ic.commitText("\n", 1)
        }
        // Сообщение ушло — строка обнуляется вместе с полем.
        engine?.onTextChanged("")
    }

    /**
     * Берём то, что стоит перед курсором. 400 символов с запасом: состав
     * обычно в последней фразе, а везти в модель всю переписку незачем.
     */
    private fun analyzeCurrent() {
        if (suppressed) return
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(400, 0)?.toString() ?: return
        engine?.onTextChanged(before)
    }

    /**
     * Когда человек правит текст другой рукой (тап по полю, выделение,
     * автозамена хоста) — тоже пересчитываем.
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd,
        )
        analyzeCurrent()
    }
}
