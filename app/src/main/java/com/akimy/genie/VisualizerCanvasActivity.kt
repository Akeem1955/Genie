package com.akimy.genie

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.akimy.genie.service.GenieAccessibilityService
import com.akimy.genie.tools.VisualizerExportManager
import com.akimy.genie.tools.VisualizerSceneMeta
import com.akimy.genie.tools.VisualizerSceneStore
import com.akimy.genie.tools.currentStepIndex
import com.akimy.genie.tools.visibleObjects
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

private val CanvasDarkScheme = darkColorScheme(
    primary = Color(0xFF6D4DBA),
    secondary = Color(0xFF38BDF8),
    tertiary = Color(0xFF31E7B6),
    background = Color(0xFF0F1117),
    surface = Color(0xFF181A20),
    surfaceVariant = Color(0xFF23262F),
    onBackground = Color(0xFFF1F1F4),
    onSurface = Color(0xFFE4E4E9),
    onSurfaceVariant = Color(0xFF9CA3AF),
    error = Color(0xFFF87171),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
)

private val CanvasLightScheme = lightColorScheme(
    primary = Color(0xFF6D4DBA),
    secondary = Color(0xFF0284C7),
    tertiary = Color(0xFF059669),
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF1E293B),
    onSurfaceVariant = Color(0xFF64748B),
    error = Color(0xFFDC2626),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
)

class VisualizerCanvasActivity : ComponentActivity() {

