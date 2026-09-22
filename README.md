# ShortsClipper — Long-to-Short Video Clipper (Android)

Transform long videos into professionally framed 9:16 short clips with **on-device processing**: video
editing, manual + automatic face tracking, local silence detection, and high-quality export through
AndroidX Media3.

**Privacy by design:** no accounts, no backend, no paid APIs, no cloud AI inference, and no in-app
model download flow. Videos and exports stay on the device. The bundled Google ML Kit face detector
performs inference on-device, but its terms say the SDK *may* contact Google for software/model or
accelerator updates and performance/utilization metrics; this dependency is therefore not a
zero-network/zero-telemetry SDK.

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
- Automatic face tracking (ML Kit inference is on-device): one tracking-enabled detector processes **every
  sequentially decoded frame** in the selected range; only its compact retained path is downsampled
  for storage, then associated and smoothed (Responsive / Balanced / Smooth presets)
- **Hybrid tracking**: manual keyframes correct the automatic path and fade in/out around the correction
- Multiple faces supported: all are detected, only the selected target is tracked (no split-screen in V1)
- **Preview/export consistency**: one shared `CropCalculator` drives the live preview and the export
  vertex matrix — what you see is exactly what gets rendered

### Audio editing
- Local audio-envelope preparation on demand for waveform and silence detection
- Configurable silence threshold/minimum-pause detection; removal cuts only inside detected quiet
  windows, keeps padding, merges micro-segments, and stays synchronized between preview and export

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
│   ├── model/          # ProjectState, TimelineState, TrackingState, ExportState,
│   │                   # HistoryStack (undo/redo)
│   ├── video/          # VideoManager (metadata/PCM decode), CropCalculator (shared transform),
│   │                   # ExportManager (Media3 Transformer + MediaStore)
│   ├── tracking/       # FaceTracker (ML Kit pass), TrackingEngine, TrackingSmoother
│   ├── ai/             # SilenceDetector
│   ├── data/           # ProjectRepository (local JSON autosave)
│   └── ui/             # HomeScreen, EditorScreen, ExportScreen, EditorViewModel,
│                       # theme and preview/timeline/tracking components
└── AndroidManifest.xml
```

## Building

Requirements: JDK 17 and Android SDK (platform 34, build-tools 34).

```bash
./gradlew assembleDebug                 # debug APK (installable)
./gradlew testDebugUnitTest             # JVM unit tests
./gradlew connectedDebugAndroidTest      # Android emulator/device smoke tests
./gradlew assembleRelease                # unsigned release APK
```

Or simply push — **GitHub Actions builds debug+release, runs JVM tests, and boots an API 30
x86_64 emulator for an offline app-launch smoke test** (`.github/workflows/Build.yml`); APKs are
uploaded as workflow artifacts.

Install on a device: `adb install app/build/outputs/apk/debug/app-debug.apk`.

Release signing is intentionally not configured (no secrets in the repo). Sign the release APK with
your own keystore (`apksigner sign --ks ...`) before distribution.

## Known limitations
- V1 exports MP4, preferring H.264/AVC and using a device-supported fallback only when AVC is unavailable;
  exotic source containers are decoded and re-encoded rather than remuxed because 9:16 reframing requires it.
- Face tracking analyzes every sequential decoder frame, but extreme motion can still challenge a fast
  on-device detector; use the Responsive preset or manual keyframes for creative corrections.
- The bundled Google ML Kit face detector is free and its inference is on-device, but it is governed by Google ML Kit Terms rather than an open-source license; those terms allow update/accelerator checks and performance/utilization metrics. No in-app opt-out is implemented.
- CI runs JVM logic tests plus an API 30 x86_64 emulator app-launch smoke test. Real-device validation is still recommended for vendor-specific MediaCodec, ML Kit, gallery and 90°/270° source behavior.
