# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
# Build debug APK
./gradlew assembleDebug

# Build release APK
./gradlew assembleRelease

# Run unit tests
./gradlew test

# Run instrumented tests (requires connected device/emulator)
./gradlew connectedAndroidTest

# Run a single test class
./gradlew test --tests "net.darkroom.monolithmaestro.ExampleUnitTest"

# Lint
./gradlew lint

# Clean build
./gradlew clean assembleDebug
```

## Architecture

Single-module Android app (`net.darkroom.monolithmaestro`) using MVVM with Jetpack Compose.

**Data flow:**
1. `TunerViewModel` opens `AudioRecord` and reads chunks (1024 samples @ 44100 Hz) into a circular buffer (2048 samples)
2. Each chunk triggers `PitchDetector.detectPitch()` — implements the McLeod Pitch Method (MPM) via NSDF → peak picking → parabolic interpolation
3. Detected frequency passes through a 5-frame median filter and silence-detection patience mechanism inside the ViewModel
4. `NoteDetector` converts Hz → MIDI note → note name + octave + cents deviation
5. Results are emitted as `TunerState` via `StateFlow`, consumed in `MainActivity` with `collectAsStateWithLifecycle`

**Key files:**
- `TunerViewModel.kt` — audio lifecycle, circular buffer, HPF (α=0.995, ~35 Hz cutoff), median filter, `TunerState` StateFlow
- `PitchDetector.kt` — MPM algorithm; confidence threshold 0.45; range 60–1400 Hz
- `NoteDetector.kt` — frequency-to-note conversion; A4 = 440 Hz reference
- `MainActivity.kt` — single Activity, Compose UI, RECORD_AUDIO permission handling, custom Canvas cents meter

**Audio constants (TunerViewModel):**
- Sample rate: 44100 Hz
- Analysis window: 2048 samples (~46 ms)
- Read chunk: 1024 samples (~23 ms)

## Tech Stack

- Kotlin + Jetpack Compose + Material 3
- Target SDK 34, Min SDK 24
- `androidx.lifecycle:lifecycle-viewmodel-compose` for ViewModel integration
- `collectAsStateWithLifecycle` for lifecycle-aware state collection
- No dependency injection framework; ViewModel created via `viewModel()` in Compose
