package dev.souchastnik.data

import android.content.Context
import org.json.JSONObject

/**
 * Справочник статей. Единственный источник правды и для показа в строке,
 * и для набора меток, которыми отвечает модель.
 *
 * Сроки и штрафы берутся ОТСЮДА, а не из генерации. Модель 0.8B будет
 * уверенно писать "до 4 лет" там, где в кодексе штраф, и вся шутка
 * ломается: читатель уже не понимает, где прикол, а где косяк приложения.
 */
data class Article(
    val code: String,
    val act: String,
    val title: String,
    val penalty: String,
    val severity: Int,
    /**
     * Однотокенная метка, которой модель обозначает эту статью.
     * Метка задана в articles.json ЯВНО и менять её нельзя: обученная
     * LoRA знает конкретный символ, и переназначение меток при
     * переупорядочивании файла молча сломало бы все предсказания.
     */
    val label: Char,
) {
    /** "ст. 5.61 КоАП · оскорбление · 3–5 тыс ₽" */
    fun strip(): String = "ст. $code $act · $title · $penalty"
}

object Articles {

    const val NONE = "none"

    private var list: List<Article> = emptyList()
    private var byCode: Map<String, Article> = emptyMap()
    private var noneLabel: Char = 'A'

    /** Системный промпт лежит в assets и читается ещё и обучающим скриптом. */
    var systemPrompt: String = ""
        private set

    fun load(ctx: Context) {
        if (list.isNotEmpty()) return

        val json = ctx.assets.open("articles.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        noneLabel = root.getString("_none_label")[0]

        val arr = root.getJSONArray("articles")
        val items = ArrayList<Article>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            items += Article(
                code = o.getString("code"),
                act = o.getString("act"),
                title = o.getString("title"),
                penalty = o.getString("penalty"),
                severity = o.getInt("severity"),
                label = o.getString("label")[0],
            )
        }
        list = items
        byCode = items.associateBy { it.code }

        systemPrompt = ctx.assets.open("prompt.txt").bufferedReader().use { it.readText() }
    }

    operator fun get(code: String): Article? = byCode[code]

    fun size(): Int = list.size

    /**
     * Алфавит меток в порядке индексов: нулевая — "чисто", дальше статьи
     * в порядке файла. Именно эту строку получает нативная часть, и индекс
     * в ней и есть ответ модели.
     *
     * Почему метки, а не сами коды статей под GBNF-грамматикой: замер
     * показал, что грамматический сэмплер обходит весь словарь на
     * 248 320 токенов на каждом шаге и стоит сотни миллисекунд на токен.
     * С однотокенной меткой нужен один шаг декода и argmax по 83
     * значениям — микросекунды.
     */
    fun labels(): String = buildString {
        append(noneLabel)
        list.forEach { append(it.label) }
    }

    /**
     * Индекс метки статьи для нативной части. Нумерация та же, что в
     * [labels]: 0 -- "чисто", статьи с 1.
     */
    fun indexOf(code: String): Int? {
        val i = list.indexOfFirst { it.code == code }
        return if (i < 0) null else i + 1
    }

    /** null означает "чисто" (индекс 0) либо мусорный индекс. */
    fun byIndex(index: Int): Article? =
        if (index <= 0 || index > list.size) null else list[index - 1]
}
