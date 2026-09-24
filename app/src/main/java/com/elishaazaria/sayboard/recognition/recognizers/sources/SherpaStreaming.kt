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
import com.elishaazaria.sayboard.BuildConfig
import com.elishaazaria.sayboard.R
import com.elishaazaria.sayboard.data.SherpaLocalModel
import com.elishaazaria.sayboard.downloader.ArchiveTools
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerSource
import com.elishaazaria.sayboard.recognition.recognizers.RecognizerState
import com.elishaazaria.sayboard.utils.AppLog
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.IOException
import java.util.Arrays
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Live-transcription source over sherpa-onnx OnlineRecognizer: NeMo unified
 * streaming checkpoints and zipformer streaming exports alike (the native
 * factory picks its implementation from the model files on its own).
 * Behaves like Vosk: live partials while recording, endpointed segments
 * as results.
 *
 * Tunables (R16):
 * - `numThreads=1`, `provider="cpu"`, `debug=false`: single-threaded CPU
 *   streaming; higher threads gain little (mic-paced) and risk overruns.
 * - [SHERPA_MAX_BYTES] (1.5GB): pre-flight sanity cap; refused with
 *   ERROR + fireLoaded(null), never stuck LOADING.
 * - Keep-in-RAM is owned by ModelManager; close(false) is a no-op.
 *
 * Ownership (R7): native OnlineRecognizer/stream are released exactly once
 * inside their locks with try/finally; [released] flags make release
 * idempotent.
 */
class SherpaStreaming(private val sherpaModel: SherpaLocalModel) : RecognizerSource {
    private val stateMLD = MutableLiveData(RecognizerState.NONE)
    override val stateLD: LiveData<RecognizerState>
        get() = stateMLD
    private var offline: OnlineRecognizer? = null
    private var myRecognizer: StreamingRecognizer? = null
    override val recognizer: Recognizer
        get() = myRecognizer!!
    // Streaming: the default. Partials flow through MySpeechService unchanged.

    /** Set by close(true): a load still in flight must release instead of publishing. */
    @Volatile
    private var closeRequested = false
    private val closeLock = Any()
    @Volatile
    private var released = false
    /** Callbacks waiting on an in-flight load (binds can re-fire rapidly). */
    private val pendingCallbacks = ArrayList<Observer<RecognizerSource?>>()

    private val familyLabel: String
        get() = if (ArchiveTools.isZipformerStreaming(File(sherpaModel.path))) {
            "Zipformer live"
        } else {
            "Parakeet live"
        }

