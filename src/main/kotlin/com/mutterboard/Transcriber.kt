package com.mutterboard

import java.io.File

/**
 * The outcome of one transcription attempt.
 *
 * [Text] and [Failed] are kept apart because they mean different things to the
 * user and to the history: an empty [Text] is a real answer ("no speech in
 * there"), while [Failed] carries a reason worth showing and a take worth
 * offering to retry.
 */
sealed interface Transcription {
    data class Text(val value: String) : Transcription
    data class Failed(val reason: String) : Transcription
}

/**
 * Turns a recorded 16 kHz mono WAV file into text. Ported from the Android app;
 * the desktop ships a cloud implementation ([GroqWhisperClient]) and a local
 * one ([ParakeetTranscriber]).
 */
interface Transcriber {
    fun transcribe(audioFile: File, onResult: (Transcription) -> Unit)

    /** Optional hint that a transcription is imminent, so the impl can warm up. */
    fun warmUp() {}

    /** Release any network resources. Safe to call more than once. */
    fun close() {}
}
