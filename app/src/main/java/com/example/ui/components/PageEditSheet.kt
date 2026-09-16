package com.example.ui.components

import androidx.compose.foundation.Canvas
import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
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
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.theme.EmeraldSuccess

/**
 * Fullscreen page editor opened by tapping a thumbnail in any tool's page list.
 *
 * Gives every workbench the same set of per-page tools:
 *  - [Zoom]: pinch on the page.
 *  - [Rotate]: 90 degrees clockwise, applied to the staged page immediately
 *    through [onRotatePage] so the grid underneath updates.
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

    // Raw strokes in the canvas' pixel space; converted to normalized points
    // (relative to the page bitmap) when the user hits the apply button.
    val strokes = remember { mutableStateListOf<PointF>() }
    val density = LocalDensity.current
    val listState = rememberLazyListState()

    LaunchedEffect(currentPage) {
        listState.scrollToItem(currentPage.coerceIn(0, pages.lastIndex))
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
                            icon = Icons.Default.Brush,
                            description = "Toggle drawing",
                            tag = "page_edit_draw",
                            highlight = drawMode,
                            onClick = { drawMode = !drawMode }
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
                    val canvasWpx = with(density) { maxWidth.toPx() }
                    val canvasHpx = with(density) { maxHeight.toPx() }
                    val page = pages[currentPage]

                    // ContentScale.Fit: the page is letterboxed inside the box,
                    // so compute the transform that maps the box onto the image.
                    val fitScale = minOf(
                        canvasWpx / page.width,
                        canvasHpx / page.height
                    ).coerceAtLeast(0.0001f)
                    val offsetX = (canvasWpx - page.width * fitScale) / 2f
                    val offsetY = (canvasHpx - page.height * fitScale) / 2f

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

                    // Gestures: draw when the brush is active, otherwise
                    // pinch-zoom and pan. One pointerInput per mode, switched
                    // by the toggle so the two detectors never compete.
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(drawMode) {
                                if (drawMode) {
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
                                } else {
                                    detectTransformGestures { _, pan, zoomChange, _, _ ->
                                        zoom = (zoom * zoomChange).coerceIn(1f, 6f)
                                        panX += pan.x
                                        panY += pan.y
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
                            onClick = {
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
                                if (normalized.isNotEmpty()) {
                                    onAnnotatePage(target, normalized)
                                }
                            },
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
    var last: PointF? = null
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
