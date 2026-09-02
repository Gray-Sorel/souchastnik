package dev.souchastnik.engine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import dev.souchastnik.data.Article
import dev.souchastnik.data.Articles

/**
 * Клиент движка внутри процесса IME. Тонкий: держит бинд, гасит дребезг
 * набора и выкидывает устаревшие ответы.
 */
class EngineClient(private val ctx: Context) {

    companion object {
        private const val TAG = "souchastnik-client"

        /**
         * Пауза в наборе, после которой запускаем разбор.
         * Меньше — модель считает то, что человек ещё дописывает, и жжёт
         * батарею впустую. Больше — строка отстаёт и шутка не читается.
         */
        const val DEBOUNCE_MS = 600L

        /** Короче — не бывает состава, бывает "ок" и "ага". */
        const val MIN_CHARS = 12
    }

    sealed interface State {
        object NoModel : State
        /** Процессор без dotprod/fp16: модель на этом телефоне не запускаем. */
        object Unsupported : State
        object Loading : State
        object Clean : State
        object Thinking : State
        data class Verdict(val article: Article) : State
    }

    var onState: ((State) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private var engine: IEngine? = null
    private var nextId = 1L
    private var lastSent: String? = null

    private val callback = object : IEngineCallback.Stub() {
        override fun onVerdict(requestId: Long, code: String?, latencyMs: Long) {
            if (requestId != nextId - 1) return  // пока считали, человек напечатал ещё
            val state = when {
                code == null || code == Articles.NONE -> State.Clean
                else -> Articles[code]?.let { State.Verdict(it) } ?: State.Clean
            }
            Log.d(TAG, "verdict=$code за $latencyMs мс")
            main.post { onState?.invoke(state) }
        }

        override fun onError(requestId: Long, message: String?) {
            if (message == "not_loaded") main.post { onState?.invoke(State.NoModel) }
            // "aborted" — это норма: человек продолжил печатать. Строку не трогаем.
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val e = IEngine.Stub.asInterface(binder)
            engine = e
            onState?.invoke(State.Loading)
            // load() читает с диска пол гига — не на главном потоке.
            Thread {
                val code = try {
                    e.load()
                } catch (t: Throwable) {
                    Log.e(TAG, "load упал", t); EngineService.LOAD_INIT_FAILED
                }
                val state = when (code) {
                    EngineService.LOAD_OK -> State.Clean
                    EngineService.LOAD_UNSUPPORTED_CPU -> State.Unsupported
                    else -> State.NoModel
                }
                main.post { onState?.invoke(state) }
            }.start()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // Процесс :engine убили по памяти. Клавиатура жива, строка гаснет.
            engine = null
            main.post { onState?.invoke(State.NoModel) }
        }
    }

    fun connect() {
        if (engine != null) return
        Articles.load(ctx)
        // BIND_IMPORTANT: процесс :engine получает приоритет клавиатуры, а
        // не фонового процесса. Иначе на телефонах с раздельными cpuset
        // (у Dimensity 700 фон = только четыре A55) модель уедет на
        // маленькие ядра: замер на BenchActivity под заблокированным
        // экраном — 18 с на фразу вместо 7 на больших ядрах.
        ctx.bindService(
            Intent(ctx, EngineService::class.java),
            conn,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
    }

    fun disconnect() {
        main.removeCallbacksAndMessages(null)
        try {
            engine?.unload()
            ctx.unbindService(conn)
        } catch (_: IllegalArgumentException) {
        } catch (_: android.os.RemoteException) {
        }
        engine = null
        lastSent = null
    }

    /** Вызывается на каждое нажатие клавиши. */
    fun onTextChanged(text: String) {
        val e = engine ?: return
        val trimmed = text.trim()

        // Текст не изменился (хост подвинул курсор, сработал onUpdateSelection) —
        // не трогаем ничего. Отменять здесь генерацию нельзя: она посчитана
        // ровно для этого текста, и, убив её, мы оставим строку в "…" навсегда.
        if (trimmed == lastSent) return

        main.removeCallbacksAndMessages(null)

        // А вот теперь текст другой, и то, что считается прямо сейчас,
        // уже неактуально.
        try {
            e.cancel()
        } catch (_: android.os.RemoteException) {
            return
        }

        if (trimmed.length < MIN_CHARS) {
            onState?.invoke(State.Clean)
            lastSent = null
            return
        }

        main.postDelayed({
            lastSent = trimmed
            onState?.invoke(State.Thinking)
            try {
                e.analyze(trimmed, nextId++, callback)
            } catch (t: Throwable) {
                Log.e(TAG, "analyze упал", t)
            }
        }, DEBOUNCE_MS)
    }
}
