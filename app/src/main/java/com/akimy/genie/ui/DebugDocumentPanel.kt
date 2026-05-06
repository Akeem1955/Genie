package com.akimy.genie.ui

import android.content.Context
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

private const val TAG = "GenieDebugDocument"

private val Accent = Color(0xFF38BDF8)
private val AccentAlt = Color(0xFF6C63FF)
private val Danger = Color(0xFFDC2626)
private val Green = Color(0xFF31E7B6)
private val StripBg = Color(0xFF181A20)
private val BoardBg = Color(0xFF0F1117)

/**
 * Compact bottom-strip debug panel for Document profile tools.
 * Sits at the bottom of the screen so the user can interact with PDF apps behind it.
 * Tap the status bar to expand/collapse the results area.
 */
@Composable
fun DebugDocumentPanel(onClose: () -> Unit = {}, onDetectPdf: suspend () -> String = { "✗ Not wired" }) {
    val context = LocalContext.current
    var lastResult by remember { mutableStateOf("📄 Document Debug — tap tools below") }
    var isError by remember { mutableStateOf(false) }
    var extractedText by remember { mutableStateOf("") }
    var pdfInfo by remember { mutableStateOf("") }
    var foundPdfs by remember { mutableStateOf<List<File>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    fun exec(name: String, block: () -> String) {
        Log.d(TAG, "━━━ $name ━━━")
        val r = try { block().also { Log.d(TAG, "  → $it") } } catch (e: Exception) { Log.e(TAG, "ERR", e); "✗ ${e.message}" }
        isError = r.startsWith("✗"); lastResult = "[$name] $r"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(StripBg, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
    ) {
        // ═══ STATUS BAR (tap to expand/collapse) ═══
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = onClose,
                modifier = Modifier.size(26.dp),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Danger),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("✕", color = Color.White, fontSize = 11.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                lastResult,
                color = if (isError) Color(0xFFF87171) else Green,
                fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (expanded) "▼" else "▲",
                color = Color(0xFF64748B),
                fontSize = 14.sp,
            )
            if (foundPdfs.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text("${foundPdfs.size}📄", color = Accent, fontSize = 11.sp)
            }
        }

        // ═══ EXPANDABLE RESULTS ═══
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .background(BoardBg)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                if (pdfInfo.isNotBlank()) {
                    Text(pdfInfo, color = Color(0xFF9CA3AF), fontSize = 11.sp,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B), RoundedCornerShape(6.dp)).padding(8.dp))
                    Spacer(Modifier.height(6.dp))
                }
                if (extractedText.isNotBlank()) {
                    Text("Extracted Text", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    Text(extractedText, color = Color(0xFFE2E8F0), fontSize = 12.sp, lineHeight = 16.sp,
                        modifier = Modifier.fillMaxWidth().background(Color(0xFF1E293B), RoundedCornerShape(6.dp)).padding(8.dp))
                } else if (foundPdfs.isNotEmpty()) {
                    Text("Found PDFs:", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(3.dp))
                    foundPdfs.forEachIndexed { i, f ->
                        Text("${i + 1}. ${f.name} (${f.length() / 1024}KB)",
                            color = Color(0xFFCBD5E1), fontSize = 11.sp,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                } else {
                    Text("Tap List PDFs → then Read to extract text.\nYou can open a PDF app behind this strip.",
                        color = Color(0xFF64748B), fontSize = 12.sp)
                }
            }
        }

        // ═══ TOOL BUTTONS ═══
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Chip("List PDFs", Accent) {
                exec("list_device_pdfs") {
                    val dirs = listOf(
                        "/storage/emulated/0/Download",
                        "/storage/emulated/0/Documents",
                        "/storage/emulated/0/DCIM",
                    )
                    val pdfs = mutableListOf<File>()
                    dirs.forEach { dir ->
                        File(dir).listFiles()?.filter { it.extension.equals("pdf", true) }?.let { pdfs.addAll(it) }
                    }
                    foundPdfs = pdfs.take(20)
                    expanded = true
                    if (pdfs.isEmpty()) "✗ No PDFs found" else "✓ Found ${pdfs.size} PDF(s)"
                }
            }

            Chip("Detect", AccentAlt) {
                lastResult = "[detect_open_pdf] Capturing screenshot…"
                scope.launch {
                    val result = try { onDetectPdf() } catch (e: Exception) { "✗ ${e.message}" }
                    Log.d(TAG, "━━━ detect_open_pdf ━━━\n  → ${result.take(200)}")
                    isError = result.startsWith("✗")
                    lastResult = "[detect_open_pdf] ${result.lines().first()}"
                    if (!isError) { pdfInfo = result; expanded = true }
                }
            }

            Sep()

            Chip("Read 1st", Accent) {
                exec("read_pdf") {
                    val pdf = foundPdfs.firstOrNull() ?: return@exec "✗ Tap List PDFs first."
                    val result = readPdfText(context, pdf, 0, 2)
                    pdfInfo = "File: ${pdf.name}\nSize: ${pdf.length() / 1024}KB\nPages: 1-3"
                    extractedText = result
                    expanded = true
                    if (result.isNotBlank()) "✓ ${result.length} chars" else "✗ No text"
                }
            }

            Chip("Read All", Accent) {
                exec("read_all") {
                    val pdf = foundPdfs.firstOrNull() ?: return@exec "✗ Tap List PDFs first."
                    val result = readPdfText(context, pdf, 0, 99)
                    pdfInfo = "File: ${pdf.name}\nSize: ${pdf.length() / 1024}KB\nPages: all"
                    extractedText = result
                    expanded = true
                    if (result.isNotBlank()) "✓ ${result.length} chars" else "✗ No text"
                }
            }

            Sep()

            Chip("Clear", Danger) {
                exec("clear") {
                    extractedText = ""; pdfInfo = ""; foundPdfs = emptyList(); expanded = false
                    "✓ Cleared"
                }
            }
        }
    }
}

