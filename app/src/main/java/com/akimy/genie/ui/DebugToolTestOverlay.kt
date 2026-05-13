package com.akimy.genie.ui

import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akimy.genie.tools.BoardObject
import com.akimy.genie.tools.PathParser
import com.akimy.genie.tools.VisualizerScene
import com.akimy.genie.tools.VisualizerSceneLayout
import com.akimy.genie.tools.VisualizerSceneStore
import com.akimy.genie.tools.visibleObjects
import java.util.Locale
import kotlin.math.*

private const val TAG = "GenieDebugTool"
private const val SCENE_ID = "debug_motion_101"
private const val VIRTUAL_W = 560f

private val CONCEPT_NODES_JSON = """[
{"id":"sun","label":"Sunlight","kind":"input"},
{"id":"leaf","label":"Leaf absorbs energy","kind":"process"},
{"id":"water","label":"Water + CO2 enter","kind":"input"},
{"id":"sugar","label":"Glucose is made","kind":"output"},
{"id":"oxygen","label":"Oxygen is released","kind":"output"}
]""".trimIndent()

private val CONCEPT_EDGES_JSON = """[
{"from":"sun","to":"leaf","label":"powers"},
{"from":"water","to":"leaf","label":"feeds"},
{"from":"leaf","to":"sugar","label":"creates"},
{"from":"leaf","to":"oxygen","label":"releases"}
]""".trimIndent()

