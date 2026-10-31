@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.alicejump.rhine.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alicejump.rhine.R
import io.github.alicejump.rhine.RhineViewModel
import io.github.alicejump.rhine.data.Album
import io.github.alicejump.rhine.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private val ArchiveFont = FontFamily(
    Font(R.font.oxanium_regular),
    Font(R.font.oxanium_semibold, FontWeight.SemiBold),
)
private val DemoAlbums = listOf(
    "AFTER THE RAIN" to "FIELD RECORDINGS",
    "QUIET ORBIT" to "LISTENING ROOM",
    "PALE HORIZON" to "ARCHIVE SERIES",
    "SOFT MACHINES" to "SIGNAL STUDIES",
    "NIGHT GARDEN" to "BOTANICAL AUDIO",
    "GLASS MEMORY" to "COLLECTED SOUNDS",
).mapIndexed { index, (title, artist) ->
    Album(id = "demo-$index", title = title, artist = artist, tracks = emptyList(), artworkPath = null)
}

private enum class View(val label: String, val code: String) {
    Archive("档案", "01"), Tracks("曲目", "02"), Playing("播放", "03"), Style("外观", "04")
}

@Composable
fun RhineApp(model: RhineViewModel, onChooseFolder: () -> Unit) {
    var view by remember { mutableStateOf(View.Archive) }
    var demoSelection by remember { mutableStateOf(DemoAlbums.first().id) }
    var showAlbum by remember { mutableStateOf(false) }
    var showAbout by remember { mutableStateOf(false) }
    val library = model.albums.ifEmpty { DemoAlbums }
    val selected = library.firstOrNull { it.id == (model.selectedAlbumId ?: demoSelection) } ?: library.first()
    val realLibrary = model.albums.isNotEmpty()
    val colors = MaterialTheme.colorScheme

    Scaffold(
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.navigationBars).background(colors.background)) {
                MiniPlayer(model, onOpen = { view = View.Playing })
                HorizontalDivider(color = colors.outline.copy(alpha = .25f))
                Row(Modifier.fillMaxWidth().height(62.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    View.entries.forEach { entry ->
                        val active = entry == view
                        Column(
                            Modifier.weight(1f).fillMaxHeight().clickable { view = entry },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Box(Modifier.size(4.dp).background(if (active) colors.primary else Color.Transparent))
                            Spacer(Modifier.height(5.dp))
                            Text("${entry.code}  ${entry.label}", fontSize = 12.sp,
                                color = if (active) colors.onSurface else colors.onSurfaceVariant,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Header(onChooseFolder, canImport = !model.scanning && !model.loading)
            if (model.scanning) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("正在整理档案 · 已读取 ${model.scannedCount} 首", Modifier.weight(1f), fontSize = 12.sp)
                        TextButton(onClick = model::cancelScan) { Text("取消") }
                    }
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            model.message?.let { message ->
                Row(Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(start = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(message, Modifier.weight(1f).padding(vertical = 8.dp), fontSize = 12.sp)
                    IconButton(onClick = model::dismissMessage) { Icon(Icons.Default.Close, "关闭提示", Modifier.size(16.dp)) }
                }
            }
            Box(Modifier.weight(1f)) {
                when (view) {
                    View.Archive -> ArchivePage(
                        library, selected, !realLibrary,
                        onSelect = { if (realLibrary) model.selectAlbum(it) else demoSelection = it.id },
                        onPlay = { if (realLibrary) model.playAlbum(selected) else onChooseFolder() },
                        onOpen = { showAlbum = true },
                        canImport = !model.scanning && !model.loading,
                        onChooseFolder = onChooseFolder,
                    )
                    View.Tracks -> TrackPage(model.tracks, model.playback.trackId, model::playTrack, onChooseFolder)
                    View.Playing -> PlayingPage(model, onChooseFolder)
                    View.Style -> StylePage(model, onChooseFolder, onAbout = { showAbout = true })
                }
            }
        }
    }
    if (showAlbum) {
        ModalBottomSheet(onDismissRequest = { showAlbum = false }, containerColor = colors.background) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Eyebrow("ALBUM FILE / 曲目档案")
                Text(selected.title, fontSize = 23.sp, fontFamily = ArchiveFont, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = 12.dp))
                if (selected.tracks.isEmpty()) {
                    Text("这是用于预览界面的示例档案。选择自己的音乐文件夹后，这里会显示真实专辑和歌曲。",
                        fontSize = 14.sp, modifier = Modifier.padding(bottom = 24.dp))
                    Button(onClick = { showAlbum = false; onChooseFolder() }, enabled = !model.scanning) { Text("选择音乐文件夹") }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp)) {
                        items(selected.tracks, key = { it.id }) { track ->
                            TrackRow(track, selected.tracks.indexOf(track) + 1, track.id == model.playback.trackId,
                                onClick = { model.playTrack(track) })
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
    if (showAbout) AlertDialog(
        onDismissRequest = { showAbout = false },
        title = { Text("Rhine Android · 0.1") },
        text = { Text("独立的 Android 本地音乐播放器。\n\n视觉与盒体素材来自 RhinE / Audio Archive，保留原作者版权及 MIT 许可；Oxanium 字体按 SIL OFL 授权。\n\n首版支持文件夹导入、专辑架、曲目播放和系统媒体控制。同步歌词与音频频谱尚未接入。\n\n仅在本机处理你选择的音乐文件。") },
        confirmButton = { TextButton(onClick = { showAbout = false }) { Text("知道了") } },
    )
}

@Composable
private fun Header(onChooseFolder: () -> Unit, canImport: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(43.dp).background(colors.onSurface), contentAlignment = Alignment.Center) {
            Text("∞", color = colors.surface, fontSize = 35.sp, fontFamily = FontFamily.Serif)
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text("AUDIO ARCHIVE", fontSize = 18.sp, fontFamily = ArchiveFont, fontWeight = FontWeight.SemiBold)
            Text("RHINE  /  LISTENING TERMINAL", fontSize = 8.sp, letterSpacing = 1.sp, fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant)
        }
        IconButton(onClick = onChooseFolder, enabled = canImport) { Icon(Icons.Default.CreateNewFolder, "选择音乐文件夹", Modifier.size(22.dp)) }
    }
    HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = colors.outline.copy(alpha = .3f))
}

@Composable
private fun ArchivePage(
    albums: List<Album>, selected: Album, demo: Boolean, onSelect: (Album) -> Unit,
    onPlay: () -> Unit, onOpen: () -> Unit, canImport: Boolean, onChooseFolder: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1.5f).fillMaxHeight()) {
                    ShelfLabel(demo, albums.size)
                    ArchiveShelf(albums, selected.id, onSelect, Modifier.fillMaxWidth().weight(1f))
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(24.dp)) {
                    AlbumFile(selected, albums.indexOf(selected), demo, onPlay, onOpen, canImport)
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                ShelfLabel(demo, albums.size)
                ArchiveShelf(albums, selected.id, onSelect, Modifier.fillMaxWidth().height(330.dp))
                Column(Modifier.padding(horizontal = 24.dp)) {
                    AlbumFile(selected, albums.indexOf(selected), demo, onPlay, onOpen, canImport)
                    Spacer(Modifier.height(28.dp))
                }
            }
        }
    }
}

