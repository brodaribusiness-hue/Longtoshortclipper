package com.shortsclipper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.shortsclipper.ai.ClipGenerator
import com.shortsclipper.ai.ModelManager
import com.shortsclipper.ai.SilenceDetector
import com.shortsclipper.ai.TranscriptionEngine
import com.shortsclipper.data.ProjectRepository
import com.shortsclipper.model.AnalysisPhase
import com.shortsclipper.model.AnalysisState
import com.shortsclipper.model.AnalysisStep
import com.shortsclipper.model.ExportPhase
import com.shortsclipper.model.ExportState
import com.shortsclipper.model.FaceBox
import com.shortsclipper.model.FacePoint
import com.shortsclipper.model.ProjectState
import com.shortsclipper.model.SilenceEdit
import com.shortsclipper.model.SmoothingPreset
import com.shortsclipper.model.StepState
import com.shortsclipper.model.TargetDuration
import com.shortsclipper.model.TransformKeyframe
import com.shortsclipper.tracking.FaceTracker
import com.shortsclipper.tracking.TrackingEngine
import com.shortsclipper.tracking.TrackingSmoother
import com.shortsclipper.video.ExportManager
import com.shortsclipper.video.VideoManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Single authoritative editor state holder. Every mutation flows through
 * [commit] which pushes immutable snapshots onto the undo/redo history.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    val repository = ProjectRepository(File(application.filesDir, "data"))
    val modelManager = ModelManager(application)
    private val transcriptionEngine = TranscriptionEngine()
    private val exportManager = ExportManager(application)
    private val faceTracker = FaceTracker(application)

    private val history = com.shortsclipper.model.HistoryStack<ProjectState>()
    private val stateMutex = Mutex()

    private val _state = MutableStateFlow(repository.createProject("Untitled"))
    val state: StateFlow<ProjectState> = _state

    // Transient playback state (not part of undo history).
    val playheadMs = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)
    val previewSkipSilences = MutableStateFlow(false)

    val analysisState = MutableStateFlow(AnalysisState())
    val exportState = MutableStateFlow(ExportState())
    val trackingPassProgress = MutableStateFlow(-1f)

    /** First sampled frame bitmap for face target selection (null when none). */
    val faceSelectionFrame = MutableStateFlow<android.graphics.Bitmap?>(null)
    val faceSelectionBoxes = MutableStateFlow<List<FaceBox>>(emptyList())

    /** Raw (unsmoothed) tracks of the last detection pass, for re-smoothing. */
    private var rawTracks: List<List<FaceBox>> = emptyList()

    var player: ExoPlayer? = null
        private set

    private var pcmFile: File? = null
    private var analysisJob: Job? = null
    private var exportJob: Job? = null
    private var trackingJob: Job? = null
    private var autosaveJob: Job? = null
    private val analysisCancelled = AtomicBoolean(false)
    private val exportCancelled = AtomicBoolean(false)
    private val trackingCancelled = AtomicBoolean(false)

    val preferredModel = MutableStateFlow(ModelManager.CATALOG[1]) // base
    private val _recentProjects = MutableStateFlow<List<ProjectState>>(emptyList())
    val recentProjects: StateFlow<List<ProjectState>> = _recentProjects.asStateFlow()

    init {
        startPlayheadPoller()
        refreshProjects()
        initPreferredModel()
    }

    private fun initPreferredModel() {
        val prefs = getApplication<Application>().getSharedPreferences("settings", Context.MODE_PRIVATE)
        val savedId = prefs.getString("preferred_model_id", null)
        val found = ModelManager.CATALOG.find { it.id == savedId }
        if (found != null && modelManager.isInstalled(found)) {
            preferredModel.value = found
            return
        }
        val anyInstalled = ModelManager.CATALOG.find { modelManager.isInstalled(it) }
        if (anyInstalled != null) {
            preferredModel.value = anyInstalled
        } else if (found != null) {
            preferredModel.value = found
        }
    }

    fun setPreferredModel(model: ModelManager.ModelInfo) {
        preferredModel.value = model
        getApplication<Application>()
            .getSharedPreferences("settings", Context.MODE_PRIVATE)
            .edit()
            .putString("preferred_model_id", model.id)
            .apply()
    }

    fun refreshProjects() {
        _recentProjects.value = repository.list()
    }

    // ---------------------------------------------------------------- state

    private fun commit(mutate: (ProjectState) -> ProjectState, tag: String? = null, coalesceMs: Long = 0L) {
        val next = mutate(_state.value).copy(updatedAtMs = System.currentTimeMillis())
        synchronized(history) {
            history.push(next, tag, coalesceMs)
        }
        _state.value = next
        scheduleAutosave()
    }

    fun undo() {
        val prev = synchronized(history) { history.undo() } ?: return
        _state.value = prev
        scheduleAutosave()
    }

    fun redo() {
        val next = synchronized(history) { history.redo() } ?: return
        _state.value = next
        scheduleAutosave()
    }

    val canUndo: Boolean get() = synchronized(history) { history.canUndo }
    val canRedo: Boolean get() = synchronized(history) { history.canRedo }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(800)
            if (repository.save(_state.value)) {
                refreshProjects()
            }
        }
    }

    // -------------------------------------------------------------- project

    fun recentProjects(): List<ProjectState> = repository.list()

    fun loadProject(id: String): Boolean {
        val loaded = repository.load(id) ?: return false
        pcmFile?.delete()
        pcmFile = null
        synchronized(history) { history.clear() }
        synchronized(history) { history.push(loaded) }
        _state.value = loaded
        setupPlayer()
        return true
    }

    fun importVideo(uri: Uri, displayName: String, onResult: (Boolean, String?) -> Unit) {
        viewModelScope.launch {
            try {
                val cleanName = displayName.trim().ifBlank { "video" }
                val source = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    VideoManager.readMetadata(getApplication(), uri, cleanName)
                }
                if (source.durationMs <= 0 || source.displayWidth <= 0 || source.displayHeight <= 0) {
                    onResult(false, "This file could not be read as a video (zero duration or dimensions).")
                    return@launch
                }
                val baseProjectName = cleanName.substringBeforeLast('.').trim().ifBlank { "New clip" }
                val project = repository.createProject(baseProjectName)
                    .copy(source = source, timeline = com.shortsclipper.model.TimelineState(0L, source.durationMs))
                pcmFile?.delete()
                pcmFile = null
                synchronized(history) { history.clear(); history.push(project) }
                _state.value = project
                refreshProjects()
                setupPlayer()
                onResult(true, null)
            } catch (t: Throwable) {
                onResult(false, "Import failed: ${t.message ?: "unsupported media"}")
            }
        }
    }

    private fun setupPlayer() {
        val source = _state.value.source ?: return
        val p = player ?: ExoPlayer.Builder(getApplication()).build().also {
            player = it
            it.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    this@EditorViewModel.isPlaying.value = isPlaying
                }
            })
        }
        p.setMediaItem(MediaItem.fromUri(Uri.parse(source.uri)))
        p.prepare()
        p.seekTo(_state.value.timeline.selectionStartMs.coerceAtLeast(0))
    }

    // ------------------------------------------------------------- playback

    private fun startPlayheadPoller() {
        viewModelScope.launch {
            while (isActive) {
                delay(80)
                val p = player ?: continue
                val pos = p.currentPosition.coerceAtLeast(0)
                val s = _state.value
                // Preview-time silence skipping mirrors the export segment plan.
                if (previewSkipSilences.value && s.silenceRemovals.isNotEmpty()) {
                    val inside = s.silenceRemovals.firstOrNull { pos >= it.startMs && pos < it.endMs }
                    if (inside != null) {
                        p.seekTo(inside.endMs + 1)
                        playheadMs.value = inside.endMs + 1
                        continue
                    }
                }
                playheadMs.value = pos
                // Stop clip preview at the selection end.
                if (p.isPlaying && s.timeline.selectionEndMs > 0 && pos >= s.timeline.selectionEndMs) {
                    p.pause()
                    p.seekTo(s.timeline.selectionStartMs)
                }
            }
        }
    }

    fun play() {
        val s = _state.value
        val p = player ?: return
        if (playheadMs.value < s.timeline.selectionStartMs || playheadMs.value >= s.timeline.selectionEndMs) {
            p.seekTo(s.timeline.selectionStartMs)
        }
        p.play()
    }

    fun pause() {
        player?.pause()
    }

    fun togglePlay() {
        if (isPlaying.value) pause() else play()
    }

    fun seekTo(ms: Long) {
        player?.seekTo(ms.coerceAtLeast(0))
        playheadMs.value = ms.coerceAtLeast(0)
    }

    // ------------------------------------------------------------- timeline

    fun setSelection(startMs: Long, endMs: Long, tag: String = "selection", coalesceMs: Long = 500L) {
        val source = _state.value.source ?: return
        val minClipMs = 1000L
        val start = startMs.coerceIn(0, source.durationMs)
        val end = endMs.coerceIn(start + minClipMs, source.durationMs)
        commit({ it.copy(timeline = it.timeline.copy(selectionStartMs = start, selectionEndMs = end)) }, tag, coalesceMs)
    }

    fun setTimelineZoom(zoom: Float) {
        commit({ it.copy(timeline = it.timeline.copy(zoom = zoom.coerceIn(1f, 16f))) }, "zoom", 300L)
    }

    // ------------------------------------------------------------- tracking

    fun setAutoTracking(enabled: Boolean) {
        if (enabled && _state.value.tracking.autoPath.isEmpty()) {
            commit({ it.copy(tracking = it.tracking.copy(autoEnabled = true)) })
            startFaceDetectionPass()
        } else {
            commit({ it.copy(tracking = it.tracking.copy(autoEnabled = enabled)) })
        }
    }

    fun startFaceDetectionPass() {
        val s = _state.value
        val source = s.source ?: return
        if (trackingJob?.isActive == true) return
        trackingCancelled.set(false)
        trackingPassProgress.value = 0f
        trackingJob = viewModelScope.launch {
            try {
                val result = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    faceTracker.detectOverRange(
                        uri = Uri.parse(source.uri),
                        startMs = s.timeline.selectionStartMs,
                        endMs = s.timeline.selectionEndMs,
                        onProgress = { trackingPassProgress.value = it },
                        isCancelled = { trackingCancelled.get() },
                    )
                }
                rawTracks = TrackingEngine.associateTracks(result.samples)
                val firstTime = result.samples.firstOrNull()?.first
                val firstBoxes = rawTracks.mapNotNull { track ->
                    track.firstOrNull { it.timeMs == firstTime && it.faceId >= 0 }
                }
                val oldBmp = faceSelectionFrame.value
                if (oldBmp !== result.firstFrameBitmap) {
                    oldBmp?.recycle()
                }
                faceSelectionFrame.value = result.firstFrameBitmap
                faceSelectionBoxes.value = firstBoxes
                if (rawTracks.isEmpty()) {
                    // No faces: keep manual reframing, disable auto.
                    commit({ it.copy(tracking = it.tracking.copy(autoEnabled = false, autoPath = emptyList(), targetFaceId = null, detectedFaces = emptyList())) })
                } else {
                    // Default to the largest face; the user can tap another one.
                    selectFace(TrackingEngine.largestTrack(rawTracks)?.first()?.faceId ?: 0)
                }
            } catch (t: Throwable) {
                if (t !is InterruptedException) {
                    commit({ it.copy(tracking = it.tracking.copy(autoEnabled = false)) })
                }
            } finally {
                trackingPassProgress.value = -1f
            }
        }
    }

    fun cancelFaceDetectionPass() {
        trackingCancelled.set(true)
        trackingJob?.cancel()
        trackingPassProgress.value = -1f
    }

    fun selectFace(faceId: Int) {
        val track = TrackingEngine.trackById(rawTracks, faceId) ?: return
        val smoothed = TrackingSmoother.smooth(TrackingEngine.toPath(track), _state.value.tracking.smoothing)
        commit({
            it.copy(
                tracking = it.tracking.copy(
                    autoEnabled = true,
                    targetFaceId = faceId,
                    autoPath = smoothed,
                )
            )
        })
    }

    fun setSmoothing(preset: SmoothingPreset) {
        val track = rawTracks.firstOrNull { it.firstOrNull()?.faceId == _state.value.tracking.targetFaceId }
        val path = if (track != null) {
            TrackingSmoother.smooth(TrackingEngine.toPath(track), preset)
        } else {
            _state.value.tracking.autoPath
        }
        commit({ it.copy(tracking = it.tracking.copy(smoothing = preset, autoPath = path)) })
    }

    /** Adds a manual correction keyframe at the playhead with the current crop center. */
    fun addManualKeyframe() {
        val s = _state.value
        val source = s.source ?: return
        val time = playheadMs.value.coerceIn(s.timeline.selectionStartMs, s.timeline.selectionEndMs)
        val current = com.shortsclipper.video.CropCalculator.rectAt(s, time)
        val keyframe = TransformKeyframe(
            timeMs = time,
            centerXFrac = current.centerX,
            centerYFrac = current.centerY,
            zoom = 1f,
        )
        commit({ it.copy(tracking = it.tracking.copy(manualKeyframes = it.tracking.manualKeyframes + keyframe)) })
    }

    /** Drag handler: creates or updates the keyframe at the playhead (coalesced). */
    fun dragKeyframeAtPlayhead(centerXFrac: Float, centerYFrac: Float, zoom: Float? = null) {
        val s = _state.value
        val source = s.source ?: return
        val time = playheadMs.value
        val existing = s.tracking.manualKeyframes.minByOrNull { kotlin.math.abs(it.timeMs - time) }
        val clamped = com.shortsclipper.video.CropCalculator.clampCenter(
            centerXFrac,
            centerYFrac,
            zoom ?: existing?.zoom ?: 1f,
            source.displayWidth,
            source.displayHeight,
        )
        commit({
            val keyframes = it.tracking.manualKeyframes.toMutableList()
            val match = keyframes.firstOrNull { kf -> kotlin.math.abs(kf.timeMs - time) <= 400 }
            if (match != null) {
                keyframes[keyframes.indexOf(match)] = match.copy(
                    centerXFrac = clamped.centerX,
                    centerYFrac = clamped.centerY,
                    zoom = clamped.zoom,
                )
            } else {
                keyframes.add(TransformKeyframe(time, clamped.centerX, clamped.centerY, clamped.zoom))
            }
            it.copy(tracking = it.tracking.copy(manualKeyframes = keyframes.sortedBy { kf -> kf.timeMs }))
        }, tag = "keyframe", coalesceMs = 250L)
    }

    fun deleteKeyframe(timeMs: Long) {
        commit({ it.copy(tracking = it.tracking.copy(manualKeyframes = it.tracking.manualKeyframes.filter { kf -> kf.timeMs != timeMs })) })
    }

    fun clearManualKeyframes() {
        commit({ it.copy(tracking = it.tracking.copy(manualKeyframes = emptyList())) })
    }

    // ------------------------------------------------------------- analysis

    fun runAnalysis(onFinished: (String?) -> Unit = {}) {
        val s = _state.value
        val source = s.source ?: return
        if (analysisJob?.isActive == true) return
        analysisCancelled.set(false)

        fun step(step: AnalysisStep, state: StepState, progress: Float? = null, message: String? = null) {
            analysisState.value = analysisState.value.copy(
                phase = if (state == StepState.FAILED) AnalysisPhase.FAILED else AnalysisPhase.RUNNING,
                stepStates = analysisState.value.stepStates + (step to state),
                stepProgress = if (progress != null) analysisState.value.stepProgress + (step to progress) else analysisState.value.stepProgress,
                activeStep = if (state == StepState.RUNNING) step else analysisState.value.activeStep,
                error = message ?: analysisState.value.error,
            )
        }

        fun resetAll() {
            analysisState.value = AnalysisState(
                phase = AnalysisPhase.RUNNING,
                stepStates = AnalysisStep.entries.associateWith { StepState.PENDING },
                stepProgress = emptyMap(),
                activeStep = null,
                error = null,
            )
        }

        analysisJob = viewModelScope.launch {
            try {
                resetAll()

                // 1. Prepare audio (decode + envelope).
                step(AnalysisStep.PREPARE_AUDIO, StepState.RUNNING)
                pcmFile?.delete()
                pcmFile = null
                val decoded = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    VideoManager.decodeAudio16kMono(
                        getApplication(),
                        Uri.parse(source.uri),
                        onProgress = { step(AnalysisStep.PREPARE_AUDIO, StepState.RUNNING, it) },
                        isCancelled = { analysisCancelled.get() },
                    )
                }
                if (analysisCancelled.get()) throw InterruptedException()
                if (decoded == null) {
                    // No audio track: transcription impossible, manual editing still works.
                    step(AnalysisStep.PREPARE_AUDIO, StepState.DONE)
                    step(AnalysisStep.TRANSCRIBE, StepState.FAILED, message = "This video has no audio track, so transcription and AI clip suggestions are unavailable. Manual clipping, reframing and tracking still work.")
                    analysisState.value = analysisState.value.copy(phase = AnalysisPhase.DONE)
                    onFinished("This video has no audio track - AI analysis needs audio.")
                    return@launch
                }
                pcmFile = decoded.pcmFile
                commit({
                    it.copy(audioEnvelope = decoded.envelope, envelopeStepMs = SilenceDetector.STEP_MS)
                })
                step(AnalysisStep.PREPARE_AUDIO, StepState.DONE, 1f)

                // 2. Transcribe (local whisper.cpp) + 3. word timestamps.
                step(AnalysisStep.TRANSCRIBE, StepState.RUNNING)
                val modelFile = modelManager.modelFile(preferredModel.value)
                if (!modelManager.isInstalled(preferredModel.value)) {
                    step(AnalysisStep.TRANSCRIBE, StepState.FAILED, message = "No transcription model installed. Open Model Manager on the home screen and download one (one-time, ~${preferredModel.value.approxSizeMB} MB).")
                    analysisState.value = analysisState.value.copy(phase = AnalysisPhase.FAILED)
                    onFinished("No transcription model installed")
                    return@launch
                }
                val transcript = transcriptionEngine.transcribe(
                    pcmFile = decoded.pcmFile,
                    modelFile = modelFile,
                    onProgress = { p ->
                        step(AnalysisStep.TRANSCRIBE, StepState.RUNNING, p)
                    },
                    isCancelled = { analysisCancelled.get() },
                )
                if (analysisCancelled.get()) throw InterruptedException()
                step(AnalysisStep.TRANSCRIBE, StepState.DONE, 1f)

                if (transcript.words.isEmpty()) {
                    step(AnalysisStep.WORD_TIMESTAMPS, StepState.FAILED, message = "No speech was detected in this video.")
                    analysisState.value = analysisState.value.copy(phase = AnalysisPhase.FAILED)
                    onFinished("No speech detected")
                    return@launch
                }
                step(AnalysisStep.WORD_TIMESTAMPS, StepState.DONE, 1f)

                // 4-7. Local content analysis -> candidates -> scoring.
                step(AnalysisStep.ANALYZE_CONTENT, StepState.RUNNING)
                val sentences = com.shortsclipper.ai.HighlightAnalyzer.splitSentences(transcript.words)
                step(AnalysisStep.ANALYZE_CONTENT, StepState.DONE, 1f)

                step(AnalysisStep.FIND_MOMENTS, StepState.RUNNING)
                val anchors = com.shortsclipper.ai.HighlightAnalyzer.findAnchors(sentences)
                step(AnalysisStep.FIND_MOMENTS, StepState.DONE, 1f)

                step(AnalysisStep.SCORE, StepState.RUNNING)
                step(AnalysisStep.SCORE, StepState.DONE, 1f)

                step(AnalysisStep.GENERATE, StepState.RUNNING)
                val candidates = ClipGenerator.generate(transcript, source.durationMs, s.targetDuration)
                step(AnalysisStep.GENERATE, StepState.DONE, 1f)

                commit({
                    it.copy(transcript = transcript, candidates = candidates, rejectedCandidateIds = emptySet())
                })
                analysisState.value = analysisState.value.copy(phase = AnalysisPhase.DONE, activeStep = null)
                onFinished(if (candidates.isEmpty()) "No strong candidate moments found - adjust the clip manually." else null)
            } catch (e: InterruptedException) {
                analysisState.value = analysisState.value.copy(phase = AnalysisPhase.IDLE, activeStep = null)
            } catch (t: Throwable) {
                val msg = t.message ?: "Analysis failed"
                analysisState.value = analysisState.value.copy(
                    phase = AnalysisPhase.FAILED,
                    error = msg,
                    activeStep = null,
                )
                onFinished(msg)
            }
        }
    }

    fun cancelAnalysis() {
        analysisCancelled.set(true)
        analysisJob?.cancel()
        analysisState.value = analysisState.value.copy(phase = AnalysisPhase.IDLE, activeStep = null)
    }

    // -------------------------------------------------------------- silences

    fun detectSilences(thresholdDb: Float, minSilenceMs: Int) {
        val s = _state.value
        if (s.audioEnvelope.isEmpty()) return
        val silences = SilenceDetector.detect(
            s.audioEnvelope,
            SilenceDetector.Config(thresholdDb = thresholdDb, minSilenceMs = minSilenceMs),
            s.envelopeStepMs,
        )
        commit({ it.copy(detectedSilences = silences) })
    }

    fun applySilenceRemoval() {
        val s = _state.value
        if (s.detectedSilences.isEmpty()) return
        val inSelection = s.detectedSilences.filter {
            it.endMs > s.timeline.selectionStartMs && it.startMs < s.timeline.selectionEndMs
        }
        if (inSelection.isEmpty()) return
        commit({ it.copy(silenceRemovals = inSelection) })
        previewSkipSilences.value = true
    }

    fun clearSilenceRemoval() {
        commit({ it.copy(silenceRemovals = emptyList()) })
        previewSkipSilences.value = false
    }

    // ------------------------------------------------------------- candidates

    fun setTargetDuration(target: TargetDuration) {
        commit({ it.copy(targetDuration = target) })
        regenerateCandidates()
    }

    fun regenerateCandidates() {
        val s = _state.value
        val transcript = s.transcript ?: return
        val candidates = ClipGenerator.generate(transcript, s.source?.durationMs ?: 0L, s.targetDuration)
        commit({ it.copy(candidates = candidates, rejectedCandidateIds = emptySet()) })
    }

    fun selectCandidate(id: String) {
        val candidate = _state.value.candidates.firstOrNull { it.id == id } ?: return
        commit({
            it.copy(
                selectedCandidateId = id,
                timeline = it.timeline.copy(selectionStartMs = candidate.startMs, selectionEndMs = candidate.endMs),
                silenceRemovals = emptyList(),
            )
        })
        seekTo(candidate.startMs)
    }

    fun rejectCandidate(id: String) {
        commit({ it.copy(rejectedCandidateIds = it.rejectedCandidateIds + id) })
    }

    // ---------------------------------------------------------------- export

    fun startExport(onDone: (String?) -> Unit = {}) {
        val s = _state.value
        if (s.source == null || exportJob?.isActive == true) return
        exportCancelled.set(false)
        exportJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            exportState.value = ExportState(phase = ExportPhase.PREPARING)
            var tempFile: File? = null
            try {
                val plan = exportManager.computePlan(s)
                val outDir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                val outFile = File(outDir, "shortsclipper_${System.currentTimeMillis()}.mp4")
                tempFile = outFile
                exportState.value = exportState.value.copy(phase = ExportPhase.TRANSFORMING)
                val ticker = launch {
                    while (isActive) {
                        delay(500)
                        if (exportState.value.isBusy) {
                            exportState.value = exportState.value.copy(elapsedMs = System.currentTimeMillis() - startedAt)
                        }
                    }
                }
                val file = exportManager.export(
                    state = _state.value,
                    plan = plan,
                    outFile = outFile,
                    onProgress = { exportState.value = exportState.value.copy(progressPercent = it) },
                    isCancelled = { exportCancelled.get() },
                )
                ticker.cancel()
                exportState.value = exportState.value.copy(phase = ExportPhase.SAVING, progressPercent = 100)
                val savedUri = withContext(kotlinx.coroutines.Dispatchers.IO) {
                    exportManager.saveToGallery(file, "ShortsClipper_${s.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_${System.currentTimeMillis()}.mp4")
                }
                file.delete()
                tempFile = null
                exportState.value = ExportState(
                    phase = ExportPhase.DONE,
                    progressPercent = 100,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    outputUri = savedUri.toString(),
                    message = "Saved to Gallery > Movies/${ExportManager.GALLERY_FOLDER}",
                )
                onDone(null)
            } catch (e: com.shortsclipper.video.ExportCancelledException) {
                exportState.value = ExportState(phase = ExportPhase.CANCELLED, message = "Export cancelled")
            } catch (t: Throwable) {
                exportState.value = ExportState(
                    phase = ExportPhase.FAILED,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    message = friendlyExportError(t),
                )
                onDone(friendlyExportError(t))
            } finally {
                tempFile?.delete()
            }
        }
    }

    private fun friendlyExportError(t: Throwable): String = when (t) {
        is com.shortsclipper.video.InsufficientStorageException ->
            "Not enough free storage for the export. Free up space and try again."
        is com.shortsclipper.video.ExportCancelledException -> "Export cancelled"
        else -> "Export failed: ${t.message ?: "encoder error"}. Try the High Quality mode or a shorter clip."
    }

    fun cancelExport() {
        exportCancelled.set(true)
    }

    fun setExportQuality(mode: com.shortsclipper.model.QualityMode) {
        commit({ it.copy(exportQuality = mode) })
    }

    // ----------------------------------------------------------------- misc

    fun deleteProject(id: String) {
        repository.delete(id)
        refreshProjects()
    }

    override fun onCleared() {
        analysisJob?.cancel()
        exportJob?.cancel()
        trackingJob?.cancel()
        autosaveJob?.cancel()
        pcmFile?.delete()
        faceSelectionFrame.value?.recycle()
        faceSelectionFrame.value = null
        player?.release()
        player = null
        super.onCleared()
    }
}