/**
 * Extract text from PDF using PdfRenderer + textContents (API 35+).
 * Same approach as the real read_pdf_page_range tool in AgentOrchestrator.
 */
private fun readPdfText(context: Context, file: File, startPage: Int, endPage: Int): String {
    return try {
        val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = android.graphics.pdf.PdfRenderer(fd)
        val pageCount = renderer.pageCount
        val sb = StringBuilder()
        sb.appendLine("PDF: ${file.name} — $pageCount pages total")
        sb.appendLine()

        val actualEnd = minOf(endPage, pageCount - 1)
        val missingPages = mutableListOf<Int>()

        for (i in startPage..actualEnd) {
            val page = renderer.openPage(i)
            val pageText = if (android.os.Build.VERSION.SDK_INT >= 35) {
                try {
                    @Suppress("NewApi")
                    page.textContents
                        .mapNotNull { it?.text?.toString() }
                        .joinToString("\n")
                        .trim()
                } catch (_: Exception) { "" }
            } else ""

            if (pageText.isBlank()) {
                missingPages.add(i + 1)
                sb.appendLine("[PAGE ${i + 1}] (no extractable text)")
            } else {
                sb.appendLine("[PAGE ${i + 1}]")
                sb.appendLine(pageText)
            }
            sb.appendLine()
            page.close()
        }

        if (missingPages.isNotEmpty()) {
            sb.appendLine("⚠ No text on pages: ${missingPages.joinToString(", ")} (may be image-only)")
        }

        renderer.close()
        fd.close()
        sb.toString()
    } catch (e: Exception) {
        Log.e(TAG, "PDF read error", e)
        "Error: ${e.message}"
    }
}

@Composable
private fun Chip(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(32.dp),
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
    ) {
        Text(label, fontSize = 11.sp, color = Color.White)
    }
}

@Composable
private fun Sep() {
    Spacer(Modifier.width(1.dp).height(20.dp).background(Color(0xFF334155)))
}