@Composable
private fun ShelfLabel(demo: Boolean, count: Int) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow(if (demo) "DEMO ARCHIVE / 示例档案" else "LOCAL COLLECTION / 本地馆藏", Modifier.weight(1f))
        Text(String.format(Locale.ROOT, "%02d", count) + " ALBUMS", fontSize = 10.sp, fontFamily = ArchiveFont)
    }
}

@Composable
private fun AlbumFile(album: Album, index: Int, demo: Boolean, onPlay: () -> Unit, onOpen: () -> Unit, canImport: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Eyebrow("ARCHIVE / " + String.format(Locale.ROOT, "%04d", index + 1), Modifier.weight(1f))
        Text(if (demo) "PREVIEW" else "LOCAL AUDIO", color = colors.primary, fontSize = 9.sp,
            modifier = Modifier.border(1.dp, colors.primary.copy(alpha = .65f)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
    Spacer(Modifier.height(15.dp))
    Column(Modifier.fillMaxWidth().border(1.dp, colors.outline.copy(alpha = .4f)).background(colors.surfaceVariant.copy(alpha = .5f))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("▼ // RHINE: AUDIO ARCHIVE", Modifier.weight(1f), fontSize = 8.sp, fontFamily = FontFamily.Monospace)
            Barcode(Modifier.width(62.dp).height(10.dp))
        }
        HorizontalDivider(color = colors.outline.copy(alpha = .4f))
        Text(album.title, Modifier.padding(horizontal = 14.dp, vertical = 15.dp), fontFamily = ArchiveFont,
            fontSize = 23.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(Modifier.fillMaxWidth().height(3.dp).background(colors.primary.copy(alpha = .8f)))
    }
    Text(album.artist, Modifier.padding(top = 14.dp), color = colors.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Row(Modifier.fillMaxWidth().padding(top = 7.dp, bottom = 17.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(if (demo) "尚未导入音乐" else "${album.tracks.size} 首曲目", fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Text(if (demo) "仅供界面预览" else formatTime(album.tracks.sumOf { it.durationMs }), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
    }
    if (!demo) {
        album.tracks.take(3).forEachIndexed { trackIndex, track ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Text(String.format(Locale.ROOT, "%02d", trackIndex + 1), Modifier.width(25.dp), fontSize = 11.sp, color = colors.onSurfaceVariant)
                Text(track.title, Modifier.weight(1f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatTime(track.durationMs), Modifier.padding(start = 10.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = onPlay, enabled = !demo || canImport, shape = RoundedCornerShape(0.dp),
            colors = ButtonDefaults.buttonColors(containerColor = colors.onSurface, contentColor = colors.surface),
            modifier = Modifier.weight(1f).height(48.dp)) {
            Icon(if (demo) Icons.Default.Add else Icons.Default.PlayArrow, null, Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
            Text(if (demo) "导入音乐" else "播放专辑", fontSize = 12.sp)
        }
        OutlinedButton(onClick = onOpen, shape = RoundedCornerShape(0.dp), modifier = Modifier.height(48.dp)) {
            Text("打开档案", color = colors.onSurface, fontSize = 12.sp)
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.NorthEast, null, Modifier.size(15.dp))
        }
    }
    Text(if (demo) "选择一个音乐文件夹，让你的收藏进入档案架。" else "来源：设备文件夹 · 音乐保留在原位置", fontSize = 10.sp,
        color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun TrackPage(tracks: List<Track>, playingId: String?, onPlay: (Track) -> Unit, onImport: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(tracks, query) { tracks.filter { query.isBlank() || "${it.title} ${it.artist} ${it.album}".contains(query.trim(), ignoreCase = true) } }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Eyebrow("TRACK INDEX / 曲目索引", Modifier.padding(top = 20.dp, bottom = 12.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("搜索歌名、歌手或专辑", fontSize = 13.sp) }, leadingIcon = { Icon(Icons.Default.Search, null) },
            shape = RoundedCornerShape(0.dp))
        Text("${filtered.size} TRACKS", Modifier.padding(vertical = 12.dp), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        if (tracks.isEmpty()) {
            EmptyState("档案架还没有音乐", "选择设备中的音乐文件夹，歌曲会按专辑整理。", onImport)
        } else {
            LazyColumn(Modifier.weight(1f)) {
                items(filtered, key = { it.id }) { track ->
                    TrackRow(track, filtered.indexOf(track) + 1, track.id == playingId, onClick = { onPlay(track) })
                }
            }
        }
    }
}

@Composable
private fun TrackRow(track: Track, index: Int, playing: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (playing) "▶" else String.format(Locale.ROOT, "%02d", index), Modifier.width(30.dp), fontSize = 11.sp,
                color = if (playing) colors.primary else colors.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                Text(track.title, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (playing) colors.primary else colors.onSurface)
                Text(track.artist, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp))
            }
            Text(formatTime(track.durationMs), fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(start = 12.dp))
        }
        HorizontalDivider(color = colors.outline.copy(alpha = .18f))
    }
}

@Composable
private fun MiniPlayer(model: RhineViewModel, onOpen: () -> Unit) {
    val state = model.playback
    val colors = MaterialTheme.colorScheme
    HorizontalDivider(color = colors.outline.copy(alpha = .3f))
    Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).border(1.dp, colors.outline.copy(alpha = .4f)).clickable(onClick = onOpen), contentAlignment = Alignment.Center) {
            Text("∞", color = if (state.playing) colors.primary else colors.onSurfaceVariant, fontSize = 25.sp)
        }
        Column(Modifier.weight(1f).clickable(onClick = onOpen).padding(horizontal = 11.dp)) {
            Text(state.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (state.trackId == null) state.artist else "${state.artist} · ${formatTime(state.positionMs)}",
                fontSize = 9.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp))
        }
        IconButton(onClick = model::togglePlayback, enabled = state.connected && model.tracks.isNotEmpty()) {
            Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "暂停" else "播放")
        }
        IconButton(onClick = model::next, enabled = state.trackId != null) { Icon(Icons.Default.SkipNext, "下一首") }
    }
}

@Composable
private fun PlayingPage(model: RhineViewModel, onImport: () -> Unit) {
    val state = model.playback
    val colors = MaterialTheme.colorScheme
    val current = model.tracks.firstOrNull { it.id == state.trackId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth()) {
            Eyebrow("NOW LISTENING / 正在聆听", Modifier.weight(1f))
            Text(if (state.playing) "● PLAY" else "○ IDLE", color = if (state.playing) colors.primary else colors.onSurfaceVariant,
                fontSize = 10.sp, fontFamily = ArchiveFont)
        }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.widthIn(max = 340.dp).fillMaxWidth().aspectRatio(1f).border(1.dp, colors.outline.copy(alpha = .4f)).padding(18.dp)) {
            CoverArt(current?.artworkPath, Modifier.fillMaxSize())
            Box(Modifier.width(24.dp).height(6.dp).align(Alignment.TopEnd).offset(y = (-21).dp).background(colors.primary))
        }
        Spacer(Modifier.height(25.dp))
        Text(state.title, fontFamily = ArchiveFont, fontSize = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        Text(state.artist, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(20.dp))
        var dragging by remember(state.trackId) { mutableStateOf<Float?>(null) }
        Slider(value = dragging ?: state.positionMs.toFloat().coerceIn(0f, state.durationMs.toFloat().coerceAtLeast(1f)),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { model.seekTo(it.toLong()) }; dragging = null },
            valueRange = 0f..state.durationMs.toFloat().coerceAtLeast(1f), enabled = state.durationMs > 0,
            modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(dragging?.toLong() ?: state.positionMs), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            Text(formatTime(state.durationMs), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = model::toggleRepeat, enabled = state.connected) {
                Icon(Icons.Default.Repeat, if (state.repeat) "关闭列表循环" else "开启列表循环",
                    tint = if (state.repeat) colors.primary else colors.onSurfaceVariant)
            }
            IconButton(onClick = model::previous, enabled = state.trackId != null) { Icon(Icons.Default.SkipPrevious, "上一首", Modifier.size(30.dp)) }
            FilledIconButton(onClick = model::togglePlayback, enabled = state.connected && model.tracks.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp).size(64.dp), shape = RoundedCornerShape(0.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.onSurface, contentColor = colors.surface)) {
                Icon(if (state.playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (state.playing) "暂停播放" else "开始播放", Modifier.size(30.dp))
            }
            IconButton(onClick = model::next, enabled = state.trackId != null) { Icon(Icons.Default.SkipNext, "下一首", Modifier.size(30.dp)) }
            Spacer(Modifier.width(48.dp))
        }
        if (model.tracks.isEmpty()) TextButton(onClick = onImport, enabled = !model.scanning) { Text("选择音乐文件夹") }
        else Text("LOCAL PLAYBACK / 本地播放", color = colors.onSurfaceVariant, fontFamily = FontFamily.Monospace, fontSize = 9.sp)
    }
}

