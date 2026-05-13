package com.akimy.genie.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.akimy.genie.tools.*

private val ScribePrimary = Color(0xFF6366F1)
private val ScribeAccent = Color(0xFF8B5CF6)
private val ScribeSuccess = Color(0xFF10B981)
private val ScribeDanger = Color(0xFFEF4444)
private val ScribeWarning = Color(0xFFF59E0B)
private val ScribeBackground = Color(0xFF0F1117)
private val ScribeCard = Color(0xFF1A1D26)
private val ScribeText = Color(0xFFF1F5F9)
private val ScribeTextSecondary = Color(0xFF94A3B8)

sealed class ScribeUIState {
    object Idle : ScribeUIState()
    object ConfiguringLanguage : ScribeUIState()
    object ConfiguringMode : ScribeUIState()
    object Recording : ScribeUIState()
    object Processing : ScribeUIState()
    data class ShowingResults(val result: ScribeResult) : ScribeUIState()
}

@Composable
fun ScribeOverlay(
    onClose: () -> Unit = {},
    onStartRecording: (ScribeConfig) -> Unit = {},
    onStopRecording: () -> Unit = {},
) {
    var uiState by remember { mutableStateOf<ScribeUIState>(ScribeUIState.Idle) }
    var config by remember { mutableStateOf(ScribeConfig()) }
    var recordingDuration by remember { mutableLongStateOf(0L) }

    // Recording timer effect
    LaunchedEffect(uiState) {
        if (uiState is ScribeUIState.Recording) {
            recordingDuration = 0L
            while (true) {
                kotlinx.coroutines.delay(1000L)
                recordingDuration++
            }
        }
    }

    // Sync with store
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(300L)
            val result = ScribeSessionStore.getResult()
            if (result != null && uiState !is ScribeUIState.ShowingResults) {
                uiState = ScribeUIState.ShowingResults(result)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ScribeBackground.copy(alpha = 0.95f))
    ) {
        when (val state = uiState) {
            is ScribeUIState.Idle -> {
                IdleRecordButton(
                    onClick = { uiState = ScribeUIState.ConfiguringLanguage }
                )
            }
            is ScribeUIState.ConfiguringLanguage -> {
                LanguageConfigScreen(
                    currentConfig = config,
                    onConfigured = { newConfig ->
                        config = newConfig
                        uiState = ScribeUIState.ConfiguringMode
                    },
                    onCancel = { uiState = ScribeUIState.Idle }
                )
            }
            is ScribeUIState.ConfiguringMode -> {
                ModeConfigScreen(
                    currentConfig = config,
                    onConfigured = { newConfig ->
                        config = newConfig
                        ScribeSessionStore.setConfig(newConfig)
                        uiState = ScribeUIState.Recording
                        onStartRecording(newConfig)
                    },
                    onBack = { uiState = ScribeUIState.ConfiguringLanguage }
                )
            }
            is ScribeUIState.Recording -> {
                RecordingScreen(
                    duration = recordingDuration,
                    onStop = {
                        onStopRecording()
                        uiState = ScribeUIState.Processing
                    }
                )
            }
            is ScribeUIState.Processing -> {
                ProcessingScreen()
            }
            is ScribeUIState.ShowingResults -> {
                ResultsScreen(
                    result = state.result,
                    onDone = {
                        ScribeSessionStore.clear()
                        uiState = ScribeUIState.Idle
                    }
                )
            }
        }

        // Close button (always visible)
        IconButton(
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
        ) {
            Text("✕", fontSize = 24.sp, color = ScribeText)
        }
    }
}

