package com.elishaazaria.sayboard.data

import kotlinx.serialization.Serializable

@Serializable
enum class ModelType {
    VoskLocal,
    WhisperLocal,
    ParakeetLocal,
    StreamingLocal,

    /**
     * D18 fallback for tampered/foreign backup payloads: decode must map
     * unknown names here (via [safeValueOf]) instead of throwing before
     * per-field recovery runs.
     */
    UNKNOWN;

    companion object {
        /** Never throws: unknown/null names become [UNKNOWN]. */
        fun safeValueOf(name: String?): ModelType {
            return try {
                if (name == null) UNKNOWN else valueOf(name)
            } catch (_: IllegalArgumentException) {
                UNKNOWN
            }
        }
    }
}