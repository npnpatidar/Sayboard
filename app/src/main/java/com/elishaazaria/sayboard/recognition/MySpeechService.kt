/*
 * org.vosk.SpeechService, extended to support other recognizers.
 */
package com.elishaazaria.sayboard.recognition

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresPermission
import com.elishaazaria.sayboard.recognition.recognizers.Recognizer
import com.elishaazaria.sayboard.utils.AppLog
import org.vosk.android.RecognitionListener
import java.io.IOException
import java.util.Arrays
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Sole AudioRecord owner for a single take.
 *
 * Tunables (R16):
 * - [BUFFER_SIZE_SECONDS] (0.2s): mic frame; larger smooths decode at the
 *   cost of partial latency.
 * - [MAX_TAKE_SEC_NON_STREAMING] (120s) / [MAX_TAKE_SEC_STREAMING] (300s)
 *   (R2): total-take caps; [vadAutoStop]=true selects the non-streaming
 *   cap, otherwise the streaming cap. Beyond the cap the take auto-stops
 *   with [RecognitionListener.onError].
 * - [MAX_BUFFERED_BYTES] (10MB ≈ 300s @16kHz mono16): absolute ceiling on
 *   audio accepted per take; excess ends the take with an error, never an
 *   unbounded buffer.
 * - [PRE_SPEECH_TIMEOUT_MS] (15s) (R5): VAD takes with no detected speech
 *   end via [RecognitionListener.onTimeout] instead of holding the mic
 *   forever.
 * - [JOIN_TIMEOUT_MS] (2s) (R6): stop never blocks forever; a
 *   stopper-thread `recorder.stop()` unblocks a wedged `read`, then
 *   `join(timeout)`.
 * - VAD thresholds live in [VadHolder] (`threshold`, `minSilence`,
 *   `minSpeech`); [vadAutoStop] only enables trailing-silence end for
 *   non-streaming takes at 16kHz.
 */
