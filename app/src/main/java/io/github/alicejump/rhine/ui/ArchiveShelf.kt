package io.github.alicejump.rhine.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.util.LruCache
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alicejump.rhine.data.Album
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * A touch-first port of RhinE's specimen shelf. The original MARBLE sprite is composited above
 * each album cover using the upstream cover quadrilateral. No browser or foobar2000 host is used.
 *
 * The selected album is controlled by the caller. Only nearby covers are decoded, off the UI
 * thread; the sprite and a bounded cover cache are shared by the visible cases.
 */
@Composable
fun ArchiveShelf(
    albums: List<Album>,
    selectedAlbumId: String?,
    onSelect: (Album) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current.applicationContext
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    // Read the fields directly: MaterialTheme may update an existing ColorScheme instance.
    val sceneColors = ShelfColors(
        background = colors.surface.toArgb(),
        grid = colors.outline.copy(alpha = 0.25f).toArgb(),
        border = colors.outline.copy(alpha = 0.38f).toArgb(),
        registration = colors.onSurfaceVariant.copy(alpha = 0.65f).toArgb(),
        accent = colors.primary.toArgb(),
    )
    val selectedIndex = albums.indexOfFirst { it.id == selectedAlbumId }.coerceAtLeast(0)
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = spring(dampingRatio = 0.84f, stiffness = Spring.StiffnessLow),
        label = "archive-selection",
    )
    var dragDistance by remember { mutableFloatStateOf(0f) }
    val dragVisual by animateFloatAsState(
        targetValue = dragDistance * 0.12f,
        animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMedium),
        label = "archive-touch",
    )
    val dragThreshold = with(density) { 40.dp.toPx() }
    val hitStore = remember { ShelfHitStore() }
    val painter = remember { ShelfPainter() }
    val coverCache = remember {
        object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
            override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
        }
    }
    val asset by produceState<CaseAsset?>(null, context) {
        value = withContext(Dispatchers.IO) { readCaseAsset(context) }
    }
    val nearbyPaths = remember(albums, selectedIndex) {
        albums.subList((selectedIndex - 5).coerceAtLeast(0), (selectedIndex + 6).coerceAtMost(albums.size))
            .mapNotNull { it.artworkPath }.distinct()
    }
    val covers by produceState<Map<String, Bitmap>>(emptyMap(), nearbyPaths, context) {
        value = withContext(Dispatchers.IO) {
            buildMap {
                nearbyPaths.forEach { path ->
                    readCover(context, path, coverCache)?.let { put(path, it) }
                }
            }
        }
    }
    fun selectRelative(delta: Int): Boolean {
        val next = selectedIndex + delta
        if (next !in albums.indices) return false
        onSelect(albums[next])
        return true
    }

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        // Reserve the measured controls before assigning the remaining height to the scene.
        // This works for both a fixed portrait height and a weighted, bounded wide layout.
        val shelfHeight = if (constraints.hasBoundedHeight) maxHeight
            else (maxWidth * 0.82f + 56.dp).coerceIn(296.dp, 416.dp)
        Column(modifier = Modifier.fillMaxWidth().height(shelfHeight)) {
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                        .semantics {
                            contentDescription = "专辑档案架，左右滑动切换专辑"
                            stateDescription = albums.getOrNull(selectedIndex)?.let {
                                "${selectedIndex + 1} / ${albums.size}，${it.title}，${it.artist}"
                            } ?: "尚未导入音乐"
                            customActions = listOf(
                                CustomAccessibilityAction("上一张专辑") { selectRelative(-1) },
                                CustomAccessibilityAction("下一张专辑") { selectRelative(1) },
                            )
                        }
                        .pointerInput(albums, selectedAlbumId) {
                            detectTapGestures { point ->
                                hitStore.items.asReversed().firstOrNull { it.contains(point) }
                                    ?.let { hit -> albums.firstOrNull { it.id == hit.albumId }?.let(onSelect) }
                            }
                        }
                        .pointerInput(albums, selectedAlbumId, dragThreshold) {
                            detectHorizontalDragGestures(
                                onDragStart = { dragDistance = 0f },
                                onDragEnd = {
                                    if (abs(dragDistance) >= dragThreshold) {
                                        selectRelative(if (dragDistance < 0f) 1 else -1)
                                    }
                                    dragDistance = 0f
                                },
                                onDragCancel = { dragDistance = 0f },
                            ) { change, amount ->
                                change.consume()
                                dragDistance = (dragDistance + amount).coerceIn(-4 * dragThreshold, 4 * dragThreshold)
                            }
                        },
                ) {
                    drawIntoCanvas { canvas ->
                        painter.draw(
                            canvas = canvas.nativeCanvas,
                            width = size.width,
                            height = size.height,
                            density = density.density,
                            albums = albums,
                            position = position,
                            dragOffset = dragVisual,
                            asset = asset,
                            covers = covers,
                            hitStore = hitStore,
                            colors = sceneColors,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("SPECIMEN / MARBLE", color = colors.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text("${albums.size.toString().padStart(2, '0')} ALBUMS", color = colors.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { selectRelative(-1) }, enabled = selectedIndex > 0,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                    modifier = Modifier.semantics { contentDescription = "上一张专辑" }) {
                    Text("←", fontSize = 23.sp)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${if (albums.isEmpty()) "00" else (selectedIndex + 1).toString().padStart(2, '0')} / ${albums.size.toString().padStart(2, '0')}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        color = colors.onSurface,
                    )
                    Text(
                        if (albums.isEmpty()) "导入音乐后显示专辑" else "滑动浏览 · 轻点选择",
                        fontSize = 10.sp,
                        color = colors.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { selectRelative(1) }, enabled = selectedIndex < albums.lastIndex,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurface),
                    modifier = Modifier.semantics { contentDescription = "下一张专辑" }) {
                    Text("→", fontSize = 23.sp)
                }
            }
        }
    }
}

