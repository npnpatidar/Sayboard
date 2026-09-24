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
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Parakeet (NeMo transducer, e.g. TDT) recognizer source backed by
 * sherpa-onnx. Non-streaming: record, then transcribe on stop.
 *
 * Tunables (R16):
 * - [threads]: CPU count coerced to 1..4; more threads speed decode but
 *   raise peak RAM.
 * - `provider="cpu"`, `debug=false`, `modelType="nemo_transducer"`:
 *   on-device CPU only.
 * - [SHERPA_MAX_BYTES] (1.5GB): pre-flight sanity cap; refused with
 *   ERROR + fireLoaded(null), never stuck LOADING.
 * - Keep-in-RAM is owned by ModelManager; close(false) is a no-op.
 */
class SherpaParakeet(private val sherpaModel: SherpaLocalModel) : RecognizerSource {
    private val stateMLD = MutableLiveData(RecognizerState.NONE)
    override val stateLD: LiveData<RecognizerState>
        get() = stateMLD
    private var offline: OfflineRecognizer? = null
    private var myRecognizer: SherpaOfflineRecognizer? = null
    override val recognizer: Recognizer
        get() = myRecognizer!!
    override val isStreaming = false
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
            // flight: piggyback instead of stacking another load.
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
                    ?: throw IOException("Parakeet encoder .onnx missing")
                val decoder = ArchiveTools.findOnnxSmall(dir, "decoder")
                    ?: throw IOException("Parakeet decoder .onnx missing")
                val joiner = ArchiveTools.findOnnxSmall(dir, "joiner")
                    ?: throw IOException("Parakeet joiner .onnx missing")
                val tokens = ArchiveTools.findFile(dir, "tokens", ".txt")
                    ?: throw IOException("Parakeet tokens.txt missing")
                preflightOrThrow(listOf(encoder, decoder, joiner, tokens))
                val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
                val transducerConfig = OfflineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath
                )
                val modelConfig = OfflineModelConfig(
                    transducer = transducerConfig,
                    tokens = tokens.absolutePath,
                    numThreads = threads,
                    debug = false,
                    provider = "cpu",
                    modelType = "nemo_transducer"
                )
                val built = try {
                    OfflineRecognizer(config = OfflineRecognizerConfig(modelConfig = modelConfig))
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    throw e
                }
                offline = built
                AppLog.d(
                    TAG, "loaded leaves=[${encoder.name}, ${decoder.name}, " +
                        "${joiner.name}, ${tokens.name}] threads=$threads"
                )
                val recognizer = SherpaOfflineRecognizer(built, 16000.0f, sherpaModel.locale)
                handler.post {
                    if (closeRequested) {
                        // Freed while loading (rapid switch/rebuild): release
                        // instead of publishing an orphaned native model.
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
                Log.e(TAG, "parakeet load failed leaf=${leafName()} kind=${t.javaClass.simpleName}")
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
            // Joiners and stateless decoders are legitimately sub-MB:
            // head-only sniff, no floor. Encoder keeps the full check.
            val name = f.name.lowercase()
            val smallWeight = "joiner" in name || "decoder" in name
            val plausible = if (smallWeight) ArchiveTools.isPlausibleOnnxHead(f)
                else ArchiveTools.isPlausibleOnnx(f)
            if (name.endsWith(".onnx") && !plausible) {
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
     * OfflineRecognizer, so [offline] is nulled without a second release
     * when the recognizer is present (never double-release).
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
        get() = sherpaModel.alias.ifBlank { "Parakeet · ${sherpaModel.filename}" }

    override val locale: Locale
        get() = sherpaModel.locale

    companion object {
        private const val TAG = "SherpaParakeet"
        /** Pre-flight sanity cap for a Parakeet model dir. */
        internal const val SHERPA_MAX_BYTES = 1_500L * 1024 * 1024
    }
}
