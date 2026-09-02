package dev.souchastnik.engine

import android.util.Log
import java.io.File

/**
 * Что за процессор под нами. Читается из /proc и /sys без единой строчки
 * нативного кода — намеренно: libggml-cpu собрана с
 * `-march=armv8.2-a+dotprod+fp16` (см. app/build.gradle.kts), и на ядре без
 * этих инструкций уже её загрузка может кончиться SIGILL. Поэтому решать,
 * трогать ли [LlamaBridge] вообще, надо ДО `System.loadLibrary`.
 *
 * Обращаться к `LlamaBridge.MODEL_LIB` при этом можно: это `const val`,
 * компилятор подставляет строку по месту, объект не инициализируется.
 */
object Cpu {

    private const val TAG = "souchastnik-cpu"

    /**
     * Флаги из строки `Features` в /proc/cpuinfo, которых требует сборка.
     * dotprod — `asimddp`; fp16 — пара `fphp` (скаляры) и `asimdhp`
     * (вектора), ggml проверяет обе. Имена те же, что у HWCAP в ядре Linux.
     */
    private val REQUIRED = listOf("asimddp", "fphp", "asimdhp")

    /**
     * Есть ли у процессора инструкции, под которые собран ggml.
     *
     * Нет их у всего на Cortex-A53/A57/A72/A73 (armv8.0): Snapdragon 680/685,
     * 662/665, Helio G25/G35, Exynos 7870/7884, Kirin 970 и старше. Это
     * по-прежнему заметная часть парка бюджетных телефонов. Ядра A55/A75 и
     * новее (2018+) всё это умеют.
     *
     * Если /proc/cpuinfo не читается — считаем, что можно: лучше редкий
     * SIGILL на экзотической прошивке, чем отключённая модель у всех, кому
     * вендор закрыл procfs.
     */
    fun supported(): Boolean {
        val feats = features() ?: return true
        val missing = REQUIRED.filterNot { it in feats }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "процессору не хватает: $missing; есть: $feats")
        }
        return missing.isEmpty()
    }

    /**
     * Пересечение флагов `Features` по всем ядрам. На big.LITTLE строк
     * несколько, и ядро всё равно даёт процессу только общий набор, но
     * читаем всё, а не первую попавшуюся строку.
     *
     * @return null, если файл недоступен или строк `Features` в нём нет
     */
    fun features(): Set<String>? {
        val lines = try {
            File("/proc/cpuinfo").readLines()
        } catch (t: Throwable) {
            Log.w(TAG, "/proc/cpuinfo не читается", t)
            return null
        }
        var common: Set<String>? = null
        for (line in lines) {
            if (!line.startsWith("Features")) continue
            val set = line.substringAfter(':').trim().split(Regex("\\s+")).toSet()
            common = common?.intersect(set) ?: set
        }
        return common
    }

    /**
     * Сколько потоков отдать модели: столько, сколько «больших» ядер, но не
     * меньше двух и не больше четырёх.
     *
     * Брать все ядра — значит отдать часть работы энергоэффективным, которые
     * станут узким горлом (llama.cpp синхронизирует потоки на каждой
     * операции, темп задаёт самый медленный), и заодно подраться с
     * UI-потоком приложения, в котором человек печатает. Замер на
     * Dimensity 700 (2×A76@2,4 + 6×A55@2,0): 2 потока дали медиану 7,0 с,
     * 4 потока — 8,6 с.
     *
     * Большие ядра — все, что НЕ в самом медленном кластере по
     * cpuinfo_max_freq. Прежний критерий «не медленнее 90% самого быстрого»
     * ошибался на двух распространённых раскладках:
     *   - 1 прайм + 3–4 средних + маленькие (Snapdragon 8 Gen 2:
     *     3,2 + 4×2,8 + 3×2,0 ГГц) — попадал только прайм, модель получала
     *     два потока вместо четырёх;
     *   - большие едва быстрее маленьких (Helio G85: 2×A75@2,0 + 6×A55@1,8)
     *     — по частоте разница 10%, а по IPC двукратная.
     *
     * Однородные ядра (8×A55 на Unisoc) — кластер один, и тогда по числу
     * ядер: 8 → 4, 4 → 2. Если sysfs не читается — так же.
     */
    fun threadCount(): Int {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).mapNotNull { i ->
            try {
                File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq")
                    .readText().trim().toLongOrNull()
            } catch (_: Throwable) {
                null
            }
        }
        val threads = if (freqs.size >= 2) {
            val little = freqs.min()
            // 3% — запас на кластеры вроде 1 800 000 и 1 804 000 кГц, это
            // одна и та же ступень, а не два разных кластера.
            val big = freqs.count { it > little * 103 / 100 }
            if (big > 0) big.coerceIn(2, 4) else byCount(freqs.size)
        } else {
            byCount(n)
        }
        Log.i(TAG, "ядер $n, частоты кГц $freqs → потоков $threads")
        return threads
    }

    private fun byCount(n: Int): Int = when {
        n >= 8 -> 4
        n >= 4 -> 2
        else -> 1
    }
}
