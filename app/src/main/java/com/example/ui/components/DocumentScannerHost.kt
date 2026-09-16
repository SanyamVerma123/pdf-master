package com.example.ui.components

import androidx.camera.core.CameraSelector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.launch
import com.example.data.model.ScanPage
import com.example.engine.ScanFilter
import com.example.engine.ScanQuality
import com.example.scanner.CameraXController

/**
 * Stateful host that owns the [CameraXController] instance for the lifetime of
 * the scanner screen and pushes capture/filter/done events into the ViewModel.
 *
 * CameraX controllers must be created and bound once per screen entry, so this
 * composable deliberately keeps that object out of the ViewModel.
 */
@Composable
fun DocumentScannerHost(
    scanPages: List<ScanPage>,
    scanFilter: ScanFilter,
    scanQuality: ScanQuality,
    isProcessing: Boolean,
    lensFacing: Int,
    onProcessCapture: suspend (android.net.Uri) -> Unit,
    onRefilterPage: (ScanPage) -> Unit,
    onRemovePage: (ScanPage) -> Unit,
    onPickFromGallery: (List<android.net.Uri>) -> Unit,
    onFilterSelected: (ScanFilter) -> Unit,
    onQualitySelected: (ScanQuality) -> Unit,
    onFlipCamera: (Int) -> Unit,
    onDone: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraController = remember { CameraXController(context) }
    val captureScope = rememberCoroutineScope()

    // The preview surface lives in the composable tree (AndroidView), so we
    // build the PreviewView here and hand it both to the bind and to the screen.
    val previewView = remember {
        android.view.ViewGroup.LayoutParams.MATCH_PARENT.let { _ ->
            androidx.camera.view.PreviewView(context).apply {
                layoutParams = android.widget.FrameLayout.LayoutParams(it, it)
            }
        }
    }

    // Flip needs a full rebind, which [DocumentScannerScreen] reacts to.
    LaunchedEffect(lensFacing) {
        cameraController.bind(lifecycleOwner, previewView, lensFacing)
    }

    DisposableEffect(Unit) {
        onDispose { cameraController.unbind() }
    }

    DocumentScannerScreen(
        scanPages = scanPages,
        activeFilter = scanFilter,
        scanQuality = scanQuality,
        isProcessing = isProcessing,
        cameraController = cameraController,
        previewView = previewView,
        onCaptureRequested = { rawUri ->
            captureScope.launch {
                onProcessCapture(rawUri)
            }
        },
        onApplyFilter = { page, filter ->
            // Re-run the pipeline with the newly chosen preset.
            onRefilterPage(page)
        },
        onRemovePage = onRemovePage,
        onPickFromGallery = onPickFromGallery,
        onFlipCamera = onFlipCamera,
        onFilterSelected = onFilterSelected,
        onQualitySelected = onQualitySelected,
        onDone = onDone,
        onBack = onBack,
        modifier = modifier
    )
}