class MySpeechService @RequiresPermission(Manifest.permission.RECORD_AUDIO) constructor(
    private val recognizer: Recognizer, sampleRate: Float,
    attributionContext: Context? = null,
    /** End the take after ~1s of trailing silence (non-streaming only). */
    private val vadAutoStop: Boolean = false,
    /** Context for the bundled VAD model; null disables VAD silently. */
    vadContext: Context? = null,
    /** Fired on the main thread when VAD ends the take by itself. */
    private val onTakeFinished: (() -> Unit)? = null
) {
    private val sampleRate: Int
    private val bufferSize: Int
    private val recorder: AudioRecord
    private var recognizerThread: RecognizerThread? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    /** R8: never retain the caller's raw context; application only. */
    private val appVadContext: Context? = vadContext?.applicationContext
    /** R6: set on stop so a wedged read loop terminates. */
    @Volatile
    private var stopped = false

    init {
        this.sampleRate = sampleRate.toInt()
        // R6: getMinBufferSize-based sizing; never below the platform floor.
        val minBytes = try {
            AudioRecord.getMinBufferSize(
                this.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
        } catch (_: Throwable) {
            AudioRecord.ERROR_BAD_VALUE
        }
        val validMin = if (minBytes > 0) minBytes else (this.sampleRate * 2)
        val frameBytes = max(validMin, (this.sampleRate.toFloat() * BUFFER_SIZE_SECONDS * 2).roundToInt())
        bufferSize = max(1, frameBytes / 2)
        recorder = AudioRecord.Builder().apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && attributionContext != null) {
                setContext(attributionContext)
            }
            setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            setAudioFormat(AudioFormat.Builder().apply {
                setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                setSampleRate(this@MySpeechService.sampleRate)
                setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            }.build())
            setBufferSizeInBytes(max(frameBytes * 2, validMin * 2))
        }.build()

        if (recorder.state == AudioRecord.STATE_UNINITIALIZED) {
            try {
                recorder.release()
            } catch (_: Throwable) {
            }
            throw IOException("Failed to initialize recorder. Microphone might be already in use.")
        }
    }

    fun startListening(listener: RecognitionListener): Boolean {
        // Wired to the timeout overload (R6): default takes run under the
        // R2 total-take caps with no explicit caller timeout.
        return startListening(listener, NO_TIMEOUT)
    }

    var recordDevice: AudioDeviceInfo?
        get() = try {
            recorder.routedDevice
        } catch (_: Throwable) {
            null
        }
        set(value) {
            try {
                recorder.preferredDevice = value
            } catch (_: Throwable) {
            }
        }

    /**
     * Start with an explicit caller timeout (ms, [NO_TIMEOUT] for none).
     * The R2 total-take caps still apply on top: the take ends at
     * min(caller timeout, engine cap).
     */
    fun startListening(listener: RecognitionListener, timeout: Int): Boolean {
        if (null != recognizerThread) {
            return false
        }
        stopped = false
        recognizerThread =
            RecognizerThread(listener, timeout)
        recognizerThread!!.start()
        return true
    }

    private fun stopRecognizerThread(): Boolean {
        val thread = recognizerThread ?: return false
        stopped = true
        try {
            thread.requestStop()
        } catch (_: Throwable) {
        }
        // R6: read() ignores interrupt(): stop the recorder on a helper
        // thread so a wedged read unblocks, then join with a timeout so
        // stop can never hang forever.
        val stopper = Thread {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop()
                }
            } catch (_: Throwable) {
            }
        }
        stopper.isDaemon = true
        try {
            stopper.start()
        } catch (_: Throwable) {
        }
        try {
            thread.join(JOIN_TIMEOUT_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        try {
            if (thread.isAlive) thread.interrupt()
        } catch (_: Throwable) {
        }
        recognizerThread = null
        return true
    }

    fun stop(): Boolean {
        return stopRecognizerThread()
    }

    fun cancel(): Boolean {
        try {
            recognizerThread?.setPause(true)
        } catch (_: Throwable) {
        }
        return stopRecognizerThread()
    }

    fun shutdown() {
        try {
            recorder.release()
        } catch (_: Throwable) {
        }
    }

    fun setPause(paused: Boolean) {
        try {
            recognizerThread?.setPause(paused)
        } catch (_: Throwable) {
        }
    }

    fun reset() {
        try {
            recognizerThread?.reset()
        } catch (_: Throwable) {
        }
    }

    private inner class RecognizerThread @JvmOverloads constructor(
        var listener: RecognitionListener,
        timeout: Int = NO_TIMEOUT
    ) : Thread() {
        private var remainingSamples: Int
        private val timeoutSamples: Int
        private val callerTimeoutMs: Int = timeout

        @Volatile
        private var paused = false

        @Volatile
        private var reset = false

        @Volatile
        private var threadStopped = false

        init {
            if (timeout != NO_TIMEOUT) {
                timeoutSamples = timeout * sampleRate / 1000
            } else {
                timeoutSamples = NO_TIMEOUT
            }
            remainingSamples = timeoutSamples
        }

        fun requestStop() {
            threadStopped = true
        }

        fun setPause(paused: Boolean) {
            this.paused = paused
            // R5: pause must stop the recorder, not just flip a flag, so
            // the mic is released while paused.
            try {
                if (paused) {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } else if (!threadStopped && !this@MySpeechService.stopped) {
                    if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.startRecording()
                    }
                }
            } catch (_: Throwable) {
            }
        }

        fun reset() {
            reset = true
        }

        override fun run() {
            try {
                recorder.startRecording()
            } catch (t: Throwable) {
                val ioe = IOException("Failed to start recording. Microphone might be already in use.")
                mainHandler.post { listener.onError(ioe) }
                return
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                // R6: unrecoverable start failure: stop and RETURN instead
                // of looping on a dead recorder.
                try {
                    recorder.stop()
                } catch (_: Throwable) {
                }
                val ioe =
                    IOException("Failed to start recording. Microphone might be already in use.")
                mainHandler.post { listener.onError(ioe) }
                return
            }
            // VAD auto-stop: silero wants 16kHz float. Created per take so
            // no state leaks between recordings; null = manual stop only.
            // The model file was pre-warmed off-thread on the model-load
            // path (R11); this only builds the small native instance.
            // The native min-silence (1s) is the ONLY trailing-silence
            // control: no manual window counter on top of it.
            val vad = if (vadAutoStop && sampleRate == 16000 && appVadContext != null) {
                try {
                    VadHolder.createVad(appVadContext)
                } catch (_: Throwable) {
                    null
                }
            } else {
                null
            }
            if (vadAutoStop && vad == null) {
                AppLog.d(TAG, "vad auto-stop off sampleRate=$sampleRate")
            }
            var heardSpeech = false
            var vadEnded = false
            var readError: IOException? = null
            var capped = false
            var preSpeechTimedOut = false
            var callerTimedOut = false
            var decodeFailures = 0
            val maxTakeMs = if (callerTimeoutMs != NO_TIMEOUT) {
                minOf(callerTimeoutMs.toLong(), engineCapMs())
            } else {
                engineCapMs()
            }
            val maxBufferedSamples = MAX_BUFFERED_BYTES / 2
            var totalSamples = 0
            val startMs = System.currentTimeMillis()
            val buffer = ShortArray(bufferSize)
            try {
                while (!isInterrupted && !threadStopped && !this@MySpeechService.stopped &&
                    (timeoutSamples == NO_TIMEOUT || remainingSamples > 0) && !vadEnded &&
                    readError == null && !capped && !preSpeechTimedOut
                ) {
                    if (paused) {
                        try {
                            sleep(50)
                        } catch (_: InterruptedException) {
                            break
                        }
                        continue
                    }
                    // R2+R5 total-take guard: wall-clock cap + buffered cap.
                    val elapsed = System.currentTimeMillis() - startMs
                    if (elapsed > maxTakeMs) {
                        capped = true
                        readError = IOException("Take exceeded ${maxTakeMs}ms cap")
                        break
                    }
                    if (totalSamples >= maxBufferedSamples) {
                        capped = true
                        readError = IOException("Take exceeded buffered cap")
                        break
                    }
                    // R5 pre-speech timeout: VAD takes with no speech at all
                    // end instead of holding the mic forever.
                    if (vad != null && !heardSpeech && elapsed > PRE_SPEECH_TIMEOUT_MS) {
                        preSpeechTimedOut = true
                        break
                    }
                    val nread = try {
                        recorder.read(buffer, 0, buffer.size)
                    } catch (t: Throwable) {
                        readError = IOException("error reading audio buffer")
                        break
                    }
                    if (!paused) {
                        if (reset) {
                            try {
                                recognizer.reset()
                            } catch (t: Throwable) {
                                decodeFailures++
                                if (t is OutOfMemoryError) System.gc()
                                if (decodeFailures >= PERSISTENT_DECODE_FAILURES) {
                                    readError = IOException("recognizer reset failed persistently")
                                    break
                                }
                            }
                            reset = false
                        }
                        if (nread < 0) {
                            // R6: unrecoverable recorder error: record it and
                            // RETURN (break) instead of looping forever.
                            readError = IOException("error reading audio buffer code=$nread")
                            break
                        }
                        if (nread == 0) continue
                        totalSamples += nread
                        if (vad != null && nread > 0) {
                            try {
                                val floats = FloatArray(nread) { i -> buffer[i] / 32768f }
                                try {
                                    vad.acceptWaveform(floats)
                                    if (vad.isSpeechDetected()) {
                                        heardSpeech = true
                                    } else if (heardSpeech) {
                                        // Native min-silence already elapsed.
                                        vadEnded = true
                                    }
                                } finally {
                                    Arrays.fill(floats, 0f)
                                }
                            } catch (t: Throwable) {
                                if (t is OutOfMemoryError) System.gc()
                                AppLog.e(TAG, "vad failed")
                            }
                        }
                        var result: String?
                        try {
                            if (recognizer.acceptWaveForm(buffer, nread)) {
                                result = recognizer.getResult()
                                decodeFailures = 0
                                mainHandler.post { listener.onResult(result) }
                            } else {
                                result = recognizer.getPartialResult()
                                decodeFailures = 0
                                mainHandler.post { listener.onPartialResult(result) }
                            }
                        } catch (t: Throwable) {
                            // R14: persistent in-take decode failures route
                            // to onError; transient ones are skipped.
                            decodeFailures++
                            if (t is OutOfMemoryError) System.gc()
                            if (decodeFailures >= PERSISTENT_DECODE_FAILURES) {
                                readError = IOException("recognizer decode failed persistently")
                                break
                            }
                            continue
                        }
                        if (timeoutSamples != NO_TIMEOUT) {
                            remainingSamples -= nread
                        }
                    }
                }
                if (timeoutSamples != NO_TIMEOUT && remainingSamples <= 0) {
                    callerTimedOut = true
                }
            } finally {
                // R5: zero mic residue the moment the take ends.
                try {
                    Arrays.fill(buffer, 0.toShort())
                } catch (_: Throwable) {
                }
                try {
                    vad?.release()
                } catch (_: Throwable) {
                }
                // Always release the recorder, even on read errors: the
                // take below then decodes whatever was captured (or times
                // out) instead of leaking the mic.
                try {
                    if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        recorder.stop()
                    }
                } catch (_: Throwable) {
                }
            }
            // Separate error vs final paths (R6): errors never masquerade
            // as a final hypothesis. The try/finally guarantees every
            // terminal path (VAD end, timeout, read/decode error, user
            // stop) releases the take via onTakeFinished: without this the
            // manager keeps isRunning + the service handle and the next
            // mic tap dies silently (take works, next tap dead, alternating).
            // Paused takes keep their state for resume.
            try {
                if (readError != null) {
                    val err = readError
                    mainHandler.post { listener.onError(err) }
                    return
                }
                if (preSpeechTimedOut) {
                    mainHandler.post { listener.onTimeout() }
                    return
                }
                if (paused || threadStopped || this@MySpeechService.stopped) {
                    // User-initiated stop/pause: still transcribe what was
                    // captured (non-streaming decodes on stop), unless paused.
                    if (paused) return
                }
                if (callerTimedOut) {
                    mainHandler.post { listener.onTimeout() }
                    return
                }
                if (!paused) {
                    val finalResult = try {
                        recognizer.getFinalResult()
                    } catch (t: Throwable) {
                        if (t is OutOfMemoryError) System.gc()
                        AppLog.e(TAG, "final decode failed")
                        mainHandler.post { listener.onError(IOException("final decode failed")) }
                        return
                    }
                    mainHandler.post { listener.onFinalResult(finalResult) }
                }
            } finally {
                if (!paused) mainHandler.post { onTakeFinished?.invoke() }
            }
        }

        /** R2 engine cap: 120s non-streaming (VAD takes), else 300s. */
        private fun engineCapMs(): Long {
            return if (vadAutoStop) MAX_TAKE_SEC_NON_STREAMING * 1000L
            else MAX_TAKE_SEC_STREAMING * 1000L
        }
    }

    companion object {
        private const val TAG = "MySpeechService"
        private const val NO_TIMEOUT = -1
        private const val BUFFER_SIZE_SECONDS = 0.2f
        /** R2: non-streaming (record-then-transcribe) total-take cap. */
        internal const val MAX_TAKE_SEC_NON_STREAMING = 120
        /** R2: streaming total-take cap. */
        internal const val MAX_TAKE_SEC_STREAMING = 300
        /** R2: absolute buffered-audio ceiling per take. */
        internal const val MAX_BUFFERED_BYTES = 10 * 1024 * 1024
        /** R5: VAD takes with zero detected speech end after this long. */
        internal const val PRE_SPEECH_TIMEOUT_MS = 15_000L
        /** R6: stop() join ceiling so a wedged read can never hang stop. */
        internal const val JOIN_TIMEOUT_MS = 2_000L
        /** R14: consecutive in-take decode failures before onError. */
        internal const val PERSISTENT_DECODE_FAILURES = 5
    }
}
