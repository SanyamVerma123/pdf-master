package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class ConversionType {
    // Organize - arrange and restructure documents
    MERGE_PDF,
    SPLIT_PDF,
    ORGANIZE_PDF,
    SCAN_TO_PDF,

    // Optimize - shrink and fix files
    COMPRESS_PDF,
    REPAIR_PDF,
    OCR_PDF,

    // Convert - PDF to and from other formats
    PDF_TO_WORD,
    PDF_TO_POWERPOINT,
    PDF_TO_EXCEL,
    WORD_TO_PDF,
    POWERPOINT_TO_PDF,
    EXCEL_TO_PDF,
    PDF_TO_IMAGES,
    IMAGE_TO_PDF,
    HTML_TO_PDF,
    PDF_TO_PDFA,

    // Edit - change what is on the page
    EDIT_PDF,
    WATERMARK,
    ROTATE_PDF,
    PAGE_NUMBERS,
    CROP_PDF,
    PDF_FORMS,

    // PDF Security - protect, sign, and clean up
    SIGN_PDF,
    UNLOCK_PDF,
    PROTECT_PDF,
    COMPARE_PDF,
    REDACT_PDF,

    // PDF Intelligence - AI-assisted tools
    AI_SUMMARIZER,
    TRANSLATE_PDF,

    // Aliases / Compatibility
    PHOTO_OCR_TO_PDF,
    TEXT_TO_PDF,
    EXTRACT_TEXT
}

@Entity(tableName = "pdf_records")
data class PdfRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val pageCount: Int,
    val conversionType: ConversionType,
    val createdAt: Long = System.currentTimeMillis(),
    val isFavorite: Boolean = false,
    val description: String = ""
)
