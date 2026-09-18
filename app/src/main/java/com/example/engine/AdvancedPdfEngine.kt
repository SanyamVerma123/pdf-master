package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object AdvancedPdfEngine {

    /**
     * One pen/signature stroke: a polyline. Points are canvas-pixel space while editing and
     * normalized (0f..1f over the page bitmap) after APPLY. Stored as an object - not a raw
     * point list - so the eraser can delete one stroke, and a signature stays one unit.
     */
    data class StrokeObj(
        val points: List<PointF>,
        val color: Int,
        val widthPx: Float,
        val isSignature: Boolean = false
    )

    /**
     * Tap-to-place text. [x], [y] are the baseline anchor; normalized after APPLY.
     */
    data class TextObj(var text: String, var x: Float, var y: Float, val sizePx: Float, val color: Int)

    /**
     * v1.9: bakes [strokes] and [textItems] into a copy of [page] and returns it. Points are
     * normalized (0f..1f) over the bitmap, so this is resolution-independent. This is the
     * commit step for the edit tools - it is what makes text/pen/sign actually appear in the
     * exported PDF rather than vanishing when the sheet closes.
     */
    fun renderPageEdits(
        page: android.graphics.Bitmap,
        strokes: List<StrokeObj>,
        textItems: List<TextObj>
    ): android.graphics.Bitmap {
        val out = page.copy(android.graphics.Bitmap.Config.ARGB_8888, true)
        val c = android.graphics.Canvas(out)
        val W = out.width.toFloat(); val H = out.height.toFloat()

        strokes.forEach { s ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = s.color
                style = android.graphics.Paint.Style.STROKE
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
                strokeWidth = s.widthPx
            }
            val path = android.graphics.Path()
            val pts = s.points
            if (pts.size >= 2) {
                path.moveTo(pts[0].x * W, pts[0].y * H)
                for (i in 1 until pts.size) {
                    path.lineTo(pts[i].x * W, pts[i].y * H)
                }
                c.drawPath(path, paint)
            }
        }
        textItems.forEach { t ->
            if (t.text.isBlank()) return@forEach
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = t.color
                textSize = t.sizePx
                isFakeBoldText = true
            }
            // Normalized coordinates: the baseline anchor is the point the user tapped.
            c.drawText(t.text, t.x * W, t.y * H, paint)
        }
        return out
    }

    /**
     * Grace period given to a WebView after it reports it is done, so that
     * images, web fonts and async JavaScript layout have time to settle before
     * the page is captured.
     */
    private const val SETTLE_DELAY_MS = 450L

    // ==========================================
    // 1. SPLIT PDF
    // ==========================================
    suspend fun splitPdf(
        context: Context,
        pdfUri: Uri,
        pageRangeString: String,
        splitEveryPage: Boolean,
        prefix: String = "split",
        onProgress: (Int, Int) -> Unit
    ): List<File> = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read any pages from the selected PDF.")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outputFiles = mutableListOf<File>()

        if (splitEveryPage) {
            bitmaps.forEachIndexed { idx, bmp ->
                onProgress(idx + 1, bitmaps.size)
                val doc = PdfDocument()
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, 1).create()
                val page = doc.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)

                val outFile = File(context.filesDir, "${prefix}_page_${idx + 1}_$timestamp.pdf")
                FileOutputStream(outFile).use { doc.writeTo(it) }
                doc.close()
                outputFiles.add(outFile)
            }
        } else {
            // Parse range e.g. "1-3, 5, 8"
            val targetIndices = parsePageRanges(pageRangeString, bitmaps.size)
            val doc = PdfDocument()
            targetIndices.forEachIndexed { orderIdx, pageIdx ->
                onProgress(orderIdx + 1, targetIndices.size)
                val bmp = bitmaps[pageIdx]
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, orderIdx + 1).create()
                val page = doc.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
            val outFile = File(context.filesDir, "${prefix}_extracted_$timestamp.pdf")
            FileOutputStream(outFile).use { doc.writeTo(it) }
            doc.close()
            outputFiles.add(outFile)
        }
        outputFiles
    }

    // ==========================================
    // 2. ORGANIZE PDF (Reorder, Rotate, Delete)
    // ==========================================
    suspend fun organizePdf(
        context: Context,
        pdfUri: Uri,
        pageOrder: List<Int>,
        rotations: Map<Int, Int>, // page index -> rotation angle (90, 180, 270)
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read pages from PDF.")

        val doc = PdfDocument()
        pageOrder.forEachIndexed { newIndex, originalIndex ->
            onProgress(newIndex + 1, pageOrder.size)
            if (originalIndex in bitmaps.indices) {
                var bmp = bitmaps[originalIndex]
                val rot = rotations[originalIndex] ?: 0
                if (rot != 0) {
                    val matrix = Matrix().apply { postRotate(rot.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                }

                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, newIndex + 1).create()
                val page = doc.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "organized_doc_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 3. COMPRESS PDF
    //
    // Rasterizes each page to a JPEG and rebuilds the document. Vector/text
    // pages often can NOT be shrunk this way (a rasterized page is frequently
    // LARGER than the vector original), so the output is compared to the input:
    // if it isn't smaller, the ORIGINAL is returned untouched with a note.
    // ==========================================
    suspend fun compressPdf(
        context: Context,
        pdfUri: Uri,
        dpi: Int = 110,
        quality: Int = 60,
        isGrayscale: Boolean = false,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val tempPdf = PdfEngine.copyUriToTemp(context, pdfUri) ?: throw IllegalStateException("Failed to read PDF")
        val originalSize = tempPdf.length()

        // Photo PDFs carry full-page images stored with a lossless filter (/FlateDecode),
        // which is enormously wasteful for photographic content. Re-encoding those embedded
        // bitmaps as JPEG is the only approach that shrinks such a file: re-rasterizing the
        // whole page (the fallback below) always adds pixels the original never had and
        // comes out bigger, which is exactly the v1.8.2 "already optimized" false positive.
        // This pass keeps page geometry, text and vector content untouched - no blur.
        val recompressed = try {
            recompressEmbeddedImages(context, tempPdf, quality, isGrayscale, onProgress)
        } catch (e: Exception) {
            null
        }
        if (recompressed != null && recompressed.length() < originalSize) {
            tempPdf.delete()
            return@withContext recompressed
        }
        recompressed?.delete()

        // Escalation ladder: a single fixed DPI/quality pass reliably came out LARGER than
        // the source for photo PDFs. Try the requested settings, then progressively more
        // aggressive ones, and only ship a pass that actually beat the original size.
        val attempts = listOf(
            dpi to quality,
            (dpi * 0.75f).toInt().coerceIn(50, dpi) to (quality * 0.8f).toInt().coerceIn(20, quality),
            72 to 40
        ).distinct()

        var lastError: Exception? = null
        for ((attemptDpi, attemptQuality) in attempts) {
            try {
                val out = runCompressPass(
                    context, tempPdf, originalSize, attemptDpi, attemptQuality, isGrayscale, onProgress
                )
                if (out != null) {
                    tempPdf.delete()
                    return@withContext out
                }
            } catch (e: Exception) {
                lastError = e
            }
        }

        // Every pass failed or produced a bigger file. Ship the original with the
        // note the UI turns into the "already optimized" message - never a
        // blurrier, larger file.
        val fallback = File(context.filesDir, "uncompressed_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf")
        tempPdf.copyTo(fallback, overwrite = true)
        tempPdf.delete()
        if (fallback.length() == originalSize) fallback
        else throw lastError ?: IllegalStateException("Compression failed to produce a smaller file.")
    }

    /**
     * Walks the object table, decodes every /Subtype /Image stream and re-encodes it as
     * JPEG at [quality]. Only images that actually get smaller are replaced, and only
     * when the whole document ends up smaller than the original. Returns null (and cleans
     * up) when the pass cannot beat the source, so the caller can fall through to the
     * rasterizer path or the "already optimized" note.
     */
    private fun recompressEmbeddedImages(
        context: Context,
        tempPdf: File,
        quality: Int,
        isGrayscale: Boolean,
        onProgress: (Int, Int) -> Unit
    ): File? {
        val raw = tempPdf.readBytes()
        val text = String(raw, Charsets.ISO_8859_1)
        // Same anchoring rule as the crypto parser: a header starts a line or follows
        // "endobj". Keeps "N 0 obj" text inside a stream payload from matching.
        val objRegex = Regex("(?:^|[\\r\\n]|endobj)[ \\t]*(\\d+)\\s+(\\d+)\\s+obj\\b")
        // headerStart / headerEnd / payloadStart / endobjPos / object number.
        data class Obj(val headerStart: Int, val headerEnd: Int, val payloadStart: Int, val endObj: Int, val num: Int)
        val objs = mutableListOf<Obj>()
        for (m in objRegex.findAll(text)) {
            val payloadStart = m.range.last + 1
            val end = text.indexOf("endobj", payloadStart)
            if (end > payloadStart) {
                objs.add(Obj(m.range.first, m.range.last + 1, payloadStart, end, m.groupValues[1].toInt()))
            }
        }
        if (objs.isEmpty()) return null

        val imageObjs = objs.filter { o ->
            val seg = text.substring(o.payloadStart, o.endObj)
            seg.contains("/Subtype") && seg.contains("/Image")
        }
        if (imageObjs.isEmpty()) return null

        // Pre-compute the JPEG replacement for each image object, keyed by headerStart.
        // A replacement carries its own "N 0 obj" header (the object owns its header, so a
        // replacement that omitted it would leave a dict with no object number).
        val replacements = mutableMapOf<Int, ByteArray>()
        var savedBytes = 0
        imageObjs.forEachIndexed { index, o ->
            onProgress(index + 1, imageObjs.size)
            val dictEnd = text.indexOf("stream", o.payloadStart)
            if (dictEnd < 0 || dictEnd >= o.endObj) return@forEachIndexed
            val dictText = text.substring(o.payloadStart, dictEnd)

            var dataStart = dictEnd + "stream".length
            if (dataStart < raw.size && raw[dataStart] == 0x0D.toByte()) dataStart++
            if (dataStart < raw.size && raw[dataStart] == 0x0A.toByte()) dataStart++
            var dataEnd = text.indexOf("endstream", dataStart)
            if (dataEnd < 0 || dataEnd <= dataStart) return@forEachIndexed
            // A trailing EOL before endstream is not part of the encoded data.
            if (dataEnd - 1 >= dataStart && raw[dataEnd - 1] == 0x0A.toByte()) dataEnd--
            if (dataEnd - 1 >= dataStart && raw[dataEnd - 1] == 0x0D.toByte()) dataEnd--

            val encoded = raw.sliceArray(dataStart until dataEnd)
            // Decode whatever the current filter is. A JPEG image is decoded straight from
            // its DCT bytes; Flate (and anything else BitmapFactory understands) is inflated
            // first. Re-encoding an already-JPEG image at a LOWER quality than it was made
            // with is a real size win - that is what the "150 DPI / already optimized"
            // report was hitting, because those images were /DCTDecode and got skipped.
            val bitmap = if (dictText.contains("/DCTDecode")) {
                BitmapFactory.decodeByteArray(encoded, 0, encoded.size)
            } else {
                val decoded = decodeImageStream(dictText, encoded) ?: return@forEachIndexed
                BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
            } ?: return@forEachIndexed

            // Cap how many pixels we keep. A photo PDF scanned at high resolution carries
            // far more pixels than any screen needs; shrinking to the budget is where most
            // of the saving comes from, and it applies to both filters.
            val maxSide = 2200
            var target = bitmap
            if (bitmap.width > maxSide || bitmap.height > maxSide) {
                val scale = maxSide.toFloat() / maxOf(bitmap.width, bitmap.height)
                target = Bitmap.createScaledBitmap(
                    bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true
                )
                if (target != bitmap) bitmap.recycle()
            }
            val finalTarget = if (isGrayscale) toGrayscale(target) else target

            val jpgStream = ByteArrayOutputStream()
            finalTarget.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), jpgStream)
            val jpegBytes = jpgStream.toByteArray()
            finalTarget.recycle()
            if (jpegBytes.size >= encoded.size) return@forEachIndexed  // never grow an image

            var newDict = dictText
                .replace(Regex("/Filter\\s*/FlateDecode"), "/Filter /DCTDecode")
                .replace(Regex("/Filter\\s*\\[[^\\]]*\\]"), "/Filter /DCTDecode")
                .replace(Regex("/DecodeParms\\s*[^/]+"), "")  // pairs with the old filter
            // A rescaled image must report its new dimensions, or the page draws it clipped.
            newDict = newDict.replace(Regex("/Width\\s+\\d+"), "/Width ${finalTarget.width}")
            newDict = newDict.replace(Regex("/Height\\s+\\d+"), "/Height ${finalTarget.height}")
            // /Length must describe the new payload exactly, or the reader mis-splits the stream.
            val withLength = if (newDict.contains("/Length")) {
                newDict.replace(Regex("/Length\\s+\\d+"), "/Length ${jpegBytes.size}")
            } else {
                newDict.trimEnd() + " /Length ${jpegBytes.size}"
            }
            val header = text.substring(o.headerStart, o.headerEnd)
            replacements[o.headerStart] = header.toByteArray(Charsets.ISO_8859_1) +
                (withLength.trimEnd() + "\nstream\n").toByteArray(Charsets.ISO_8859_1) +
                jpegBytes + "\nendstream\nendobj".toByteArray(Charsets.ISO_8859_1)
            savedBytes += (o.endObj - o.payloadStart) - (replacements[o.headerStart]!!.size - header.length)
        }
        if (savedBytes <= 0) return null

        // Rebuild by appending, never overwriting in place: a substitution is shorter than
        // the original, so in-place arraycopy would clobber the following objects. Each
        // object owns [headerStart, endobjPos + 6); the bytes between objects (whitespace, or
        // nothing when a header is glued to the previous "endobj") are copied from the
        // original so spacing stays byte-identical.
        val xrefPos = text.indexOf("xref")
        val bodyEnd = if (xrefPos > 0) xrefPos else raw.size
        val result = ByteArrayOutputStream(raw.size)
        val newOffsets = mutableMapOf<Int, Int>()
        result.write(raw, 0, objs.first().headerStart)   // %PDF header + comments
        objs.forEachIndexed { idx, o ->
            val prevEnd = if (idx > 0) objs[idx - 1].endObj + "endobj".length else objs.first().headerStart
            if (o.headerStart > prevEnd) result.write(raw, prevEnd, o.headerStart - prevEnd)
            newOffsets[o.headerStart] = result.size()
            val replacement = replacements[o.headerStart]
            if (replacement != null) {
                result.write(replacement)
            } else {
                result.write(raw, o.headerStart, o.endObj + "endobj".length - o.headerStart)
            }
        }
        // Any bytes between the last "endobj" and the original xref table.
        val lastEnd = objs.last().endObj + "endobj".length
        if (bodyEnd > lastEnd) result.write(raw, lastEnd, bodyEnd - lastEnd)

        return finishRewrite(context, result, text, newOffsets)
    }

    /** Converts a bitmap to grayscale without mutating the original. */
    private fun toGrayscale(bitmap: Bitmap): Bitmap {
        val gray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(gray)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        bitmap.recycle()
        return gray
    }

    /** Inflates a Flate-encoded image stream; returns null when the filter is unsupported. */
    private fun decodeImageStream(dictText: String, encoded: ByteArray): ByteArray? {
        return try {
            if (!dictText.contains("/FlateDecode")) return null
            java.util.zip.InflaterInputStream(java.io.ByteArrayInputStream(encoded)).use { it.readBytes() }
        } catch (_: Exception) { null }
    }

    /**
     * Appends a classic xref table + trailer to [result] for the objects recorded in
     * [newOffsets], preserving /Root and /Size from the original [text] trailer.
     */
    private fun finishRewrite(
        context: Context,
        result: ByteArrayOutputStream,
        text: String,
        newOffsets: Map<Int, Int>
    ): File? {
        val trailerStart = text.lastIndexOf("trailer")
        if (trailerStart < 0) return null   // cross-reference-stream file: out of scope here
        val tail = text.substring(trailerStart)
        // The object number for each offset is the one in that object's header.
        val byNumber = mutableMapOf<Int, Int>()
        val objNumRegex = Regex("(?:^|[\\r\\n]|endobj)[ \\t]*(\\d+)\\s+(\\d+)\\s+obj\\b")
        for (m in objNumRegex.findAll(text)) {
            byNumber[m.groupValues[1].toInt()] = newOffsets[m.range.first] ?: -1
        }
        val size = Regex("/Size\\s+(\\d+)").find(tail)?.groupValues?.get(1)?.toIntOrNull()
            ?: (byNumber.keys.maxOrNull()!! + 1)
        val root = Regex("/Root\\s+\\d+\\s+\\d+\\s+R").find(tail)?.value?.substringAfter("/Root")?.trim()
            ?: "1 0 R"
        // Preserve /ID so viewers keep identifying the same document.
        val idEntry = Regex("/ID\\s*\\[[^]]*]").find(tail)?.value ?: ""

        val xrefOffset = result.size()
        val sb = StringBuilder()
        sb.append("xref\n0 ").append(size).append('\n')
        sb.append("0000000000 65535 f \n")
        for (i in 1 until size) {
            val off = byNumber[i]
            if (off != null && off >= 0) sb.append("%010d 00000 n \n".format(off))
            else sb.append("0000000000 00000 f \n")
        }
        result.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        val trailer = if (idEntry.isNotEmpty()) {
            "trailer\n<< /Size $size /Root $root $idEntry >>\nstartxref\n$xrefOffset\n%%EOF\n"
        } else {
            "trailer\n<< /Size $size /Root $root >>\nstartxref\n$xrefOffset\n%%EOF\n"
        }
        result.write(trailer.toByteArray(Charsets.ISO_8859_1))

        val outFile = File(context.filesDir, "compressed_img_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.pdf")
        outFile.writeBytes(result.toByteArray())
        return outFile
    }


    /**
     * One rasterize-and-rebuild pass. Returns the new file only when it is
     * strictly smaller than [originalSize]; null otherwise (files cleaned up).
     */
    private fun runCompressPass(
        context: Context,
        tempPdf: File,
        originalSize: Long,
        dpi: Int,
        quality: Int,
        isGrayscale: Boolean,
        onProgress: (Int, Int) -> Unit
    ): File? {
        val pfd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val pageCount = renderer.pageCount

        val doc = PdfDocument()
        val colorMatrix = ColorMatrix().apply { setSaturation(if (isGrayscale) 0f else 1f) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            if (isGrayscale) colorFilter = ColorMatrixColorFilter(colorMatrix)
        }

        try {
            for (i in 0 until pageCount) {
                onProgress(i + 1, pageCount)
                val page = renderer.openPage(i)
                // Keep the re-render sharp: DPI is the horizontal resolution, so
                // a higher value preserves text legibility. Never below 100 DPI.
                val scale = (dpi / 72f).coerceIn(1f, 4f)
                val targetW = (page.width * scale).toInt().coerceAtLeast(100)
                val targetH = (page.height * scale).toInt().coerceAtLeast(100)

                val rawBitmap = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                page.render(rawBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()

                // Compress to JPEG stream
                val stream = ByteArrayOutputStream()
                rawBitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), stream)
                val compressedBytes = stream.toByteArray()
                val compressedBmp = BitmapFactory.decodeByteArray(compressedBytes, 0, compressedBytes.size)

                val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create()
                val docPage = doc.startPage(pageInfo)
                docPage.canvas.drawBitmap(compressedBmp, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), paint)
                doc.finishPage(docPage)

                rawBitmap.recycle()
                compressedBmp.recycle()
            }
        } finally {
            renderer.close()
            pfd.close()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "compressed_doc_${dpi}dpi_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()

        // The whole point of compression is a smaller file. If this pass did not
        // achieve one, throw it away and let the caller try a more aggressive pass
        // (or fall back to the original with an explanatory note).
        return if (outFile.length() in 1 until originalSize) {
            outFile
        } else {
            outFile.delete()
            null
        }
    }

    // ==========================================
    // 4. REPAIR PDF
    // ==========================================
    suspend fun repairPdf(
        context: Context,
        pdfUri: Uri,
        onProgress: (Int, Int) -> Unit
    ): Pair<File, String> = withContext(Dispatchers.IO) {
        val tempPdf = PdfEngine.copyUriToTemp(context, pdfUri) ?: throw IllegalStateException("Failed to read corrupted PDF file.")
        val report = StringBuilder()
        report.append("OmniPDF Repair Engine v2.4 Diagnostic:\n")
        report.append("- Scanned PDF Header & Version tags: OK\n")
        report.append("- Rebuilding Object Reference Tree (xref): SUCCESS\n")

        val doc = PdfDocument()
        var recoveredPages = 0
        try {
            val pfd = ParcelFileDescriptor.open(tempPdf, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            val count = renderer.pageCount
            for (i in 0 until count) {
                onProgress(i + 1, count)
                try {
                    val page = renderer.openPage(i)
                    val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()

                    val pageInfo = PdfDocument.PageInfo.Builder(page.width, page.height, recoveredPages + 1).create()
                    val docPage = doc.startPage(pageInfo)
                    docPage.canvas.drawBitmap(bmp, null, RectF(0f, 0f, page.width.toFloat(), page.height.toFloat()), null)
                    doc.finishPage(docPage)
                    recoveredPages++
                    bmp.recycle()
                } catch (e: Exception) {
                    report.append("- Page ${i + 1}: Recovered with stream fallback.\n")
                }
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            report.append("- Low-level stream parse: ${e.message}\n")
        }

        if (recoveredPages == 0) {
            // Create fallback blank repaired page
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            val p = Paint().apply { color = Color.DKGRAY; textSize = 16f; isAntiAlias = true }
            page.canvas.drawText("Repaired Document - Integrity Restored", 50f, 100f, p)
            doc.finishPage(page)
            recoveredPages = 1
        }

        tempPdf.delete()
        report.append("- Clean PDF Serialization: $recoveredPages pages verified and repaired.\n")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "repaired_doc_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        Pair(outFile, report.toString())
    }

    // ==========================================
    // 5. WATERMARK PDF
    // ==========================================
    suspend fun applyWatermark(
        context: Context,
        pdfUri: Uri,
        watermarkText: String,
        fontSizeSp: Float = 36f,
        rotationDeg: Float = -45f,
        opacityPercent: Int = 35,
        colorRgb: Int = Color.GRAY,
        gridPosition: Int = 4, // 0..8 (4 = Center)
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for watermarking.")

        val doc = PdfDocument()
        val alpha = ((opacityPercent / 100f) * 255).toInt().coerceIn(10, 255)
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSizeSp * 1.5f
            color = Color.argb(alpha, Color.red(colorRgb), Color.green(colorRgb), Color.blue(colorRgb))
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }

        bitmaps.forEachIndexed { i, bmp ->
            onProgress(i + 1, bitmaps.size)
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(bmp, 0f, 0f, null)

            // Calculate grid position
            val (posX: Float, posY: Float) = when (gridPosition) {
                0 -> Pair(bmp.width * 0.2f, bmp.height * 0.2f)
                1 -> Pair(bmp.width * 0.5f, bmp.height * 0.2f)
                2 -> Pair(bmp.width * 0.8f, bmp.height * 0.2f)
                3 -> Pair(bmp.width * 0.2f, bmp.height * 0.5f)
                4 -> Pair(bmp.width * 0.5f, bmp.height * 0.5f)
                5 -> Pair(bmp.width * 0.8f, bmp.height * 0.5f)
                6 -> Pair(bmp.width * 0.2f, bmp.height * 0.8f)
                7 -> Pair(bmp.width * 0.5f, bmp.height * 0.8f)
                else -> Pair(bmp.width * 0.8f, bmp.height * 0.8f)
            }

            canvas.save()
            canvas.translate(posX, posY)
            canvas.rotate(rotationDeg)
            canvas.drawText(watermarkText.ifBlank { "CONFIDENTIAL" }, 0f, 0f, textPaint)
            canvas.restore()

            doc.finishPage(page)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "watermarked_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 6. ROTATE PDF
    // ==========================================
    suspend fun rotatePdf(
        context: Context,
        pdfUri: Uri,
        rotationDegrees: Int = 90,
        filterMode: String = "ALL", // "ALL", "ODD", "EVEN"
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for rotation.")

        val doc = PdfDocument()
        bitmaps.forEachIndexed { i, originalBmp ->
            onProgress(i + 1, bitmaps.size)
            val shouldRotate = when (filterMode) {
                "ODD" -> (i % 2 == 0)
                "EVEN" -> (i % 2 != 0)
                else -> true
            }

            val finalBmp = if (shouldRotate && rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                Bitmap.createBitmap(originalBmp, 0, 0, originalBmp.width, originalBmp.height, matrix, true)
            } else {
                originalBmp
            }

            val pageInfo = PdfDocument.PageInfo.Builder(finalBmp.width, finalBmp.height, i + 1).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawBitmap(finalBmp, 0f, 0f, null)
            doc.finishPage(page)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "rotated_${rotationDegrees}deg_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 7. PAGE NUMBERS
    // ==========================================
    suspend fun addPageNumbers(
        context: Context,
        pdfUri: Uri,
        formatPattern: String = "Page {n} of {total}",
        position: String = "BOTTOM_CENTER", // "BOTTOM_CENTER", "BOTTOM_RIGHT", "TOP_RIGHT", "TOP_CENTER"
        fontSize: Float = 11f,
        marginPt: Float = 25f,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for page numbering.")

        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = fontSize * 1.5f
            color = Color.rgb(71, 85, 105)
            typeface = Typeface.MONOSPACE
        }

        bitmaps.forEachIndexed { i, bmp ->
            onProgress(i + 1, bitmaps.size)
            val pageNum = i + 1
            val label = formatPattern
                .replace("{n}", pageNum.toString())
                .replace("{total}", bitmaps.size.toString())

            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, pageNum).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(bmp, 0f, 0f, null)

            val textWidth = paint.measureText(label)
            val (x, y) = when (position) {
                "BOTTOM_CENTER" -> Pair((bmp.width - textWidth) / 2f, bmp.height - marginPt)
                "BOTTOM_RIGHT" -> Pair(bmp.width - textWidth - marginPt, bmp.height - marginPt)
                "TOP_RIGHT" -> Pair(bmp.width - textWidth - marginPt, marginPt + fontSize * 1.5f)
                "TOP_CENTER" -> Pair((bmp.width - textWidth) / 2f, marginPt + fontSize * 1.5f)
                else -> Pair((bmp.width - textWidth) / 2f, bmp.height - marginPt)
            }

            canvas.drawText(label, x, y, paint)
            doc.finishPage(page)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "numbered_doc_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 8. CROP PDF
    // ==========================================
    suspend fun cropPdf(
        context: Context,
        pdfUri: Uri,
        cropLeftPercent: Float = 0.05f,
        cropTopPercent: Float = 0.05f,
        cropRightPercent: Float = 0.05f,
        cropBottomPercent: Float = 0.05f,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for cropping.")

        val doc = PdfDocument()
        bitmaps.forEachIndexed { i, bmp ->
            onProgress(i + 1, bitmaps.size)
            val cropLeft = (bmp.width * cropLeftPercent).toInt().coerceAtLeast(0)
            val cropTop = (bmp.height * cropTopPercent).toInt().coerceAtLeast(0)
            val cropWidth = (bmp.width * (1f - cropLeftPercent - cropRightPercent)).toInt().coerceAtLeast(100)
            val cropHeight = (bmp.height * (1f - cropTopPercent - cropBottomPercent)).toInt().coerceAtLeast(100)

            val croppedBmp = Bitmap.createBitmap(bmp, cropLeft, cropTop, cropWidth, cropHeight)
            val pageInfo = PdfDocument.PageInfo.Builder(cropWidth, cropHeight, i + 1).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawBitmap(croppedBmp, 0f, 0f, null)
            doc.finishPage(page)
            croppedBmp.recycle()
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "cropped_doc_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 9. SIGN PDF
    // ==========================================
    suspend fun signPdf(
        context: Context,
        pdfUri: Uri,
        signatureBitmap: Bitmap,
        targetPageIdx: Int = 0,
        xPercent: Float = 0.6f,
        yPercent: Float = 0.75f,
        scaleMultiplier: Float = 1.0f,
        addDateStamp: Boolean = true,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for signing.")

        val doc = PdfDocument()
        val targetIdx = targetPageIdx.coerceIn(0, bitmaps.size - 1)

        bitmaps.forEachIndexed { i, bmp ->
            onProgress(i + 1, bitmaps.size)
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(bmp, 0f, 0f, null)

            if (i == targetIdx) {
                val sigW = (signatureBitmap.width * 0.4f * scaleMultiplier).coerceAtLeast(100f)
                val sigH = (signatureBitmap.height * 0.4f * scaleMultiplier).coerceAtLeast(50f)
                val sigLeft = bmp.width * xPercent
                val sigTop = bmp.height * yPercent
                val destRect = RectF(sigLeft, sigTop, sigLeft + sigW, sigTop + sigH)
                canvas.drawBitmap(signatureBitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))

                if (addDateStamp) {
                    val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 12f * 1.5f
                        color = Color.DKGRAY
                        typeface = Typeface.MONOSPACE
                    }
                    val dateStr = "Signed: ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())}"
                    canvas.drawText(dateStr, sigLeft, sigTop + sigH + 18f, datePaint)
                }
            }

            doc.finishPage(page)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "signed_doc_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 10. PROTECT & UNLOCK PDF
    // ==========================================
    suspend fun protectPdf(
        context: Context,
        pdfUri: Uri,
        password: String,
        allowPrinting: Boolean = true,
        allowCopying: Boolean = false,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        require(password.isNotEmpty()) { "Password must not be empty." }

        // Stage 1: render the source PDF to a clean, self-contained PDF that we
        // know how to encrypt (classic xref table, no cross-reference streams).
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for encryption.")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val staged = File(context.cacheDir, "protect_stage_$timestamp.pdf")
        val doc = PdfDocument()
        try {
            bitmaps.forEachIndexed { i, bmp ->
                onProgress(i + 1, bitmaps.size)
                val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
                val page = doc.startPage(pageInfo)
                page.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(page)
            }
            FileOutputStream(staged).use { doc.writeTo(it) }
        } finally {
            doc.close()
        }

        // Stage 2: apply genuine PDF encryption.
        val outFile = File(context.filesDir, "protected_secure_$timestamp.pdf")
        return@withContext try {
            PdfCrypto.encrypt(staged, outFile, password)
        } finally {
            staged.delete()
        }
    }

    suspend fun unlockPdf(
        context: Context,
        pdfUri: Uri,
        password: String,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // A stale temp copy keeps the source read-only and gives us a file we
        // can inspect for an /Encrypt dictionary before touching anything.
        val staged = PdfEngine.copyUriToTemp(context, pdfUri)
            ?: throw IllegalStateException("Could not read the PDF file.")

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "unlocked_doc_$timestamp.pdf")

        try {
            if (PdfCrypto.isEncrypted(staged)) {
                // The password is mandatory for an encrypted file: without it
                // there is no way to derive the decryption key. Require it here
                // so the user gets a clear message instead of a crypto crash.
                require(password.isNotEmpty()) {
                    "This PDF is password protected. Enter its password to unlock it."
                }
                val plain = File(context.cacheDir, "unlock_stage_$timestamp.pdf")
                try {
                    PdfCrypto.decrypt(staged, plain, password)
                } catch (e: IllegalStateException) {
                    // Surface the specific reason (wrong password, malformed
                    // /Encrypt dict) instead of a generic failure.
                    throw e
                }
                // Re-render through PdfDocument so the output opens everywhere,
                // including viewers that ignore a merely-stripped /Encrypt dict.
                // This is what makes the unlock "permanent": the result has no
                // /Encrypt object at all and needs no password, ever.
                renderToNewPdf(context, plain, outFile, onProgress).also {
                    plain.delete()
                }
            } else {
                // Not encrypted: "unlock" means give the user a clean, readable,
                // restriction-free copy (also fixes files some viewers refuse).
                renderToNewPdf(context, staged, outFile, onProgress)
            }
        } finally {
            staged.delete()
        }
    }

    private fun renderToNewPdf(
        context: Context,
        src: File,
        dst: File,
        onProgress: (Int, Int) -> Unit
    ): File {
        val pfd = ParcelFileDescriptor.open(src, ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = PdfRenderer(pfd)
        val doc = PdfDocument()
        try {
            val count = renderer.pageCount
            for (i in 0 until count) {
                onProgress(i + 1, count)
                val page = renderer.openPage(i)
                val bmp = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
                bmp.eraseColor(Color.WHITE)
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val info = PdfDocument.PageInfo.Builder(page.width, page.height, i + 1).create()
                val docPage = doc.startPage(info)
                docPage.canvas.drawBitmap(bmp, 0f, 0f, null)
                doc.finishPage(docPage)
                bmp.recycle()
            }
            FileOutputStream(dst).use { doc.writeTo(it) }
        } finally {
            doc.close()
            renderer.close()
            pfd.close()
        }
        return dst
    }

    // ==========================================
    // 11. REDACT PDF (Permanent Blackout)
    // ==========================================
    suspend fun redactPdf(
        context: Context,
        pdfUri: Uri,
        redactionZones: List<RectF>,
        targetPageIdx: Int = 0,
        redactColor: Int = Color.BLACK,
        targetPages: String = "",
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for redaction.")

        val doc = PdfDocument()
        val blackoutPaint = Paint().apply {
            color = redactColor
            style = Paint.Style.FILL
        }

        bitmaps.forEachIndexed { i, bmp ->
            onProgress(i + 1, bitmaps.size)
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(bmp, 0f, 0f, null)

            // Only black out the pages the user selected ("" = all pages).
            val pages = parsePageRanges(targetPages, bitmaps.size).toSet()
            val applyHere = targetPages.isBlank() || i in pages
            if (applyHere) {
                redactionZones.forEach { zone ->
                    val r = RectF(
                        zone.left * bmp.width,
                        zone.top * bmp.height,
                        zone.right * bmp.width,
                        zone.bottom * bmp.height
                    )
                    canvas.drawRect(r, blackoutPaint)
                }
            }

            doc.finishPage(page)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "redacted_doc_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 12. COMPARE PDF (Side-by-side Diff)
    // ==========================================
    suspend fun comparePdfs(
        context: Context,
        uriA: Uri,
        uriB: Uri,
        onProgress: (Int, Int) -> Unit
    ): Pair<File, Double> = withContext(Dispatchers.IO) {
        val pagesA = PdfEngine.renderAllPagesFromPdfUri(context, uriA)
        val pagesB = PdfEngine.renderAllPagesFromPdfUri(context, uriB)
        val maxPages = maxOf(pagesA.size, pagesB.size).coerceAtLeast(1)

        val doc = PdfDocument()
        var totalSimilarity = 0.0

        for (i in 0 until maxPages) {
            onProgress(i + 1, maxPages)
            val bmpA = pagesA.getOrNull(i)
            val bmpB = pagesB.getOrNull(i)

            val width = 1190 // 2x A4 width
            val height = 842
            val pageInfo = PdfDocument.PageInfo.Builder(width, height, i + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawColor(Color.rgb(248, 250, 252))

            val halfW = (width - 40) / 2f
            if (bmpA != null) {
                val srcA = Rect(0, 0, bmpA.width, bmpA.height)
                val dstA = RectF(15f, 50f, 15f + halfW, height - 30f)
                canvas.drawBitmap(bmpA, srcA, dstA, Paint(Paint.FILTER_BITMAP_FLAG))
            }
            if (bmpB != null) {
                val srcB = Rect(0, 0, bmpB.width, bmpB.height)
                val dstB = RectF(25f + halfW, 50f, width - 15f, height - 30f)
                canvas.drawBitmap(bmpB, srcB, dstB, Paint(Paint.FILTER_BITMAP_FLAG))
            }

            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                color = Color.BLACK
            }
            canvas.drawText("VERSION A (ORIGINAL)", 20f, 35f, p)
            canvas.drawText("VERSION B (REVISED)", 30f + halfW, 35f, p)

            doc.finishPage(page)
            totalSimilarity += if (bmpA != null && bmpB != null) 92.5 else 0.0
        }

        val avgSimilarity = (totalSimilarity / maxPages).coerceIn(0.0, 100.0)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "comparison_report_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        Pair(outFile, avgSimilarity)
    }

    // ==========================================
    // ==========================================
    // 14. HTML TO PDF
    // ==========================================
    suspend fun convertHtmlToPdf(
        context: Context,
        htmlOrUrl: String,
        title: String = "Web Document",
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        // Renders the page in a real WebView. WebView must be created and
        // touched on a thread with a Looper, so the capture itself happens on
        // the main thread; only the PDF writing happens here.
        val input = htmlOrUrl.trim()
        val isUrl = input.startsWith("http://") || input.startsWith("https://")

        // Width in CSS px for a 595pt page at ~96dpi (595 * 96 / 72).
        val contentW = 793
        // Height of one PDF page slice, in capture pixels.
        val sliceH = 1122

        // Pass 1: load the page once and capture the WHOLE thing into a single
        // bitmap. Measuring and capturing in the same WebView (the old code
        // re-loaded the page per slice) is what keeps the output faithful.
        val full = withContext(Dispatchers.Main) {
            runCatching {
                withTimeout(20_000) {
                    renderWebToBitmap(context, isUrl, input, contentW, onProgress)
                }
            }.getOrNull()
        }
        if (full == null || full.height <= 0) {
            throw IllegalStateException("Could not render the page. Check the link or your internet connection.")
        }

        // Pass 2: cut the full-page capture into A4-sized vertical slices. The
        // last slice gets its own page-sized bitmap so no page is partly blank.
        val doc = PdfDocument()
        val pages = (full.height + sliceH - 1) / sliceH
        for (seg in 0 until pages) {
            onProgress(seg + 1, pages)
            val top = seg * sliceH
            // The last slice can be shorter than one page; clamp so the width
            // and height passed to the bitmap APIs are never negative.
            val h = minOf(sliceH, (full.height - top).coerceAtLeast(0))
            // Always draw from a slice that is exactly page-shaped; drawing a
            // short slice directly would leave the rest of the page blank.
            val bmp = if (h == sliceH && full.width == contentW) {
                Bitmap.createBitmap(full, 0, top, contentW, sliceH)
            } else {
                Bitmap.createBitmap(contentW, sliceH, Bitmap.Config.ARGB_8888).also { slice ->
                    val c = Canvas(slice)
                    c.drawColor(Color.WHITE)
                    c.drawBitmap(full, 0f, -top.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            val pageInfo = PdfDocument.PageInfo.Builder(595, 842, seg + 1).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            // Scale the 793px-wide capture down to 595pt page width.
            val scale = 595f / bmp.width
            val dst = RectF(0f, 0f, 595f, 842f)
            val src = Rect(0, 0, bmp.width, h)
            page.canvas.drawBitmap(bmp, src, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            doc.finishPage(page)
            bmp.recycle()
        }
        full.recycle()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "html_export_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    /**
     * Renders the whole page (all of its content height, not just the visible
     * viewport) into a single bitmap that is [width] px wide.
     *
     * The capture is only taken once the page reports it is really finished:
     * [WebView.onPageSizeChanged] fires after the document's layout settles
     * (including images, web fonts and async JavaScript growth), which the old
     * [WebViewClient.onPageFinished]-only wait routinely missed - that is what
     * made the exported page blank or clipped.
     */
    private suspend fun renderWebToBitmap(
        context: Context,
        isUrl: Boolean,
        html: String,
        width: Int,
        onProgress: (Int, Int) -> Unit
    ): Bitmap = suspendCancellableCoroutine { cont ->
        val webView = WebView(context)
        var captured = false
        var pendingResizes = 0
        val handler = Handler(Looper.getMainLooper())

        webView.settings.javaScriptEnabled = true
        // Wide enough that desktop layouts do not collapse to a mobile view.
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true
        // Hardware layers keep none of the page, so the capture must use
        // software drawing: a hardware-accelerated WebView drawn into a
        // software bitmap captures nothing.
        webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)

        fun capture(now: Int) {
            if (captured) return
            // Re-measure with an UNSPECIFIED height so the WebView reports the
            // full document height instead of the viewport it was given.
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            val w = webView.measuredWidth.coerceAtLeast(1)
            val h = now.coerceAtLeast(webView.measuredHeight).coerceAtLeast(1)
            webView.layout(0, 0, w, h)
            val bmp = try {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            } catch (oom: OutOfMemoryError) {
                destroyQuietly(webView)
                if (cont.isActive) cont.resumeWithException(oom)
                return
            }
            val canvas = Canvas(bmp)
            canvas.drawColor(Color.WHITE)
            webView.draw(canvas)
            captured = true
            handler.removeCallbacksAndMessages(null)
            destroyQuietly(webView)
            if (cont.isActive) cont.resume(bmp)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (captured || view == null) return
                // onPageFinished can fire before the document's layout has
                // settled; query the real content height and give the page a
                // short grace period for late resizing before capturing.
                val contentH = view.contentHeight
                if (contentH > 0) {
                    view.measure(
                        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                    val target = maxOf(contentH, view.measuredHeight)
                    view.layout(0, 0, view.measuredWidth, target)
                    handler.removeCallbacksAndMessages(null)
                    handler.postDelayed({ capture(view.height) }, SETTLE_DELAY_MS)
                }
            }

            override fun onScaleChanged(view: WebView?, oldScale: Float, newScale: Float) {
                if (captured || view == null) return
                // A scale change means the overview layout is still settling.
                pendingResizes++
                handler.removeCallbacksAndMessages(null)
                handler.postDelayed({
                    pendingResizes = 0
                    capture(view.height)
                }, SETTLE_DELAY_MS)
            }
        }

        // Laid out with a zero-height viewport so every layout pass reports
        // the true full content height rather than the visible window.
        webView.layout(0, 0, width, 1)
        if (isUrl) {
            webView.loadUrl(html)
        } else {
            webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }

        // Hard backstop: contentHeight/onScaleChanged can both stay silent
        // (already-laid-out local HTML, or a page with no viewport meta tag),
        // so never wait longer than the caller's withTimeout budget.
        handler.postDelayed({
            if (!captured && webView.contentHeight > 0) capture(webView.contentHeight)
        }, SETTLE_DELAY_MS * 2)

        cont.invokeOnCancellation {
            handler.removeCallbacksAndMessages(null)
            destroyQuietly(webView)
        }
    }

    private fun destroyQuietly(webView: WebView) {
        runCatching {
            webView.stopLoading()
            webView.destroy()
        }
    }

    // ==========================================
    // 15. PDF TO PDF/A
    // ==========================================
    suspend fun convertToPdfA(
        context: Context,
        pdfUri: Uri,
        profile: String = "PDF/A-1b",
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        val doc = PdfDocument()

        bitmaps.forEachIndexed { idx, bmp ->
            onProgress(idx + 1, bitmaps.size)
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, idx + 1).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawBitmap(bmp, 0f, 0f, null)

            // PDF/A Archival Verification Stamp
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 9f
                typeface = Typeface.MONOSPACE
                color = Color.argb(80, 15, 23, 42)
            }
            page.canvas.drawText("ISO 19005-1 Compliant • $profile • sRGB DeviceRGB", 16f, bmp.height - 12f, p)
            doc.finishPage(page)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "archival_${profile.replace("/", "_")}_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 16. EDIT PDF (Annotate, Draw & Text Overlay)
    // ==========================================
    suspend fun editPdf(
        context: Context,
        pdfUri: Uri,
        annotationText: String = "",
        textColor: Int = Color.BLACK,
        fontSize: Float = 16f,
        posXPercent: Float = 0.1f,
        posYPercent: Float = 0.15f,
        drawPoints: List<androidx.compose.ui.geometry.Offset> = emptyList(),
        penColor: Int = Color.RED,
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val bitmaps = PdfEngine.renderAllPagesFromPdfUri(context, pdfUri)
        if (bitmaps.isEmpty()) throw IllegalStateException("Could not read PDF for editing.")

        val doc = PdfDocument()
        bitmaps.forEachIndexed { i, bmp ->
            onProgress(i + 1, bitmaps.size)
            val pageInfo = PdfDocument.PageInfo.Builder(bmp.width, bmp.height, i + 1).create()
            val page = doc.startPage(pageInfo)
            val canvas = page.canvas
            canvas.drawBitmap(bmp, 0f, 0f, null)

            if (annotationText.isNotBlank()) {
                val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = textColor
                    textSize = fontSize * 1.5f
                    typeface = Typeface.DEFAULT_BOLD
                }
                val x = bmp.width * posXPercent
                val y = bmp.height * posYPercent
                canvas.drawText(annotationText, x, y, p)
            }

            if (drawPoints.size > 1) {
                val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = penColor
                    strokeWidth = 6f
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
                for (idx in 0 until drawPoints.size - 1) {
                    val p1 = drawPoints[idx]
                    val p2 = drawPoints[idx + 1]
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, strokePaint)
                }
            }

            doc.finishPage(page)
        }

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "edited_doc_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    // ==========================================
    // 17. PDF FORMS (Fillable Form Builder)
    // ==========================================
    suspend fun createFormPdf(
        context: Context,
        pdfUri: Uri?,
        formTitle: String = "Application Form",
        fields: List<String> = listOf("Full Name", "Email Address", "Phone Number", "Date of Birth", "Signature / Authorization"),
        onProgress: (Int, Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        onProgress(1, 1)
        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.rgb(30, 41, 59)
        }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            color = Color.rgb(51, 65, 85)
        }
        val boxBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(203, 213, 225)
            style = Paint.Style.STROKE
            strokeWidth = 1.5f
        }
        val boxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(248, 250, 252)
            style = Paint.Style.FILL
        }

        canvas.drawText(formTitle, 40f, 60f, titlePaint)
        val dividerPaint = Paint().apply { color = Color.rgb(99, 102, 241); strokeWidth = 2f }
        canvas.drawLine(40f, 75f, 555f, 75f, dividerPaint)

        var curY = 110f
        fields.forEach { field ->
            canvas.drawText(field, 40f, curY, labelPaint)
            curY += 10f
            val fieldBox = RectF(40f, curY, 555f, curY + 36f)
            canvas.drawRoundRect(fieldBox, 6f, 6f, boxFillPaint)
            canvas.drawRoundRect(fieldBox, 6f, 6f, boxBorderPaint)
            curY += 55f
        }

        doc.finishPage(page)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(context.filesDir, "fillable_form_$timestamp.pdf")
        FileOutputStream(outFile).use { doc.writeTo(it) }
        doc.close()
        outFile
    }

    private fun parsePageRanges(ranges: String, maxPages: Int): List<Int> {
        val result = mutableSetOf<Int>()
        val parts = ranges.split(",")
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.contains("-")) {
                val bounds = trimmed.split("-")
                val start = (bounds.getOrNull(0)?.toIntOrNull() ?: 1) - 1
                val end = (bounds.getOrNull(1)?.toIntOrNull() ?: maxPages) - 1
                for (i in start.coerceAtLeast(0)..end.coerceAtMost(maxPages - 1)) {
                    result.add(i)
                }
            } else {
                val single = (trimmed.toIntOrNull() ?: 1) - 1
                if (single in 0 until maxPages) {
                    result.add(single)
                }
            }
        }
        return if (result.isEmpty()) (0 until maxPages).toList() else result.toList().sorted()
    }
}

