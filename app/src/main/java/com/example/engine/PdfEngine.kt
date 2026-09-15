package com.example.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class PageSize(val title: String, val widthPt: Int, val heightPt: Int) {
    A4("A4", 595, 842),
    LETTER("Letter", 612, 792),
    FIT_IMAGE("Fit Image", 0, 0)
}

enum class PageOrientation(val title: String) {
    AUTO("Auto"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape")
}

enum class PageMargin(val title: String, val marginPt: Int) {
    NONE("None", 0),
    SMALL("Small", 16),
    NORMAL("Normal", 32),
    LARGE("Large", 48)
}

enum class ImageScaleMode(val title: String) {
    FIT("Fit Page"),
    FILL("Fill / Crop")
}

enum class CompressionLevel(val title: String, val quality: Int, val maxDim: Int) {
    HIGH("High (Original)", 95, 2400),
    MEDIUM("Medium (Balanced)", 80, 1600),
    LOW("Small (Compact)", 60, 1080)
}

data class ImagePdfConfig(
    val pageSize: PageSize = PageSize.A4,
    val orientation: PageOrientation = PageOrientation.AUTO,
    val margin: PageMargin = PageMargin.NONE,
    val scaleMode: ImageScaleMode = ImageScaleMode.FIT,
    val compression: CompressionLevel = CompressionLevel.MEDIUM,
    val watermarkText: String = "",
    val showPageNumbers: Boolean = true,
    val customFileName: String = ""
)

data class TextPdfConfig(
    val title: String = "",
    val author: String = "",
    val fontSize: Float = 12f,
    val lineSpacing: Float = 1.3f,
    val pageSize: PageSize = PageSize.A4,
    val margin: PageMargin = PageMargin.NORMAL,
    val showHeader: Boolean = true,
    val showPageNumbers: Boolean = true,
    val watermarkText: String = "",
    val customFileName: String = ""
)

object PdfEngine {

    /**
     * Converts a list of image URIs to a single high-quality PDF.
     */
    suspend fun convertImagesToPdf(
        context: Context,
        imageUris: List<Uri>,
        config: ImagePdfConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val total = imageUris.size
        require(total > 0) { "No images provided for PDF conversion." }

        val outputDir = File(context.filesDir, "generated_pdfs").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val name = if (config.customFileName.isNotBlank()) {
            val clean = config.customFileName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
        } else {
            "OmniDoc_$timeStamp.pdf"
        }
        val outputFile = File(outputDir, name)

        val document = PdfDocument()

        try {
            for ((index, uri) in imageUris.withIndex()) {
                onProgress(index + 1, total)

                val bitmap = loadAndProcessBitmap(context, uri, config.compression) ?: continue

                // Determine Page Dimensions
                val (pageWidth, pageHeight) = calculatePageDimensions(
                    bitmap.width,
                    bitmap.height,
                    config.pageSize,
                    config.orientation
                )

                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas

                // Background
                canvas.drawColor(Color.WHITE)

                val margin = config.margin.marginPt.toFloat()
                val targetRect = RectF(
                    margin,
                    margin,
                    pageWidth - margin,
                    pageHeight - margin
                )

                drawImageOnCanvas(canvas, bitmap, targetRect, config.scaleMode)

                // Optional Watermark
                if (config.watermarkText.isNotBlank()) {
                    drawWatermark(canvas, config.watermarkText, pageWidth, pageHeight)
                }

                // Optional Page Numbers
                if (config.showPageNumbers && total > 1) {
                    drawPageNumber(canvas, index + 1, total, pageWidth, pageHeight, margin)
                }

                document.finishPage(page)
                bitmap.recycle()
            }

            FileOutputStream(outputFile).use { out ->
                document.writeTo(out)
            }
        } finally {
            document.close()
        }

        outputFile
    }

    /**
     * Converts formatted text/notes into a multi-page PDF document.
     */
    suspend fun convertTextToPdf(
        context: Context,
        content: String,
        config: TextPdfConfig,
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "generated_pdfs").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val name = if (config.customFileName.isNotBlank()) {
            val clean = config.customFileName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
        } else {
            "OmniText_$timeStamp.pdf"
        }
        val outputFile = File(outputDir, name)

        val document = PdfDocument()

        val pageWidth = if (config.pageSize == PageSize.LETTER) 612 else 595
        val pageHeight = if (config.pageSize == PageSize.LETTER) 792 else 842
        val margin = config.margin.marginPt.toFloat().coerceAtLeast(28f)

