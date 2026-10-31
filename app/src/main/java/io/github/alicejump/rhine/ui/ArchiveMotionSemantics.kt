package io.github.alicejump.rhine.ui

import androidx.compose.ui.semantics.SemanticsPropertyKey

/** The rendered expansion, exposed so accessibility/UI checks observe the actual frame. */
internal val ArchiveExpansionKey = SemanticsPropertyKey<Float>("ArchiveExpansion")

internal val ArchiveSelectedAlbumKey = SemanticsPropertyKey<String>("ArchiveSelectedAlbum")
internal val ArchiveBrowseAnchorKey = SemanticsPropertyKey<String>("ArchiveBrowseAnchor")
internal val ArchiveCameraZoomKey = SemanticsPropertyKey<Float>("ArchiveCameraZoom")
internal val ArchiveCameraCenterXKey = SemanticsPropertyKey<Float>("ArchiveCameraCenterX")
internal val ArchiveCameraCenterYKey = SemanticsPropertyKey<Float>("ArchiveCameraCenterY")
internal val ArchiveVisibleWorldWidthKey = SemanticsPropertyKey<Float>("ArchiveVisibleWorldWidth")
internal val ArchiveVisibleWorldHeightKey = SemanticsPropertyKey<Float>("ArchiveVisibleWorldHeight")
internal val ArchiveAlbumFocusKey = SemanticsPropertyKey<Float>("ArchiveAlbumFocus")
internal val ArchiveLaneKey = SemanticsPropertyKey<Int>("ArchiveLane")
internal val ArchiveSlotKey = SemanticsPropertyKey<Int>("ArchiveSlot")
internal val ArchiveBlankXKey = SemanticsPropertyKey<Float>("ArchiveBlankX")
internal val ArchiveBlankYKey = SemanticsPropertyKey<Float>("ArchiveBlankY")
internal val ArchiveCaseCenterXKey = SemanticsPropertyKey<Float>("ArchiveCaseCenterX")
internal val ArchiveCaseCenterYKey = SemanticsPropertyKey<Float>("ArchiveCaseCenterY")
internal val ArchiveCaseWidthKey = SemanticsPropertyKey<Float>("ArchiveCaseWidth")
internal val ArchiveCaseHeightKey = SemanticsPropertyKey<Float>("ArchiveCaseHeight")
internal val ArchiveViewportCenterXKey = SemanticsPropertyKey<Float>("ArchiveViewportCenterX")
internal val ArchiveViewportCenterYKey = SemanticsPropertyKey<Float>("ArchiveViewportCenterY")