private data class CaseAsset(val bitmap: Bitmap, val coverQuad: FloatArray, val logicalSize: Float)

private fun readCaseAsset(context: Context): CaseAsset? = runCatching {
    val metadata = context.assets.open("rhine/marble/case.json").bufferedReader().use { JSONObject(it.readText()) }
    val quad = metadata.getJSONObject("array").getJSONArray("cover")
    val points = FloatArray(8) { i -> quad.getJSONArray(i / 2).getDouble(i % 2).toFloat() }
    val bitmap = context.assets.open("rhine/marble/array-clear.png").use(BitmapFactory::decodeStream)
        ?: return@runCatching null
    CaseAsset(bitmap, points, metadata.getJSONArray("array_res").getDouble(0).toFloat())
}.getOrNull()

private fun readCover(context: Context, path: String, cache: LruCache<String, Bitmap>): Bitmap? = runCatching {
    val file = File(path).canonicalFile
    val allowedRoots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
    if (!file.isFile || allowedRoots.none { file.path.startsWith(it.path + File.separator) }) return@runCatching null
    val key = "${file.path}:${file.length()}:${file.lastModified()}"
    cache.get(key)?.let { return@runCatching it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > 512) options.inSampleSize *= 2
    BitmapFactory.decodeFile(file.path, options)?.also { cache.put(key, it) }
}.getOrNull()

private class ShelfHitStore { var items: List<ShelfHit> = emptyList() }

private data class ShelfColors(
    val background: Int,
    val grid: Int,
    val border: Int,
    val registration: Int,
    val accent: Int,
)

