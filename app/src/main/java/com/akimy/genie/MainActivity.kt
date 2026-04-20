package com.akimy.genie

import android.Manifest
import android.content.Intent
import android.graphics.Paint
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akimy.genie.service.ScreenMapStore
import com.akimy.genie.tools.SceneSnapshot
import com.akimy.genie.tools.VisualizerSceneStore
import kotlinx.coroutines.delay

private const val TAG = "GenieMainActivity"

/**
 * Minimal MainActivity for Genie.
 *
 * This is a lightweight setup screen for:
 * 1. Granting RECORD_AUDIO permission
 * 2. Navigating to Accessibility Settings to enable the service
 */
class MainActivity : ComponentActivity() {

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "RECORD_AUDIO permission: $isGranted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        VisualizerSceneStore.initialize(applicationContext)
        ScreenMapStore.initialize(applicationContext)

        audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)

        setContent {
            MaterialTheme {
                GenieSetupScreen()
            }
        }
    }
}

@Composable
fun GenieSetupScreen() {
    val context = LocalContext.current
    var refreshTick by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            refreshTick = System.currentTimeMillis()
        }
    }

    val snapshot = remember(refreshTick) { VisualizerSceneStore.getLatestSnapshot() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = "Genie",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Autonomous AI Accessibility Agent",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Setup Status",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusRow(
                        label = "On-device Model",
                        done = true,
                    )
                    StatusRow(
                        label = "Accessibility Service",
                        done = false,
                    )
                }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Concept Canvas Preview",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { refreshTick = System.currentTimeMillis() }) {
                                Text("Refresh")
                            }
                            TextButton(
                                enabled = snapshot != null,
                                onClick = {
                                    val intent = Intent(context, VisualizerCanvasActivity::class.java).apply {
                                        putExtra(VisualizerCanvasActivity.EXTRA_SCENE_ID, snapshot?.scene?.sceneId)
                                    }
                                    context.startActivity(intent)
                                },
                            ) {
                                Text("Open")
                            }
                        }
                    }
                    VisualizerPreview(snapshot = snapshot)
                }
            }

            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                ),
            ) {
                Text("Enable Genie Accessibility Service")
            }

            Text(
                text = "After enabling the service, say \"Gemma\" to activate Genie.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
fun VisualizerPreview(snapshot: SceneSnapshot?) {
    if (snapshot == null) {
        Text(
            text = "No scene available yet. Ask Genie to visualize a concept first.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val isBoard = snapshot.scene.board != null
    val density = LocalDensity.current
    val layout = snapshot.layout
    val focusSet = remember(snapshot.scene.focusNodeIds) { snapshot.scene.focusNodeIds.toSet() }
    val viewport = if (isBoard) boardViewport(snapshot.scene) else null

    val maxNodeX = layout.nodeLayouts.maxOfOrNull { it.x + it.width } ?: 600f
    val maxNodeY = layout.nodeLayouts.maxOfOrNull { it.y + it.height } ?: 320f
    val canvasWidthPx = viewport?.widthPx ?: (maxNodeX + 84f)
    val canvasHeightPx = viewport?.heightPx ?: (maxNodeY + 84f)
    val canvasWidthDp = with(density) { canvasWidthPx.toDp() }
    val canvasHeightDp = with(density) { canvasHeightPx.toDp() }
    val xScroll = rememberScrollState()
    val yScroll = rememberScrollState()

    Text(
        text = buildString {
            append(snapshot.scene.title.ifBlank { "Untitled" })
            append(" (")
            append(if (isBoard) "teaching board" else layout.layoutType)
            append(")")
            if (isBoard) {
                boardCurrentStepLabel(snapshot.scene)?.let {
                    append(" • ")
                    append(it)
                }
            }
        },
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(bottom = 8.dp),
    )

    if (isBoard) {
        boardNarration(snapshot.scene)?.let { narration ->
            Text(
                text = narration,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .background(
                if (isBoard) boardSurfaceBackground(snapshot.scene.board?.theme)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                RoundedCornerShape(12.dp),
            )
            .padding(8.dp)
            .horizontalScroll(xScroll)
            .verticalScroll(yScroll),
    ) {
        Canvas(
            modifier = Modifier
                .size(canvasWidthDp, canvasHeightDp)
                .background(
                    if (isBoard) boardCanvasBackground(snapshot.scene.board?.theme) else Color(0xFFF7F9FB),
                    RoundedCornerShape(10.dp),
                ),
        ) {
            if (isBoard) {
                drawTeachingBoard(snapshot.scene)
            } else {
                layout.edgeLayouts.forEach { edge ->
                    val isFocused = focusSet.contains(edge.from) || focusSet.contains(edge.to)
                    val color = if (isFocused) Color(0xFF0B8A5A) else Color(0xFF4B5563)
                    val stroke = if (isFocused) 3.2f else 2.0f

                    val points = edge.points
                    for (i in 0 until points.lastIndex) {
                        val from = points[i]
                        val to = points[i + 1]
                        drawLine(
                            color = color,
                            start = Offset(from.x, from.y),
                            end = Offset(to.x, to.y),
                            strokeWidth = stroke,
                            pathEffect = PathEffect.cornerPathEffect(10f),
                        )
                    }

                    if (points.size >= 2) {
                        val p2 = points.last()
                        val arrow = 7f
                        drawLine(color, Offset(p2.x, p2.y), Offset(p2.x - arrow, p2.y - arrow), stroke)
                        drawLine(color, Offset(p2.x, p2.y), Offset(p2.x - arrow, p2.y + arrow), stroke)
                    }
                }

                layout.nodeLayouts.forEach { node ->
                    val isFocused = focusSet.contains(node.id)
                    val bg = if (isFocused) Color(0xFFDCFCE7) else Color(0xFFFFFFFF)
                    val border = if (isFocused) Color(0xFF0B8A5A) else Color(0xFF334155)

                    drawRoundRect(
                        color = bg,
                        topLeft = Offset(node.x, node.y),
                        size = Size(node.width, node.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f),
                    )
                    drawRoundRect(
                        color = border,
                        topLeft = Offset(node.x, node.y),
                        size = Size(node.width, node.height),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f, 14f),
                        style = Stroke(width = if (isFocused) 2.8f else 1.6f),
                    )

                    val label = snapshot.scene.nodes.firstOrNull { it.id == node.id }?.label ?: node.id
                    val lines = wrapLabel(label)
                    val lineHeight = 21f
                    val startY = node.y + (node.height / 2f) - ((lines.size - 1) * lineHeight / 2f) + 6f
                    lines.forEachIndexed { idx, line ->
                        drawContext.canvas.nativeCanvas.drawText(
                            line,
                            node.x + 12f,
                            startY + (idx * lineHeight),
                            Paint().apply {
                                isAntiAlias = true
                                textSize = 24f
                                color = android.graphics.Color.parseColor("#0F172A")
                            }
                        )
                    }
                }
            }
        }
    }
}

private fun wrapLabel(text: String, maxCharsPerLine: Int = 16, maxLines: Int = 3): List<String> {
    if (text.length <= maxCharsPerLine) return listOf(text)
    val words = text.split(Regex("\\s+"))
    if (words.size == 1) {
        return text.chunked(maxCharsPerLine).take(maxLines)
    }

    val lines = mutableListOf<String>()
    var current = ""
    for (word in words) {
        val candidate = if (current.isBlank()) word else "$current $word"
        if (candidate.length <= maxCharsPerLine) {
            current = candidate
        } else {
            if (current.isNotBlank()) lines += current
            current = word
            if (lines.size == maxLines - 1) break
        }
    }
    if (lines.size < maxLines && current.isNotBlank()) lines += current
    return lines.take(maxLines)
}

@Composable
fun StatusRow(label: String, done: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 14.sp)
        Text(
            text = if (done) "Done" else "Needed",
            fontSize = 14.sp,
            color = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
    }
}
