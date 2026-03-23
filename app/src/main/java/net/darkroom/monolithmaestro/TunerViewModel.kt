package net.darkroom.monolithmaestro

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
            // Circular buffer accumulates filtered samples
            val circularBuf = FloatArray(analysisSize)
            val readBuf = ShortArray(readChunkSize)
            var writePos = 0

            while (isActive) {
                val read = audioRecord?.read(readBuf, 0, readChunkSize) ?: break
                if (read <= 0) continue

                // Normalize, apply high-pass filter, write into circular buffer
                for (i in 0 until read) {
                    val raw = readBuf[i] / 32768f
                    val filtered = hpfAlpha * (hpfPrevOutput + raw - hpfPrevInput)
                    hpfPrevInput = raw
                    hpfPrevOutput = filtered
                    circularBuf[writePos % analysisSize] = filtered
                    writePos++
                }

                // Wait until we have a full window before analysing
                if (writePos < analysisSize) continue

                // Reconstruct chronological order from the circular buffer
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
        } else {
            silenceCount++
            if (silenceCount >= silencePatience) {
                recentFrequencies.clear()
                _state.value = TunerState()
            }
            // While within patience window, hold the last displayed state.
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
