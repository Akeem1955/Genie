package com.akimy.genie

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akimy.genie.tools.SceneSnapshot
import com.akimy.genie.tools.VisualizerExportManager
import com.akimy.genie.tools.VisualizerSceneMeta
import com.akimy.genie.tools.VisualizerSceneStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VisualizerCanvasActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VisualizerSceneStore.initialize(applicationContext)
        setContent {
            MaterialTheme {
                VisualizerCanvasScreen(initialSceneId = intent.getStringExtra(EXTRA_SCENE_ID))
            }
        }
    }

    companion object {
        const val EXTRA_SCENE_ID = "extra_scene_id"
    }
}

@Composable
private fun VisualizerCanvasScreen(initialSceneId: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableLongStateOf(0L) }
    var sceneId by remember { mutableStateOf(initialSceneId) }
    var renameDialogOpen by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            refreshTick = System.currentTimeMillis()
        }
    }

    val snapshot = remember(refreshTick, sceneId) {
        if (sceneId.isNullOrBlank()) {
            VisualizerSceneStore.getLatestSnapshot()
        } else {
            VisualizerSceneStore.getSnapshot(sceneId!!)
        }
    }
    val sceneList = remember(refreshTick) { VisualizerSceneStore.listScenes() }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "Canvas Visualizer",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
            )

            if (snapshot == null) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Text(
                        text = "No scene available yet. Ask Genie to visualize a concept, then reopen this screen.",
                        modifier = Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            sceneId = snapshot.scene.sceneId

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = snapshot.scene.title.ifBlank { "Untitled" },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { refreshTick = System.currentTimeMillis() }) {
                        Text("Refresh")
                    }
                    TextButton(
                        onClick = {
                            renameText = snapshot.scene.title
                            renameDialogOpen = true
                        }
                    ) {
                        Text("Rename")
                    }
                    TextButton(
                        onClick = {
                            val result = VisualizerSceneStore.clearScene(snapshot.scene.sceneId)
                            Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                            sceneId = VisualizerSceneStore.getLatestSnapshot()?.scene?.sceneId
                            refreshTick = System.currentTimeMillis()
                        }
                    ) {
                        Text("Delete")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val uri = VisualizerExportManager.exportSceneAsPng(context, snapshot)
                                val text = if (uri != null) "PNG exported" else "PNG export failed"
                                Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        Text("Export PNG")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val uri = VisualizerExportManager.exportSceneAsPng(context, snapshot)
                                if (uri != null) {
                                    sharePng(context, uri)
                                } else {
                                    Toast.makeText(context, "Share failed: could not export PNG", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        Text("Share")
                    }
                }
            }

            if (snapshot.scene.board != null) {
                TeachingBoardStatusCard(
                    snapshot = snapshot,
                    onRefresh = {
                        refreshTick = System.currentTimeMillis()
                    },
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = if (snapshot.scene.board != null) {
                            "Pinch to zoom. Drag to pan. Step highlights and narration stay synced with the board scene."
                        } else {
                            "Pinch to zoom. Drag to pan. Double-check focus highlights in green."
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ZoomableVisualizerCanvas(snapshot = snapshot)
                }
            }

            SceneHistoryCard(
                scenes = sceneList,
                activeSceneId = snapshot.scene.sceneId,
                onSelect = {
                    sceneId = it
                    refreshTick = System.currentTimeMillis()
                },
            )
        }

        if (renameDialogOpen) {
            AlertDialog(
                onDismissRequest = { renameDialogOpen = false },
                title = { Text("Rename Scene") },
                text = {
                    OutlinedTextField(
                        value = renameText,
                        onValueChange = { renameText = it },
                        singleLine = true,
                        label = { Text("Title") },
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val result = VisualizerSceneStore.renameScene(snapshot?.scene?.sceneId ?:"Ooops Error Happened" , renameText)
                        Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
                        renameDialogOpen = false
                        refreshTick = System.currentTimeMillis()
                    }) {
                        Text("Save")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { renameDialogOpen = false }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

@Composable
private fun TeachingBoardStatusCard(
    snapshot: SceneSnapshot,
    onRefresh: () -> Unit,
) {
    val board = snapshot.scene.board ?: return
    val stepLabel = boardCurrentStepLabel(snapshot.scene) ?: "No step selected yet"
    val narration = boardNarration(snapshot.scene) ?: "No narration set yet."

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Teaching Board", fontWeight = FontWeight.SemiBold)
            Text(
                text = stepLabel,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = narration,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = board.steps.isNotEmpty(),
                    onClick = {
                        VisualizerSceneStore.boardPrevStep(snapshot.scene.sceneId)
                        onRefresh()
                    },
                ) {
                    Text("Prev Step")
                }
                Button(
                    enabled = board.steps.isNotEmpty(),
                    onClick = {
                        VisualizerSceneStore.boardNextStep(snapshot.scene.sceneId)
                        onRefresh()
                    },
                ) {
                    Text("Next Step")
                }
                TextButton(
                    enabled = board.steps.isNotEmpty(),
                    onClick = {
                        VisualizerSceneStore.boardReplayStep(snapshot.scene.sceneId)
                        onRefresh()
                    },
                ) {
                    Text("Replay")
                }
            }
        }
    }
}

