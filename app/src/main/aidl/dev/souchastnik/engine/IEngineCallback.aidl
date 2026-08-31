package dev.souchastnik.engine;

interface IEngineCallback {
    /**
     * @param code    код статьи из assets/articles.json, либо "none"
     * @param latencyMs время генерации, для докрутки дебаунса и спайка
     */
    oneway void onVerdict(long requestId, String code, long latencyMs);

    oneway void onError(long requestId, String message);
}