// ── Test data — projectile motion lesson ──
private val OBJECTS_JSON = """[
{"objectId":"title_1","objectType":"title","text":"Projectile Motion","position":{"x":20,"y":15},"size":{"width":520,"height":44},"style":{"textColor":"#F8FAFC","textSize":28},"stepId":"step_1","animation":"slide_up"},
{"objectId":"given_box","objectType":"card","text":"Given: v₀ = 20 m/s, θ = 45°","position":{"x":20,"y":65},"size":{"width":250,"height":50},"style":{"fillColor":"#1E3A5F","strokeColor":"#38BDF8","textColor":"#B8D8F8","cornerRadius":10,"textSize":14,"strokeWidth":1},"stepId":"step_1","animation":"reveal"},
{"objectId":"find_box","objectType":"card","text":"Find: Range, Max Height, Time","position":{"x":285,"y":65},"size":{"width":250,"height":50},"style":{"fillColor":"#3B2F00","strokeColor":"#F59E0B","textColor":"#FDE68A","cornerRadius":10,"textSize":14,"strokeWidth":1},"stepId":"step_1","animation":"reveal"},
{"objectId":"x_axis","objectType":"path","pathData":"M 40 340 L 520 340","style":{"strokeColor":"#64748B","strokeWidth":2},"stepId":"step_2","animation":"draw","position":{"x":0,"y":0},"size":{"width":0,"height":0}},
{"objectId":"y_axis","objectType":"path","pathData":"M 40 340 L 40 120","style":{"strokeColor":"#64748B","strokeWidth":2},"stepId":"step_2","animation":"draw","position":{"x":0,"y":0},"size":{"width":0,"height":0}},
{"objectId":"x_label","objectType":"text","text":"x","position":{"x":525,"y":330},"size":{"width":20,"height":20},"style":{"textColor":"#64748B","textSize":14},"stepId":"step_2"},
{"objectId":"y_label","objectType":"text","text":"y","position":{"x":35,"y":105},"size":{"width":20,"height":20},"style":{"textColor":"#64748B","textSize":14},"stepId":"step_2"},
{"objectId":"trajectory","objectType":"path","pathData":"M 40 340 Q 280 80 520 340","style":{"strokeColor":"#38BDF8","strokeWidth":4},"stepId":"step_2","animation":"draw","position":{"x":0,"y":0},"size":{"width":0,"height":0}},
{"objectId":"launch_dot","objectType":"circle","text":"","position":{"x":32,"y":332},"size":{"width":16,"height":16},"style":{"fillColor":"#38BDF8"},"stepId":"step_2","animation":"pulse"},
{"objectId":"peak_dot","objectType":"circle","text":"","position":{"x":272,"y":115},"size":{"width":16,"height":16},"style":{"fillColor":"#F59E0B"},"stepId":"step_2","animation":"pulse"},
{"objectId":"land_dot","objectType":"circle","text":"","position":{"x":512,"y":332},"size":{"width":16,"height":16},"style":{"fillColor":"#38BDF8"},"stepId":"step_2","animation":"pulse"},
{"objectId":"lbl_launch","objectType":"text","text":"Launch","position":{"x":20,"y":350},"size":{"width":60,"height":20},"style":{"textColor":"#94A3B8","textSize":11},"stepId":"step_2"},
{"objectId":"lbl_peak","objectType":"text","text":"Peak","position":{"x":264,"y":100},"size":{"width":40,"height":20},"style":{"textColor":"#F59E0B","textSize":11},"stepId":"step_2"},
{"objectId":"lbl_land","objectType":"text","text":"Landing","position":{"x":498,"y":350},"size":{"width":60,"height":20},"style":{"textColor":"#94A3B8","textSize":11},"stepId":"step_2"},
{"objectId":"v0_arrow","objectType":"path","pathData":"M 44 336 L 120 260","style":{"strokeColor":"#6C63FF","strokeWidth":3},"stepId":"step_3","animation":"draw","position":{"x":0,"y":0},"size":{"width":0,"height":0}},
{"objectId":"v0_label","objectType":"text","text":"v₀","position":{"x":125,"y":248},"size":{"width":30,"height":20},"style":{"textColor":"#6C63FF","textSize":14},"stepId":"step_3","animation":"reveal"},
{"objectId":"theta_arc","objectType":"path","pathData":"M 80 340 A 36 36 0 0 1 64 310","style":{"strokeColor":"#F59E0B","strokeWidth":2,"dashed":true},"stepId":"step_3","animation":"draw","position":{"x":0,"y":0},"size":{"width":0,"height":0}},
{"objectId":"theta_label","objectType":"text","text":"θ","position":{"x":82,"y":312},"size":{"width":20,"height":20},"style":{"textColor":"#F59E0B","textSize":14},"stepId":"step_3","animation":"reveal"},
{"objectId":"eq_box","objectType":"card","text":"R = v₀²sin(2θ)/g\nH = v₀²sin²(θ)/2g\nT = 2v₀sin(θ)/g","position":{"x":340,"y":130},"size":{"width":200,"height":90},"style":{"fillColor":"#0F172A","strokeColor":"#334155","textColor":"#E2E8F0","cornerRadius":12,"textSize":13,"strokeWidth":1},"stepId":"step_4","animation":"slide_up"},
{"objectId":"solve_box","objectType":"card","text":"R = 40.8 m\nH = 10.2 m\nT = 2.88 s","position":{"x":340,"y":235},"size":{"width":200,"height":80},"style":{"fillColor":"#1C1917","strokeColor":"#F59E0B","textColor":"#FDE68A","cornerRadius":12,"textSize":14,"strokeWidth":2},"stepId":"step_5","animation":"slide_up"}
]""".trimIndent()

private val STEPS_JSON = """[
{"stepId":"step_1","title":"Problem Setup","narration":"Let's solve a projectile motion problem. We launch an object at 20 meters per second at a 45 degree angle."},
{"stepId":"step_2","title":"Trajectory","narration":"The object follows a parabolic path. It launches from the ground, reaches a peak, and lands back down."},
{"stepId":"step_3","title":"Vectors","narration":"The initial velocity v-zero points at angle theta from the ground. This creates horizontal and vertical components."},
{"stepId":"step_4","title":"Equations","narration":"Here are the key equations for range, maximum height, and total time of flight."},
{"stepId":"step_5","title":"Solution","narration":"Substituting our values: the range is 40.8 meters, maximum height is 10.2 meters, and total time is 2.88 seconds."}
]""".trimIndent()

private val Accent = Color(0xFF6C63FF)
private val AccentAlt = Color(0xFF0284C7)
private val Danger = Color(0xFFDC2626)
private val Green = Color(0xFF31E7B6)
private val BoardBg = Color(0xFF0F1117)
private val StripBg = Color(0xFF181A20)

// ═══════════════════════════════════════════
// Main Composable
// ═══════════════════════════════════════════

