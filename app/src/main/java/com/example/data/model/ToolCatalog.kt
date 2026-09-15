package com.example.data.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.BrandingWatermark
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess

enum class ToolCategory(val title: String, val iconLabel: String) {
    ALL("All", "🌟"),
    ORGANIZE("Organize", "📂"),
    OPTIMIZE("Optimize", "⚡"),
    CONVERT("Convert", "🔄"),
    EDIT("Edit", "✏️"),
    SECURITY("Security", "🔒"),
    AI_INTELLIGENCE("AI Intelligence", "✨")
}

data class ToolItem(
    val type: ConversionType,
    val title: String,
    val subtitle: String,
    val description: String,
    val category: ToolCategory,
    val badge: String,
    val accentColor: Color,
    val icon: ImageVector,
    val testTag: String
)

object ToolCatalog {
    val tools: List<ToolItem> = listOf(
        // ==========================================
        // Group 1: Organize - arrange and restructure documents
        // ==========================================
        ToolItem(
            type = ConversionType.MERGE_PDF,
            title = "Merge PDF",
            subtitle = "COMBINE & JOIN",
            description = "Combine multiple PDFs in the exact order you want with visual drag reordering and sorting.",
            category = ToolCategory.ORGANIZE,
            badge = "FLAGSHIP",
            accentColor = Color(0xFF8B5CF6),
            icon = Icons.Default.CallSplit,
            testTag = "tool_card_merge_pdf"
        ),
        ToolItem(
            type = ConversionType.SPLIT_PDF,
            title = "Split PDF",
            subtitle = "PAGE RANGES & EXTRACT",
            description = "Separate one page or a whole set for easy conversion into independent PDF files.",
            category = ToolCategory.ORGANIZE,
            badge = "ESSENTIAL",
            accentColor = Color(0xFFF97316),
            icon = Icons.Default.ContentCut,
            testTag = "tool_card_split_pdf"
        ),
        ToolItem(
            type = ConversionType.ORGANIZE_PDF,
            title = "Organize PDF",
            subtitle = "SORT, ROTATE & REMOVE",
            description = "Sort pages of your PDF file however you like. Delete pages or add new pages at your convenience.",
            category = ToolCategory.ORGANIZE,
            badge = "PAGES",
            accentColor = Color(0xFF06B6D4),
            icon = Icons.Default.GridView,
            testTag = "tool_card_organize_pdf"
        ),
        ToolItem(
            type = ConversionType.SCAN_TO_PDF,
            title = "Scan to PDF",
            subtitle = "CAMERA DOCUMENT SCANNER",
            description = "Capture document scans from your mobile device with crisp B&W, contrast, and deskew filters.",
            category = ToolCategory.ORGANIZE,
            badge = "CAMERA",
            accentColor = Color(0xFF10B981),
            icon = Icons.Default.CameraAlt,
            testTag = "tool_card_scan_to_pdf"
        ),

        // ==========================================
        // Group 2: Optimize - shrink and fix files
        // ==========================================
        ToolItem(
            type = ConversionType.COMPRESS_PDF,
            title = "Compress PDF",
            subtitle = "REDUCE FILE SIZE",
            description = "Reduce file size while optimizing for maximal quality. Features Extreme, Recommended & Custom DPI.",
            category = ToolCategory.OPTIMIZE,
            badge = "POPULAR",
            accentColor = Color(0xFF0284C7),
            icon = Icons.Default.Speed,
            testTag = "tool_card_compress_pdf"
        ),
        ToolItem(
            type = ConversionType.REPAIR_PDF,
            title = "Repair PDF",
            subtitle = "CORRUPT FILE RECOVERY",
            description = "Repair damaged PDFs and recover lost data from corrupt or broken files with structural diagnostics.",
            category = ToolCategory.OPTIMIZE,
            badge = "REPAIR",
            accentColor = Color(0xFFEC4899),
            icon = Icons.Default.Build,
            testTag = "tool_card_repair_pdf"
        ),
        ToolItem(
            type = ConversionType.OCR_PDF,
            title = "OCR PDF",
            subtitle = "SEARCHABLE & SELECTABLE",
            description = "Easily convert scanned PDFs or document photos into searchable, selectable text using on-device ML.",
            category = ToolCategory.OPTIMIZE,
            badge = "ON-DEVICE ML",
            accentColor = AmberWarning,
            icon = Icons.Default.DocumentScanner,
            testTag = "tool_card_ocr_pdf"
        ),

        // ==========================================
        // Group 3: Convert - PDF to and from other formats
        // ==========================================
        ToolItem(
            type = ConversionType.PDF_TO_WORD,
            title = "PDF to Word",
            subtitle = "DOC & DOCX EXPORT",
            description = "Easily convert your PDF files into easy to edit DOC and DOCX documents with preserved paragraphs.",
            category = ToolCategory.CONVERT,
            badge = "OFFICE",
            accentColor = Color(0xFF2563EB),
            icon = Icons.Default.Description,
            testTag = "tool_card_pdf_to_word"
        ),
        ToolItem(
            type = ConversionType.PDF_TO_POWERPOINT,
            title = "PDF to PowerPoint",
            subtitle = "PPT & PPTX SLIDESHOW",
            description = "Turn your PDF pages into easy to edit PPT and PPTX slideshows with 16:9 widescreen presentation layout.",
            category = ToolCategory.CONVERT,
            badge = "SLIDES",
            accentColor = Color(0xFFEA580C),
            icon = Icons.Default.Slideshow,
            testTag = "tool_card_pdf_to_powerpoint"
        ),
        ToolItem(
            type = ConversionType.PDF_TO_EXCEL,
            title = "PDF to Excel",
            subtitle = "SPREADSHEET & CSV",
            description = "Pull tabular data and numerical figures straight from PDFs into Excel spreadsheets in a few seconds.",
            category = ToolCategory.CONVERT,
            badge = "DATA",
            accentColor = Color(0xFF059669),
            icon = Icons.Default.TableChart,
            testTag = "tool_card_pdf_to_excel"
        ),
        ToolItem(
            type = ConversionType.WORD_TO_PDF,
            title = "Word to PDF",
            subtitle = "DOCX TO CLEAN PDF",
            description = "Make DOC and DOCX files easy to read and share by converting them to clean, styled PDF documents.",
            category = ToolCategory.CONVERT,
            badge = "READER",
            accentColor = Color(0xFF3B82F6),
            icon = Icons.Default.PictureAsPdf,
            testTag = "tool_card_word_to_pdf"
        ),
        ToolItem(
            type = ConversionType.POWERPOINT_TO_PDF,
            title = "PowerPoint to PDF",
            subtitle = "PRESENTATION HANDOUTS",
            description = "Make PPT and PPTX slideshows easy to view by converting them to crisp PDF presentation handouts.",
            category = ToolCategory.CONVERT,
            badge = "DECKS",
            accentColor = Color(0xFFD97706),
            icon = Icons.Default.Slideshow,
            testTag = "tool_card_powerpoint_to_pdf"
        ),
        ToolItem(
            type = ConversionType.EXCEL_TO_PDF,
            title = "Excel to PDF",
            subtitle = "TABULAR GRID REPORT",
            description = "Make EXCEL spreadsheets easy to read by converting them to formatted PDF reports with zebra striping.",
            category = ToolCategory.CONVERT,
            badge = "TABLES",
            accentColor = Color(0xFF10B981),
            icon = Icons.Default.TableChart,
            testTag = "tool_card_excel_to_pdf"
        ),
        ToolItem(
            type = ConversionType.PDF_TO_IMAGES,
            title = "PDF to JPG",
            subtitle = "PNG / JPG EXTRACTION",
            description = "Convert each PDF page into high-res JPG/PNG photos or download all extracted photos as a ZIP archive.",
            category = ToolCategory.CONVERT,
            badge = "GALLERY",
            accentColor = EmeraldSuccess,
            icon = Icons.Default.BurstMode,
            testTag = "tool_card_pdf_to_images"
        ),
        ToolItem(
            type = ConversionType.IMAGE_TO_PDF,
            title = "JPG to PDF",
            subtitle = "PHOTOS TO PDF",
            description = "Convert JPG/PNG images to PDF in seconds. Easily adjust orientation, fit modes, margins, and page sizes.",
            category = ToolCategory.CONVERT,
            badge = "POPULAR",
            accentColor = CrimsonPrimary,
            icon = Icons.Default.PhotoLibrary,
            testTag = "tool_card_image_to_pdf"
        ),
        ToolItem(
            type = ConversionType.HTML_TO_PDF,
            title = "HTML to PDF",
            subtitle = "WEBPAGE & CODE",
            description = "Convert webpages and HTML markup to PDF. Copy/paste URLs or code and generate with custom scale.",
            category = ToolCategory.CONVERT,
            badge = "WEB",
            accentColor = Color(0xFF6366F1),
            icon = Icons.Default.Language,
            testTag = "tool_card_html_to_pdf"
        ),
        ToolItem(
            type = ConversionType.PDF_TO_PDFA,
            title = "PDF to PDF/A",
            subtitle = "ISO LONG-TERM ARCHIVE",
            description = "Transform your PDF to PDF/A, the ISO-standardized version for long-term archiving and permanent preservation.",
            category = ToolCategory.CONVERT,
            badge = "ISO ARCHIVE",
            accentColor = Color(0xFF475569),
            icon = Icons.Default.Archive,
            testTag = "tool_card_pdf_to_pdfa"
        ),

        // ==========================================
        // Group 4: Edit - change what is on the page
        // ==========================================
        ToolItem(
            type = ConversionType.EDIT_PDF,
            title = "Edit PDF",
            subtitle = "ANNOTATE, DRAW & SHAPES",
            description = "Add text, images, shapes or freehand annotations to a PDF. Customize font size, color, and positioning.",
            category = ToolCategory.EDIT,
            badge = "EDITOR",
            accentColor = Color(0xFF8B5CF6),
            icon = Icons.Default.BorderColor,
            testTag = "tool_card_edit_pdf"
        ),
        ToolItem(
            type = ConversionType.WATERMARK,
            title = "Watermark",
            subtitle = "TEXT & IMAGE STAMPS",
            description = "Stamp custom text or image over your PDF in seconds. Choose typography, transparency, angle and position.",
            category = ToolCategory.EDIT,
            badge = "STAMP",
            accentColor = Color(0xFF0284C7),
            icon = Icons.Default.BrandingWatermark,
            testTag = "tool_card_watermark"
        ),
        ToolItem(
            type = ConversionType.ROTATE_PDF,
            title = "Rotate PDF",
            subtitle = "90° / 180° / 270° CW & CCW",
            description = "Rotate your PDFs the way you need them. Rotate multiple pages at once, or filter by odd/even pages.",
            category = ToolCategory.EDIT,
            badge = "ORIENTATION",
            accentColor = Color(0xFFF59E0B),
            icon = Icons.Default.RotateRight,
            testTag = "tool_card_rotate_pdf"
        ),
        ToolItem(
            type = ConversionType.PAGE_NUMBERS,
            title = "Page numbers",
            subtitle = "HEADER & FOOTER NUMBERING",
            description = "Add page numbers into PDFs with ease. Customize positions, dimensions, typography, and format.",
            category = ToolCategory.EDIT,
            badge = "NUMBERING",
            accentColor = Color(0xFF14B8A6),
            icon = Icons.Default.FormatListNumbered,
            testTag = "tool_card_page_numbers"
        ),
        ToolItem(
            type = ConversionType.CROP_PDF,
            title = "Crop PDF",
            subtitle = "MARGINS & BOUNDING BOX",
            description = "Crop margins of PDF documents or select specific areas, then apply changes to one page or whole file.",
            category = ToolCategory.EDIT,
            badge = "MARGINS",
            accentColor = Color(0xFFEC4899),
            icon = Icons.Default.Crop,
            testTag = "tool_card_crop_pdf"
        ),
        ToolItem(
            type = ConversionType.PDF_FORMS,
            title = "PDF Forms",
            subtitle = "FILLABLE FORM BUILDER",
            description = "Detect form fields automatically, create fillable PDFs, or fill forms with text fields, checkboxes and lists.",
            category = ToolCategory.EDIT,
            badge = "FORMS",
            accentColor = Color(0xFF6366F1),
            icon = Icons.Default.CheckBox,
            testTag = "tool_card_pdf_forms"
        ),

        // ==========================================
        // Group 5: PDF Security - protect, sign, and clean up
        // ==========================================
        ToolItem(
            type = ConversionType.SIGN_PDF,
            title = "Sign PDF",
            subtitle = "E-SIGNATURE & DRAW PAD",
            description = "Sign documents yourself with smooth touch stylus drawing, elegant signature scripts, and date stamps.",
            category = ToolCategory.SECURITY,
            badge = "E-SIGN",
            accentColor = CrimsonPrimary,
            icon = Icons.Default.Draw,
            testTag = "tool_card_sign_pdf"
        ),
        ToolItem(
            type = ConversionType.UNLOCK_PDF,
            title = "Unlock PDF",
            subtitle = "REMOVE RESTRICTIONS",
            description = "Remove PDF password security and printing restrictions, giving you full freedom to use your PDFs.",
            category = ToolCategory.SECURITY,
            badge = "DECRYPT",
            accentColor = EmeraldSuccess,
            icon = Icons.Default.LockOpen,
            testTag = "tool_card_unlock_pdf"
        ),
        ToolItem(
            type = ConversionType.PROTECT_PDF,
            title = "Protect PDF",
            subtitle = "PASSWORD ENCRYPTION",
            description = "Protect PDF files with strong password encryption to prevent unauthorized access, copying, or printing.",
            category = ToolCategory.SECURITY,
            badge = "ENCRYPT",
            accentColor = Color(0xFFEF4444),
            icon = Icons.Default.Lock,
            testTag = "tool_card_protect_pdf"
        ),
        ToolItem(
            type = ConversionType.COMPARE_PDF,
            title = "Compare PDF",
            subtitle = "SIDE-BY-SIDE DIFF",
            description = "Show a side-by-side document comparison and easily spot visual and text changes between different versions.",
            category = ToolCategory.SECURITY,
            badge = "DIFF CHECK",
            accentColor = Color(0xFF8B5CF6),
            icon = Icons.Default.Compare,
            testTag = "tool_card_compare_pdf"
        ),
        ToolItem(
            type = ConversionType.REDACT_PDF,
            title = "Redact PDF",
            subtitle = "PERMANENT BLACKOUT",
            description = "Redact text and rectangular graphics to permanently remove sensitive information and PII from a PDF.",
            category = ToolCategory.SECURITY,
            badge = "PRIVACY",
            accentColor = Color(0xFF1E293B),
            icon = Icons.Default.VisibilityOff,
            testTag = "tool_card_redact_pdf"
        ),

        // ==========================================
        // Group 6: PDF Intelligence - AI-assisted tools
        // ==========================================
        ToolItem(
            type = ConversionType.AI_SUMMARIZER,
            title = "AI Summarizer",
            subtitle = "KEY POINTS & BULLETS",
            description = "Quickly generate concise summaries from articles, contracts, and essays with executive key points in seconds.",
            category = ToolCategory.AI_INTELLIGENCE,
            badge = "AI CORE",
            accentColor = Color(0xFF8B5CF6),
            icon = Icons.Default.AutoAwesome,
            testTag = "tool_card_ai_summarizer"
        ),
        ToolItem(
            type = ConversionType.TRANSLATE_PDF,
            title = "Translate PDF",
            subtitle = "MULTI-LANGUAGE TRANSLATE",
            description = "Easily translate PDF files powered by AI into 15+ languages while preserving layout, fonts, and readability.",
            category = ToolCategory.AI_INTELLIGENCE,
            badge = "AI TRANSLATE",
            accentColor = CyanAccent,
            icon = Icons.Default.Translate,
            testTag = "tool_card_translate_pdf"
        )
    )

    fun findTool(type: ConversionType): ToolItem {
        return tools.find { it.type == type } ?: tools.first()
    }
}
