package dev.souchastnik.data

import android.content.Context
import org.json.JSONObject

/**
 * Реестр для детерминированной пометки иноагентов.
 *
 * Человек дописывает фамилию из assets/agents.json и ставит пробел или знак
 * препинания — клавиатура сама добавляет после фамилии канцелярскую
 * пометку о статусе НАЗВАННОГО лица: «(ЮРИЙ ДУДЬ ПРИЗНАН(А) ИНОСТРАННЫМ
 * АГЕНТОМ И ВКЛЮЧЁН(А) В РЕЕСТР ИНОСТРАННЫХ АГЕНТОВ МИНИСТЕРСТВА ЮСТИЦИИ
 * РОССИЙСКОЙ ФЕДЕРАЦИИ НА ТЕРРИТОРИИ РОССИЙСКОЙ ФЕДЕРАЦИИ)». Пометка про
 * упомянутого человека, а не про автора сообщения. Модель не участвует:
 * это словарная подстановка, как автозамена, срабатывает всегда одинаково.
 *
 * Список — официальный статус из реестра Минюста, и перед релизом его надо
 * сверять с реестром (см. _source в файле). Ошибочная пометка человека,
 * которого в реестре нет, — не шутка.
 *
 * Тем же механизмом помечаются сервисы из раздела services: Instagram и
 * Facebook — «принадлежит компании Meta, признанной экстремистской», X и
 * Telegram — «доступ ограничен Роскомнадзором». Шаблон выбирается полем
 * kind из templates; статусы разные, и путать их нельзя (см. _templates).
 */
object Agents {

    /**
     * forms — начала слова; exact — слова целиком (для коротких форм вроде
     * «инст», «тг», «X», где совпадение по началу ловило бы «институт»).
     * strict — сравнение с учётом регистра.
     */
    private class Entry(
        val forms: List<String>,
        val exact: List<String>,
        val strict: Boolean,
        val marker: String,
    )

    private var entries: List<Entry> = emptyList()

    /** Куда в template подставляется ФИО заглавными. */
    private const val NAME_MARK = "{NAME}"

    fun load(ctx: Context) {
        if (entries.isNotEmpty()) return
        val raw = try {
            ctx.assets.open("agents.json").bufferedReader().use { it.readText() }
        } catch (_: java.io.IOException) {
            return
        }
        val root = JSONObject(raw)
        // Официальная формула маркировки, ФИО или название заглавными, как в
        // реестре. Громоздкость намеренная — в ней и шутка. Шаблонов
        // несколько: у иноагента, сервиса Meta и заблокированного сервиса
        // разный статус, и одной формулой их не описать.
        val templates = HashMap<String, String>()
        root.optJSONObject("templates")?.let { t ->
            for (k in t.keys()) templates[k] = t.getString(k)
        }
        root.optString("template", "").takeIf { it.isNotEmpty() }?.let { templates["agent"] = it }
        val out = ArrayList<Entry>()
        for (section in listOf("agents", "services")) {
            val arr = root.optJSONArray(section) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.optString("kind", "agent")
                val template = templates[kind]
                    ?: throw IllegalStateException("agents.json: нет шаблона для kind=$kind")
                val strict = o.optBoolean("strict", false)
                val forms = o.optJSONArray("forms").toStrings(strict)
                val exact = o.optJSONArray("exact").toStrings(strict)
                val marker = template.replace(NAME_MARK, o.getString("name").uppercase())
                out += Entry(forms, exact, strict, marker)
            }
        }
        entries = out
    }

    private fun org.json.JSONArray?.toStrings(strict: Boolean): List<String> {
        if (this == null) return emptyList()
        return (0 until length()).map { val f = getString(it); if (strict) f else f.lowercase() }
    }

    fun size(): Int = entries.size

    /**
     * Пометка для слова, которое человек только что дописал, либо null.
     *
     * Совпадение — по началу слова, чтобы покрыть падежи, либо слово целиком
     * (exact) для коротких форм: «инст», «тг», «X». Для фамилий,
     * совпадающих с обычными словами (Соболь, Волков, Медуза),
     * сравнение строгое: с учётом регистра и только с заглавной буквы,
     * иначе «белый снег» получил бы пометку. Ограничение в три буквы — только
     * для совпадения по началу: точным формам оно мешало бы.
     */
    fun markerFor(word: String): String? {
        if (word.isEmpty()) return null
        val lower = word.lowercase()
        for (e in entries) {
            val w = if (e.strict) word else lower
            for (form in e.exact) if (w == form) return e.marker
            if (word.length < 3) continue
            for (form in e.forms) if (w.startsWith(form)) return e.marker
        }
        return null
    }

    /**
     * Последнее слово перед курсором, если курсор стоит сразу за ним.
     * Слово — буквы, дефис и апостроф; цифры и знаки не в счёт.
     */
    fun lastWord(before: CharSequence): String? {
        var end = before.length
        var start = end
        while (start > 0) {
            val c = before[start - 1]
            if (c.isLetter() || c == '-' || c == '\'') start-- else break
        }
        if (start == end) return null
        return before.subSequence(start, end).toString()
    }

    /**
     * Многословные названия («Новая газета Европа»): проверяем хвост текста
     * целиком, до трёх слов. Возвращает пометку либо null.
     */
    fun markerForTail(before: CharSequence): String? {
        val tail = before.takeLast(64).toString()
        val lower = tail.lowercase()
        for (e in entries) {
            for (form in e.forms) {
                if (!form.contains(' ')) continue
                val hit = if (e.strict) tail.endsWith(form) else lower.endsWith(form)
                if (hit) return e.marker
            }
        }
        return null
    }
}
