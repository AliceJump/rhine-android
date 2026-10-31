package io.github.alicejump.rhine.ui

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.snap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alicejump.rhine.data.Album
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** A single persistent collection of MARBLE cases, moving between close and expanded layouts. */
@Composable
internal fun ArchiveShelf(
    albums: List<Album>,
    selectedAlbumId: String?,
    onSelect: (Album) -> Unit,
    modifier: Modifier = Modifier,
    panorama: Boolean = false,
    expansion: Float = 0f,
    normalScene: PanoramaRect,
    normalClip: PanoramaRect,
    onPanoramaChange: (Boolean) -> Unit = {},
    onExpansionGesture: (Float?) -> Unit = {},
    onNormalScroll: (Float) -> Unit = {},
    onPlay: () -> Unit = {},
    onOpen: () -> Unit = {},
    canPlay: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val colors = MaterialTheme.colorScheme
    val sceneColors = ShelfColors(
        background = colors.surface.toArgb(),
        grid = colors.outline.copy(alpha = .25f).toArgb(),
        border = colors.outline.copy(alpha = .38f).toArgb(),
        registration = colors.onSurfaceVariant.copy(alpha = .65f).toArgb(),
        accent = colors.primary.toArgb(),
    )
    val selectedIndex = albums.indexOfFirst { it.id == selectedAlbumId }.coerceAtLeast(0)
    // The selected case lifts before the slower row follows it, as in the upstream shelf.
    // In full panorama the close pose is invisible, so prepare its new anchor immediately.
    val position by animateFloatAsState(selectedIndex.toFloat(),
        if (expansion >= .999f) snap() else spring(dampingRatio = 1f, stiffness = 90f),
        label = "archive-row")
    val indicesById = remember(albums) { albums.mapIndexed { index, album -> album.id to index }.toMap() }
    val focusStates = remember(albums) {
        mutableStateMapOf<String, Animatable<Float, AnimationVector1D>>().apply {
            albums.getOrNull(selectedIndex)?.let { put(it.id, Animatable(1f)) }
        }
    }
    LaunchedEffect(selectedAlbumId, albums) {
        val selectedId = albums.getOrNull(selectedIndex)?.id
        focusStates.keys.filter { it != selectedId && (focusStates[it]?.value ?: 0f) < .001f }
            .forEach(focusStates::remove)
        if (selectedId != null) focusStates.getOrPut(selectedId) { Animatable(0f) }
        coroutineScope {
            focusStates.toMap().forEach { (id, motion) ->
                launch {
                    motion.animateTo(if (id == selectedId) 1f else 0f,
                        spring(dampingRatio = 1f, stiffness = 190f))
                    if (id != selectedId) focusStates.remove(id)
                }
            }
        }
    }
    val selectionWeights = focusStates.mapNotNull { (id, motion) ->
        indicesById[id]?.let { it to motion.value }
    }.toMap()
    val painter = remember { ShelfPainter() }
    val coverCache = remember {
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }
    val asset by produceState<CaseAsset?>(null, context) {
        value = withContext(Dispatchers.IO) { readCaseAsset(context) }
    }
    PanoramaShelf(
        albums = albums, selectedAlbumId = selectedAlbumId, onSelect = onSelect,
        panorama = panorama, expansion = expansion, normalScene = normalScene, normalClip = normalClip,
        position = position, selectionFocus = selectedIndex.toFloat(), selectionWeights = selectionWeights,
        onPanoramaChange = onPanoramaChange, onExpansionGesture = onExpansionGesture,
        onNormalScroll = onNormalScroll, onPlay = onPlay, onOpen = onOpen, canPlay = canPlay,
        asset = asset, painter = painter, coverCache = coverCache,
        sceneColors = sceneColors, modifier = modifier,
    )
}

@Composable
internal fun ArchiveShelfControls(count: Int, selectedIndex: Int, onSelectRelative: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        TextButton(onClick = { onSelectRelative(-1) }, enabled = selectedIndex > 0,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
            modifier = Modifier.semantics { contentDescription = "上一张专辑" }) { Text("←", fontSize = 23.sp) }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${if (count == 0) "00" else (selectedIndex + 1).toString().padStart(2, '0')} / ${count.toString().padStart(2, '0')}",
                fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = colors.onSurface)
            Text(if (count == 0) "导入音乐后显示专辑" else "滑动浏览 · 轻点选择",
                fontSize = 10.sp, color = colors.onSurfaceVariant)
        }
        TextButton(onClick = { onSelectRelative(1) }, enabled = selectedIndex < count - 1,
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
            modifier = Modifier.semantics { contentDescription = "下一张专辑" }) { Text("→", fontSize = 23.sp) }
    }
}
