package com.example.eyecontrol

import org.junit.Assert.assertEquals
import org.junit.Test

class EyeClosureDetectorTest {
    private fun closure(left: Boolean, right: Boolean, duration: Long): EyeClosureDetector.Event {
        val detector = EyeClosureDetector()
        detector.update(false, false, 0)
        var t = 100L
        while (t < 100 + duration) {
            detector.update(left, right, t)
            t += 50
        }
        return detector.update(false, false, 100 + duration)
    }
    @Test fun shortBlinkNeverBecomesClick() {
        for (duration in listOf(50L, 100L, 150L, 250L, 400L))
            assertEquals(EyeClosureDetector.Event.NATURAL, closure(true, true, duration))
    }
    @Test fun deliberateClosureIsDistinct() {
        assertEquals(EyeClosureDetector.Event.DELIBERATE, closure(true, true, 700))
    }
    @Test fun winksAreDistinctFromClick() {
        assertEquals(EyeClosureDetector.Event.LEFT_WINK, closure(true, false, 700))
        assertEquals(EyeClosureDetector.Event.RIGHT_WINK, closure(false, true, 700))
    }
    @Test fun prolongedClosureDoesNotClick() {
        assertEquals(EyeClosureDetector.Event.NONE, closure(true, true, 1800))
    }
    @Test fun lostTrackingDoesNotCompleteBlink() {
        val detector = EyeClosureDetector()
        detector.update(true, true, 100)
        assertEquals(EyeClosureDetector.Event.NONE, detector.update(false, false, 800))
    }
    @Test fun mixedWinkAndBlinkDoesNotClick() {
        val detector = EyeClosureDetector()
        detector.update(true, false, 100)
        for (t in 150L..750L step 50) detector.update(true, true, t)
        assertEquals(EyeClosureDetector.Event.NONE, detector.update(false, false, 800))
    }
    @Test fun resetCancelsPendingClosure() {
        val detector = EyeClosureDetector()
        for (t in 100L..700L step 100) detector.update(true, true, t)
        detector.reset()
        assertEquals(EyeClosureDetector.Event.NONE, detector.update(false, false, 800))
    }
}
