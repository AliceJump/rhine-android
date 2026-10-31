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
import androidx.compose.ui.geometry.Offset
import io.github.alicejump.rhine.data.Album
import java.io.File
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.min
import org.json.JSONObject

internal data class CaseAsset(val bitmap: Bitmap, val coverQuad: FloatArray, val logicalSize: Float)

internal fun readCaseAsset(context: Context): CaseAsset? = runCatching {
    val metadata = context.assets.open("rhine/marble/case.json").bufferedReader().use { JSONObject(it.readText()) }
    val quad = metadata.getJSONObject("array").getJSONArray("cover")
    val points = FloatArray(8) { i -> quad.getJSONArray(i / 2).getDouble(i % 2).toFloat() }
    val bitmap = context.assets.open("rhine/marble/array-clear.png").use(BitmapFactory::decodeStream)
        ?: return@runCatching null
    CaseAsset(bitmap, points, metadata.getJSONArray("array_res").getDouble(0).toFloat())
}.getOrNull()

internal fun readCover(context: Context, path: String, cache: LruCache<String, Bitmap>, maxSize: Int = 512): Bitmap? = runCatching {
    val file = File(path).canonicalFile
    val allowedRoots = listOf(context.filesDir.canonicalFile, context.cacheDir.canonicalFile)
    if (!file.isFile || allowedRoots.none { file.path.startsWith(it.path + File.separator) }) return@runCatching null
    val key = "${file.path}:${file.length()}:${file.lastModified()}:$maxSize"
    cache.get(key)?.let { return@runCatching it }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
    val options = BitmapFactory.Options().apply { inSampleSize = 1 }
    while (maxOf(bounds.outWidth, bounds.outHeight) / options.inSampleSize > maxSize) options.inSampleSize *= 2
    BitmapFactory.decodeFile(file.path, options)?.also { cache.put(key, it) }
}.getOrNull()

internal class ShelfHitStore {
    var items: List<ShelfHit> = emptyList()
    var clip: PanoramaRect? = null

    fun hitTest(point: Offset): ShelfHit? {
        val visible = clip
        if (visible != null && (visible.width <= 0f || visible.height <= 0f ||
            point.x !in visible.left..visible.right || point.y !in visible.top..visible.bottom)) return null
        return items.asReversed().firstOrNull { it.contains(point) }
    }
}

internal data class ShelfColors(
    val background: Int,
    val grid: Int,
    val border: Int,
    val registration: Int,
    val accent: Int,
)

internal data class ShelfHit(val albumId: String, val quad: FloatArray) {
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

internal class ShelfPainter {
    private val monospace = Typeface.create("monospace", Typeface.NORMAL)
    private val sansSerif = Typeface.create("sans-serif", Typeface.NORMAL)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = sansSerif }
    private val matrix = Matrix()
    private val path = Path()
    private val spriteRect = RectF()
    private val palette = arrayOf(
        intArrayOf(0xFF577C7C.toInt(), 0xFFD2DAD1.toInt()),
        intArrayOf(0xFF655A7B.toInt(), 0xFFD2CCD5.toInt()),
        intArrayOf(0xFFAD8559.toInt(), 0xFFE1D6BD.toInt()),
        intArrayOf(0xFF516B8A.toInt(), 0xFFC6D4DA.toInt()),
    )
    private val placeholderShaders = palette.map {
        LinearGradient(0f, 0f, 512f, 512f, it[0], it[1], Shader.TileMode.CLAMP)
    }

    private fun drawBackground(canvas: AndroidCanvas, width: Float, height: Float, density: Float, colors: ShelfColors) {
        canvas.drawColor(colors.background)
        line.color = colors.grid
        line.strokeWidth = 0.5f * density
        val gridStep = 56f * density
        val lines = ceil((width + height * 2.1f) / gridStep).toInt()
        for (i in -lines..lines) {
            val x = i * gridStep
            canvas.drawLine(x, height, x + height * 1.1f, 0f, line)
            canvas.drawLine(x, 0f, x + height * 2.1f, height, line)
        }
        line.color = colors.border
        line.strokeWidth = density
        canvas.drawLine(0f, height - density, width, height - density, line)
    }

