// Соучастник -- нативный мост к llama.cpp.
//
// Задача узкая: получить строку, которую человек печатает, и вернуть индекс
// одной статьи из закрытого перечня.
//
// ПОЧЕМУ НЕ GBNF-ГРАММАТИКА. Первая версия зажимала вывод грамматикой на
// перечень кодов ("5.61" | "158" | ...). Замер на десктопе
// (x64, 4 потока) показал:
//
//     без грамматики          sampling =   2.3 мс
//     грамматика, 1 токен     sampling = 325.8 мс
//     грамматика, 3 токена    sampling = 571.9 мс
//
// Грамматический сэмплер обходит весь словарь -- у Qwen3.5 это 248 320
// токенов -- на каждом шаге. Инструмент, введённый ради скорости, оказался
// в сто раз дороже самого вывода, и на ARM было бы ещё хуже.
//
// Поэтому каждая статья обозначена ОДНИМ токеном (метка в articles.json).
// Нужен один шаг префилла, дальше argmax по 83 значениям логитов --
// это микросекунды, и никакого сэмплера в проекте больше нет.

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstring>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "souchastnik-native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

using steady = std::chrono::steady_clock;

long ms_since(steady::time_point t0) {
    return (long) std::chrono::duration_cast<std::chrono::milliseconds>(
        steady::now() - t0).count();
}

// Контекст держим крошечным. У модели заявлено 262K, нам нужно ~200:
// системный промпт плюс одно сообщение. Каждый лишний токен n_ctx --
// это байты состояния, которые мы платим из бюджета RSS.
constexpr int N_CTX = 512;

struct Engine {
    llama_model   * model = nullptr;
    llama_context * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;

    std::vector<llama_token> sys_tokens;
    // Токены меток, по индексам: 0 -- "чисто", дальше статьи.
    std::vector<llama_token> label_tokens;

    // Выставляется из cancel() и читается abort-колбэком llama.cpp:
    // пользователь нажал следующую клавишу -- текущий разбор не нужен.
    std::atomic<bool> abort_flag{false};
};

bool abort_cb(void * data) {
    Engine * e = static_cast<Engine *>(data);
    return e->abort_flag.load(std::memory_order_relaxed);
}

std::string jstr(JNIEnv * env, jstring s) {
    if (!s) return std::string();
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    env->ReleaseStringUTFChars(s, c);
    return out;
}

std::vector<llama_token> tokenize(const llama_vocab * vocab,
                                 const std::string & text,
                                 bool add_special) {
    int n = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                            nullptr, 0, add_special, /*parse_special*/ true);
    std::vector<llama_token> out(n);
    int got = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                             out.data(), n, add_special, true);
    if (got < 0) return std::vector<llama_token>();
    out.resize(got);
    return out;
}

// Сброс состояния между запросами. В новых ревизиях llama.cpp
// llama_kv_cache_clear заменён на llama_memory_clear -- и для гибридных
// моделей вроде Qwen3.5 сбрасывать надо именно через memory API,
// иначе рекуррентное состояние DeltaNet-слоёв переживёт запрос
// и следующий разбор поедет по контексту предыдущего.
void reset_state(Engine * e) {
    llama_memory_clear(llama_get_memory(e->ctx), /*data*/ true);
}

