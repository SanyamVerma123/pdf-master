package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.ConversionType
import com.example.engine.ShareUtils
import com.example.ui.components.AccountScreen
import com.example.ui.components.AppNavDrawerContent
import com.example.ui.components.ConversionStatusCard
import com.example.ui.components.DocumentScannerHost
import com.example.ui.components.MinimalTopBar
import com.example.ui.components.MyLibraryScreen
import com.example.ui.components.MyPdfsScreen
import com.example.ui.components.PdfViewerModal
import com.example.ui.components.SettingsScreen
import com.example.ui.components.ToolSelectionDashboard
import com.example.ui.components.ToolWorkbenchScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.ThemeMode
import com.example.ui.viewmodel.AppScreen
import com.example.ui.viewmodel.PdfConverterViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val appViewModel: PdfConverterViewModel = viewModel()
            val themeMode by appViewModel.themeMode.collectAsStateWithLifecycle()
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            MyApplicationTheme(darkTheme = isDark) {
                OmniPdfApp(
                    viewModel = appViewModel,
                    isDarkTheme = isDark
                )
            }
        }
    }
}

@Composable
fun OmniPdfApp(
    viewModel: PdfConverterViewModel = viewModel(),
    isDarkTheme: Boolean = true
) {
    val currentScreen by viewModel.currentScreen.collectAsStateWithLifecycle()
    val activeTool by viewModel.activeTool.collectAsStateWithLifecycle()
    val conversionState by viewModel.conversionState.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()

    // Tool states
    val selectedImages by viewModel.selectedImages.collectAsStateWithLifecycle()
    val imageConfig by viewModel.imageConfig.collectAsStateWithLifecycle()

    // OCR states
    val ocrImages by viewModel.ocrImages.collectAsStateWithLifecycle()
    val ocrExtractedText by viewModel.ocrExtractedText.collectAsStateWithLifecycle()
    val ocrDocumentTitle by viewModel.ocrDocumentTitle.collectAsStateWithLifecycle()
    val isOcrScanning by viewModel.isOcrScanning.collectAsStateWithLifecycle()

    // Text states
    val textTitle by viewModel.textTitle.collectAsStateWithLifecycle()
    val textContent by viewModel.textContent.collectAsStateWithLifecycle()
    val textConfig by viewModel.textConfig.collectAsStateWithLifecycle()

    // Merge states
    val selectedPdfsForMerge by viewModel.selectedPdfsForMerge.collectAsStateWithLifecycle()
    val mergeFileName by viewModel.mergeFileName.collectAsStateWithLifecycle()

    // Extract states
    val selectedPdfForExtract by viewModel.selectedPdfForExtract.collectAsStateWithLifecycle()
    val extractedPages by viewModel.extractedPages.collectAsStateWithLifecycle()
    val exportedFiles by viewModel.exportedImageFiles.collectAsStateWithLifecycle()
    val isExtracting by viewModel.isExtracting.collectAsStateWithLifecycle()
    val selectedPagesForCut by viewModel.selectedPagesForCut.collectAsStateWithLifecycle()

    // Viewer states
    val viewerFile by viewModel.viewerFile.collectAsStateWithLifecycle()
    val viewerPages by viewModel.viewerPages.collectAsStateWithLifecycle()
    val isViewerLoading by viewModel.isViewerLoading.collectAsStateWithLifecycle()

    // Live scanner states
    val scanPages by viewModel.scanPages.collectAsStateWithLifecycle()
    val scanFilter by viewModel.scanFilter.collectAsStateWithLifecycle()
    val scanQuality by viewModel.scanQuality.collectAsStateWithLifecycle()
    val isScanProcessing by viewModel.isScanProcessing.collectAsStateWithLifecycle()
    val scannerLensFacing by viewModel.scannerLensFacing.collectAsStateWithLifecycle()

    // History / Vault states
    val historyRecords by viewModel.historyRecords.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val historyFilterType by viewModel.historyFilterType.collectAsStateWithLifecycle()
    val showFavoritesOnly by viewModel.showFavoritesOnly.collectAsStateWithLifecycle()

    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Handle Back Button gracefully
    BackHandler(enabled = drawerState.isOpen || currentScreen !is AppScreen.ToolSelection) {
        if (drawerState.isOpen) {
            scope.launch { drawerState.close() }
        } else if (currentScreen !is AppScreen.ToolSelection) {
            viewModel.navigateBack()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                AppNavDrawerContent(
                    currentScreen = currentScreen,
                    totalDocuments = historyRecords.size,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { viewModel.toggleThemeMode() },
                    onSelectScreen = { screen ->
                        scope.launch { drawerState.close() }
                        viewModel.navigateTo(screen)
                    },
                )
            }
        }
    ) {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding)
            ) {
                when (val screen = currentScreen) {
                    // ====================================================
                    // 1. MAIN SCREEN: 2-1-2 TOOL SELECTION DASHBOARD
                    // ====================================================
                    is AppScreen.ToolSelection -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .navigationBarsPadding()
                        ) {
                            // Top Bar with 3-Lines Hamburger Menu Button on top left
                            item {
                                MinimalTopBar(
                                    isDarkTheme = isDarkTheme,
                                    onToggleTheme = { viewModel.toggleThemeMode() },
                                    onOpenDrawer = {
                                        scope.launch { drawerState.open() }
                                    },
                                    onClearAll = { viewModel.clearAllHistory() }
                                )
                            }

                            // Active Conversion Notification Banner (if any conversion active/done)
                            item {
                                ConversionStatusCard(
                                    state = conversionState,
                                    onDismiss = { viewModel.dismissConversionState() },
                                    onViewInApp = { file -> viewModel.openInViewer(file) },
                                    onShare = { file -> ShareUtils.sharePdf(context, file) },
                                    onPrint = { file ->
                                        if (activity != null) ShareUtils.printDocument(activity, file)
                                    },
                                    onOpenExternal = { file -> ShareUtils.openInExternalApp(context, file) }
                                )
                            }

                            // 2-1-2 Tool Selection Layout
                            item {
                                ToolSelectionDashboard(
                                    totalDocuments = historyRecords.size,
                                    onSelectTool = { tool ->
                                        viewModel.openTool(tool)
                                    },
                                    onOpenMyPdfs = {
                                        viewModel.navigateTo(AppScreen.MyPdfs)
                                    }
                                )
                                Spacer(modifier = Modifier.height(30.dp))
                            }
                        }
                    }

                    // ====================================================
                    // 2. DEDICATED TOOL PAGE (RENDERED WHEN TOOL CLICKED)
                    // ====================================================
                    is AppScreen.ToolWorkbench -> {
                        ToolWorkbenchScreen(
                            tool = screen.tool,
                            conversionState = conversionState,
                            onBack = { viewModel.navigateBack() },
                            onDismissConversion = { viewModel.dismissConversionState() },
                            onViewInApp = { file -> viewModel.openInViewer(file) },

                            // Images to PDF
                            selectedImages = selectedImages,
                            imageConfig = imageConfig,
                            onAddImages = { uris -> viewModel.addImages(uris) },
                            onRemoveImage = { index -> viewModel.removeImage(index) },
                            onMoveImage = { from, to -> viewModel.moveImage(from, to) },
                            onClearImages = { viewModel.clearImages() },
                            onUpdateImageConfig = { updater -> viewModel.updateImageConfig(updater) },
                            onConvertImages = { viewModel.convertImagesToPdf() },
                            onOcrScanShortcut = { viewModel.openTool(ConversionType.PHOTO_OCR_TO_PDF) },

                            // Photo OCR
                            ocrImages = ocrImages,
                            ocrExtractedText = ocrExtractedText,
                            ocrDocumentTitle = ocrDocumentTitle,
                            isOcrScanning = isOcrScanning,
                            onAddOcrImages = { uris -> viewModel.addOcrImages(uris) },
                            onRemoveOcrImage = { index -> viewModel.removeOcrImage(index) },
                            onClearOcrImages = { viewModel.clearOcrImages() },
                            onOcrTextChange = { text -> viewModel.updateOcrText(text) },
                            onOcrTitleChange = { title -> viewModel.updateOcrTitle(title) },
                            onScanOcr = { viewModel.scanOcr() },
                            onConvertOcrToPdf = { viewModel.convertOcrToPdf() },
                            onSendToComposer = {
                                viewModel.sendOcrToComposer()
                                viewModel.openTool(ConversionType.TEXT_TO_PDF)
                            },

                            // Text to PDF
                            textTitle = textTitle,
                            textContent = textContent,
                            textConfig = textConfig,
                            onTextTitleChange = { viewModel.updateTextTitle(it) },
                            onTextContentChange = { viewModel.updateTextContent(it) },
                            onClearTextComposer = { viewModel.clearTextComposer() },
                            onUpdateTextConfig = { updater -> viewModel.updateTextConfig(updater) },
                            onConvertText = { viewModel.convertTextToPdf() },

                            // Merge PDFs
                            selectedPdfsForMerge = selectedPdfsForMerge,
                            mergeFileName = mergeFileName,
                            onAddPdfsForMerge = { uris -> viewModel.addPdfsForMerge(uris) },
                            onRemovePdfForMerge = { index -> viewModel.removePdfForMerge(index) },
                            onMovePdfForMerge = { from, to -> viewModel.movePdfForMerge(from, to) },
                            onClearPdfsForMerge = { viewModel.clearPdfsForMerge() },
                            onMergeFileNameChange = { viewModel.updateMergeFileName(it) },
                            onMergePdfs = { viewModel.mergePdfs() },
                            onReversePdfs = { viewModel.reversePdfsForMerge() },
                            onSortByName = { viewModel.sortPdfsByName() },
                            onSortBySize = { viewModel.sortPdfsBySize() },

                            // PDF to Images
                            selectedPdfForExtract = selectedPdfForExtract,
                            extractedPages = extractedPages,
                            exportedFiles = exportedFiles,
                            isExtracting = isExtracting,
                            selectedPagesForCut = selectedPagesForCut,
                            onSelectPdfForExtract = { uri -> viewModel.selectPdfForExtract(uri) },
                            onExportImages = { viewModel.exportExtractedImages() },
                            onExportSelectedImages = { viewModel.exportSelectedImages() },
                            onExportZip = { viewModel.exportExtractedImagesAsZip() },
                            onExportSoloImage = { pageIdx -> viewModel.exportSoloImage(pageIdx) },
                            onCutSpecificPageToPdf = { pageIdx -> viewModel.cutSpecificPageToPdf(pageIdx) },
                            onCutSelectedPagesToPdf = { viewModel.cutSelectedPagesToPdf() },
                            onTogglePageSelection = { pageIdx -> viewModel.togglePageSelectionForCut(pageIdx) },
                            onSelectAllPages = { viewModel.selectAllPagesForCut() },
                            onClearPageSelection = { viewModel.clearPageSelectionForCut() },
                            onSendPageToOcr = { pageIdx -> viewModel.sendExtractedPageToOcr(pageIdx) },
                            onSendPageToComposer = { pageIdx -> viewModel.sendExtractedPageToImageToPdf(pageIdx) },
                            viewModel = viewModel
                        )
                    }

                    // ====================================================
                    // 3. FULL-SCREEN LIVE CAMERA SCANNER (Scan to PDF)
                    // ====================================================
                    is AppScreen.Scanner -> {
                        DocumentScannerHost(
                            scanPages = scanPages,
                            scanFilter = scanFilter,
                            scanQuality = scanQuality,
                            isProcessing = isScanProcessing,
                            lensFacing = scannerLensFacing,
                            onProcessCapture = { rawUri ->
                                viewModel.processScanCapture(rawUri)
                            },
                            onRefilterPage = { page -> viewModel.refilterScanPage(page) },
                            onRemovePage = { page -> viewModel.removeScanPage(page) },
                            onPickFromGallery = { uris -> viewModel.processGalleryScans(uris) },
                            onFilterSelected = { filter -> viewModel.setScanFilter(filter) },
                            onQualitySelected = { quality -> viewModel.setScanQuality(quality) },
                            onFlipCamera = { facing -> viewModel.setScannerLensFacing(facing) },
                            onDone = {
                                viewModel.convertScansToPdf()
                                viewModel.navigateBack()
                            },
                            onBack = { viewModel.navigateBack() }
                        )
                    }

                    // ====================================================
                    // 4. DRAWER SECTION: MY PDFS
                    // ====================================================
                    is AppScreen.MyPdfs -> {
                        MyPdfsScreen(
                            records = historyRecords,
                            searchQuery = searchQuery,
                            activeFilter = historyFilterType,
                            favoritesOnly = showFavoritesOnly,
                            onSearchChange = { viewModel.setSearchQuery(it) },
                            onFilterChange = { viewModel.setHistoryFilter(it) },
                            onToggleFavoritesOnly = { viewModel.toggleFavoritesFilter() },
                            onOpenRecord = { file -> viewModel.openInViewer(file) },
                            onToggleFavorite = { id -> viewModel.toggleFavoriteRecord(id) },
                            onDeleteRecord = { record -> viewModel.deleteRecord(record) },
                            onBack = { viewModel.navigateBack() }
                        )
                    }

                    // ====================================================
                    // 4. DRAWER SECTION: MY LIBRARY
                    // ====================================================
                    is AppScreen.MyLibrary -> {
                        MyLibraryScreen(
                            onApplyTemplate = { title, content ->
                                viewModel.applyTemplate(title, content)
                            },
                            onBack = { viewModel.navigateBack() }
                        )
                    }

                    // ====================================================
                    // 5. DRAWER SECTION: SETTINGS
                    // ====================================================
                    is AppScreen.Settings -> {
                        SettingsScreen(
                            themeMode = themeMode,
                            currentPageSize = imageConfig.pageSize,
                            currentMargin = imageConfig.margin,
                            currentCompression = imageConfig.compression,
                            onSetThemeMode = { viewModel.setThemeMode(it) },
                            onSetDefaultPageSize = { viewModel.setDefaultPageSize(it) },
                            onSetDefaultMargin = { viewModel.setDefaultMargin(it) },
                            onSetDefaultCompression = { viewModel.setDefaultCompression(it) },
                            onClearCache = { onFreed -> viewModel.clearTempCache(onFreed) },
                            onBack = { viewModel.navigateBack() }
                        )
                    }

                    // ====================================================
                    // 6. DRAWER SECTION: ACCOUNT
                    // ====================================================
                    is AppScreen.Account -> {
                        AccountScreen(
                            totalDocuments = historyRecords.size,
                            totalSizeBytes = historyRecords.sumOf { it.fileSizeBytes },
                            onBack = { viewModel.navigateBack() }
                        )
                    }
                }

                // Global In-App PDF Viewer Modal
                PdfViewerModal(
                    file = viewerFile,
                    pages = viewerPages,
                    isLoading = isViewerLoading,
                    onClose = { viewModel.closeViewer() }
                )
            }
        }
    }
}
