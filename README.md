# ShortsClipper — Long-to-Short Video Clipper (Android)

Transform long videos into professionally framed 9:16 short clips — **100% on-device**: local
transcription (whisper.cpp), deterministic content analysis, manual + automatic face tracking,
and high-quality export through AndroidX Media3.

**Privacy by design:** no accounts, no backend, no paid APIs, no cloud AI. Videos, transcripts and
exports never leave the device. Internet is used only if the user explicitly downloads an open-source
whisper model.

---

## Features

### Video
- Import long videos via the system picker (any container the platform can decode: MP4, MKV, WebM, 3GP…)
- Metadata detection: duration, resolution, rotation, FPS (where available), audio presence, codec/container MIME
- URI-based processing — a long video is never loaded into RAM

### Player & timeline
- Media3/ExoPlayer playback: play, pause, accurate seek, current/total time, rotation-safe preview
- Explicit **Source** preview that fits the original upright aspect without stretching/cropping, plus a
  separate **9:16 output** preview that shows the intentional tracked export crop
- Professional timeline: waveform, draggable start/end handles, playhead scrubbing, zoom (1×–16×)
- Clip preview with automatic in/out point handling
- Real state-based undo/redo (timeline, crop, tracking, keyframes and silence edits)

### 9:16 reframing & tracking
- Primary output aspect ratio 9:16
- Manual reframing: drag the preview to pan; zoom via keyframes; clamped crop window
- Manual keyframe tracking with smoothstep interpolation (`00:00 → left`, `00:05 → center`, …)
- Automatic face tracking (ML Kit, fully on-device): one tracking-enabled detector processes **every
  sequentially decoded frame** in the selected range; only its compact retained path is downsampled
  for storage, then associated and smoothed (Responsive / Balanced / Smooth presets)
- **Hybrid tracking**: manual keyframes correct the automatic path and fade in/out around the correction
- Multiple faces supported: all are detected, only the selected target is tracked (no split-screen in V1)
- **Preview/export consistency**: one shared `CropCalculator` drives the live preview and the export
  vertex matrix — what you see is exactly what gets rendered

### Local AI
- On-device speech-to-text via **whisper.cpp v1.6.2** (vendored C/C++ built with NDK, JNI bridge)
- Sliding-window streaming transcription (30 s windows, 1.5 s overlap) — bounded memory on hour-long videos
- Word-level timestamps (token timestamps + word assembly) with per-token confidence
- Controlled model manager: official whisper.cpp ggml models (tiny/base/small) downloaded explicitly
  from the official repository with progress/verification, or imported from a local file
- Deterministic local analysis: sentence splitting, question/contrast/number/importance/curiosity/
  conclusion/density signals, interesting-moment anchors, hook detection
- Automatic candidate clips on sentence boundaries, 15/30/60/90 s targets (+ Auto), overlap
  suppression, **Content Potential Score** (transparent components: hook, context, engagement,
  completeness, density). It is an internal ranking signal — *not* a viral-prediction claim.
- Silence detection from the decoded audio envelope; configurable threshold/min-pause; removal
  preserves speech (cuts only inside silence, padding kept, micro-segments merged); preview skips silences

### Export
- Media3 Transformer: crop/pan/zoom re-applied per frame, audio preserved, A/V synced
- Device-capability-aware: encoder/mime probing, resolution tiers (2160→360), never upscales
- Broad playback first: H.264/AVC is selected when supported, with codec fallback only when needed
- Quality modes: *Same as Original* (source-detail resolution when encodable) / *High Quality* (1080p cap)
- Silence removal exported as an `EditedMediaItemSequence` (hard cuts, per-segment clipping)
- Progress, elapsed time, cancellation, friendly errors (unsupported encoder, insufficient storage…)
- Saved to the standard gallery via MediaStore → `Movies/ShortsClipper`

---

## Project structure

```
app/src/main/
├── java/com/shortsclipper/
│   ├── MainActivity.kt
│   ├── model/          # ProjectState, TimelineState, TrackingState, TranscriptModels,
│   │                   # ClipCandidate, ExportState, HistoryStack (undo/redo)
│   ├── video/          # VideoManager (metadata/PCM decode), CropCalculator (shared transform),
│   │                   # ExportManager (Media3 Transformer + MediaStore)
│   ├── tracking/       # FaceTracker (ML Kit pass), TrackingEngine, TrackingSmoother
│   ├── ai/             # WhisperNative (JNI), TranscriptionEngine, WordAssembler, ModelManager,
│   │                   # HighlightAnalyzer, ClipScorer, ClipGenerator, SilenceDetector
│   ├── data/           # ProjectRepository (local JSON autosave)
│   └── ui/             # HomeScreen, EditorScreen, AnalysisScreen, ClipsScreen, ExportScreen,
│                       # EditorViewModel, theme, components (preview, timeline, overlays)
├── native/             # whisper-jni.cpp + vendored whisper.cpp v1.6.2 sources (MIT)
└── AndroidManifest.xml
```

## Building

Requirements: JDK 17, Android SDK (platform 34, build-tools 34), NDK 26.3.11579264, CMake 3.22.1.

```bash
./gradlew assembleDebug          # debug APK (installable)
./gradlew testDebugUnitTest      # JVM unit tests
./gradlew assembleRelease        # unsigned release APK
```

Or simply push — **GitHub Actions builds debug+release and runs the tests automatically**
(`.github/workflows/Build.yml`); APKs are uploaded as workflow artifacts.

Install on a device: `adb install app/build/outputs/apk/debug/app-debug.apk`.

Release signing is intentionally not configured (no secrets in the repo). Sign the release APK with
your own keystore (`apksigner sign --ks ...`) before distribution.

### Whisper models
The APK ships without a model (keeps the download small). On first use, open **Model Manager** on the
home screen and download *Tiny* (~78 MB), *Base* (~148 MB, recommended) or *Small* (~488 MB) from the
official whisper.cpp repository, or import a local `.bin` model. Transcription is fully offline afterwards.

## Known limitations
- Transcription quality depends on the chosen model; word timestamps are model-derived estimates.
- Content signals are English-keyword-based heuristics; non-English content still transcribes but
  scores fewer signal matches.
- Content Potential Score is a transparent local heuristic — it does not predict virality or engagement.
- V1 exports MP4, preferring H.264/AVC and using a device-supported fallback only when AVC is unavailable;
  exotic source containers are decoded and re-encoded rather than remuxed because 9:16 reframing requires it.
- Face tracking analyzes every sequential decoder frame, but extreme motion can still challenge a fast
  on-device detector; use the Responsive preset or manual keyframes for creative corrections.
- CI/test coverage is JVM-level (logic). No emulator-based instrumented tests run in this environment.
