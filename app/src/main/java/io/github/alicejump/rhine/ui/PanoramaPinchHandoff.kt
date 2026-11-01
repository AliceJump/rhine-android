package io.github.alicejump.rhine.ui

import kotlin.math.exp
import kotlin.math.ln

/**
 * UI geometry authorizes a handoff for this album and supplies the remaining camera zoom.
 * [zoomToHandoff] is relative to the current camera, not an absolute camera zoom. The UI
 * must check the album's projected size and its centre at the proposed handoff position.
 * A null gate means that no album is selected, or that the selected album is off-centre.
 */
internal data class PanoramaExitGate(val albumId: String, val zoomToHandoff: Float)

/** Parts of one pointer event. The UI updates the camera before publishing expansion. */
internal data class PanoramaPinchStep(
    val cameraZoom: Float = 1f,
    val cameraPanFraction: Float = 0f,
    val expansion: Float? = null,
)

/**
 * Keeps a panorama pinch alive while it changes from camera zoom to shelf collapse.
 * It knows no pixels, Android types, or layout thresholds: the UI supplies the geometry gate.
 *
 * Log zoom is additive. If an event crosses the gate, only the required prefix zooms the
 * camera; the remainder moves expansion during the SAME event. Reversing first unwinds
 * that expansion and then returns any remaining zoom to the camera. Pan uses the same
 * event fraction; the collapse-owned portion is consumed while its anchor stays fixed.
 * Reaching zero commits the close and consumes the rest of the gesture.
 *
 * After reversing to panorama, another handoff is disarmed until the camera has moved
 * outward by [rearmLogDistance] from the handoff camera. This is a relative zoom dead band
 * (0.10 log units is about 9.5% smaller), not a device-dependent absolute zoom threshold.
 * Small alternating deltas therefore cannot repeatedly switch owners at the boundary.
 */
internal class PanoramaPinchHandoff(
    private val collapseLogSpan: Float = .65f,
    private val rearmLogDistance: Float = .10f,
    private val settleExpandedAt: Float = .35f,
) {
    private enum class Owner { Camera, Collapse, Committed }

    private var owner = Owner.Camera
    private var progress = 1.0
    private var returningCamera = false
    private var cameraLogSinceReturn = 0.0
    private var handoffAlbumId: String? = null

    var suppressTap: Boolean = false
        private set

    val expansion: Float get() = progress.toFloat()
    val committed: Boolean get() = owner == Owner.Committed

    init {
        require(collapseLogSpan.isFinite() && collapseLogSpan > 0f)
        require(rearmLogDistance.isFinite() && rearmLogDistance > 0f)
        require(settleExpandedAt.isFinite() && settleExpandedAt in 0f..1f)
    }

    fun step(zoomChange: Float, multiplePointers: Boolean, gate: PanoramaExitGate?): PanoramaPinchStep {
        val safeZoom = zoomChange.takeIf { it.isFinite() && it > 0f } ?: 1f
        val logZoom = ln(safeZoom.toDouble())
        return when (owner) {
            Owner.Committed -> PanoramaPinchStep()
            Owner.Collapse -> {
                // A remaining finger belongs to the pinch, not a new camera pan or tap.
                if (!multiplePointers || logZoom == 0.0) PanoramaPinchStep()
                else collapse(logZoom)
            }
            Owner.Camera -> camera(safeZoom, logZoom, multiplePointers, gate)
        }
    }

    /** Null means this gesture never owned expansion and must not change its target. */
    fun settleExpanded(): Boolean? = if (suppressTap) !committed && progress >= settleExpandedAt else null

    private fun camera(
        zoomChange: Float, logZoom: Double, multiplePointers: Boolean, gate: PanoramaExitGate?,
    ): PanoramaPinchStep {
        if (returningCamera) {
            cameraLogSinceReturn += logZoom
            if (cameraLogSinceReturn <= -rearmLogDistance) returningCamera = false
            return PanoramaPinchStep(cameraZoom = zoomChange, cameraPanFraction = 1f)
        }
        val required = gate?.zoomToHandoff
        if (!multiplePointers || logZoom <= 0.0 || gate == null || required == null ||
            !required.isFinite() || required <= 0f ||
            (handoffAlbumId != null && handoffAlbumId != gate.albumId)) {
            return PanoramaPinchStep(cameraZoom = zoomChange, cameraPanFraction = 1f)
        }
        val cameraLog = ln(required.coerceAtLeast(1f).toDouble())
        // Arriving exactly at the gate is still camera motion. Only an actual remainder
        // can begin collapse, so a stationary boundary does not acquire another owner.
        if (logZoom - cameraLog <= EPSILON) return PanoramaPinchStep(cameraZoom = zoomChange, cameraPanFraction = 1f)

        handoffAlbumId = gate.albumId
        suppressTap = true
        owner = Owner.Collapse
        progress = boundedProgress(1.0 - (logZoom - cameraLog) / collapseLogSpan)
        if (progress <= 0.0) owner = Owner.Committed
        return PanoramaPinchStep(
            cameraZoom = exp(cameraLog).toFloat(),
            cameraPanFraction = (cameraLog / logZoom).toFloat().coerceIn(0f, 1f),
            expansion = expansion,
        )
    }

    private fun collapse(logZoom: Double): PanoramaPinchStep {
        val next = progress - logZoom / collapseLogSpan
        if (next < 1.0 - EPSILON) {
            progress = boundedProgress(next)
            if (progress == 0.0) owner = Owner.Committed
            return PanoramaPinchStep(expansion = expansion)
        }

        val unwindLog = -(1.0 - progress) * collapseLogSpan
        val cameraLog = (logZoom - unwindLog).coerceAtMost(0.0)
        progress = 1.0
        owner = Owner.Camera
        cameraLogSinceReturn = cameraLog
        returningCamera = cameraLog > -rearmLogDistance
        return PanoramaPinchStep(
            cameraZoom = exp(cameraLog).toFloat(),
            cameraPanFraction = (cameraLog / logZoom).toFloat().coerceIn(0f, 1f),
            expansion = 1f,
        )
    }

    private fun boundedProgress(value: Double): Double = when {
        value <= EPSILON -> 0.0
        value >= 1.0 - EPSILON -> 1.0
        else -> value
    }

    private companion object {
        const val EPSILON = .000001
    }
}
