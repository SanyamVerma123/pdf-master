package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.request.ImageRequest
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.engine.CompressionLevel
import com.example.engine.ImagePdfConfig
import com.example.engine.PageMargin
import com.example.engine.PageSize
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.SlateBorder
import com.example.ui.theme.SlateDark
import com.example.ui.theme.SlateSurfaceVariant
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.TextTertiary

/**
 * Prepares a temp capture file in app-private cache and hands its [FileProvider] Uri to the
 * camera contract. Kept outside the composable so the permission callback can call it too,
 * which is what lets a first-time user tap the button once and get the camera immediately.
 */
private fun startCameraCapture(
    context: Context,
    cameraOutUriSetter: (Uri) -> Unit,
    launch: (Uri) -> Unit
) {
    val dir = java.io.File(context.cacheDir, "camera_captures").apply { mkdirs() }
    val file = java.io.File(dir, "img_${System.currentTimeMillis()}.jpg")
    // The file must exist before the Uri is handed over, or some camera apps fail silently.
    file.createNewFile()
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    cameraOutUriSetter(uri)
    launch(uri)
}

@Composable
fun ImageToPdfWorkbench(
    selectedImages: List<Uri>,
    config: ImagePdfConfig,
    onAddImages: (List<Uri>) -> Unit,
    onRemoveImage: (Int) -> Unit,
    onMoveImage: (Int, Int) -> Unit,
    onClearImages: () -> Unit,
    onUpdateConfig: ((ImagePdfConfig) -> ImagePdfConfig) -> Unit,
    onConvert: () -> Unit,
    onReplaceImage: (Int, Uri) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddImages(uris)
        }
    }

    // CAMERA: capture a single photo straight into the staged page list. The
    // contract takes a temp Uri we own in app-private cache, so the result is a
    // real file Uri the export pipeline can read directly (no ContentResolver
    // pick-permission needed, no stale-stream problem).
    var cameraOutUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { saved ->
        val uri = cameraOutUri
        cameraOutUri = null
        // Some stock cameras report success but write nothing - or the user
        // backed out. Either way, never stage a Uri with no image behind it,
        // because that is what produces a zero-page "corrupted" PDF.
        if (saved && uri != null) {
            scope.launch(Dispatchers.IO) {
                val ok = runCatching {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes()?.isNotEmpty() == true }
                }.getOrNull() == true
                if (ok) onAddImages(listOf(uri))
            }
        }
    }

    // The TakePicture contract hands a content:// Uri to the camera app, which needs
    // the CAMERA permission granted by US to function. Declaring it in the manifest is
    // not enough on API 23+; launching without the runtime grant crashes the whole app
    // (the camera process dies and takes the foreground activity with it), which is the
    // "app closes when I tap camera" report.
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) startCameraCapture(context, cameraOutUriSetter = { cameraOutUri = it }, launch = { uri -> cameraLauncher.launch(uri) })
    }

    var showAdvancedSettings by remember { mutableStateOf(false) }

    // Page editor: rendered bitmaps of the staged images, decoded on demand the
    // first time the user opens the sheet (they are only needed for editing).
    var editingPageIndex by remember { mutableStateOf<Int?>(null) }
    var pageBitmaps by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var isLoadingPageBitmaps by remember { mutableStateOf(false) }

    // Persists an edited page bitmap and swaps the staged Uri so the preview
    // grid AND the export pipeline both reflect the edit.
    fun persistEdit(index: Int, bmp: android.graphics.Bitmap) {
        scope.launch {
            persistEditedPage(
                context = context,
                uris = selectedImages,
                index = index,
                bitmap = bmp
            ) { updated -> updated.forEachIndexed { i, uri ->
                    if (uri != selectedImages.getOrNull(i)) onReplaceImage(i, uri)
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(24.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "IMAGE WORKBENCH",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 1.2.sp,
                        color = CrimsonPrimary,
                        fontWeight = FontWeight.Bold
                    )
                )
                Text(
                    text = if (selectedImages.isEmpty()) "Select photos to compile" else "${selectedImages.size} pages staged",
                    style = MaterialTheme.typography.titleSmall.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (selectedImages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SlateDark)
                            .border(1.dp, SlateBorder, RoundedCornerShape(8.dp))
                            .clickable { onClearImages() }
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = "CLEAR",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = TextTertiary
                            )
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (showAdvancedSettings) CrimsonPrimary.copy(alpha = 0.15f) else SlateDark)
                        .border(
                            1.dp,
                            if (showAdvancedSettings) CrimsonPrimary else SlateBorder,
                            RoundedCornerShape(8.dp)
                        )
                        .clickable { showAdvancedSettings = !showAdvancedSettings }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Settings",
                            tint = if (showAdvancedSettings) CrimsonPrimary else TextSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "CONFIG",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (showAdvancedSettings) CrimsonPrimary else TextSecondary
                            )
                        )
                    }
                }
            }
        }

        // Image Selection Area
        if (selectedImages.isEmpty()) {
            // Empty / Staging Prompt Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(130.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateDark)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .clickable {
                        photoPickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                    .testTag("pick_images_button"),
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
                            .background(CrimsonPrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddPhotoAlternate,
                            contentDescription = "Pick Photos",
                            tint = CrimsonPrimary,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Tap to choose images from gallery",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.Medium
                        )
                    )
                    Text(
                        text = "JPG • PNG • WEBP • HEIC • AVIF • ANY FORMAT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextTertiary
                        )
                    )
                    // Camera: capture a page straight into this PDF.
                    OutlinedButton(
                        onClick = {
                            if (hasCameraPermission) {
                                startCameraCapture(
                                    context = context,
                                    cameraOutUriSetter = { cameraOutUri = it },
                                    launch = { uri -> cameraLauncher.launch(uri) }
                                )
                            } else {
                                // Request first; the result callback starts the capture
                                // itself so the user does not have to tap twice.
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                        },
                        border = androidx.compose.foundation.BorderStroke(1.dp, CrimsonPrimary),
                        modifier = Modifier.testTag("camera_capture_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.PhotoCamera,
                            contentDescription = "Capture with camera",
                            tint = CrimsonPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "USE CAMERA",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = CrimsonPrimary
                            )
                        )
                    }
                }
            }
        } else {
            // ==========================================
            // 2-PER-ROW PAGE GRID
            //
            // The previous single-row LazyRow crushed every selected image
            // into one thin strip at the top. A fixed 2-column grid gives each
            // page a real preview and keeps long lists scrollable.
            // ==========================================
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PAGES (${selectedImages.size}) • TAP TO EDIT",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = TextTertiary
                        )
                    )
                    Text(
                        text = "LONG-PRESS TO DRAG",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = TextTertiary
                        )
                    )
                }

                val gridState = rememberLazyGridState()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp)
                ) {
                    itemsIndexed(selectedImages) { index, uri ->
                        PageGridCell(
                            index = index,
                            total = selectedImages.size,
                            uri = uri,
                            onRemove = { onRemoveImage(index) },
                            onMoveLeft = { onMoveImage(index, index - 1) },
                            onMoveRight = { onMoveImage(index, index + 1) },
                            onEdit = { editingPageIndex = index },
                            modifier = Modifier.testTag("image_page_cell_$index")
                        )
                    }
                    // Add-more tile as the last cell of the grid.
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(0.75f)
                                .clip(RoundedCornerShape(12.dp))
                                .background(SlateDark)
                                .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
                                .clickable {
                                    photoPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                                .testTag("add_more_images_button"),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AddPhotoAlternate,
                                    contentDescription = "Add More",
                                    tint = CrimsonPrimary,
                                    modifier = Modifier.size(22.dp)
                                )
                                Text(
                                    text = "ADD MORE",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        color = TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }

        // Advanced Configuration Drawer
        AnimatedVisibility(
            visible = showAdvancedSettings,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(SlateDark)
                    .border(1.dp, SlateBorder, RoundedCornerShape(16.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Page Size & Orientation Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PAGE FORMAT",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = TextTertiary
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PageSize.entries.forEach { size ->
                                val active = config.pageSize == size
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) CrimsonPrimary else SlateSurfaceVariant)
                                        .clickable { onUpdateConfig { it.copy(pageSize = size) } }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = size.title,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                                            color = if (active) Color.White else TextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Margins & Quality
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "PAGE MARGIN",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                color = TextTertiary
                            )
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            PageMargin.entries.forEach { margin ->
                                val active = config.margin == margin
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(if (active) CrimsonPrimary else SlateSurfaceVariant)
                                        .clickable { onUpdateConfig { it.copy(margin = margin) } }
                                        .padding(horizontal = 8.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = margin.title,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontSize = 11.sp,
                                            color = if (active) Color.White else TextSecondary
                                        )
                                    )
                                }
                            }
                        }
                    }
                }

                // Quality Compression
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "COMPRESSION QUALITY",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = TextTertiary
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        CompressionLevel.entries.forEach { level ->
                            val active = config.compression == level
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (active) CrimsonPrimary else SlateSurfaceVariant)
                                    .clickable { onUpdateConfig { it.copy(compression = level) } }
                                    .padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(
                                    text = level.title,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = 11.sp,
                                        color = if (active) Color.White else TextSecondary
                                    )
                                )
                            }
                        }
                    }
                }

                // Watermark input
                OutlinedTextField(
                    value = config.watermarkText,
                    onValueChange = { text -> onUpdateConfig { it.copy(watermarkText = text) } },
                    label = { Text("Watermark Text (Optional)", fontSize = 11.sp) },
                    placeholder = { Text("e.g. CONFIDENTIAL / DRAFT", fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CrimsonPrimary,
                        unfocusedBorderColor = SlateBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                // Custom file name
                OutlinedTextField(
                    value = config.customFileName,
                    onValueChange = { name -> onUpdateConfig { it.copy(customFileName = name) } },
                    label = { Text("Custom File Name (Optional)", fontSize = 11.sp) },
                    placeholder = { Text("e.g. Scanned_Receipts.pdf", fontSize = 11.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = CrimsonPrimary,
                        unfocusedBorderColor = SlateBorder,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // Action Button: Convert Now
        Button(
            onClick = onConvert,
            enabled = selectedImages.isNotEmpty(),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = CrimsonPrimary,
                disabledContainerColor = SlateBorder
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("convert_images_button")
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = if (selectedImages.isEmpty()) "SELECT IMAGES TO CONVERT" else "GENERATE PDF (${selectedImages.size} PAGES)",
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = Color.White
                    )
                )
            }
        }
    }

    // ==========================================
    // PAGE EDIT SHEET
    //
    // Tapping any grid cell opens the shared editor: Rotate, Zoom and the
    // freehand annotation tools all live here, so every tool that stages pages
    // gets identical editing. The editor works on decoded bitmaps, so decode
    // the staged Uris here before opening it.
    // ==========================================
    editingPageIndex?.let { pageIndex ->
        LaunchedEffect(selectedImages) {
            if (pageBitmaps.size != selectedImages.size) {
                pageBitmaps = emptyList()
            }
        }
        if (pageBitmaps.isEmpty() && !isLoadingPageBitmaps) {
            isLoadingPageBitmaps = true
            scope.launch(Dispatchers.IO) {
                val loader = ImageLoader(context)
                val decoded = selectedImages.mapNotNull { uri ->
                    runCatching {
                        val request = ImageRequest.Builder(context)
                            .data(uri)
                            .build()
                        loader.execute(request).drawable?.toBitmap()
                    }.getOrNull()
                }
                pageBitmaps = decoded
                isLoadingPageBitmaps = false
            }
        }
        if (pageBitmaps.isNotEmpty()) {
            PageEditSheet(
                pages = pageBitmaps,
                initialPage = pageIndex.coerceIn(0, pageBitmaps.lastIndex),
                onDismiss = { editingPageIndex = null },
                onRotatePage = { index ->
                    pageBitmaps = pageBitmaps.toMutableList().apply {
                        val bmp = getOrNull(index) ?: return@PageEditSheet
                        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                        val rotated = android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                        set(index, rotated)
                        persistEdit(index, rotated)
                    }
                },
                onRemovePage = { index ->
                    onRemoveImage(index)
                    pageBitmaps = pageBitmaps.toMutableList().apply { removeAt(index) }
                    editingPageIndex = null
                },
                onAnnotatePage = { index, strokes ->
                    val bmp = pageBitmaps.getOrNull(index) ?: return@PageEditSheet
                    // Burn annotations into the staged page so they survive into
                    // the exported PDF (the export reads the Uri list, not memory).
                    val rendered = renderAnnotationsToBitmap(bmp, strokes)
                    pageBitmaps = pageBitmaps.toMutableList().apply { set(index, rendered) }
                    persistEdit(index, rendered)
                },
                onCropPage = { index, crop ->
                    pageBitmaps = pageBitmaps.toMutableList().apply {
                        val bmp = getOrNull(index) ?: return@PageEditSheet
                        // Crop the staged bitmap in place so the result shows in
                        // the preview grid and lands in the exported PDF.
                        val cropped = cropBitmapNormalized(bmp, crop)
                        set(index, cropped)
                        persistEdit(index, cropped)
                    }
                }
            )
        }
    }
}

/**
 * One tile of the 2-per-row page grid.
 *
 * Shows the image, its page number, and the same per-page controls the old
 * carousel had (remove + nudge left/right), and opens the shared editor on tap.
 */
@Composable
private fun PageGridCell(
    index: Int,
    total: Int,
    uri: Uri,
    onRemove: () -> Unit,
    onMoveLeft: () -> Unit,
    onMoveRight: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .border(1.dp, SlateBorder, RoundedCornerShape(12.dp))
            .clickable { onEdit() }
    ) {
        AsyncImage(
            model = uri,
            contentDescription = "Page ${index + 1}",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Top bar: Page Number Pill + Remove Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(
                    text = String.format("%02d", index + 1),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = Color.White
                    )
                )
            }

            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .clickable { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove",
                    tint = Color.White,
                    modifier = Modifier.size(12.dp)
                )
            }
        }

        // Bottom Reorder controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.6f))
                .padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            // Drag handle: visual affordance for the drag-to-reorder gesture.
            Icon(
                imageVector = Icons.Default.DragIndicator,
                contentDescription = "Drag to reorder",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onMoveLeft() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Move Left",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
            if (index < total - 1) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onMoveRight() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Move Right",
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
    }
}
