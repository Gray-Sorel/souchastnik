package dev.souchastnik.engine

import android.util.Log
import java.io.File

/**
 * Что за процессор под нами: справка для логов и настроек и выбор числа
 * потоков. Читается из /proc и /sys без нативного кода.
 *
 * Какие инструкции использовать, решает не этот класс: ggml собран во всех
 * вариантах под arm64 (GGML_CPU_ALL_VARIANTS, см. build.gradle.kts), и мост
 * выбирает подходящий вариант по HWCAP в `load_cpu_backend()`. Здесь только
 * предупреждение о медленном пути.
 */
object Cpu {

    private const val TAG = "souchastnik-cpu"

    /**
     * Есть ли dotprod (`asimddp` в строке `Features` /proc/cpuinfo; имя то же,
     * что у HWCAP_ASIMDDP в ядре Linux).
     *
     * С ним ядра Q4_0 идут через «repack»-путь и на Dimensity 700 префиллят
     * ~73 т/с; без него ggml берёт вариант armv8.0 и на том же телефоне было
     * ~11 т/с — разбор фразы с промптом судьи в ~300 токенов растягивается на
     * десятки секунд. Без dotprod всё на Cortex-A53/A57/A72/A73: Kirin 710/970
     * (Honor 8X/9X, P30 Lite), Snapdragon 680/685, 662/665, Helio G25/G35,
     * Exynos 7870/7884. Ядра A55/A75 и новее (2018+) dotprod умеют.
     *
     * Если /proc/cpuinfo не читается — считаем, что есть: это только справка.
     */
    fun hasDotprod(): Boolean {
        val feats = features() ?: return true
        val has = "asimddp" in feats
        if (!has) Log.w(TAG, "процессор без dotprod; есть: $feats")
        return has
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
     * Kirin 710F (4×A73@2,2 + 4×A53@1,7) даёт 4. Однородные ядра (8×A55 на
     * Unisoc) — кластер один, и тогда по числу ядер: 8 → 4, 4 → 2. Если sysfs
     * не читается — так же.
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
