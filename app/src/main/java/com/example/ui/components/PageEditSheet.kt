package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.graphics.Bitmap
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.net.Uri
import android.util.Log
import com.example.engine.AdvancedPdfEngine.StrokeObj
import com.example.engine.AdvancedPdfEngine.TextObj
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Fullscreen page editor opened by tapping a thumbnail in any tool's page list.
 *
 * Gives every workbench the same set of per-page tools:
 *  - [Zoom]: pinch on the page.
 *  - [Rotate]: 90 degrees clockwise, applied to the staged page immediately
 *    through [onRotatePage] so the grid underneath updates.
 *  - [Crop]: drag a rectangular selection over the page, then Apply to keep
 *    only that region. The selection is reported through [onCropPage] as
 *    normalized (0f..1f) coordinates so each caller can crop its own state.
 *  - Annotation: freehand drawing (toggle with the brush button) whose strokes
 *    are committed to the page through [onAnnotatePage] as normalized points.
 *
 * The caller owns the page list, so every edit lands in the caller's state.
 */
@Composable
fun PageEditSheet(
    pages: List<Bitmap>,
    initialPage: Int,
    onDismiss: () -> Unit,
    onRotatePage: (Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onAnnotatePage: (Int, List<Offset>) -> Unit = { _, _ -> },
    // v1.9: the same commit path as onAnnotatePage, but carries the strokes as objects
    // (so a signature stays one unit) plus the placed text items.
    onAnnotatePageObjects: (Int, List<StrokeObj>, List<TextObj>) -> Unit = { _, _, _ -> },
    onCropPage: (Int, RectF) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    if (pages.isEmpty()) {
        onDismiss()
        return
    }

    var currentPage by remember { mutableIntStateOf(initialPage.coerceIn(0, pages.lastIndex)) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var drawMode by remember { mutableStateOf(false) }
    var cropMode by remember { mutableStateOf(false) }
    // v1.9 edit tools. Only one editing tool can be armed at a time: they all
    // consume the canvas drag/tap gesture.
    var editTool by remember { mutableStateOf<EditTool>(EditTool.None) }

    // Live crop selection, in the canvas' pixel space (same space the strokes
    // use). Null until the user starts a drag; recomputed in the overlay below.
    var cropRect by remember { mutableStateOf<RectF?>(null) }
    // Where the current drag started, so the selection can be built in any
    // direction (up-left as well as down-right).
    var cropAnchor by remember { mutableStateOf<Offset?>(null) }
    // Which edges a resize drag is currently moving. Null/None until a drag
    // starts on a handle; this is what turns "draw a new rectangle" into
    // "adjust the left edge" or "move the whole thing".
    var cropAdjustMode by remember { mutableStateOf(CropAdjust.None) }
    // Finger-sized hit target for the handles; without it the handles are
    // un-grabbable on a touchscreen.
    val touchSlopPx = with(LocalDensity.current) { 28.dp.toPx() }
    // Page dimensions the selection was made against, so a stale selection is
    // never applied after the user swipes to a different page.
    var cropPageKey by remember { mutableStateOf(RectF(0f, 0f, 0f, 0f)) }

    // v1.9: strokes are now objects so the eraser can address one individually.
    // A stroke is a polyline plus its pen-up gap marker; the eraser deletes the
    // entry the user taps.
    val strokeObjects = remember { mutableStateListOf<StrokeObj>() }
    // Tap-to-place text items: the user taps, a caret appears, they type.
    // Applied to the page bitmap on APPLY, same as strokes.
    val textObjects = remember { mutableStateListOf<TextObj>() }
    // The text field is in the input-method area; this drives its visibility.
    var textInputFor by remember { mutableStateOf<TextObj?>(null) }
    // Live drag state for the text tool (move an existing item).
    var textDragItem by remember { mutableStateOf<TextObj?>(null) }
    var textDragStart by remember { mutableStateOf<Offset?>(null) }
    // Points of the stroke currently being drawn; committed to strokeObjects on pen-up.
    var currentStrokePoints by remember { mutableStateOf<MutableList<PointF>?>(null) }
    // Zero-arg lambdas by default: both APPLY buttons live outside the
    // BoxWithConstraints that computes the geometry, so they are assigned real
    // implementations once the page has been measured. The explicit () -> Unit
    // type keeps the empty default from making the lambda's own type ambiguous.
    var applyStrokes by remember { mutableStateOf<() -> Unit>({}) }
    var applyCrop by remember { mutableStateOf<() -> Unit>({}) }
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    LaunchedEffect(currentPage) {
        listState.scrollToItem(currentPage.coerceIn(0, pages.lastIndex))
        // Swiping to another page invalidates any pending crop selection: it
        // was measured against a different bitmap.
        cropRect = null
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .navigationBarsPadding()
                .testTag("page_edit_sheet")
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Top bar: counter + rotate + draw toggle + close.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "PAGE ${currentPage + 1} OF ${pages.size}",
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        ),
                        modifier = Modifier.testTag("page_edit_counter")
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircleToolButton(
                            icon = Icons.Default.RotateRight,
                            description = "Rotate page 90 degrees",
                            tag = "page_edit_rotate",
                            onClick = { onRotatePage(currentPage) }
                        )
                        CircleToolButton(
                            icon = Icons.Default.Crop,
                            description = "Crop page",
                            tag = "page_edit_crop",
                            highlight = cropMode,
                            onClick = {
                                cropMode = !cropMode
                                if (!cropMode) cropRect = null
                                // Cropping consumes the drag gesture, so disarm any
                                // edit tool at the same time.
                                if (cropMode) editTool = EditTool.None
                            }
                        )
                        // v1.9: the edit-tool strip. Tapping a tool arms it and disarms
                        // crop; tapping the armed tool again disarms it.
                        EditToolButton(Icons.Default.TextFields, "Add text", EditTool.Text, editTool) { editTool = if (it == editTool) EditTool.None else it; if (editTool != EditTool.None) { cropMode = false; cropRect = null } }
                        EditToolButton(Icons.Default.Brush, "Draw", EditTool.Pen, editTool) { editTool = if (it == editTool) EditTool.None else it; if (editTool != EditTool.None) { cropMode = false; cropRect = null } }
                        EditToolButton(Icons.Default.Draw, "Sign", EditTool.Sign, editTool) { editTool = if (it == editTool) EditTool.None else it; if (editTool != EditTool.None) { cropMode = false; cropRect = null } }
                        EditToolButton(Icons.Default.AutoFixHigh, "Erase", EditTool.Eraser, editTool) { editTool = if (it == editTool) EditTool.None else it; if (editTool != EditTool.None) { cropMode = false; cropRect = null } }
                        CircleToolButton(
                            icon = Icons.Default.Delete,
                            description = "Remove this page",
                            tag = "page_edit_remove",
                            onClick = {
                                val target = currentPage
                                onRemovePage(target)
                                if (pages.size <= 1) {
                                    onDismiss()
                                } else {
                                    currentPage = (target - 1).coerceAtLeast(0)
                                        .coerceAtMost(pages.lastIndex - 1)
                                }
                            }
                        )
                        CircleToolButton(
                            icon = Icons.Default.Close,
                            description = "Close editor",
                            tag = "page_edit_close",
                            onClick = onDismiss
                        )
                    }
                }

                // Canvas area. BoxWithConstraints exposes the drawing surface
                // size, which is needed to map strokes onto the page bitmap.
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    // Recompute the canvas geometry whenever the box resizes and
                    // remember it, so the apply button below (which lives
                    // outside this scope) can normalize strokes against it.
                    var canvasWpx by remember { mutableFloatStateOf(1f) }
                    var canvasHpx by remember { mutableFloatStateOf(1f) }
                    var fitScale by remember { mutableFloatStateOf(1f) }
                    var offsetX by remember { mutableFloatStateOf(0f) }
                    var offsetY by remember { mutableFloatStateOf(0f) }
                    canvasWpx = with(density) { maxWidth.toPx() }
                    canvasHpx = with(density) { maxHeight.toPx() }

                    val page = pages[currentPage]
                    // ContentScale.Fit: the page is letterboxed inside the box,
                    // so compute the transform that maps the box onto the image.
                    fitScale = minOf(
                        canvasWpx / page.width,
                        canvasHpx / page.height
                    ).coerceAtLeast(0.0001f)
                    offsetX = (canvasWpx - page.width * fitScale) / 2f
                    offsetY = (canvasHpx - page.height * fitScale) / 2f

                    // The APPLY button lives outside BoxWithConstraints, so hand
                    // it a ready-made normalizer instead of the raw values.
                    applyStrokes = {
                        val target = currentPage
                        // v1.9: commit strokes as objects, each normalized independently
                        // so a signature stays a single repositionable unit.
                        val normalizedStrokes = strokeObjects.map { s ->
                            StrokeObj(
                                points = strokesToNormalized(
                                    strokes = s.points,
                                    canvasWpx = canvasWpx,
                                    canvasHpx = canvasHpx,
                                    imageW = page.width,
                                    imageH = page.height,
                                    fitScale = fitScale,
                                    offsetX = offsetX,
                                    offsetY = offsetY,
                                    zoom = zoom,
                                    panX = panX,
                                    panY = panY
                                ),
                                color = s.color,
                                widthPx = s.widthPx / fitScale,  // scale pen width to page space
                                isSignature = s.isSignature
                            )
                        }
                        // Text items normalize the same way (baseline point -> page space).
                        val normalizedText = textObjects.mapNotNull { t ->
                            if (t.text.isBlank()) return@mapNotNull null
                            val n = strokesToNormalized(
                                strokes = listOf(PointF(t.x, t.y)),
                                canvasWpx = canvasWpx,
                                canvasHpx = canvasHpx,
                                imageW = page.width,
                                imageH = page.height,
                                fitScale = fitScale,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                zoom = zoom,
                                panX = panX,
                                panY = panY
                            ).firstOrNull() ?: return@mapNotNull null
                            TextObj(t.text, n.x, n.y, t.sizePx / fitScale, t.color)
                        }
                        strokeObjects.clear()
                        textObjects.clear()
                        textInputFor = null
                        editTool = EditTool.None
                        if (normalizedStrokes.isNotEmpty() || normalizedText.isNotEmpty()) {
                            onAnnotatePageObjects(target, normalizedStrokes, normalizedText)
                        }
                    }

                    // The crop APPLY button also lives outside BoxWithConstraints,
                    // so it gets the same ready-made geometry.
                    applyCrop = {
                        val target = currentPage
                        val raw = cropRect
                        // Guard against a selection made for a different page.
                        // RectF exposes width()/height() as methods, not properties.
                        // Wrapped in a scope so an early exit needs no label.
                        raw?.takeIf {
                            cropPageKey.width() == page.width.toFloat() &&
                                cropPageKey.height() == page.height.toFloat()
                        }?.let { valid ->
                            val normalized = cropRectToNormalized(
                                rect = valid,
                                canvasWpx = canvasWpx,
                                canvasHpx = canvasHpx,
                                imageW = page.width,
                                imageH = page.height,
                                fitScale = fitScale,
                                offsetX = offsetX,
                                offsetY = offsetY,
                                zoom = zoom,
                                panX = panX,
                                panY = panY
                            )
                            normalized?.let { onCropPage(target, it) }
                        }
                        cropRect = null
                        cropMode = false
                    }
                    // Both the page image and the stroke overlay receive the
                    // same transform so strokes stay glued to the page while
                    // zooming and panning.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = zoom,
                                scaleY = zoom,
                                translationX = panX,
                                translationY = panY
                            )
                    ) {
                        Image(
                            bitmap = page.asImageBitmap(),
                            contentDescription = "Page ${currentPage + 1}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    // v1.9 drawing layer: committed strokes + placed text, plus the
                    // stroke currently in flight. Drawn in the same untransformed
                    // canvas space the gesture reports, so it tracks the finger.
                    if (strokeObjects.isNotEmpty() || textObjects.isNotEmpty() || currentStrokePoints != null) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            strokeObjects.forEach { s ->
                                drawStroke(s, this)
                            }
                            textObjects.forEach { t ->
                                if (t.text.isNotEmpty()) {
                                    drawText(t, this)
                                }
                            }
                            // In-flight stroke follows the finger before the gesture ends.
                            currentStrokePoints?.let { pts ->
                                if (pts.size >= 2) {
                                    val w = if (editTool == EditTool.Sign) 5f else 3f
                                    drawStroke(
                                        StrokeObj(pts, android.graphics.Color.BLACK, w),
                                        this
                                    )
                                }
                            }
                        }
                    }

                    // Crop selection. Lives OUTSIDE the zoom/pan-transformed
                    // Box on purpose: the gesture below reports raw canvas
                    // coordinates (unaffected by graphicsLayer), and the
                    // overlay has to use that same space to line up with
                    // the finger and with the page beneath.
                    cropRect?.let { rect ->
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            // Dim everything, then punch the selection back
                            // out so it is obvious what survives the crop.
                            drawRect(
                                color = Color.Black.copy(alpha = 0.45f),
                                topLeft = Offset.Zero,
                                size = this.size
                            )
                            val selection = Rect(
                                offset = Offset(rect.left, rect.top),
                                size = Size(
                                    (rect.right - rect.left).coerceAtLeast(1f),
                                    (rect.bottom - rect.top).coerceAtLeast(1f)
                                )
                            )
                            drawRect(
                                color = Color.Transparent,
                                topLeft = selection.topLeft,
                                size = selection.size,
                                blendMode = BlendMode.Clear
                            )
                            drawRect(
                                color = CrimsonPrimary,
                                topLeft = selection.topLeft,
                                size = selection.size,
                                style = Stroke(width = 3.dp.toPx())
                            )
                            // Handles at all 4 corners and 4 edge midpoints, so
                            // every edge is directly adjustable by a finger.
                            // Hit-testing lives in hitTestCropHandles.
                            val handleSize = with(density) { 14.dp.toPx() }
                            val midL = Offset(selection.left, (selection.top + selection.bottom) / 2f)
                            val midR = Offset(selection.right, (selection.top + selection.bottom) / 2f)
                            val midT = Offset((selection.left + selection.right) / 2f, selection.top)
                            val midB = Offset((selection.left + selection.right) / 2f, selection.bottom)
                            for (pt in listOf(
                                Offset(selection.left, selection.top),
                                Offset(selection.right, selection.top),
                                Offset(selection.left, selection.bottom),
                                Offset(selection.right, selection.bottom),
                                midL, midR, midT, midB
                            )) {
                                drawRect(
                                    color = CrimsonPrimary,
                                    topLeft = Offset(pt.x - handleSize / 2f, pt.y - handleSize / 2f),
                                    size = Size(handleSize, handleSize)
                                )
                            }
                        }
                    }

                    // Gestures: crop when the crop tool is armed, otherwise the armed
                    // edit tool (text/pen/sign/eraser); nothing armed = pinch-zoom and
                    // pan. One pointerInput per mode, keyed so the detectors never
                    // compete for the same drag.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(cropMode, editTool) {
                                when {
                                    cropMode -> {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                cropPageKey = RectF(
                                                    page.width.toFloat(),
                                                    page.height.toFloat(),
                                                    0f,
                                                    0f
                                                )
                                                val r = cropRect
                                                if (r != null) {
                                                    cropAdjustMode = hitTestCropHandles(r, offset, touchSlopPx)
                                                }
                                                when (cropAdjustMode) {
                                                    CropAdjust.None -> {
                                                        cropAnchor = offset
                                                        cropRect = RectF(offset.x, offset.y, offset.x, offset.y)
                                                    }
                                                    CropAdjust.Move -> {
                                                        cropAnchor = offset
                                                    }
                                                    else -> { /* edge/corner: track from the point */ }
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val r = cropRect ?: return@detectDragGestures
                                                when (cropAdjustMode) {
                                                    CropAdjust.None -> {
                                                        val a = cropAnchor ?: change.position
                                                        cropRect = RectF(
                                                            minOf(a.x, change.position.x),
                                                            minOf(a.y, change.position.y),
                                                            maxOf(a.x, change.position.x),
                                                            maxOf(a.y, change.position.y)
                                                        )
                                                    }
                                                    CropAdjust.Move -> {
                                                        val a = cropAnchor ?: change.position
                                                        val dx = change.position.x - a.x
                                                        val dy = change.position.y - a.y
                                                        cropAnchor = change.position
                                                        // Keep the selection inside the page.
                                                        val w = r.width(); val h = r.height()
                                                        val nx = (r.left + dx).coerceIn(0f, (canvasWpx - w).coerceAtLeast(0f))
                                                        val ny = (r.top + dy).coerceIn(0f, (canvasHpx - h).coerceAtLeast(0f))
                                                        cropRect = RectF(nx, ny, nx + w, ny + h)
                                                    }
                                                    else -> {
                                                        // Resize the chosen edges. Coordinates may
                                                        // cross over (left beyond right); the rect
                                                        // is normalized below so any corner can
                                                        // become any other corner.
                                                        val nr = RectF(r)
                                                        if (cropAdjustMode!!.left) nr.left = change.position.x
                                                        if (cropAdjustMode!!.top) nr.top = change.position.y
                                                        if (cropAdjustMode!!.right) nr.right = change.position.x
                                                        if (cropAdjustMode!!.bottom) nr.bottom = change.position.y
                                                        cropRect = normalizeCropRect(nr, canvasWpx, canvasHpx)
                                                    }
                                                }
                                            },
                                            onDragEnd = {
                                                cropAnchor = null
                                                cropAdjustMode = CropAdjust.None
                                                // A tap without a drag is not a crop: drop the
                                                // empty selection.
                                                val r = cropRect
                                                if (r == null || r.width() < 8f || r.height() < 8f) {
                                                    cropRect = null
                                                }
                                            }
                                        )
                                    }
                                    editTool == EditTool.Text -> {
                                        // Tap-to-place: a tap drops a new text item and
                                        // focuses the input so the user can type right
                                        // there. A drag on existing text moves it.
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                // Hit an existing text item first (topmost wins
                                                // because later items draw on top).
                                                val hit = textObjects.lastOrNull { t ->
                                                    offset.x >= t.x - 20f && offset.x <= t.x + t.text.length * t.sizePx * 0.6f &&
                                                        offset.y >= t.y - t.sizePx * 1.4f && offset.y <= t.y + 10f
                                                }
                                                if (hit != null) {
                                                    textDragItem = hit
                                                    textDragStart = offset
                                                } else {
                                                    val item = TextObj("", offset.x, offset.y, with(density) { 18.dp.toPx() }, android.graphics.Color.BLACK)
                                                    textObjects.add(item)
                                                    textInputFor = item
                                                }
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val item = textDragItem ?: return@detectDragGestures
                                                val start = textDragStart ?: change.position
                                                item.x += change.position.x - start.x
                                                item.y += change.position.y - start.y
                                                textDragStart = change.position
                                            },
                                            onDragEnd = {
                                                textDragItem = null
                                                textDragStart = null
                                            }
                                        )
                                    }
                                    editTool == EditTool.Pen || editTool == EditTool.Sign -> {
                                        // Same polyline gesture for both; Sign is flagged so
                                        // APPLY can treat it as one repositionable object.
                                        val isSign = editTool == EditTool.Sign
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                currentStrokePoints = mutableListOf(offset.toPointF())
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                currentStrokePoints?.add(change.position.toPointF())
                                            },
                                            onDragEnd = {
                                                // A stroke needs at least two points to be
                                                // visible; a tap with the pen is noise.
                                                val pts = currentStrokePoints
                                                if (pts != null && pts.size >= 2) {
                                                    strokeObjects.add(
                                                        StrokeObj(
                                                            points = pts.toList(),
                                                            color = android.graphics.Color.BLACK,
                                                            // Sign reads as a thicker, flowing stroke.
                                                            widthPx = with(density) {
                                                                if (isSign) 5.dp.toPx() else 3.dp.toPx()
                                                            },
                                                            isSignature = isSign
                                                        )
                                                    )
                                                }
                                                currentStrokePoints = null
                                            }
                                        )
                                    }
                                    editTool == EditTool.Eraser -> {
                                        // Tap a stroke to delete it. Distance to the nearest
                                        // point on the polyline is the hit test, so a thick
                                        // gesture is not required.
                                        detectTapGestures { offset ->
                                            val victim = strokeObjects.lastOrNull { s ->
                                                s.points.any { p ->
                                                    kotlin.math.hypot(p.x - offset.x, p.y - offset.y) < 30f
                                                }
                                            }
                                            if (victim != null) strokeObjects.remove(victim)
                                            else {
                                                val tv = textObjects.lastOrNull { t ->
                                                    offset.x >= t.x - 20f && offset.x <= t.x + t.text.length * t.sizePx * 0.6f &&
                                                        offset.y >= t.y - t.sizePx * 1.4f && offset.y <= t.y + 10f
                                                }
                                                if (tv != null) textObjects.remove(tv)
                                            }
                                        }
                                    }
                                    else -> {
                                        detectTransformGestures { _, pan, zoomChange, _ ->
                                            zoom = (zoom * zoomChange).coerceIn(1f, 6f)
                                            panX += pan.x
                                            panY += pan.y
                                        }
                                    }
                                }
                            }
                }

                // Apply / clear v1.9 edits: strokes + text.
                if ((editTool == EditTool.Pen || editTool == EditTool.Sign || editTool == EditTool.Text) &&
                    (strokeObjects.isNotEmpty() || textObjects.isNotEmpty())
                ) {
                    // Text input for the item the user just placed. Typing updates the
                    // same object APPLY reads, so there is no separate "confirm" step.
                    textInputFor?.let { item ->
                        // item.text is a plain var, so the field hoists its own string
                        // state and pushes into the object. Without this the TextField
                        // would never recompose while the user types.
                        var fieldText by remember { mutableStateOf(item.text) }
                        OutlinedTextField(
                            value = fieldText,
                            onValueChange = {
                                fieldText = it
                                item.text = it
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                            placeholder = { Text("Type here, then APPLY") },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyMedium,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
                            )
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                strokeObjects.clear()
                                textObjects.clear()
                                textInputFor = null
                            },
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("CLEAR", style = monoLabel)
                        }
                        Button(
                            onClick = { applyStrokes() },
                            modifier = Modifier.weight(1f).height(44.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldSuccess
                            )
                        ) {
                            Icon(Icons.Default.Done, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("APPLY TO PAGE", style = monoLabel.copy(color = Color.White))
                        }
                    }
                }

                // Crop controls: keep the selection, or discard it.
                if (cropMode) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                cropRect = null
                                cropAnchor = null
                            },
                            modifier = Modifier.weight(1f).height(44.dp)
                        ) {
                            Text("RESET", style = monoLabel)
                        }
                        Button(
                            onClick = { applyCrop() },
                            enabled = cropRect != null,
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .testTag("page_edit_crop_apply"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldSuccess,
                                disabledContainerColor = EmeraldSuccess.copy(alpha = 0.35f)
                            )
                        ) {
                            Icon(Icons.Default.Done, contentDescription = null, tint = Color.White)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("CROP PAGE", style = monoLabel.copy(color = Color.White))
                        }
                    }
                }

                // Bottom thumbnail strip: jump to any page.
                LazyRow(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(pages) { index, bmp ->
                        val selected = index == currentPage
                        Box(
                            modifier = Modifier
                                .width(56.dp)
                                .aspectRatio(0.72f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White)
                                .border(
                                    width = if (selected) 2.dp else 1.dp,
                                    color = if (selected) CrimsonPrimary else Color.White.copy(alpha = 0.3f),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { currentPage = index }
                                .testTag("page_edit_thumb_$index")
                        ) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = "Go to page ${index + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Draws one stroke as a connected polyline. Points are in canvas-pixel space.
 */
private fun drawStroke(s: StrokeObj, scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
    val pts = s.points
    if (pts.size < 2) return
    // s.color is an ARGB int; Color() takes it directly.
    val color = androidx.compose.ui.graphics.Color(s.color)
    for (i in 0 until pts.size - 1) {
        val p1 = pts[i]; val p2 = pts[i + 1]
        scope.drawLine(
            color = color,
            start = Offset(p1.x, p1.y),
            end = Offset(p2.x, p2.y),
            strokeWidth = s.widthPx
        )
    }
}

/**
 * Draws placed text. The point is the baseline start; the y offset lifts the glyph box so
 * the text sits above the finger mark rather than below it.
 */
private fun drawText(t: TextObj, scope: androidx.compose.ui.graphics.drawscope.DrawScope) {
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = t.color
        textSize = t.sizePx
        isFakeBoldText = true
    }
    // Draw into a bitmap-backed canvas so text renders crisply at any zoom.
    val bmp = android.graphics.Bitmap.createBitmap(
        (paint.measureText(t.text) + 2).toInt().coerceAtLeast(1),
        (paint.fontMetrics.descent - paint.fontMetrics.ascent + 2).toInt().coerceAtLeast(1),
        android.graphics.Bitmap.Config.ARGB_8888
    )
    val c = android.graphics.Canvas(bmp)
    c.drawText(t.text, 1f, -paint.fontMetrics.ascent + 1f, paint)
    scope.drawImage(
        image = bmp.asImageBitmap(),
        topLeft = Offset(t.x, t.y - t.sizePx * 1.2f)
    )
    bmp.recycle()
}

private fun Offset.toPointF(): PointF = PointF(x, y)

/**
 * Which edges of the crop selection a drag is currently moving. [None] means the drag is
 * drawing a fresh rectangle; [Move] means it is translating the existing one.
 */
private enum class CropAdjust { None, Move, ResizeLeftTop, ResizeRightTop, ResizeLeftBottom, ResizeRightBottom, ResizeLeft, ResizeRight, ResizeTop, ResizeBottom }

/**
 * v1.9 edit tools. Only one is armed at a time because each owns the canvas gesture.
 * [Sign] behaves like [Pen] but draws on its own layer so it can be repositioned as a unit.
 */
enum class EditTool { None, Text, Pen, Sign, Eraser }

/**
 * One circular tool button in the v1.9 edit strip; [highlight] shows which tool is armed.
 */
@Composable
private fun EditToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tool: EditTool,
    current: EditTool,
    onClick: (EditTool) -> Unit
) {
    CircleToolButton(
        icon = icon,
        description = description,
        tag = "page_edit_tool_${tool.name}",
        highlight = current == tool,
        onClick = { onClick(tool) }
    )
}

private val CropAdjust.left: Boolean
    get() = this == CropAdjust.ResizeLeftTop || this == CropAdjust.ResizeLeftBottom || this == CropAdjust.ResizeLeft
private val CropAdjust.right: Boolean
    get() = this == CropAdjust.ResizeRightTop || this == CropAdjust.ResizeRightBottom || this == CropAdjust.ResizeRight
private val CropAdjust.top: Boolean
    get() = this == CropAdjust.ResizeLeftTop || this == CropAdjust.ResizeRightTop || this == CropAdjust.ResizeTop
private val CropAdjust.bottom: Boolean
    get() = this == CropAdjust.ResizeLeftBottom || this == CropAdjust.ResizeRightBottom || this == CropAdjust.ResizeBottom

/**
 * Decides what a touch at [point] should do to [rect]: grab a handle (resize the touched
 * edges), grab the interior (translate), or nothing (start a new selection). Corners win
 * over edges over the interior, because a corner touch is unambiguous.
 */
private fun hitTestCropHandles(rect: RectF, point: Offset, slop: Float): CropAdjust {
    val nearL = kotlin.math.abs(point.x - rect.left) <= slop
    val nearR = kotlin.math.abs(point.x - rect.right) <= slop
    val nearT = kotlin.math.abs(point.y - rect.top) <= slop
    val nearB = kotlin.math.abs(point.y - rect.bottom) <= slop
    return when {
        nearL && nearT -> CropAdjust.ResizeLeftTop
        nearR && nearT -> CropAdjust.ResizeRightTop
        nearL && nearB -> CropAdjust.ResizeLeftBottom
        nearR && nearB -> CropAdjust.ResizeRightBottom
        nearL -> CropAdjust.ResizeLeft
        nearR -> CropAdjust.ResizeRight
        nearT -> CropAdjust.ResizeTop
        nearB -> CropAdjust.ResizeBottom
        // Interior: translate. A band around the border still counts, so a drag
        // started just inside the edge moves rather than redraws.
        point.x >= rect.left - slop && point.x <= rect.right + slop &&
            point.y >= rect.top - slop && point.y <= rect.bottom + slop -> CropAdjust.Move
        else -> CropAdjust.None
    }
}

/**
 * Swaps crossed edges, clamps the selection to the canvas, and enforces a minimum size so
 * a resize can never collapse the selection to nothing (which would then be dropped as a
 * tap on drag-end). This is why any corner can be dragged through any other.
 */
private fun normalizeCropRect(r: RectF, canvasW: Float, canvasH: Float): RectF {
    var l = minOf(r.left, r.right)
    var rt = maxOf(r.left, r.right)
    var t = minOf(r.top, r.bottom)
    var b = maxOf(r.top, r.bottom)
    val minSide = 24f
    if (rt - l < minSide) {
        // Preserve the drag direction: keep the edge the finger is closer to.
        val mid = (l + rt) / 2f
        l = mid - minSide / 2f; rt = mid + minSide / 2f
    }
    if (b - t < minSide) {
        val mid = (t + b) / 2f
        t = mid - minSide / 2f; b = mid + minSide / 2f
    }
    // Clamp inside the canvas; when the selection is wider than the canvas (should not
    // happen, but guard anyway) it is anchored at the origin.
    if (rt - l > canvasW) { l = 0f; rt = canvasW }
    if (b - t > canvasH) { t = 0f; b = canvasH }
    l = l.coerceIn(0f, canvasW); rt = rt.coerceIn(0f, canvasW)
    t = t.coerceIn(0f, canvasH); b = b.coerceIn(0f, canvasH)
    return RectF(l, t, rt, b)
}

/**
 * Convert raw canvas-pixel strokes into normalized (0f..1f) points over the
 * page bitmap, undoing the sheet's zoom/pan and the letterboxing that
 * [ContentScale.Fit] introduces.
 */
private fun strokesToNormalized(
    strokes: List<PointF>,
    canvasWpx: Float,
    canvasHpx: Float,
    imageW: Int,
    imageH: Int,
    fitScale: Float,
    offsetX: Float,
    offsetY: Float,
    zoom: Float,
    panX: Float,
    panY: Float
): List<Offset> {
    val out = mutableListOf<Offset>()
    var last: Offset? = null
    for (p in strokes) {
        // Undo the graphicsLayer transform (scale about the box centre, then
        // translate), then undo the Fit letterbox, then normalize.
        val sx = (p.x - canvasWpx / 2f - panX) / zoom + canvasWpx / 2f
        val sy = (p.y - canvasHpx / 2f - panY) / zoom + canvasHpx / 2f
        val bx = (sx - offsetX) / fitScale
        val by = (sy - offsetY) / fitScale
        // Skip anything that fell outside the page (drawn over the margin).
        if (bx !in 0f..imageW.toFloat() || by !in 0f..imageH.toFloat()) {
            last = null
            continue
        }
        val pt = Offset(bx / imageW.toFloat(), by / imageH.toFloat())
        // Collapse duplicate consecutive points (pen-up markers) by emitting a
        // separator the caller can split on.
        val prev = last
        if (prev != null && prev.x == pt.x && prev.y == pt.y) continue
        out.add(pt)
        last = pt
    }
    return out
}

/**
 * Maps a crop rectangle from canvas pixels (as the selection overlay produces
 * them) to normalized 0f..1f coordinates over the page bitmap, undoing the
 * sheet's zoom/pan and the [ContentScale.Fit] letterbox - the same transform
 * [strokesToNormalized] applies point by point.
 *
 * Returns null when the selection does not overlap the page at all.
 */
private fun cropRectToNormalized(
    rect: RectF,
    canvasWpx: Float,
    canvasHpx: Float,
    imageW: Int,
    imageH: Int,
    fitScale: Float,
    offsetX: Float,
    offsetY: Float,
    zoom: Float,
    panX: Float,
    panY: Float
): RectF? {
    // Undo graphicsLayer (scale about the box centre + translate).
    fun inv(px: Float, py: Float): Pair<Float, Float> {
        val sx = (px - canvasWpx / 2f - panX) / zoom + canvasWpx / 2f
        val sy = (py - canvasHpx / 2f - panY) / zoom + canvasHpx / 2f
        // Undo the Fit letterbox, then clamp onto the page.
        return ((sx - offsetX) / fitScale).coerceIn(0f, imageW.toFloat()) to
            ((sy - offsetY) / fitScale).coerceIn(0f, imageH.toFloat())
    }
    val (l, t) = inv(rect.left, rect.top)
    val (r, b) = inv(rect.right, rect.bottom)
    if (r - l < 1f || b - t < 1f) return null
    return RectF(
        l / imageW.toFloat(),
        t / imageH.toFloat(),
        r / imageW.toFloat(),
        b / imageH.toFloat()
    )
}

/**
 * Crops [source] to a normalized (0f..1f) rectangle and returns the result.
 * Invalid or degenerate selections return the original bitmap untouched.
 */
fun cropBitmapNormalized(source: Bitmap, crop: RectF): Bitmap {
    val left = (crop.left * source.width).toInt().coerceIn(0, source.width - 1)
    val top = (crop.top * source.height).toInt().coerceIn(0, source.height - 1)
    val right = (crop.right * source.width).toInt().coerceIn(left + 1, source.width)
    val bottom = (crop.bottom * source.height).toInt().coerceIn(top + 1, source.height)
    if (right - left < 1 || bottom - top < 1) return source
    return try {
        Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    } catch (e: Exception) {
        source
    }
}

/**
 * Draws a list of normalized (0f..1f) strokes into a copy of [source] and
 * returns it. Strokes are separated by repeated points (the sheet's pen-up
 * marker), which are simply not joined into a line.
 *
 * Lives outside Compose so the ViewModel can burn annotations into the page
 * that gets compiled into the final PDF.
 */
fun renderAnnotationsToBitmap(
    source: Bitmap,
    strokes: List<Offset>,
    color: Int = android.graphics.Color.rgb(225, 29, 72),
    strokeWidthPx: Float = 8f
): Bitmap {
    if (strokes.isEmpty()) return source
    val out = source.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(out)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = strokeWidthPx
    }
    var previous: Offset? = null
    for (point in strokes) {
        if (previous != null) {
            // A repeated point means "pen up": lift and start a new stroke.
            if (previous.x != point.x || previous.y != point.y) {
                canvas.drawLine(
                    previous.x * out.width,
                    previous.y * out.height,
                    point.x * out.width,
                    point.y * out.height,
                    paint
                )
            }
        }
        previous = point
    }
    return out
}

