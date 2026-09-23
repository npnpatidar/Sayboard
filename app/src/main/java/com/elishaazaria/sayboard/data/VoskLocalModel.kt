package com.elishaazaria.sayboard.data

import java.io.Serializable
import java.util.*

data class VoskLocalModel(
    val path: String,
    val locale: Locale,
    val filename: String,
    val alias: String = ""
) : Serializable {
    companion object {
        // D18: explicit UID so app updates never break serialized instances.
        private const val serialVersionUID: Long = 1L
    }
}
