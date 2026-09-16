package com.example.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import coil.compose.AsyncImage
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
    onOcrScan: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 50)
    ) { uris ->
        if (uris.isNotEmpty()) {
            onAddImages(uris)
        }
    }

    var showAdvancedSettings by remember { mutableStateOf(false) }

    // Page editor: rendered bitmaps of the staged images, decoded on demand the
    // first time the user opens the sheet (they are only needed for editing).
    var editingPageIndex by remember { mutableStateOf<Int?>(null) }
    var pageBitmaps by remember { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var isLoadingPageBitmaps by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                        text = "JPG • PNG • WEBP • HEIC (Instant compilation)",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            color = TextTertiary
                        )
                    )
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

        // OCR Text Extraction Shortcut
        if (selectedImages.isNotEmpty() && onOcrScan != null) {
            OutlinedButton(
                onClick = onOcrScan,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .testTag("ocr_from_images_button")
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.DocumentScanner,
                        contentDescription = null,
                        tint = CrimsonPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "EXTRACT TEXT WITH OCR SCANNER",
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = 0.5.sp
                        )
                    )
                }
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
                        set(index, android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true))
                    }
                },
                onRemovePage = { index ->
                    onRemoveImage(index)
                    pageBitmaps = pageBitmaps.toMutableList().apply { removeAt(index) }
                    editingPageIndex = null
                },
                onAnnotatePage = { _, _ -> }
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
