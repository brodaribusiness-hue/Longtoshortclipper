package com.shortsclipper

import com.shortsclipper.data.ProjectRepository
import com.shortsclipper.model.ClipCandidate
import com.shortsclipper.model.ScoreComponents
import com.shortsclipper.model.SilenceEdit
import com.shortsclipper.model.TargetDuration
import com.shortsclipper.model.TimelineState
import com.shortsclipper.model.TransformKeyframe
import com.shortsclipper.model.Transcript
import com.shortsclipper.model.VideoSource
import com.shortsclipper.model.Word
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun fullProject(id: String) = com.shortsclipper.model.ProjectState(
        id = id,
        name = "Interview $id",
        createdAtMs = 1_000L,
        updatedAtMs = 2_000L,
        source = VideoSource(
            uri = "content://media/video/123",
            displayName = "interview.mp4",
            durationMs = 600_000,
            width = 1920,
            height = 1080,
            rotationDegrees = 0,
            displayWidth = 1920,
            displayHeight = 1080,
            fps = 30f,
            hasAudio = true,
            videoMimeType = "video/avc",
            audioMimeType = "audio/mp4a-latm",
        ),
        timeline = TimelineState(10_000, 45_000, zoom = 2.5f),
        transcript = Transcript(
            language = "en",
            words = listOf(Word("hello", 10_500, 10_800, 0.93f), Word("world.", 10_900, 11_200, 0.91f)),
            segments = listOf(com.shortsclipper.model.Segment("hello world.", 10_500, 11_200)),
        ),
        audioEnvelope = listOf(0.1f, 0.5f, 0.3f),
        detectedSilences = listOf(SilenceEdit(20_000, 21_000)),
        silenceRemovals = listOf(SilenceEdit(20_000, 21_000)),
        candidates = listOf(
            ClipCandidate(
                id = "c1", startMs = 10_000, endMs = 40_000, durationMs = 30_000,
                score = ScoreComponents(8f, 7f, 8f, 9f, 6.5f),
                potential = 7.8f,
                reasons = listOf("Starts with a question"),
                title = "Hook clip",
            ),
        ),
        rejectedCandidateIds = setOf("c2"),
        selectedCandidateId = "c1",
        targetDuration = TargetDuration.T60,
        tracking = com.shortsclipper.model.TrackingState(
            autoEnabled = true,
            targetFaceId = 3,
            autoPath = listOf(com.shortsclipper.model.FacePoint(0, 0.5f, 0.4f, 0.2f)),
            manualKeyframes = listOf(TransformKeyframe(5_000, 0.4f, 0.5f, 1.25f)),
        ),
    )

    @Test
    fun `save and load round trip preserves full state`() {
        val repo = ProjectRepository(tmp.root)
        val project = fullProject("p1")
        assertTrue(repo.save(project))
        val loaded = repo.load("p1")
        assertNotNull(loaded)
        assertEquals(project, loaded)
    }

    @Test
    fun `list returns projects sorted by update time`() {
        val repo = ProjectRepository(tmp.root)
        repo.save(fullProject("old").copy(updatedAtMs = 100))
        repo.save(fullProject("new").copy(updatedAtMs = 200))
        val list = repo.list()
        assertEquals(listOf("new", "old"), list.map { it.id })
    }

    @Test
    fun `delete removes the project`() {
        val repo = ProjectRepository(tmp.root)
        repo.save(fullProject("gone"))
        assertTrue(repo.delete("gone"))
        assertNull(repo.load("gone"))
        assertFalse(repo.delete("gone"))
    }

    @Test
    fun `corrupt file loads as null without crashing`() {
        val repo = ProjectRepository(tmp.root)
        tmp.newFile("projects/bad.json").writeText("{ not json ")
        assertNull(repo.load("bad"))
    }

    @Test
    fun `missing project loads as null`() {
        assertNull(ProjectRepository(tmp.root).load("nope"))
    }

    @Test
    fun `createProject assigns id and defaults`() {
        val repo = ProjectRepository(tmp.root)
        val project = repo.createProject("My clip", nowMs = 5)
        assertEquals("My clip", project.name)
        assertTrue(project.id.isNotBlank())
        assertEquals(5L, project.createdAtMs)
        assertEquals(TargetDuration.T30, project.targetDuration)
    }
}
