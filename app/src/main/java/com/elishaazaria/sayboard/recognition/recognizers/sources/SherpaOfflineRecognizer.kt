package com.elishaazaria.sayboard.recognition.recognizers.sources

import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.utils.AppLog
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import java.util.Arrays
import java.util.Locale

/**
 * Non-streaming [Recognizer] over a sherpa-onnx OfflineRecognizer.
 *
 * Audio is buffered in [acceptWaveForm] (which always returns false, so no
 * partial results are emitted) and decoded exactly once in [getFinalResult].
 * Chunks are kept as primitive ShortArrays to bound memory on long takes.
 *
 * Tunables (R16):
 * - [MAX_BUFFERED_SAMPLES] (120s @16kHz mono): hard ceiling matching
 *   R2's 120s non-streaming take cap; excess audio is refused, never
 *   buffered unboundedly.
 * - Decode runs single-threaded on the stop executor with `cpu` provider,
 *   `debug=false`; thread count is set on the owning source
 *   (1..4 by CPU count).
 *
 * Ownership (R7): [release] is idempotent and releases the native
 * [OfflineRecognizer] exactly once inside [lock] with try/finally.
 */
class SherpaOfflineRecognizer(
    private val sherpa: OfflineRecognizer,
    override val sampleRate: Float,
    override val locale: Locale?
) : Recognizer {
    private val lock = Any()
    private val chunks = ArrayList<ShortArray>()
    private var bufferedSamples = 0
    private var released = false
    private var decodeFailures = 0
    private var capLogged = false

    override fun reset() {
        synchronized(lock) {
            if (released) return
            for (c in chunks) Arrays.fill(c, 0.toShort())
            chunks.clear()
            bufferedSamples = 0
        }
    }

    override fun acceptWaveForm(buffer: ShortArray?, nread: Int): Boolean {
        if (buffer == null || nread <= 0) return false
        synchronized(lock) {
            if (released) return false
            // R2 hard cap: refuse beyond 120s worth of samples instead of
            // growing chunks unboundedly until OOM.
            if (bufferedSamples >= MAX_BUFFERED_SAMPLES) {
                if (!capLogged) {
                    capLogged = true
                    AppLog.e(TAG, "buffer cap hit samples=$bufferedSamples")
                }
                return false
            }
            val room = MAX_BUFFERED_SAMPLES - bufferedSamples
            val take = if (nread > room) room else nread
            if (take <= 0) return false
            chunks.add(buffer.copyOf(take))
            bufferedSamples += take
        }
        // Never a final chunk: decode happens once in getFinalResult().
        return false
    }

    override fun getResult(): String = ""

    override fun getPartialResult(): String = ""

    override fun getFinalResult(): String {
        val pcm: ShortArray
        synchronized(lock) {
            if (released) return ""
            pcm = try {
                ShortArray(bufferedSamples)
            } catch (e: OutOfMemoryError) {
                AppLog.e(TAG, "pcm alloc OOM samples=$bufferedSamples")
                for (c in chunks) Arrays.fill(c, 0.toShort())
                chunks.clear()
                bufferedSamples = 0
                return ""
            }
            var offset = 0
            for (chunk in chunks) {
                System.arraycopy(chunk, 0, pcm, offset, chunk.size)
                Arrays.fill(chunk, 0.toShort())
                offset += chunk.size
            }
            chunks.clear()
            bufferedSamples = 0
            capLogged = false
            if (pcm.isEmpty()) return ""
        }
        var samples: FloatArray? = null
        try {
            AppLog.d(TAG, "decode start samples=${pcm.size}")
            val t0 = System.currentTimeMillis()
            samples = try {
                FloatArray(pcm.size) { i -> pcm[i] / 32768f }
            } catch (e: OutOfMemoryError) {
                AppLog.e(TAG, "float alloc OOM samples=${pcm.size}")
                return ""
            }
            val stream = try {
                sherpa.createStream()
            } catch (t: Throwable) {
                onDecodeFailure(t)
                return ""
            }
            try {
                stream.acceptWaveform(samples, sampleRate.toInt())
                sherpa.decode(stream)
                val text = removeSpaceForLocale(sherpa.getResult(stream).text.trim())
                decodeFailures = 0
                AppLog.d(
                    TAG,
                    "decode done ms=${System.currentTimeMillis() - t0} chars=${text.length}"
                )
                return text
            } finally {
                try {
                    stream.release()
                } catch (_: Throwable) {
                }
            }
        } catch (e: OutOfMemoryError) {
            onDecodeFailure(e)
            return ""
        } catch (t: Throwable) {
            // R14: persistent decode failure surfaces as an error log
            // (MySpeechService routes persistent failures to onError);
            // "" is returned only for true-empty above.
            onDecodeFailure(t)
            return ""
        } finally {
            // R5: zero audio after decode so mic residue never lingers.
            Arrays.fill(pcm, 0.toShort())
            if (samples != null) Arrays.fill(samples, 0f)
        }
    }

    /** R14: count consecutive failures; persistent ones log as errors. */
    private fun onDecodeFailure(t: Throwable) {
        decodeFailures++
        if (t is OutOfMemoryError) System.gc()
        if (decodeFailures >= PERSISTENT_FAILURES) {
            AppLog.e(TAG, "offline decode persistent failures=$decodeFailures")
        } else {
            AppLog.e(TAG, "offline decode failed failures=$decodeFailures")
        }
    }

    /**
     * Single-ownership native release (R7): idempotent, inside [lock]
     * with try/finally, never double-releases the native object.
     */
    fun release() {
        synchronized(lock) {
            if (released) return
            released = true
            try {
                for (c in chunks) {
                    try {
                        Arrays.fill(c, 0.toShort())
                    } catch (_: Throwable) {
                    }
                }
                chunks.clear()
                bufferedSamples = 0
            } finally {
                try {
                    sherpa.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    companion object {
        private const val TAG = "SherpaDecode"
        /** R2: 120s non-streaming cap @16kHz mono. */
        internal const val MAX_BUFFERED_SAMPLES = 120 * 16000
        private const val PERSISTENT_FAILURES = 3
    }
}
