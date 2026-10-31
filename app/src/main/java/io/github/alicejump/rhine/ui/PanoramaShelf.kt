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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** The scene is mounted once; expansion only changes each album's pose and the surrounding chrome. */
@Composable
internal fun PanoramaShelf(
    albums: List<Album>, selectedAlbumId: String?, onSelect: (Album) -> Unit,
    panorama: Boolean, expansion: Float, normalScene: PanoramaRect, normalClip: PanoramaRect,
    position: Float, selectionFocus: Float, selectionWeights: Map<Int, Float>,
    onPanoramaChange: (Boolean) -> Unit, onExpansionGesture: (Float?) -> Unit,
    onNormalScroll: (Float) -> Unit, onPlay: () -> Unit, onOpen: () -> Unit, canPlay: Boolean,
    asset: CaseAsset?, painter: ShelfPainter, coverCache: LruCache<String, Bitmap>,
    sceneColors: ShelfColors, modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    var focusedAlbumId by rememberSaveable { mutableStateOf<String?>(null) }
    var actionSize by remember { mutableStateOf(IntSize.Zero) }
    var gesturing by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val dragVisual by animateFloatAsState(dragDistance * .12f,
        spring(dampingRatio = 1f, stiffness = 300f), label = "archive-touch")
    val focusAmount by animateFloatAsState(if (focusedAlbumId == null) 0f else 1f,
        spring(dampingRatio = 1f, stiffness = 190f), label = "panorama-lift")
    val preview = albums.isNotEmpty() && albums.all { it.tracks.isEmpty() }

    BoxWithConstraints(modifier.fillMaxSize().testTag("archive-scene")
        .semantics { this[ArchiveExpansionKey] = expansion }) {
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
            mutableStateOf(if (focusedIndex >= 0) layout.focusCamera(focusedIndex, cardHeight)
                else layout.overviewCamera(selectedIndex))
        }
        val motion = if (gesturing) snap<Float>() else tween(400, easing = FastOutSlowInEasing)
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

        LaunchedEffect(layout, focusedAlbumId, focusedIndex, actionSize.height) {
            if (focusedIndex >= 0) targetCamera = layout.focusCamera(focusedIndex, cardHeight)
            else if (focusedAlbumId != null) focusedAlbumId = null
        }
        LaunchedEffect(expansion <= .001f, selectedIndex) {
            if (expansion <= .001f) {
                focusedAlbumId = null
                targetCamera = layout.overviewCamera(selectedIndex)
            }
        }
        fun focus(album: Album) {
            focusedAlbumId = album.id
            currentOnSelect(album)
            val index = albums.indexOfFirst { it.id == album.id }
            if (index >= 0) targetCamera = layout.focusCamera(index, cardHeight)
        }
        fun selectRelative(delta: Int): Boolean {
            val album = albums.getOrNull(currentSelectedIndex + delta) ?: return false
            currentOnSelect(album)
            return true
        }
        fun overview() {
            focusedAlbumId = null
            targetCamera = layout.overviewCamera(selectedIndex)
        }

        val transitioning = expansion > .001f && expansion < .999f
        var retainedIndices by remember(albums, layout) { mutableStateOf(emptySet<Int>()) }
        LaunchedEffect(transitioning, panorama, targetCamera, selectedIndex) {
            retainedIndices = if (transitioning) retainedIndices + ShelfMotionGeometry.transitionIndices(
                albums.size, position, layout, targetCamera, selectionFocus,
            ) + layout.visibleIndices(camera) else emptySet()
        }
        val frame = ShelfMotionGeometry.frame(
            count = albums.size, normalScene = normalScene, panoramaLayout = layout,
            panoramaCamera = camera, position = position, selectionFocus = selectionFocus,
            progress = expansion, dragOffset = dragVisual, focusedIndex = focusedIndex,
            retainedIndices = retainedIndices, focusAmount = focusAmount,
            selectionWeights = selectionWeights, normalClip = normalClip,
        )
        val currentFrame by rememberUpdatedState(frame)
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
                        "${layout.columns} 列，${albums.size} 张专辑，缩放 ${(zoom * 100).roundToInt()}%"
                        else albums.getOrNull(selectedIndex)?.let { "${selectedIndex + 1} / ${albums.size}，${it.title}，${it.artist}" }
                            ?: "尚未导入音乐"
                    customActions = if (expansion > .999f) listOf(
                        CustomAccessibilityAction("总览") { overview(); true },
                        CustomAccessibilityAction("下一排") {
                            focusedAlbumId = null
                            targetCamera = layout.clamp(targetCamera.copy(y = targetCamera.y + layout.cellHeight)); true
                        },
                        CustomAccessibilityAction("上一排") {
                            focusedAlbumId = null
                            targetCamera = layout.clamp(targetCamera.copy(y = targetCamera.y - layout.cellHeight)); true
                        },
                        CustomAccessibilityAction("退出全景") { currentOnChange(false); true },
                    ) else listOf(
                        CustomAccessibilityAction("上一张专辑") { selectRelative(-1) },
                        CustomAccessibilityAction("下一张专辑") { selectRelative(1) },
                        CustomAccessibilityAction("全景浏览") { currentOnChange(true); true },
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
                            currentFrame.hitTest(global.x, global.y)?.let { pose ->
                                if (currentExpansion > .999f) focus(albums[pose.index])
                                else if (currentExpansion < .001f) currentOnSelect(albums[pose.index])
                            }
                        },
                        onDrag = { dragDistance = it }, onSwipe = { selectRelative(it) },
                        onNormalScroll = { currentOnScroll(it) },
                        onExpansion = { currentOnExpansion(it) },
                        onExpansionEnd = { currentOnChange(it) },
                        onPanoramaStart = {
                            targetCamera = currentCamera
                            gesturing = true
                            focusedAlbumId = null
                        },
                        onPanoramaTransform = { centroid, pan, zoomChange ->
                            val global = inHost(centroid)
                            targetCamera = layout.transform(targetCamera, global.x, global.y, pan.x, pan.y, zoomChange)
                        },
                        onPanoramaEnd = { gesturing = false },
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
                val bounds = item.accessibleBounds
                val clip = frame.clip
                if (item.alpha > .02f && bounds.left >= clip.left && bounds.right <= clip.right &&
                    bounds.top >= clip.top && bounds.bottom <= clip.bottom) {
                    val album = albums[item.index]
                    Box(Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                        .width(with(density) { bounds.width.toDp() }).height(with(density) { bounds.height.toDp() })
                        .testTag("archive-album-${album.id}")
                        .semantics {
                            contentDescription = "${album.title}，${album.artist}"
                            role = Role.Button
                            selected = album.id == selectedAlbumId
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
            Row(Modifier.offset { IntOffset(normalScene.left.roundToInt(), normalScene.top.roundToInt()) }
                .width(with(density) { normalScene.width.toDp() }).padding(horizontal = 14.dp, vertical = 10.dp)
                .graphicsLayer { alpha = 1f - expansion }, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("SPECIMEN / MARBLE", color = colors.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Text("${albums.size.toString().padStart(2, '0')} ALBUMS", color = colors.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
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
                        Text(if (preview) "示例馆藏 · 仅供预览" else "${albums.size} 张专辑 · ${layout.columns} 列",
                            color = colors.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = ::overview, enabled = expansion > .999f,
                        modifier = Modifier.semantics { contentDescription = "总览" }) { Text("总览", fontSize = 12.sp) }
                    TextButton(onClick = { onPanoramaChange(false) }, modifier = Modifier.semantics { contentDescription = "退出全景" },
                        colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface)) { Text("退出全景", fontSize = 12.sp) }
                }
                Text("双指缩放 · 拖动浏览 · 轻点聚焦",
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).graphicsLayer { alpha = expansion },
                    color = colors.onSurfaceVariant, fontSize = 10.sp)
                if (focusedIndex >= 0 && expansion > .9f) {
                    val album = albums[focusedIndex]
                    val placement = frame.items.firstOrNull { it.index == focusedIndex }?.asPanoramaPlacement()
                        ?: layout.placement(focusedIndex, camera)
                    val cardWidth = min(272f * density.density, (width - 24f * density.density).coerceAtLeast(1f))
                    val bounds = layout.cardBounds(placement, cardWidth, cardHeight)
                    Surface(
                        modifier = Modifier.offset { IntOffset(bounds.left.roundToInt(), bounds.top.roundToInt()) }
                            .width(with(density) { cardWidth.toDp() })
                            .heightIn(max = with(density) { maxCardHeight.toDp() })
                            .graphicsLayer { alpha = ((expansion - .9f) * 10f).coerceIn(0f, 1f) * focusAmount }
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
