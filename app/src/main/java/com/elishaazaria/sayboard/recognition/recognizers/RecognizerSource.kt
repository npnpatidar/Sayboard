package com.elishaazaria.sayboard.recognition.recognizers

import androidx.annotation.StringRes
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import java.util.Locale
import java.util.concurrent.Executor

interface RecognizerSource {
    fun initialize(executor: Executor, onLoaded: Observer<RecognizerSource?>)
    val recognizer: Recognizer
    fun close(freeRAM: Boolean)
    val stateLD: LiveData<RecognizerState>
    
    val addSpaces: Boolean

    val closed: Boolean

    /**
     * Streaming sources (Vosk) emit partial results while recording.
     * Non-streaming sources (Whisper/Parakeet) buffer audio and transcribe
     * once when recording stops.
     */
    val isStreaming: Boolean
        get() = true

    /**
     * True when this source can transcribe any language (multilingual
     * Whisper with auto-detect): the fallback when no model matches the
     * requested locale.
     */
    val isUniversal: Boolean
        get() = false

    @get:StringRes
    val errorMessage: Int
    val name: String

    val locale: Locale
}