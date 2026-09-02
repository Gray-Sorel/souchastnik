package dev.souchastnik.data

import android.content.Context
import org.json.JSONObject

/**
 * Примеры фраз для промпта — те же, что подмешивает tools/try.py.
 *
 * Зачем они нужны. Без примеров модель осторожничает и отвечает none:
 * на 35 контрольных фразах с составом судья без примеров находит 16,
 * с примерами — 29 (tools/bench_variants.py). Примеры задают порог, при
 * котором «дурак» это состав, а раздражение — нет.
 *
 * Файла может не быть: assets/examples.json лежит в .gitignore, потому что
 * публиковать дословные оскорбления под своим именем — лишний риск. Тогда
 * промпт собирается без примеров, и это рабочий, хоть и осторожный, режим.
 */
object Examples {

    /** Примеры по коду статьи: «фраза» → код. */
    private var shots: Map<String, List<String>> = emptyMap()

    /** Чистые примеры. Идут в промпт всегда: они держат порог none. */
    private var clean: List<String> = emptyList()

    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true

        val raw = try {
            ctx.assets.open("examples.json").bufferedReader().use { it.readText() }
        } catch (e: java.io.IOException) {
            android.util.Log.i("souchastnik-data", "examples.json нет — промпт без примеров")
            return
        }

        val root = JSONObject(raw)
        root.optJSONObject("shots")?.let { o ->
            val map = LinkedHashMap<String, List<String>>()
            for (code in o.keys()) {
                map[code] = o.getJSONArray(code).let { a ->
                    (0 until a.length()).map { a.getString(it) }
                }
            }
            shots = map
        }
        clean = root.optJSONArray("clean").strings()
    }

    private fun org.json.JSONArray?.strings(): List<String> =
        if (this == null) emptyList() else (0 until length()).map { getString(it) }

    fun isEmpty(): Boolean = shots.isEmpty() && clean.isEmpty()

    /**
     * Хвост системного сообщения судьи: примеры только по статьям-кандидатам
     * плюс контрпримеры сработавших групп.
     *
     * Контрпример сработавшей группы — в той же лексике, что и запрос.
     * Именно он не даёт модели схватить статью за одно ключевое слово.
     *
     * Общие чистые примеры (поле clean) в промпт НЕ идут. Замер на 66
     * контрольных фразах (tools/bench_variants.py, judge против judge_lite):
     * с ними 29/35 по составу и 21/31 по чистым при 298 токенах, без них
     * 22/35 и 28/31 при 235 токенах — меньше ложных, короче префилл, и ни
     * одной перепутанной статьи. Порог «чисто» при этом не нужен.
     */
    fun judgeBlock(codes: List<String>, groupClean: List<String>): String {
        val lines = ArrayList<String>()
        for (code in codes) {
            shots[code]?.forEach { lines += "«$it» → $code" }
        }
        for (phrase in groupClean) lines += "«$phrase» → none"
        return if (lines.isEmpty()) "" else "\nПримеры:\n" + lines.joinToString("\n") + "\n"
    }
}