// Один проход: префилл системного промпта и сообщения, затем argmax
// по логитам меток. Возвращает индекс метки, -1 при ошибке или отмене.
//
// Здесь реализован ПЛАН Б: системный промпт префиллится заново на каждый
// запрос. Это дёшево ровно потому, что assets/prompt.txt короткий (~8
// токенов). План А -- переиспользование сохранённого состояния -- включаем
// только если сохранение состояния окажется рабочим: у Qwen3.5 вместо
// KV-кэша рекуррентное состояние DeltaNet-слоёв, и оно отматывается
// лишь до грубой контрольной точки -- переиспользовать удаётся около
// половины промпта, что для восьми токенов системной части не стоит
// усложнения.
//
// candidates -- индексы меток от словаря триггеров (Triggers.kt). argmax
// берётся только по ним плюс индекс 0 ("чисто"). Пустой список означает
// "по всем": так работает bench, а клиент до этого не доводит -- если
// триггеры молчат, модель не запускается вообще.
int run(Engine * e, const std::string & text,
        const std::vector<int> & candidates,
        long * out_prefill = nullptr,
        long * out_pick    = nullptr) {
    e->abort_flag.store(false);
    reset_state(e);

    // Формат запроса. Обучение адаптера должно использовать ровно этот же
    // формат, причём системная часть и сообщение токенизируются РАЗДЕЛЬНО:
    // при склейке в одну строку BPE может слить токены через границу, и
    // адаптер увидит в бою не тот формат, что при обучении.
    std::string user = "<msg>" + text + "</msg>\n";

    std::vector<llama_token> toks = e->sys_tokens;
    std::vector<llama_token> u = tokenize(e->vocab, user, /*add_special*/ false);
    toks.insert(toks.end(), u.begin(), u.end());

    if ((int) toks.size() >= N_CTX - 1) {
        // Человек пишет простыню -- берём хвост, там обычно и состав.
        int keep = N_CTX - 1 - (int) e->sys_tokens.size() - 1;
        if (keep < 8) return -1;
        std::vector<llama_token> tail(toks.end() - keep, toks.end());
        toks = e->sys_tokens;
        toks.insert(toks.end(), tail.begin(), tail.end());
    }

    steady::time_point t0 = steady::now();
    llama_batch batch = llama_batch_get_one(toks.data(), (int32_t) toks.size());
    if (llama_decode(e->ctx, batch) != 0) {
        LOGE("prefill failed");
        return -1;
    }
    if (out_prefill) *out_prefill = ms_since(t0);

    if (e->abort_flag.load(std::memory_order_relaxed)) return -1;

    steady::time_point t1 = steady::now();
    const float * logits = llama_get_logits_ith(e->ctx, -1);
    if (!logits) return -1;

    // Индекс 0 -- "чисто", он в выборе всегда: иначе модели некуда ответить
    // "состава нет", и она обязана назвать статью.
    int best = 0;
    float best_logit = logits[e->label_tokens[0]];

    if (candidates.empty()) {
        for (size_t i = 1; i < e->label_tokens.size(); ++i) {
            const float l = logits[e->label_tokens[i]];
            if (l > best_logit) { best_logit = l; best = (int) i; }
        }
    } else {
        for (int idx : candidates) {
            if (idx <= 0 || idx >= (int) e->label_tokens.size()) continue;
            const float l = logits[e->label_tokens[idx]];
            if (l > best_logit) { best_logit = l; best = idx; }
        }
    }
    if (out_pick) *out_pick = ms_since(t1);
    return best;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_dev_souchastnik_engine_LlamaBridge_init(JNIEnv * env, jobject,
                                            jstring jmodel, jstring jlabels,
                                            jstring jsystem, jint n_threads) {
    static std::atomic<bool> backend_ready{false};
    if (!backend_ready.exchange(true)) {
        llama_backend_init();
    }

    Engine * e = new Engine();
    const std::string model_path = jstr(env, jmodel);
    const std::string labels     = jstr(env, jlabels);
    const std::string system     = jstr(env, jsystem);

    llama_model_params mp = llama_model_default_params();
    mp.use_mmap  = true;   // модель лежит распакованной в nativeLibraryDir
    mp.use_mlock = false;  // на телефоне лочить 500 МБ нельзя
    mp.n_gpu_layers = 0;   // CPU. Vulkan-бэкенд для DeltaNet-ops пока лотерея,
                           // включать только после замеров на устройстве

    e->model = llama_model_load_from_file(model_path.c_str(), mp);
    if (!e->model) {
        LOGE("не удалось загрузить модель: %s", model_path.c_str());
        delete e;
        return 0;
    }
    e->vocab = llama_model_get_vocab(e->model);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = N_CTX;
    cp.n_batch         = N_CTX;
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    cp.abort_callback      = abort_cb;
    cp.abort_callback_data = e;

    e->ctx = llama_init_from_model(e->model, cp);
    if (!e->ctx) {
        LOGE("не удалось создать контекст");
        llama_model_free(e->model);
        delete e;
        return 0;
    }

    // Каждая метка ОБЯЗАНА быть одним токеном -- на этом стоит весь
    // расчёт. Если словарь модели сменился и метка распалась на два
    // токена, честнее упасть здесь, чем молча выдавать чужие статьи.
    for (char c : labels) {
        std::vector<llama_token> t = tokenize(e->vocab, std::string(1, c),
                                              /*add_special*/ false);
        if (t.size() != 1) {
            LOGE("метка '%c' не однотокенная (%d токенов) -- проверь алфавит "
                 "меток в articles.json против словаря модели", c, (int) t.size());
            llama_free(e->ctx);
            llama_model_free(e->model);
            delete e;
            return 0;
        }
        e->label_tokens.push_back(t[0]);
    }

    e->sys_tokens = tokenize(e->vocab, system, /*add_special*/ true);
    LOGI("движок готов: системный промпт %d токенов, меток %d, потоков %d",
         (int) e->sys_tokens.size(), (int) e->label_tokens.size(), (int) n_threads);

    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jint JNICALL
Java_dev_souchastnik_engine_LlamaBridge_analyze(JNIEnv * env, jobject,
                                                jlong handle, jstring jtext,
                                                jintArray jcandidates) {
    Engine * e = reinterpret_cast<Engine *>(handle);
    if (!e) return -1;

    std::vector<int> candidates;
    if (jcandidates) {
        const jsize n = env->GetArrayLength(jcandidates);
        candidates.resize(n);
        if (n > 0) {
            env->GetIntArrayRegion(jcandidates, 0, n, candidates.data());
        }
    }
    return run(e, jstr(env, jtext), candidates);
}

JNIEXPORT void JNICALL
Java_dev_souchastnik_engine_LlamaBridge_cancel(JNIEnv *, jobject, jlong handle) {
    Engine * e = reinterpret_cast<Engine *>(handle);
    if (e) e->abort_flag.store(true, std::memory_order_relaxed);
}

JNIEXPORT void JNICALL
Java_dev_souchastnik_engine_LlamaBridge_free(JNIEnv *, jobject, jlong handle) {
    Engine * e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    e->abort_flag.store(true);
    if (e->ctx)   llama_free(e->ctx);
    if (e->model) llama_model_free(e->model);
    delete e;
}

// --- диагностика ---

JNIEXPORT jboolean JNICALL
Java_dev_souchastnik_engine_LlamaBridge_probeStateCache(JNIEnv * env, jobject,
                                                       jlong handle, jstring jpath) {
    Engine * e = reinterpret_cast<Engine *>(handle);
    if (!e) return JNI_FALSE;
    const std::string path = jstr(env, jpath);

    reset_state(e);
    llama_batch b = llama_batch_get_one(e->sys_tokens.data(),
                                        (int32_t) e->sys_tokens.size());
    if (llama_decode(e->ctx, b) != 0) return JNI_FALSE;

    size_t written = llama_state_seq_save_file(e->ctx, path.c_str(), 0,
                                               e->sys_tokens.data(),
                                               e->sys_tokens.size());
    if (written == 0) {
        LOGE("state_seq_save вернул 0 -- сохранение состояния не поддержано");
        return JNI_FALSE;
    }

    reset_state(e);
    std::vector<llama_token> restored(e->sys_tokens.size());
    size_t n_restored = 0;
    size_t read = llama_state_seq_load_file(e->ctx, path.c_str(), 0,
                                            restored.data(), restored.size(),
                                            &n_restored);
    if (read == 0 || n_restored != e->sys_tokens.size()) {
        LOGE("state_seq_load не восстановил состояние (read=%d n=%d)",
             (int) read, (int) n_restored);
        return JNI_FALSE;
    }
    LOGI("prompt cache работает: %d байт, %d токенов",
         (int) written, (int) n_restored);
    return JNI_TRUE;
}

JNIEXPORT jlongArray JNICALL
Java_dev_souchastnik_engine_LlamaBridge_benchOnce(JNIEnv * env, jobject,
                                                  jlong handle, jstring jtext) {
    Engine * e = reinterpret_cast<Engine *>(handle);
    jlongArray res = env->NewLongArray(3);
    if (!e) return res;

    long prefill = 0, pick = 0;
    // Bench смотрит на модель без словаря: сколько она стоит сама по себе.
    int index = run(e, jstr(env, jtext), std::vector<int>(), &prefill, &pick);

    jlong vals[3] = { (jlong) prefill, (jlong) pick, (jlong) index };
    env->SetLongArrayRegion(res, 0, 3, vals);
    return res;
}

} // extern "C"
