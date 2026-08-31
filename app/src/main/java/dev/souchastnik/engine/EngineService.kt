package dev.souchastnik.engine

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.souchastnik.data.Articles
import dev.souchastnik.data.Triggers
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Живёт в процессе :engine (см. AndroidManifest). Здесь и только здесь
 * сидит модель на ~700 МБ RSS. Если Android прибьёт этот процесс по памяти,
 * клавиатура в своём процессе продолжит работать — просто строка погаснет
 * до следующего пере-бинда.
 */
class EngineService : Service() {

    companion object {
        private const val TAG = "souchastnik-engine"

        /**
         * Модель едет в APK как "нативная библиотека" и распаковывается
         * установщиком в nativeLibraryDir реальным файлом — его можно mmap-ить.
         * Классический путь через assets потребовал бы копии в filesDir,
         * то есть ещё 500 МБ на диске у пользователя.
         */
        fun modelFile(ctx: android.content.Context): File =
            File(ctx.applicationInfo.nativeLibraryDir, LlamaBridge.MODEL_LIB)
    }

    // Один поток: генерации строго по одной. Параллелить нечего — модель
    // одна, и второй запрос всё равно ждал бы блокировки.
    private val worker = Executors.newSingleThreadExecutor()

    private var handle = 0L
    private val latestRequest = AtomicLong(0)

    private val binder = object : IEngine.Stub() {

        override fun load(): Boolean {
            if (handle != 0L) return true
            val model = modelFile(this@EngineService)
            if (!model.exists()) {
                Log.w(TAG, "модель не установлена: ${model.absolutePath}")
                return false
            }
            Articles.load(this@EngineService)
            Triggers.load(this@EngineService)

            val t0 = System.currentTimeMillis()
            handle = LlamaBridge.init(
                model.absolutePath,
                Articles.labels(),
                Articles.systemPrompt,
                threadCount(),
            )
            Log.i(TAG, "load: handle=$handle за ${System.currentTimeMillis() - t0} мс")
            return handle != 0L
        }

        override fun unload() {
            val h = handle
            handle = 0L
            if (h != 0L) worker.execute { LlamaBridge.free(h) }
        }

        override fun isLoaded(): Boolean = handle != 0L

        override fun analyze(text: String?, requestId: Long, cb: IEngineCallback?) {
            if (text == null || cb == null) return
            latestRequest.set(requestId)

            // Человек нажал следующую клавишу — предыдущая генерация не нужна.
            // abort-колбэк в нативной части вернёт true и llama_decode выйдет.
            if (handle != 0L) LlamaBridge.cancel(handle)

            worker.execute {
                if (latestRequest.get() != requestId) return@worker  // устарел, пока ждал очереди
                val h = handle
                if (h == 0L) {
                    safe { cb.onError(requestId, "not_loaded") }
                    return@worker
                }
                // Словарь триггеров -- ДО модели. Молчат триггеры -- значит
                // в тексте нет ни одного слова, за которое хоть что-то
                // прилетает, и полгига модели крутить незачем.
                val candidates = Triggers.candidates(text)
                if (candidates.isEmpty()) {
                    safe { cb.onVerdict(requestId, Articles.NONE, 0) }
                    return@worker
                }

                val t0 = System.currentTimeMillis()
                val index = try {
                    LlamaBridge.analyze(h, text, candidates)
                } catch (t: Throwable) {
                    Log.e(TAG, "analyze упал", t)
                    LlamaBridge.INDEX_ERROR
                }
                val ms = System.currentTimeMillis() - t0

                if (latestRequest.get() != requestId) return@worker  // успел устареть, пока считали
                if (index == LlamaBridge.INDEX_ERROR) {
                    safe { cb.onError(requestId, "aborted") }
                } else {
                    // Индекс -> код статьи. Справочник загружен в этом же
                    // процессе, так что за Binder едет уже готовая строка.
                    val code = Articles.byIndex(index)?.code ?: Articles.NONE
                    safe { cb.onVerdict(requestId, code, ms) }
                }
            }
        }

        override fun cancel() {
            latestRequest.incrementAndGet()
            if (handle != 0L) LlamaBridge.cancel(handle)
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        binder.unload()
        worker.shutdown()
        super.onDestroy()
    }

    /**
     * Только большие ядра. Брать все — значит отдать часть работы
     * энергоэффективным ядрам, которые станут узким горлом, и заодно
     * подраться с UI-потоком приложения, в котором человек печатает.
     */
    private fun threadCount(): Int =
        Runtime.getRuntime().availableProcessors().let { n ->
            when {
                n >= 8 -> 4
                n >= 4 -> 2
                else -> 1
            }
        }

    /** Клиент мог умереть, пока мы считали: DeadObjectException — норма, не ошибка. */
    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: android.os.RemoteException) {
        }
    }
}
