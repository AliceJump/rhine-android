package io.github.alicejump.rhine.ui

import kotlin.math.exp
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanoramaPinchHandoffTest {
    private fun zoom(logAmount: Double) = exp(logAmount).toFloat()
    private fun log(value: Float) = ln(value.toDouble())
    private val selected = PanoramaExitGate("selected", 1f)

    @Test
    fun crossingAppliesTheCameraPrefixAndCollapseRemainderInTheSameEvent() {
        val motion = PanoramaPinchHandoff(collapseLogSpan = .5f)
        val eventZoom = 1.5f
        val gateZoom = 1.2f
        val step = motion.step(eventZoom, true, selected.copy(zoomToHandoff = gateZoom))

        assertEquals(gateZoom, step.cameraZoom, .00001f)
        val progress = requireNotNull(step.expansion)
        assertTrue(progress in 0f..1f && progress < 1f)
        // None of the first crossing event is discarded or applied twice.
        assertEquals(log(eventZoom), log(step.cameraZoom) + (1f - progress) * .5, .00001)
        assertEquals((log(gateZoom) / log(eventZoom)).toFloat(), step.cameraPanFraction, .00001f)
        assertTrue(motion.suppressTap)
    }

    @Test
    fun missingOffCentreAndUnreachedGatesLeaveTheWholeEventWithTheCamera() {
        // Null covers no selection and a UI geometry check rejecting the scene centre.
        for (gate in listOf(null, selected.copy(zoomToHandoff = 1.5f))) {
            val motion = PanoramaPinchHandoff()
            val step = motion.step(1.2f, true, gate)
            assertEquals(1.2f, step.cameraZoom, 0f)
            assertEquals(1f, step.cameraPanFraction, 0f)
            assertNull(step.expansion)
            assertNull(motion.settleExpanded())
        }
    }

    @Test
    fun exactBoundarySinglePointerAndNonEnlargingEventsDoNotStartCollapse() {
        val boundary = PanoramaPinchHandoff()
        assertNull(boundary.step(1.2f, true, selected.copy(zoomToHandoff = 1.2f)).expansion)
        assertNull(boundary.settleExpanded())
        for ((eventZoom, multiple) in listOf(1.5f to false, 1f to true, .8f to true)) {
            val motion = PanoramaPinchHandoff()
            val step = motion.step(eventZoom, multiple, selected)
            assertEquals(eventZoom, step.cameraZoom, 0f)
            assertNull(step.expansion)
            assertNull(motion.settleExpanded())
        }
    }

    @Test
    fun reversalUnwindsCollapseThenReturnsItsRemainderToTheCamera() {
        val motion = PanoramaPinchHandoff(collapseLogSpan = .5f)
        motion.step(zoom(.2), true, selected)
        assertEquals(.6f, motion.expansion, .00001f)

        val partial = motion.step(zoom(-.1), true, null)
        assertEquals(.8f, requireNotNull(partial.expansion), .00001f)
        assertEquals(1f, partial.cameraZoom, 0f)

        val before = motion.expansion
        val reverseZoom = zoom(-.2)
        val returned = motion.step(reverseZoom, true, null)
        assertEquals(1f, requireNotNull(returned.expansion), 0f)
        assertEquals(zoom(-.1), returned.cameraZoom, .00001f)
        assertEquals(log(reverseZoom), -(1f - before) * .5 + log(returned.cameraZoom), .00001)
        assertEquals(.5f, returned.cameraPanFraction, .00001f)
        assertEquals(true, motion.settleExpanded())
    }

    @Test
    fun outwardDeadBandPreventsRepeatedHandoffsUntilTheCameraReallyMovesAway() {
        val motion = PanoramaPinchHandoff(collapseLogSpan = .5f, rearmLogDistance = .1f)
        motion.step(zoom(.1), true, selected)
        motion.step(zoom(-.1), true, null)
        assertEquals(1f, motion.expansion, .00001f)

        // A small inward reversal at the same boundary must stay in camera mode.
        assertNull(motion.step(zoom(.02), true, selected).expansion)
        assertNull(motion.step(zoom(-.04), true, null).expansion)
        assertNull(motion.step(zoom(.02), true, selected).expansion)
        // The *net* camera distance now crosses the outward hysteresis threshold.
        assertNull(motion.step(zoom(-.12), true, null).expansion)
        assertTrue(requireNotNull(motion.step(zoom(.03), true, selected).expansion) < 1f)
    }

    @Test
    fun reachingZeroCommitsAndConsumesEvenALargeReverseBeforePointerUp() {
        val motion = PanoramaPinchHandoff(collapseLogSpan = .5f)
        assertEquals(0f, requireNotNull(motion.step(zoom(.6), true, selected).expansion), 0f)
        assertTrue(motion.committed)
        val reverse = motion.step(zoom(-2.0), true, selected)
        assertEquals(1f, reverse.cameraZoom, 0f)
        assertEquals(0f, reverse.cameraPanFraction, 0f)
        assertNull(reverse.expansion)
        assertEquals(0f, motion.expansion, 0f)
        assertEquals(false, motion.settleExpanded())
        assertTrue(motion.suppressTap)
    }

    @Test
    fun remainingFingerAndReleaseCannotTurnAnUnwoundPinchIntoATap() {
        val motion = PanoramaPinchHandoff(collapseLogSpan = .5f)
        motion.step(zoom(.2), true, selected)
        val beforeLift = motion.expansion
        val remainingFinger = motion.step(1.2f, false, selected)
        assertEquals(0f, remainingFinger.cameraPanFraction, 0f)
        assertEquals(beforeLift, motion.expansion, 0f)

        motion.step(zoom(-.3), true, null)
        assertEquals(1f, motion.expansion, 0f)
        // Even though ownership returned to camera, this original gesture stays consumed.
        motion.step(1f, false, null)
        assertEquals(true, motion.settleExpanded())
        assertTrue(motion.suppressTap)
    }

    @Test
    fun aSingleGestureKeepsItsOriginalAlbumWhenItRearms() {
        val motion = PanoramaPinchHandoff(collapseLogSpan = .5f)
        motion.step(zoom(.1), true, selected)
        motion.step(zoom(-.3), true, null)
        assertEquals(1f, motion.expansion, 0f)
        val differentAlbum = motion.step(zoom(.1), true, PanoramaExitGate("different", 1f))
        assertNull(differentAlbum.expansion)
        assertEquals(zoom(.1), differentAlbum.cameraZoom, 0f)
    }

    @Test
    fun invalidGeometryOrZoomCannotPutNonFiniteValuesIntoExpansion() {
        for (factor in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0f, -1f)) {
            val motion = PanoramaPinchHandoff()
            val rejectedGate = motion.step(1.2f, true, selected.copy(zoomToHandoff = factor))
            assertNull(rejectedGate.expansion)
            assertEquals(1.2f, rejectedGate.cameraZoom, 0f)
            val rejectedZoom = motion.step(factor, true, selected)
            assertEquals(1f, rejectedZoom.cameraZoom, 0f)
            assertEquals(1f, motion.expansion, 0f)
            assertNull(motion.settleExpanded())
        }
    }
}
