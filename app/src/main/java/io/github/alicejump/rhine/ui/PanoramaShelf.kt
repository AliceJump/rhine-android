package io.github.alicejump.rhine.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alicejump.rhine.data.Album
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

@Composable
internal fun PanoramaShelf(
    albums: List<Album>,
    selectedAlbumId: String?,
    onSelect: (Album) -> Unit,
    onExit: () -> Unit,
    onPlay: () -> Unit,
    onOpen: () -> Unit,
    canPlay: Boolean,
    asset: CaseAsset?,
    painter: ShelfPainter,
    coverCache: LruCache<String, Bitmap>,
    sceneColors: ShelfColors,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    var focusedAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var entered by rememberSaveable { mutableStateOf(false) }
    var actionSize by remember { mutableStateOf(IntSize.Zero) }
    var gesturing by remember { mutableStateOf(false) }
    val preview = albums.isNotEmpty() && albums.all { it.tracks.isEmpty() }

    BoxWithConstraints(modifier.fillMaxSize().testTag("panorama-mode")) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val layout = remember(width, height, density.density, albums.size) {
            PanoramaLayout.create(width, height, density.density, albums.size)
        }
        val selectedIndex = albums.indexOfFirst { it.id == selectedAlbumId }.coerceAtLeast(0)
        val focusedIndex = albums.indexOfFirst { it.id == focusedAlbumId }
        val compactCard = maxHeight < 400.dp
        val maxCardHeight = min(164f * density.density, layout.viewport.height * .55f)
        val cardHeight = (actionSize.height.takeIf { it > 0 }?.toFloat()
            ?: (if (compactCard) 96f else 128f) * density.density).coerceAtMost(maxCardHeight)
        var targetCamera by remember(layout) {
            val initial = when {
                focusedIndex >= 0 -> layout.focusCamera(focusedIndex, cardHeight)
                !entered && albums.isNotEmpty() -> layout.center(selectedIndex).let {
                    layout.clamp(PanoramaCamera(it.x, it.y, 1.35f))
                }
                else -> layout.overviewCamera(selectedIndex)
            }
            mutableStateOf(initial)
        }
        val motion = if (gesturing) snap<Float>() else tween(durationMillis = 400, easing = FastOutSlowInEasing)
        val cameraX by animateFloatAsState(targetCamera.x, motion, label = "panorama-x")
        val cameraY by animateFloatAsState(targetCamera.y, motion, label = "panorama-y")
        val zoom by animateFloatAsState(targetCamera.zoom, motion, label = "panorama-zoom")
        val camera = PanoramaCamera(cameraX, cameraY, zoom)
        val currentCamera by rememberUpdatedState(camera)
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentOnExit by rememberUpdatedState(onExit)

        LaunchedEffect(Unit) {
            if (!entered) {
                // Let the closer first frame paint before pulling back into the whole room.
                withFrameNanos { }
                if (focusedAlbumId == null && !gesturing) targetCamera = layout.overviewCamera(selectedIndex)
                entered = true
            }
        }

        // Saved identity survives rotation; a new geometry supplies its new coordinates.
        LaunchedEffect(layout, focusedAlbumId, focusedIndex, actionSize.height) {
            if (focusedIndex >= 0) targetCamera = layout.focusCamera(focusedIndex, cardHeight)
            else if (focusedAlbumId != null) focusedAlbumId = null
        }

        fun focus(album: Album) {
            focusedAlbumId = album.id
            currentOnSelect(album)
            val index = albums.indexOfFirst { it.id == album.id }
            if (index >= 0) targetCamera = layout.focusCamera(index, cardHeight)
        }
        fun overview() {
            focusedAlbumId = null
            targetCamera = layout.overviewCamera(selectedIndex)
        }

        val visibleIndices = remember(layout, camera) { layout.visibleIndices(camera) }
        val placements = remember(layout, camera, visibleIndices, focusedIndex) {
            visibleIndices.map { layout.placement(it, camera, if (it == focusedIndex) 7f * density.density else 0f) }
                .sortedBy { if (it.index == focusedIndex) 1 else 0 }
        }
        val currentPlacements by rememberUpdatedState(placements)
        val paths = remember(albums, visibleIndices) {
            visibleIndices.mapNotNull { albums[it].artworkPath }.distinct()
        }
        // More visible covers need smaller thumbnails. Neither camera movement nor a large
        // library should retain a full-size decoded bitmap for every album.
        val thumbnailSize = when {
            paths.size <= 24 -> 256
            paths.size <= 56 -> 192
            else -> 128
        }
        val covers by produceState<Map<String, Bitmap>>(emptyMap(), paths, thumbnailSize, context) {
            value = withContext(Dispatchers.IO) {
                buildMap {
                    for (path in paths) {
                        ensureActive()
                        readCover(context, path, coverCache, thumbnailSize)?.let { put(path, it) }
                    }
                }
            }
        }

        Canvas(
            Modifier.fillMaxSize().testTag("panorama-canvas")
                .semantics {
                    contentDescription = "唱片全景，双指缩放，拖动浏览，轻点聚焦"
                    stateDescription = "${layout.columns} 列，${albums.size} 张专辑，缩放 ${(zoom * 100).roundToInt()}%"
                    customActions = listOf(
                        CustomAccessibilityAction("总览") { overview(); true },
                        CustomAccessibilityAction("下一排") {
                            focusedAlbumId = null
                            targetCamera = layout.clamp(targetCamera.copy(y = targetCamera.y + layout.cellHeight))
                            true
                        },
                        CustomAccessibilityAction("上一排") {
                            focusedAlbumId = null
                            targetCamera = layout.clamp(targetCamera.copy(y = targetCamera.y - layout.cellHeight))
                            true
                        },
                        CustomAccessibilityAction("退出全景") { currentOnExit(); true },
                    )
                }
                .pointerInput(layout, albums) {
                    detectPanoramaNavigation(
                        onTap = { point ->
                            currentPlacements.asReversed().firstOrNull { it.contains(point.x, point.y) }
                                ?.let { focus(albums[it.index]) }
                        },
                        onStart = {
                            targetCamera = currentCamera
                            gesturing = true
                            focusedAlbumId = null
                        },
                        onTransform = { centroid, pan, zoomChange ->
                            targetCamera = layout.transform(targetCamera, centroid.x, centroid.y, pan.x, pan.y, zoomChange)
                        },
                        onEnd = { gesturing = false },
                    )
                },
        ) {
            drawIntoCanvas { canvas ->
                painter.drawPanorama(canvas.nativeCanvas, layout, albums, placements, selectedAlbumId, asset, covers, sceneColors)
            }
        }

        // Accessibility targets follow the painted faces. They deliberately have no pointer
        // handler, so real touches still reach the same tap / drag / pinch recognizer.
        placements.forEach { item ->
            val bounds = item.accessibleBounds
            if (bounds.left >= 0f && bounds.right <= width && bounds.top >= layout.viewport.top && bounds.bottom <= layout.viewport.bottom) {
                val album = albums[item.index]
                Box(
                    Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                        .width(with(density) { bounds.width.toDp() })
                        .height(with(density) { bounds.height.toDp() })
                        .testTag("panorama-album-${album.id}")
                        .semantics {
                            contentDescription = "${album.title}，${album.artist}"
                            role = Role.Button
                            selected = album.id == selectedAlbumId
                            onClick(label = "聚焦专辑") { focus(album); true }
                        },
                )
            }
        }

        Row(
            Modifier.fillMaxWidth().height(with(density) { layout.viewport.top.toDp() })
                .background(colors.surface.copy(alpha = .96f)).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("ARCHIVE / PANORAMA", fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = colors.onSurface)
                Text(if (preview) "示例馆藏 · 仅供预览" else "${albums.size} 张专辑 · ${layout.columns} 列",
                    color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton(onClick = ::overview, modifier = Modifier.semantics { contentDescription = "总览" }) { Text("总览", fontSize = 12.sp) }
            TextButton(onClick = onExit, modifier = Modifier.semantics { contentDescription = "退出全景" },
                colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("退出全景", fontSize = 12.sp) }
        }
        Text(
            "双指缩放 · 拖动浏览 · 轻点聚焦",
            Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
            color = colors.onSurfaceVariant, fontSize = 10.sp,
        )

        if (focusedIndex >= 0) {
            val album = albums[focusedIndex]
            val placement = layout.placement(focusedIndex, camera, 7f * density.density)
            val cardWidth = min(272f * density.density, (width - 24f * density.density).coerceAtLeast(1f))
            val bounds = layout.cardBounds(placement, cardWidth, cardHeight)
            Surface(
                modifier = Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                    .width(with(density) { cardWidth.toDp() })
                    .heightIn(max = with(density) { maxCardHeight.toDp() })
                    .onSizeChanged { actionSize = it }
                    .testTag("panorama-actions"),
                shape = RoundedCornerShape(10.dp),
                color = colors.surface,
                border = BorderStroke(1.dp, colors.primary.copy(alpha = .45f)),
                shadowElevation = 8.dp,
            ) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(album.title, fontSize = if (compactCard) 13.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!compactCard) Text(album.artist, fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onPlay, enabled = canPlay && album.tracks.isNotEmpty(),
                            modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics { contentDescription = "播放选中专辑" }) {
                            Text("播放", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onOpen,
                            modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics { contentDescription = "全景专辑详情" }) {
                            Text("详情", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
