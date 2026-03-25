package net.darkroom.monotune

import kotlin.math.abs

data class PitchResult(val frequency: Float, val confidence: Float)

/**
 * McLeod Pitch Method (MPM) implementation.
 *
 * Based on: "A Smarter Way to Find Pitch" by Philip McLeod & Geoff Wyvill (ICMC 2005)
 * https://www.cs.otago.ac.nz/graphics/Geoff/tartini/papers/A_Smarter_Way_to_Find_Pitch.pdf
 *
 * Advantages over YIN for guitar:
 *  - Needs only 2 signal periods (lower latency)
 *  - Relative threshold avoids sub-octave errors
 *  - No low-pass filter required — handles strong upper harmonics on electric guitar
 *  - Used in: TarsosDSP, Tartini, Cythara, Choona, and most commercial tuner apps
 */
class PitchDetector(private val sampleRate: Int = 44100) {

    companion object {
        // Relative threshold: first peak above (highestPeak * K) is the fundamental.
        // 0.93 is the canonical value from the Tartini/TarsosDSP implementation.
        private const val K_THRESHOLD = 0.93f

        // Min confidence to emit a result (absolute NSDF value at the chosen peak).
        // Filters out noise frames with weak periodicity.
        private const val MIN_CONFIDENCE = 0.45f

        // Guitar range: low E2 = 82.4 Hz, high e4 = 1318.5 Hz.
        // Give a little headroom on each side.
        private const val MIN_FREQ = 60f
        private const val MAX_FREQ = 1400f
    }

    /**
     * Detect pitch in [samples] using MPM.
     * Returns PitchResult(-1, 0) when no confident pitch is found.
     */
    fun detect(samples: FloatArray): PitchResult {
        val n = samples.size
        val maxTau = (sampleRate / MIN_FREQ).toInt().coerceAtMost(n / 2)
        val minTau = (sampleRate / MAX_FREQ).toInt().coerceAtLeast(2)

        if (maxTau <= minTau) return PitchResult(-1f, 0f)

        val nsdf = computeNSDF(samples, maxTau)

        val peakTau = pickFundamentalPeak(nsdf, minTau, maxTau)
            ?: return PitchResult(-1f, 0f)

        val confidence = nsdf[peakTau]
        if (confidence < MIN_CONFIDENCE) return PitchResult(-1f, 0f)

        val refinedTau = parabolicInterpolation(nsdf, peakTau, maxTau)
        return PitchResult(sampleRate / refinedTau, confidence)
    }

    /**
     * Normalized Square Difference Function (NSDF).
     *
     * NSDF(τ) = 2·r(τ) / m(τ)
     *
     * where:
     *   r(τ) = Σ x[j]·x[j+τ]          (unnormalized autocorrelation)
     *   m(τ) = Σ (x[j]² + x[j+τ]²)    (normalization — always positive)
     *
     * Result is bounded to [-1, 1]. NSDF(0) = 1 by construction.
     * Unlike YIN's cumulative mean normalization, this is an absolute bound
     * which makes the K threshold stable across different signal levels.
     *
     * Computed in doubles to avoid float rounding accumulation over 2048 samples.
     */
    private fun computeNSDF(samples: FloatArray, maxTau: Int): FloatArray {
        val n = samples.size
        val nsdf = FloatArray(maxTau)

        for (tau in 0 until maxTau) {
            var acf = 0.0
            var norm = 0.0
            val limit = n - tau
            for (j in 0 until limit) {
                val xj = samples[j].toDouble()
                val xjt = samples[j + tau].toDouble()
                acf += xj * xjt
                norm += xj * xj + xjt * xjt
            }
            nsdf[tau] = if (norm < 1e-10) 0f else (2.0 * acf / norm).toFloat()
        }

        return nsdf
    }

    /**
     * MPM peak picking — the core insight of the algorithm.
     *
     * Step 1: Collect all local maxima that occur inside positive regions of the NSDF.
     *         (Maxima in negative regions cannot be pitch candidates.)
     *
     * Step 2: Among all those maxima, find the highest amplitude H.
     *
     * Step 3: Return the FIRST maximum whose value >= H * K_THRESHOLD.
     *
     * Returning the *first* qualifying peak (not the largest) selects the
     * fundamental period and avoids returning a sub-octave harmonic period.
     * The relative threshold K handles signals where the fundamental is weaker
     * than some harmonic (common on guitar strings).
     */
    private fun pickFundamentalPeak(nsdf: FloatArray, minTau: Int, maxTau: Int): Int? {
        val maxima = mutableListOf<Int>()
        var i = minTau

        // Advance past the opening negative region (if any)
        while (i < maxTau - 1 && nsdf[i] <= 0f) i++

        while (i < maxTau - 1) {
            // Scan the positive region, tracking the local maximum
            var localMax = i
            while (i < maxTau - 1 && nsdf[i] >= 0f) {
                if (nsdf[i] > nsdf[localMax]) localMax = i
                i++
            }
            maxima.add(localMax)

            // Skip over the negative valley before the next positive lobe
            while (i < maxTau - 1 && nsdf[i] < 0f) i++
        }

        if (maxima.isEmpty()) return null

        val highest = maxima.maxOf { nsdf[it] }
        val relativeThreshold = highest * K_THRESHOLD

        return maxima.firstOrNull { nsdf[it] >= relativeThreshold }
    }

    /**
     * Parabolic interpolation for sub-sample period accuracy.
     * Fits a parabola through (τ-1, τ, τ+1) and finds its vertex.
     * Converts integer lag into a fractional period, reducing pitch error
     * from ±0.5 sample to a small fraction — critical for narrow cents display.
     */
    private fun parabolicInterpolation(nsdf: FloatArray, tau: Int, maxTau: Int): Float {
        if (tau <= 0 || tau >= maxTau - 1) return tau.toFloat()
        val s0 = nsdf[tau - 1]
        val s1 = nsdf[tau]
        val s2 = nsdf[tau + 1]
        val denom = s0 - 2f * s1 + s2
        return if (abs(denom) < 1e-10f) tau.toFloat()
        else tau - (s0 - s2) / (2f * denom)
    }
}