@Composable
fun DebugToolTestOverlay(onClose: () -> Unit = {}) {
    var refreshKey by remember { mutableIntStateOf(0) }
    var lastResult by remember { mutableStateOf("Ready") }
    var isError by remember { mutableStateOf(false) }
    var parentWidthPx by remember { mutableFloatStateOf(0f) }
    var demoObjectId by remember { mutableStateOf<String?>(null) }

    // TTS
    val context = LocalContext.current
    var ttsReady by remember { mutableStateOf(false) }
    val ttsRef = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        val t = TextToSpeech(context) { s -> if (s == TextToSpeech.SUCCESS) { ttsReady = true; ttsRef.value?.language = Locale.US } }
        ttsRef.value = t
        onDispose { t.shutdown() }
    }
    fun speak(text: String) { if (ttsReady && text.isNotBlank()) ttsRef.value?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "dtts") }

    fun exec(name: String, block: () -> String) {
        Log.d(TAG, "━━━ $name ━━━")
        val r = try { block().also { Log.d(TAG, "  → $it") } } catch (e: Exception) { Log.e(TAG, "ERR", e); "✗ ${e.message}" }
        isError = r.startsWith("✗"); lastResult = "[$name] $r"; refreshKey++
    }
    fun ok(r: com.akimy.genie.tools.SceneStoreResult) = if (r.ok) "✓ ${r.message}" else "✗ ${r.message}"

    val snapshot = remember(refreshKey) { VisualizerSceneStore.getSnapshot(SCENE_ID) }
    val scene = snapshot?.scene
    val layout = snapshot?.layout
    val board = scene?.board
    val objects = board?.visibleObjects() ?: emptyList()
    val focusObjectIds = board?.focusObjectIds?.toSet() ?: emptySet()
    val conceptProgress = remember { Animatable(1f) }

    LaunchedEffect(scene?.sceneId, scene?.updatedAt, scene?.diagramType) {
        if (scene != null && scene.board == null && layout?.nodeLayouts?.isNotEmpty() == true) {
            conceptProgress.snapTo(0f)
            conceptProgress.animateTo(1f, tween(durationMillis = 3200, easing = LinearEasing))
        } else {
            conceptProgress.snapTo(1f)
        }
    }

    // Track which objects are newly visible for animations
    var prevIds by remember { mutableStateOf(emptySet<String>()) }
    val newIds = remember(objects) { objects.map { it.objectId }.toSet() - prevIds }
    LaunchedEffect(objects) { prevIds = objects.map { it.objectId }.toSet() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ═══ TOP BAR ═══
        Row(
            modifier = Modifier.fillMaxWidth().background(StripBg).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { ttsRef.value?.stop(); onClose() }, modifier = Modifier.size(30.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Danger), contentPadding = PaddingValues(0.dp)) {
                Text("✕", color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("🧪 Board Debug", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (board != null) {
                Text("${board.currentStepId ?: "—"}", color = Green, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Text("${objects.size}/${board.objects.size}", color = Color(0xFF9CA3AF), fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                if (board.narrationText.isNotBlank()) Chip("🔊", Accent) { speak(board.narrationText) }
            }
        }

        // ═══ CANVAS ═══
        Box(modifier = Modifier.fillMaxWidth().weight(1f).background(BoardBg)
            .onGloballyPositioned { parentWidthPx = it.size.width.toFloat() }) {

            if (parentWidthPx > 0f && objects.isNotEmpty()) {
                val scale = parentWidthPx / VIRTUAL_W
                val maxBottom = objects.maxOf { maxObjBottom(it) }.coerceAtLeast(200f) + 40f
                val canvasHDp = with(LocalDensity.current) { (maxBottom * scale).toDp() }

                Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    // Animated objects
                    val animatedObjects = objects.map { obj ->
                        val isNew = obj.objectId in newIds
                        val animType = obj.animation ?: "reveal"
                        obj to AnimState(isNew, animType)
                    }
                    Canvas(modifier = Modifier.fillMaxWidth().height(canvasHDp)) {
                        drawGrid(scale)
                        animatedObjects.forEach { (obj, anim) -> drawObj(obj, scale, anim, obj.objectId in focusObjectIds) }
                    }
                    // Per-object animated overlays for stroke-draw animation
                    animatedObjects.filter { it.second.isNew && it.second.type == "draw" }.forEach { (obj, _) ->
                        AnimatedPathOverlay(obj, scale, Modifier.fillMaxWidth().height(canvasHDp))
                    }
                }
            } else if (parentWidthPx > 0f && scene != null && layout != null && layout.nodeLayouts.isNotEmpty()) {
                val virtualWidth = maxConceptRight(layout).coerceAtLeast(VIRTUAL_W)
                val virtualHeight = maxConceptBottom(layout).coerceAtLeast(260f)
                val scale = parentWidthPx / virtualWidth
                val canvasHDp = with(LocalDensity.current) { ((virtualHeight + 40f) * scale).toDp() }

                Box(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Canvas(modifier = Modifier.fillMaxWidth().height(canvasHDp)) {
                        drawConceptScene(scene, layout, scale, conceptProgress.value)
                    }
                }
            } else if (board == null) {
                Text("Tap \"Concept\" or \"Create\" below", color = Color(0xFF64748B), fontSize = 14.sp, modifier = Modifier.align(Alignment.Center))
            }

            Text(lastResult, color = if (isError) Color(0xFFF87171) else Green, fontSize = 10.sp, maxLines = 1,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0F1117)).padding(horizontal = 12.dp, vertical = 3.dp))
        }

        // ═══ BOTTOM STRIP ═══
        Row(modifier = Modifier.fillMaxWidth().background(StripBg).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("Concept", Accent) { exec("visualize_concept") {
                VisualizerSceneStore.clearScene(SCENE_ID)
                demoObjectId = null
                ok(VisualizerSceneStore.createScene(
                    sceneId = SCENE_ID,
                    diagramType = "flowchart",
                    title = "Photosynthesis Flow",
                    nodesJson = CONCEPT_NODES_JSON,
                    edgesJson = CONCEPT_EDGES_JSON,
                ))
            } }
            Chip("Create", Accent) { exec("create") {
                val result = VisualizerSceneStore.teachWithBoard(SCENE_ID, "Projectile Motion", "dark_classroom", OBJECTS_JSON, STEPS_JSON, "")
                if (result.ok) demoObjectId = null
                ok(result)
            } }
            Chip("Clear", Danger) { exec("clear") {
                val result = VisualizerSceneStore.clearScene(SCENE_ID)
                if (result.ok) demoObjectId = null
                ok(result)
            } }
            Sep()
            Chip("Add", Accent) { exec("add_object") {
                val newId = "debug_note_${System.currentTimeMillis() % 10000}"
                val y = objects.maxOfOrNull { maxObjBottom(it) }?.plus(20f) ?: 380f
                val result = VisualizerSceneStore.boardAddObject(
                    sceneId = SCENE_ID,
                    objectId = newId,
                    objectType = "card",
                    text = "Manual add_object demo",
                    x = 20f,
                    y = y,
                    width = 240f,
                    height = 56f,
                    styleJson = """{"fillColor":"#06281F","strokeColor":"#31E7B6","textColor":"#D1FAE5","cornerRadius":10,"textSize":13,"strokeWidth":2}""",
                    stepId = null,
                    animation = "reveal",
                )
                if (result.ok) demoObjectId = newId
                ok(result)
            } }
            Chip("Update", Accent) { exec("update_object") {
                ok(VisualizerSceneStore.boardUpdateObject(
                    sceneId = SCENE_ID,
                    objectId = "given_box",
                    objectType = null,
                    text = "Updated: v0 = 20 m/s, angle = 45 deg",
                    x = 20f,
                    y = 65f,
                    width = 270f,
                    height = 54f,
                    styleJson = """{"fillColor":"#102A43","strokeColor":"#31E7B6","textColor":"#D1FAE5","cornerRadius":10,"textSize":13,"strokeWidth":2}""",
                    stepId = null,
                    animation = "pulse",
                ))
            } }
            Chip("Remove", Danger) { exec("remove_object") {
                val id = demoObjectId
                if (id == null) {
                    "Tap Add first"
                } else {
                    val result = VisualizerSceneStore.boardRemoveObject(SCENE_ID, id)
                    if (result.ok) demoObjectId = null
                    ok(result)
                }
            } }
            Chip("Focus", AccentAlt) { exec("focus_object") { ok(VisualizerSceneStore.boardFocusObject(SCENE_ID, "peak_dot")) } }
            Chip("Narrate", Accent) { exec("set_narration") {
                val text = "Debug narration updated manually for the current teaching board state."
                val result = VisualizerSceneStore.boardSetNarration(SCENE_ID, text)
                if (result.ok) speak(text)
                ok(result)
            } }
            Chip("Replay", AccentAlt) { exec("replay") { val r = ok(VisualizerSceneStore.boardReplayStep(SCENE_ID)); speakStep(speak = ::speak); r } }
            Sep()
            Chip("S1", AccentAlt) { exec("s1") { val r = ok(VisualizerSceneStore.boardRevealStep(SCENE_ID, "step_1")); speakStep(speak = ::speak); r } }
            Chip("S2", AccentAlt) { exec("s2") { val r = ok(VisualizerSceneStore.boardRevealStep(SCENE_ID, "step_2")); speakStep(speak = ::speak); r } }
            Chip("S3", AccentAlt) { exec("s3") { val r = ok(VisualizerSceneStore.boardRevealStep(SCENE_ID, "step_3")); speakStep(speak = ::speak); r } }
            Chip("S4", AccentAlt) { exec("s4") { val r = ok(VisualizerSceneStore.boardRevealStep(SCENE_ID, "step_4")); speakStep(speak = ::speak); r } }
            Chip("S5", AccentAlt) { exec("s5") { val r = ok(VisualizerSceneStore.boardRevealStep(SCENE_ID, "step_5")); speakStep(speak = ::speak); r } }
            Sep()
            Chip("→", AccentAlt) { exec("next") { val r = ok(VisualizerSceneStore.boardNextStep(SCENE_ID)); speakStep(speak = ::speak); r } }
            Chip("←", AccentAlt) { exec("prev") { val r = ok(VisualizerSceneStore.boardPrevStep(SCENE_ID)); speakStep(speak = ::speak); r } }
            Sep()
            Chip("+Path", Accent) { exec("+path") {
                val y = objects.maxOfOrNull { maxObjBottom(it) }?.plus(20f) ?: 100f
                ok(VisualizerSceneStore.boardAddObject(SCENE_ID, "dyn_path_${System.currentTimeMillis()%10000}", "path", "", 0f, 0f, 0f, 0f, """{"strokeColor":"#31E7B6","strokeWidth":3}""", null, "draw", "M 40 ${y} Q 280 ${y-80} 520 ${y}"))
            }}
        }
    }
}