@Composable
private fun CoverArt(path: String?, modifier: Modifier = Modifier) {
    val image by produceState<ImageBitmap?>(null, path) {
        value = withContext(Dispatchers.IO) {
            path?.let {
                try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(it, bounds)
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 768).coerceAtLeast(1)
                    }
                    BitmapFactory.decodeFile(it, options)?.asImageBitmap()
                } catch (_: Exception) { null }
            }
        }
    }
    if (image != null) Image(image!!, "专辑封面", modifier, contentScale = ContentScale.Crop)
    else {
        val colors = MaterialTheme.colorScheme
        Box(modifier.background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(35.dp)) {
                val radius = size.minDimension / 2.2f
                for (i in 0..7) drawCircle(colors.outline.copy(alpha = .18f), radius * (1f - i * .07f), style = Stroke(1.4f))
                drawCircle(colors.primary, radius * .13f)
                drawLine(colors.onSurface.copy(alpha = .5f), Offset(0f, size.height * .85f), Offset(size.width, size.height * .85f), 1f)
            }
            Text("RHINE", fontFamily = ArchiveFont, fontSize = 15.sp, letterSpacing = 5.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 21.dp))
        }
    }
}

@Composable
private fun StylePage(model: RhineViewModel, onImport: () -> Unit, onAbout: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Eyebrow("APPEARANCE / 外观与馆藏")
        Text("你的聆听空间", fontSize = 25.sp, fontFamily = ArchiveFont, modifier = Modifier.padding(top = 17.dp, bottom = 25.dp))
        SectionTitle("01", "明暗模式")
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChoiceCard("LIGHT", "浅色", !model.darkMode, { model.setDark(false) }, Modifier.weight(1f))
            ChoiceCard("DARK", "深色", model.darkMode, { model.setDark(true) }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(15.dp))
        SectionTitle("02", "强调色")
        Row(Modifier.fillMaxWidth().padding(vertical = 15.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("ARCHIVE", "FIELD", "COLD FRONT").forEachIndexed { i, label ->
                val accent = listOf(ArchiveOrange, Color(0xFF5B8F85), Color(0xFF557BA4))[i]
                Column(Modifier.weight(1f).border(if (model.accentIndex == i) 2.dp else 1.dp,
                    if (model.accentIndex == i) colors.primary else colors.outline.copy(alpha = .4f))
                    .clickable { model.setAccent(i) }.padding(12.dp)) {
                    Box(Modifier.fillMaxWidth().height(26.dp).background(accent))
                    Text(label, fontSize = 8.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 12.dp), maxLines = 1)
                }
            }
        }
        Spacer(Modifier.height(15.dp))
        SectionTitle("03", "音乐文件夹")
        Text("${model.tracks.size} 首曲目 / ${model.albums.size} 张专辑", fontSize = 13.sp, modifier = Modifier.padding(vertical = 15.dp))
        Text("通过系统选择器授权一个文件夹。音乐留在原位置；新选择的文件夹会替换当前馆藏。",
            color = colors.onSurfaceVariant, fontSize = 12.sp, lineHeight = 19.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onImport, enabled = !model.scanning && !model.loading, shape = RoundedCornerShape(0.dp)) { Text("选择文件夹") }
            TextButton(onClick = model::rescan, enabled = model.lastFolder != null && !model.scanning) { Text("重新扫描") }
        }
        Spacer(Modifier.height(15.dp))
        SectionTitle("04", "关于")
        Text("RHINE ANDROID / 0.1", fontFamily = ArchiveFont, fontSize = 12.sp, modifier = Modifier.padding(top = 17.dp))
        Text("音乐在这里，思绪在别处。", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 7.dp))
        TextButton(onClick = onAbout, modifier = Modifier.padding(top = 8.dp)) { Text("版本与致谢") }
    }
}

