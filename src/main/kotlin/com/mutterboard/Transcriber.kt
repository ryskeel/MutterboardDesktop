package com.mutterboard

import java.io.File

/**
 * Turns a recorded 16 kHz mono WAV file into text. Ported from the Android app;
 * the desktop v0.1 ships only the cloud implementation ([GroqWhisperClient]),
 * which is the "Default" engine the user prefers.
 */
interface Transcriber {
    fun transcribe(audioFile: File, onResult: (String?) -> Unit)

    /** Optional hint that a transcription is imminent, so the impl can warm up. */
    fun warmUp() {}

    /** Release any network resources. Safe to call more than once. */
    fun close() {}
}