private fun speakStep(speak: (String) -> Unit) {
    val n = VisualizerSceneStore.getSnapshot(SCENE_ID)?.scene?.board?.narrationText
    if (!n.isNullOrBlank()) speak(n)
}

private fun maxObjBottom(obj: BoardObject): Float {
    if (obj.objectType == "path" && obj.pathData != null) {
        // Estimate max Y from path tokens
        val nums = obj.pathData.replace(Regex("[A-Za-z,]"), " ").trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
        // Take every other number as Y (rough estimate)
        return nums.filterIndexed { i, _ -> i % 2 == 1 }.maxOrNull() ?: 0f
    }
    return obj.position.y + obj.size.height
}

private fun maxConceptRight(layout: VisualizerSceneLayout): Float {
    return layout.nodeLayouts.maxOfOrNull { it.x + it.width }?.plus(56f) ?: VIRTUAL_W
}

private fun maxConceptBottom(layout: VisualizerSceneLayout): Float {
    return layout.nodeLayouts.maxOfOrNull { it.y + it.height }?.plus(56f) ?: 260f
}

private fun DrawScope.drawConceptScene(
    scene: VisualizerScene,
    layout: VisualizerSceneLayout,
    scale: Float,
    buildProgress: Float,
) {
    drawRect(Color(0xFFFCFDFE))
    val focusIds = scene.focusNodeIds.toSet()
    val orderedNodes = layout.nodeLayouts.sortedWith(compareBy({ it.x }, { it.y }))
    val nodeRevealById = orderedNodes.mapIndexed { index, node ->
        node.id to staggeredReveal(buildProgress, index * 2, orderedNodes.size * 2 + layout.edgeLayouts.size)
    }.toMap()

    layout.edgeLayouts.forEachIndexed { index, edge ->
        val edgeProgress = staggeredReveal(buildProgress, (index * 2) + 1, orderedNodes.size * 2 + layout.edgeLayouts.size)
        if (edgeProgress <= 0f) return@forEachIndexed

        val isFocused = edge.from in focusIds || edge.to in focusIds
        val color = (if (isFocused) Color(0xFF0B8A5A) else Color(0xFF334155)).copy(alpha = edgeProgress)
        val stroke = (if (isFocused) 3.1f else 2.0f) * scale
        val points = edge.points
        val segmentCount = points.lastIndex.coerceAtLeast(1)

        for (i in 0 until points.lastIndex) {
            val from = points[i]
            val to = points[i + 1]
            val segmentProgress = ((edgeProgress * segmentCount) - i).coerceIn(0f, 1f)
            if (segmentProgress <= 0f) continue
            val endX = from.x + ((to.x - from.x) * segmentProgress)
            val endY = from.y + ((to.y - from.y) * segmentProgress)
            drawLine(
                color = color,
                start = Offset(from.x * scale, from.y * scale),
                end = Offset(endX * scale, endY * scale),
                strokeWidth = stroke.coerceAtLeast(1.5f),
                pathEffect = PathEffect.cornerPathEffect(10f * scale),
            )
        }

        if (points.size >= 2 && edgeProgress >= 0.98f) {
            val tip = points.last()
            val arrow = 8f * scale
            val tipOffset = Offset(tip.x * scale, tip.y * scale)
            drawLine(color, tipOffset, Offset(tipOffset.x - arrow, tipOffset.y - arrow), stroke)
            drawLine(color, tipOffset, Offset(tipOffset.x - arrow, tipOffset.y + arrow), stroke)
        }
    }

    orderedNodes.forEach { node ->
        val nodeReveal = nodeRevealById[node.id] ?: 1f
        if (nodeReveal <= 0f) return@forEach

        val isFocused = node.id in focusIds
        val bg = (if (isFocused) Color(0xFFDCFCE7) else Color.White).copy(alpha = nodeReveal)
        val border = (if (isFocused) Color(0xFF0B8A5A) else Color(0xFF1E293B)).copy(alpha = nodeReveal)
        val popScale = 0.88f + (0.12f * nodeReveal)
        val fullWidth = node.width * scale
        val fullHeight = node.height * scale
        val size = Size(fullWidth * popScale, fullHeight * popScale)
        val topLeft = Offset(
            (node.x * scale) + ((fullWidth - size.width) / 2f),
            (node.y * scale) + ((fullHeight - size.height) / 2f),
        )

        drawRoundRect(
            color = bg,
            topLeft = topLeft,
            size = size,
            cornerRadius = CornerRadius(14f * scale, 14f * scale),
        )
        drawRoundRect(
            color = border,
            topLeft = topLeft,
            size = size,
            cornerRadius = CornerRadius(14f * scale, 14f * scale),
            style = Stroke(width = if (isFocused) 2.7f * scale else 1.5f * scale),
        )

        val label = scene.nodes.firstOrNull { it.id == node.id }?.label ?: node.id
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(
                (255 * nodeReveal).toInt().coerceIn(0, 255),
                15,
                23,
                42,
            )
            textSize = (22f * scale).coerceAtLeast(12f)
        }
        val lines = wrapText(label, paint, size.width - (24f * scale))
        val lineHeight = paint.textSize * 1.2f
        val startY = topLeft.y + (size.height / 2f) - ((lines.size - 1) * lineHeight / 2f) + (paint.textSize / 3f)
        lines.forEachIndexed { idx, line ->
            drawContext.canvas.nativeCanvas.drawText(
                line,
                topLeft.x + (12f * scale),
                startY + (idx * lineHeight),
                paint,
            )
        }
    }
}