private data class ShelfHit(val albumId: String, val quad: FloatArray) {
    fun contains(point: Offset): Boolean {
        var positive = false
        var negative = false
        for (i in 0..3) {
            val next = (i + 1) % 4
            val cross = (quad[next * 2] - quad[i * 2]) * (point.y - quad[i * 2 + 1]) -
                (quad[next * 2 + 1] - quad[i * 2 + 1]) * (point.x - quad[i * 2])
            positive = positive || cross > 0
            negative = negative || cross < 0
            if (positive && negative) return false
        }
        return true
    }
}

private class ShelfPainter {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val matrix = Matrix()
    private val path = Path()
    private val palette = arrayOf(
        intArrayOf(0xFF577C7C.toInt(), 0xFFD2DAD1.toInt()),
        intArrayOf(0xFF655A7B.toInt(), 0xFFD2CCD5.toInt()),
        intArrayOf(0xFFAD8559.toInt(), 0xFFE1D6BD.toInt()),
        intArrayOf(0xFF516B8A.toInt(), 0xFFC6D4DA.toInt()),
    )

    fun draw(
        canvas: AndroidCanvas,
        width: Float,
        height: Float,
        density: Float,
        albums: List<Album>,
        position: Float,
        dragOffset: Float,
        asset: CaseAsset?,
        covers: Map<String, Bitmap>,
        hitStore: ShelfHitStore,
        colors: ShelfColors,
    ) {
        val clip = canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.drawColor(colors.background)
        line.color = colors.grid
        line.strokeWidth = 0.5f * density
        val gridStep = 56f * density
        for (i in -10..20) {
            val x = i * gridStep
            canvas.drawLine(x, height, x + height * 1.1f, 0f, line)
            canvas.drawLine(x, 0f, x + height * 2.1f, height, line)
        }
        line.color = colors.border
        line.strokeWidth = density
        canvas.drawLine(0f, height - density, width, height - density, line)

        val spriteSize = min(width * 0.96f, height * 1.13f)
        val baseX = (width - spriteSize) * 0.5f + dragOffset
        val baseY = (height - spriteSize) * 0.5f + 12f * density
        val stride = spriteSize * 0.155f
        val logicalSize = asset?.logicalSize ?: 600f
        val scale = spriteSize / logicalSize
        val lower = floor(position).toInt() - 4
        val upper = ceil(position).toInt() + 4
        val order = if (albums.isEmpty()) listOf(0) else (lower..upper).filter { it in albums.indices }
            .sortedByDescending { abs(it - position) }
        val hits = ArrayList<ShelfHit>(order.size)
        for (index in order) {
            val relative = if (albums.isEmpty()) 0f else index - position
            val focus = (1f - abs(relative)).coerceIn(0f, 1f)
            val x = baseX + relative * stride
            val y = baseY - relative * stride * 0.34f - focus * 21f * density
            val album = albums.getOrNull(index)
            val alpha = ((0.55f + focus * 0.45f - (abs(relative) - 1f).coerceAtLeast(0f) * 0.08f)
                .coerceIn(0.24f, 1f) * 255).toInt()
            val saved = canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            drawCase(canvas, asset, album, album?.artworkPath?.let(covers::get), alpha, focus, index, colors.accent)
            canvas.restoreToCount(saved)
            if (album != null) {
                val shape = floatArrayOf(128f, 100f, 472f, 231f, 472f, 518f, 128f, 382f)
                for (k in 0..3) { shape[k * 2] = x + shape[k * 2] * scale; shape[k * 2 + 1] = y + shape[k * 2 + 1] * scale }
                hits += ShelfHit(album.id, shape)
            }
        }
        hitStore.items = hits
        drawRegistration(canvas, width, height, density, colors.registration)
        canvas.restoreToCount(clip)
    }

