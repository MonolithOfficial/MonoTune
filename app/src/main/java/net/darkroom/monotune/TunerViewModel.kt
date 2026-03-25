package net.darkroom.monotune

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.log2

data class StringTarget(val displayName: String, val targetFrequency: Float)

enum class Tuning(val displayName: String, val strings: List<StringTarget>) {

    // ── Standard & transpositions ─────────────────────────────────────────
    STANDARD("Standard", listOf(
        StringTarget("E",  82.41f),
        StringTarget("A", 110.00f),
        StringTarget("D", 146.83f),
        StringTarget("G", 196.00f),
        StringTarget("B", 246.94f),
        StringTarget("E", 329.63f)
    )),
    HALF_STEP_DOWN("Half Step Down", listOf(
        StringTarget("Eb",  77.78f),
        StringTarget("Ab", 103.83f),
        StringTarget("Db", 138.59f),
        StringTarget("Gb", 185.00f),
        StringTarget("Bb", 233.08f),
        StringTarget("Eb", 311.13f)
    )),
    WHOLE_STEP_DOWN("Whole Step Down", listOf(
        StringTarget("D",  73.42f),
        StringTarget("G",  98.00f),
        StringTarget("C", 130.81f),
        StringTarget("F", 174.61f),
        StringTarget("A", 220.00f),
        StringTarget("D", 293.66f)
    )),

    // ── Drop tunings ──────────────────────────────────────────────────────
    DROP_D("Drop D", listOf(
        StringTarget("D",  73.42f),
        StringTarget("A", 110.00f),
        StringTarget("D", 146.83f),
        StringTarget("G", 196.00f),
        StringTarget("B", 246.94f),
        StringTarget("E", 329.63f)
    )),
    DROP_C("Drop C", listOf(
        StringTarget("C",  65.41f),
        StringTarget("G",  98.00f),
        StringTarget("C", 130.81f),
        StringTarget("F", 174.61f),
        StringTarget("A", 220.00f),
        StringTarget("D", 293.66f)
    )),
    DROP_B("Drop B", listOf(
        StringTarget("B",   61.74f),
        StringTarget("F#",  92.50f),
        StringTarget("B",  123.47f),
        StringTarget("E",  164.81f),
        StringTarget("G#", 207.65f),
        StringTarget("C#", 277.18f)
    )),
    DROP_A("Drop A", listOf(
        StringTarget("A",  55.00f),
        StringTarget("E",  82.41f),
        StringTarget("A", 110.00f),
        StringTarget("D", 146.83f),
        StringTarget("F#",185.00f),
        StringTarget("B", 246.94f)
    )),
    DROP_G("Drop G", listOf(
        StringTarget("G",  49.00f),
        StringTarget("D",  73.42f),
        StringTarget("G",  98.00f),
        StringTarget("C", 130.81f),
        StringTarget("E", 164.81f),
        StringTarget("A", 220.00f)
    )),

    // ── Open tunings ──────────────────────────────────────────────────────
    OPEN_D("Open D", listOf(
        StringTarget("D",  73.42f),
        StringTarget("A", 110.00f),
        StringTarget("D", 146.83f),
        StringTarget("F#",185.00f),
        StringTarget("A", 220.00f),
        StringTarget("D", 293.66f)
    )),
    OPEN_C("Open C", listOf(
        StringTarget("C",  65.41f),
        StringTarget("G",  98.00f),
        StringTarget("C", 130.81f),
        StringTarget("G", 196.00f),
        StringTarget("C", 261.63f),
        StringTarget("E", 329.63f)
    )),
    OPEN_G("Open G", listOf(
        StringTarget("D",  73.42f),
        StringTarget("G",  98.00f),
        StringTarget("D", 146.83f),
        StringTarget("G", 196.00f),
        StringTarget("B", 246.94f),
        StringTarget("D", 293.66f)
    )),
    OPEN_E("Open E", listOf(
        StringTarget("E",  82.41f),
        StringTarget("B", 123.47f),
        StringTarget("E", 164.81f),
        StringTarget("G#",207.65f),
        StringTarget("B", 246.94f),
        StringTarget("E", 329.63f)
    )),
    OPEN_A("Open A", listOf(
        StringTarget("E",  82.41f),
        StringTarget("A", 110.00f),
        StringTarget("E", 164.81f),
        StringTarget("A", 220.00f),
        StringTarget("C#",277.18f),
        StringTarget("E", 329.63f)
    )),

