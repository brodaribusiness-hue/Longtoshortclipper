package com.shortsclipper.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import com.shortsclipper.ai.SilenceDetector
import com.shortsclipper.data.ProjectRepository
import com.shortsclipper.model.ExportPhase
import com.shortsclipper.model.ExportPlanner
import com.shortsclipper.model.ExportState
import com.shortsclipper.model.FaceBox
import com.shortsclipper.model.FacePoint
import com.shortsclipper.model.ProjectState
import com.shortsclipper.model.SilenceEdit
import com.shortsclipper.model.SmoothingPreset
import com.shortsclipper.model.TransformKeyframe
import com.shortsclipper.tracking.FaceTracker
import com.shortsclipper.tracking.TrackingEngine
import com.shortsclipper.tracking.TrackingSmoother
import com.shortsclipper.video.ExportManager
import com.shortsclipper.video.VideoManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single authoritative editor state holder. Every mutation flows through
 * [commit] which pushes immutable snapshots onto the undo/redo history.
 */
class EditorViewModel(application: Application) : AndroidViewModel(application) {

    val repository = ProjectRepository(File(application.filesDir, "data"))
    private val exportManager = ExportManager(application)
    private val faceTracker = FaceTracker(application)

    private val history = com.shortsclipper.model.HistoryStack<ProjectState>()
    /** State/history edits may complete on different worker callbacks. */
    private val stateLock = Any()

    private val _state = MutableStateFlow(repository.createProject("Untitled"))
    val state: StateFlow<ProjectState> = _state

    private val _recentProjects = MutableStateFlow<List<ProjectState>>(emptyList())
    /** Home observes this list instead of performing filesystem reads on the UI thread. */
    val recentProjects: StateFlow<List<ProjectState>> = _recentProjects.asStateFlow()
    private val projectListGeneration = AtomicLong(0L)
    /** Ensures stale disk reads/imports cannot replace a newer user choice. */
    private val projectOpenGeneration = AtomicLong(0L)
    /** Invalidates callbacks from jobs that belonged to a replaced project. */
    private val projectSessionGeneration = AtomicLong(0L)

    // Transient playback state (not part of undo history).
    val playheadMs = MutableStateFlow(0L)
    val isPlaying = MutableStateFlow(false)
    val playbackError = MutableStateFlow<String?>(null)
    val previewSkipSilences = MutableStateFlow(false)

    val isPreparingAudio = MutableStateFlow(false)
    val audioPreparationError = MutableStateFlow<String?>(null)
    val exportState = MutableStateFlow(ExportState())
    val trackingPassProgress = MutableStateFlow(-1f)

    /** Source-coordinate boxes at the current playhead for target selection. */
    val faceSelectionBoxes = MutableStateFlow<List<FaceBox>>(emptyList())
    val trackingError = MutableStateFlow<String?>(null)

    /** Raw continuous tracks of the last pass, retained only for re-smoothing/selection. */
    private var rawTracks: List<List<FaceBox>> = emptyList()
    private var lastFaceBoxTimeMs = Long.MIN_VALUE

    /** Expensive association/smoothing output prepared off the main thread. */
    private data class PreparedTrackingPass(
        val tracks: List<List<FaceBox>>,
        val firstDetectionTimeMs: Long?,
        val firstBoxes: List<FaceBox>,
        val defaultTrack: List<FaceBox>?,
        val decodedFrameCount: Int,
    )

    var player: ExoPlayer? = null
        private set

    private var importJob: Job? = null
    private var audioPreparationJob: Job? = null
    private var exportJob: Job? = null
    private var trackingJob: Job? = null
    /** Serializes a rapid cancel/restart so two decoder passes never overlap. */
    private var trackingRestartJob: Job? = null
    private var autosaveJob: Job? = null
    private val audioPreparationCancelled = AtomicBoolean(false)
    private val exportCancelled = AtomicBoolean(false)
    private val trackingCancelled = AtomicBoolean(false)
    /** Invalidates worker smoothing/selection calculations when their raw path changes. */
    private val trackingPathGeneration = AtomicLong(0L)
    /** Keeps stale audio/export/tracking callbacks out of a newer UI session. */
    private val audioPreparationGeneration = AtomicLong(0L)
    private val exportRunGeneration = AtomicLong(0L)
    private val trackingPassGeneration = AtomicLong(0L)

    init {
        synchronized(history) { history.push(_state.value) }
        refreshRecentProjects()
        startPlayheadPoller()
    }

