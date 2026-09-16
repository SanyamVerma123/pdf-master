package com.example.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.request.ImageRequest
import coil.size.Size
import androidx.core.graphics.drawable.toBitmap
import com.example.data.model.ScanPage
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.viewmodel.PdfConverterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Camera-first entry point for the "Scan to PDF" tool.
 *
 * Shows the live scanner viewport plus the staged-page count and primary actions
 * (convert to PDF / run OCR). The camera surface itself is provided by
 * [DocumentScannerHost] once the user taps "Scan".
 */
@Composable
fun ScanToPdfWorkbench(
    viewModel: PdfConverterViewModel,
    modifier: Modifier = Modifier
) {
    val scanPages by viewModel.scanPages.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isScanProcessing.collectAsStateWithLifecycle()
    val scanFilter by viewModel.scanFilter.collectAsStateWithLifecycle()
    val scanQuality by viewModel.scanQuality.collectAsStateWithLifecycle()
    val lensFacing by viewModel.scannerLensFacing.collectAsStateWithLifecycle()
    val scannerError by viewModel.scannerError.collectAsStateWithLifecycle()

    var cameraOpen by remember { mutableStateOf(false) }

    // Page editor: opened by tapping a thumbnail in the strip below.
    var editingPageIndex by remember { mutableStateOf<Int?>(null) }
    var scanPageBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var scanBitmapToken by remember { mutableStateOf(0) }
    var isLoadingScanBitmaps by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // (Re)decode the staged pages into bitmaps whenever the set of pages
    // changes, so both the thumbnail strip and the page editor stay in sync
    // with rotate / filter / remove operations.
    LaunchedEffect(scanPages) {
        scanPageBitmaps = emptyList()
        scanBitmapToken++
    }
    LaunchedEffect(scanBitmapToken) {
        if (scanPages.isNotEmpty() && scanPageBitmaps.isEmpty() && !isLoadingScanBitmaps) {
            isLoadingScanBitmaps = true
            scope.launch(Dispatchers.IO) {
                val loader = ImageLoader(context)
                val decoded = scanPages.map { page ->
                    runCatching {
                        ImageRequest.Builder(context)
                            .data(page.processedUri)
                            .size(Size.ORIGINAL)
                            .build()
                            .let { loader.execute(it).drawable?.let { d -> (d as android.graphics.drawable.BitmapDrawable).bitmap } }
                    }.getOrNull()
                }
                @Suppress("UNCHECKED_CAST")
                scanPageBitmaps = decoded.filterNotNull() as List<android.graphics.Bitmap>
                isLoadingScanBitmaps = false
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(0.dp)
    ) {
        if (cameraOpen) {
            DocumentScannerHost(
                scanPages = scanPages,
                scanFilter = scanFilter,
                scanQuality = scanQuality,
                isProcessing = isProcessing,
                lensFacing = lensFacing,
                onProcessCapture = { rawUri -> viewModel.processScanCapture(rawUri) },
                onRefilterPage = { page -> viewModel.refilterScanPage(page) },
                onRemovePage = { page -> viewModel.removeScanPage(page) },
                onPickFromGallery = { uris -> viewModel.processGalleryScans(uris) },
                onFilterSelected = { filter -> viewModel.setScanFilter(filter) },
                onQualitySelected = { quality -> viewModel.setScanQuality(quality) },
                onFlipCamera = { facing -> viewModel.setScannerLensFacing(facing) },
                onDone = {
                    cameraOpen = false
                    viewModel.convertScansToPdf()
                },
                onBack = { cameraOpen = false },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(560.dp)
                    .clip(RoundedCornerShape(20.dp))
            )
            return@Column
        }

        // --- Staged pages summary + primary actions (camera closed) ---
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DOCUMENT SCANNER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.2.sp,
                            color = EmeraldSuccess,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Text(
                        text = if (scanPages.isEmpty()) "Capture pages with your camera" else "${scanPages.size} pages staged",
                        style = MaterialTheme.typography.titleSmall.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                if (scanPages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SlateDark)
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            .clickable { viewModel.clearScanPages() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                            .testTag("scan_clear_pages")
                    ) {
                        Text(
                            text = "CLEAR",
                            style = CameraWorkbenchLabel
                        )
                    }
                }
            }

            // Open camera CTA. Tapping Scan always starts a completely fresh
            // session: any pages staged during an earlier visit are discarded
            // first so the user never compiles stale captures.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateDark)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .clickable {
                        viewModel.startFreshScanSession()
                        cameraOpen = true
                    }
                    .testTag("scan_open_camera"),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(EmeraldSuccess.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DocumentScanner,
                            contentDescription = "Open camera scanner",
                            tint = EmeraldSuccess,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Open camera & start scanning",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = "AUTO CROP • DESKEW • MAGIC COLOR • B&W",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = TextTertiary
                        )
                    )
                }
            }

            scannerError?.let { message ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(CrimsonPrimary.copy(alpha = 0.12f))
                        .border(1.dp, CrimsonPrimary.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(12.dp)
                        .testTag("scan_error_banner")
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall.copy(color = CrimsonPrimary)
                    )
                }
            }

            // Primary actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        // Every Scan tap starts a completely fresh session: any
                        // pages staged during an earlier visit are discarded so
                        // the user never compiles stale captures.
                        viewModel.startFreshScanSession()
                        cameraOpen = true
                    },
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldSuccess),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("scan_to_pdf_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = if (scanPages.isEmpty()) "SCAN" else "SCAN MORE",
                        style = CameraWorkbenchLabel
                    )
                }

                Button(
                    onClick = { viewModel.convertScansToPdf() },
                    enabled = scanPages.isNotEmpty() && !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = CrimsonPrimary),
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                        .testTag("scan_convert_pdf_button")
                ) {
                    if (isProcessing) {
                        // Visual feedback on the confirm/convert button while
                        // the pages are being compiled into a PDF.
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PictureAsPdf,
                            // "Convert to PDF" icon semantics handled by text label.
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = if (isProcessing) "COMPILING…" else "TO PDF",
                        style = CameraWorkbenchLabel
                    )
                }
            }

            Button(
                onClick = { viewModel.runOcrOnScans() },
                enabled = scanPages.isNotEmpty() && !isProcessing,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("scan_ocr_button")
            ) {
                Icon(
                    imageVector = Icons.Default.DocumentScanner,
                    contentDescription = null,
                    tint = TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "EXTRACT TEXT (OCR)",
                    style = CameraWorkbenchLabel.copy(color = TextPrimary)
                )
            }

            if (scanPages.isNotEmpty()) {
                // Preview thumbnail strip (same shape as JPG->PDF's page list):
                // every captured page is visible, tappable, reorderable and
                // removable, instead of the old bare "N pages staged" text.
                ScanPageThumbnailStrip(
                    pages = scanPages,
                    bitmaps = scanPageBitmaps,
                    isLoading = isLoadingScanBitmaps,
                    onRemovePage = { page -> viewModel.removeScanPage(page) },
                    onRotatePage = { page -> viewModel.rotateScanPage(page) },
                    onMovePage = { from, to -> viewModel.moveScanPage(from, to) },
                    onEditPage = { index -> editingPageIndex = index },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    // ==========================================
    // PAGE EDIT SHEET
    //
    // Tapping a thumbnail opens the same editor JPG->PDF uses: rotate, zoom
    // and the drawing/annotation tools.
    // ==========================================
    val editable = editingPageIndex
    if (editable != null && scanPageBitmaps.isNotEmpty()) {
        PageEditSheet(
            pages = scanPageBitmaps,
            initialPage = editable.coerceIn(0, scanPageBitmaps.lastIndex),
            onDismiss = { editingPageIndex = null },
            onRotatePage = { index ->
                // Rotation is applied to the source page in the ViewModel so
                // the compiled PDF and the strip both pick it up.
                scanPages.getOrNull(index)?.let { viewModel.rotateScanPage(it) }
            },
            onRemovePage = { index ->
                scanPages.getOrNull(index)?.let { page ->
                    viewModel.removeScanPage(page)
                    editingPageIndex = null
                }
            },
            onAnnotatePage = { _, _ -> }
        )
    }
}

/**
 * Horizontally scrollable thumbnail strip of the staged scan pages.
 *
 * Mirrors the page list used by JPG->PDF: page number badge, drag handle to
 * reorder, tap to open the shared page editor, and a remove button. Pages that
 * have not finished decoding show a placeholder so the strip never appears
 * empty while [isLoading] is true.
 */
@Composable
private fun ScanPageThumbnailStrip(
    pages: List<ScanPage>,
    bitmaps: List<Bitmap>,
    isLoading: Boolean,
    onRemovePage: (ScanPage) -> Unit,
    onRotatePage: (ScanPage) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onEditPage: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "PAGES • ${pages.size}",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp,
                    color = TextTertiary,
                    fontWeight = FontWeight.Bold
                )
            )
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = TextTertiary
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            pages.forEachIndexed { index, page ->
                val bmp = bitmaps.getOrNull(index)
                Box(
                    modifier = Modifier
                        .size(width = 72.dp, height = 96.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(SlateDark)
                        .border(
                            1.dp,
                            if (bmp != null) SlateBorder else SlateBorder.copy(alpha = 0.4f),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { onEditPage(index) }
                        .testTag("scan_page_thumb_$index")
                ) {
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "Scan page ${index + 1}",
                            modifier = Modifier.fillMaxWidth(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .align(Alignment.Center),
                            strokeWidth = 2.dp,
                            color = TextTertiary
                        )
                    }

                    // Page number badge.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .clip(CircleShape)
                            .background(SlateDark.copy(alpha = 0.75f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // Remove.
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(CrimsonPrimary.copy(alpha = 0.9f))
                            .clickable { onRemovePage(page) }
                            .testTag("scan_page_remove_$index"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "✕",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }

                    // Rotate + drag-to-reorder handle.
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(SlateDark.copy(alpha = 0.75f))
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .clickable { onRotatePage(page) }
                                .padding(2.dp)
                        ) {
                            Text(
                                text = "⟳",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White,
                                    fontSize = 12.sp
                                )
                            )
                        }
                        Box(
                            modifier = Modifier
                                .pointerInput(pages) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { },
                                        onDragEnd = { },
                                        onDragCancel = { },
                                        onDrag = { change, drag ->
                                            change.consume()
                                            // Horizontal drag moves the page
                                            // left/right through the strip.
                                            val step = if (drag.x > 18f) 1 else if (drag.x < -18f) -1 else 0
                                            if (step != 0) {
                                                onMovePage(index, (index + step).coerceIn(0, pages.lastIndex))
                                            }
                                        }
                                    )
                                }
                                .padding(2.dp)
                        ) {
                            Text(
                                text = "⠿",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    color = Color.White,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private val CameraWorkbenchLabel = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    color = Color.White
)