    private fun drawCase(canvas: AndroidCanvas, asset: CaseAsset?, album: Album?, cover: Bitmap?, alpha: Int, focus: Float, index: Int, accentColor: Int) {
        val quad = asset?.coverQuad ?: floatArrayOf(148.6f, 136.66f, 369.09f, 239.25f, 369.09f, 448.34f, 148.6f, 345.74f)
        val crop = cover?.let { min(it.width, it.height).toFloat() } ?: 512f
        val left = cover?.let { (it.width - crop) * 0.5f } ?: 0f
        val top = cover?.let { (it.height - crop) * 0.5f } ?: 0f
        matrix.reset()
        matrix.setPolyToPoly(floatArrayOf(left, top, left + crop, top, left + crop, top + crop, left, top + crop), 0, quad, 0, 4)
        val saved = canvas.save()
        polygon(quad)
        canvas.clipPath(path)
        canvas.concat(matrix)
        paint.shader = null
        paint.alpha = alpha
        if (cover != null) {
            canvas.drawBitmap(cover, 0f, 0f, paint)
        } else {
            val colours = palette[Math.floorMod(album?.id?.hashCode() ?: index, palette.size)]
            paint.shader = LinearGradient(0f, 0f, 512f, 512f, colours[0], colours[1], Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, 512f, 512f, paint)
            paint.shader = null
            line.color = AndroidColor.argb(alpha * 2 / 3, 245, 244, 236)
            line.strokeWidth = 2f
            for (r in 1..6) canvas.drawCircle(256f, 256f, r * 35f, line)
            canvas.drawLine(0f, 512f, 512f, 0f, line)
            canvas.drawLine(0f, 0f, 512f, 512f, line)
            text.color = AndroidColor.argb(alpha, 248, 247, 239)
            text.textSize = 32f
            text.typeface = Typeface.create("monospace", Typeface.NORMAL)
            canvas.drawText("A / ${(index + 1).toString().padStart(3, '0')}", 33f, 63f, text)
            text.textSize = 15f
            canvas.drawText("LOCAL AUDIO ARCHIVE", 33f, 475f, text)
        }
        canvas.restoreToCount(saved)
        if (focus < 0.98f) {
            polygon(quad)
            paint.color = AndroidColor.argb(((1f - focus) * 170f).toInt(), 237, 238, 232)
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        paint.alpha = alpha
        if (asset != null) {
            canvas.drawBitmap(asset.bitmap, null, RectF(0f, 0f, asset.logicalSize, asset.logicalSize), paint)
        } else {
            polygon(floatArrayOf(130f, 109f, 462f, 247f, 462f, 506f, 130f, 368f))
            line.color = AndroidColor.argb(alpha, 143, 147, 141)
            line.strokeWidth = 15f
            canvas.drawPath(path, line)
        }
        if (album != null) {
            val labelState = canvas.save()
            canvas.rotate(25f, 151f, 112f)
            text.color = AndroidColor.argb(alpha, 49, 53, 47)
            text.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            text.textSize = 8f
            canvas.drawText(album.title.replace('\n', ' ').uppercase(Locale.ROOT).take(24), 151f, 112f, text)
            canvas.restoreToCount(labelState)
        }
        if (focus > 0.02f) {
            paint.color = accentColor
            paint.alpha = (focus * 240f).toInt()
            for (k in 0..3) canvas.drawRect(quad[k * 2] - 2f, quad[k * 2 + 1] - 2f, quad[k * 2] + 2f, quad[k * 2 + 1] + 2f, paint)
        }
        paint.alpha = 255
    }

    private fun polygon(points: FloatArray) {
        path.reset()
        path.moveTo(points[0], points[1])
        for (k in 1..3) path.lineTo(points[k * 2], points[k * 2 + 1])
        path.close()
    }

    private fun drawRegistration(canvas: AndroidCanvas, width: Float, height: Float, density: Float, color: Int) {
        line.color = color
        line.strokeWidth = density * 0.6f
        val inset = 13f * density
        val arm = 4f * density
        for (x in listOf(inset, width - inset)) for (y in listOf(33f * density, height - inset)) {
            canvas.drawLine(x - arm, y, x + arm, y, line)
            canvas.drawLine(x, y - arm, x, y + arm, line)
        }
    }
}
