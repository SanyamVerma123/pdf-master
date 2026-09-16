package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.example.engine.ScanFilter
import com.example.engine.ScanQuality
import com.example.scanner.CameraSessionState
import com.example.scanner.CameraXController
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.SlateBlack
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * Full-screen live document scanner: CameraX preview, capture, filter strip,
 * thumbnail carousel and a review sheet. All heavy work is delegated to
 * [CameraXController] and [com.example.engine.DocumentScanner].
 *
 * @param scanPages already captured + processed pages for this session.
 * @param onCaptureRequested called with the raw capture Uri; the caller runs the
 *   enhance pipeline and appends the result to [scanPages].
 * @param onApplyFilter called when the user re-filters an existing page.
 */
@Composable
fun DocumentScannerScreen(
    scanPages: List<ScanPageUi>,
    activeFilter: ScanFilter,
    scanQuality: ScanQuality,
    isProcessing: Boolean,
    onCaptureRequested: (Uri) -> Unit,
    onApplyFilter: (ScanPageUi, ScanFilter) -> Unit,
    onRemovePage: (ScanPageUi) -> Unit,
    onPickFromGallery: (List<Uri>) -> Unit,
    onFlipCamera: () -> Unit,
    onFilterSelected: (ScanFilter) -> Unit,
    onQualitySelected: (ScanQuality) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    cameraController: CameraXController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraState by cameraController.state.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var rawCaptureJob by remember { mutableStateOf<Job?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(
            context.checkSelfPermission(Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Preview surface; created once and bound as soon as permission is granted.
    val previewView = remember { PreviewView(context) }

    LaunchedEffect(hasCameraPermission) {
        if (hasCameraPermission) {
            cameraController.bind(lifecycleOwner, previewView)
        }
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.unbind() }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(SlateBlack)
            .testTag("scanner_screen_root")
    ) {
        if (!hasCameraPermission) {
            CameraPermissionRationale(
                onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            AndroidView(
                factory = { previewView },
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("camera_preview"),
                update = { view ->
                    // Keep the preview scale mode in sync with the view state.
                    view.scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            )

            // Dim the sheet area below the document frame for the scanner look.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f))
            )

            ScannerOverlayGraphic(
                isReady = cameraState.isCameraReady,
                modifier = Modifier.align(Alignment.Center)
            )

            // Top control bar: close, flash, flip, quality.
            ScannerTopBar(
                cameraState = cameraState,
                quality = scanQuality,
                onBack = onBack,
                onToggleFlash = { cameraController.toggleTorch() },
                onFlip = onFlipCamera,
                onQualitySelected = onQualitySelected,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
            )

            // Bottom stack: filter strip + capture row + page carousel.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            ) {
                ScanFilterStrip(
                    activeFilter = activeFilter,
                    onFilterSelected = onFilterSelected,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))

                CaptureControlsRow(
                    pageCount = scanPages.size,
                    canCapture = cameraState.canCapture && !isProcessing,
                    isProcessing = isProcessing,
                    onCapture = {
                        val rawDir = java.io.File(context.cacheDir, "scan_raw")
                        cameraController.capture(
                            onResult = { bitmap ->
                                rawCaptureJob = coroutineScope.launch {
                                    val rawUri = cameraController.saveRawCapture(bitmap, rawDir)
                                    onCaptureRequested(rawUri)
                                }
                            },
                            onError = { /* surfaced via controller state */ }
                        )
                    },
                    onPickFromGallery = onPickFromGallery,
                    onDone = onDone,
                    modifier = Modifier.fillMaxWidth()
                )

                if (scanPages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    ScanPageCarousel(
                        pages = scanPages,
                        onRemovePage = onRemovePage,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * One processed page as the scanner UI sees it.
 */
data class ScanPageUi(
    val id: String,
    val processedUri: Uri,
    val sourceUri: Uri,
    val filter: ScanFilter,
    val width: Int,
    val height: Int
)

/**
 * Animated guide frame + crosshair shown over the live preview.
 */
@Composable
private fun ScannerOverlayGraphic(
    isReady: Boolean,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .width(280.dp)
                .aspectRatio(0.72f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Transparent)
                .border(
                    width = if (isReady) 2.dp else 1.dp,
                    color = if (isReady) EmeraldSuccess else TextTertiary.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(12.dp)
                )
                .testTag("scanner_guide_frame")
        )
        Text(
            text = when {
                !isReady -> "Starting camera…"
                else -> "Align the document inside the frame"
            },
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                color = TextSecondary
            )
        )
    }
}


/**
 * Camera permission rationale shown when CAMERA has not been granted.
 */
@Composable
private fun CameraPermissionRationale(
    onRequest: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .padding(28.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(SlateDark)
            .border(1.dp, SlateBorder, RoundedCornerShape(20.dp))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(CrimsonPrimary.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = null,
                tint = CrimsonPrimary,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = "Camera access needed",
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        )
        Text(
            text = "OmniPDF needs the camera to scan documents. Photos are processed " +
                    "on-device and never leave your phone.",
            style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(SlateSurfaceVariant)
                    .border(1.dp, SlateBorder, RoundedCornerShape(10.dp))
                    .clickable { onBack() }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("scanner_permission_back")
            ) {
                Text(
                    text = "NOT NOW",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                )
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(CrimsonPrimary)
                    .clickable { onRequest() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .testTag("scanner_permission_grant")
            ) {
                Text(
                    text = "GRANT CAMERA",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
            }
        }
    }
}

/**
 * Close / flash / flip / quality controls pinned above the preview.
 */
@Composable
private fun ScannerTopBar(
    cameraState: CameraSessionState,
    quality: ScanQuality,
    onBack: () -> Unit,
    onToggleFlash: () -> Unit,
    onFlip: () -> Unit,
    onQualitySelected: (ScanQuality) -> Unit,
    modifier: Modifier = Modifier
) {
    var showQualityMenu by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        ScannerCircleButton(
            icon = Icons.Default.Close,
            contentDescription = "Close scanner",
            testTag = "scanner_close_button",
            onClick = onBack
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ScannerCircleButton(
                icon = if (cameraState.isFlashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = if (cameraState.isFlashOn) "Flash on" else "Flash off",
                testTag = "scanner_flash_button",
                highlight = cameraState.isFlashOn,
                onClick = onToggleFlash
            )
            ScannerCircleButton(
                icon = Icons.Default.FlipCameraAndroid,
                contentDescription = "Flip camera",
                testTag = "scanner_flip_button",
                onClick = onFlip
            )
            ScannerCircleButton(
                icon = Icons.Default.Settings,
                contentDescription = "Scan quality",
                testTag = "scanner_quality_button",
                onClick = { showQualityMenu = !showQualityMenu }
            )
        }
    }

    AnimatedVisibility(
        visible = showQualityMenu,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 14.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(SlateDark)
                .border(1.dp, SlateBorder, RoundedCornerShape(14.dp))
                .padding(10.dp),
        ) {
            ScanQuality.entries.forEach { entry ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            onQualitySelected(entry)
                            showQualityMenu = false
                        }
                        .padding(vertical = 8.dp, horizontal = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = if (quality == entry) EmeraldSuccess else TextPrimary
                        )
                    )
                    if (quality == entry) {
                        Text(
                            text = "ACTIVE",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = EmeraldSuccess,
                                fontWeight = FontWeight.Bold
                            )
                        )
                    }
                }
            }
        }
    }
}