    // ── Named alternates ──────────────────────────────────────────────────
    DADGAD("DADGAD", listOf(
        StringTarget("D",  73.42f),
        StringTarget("A", 110.00f),
        StringTarget("D", 146.83f),
        StringTarget("G", 196.00f),
        StringTarget("A", 220.00f),
        StringTarget("D", 293.66f)
    )),
    CGCGCD("CGCGCD", listOf(
        StringTarget("C",  65.41f),
        StringTarget("G",  98.00f),
        StringTarget("C", 130.81f),
        StringTarget("G", 196.00f),
        StringTarget("C", 261.63f),
        StringTarget("D", 293.66f)
    )),
    DADF_SHARP_AD("DADF#AD", listOf(
        StringTarget("D",  73.42f),
        StringTarget("A", 110.00f),
        StringTarget("D", 146.83f),
        StringTarget("F#",185.00f),
        StringTarget("A", 220.00f),
        StringTarget("D", 293.66f)
    )),

    // ── Other variants ────────────────────────────────────────────────────
    STANDARD_C("Standard C", listOf(
        StringTarget("C",  65.41f),
        StringTarget("F",  87.31f),
        StringTarget("Bb",116.54f),
        StringTarget("Eb",155.56f),
        StringTarget("G", 196.00f),
        StringTarget("C", 261.63f)
    )),
    E_A_D_G_C_E("E A D G C E", listOf(
        StringTarget("E",  82.41f),
        StringTarget("A", 110.00f),
        StringTarget("D", 146.83f),
        StringTarget("G", 196.00f),
        StringTarget("C", 261.63f),
        StringTarget("E", 329.63f)
    )),
    C_G_D_G_B_D("C G D G B D", listOf(
        StringTarget("C",  65.41f),
        StringTarget("G",  98.00f),
        StringTarget("D", 146.83f),
        StringTarget("G", 196.00f),
        StringTarget("B", 246.94f),
        StringTarget("D", 293.66f)
    )),
    F_A_C_G_C_E("F A C G C E", listOf(
        StringTarget("F",  87.31f),
        StringTarget("A", 110.00f),
        StringTarget("C", 130.81f),
        StringTarget("G", 196.00f),
        StringTarget("C", 261.63f),
        StringTarget("E", 329.63f)
    ))
}

data class TunerState(
    val frequency: Float = 0f,
    val noteName: String = "--",
    val octave: Int = 0,
    val cents: Float = 0f,
    val isDetected: Boolean = false
)

class TunerViewModel : ViewModel() {

    private val _state = MutableStateFlow(TunerState())
    val state: StateFlow<TunerState> = _state

    private val _selectedTuning = MutableStateFlow(Tuning.STANDARD)
    val selectedTuning: StateFlow<Tuning> = _selectedTuning

    // Index into the current tuning's strings list (0 = thickest/lowest)
    private val _selectedString = MutableStateFlow<Int?>(null)
    val selectedString: StateFlow<Int?> = _selectedString

    private val _tunedStrings = MutableStateFlow<Set<Int>>(emptySet())
    val tunedStrings: StateFlow<Set<Int>> = _tunedStrings

    fun selectTuning(tuning: Tuning) {
        if (_selectedTuning.value == tuning) return
        _selectedTuning.value = tuning
        _selectedString.value = null
        _tunedStrings.value = emptySet()
    }

