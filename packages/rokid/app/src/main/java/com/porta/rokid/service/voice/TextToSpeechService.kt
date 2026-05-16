package com.porta.rokid.service.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import java.util.UUID

/**
 * Text-to-Speech service for reading agent responses aloud.
 *
 * Used for:
 * - Reading Antigravity responses through phone speaker
 * - Audio output to Rokid glasses speaker (via CXR audio channel)
 */
class TextToSpeechService(context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isInitialized = true
                // Default to Indonesian, fallback to English
                val result = tts?.setLanguage(Locale("id", "ID"))
                if (result == TextToSpeech.LANG_MISSING_DATA ||
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.US)
                }
                tts?.setSpeechRate(1.1f) // Slightly faster for technical content
            }
        }
    }

    /**
     * Speak the given text aloud.
     *
     * @param text The text to speak
     * @param flush If true, interrupts any current speech. If false, queues after current.
     */
    fun speak(text: String, flush: Boolean = true) {
        if (!isInitialized || text.isBlank()) return

        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                _isSpeaking.value = true
            }
            override fun onDone(utteranceId: String?) {
                _isSpeaking.value = false
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _isSpeaking.value = false
            }
        })

        val queueMode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        tts?.speak(text, queueMode, null, utteranceId)
    }

    /** Stop any current speech. */
    fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    /** Set the speech language. */
    fun setLanguage(locale: Locale) {
        tts?.setLanguage(locale)
    }

    /** Set speech rate (1.0 = normal). */
    fun setSpeechRate(rate: Float) {
        tts?.setSpeechRate(rate.coerceIn(0.5f, 2.0f))
    }

    /** Release all TTS resources. */
    fun destroy() {
        stop()
        tts?.shutdown()
        tts = null
    }
}
