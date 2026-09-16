package com.example.data.model

import android.net.Uri
import com.example.engine.ScanFilter

/**
 * One captured and enhanced page produced by the in-app camera document scanner.
 *
 * The raw camera frame is kept as [sourceUri] so enhancement filters can be
 * re-applied non-destructively, while [processedUri] holds the current
 * pipeline output that gets compiled into the final PDF.
 */
data class ScanPage(
    val id: String,
    val processedUri: Uri,
    val sourceUri: Uri,
    val filter: ScanFilter,
    val width: Int,
    val height: Int
)
