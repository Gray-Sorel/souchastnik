package dev.souchastnik.engine;

import dev.souchastnik.engine.IEngineCallback;

interface IEngine {
    /** Загрузить модель. Возвращает false, если файла модели нет. */
    boolean load();

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