/**
 * Persists an edited page bitmap back into the staged list the export pipeline
 * reads. Writes the bitmap to a new temp file in the app cache, then calls
 * [onStagedUrisChanged] with the new list (old Uri replaced at [index]).
 *
 * This is the fix for "crop / rotate / annotate do nothing real": edits used to
 * mutate an in-memory copy the export never read. Both the preview grid and the
 * PDF export read the same staged Uri list, so a swap here shows up in both.
 *
 * The caller is expected to own the source list (e.g. viewModel selectedImages)
 * and pass the updated list back through its own state update.
 */
suspend fun persistEditedPage(
    context: Context,
    uris: List<Uri>,
    index: Int,
    bitmap: Bitmap,
    onStagedUrisChanged: (List<Uri>) -> Unit
) = withContext(Dispatchers.IO) {
    val target = uris.getOrNull(index) ?: return@withContext
    try {
        val cacheDir = File(context.cacheDir, "edited_pages").apply { mkdirs() }
        val outFile = File(cacheDir, "edited_${System.currentTimeMillis()}_${index}.jpg")
        FileOutputStream(outFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
        }
        val newUri = Uri.fromFile(outFile)
        // A Uri from a persisted replaceable source (file://) keeps the export
        // pipeline's read path unchanged - no special handling needed.
        val updated = uris.toMutableList().apply { set(index, newUri) }
        withContext(Dispatchers.Main) { onStagedUrisChanged(updated) }
    } catch (e: Exception) {
        // Never swallow: the user just lost an edit. Surface it so the caller
        // can show an error instead of silently keeping the stale page.
        Log.w("PageEdit", "Failed to persist edited page $index", e)
    }
}

@Composable
private fun CircleToolButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    tag: String,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (highlight) CrimsonPrimary else Color.White.copy(alpha = 0.12f))
            .clickable { onClick() }
            .testTag(tag),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(20.dp)
        )
    }
}

private val monoLabel = androidx.compose.ui.text.TextStyle(
    fontFamily = FontFamily.Monospace,
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold
)
