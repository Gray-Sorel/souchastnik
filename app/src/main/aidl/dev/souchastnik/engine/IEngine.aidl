package dev.souchastnik.engine;

import dev.souchastnik.engine.IEngineCallback;

interface IEngine {
    /**
     * Загрузить модель. Коды ответа:
     *   0 — готово;
     *   1 — файла модели нет (сборка без весов);
     *   2 — процессор не умеет инструкции, под которые собран ggml
     *       (dotprod/fp16, см. engine/Cpu.kt); нативную библиотеку даже не трогали;
     *   3 — llama.cpp не смогла загрузить модель (см. logcat souchastnik-native).
     */
    int load();

    /** Выгрузить модель и освободить память (вызывается при выключении тумблера). */
    void unload();

    boolean isLoaded();

    /**
     * Асинхронный разбор. requestId монотонно растёт; движок бросает
     * генерацию предыдущего запроса, ответ на устаревший id клиент игнорирует.
     */
    oneway void analyze(String text, long requestId, IEngineCallback cb);

    /** Отменить текущую генерацию (пользователь снова начал печатать). */
    oneway void cancel();
}
