package com.example.ui.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.db.AppDatabase
import com.example.data.model.ConversionType
import com.example.data.model.PdfRecord
import com.example.data.model.ScanPage
import com.example.data.repository.PdfRepository
import com.example.engine.CompressionLevel
import android.graphics.RectF
import com.example.engine.DocumentScanner
import com.example.engine.ImagePdfConfig
import com.example.engine.ImageScaleMode
import com.example.engine.PageMargin
import com.example.engine.PageOrientation
import com.example.engine.PageSize
import com.example.engine.PdfEngine
import com.example.engine.ScanEnhanceConfig
import com.example.engine.ScanFilter
import com.example.engine.ScanQuality
import com.example.engine.ShareUtils
import com.example.engine.TextPdfConfig
import com.example.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PdfMetadataItem(
    val uri: Uri,
    val name: String,
    val pageCount: Int,
    val sizeBytes: Long
)

sealed class ConversionUiState {
    data object Idle : ConversionUiState()
    data class Processing(val current: Int, val total: Int, val message: String) : ConversionUiState()
    data class Success(val file: File, val record: PdfRecord) : ConversionUiState()
    data class Error(val message: String) : ConversionUiState()
}

sealed class AppScreen {
    data object ToolSelection : AppScreen()
    data class ToolWorkbench(val tool: ConversionType) : AppScreen()
    data object MyPdfs : AppScreen()
    data object MyLibrary : AppScreen()
    data object Settings : AppScreen()
    data object Account : AppScreen()

    /** Immersive full-screen live camera document scanner. */
    data object Scanner : AppScreen()
}

class PdfConverterViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: PdfRepository

    init {
        val database = AppDatabase.getDatabase(application)
        repository = PdfRepository(database.pdfDao())
    }

    // Active Tab/Tool
    private val _activeTool = MutableStateFlow(ConversionType.IMAGE_TO_PDF)
    val activeTool: StateFlow<ConversionType> = _activeTool.asStateFlow()

    // Navigation State
    private val _currentScreen = MutableStateFlow<AppScreen>(AppScreen.ToolSelection)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun openTool(tool: ConversionType) {
        _activeTool.value = tool
        _currentScreen.value = AppScreen.ToolWorkbench(tool)
    }

    /** Jumps straight into the immersive live camera scanner. */
    fun openScanner() {
        _currentScreen.value = AppScreen.Scanner
    }

    fun navigateBack() {
        _currentScreen.value = AppScreen.ToolSelection
    }

    // Conversion state
    private val _conversionState = MutableStateFlow<ConversionUiState>(ConversionUiState.Idle)
    val conversionState: StateFlow<ConversionUiState> = _conversionState.asStateFlow()

    // Theme Mode
    // v1.8: LIGHT is the app default (previously DARK).
    private val _themeMode = MutableStateFlow(ThemeMode.LIGHT)
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun toggleThemeMode() {
        _themeMode.update { current ->
            if (current == ThemeMode.LIGHT) ThemeMode.DARK else ThemeMode.LIGHT
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
    }

    // Apply template from library
    fun applyTemplate(title: String, content: String) {
        _textTitle.value = title
        _textContent.value = content
        openTool(ConversionType.TEXT_TO_PDF)
    }

    // Global Settings defaults
    fun setDefaultPageSize(pageSize: PageSize) {
        _imageConfig.update { it.copy(pageSize = pageSize) }
        _textConfig.update { it.copy(pageSize = pageSize) }
    }

    fun setDefaultMargin(margin: PageMargin) {
        _imageConfig.update { it.copy(margin = margin) }
        _textConfig.update { it.copy(margin = margin) }
    }

    fun setDefaultCompression(level: CompressionLevel) {
        _imageConfig.update { it.copy(compression = level) }
    }

    fun clearTempCache(onFreed: (Long) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val cacheDir = getApplication<Application>().cacheDir
            var freedBytes = 0L
            cacheDir.listFiles()?.forEach { file ->
                if (file.isFile && !file.name.endsWith(".db")) {
                    val len = file.length()
                    if (file.delete()) {
                        freedBytes += len
                    }
                }
            }
            launch(Dispatchers.Main) {
                onFreed(freedBytes)
            }
        }
    }

    // --- Tool 1: Images to PDF ---
    private val _selectedImages = MutableStateFlow<List<Uri>>(emptyList())
    val selectedImages: StateFlow<List<Uri>> = _selectedImages.asStateFlow()

    private val _imageConfig = MutableStateFlow(ImagePdfConfig())
    val imageConfig: StateFlow<ImagePdfConfig> = _imageConfig.asStateFlow()

    // --- Tool 2: Text to PDF ---
    private val _textTitle = MutableStateFlow("")
    val textTitle: StateFlow<String> = _textTitle.asStateFlow()

    private val _textContent = MutableStateFlow("")
    val textContent: StateFlow<String> = _textContent.asStateFlow()

    private val _textConfig = MutableStateFlow(TextPdfConfig())
    val textConfig: StateFlow<TextPdfConfig> = _textConfig.asStateFlow()

    // --- Tool 3: Merge PDFs ---
    private val _selectedPdfsForMerge = MutableStateFlow<List<PdfMetadataItem>>(emptyList())
    val selectedPdfsForMerge: StateFlow<List<PdfMetadataItem>> = _selectedPdfsForMerge.asStateFlow()

    private val _mergeFileName = MutableStateFlow("")
    val mergeFileName: StateFlow<String> = _mergeFileName.asStateFlow()

    // --- Tool 4: PDF to Images ---
    private val _selectedPdfForExtract = MutableStateFlow<Uri?>(null)
    val selectedPdfForExtract: StateFlow<Uri?> = _selectedPdfForExtract.asStateFlow()

    private val _extractedPages = MutableStateFlow<List<Bitmap>>(emptyList())
    val extractedPages: StateFlow<List<Bitmap>> = _extractedPages.asStateFlow()

    private val _exportedImageFiles = MutableStateFlow<List<File>>(emptyList())
    val exportedImageFiles: StateFlow<List<File>> = _exportedImageFiles.asStateFlow()

    private val _isExtracting = MutableStateFlow(false)
    val isExtracting: StateFlow<Boolean> = _isExtracting.asStateFlow()

    private val _selectedPagesForCut = MutableStateFlow<Set<Int>>(emptySet())
    val selectedPagesForCut: StateFlow<Set<Int>> = _selectedPagesForCut.asStateFlow()

    // --- Built-in PDF Viewer Modal ---
    private val _viewerFile = MutableStateFlow<File?>(null)
    val viewerFile: StateFlow<File?> = _viewerFile.asStateFlow()

    private val _viewerPages = MutableStateFlow<List<Bitmap>>(emptyList())
    val viewerPages: StateFlow<List<Bitmap>> = _viewerPages.asStateFlow()

    private val _isViewerLoading = MutableStateFlow(false)
    val isViewerLoading: StateFlow<Boolean> = _isViewerLoading.asStateFlow()

    // --- Tool: Organize (page reorder / delete / rotate) ---
    private val _pageOrder = MutableStateFlow<List<Int>>(emptyList())
    val pageOrder: StateFlow<List<Int>> = _pageOrder.asStateFlow()

    fun setPageOrder(order: List<Int>) { _pageOrder.value = order }

    // --- Live Camera Document Scanner ---
    private val _scanPages = MutableStateFlow<List<ScanPage>>(emptyList())
    val scanPages: StateFlow<List<ScanPage>> = _scanPages.asStateFlow()

    private val _scanFilter = MutableStateFlow(ScanFilter.MAGIC_COLOR)
    val scanFilter: StateFlow<ScanFilter> = _scanFilter.asStateFlow()

    private val _scanQuality = MutableStateFlow(ScanQuality.BALANCED)
    val scanQuality: StateFlow<ScanQuality> = _scanQuality.asStateFlow()

    private val _isScanProcessing = MutableStateFlow(false)
    val isScanProcessing: StateFlow<Boolean> = _isScanProcessing.asStateFlow()

    private val _scannerError = MutableStateFlow<String?>(null)
    val scannerError: StateFlow<String?> = _scannerError.asStateFlow()

    // Lens facing is kept as an Int so it can round-trip through camera selector
    // constants without pulling CameraX into the ViewModel layer.
    private val _scannerLensFacing = MutableStateFlow(androidx.camera.core.CameraSelector.LENS_FACING_BACK)
    val scannerLensFacing: StateFlow<Int> = _scannerLensFacing.asStateFlow()

    /**
     * Runs the enhance pipeline over a raw camera frame and appends the result.
     */
    suspend fun processScanCapture(rawUri: Uri) {
        _isScanProcessing.value = true
        _scannerError.value = null
        try {
            val processed = DocumentScanner.processScan(
                context = getApplication(),
                uri = rawUri,
                config = ScanEnhanceConfig(
                    filter = _scanFilter.value,
                    quality = _scanQuality.value,
                    autoDeskew = true,
                    autoCrop = true,
                    sharpen = true
                )
            )
            _scanPages.update { current ->
                current + ScanPage(
                    id = processed.processedUri.toString(),
                    processedUri = processed.processedUri,
                    sourceUri = processed.sourceUri,
                    filter = processed.filter,
                    width = processed.width,
                    height = processed.height
                )
            }
        } catch (e: Exception) {
            _scannerError.value = e.localizedMessage ?: "Failed to process scan."
        } finally {
            _isScanProcessing.value = false
        }
    }

    fun setScanFilter(filter: ScanFilter) {
        _scanFilter.value = filter
    }

    fun setScanQuality(quality: ScanQuality) {
        _scanQuality.value = quality
    }

    fun setScannerLensFacing(lensFacing: Int) {
        _scannerLensFacing.value = lensFacing
    }

    fun removeScanPage(page: ScanPage) {
        _scanPages.update { current -> current.filterNot { it.id == page.id } }
    }

    fun clearScanPages() {
        _scanPages.value = emptyList()
        _scannerError.value = null
    }

    /**
     * Starts a fresh scan session: any pages staged from a previous visit are
     * discarded so the tool never shows stale captures. Called every time the
     * user taps Scan from the workbench.
     */
    fun startFreshScanSession() {
        clearScanPages()
    }

    /**
     * Rotates a staged scan page 90 degrees clockwise by re-encoding the
     * processed bitmap. Updates the page in place so the thumbnail strip and
     * the compiled PDF both reflect the new orientation.
     */
    fun rotateScanPage(page: ScanPage) {
        viewModelScope.launch {
            _isScanProcessing.value = true
            try {
                val rotated = DocumentScanner.rotatePage(getApplication(), page)
                _scanPages.update { current ->
                    current.map { existing ->
                        if (existing.id == page.id) rotated else existing
                    }
                }
            } catch (e: Exception) {
                _scannerError.value = e.localizedMessage ?: "Failed to rotate the page."
            } finally {
                _isScanProcessing.value = false
            }
        }
    }

    /**
     * Crops a staged scan page to a normalized (0f..1f) rectangle. The crop runs
     * through the scanner so the compiled PDF and the thumbnail strip both pick
     * up the result, and the raw source frame is preserved for re-filtering.
     */
    fun cropScanPage(page: ScanPage, crop: RectF) {
        viewModelScope.launch {
            _isScanProcessing.value = true
            try {
                val cropped = DocumentScanner.cropPage(getApplication(), page, crop)
                _scanPages.update { current ->
                    current.map { existing ->
                        if (existing.id == page.id) cropped else existing
                    }
                }
            } catch (e: Exception) {
                _scannerError.value = e.localizedMessage ?: "Failed to crop the page."
            } finally {
                _isScanProcessing.value = false
            }
        }
    }

    /**
     * Reorders a staged scan page by [from] -> [to], used by the drag handles
     * in the thumbnail strip.
     */
    fun moveScanPage(from: Int, to: Int) {
        _scanPages.update { current ->
            if (from in current.indices && to in current.indices) {
                val list = current.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)
                list
            } else current
        }
    }

    fun dismissScannerError() {
        _scannerError.value = null
    }

    /**
     * Imports gallery photos as scan pages by running the same enhance pipeline
     * used for live camera captures.
     */
    fun processGalleryScans(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isScanProcessing.value = true
            uris.forEach { uri ->
                try {
                    val processed = DocumentScanner.processScan(
                        context = getApplication(),
                        uri = uri,
                        config = ScanEnhanceConfig(
                            filter = _scanFilter.value,
                            quality = _scanQuality.value,
                            autoDeskew = true,
                            autoCrop = true,
                            sharpen = true
                        )
                    )
                    _scanPages.update { current ->
                        current + ScanPage(
                            id = processed.processedUri.toString(),
                            processedUri = processed.processedUri,
                            sourceUri = processed.sourceUri,
                            filter = processed.filter,
                            width = processed.width,
                            height = processed.height
                        )
                    }
                } catch (e: Exception) {
                    _scannerError.value = e.localizedMessage ?: "Failed to import a photo."
                }
            }
            _isScanProcessing.value = false
        }
    }

    /**
     * Re-runs the enhance pipeline on the *original* raw frame of [page] with the
     * currently selected filter, keeping filtering non-destructive.
     */
    fun refilterScanPage(page: ScanPage) {
        viewModelScope.launch {
            _isScanProcessing.value = true
            try {
                val newUri = DocumentScanner.refilterScan(
                    context = getApplication(),
                    sourceUri = page.sourceUri,
                    filter = _scanFilter.value
                )
                _scanPages.update { current ->
                    current.map { existing ->
                        if (existing.id == page.id) {
                            existing.copy(processedUri = newUri, filter = _scanFilter.value)
                        } else existing
                    }
                }
            } catch (e: Exception) {
                _scannerError.value = e.localizedMessage ?: "Failed to apply filter."
            } finally {
                _isScanProcessing.value = false
            }
        }
    }

    /**
     * Hands the processed scan pages to the image-to-PDF pipeline and records the
     * result in the vault as a [ConversionType.SCAN_TO_PDF] entry.
     */
    fun convertScansToPdf() {
        val pages = _scanPages.value
        if (pages.isEmpty()) {
            _conversionState.value = ConversionUiState.Error("Capture at least one scan first.")
            return
        }

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(0, pages.size, "Compiling scans into PDF...")
            try {
                val config = _imageConfig.value.copy(
                    pageSize = PageSize.FIT_IMAGE,
                    scaleMode = ImageScaleMode.FIT,
                    showPageNumbers = pages.size > 1
                )
                val file = PdfEngine.convertImagesToPdf(
                    context = getApplication(),
                    imageUris = pages.map { it.processedUri },
                    config = config,
                    onProgress = { cur, tot ->
                        _conversionState.value = ConversionUiState.Processing(cur, tot, "Rendering page $cur of $tot...")
                    }
                )

                val record = PdfRecord(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    pageCount = pages.size,
                    conversionType = ConversionType.SCAN_TO_PDF,
                    description = "Scanned ${pages.size} page(s) with in-app camera scanner"
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(file, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to build scan PDF.")
            }
        }
    }

    // --- Vault / History Filtering ---
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _historyFilterType = MutableStateFlow<ConversionType?>(null)
    val historyFilterType: StateFlow<ConversionType?> = _historyFilterType.asStateFlow()

    private val _showFavoritesOnly = MutableStateFlow(false)
    val showFavoritesOnly: StateFlow<Boolean> = _showFavoritesOnly.asStateFlow()

    val historyRecords: StateFlow<List<PdfRecord>> = combine(
        repository.allRecords,
        _searchQuery,
        _historyFilterType,
        _showFavoritesOnly
    ) { records, query, filterType, favOnly ->
        records.filter { record ->
            val matchesQuery = query.isBlank() || record.fileName.contains(query, ignoreCase = true)
            val matchesType = filterType == null || record.conversionType == filterType
            val matchesFav = !favOnly || record.isFavorite
            matchesQuery && matchesType && matchesFav
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    // Actions
    fun setActiveTool(tool: ConversionType) {
        _activeTool.value = tool
        _conversionState.value = ConversionUiState.Idle
    }

    // Images to PDF Actions
    fun addImages(uris: List<Uri>) {
        _selectedImages.update { current -> current + uris }
    }

    fun removeImage(index: Int) {
        _selectedImages.update { current ->
            if (index in current.indices) current.toMutableList().apply { removeAt(index) } else current
        }
    }

    fun moveImage(from: Int, to: Int) {
        _selectedImages.update { current ->
            if (from in current.indices && to in current.indices) {
                val list = current.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)
                list
            } else current
        }
    }

    // Replaces a staged page with its edited version (crop / rotate / annotate
    // persisted to a temp file). The export pipeline reads this same list, so a
    // swap here lands in the generated PDF - the fix for edits vanishing.
    fun replaceImage(index: Int, uri: Uri) {
        _selectedImages.update { current ->
            if (index in current.indices) current.toMutableList().apply { set(index, uri) } else current
        }
    }

    fun clearImages() {
        _selectedImages.value = emptyList()
    }

    fun updateImageConfig(updater: (ImagePdfConfig) -> ImagePdfConfig) {
        _imageConfig.update(updater)
    }

    fun convertImagesToPdf() {
        val uris = _selectedImages.value
        if (uris.isEmpty()) {
            _conversionState.value = ConversionUiState.Error("Please select at least one image.")
            return
        }

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(0, uris.size, "Preparing images...")
            try {
                val file = PdfEngine.convertImagesToPdf(
                    context = getApplication(),
                    imageUris = uris,
                    config = _imageConfig.value,
                    onProgress = { cur, tot ->
                        _conversionState.value = ConversionUiState.Processing(cur, tot, "Processing page $cur of $tot...")
                    }
                )
                // Guard: a zero-length file means the render produced nothing
                // (every image failed to decode). Never report Success on an
                // empty file - that is what produced "unable to open / corrupted".
                if (!file.exists() || file.length() == 0L) {
                    _conversionState.value = ConversionUiState.Error(
                        "No page could be rendered from the selected images. The files may be unreadable or in an unsupported format."
                    )
                    return@launch
                }
                val pages = PdfEngine.inspectPdf(getApplication(), Uri.fromFile(file)).first
                if (pages <= 0) {
                    _conversionState.value = ConversionUiState.Error(
                        "The generated PDF has no pages. The selected images could not be read."
                    )
                    return@launch
                }

                val record = PdfRecord(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    pageCount = pages,
                    conversionType = ConversionType.IMAGE_TO_PDF,
                    description = "${uris.size} image(s) converted"
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(file, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to convert images to PDF.")
            }
        }
    }

    // Text to PDF Actions
    fun updateTextTitle(title: String) { _textTitle.value = title }
    fun updateTextContent(content: String) { _textContent.value = content }
    fun clearTextComposer() {
        _textTitle.value = ""
        _textContent.value = ""
    }
    fun updateTextConfig(updater: (TextPdfConfig) -> TextPdfConfig) { _textConfig.update(updater) }

    fun convertTextToPdf() {
        val content = _textContent.value
        if (content.isBlank()) {
            _conversionState.value = ConversionUiState.Error("Please enter some text content to convert.")
            return
        }

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(1, 1, "Formatting document...")
            try {
                val config = _textConfig.value.copy(title = _textTitle.value)
                val file = PdfEngine.convertTextToPdf(
                    context = getApplication(),
                    content = content,
                    config = config,
                    onProgress = { cur, tot ->
                        _conversionState.value = ConversionUiState.Processing(cur, tot, "Rendering page $cur of $tot...")
                    }
                )

                val (pages, size) = PdfEngine.inspectPdf(getApplication(), Uri.fromFile(file))
                val record = PdfRecord(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = size,
                    pageCount = pages.coerceAtLeast(1),
                    conversionType = ConversionType.TEXT_TO_PDF,
                    description = _textTitle.value.ifBlank { "Text document" }
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(file, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to generate text PDF.")
            }
        }
    }

    // Merge PDF Actions
    fun addPdfsForMerge(uris: List<Uri>) {
        viewModelScope.launch {
            val items = uris.map { uri ->
                val (pages, size) = PdfEngine.inspectPdf(getApplication(), uri)
                val displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
                PdfMetadataItem(uri = uri, name = displayName, pageCount = pages, sizeBytes = size)
            }
            _selectedPdfsForMerge.update { it + items }
        }
    }

    fun removePdfForMerge(index: Int) {
        _selectedPdfsForMerge.update { current ->
            if (index in current.indices) current.toMutableList().apply { removeAt(index) } else current
        }
    }

    fun movePdfForMerge(from: Int, to: Int) {
        _selectedPdfsForMerge.update { current ->
            if (from in current.indices && to in current.indices) {
                val list = current.toMutableList()
                val item = list.removeAt(from)
                list.add(to, item)
                list
            } else current
        }
    }

    fun reversePdfsForMerge() {
        _selectedPdfsForMerge.update { it.reversed() }
    }

    fun sortPdfsByName() {
        _selectedPdfsForMerge.update { it.sortedBy { item -> item.name.lowercase() } }
    }

    fun sortPdfsBySize() {
        _selectedPdfsForMerge.update { it.sortedByDescending { item -> item.sizeBytes } }
    }

    fun clearPdfsForMerge() {
        _selectedPdfsForMerge.value = emptyList()
    }

    fun updateMergeFileName(name: String) {
        _mergeFileName.value = name
    }


    fun mergePdfs() {
        val items = _selectedPdfsForMerge.value
        if (items.size < 2) {
            _conversionState.value = ConversionUiState.Error("Please select at least 2 PDF files to merge.")
            return
        }

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(0, items.size, "Preparing PDF streams...")
            try {
                val file = PdfEngine.mergePdfs(
                    context = getApplication(),
                    pdfUris = items.map { it.uri },
                    customName = _mergeFileName.value,
                    onProgress = { cur, tot ->
                        _conversionState.value = ConversionUiState.Processing(cur, tot, "Merging page $cur of $tot...")
                    }
                )

                val (pages, size) = PdfEngine.inspectPdf(getApplication(), Uri.fromFile(file))
                val record = PdfRecord(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = size,
                    pageCount = pages,
                    conversionType = ConversionType.MERGE_PDF,
                    description = "Merged ${items.size} documents"
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(file, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to merge PDF documents.")
            }
        }
    }

    // PDF to Images Actions
    fun selectPdfForExtract(uri: Uri) {
        _selectedPdfForExtract.value = uri
        _extractedPages.value = emptyList()
        _exportedImageFiles.value = emptyList()

        viewModelScope.launch {
            _isExtracting.value = true
            try {
                val tempFile = PdfEngine.copyUriToTemp(getApplication(), uri)
                if (tempFile != null) {
                    val pages = PdfEngine.renderPdfPages(getApplication(), tempFile, scale = 1.6f, maxPages = 40)
                    _extractedPages.value = pages
                    tempFile.delete()
                }
            } catch (_: Exception) {
            } finally {
                _isExtracting.value = false
            }
        }
    }

    fun togglePageSelectionForCut(index: Int) {
        _selectedPagesForCut.update { current ->
            if (current.contains(index)) current - index else current + index
        }
    }

    fun selectAllPagesForCut() {
        val total = _extractedPages.value.size
        _selectedPagesForCut.value = (0 until total).toSet()
    }

    fun clearPageSelectionForCut() {
        _selectedPagesForCut.value = emptySet()
    }

    fun exportExtractedImages() {
        val bitmaps = _extractedPages.value
        if (bitmaps.isEmpty()) return

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(0, bitmaps.size, "Extracting & downloading ${bitmaps.size} photos as PNG...")
            try {
                val base = _selectedPdfForExtract.value?.lastPathSegment ?: "Extracted"
                val files = PdfEngine.exportBitmapsToImages(getApplication(), bitmaps, base)
                _exportedImageFiles.value = files

                if (files.isNotEmpty()) {
                    // Automatically download all photos directly into device public gallery/photos
                    try {
                        ShareUtils.saveMultipleImagesToGallery(getApplication(), files)
                    } catch (e: Exception) {}

                    val first = files.first()
                    val recordName = if (files.size == 1) first.name else "${first.nameWithoutExtension}_plus_${files.size - 1}_photos.png"
                    val record = PdfRecord(
                        fileName = recordName,
                        filePath = first.absolutePath,
                        fileSizeBytes = files.sumOf { it.length() },
                        pageCount = files.size,
                        conversionType = ConversionType.PDF_TO_IMAGES,
                        description = "Downloaded ${files.size} photos (.png) to Gallery & Vault"
                    )
                    repository.insert(record)
                    _conversionState.value = ConversionUiState.Success(first, record)
                }
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to export photos.")
            }
        }
    }

    fun exportSelectedImages() {
        val bitmaps = _extractedPages.value
        val selected = _selectedPagesForCut.value.sorted()
        if (bitmaps.isEmpty()) return
        val targetBitmaps = if (selected.isNotEmpty()) selected.mapNotNull { bitmaps.getOrNull(it) } else bitmaps
        if (targetBitmaps.isEmpty()) return

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(0, targetBitmaps.size, "Downloading ${targetBitmaps.size} selected photos as PNG...")
            try {
                val base = _selectedPdfForExtract.value?.lastPathSegment ?: "Extracted"
                val files = PdfEngine.exportBitmapsToImages(getApplication(), targetBitmaps, base)
                _exportedImageFiles.update { it + files }

                if (files.isNotEmpty()) {
                    try {
                        ShareUtils.saveMultipleImagesToGallery(getApplication(), files)
                    } catch (e: Exception) {}

                    val first = files.first()
                    val recordName = if (files.size == 1) first.name else "${first.nameWithoutExtension}_plus_${files.size - 1}_photos.png"
                    val record = PdfRecord(
                        fileName = recordName,
                        filePath = first.absolutePath,
                        fileSizeBytes = files.sumOf { it.length() },
                        pageCount = files.size,
                        conversionType = ConversionType.PDF_TO_IMAGES,
                        description = "Downloaded ${files.size} selected photos (.png) to Gallery"
                    )
                    repository.insert(record)
                    _conversionState.value = ConversionUiState.Success(first, record)
                }
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to download photos.")
            }
        }
    }

    fun exportExtractedImagesAsZip() {
        val bitmaps = _extractedPages.value
        if (bitmaps.isEmpty()) return

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(0, bitmaps.size, "Packaging & downloading ZIP archive...")
            try {
                val base = _selectedPdfForExtract.value?.lastPathSegment ?: "Extracted"
                val files = PdfEngine.exportBitmapsToImages(getApplication(), bitmaps, base)
                _exportedImageFiles.value = files
                val zipFile = PdfEngine.createZipArchive(getApplication(), files, base)
                try {
                    ShareUtils.saveFileToDownloads(getApplication(), zipFile, "application/zip")
                } catch (_: Exception) {}

                val record = PdfRecord(
                    fileName = zipFile.name,
                    filePath = zipFile.absolutePath,
                    fileSizeBytes = zipFile.length(),
                    pageCount = bitmaps.size,
                    conversionType = ConversionType.PDF_TO_IMAGES,
                    description = "ZIP archive with ${bitmaps.size} photos (saved in Downloads)"
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(zipFile, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to create ZIP.")
            }
        }
    }

    fun exportSoloImage(pageIndex: Int) {
        val bitmaps = _extractedPages.value
        if (pageIndex !in bitmaps.indices) return
        val bitmap = bitmaps[pageIndex]

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(1, 1, "Downloading photo ${pageIndex + 1} as PNG...")
            try {
                val base = _selectedPdfForExtract.value?.lastPathSegment ?: "Photo"
                val file = PdfEngine.exportSingleBitmap(getApplication(), bitmap, base, pageIndex + 1)
                _exportedImageFiles.update { it + file }

                try {
                    ShareUtils.saveImageToGallery(getApplication(), file, showToast = true)
                } catch (_: Exception) {}

                val record = PdfRecord(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = file.length(),
                    pageCount = 1,
                    conversionType = ConversionType.PDF_TO_IMAGES,
                    description = "Photo ${pageIndex + 1} saved as PNG to Gallery & Vault"
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(file, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to download photo.")
            }
        }
    }

    fun cutSpecificPageToPdf(pageIndex: Int) {
        val bitmaps = _extractedPages.value
        val sourceUri = _selectedPdfForExtract.value
        if (pageIndex !in bitmaps.indices) return

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(1, 1, "Cutting page ${pageIndex + 1} to PDF...")
            try {
                val base = _selectedPdfForExtract.value?.lastPathSegment ?: "CutPage"
                val file = if (sourceUri != null) {
                    try {
                        PdfEngine.cutPdfPages(
                            context = getApplication(),
                            sourcePdfUri = sourceUri,
                            pageIndices = listOf(pageIndex),
                            customName = "${base}_page_${pageIndex + 1}"
                        )
                    } catch (_: Exception) {
                        PdfEngine.convertBitmapToSinglePagePdf(
                            context = getApplication(),
                            bitmap = bitmaps[pageIndex],
                            title = "${base}_page_${pageIndex + 1}"
                        )
                    }
                } else {
                    PdfEngine.convertBitmapToSinglePagePdf(
                        context = getApplication(),
                        bitmap = bitmaps[pageIndex],
                        title = "${base}_page_${pageIndex + 1}"
                    )
                }

                val (pages, size) = PdfEngine.inspectPdf(getApplication(), Uri.fromFile(file))
                val record = PdfRecord(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = size,
                    pageCount = pages.coerceAtLeast(1),
                    conversionType = ConversionType.IMAGE_TO_PDF,
                    description = "Cut page ${pageIndex + 1} from PDF"
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(file, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to cut page to PDF.")
            }
        }
    }

    fun cutSelectedPagesToPdf(customName: String = "") {
        val selected = _selectedPagesForCut.value.sorted()
        if (selected.isEmpty()) {
            _conversionState.value = ConversionUiState.Error("Please select at least 1 page to cut.")
            return
        }
        val sourceUri = _selectedPdfForExtract.value
        val bitmaps = _extractedPages.value

        viewModelScope.launch {
            _conversionState.value = ConversionUiState.Processing(0, selected.size, "Cutting ${selected.size} pages into PDF...")
            try {
                val base = _selectedPdfForExtract.value?.lastPathSegment ?: "CutDocument"
                val name = customName.ifBlank { "${base}_cut_${selected.size}pages" }
                val file = if (sourceUri != null) {
                    try {
                        PdfEngine.cutPdfPages(
                            context = getApplication(),
                            sourcePdfUri = sourceUri,
                            pageIndices = selected,
                            customName = name
                        )
                    } catch (_: Exception) {
                        val selectedBitmaps = selected.mapNotNull { bitmaps.getOrNull(it) }
                        val exportedTemps = PdfEngine.exportBitmapsToImages(getApplication(), selectedBitmaps, "temp_cut")
                        val merged = PdfEngine.mergePdfs(
                            context = getApplication(),
                            pdfUris = exportedTemps.map { Uri.fromFile(it) },
                            customName = name
                        )
                        exportedTemps.forEach { it.delete() }
                        merged
                    }
                } else {
                    throw IllegalStateException("No source PDF document found.")
                }

                val (pages, size) = PdfEngine.inspectPdf(getApplication(), Uri.fromFile(file))
                val record = PdfRecord(
                    fileName = file.name,
                    filePath = file.absolutePath,
                    fileSizeBytes = size,
                    pageCount = pages.coerceAtLeast(selected.size),
                    conversionType = ConversionType.MERGE_PDF,
                    description = "Cut ${selected.size} pages from document"
                )
                repository.insert(record)
                _conversionState.value = ConversionUiState.Success(file, record)
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.localizedMessage ?: "Failed to cut selected pages.")
            }
        }
    }

    /**
     * Kept for the "send page to OCR" affordance in the page extractor. The OCR
     * tool was removed in v1.8, so this now routes the page into Images-to-PDF
     * (the nearest surviving tool) instead of into a deleted screen.
     */
    fun sendExtractedPageToOcr(pageIndex: Int) {
        sendExtractedPageToImageToPdf(pageIndex)
    }

    fun sendExtractedPageToImageToPdf(pageIndex: Int) {
        val bitmaps = _extractedPages.value
        if (pageIndex !in bitmaps.indices) return
        viewModelScope.launch {
            try {
                val file = PdfEngine.exportSingleBitmap(getApplication(), bitmaps[pageIndex], "img_to_pdf_cut", pageIndex + 1)
                _selectedImages.update { it + Uri.fromFile(file) }
                _activeTool.value = ConversionType.IMAGE_TO_PDF
                _currentScreen.value = AppScreen.ToolWorkbench(ConversionType.IMAGE_TO_PDF)
            } catch (_: Exception) {}
        }
    }

    // Viewer Actions
    fun openInViewer(file: File) {
        _viewerFile.value = file
        _viewerPages.value = emptyList()
        _isViewerLoading.value = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (file.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp")) {
                    val bitmap = android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        _viewerPages.value = listOf(bitmap)
                    }
                } else if (file.isDirectory) {
                    val imgFiles = file.listFiles { f -> f.extension.lowercase() in listOf("png", "jpg", "jpeg", "webp") }
                        ?.sortedBy { it.name } ?: emptyList()
                    val loaded = imgFiles.mapNotNull { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
                    _viewerPages.value = loaded
                } else {
                    val pages = PdfEngine.renderPdfPages(getApplication(), file, scale = 1.8f, maxPages = 50)
                    _viewerPages.value = pages
                }
            } catch (_: Exception) {
            } finally {
                _isViewerLoading.value = false
            }
        }
    }

    fun closeViewer() {
        _viewerFile.value = null
        _viewerPages.value.forEach { it.recycle() }
        _viewerPages.value = emptyList()
    }

    fun dismissConversionState() {
        _conversionState.value = ConversionUiState.Idle
    }

    /**
     * Lets a composable report a validation error through the same status card
     * the engine uses, so input problems surface where the user is looking.
     */
    fun setConversionError(message: String) {
        _conversionState.value = ConversionUiState.Error(message)
    }

    // History Actions
    fun setSearchQuery(query: String) { _searchQuery.value = query }
    fun setHistoryFilter(type: ConversionType?) { _historyFilterType.value = type }
    fun toggleFavoritesFilter() { _showFavoritesOnly.update { !it } }

    fun toggleFavoriteRecord(id: Long) {
        viewModelScope.launch { repository.toggleFavorite(id) }
    }

    fun deleteRecord(record: PdfRecord) {
        viewModelScope.launch { repository.delete(record) }
    }

    fun executeUniversalTool(
        tool: ConversionType,
        pdfUri: Uri?,
        docB_Uri: Uri? = null,
        watermarkText: String = "CONFIDENTIAL",
        watermarkOpacity: Int = 35,
        watermarkAngle: Float = -45f,
        watermarkFontSize: Float = 36f,
        watermarkGridPos: Int = 4,
        compressDpi: Int = 150,
        compressQuality: Int = 80,
        compressGrayscale: Boolean = false,
        splitRange: String = "1",
        splitEvery: Boolean = false,
        rotateDeg: Int = 90,
        rotateFilter: String = "ALL",
        pageFmt: String = "Page {n} of {total}",
        pagePos: String = "BOTTOM_CENTER",
        password: String = "",
        htmlContent: String = "",
        signaturePoints: List<androidx.compose.ui.geometry.Offset> = emptyList(),
        penColorInt: Int = android.graphics.Color.BLACK,
        signPageIdx: Int = 0,
        addDateStamp: Boolean = true,
        cropMargin: Float = 0.05f,
        redactText: String = "",
        redactPages: String = ""
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            _conversionState.value = ConversionUiState.Processing(1, 10, "Initializing ${tool.name.replace("_", " ")}...")
            try {
                var outputFile: File? = null
                var pageCount = if (pdfUri != null) {
                    runCatching {
                        com.example.engine.PdfEngine.renderAllPagesFromPdfUri(app, pdfUri).size
                    }.getOrDefault(1)
                } else 1

                when (tool) {
                    ConversionType.WATERMARK -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Applying custom watermark text...")
                        outputFile = com.example.engine.AdvancedPdfEngine.applyWatermark(
                            context = app,
                            pdfUri = pdfUri,
                            watermarkText = watermarkText,
                            fontSizeSp = watermarkFontSize,
                            rotationDeg = watermarkAngle,
                            opacityPercent = watermarkOpacity,
                            gridPosition = watermarkGridPos
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Watermarking page $cur of $tot")
                        }
                    }
                    ConversionType.COMPRESS_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Optimizing and compressing pages...")
                        val before = runCatching {
                            com.example.engine.PdfEngine.copyUriToTemp(app, pdfUri)?.length() ?: 0L
                        }.getOrDefault(0L)
                        val compressed = com.example.engine.AdvancedPdfEngine.compressPdf(
                            context = app,
                            pdfUri = pdfUri,
                            dpi = compressDpi,
                            quality = compressQuality,
                            isGrayscale = compressGrayscale
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Compressing page $cur of $tot")
                        }
                        // A vector/text PDF can come out LARGER after rasterizing;
                        // the engine returns the untouched original in that case.
                        // Make it clear to the user instead of looking like a bug.
                        if (compressed.length() >= before && before > 0L) {
                            _conversionState.value = ConversionUiState.Error(
                                "This PDF is already optimized - it cannot be made smaller without " +
                                    "losing quality. Vector/text pages do not shrink when rasterized."
                            )
                            return@launch
                        }
                        outputFile = compressed
                    }
                    ConversionType.SPLIT_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Extracting page subsets...")
                        val files = com.example.engine.AdvancedPdfEngine.splitPdf(
                            context = app,
                            pdfUri = pdfUri,
                            pageRangeString = splitRange,
                            splitEveryPage = splitEvery
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Processing page $cur of $tot")
                        }
                        if (files.isEmpty()) throw IllegalStateException("No pages extracted")
                        // Keep every produced file in the vault, not just the
                        // first: a split is a many-file operation.
                        files.drop(1).forEach { extra ->
                            runCatching {
                                repository.insert(
                                    PdfRecord(
                                        fileName = extra.name,
                                        filePath = extra.absolutePath,
                                        fileSizeBytes = extra.length(),
                                        pageCount = 1,
                                        conversionType = tool,
                                        description = "Generated via ${tool.name.replace("_", " ")}"
                                    )
                                )
                            }
                        }
                        outputFile = files.first()
                    }
                    ConversionType.ROTATE_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Rotating pages by $rotateDeg°...")
                        outputFile = com.example.engine.AdvancedPdfEngine.rotatePdf(
                            context = app,
                            pdfUri = pdfUri,
                            rotationDegrees = rotateDeg,
                            filterMode = rotateFilter
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Rotating page $cur of $tot")
                        }
                    }
                    ConversionType.PAGE_NUMBERS -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Numbering document headers & footers...")
                        outputFile = com.example.engine.AdvancedPdfEngine.addPageNumbers(
                            context = app,
                            pdfUri = pdfUri,
                            formatPattern = pageFmt,
                            position = pagePos
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Numbering page $cur of $tot")
                        }
                    }
                    ConversionType.CROP_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Cropping margins...")
                        outputFile = com.example.engine.AdvancedPdfEngine.cropPdf(
                            context = app,
                            pdfUri = pdfUri,
                            cropLeftPercent = cropMargin,
                            cropTopPercent = cropMargin,
                            cropRightPercent = cropMargin,
                            cropBottomPercent = cropMargin
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Cropping page $cur of $tot")
                        }
                    }
                    ConversionType.SIGN_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Applying digital e-signature...")
                        val sigBmp = Bitmap.createBitmap(400, 160, Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(sigBmp)
                        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                            color = penColorInt
                            strokeWidth = 6f
                            style = android.graphics.Paint.Style.STROKE
                            strokeCap = android.graphics.Paint.Cap.ROUND
                        }
                        for (i in 0 until signaturePoints.size - 1) {
                            val p1 = signaturePoints[i]
                            val p2 = signaturePoints[i + 1]
                            if (p1 != null && p2 != null) {
                                canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                            }
                        }
                        outputFile = com.example.engine.AdvancedPdfEngine.signPdf(
                            context = app,
                            pdfUri = pdfUri,
                            signatureBitmap = sigBmp,
                            targetPageIdx = signPageIdx,
                            addDateStamp = addDateStamp
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Signing page $cur of $tot")
                        }
                    }
                    ConversionType.PROTECT_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Securing document with AES encryption...")
                        outputFile = com.example.engine.AdvancedPdfEngine.protectPdf(
                            context = app,
                            pdfUri = pdfUri,
                            password = password
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Encrypting page $cur of $tot")
                        }
                    }
                    ConversionType.UNLOCK_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Removing permissions lock & restrictions...")
                        outputFile = com.example.engine.AdvancedPdfEngine.unlockPdf(
                            context = app,
                            pdfUri = pdfUri,
                            password = password
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Unlocking page $cur of $tot")
                        }
                    }
                    ConversionType.REPAIR_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Diagnosing and repairing PDF structure...")
                        val (file, report) = com.example.engine.AdvancedPdfEngine.repairPdf(
                            context = app,
                            pdfUri = pdfUri
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Recovering page $cur of $tot")
                        }
                        outputFile = file
                    }
                    ConversionType.COMPARE_PDF -> {
                        if (pdfUri == null || docB_Uri == null) throw IllegalArgumentException("Please select both Document A and Document B to compare")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Running side-by-side visual diff analysis...")
                        val (file, _) = com.example.engine.AdvancedPdfEngine.comparePdfs(
                            context = app,
                            uriA = pdfUri,
                            uriB = docB_Uri
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Comparing page $cur of $tot")
                        }
                        outputFile = file
                    }
                    ConversionType.HTML_TO_PDF -> {
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Rendering HTML markup to PDF...")
                        outputFile = com.example.engine.AdvancedPdfEngine.convertHtmlToPdf(
                            context = app,
                            htmlOrUrl = htmlContent.ifBlank { "<h1>Sample Document</h1><p>Generated by OmniPDF</p>" }
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Finalizing HTML document")
                        }
                    }
                    ConversionType.PDF_TO_PDFA -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Verifying ISO 19005 compliance...")
                        outputFile = com.example.engine.AdvancedPdfEngine.convertToPdfA(
                            context = app,
                            pdfUri = pdfUri
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Preserving archival tags $cur of $tot")
                        }
                    }
                    ConversionType.EDIT_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Applying annotations and edits...")
                        outputFile = com.example.engine.AdvancedPdfEngine.editPdf(
                            context = app,
                            pdfUri = pdfUri,
                            annotationText = htmlContent,
                            drawPoints = signaturePoints,
                            penColor = penColorInt
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Editing page $cur of $tot")
                        }
                    }
                    ConversionType.PDF_FORMS -> {
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Generating fillable form PDF...")
                        outputFile = com.example.engine.AdvancedPdfEngine.createFormPdf(
                            context = app,
                            pdfUri = pdfUri,
                            formTitle = htmlContent.ifBlank { "OmniPDF Application Form" }
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Building form field $cur of $tot")
                        }
                    }
                    ConversionType.REDACT_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(3, 10, "Permanently blacking out sensitive areas...")
                        // Full-width band across the vertical middle of the page,
                        // wide enough to cover a line of text. Only applied to the
                        // pages the user asked for.
                        val zone = listOf(android.graphics.RectF(0.04f, 0.42f, 0.96f, 0.58f))
                        outputFile = com.example.engine.AdvancedPdfEngine.redactPdf(
                            context = app,
                            pdfUri = pdfUri,
                            redactionZones = zone,
                            targetPages = redactPages
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Redacting page $cur of $tot")
                        }
                    }
                    ConversionType.ORGANIZE_PDF -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(1, 1, "Organizing pages...")
                        // Applies the reorder/delete/rotate edits the user made
                        // in the organize editor; with no edits this is a clean
                        // round-trip (and still validates the document parses).
                        // An empty order means the user made no reorder/delete
                        // edits: pass the identity so every page survives.
                        val raw = _pageOrder.value
                        val pageCount = com.example.engine.PdfEngine
                            .renderAllPagesFromPdfUri(app, pdfUri).size
                        val order = if (raw.isEmpty()) (0 until pageCount).toList() else raw
                        outputFile = com.example.engine.AdvancedPdfEngine.organizePdf(
                            context = app,
                            pdfUri = pdfUri,
                            pageOrder = order,
                            rotations = emptyMap()
                        ) { cur, tot ->
                            _conversionState.value = ConversionUiState.Processing(cur, tot, "Organizing page $cur of $tot")
                        }
                    }
                    // Extract the embedded text layer; if the PDF is a pure image
                    // scan with no text layer, this yields an empty string. There is
                    // no on-device OCR fallback in v1.8 (the OCR tool was removed).
                    ConversionType.EXTRACT_TEXT -> {
                        if (pdfUri == null) throw IllegalArgumentException("Please select a PDF document first")
                        _conversionState.value = ConversionUiState.Processing(1, 1, "Extracting text...")
                        val text = com.example.engine.PdfEngine.extractTextFromPdfUriTextLayer(app, pdfUri) ?: ""
                        if (text.isBlank()) {
                            _conversionState.value = ConversionUiState.Error("No extractable text was found in this PDF. The document may be a scanned image with no text layer.")
                            return@launch
                        }
                        outputFile = File(app.cacheDir, "extracted_text_${System.currentTimeMillis()}.txt")
                        outputFile.writeText(text)
                    }
                    else -> {
                        if (pdfUri != null) {
                            outputFile = com.example.engine.AdvancedPdfEngine.compressPdf(app, pdfUri, 150, 85) { c, t -> }
                        } else {
                            throw IllegalArgumentException("Please select a source document")
                        }
                    }
                }

                if (outputFile != null && outputFile.exists()) {
                    val record = PdfRecord(
                        fileName = outputFile.name,
                        filePath = outputFile.absolutePath,
                        fileSizeBytes = outputFile.length(),
                        pageCount = pageCount,
                        conversionType = tool,
                        description = "Generated via ${tool.name.replace("_", " ")}"
                    )
                    val id = repository.insert(record)
                    _conversionState.value = ConversionUiState.Success(outputFile, record.copy(id = id))
                } else {
                    _conversionState.value = ConversionUiState.Error("Failed to generate file output.")
                }
            } catch (e: Exception) {
                _conversionState.value = ConversionUiState.Error(e.message ?: "An unexpected error occurred during processing.")
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch { repository.clearAll() }
    }
}

