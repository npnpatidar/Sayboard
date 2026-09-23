package com.elishaazaria.sayboard.data

import java.io.Serializable
import java.util.*

/** Inference engine for sherpa-onnx based local models. */
enum class SherpaEngine {
    WHISPER,
    PARAKEET,
    /**
     * Live-transcription checkpoints: NeMo unified streaming (decoder
     * metadata marker) or zipformer streaming exports ("chunk" in the
     * encoder file name).
     */
    STREAMING
}

/** Runtime handle for an installed sherpa-onnx (Whisper/Parakeet) model. */
data class SherpaLocalModel(
    val path: String,
    val locale: Locale,
    val filename: String,
    val engine: SherpaEngine,
    val alias: String = ""
) : Serializable {
    companion object {
        // D18: explicit UID so app updates never break serialized instances.
        private const val serialVersionUID: Long = 1L
    }
}
