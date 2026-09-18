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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldSuccess

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

    // Live crop selection, in the canvas' pixel space (same space the strokes
    // use). Null until the user starts a drag; recomputed in the overlay below.
    var cropRect by remember { mutableStateOf<RectF?>(null) }
    // Where the current drag started, so the selection can be built in any
    // direction (up-left as well as down-right).
    var cropAnchor by remember { mutableStateOf<Offset?>(null) }
    // Page dimensions the selection was made against, so a stale selection is
    // never applied after the user swipes to a different page.
    var cropPageKey by remember { mutableStateOf(RectF(0f, 0f, 0f, 0f)) }

    // Raw strokes in the canvas' pixel space; converted to normalized points
    // (relative to the page bitmap) when the user hits the apply button.
    val strokes = remember { mutableStateListOf<PointF>() }
    // Zero-arg lambdas by default: both APPLY buttons live outside the
    // BoxWithConstraints that computes the geometry, so they are assigned real
    // implementations once the page has been measured.
    var applyStrokes by remember { mutableStateOf({}) }
    var applyCrop by remember { mutableStateOf({}) }
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
                                // Drawing and cropping share the drag gesture,
                                // so only one can be armed at a time.
                                if (cropMode) drawMode = false
                            }
                        )
                        CircleToolButton(
                            icon = Icons.Default.Brush,
                            description = "Toggle drawing",
                            tag = "page_edit_draw",
                            highlight = drawMode,
                            onClick = {
                                drawMode = !drawMode
                                if (drawMode) {
                                    cropMode = false
                                    cropRect = null
                                }
                            }
                        )
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
                        val normalized = strokesToNormalized(
                            strokes = strokes.toList(),
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
                        strokes.clear()
                        drawMode = false
                        if (normalized.isNotEmpty()) onAnnotatePage(target, normalized)
                    }

                    // The crop APPLY button also lives outside BoxWithConstraints,
                    // so it gets the same ready-made geometry.
                    applyCrop = {
                        val target = currentPage
                        val raw = cropRect
                        // Guard against a selection made for a different page.
                        if (raw == null || cropPageKey.width != page.width.toFloat() ||
                            cropPageKey.height != page.height.toFloat()
                        ) return@applyCrop
                        val normalized = cropRectToNormalized(
                            rect = raw,
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
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            for (i in 0 until strokes.size - 1) {
                                val p1 = strokes[i]
                                val p2 = strokes[i + 1]
                                // Pen up marker: consecutive equal points
                                // separate strokes and must not be joined.
                                if (p1.x == p2.x && p1.y == p2.y) continue
                                drawLine(
                                    color = CrimsonPrimary,
                                    start = Offset(p1.x, p1.y),
                                    end = Offset(p2.x, p2.y),
                                    strokeWidth = 8f
                                )
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
                            // Corner handles, so the region reads as an
                            // adjustable selection rather than a marquee.
                            val handle = 14f
                            for (corner in listOf(
                                Offset(selection.left, selection.top),
                                Offset(selection.right, selection.top),
                                Offset(selection.left, selection.bottom),
                                Offset(selection.right, selection.bottom)
                            )) {
                                drawRect(
                                    color = CrimsonPrimary,
                                    topLeft = Offset(
                                        corner.x - handle / 2f,
                                        corner.y - handle / 2f
                                    ),
                                    size = Size(handle, handle)
                                )
                            }
                        }
                    }

                    // Gestures: draw when the brush is active, crop when the
                    // crop tool is armed, otherwise pinch-zoom and pan. One
                    // pointerInput per mode, keyed so the detectors never
                    // compete for the same drag.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(drawMode, cropMode) {
                                when {
                                    drawMode -> {
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                strokes.add(PointF(offset.x, offset.y))
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                strokes.add(PointF(change.position.x, change.position.y))
                                            },
                                            onDragEnd = {
                                                // Pen-up marker between strokes.
                                                strokes.add(
                                                    PointF(
                                                        strokes.lastOrNull()?.x ?: -1f,
                                                        strokes.lastOrNull()?.y ?: -1f
                                                    )
                                                )
                                            }
                                        )
                                    }
                                    cropMode -> {
                                        // Drag a new selection from scratch;
                                        // the live rectangle follows the
                                        // finger and grows in any direction.
                                        detectDragGestures(
                                            onDragStart = { offset ->
                                                cropPageKey = RectF(
                                                    page.width.toFloat(),
                                                    page.height.toFloat(),
                                                    0f,
                                                    0f
                                                )
                                                cropAnchor = offset
                                                cropRect = RectF(offset.x, offset.y, offset.x, offset.y)
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val a = cropAnchor ?: change.position
                                                // Build the rectangle from the
                                                // anchor and the current point
                                                // so dragging up-left works as
                                                // well as down-right.
                                                cropRect = RectF(
                                                    minOf(a.x, change.position.x),
                                                    minOf(a.y, change.position.y),
                                                    maxOf(a.x, change.position.x),
                                                    maxOf(a.y, change.position.y)
                                                )
                                            },
                                            onDragEnd = {
                                                cropAnchor = null
                                                // A tap without a drag is not a
                                                // crop: drop the empty selection.
                                                val r = cropRect
                                                if (r == null || r.right - r.left < 8f || r.bottom - r.top < 8f) {
                                                    cropRect = null
                                                }
                                            }
                                        )
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
                    )
                }

                // Apply / clear annotation strokes.
                if (drawMode && strokes.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { strokes.clear() },
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
