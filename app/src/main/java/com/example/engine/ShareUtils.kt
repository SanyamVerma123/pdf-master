package com.example.engine

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object ShareUtils {

    fun getFileUri(context: Context, file: File): Uri {
        val authority = "${context.packageName}.fileprovider"
        return FileProvider.getUriForFile(context, authority, file)
    }

    fun sharePdf(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = getFileUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share PDF Document"))
    }

    fun shareSingleImage(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = getFileUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Photo (PNG)"))
    }

    fun shareZip(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = getFileUri(context, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ZIP Archive"))
    }

    fun shareFile(context: Context, file: File) {
        if (file.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp")) {
            shareSingleImage(context, file)
        } else if (file.name.endsWith(".zip", ignoreCase = true)) {
            shareZip(context, file)
        } else {
            sharePdf(context, file)
        }
    }

    fun openInExternalApp(context: Context, file: File) {
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = getFileUri(context, file)
        val mimeType = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "zip" -> "application/zip"
            else -> "application/pdf"
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "No app available to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareImages(context: Context, files: List<File>) {
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { getFileUri(context, it) })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/png"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Rendered Photos"))
    }

    /**
     * Automatically downloads a photo file into the device's public Gallery / Pictures directory (Pictures/OmniPDF)
     * and triggers MediaScanner so it immediately appears in Google Photos / Gallery.
     */
    fun saveImageToGallery(context: Context, imageFile: File, showToast: Boolean = true): Uri? {
        return try {
            val fileName = if (imageFile.name.endsWith(".png", ignoreCase = true)) imageFile.name else "${imageFile.nameWithoutExtension}.png"
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OmniPDF")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(imageFile).use { it.copyTo(out) }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    contentValues.clear()
                    contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, contentValues, null, null)
                }

                // Also trigger MediaScanner
                try {
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(imageFile.absolutePath),
                        arrayOf("image/png"),
                        null
                    )
                } catch (_: Exception) {}

                if (showToast) {
                    Toast.makeText(context, "✓ Photo downloaded to Gallery: $fileName", Toast.LENGTH_SHORT).show()
                }
                uri
            } else {
                null
            }
        } catch (e: Exception) {
            if (showToast) {
                Toast.makeText(context, "Saved to App Storage (Gallery: ${e.message})", Toast.LENGTH_SHORT).show()
            }
            null
        }
    }

    /**
     * Automatically saves multiple images to the device Gallery.
     */
    fun saveMultipleImagesToGallery(context: Context, files: List<File>): Int {
        var count = 0
        for (file in files) {
            val uri = saveImageToGallery(context, file, showToast = false)
            if (uri != null) count++
        }
        if (count > 0) {
            Toast.makeText(context, "✓ Automatically downloaded $count photos to your device Photos & Gallery!", Toast.LENGTH_LONG).show()
        }
        return count
    }

    /**
     * Saves a ZIP or other file into the public Downloads directory (Download/OmniPDF).
     */
    fun saveFileToDownloads(context: Context, file: File, mimeType: String): Uri? {
        return try {
            val contentValues = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/OmniPDF")
                }
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    FileInputStream(file).use { it.copyTo(out) }
                }
                Toast.makeText(context, "✓ Downloaded to Downloads folder: ${file.name}", Toast.LENGTH_SHORT).show()
            }
            uri
        } catch (_: Exception) {
            null
        }
    }

    fun printDocument(activity: Activity, file: File) {
        if (!file.exists()) {
            Toast.makeText(activity, "File does not exist", Toast.LENGTH_SHORT).show()
            return
        }
        val printManager = activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
        if (printManager == null) {
            Toast.makeText(activity, "Print service not available", Toast.LENGTH_SHORT).show()
            return
        }

        val printAdapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: android.os.Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val pdi = android.print.PrintDocumentInfo.Builder(file.name)
                    .setContentType(android.print.PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .build()
                callback?.onLayoutFinished(pdi, true)
            }

            override fun onWrite(
                pages: Array<out android.print.PageRange>?,
                destination: android.os.ParcelFileDescriptor?,
                cancellationSignal: android.os.CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    FileInputStream(file).use { input ->
                        FileOutputStream(destination?.fileDescriptor).use { output ->
                            input.copyTo(output)
                        }
                    }
                    callback?.onWriteFinished(arrayOf(android.print.PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback?.onWriteFailed(e.message)
                }
            }
        }

        printManager.print(file.nameWithoutExtension, printAdapter, PrintAttributes.Builder().build())
    }

    fun printPdf(activity: Activity, file: File) {
        printDocument(activity, file)
    }
}