private fun staggeredReveal(progress: Float, index: Int, count: Int): Float {
    val span = 0.28f
    val step = if (count <= 1) 0f else (1f - span) / (count - 1)
    val start = index * step
    val raw = ((progress - start) / span).coerceIn(0f, 1f)
    return raw * raw * (3f - (2f * raw))
}

// ═══════════════════════════════════════════
// Animation helpers
// ═══════════════════════════════════════════

private data class AnimState(val isNew: Boolean, val type: String)

/** Overlay composable that draws a path with stroke animation */
@Composable
private fun AnimatedPathOverlay(obj: BoardObject, scale: Float, modifier: Modifier) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(obj.objectId) { progress.animateTo(1f, tween(900, easing = EaseOutCubic)) }

    Canvas(modifier = modifier) {
        val androidPath = PathParser.parse(obj.pathData, scale) ?: return@Canvas
        val pm = android.graphics.PathMeasure(androidPath, false)
        val dst = android.graphics.Path()
        pm.getSegment(0f, pm.length * progress.value, dst, true)

        val strokeColor = hex(obj.style.strokeColor, Color(0xFF38BDF8))
        val sw = obj.style.strokeWidth * scale * 0.5f
        val pe = if (obj.style.dashed) PathEffect.dashPathEffect(floatArrayOf(8f * scale, 6f * scale)) else null
        drawPath(dst.asComposePath(), strokeColor, style = Stroke(sw.coerceAtLeast(1.5f), pathEffect = pe))
    }
}

