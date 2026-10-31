package io.github.alicejump.rhine

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import io.github.alicejump.rhine.ui.ArchiveAlbumFocusKey
import io.github.alicejump.rhine.ui.ArchiveBlankXKey
import io.github.alicejump.rhine.ui.ArchiveBlankYKey
import io.github.alicejump.rhine.ui.ArchiveBrowseAnchorKey
import io.github.alicejump.rhine.ui.ArchiveCameraCenterXKey
import io.github.alicejump.rhine.ui.ArchiveCameraCenterYKey
import io.github.alicejump.rhine.ui.ArchiveCameraZoomKey
import io.github.alicejump.rhine.ui.ArchiveCaseAssetReadyKey
import io.github.alicejump.rhine.ui.ArchiveCaseCenterXKey
import io.github.alicejump.rhine.ui.ArchiveCaseCenterYKey
import io.github.alicejump.rhine.ui.ArchiveCaseHeightKey
import io.github.alicejump.rhine.ui.ArchiveCaseWidthKey
import io.github.alicejump.rhine.ui.ArchiveExpansionKey
import io.github.alicejump.rhine.ui.ArchiveLaneKey
import io.github.alicejump.rhine.ui.ArchiveSelectedAlbumKey
import io.github.alicejump.rhine.ui.ArchiveSlotKey
import io.github.alicejump.rhine.ui.ArchiveVisibleWorldHeightKey
import io.github.alicejump.rhine.ui.ArchiveVisibleWorldWidthKey
import io.github.alicejump.rhine.ui.ArchiveViewportCenterXKey
import io.github.alicejump.rhine.ui.ArchiveViewportCenterYKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Reads rendered state and injects touch at the renderer's genuinely exposed hit regions. */
internal class ArchiveSceneProbe(private val compose: ComposeTestRule) {
    fun scene(): SemanticsNode = compose.onNodeWithTag("archive-scene", useUnmergedTree = true).fetchSemanticsNode()

    fun album(id: String): SemanticsNode = compose.onNodeWithTag("archive-album-$id", useUnmergedTree = true).fetchSemanticsNode()

    fun selectedId(): String = scene().config[ArchiveSelectedAlbumKey]
    fun anchorId(): String = scene().config[ArchiveBrowseAnchorKey]
    fun expansion(): Float = scene().config[ArchiveExpansionKey]
    fun caseAssetReady(): Boolean = scene().config[ArchiveCaseAssetReadyKey]
    fun zoom(): Float = scene().config[ArchiveCameraZoomKey]
    fun cameraCenter(): Offset = scene().config.let { Offset(it[ArchiveCameraCenterXKey], it[ArchiveCameraCenterYKey]) }
    fun worldWidth(): Float = scene().config[ArchiveVisibleWorldWidthKey]
    fun worldHeight(): Float = scene().config[ArchiveVisibleWorldHeightKey]
    fun focus(id: String): Float = album(id).config[ArchiveAlbumFocusKey]
    fun address(id: String): Pair<Int, Int> = album(id).config.let { it[ArchiveLaneKey] to it[ArchiveSlotKey] }
    fun caseCenter(id: String): Offset = album(id).config.let { Offset(it[ArchiveCaseCenterXKey], it[ArchiveCaseCenterYKey]) }
    fun caseBounds(id: String): Rect = album(id).config.let {
        val x = it[ArchiveCaseCenterXKey]
        val y = it[ArchiveCaseCenterYKey]
        val halfWidth = it[ArchiveCaseWidthKey] / 2f
        val halfHeight = it[ArchiveCaseHeightKey] / 2f
        Rect(x - halfWidth, y - halfHeight, x + halfWidth, y + halfHeight)
    }
    fun viewportCenter(): Offset = scene().config.let { Offset(it[ArchiveViewportCenterXKey], it[ArchiveViewportCenterYKey]) }

    fun visibleAlbums(): List<SemanticsNode> = compose.onAllNodes(
        SemanticsMatcher("an exposed archive album") {
            it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("archive-album-") == true
        },
        useUnmergedTree = true,
    ).fetchSemanticsNodes().filter { it.boundsInRoot.width > 0f && it.boundsInRoot.height > 0f }

    fun assertSelected(id: String) {
        assertEquals("The browsing anchor must not substitute for the explicit selection", id, selectedId())
        if (id.isEmpty()) {
            assertTrue("No exposed album may remain selected", visibleAlbums().none { it.config.getOrNull(SemanticsProperties.Selected) == true })
        } else {
            visibleAlbums().firstOrNull { it.config[SemanticsProperties.TestTag] == "archive-album-$id" }?.let {
                assertTrue("The selected exposed album must advertise selection", it.config[SemanticsProperties.Selected])
            }
        }
    }

