package com.akimy.genie.ui

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akimy.genie.service.AnnotationOverlayController
import com.akimy.genie.tools.BoardStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

private const val TAG = "GenieDebugAnnotation"
private const val SESSION = "debug_session"

private val Accent = Color(0xFF38BDF8)
private val AccentAlt = Color(0xFF6C63FF)
private val Danger = Color(0xFFDC2626)
private val Green = Color(0xFF31E7B6)
private val StripBg = Color(0xFF181A20)

/**
 * Debug panel for Annotation profile tools.
 * Draws boxes, labels, and pointers on the real screen
 * via [AnnotationOverlayController].
 */
@Composable
fun DebugAnnotationPanel(
    controller: AnnotationOverlayController,
    onClose: () -> Unit = {},
) {
    var lastResult by remember { mutableStateOf("Ready — annotations draw on real screen behind this panel") }
    var isError by remember { mutableStateOf(false) }
    var opCounter by remember { mutableIntStateOf(0) }

    val scope = remember { CoroutineScope(Dispatchers.Main) }

    fun exec(name: String, block: () -> String) {
        Log.d(TAG, "━━━ $name ━━━")
        val r = try { block().also { Log.d(TAG, "  → $it") } } catch (e: Exception) { Log.e(TAG, "ERR", e); "✗ ${e.message}" }
        isError = r.startsWith("✗"); lastResult = "[$name] $r"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // ═══ TOP BAR ═══
        Row(
            modifier = Modifier.fillMaxWidth().background(StripBg).padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = { controller.detach(); onClose() }, modifier = Modifier.size(30.dp), shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = Danger), contentPadding = PaddingValues(0.dp)) {
                Text("✕", color = Color.White, fontSize = 13.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text("🎯 Annotation Debug", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("Ops: $opCounter", color = Color(0xFF9CA3AF), fontSize = 11.sp)
        }

        // ═══ INFO AREA ═══
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).background(Color(0x330F1117)),
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Annotations draw on the real screen", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("This panel is semi-transparent so you can see them underneath.", color = Color(0xFF9CA3AF), fontSize = 13.sp)
                Spacer(Modifier.height(16.dp))
                Text("Tap buttons below to draw boxes, labels, and pointers.", color = Color(0xFF64748B), fontSize = 12.sp)
            }

            // Status
            Text(lastResult, color = if (isError) Color(0xFFF87171) else Green, fontSize = 10.sp, maxLines = 2,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC0F1117)).padding(horizontal = 12.dp, vertical = 3.dp))
        }

        // ═══ BOTTOM STRIP ═══
        Row(modifier = Modifier.fillMaxWidth().background(StripBg).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {

            Chip("Start", Accent) {
                exec("start_session") {
                    controller.startSession(SESSION)
                    opCounter = 0
                    "✓ Session started"
                }
            }

            Sep()

            Chip("+Box 1", AccentAlt) {
                exec("add_box") {
                    opCounter++
                    controller.addBox(SESSION, "box_$opCounter", 0L, 100f, 300f, 600f, 200f, "Settings Area",
                        BoardStyle(strokeColor = "#38BDF8", fillColor = "#1E293B", textColor = "#FFFFFF", strokeWidth = 3f, alpha = 0.3f))
                    "✓ Box drawn at (100,300) 600×200"
                }
            }

            Chip("+Box 2", AccentAlt) {
                exec("add_box") {
                    opCounter++
                    controller.addBox(SESSION, "box_$opCounter", 0L, 200f, 600f, 500f, 150f, "Content Section",
                        BoardStyle(strokeColor = "#F59E0B", fillColor = "#3B2F00", textColor = "#FDE68A", strokeWidth = 3f, alpha = 0.3f))
                    "✓ Box drawn at (200,600) 500×150"
                }
            }

            Chip("+Label", Accent) {
                exec("add_label") {
                    opCounter++
                    controller.addLabel(SESSION, "lbl_$opCounter", 0L, 150f, 250f, "← Tap here to open settings",
                        BoardStyle(textColor = "#31E7B6", textSize = 28f))
                    "✓ Label at (150,250)"
                }
            }

            Chip("+Label 2", Accent) {
                exec("add_label") {
                    opCounter++
                    controller.addLabel(SESSION, "lbl_$opCounter", 0L, 300f, 550f, "Important: Read this section",
                        BoardStyle(textColor = "#F87171", textSize = 32f))
                    "✓ Label at (300,550)"
                }
            }

            Sep()

            Chip("+Pointer", AccentAlt) {
                exec("add_pointer") {
                    opCounter++
                    controller.addPointer(SESSION, "ptr_$opCounter", 0L, 100f, 150f, 400f, 350f, "Click here",
                        BoardStyle(strokeColor = "#6C63FF", textColor = "#FFFFFF", strokeWidth = 3f))
                    "✓ Pointer (100,150)→(400,350)"
                }
            }

            Chip("+Pointer 2", AccentAlt) {
                exec("add_pointer") {
                    opCounter++
                    controller.addPointer(SESSION, "ptr_$opCounter", 0L, 800f, 200f, 500f, 500f, "Scroll down",
                        BoardStyle(strokeColor = "#F59E0B", textColor = "#FDE68A", strokeWidth = 3f))
                    "✓ Pointer (800,200)→(500,500)"
                }
            }

            Sep()

            Chip("Replay", Accent) {
                exec("replay") {
                    controller.replaySession(SESSION, scope)
                    "✓ Replaying session"
                }
            }

            Chip("Clear", Danger) {
                exec("clear") {
                    controller.clearSession(SESSION)
                    opCounter = 0
                    "✓ Cleared"
                }
            }
        }
    }
}

@Composable private fun Chip(label: String, color: Color, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.height(34.dp), shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color), contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
        Text(label, fontSize = 12.sp, color = Color.White)
    }
}
@Composable private fun Sep() { Spacer(Modifier.width(1.dp).height(22.dp).background(Color(0xFF334155))) }