// ═══════════════════════════════════════════
// Canvas drawing
// ═══════════════════════════════════════════

private fun DrawScope.drawGrid(scale: Float) {
    val step = 50f * scale; val c = Color(0xFF1A1D26)
    var y = step; while (y < size.height) { drawLine(c, Offset(0f, y), Offset(size.width, y)); y += step }
    var x = step; while (x < size.width) { drawLine(c, Offset(x, 0f), Offset(x, size.height)); x += step }
}

private fun DrawScope.drawObj(obj: BoardObject, s: Float, anim: AnimState, isFocused: Boolean = false) {
    val alpha = if (anim.isNew && anim.type == "reveal") {
        // Simple instant reveal for non-animated path (animated paths use overlay)
        1f
    } else 1f

    when (obj.objectType.lowercase()) {
        "path" -> {
            // Static paths (non-new or non-draw) drawn here; animated draw paths use overlay
            if (!(anim.isNew && anim.type == "draw")) {
                drawPathObj(obj, s)
            }
        }
        "box", "card" -> drawBoxObj(obj, s, alpha)
        "circle" -> drawCircleObj(obj, s, alpha)
        "arrow" -> drawArrowObj(obj, s)
        "line" -> drawLineObj(obj, s)
        "title", "text", "code" -> drawLabel(obj, obj.position.x * s, obj.position.y * s, obj.size.width * s, obj.size.height * s, s)
    }

    if (isFocused && obj.objectType.lowercase() != "path") {
        val x = obj.position.x * s
        val y = obj.position.y * s
        val w = obj.size.width.coerceAtLeast(24f) * s
        val h = obj.size.height.coerceAtLeast(24f) * s
        drawRoundRect(
            color = Color(0xFFFDE047),
            topLeft = Offset(x - 4f, y - 4f),
            size = Size(w + 8f, h + 8f),
            cornerRadius = CornerRadius(10f * s),
            style = Stroke(width = 2.5f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f * s, 7f * s))),
        )
    }
}

