package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.LocalLibrary
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess

data class DocumentTemplate(
    val id: String,
    val title: String,
    val category: String,
    val description: String,
    val sampleText: String
)

val PREBUILT_TEMPLATES = listOf(
    DocumentTemplate(
        id = "formal_letter",
        title = "Formal Business Letter",
        category = "COMMUNICATION",
        description = "Standard corporate letterhead format with date, addressee, formal body, and executive closing.",
        sampleText = """Date: September 15, 2026

To:
Acme Global Technologies Inc.
742 Evergreen Terrace, Suite 400

Subject: Strategic Enterprise Collaboration Proposal

Dear Executive Committee,

We are pleased to submit this formal proposal outlining our integrated digital document infrastructure. Our high-throughput on-device PDF engine provides enterprise-grade formatting, cryptographic integrity, and zero cloud latency.

Key Deliverables:
1. Complete local batch conversion with customized vector rendering.
2. Robust OCR data extraction compliant with local data governance standards.
3. Automated document union pipelines with lossless compression.

We look forward to scheduling a technical briefing at your earliest convenience.

Sincerely,

Operations Directorate
OmniPDF Enterprise Solutions"""
    ),
    DocumentTemplate(
        id = "meeting_minutes",
        title = "Executive Meeting Minutes",
        category = "MANAGEMENT",
        description = "Structured agenda, attendees list, key discussion points, and assigned action item table.",
        sampleText = """MEETING MINUTES & STRATEGY SYNC
Date: September 15, 2026 | Time: 10:00 AM UTC
Chairperson: Engineering Lead | Scribe: Project Manager

ATTENDEES:
- Engineering Operations
- Architecture Directorate
- Quality Assurance Lead

AGENDA:
1. Review of Q3 PDF Performance Metrics
2. Architecture Rollout for ML-based On-Device OCR
3. Storage Optimization & Multi-Page Reordering

KEY DECISIONS:
- Approved lossless compression pipeline for mobile exports.
- Adopted strict on-device data confidentiality guidelines.
- Standardized default output dimensions to ISO A4 format.

ACTION ITEMS:
[ ] Benchmark rendering throughput across diverse multi-page batches.
[ ] Deploy automated document hash verification in next build cycle.
[ ] Finalize localization templates for international stakeholders."""
    ),
    DocumentTemplate(
        id = "commercial_invoice",
        title = "Commercial Tax Invoice",
        category = "FINANCE",
        description = "Clean billing statement with itemized deliverables, payment terms, and total breakdown.",
        sampleText = """COMMERCIAL TAX INVOICE
Invoice No: INV-2026-0894 | Date: September 15, 2026
Due Date: Net 30 Days

BILLED TO:
Client Organization: Horizon Media Ventures
Tax ID: US-EIN-94820194
Address: 100 Innovation Parkway, New York, NY

ITEMIZED SERVICES:
1. Technical Document Architecture Consulting ........... ${'$'}2,400.00
2. Multi-Format PDF Conversion Engine Integration ..... ${'$'}4,850.00
3. OCR Data Pipeline Validation & Testing ............. ${'$'}1,750.00
-----------------------------------------------------------------
SUBTOTAL: ............................................ ${'$'}9,000.00
TAX (0%): ............................................ ${'$'}0.00
TOTAL DUE: ........................................... ${'$'}9,000.00

Payment Details:
Bank: First International Commerce Bank
Routing: 021000021 | Account: 84920194812
Payment Terms: Wire transfer within 30 days of issuance."""
    ),
    DocumentTemplate(
        id = "nda_agreement",
        title = "Non-Disclosure Agreement (NDA)",
        category = "LEGAL",
        description = "Mutual confidentiality agreement safeguarding proprietary algorithms, source code, and assets.",
        sampleText = """MUTUAL NON-DISCLOSURE AGREEMENT

This Mutual Non-Disclosure Agreement ("Agreement") is entered into as of September 15, 2026, by and between the Disclosing Party and the Receiving Party.

1. PURPOSE
The parties wish to explore a business opportunity concerning advanced mobile document conversion algorithms and secure client-side cryptographic storage.

2. CONFIDENTIAL INFORMATION
"Confidential Information" refers to any proprietary technical data, trade secrets, software architecture, or client records disclosed either directly or indirectly.

3. OBLIGATIONS
The Receiving Party agrees to:
a) Hold all Confidential Information in strict confidence.
b) Restrict access solely to authorized personnel with a need to know.
c) Refrain from reverse engineering, reproducing, or exporting proprietary algorithms.

4. TERM & GOVERNING LAW
This agreement remains enforceable for a period of three (3) years from the effective date.

[Authorized Signature]                     [Authorized Signature]
Disclosing Party                          Receiving Party"""
    )
)

@Composable
fun MyLibraryScreen(
    onApplyTemplate: (title: String, content: String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var copiedTemplateId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Top Action Bar
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
                    .testTag("my_library_back_button")
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
                    .clip(RoundedCornerShape(8.dp))
                    .background(CyanAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.LocalLibrary,
                    contentDescription = null,
                    tint = CyanAccent,
                    modifier = Modifier.size(20.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "MY LIBRARY",
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                )
                Text(
                    text = "TEMPLATES, SNIPPETS & ASSETS",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = CyanAccent
                    )
                )
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = CyanAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "READY-TO-USE DOCUMENT TEMPLATES",
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = CyanAccent,
                                letterSpacing = 1.sp
                            )
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Pick a template to instantly load into the Text-to-PDF Composer with optimal formatting",
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }

            items(PREBUILT_TEMPLATES) { template ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = template.title,
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(CyanAccent.copy(alpha = 0.15f))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = template.category,
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CyanAccent
                                    )
                                )
                            }
                        }

                        Text(
                            text = template.description,
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 16.sp
                            )
                        )

                        // Sample Preview Snippet
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.background)
                                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                                .padding(10.dp)
                        ) {
                            Text(
                                text = template.sampleText.take(180) + "...",
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 10.sp,
                                    lineHeight = 14.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                                )
                            )
                        }

                        // Action Buttons
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Copy button
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(template.sampleText))
                                        copiedTemplateId = template.id
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.ContentCopy,
                                        contentDescription = "Copy",
                                        tint = if (copiedTemplateId == template.id) EmeraldSuccess else MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (copiedTemplateId == template.id) "COPIED" else "COPY TEXT",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (copiedTemplateId == template.id) EmeraldSuccess else MaterialTheme.colorScheme.onSurface
                                        )
                                    )
                                }
                            }

                            // Use in Composer button
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(CyanAccent)
                                    .clickable { onApplyTemplate(template.title, template.sampleText) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.PostAdd,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "USE IN COMPOSER",
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.Black
                                        )
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(30.dp))
            }
        }
    }
}