    fun selectString(index: Int) {
        _selectedString.value = if (_selectedString.value == index) null else index
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null

    private val sampleRate = 44100

    // 2048 samples = ~46ms window.
    // Low E string (82.4 Hz) → period ≈ 535 samples → ~3.8 periods fit in 2048.
    // MPM requires at least 2 periods, so 2048 is safe for all 6 strings.
    private val analysisSize = 2048

    // Read in 1024-sample chunks so we update ~every 23ms while accumulating
    // a full 2048-sample window for analysis.
    private val readChunkSize = 1024

    private val pitchDetector = PitchDetector(sampleRate)
    private val noteDetector = NoteDetector()

    // Median filter: keeps the last N confident frequency readings and returns
    // the median. Suppresses single-frame outliers (octave jumps, noise spikes).
    private val medianWindowSize = 5
    private val recentFrequencies = ArrayDeque<Float>()

    // Silence patience: don't declare "no note" until this many consecutive
    // missed frames. Prevents the display from flickering during brief dropouts
    // between plucks or while a note decays.
    private val silencePatience = 4
    private var silenceCount = 0

    // First-order IIR high-pass filter.
    // Transfer function: H(z) = (1 - z⁻¹) / (1 - a·z⁻¹),  a = 0.995
    // Cutoff ≈ (1 - 0.995) × 44100 / (2π) ≈ 35 Hz.
    // Removes DC offset and mechanical low-frequency rumble while passing
    // guitar fundamentals from 60 Hz upward with negligible attenuation.
    private val hpfAlpha = 0.995f
    private var hpfPrevInput = 0f
    private var hpfPrevOutput = 0f

    fun startListening() {
        if (recordingJob?.isActive == true) return

        val minBufBytes = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBufBytes, readChunkSize * Short.SIZE_BYTES * 4)
        )

        audioRecord?.startRecording()
        reset()

        recordingJob = viewModelScope.launch(Dispatchers.IO) {
            val circularBuf = FloatArray(analysisSize)
            val readBuf = ShortArray(readChunkSize)
            var writePos = 0

            while (isActive) {
                val read = audioRecord?.read(readBuf, 0, readChunkSize) ?: break
                if (read <= 0) continue

                for (i in 0 until read) {
                    val raw = readBuf[i] / 32768f
                    val filtered = hpfAlpha * (hpfPrevOutput + raw - hpfPrevInput)
                    hpfPrevInput = raw
                    hpfPrevOutput = filtered
                    circularBuf[writePos % analysisSize] = filtered
                    writePos++
                }

                if (writePos < analysisSize) continue

                val offset = writePos % analysisSize
                val window = FloatArray(analysisSize) { i ->
                    circularBuf[(offset + i) % analysisSize]
                }

                val result = pitchDetector.detect(window)
                updateState(result)
            }
        }
    }

    private fun updateState(result: PitchResult) {
        if (result.frequency > 0f && result.confidence >= 0.45f) {
            silenceCount = 0

            recentFrequencies.addLast(result.frequency)
            if (recentFrequencies.size > medianWindowSize) recentFrequencies.removeFirst()

            val freq = median(recentFrequencies)
            val note = noteDetector.getNoteInfo(freq)

            _state.value = TunerState(
                frequency = freq,
                noteName = note.noteName,
                octave = note.octave,
                cents = note.cents,
                isDetected = true
            )

            // Mark any string whose target frequency is within ±10 cents as tuned
            val tuningStrings = _selectedTuning.value.strings
            val alreadyTuned = _tunedStrings.value
            val newlyTuned = tuningStrings.indices.filter { i ->
                i !in alreadyTuned &&
                abs((1200.0 * log2(freq / tuningStrings[i].targetFrequency)).toFloat()) <= 10f
            }
            if (newlyTuned.isNotEmpty()) {
                _tunedStrings.value = alreadyTuned + newlyTuned

                // If the selected string was just tuned, advance to the next string
                val selected = _selectedString.value
                if (selected != null && selected in newlyTuned) {
                    val nextIndex = selected + 1
                    _selectedString.value = if (nextIndex < tuningStrings.size) nextIndex else null
                }
            }
        } else {
            silenceCount++
            if (silenceCount >= silencePatience) {
                recentFrequencies.clear()
                _state.value = TunerState()
            }
        }
    }

    private fun median(values: Collection<Float>): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f
        else sorted[mid]
    }

    private fun reset() {
        recentFrequencies.clear()
        silenceCount = 0
        hpfPrevInput = 0f
        hpfPrevOutput = 0f
        _tunedStrings.value = emptySet()
    }

    fun stopListening() {
        recordingJob?.cancel()
        recordingJob = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        reset()
        _state.value = TunerState()
    }

    override fun onCleared() {
        super.onCleared()
        stopListening()
    }
}