@Composable
private fun SceneHistoryCard(
    scenes: List<VisualizerSceneMeta>,
    activeSceneId: String,
    onSelect: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Recent Scenes", fontWeight = FontWeight.SemiBold)
            if (scenes.isEmpty()) {
                Text("No scenes yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            scenes.take(8).forEach { meta ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (meta.title.isBlank()) "Untitled" else meta.title,
                            fontWeight = if (meta.sceneId == activeSceneId) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${meta.diagramType} • ${meta.sceneId}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onSelect(meta.sceneId) }) {
                        Text(if (meta.sceneId == activeSceneId) "Viewing" else "Open")
                    }
                }
                Divider()
            }
        }
    }
}

@Composable
private fun ZoomableVisualizerCanvas(snapshot: SceneSnapshot) {
    val density = LocalDensity.current
    val isBoard = snapshot.scene.board != null
    val layout = snapshot.layout
    val focusSet = remember(snapshot.scene.focusNodeIds) { snapshot.scene.focusNodeIds.toSet() }

    val viewport = if (isBoard) boardViewport(snapshot.scene) else null
    val maxNodeX = layout.nodeLayouts.maxOfOrNull { it.x + it.width } ?: 600f
    val maxNodeY = layout.nodeLayouts.maxOfOrNull { it.y + it.height } ?: 360f
    val canvasWidthPx = viewport?.widthPx ?: (maxNodeX + 120f)
    val canvasHeightPx = viewport?.heightPx ?: (maxNodeY + 120f)
    val canvasWidthDp = with(density) { canvasWidthPx.toDp() }
    val canvasHeightDp = with(density) { canvasHeightPx.toDp() }

    var scale by remember { mutableFloatStateOf(1f) }
    var translateX by remember { mutableFloatStateOf(0f) }
    var translateY by remember { mutableFloatStateOf(0f) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scale = 1f
                translateX = 0f
                translateY = 0f
            }) {
                Text("Reset View")
            }
            Text(
                text = "Zoom ${(scale * 100).toInt()}%",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(520.dp)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .background(
                    if (isBoard) boardSurfaceBackground(snapshot.scene.board?.theme) else Color(0xFFF2F6FA),
                    RoundedCornerShape(12.dp),
                )
                .pointerInput(snapshot.scene.sceneId) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(0.6f, 2.8f)
                        translateX += pan.x
                        translateY += pan.y
                    }
                },
        ) {
            Canvas(
                modifier = Modifier
                    .size(canvasWidthDp, canvasHeightDp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = translateX
                        translationY = translateY
                    }
                    .align(Alignment.TopStart)
                    .background(
                        if (isBoard) boardCanvasBackground(snapshot.scene.board?.theme) else Color(0xFFFCFDFE),
                        RoundedCornerShape(10.dp),
                    ),
            ) {
                if (isBoard) {
                    drawTeachingBoard(snapshot.scene)
                } else {
                    layout.edgeLayouts.forEach { edge ->
                        val isFocused = focusSet.contains(edge.from) || focusSet.contains(edge.to)
                        val color = if (isFocused) Color(0xFF0B8A5A) else Color(0xFF334155)
                        val stroke = if (isFocused) 3.1f else 2.0f

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
                            val tip = points.last()
                            val arrow = 8f
                            drawLine(color, Offset(tip.x, tip.y), Offset(tip.x - arrow, tip.y - arrow), stroke)
                            drawLine(color, Offset(tip.x, tip.y), Offset(tip.x - arrow, tip.y + arrow), stroke)
                        }
                    }

                    layout.nodeLayouts.forEach { node ->
                        val isFocused = focusSet.contains(node.id)
                        val bg = if (isFocused) Color(0xFFDCFCE7) else Color(0xFFFFFFFF)
                        val border = if (isFocused) Color(0xFF0B8A5A) else Color(0xFF1E293B)

                        drawRoundRect(
                            color = bg,
                            topLeft = Offset(node.x, node.y),
                            size = Size(node.width, node.height),
                            cornerRadius = CornerRadius(14f, 14f),
                        )
                        drawRoundRect(
                            color = border,
                            topLeft = Offset(node.x, node.y),
                            size = Size(node.width, node.height),
                            cornerRadius = CornerRadius(14f, 14f),
                            style = Stroke(width = if (isFocused) 2.7f else 1.5f),
                        )

                        val label = snapshot.scene.nodes.firstOrNull { it.id == node.id }?.label ?: node.id
                        val lines = VisualizerExportManager.wrapLabelForNode(label)
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
}

private fun sharePng(context: Context, uri: Uri) {
    val intent = VisualizerExportManager.buildSharePngIntent(uri)
    context.startActivity(Intent.createChooser(intent, "Share visualizer image"))
}
