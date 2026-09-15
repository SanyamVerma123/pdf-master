package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.DocumentScanner
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ConversionType
import com.example.ui.theme.CrimsonPrimary

data class ToolDefinition(
    val type: ConversionType,
    val label: String,
    val subtitle: String,
    val icon: ImageVector
)

val AVAILABLE_TOOLS = listOf(
    ToolDefinition(
        type = ConversionType.IMAGE_TO_PDF,
        label = "Images to PDF",
        subtitle = "Photo / Gallery",
        icon = Icons.Outlined.PhotoLibrary
    ),
    ToolDefinition(
        type = ConversionType.PHOTO_OCR_TO_PDF,
        label = "Photo OCR",
        subtitle = "Scan to PDF",
        icon = Icons.Outlined.DocumentScanner
    ),
    ToolDefinition(
        type = ConversionType.TEXT_TO_PDF,
        label = "Text to PDF",
        subtitle = "Notes & Docs",
        icon = Icons.Outlined.EditNote
    ),
    ToolDefinition(
        type = ConversionType.MERGE_PDF,
        label = "Merge PDFs",
        subtitle = "Reorder & Join",
        icon = Icons.Outlined.CallSplit
    ),
    ToolDefinition(
        type = ConversionType.PDF_TO_IMAGES,
        label = "PDF to Images",
        subtitle = "Page Extractor",
        icon = Icons.Outlined.Collections
    )
)

@Composable
fun ToolSelectorStrip(
    activeTool: ConversionType,
    onToolSelected: (ConversionType) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        AVAILABLE_TOOLS.forEach { tool ->
            val isSelected = activeTool == tool.type

            val bgColor by animateColorAsState(
                targetValue = if (isSelected) CrimsonPrimary else MaterialTheme.colorScheme.surface,
                animationSpec = spring(),
                label = "bgColor"
            )
            val borderColor by animateColorAsState(
                targetValue = if (isSelected) CrimsonPrimary else MaterialTheme.colorScheme.outline,
                animationSpec = spring(),
                label = "borderColor"
            )
            val iconTint by animateColorAsState(
                targetValue = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "iconTint"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
                label = "textColor"
            )
            val subtextColor by animateColorAsState(
                targetValue = if (isSelected) Color(0xFFFFD9DF) else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "subtextColor"
            )

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(bgColor)
                    .border(1.dp, borderColor, RoundedCornerShape(16.dp))
                    .clickable { onToolSelected(tool.type) }
                    .testTag("tool_chip_${tool.type.name.lowercase()}")
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) Color.White.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceVariant
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = tool.icon,
                            contentDescription = tool.label,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Column {
                        Text(
                            text = tool.label,
                            style = MaterialTheme.typography.labelLarge.copy(
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = textColor,
                                fontSize = 13.sp
                            )
                        )
                        Text(
                            text = tool.subtitle,
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                color = subtextColor
                            )
                        )
                    }
                }
            }
        }
    }
}
