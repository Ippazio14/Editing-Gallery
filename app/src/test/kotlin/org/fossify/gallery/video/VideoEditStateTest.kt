package org.fossify.gallery.video

import org.junit.Assert.*
import org.junit.Test

class VideoEditStateTest {
    @Test fun trimCannotBeEmptyReversedOrBeyondSource() {
        val edit = VideoEditState(endMs = 120000)
        assertTrue(edit.valid(120000))
        assertFalse(edit.copy(startMs = 120000).valid(120000))
        assertFalse(edit.copy(startMs = -1).valid(120000))
        assertFalse(edit.copy(endMs = 120001).valid(120000))
        assertTrue(edit.copy(startMs = 1500, endMs = 3000).valid(120000))
    }
    @Test fun cropMustRemainWithinFrameWithPositiveArea() {
        val edit = VideoEditState(endMs = 1000)
        assertFalse(edit.copy(left = .8f, right = .2f).valid(1000))
        assertFalse(edit.copy(bottom = 1.1f).valid(1000))
        assertFalse(edit.copy(top = Float.NaN).valid(1000))
        assertTrue(edit.copy(left = .1f, top = .2f, right = .9f, bottom = .8f, rotation = 90).valid(1000))
    }
    @Test fun estimateUsesTrimmedDurationResolutionAndMeasuredDeviceSpeed() {
        val long = VideoEditState(endMs = 120000)
        val short = long.copy(endMs = 30000)
        val baseline = long.estimateSeconds(1920, 1080, 30f, null)
        assertTrue(short.estimateSeconds(1920, 1080, 30f, null).last < baseline.last)
        assertTrue(long.estimateSeconds(3840, 2160, 30f, null).last > baseline.last)
        assertTrue(long.estimateSeconds(1920, 1080, 30f, .5f).last < baseline.last)
        assertTrue(baseline.first > 0 && baseline.last > baseline.first)
    }
}
