package dev.souchastnik.ime

import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Prefs
import dev.souchastnik.engine.EngineClient

/**
 * Клавиатура «Соучастник».
 *
 * Работает в любом поле ввода в системе — мессенджеры, браузер, заметки.
 * Иначе не смешно: шутка живёт ровно там, где человек реально пишет.
 *
 * Текст НИКУДА не уходит и НИГДЕ не логируется. У приложения нет
 * разрешения INTERNET (см. AndroidManifest), а сюда специально не
 * добавлено ни одного Log-вызова с содержимым поля ввода.
 */
class SouchastnikIME : InputMethodService(), KeyboardView.Listener {

    private lateinit var strip: VerdictStrip
    private lateinit var keyboard: KeyboardView
    private var engine: EngineClient? = null

    /** Пароли и прочее чувствительное не разбираем вообще. */
    private var suppressed = false

    override fun onCreate() {
        super.onCreate()
        Articles.load(this)
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
        // Клавиатуру убрали — держать пол гига в памяти незачем.
        engine?.disconnect()
        engine = null
    }

    override fun onDestroy() {
        engine?.disconnect()
        engine = null
        super.onDestroy()
    }

    private fun applyEnabled(enabled: Boolean) {
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
        currentInputConnection?.commitText(c.toString(), 1)
        analyzeCurrent()
    }

    override fun onSpace() {
        currentInputConnection?.commitText(" ", 1)
        analyzeCurrent()
    }

    override fun onBackspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
        analyzeCurrent()
    }

    override fun onEnter() {
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
