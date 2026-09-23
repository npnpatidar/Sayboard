package com.elishaazaria.sayboard.recognition.recognizers.sources

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import com.elishaazaria.sayboard.AppCtx
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.SherpaLocalModel
import com.elishaazaria.sayboard.downloader.ArchiveTools
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import com.elishaazaria.sayboard.utils.AppLog
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Whisper recognizer source backed by sherpa-onnx (non-streaming: record,
 * then transcribe on stop). An empty language lets multilingual models
 * auto-detect the spoken language.
 *
 * Tunables (R16):
 * - [threads]: CPU count coerced to 1..4; more threads speed large-model
 *   decode but raise peak RAM.
 * - `provider="cpu"`, `debug=false`: on-device CPU only, no GPU/NNAPI path.
 * - `task="transcribe"` (never translate); [whisperLanguage] maps the
 *   locale or "" for auto-detect (invalid codes auto-detect because sherpa
 *   aborts the process on an invalid language).
 * - [SHERPA_MAX_BYTES] (1.5GB): pre-flight sanity cap; refused with
 *   ERROR + fireLoaded(null), never stuck LOADING.
 * - Keep-in-RAM is owned by ModelManager (`logicKeepModelInRam`); close(false)
 *   is a no-op so a kept model survives keyboard switches.
 */
class SherpaWhisper(private val sherpaModel: SherpaLocalModel) : RecognizerSource {
    private val stateMLD = MutableLiveData(RecognizerState.NONE)
    override val stateLD: LiveData<RecognizerState>
        get() = stateMLD
    private var offline: OfflineRecognizer? = null
    private var myRecognizer: SherpaOfflineRecognizer? = null
    override val recognizer: Recognizer
        get() = myRecognizer!!
    override val isStreaming = false

    /**
     * Universal when the model files look multilingual. English-only
     * (.en) Whisper builds stay locale-bound like everything else.
     *
     * R11: volatile cache + single-flight warming on the executor; the
     * walk must never run on the main or audio thread per field-open.
     */
    @Volatile
    private var universalCache: Boolean? = null
    @Volatile
    private var universalWarming = false
    override val isUniversal: Boolean
        get() {
            universalCache?.let { return it }
            val names = try {
                java.io.File(sherpaModel.path).walkTopDown().maxDepth(3)
                    .filter { it.isFile }
                    .map { it.name.lowercase() }
                    .toList()
            } catch (_: Exception) {
                return false
            } catch (e: OutOfMemoryError) {
                System.gc()
                return false
            }
            val hasWhisper = names.any { "encoder" in it && it.endsWith(".onnx") } &&
                names.any { "decoder" in it && it.endsWith(".onnx") }
            // Bare "tiny.en" / "base.en" as well as dotted/dashed forms.
            val enToken = Regex("(^|[.\\-_])en([.\\-_]|$)")
            val englishOnly = names.any { enToken.containsMatchIn(it) }
            return (hasWhisper && !englishOnly).also { universalCache = it }
        }

    /**
     * R11 single-flight universal warm on [executor]; safe to call from
     * any thread, runs the dir walk off the caller thread.
     */
    fun warmUniversalAsync(executor: Executor) {
        if (universalCache != null || universalWarming) return
        universalWarming = true
        try {
            executor.execute {
                try {
                    isUniversal
                } catch (_: Throwable) {
                } finally {
                    universalWarming = false
                }
            }
        } catch (_: Throwable) {
            universalWarming = false
        }
    }

    /** Set by close(true): a load still in flight must release instead of publishing. */
    @Volatile
    private var closeRequested = false
    private val closeLock = Any()
    @Volatile
    private var released = false
    /** Callbacks waiting on an in-flight load (binds can re-fire rapidly). */
    private val pendingCallbacks = ArrayList<Observer<RecognizerSource?>>()