    /**
     * Holds a scene ID delivered by onNewIntent (FLAG_ACTIVITY_SINGLE_TOP re-delivery).
     * Compose observes this directly — no channel or flow needed.
     */
    private var overrideSceneId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VisualizerSceneStore.initialize(applicationContext)
        setContent {
            val isDark = isSystemInDarkTheme()
            val colorScheme = if (isDark) CanvasDarkScheme else CanvasLightScheme

            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                )
            }

            MaterialTheme(colorScheme = colorScheme) {
                UnifiedVisualizerScreen(
                    initialSceneId = intent.getStringExtra(EXTRA_SCENE_ID),
                    overrideSceneId = overrideSceneId,
                )
            }
        }
    }

    /**
     * Called instead of onCreate when the activity is already at the top of the stack
     * and a new Intent is sent with FLAG_ACTIVITY_SINGLE_TOP.
     *
     * The orchestrator uses this path when it fires openTeachingBoardScene() for a
     * visualize_concept scene (e.g., teaching_session_concept) while the board is
     * already showing teaching_session.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newSceneId = intent.getStringExtra(EXTRA_SCENE_ID)
        if (newSceneId != null) {
            overrideSceneId = newSceneId
        }
    }

    companion object {
        const val EXTRA_SCENE_ID = "extra_scene_id"
    }
}

@Composable
private fun UnifiedVisualizerScreen(initialSceneId: String?, overrideSceneId: String? = null) {
    val context = LocalContext.current
    var refreshTick by remember { mutableLongStateOf(0L) }
    var sceneId by remember { mutableStateOf(initialSceneId) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1200)
            refreshTick = System.currentTimeMillis()
        }
    }

    LaunchedEffect(overrideSceneId) {
        if (overrideSceneId != null) {
            sceneId = overrideSceneId
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

    if (snapshot == null) {
        Surface(modifier = Modifier.fillMaxSize().systemBarsPadding(), color = MaterialTheme.colorScheme.background) {
            Box(contentAlignment = Alignment.Center) {
                Text("No scene available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    sceneId = snapshot.scene.sceneId

    UnifiedTeachingScreen(
        snapshot = snapshot,
        onRefresh = { refreshTick = System.currentTimeMillis() },
        onClose = { (context as? ComponentActivity)?.finish() }
    )
}

@Composable
private fun UnifiedTeachingScreen(
    snapshot: SceneSnapshot,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isBoard = snapshot.scene.board != null
    val board = snapshot.scene.board
    val stepLabel = if (isBoard) boardCurrentStepLabel(snapshot.scene) ?: "Teaching board" else "Concept Diagram"
    val narration = if (isBoard) boardNarration(snapshot.scene).orEmpty() else ""
    
    var ttsReady by remember { mutableStateOf(false) }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }

    DisposableEffect(context) {
        val tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                ttsRef.value?.language = Locale.US
            }
        }
        ttsRef.value = tts
        onDispose {
            tts.stop()
            tts.shutdown()
        }
    }

    fun speak(text: String = narration) {
        if (ttsReady && text.isNotBlank()) {
            ttsRef.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "teaching_board")
        }
    }

    LaunchedEffect(snapshot.scene.updatedAt, narration, ttsReady) {
        if (narration.isNotBlank()) speak(narration)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = {
                        ttsRef.value?.stop()
                        onClose()
                    },
                    modifier = Modifier.size(42.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("X", color = Color.White, fontSize = 18.sp)
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = snapshot.scene.title.ifBlank { "Teaching Session" },
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stepLabel,
                        color = MaterialTheme.colorScheme.tertiary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (narration.isNotBlank()) {
                        TextButton(onClick = { speak() }) {
                            Text("Speak", color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val uri = VisualizerExportManager.exportSceneAsPng(context, snapshot)
                            val text = if (uri != null) "PNG exported" else "PNG export failed"
                            Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Text("Export", color = MaterialTheme.colorScheme.secondary)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            val uri = VisualizerExportManager.exportSceneAsPng(context, snapshot)
                            if (uri != null) {
                                sharePng(context, uri)
                            } else {
                                Toast.makeText(context, "Share failed: could not export PNG", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Text("Share", color = MaterialTheme.colorScheme.secondary)
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(if (isBoard) boardSurfaceBackground(board?.theme) else Color(0xFFF2F6FA)),
            ) {
                ZoomableVisualizerCanvas(snapshot = snapshot)
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (narration.isNotBlank()) {
                    Text(
                        text = narration,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (isBoard && board != null) {
                        Button(
                            enabled = board.steps.isNotEmpty(),
                            onClick = {
                                VisualizerSceneStore.boardPrevStep(snapshot.scene.sceneId)
                                onRefresh()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Text("Back", color = MaterialTheme.colorScheme.onSurface)
                        }
                        Button(
                            enabled = board.steps.isNotEmpty(),
                            onClick = {
                                val isAtLastStep = board.currentStepIndex() >= board.steps.lastIndex
                                if (isAtLastStep) {
                                    val requested = GenieAccessibilityService.requestTeachingCommand("next")
                                    if (!requested) {
                                        VisualizerSceneStore.boardNextStep(snapshot.scene.sceneId)
                                        onRefresh()
                                    } else {
                                        Toast.makeText(context, "Asking Genie for the next step", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    VisualizerSceneStore.boardNextStep(snapshot.scene.sceneId)
                                    onRefresh()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            val isAtLastStep = board.currentStepIndex() >= board.steps.lastIndex
                            Text(if (isAtLastStep) "Generate Next" else "Next", color = Color.White)
                        }
                        TextButton(
                            enabled = board.steps.isNotEmpty(),
                            onClick = {
                                VisualizerSceneStore.boardReplayStep(snapshot.scene.sceneId)
                                onRefresh()
                            },
                        ) {
                            Text("Replay", color = MaterialTheme.colorScheme.tertiary)
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${board.visibleObjects().size}/${board.objects.size}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                        )
                    } else {
                        Button(
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                val requested = GenieAccessibilityService.requestTeachingCommand("next")
                                if (requested) {
                                    Toast.makeText(context, "Asking Genie for the next step", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            Text("Generate Next", color = Color.White)
                        }
                    }
                }
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

    Box(
        modifier = Modifier
            .fillMaxSize()
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
                .align(Alignment.TopStart),
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
                                textAlign = Paint.Align.CENTER
                            }
                        )
                    }
                }
            }
        }

        // Floating controls
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(50))
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = {
                    scale = 1f
                    translateX = 0f
                    translateY = 0f
                },
                contentPadding = PaddingValues(0.dp)
            ) {
                Text("Reset View")
            }
            Text(
                text = "${(scale * 100).toInt()}%",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun sharePng(context: Context, uri: Uri) {
    val intent = VisualizerExportManager.buildSharePngIntent(uri)
    context.startActivity(Intent.createChooser(intent, "Share visualizer image"))
}