        val headerHeight = if (config.showHeader && config.title.isNotBlank()) 60f else 20f
        val footerHeight = 35f
        val contentWidth = (pageWidth - (margin * 2)).toInt()
        val contentHeight = pageHeight - margin - headerHeight - footerHeight

        val textPaint = TextPaint().apply {
            isAntiAlias = true
            textSize = config.fontSize
            color = Color.rgb(30, 41, 59) // Slate dark text
        }

        val staticLayout = StaticLayout.Builder.obtain(
            content.ifBlank { "No text content." },
            0,
            content.ifBlank { "No text content." }.length,
            textPaint,
            contentWidth
        ).setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, config.lineSpacing)
            .setIncludePad(false)
            .build()

        val totalLines = staticLayout.lineCount
        var currentLine = 0
        var pageNumber = 1

        val pagesInfoList = mutableListOf<Pair<Int, Int>>() // startLine, endLine

        while (currentLine < totalLines) {
            val startLine = currentLine
            var accumulatedHeight = 0f
            while (currentLine < totalLines) {
                val lineH = staticLayout.getLineBottom(currentLine) - staticLayout.getLineTop(currentLine)
                if (accumulatedHeight + lineH > contentHeight) {
                    break
                }
                accumulatedHeight += lineH
                currentLine++
            }
            val endLine = currentLine
            pagesInfoList.add(Pair(startLine, endLine))
        }

        val totalPages = pagesInfoList.size.coerceAtLeast(1)

        for ((idx, lineRange) in pagesInfoList.withIndex()) {
            onProgress(idx + 1, totalPages)
            val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, idx + 1).create()
            val page = document.startPage(pageInfo)
            val canvas = page.canvas

            canvas.drawColor(Color.WHITE)

            // Draw Header
            if (config.showHeader && config.title.isNotBlank()) {
                val titlePaint = Paint().apply {
                    isAntiAlias = true
                    textSize = 16f
                    isFakeBoldText = true
                    color = Color.rgb(15, 23, 42)
                }
                canvas.drawText(config.title, margin, margin + 20f, titlePaint)

                val dateStr = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date())
                val metaPaint = Paint().apply {
                    isAntiAlias = true
                    textSize = 9f
                    color = Color.rgb(148, 163, 184)
                }
                canvas.drawText(dateStr, pageWidth - margin - metaPaint.measureText(dateStr), margin + 20f, metaPaint)

