package dev.souchastnik.data

import android.content.Context
import org.json.JSONObject

/**
 * Словарь триггеров. Сужает список статей ДО запуска модели.
 *
 * Никогда не ставит статью сам: слово «армия» лишь поднимает подозрение,
 * а про чью армию речь и хорошее ли это сказано — решает модель.
 *
 * Две причины, почему это не украшение:
 *
 * 1. **Батарея.** Если не сработал ни один триггер — «чисто» без запуска
 *    модели вообще. В обычной переписке триггеры молчат, то есть модель
 *    не крутится на каждую фразу про молоко и метро.
 *
 * 2. **Точность.** При выборе из 82 статей модель путает близкие составы;
 *    при выборе из нескольких кандидатов — почти не путает. Она умеет
 *    различать, но не одним шагом среди 82 вариантов.
 */
object Triggers {

    private class Group(
        val words: List<String>,
        val codes: List<String>,
        /** Чистая фраза в той же лексике — контрпример для промпта. */
        val clean: String?,
    )

    /** Что сказал словарь: кандидаты и контрпримеры сработавших групп. */
    class Match(val codes: List<String>, val clean: List<String>)

    private var groups: List<Group> = emptyList()

    fun load(ctx: Context) {
        if (groups.isNotEmpty()) return
        val json = ctx.assets.open("triggers.json").bufferedReader().use { it.readText() }
        val arr = JSONObject(json).getJSONArray("groups")
        val out = ArrayList<Group>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val words = o.getJSONArray("words").let { w ->
                (0 until w.length()).map { normalize(w.getString(it)) }
            }
            val codes = o.getJSONArray("codes").let { c ->
                (0 until c.length()).map { c.getString(it) }
            }
            out += Group(words, codes, o.optString("clean").ifEmpty { null })
        }
        groups = out
    }

    fun size(): Int = groups.size

    /** Регистр и «ё» не должны решать, сработал триггер или нет. */
    private fun normalize(s: String): String =
        s.lowercase().replace('ё', 'е').replace(Regex("\\s+"), " ")

    /**
     * Кандидаты словаря: пустой список кодов означает «ни один триггер не
     * сработал», и модель запускать не надо.
     *
     * "none" в результат не входит — его добавляет вызывающая сторона,
     * иначе модели было бы некуда ответить «состава нет».
     */
    fun match(text: String): Match {
        val t = normalize(text)
        val codes = LinkedHashSet<String>()
        val clean = ArrayList<String>()
        for (g in groups) {
            if (g.words.none { it in t }) continue
            g.clean?.let { clean += it }
            codes += g.codes
        }
        return Match(codes.toList(), clean)
    }
}
