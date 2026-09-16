package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Scanner enhancement modes that can be applied to a captured page.
 * Mirrors the classic "scanner app" filter strip.
 */
enum class ScanFilter(val title: String, val key: String) {
    NONE("Original", "none"),
    MAGIC_COLOR("Magic Color", "magic"),
    GRAYSCALE("Grayscale", "gray"),
    BLACK_AND_WHITE("B&W Document", "bw"),
    HIGHPASS("Light Background", "highpass");

    companion object {
        fun fromKey(key: String?): ScanFilter = entries.firstOrNull { it.key == key } ?: NONE
    }
}

/**
 * Quality / sharpening preset used after the colour filter.
 */
enum class ScanQuality(val title: String, val maxDim: Int) {
    HIGH("High (2000px)", 2000),
    BALANCED("Balanced (1400px)", 1400),
    COMPACT("Compact (1000px)", 1000)
}

/**
 * Configuration for one scan-processing pass.
 */
data class ScanEnhanceConfig(
    val filter: ScanFilter = ScanFilter.MAGIC_COLOR,
    val quality: ScanQuality = ScanQuality.BALANCED,
    val autoDeskew: Boolean = true,
    val autoCrop: Boolean = true,
    val sharpen: Boolean = true
)

/**
 * Result of processing a raw camera frame.
 */
data class ProcessedScan(
    val sourceUri: Uri,
    val processedUri: Uri,
    val width: Int,
    val height: Int,
    val filter: ScanFilter,
    val deskewed: Boolean,
    val cropped: Boolean
)

/**
 * Pure-Android document scanner image pipeline (no OpenCV dependency).
 *
 * Pipeline: downsample -> auto crop + perspective correction -> auto deskew ->
 * colour filter -> unsharp-mask sharpen -> persist to cache as JPEG/PNG.
 */
object DocumentScanner {

    private const val TAG = "DocumentScanner"

    /** Directory that holds processed scan pages for the current session. */
    private fun scanDir(context: Context): File =
        File(context.cacheDir, "scan_pages").apply { mkdirs() }

    /** Directory that holds the raw camera shots (kept tiny & wiped often). */
    private fun rawDir(context: Context): File =
        File(context.cacheDir, "scan_raw").apply { mkdirs() }

    /**
     * Loads a raw frame from [uri], runs the full enhance pipeline and returns
     * a [ProcessedScan] pointing at the optimised page.
     */
    suspend fun processScan(
        context: Context,
        uri: Uri,
        config: ScanEnhanceConfig = ScanEnhanceConfig()
    ): ProcessedScan = withContext(Dispatchers.IO) {
        val raw = decodeBitmap(context, uri, config.quality.maxDim)
            ?: throw IllegalStateException("Could not decode captured frame.")

        try {
            var working = raw
            var cropped = false
            var deskewed = false

            if (config.autoCrop) {
                cropToDocument(working)?.let {
                    if (it !== working) working.recycleSafe()
                    working = it
                    cropped = true
                }
            }

            if (config.autoDeskew) {
                deskew(working)?.let {
                    if (it !== working) working.recycleSafe()
                    working = it
                    deskewed = true
                }
            }

            var filtered = applyFilter(working, config.filter)
            if (filtered !== working) working.recycleSafe()

            if (config.sharpen) {
                val sharp = unsharpMask(filtered)
                if (sharp !== filtered) filtered.recycleSafe()
                filtered = sharp
            }

            val out = saveBitmap(
                context = context,
                bitmap = filtered,
                prefix = "scan_${System.currentTimeMillis()}"
            )
            filtered.recycleSafe()

            ProcessedScan(
                sourceUri = uri,
                processedUri = out.first,
                width = out.second.width,
                height = out.second.height,
                filter = config.filter,
                deskewed = deskewed,
                cropped = cropped
            )
        } finally {
            raw.recycleSafe()
        }
    }