    override fun initialize(executor: Executor, onLoaded: Observer<RecognizerSource?>) {
        synchronized(pendingCallbacks) {
            // Already loaded (e.g. kept in RAM): no native reload. A load in
            // flight: piggyback instead of stacking another ~200MB load.
            if (myRecognizer != null) {
                stateMLD.postValue(RecognizerState.READY)
                onLoaded.onChanged(this)
                return
            }
            if (stateMLD.value == RecognizerState.LOADING) {
                pendingCallbacks.add(onLoaded)
                return
            }
            pendingCallbacks.add(onLoaded)
        }
        synchronized(closeLock) {
            released = false
        }
        closeRequested = false
        stateMLD.postValue(RecognizerState.LOADING)
        val handler = Handler(Looper.getMainLooper())
        executor.execute {
            try {
                val dir = File(sherpaModel.path)
                val encoder = ArchiveTools.findOnnx(dir, "encoder")
                    ?: throw IOException("Whisper encoder .onnx missing")
                val decoder = ArchiveTools.findOnnx(dir, "decoder")
                    ?: throw IOException("Whisper decoder .onnx missing")
                val tokens = ArchiveTools.findFile(dir, "tokens", ".txt")
                    ?: throw IOException("Whisper tokens.txt missing")
                preflightOrThrow(listOf(encoder, decoder, tokens))
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                val whisperConfig = OfflineWhisperModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    language = whisperLanguage(sherpaModel.locale),
                    task = "transcribe"
                )
                val modelConfig = OfflineModelConfig(
                    whisper = whisperConfig,
                    tokens = tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                    modelType = "whisper"
                )
                val built = try {
                    OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    throw e
                }
                offline = built
                AppLog.d(
                    TAG, "loaded leaves=[${encoder.name}, ${decoder.name}, ${tokens.name}] " +
                        "langLen=${whisperConfig.language.length} threads=$threads"
                )
                val recognizer = SherpaOfflineRecognizer(built, 16000.0f, sherpaModel.locale)
                handler.post {
                    if (closeRequested) {
                        // Freed while loading: release instead of publishing
                        // an orphaned native model.
                        try {
                            recognizer.release()
                        } catch (_: Throwable) {
                        }
                        synchronized(closeLock) {
                            offline = null
                        }
                        stateMLD.postValue(RecognizerState.ERROR)
                        fireLoaded(null)
                    } else {
                        synchronized(closeLock) {
                            released = false
                            myRecognizer = recognizer
                        }
                        stateMLD.postValue(RecognizerState.READY)
                        fireLoaded(this)
                    }
                }
            } catch (e: OutOfMemoryError) {
                System.gc()
                AppLog.e(TAG, "load OOM leaf=${leafName()}")
                handler.post {
                    stateMLD.postValue(RecognizerState.ERROR)
                    fireLoaded(null)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "whisper load failed leaf=${leafName()} kind=${t.javaClass.simpleName}")
                AppLog.e(TAG, "load failed leaf=${leafName()} kind=${t.javaClass.simpleName} msg=${com.elishaazaria.sayboard.Tools.redactErrorMessage(t.message)}")
                handler.post {
                    stateMLD.postValue(RecognizerState.ERROR)
                    fireLoaded(null)
                }
            }
        }
    }

    /**
     * R1+R3 pre-flight: required files exist, each non-empty, each .onnx
     * passes the header sniff ([ArchiveTools.isPlausibleOnnx]), total under
     * [SHERPA_MAX_BYTES], and an ActivityManager memory gate. Throws (fail
     * fast before native construction) on any refusal.
     */
    @Throws(IOException::class)
    private fun preflightOrThrow(files: List<File>) {
        var total = 0L
        for (f in files) {
            if (!f.isFile) throw IOException("model file missing")
            val len = try {
                f.length()
            } catch (_: Throwable) {
                throw IOException("model stat failed")
            }
            if (len <= 0L || len > SHERPA_MAX_BYTES) throw IOException("model size refused")
            // R1: header sniff on each weight file, in addition to the
            // size/marker checks (findOnnx already sniff-gates selection).
            if (f.name.lowercase().endsWith(".onnx") && !ArchiveTools.isPlausibleOnnx(f)) {
                AppLog.e(TAG, "magic refused leaf=${f.name.take(80)}")
                throw IOException("model magic refused")
            }
            total += len
            if (total > SHERPA_MAX_BYTES) throw IOException("model total refused")
        }
        if (total <= 0L) throw IOException("model empty")
        if (!hasMemoryFor(total)) {
            AppLog.e(TAG, "memory refused bytes=$total leaf=${leafName()}")
            throw IOException("model memory refused")
        }
    }

    private fun hasMemoryFor(totalBytes: Long): Boolean {
        return try {
            val app: Context = AppCtx.appCtx ?: return totalBytes <= SHERPA_MAX_BYTES
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            totalBytes <= info.availMem / 2
        } catch (_: Throwable) {
            totalBytes <= SHERPA_MAX_BYTES
        }
    }

    private fun leafName(): String {
        return try {
            File(sherpaModel.path).name.take(80)
        } catch (_: Throwable) {
            "model"
        }
    }

    private fun fireLoaded(source: RecognizerSource?) {
        val callbacks: List<Observer<RecognizerSource?>>
        synchronized(pendingCallbacks) {
            callbacks = pendingCallbacks.toList()
            pendingCallbacks.clear()
        }
        callbacks.forEach { it.onChanged(source) }
    }

    override val closed: Boolean
        get() = myRecognizer == null

    override val addSpaces: Boolean
        get() = !listOf("ja", "zh").contains(sherpaModel.locale.language)

    /**
     * Single-ownership native release (R7): idempotent [released] flag
     * inside [closeLock] with try/finally. The recognizer owns the native
     * OfflineRecognizer, so when it is present the [offline] handle is
     * nulled without a second release (never double-release).
     */
    override fun close(freeRAM: Boolean) {
        if (!freeRAM) return
        synchronized(closeLock) {
            if (released) return
            released = true
            closeRequested = true
            val r = myRecognizer
            myRecognizer = null
            val o = offline
            offline = null
            try {
                try {
                    r?.release()
                } catch (_: Throwable) {
                }
            } finally {
                if (r == null && o != null) {
                    try {
                        o.release()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    override val errorMessage: Int
        get() = R.string.sherpa_error_load

    override val name: String
        get() = sherpaModel.alias.ifBlank { "Whisper · ${sherpaModel.filename}" }

    override val locale: Locale
        get() = sherpaModel.locale

    companion object {
        private const val TAG = "SherpaWhisper"
        /** Pre-flight sanity cap for a Whisper model dir. */
        internal const val SHERPA_MAX_BYTES = 1_500L * 1024 * 1024

        /**
         * Whisper language code for [locale], or "" for auto-detect.
         * Unknown/undefined locales auto-detect; codes outside Whisper's
         * language set also fall back to auto-detect because sherpa aborts
         * the process on an invalid language.
         */
        fun whisperLanguage(locale: Locale): String {
            val lang = locale.language
            if (lang.isEmpty() || lang == "und") return ""
            return if (WHISPER_LANGUAGES.contains(lang)) lang else ""
        }

        // Exact set from sherpa-onnx whisper language table; anything else
        // auto-detects because sherpa aborts on an invalid language.
        private val WHISPER_LANGUAGES = setOf(
            "hi", "cy", "oc", "so", "fr", "az", "eu", "ba", "no", "as",
            "nl", "bn", "es", "ml", "km", "mk", "sq", "mt", "et", "ms",
            "tr", "bg", "ps", "br", "ht", "tt", "tk", "la", "de", "ur",
            "ro", "fa", "uk", "mg", "lo", "sr", "yo", "id", "da", "pt",
            "nn", "sn", "sa", "sd", "gl", "ja", "pl", "ru", "ko", "ne",
            "kn", "zh", "be", "ca", "el", "it", "hu", "lt", "ta", "is",
            "jw", "fi", "bo", "sv", "mi", "hr", "bs", "yi", "sk", "lv",
            "af", "vi", "ha", "mn", "cs", "sl", "pa", "su", "ka", "ln",
            "lb", "sw", "en", "tl", "hy", "te", "he", "my", "haw", "fo",
            "kk", "si", "tg", "th", "ar", "am", "mr", "uz", "gu"
        )
    }
}