private fun DrawScope.drawPathObj(obj: BoardObject, s: Float) {
    val androidPath = PathParser.parse(obj.pathData, s) ?: return
    val strokeColor = hex(obj.style.strokeColor, Color(0xFF38BDF8))
    val sw = obj.style.strokeWidth * s * 0.5f
    val pe = if (obj.style.dashed) PathEffect.dashPathEffect(floatArrayOf(8f * s, 6f * s)) else null
    drawPath(androidPath.asComposePath(), strokeColor, style = Stroke(sw.coerceAtLeast(1.5f), pathEffect = pe))
}

private fun DrawScope.drawBoxObj(obj: BoardObject, s: Float, alpha: Float) {
    val x = obj.position.x * s; val y = obj.position.y * s; val w = obj.size.width * s; val h = obj.size.height * s
    val fill = hex(obj.style.fillColor, Color(0xFF1E293B)); val stroke = hex(obj.style.strokeColor, Color(0xFF38BDF8))
    val sw = obj.style.strokeWidth * s * 0.5f; val cr = obj.style.cornerRadius * s * 0.5f
    drawRoundRect(fill.copy(alpha = alpha), Offset(x, y), Size(w, h), CornerRadius(cr))
    drawRoundRect(stroke.copy(alpha = alpha), Offset(x, y), Size(w, h), CornerRadius(cr), style = Stroke(sw))
    drawLabel(obj, x + 8 * s, y, w - 16 * s, h, s)
}