    // ---------------------------------------------------------------- state

    /**
     * Worker jobs keep their own immutable input snapshots. Do not let an old
     * decoder/audio-preparation/export callback overwrite runtime state after the
     * user opened, imported, or deleted a different project.
     */
    private fun isCurrentProjectSession(session: Long, snapshot: ProjectState): Boolean {
        val current = _state.value
        return session == projectSessionGeneration.get() &&
            current.id == snapshot.id &&
            current.source?.uri == snapshot.source?.uri
    }

    private fun commit(mutate: (ProjectState) -> ProjectState, tag: String? = null, coalesceMs: Long = 0L) {
        synchronized(stateLock) {
            val current = _state.value
            val next = mutate(current).copy(
                updatedAtMs = System.currentTimeMillis(),
                revision = current.revision + 1L,
            )
            synchronized(history) { history.push(next, tag, coalesceMs) }
            _state.value = next
        }
        scheduleAutosave()
    }

    fun undo() {
        val restored = synchronized(stateLock) {
            val historical = synchronized(history) { history.undo() } ?: return@synchronized null
            historical.copy(
                updatedAtMs = System.currentTimeMillis(),
                revision = _state.value.revision + 1L,
            ).also { _state.value = it }
        } ?: return
        scheduleAutosave()
    }

    fun redo() {
        val restored = synchronized(stateLock) {
            val historical = synchronized(history) { history.redo() } ?: return@synchronized null
            historical.copy(
                updatedAtMs = System.currentTimeMillis(),
                revision = _state.value.revision + 1L,
            ).also { _state.value = it }
        } ?: return
        scheduleAutosave()
    }