/**
 * Reusable circular translucent control button used by the scanner chrome.
 */
@Composable
private fun ScannerCircleButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    testTag: String,
    onClick: () -> Unit,
    highlight: Boolean = false
) {
    Box(
        modifier = Modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (highlight) CrimsonPrimary else Color.Black.copy(alpha = 0.55f))
            .border(
                1.dp,
                if (highlight) CrimsonPrimary else SlateBorder,
                CircleShape
            )
            .clickable { onClick() }
            .testTag(testTag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (highlight) Color.White else TextPrimary,
            modifier = Modifier.size(20.dp)
        )
    }
}



/**
 * Horizontally scrollable strip of scanner enhancement presets.
 */
@Composable
private fun ScanFilterStrip(
    activeFilter: ScanFilter,
    onFilterSelected: (ScanFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp)
    ) {
        items(ScanFilter.entries) { filter ->
            val selected = filter == activeFilter
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) CrimsonPrimary else Color.Black.copy(alpha = 0.55f))
                    .border(
                        1.dp,
                        if (selected) CrimsonPrimary else SlateBorder,
                        RoundedCornerShape(20.dp)
                    )
                    .clickable { onFilterSelected(filter) }
                    .padding(horizontal = 14.dp, vertical = 7.dp)
                    .testTag("scanner_filter_${filter.key}")
            ) {
                Text(
                    text = filter.title,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (selected) Color.White else TextSecondary
                    )
                )
            }
        }
    }
}

/**
 * Big shutter button flanked by gallery import and "done" actions.
 */
@Composable
private fun CaptureControlsRow(
    pageCount: Int,
    canCapture: Boolean,
    isProcessing: Boolean,
    onCapture: () -> Unit,
    onPickFromGallery: (List<Uri>) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 30)
    ) { uris ->
        if (uris.isNotEmpty()) onPickFromGallery(uris)
    }

    Row(
        modifier = modifier
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: gallery import
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(Color.Black.copy(alpha = 0.55f))
                .border(1.dp, SlateBorder, CircleShape)
                .clickable {
                    galleryLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                }
                .testTag("scanner_gallery_button"),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.PhotoLibrary,
                contentDescription = "Import from gallery",
                tint = TextPrimary,
                modifier = Modifier.size(22.dp)
            )
        }

        // Center: shutter
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(if (canCapture) CrimsonPrimary else SlateSurface)
                .border(
                    width = 4.dp,
                    color = if (canCapture) Color.White else SlateBorder,
                    shape = CircleShape
                )
                .clickable(enabled = canCapture) { onCapture() }
                .testTag("scanner_capture_button"),
            contentAlignment = Alignment.Center
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(30.dp)
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Camera,
                    contentDescription = "Capture scan",
                    tint = if (canCapture) Color.White else TextTertiary,
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // Right: done / page count
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (pageCount > 0) EmeraldSuccess else Color.Black.copy(alpha = 0.05f))
                .border(
                    1.dp,
                    if (pageCount > 0) EmeraldSuccess else SlateBorder,
                    CircleShape
                )
                .clickable(enabled = pageCount > 0) { onDone() }
                .testTag("scanner_done_button"),
            contentAlignment = Alignment.Center
        ) {
            if (pageCount > 0) {
                Text(
                    text = pageCount.toString(),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Horizontal preview carousel of already captured pages.
 */
@Composable
private fun ScanPageCarousel(
    pages: List<ScanPageUi>,
    onRemovePage: (ScanPageUi) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(pages) { page ->
            Box(
                modifier = Modifier
                    .width(72.dp)
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SlateDark)
                    .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                    .testTag("scanner_page_thumb_${page.id}")
            ) {
                AsyncImage(
                    model = page.processedUri,
                    contentDescription = "Scanned page ${page.processedUri.lastPathSegment}",
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable { onRemovePage(page) }
                        .testTag("scanner_page_remove_${page.id}"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove page",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                        )
                }
            }
        }
    }
}