@Composable
fun IdleRecordButton(onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size((80 * scale).dp)
                    .clip(CircleShape)
                    .background(ScribePrimary)
                    .clickable(onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎙️",
                    fontSize = 36.sp
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Tap to record",
                fontSize = 16.sp,
                color = ScribeText,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun LanguageConfigScreen(
    currentConfig: ScribeConfig,
    onConfigured: (ScribeConfig) -> Unit,
    onCancel: () -> Unit
) {
    var inputLanguage by remember { mutableStateOf(currentConfig.inputLanguage) }
    var outputLanguage by remember { mutableStateOf(currentConfig.outputLanguage) }

    val languageOptions = listOf(
        "en-US" to "English (US)",
        "es-ES" to "Spanish",
        "fr-FR" to "French",
        "de-DE" to "German",
        "zh-CN" to "Chinese",
        "ja-JP" to "Japanese",
        "ar-SA" to "Arabic",
        "hi-IN" to "Hindi",
        "pt-BR" to "Portuguese"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ScribeCard)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Language Settings",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScribeText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Configure input and output languages",
                    fontSize = 14.sp,
                    color = ScribeTextSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))

                // Input Language
                Text(
                    text = "I will speak in:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ScribeText
                )
                Spacer(modifier = Modifier.height(8.dp))
                LanguageSelector(
                    selectedLanguage = inputLanguage,
                    languages = languageOptions,
                    onLanguageSelected = { inputLanguage = it }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Output Language
                Text(
                    text = "Model should respond in:",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = ScribeText
                )
                Spacer(modifier = Modifier.height(8.dp))
                LanguageSelector(
                    selectedLanguage = outputLanguage,
                    languages = languageOptions,
                    onLanguageSelected = { outputLanguage = it }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancel,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ScribeText
                        )
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = {
                            onConfigured(
                                currentConfig.copy(
                                    inputLanguage = inputLanguage,
                                    outputLanguage = outputLanguage
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ScribePrimary
                        )
                    ) {
                        Text("Next")
                    }
                }
            }
        }
    }
}

@Composable
fun LanguageSelector(
    selectedLanguage: String,
    languages: List<Pair<String, String>>,
    onLanguageSelected: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        languages.take(4).forEach { (code, name) ->
            val isSelected = code == selectedLanguage
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isSelected) ScribePrimary.copy(alpha = 0.2f)
                        else Color.Transparent
                    )
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) ScribePrimary else ScribeTextSecondary.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .clickable { onLanguageSelected(code) }
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = name,
                    fontSize = 14.sp,
                    color = if (isSelected) ScribePrimary else ScribeText,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
                if (isSelected) {
                    Text("✓", fontSize = 18.sp, color = ScribePrimary)
                }
            }
        }
    }
}

@Composable
fun ModeConfigScreen(
    currentConfig: ScribeConfig,
    onConfigured: (ScribeConfig) -> Unit,
    onBack: () -> Unit
) {
    var selectedMode by remember { mutableStateOf(currentConfig.mode) }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = ScribeCard)
        ) {
            Column(
                modifier = Modifier.padding(24.dp)
            ) {
                Text(
                    text = "Recording Mode",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScribeText
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Choose how to process your recording",
                    fontSize = 14.sp,
                    color = ScribeTextSecondary
                )
                Spacer(modifier = Modifier.height(24.dp))

                // General Mode
                ModeCard(
                    title = "General",
                    description = "Standard transcription with key insights and action items",
                    icon = "📝",
                    isSelected = selectedMode == ScribeMode.GENERAL,
                    onClick = { selectedMode = ScribeMode.GENERAL }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Doctor Scribe Mode
                ModeCard(
                    title = "Doctor Scribe",
                    description = "Medical transcription formatted in SOAP note structure",
                    icon = "🩺",
                    isSelected = selectedMode == ScribeMode.DOCTOR_SCRIBE,
                    onClick = { selectedMode = ScribeMode.DOCTOR_SCRIBE }
                )

                Spacer(modifier = Modifier.height(32.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onBack,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = ScribeText
                        )
                    ) {
                        Text("Back")
                    }
                    Button(
                        onClick = {
                            onConfigured(currentConfig.copy(mode = selectedMode))
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = ScribePrimary
                        )
                    ) {
                        Text("Start Recording")
                    }
                }
            }
        }
    }
}

@Composable
fun ModeCard(
    title: String,
    description: String,
    icon: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (isSelected) ScribePrimary.copy(alpha = 0.2f)
                else Color.Transparent
            )
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = if (isSelected) ScribePrimary else ScribeTextSecondary.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = icon,
            fontSize = 32.sp
        )
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isSelected) ScribePrimary else ScribeText
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = ScribeTextSecondary
            )
        }
        if (isSelected) {
            Text("✓", fontSize = 20.sp, color = ScribePrimary)
        }
    }
}

