package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.filled.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.PdfEngine
import com.example.ui.theme.CrimsonPrimary
import com.example.ui.viewmodel.PdfMetadataItem

@Composable
fun ReorderablePdfList(
    items: List<PdfMetadataItem>,
    onMove: (fromIndex: Int, toIndex: Int) -> Unit,
    onRemove: (index: Int) -> Unit,
    onReverse: () -> Unit,
    onSortByName: () -> Unit,
    onSortBySize: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expandedControlsIndex by remember { mutableStateOf<Int?>(null) }

    // Long-press drag state: which card is held, and how far it has travelled.
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var draggedOffset by remember { mutableStateOf(0f) }
    val listState = rememberLazyListState()

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Quick Queue Sorting Bar
        if (items.size >= 2) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REORDER:",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Reverse Order
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { onReverse() }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SwapVert,
                            contentDescription = "Reverse",
                            tint = CrimsonPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Reverse",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }

                // Sort A-Z
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { onSortByName() }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SortByAlpha,
                            contentDescription = "Sort A to Z",
                            tint = CrimsonPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "A → Z",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }

                // Sort by Size
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                        .clickable { onSortBySize() }
                        .padding(horizontal = 8.dp, vertical = 5.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Sort,
                            contentDescription = "Sort Size",
                            tint = CrimsonPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "By Size",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        )
                    }
                }
            }
        }

        // List of draggable / reorderable item cards.
        //
        // LONG-PRESS DRAG: hold a card to pick it up and drag it to a new
        // position; the list reorders live. The arrow controls below stay as a
        // precise fallback. Previously the drag handle was just a click that
        // expanded the arrows - there was no drag gesture at all.
        LazyColumn(
            state = listState,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(items.size) {
                    // Re-attach the gesture scope whenever the list length
                    // changes so the target indices stay valid.
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            // Which card did the user grab? Map the touch point
                            // to a list index via the layout info.
                            draggedIndex = listState.layoutInfo
                                .visibleItemsInfo.firstOrNull { info ->
                                    offset.y.toInt() in info.offset..(info.offset + info.size)
                                }?.index
                        },
                        onDragEnd = { draggedIndex = null },
                        onDragCancel = { draggedIndex = null },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val from = draggedIndex
                            if (from == null) return@detectDragGesturesAfterLongPress
                            // Scroll while dragging past the list edges.
                            draggedOffset += dragAmount.y
                            val threshold = 56f
                            while (draggedOffset > threshold && from < items.size - 1) {
                                draggedOffset -= threshold
                                onMove(from, from + 1)
                            }
                            while (draggedOffset < -threshold && from > 0) {
                                draggedOffset += threshold
                                onMove(from, from - 1)
                            }
                        }
                    )
                }
        ) {
            itemsIndexed(items, key = { _, item -> item.uri }) { index, item ->
                val isExpanded = expandedControlsIndex == index
                val isDragging = draggedIndex == index
                val elevation by animateDpAsState(
                    targetValue = if (isExpanded) 4.dp else 0.dp,
                    label = "elevation"
                )

                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = elevation,
                    shadowElevation = if (isDragging) 8.dp else 0.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isExpanded) CrimsonPrimary else MaterialTheme.colorScheme.outline,
                            RoundedCornerShape(14.dp)
                        )
                        .testTag("reorder_item_$index")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Left: Sequence + Drag Handle + Title
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                // Drag Handle Icon
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable {
                                            expandedControlsIndex = if (isExpanded) null else index
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DragIndicator,
                                        contentDescription = "Reorder handle",
                                        tint = if (isExpanded) CrimsonPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                // Order Number Pill
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(CrimsonPrimary.copy(alpha = 0.12f))
                                        .border(1.dp, CrimsonPrimary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 6.dp, vertical = 2.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = String.format("%02d", index + 1),
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold,
                                            color = CrimsonPrimary,
                                            fontSize = 11.sp
                                        )
                                    )
                                }

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.name,
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "${item.pageCount} PAGES • ${PdfEngine.formatFileSize(item.sizeBytes)}",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    )
                                }
                            }

                            // Right: Primary Step Controls + Delete
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                // Move Up
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = index > 0) {
                                            onMove(index, index - 1)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = "Move Up",
                                        tint = if (index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Move Down
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable(enabled = index < items.size - 1) {
                                            onMove(index, index + 1)
                                        },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowDown,
                                        contentDescription = "Move Down",
                                        tint = if (index < items.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }

                                // Delete
                                Box(
                                    modifier = Modifier
                                        .size(28.dp)
                                        .clip(CircleShape)
                                        .clickable { onRemove(index) },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = CrimsonPrimary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Expanded Jumpers (Top / Bottom)
                        AnimatedVisibility(
                            visible = isExpanded,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Send to Top
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable(enabled = index > 0) {
                                            onMove(index, 0)
                                            expandedControlsIndex = null
                                        }
                                        .padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VerticalAlignTop,
                                            contentDescription = null,
                                            tint = if (index > 0) CrimsonPrimary else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "MOVE TO TOP",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (index > 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                            )
                                        )
                                    }
                                }

                                // Send to Bottom
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable(enabled = index < items.size - 1) {
                                            onMove(index, items.size - 1)
                                            expandedControlsIndex = null
                                        }
                                        .padding(vertical = 5.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.VerticalAlignBottom,
                                            contentDescription = null,
                                            tint = if (index < items.size - 1) CrimsonPrimary else MaterialTheme.colorScheme.outline,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "MOVE TO BOTTOM",
                                            style = MaterialTheme.typography.labelSmall.copy(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (index < items.size - 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

