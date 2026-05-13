package com.akimy.genie.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akimy.genie.tools.*
import kotlin.math.roundToInt

private val Accent = Color(0xFF10B981)
private val AccentBlue = Color(0xFF3B82F6)
private val Danger = Color(0xFFEF4444)
private val Surface = Color(0xFF1F2937)
private val TextPrimary = Color(0xFFF9FAFB)
private val TextSecondary = Color(0xFF9CA3AF)
private val StripBg = Color(0xFF111827)
private val Purple = Color(0xFF8B5CF6)
private val Cyan = Color(0xFF06B6D4)
private val Pink = Color(0xFFEC4899)
private val Orange = Color(0xFFF97316)

sealed class HealthUIState {
    object Idle : HealthUIState()
    data class Processing(val message: String) : HealthUIState()
    data class ShowingFoodAnalysis(val analysis: FoodNutritionAnalysis) : HealthUIState()
    data class ShowingHealthTopic(val record: HealthRecord) : HealthUIState()
    data class Error(val message: String) : HealthUIState()
}

@Composable
fun HealthOverlay(
    onAnalyzeFoodFromScreen: () -> Unit,
    onRequestGalleryPick: () -> Unit,
    onSearchHealthTopic: (String) -> Unit,
    onStartVoiceSearch: () -> Unit,
    onToggleFullscreen: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var uiState by remember { mutableStateOf<HealthUIState>(HealthUIState.Idle) }
    var showResults by remember { mutableStateOf(false) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showTextInput by remember { mutableStateOf(false) }
    var textQuery by remember { mutableStateOf("") }

    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        HealthSessionStore.resultFlow.collect { result ->
            uiState = when (result) {
                is HealthResult.FoodAnalysis -> HealthUIState.ShowingFoodAnalysis(result.analysis)
                is HealthResult.HealthTopic -> HealthUIState.ShowingHealthTopic(result.record)
                is HealthResult.Error -> HealthUIState.Error(result.message)
            }
            showResults = true
            showTextInput = false
            onToggleFullscreen(true)
        }
    }

    if (showResults && uiState !is HealthUIState.Idle && uiState !is HealthUIState.Processing) {
        // Full-screen results panel (only for actual results, not processing)
        Box(modifier = Modifier.fillMaxSize()) {
            ResultsPanel(
                state = uiState,
                onDismiss = {
                    uiState = HealthUIState.Idle
                    showResults = false
                    onToggleFullscreen(false)
                }
            )
        }
    } else {
        // Compact menu strip only
        Row(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Draggable vertical action strip (collapsible)
            Column(
                modifier = Modifier
                    .background(StripBg, RoundedCornerShape(20.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            offsetX += dragAmount.x
                            offsetY += dragAmount.y
                        }
                    }
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Main toggle button
                Pill("💊", Accent) { menuExpanded = !menuExpanded }

                // Expanded menu
                AnimatedVisibility(visible = menuExpanded) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Analyze food on screen
                        Pill("📷", Accent) {
                            uiState = HealthUIState.Processing("Analyzing screen...")
                            showResults = true
                            onToggleFullscreen(true)
                            onAnalyzeFoodFromScreen()
                        }

                        // Pick image from gallery
                        Pill("🖼", Accent) {
                            onRequestGalleryPick()
                        }

                        // Voice search for health topic
                        Pill("🎤", AccentBlue) {
                            onStartVoiceSearch()
                        }

                        // Text search for health topic
                        Pill("⌨", AccentBlue) {
                            showTextInput = !showTextInput
                        }

                        // Close
                        Pill("✕", Danger) { onClose() }
                    }
                }
            }

            // Text input panel
            AnimatedVisibility(visible = showTextInput) {
                Row(
                    modifier = Modifier
                        .widthIn(max = 250.dp)
                        .background(Surface, RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = textQuery,
                        onValueChange = { textQuery = it },
                        textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                        cursorBrush = SolidColor(Accent),
                        singleLine = true,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        decorationBox = { inner ->
                            if (textQuery.isEmpty()) {
                                Text("Health topic...", color = TextSecondary, fontSize = 13.sp)
                            }
                            inner()
                        }
                    )
                    Button(
                        onClick = {
                            if (textQuery.isNotBlank()) {
                                uiState = HealthUIState.Processing("Searching...")
                                showResults = true
                                onToggleFullscreen(true)
                                onSearchHealthTopic(textQuery.trim())
                                textQuery = ""
                                showTextInput = false
                            }
                        },
                        modifier = Modifier.height(32.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        enabled = textQuery.isNotBlank(),
                    ) {
                        Text("Go", fontSize = 11.sp, color = Color.White)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Results Panel
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ResultsPanel(state: HealthUIState, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Surface)
            .padding(16.dp)
    ) {
        // Close button at top
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            Pill("✕", Danger) { onDismiss() }
        }

        Spacer(Modifier.height(8.dp))

        when (state) {
            is HealthUIState.Processing -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(state.message, color = TextSecondary, fontSize = 16.sp)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(
                        modifier = Modifier.width(200.dp),
                        color = Accent
                    )
                }
            }

            is HealthUIState.ShowingFoodAnalysis -> {
                FoodAnalysisContent(state.analysis, onDismiss)
            }

            is HealthUIState.ShowingHealthTopic -> {
                HealthTopicContent(state.record, onDismiss)
            }

            is HealthUIState.Error -> {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("⚠ Error", color = Danger, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(state.message, color = TextSecondary, fontSize = 14.sp)
                }
            }

            else -> {}
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Food Analysis Result
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FoodAnalysisContent(analysis: FoodNutritionAnalysis, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header
        Text(analysis.foodName, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "${analysis.totalCalories} kcal • ${analysis.servingSize}",
            color = Accent, fontSize = 16.sp, fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(12.dp))

        // Macronutrients
        if (analysis.macronutrients.isNotEmpty()) {
            NutrientSection("Macronutrients", Purple, analysis.macronutrients)
        }
        // Vitamins
        if (analysis.vitamins.isNotEmpty()) {
            NutrientSection("Vitamins", Cyan, analysis.vitamins)
        }
        // Minerals
        if (analysis.minerals.isNotEmpty()) {
            NutrientSection("Minerals", Pink, analysis.minerals)
        }
        // Other
        if (analysis.otherNutrients.isNotEmpty()) {
            NutrientSection("Other", Orange, analysis.otherNutrients)
        }

        // Coverage
        Spacer(Modifier.height(12.dp))
        Text("Coverage", color = Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        if (analysis.nutritionCoverage.covered.isNotEmpty()) {
            Text(
                "✓ ${analysis.nutritionCoverage.covered.joinToString(", ")}",
                color = Accent, fontSize = 11.sp
            )
        }
        if (analysis.nutritionCoverage.missing.isNotEmpty()) {
            Text(
                "✗ ${analysis.nutritionCoverage.missing.joinToString(", ")}",
                color = Orange, fontSize = 11.sp
            )
        }
        if (analysis.nutritionCoverage.summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(analysis.nutritionCoverage.summary, color = TextSecondary, fontSize = 11.sp)
        }
    }
}

@Composable
private fun NutrientSection(title: String, color: Color, nutrients: List<NutrientInfo>) {
    Text(title, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    nutrients.forEach { nutrient ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "${nutrient.name}: ${nutrient.amount}${nutrient.unit}",
                color = TextPrimary, fontSize = 11.sp,
                modifier = Modifier.weight(1f)
            )
            val dv = nutrient.dailyValuePercent
            if (dv != null && dv > 0) {
                Text("${dv}%", color = TextSecondary, fontSize = 11.sp)
            }
        }
        if (nutrient.explanation.isNotBlank()) {
            Text(nutrient.explanation, color = TextSecondary, fontSize = 10.sp)
        }
    }
    Spacer(Modifier.height(8.dp))
}

// ═══════════════════════════════════════════════════════════════════════════
// Health Topic Result
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun HealthTopicContent(record: HealthRecord, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(record.disease, color = TextPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("Source: WHO", color = TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))

        record.data.forEach { (section, value) ->
            Text(section, color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            when (value) {
                is DataValue.Text -> {
                    Text(value.value, color = TextPrimary, fontSize = 11.sp, lineHeight = 16.sp)
                }
                is DataValue.ListText -> {
                    value.value.forEach { item ->
                        Text("• $item", color = TextPrimary, fontSize = 11.sp, lineHeight = 15.sp,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                    }
                }
                is DataValue.NestedMap -> {
                    value.value.forEach { (k, v) ->
                        Text("$k: $v", color = TextPrimary, fontSize = 11.sp,
                            modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Shared Components
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun Pill(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(36.dp),
        shape = RoundedCornerShape(10.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 16.sp, color = Color.White)
    }
}
