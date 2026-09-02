package dev.souchastnik.data

import android.content.Context
import org.json.JSONObject

/**
 * Справочник статей. Единственный источник правды для строки над клавишами
 * и для таблицы кандидатов в промпте судьи.
 *
 * Сроки и штрафы берутся ОТСЮДА, а не из генерации. Модель 0.8B будет
 * уверенно писать "до 4 лет" там, где в кодексе штраф, и вся шутка
 * ломается: читатель уже не понимает, где прикол, а где косяк приложения.
 *
 * ПОЧЕМУ ЗДЕСЬ НЕТ ПОЛЯ label. В articles.json у каждой статьи есть
 * однотокенная метка, а рядом лежит `_none_label`. Приложение их не читает:
 * судья отвечает КОДОМ статьи, а мост берёт argmax только по токенам живых
 * вариантов (см. llama_bridge.cpp, раздел «ПОЧЕМУ НЕ ОДНОТОКЕННЫЕ МЕТКИ»).
 * Из файла поля не убраны, потому что на них держатся десктопные
 * инструменты: `tools/try.py` читает `_none_label` в `__init__` в любом
 * режиме, `tools/bench_variants.py` строит на метках вариант «все статьи в
 * фиксированном промпте», и на них же весь `train/`. Не добавляйте label
 * сюда обратно, решив, что парсер без него неполный.
 */
data class Article(
    val code: String,
    val act: String,
    val title: String,
    val penalty: String,
    val severity: Int,
) {
    /** "ст. 5.61 КоАП · оскорбление · 3–5 тыс ₽" */
    fun strip(): String = "ст. $code $act · $title · $penalty"
}

object Articles {

    const val NONE = "none"

    private var list: List<Article> = emptyList()
    private var byCode: Map<String, Article> = emptyMap()

    /** Куда в judge.txt подставляется таблица статей-кандидатов. */
    private const val TABLE_MARK = "@@TABLE@@"

    /** Шаблон системного сообщения судьи с [TABLE_MARK] вместо таблицы. */
    private var judgeTemplate: String = ""

    fun load(ctx: Context) {
        if (list.isNotEmpty()) return

        val json = ctx.assets.open("articles.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
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
            )
        }
        list = items
        byCode = items.associateBy { it.code }

        Examples.load(ctx)
        judgeTemplate = asset(ctx, "judge.txt")
    }

    operator fun get(code: String): Article? = byCode[code]

    fun size(): Int = list.size

    private fun asset(ctx: Context, name: String): String =
        ctx.assets.open(name).bufferedReader().use { it.readText() }

    /**
     * Системное сообщение судьи: инструкции плюс таблица ТОЛЬКО тех статей,
     * которые подняли триггеры, плюс примеры. Весь справочник в промпт не
     * идёт — при выборе из 82 модель путает близкие составы (20.3.1 с
     * 20.3.3, 280 с 280.1), при выборе из нескольких кандидатов почти не
     * путает.
     *
     * Это единственный запрос к модели на разбор. Отдельной калитки «есть
     * состав?» перед судьёй нет: замер на 66 фразах (tools/bench_variants.py)
     * показал, что она удваивает префилл и не меняет точность.
     *
     * Поле hint из articles.json сюда НЕ попадает: на замерах оно не дало
     * эффекта, а каждая строка подсказки — это токены префилла.
     */
    fun judgeSystem(codes: List<String>, groupClean: List<String>): String {
        val table = codes.asSequence()
            .mapNotNull { byCode[it] }
            .joinToString(separator = "\n") { "${it.code} ${it.act} — ${it.title}" }
        return judgeTemplate.replace(TABLE_MARK, table) +
            Examples.judgeBlock(codes, groupClean)
    }
}
