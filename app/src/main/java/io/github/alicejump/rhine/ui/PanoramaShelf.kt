package io.github.alicejump.rhine.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alicejump.rhine.data.Album
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private enum class ShelfCameraIntent { Overview, Selection, Free }

private val CameraSaver = listSaver<PanoramaCamera, Float>(
    save = { listOf(it.x, it.y, it.zoom) },
    restore = { PanoramaCamera(it[0], it[1], it[2]) },
)

/** The scene is mounted once; expansion only changes each album's pose and the surrounding chrome. */
@Composable
internal fun PanoramaShelf(
    albums: List<Album>, selectedAlbumId: String?, browseAnchorId: String?, onSelect: (Album) -> Unit,
    onClearSelection: () -> Unit, onBrowseAnchor: (Album) -> Unit,
    panorama: Boolean, expansion: Float, normalScene: PanoramaRect, normalClip: PanoramaRect,
    position: Float, selectionFocus: Float, selectionWeights: Map<Int, Float>,
    onPanoramaChange: (Boolean) -> Unit, onExpansionGesture: (Float?) -> Unit,
    gestureVersion: () -> Int,
    onNormalScroll: (Float) -> Unit, onPlay: () -> Unit, onOpen: () -> Unit, canPlay: Boolean,
    asset: CaseAsset?, painter: ShelfPainter, coverCache: LruCache<String, Bitmap>,
    sceneColors: ShelfColors, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    var showActions by rememberSaveable { mutableStateOf(false) }
    var cameraIntent by rememberSaveable { mutableStateOf(ShelfCameraIntent.Overview) }
    var actionSize by remember { mutableStateOf(IntSize.Zero) }
    var gesturing by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val dragVisual by animateFloatAsState(dragDistance * .12f,
        spring(dampingRatio = 1f, stiffness = 300f), label = "archive-touch")
    val actionOpacity by animateFloatAsState(if (showActions && selectedAlbumId != null && !gesturing) 1f else 0f,
        tween(180), label = "panorama-card")
    val preview = albums.isNotEmpty() && albums.all { it.tracks.isEmpty() }

    BoxWithConstraints(modifier.fillMaxSize().clipToBounds()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val projection = asset?.projection ?: ShelfProjection()
        val layout = remember(width, height, density.density, albums.size, projection) {
            PanoramaLayout.create(width, height, density.density, albums.size, projection)
        }
        val selectedIndex = albums.indexOfFirst { it.id == selectedAlbumId }
        val browseIndex = albums.indexOfFirst { it.id == browseAnchorId }.coerceAtLeast(0)
        val anchorIndex = if (selectedIndex >= 0) selectedIndex else browseIndex
        val compactCard = maxHeight < 400.dp
        val maxCardHeight = min(164f * density.density, layout.viewport.height * .55f)
        val cardHeight = (actionSize.height.takeIf { it > 0 }?.toFloat()
            ?: (if (compactCard) 96f else 128f) * density.density).coerceAtMost(maxCardHeight)
        var targetCamera by rememberSaveable(stateSaver = CameraSaver) {
            mutableStateOf(layout.overviewCamera(anchorIndex))
        }
        val motion = if (gesturing || expansion < .001f) snap<Float>() else tween(400, easing = FastOutSlowInEasing)
        val cameraX by animateFloatAsState(targetCamera.x, motion, label = "panorama-x")
        val cameraY by animateFloatAsState(targetCamera.y, motion, label = "panorama-y")
        val zoom by animateFloatAsState(targetCamera.zoom, motion, label = "panorama-zoom")
        val camera = PanoramaCamera(cameraX, cameraY, zoom)
        val currentCamera by rememberUpdatedState(camera)
        val currentExpansion by rememberUpdatedState(expansion)
        val currentOnSelect by rememberUpdatedState(onSelect)
        val currentOnChange by rememberUpdatedState(onPanoramaChange)
        val currentOnExpansion by rememberUpdatedState(onExpansionGesture)
        val currentOnScroll by rememberUpdatedState(onNormalScroll)
        val currentSelectedIndex by rememberUpdatedState(selectedIndex)
        val currentAnchorIndex by rememberUpdatedState(anchorIndex)
        val currentSelectionWeights by rememberUpdatedState(selectionWeights)
        val currentOnClear by rememberUpdatedState(onClearSelection)
        val currentOnBrowse by rememberUpdatedState(onBrowseAnchor)
        val currentGestureVersion by rememberUpdatedState(gestureVersion)
        val currentNormalScene by rememberUpdatedState(normalScene)

        fun selectionCamera(index: Int): PanoramaCamera {
            // A tap reveals the raised case and card. The user can then deliberately zoom
            // beyond this size to return to the close shelf; a programmatic focus cannot exit.
            val closeZoom = ShelfMotionGeometry.normalSpriteSize(normalScene) / layout.spriteSize * .78f
            return layout.focusCamera(index, cardHeight,
                zoomOverride = closeZoom.coerceIn(PanoramaLayout.MIN_ZOOM, layout.maxZoom))
        }

        LaunchedEffect(layout, selectedAlbumId, actionSize.height) {
            if (selectedIndex < 0) showActions = false
            targetCamera = when {
                cameraIntent == ShelfCameraIntent.Selection && selectedIndex >= 0 && panorama && !gesturing ->
                    selectionCamera(selectedIndex)
                else -> layout.clamp(targetCamera)
            }
        }
        LaunchedEffect(expansion <= .001f, panorama, selectedAlbumId, browseIndex, layout) {
            if (expansion <= .001f && !panorama && !gesturing) {
                targetCamera = if (selectedIndex >= 0) selectionCamera(selectedIndex)
                    else layout.overviewCamera(browseIndex)
            }
        }
        // Entry chooses a default composition once. Ordinary selection, cancelling a card,
        // leaving panorama, and free camera gestures never clear the shared selection.
        var wasPanorama by rememberSaveable { mutableStateOf(panorama) }
        LaunchedEffect(panorama) {
            if (panorama && !wasPanorama) {
                showActions = selectedIndex >= 0
                cameraIntent = if (selectedIndex >= 0) ShelfCameraIntent.Selection else ShelfCameraIntent.Overview
                targetCamera = if (selectedIndex >= 0) selectionCamera(selectedIndex)
                    else layout.overviewCamera(browseIndex)
            }
            wasPanorama = panorama
        }
        fun focus(album: Album) {
            currentOnSelect(album)
            showActions = true
            cameraIntent = ShelfCameraIntent.Selection
            val index = albums.indexOfFirst { it.id == album.id }
            if (index >= 0) targetCamera = selectionCamera(index)
        }
        fun clearSelection() {
            showActions = false
            cameraIntent = ShelfCameraIntent.Free
            targetCamera = currentCamera
            if (currentExpansion > .999f) {
                layout.nearestIndex(currentCamera)?.let { albums.getOrNull(it)?.let(currentOnBrowse) }
            }
            currentOnClear()
        }
        fun selectRelative(delta: Int): Boolean {
            val album = albums.getOrNull(currentAnchorIndex + delta) ?: return false
            if (currentExpansion > .999f) focus(album) else currentOnSelect(album)
            return true
        }
        fun overview() {
            showActions = false
            cameraIntent = ShelfCameraIntent.Overview
            targetCamera = layout.overviewCamera(anchorIndex)
        }
        fun centerSelection() {
            val index = currentSelectedIndex
            if (index < 0) return
            showActions = false
            cameraIntent = ShelfCameraIntent.Free
            val current = currentCamera
            val body = ShelfMotionGeometry.panoramaPose(index, layout, current, index.toFloat(),
                mapOf(index to 1f)).caseBounds
            targetCamera = layout.clamp(current.copy(
                x = current.x + (body.centerX - layout.viewport.centerX) / current.zoom,
                y = current.y + (body.centerY - layout.viewport.centerY) / current.zoom,
            ))
        }
        fun changeMode(expanded: Boolean) {
            if (!expanded && currentSelectedIndex < 0) {
                layout.nearestIndex(currentCamera)?.let { albums.getOrNull(it)?.let(currentOnBrowse) }
            }
            currentOnChange(expanded)
        }

        val transitioning = expansion > .001f && expansion < .999f
        var retainedIndices by remember(albums, layout) { mutableStateOf(emptySet<Int>()) }
        LaunchedEffect(transitioning, panorama, targetCamera, selectedIndex) {
            retainedIndices = if (transitioning) retainedIndices + ShelfMotionGeometry.transitionIndices(
                albums.size, position, layout, targetCamera, selectionFocus, selectionWeights,
            ) + layout.visibleIndices(camera) else emptySet()
        }
        val frame = ShelfMotionGeometry.frame(
            count = albums.size, normalScene = normalScene, panoramaLayout = layout,
            panoramaCamera = camera, position = position, selectionFocus = selectionFocus,
            progress = expansion, dragOffset = dragVisual, focusedIndex = selectedIndex,
            retainedIndices = retainedIndices,
            selectionWeights = selectionWeights, normalClip = normalClip,
        )
        val currentFrame by rememberUpdatedState(frame)
        val backgroundPoint = frame.backgroundPoint()
        Box(Modifier.fillMaxSize().testTag("archive-scene").semantics {
            this[ArchiveExpansionKey] = expansion
            this[ArchiveSelectedAlbumKey] = selectedAlbumId.orEmpty()
            this[ArchiveBrowseAnchorKey] = browseAnchorId.orEmpty()
            this[ArchiveCameraZoomKey] = camera.zoom
            this[ArchiveCameraCenterXKey] = camera.x
            this[ArchiveCameraCenterYKey] = camera.y
            this[ArchiveVisibleWorldWidthKey] = layout.viewport.width / camera.zoom
            this[ArchiveVisibleWorldHeightKey] = layout.viewport.height / camera.zoom
            this[ArchiveViewportCenterXKey] = layout.viewport.centerX
            this[ArchiveViewportCenterYKey] = layout.viewport.centerY
            backgroundPoint?.let {
                this[ArchiveBlankXKey] = it.x
                this[ArchiveBlankYKey] = it.y
            }
        })
        // Input occupies only the animated scene. Ordinary buttons and portrait scrolling
        // remain reachable outside it; painter and pointer positions share host coordinates.
        val inputLeft = maxOf(normalScene.left, normalClip.left)
        val inputTop = maxOf(normalScene.top, normalClip.top)
        val inputRight = minOf(normalScene.right, normalClip.right).coerceAtLeast(inputLeft)
        val inputBottom = minOf(normalScene.bottom, normalClip.bottom).coerceAtLeast(inputTop)
        val scene = PanoramaRect(
            (inputLeft * (1f - expansion)).coerceIn(0f, width),
            (inputTop * (1f - expansion)).coerceIn(0f, height),
            (inputRight + (width - inputRight) * expansion).coerceIn(0f, width),
            (inputBottom + (height - inputBottom) * expansion).coerceIn(0f, height),
        )
        val currentScene by rememberUpdatedState(scene)
        val paths = frame.items.asSequence().filter { expansion > .001f || it.alpha > .02f }
            .mapNotNull { albums[it.index].artworkPath }.distinct().sorted().toList()
        val thumbnailSize = if (expansion < .001f) 512 else when {
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
        val hitStore = remember { ShelfHitStore() }
        val dragThreshold = 40f * density.density
        Canvas(
            Modifier.offset { IntOffset(scene.left.roundToInt(), scene.top.roundToInt()) }
                .width(with(density) { scene.width.coerceAtLeast(1f).toDp() })
                .height(with(density) { scene.height.coerceAtLeast(1f).toDp() })
                .testTag(if (expansion > .001f) "panorama-canvas" else "archive-shelf")
                .semantics {
                    contentDescription = if (expansion > .999f) "唱片全景，双指缩放，拖动浏览，轻点聚焦"
                        else "专辑档案架，左右滑动切换专辑，双指捏合展开"
                    stateDescription = if (expansion > .999f)
                        "${layout.columns} 条架道，${albums.size} 张专辑，缩放 ${(zoom * 100).roundToInt()}%"
                        else albums.getOrNull(anchorIndex)?.let {
                            "${anchorIndex + 1} / ${albums.size}，${it.title}，${if (selectedIndex >= 0) "已选中" else "未选中"}"
                        }
                            ?: "尚未导入音乐"
                    customActions = if (expansion > .999f) listOf(
                        CustomAccessibilityAction("总览") { overview(); true },
                        CustomAccessibilityAction("回到选中") { centerSelection(); selectedIndex >= 0 },
                        CustomAccessibilityAction("下一排") {
                            showActions = false; cameraIntent = ShelfCameraIntent.Free
                            targetCamera = layout.clamp(targetCamera.copy(y = targetCamera.y + layout.cellHeight))
                            layout.nearestIndex(targetCamera)?.let { albums.getOrNull(it)?.let(currentOnBrowse) }
                            true
                        },
                        CustomAccessibilityAction("上一排") {
                            showActions = false; cameraIntent = ShelfCameraIntent.Free
                            targetCamera = layout.clamp(targetCamera.copy(y = targetCamera.y - layout.cellHeight))
                            layout.nearestIndex(targetCamera)?.let { albums.getOrNull(it)?.let(currentOnBrowse) }
                            true
                        },
                        CustomAccessibilityAction("取消选择") { clearSelection(); true },
                        CustomAccessibilityAction("退出全景") { changeMode(false); true },
                    ) else listOf(
                        CustomAccessibilityAction("上一张专辑") { selectRelative(-1) },
                        CustomAccessibilityAction("下一张专辑") { selectRelative(1) },
                        CustomAccessibilityAction("全景浏览") { changeMode(true); true },
                        CustomAccessibilityAction("取消选择") { clearSelection(); true },
                    )
                }
                .pointerInput(layout, albums) {
                    fun inHost(point: Offset) = point + Offset(currentScene.left, currentScene.top)
                    detectShelfNavigation(
                        acceptStart = { point ->
                            val global = inHost(point)
                            val clip = currentFrame.clip
                            global.x in clip.left..clip.right && global.y in clip.top..clip.bottom
                        },
                        expansion = { currentExpansion }, dragThreshold = dragThreshold,
                        onTap = { point ->
                            val global = inHost(point)
                            if (currentExpansion > .999f || currentExpansion < .001f) {
                                val pose = currentFrame.hitTest(global.x, global.y)
                                if (pose == null) clearSelection()
                                else if (currentExpansion > .999f) focus(albums[pose.index])
                                else currentOnSelect(albums[pose.index])
                            }
                        },
                        onDrag = { dragDistance = it }, onSwipe = { selectRelative(it) },
                        onNormalScroll = { currentOnScroll(it) },
                        onExpansion = { currentOnExpansion(it) },
                        onExpansionEnd = { changeMode(it) },
                        onGestureCancel = { currentOnExpansion(null) },
                        onPanoramaStart = {
                            targetCamera = currentCamera
                            gesturing = true
                            cameraIntent = ShelfCameraIntent.Free
                            showActions = false
                        },
                        onPanoramaTransform = { centroid, pan, zoomChange ->
                            val global = inHost(centroid)
                            targetCamera = layout.transform(targetCamera, global.x, global.y, pan.x, pan.y, zoomChange)
                        },
                        onPanoramaEnd = {
                            gesturing = false
                            layout.nearestIndex(targetCamera)?.let { albums.getOrNull(it)?.let(currentOnBrowse) }
                        },
                        gestureVersion = { currentGestureVersion() },
                        panoramaExitGate = exit@{ centroid, pan, zoomChange ->
                            val index = currentSelectedIndex
                            if (index < 0 || zoomChange <= 1f) return@exit null
                            val weights = currentSelectionWeights
                            val normal = ShelfMotionGeometry.normalPose(index, currentNormalScene,
                                density.density, index.toFloat(), index.toFloat(), selectionWeights = weights)
                            val full = ShelfMotionGeometry.panoramaPose(index, layout, targetCamera,
                                index.toFloat(), weights)
                            if (full.size <= 0f || normal.size <= 0f) return@exit null
                            val required = (normal.size * .90f / full.size).coerceAtLeast(1f)
                            val part = (ln(required) / ln(zoomChange)).coerceIn(0f, 1f)
                            val point = inHost(centroid)
                            val candidate = layout.transform(targetCamera, point.x, point.y,
                                pan.x * part, pan.y * part, min(required, zoomChange))
                            val candidatePose = ShelfMotionGeometry.panoramaPose(index, layout, candidate,
                                index.toFloat(), weights)
                            if (candidatePose.size + .01f < normal.size * .90f) return@exit null
                            val body = candidatePose.caseBounds
                            val viewport = layout.viewport
                            if (abs(body.centerX - viewport.centerX) > viewport.width * .09f ||
                                abs(body.centerY - viewport.centerY) > viewport.height * .09f) return@exit null
                            PanoramaExitGate(albums[index].id, required)
                        },
                    )
                },
        ) {
            drawIntoCanvas { canvas ->
                val native = canvas.nativeCanvas
                val saved = native.save()
                native.translate(-scene.left, -scene.top)
                painter.drawFrame(native, width, height, density.density, albums, frame, asset, covers, hitStore, sceneColors)
                native.restoreToCount(saved)
            }
        }

        // These nodes keep the same album key during normal selection and both transitions.
        frame.items.forEach { item ->
            key(albums[item.index].id) {
                val bounds = frame.visibleBounds(item.index)
                val clip = frame.clip
                if (bounds != null && item.alpha > .02f && bounds.left >= clip.left && bounds.right <= clip.right &&
                    bounds.top >= clip.top && bounds.bottom <= clip.bottom) {
                    val album = albums[item.index]
                    Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                        .width(with(density) { bounds.width.toDp() }).height(with(density) { bounds.height.toDp() })
                        .testTag("archive-album-${album.id}")
                        .semantics {
                            contentDescription = "${album.title}，${album.artist}"
                            role = Role.Button
                            selected = album.id == selectedAlbumId
                            this[ArchiveAlbumFocusKey] = item.focus
                            this[ArchiveLaneKey] = layout.laneOf(item.index)
                            this[ArchiveSlotKey] = layout.slotOf(item.index)
                            this[ArchiveCaseCenterXKey] = item.caseBounds.centerX
                            this[ArchiveCaseCenterYKey] = item.caseBounds.centerY
                            this[ArchiveCaseWidthKey] = item.caseBounds.width
                            this[ArchiveCaseHeightKey] = item.caseBounds.height
                            onClick(label = if (expansion > .999f) "聚焦专辑" else "选择专辑") {
                                if (expansion > .999f) focus(album)
                                else if (expansion < .001f) currentOnSelect(album)
                                true
                            }
                        }) {
                        if (expansion > .999f) Box(Modifier.fillMaxSize().testTag("panorama-album-${album.id}"))
                    }
                }
            }
        }

        if (expansion < .999f) {
            Box(Modifier.offset { IntOffset(normalClip.left.roundToInt(), normalClip.top.roundToInt()) }
                .width(with(density) { normalClip.width.toDp() }).height(with(density) { normalClip.height.toDp() })
                .clipToBounds()) {
                Row(Modifier.offset { IntOffset((normalScene.left - normalClip.left).roundToInt(),
                    (normalScene.top - normalClip.top).roundToInt()) }
                    .width(with(density) { normalScene.width.toDp() }).padding(horizontal = 14.dp, vertical = 10.dp)
                    .graphicsLayer { alpha = 1f - expansion }, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("SPECIMEN / MARBLE", color = colors.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("${albums.size.toString().padStart(2, '0')} ALBUMS", color = colors.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        if (expansion > .001f) {
            Box(Modifier.fillMaxSize().testTag("panorama-mode")) {
                Row(Modifier.fillMaxWidth().height(with(density) { layout.viewport.top.toDp() })
                    .graphicsLayer { alpha = expansion; translationY = -size.height * (1f - expansion) }
                    .background(colors.surface.copy(alpha = .96f)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("ARCHIVE / PANORAMA", fontFamily = FontFamily.Monospace, fontSize = 10.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, color = colors.onSurface)
                        Text(if (preview) "示例馆藏 · 仅供预览" else "${albums.size} 张专辑 · ${layout.columns} 条架道",
                            color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = ::overview, enabled = expansion > .999f,
                        modifier = Modifier.semantics { contentDescription = "总览" }) { Text("总览", fontSize = 12.sp) }
                    TextButton(onClick = ::centerSelection, enabled = selectedIndex >= 0 && expansion > .999f,
                        modifier = Modifier.semantics { contentDescription = "回到选中" }) { Text("定位", fontSize = 12.sp) }
                    TextButton(onClick = ::clearSelection, enabled = selectedIndex >= 0 && expansion > .999f,
                        modifier = Modifier.semantics { contentDescription = "取消选择" }) { Text("取消", fontSize = 12.sp) }
                    TextButton(onClick = { changeMode(false) }, modifier = Modifier.semantics { contentDescription = "退出全景" },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("退出全景", fontSize = 12.sp) }
                }
                Text("捏合浏览 · 选中居中放大可收回",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).graphicsLayer { alpha = expansion },
                    color = colors.onSurfaceVariant, fontSize = 10.sp)
                if (selectedIndex >= 0 && actionOpacity > .001f && expansion > .9f) {
                    val album = albums[selectedIndex]
                    val placement = frame.items.firstOrNull { it.index == selectedIndex }?.asPanoramaPlacement()
                        ?: layout.placement(selectedIndex, camera)
                    val cardWidth = min(272f * density.density, (width - 24f * density.density).coerceAtLeast(1f))
                    val bounds = layout.cardBounds(placement, cardWidth, cardHeight)
                    Surface(
                        modifier = Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                            .width(with(density) { cardWidth.toDp() })
                            .heightIn(max = with(density) { maxCardHeight.toDp() })
                            .graphicsLayer { alpha = ((expansion - .9f) * 10f).coerceIn(0f, 1f) * actionOpacity }
                            .onSizeChanged { actionSize = it }.testTag("panorama-actions"),
                        shape = RoundedCornerShape(10.dp), color = colors.surface,
                        border = BorderStroke(1.dp, colors.primary.copy(alpha = .45f)), shadowElevation = 8.dp,
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(album.title, fontSize = if (compactCard) 13.sp else 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!compactCard) Text(album.artist, fontSize = 10.sp, color = colors.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = onPlay, enabled = canPlay && album.tracks.isNotEmpty() && expansion > .999f,
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics { contentDescription = "播放选中专辑" }) {
                                    Text("播放", fontSize = 12.sp)
                                }
                                OutlinedButton(onClick = onOpen, enabled = expansion > .999f,
                                    modifier = Modifier.weight(1f).heightIn(min = 40.dp).semantics { contentDescription = "全景专辑详情" }) {
                                    Text("详情", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