@Composable
private fun ChoiceCard(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.border(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outline.copy(alpha = .4f))
        .clickable(onClick = onClick).padding(17.dp)) {
        Text(title, fontFamily = ArchiveFont, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
        Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
            Text(subtitle, Modifier.weight(1f), fontSize = 12.sp)
            if (selected) Box(Modifier.size(6.dp).background(colors.primary))
        }
    }
}

@Composable
private fun SectionTitle(number: String, title: String) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.onSurface).padding(horizontal = 9.dp, vertical = 5.dp)) {
        Text(title, Modifier.weight(1f), fontSize = 10.sp, color = MaterialTheme.colorScheme.surface)
        Text(number, fontSize = 10.sp, color = MaterialTheme.colorScheme.surface, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun EmptyState(title: String, message: String, onImport: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 50.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("∞", fontSize = 65.sp, color = MaterialTheme.colorScheme.outline)
        Text(title, fontSize = 20.sp, modifier = Modifier.padding(top = 20.dp))
        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 15.dp))
        OutlinedButton(onClick = onImport, shape = RoundedCornerShape(0.dp)) { Text("选择音乐文件夹") }
    }
}

@Composable
private fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontSize = 9.sp, letterSpacing = .8.sp, fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun Barcode(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f)
    Canvas(modifier) {
        repeat(22) { index ->
            val x = size.width * index / 22f
            drawLine(color, Offset(x, 0f), Offset(x, size.height), if (index % 3 == 0) 2.4f else 1f)
        }
    }
}

fun formatTime(milliseconds: Long): String {
    val total = milliseconds.coerceAtLeast(0) / 1_000
    return if (total >= 3_600) String.format(Locale.ROOT, "%d:%02d:%02d", total / 3_600, total / 60 % 60, total % 60)
    else String.format(Locale.ROOT, "%d:%02d", total / 60, total % 60)
}