private fun DrawScope.drawCircleObj(obj: BoardObject, s: Float, alpha: Float) {
    val x = obj.position.x * s; val y = obj.position.y * s; val w = obj.size.width * s; val h = obj.size.height * s
    val fill = hex(obj.style.fillColor, Color(0xFF6C63FF)); val stroke = hex(obj.style.strokeColor, Color.Transparent)
    val r = min(w, h) / 2f
    drawCircle(fill.copy(alpha = alpha), r, Offset(x + w / 2, y + h / 2))
    if (stroke != Color.Transparent) drawCircle(stroke, r, Offset(x + w / 2, y + h / 2), style = Stroke(obj.style.strokeWidth * s * 0.5f))
    if (obj.text.isNotBlank()) drawLabel(obj, x, y, w, h, s)
}

private fun DrawScope.drawArrowObj(obj: BoardObject, s: Float) {
    val x = obj.position.x * s; val y = obj.position.y * s; val ex = x + obj.size.width * s; val ey = y + obj.size.height * s
    val stroke = hex(obj.style.strokeColor, Color(0xFF38BDF8)); val sw = (obj.style.strokeWidth * s * 0.5f).coerceAtLeast(2f)
    drawLine(stroke, Offset(x, y), Offset(ex, ey), sw)
    val ang = atan2(ey - y, ex - x); val al = 10f * s * 0.5f
    drawLine(stroke, Offset(ex, ey), Offset(ex - al * cos(ang - 0.45f), ey - al * sin(ang - 0.45f)), sw)
    drawLine(stroke, Offset(ex, ey), Offset(ex - al * cos(ang + 0.45f), ey - al * sin(ang + 0.45f)), sw)
}

private fun DrawScope.drawLineObj(obj: BoardObject, s: Float) {
    val x = obj.position.x * s; val y = obj.position.y * s
    val stroke = hex(obj.style.strokeColor, Color(0xFF64748B)); val sw = (obj.style.strokeWidth * s * 0.5f).coerceAtLeast(1.5f)
    drawLine(stroke, Offset(x, y), Offset(x + obj.size.width * s, y + obj.size.height * s), sw)
}

private fun DrawScope.drawLabel(obj: BoardObject, x: Float, y: Float, w: Float, h: Float, s: Float) {
    if (obj.text.isBlank()) return
    val tsPx = obj.style.textSize * s * 0.55f
    val paint = android.graphics.Paint().apply {
        color = android.graphics.Color.parseColor(obj.style.textColor?.takeIf { it.isNotBlank() } ?: "#F8FAFC")
        textSize = tsPx; isAntiAlias = true
        if (obj.objectType == "code") typeface = android.graphics.Typeface.MONOSPACE
    }
    val lines = wrapText(obj.text, paint, w)
    val totalH = lines.size * tsPx * 1.3f
    val startY = y + (h - totalH) / 2f + tsPx
    lines.forEachIndexed { i, line ->
        val lx = if (obj.style.textAlign == "center") x + (w - paint.measureText(line)) / 2f else x
        drawContext.canvas.nativeCanvas.drawText(line, lx, startY + i * tsPx * 1.3f, paint)
    }
}

private fun wrapText(text: String, paint: android.graphics.Paint, maxW: Float): List<String> {
    val lines = mutableListOf<String>()
    text.split("\n").forEach { paragraph ->
        val words = paragraph.split(" ")
        var cur = ""
        words.forEach { w ->
            val test = if (cur.isEmpty()) w else "$cur $w"
            if (paint.measureText(test) > maxW && cur.isNotEmpty()) { lines.add(cur); cur = w } else cur = test
        }
        if (cur.isNotEmpty()) lines.add(cur)
    }
    return lines
}

private fun hex(hex: String?, default: Color): Color {
    if (hex.isNullOrBlank() || hex == "none") return default
    return try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { default }
}

// ═══════════════════════════════════════════
// UI Components
// ═══════════════════════════════════════════

@Composable private fun Chip(label: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(label, fontSize = 12.sp, color = Color.White)
    }
}

@Composable private fun Sep() { Spacer(Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155))) }