                // Header Divider rule
                val linePaint = Paint().apply {
                    color = Color.rgb(226, 232, 240)
                    strokeWidth = 1f
                }
                canvas.drawLine(margin, margin + 30f, pageWidth - margin, margin + 30f, linePaint)
            }

            // Draw Text Lines
            canvas.save()
            canvas.translate(margin, margin + headerHeight)
            val clipTop = staticLayout.getLineTop(lineRange.first)
            canvas.translate(0f, -clipTop.toFloat())

            // Clip to current page's lines
            val bottomY = staticLayout.getLineBottom(lineRange.second - 1).toFloat()
            canvas.clipRect(0f, clipTop.toFloat(), contentWidth.toFloat(), bottomY)
            staticLayout.draw(canvas)
            canvas.restore()

            // Draw Watermark if configured
            if (config.watermarkText.isNotBlank()) {
                drawWatermark(canvas, config.watermarkText, pageWidth, pageHeight)
            }

            // Draw Footer
            if (config.showPageNumbers) {
                drawPageNumber(canvas, idx + 1, totalPages, pageWidth, pageHeight, margin)
            }

            document.finishPage(page)
        }

        FileOutputStream(outputFile).use { out ->
            document.writeTo(out)
        }
        document.close()

        outputFile
    }

    /**
     * Merges multiple PDF files into one output PDF using PdfRenderer.
     */
    suspend fun mergePdfs(
        context: Context,
        pdfUris: List<Uri>,
        customName: String = "",
        onProgress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "generated_pdfs").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val name = if (customName.isNotBlank()) {
            val clean = customName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
        } else {
            "OmniMerged_$timeStamp.pdf"
        }
        val outputFile = File(outputDir, name)

        val newDoc = PdfDocument()
        var globalPageNumber = 1

        try {
            // First count total pages for progress
            var totalPagesAll = 0
            val tempFiles = mutableListOf<File>()

            for (uri in pdfUris) {
                val tempFile = copyUriToTemp(context, uri) ?: continue
                tempFiles.add(tempFile)
                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)
                totalPagesAll += renderer.pageCount
                renderer.close()
                pfd.close()
            }

            var processedCount = 0

            for (tempFile in tempFiles) {
                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                val renderer = PdfRenderer(pfd)

                for (i in 0 until renderer.pageCount) {
                    processedCount++
                    onProgress(processedCount, totalPagesAll)

                    val page = renderer.openPage(i)
                    val ptWidth = page.width
                    val ptHeight = page.height

                    // Render at high crispness (approx 2x point scale)
                    val renderScale = 2f
                    val bmpW = (ptWidth * renderScale).toInt().coerceIn(400, 2400)
                    val bmpH = (ptHeight * renderScale).toInt().coerceIn(400, 3200)

                    val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    val canvasBg = Canvas(bitmap)
                    canvasBg.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    page.close()

                    val pageInfo = PdfDocument.PageInfo.Builder(ptWidth, ptHeight, globalPageNumber++).create()
                    val newPage = newDoc.startPage(pageInfo)
                    val canvas = newPage.canvas
                    canvas.drawBitmap(bitmap, null, RectF(0f, 0f, ptWidth.toFloat(), ptHeight.toFloat()), null)
                    newDoc.finishPage(newPage)
                    bitmap.recycle()
                }

                renderer.close()
                pfd.close()
                tempFile.delete()
            }

            FileOutputStream(outputFile).use { out ->
                newDoc.writeTo(out)
            }
        } finally {
            newDoc.close()
        }

        outputFile
    }

    /**
     * Renders pages of a PDF, image directory, image file, or ZIP to bitmaps for in-app preview or export.
     */
    suspend fun renderPdfPages(
        context: Context,
        pdfFile: File,
        scale: Float = 1.5f,
        maxPages: Int = 100
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val bitmaps = mutableListOf<Bitmap>()
        if (!pdfFile.exists()) return@withContext bitmaps

        // 1. If it's a directory (e.g. extracted_images folder)
        if (pdfFile.isDirectory) {
            val imageFiles = pdfFile.listFiles { f ->
                f.isFile && (f.name.endsWith(".png", true) || f.name.endsWith(".jpg", true) || f.name.endsWith(".jpeg", true) || f.name.endsWith(".webp", true))
            }?.sortedBy { it.name } ?: emptyList()

            for (imgFile in imageFiles.take(maxPages)) {
                try {
                    val bmp = BitmapFactory.decodeFile(imgFile.absolutePath)
                    if (bmp != null) bitmaps.add(bmp)
                } catch (_: Exception) {}
            }
            return@withContext bitmaps
        }

        // 2. If it's a ZIP archive of images
        if (pdfFile.name.endsWith(".zip", ignoreCase = true)) {
            try {
                ZipInputStream(FileInputStream(pdfFile)).use { zis ->
                    var entry: ZipEntry? = zis.nextEntry
                    while (entry != null && bitmaps.size < maxPages) {
                        if (!entry.isDirectory && (entry.name.endsWith(".png", true) || entry.name.endsWith(".jpg", true) || entry.name.endsWith(".jpeg", true))) {
                            val bytes = zis.readBytes()
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            if (bmp != null) bitmaps.add(bmp)
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            } catch (_: Exception) {}
            return@withContext bitmaps
        }

        // 3. If it's a single image file
        if (pdfFile.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp")) {
            try {
                val bmp = BitmapFactory.decodeFile(pdfFile.absolutePath)
                if (bmp != null) bitmaps.add(bmp)
            } catch (_: Exception) {}
            return@withContext bitmaps
        }

        // 4. Default: Render as PDF
        try {
            val pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                val count = renderer.pageCount.coerceAtMost(maxPages)
                for (i in 0 until count) {
                    val page = renderer.openPage(i)
                    val w = (page.width * scale).toInt().coerceAtLeast(200)
                    val h = (page.height * scale).toInt().coerceAtLeast(200)

                    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    canvas.drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    page.close()
                    bitmaps.add(bitmap)
                }
            } finally {
                renderer.close()
                pfd.close()
            }
        } catch (_: Exception) {}
        bitmaps
    }

    /**
     * Exports rendered bitmaps as individual PNG/JPEG image files in app storage and public Gallery.
     */
    suspend fun exportBitmapsToImages(
        context: Context,
        bitmaps: List<Bitmap>,
        baseName: String,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG
    ): List<File> = withContext(Dispatchers.IO) {
        val exportDir = File(context.filesDir, "extracted_images").apply { mkdirs() }
        val ext = if (format == Bitmap.CompressFormat.PNG) "png" else "jpg"
        var cleanBase = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .removeSuffix(".pdf").removeSuffix(".PDF").removeSuffix(".png").removeSuffix(".PNG")
        if (cleanBase.isBlank()) cleanBase = "Photo"

        val files = bitmaps.mapIndexed { index, bitmap ->
            val file = File(exportDir, "${cleanBase}_photo_${index + 1}.$ext")
            FileOutputStream(file).use { out ->
                bitmap.compress(format, 100, out)
            }
            // Automatically download to device Gallery/Photos
            try {
                ShareUtils.saveImageToGallery(context, file, showToast = false)
            } catch (_: Exception) {}
            file
        }
        files
    }

    /**
     * Exports a single bitmap as an individual PNG file in app storage and public Gallery.
     */
    suspend fun exportSingleBitmap(
        context: Context,
        bitmap: Bitmap,
        baseName: String,
        pageNumber: Int
    ): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.filesDir, "extracted_images").apply { mkdirs() }
        var cleanBase = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .removeSuffix(".pdf").removeSuffix(".PDF").removeSuffix(".png").removeSuffix(".PNG")
        if (cleanBase.isBlank()) cleanBase = "Photo"
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val file = File(exportDir, "${cleanBase}_photo_${pageNumber}_$timeStamp.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        // Automatically download to device Gallery/Photos
        try {
            ShareUtils.saveImageToGallery(context, file, showToast = true)
        } catch (_: Exception) {}
        file
    }

    /**
     * Creates a ZIP archive bundling all given image files.
     */
    suspend fun createZipArchive(
        context: Context,
        files: List<File>,
        zipBaseName: String
    ): File = withContext(Dispatchers.IO) {
        val zipDir = File(context.filesDir, "extracted_zips").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        var clean = zipBaseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .removeSuffix(".pdf").removeSuffix(".PDF")
        if (clean.isBlank()) clean = "Extracted_Photos"
        val zipFile = File(zipDir, "${clean}_$timeStamp.zip")

        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            val buffer = ByteArray(8192)
            for (file in files) {
                if (!file.exists() || !file.isFile) continue
                val entry = ZipEntry(file.name)
                zos.putNextEntry(entry)
                FileInputStream(file).use { fis ->
                    var len: Int
                    while (fis.read(buffer).also { len = it } > 0) {
                        zos.write(buffer, 0, len)
                    }
                }
                zos.closeEntry()
            }
        }
        try {
            ShareUtils.saveFileToDownloads(context, zipFile, "application/zip")
        } catch (_: Exception) {}
        zipFile
    }

    /**
     * Cuts selected pages from a source PDF and writes them to a new standalone PDF.
     */
    suspend fun cutPdfPages(
        context: Context,
        sourcePdfUri: Uri,
        pageIndices: List<Int>,
        customName: String = ""
    ): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "generated_pdfs").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val name = if (customName.isNotBlank()) {
            val clean = customName.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
            if (clean.endsWith(".pdf", ignoreCase = true)) clean else "$clean.pdf"
        } else {
            "OmniCut_$timeStamp.pdf"
        }
        val outputFile = File(outputDir, name)

        val tempFile = copyUriToTemp(context, sourcePdfUri)
            ?: throw IllegalStateException("Could not read source PDF file.")

        val newDoc = PdfDocument()
        try {
            val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            try {
                var newPageNum = 1
                for (pageIdx in pageIndices) {
                    if (pageIdx in 0 until renderer.pageCount) {
                        val page = renderer.openPage(pageIdx)
                        val ptWidth = page.width
                        val ptHeight = page.height

                        val renderScale = 2f
                        val bmpW = (ptWidth * renderScale).toInt().coerceIn(400, 2400)
                        val bmpH = (ptHeight * renderScale).toInt().coerceIn(400, 3200)

                        val bitmap = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                        val canvasBg = Canvas(bitmap)
                        canvasBg.drawColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        page.close()

                        val pageInfo = PdfDocument.PageInfo.Builder(ptWidth, ptHeight, newPageNum++).create()
                        val newPage = newDoc.startPage(pageInfo)
                        newPage.canvas.drawBitmap(bitmap, null, RectF(0f, 0f, ptWidth.toFloat(), ptHeight.toFloat()), null)
                        newDoc.finishPage(newPage)
                        bitmap.recycle()
                    }
                }
            } finally {
                renderer.close()
                pfd.close()
                tempFile.delete()
            }

            FileOutputStream(outputFile).use { out ->
                newDoc.writeTo(out)
            }
        } finally {
            newDoc.close()
        }
        outputFile
    }

    /**
     * Converts a single high-resolution Bitmap into a 1-page PDF file.
     */
    suspend fun convertBitmapToSinglePagePdf(
        context: Context,
        bitmap: Bitmap,
        title: String
    ): File = withContext(Dispatchers.IO) {
        val outputDir = File(context.filesDir, "generated_pdfs").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val clean = title.trim().replace(Regex("[^a-zA-Z0-9._-]"), "_")
        val outputFile = File(outputDir, "${clean}_$timeStamp.pdf")

        val document = PdfDocument()
        val ptWidth = 595
        val ptHeight = 842

        val pageInfo = PdfDocument.PageInfo.Builder(ptWidth, ptHeight, 1).create()
        val page = document.startPage(pageInfo)
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        val bmpWidth = bitmap.width.toFloat()
        val bmpHeight = bitmap.height.toFloat()
        val scale = minOf(ptWidth.toFloat() / bmpWidth, ptHeight.toFloat() / bmpHeight)
        val drawW = bmpWidth * scale
        val drawH = bmpHeight * scale
        val left = (ptWidth - drawW) / 2f
        val top = (ptHeight - drawH) / 2f

        canvas.drawBitmap(bitmap, null, RectF(left, top, left + drawW, top + drawH), null)
        document.finishPage(page)

        FileOutputStream(outputFile).use { out ->
            document.writeTo(out)
        }
        document.close()
        outputFile
    }

    /**
     * Gets page count and file size of a PDF URI.
     */
    suspend fun inspectPdf(context: Context, uri: Uri): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val temp = copyUriToTemp(context, uri) ?: return@withContext Pair(0, 0L)
        val size = temp.length()
        var pageCount = 0
        try {
            val pfd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = PdfRenderer(pfd)
            pageCount = renderer.pageCount
            renderer.close()
            pfd.close()
        } catch (_: Exception) {}
        temp.delete()
        Pair(pageCount, size)
    }

    // Helper functions

    private fun loadAndProcessBitmap(context: Context, uri: Uri, compression: CompressionLevel): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null

        var sampleSize = 1
        while (options.outWidth / sampleSize > compression.maxDim || options.outHeight / sampleSize > compression.maxDim) {
            sampleSize *= 2
        }

        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        var bitmap = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOptions)
        } ?: return null

        // Check EXIF rotation
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                val orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL
                )
                val rotationAngle = when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                if (rotationAngle != 0f) {
                    val matrix = android.graphics.Matrix().apply { postRotate(rotationAngle) }
                    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    bitmap.recycle()
                    bitmap = rotated
                }
            }
        } catch (_: Exception) {}

        return bitmap
    }

    private fun calculatePageDimensions(
        imgW: Int,
        imgH: Int,
        pageSize: PageSize,
        orientation: PageOrientation
    ): Pair<Int, Int> {
        return when (pageSize) {
            PageSize.FIT_IMAGE -> Pair(imgW.coerceIn(200, 2000), imgH.coerceIn(200, 2000))
            PageSize.A4 -> {
                val isImgLandscape = imgW > imgH
                val makeLandscape = when (orientation) {
                    PageOrientation.LANDSCAPE -> true
                    PageOrientation.PORTRAIT -> false
                    PageOrientation.AUTO -> isImgLandscape
                }
                if (makeLandscape) Pair(842, 595) else Pair(595, 842)
            }
            PageSize.LETTER -> {
                val isImgLandscape = imgW > imgH
                val makeLandscape = when (orientation) {
                    PageOrientation.LANDSCAPE -> true
                    PageOrientation.PORTRAIT -> false
                    PageOrientation.AUTO -> isImgLandscape
                }
                if (makeLandscape) Pair(792, 612) else Pair(612, 792)
            }
        }
    }

    private fun drawImageOnCanvas(canvas: Canvas, bitmap: Bitmap, targetRect: RectF, scaleMode: ImageScaleMode) {
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val tw = targetRect.width()
        val th = targetRect.height()

        if (scaleMode == ImageScaleMode.FIT) {
            val scale = (tw / bw).coerceAtMost(th / bh)
            val dw = bw * scale
            val dh = bh * scale
            val left = targetRect.left + (tw - dw) / 2f
            val top = targetRect.top + (th - dh) / 2f
            val destRect = RectF(left, top, left + dw, top + dh)
            canvas.drawBitmap(bitmap, null, destRect, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            // Fill / Center Crop
            val scale = (tw / bw).coerceAtLeast(th / bh)
            val cropW = tw / scale
            val cropH = th / scale
            val srcLeft = ((bw - cropW) / 2f).toInt().coerceAtLeast(0)
            val srcTop = ((bh - cropH) / 2f).toInt().coerceAtLeast(0)
            val srcRect = Rect(srcLeft, srcTop, (srcLeft + cropW).toInt(), (srcTop + cropH).toInt())
            canvas.drawBitmap(bitmap, srcRect, targetRect, Paint(Paint.FILTER_BITMAP_FLAG))
        }
    }

    private fun drawWatermark(canvas: Canvas, text: String, width: Int, height: Int) {
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.rotate(-40f)

        val paint = Paint().apply {
            isAntiAlias = true
            textSize = 38f
            color = Color.argb(45, 15, 23, 42) // subtle dark slate watermark
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, 0f, 0f, paint)
        canvas.restore()
    }

    private fun drawPageNumber(canvas: Canvas, page: Int, total: Int, width: Int, height: Int, margin: Float) {
        val text = "Page $page of $total"
        val paint = Paint().apply {
            isAntiAlias = true
            textSize = 9f
            color = Color.rgb(148, 163, 184)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(text, width / 2f, height - (margin / 2f).coerceAtLeast(16f), paint)
    }

    fun copyUriToTemp(context: Context, uri: Uri): File? {
        return try {
            val tempFile = File.createTempFile("temp_pdf_", ".tmp", context.cacheDir)
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (_: Exception) {
            null
        }
    }

    suspend fun renderAllPagesFromPdfUri(
        context: Context,
        uri: Uri,
        scale: Float = 1.5f,
        maxPages: Int = 100
    ): List<Bitmap> = withContext(Dispatchers.IO) {
        val temp = copyUriToTemp(context, uri) ?: return@withContext emptyList<Bitmap>()
        try {
            renderPdfPages(context, temp, scale, maxPages)
        } finally {
            temp.delete()
        }
    }

    fun formatFileSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes.toDouble() / 1024)
            else -> "$bytes B"
        }
    }

    suspend fun extractTextFromImageUri(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            try {
                val image = InputImage.fromFilePath(context, uri)
                val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                recognizer.process(image)
                    .addOnSuccessListener { visionText ->
                        if (continuation.isActive) {
                            continuation.resume(visionText.text)
                        }
                    }
                    .addOnFailureListener { e ->
                        if (continuation.isActive) {
                            continuation.resumeWithException(e)
                        }
                    }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    suspend fun extractTextFromMultipleImages(
        context: Context,
        uris: List<Uri>,
        onProgress: (current: Int, total: Int) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val sb = StringBuilder()
        uris.forEachIndexed { index, uri ->
            onProgress(index + 1, uris.size)
            try {
                val pageText = extractTextFromImageUri(context, uri)
                if (pageText.isNotBlank()) {
                    if (uris.size > 1) {
                        sb.append("--- PAGE ${index + 1} ---\n\n")
                    }
                    sb.append(pageText.trim())
                    sb.append("\n\n")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        sb.toString().trim()
    }

    suspend fun convertPhotosToOcrPdf(
        context: Context,
        imageUris: List<Uri>,
        title: String = "Scanned OCR Document",
        watermark: String = "",
        pageSize: PageSize = PageSize.A4,
        onProgress: (current: Int, total: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val extractedText = extractTextFromMultipleImages(context, imageUris, onProgress)
        val finalContent = if (extractedText.isNotBlank()) {
            extractedText
        } else {
            "No text was detected in the provided images."
        }

        val config = TextPdfConfig(
            title = title,
            pageSize = pageSize,
            watermarkText = watermark,
            fontSize = 12f,
            lineSpacing = 1.3f,
            margin = PageMargin.NORMAL,
            showHeader = true,
            showPageNumbers = true
        )

        convertTextToPdf(
            context = context,
            content = finalContent,
            config = config,
            onProgress = onProgress
        )
    }
}
