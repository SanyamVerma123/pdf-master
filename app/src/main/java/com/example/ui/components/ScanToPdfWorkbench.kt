package com.example.ui.components
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ScanPage
import com.example.engine.ScanFilter
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary
import com.example.ui.viewmodel.PdfConverterViewModel

/**
 * Camera-first entry point for the "Scan to PDF" tool.
 *
 * Shows the live scanner viewport plus the staged-page count and primary actions
 * (convert to PDF / run OCR). The camera surface itself is provided by
 * [DocumentScannerHost] once the user taps "Open Camera".
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

            // Open camera CTA
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateDark)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .clickable { cameraOpen = true }
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
                            imageVector = DocumentsScanner,
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
                    onClick = { cameraOpen = true },
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
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        // "Convert to PDF" icon semantics handled by text label.
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = "TO PDF",
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
                ScanPageSummaryRow(pages = scanPages)
            }
        }
    }
}

@Composable
private fun ScanPageSummaryRow(pages: List<ScanPage>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SlateDark)
            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${pages.size} page(s) staged",
            style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
        )
        Text(
            text = pages.joinToString(" • ") { it.filter.title },
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextTertiary
            )
        )
    }
}

private val CameraWorkbenchLabel = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    color = Color.White
)

private val DocumentsScanner = Icons.Default.DocumentScanner