    /**
     * Re-applies [filter] (plus optional re-sharpen) to an already processed page,
     * used when the user taps a different filter chip on an existing thumbnail.
     */
    suspend fun refilterScan(
        context: Context,
        sourceUri: Uri,
        filter: ScanFilter,
        sharpen: Boolean = true,
        maxDim: Int = ScanQuality.BALANCED.maxDim
    ): Uri = withContext(Dispatchers.IO) {
        // Prefer the untouched original frame so filters stay non-destructive.
        val original = decodeBitmap(context, sourceUri, maxDim)
            ?: throw IllegalStateException("Could not re-decode scan source.")
        try {
            var filtered = applyFilter(original, filter)
            if (sharpen) {
                val sharp = unsharpMask(filtered)
                if (sharp !== filtered) filtered.recycleSafe()
                filtered = sharp
            }
            val (outUri, _) = saveBitmap(context, filtered, prefix = "scan_refilter")
            filtered.recycleSafe()
            outUri
        } finally {
            original.recycleSafe()
        }
    }

    // ------------------------------------------------------------------
    // Bitmap IO
    // ------------------------------------------------------------------

    private fun decodeBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? {
        return try {
            // Two-pass decode: read bounds first so [maxDim] can be honoured.
            val bounds = android.graphics.BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            context.contentResolver.openInputStream(uri)?.use { b0 ->
                android.graphics.BitmapFactory.decodeStream(b0, null, bounds)
            }

            var sample = 1
            val longest = max(bounds.outWidth, bounds.outHeight)
            while (longest / sample > maxDim * 2) sample *= 2

            val opts = android.graphics.BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inSampleSize = sample
            }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                android.graphics.BitmapFactory.decodeStream(stream, null, opts)
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Writes [bitmap] into the session scan dir and returns its FileProvider Uri. */
    private fun saveBitmap(
        context: Context,
        bitmap: Bitmap,
        prefix: String
    ): Pair<Uri, Bitmap> {
        val dir = scanDir(context)
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmssSSS", Locale.getDefault()).format(Date())
        val file = File(dir, "${prefix}_$timeStamp.jpg")

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        return uri to bitmap
    }

    /** Deletes every page produced for the current scanning session. */
    suspend fun clearSessionScans(context: Context) = withContext(Dispatchers.IO) {
        scanDir(context).deleteRecursively()
        rawDir(context).deleteRecursively()
    }

    // ------------------------------------------------------------------
    // Colour filters
    // ------------------------------------------------------------------

    fun applyFilter(bitmap: Bitmap, filter: ScanFilter): Bitmap {
        if (filter == ScanFilter.NONE) return bitmap
        return when (filter) {
            ScanFilter.MAGIC_COLOR -> applyColorMatrix(bitmap, magicColorMatrix())
            ScanFilter.GRAYSCALE -> applyColorMatrix(bitmap, ColorMatrix().apply { setSaturation(0f) })
            ScanFilter.BLACK_AND_WHITE -> adaptiveThreshold(bitmap)
            ScanFilter.HIGHPASS -> highpassDodge(bitmap)
            ScanFilter.NONE -> bitmap
        }
    }

    private fun magicColorMatrix(): ColorMatrix {
        // Mild saturation lift + contrast/brightness punch for "magic colour" scans.
        val saturation = ColorMatrix().apply { setSaturation(1.32f) }
        val contrast = 1.18f
        val lift = 6f
        val punch = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, lift,
                0f, contrast, 0f, 0f, lift,
                0f, 0f, contrast, 0f, lift,
                0f, 0f, 0f, 1f, 0f
            )
        )
        saturation.postConcat(punch)
        return saturation
    }

    private fun applyColorMatrix(bitmap: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = android.graphics.ColorMatrixColorFilter(matrix)
            isFilterBitmap = true
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return out
    }

    /**
     * Local (adaptive) threshold: compares every pixel against the mean luminance
     * of its neighbourhood, which whites-out uneven lighting and keeps ink crisp.
     */
    private fun adaptiveThreshold(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h) { luminance(px[it]) }
        // Box blur approximated by a downsample/upsample round trip.
        val blurred = boxBlur(gray, w, h, radius = max(w, h) / 24)

        val out = IntArray(w * h)
        for (i in px.indices) {
            val threshold = (blurred[i] - 14).coerceAtLeast(60)
            val g = gray[i]
            val value = if (g < threshold) 18 else 255
            out[i] = (0xFF shl 24) or (value shl 16) or (value shl 8) or value
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /**
     * "Lighten background": dodges the image with a heavily blurred copy so dark
     * text stays readable while shaded paper becomes near-white.
     */
    private fun highpassDodge(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)

        val gray = IntArray(w * h) { luminance(px[it]) }
        val blurred = boxBlur(gray, w, h, radius = max(w, h) / 16)

        val out = IntArray(w * h)
        for (i in px.indices) {
            val dodge = 255 - blurred[i]
            val r = (Color.red(px[i]) + dodge).coerceIn(0, 255)
            val g = (Color.green(px[i]) + dodge).coerceIn(0, 255)
            val b = (Color.blue(px[i]) + dodge).coerceIn(0, 255)
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    /** Cheap separable box blur over an [IntArray] of luminance values. */
    private fun boxBlur(src: IntArray, w: Int, h: Int, radius: Int): IntArray {
        val r = radius.coerceAtLeast(1)
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            var sum = 0
            val row = y * w
            for (x in -r until w + r) {
                val cx = x.coerceIn(0, w - 1)
                sum += src[row + cx]
                if (x >= r) {
                    val left = (x - 2 * r).coerceIn(0, w - 1)
                    sum -= src[row + left]
                    tmp[row + x - r] = sum / (2 * r + 1)
                }
            }
        }
        // Vertical pass
        for (x in 0 until w) {
            var sum = 0
            for (y in -r until h + r) {
                val cy = y.coerceIn(0, h - 1)
                sum += tmp[cy * w + x]
                if (y >= r) {
                    val top = (y - 2 * r).coerceIn(0, h - 1)
                    sum -= tmp[top * w + x]
                    out[(y - r) * w + x] = sum / (2 * r + 1)
                }
            }
        }
        return out
    }

    private fun luminance(color: Int): Int {
        return (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)).toInt()
    }

    // ------------------------------------------------------------------
    // Sharpening
    // ------------------------------------------------------------------

    /** Classic 3x3 unsharp mask: out = src + amount * (src - blurred). */
    private fun unsharpMask(bitmap: Bitmap, amount: Float = 0.55f): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 4 || h < 4) return bitmap

        val src = IntArray(w * h)
        bitmap.getPixels(src, 0, w, 0, 0, w, h)
        val gray = IntArray(w * h) { luminance(src[it]) }

        // Light blur via averaging the 4-neighbourhood.
        val blurred = IntArray(w * h)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                blurred[i] = (gray[i - 1] + gray[i + 1] + gray[i - w] + gray[i + w] + gray[i]) / 5
            }
        }

        val out = IntArray(w * h)
        for (i in src.indices) {
            val mask = (gray[i] - blurred[i]) * amount
            val r = (Color.red(src[i]) + mask).coerceIn(0f, 255f).toInt()
            val g = (Color.green(src[i]) + mask).coerceIn(0f, 255f).toInt()
            val b = (Color.blue(src[i]) + mask).coerceIn(0f, 255f).toInt()
            out[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, w, 0, 0, w, h)
        return result
    }

    // ------------------------------------------------------------------
    // Auto crop + perspective correction
    // ------------------------------------------------------------------

    private const val DETECT_MAX_DIM = 500

    /**
     * Finds the document quadrilateral with a Sobel edge sweep and resamples it
     * into a head-on rectangle using bilinear warping.
     */
    fun cropToDocument(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 100 || h < 100) return null

        val small = Bitmap.createScaledBitmap(
            bitmap,
            DETECT_MAX_DIM,
            (h.toFloat() / w * DETECT_MAX_DIM).toInt().coerceAtLeast(1),
            true
        )
        val sw = small.width
        val sh = small.height

        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycleSafe()

        // Sobel gradient magnitude.
        val gray = IntArray(sw * sh) { luminance(px[it]) }
        val mag = IntArray(sw * sh)
        var maxMag = 0
        for (y in 1 until sh - 1) {
            for (x in 1 until sw - 1) {
                val i = y * sw + x
                val gx = -gray[i - sw - 1] - 2 * gray[i - 1] - gray[i + sw - 1] +
                        gray[i - sw + 1] + 2 * gray[i + 1] + gray[i + sw + 1]
                val gy = -gray[i - sw - 1] - 2 * gray[i - sw] - gray[i - sw + 1] +
                        gray[i + sw - 1] + 2 * gray[i + sw] + gray[i + sw + 1]
                val m = hypot(gx, gy).toInt()
                mag[i] = m
                if (m > maxMag) maxMag = m
            }
        }
        if (maxMag < 40) return null

        val threshold = (maxMag * 0.32f).toInt()
        val sx = sw.toFloat() / w
        val sy = sh.toFloat() / h

        // Extreme-corner heuristic on strong edge pixels.
        var tl = floatArrayOf(sw.toFloat(), sh.toFloat())
        var tr = floatArrayOf(0f, sh.toFloat())
        var br = floatArrayOf(0f, 0f)
        var bl = floatArrayOf(sw.toFloat(), 0f)
        var minSum = Float.MAX_VALUE
        var maxDiff = Float.MIN_VALUE
        var maxSum = Float.MIN_VALUE
        var minDiff = Float.MAX_VALUE
        var edgeCount = 0

        for (i in mag.indices) {
            if (mag[i] < threshold) continue
            edgeCount++
            val x = i % sw
            val y = i / sw
            val sum = x + y
            val diff = x - y
            if (sum < minSum) { minSum = sum; tl = floatArrayOf(x.toFloat(), y.toFloat()) }
            if (diff > maxDiff) { maxDiff = diff; tr = floatArrayOf(x.toFloat(), y.toFloat()) }
            if (sum > maxSum) { maxSum = sum; br = floatArrayOf(x.toFloat(), y.toFloat()) }
            if (diff < minDiff) { minDiff = diff; bl = floatArrayOf(x.toFloat(), y.toFloat()) }
        }

        if (edgeCount < 16) return null

        // Reject degenerate quads (document does not occupy enough of the frame).
        val quadArea = quadrilateralArea(tl, tr, br, bl)
        val frameArea = (sw * sh).toFloat()
        if (quadArea < frameArea * 0.18f) return null

        // Scale corners back to full-resolution coordinates.
        val fullTl = floatArrayOf(tl[0] / sx, tl[1] / sy)
        val fullTr = floatArrayOf(tr[0] / sx, tr[1] / sy)
        val fullBr = floatArrayOf(br[0] / sx, br[1] / sy)
        val fullBl = floatArrayOf(bl[0] / sx, bl[1] / sy)

        return warpToRectangle(bitmap, fullTl, fullTr, fullBr, fullBl)
    }

    private fun quadrilateralArea(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray): Float {
        fun triArea(p1: FloatArray, p2: FloatArray, p3: FloatArray): Float {
            return abs(
                (p1[0] * (p2[1] - p3[1]) + p2[0] * (p3[1] - p1[1]) + p3[0] * (p1[1] - p2[1])) / 2f
            )
        }
        return triArea(a, b, c) + triArea(a, c, d)
    }

    /**
     * Bilinear warp of the source bitmap bounded by [tl],[tr],[br],[bl] into a
     * clean rectangle. Padding is added so edges are never clipped.
     */
    private fun warpToRectangle(
        bitmap: Bitmap,
        tl: FloatArray, tr: FloatArray, br: FloatArray, bl: FloatArray
    ): Bitmap {
        val pad = 6f
        val pTl = floatArrayOf(tl[0] - pad, tl[1] - pad)
        val pTr = floatArrayOf(tr[0] + pad, tr[1] - pad)
        val pBr = floatArrayOf(br[0] + pad, br[1] + pad)
        val pBl = floatArrayOf(bl[0] - pad, bl[1] + pad)

        val topW = hypot(pTr[0] - pTl[0], pTr[1] - pTl[1])
        val botW = hypot(pBr[0] - pBl[0], pBr[1] - pBl[1])
        val leftH = hypot(pBl[0] - pTl[0], pBl[1] - pTl[1])
        val rightH = hypot(pBr[0] - pTr[0], pBr[1] - pTr[1])

        val outW = min(((topW + botW) / 2f).toInt(), bitmap.width)
        val outH = min(((leftH + rightH) / 2f).toInt(), bitmap.height)
        if (outW < 80 || outH < 80) return bitmap

        val srcW = bitmap.width
        val srcH = bitmap.height
        val srcPx = IntArray(srcW * srcH)
        bitmap.getPixels(srcPx, 0, srcW, 0, 0, srcW, srcH)

        val out = IntArray(outW * outH)
        for (y in 0 until outH) {
            val v = y / (outH - 1).toFloat()
            // Left and right edges interpolated by [v], then swept across by [u].
            val leftX = pTl[0] + (pBl[0] - pTl[0]) * v
            val leftY = pTl[1] + (pBl[1] - pTl[1]) * v
            val rightX = pTr[0] + (pBr[0] - pTr[0]) * v
            val rightY = pTr[1] + (pBr[1] - pTr[1]) * v

            for (x in 0 until outW) {
                val u = x / (outW - 1).toFloat()
                val sx = leftX + (rightX - leftX) * u
                val sy = leftY + (rightY - leftY) * u
                out[y * outW + x] = sampleBilinear(srcPx, srcW, srcH, sx, sy)
            }
        }

        val result = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        result.setPixels(out, 0, outW, 0, 0, outW, outH)
        return result
    }

    private fun sampleBilinear(px: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val fx = x.coerceIn(0f, (w - 1).toFloat())
        val fy = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = fx.toInt()
        val y0 = fy.toInt()
        val x1 = (x0 + 1).coerceAtMost(w - 1)
        val y1 = (y0 + 1).coerceAtMost(h - 1)
        val dx = fx - x0
        val dy = fy - y0

        val c00 = px[y0 * w + x0]
        val c01 = px[y0 * w + x1]
        val c10 = px[y1 * w + x0]
        val c11 = px[y1 * w + x1]

        val r = lerp(lerp(Color.red(c00), Color.red(c01), dx), lerp(Color.red(c10), Color.red(c11), dx), dy)
        val g = lerp(lerp(Color.green(c00), Color.green(c01), dx), lerp(Color.green(c10), Color.green(c11), dx), dy)
        val b = lerp(lerp(Color.blue(c00), Color.blue(c01), dx), lerp(Color.blue(c10), Color.blue(c11), dx), dy)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun lerp(a: Int, b: Int, t: Float): Int = (a + (b - a) * t).toInt()

    // ------------------------------------------------------------------
    // Auto deskew
    // ------------------------------------------------------------------

    private const val DESKEW_MAX_DIM = 320
    private const val DESKEW_RANGE_DEG = 12

    /**
     * Estimates the page skew from horizontal projection profiles of the ink and
     * rotates the bitmap back to level. Returns null when the page looks flat.
     */
    fun deskew(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        if (w < 80 || h < 80) return null

        val small = Bitmap.createScaledBitmap(
            bitmap,
            DESKEW_MAX_DIM,
            (h.toFloat() / w * DESKEW_MAX_DIM).toInt().coerceAtLeast(1),
            true
        )
        val sw = small.width
        val sh = small.height
        val px = IntArray(sw * sh)
        small.getPixels(px, 0, sw, 0, 0, sw, sh)
        small.recycleSafe()

        // Otsu-style global threshold on luminance to isolate ink.
        val gray = IntArray(sw * sh) { luminance(px[it]) }
        val meanLuma = gray.average().toInt()
        val inkPoints = ArrayList<Int>(gray.size / 10)
        gray.forEachIndexed { i, v -> if (v < meanLuma - 24) inkPoints.add(i) }

        if (inkPoints.size < 24) return null

        val cx = sw / 2f
        val cy = sh / 2f

        // Coarse sweep at 1 degree, then refine at 0.1 degree around the winner.
        var bestAngle = 0f
        var bestScore = projectionScore(inkPoints, sw, sh, cx, cy, 0f)

        for (deg in 1..DESKEW_RANGE_DEG) {
            for (sign in listOf(1f, -1f)) {
                val angle = deg * sign
                val score = projectionScore(inkPoints, sw, sh, cx, cy, angle)
                if (score > bestScore) {
                    bestScore = score
                    bestAngle = angle
                }
            }
        }

        var refined = bestAngle
        var refinedScore = bestScore
        var step = 0.5f
        while (step > 0.05f) {
            var improved = false
            for (delta in listOf(step, -step)) {
                val candidate = bestAngle + delta
                if (abs(candidate) > DESKEW_RANGE_DEG + 2f) continue
                val score = projectionScore(inkPoints, sw, sh, cx, cy, candidate)
                if (score > refinedScore) {
                    refinedScore = score
                    refined = candidate
                    improved = true
                }
            }
            if (!improved) step /= 2f
            bestAngle = refined
        }

        if (abs(refined) < 0.12f) return null

        val matrix = Matrix().apply {
            postRotate(refined, w / 2f, h / 2f)
        }
        // Rotate into a canvas large enough to hold the rotated page, paper-white.
        val bounds = FloatArray(8)
        matrix.mapPoints(bounds, floatArrayOf(0f, 0f, w.toFloat(), 0f, w.toFloat(), h.toFloat(), 0f, h.toFloat()))
        val outW = (max(bounds[0], max(bounds[2], max(bounds[4], bounds[6]))) -
                min(bounds[0], min(bounds[2], min(bounds[4], bounds[6])))).toInt()
        val outH = (max(bounds[1], max(bounds[3], max(bounds[5], bounds[7]))) -
                min(bounds[1], min(bounds[3], min(bounds[5], bounds[7])))).toInt()

        val rotated = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(rotated)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(bitmap, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return rotated
    }

    /**
     * Sharpness metric for a candidate rotation: the sum of squared ink counts per
     * projection row, normalised by the number of occupied rows.
     */
    private fun projectionScore(
        inkPoints: List<Int>,
        sw: Int,
        sh: Int,
        cx: Float,
        cy: Float,
        angleDeg: Float
    ): Float {
        val rad = Math.toRadians(angleDeg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val halfDiag = hypot(sw, sh) / 2f
        val rows = (halfDiag * 2).toInt() + 2

        val buckets = IntArray(rows)
        var used = 0
        for (i in inkPoints) {
            val x = i % sw - cx
            val y = i / sw - cy
            val ry = x * sinA + y * cosA + halfDiag
            val bucket = ry.toInt().coerceIn(0, rows - 1)
            if (buckets[bucket] == 0) used++
            buckets[bucket]++
        }
        if (used == 0) return 0f
        var sumSquares = 0L
        for (count in buckets) sumSquares += count.toLong() * count
        return sumSquares.toFloat() / used
    }

    // ------------------------------------------------------------------
    // Misc helpers
    // ------------------------------------------------------------------

    private fun Bitmap?.recycleSafe() {
        if (this != null && !isRecycled) recycle()
    }
}