    /** One clipped host canvas paints the same entities for every transition frame. */
    fun drawFrame(
        canvas: AndroidCanvas,
        width: Float,
        height: Float,
        density: Float,
        albums: List<Album>,
        frame: ShelfMotionFrame,
        asset: CaseAsset?,
        covers: Map<String, Bitmap>,
        hitStore: ShelfHitStore,
        colors: ShelfColors,
    ) {
        val saved = canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        canvas.clipRect(frame.clip.left, frame.clip.top, frame.clip.right, frame.clip.bottom)
        // drawColor also respects this clip: ordinary header / inspector space stays untouched.
        drawBackground(canvas, width, height, density, colors)
        val hits = ArrayList<ShelfHit>(frame.items.size)
        for (item in frame.items) {
            if (item.alpha <= .005f) continue
            val album = albums.getOrNull(item.index) ?: continue
            val bounds = item.caseBounds
            if (bounds.right < frame.clip.left || bounds.left > frame.clip.right ||
                bounds.bottom < frame.clip.top || bounds.top > frame.clip.bottom) continue
            val entity = canvas.save()
            canvas.translate(item.x, item.y)
            val logicalSize = asset?.logicalSize ?: 600f
            val scale = item.size / logicalSize
            canvas.scale(scale, scale)
            canvas.rotate(item.rotationDegrees, logicalSize * .5f, logicalSize * .515f)
            drawCase(canvas, asset, album, album.artworkPath?.let(covers::get),
                (item.alpha.coerceIn(0f, 1f) * 255f).toInt(), item.focus, item.index,
                colors.accent, frostAmount = item.frost)
            canvas.restoreToCount(entity)
            if (item.alpha > .1f) hits += ShelfHit(album.id, item.quad)

            val decorationAlpha = frame.progress * item.alpha
            if (decorationAlpha > .01f) {
                line.color = withAlpha(colors.border, decorationAlpha)
                line.strokeWidth = density * .7f
                val shelfY = bounds.bottom + 6f * density
                canvas.drawLine(bounds.left - 7f * density, shelfY, bounds.right + 7f * density, shelfY, line)
                text.typeface = monospace
                text.textSize = 9f * density
                text.color = withAlpha(if (item.focus > .5f) colors.accent else colors.registration, decorationAlpha)
                val label = "${(item.index + 1).toString().padStart(2, '0')} / ${album.title.replace('\n', ' ')}"
                val length = text.breakText(label, true, bounds.width.coerceAtLeast(1f), null)
                canvas.drawText(label.take(length), bounds.left, shelfY + 12f * density, text)
            }
        }
        hitStore.items = hits
        hitStore.clip = frame.clip
        canvas.restoreToCount(saved)
    }

    private fun withAlpha(color: Int, opacity: Float): Int = AndroidColor.argb(
        (AndroidColor.alpha(color) * opacity.coerceIn(0f, 1f)).toInt(),
        AndroidColor.red(color), AndroidColor.green(color), AndroidColor.blue(color),
    )

    private fun drawCase(canvas: AndroidCanvas, asset: CaseAsset?, album: Album?, cover: Bitmap?, alpha: Int, focus: Float, index: Int, accentColor: Int, frostAmount: Float = 1f - focus) {
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
            paint.shader = placeholderShaders[Math.floorMod(album?.id?.hashCode() ?: index, placeholderShaders.size)]
            canvas.drawRect(0f, 0f, 512f, 512f, paint)
            paint.shader = null
            line.color = AndroidColor.argb(alpha * 2 / 3, 245, 244, 236)
            line.strokeWidth = 2f
            for (r in 1..6) canvas.drawCircle(256f, 256f, r * 35f, line)
            canvas.drawLine(0f, 512f, 512f, 0f, line)
            canvas.drawLine(0f, 0f, 512f, 512f, line)
            text.color = AndroidColor.argb(alpha, 248, 247, 239)
            text.textSize = 32f
            text.typeface = monospace
            canvas.drawText("A / ${(index + 1).toString().padStart(3, '0')}", 33f, 63f, text)
            text.textSize = 15f
            canvas.drawText("LOCAL AUDIO ARCHIVE", 33f, 475f, text)
        }
        canvas.restoreToCount(saved)
        if (frostAmount > .01f) {
            polygon(quad)
            paint.color = AndroidColor.argb((frostAmount.coerceIn(0f, 1f) * 170f * alpha / 255f).toInt(), 237, 238, 232)
            canvas.drawPath(path, paint)
        }
        paint.shader = null
        paint.alpha = alpha
        if (asset != null) {
            spriteRect.set(0f, 0f, asset.logicalSize, asset.logicalSize)
            canvas.drawBitmap(asset.bitmap, null, spriteRect, paint)
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
            text.typeface = sansSerif
            text.textSize = 8f
            canvas.drawText(album.title.replace('\n', ' ').uppercase(Locale.ROOT).take(24), 151f, 112f, text)
            canvas.restoreToCount(labelState)
        }
        if (focus > 0.02f) {
            paint.color = accentColor
            paint.alpha = (focus * 240f * alpha / 255f).toInt()
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

}
