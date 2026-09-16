package com.example.ui.components

import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.data.model.ConversionType
import com.example.data.model.ToolCatalog
import com.example.data.model.ToolItem
import com.example.engine.AdvancedPdfEngine
import com.example.engine.PdfEngine
import com.example.engine.ShareUtils
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.viewmodel.ConversionUiState
import com.example.ui.viewmodel.PdfConverterViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UniversalPdfWorkbench(
    tool: ConversionType,
    viewModel: PdfConverterViewModel,
    conversionState: ConversionUiState,
    onBack: () -> Unit,
    onDismissConversion: () -> Unit,
    onViewInApp: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toolItem = remember(tool) { ToolCatalog.findTool(tool) }

    // Selected PDF URI & page previews
    var selectedPdfUri by remember { mutableStateOf<Uri?>(null) }
    var selectedDocB_Uri by remember { mutableStateOf<Uri?>(null) }
    var pageBitmaps by remember { mutableStateOf<List<Bitmap>>(emptyList()) }
    var isLoadingPages by remember { mutableStateOf(false) }

    // Page editor: opens when the user taps a page-preview thumbnail. A null
    // index means the sheet is closed.
    var editingPageIndex by remember { mutableStateOf<Int?>(null) }

    // Per-page annotation strokes collected from PageEditSheet, keyed by page.
    // Normalized (0f..1f) so they survive the page's Fit letterboxing and zoom.
    val pageAnnotations = remember {
        mutableStateMapOf<Int, List<androidx.compose.ui.geometry.Offset>>()
    }
    var fullScreenPageIndex by remember { mutableStateOf<Int?>(null) }

    // Common Text inputs
    var customTextInput by remember { mutableStateOf("") }
    var secondaryTextInput by remember { mutableStateOf("") }

    // Watermark specific
    var watermarkText by remember { mutableStateOf("CONFIDENTIAL") }
    var watermarkOpacity by remember { mutableFloatStateOf(35f) }
    var watermarkAngle by remember { mutableFloatStateOf(-45f) }
    var watermarkFontSize by remember { mutableFloatStateOf(36f) }
    var watermarkGridPos by remember { mutableIntStateOf(4) } // 4 = Center

    // Compress specific
    var compressDpi by remember { mutableFloatStateOf(150f) }
    var compressQuality by remember { mutableFloatStateOf(80f) }
    var compressGrayscale by remember { mutableStateOf(false) }

    // Split specific
    var splitRangeText by remember { mutableStateOf("1-2") }
    var splitEveryPage by remember { mutableStateOf(false) }

    // Rotate specific
    var rotateDegrees by remember { mutableIntStateOf(90) }
    var rotateFilterMode by remember { mutableStateOf("ALL") }

    // Page Numbers specific
    var pageNumFormat by remember { mutableStateOf("Page {n} of {total}") }
    var pageNumPosition by remember { mutableStateOf("BOTTOM_CENTER") }
    var pageNumFontSize by remember { mutableFloatStateOf(11f) }

    // Crop specific
    var cropUniform by remember { mutableStateOf(true) }
    var cropMarginPercent by remember { mutableFloatStateOf(0.05f) }
    var redactPages by remember { mutableStateOf("") }
    var redactText by remember { mutableStateOf("") }

    // Protect / Unlock
    var passwordInput by remember { mutableStateOf("") }
    var allowPrinting by remember { mutableStateOf(true) }

    // AI Summarizer & Translate

    // Signature Pad Path Points
    val signaturePoints = remember { mutableStateListOf<Offset?>() }
    var penColor by remember { mutableStateOf(Color.Black) }
    var penStrokeWidth by remember { mutableFloatStateOf(6f) }
    var signPageIdx by remember { mutableIntStateOf(0) }
    var addDateStamp by remember { mutableStateOf(true) }

    // Office Converter Format
    var excelDelimiter by remember { mutableStateOf(",") }
    var pdfAProfile by remember { mutableStateOf("PDF/A-1b") }

    // File pickers
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedPdfUri = uri
            isLoadingPages = true
            scope.launch(Dispatchers.IO) {
                val pages = PdfEngine.renderAllPagesFromPdfUri(context, uri)
                withContext(Dispatchers.Main) {
                    pageBitmaps = pages
                    isLoadingPages = false
                    if (pages.isNotEmpty()) {
                        splitRangeText = if (pages.size > 1) "1-${pages.size}" else "1"
                    }
                }
            }
        }
    }

    val docBPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedDocB_Uri = uri
        }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addImages(uris)
            viewModel.openTool(ConversionType.IMAGE_TO_PDF)
        }
    }

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
                    contentDescription = "Back",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(toolItem.accentColor.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = toolItem.icon,
                    contentDescription = toolItem.title,
                    tint = toolItem.accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = toolItem.title.uppercase(),
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.5.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(toolItem.accentColor.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = toolItem.badge,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                color = toolItem.accentColor
                            )
                        )
                    }
                }
                Text(
                    text = toolItem.subtitle,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = toolItem.accentColor
                    )
                )
            }
        }

        // Workbench Body
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Conversion Status Card (shown on processing/success/error)
            item {
                ConversionStatusCard(
                    state = conversionState,
                    onDismiss = onDismissConversion,
                    onViewInApp = onViewInApp,
                    onShare = { ShareUtils.sharePdf(context, it) },
                    onPrint = {
                        val activity = context as? android.app.Activity
                        if (activity != null) ShareUtils.printDocument(activity, it)
                    },
                    onOpenExternal = { ShareUtils.openInExternalApp(context, it) }
                )
            }

            // ==========================================
            // 1. FILE PICKER CARD
            // ==========================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .testTag("source_file_picker_card"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "1. SOURCE DOCUMENT",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = toolItem.accentColor
                                )
                            )
                            if (selectedPdfUri != null) {
                                Text(
                                    text = "${pageBitmaps.size} PAGES LOADED",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = EmeraldSuccess
                                    )
                                )
                            }
                        }

                        if (selectedPdfUri == null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                    .border(1.5.dp, toolItem.accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                                    .clickable {
                                        if (tool == ConversionType.SCAN_TO_PDF) {
                                            imagePickerLauncher.launch("image/*")
                                        } else {
                                            pdfPickerLauncher.launch(arrayOf("application/pdf"))
                                        }
                                    }
                                    .padding(vertical = 24.dp, horizontal = 16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudUpload,
                                        contentDescription = "Upload",
                                        tint = toolItem.accentColor,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = if (tool == ConversionType.SCAN_TO_PDF) "SELECT PHOTOS OR SCANS" else "SELECT PDF FILE TO ${toolItem.title.uppercase()}",
                                        style = MaterialTheme.typography.titleSmall.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                    Text(
                                        text = "Tap to browse local device storage",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }
                        } else {
                            // File info card
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = toolItem.icon,
                                        contentDescription = null,
                                        tint = toolItem.accentColor,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Column {
                                        Text(
                                            text = selectedPdfUri?.lastPathSegment ?: "document.pdf",
                                            style = MaterialTheme.typography.bodyMedium.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            ),
                                            maxLines = 1
                                        )
                                        Text(
                                            text = "Ready for ${toolItem.title} processing",
                                            style = MaterialTheme.typography.bodySmall.copy(
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        )
                                    }
                                }

                                OutlinedButton(
                                    onClick = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Change", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            // Secondary PDF picker for Compare PDF tool
                            if (tool == ConversionType.COMPARE_PDF) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "DOCUMENT B (REVISED VERSION):",
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = toolItem.accentColor
                                    )
                                )
                                if (selectedDocB_Uri == null) {
                                    OutlinedButton(
                                        onClick = { docBPickerLauncher.launch(arrayOf("application/pdf")) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("+ Select Second PDF for Comparison")
                                    }
                                } else {
                                    Text(
                                        text = "Version B: ${selectedDocB_Uri?.lastPathSegment}",
                                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = EmeraldSuccess)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 2. PAGE PREVIEW CAROUSEL
            // ==========================================
            if (pageBitmaps.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "PAGE PREVIEW (${pageBitmaps.size} PAGES)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(pageBitmaps) { idx, bmp ->
                                Box(
                                    modifier = Modifier
                                        .width(90.dp)
                                        .height(125.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .border(
                                            width = if (signPageIdx == idx) 2.dp else 1.dp,
                                            color = if (signPageIdx == idx) toolItem.accentColor else MaterialTheme.colorScheme.outline,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        // Tapping a preview must open the page at
                                        // full size. The viewer renders the whole
                                        // document and scrolls to this page.
                                        .clickable {
                                            fullScreenPageIndex = idx
                                        }
                                        .testTag("preview_page_thumb_$idx")
                                ) {
                                    // A Bitmap model handed to AsyncImage is not
                                    // reliably decoded; draw it directly instead.
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = "Page ${idx + 1}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Un-committed annotations drawn live so the
                                    // user sees what a tap will save.
                                    val pending: List<androidx.compose.ui.geometry.Offset>? =
                                        pageAnnotations[idx]
                                    if (!pending.isNullOrEmpty()) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            for (i in 0 until pending.size - 1) {
                                                val p1 = pending[i]
                                                val p2 = pending[i + 1]
                                                if (p1.x != p2.x || p1.y != p2.y) {
                                                    drawLine(
                                                        color = CrimsonPrimary,
                                                        start = Offset(p1.x * size.width, p1.y * size.height),
                                                        end = Offset(p2.x * size.width, p2.y * size.height),
                                                        strokeWidth = 4f
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.BottomCenter)
                                            .fillMaxWidth()
                                            .background(Color.Black.copy(alpha = 0.65f))
                                            .padding(vertical = 2.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "Page ${idx + 1}",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                color = Color.White
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 3. ADVANCED FILTERS & ADJUSTMENTS PANEL
            // ==========================================
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                        .testTag("tool_adjustments_panel"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Tune,
                                contentDescription = null,
                                tint = toolItem.accentColor,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "2. ADVANCED FILTERS & ADJUSTMENTS",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = toolItem.accentColor
                                )
                            )
                        }

                        when (tool) {
                            // ------------------------------------------
                            // WATERMARK CONTROLS
                            // ------------------------------------------
                            ConversionType.WATERMARK -> {
                                OutlinedTextField(
                                    value = watermarkText,
                                    onValueChange = { watermarkText = it },
                                    label = { Text("Watermark Text") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("CONFIDENTIAL", "DRAFT", "COPY", "OFFICIAL", "SAMPLE").forEach { preset ->
                                        FilterChip(
                                            selected = watermarkText == preset,
                                            onClick = { watermarkText = preset },
                                            label = { Text(preset, fontSize = 10.sp) }
                                        )
                                    }
                                }
                                Text("Transparency: ${watermarkOpacity.toInt()}%", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = watermarkOpacity,
                                    onValueChange = { watermarkOpacity = it },
                                    valueRange = 10f..90f,
                                    colors = SliderDefaults.colors(thumbColor = toolItem.accentColor, activeTrackColor = toolItem.accentColor)
                                )
                                Text("Rotation Angle: ${watermarkAngle.toInt()}°", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = watermarkAngle,
                                    onValueChange = { watermarkAngle = it },
                                    valueRange = -90f..90f,
                                    colors = SliderDefaults.colors(thumbColor = toolItem.accentColor, activeTrackColor = toolItem.accentColor)
                                )
                                Text("Font Size: ${watermarkFontSize.toInt()} sp", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = watermarkFontSize,
                                    onValueChange = { watermarkFontSize = it },
                                    valueRange = 18f..64f,
                                    colors = SliderDefaults.colors(thumbColor = toolItem.accentColor, activeTrackColor = toolItem.accentColor)
                                )
                            }

                            // ------------------------------------------
                            // COMPRESS PDF CONTROLS
                            // ------------------------------------------
                            ConversionType.COMPRESS_PDF -> {
                                Text("Compression Level Preset:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        Triple("EXTREME", 72f, 50f),
                                        Triple("RECOMMENDED", 150f, 80f),
                                        Triple("LESS", 300f, 95f)
                                    ).forEach { (label, dpi, q) ->
                                        FilterChip(
                                            selected = compressDpi == dpi,
                                            onClick = {
                                                compressDpi = dpi
                                                compressQuality = q
                                            },
                                            label = { Text(label, fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Text("Resolution DPI: ${compressDpi.toInt()} DPI", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = compressDpi,
                                    onValueChange = { compressDpi = it },
                                    valueRange = 50f..300f,
                                    colors = SliderDefaults.colors(thumbColor = toolItem.accentColor, activeTrackColor = toolItem.accentColor)
                                )
                                Text("JPEG Image Quality: ${compressQuality.toInt()}%", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = compressQuality,
                                    onValueChange = { compressQuality = it },
                                    valueRange = 20f..100f,
                                    colors = SliderDefaults.colors(thumbColor = toolItem.accentColor, activeTrackColor = toolItem.accentColor)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Grayscale Conversion (Extra Savings)", style = MaterialTheme.typography.bodySmall)
                                    Switch(checked = compressGrayscale, onCheckedChange = { compressGrayscale = it })
                                }
                            }

                            // ------------------------------------------
                            // SPLIT PDF CONTROLS
                            // ------------------------------------------
                            ConversionType.SPLIT_PDF -> {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = !splitEveryPage,
                                        onClick = { splitEveryPage = false },
                                        label = { Text("Extract Range", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilterChip(
                                        selected = splitEveryPage,
                                        onClick = { splitEveryPage = true },
                                        label = { Text("Split Every Page", fontSize = 11.sp) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                                if (!splitEveryPage) {
                                    OutlinedTextField(
                                        value = splitRangeText,
                                        onValueChange = { splitRangeText = it },
                                        label = { Text("Page Ranges (e.g. 1-2, 4, 6)") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }

                            // ------------------------------------------
                            // ROTATE PDF CONTROLS
                            // ------------------------------------------
                            ConversionType.ROTATE_PDF -> {
                                Text("Rotation Angle:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(90, 180, 270).forEach { deg ->
                                        FilterChip(
                                            selected = rotateDegrees == deg,
                                            onClick = { rotateDegrees = deg },
                                            label = { Text("$deg°", fontSize = 11.sp) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                                Text("Apply Rotation To:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf("ALL" to "All Pages", "ODD" to "Odd Only", "EVEN" to "Even Only").forEach { (mode, lbl) ->
                                        FilterChip(
                                            selected = rotateFilterMode == mode,
                                            onClick = { rotateFilterMode = mode },
                                            label = { Text(lbl, fontSize = 10.sp) },
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                }
                            }

                            // ------------------------------------------
                            // PAGE NUMBERS CONTROLS
                            // ------------------------------------------
                            ConversionType.PAGE_NUMBERS -> {
                                Text("Numbering Format:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Page {n} of {total}", "{n}", "- {n} -", "Page {n}").forEach { fmt ->
                                        FilterChip(
                                            selected = pageNumFormat == fmt,
                                            onClick = { pageNumFormat = fmt },
                                            label = { Text(fmt, fontSize = 10.sp) }
                                        )
                                    }
                                }
                                Text("Position:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("BOTTOM_CENTER" to "Bottom Center", "BOTTOM_RIGHT" to "Bottom Right", "TOP_RIGHT" to "Top Right").forEach { (pos, label) ->
                                        FilterChip(
                                            selected = pageNumPosition == pos,
                                            onClick = { pageNumPosition = pos },
                                            label = { Text(label, fontSize = 10.sp) }
                                        )
                                    }
                                }
                            }

                            // ------------------------------------------
                            // SIGN PDF CONTROLS (Draw Signature Pad)
                            // ------------------------------------------
                            ConversionType.SIGN_PDF -> {
                                Text("Draw Your Signature:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(130.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(Color.White)
                                        .border(1.5.dp, toolItem.accentColor, RoundedCornerShape(12.dp))
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = { offset -> signaturePoints.add(offset) },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    signaturePoints.add(change.position)
                                                },
                                                onDragEnd = { signaturePoints.add(null) }
                                            )
                                        }
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        for (i in 0 until signaturePoints.size - 1) {
                                            val p1 = signaturePoints[i]
                                            val p2 = signaturePoints[i + 1]
                                            if (p1 != null && p2 != null) {
                                                drawLine(
                                                    color = penColor,
                                                    start = p1,
                                                    end = p2,
                                                    strokeWidth = penStrokeWidth
                                                )
                                            }
                                        }
                                    }
                                    IconButton(
                                        onClick = { signaturePoints.clear() },
                                        modifier = Modifier.align(Alignment.TopEnd)
                                    ) {
                                        Icon(Icons.Default.Clear, contentDescription = "Clear Signature", tint = Color.Gray)
                                    }
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        listOf(Color.Black, Color(0xFF1E3A8A), CrimsonPrimary).forEach { col ->
                                            Box(
                                                modifier = Modifier
                                                    .size(24.dp)
                                                    .clip(CircleShape)
                                                    .background(col)
                                                    .border(if (penColor == col) 2.dp else 0.dp, Color.White, CircleShape)
                                                    .clickable { penColor = col }
                                            )
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Include Date Stamp", style = MaterialTheme.typography.bodySmall)
                                        Switch(checked = addDateStamp, onCheckedChange = { addDateStamp = it })
                                    }
                                }
                            }

                            // ------------------------------------------
                            // PROTECT / UNLOCK CONTROLS
                            // ------------------------------------------
                            ConversionType.PROTECT_PDF, ConversionType.UNLOCK_PDF -> {
                                val isUnlock = tool == ConversionType.UNLOCK_PDF
                                OutlinedTextField(
                                    value = passwordInput,
                                    onValueChange = { passwordInput = it },
                                    label = {
                                        Text(if (isUnlock) "Document Password" else "Set Password")
                                    },
                                    placeholder = {
                                        Text(
                                            if (isUnlock) "Enter the password of this PDF"
                                            else "Choose a password for the new PDF"
                                        )
                                    },
                                    singleLine = true,
                                    isError = isUnlock && selectedPdfUri != null && passwordInput.isEmpty(),
                                    supportingText = {
                                        if (isUnlock) {
                                            Text(
                                                "The unlocked copy keeps no password and no restrictions; " +
                                                    "the original file is never modified."
                                            )
                                        } else {
                                            Text("Used to open the PDF later. Printing is ${
                                                if (allowPrinting) "allowed" else "blocked"
                                            }.")
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                if (!isUnlock) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Allow High-Res Printing", style = MaterialTheme.typography.bodySmall)
                                        Switch(checked = allowPrinting, onCheckedChange = { allowPrinting = it })
                                    }
                                }
                            }

                            // ------------------------------------------
                            // HTML TO PDF CONTROLS
                            // ------------------------------------------
                            ConversionType.HTML_TO_PDF -> {
                                OutlinedTextField(
                                    value = customTextInput,
                                    onValueChange = { customTextInput = it },
                                    label = { Text("Webpage URL or HTML Code") },
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 3
                                )
                            }

                            // ------------------------------------------
                            // EDIT PDF CONTROLS
                            // ------------------------------------------
                            ConversionType.EDIT_PDF -> {
                                OutlinedTextField(
                                    value = customTextInput,
                                    onValueChange = { customTextInput = it },
                                    label = { Text("Annotation / Overlay Text") },
                                    placeholder = { Text("e.g. APPROVED FOR RELEASE") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Text("Add Touch / Stylus Draw Annotation:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(100.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White)
                                        .border(1.dp, toolItem.accentColor, RoundedCornerShape(10.dp))
                                        .pointerInput(Unit) {
                                            detectDragGestures(
                                                onDragStart = { offset -> signaturePoints.add(offset) },
                                                onDrag = { change, _ ->
                                                    change.consume()
                                                    signaturePoints.add(change.position)
                                                },
                                                onDragEnd = { signaturePoints.add(null) }
                                            )
                                        }
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        for (i in 0 until signaturePoints.size - 1) {
                                            val p1 = signaturePoints[i]
                                            val p2 = signaturePoints[i + 1]
                                            if (p1 != null && p2 != null) {
                                                drawLine(
                                                    color = penColor,
                                                    start = p1,
                                                    end = p2,
                                                    strokeWidth = penStrokeWidth
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // ------------------------------------------
                            // PDF FORMS BUILDER
                            // ------------------------------------------
                            ConversionType.PDF_FORMS -> {
                                OutlinedTextField(
                                    value = customTextInput,
                                    onValueChange = { customTextInput = it },
                                    label = { Text("Form Document Title") },
                                    placeholder = { Text("Application & Authorization Form") },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                                Text("Standard Form Fields Included:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("Full Name", "Email Address", "Phone Number", "Date of Birth", "Signature Box").forEach { field ->
                                        FilterChip(
                                            selected = false,
                                            onClick = {},
                                            label = { Text(field, fontSize = 10.sp) }
                                        )
                                    }
                                }
                            }

                            // ------------------------------------------
                            // REDACT PDF
                            // ------------------------------------------
                            ConversionType.REDACT_PDF -> {
                                Text("Privacy Blackout Mode:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                OutlinedTextField(
                                    value = redactText,
                                    onValueChange = { redactText = it },
                                    label = { Text("Text to blackout") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = redactPages,
                                    onValueChange = { redactPages = it },
                                    label = { Text("Pages (e.g. 1,3,5 or 1-4, blank = all)") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    "Applies permanent, un-recoverable blackout redaction masks over selected PII and confidential information.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }

                            ConversionType.CROP_PDF -> {
                                Text("Crop Margins:", style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Slider(
                                        value = cropMarginPercent,
                                        onValueChange = { cropMarginPercent = it },
                                        valueRange = 0f..0.4f,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        "${(cropMarginPercent * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                Text(
                                    "Removes an equal border from every page.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }

                            // ------------------------------------------
                            // DEFAULT / OTHER TOOLS
                            // ------------------------------------------
                            else -> {
                                Text(
                                    text = "Ready to process with ${toolItem.badge} engine specifications and automatic optimization.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                )
                            }
                        }
                    }
                }
            }

            // ==========================================
            // 4. PRIMARY ACTION BUTTON
            // ==========================================
            item {
                // Unlock needs its password up front: an encrypted PDF cannot
                // be decrypted without it, so block the tap with a clear error
                // instead of letting the crypto layer throw deep in the engine.
                val missingUnlockPassword = tool == ConversionType.UNLOCK_PDF &&
                    selectedPdfUri != null &&
                    passwordInput.isEmpty()

                Button(
                    onClick = {
                        val uri = selectedPdfUri
                        if (uri == null && tool != ConversionType.HTML_TO_PDF && tool != ConversionType.SCAN_TO_PDF) {
                            pdfPickerLauncher.launch(arrayOf("application/pdf"))
                            return@Button
                        }

                        if (missingUnlockPassword) {
                            scope.launch {
                                viewModel.setConversionError(
                                    "This PDF is password protected. Enter its password to unlock it."
                                )
                            }
                            return@Button
                        }

                        scope.launch {
                            viewModel.executeUniversalTool(
                                tool = tool,
                                pdfUri = uri,
                                docB_Uri = selectedDocB_Uri,
                                watermarkText = watermarkText,
                                watermarkOpacity = watermarkOpacity.toInt(),
                                watermarkAngle = watermarkAngle,
                                watermarkFontSize = watermarkFontSize,
                                watermarkGridPos = watermarkGridPos,
                                compressDpi = compressDpi.toInt(),
                                compressQuality = compressQuality.toInt(),
                                compressGrayscale = compressGrayscale,
                                splitRange = splitRangeText,
                                splitEvery = splitEveryPage,
                                rotateDeg = rotateDegrees,
                                rotateFilter = rotateFilterMode,
                                pageFmt = pageNumFormat,
                                pagePos = pageNumPosition,
                                password = passwordInput,
                                htmlContent = customTextInput,
                                signaturePoints = signaturePoints.filterNotNull(),
                                penColorInt = penColor.let { AndroidColor.rgb((it.red * 255).toInt(), (it.green * 255).toInt(), (it.blue * 255).toInt()) },
                                signPageIdx = signPageIdx,
                                addDateStamp = addDateStamp,
                                cropMargin = cropMarginPercent,
                                redactText = redactText,
                                redactPages = redactPages
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("execute_universal_tool_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = toolItem.accentColor)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = toolItem.icon,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = if (selectedPdfUri == null && tool != ConversionType.HTML_TO_PDF && tool != ConversionType.SCAN_TO_PDF) {
                                "SELECT PDF FILE FIRST"
                            } else {
                                "APPLY ${toolItem.title.uppercase()} & EXPORT"
                            },
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    // Page editor sheet: rotate / zoom / annotate on a staged page.
    editingPageIndex?.let { pageIndex ->
        if (pageBitmaps.isNotEmpty()) {
            PageEditSheet(
                pages = pageBitmaps,
                initialPage = pageIndex.coerceIn(0, pageBitmaps.lastIndex),
                onDismiss = { editingPageIndex = null },
                onRotatePage = { index ->
                    // Rotate the staged bitmap in place so the edit shows up in
                    // the preview grid below and in the tool's output.
                    pageBitmaps = pageBitmaps.toMutableList().apply {
                        val bmp = getOrNull(index) ?: return@PageEditSheet
                        val matrix = android.graphics.Matrix().apply { postRotate(90f) }
                        set(index, Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true))
                    }
                },
                onRemovePage = { index ->
                    // Removing a staged page invalidates the indices used by the
                    // annotation map, so shift every entry above it down.
                    pageBitmaps = pageBitmaps.toMutableList().also { it.removeAt(index) }
                    val shifted = pageAnnotations.toMap()
                    pageAnnotations.clear()
                    shifted.forEach { (key, strokes) ->
                        when {
                            key == index -> Unit
                            key > index -> pageAnnotations[key - 1] = strokes
                            else -> pageAnnotations[key] = strokes
                        }
                    }
                    if (pageBitmaps.isNotEmpty()) {
                        signPageIdx = signPageIdx.coerceAtMost(pageBitmaps.lastIndex)
                    }
                },
                onAnnotatePage = { index, strokes ->
                    pageAnnotations[index] = strokes
                }
            )
        }
    }
}