    fun awaitMode(expanded: Boolean) {
        compose.waitUntil(10_000) {
            runCatching { if (expanded) expansion() > .999f else expansion() < .001f }.getOrDefault(false)
        }
        compose.waitForIdle()
    }

    fun awaitScene() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("archive-scene", useUnmergedTree = true).fetchSemanticsNodes()
                .any { it.config[ArchiveCaseAssetReadyKey] }
        }
        compose.waitForIdle()
    }

    fun touchAlbum(id: String) {
        // The node is a visible sliver, not the full face hidden behind the next case.
        compose.onNodeWithTag("archive-album-$id", useUnmergedTree = true)
            .assertIsDisplayed().performTouchInput { click(center) }
        compose.waitForIdle()
    }

    fun touchBlank() {
        val state = scene().config
        val x = checkNotNull(state.getOrNull(ArchiveBlankXKey)) { "This camera must expose an actual blank scene point" }
        val y = checkNotNull(state.getOrNull(ArchiveBlankYKey)) { "This camera must expose an actual blank scene point" }
        compose.onNodeWithTag("archive-scene", useUnmergedTree = true).performTouchInput { click(Offset(x, y)) }
        compose.waitForIdle()
    }

    fun canvasBounds(): Rect = compose.onNodeWithTag(canvasTag()).fetchSemanticsNode().boundsInRoot

    fun pan(xFraction: Float, yFraction: Float) {
        compose.onNodeWithTag(canvasTag()).performTouchInput {
            val start = center
            down(start)
            moveTo(start + Offset(width * xFraction, height * yFraction), delayMillis = 240)
            up()
        }
        compose.waitForIdle()
    }

    fun moveSelectedTo(destinationInHost: Offset = viewportCenter()) {
        val delta = destinationInHost - caseCenter(selectedId())
        val canvas = compose.onNodeWithTag(canvasTag())
        val bounds = canvas.fetchSemanticsNode().boundsInRoot
        val exposedStart = album(selectedId()).boundsInRoot.center - bounds.topLeft
        canvas.performTouchInput {
            down(exposedStart)
            moveTo(exposedStart + delta, delayMillis = 240)
            up()
        }
        compose.waitForIdle()
    }

    fun beginCenteredPinch(maximumScale: Float = 3.5f): HeldArchivePinch {
        val host = scene().boundsInRoot
        val pivot = viewportCenter()
        val canvas = canvasBounds()
        val rootPivot = pivot + host.topLeft
        val available = minOf(rootPivot.x - canvas.left, canvas.right - rootPivot.x) * .82f
        return HeldArchivePinch(
            compose.onNodeWithTag("archive-scene", useUnmergedTree = true),
            pivot,
            available / maximumScale,
        )
    }

    fun pinch(scale: Float, pivotInRoot: Offset? = null, steps: Int = 16) {
        val target = compose.onNodeWithTag(canvasTag())
        val bounds = target.fetchSemanticsNode().boundsInRoot
        target.performTouchInput {
            val pivot = pivotInRoot?.minus(bounds.topLeft) ?: center
            val available = minOf(pivot.x, width - pivot.x, pivot.y, height - pivot.y) * .82f
            val start = available / maxOf(1f, scale)
            val finish = start * scale
            assertTrue("Both fingers must start inside the visible canvas", available > 12f)
            down(0, pivot - Offset(start, 0f))
            down(1, pivot + Offset(start, 0f))
            for (step in 1..steps) {
                val distance = start + (finish - start) * step / steps
                updatePointerTo(0, pivot - Offset(distance, 0f))
                updatePointerTo(1, pivot + Offset(distance, 0f))
                move(delayMillis = 16)
            }
            up(1)
            up(0)
        }
        compose.waitForIdle()
    }

    private fun canvasTag(): String = if (expansion() > .001f) "panorama-canvas" else "archive-shelf"
}

/** Keeps the same physical pointers down across clock samples and frame captures. */
internal class HeldArchivePinch(
    private val target: SemanticsNodeInteraction,
    private val pivot: Offset,
    private val initialSpan: Float,
) : AutoCloseable {
    init {
        check(initialSpan > 8f) { "The viewport must leave room for both fingers" }
        target.performTouchInput {
            down(0, pivot - Offset(initialSpan, 0f))
            down(1, pivot + Offset(initialSpan, 0f))
        }
    }

    fun moveToScale(scale: Float) {
        target.performTouchInput {
            updatePointerTo(0, pivot - Offset(initialSpan * scale, 0f))
            updatePointerTo(1, pivot + Offset(initialSpan * scale, 0f))
            move(delayMillis = 16)
        }
    }

    override fun close() {
        target.performTouchInput {
            up(1)
            up(0)
        }
    }
}
