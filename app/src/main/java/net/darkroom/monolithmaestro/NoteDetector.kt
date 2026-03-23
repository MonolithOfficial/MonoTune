package net.darkroom.monolithmaestro

import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.round

data class NoteInfo(
    val noteName: String,
    val octave: Int,
    val frequency: Float,
    val cents: Float,
    val isDetected: Boolean
)

class NoteDetector {

    companion object {
        private const val A4_FREQ = 440.0
        private const val A4_MIDI = 69
        private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    }

    fun getNoteInfo(frequency: Float): NoteInfo {
        if (frequency <= 0f) {
            return NoteInfo("--", 0, 0f, 0f, false)
        }

        // Convert frequency to MIDI note number
        val midiNote = 12.0 * log2(frequency / A4_FREQ) + A4_MIDI
        val roundedMidi = round(midiNote).toInt()

        // Get note name and octave
        val noteIndex = ((roundedMidi % 12) + 12) % 12
        val octave = (roundedMidi / 12) - 1

        // Calculate target frequency for this note
        val targetFreq = (A4_FREQ * 2.0.pow((roundedMidi - A4_MIDI) / 12.0)).toFloat()

        // Calculate cents deviation
        val cents = (1200.0 * log2(frequency / targetFreq)).toFloat()

        return NoteInfo(
            noteName = NOTE_NAMES[noteIndex],
            octave = octave,
            frequency = frequency,
            cents = cents,
            isDetected = true
        )
    }
}
