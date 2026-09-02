package dev.souchastnik.ime

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dev.souchastnik.R
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Prefs
import dev.souchastnik.engine.EngineService

/** Экран установки: включить клавиатуру в системе и глобальный тумблер. */
class SetupActivity : AppCompatActivity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Articles.load(this)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(text(getString(R.string.app_name), 26f, bold = true))
        root.addView(text(getString(R.string.disclaimer), 15f, color = "#C0392B"))
        root.addView(spacer(pad))

        root.addView(
            text(
                "Клавиатура показывает одну строку над клавишами: статью и наказание " +
                    "за то, что вы печатаете. Работает в любом приложении.\n\n" +
                    "Разбор идёт целиком на телефоне. У приложения нет разрешения на " +
                    "интернет — проверьте сами: aapt dump permissions на APK.",
                14f,
            )
        )
        root.addView(spacer(pad))

        root.addView(Button(this).apply {
            text = "1. Включить клавиатуру в системе"
            setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "2. Выбрать «Соучастник»"
            setOnClickListener {
                (getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager)
                    .showInputMethodPicker()
            }
        })
        root.addView(spacer(pad))

        root.addView(Switch(this).apply {
            text = "  Показывать статьи"
            textSize = 16f
            isChecked = Prefs.isEnabled(this@SetupActivity)
            setOnCheckedChangeListener { _, checked ->
                Prefs.setEnabled(this@SetupActivity, checked)
            }
        })
        root.addView(
            text(
                "Выключено — клавиатура работает как обычная, модель не загружается " +
                    "в память вообще. Тот же тумблер есть справа в самой строке.",
                12.5f,
                color = "#8A8A90",
            )
        )
        root.addView(spacer(pad))

        status = text("", 13f, color = "#8A8A90")
        root.addView(status)

        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
    }

    override fun onResume() {
        super.onResume()
        val model = EngineService.modelFile(this)
        status.text = buildString {
            append("Статей в справочнике: ${Articles.size()}\n")
            if (!dev.souchastnik.engine.Cpu.supported()) {
                append("Процессор без dotprod/fp16 — модель на этом телефоне не запустится,\n")
                append("строка будет пустой.\n")
            } else if (model.exists()) {
                append("Модель: ${model.length() / 1024 / 1024} МБ\n")
            } else {
                append("Модель не установлена — строка будет пустой.\n")
                append("Сборка без весов: см. app/src/main/jniLibs/README.md\n")
            }
        }
    }

    private fun text(s: String, size: Float, bold: Boolean = false, color: String? = null) =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            color?.let { setTextColor(Color.parseColor(it)) }
            val p = (4 * resources.displayMetrics.density).toInt()
            setPadding(0, p, 0, p)
        }

    private fun spacer(h: Int) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, h)
    }
}
