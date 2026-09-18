package com.example.ui.components

import android.app.Activity
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BurstMode
import androidx.compose.material.icons.filled.CallSplit
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConversionType
import com.example.engine.ImagePdfConfig
import com.example.engine.ShareUtils
import com.example.engine.TextPdfConfig
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.viewmodel.ConversionUiState
import com.example.ui.viewmodel.PdfMetadataItem
import java.io.File

@Composable
fun ToolWorkbenchScreen(
    tool: ConversionType,
    conversionState: ConversionUiState,
    onBack: () -> Unit,
    onDismissConversion: () -> Unit,
    onViewInApp: (File) -> Unit,

    // Image to PDF parameters
    selectedImages: List<Uri>,
    imageConfig: ImagePdfConfig,
    onAddImages: (List<Uri>) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onMoveImage: (Int, Int) -> Unit,
    onReplaceImage: (Int, Uri) -> Unit = { _, _ -> },
    onClearImages: () -> Unit,
    onUpdateImageConfig: ((ImagePdfConfig) -> ImagePdfConfig) -> Unit,
    onConvertImages: () -> Unit,
    onOcrScanShortcut: () -> Unit,

    // Photo OCR parameters
    ocrImages: List<Uri>,
    ocrExtractedText: String,
    ocrDocumentTitle: String,
    isOcrScanning: Boolean,
    onAddOcrImages: (List<Uri>) -> Unit,
    onRemoveOcrImage: (Int) -> Unit,
    onClearOcrImages: () -> Unit,
    onOcrTextChange: (String) -> Unit,
    onOcrTitleChange: (String) -> Unit,
    onScanOcr: () -> Unit,
    onConvertOcrToPdf: () -> Unit,
    onSendToComposer: () -> Unit,

    // Text to PDF parameters
    textTitle: String,
    textContent: String,
    textConfig: TextPdfConfig,
    onTextTitleChange: (String) -> Unit,
    onTextContentChange: (String) -> Unit,
    onClearTextComposer: () -> Unit,
    onUpdateTextConfig: ((TextPdfConfig) -> TextPdfConfig) -> Unit,
    onConvertText: () -> Unit,

    // Merge PDF parameters
    selectedPdfsForMerge: List<PdfMetadataItem>,
    mergeFileName: String,
    onAddPdfsForMerge: (List<Uri>) -> Unit,
    onRemovePdfForMerge: (Int) -> Unit,
    onMovePdfForMerge: (Int, Int) -> Unit,
    onClearPdfsForMerge: () -> Unit,
    onMergeFileNameChange: (String) -> Unit,
    onMergePdfs: () -> Unit,
    onReversePdfs: () -> Unit,
    onSortByName: () -> Unit,
    onSortBySize: () -> Unit,

    // PDF to Images parameters
    selectedPdfForExtract: Uri?,
    extractedPages: List<android.graphics.Bitmap>,
    exportedFiles: List<File>,
    isExtracting: Boolean,
    selectedPagesForCut: Set<Int> = emptySet(),
    onSelectPdfForExtract: (Uri) -> Unit,
    onExportImages: () -> Unit,
    onExportSelectedImages: () -> Unit = onExportImages,
    onExportZip: () -> Unit = {},
    onExportSoloImage: (Int) -> Unit = {},
    onCutSpecificPageToPdf: (Int) -> Unit = {},
    onCutSelectedPagesToPdf: () -> Unit = {},
    onTogglePageSelection: (Int) -> Unit = {},
    onSelectAllPages: () -> Unit = {},
    onClearPageSelection: () -> Unit = {},
    onSendPageToOcr: (Int) -> Unit = {},
    onSendPageToComposer: (Int) -> Unit = {},
    viewModel: com.example.ui.viewmodel.PdfConverterViewModel? = null,
    modifier: Modifier = Modifier
) {
    if (viewModel != null && tool !in listOf(
        ConversionType.IMAGE_TO_PDF,
        ConversionType.MERGE_PDF,
        ConversionType.TEXT_TO_PDF,
        ConversionType.PDF_TO_IMAGES,
        ConversionType.EXTRACT_TEXT,
        ConversionType.SCAN_TO_PDF
    )) {
        UniversalPdfWorkbench(
            tool = tool,
            viewModel = viewModel,
            conversionState = conversionState,
            onBack = onBack,
            onDismissConversion = onDismissConversion,
            onViewInApp = onViewInApp,
            modifier = modifier
        )
        return
    }

    val context = LocalContext.current
    val activity = context as? Activity

    val toolItem = com.example.data.model.ToolCatalog.findTool(tool)
    val toolTitle = toolItem.title.uppercase()
    val toolSubtitle = toolItem.subtitle
    val toolColor = toolItem.accentColor
    val toolIcon = toolItem.icon

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Dedicated Tool Header Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .testTag("tool_page_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back to Tool Selection",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(toolColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = toolIcon,
                    contentDescription = null,
                    tint = toolColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = toolTitle,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = toolSubtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = toolColor
                    )
                )
            }
        }

        // Workbench Content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // Conversion Status Banner / Card
            item {
                ConversionStatusCard(
                    state = conversionState,
                    onDismiss = onDismissConversion,
                    onViewInApp = onViewInApp,
                    onShare = { file -> ShareUtils.sharePdf(context, file) },
                    onPrint = { file ->
                        if (activity != null) ShareUtils.printDocument(activity, file)
                    },
                    onOpenExternal = { file -> ShareUtils.openInExternalApp(context, file) }
                )
            }

            // Interactive Workbench
            item {
                when (tool) {
                    ConversionType.IMAGE_TO_PDF -> {
                        ImageToPdfWorkbench(
                            selectedImages = selectedImages,
                            config = imageConfig,
                            onAddImages = onAddImages,
                            onRemoveImage = onRemoveImage,
                            onMoveImage = onMoveImage,
                            onReplaceImage = onReplaceImage,
                            onClearImages = onClearImages,
                            onUpdateConfig = onUpdateImageConfig,
                            onConvert = onConvertImages,
                            onOcrScan = onOcrScanShortcut
                        )
                    }

                    // PHOTO_OCR_TO_PDF removed in v1.8. The enum value is kept
                    // so saved DB history rows still deserialize; this screen is
                    // unreachable because the tool card and shortcut are gone.

                    ConversionType.SCAN_TO_PDF -> {
                        if (viewModel != null) {
                            ScanToPdfWorkbench(
                                viewModel = viewModel,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 0.dp)
                            )
                        }
                    }

                    ConversionType.MERGE_PDF -> {
                        MergePdfWorkbench(
                            selectedPdfs = selectedPdfsForMerge,
                            customFileName = mergeFileName,
                            onAddPdfs = onAddPdfsForMerge,
                            onRemovePdf = onRemovePdfForMerge,
                            onMovePdf = onMovePdfForMerge,
                            onClearPdfs = onClearPdfsForMerge,
                            onFileNameChange = onMergeFileNameChange,
                            onMerge = onMergePdfs,
                            onReversePdfs = onReversePdfs,
                            onSortByName = onSortByName,
                            onSortBySize = onSortBySize
                        )
                    }

                    ConversionType.TEXT_TO_PDF -> {
                        TextToPdfWorkbench(
                            title = textTitle,
                            content = textContent,
                            config = textConfig,
                            onTitleChange = onTextTitleChange,
                            onContentChange = onTextContentChange,
                            onClear = onClearTextComposer,
                            onUpdateConfig = onUpdateTextConfig,
                            onConvert = onConvertText
                        )
                    }

                    ConversionType.PDF_TO_IMAGES, ConversionType.EXTRACT_TEXT -> {
                        PdfToImagesWorkbench(
                            selectedPdf = selectedPdfForExtract,
                            extractedPages = extractedPages,
                            exportedFiles = exportedFiles,
                            isLoading = isExtracting,
                            selectedPagesForCut = selectedPagesForCut,
                            onSelectPdf = onSelectPdfForExtract,
                            onExportImages = onExportImages,
                            onExportSelectedImages = onExportSelectedImages,
                            onExportZip = onExportZip,
                            onExportSoloImage = onExportSoloImage,
                            onCutSpecificPageToPdf = onCutSpecificPageToPdf,
                            onCutSelectedPagesToPdf = onCutSelectedPagesToPdf,
                            onTogglePageSelection = onTogglePageSelection,
                            onSelectAllPages = onSelectAllPages,
                            onClearPageSelection = onClearPageSelection,
                            onSendPageToOcr = onSendPageToOcr,
                            onSendPageToComposer = onSendPageToComposer,
                            onShareExported = { files -> ShareUtils.shareImages(context, files) }
                        )
                    }
                    else -> {}
                }
            }

            item {
                Spacer(modifier = Modifier.height(40.dp))
            }
        }
    }
}

private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