    val canUndo: Boolean get() = synchronized(history) { history.canUndo }
    val canRedo: Boolean get() = synchronized(history) { history.canRedo }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch {
            delay(800)
            val snapshot = _state.value
            val saved = withContext(kotlinx.coroutines.Dispatchers.IO) { repository.save(snapshot) }
            if (saved) refreshRecentProjects()
        }
    }

    // -------------------------------------------------------------- project

    private fun refreshRecentProjects() {
        val generation = projectListGeneration.incrementAndGet()
        viewModelScope.launch {
            val projects = withContext(kotlinx.coroutines.Dispatchers.IO) { repository.list() }
            if (generation == projectListGeneration.get()) _recentProjects.value = projects
        }
    }

    private fun stopProjectWork() {
        // Invalidate state/progress callbacks before asking workers to stop.
        // MediaCodec and ML Kit can finish a callback after a coroutine has
        // been cancelled, especially during project replacement.
        audioPreparationGeneration.incrementAndGet()
        trackingPassGeneration.incrementAndGet()
        exportRunGeneration.incrementAndGet()
        audioPreparationCancelled.set(true)
        audioPreparationJob?.cancel()
        isPreparingAudio.value = false
        audioPreparationError.value = null
        trackingCancelled.set(true)
        trackingRestartJob?.cancel()
        trackingRestartJob = null
        trackingJob?.cancel()
        exportCancelled.set(true)
        exportJob?.cancel()
        trackingPassProgress.value = -1f
        trackingError.value = null
        trackingPathGeneration.incrementAndGet()
        rawTracks = emptyList()
        faceSelectionBoxes.value = emptyList()
        lastFaceBoxTimeMs = Long.MIN_VALUE
    }

    private fun replaceProject(project: ProjectState) {
        projectSessionGeneration.incrementAndGet()
        stopProjectWork()
        player?.pause()
        autosaveJob?.cancel()
        synchronized(stateLock) {
            synchronized(history) {
                history.clear()
                history.push(project)
            }
            _state.value = project
        }
        playheadMs.value = project.timeline.selectionStartMs
        previewSkipSilences.value = false
        exportState.value = ExportState()
        playbackError.value = null
        if (project.source == null) {
            player?.clearMediaItems()
            isPlaying.value = false
        } else {
            setupPlayer()
        }
    }

    fun loadProject(id: String, onResult: (Boolean, String?) -> Unit) {
        val generation = projectOpenGeneration.incrementAndGet()
        // A project picked from Recents takes precedence over a pending SAF
        // metadata read, which otherwise could unexpectedly reopen its video.
        importJob?.cancel()
        viewModelScope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.load(id) }
                if (generation != projectOpenGeneration.get()) return@launch
                if (loaded == null) {
                    refreshRecentProjects()
                    onResult(false, "This project could not be opened. Its saved file may be missing or corrupted.")
                } else {
                    replaceProject(loaded)
                    onResult(true, null)
                }
            } catch (_: CancellationException) {
                // A newer open/import request owns the UI now.
            } catch (t: Throwable) {
                if (generation == projectOpenGeneration.get()) {
                    onResult(false, "Could not open this project: ${t.message ?: "storage error"}")
                }
            }
        }
    }

    fun importVideo(uri: Uri, displayName: String, onResult: (Boolean, String?) -> Unit) {
        // A cancelled MediaMetadataRetriever operation can still be unwinding
        // on its worker thread after Job.isActive becomes false. Do not start a
        // second import until that job has actually released its media handles.
        if (importJob?.isCompleted == false) {
            onResult(false, "Another video is still being imported.")
            return
        }
        val generation = projectOpenGeneration.incrementAndGet()
        importJob = viewModelScope.launch {
            try {
                val source = withContext(Dispatchers.IO) {
                    VideoManager.readMetadata(getApplication(), uri, displayName)
                }
                if (generation != projectOpenGeneration.get()) return@launch
                if (source.durationMs <= 0 || source.displayWidth <= 0 || source.displayHeight <= 0) {
                    onResult(false, "This file could not be read as a video.")
                    return@launch
                }
                val project = withContext(Dispatchers.IO) {
                    repository.createProject(source.displayName.substringBeforeLast('.', source.displayName).ifBlank { "New clip" })
                        .copy(source = source, timeline = com.shortsclipper.model.TimelineState(0L, source.durationMs))
                        .also { state -> check(repository.save(state)) { "Could not create the project file" } }
                }
                if (generation != projectOpenGeneration.get()) return@launch
                replaceProject(project)
                refreshRecentProjects()
                onResult(true, null)
            } catch (_: CancellationException) {
                // A newer project request superseded this import.
            } catch (t: Throwable) {
                if (generation == projectOpenGeneration.get()) {
                    onResult(false, "Import failed: ${t.message ?: "unsupported media"}")
                }
            } finally {
                if (generation == projectOpenGeneration.get()) importJob = null
            }
        }
    }

    private fun setupPlayer() {
        val source = _state.value.source ?: return
        val p = player ?: ExoPlayer.Builder(
            getApplication(),
            DefaultRenderersFactory(getApplication()).setEnableDecoderFallback(true),
        ).build().also {
            player = it
            it.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    this@EditorViewModel.isPlaying.value = isPlaying
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    this@EditorViewModel.isPlaying.value = false
                    this@EditorViewModel.playbackError.value =
                        "Playback failed: ${error.message ?: "unsupported media"}"
                }
            })
        }
        playbackError.value = null
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
                // Preview-time skipping uses the exact padded/coalesced cuts
                // used by ExportPlanner, not the raw detector windows.
                if (previewSkipSilences.value && s.silenceRemovals.isNotEmpty()) {
                    val inside = ExportPlanner.effectiveCuts(
                        s.timeline.selectionStartMs,
                        s.timeline.selectionEndMs,
                        s.silenceRemovals,
                    ).firstOrNull { pos >= it.startMs && pos < it.endMs }
                    if (inside != null) {
                        val resumeAt = inside.endMs.coerceAtMost(s.timeline.selectionEndMs)
                        p.seekTo(resumeAt)
                        playheadMs.value = resumeAt
                        continue
                    }
                }
                playheadMs.value = pos
                updateFaceSelectionBoxes(pos)
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
        playbackError.value = null
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
        val source = _state.value.source ?: return
        val position = ms.coerceIn(0L, source.durationMs)
        player?.seekTo(position)
        playheadMs.value = position
        updateFaceSelectionBoxes(position, force = true)
    }

    private fun updateFaceSelectionBoxes(timeMs: Long, force: Boolean = false) {
        if (!force && kotlin.math.abs(timeMs - lastFaceBoxTimeMs) < 80L) return
        lastFaceBoxTimeMs = timeMs
        // Tracks are retained at 100-ms points. Select nearby boxes so overlays
        // follow playback without storing every decoder frame in UI state.
        val boxes = rawTracks.mapNotNull { track ->
            val nearest = track.minByOrNull { kotlin.math.abs(it.timeMs - timeMs) }
            nearest?.takeIf { kotlin.math.abs(it.timeMs - timeMs) <= 350L }
        }
        if (faceSelectionBoxes.value != boxes) faceSelectionBoxes.value = boxes
    }

    // ------------------------------------------------------------- timeline

    fun setSelection(startMs: Long, endMs: Long, tag: String = "selection", coalesceMs: Long = 500L) {
        val source = _state.value.source ?: return
        val minClipMs = minOf(1_000L, source.durationMs)
        val start = startMs.coerceIn(0L, (source.durationMs - minClipMs).coerceAtLeast(0L))
        val end = endMs.coerceIn(start + minClipMs, source.durationMs)
        val selectionChanged = start != _state.value.timeline.selectionStartMs || end != _state.value.timeline.selectionEndMs
        commit({ current ->
            val clearedTracking = if (selectionChanged) {
                // A path only describes the range that was actually scanned;
                // never let it silently extrapolate across a newly edited clip.
                current.tracking.copy(
                    autoEnabled = false,
                    targetFaceId = null,
                    detectedFaces = emptyList(),
                    autoPath = emptyList(),
                )
            } else {
                current.tracking
            }
            current.copy(
                timeline = current.timeline.copy(selectionStartMs = start, selectionEndMs = end),
                tracking = clearedTracking,
            )
        }, tag, coalesceMs)
        if (selectionChanged) cancelFaceDetectionPass()
        if (selectionChanged) {
            trackingError.value = null
            rawTracks = emptyList()
            lastFaceBoxTimeMs = Long.MIN_VALUE
            faceSelectionBoxes.value = emptyList()
        }
    }

    fun setTimelineZoom(zoom: Float) {
        commit({ it.copy(timeline = it.timeline.copy(zoom = zoom.coerceIn(1f, 16f))) }, "zoom", 300L)
    }

    // ------------------------------------------------------------- tracking

    fun setAutoTracking(enabled: Boolean) {
        if (!enabled) {
            // Update intent first so cancellation does not create a second
            // history entry merely to turn an already-disabled switch off.
            commit({ it.copy(tracking = it.tracking.copy(autoEnabled = false)) })
            cancelFaceDetectionPass()
            return
        }
        trackingError.value = null
        if (_state.value.tracking.autoPath.isEmpty()) {
            commit({ it.copy(tracking = it.tracking.copy(autoEnabled = true)) })
            startFaceDetectionPass()
        } else {
            commit({ it.copy(tracking = it.tracking.copy(autoEnabled = true)) })
        }
    }

    fun startFaceDetectionPass() {
        val snapshot = _state.value
        val source = snapshot.source ?: return
        val projectSession = projectSessionGeneration.get()
        // Each user request gets a token. A decoder can report progress/error
        // after cancellation, so a token is stronger than relying on Job state.
        val passGeneration = trackingPassGeneration.incrementAndGet()
        // A manually requested rescan supersedes any worker path calculation
        // based on the previous raw tracks, even while the old decoder drains.
        trackingPathGeneration.incrementAndGet()
        val runningPass = trackingJob
        if (runningPass?.isCompleted == false) {
            // A new user request must wait for the old decoder/ML Kit frame to
            // release. Starting both passes concurrently wastes memory and can
            // leave the latest request silently ignored after cancellation.
            trackingCancelled.set(true)
            runningPass.cancel()
            trackingRestartJob?.cancel()
            trackingRestartJob = viewModelScope.launch {
                runningPass.join()
                if (!isActive) return@launch
                // Clear our own marker before re-entering startFaceDetectionPass;
                // otherwise it would cancel this restart coroutine as though it
                // were a second user request.
                trackingRestartJob = null
                val current = _state.value
                if (projectSession == projectSessionGeneration.get() &&
                    current.id == snapshot.id && current.source?.uri == source.uri &&
                    current.timeline.selectionStartMs == snapshot.timeline.selectionStartMs &&
                    current.timeline.selectionEndMs == snapshot.timeline.selectionEndMs
                ) {
                    startFaceDetectionPass()
                }
            }
            return
        }
        trackingRestartJob?.cancel()
        trackingRestartJob = null
        trackingCancelled.set(false)
        trackingError.value = null
        trackingPassProgress.value = 0f
        trackingJob = viewModelScope.launch {
            fun isCurrentPass(): Boolean {
                val current = _state.value
                return passGeneration == trackingPassGeneration.get() &&
                    !trackingCancelled.get() &&
                    isCurrentProjectSession(projectSession, snapshot) &&
                    current.timeline.selectionStartMs == snapshot.timeline.selectionStartMs &&
                    current.timeline.selectionEndMs == snapshot.timeline.selectionEndMs
            }

            try {
                if (!isCurrentPass()) return@launch
                val result = faceTracker.detectOverRange(
                    uri = Uri.parse(source.uri),
                    startMs = snapshot.timeline.selectionStartMs,
                    endMs = snapshot.timeline.selectionEndMs,
                    rotationDegrees = source.rotationDegrees,
                    onProgress = { progress ->
                        if (isCurrentPass()) trackingPassProgress.value = progress
                    },
                    isCancelled = { trackingCancelled.get() },
                )
                if (!isCurrentPass()) throw CancellationException("Face tracking cancelled")

                // Association can walk tens of thousands of retained points on
                // long clips. Keep it off the UI dispatcher just like decoding;
                // otherwise the finished scan can freeze playback and controls.
                val prepared = withContext(Dispatchers.Default) {
                    val tracks = TrackingEngine.associateTracks(result.samples)
                    val firstDetectionTime = result.samples.firstOrNull { it.second.isNotEmpty() }?.first
                    val firstBoxes = firstDetectionTime?.let { time ->
                        tracks.mapNotNull { track -> track.firstOrNull { it.timeMs == time } }
                    }.orEmpty()
                    PreparedTrackingPass(
                        tracks = tracks,
                        firstDetectionTimeMs = firstDetectionTime,
                        firstBoxes = firstBoxes,
                        defaultTrack = TrackingEngine.largestTrack(tracks),
                        decodedFrameCount = result.decodedFrameCount,
                    )
                }
                if (!isCurrentPass()) throw CancellationException("Face tracking cancelled")

                var current = _state.value
                if (!isCurrentPass()) return@launch

                val defaultTrack = prepared.defaultTrack
                val smoothing = current.tracking.smoothing
                var path = defaultTrack?.let { track ->
                    withContext(Dispatchers.Default) { smoothTrack(track, smoothing) }
                }.orEmpty()
                if (!isCurrentPass()) throw CancellationException("Face tracking cancelled")
                current = _state.value
                if (!isCurrentPass()) return@launch

                // Honor a smoothing choice made while association was running.
                // The second worker calculation is rare, but prevents the UI
                // label and installed path from describing different presets.
                if (defaultTrack != null && current.tracking.smoothing != smoothing) {
                    path = withContext(Dispatchers.Default) {
                        smoothTrack(defaultTrack, current.tracking.smoothing)
                    }
                    if (!isCurrentPass()) throw CancellationException("Face tracking cancelled")
                    current = _state.value
                    if (!isCurrentPass()) return@launch
                }

                // Supersede any asynchronous re-smoothing request that was
                // based on the previous tracking pass before replacing tracks.
                trackingPathGeneration.incrementAndGet()
                rawTracks = prepared.tracks
                faceSelectionBoxes.value = prepared.firstBoxes
                lastFaceBoxTimeMs = prepared.firstDetectionTimeMs ?: Long.MIN_VALUE

                if (defaultTrack == null || prepared.decodedFrameCount == 0) {
                    commit(mutate = {
                        it.copy(
                            tracking = it.tracking.copy(
                                autoEnabled = false,
                                autoPath = emptyList(),
                                targetFaceId = null,
                                detectedFaces = emptyList(),
                            ),
                        )
                    })
                    trackingError.value = "No face was found in this selected range. You can still reframe it manually."
                } else {
                    val faceId = defaultTrack.first().faceId
                    commit(mutate = {
                        it.copy(
                            tracking = it.tracking.copy(
                                autoEnabled = true,
                                targetFaceId = faceId,
                                detectedFaces = prepared.firstBoxes,
                                autoPath = path,
                            ),
                        )
                    })
                    updateFaceSelectionBoxes(playheadMs.value, force = true)
                }
            } catch (_: CancellationException) {
                // Explicit cancellation is expected and leaves existing manual
                // framing untouched. Native decoder/detector resources close in FaceTracker.
            } catch (t: Throwable) {
                if (isCurrentPass()) {
                    commit(mutate = { it.copy(tracking = it.tracking.copy(autoEnabled = false)) })
                    trackingError.value = "Face tracking failed: ${t.message ?: "unsupported video decoder"}"
                }
            } finally {
                if (passGeneration == trackingPassGeneration.get()) {
                    trackingPassProgress.value = -1f
                }
            }
        }
    }

    fun cancelFaceDetectionPass() {
        trackingPassGeneration.incrementAndGet()
        trackingCancelled.set(true)
        trackingPathGeneration.incrementAndGet()
        trackingRestartJob?.cancel()
        trackingRestartJob = null
        // FaceTracker keeps an Image alive until the current ML Kit task
        // completes before cancellation unwinds its decoder resources.
        trackingJob?.cancel()
        trackingPassProgress.value = -1f

        // If the user enabled tracking solely to start this incomplete scan,
        // leave the switch honest rather than displaying "on" with no path.
        // A retained path from an earlier pass remains usable after cancelling
        // a rescan, so it is deliberately preserved.
        if (_state.value.tracking.autoEnabled && _state.value.tracking.autoPath.isEmpty()) {
            commit(mutate = { current ->
                if (current.tracking.autoPath.isEmpty()) {
                    current.copy(tracking = current.tracking.copy(autoEnabled = false))
                } else {
                    current
                }
            })
        }
    }

    /** Stop playback/scanning while the editor is no longer visible. */
    fun onEditorBackgrounded() {
        pause()
        cancelFaceDetectionPass()
    }

    private fun smoothTrack(track: List<FaceBox>, preset: SmoothingPreset): List<FacePoint> =
        TrackingSmoother.compact(
            TrackingSmoother.smooth(TrackingEngine.toPath(track), preset),
        )

    fun selectFace(faceId: Int) {
        val track = TrackingEngine.trackById(rawTracks, faceId) ?: return
        val sourceUri = _state.value.source?.uri
        val smoothing = _state.value.tracking.smoothing
        val generation = trackingPathGeneration.incrementAndGet()
        viewModelScope.launch {
            val path = withContext(Dispatchers.Default) { smoothTrack(track, smoothing) }
            // A newer face choice, rescan, cancellation or project replacement
            // wins over this worker result.
            if (generation != trackingPathGeneration.get() || _state.value.source?.uri != sourceUri) return@launch
            commit(mutate = {
                it.copy(
                    tracking = it.tracking.copy(
                        autoEnabled = true,
                        targetFaceId = faceId,
                        autoPath = path,
                    ),
                )
            })
            updateFaceSelectionBoxes(playheadMs.value, force = true)
        }
    }

    fun setSmoothing(preset: SmoothingPreset) {
        val current = _state.value
        val selectedId = current.tracking.targetFaceId
        val track = selectedId?.let { TrackingEngine.trackById(rawTracks, it) }
        // Update the selected preset promptly; the potentially large path is
        // then regenerated on a worker without blocking the Compose UI.
        commit(mutate = { it.copy(tracking = it.tracking.copy(smoothing = preset)) })
        if (track == null || selectedId == null) return

        val sourceUri = current.source?.uri
        val generation = trackingPathGeneration.incrementAndGet()
        viewModelScope.launch {
            val path = withContext(Dispatchers.Default) { smoothTrack(track, preset) }
            val latest = _state.value
            if (generation != trackingPathGeneration.get() || latest.source?.uri != sourceUri ||
                latest.tracking.targetFaceId != selectedId || latest.tracking.smoothing != preset
            ) return@launch
            commit(mutate = { it.copy(tracking = it.tracking.copy(autoPath = path)) })
            updateFaceSelectionBoxes(playheadMs.value, force = true)
        }
    }

    /** Adds a manual correction keyframe at the playhead with the current crop center. */
    fun addManualKeyframe() {
        val s = _state.value
        val source = s.source ?: return
        val time = playheadMs.value.coerceIn(s.timeline.selectionStartMs, s.timeline.selectionEndMs)
        // Preserve the currently evaluated auto/manual zoom rather than
        // introducing a visible jump back to 1x at a new correction point.
        val evaluated = TrackingEngine.evaluateAt(s.tracking, time)
        val current = com.shortsclipper.video.CropCalculator.clampCenter(
            evaluated.centerX,
            evaluated.centerY,
            evaluated.zoom,
            source.displayWidth,
            source.displayHeight,
        )
        val keyframe = TransformKeyframe(
            timeMs = time,
            centerXFrac = current.centerX,
            centerYFrac = current.centerY,
            zoom = current.zoom,
        )
        commit({ currentState ->
            val keyframes = currentState.tracking.manualKeyframes
                .filterNot { kotlin.math.abs(it.timeMs - time) <= 400L } + keyframe
            currentState.copy(tracking = currentState.tracking.copy(manualKeyframes = keyframes.sortedBy { it.timeMs }))
        })
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

    // ------------------------------------------------------- silence audio

    /** Prepares the bounded local audio envelope used by the retained silence editor. */
    fun prepareAudioForSilence() {
        val snapshot = _state.value
        val source = snapshot.source ?: return
        // An extractor/decoder can still be releasing its handles after its
        // coroutine has been cancelled. Keep one preparation pass at a time.
        if (audioPreparationJob?.isCompleted == false) return
        val projectSession = projectSessionGeneration.get()
        val runGeneration = audioPreparationGeneration.incrementAndGet()
        audioPreparationCancelled.set(false)
        audioPreparationError.value = null
        isPreparingAudio.value = true
        audioPreparationJob = viewModelScope.launch {
            var pcmFile: File? = null

            fun isCurrentAudioPreparation(): Boolean =
                runGeneration == audioPreparationGeneration.get() &&
                    !audioPreparationCancelled.get() &&
                    isCurrentProjectSession(projectSession, snapshot)

            try {
                val decoded = withContext(Dispatchers.IO) {
                    VideoManager.decodeAudio16kMono(
                        context = getApplication(),
                        uri = Uri.parse(source.uri),
                        isCancelled = { !isCurrentAudioPreparation() },
                    )
                }
                // Keep ownership before checking the generation. A completed
                // decoder can return just as cancellation arrives; finally
                // must still remove its temporary PCM file in that race.
                pcmFile = decoded?.pcmFile
                if (!isCurrentAudioPreparation()) throw CancellationException("Audio preparation cancelled")
                if (decoded == null) {
                    audioPreparationError.value = "This video has no audio track, so silence detection is unavailable."
                    return@launch
                }
                commit {
                    it.copy(
                        audioEnvelope = decoded.envelope,
                        audioStartMs = decoded.startTimeMs,
                        envelopeStepMs = SilenceDetector.STEP_MS,
                    )
                }
            } catch (_: CancellationException) {
                // Cancellation is expected when the source/project changes.
            } catch (t: Throwable) {
                if (isCurrentAudioPreparation()) {
                    audioPreparationError.value = "Could not analyze audio: ${t.message ?: "unsupported audio"}"
                }
            } finally {
                pcmFile?.delete()
                if (runGeneration == audioPreparationGeneration.get()) {
                    isPreparingAudio.value = false
                    audioPreparationJob = null
                }
            }
        }
    }

    fun cancelAudioPreparation() {
        audioPreparationGeneration.incrementAndGet()
        audioPreparationCancelled.set(true)
        audioPreparationJob?.cancel()
        isPreparingAudio.value = false
    }

    // -------------------------------------------------------------- silences

    fun detectSilences(thresholdDb: Float, minSilenceMs: Int) {
        val s = _state.value
        if (s.audioEnvelope.isEmpty()) return
        val silences = SilenceDetector.detect(
            s.audioEnvelope,
            SilenceDetector.Config(thresholdDb = thresholdDb, minSilenceMs = minSilenceMs),
            s.envelopeStepMs,
        ).map { silence ->
            silence.copy(
                startMs = silence.startMs + s.audioStartMs,
                endMs = silence.endMs + s.audioStartMs,
            )
        }
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

    // ---------------------------------------------------------------- export

    fun startExport(onDone: (String?) -> Unit = {}) {
        val exportSnapshot = _state.value
        // A cancelling Transformer job can have isActive=false before its
        // output and codec cleanup finish. Never overlap two export sessions.
        if (exportSnapshot.source == null || exportJob?.isCompleted == false) return
        val projectSession = projectSessionGeneration.get()
        val runGeneration = exportRunGeneration.incrementAndGet()
        exportCancelled.set(false)
        exportJob = viewModelScope.launch {
            fun isCurrentExportRun(): Boolean =
                runGeneration == exportRunGeneration.get() && isCurrentProjectSession(projectSession, exportSnapshot)

            val startedAt = System.currentTimeMillis()
            var ticker: Job? = null
            var outFile: File? = null
            var savedOutputUri: Uri? = null
            if (!isCurrentExportRun()) return@launch
            exportState.value = ExportState(phase = ExportPhase.PREPARING)
            try {
                val plan = withContext(kotlinx.coroutines.Dispatchers.Default) {
                    exportManager.computePlan(exportSnapshot)
                }
                if (!isCurrentExportRun()) throw CancellationException("Export superseded")
                val outDir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
                outFile = File(outDir, ".shortsclipper_${System.currentTimeMillis()}.tmp.mp4")
                if (isCurrentExportRun()) {
                    exportState.value = exportState.value.copy(phase = ExportPhase.TRANSFORMING, progressPercent = 0)
                }
                ticker = launch {
                    while (isActive) {
                        delay(500)
                        if (isCurrentExportRun() && exportState.value.isBusy) {
                            exportState.value = exportState.value.copy(elapsedMs = System.currentTimeMillis() - startedAt)
                        }
                    }
                }
                val exported = exportManager.export(
                    state = exportSnapshot,
                    plan = plan,
                    outFile = requireNotNull(outFile),
                    onProgress = { progress ->
                        if (isCurrentExportRun()) {
                            exportState.value = exportState.value.copy(progressPercent = progress.coerceIn(0, 100))
                        }
                    },
                    isCancelled = { exportCancelled.get() || !isCurrentExportRun() },
                )
                if (exportCancelled.get() || !isCurrentExportRun()) {
                    throw com.shortsclipper.video.ExportCancelledException()
                }
                exportState.value = exportState.value.copy(phase = ExportPhase.SAVING, progressPercent = 100)
                val outputUri = withContext(NonCancellable + Dispatchers.IO) {
                    exportManager.saveToGallery(
                        sourceFile = exported,
                        displayName = "ShortsClipper_${exportSnapshot.name.replace(Regex("[^a-zA-Z0-9_-]"), "_")}_${System.currentTimeMillis()}.mp4",
                        isCancelled = { exportCancelled.get() || !isCurrentExportRun() },
                    )
                }
                // NonCancellable above guarantees a URI returned after Gallery
                // publication is retained here, so the cancellation branch can
                // delete it instead of leaking a completed file to the user.
                savedOutputUri = outputUri
                if (exportCancelled.get() || !isCurrentExportRun()) {
                    throw com.shortsclipper.video.ExportCancelledException()
                }
                exportState.value = ExportState(
                    phase = ExportPhase.DONE,
                    progressPercent = 100,
                    elapsedMs = System.currentTimeMillis() - startedAt,
                    outputUri = outputUri.toString(),
                    message = "Saved to Gallery > Movies/${ExportManager.GALLERY_FOLDER}",
                )
                // UI callbacks must not turn a successfully saved export into
                // an apparent failure or trigger cleanup of its valid file.
                runCatching { onDone(null) }
            } catch (_: com.shortsclipper.video.ExportCancelledException) {
                discardCancelledGalleryOutput(savedOutputUri)
                if (isCurrentExportRun()) {
                    exportState.value = ExportState(phase = ExportPhase.CANCELLED, message = "Export cancelled")
                }
            } catch (_: CancellationException) {
                discardCancelledGalleryOutput(savedOutputUri)
                if (isCurrentExportRun()) {
                    exportState.value = ExportState(phase = ExportPhase.CANCELLED, message = "Export cancelled")
                }
            } catch (t: Throwable) {
                if (isCurrentExportRun()) {
                    val message = friendlyExportError(t)
                    exportState.value = ExportState(
                        phase = ExportPhase.FAILED,
                        elapsedMs = System.currentTimeMillis() - startedAt,
                        message = message,
                    )
                    runCatching { onDone(message) }
                }
            } finally {
                ticker?.cancel()
                outFile?.delete()
            }
        }
    }

    /** Deletes a just-created Gallery entry even after the export coroutine was cancelled. */
    private suspend fun discardCancelledGalleryOutput(uri: Uri?) {
        if (uri == null) return
        withContext(NonCancellable + Dispatchers.IO) {
            exportManager.deleteGalleryOutput(uri)
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
        // The transformer observes the signal; cancelling the coroutine also
        // stops its progress ticker and executes ExportManager cleanup promptly.
        exportJob?.cancel()
    }

    fun setExportQuality(mode: com.shortsclipper.model.QualityMode) {
        commit({ it.copy(exportQuality = mode) })
    }

    // ----------------------------------------------------------------- misc

    fun deleteProject(id: String) {
        viewModelScope.launch {
            val deleted = withContext(kotlinx.coroutines.Dispatchers.IO) { repository.delete(id) }
            if (deleted && _state.value.id == id) {
                replaceProject(repository.createProject("Untitled"))
            }
            refreshRecentProjects()
        }
    }

    override fun onCleared() {
        stopProjectWork()
        autosaveJob?.cancel()
        player?.release()
        player = null
        super.onCleared()
    }
}
