package dev.souchastnik.engine

/**
 * Тонкая обёртка над llama.cpp. Живёт только в процессе :engine.
 *
 * Модель отвечает ОДНИМ токеном-меткой, нативная часть делает argmax по
 * логитам меток и возвращает индекс. Никакого сэмплера и никакой
 * GBNF-грамматики в проекте нет: замер показал, что грамматический сэмплер
 * обходит весь словарь на 248 320 токенов и стоит сотни миллисекунд на
 * токен — в разы дороже самого вывода. Подробности в llama_bridge.cpp.
 */
object LlamaBridge {

    init {
        System.loadLibrary("souchastnik")
    }

    /** Имя файла модели внутри nativeLibraryDir. См. jniLibs/README.md */
    const val MODEL_LIB = "libmodel-qwen35-08b-q4km.so"

    /** Индекс "чисто" — статья не найдена. */
    const val INDEX_NONE = 0

    /** Ошибка или отмена. */
    const val INDEX_ERROR = -1

    /**
     * @param labels алфавит меток из [dev.souchastnik.data.Articles.labels];
     *   каждый символ обязан быть одним токеном, иначе init вернёт 0.
     * @return хендл движка, 0 — ошибка
     */
    external fun init(modelPath: String, labels: String, systemPrompt: String, nThreads: Int): Long

    /**
     * @param candidates индексы меток-кандидатов от [dev.souchastnik.data.Triggers];
     *   argmax берётся только по ним плюс «чисто». Пустой массив означает
     *   «искать среди всех» — но клиент до этого не доводит: если триггеры
     *   молчат, модель не запускается вообще.
     * @return индекс метки: 0 — чисто, >0 — статья, -1 — ошибка/отмена
     */
    external fun analyze(handle: Long, text: String, candidates: IntArray): Int

    external fun cancel(handle: Long)

    external fun free(handle: Long)

    // --- спайк ---

    /**
     * Пытается сохранить состояние после системного промпта и восстановить его.
     * У Qwen3.5 18 из 24 слоёв — Gated DeltaNet с рекуррентным состоянием,
     * а не KV-кэш, и seq-state save/restore для гибридных архитектур
     * в llama.cpp работает хуже, чем для чистых трансформеров.
     * Если здесь false — короткий системный промпт и полный префилл на
     * каждый запрос; на восьми токенах терять нечего.
     */
    external fun probeStateCache(handle: Long, path: String): Boolean

    /** @return [prefillMs, argmaxMs, labelIndex] */
    external fun benchOnce(handle: Long, text: String): LongArray
}