@Composable
fun RecordingScreen(
    duration: Long,
    onStop: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recording")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "recording"
    )

    val minutes = duration / 60
    val seconds = duration % 60

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(ScribeDanger.copy(alpha = alpha)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎙️",
                    fontSize = 48.sp
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Recording...",
                fontSize = 20.sp,
                color = ScribeText,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = String.format("%02d:%02d", minutes, seconds),
                fontSize = 48.sp,
                color = ScribeDanger,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = ScribeDanger
                ),
                modifier = Modifier.size(width = 200.dp, height = 56.dp),
                shape = RoundedCornerShape(28.dp)
            ) {
                Text("Stop Recording", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
fun ProcessingScreen() {
    val infiniteTransition = rememberInfiniteTransition(label = "processing")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "processing"
    )

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(64.dp),
                color = ScribePrimary,
                strokeWidth = 6.dp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Processing recording...",
                fontSize = 18.sp,
                color = ScribeText,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Extracting insights and formatting",
                fontSize = 14.sp,
                color = ScribeTextSecondary
            )
        }
    }
}

@Composable
fun ResultsScreen(
    result: ScribeResult,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            when (result) {
                is ScribeResult.General -> GeneralResultsView(result.insights)
                is ScribeResult.Medical -> SoapNoteView(result.soapNote)
                is ScribeResult.Error -> ErrorView(result.message)
            }

            Spacer(modifier = Modifier.height(80.dp))
        }

        // Done button at bottom
        Button(
            onClick = onDone,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = ScribePrimary
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("Done", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun GeneralResultsView(insights: GeneralInsights) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Recording Summary",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = ScribeText
        )

        // Summary Card
        ResultCard(
            title = "Summary",
            icon = "📋",
            color = ScribePrimary
        ) {
            Text(
                text = insights.summary,
                fontSize = 14.sp,
                color = ScribeText,
                lineHeight = 20.sp
            )
        }

        // Key Points
        if (insights.keyPoints.isNotEmpty()) {
            ResultCard(
                title = "Key Points",
                icon = "💡",
                color = ScribeWarning
            ) {
                insights.keyPoints.forEach { point ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("•", fontSize = 14.sp, color = ScribeWarning)
                        Text(
                            text = point,
                            fontSize = 14.sp,
                            color = ScribeText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Action Items
        if (insights.actionItems.isNotEmpty()) {
            ResultCard(
                title = "Action Items",
                icon = "✅",
                color = ScribeSuccess
            ) {
                insights.actionItems.forEach { action ->
                    Row(
                        modifier = Modifier.padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("☐", fontSize = 14.sp, color = ScribeSuccess)
                        Text(
                            text = action,
                            fontSize = 14.sp,
                            color = ScribeText,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        // Full Transcription
        ResultCard(
            title = "Full Transcription",
            icon = "📄",
            color = ScribeTextSecondary
        ) {
            Text(
                text = insights.transcription,
                fontSize = 13.sp,
                color = ScribeTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun SoapNoteView(soapNote: SoapNote) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Medical SOAP Note",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = ScribeText
        )

        // Subjective
        SoapSection(
            title = "Subjective",
            subtitle = "Patient's complaints and symptoms",
            icon = "💬",
            color = Color(0xFF3B82F6),
            content = soapNote.subjective
        )

        // Objective
        SoapSection(
            title = "Objective",
            subtitle = "Clinical observations and measurements",
            icon = "🔍",
            color = Color(0xFF8B5CF6),
            content = soapNote.objective
        )

        // Assessment
        SoapSection(
            title = "Assessment",
            subtitle = "Diagnosis and clinical impression",
            icon = "⚕️",
            color = Color(0xFFF59E0B),
            content = soapNote.assessment
        )

        // Plan
        SoapSection(
            title = "Plan",
            subtitle = "Treatment plan and follow-up",
            icon = "📋",
            color = Color(0xFF10B981),
            content = soapNote.plan
        )

        // Full Transcription
        ResultCard(
            title = "Original Transcription",
            icon = "📄",
            color = ScribeTextSecondary
        ) {
            Text(
                text = soapNote.transcription,
                fontSize = 13.sp,
                color = ScribeTextSecondary,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun SoapSection(
    title: String,
    subtitle: String,
    icon: String,
    color: Color,
    content: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ScribeCard)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(color.copy(alpha = 0.2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = icon, fontSize = 24.sp)
                }
                Column {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = color
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = ScribeTextSecondary
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = color.copy(alpha = 0.3f), thickness = 1.dp)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = content,
                fontSize = 14.sp,
                color = ScribeText,
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
fun ResultCard(
    title: String,
    icon: String,
    color: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = ScribeCard)
    ) {
        Column(
            modifier = Modifier.padding(20.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = icon, fontSize = 20.sp)
                Text(
                    text = title,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = color
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun ErrorView(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = ScribeCard)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "⚠️", fontSize = 48.sp)
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Processing Error",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = ScribeDanger
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = ScribeTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
