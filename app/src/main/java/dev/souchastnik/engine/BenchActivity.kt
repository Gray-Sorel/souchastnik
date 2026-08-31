package dev.souchastnik.engine

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import dev.souchastnik.data.Articles

/**
 * Спайк по латентности. Запускается напрямую:
 *
 *   adb shell am start -n dev.souchastnik/.engine.BenchActivity
 *   adb logcat -s souchastnik-bench souchastnik-native
 *
 * Проверяет три вещи, от которых зависит выбор модели:
 *   1. время загрузки модели с диска;
 *   2. префилл + декод на реальных фразах — влезаем ли в 600 мс;
 *   3. живо ли сохранение состояния для гибридной архитектуры Qwen3.5.
 *
 * Работает в процессе по умолчанию, а не в :engine — здесь нам нужен
 * прямой доступ к LlamaBridge без Binder-накладных.
 */
class BenchActivity : Activity() {

    companion object {
        private const val TAG = "souchastnik-bench"

        /** Фразы разной длины: три последние заведомо чистые. */
        private val CASES = listOf(
            "ты совсем дурак что ли",
            "если ты еще раз так сделаешь я тебя закопаю",
            "скинь мне фильм который в кино идет",
            "давай я тебе налом отдам а ты счет не выставляй",
            "я вчера сел за руль после трех бутылок пива и доехал нормально",
            "он мне денег не отдает, пойду вынесу у него из машины магнитолу",
            "во сколько встречаемся завтра у метро",
            "купи молока и хлеба пожалуйста",
            "поздравляю с днем рождения, здоровья тебе",
        )
    }

    private val out = StringBuilder()
    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Articles.load(this)

        view = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setBackgroundColor(Color.parseColor("#101012"))
            setTextColor(Color.parseColor("#E8E8E8"))
            val p = (12 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply { addView(view) })

        Thread { run() }.start()
    }

    private fun run() {
        val model = EngineService.modelFile(this)
        log("модель: ${model.absolutePath}")
        if (!model.exists()) {
            log("НЕТ ФАЙЛА МОДЕЛИ — см. app/src/main/jniLibs/README.md")
            return
        }
        log("размер: ${model.length() / 1024 / 1024} МБ")
        log("статей: ${Articles.size()}, меток: ${Articles.labels().length}")

        val threads = Runtime.getRuntime().availableProcessors().let { if (it >= 8) 4 else 2 }
        log("потоков: $threads")

        val t0 = System.currentTimeMillis()
        val h = LlamaBridge.init(
            model.absolutePath,
            Articles.labels(),
            Articles.systemPrompt,
            threads,
        )
        if (h == 0L) {
            log("init ПРОВАЛИЛСЯ — либо llama.cpp без поддержки qwen35,")
            log("либо метка не однотокенная; смотри logcat souchastnik-native")
            return
        }
        log("загрузка: ${System.currentTimeMillis() - t0} мс")
        log("")

        // Замер 2: prompt cache. От результата зависит, нужен ли план Б
        // с постоянно живым контекстом.
        val cacheOk = LlamaBridge.probeStateCache(h, "${cacheDir.absolutePath}/state.bin")
        log("prompt cache: ${if (cacheOk) "РАБОТАЕТ" else "НЕ РАБОТАЕТ → план Б"}")
        log("")

        // Прогрев: первый проход всегда медленнее (страницы модели ещё не
        // подтянуты из mmap), включать его в статистику нечестно.
        LlamaBridge.benchOnce(h, CASES[0])

        log("%-44s %7s %6s  %s".format("фраза", "prefill", "argmax", "статья"))
        val totals = ArrayList<Long>()
        for (case in CASES) {
            val r = LlamaBridge.benchOnce(h, case)
            val total = r[0] + r[1]
            totals += total
            val index = r[2].toInt()
            val verdict = when {
                index == LlamaBridge.INDEX_ERROR -> "ОШИБКА"
                index == LlamaBridge.INDEX_NONE -> "чисто"
                else -> Articles.byIndex(index)?.let { "${it.code} ${it.act}" } ?: "?$index"
            }
            log("%-44s %6dм %5dм  %s".format(case.take(42), r[0], r[1], verdict))
        }
        log("")
        totals.sort()
        log("медиана: ${totals[totals.size / 2]} мс, максимум: ${totals.last()} мс")
        log("порог решения: медиана < 600 мс → берём Qwen3.5-0.8B")
        log("               иначе          → откат на Qwen3-0.6B")

        LlamaBridge.free(h)
    }

    private fun log(s: String) {
        android.util.Log.i(TAG, s)
        out.append(s).append('\n')
        runOnUiThread { view.text = out.toString() }
    }
}
