package com.shortsclipper

import com.shortsclipper.model.HistoryStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryStackTest {

    @Test
    fun `push undo redo`() {
        val stack = HistoryStack<Int>()
        stack.push(1)
        stack.push(2)
        stack.push(3)
        assertEquals(3, stack.current())
        assertEquals(2, stack.undo())
        assertEquals(1, stack.undo())
        assertNull(stack.undo())
        assertFalse(stack.canUndo)
        assertEquals(2, stack.redo())
        assertEquals(3, stack.redo())
        assertNull(stack.redo())
    }

    @Test
    fun `push truncates redo branch`() {
        val stack = HistoryStack<Int>()
        stack.push(1)
        stack.push(2)
        stack.undo()
        stack.push(9)
        assertNull(stack.redo())
        assertEquals(9, stack.current())
        assertEquals(1, stack.undo())
    }

    @Test
    fun `same tag inside window coalesces into one entry`() {
        val stack = HistoryStack<Int>()
        stack.push(1)
        stack.push(2, tag = "drag", coalesceMs = 500, nowMs = 1_000)
        stack.push(3, tag = "drag", coalesceMs = 500, nowMs = 1_200)
        stack.push(4, tag = "drag", coalesceMs = 500, nowMs = 1_400)
        assertEquals(4, stack.current())
        assertEquals(1, stack.undo()) // the whole drag was a single undo step
        assertFalse(stack.canRedo)
    }

    @Test
    fun `different tags do not coalesce`() {
        val stack = HistoryStack<Int>()
        stack.push(1)
        stack.push(2, tag = "a", coalesceMs = 500, nowMs = 1_000)
        stack.push(3, tag = "b", coalesceMs = 500, nowMs = 1_100)
        assertEquals(3, stack.current())
        assertEquals(2, stack.undo())
    }

    @Test
    fun `old tag outside window pushes new entry`() {
        val stack = HistoryStack<Int>()
        stack.push(1)
        stack.push(2, tag = "drag", coalesceMs = 100, nowMs = 1_000)
        stack.push(3, tag = "drag", coalesceMs = 100, nowMs = 5_000)
        assertEquals(3, stack.current())
        assertEquals(2, stack.undo())
    }

    @Test
    fun `max size keeps most recent states`() {
        val stack = HistoryStack<Int>(maxSize = 3)
        for (i in 1..10) stack.push(i)
        assertEquals(10, stack.current())
        assertEquals(9, stack.undo())
        assertEquals(3, stack.size)
    }
}