    override fun initialize(executor: Executor, onLoaded: Observer<RecognizerSource?>) {
        synchronized(pendingCallbacks) {
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
                    ?: throw IOException("Streaming encoder .onnx missing")
                val decoder = ArchiveTools.findOnnxSmall(dir, "decoder")
                    ?: throw IOException("Streaming decoder .onnx missing")
                val joiner = ArchiveTools.findOnnxSmall(dir, "joiner")
                    ?: throw IOException("Streaming joiner .onnx missing")
                val tokens = ArchiveTools.findFile(dir, "tokens", ".txt")
                    ?: throw IOException("Streaming tokens.txt missing")
                preflightOrThrow(listOf(encoder, decoder, joiner, tokens))
                val transducerConfig = OnlineTransducerModelConfig(
                    encoder = encoder.absolutePath,
                    decoder = decoder.absolutePath,
                    joiner = joiner.absolutePath
                )
                val modelConfig = OnlineModelConfig(
                    transducer = transducerConfig,
                    tokens = tokens.absolutePath,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                    modelType = ""
                )
                val built = try {
                    OnlineRecognizer(config = OnlineRecognizerConfig(modelConfig = modelConfig))
                } catch (e: OutOfMemoryError) {
                    System.gc()
                    throw e
                }
                offline = built
                AppLog.d(
                    TAG, "loaded leaves=[${encoder.name}, ${decoder.name}, " +
                        "${joiner.name}, ${tokens.name}]"
                )
                val recognizer = StreamingRecognizer(built, 16000.0f, sherpaModel.locale)
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
                Log.e(TAG, "streaming load failed leaf=${leafName()} kind=${t.javaClass.simpleName}")
                AppLog.e(TAG, "streaming load failed leaf=${leafName()} kind=${t.javaClass.simpleName} msg=${com.elishaazaria.sayboard.Tools.redactErrorMessage(t.message)}")
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
     * OnlineRecognizer, so [offline] is nulled without a second release
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
        get() = sherpaModel.alias.ifBlank { "$familyLabel · ${sherpaModel.filename}" }

    override val locale: Locale
        get() = sherpaModel.locale

    /**
     * Streaming [Recognizer]: each mic buffer is decoded when a full chunk
     * is ready (decoding early is fatal for some implementations);
     * endpointed segments surface as results, everything else as partials.
     */
    private class StreamingRecognizer(
        private val sherpa: OnlineRecognizer,
        override val sampleRate: Float,
        override val locale: Locale?
    ) : Recognizer {
        private val lock = Any()
        private var stream: OnlineStream? = null
        private var released = false
        private var loggedFirst = false
        private var buffers = 0
        private var zeroReads = 0
        private var peak = 0f
        private var lastCallMs = 0L
        private var sumDecodeMs = 0L
        private var sumIntervalMs = 0L
        private var decodeFailures = 0

        private fun liveStream(): OnlineStream? {
            synchronized(lock) {
                if (released) return null
                if (stream == null) {
                    try {
                        stream = sherpa.createStream()
                    } catch (_: Throwable) {
                        return null
                    }
                }
                return stream
            }
        }

        override fun reset() {
            synchronized(lock) {
                if (released) return
                try {
                    try {
                        stream?.release()
                    } catch (_: Throwable) {
                    }
                } finally {
                    stream = null
                    // Reset every probe counter: averages divide stale sums by a
                    // reset count otherwise, and the first interval would include
                    // inter-take idle time (phantom overrun).
                    loggedFirst = false
                    buffers = 0
                    zeroReads = 0
                    peak = 0f
                    lastCallMs = 0L
                    sumDecodeMs = 0L
                    sumIntervalMs = 0L
                    decodeFailures = 0
                }
            }
        }

        override fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean {
            val s = liveStream() ?: return false
            if (buffer == null || nread <= 0) {
                zeroReads++
                return false
            }
            var samples: FloatArray? = null
            return try {
                samples = FloatArray(nread) { i -> buffer[i] / 32768f }
                var max = 0f
                for (v in samples) {
                    val a = if (v < 0) -v else v
                    if (a > max) max = a
                }
                if (max > peak) peak = max
                s.acceptWaveform(samples, sampleRate.toInt())
                val now = System.currentTimeMillis()
                if (lastCallMs != 0L) sumIntervalMs += now - lastCallMs
                lastCallMs = now
                val t0 = System.currentTimeMillis()
                while (sherpa.isReady(s)) {
                    sherpa.decode(s)
                }
                sumDecodeMs += System.currentTimeMillis() - t0
                buffers++
                decodeFailures = 0
                if (BuildConfig.DEBUG && !loggedFirst) {
                    loggedFirst = true
                    AppLog.d(TAG, "streaming decode flowing")
                }
                // Periodic state: audio peak proves the mic feeds real
                // speech; partial length + endpoint show decoder response.
                // Interval/decode averages diagnose recorder overrun: the
                // loop must turn faster than the 0.2s audio it consumes.
                // Debug-only: three extra native calls per probe on the mic
                // thread. Endpoint defaults stay native until tuned on-device.
                if (BuildConfig.DEBUG && buffers % 5 == 1) {
                    val partial = try {
                        sherpa.getResult(s).text.trim().length
                    } catch (_: Exception) {
                        -1
                    }
                    val endpoint = try {
                        sherpa.isEndpoint(s)
                    } catch (_: Exception) {
                        false
                    }
                    val ready = try {
                        sherpa.isReady(s)
                    } catch (_: Exception) {
                        false
                    }
                    AppLog.d(
                        TAG, "streaming buffers=$buffers peak=$peak " +
                            "zeroReads=$zeroReads partialChars=$partial " +
                            "ready=$ready endpoint=$endpoint " +
                            "avgIntervalMs=${sumIntervalMs / buffers} " +
                            "avgDecodeMs=${sumDecodeMs / buffers}"
                    )
                    peak = 0f
                }
                sherpa.isEndpoint(s)
            } catch (t: Throwable) {
                decodeFailures++
                if (t is OutOfMemoryError) System.gc()
                // R14: redacted counts only, never hypothesis text.
                if (decodeFailures >= PERSISTENT_FAILURES) {
                    AppLog.e(TAG, "streaming accept persistent failures=$decodeFailures")
                } else {
                    AppLog.e(TAG, "streaming accept failed failures=$decodeFailures")
                }
                false
            } finally {
                if (samples != null) Arrays.fill(samples, 0f)
            }
        }

        override fun getResult(): String {
            val s = synchronized(lock) { if (released) null else stream } ?: return ""
            return try {
                val text = removeSpaceForLocale(sherpa.getResult(s).text.trim())
                // R4: redacted to length only, never endpoint text.
                if (text.isNotEmpty()) AppLog.d(TAG, "streaming endpoint chars=${text.length}")
                decodeFailures = 0
                // Fresh decoding state for the audio after this endpoint.
                sherpa.reset(s)
                text
            } catch (t: Throwable) {
                decodeFailures++
                if (t is OutOfMemoryError) System.gc()
                AppLog.e(TAG, "streaming getResult failed failures=$decodeFailures")
                ""
            }
        }

        override fun getPartialResult(): String {
            val s = synchronized(lock) { if (released) null else stream } ?: return ""
            return try {
                removeSpaceForLocale(sherpa.getResult(s).text.trim())
            } catch (t: Throwable) {
                decodeFailures++
                if (t is OutOfMemoryError) System.gc()
                // Partials fail every buffer on some builds: count quietly.
                if (decodeFailures >= PERSISTENT_FAILURES) {
                    AppLog.e(TAG, "streaming partial persistent failures=$decodeFailures")
                }
                ""
            }
        }

        override fun getFinalResult(): String {
            val s = synchronized(lock) { if (released) null else stream } ?: return ""
            return try {
                s.inputFinished()
                while (sherpa.isReady(s)) {
                    sherpa.decode(s)
                }
                val text = removeSpaceForLocale(sherpa.getResult(s).text.trim())
                AppLog.d(TAG, "streaming final chars=${text.length}")
                decodeFailures = 0
                // Clean boundary for the next take.
                reset()
                text
            } catch (t: Throwable) {
                decodeFailures++
                if (t is OutOfMemoryError) System.gc()
                AppLog.e(TAG, "streaming getFinalResult failed failures=$decodeFailures")
                try {
                    reset()
                } catch (_: Throwable) {
                }
                ""
            }
        }

        /**
         * Single-ownership native release (R7): idempotent, inside [lock]
         * with try/finally; the stream and the recognizer are each
         * released exactly once, never double-released.
         */
        fun release() {
            synchronized(lock) {
                if (released) return
                released = true
                try {
                    try {
                        stream?.release()
                    } catch (_: Throwable) {
                    }
                } finally {
                    stream = null
                    try {
                        sherpa.release()
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    companion object {
        private const val TAG = "SherpaStreaming"
        /** Pre-flight sanity cap for a streaming model dir. */
        internal const val SHERPA_MAX_BYTES = 1_500L * 1024 * 1024
        private const val PERSISTENT_FAILURES = 3
    }
}
