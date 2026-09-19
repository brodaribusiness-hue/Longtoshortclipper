package com.shortsclipper

import com.shortsclipper.model.ExportPlanner
import com.shortsclipper.model.ProjectState
import com.shortsclipper.model.SilenceEdit
import com.shortsclipper.model.TimelineState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {

    @Test
    fun `builds one segment without removals`() {
        val segments = ExportPlanner.buildSegments(1000, 10_000, emptyList())
        assertEquals(1, segments.size)
        assertEquals(1000L, segments[0].startMs)
        assertEquals(10_000L, segments[0].endMs)
    }

    @Test
    fun `invalid range produces no segments`() {
        assertTrue(ExportPlanner.buildSegments(5000, 5000, emptyList()).isEmpty())
        assertTrue(ExportPlanner.buildSegments(6000, 5000, emptyList()).isEmpty())
    }

    @Test
    fun `removes interior silence and keeps padding`() {
        val segments = ExportPlanner.buildSegments(0, 20_000, listOf(SilenceEdit(5_000, 8_000)))
        assertEquals(2, segments.size)
        // 120ms of quiet is kept on each side so words are never clipped
        assertEquals(0L, segments[0].startMs)
        assertEquals(5_000L + ExportPlanner.KEEP_PAD_MS, segments[0].endMs)
        assertEquals(8_000L - ExportPlanner.KEEP_PAD_MS, segments[1].startMs)
        assertEquals(20_000L, segments[1].endMs)
    }

    @Test
    fun `removals outside selection are ignored`() {
        val segments = ExportPlanner.buildSegments(10_000, 20_000, listOf(SilenceEdit(0, 5_000), SilenceEdit(25_000, 30_000)))
        assertEquals(1, segments.size)
        assertEquals(10_000L, segments[0].startMs)
        assertEquals(20_000L, segments[0].endMs)
    }

    @Test
    fun `micro segments are merged to avoid chopping speech`() {
        // Many tiny cuts produce slivers that must merge into valid segments.
        val cuts = (0 until 5).map { SilenceEdit(1_000L * (it * 2 + 1), 1_000L * (it * 2 + 1) + 50) }
        val segments = ExportPlanner.buildSegments(0, 20_000, cuts)
        assertTrue(segments.all { it.durationMs >= ExportPlanner.MIN_SEGMENT_MS })
        assertTrue(segments.isNotEmpty())
    }

    @Test
    fun `edited duration equals sum of segments`() {
        val state = ProjectState(id = "t", name = "t").copy(
            timeline = TimelineState(0, 20_000),
            silenceRemovals = listOf(SilenceEdit(5_000, 8_000)),
        )
        assertEquals(20_000L - 2_760L, state.editedDurationMs)
    }

    @Test
    fun `sourceAt maps edited time back to source time`() {
        val state = ProjectState(id = "t", name = "t").copy(
            timeline = TimelineState(0, 20_000),
            silenceRemovals = listOf(SilenceEdit(5_000, 8_000)),
        )
        val cutStart = 5_000L + ExportPlanner.KEEP_PAD_MS // 5120
        val cutEnd = 8_000L - ExportPlanner.KEEP_PAD_MS   // 7880
        assertEquals(cutStart - 1, state.sourceAt(cutStart - 1)) // before cut: identity
        assertEquals(cutEnd, state.sourceAt(cutStart))           // first edited ms jumps past the cut
        assertEquals(cutEnd + 1, state.sourceAt(cutStart + 1))
        // last edited ms maps just before the end of the source
        val edited = state.editedDurationMs
        assertEquals(19_999L, state.sourceAt(edited - 1))
    }

    @Test
    fun `very short clip of one second is valid`() {
        val segments = ExportPlanner.buildSegments(0, 1_000, emptyList())
        assertEquals(1, segments.size)
        assertEquals(1_000L, segments[0].durationMs)
    }
}
