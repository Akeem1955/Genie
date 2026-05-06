package com.akimy.genie.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.StateFlow

// Premium Google-like Colors
private val GeniePrimary = Color(0xFF6C63FF)
private val GenieBlue = Color(0xFF4285F4)
private val GenieRed = Color(0xFFEA4335)
private val GenieYellow = Color(0xFFFBBC05)
private val GenieGreen = Color(0xFF34A853)
private val OverlayBackground = Color(0xCC1E1E1E) // Semi-transparent dark glass
private val TextPrimary = Color(0xFFFFFFFF)
private val TextSecondary = Color(0xFFAAAAAA)

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun GenieOverlayUI(stateFlow: StateFlow<AgentUIState>) {
    val state by stateFlow.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = 64.dp, end = 24.dp), // Position bottom-right
        contentAlignment = Alignment.BottomEnd
    ) {
        AnimatedContent(
            targetState = state,
            transitionSpec = {
                fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
            },
            label = "GenieStateTransition"
        ) { targetState ->
            when (targetState) {
                is AgentUIState.Initializing -> {
                    InitializingPill()
                }
                is AgentUIState.Idle -> {
                    IdleOrb()
                }
                is AgentUIState.Waking -> {
                    WakingPill()
                }
                is AgentUIState.Listening -> {
                    ListeningPill()
                }
                is AgentUIState.Thinking -> {
                    ThinkingOrb()
                }
                is AgentUIState.Executing -> {
                    ExecutingPill(targetState.title, targetState.subtitle)
                }
                is AgentUIState.Speaking -> {
                    SpeakingPill(targetState.text)
                }
            }
        }
    }
}


@Composable
fun IdleOrb() {
    val infiniteTransition = rememberInfiniteTransition(label = "idle_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_anim"
    )

    Box(
        modifier = Modifier
            .size(32.dp)
            .shadow(8.dp, CircleShape)
            .clip(CircleShape)
            .background(Color(0xFF6C63FF).copy(alpha = alpha)),
        contentAlignment = Alignment.Center
    ) {
        Text("🧞", fontSize = 16.sp)
    }
}

@Composable
fun ListeningPill() {
    val infiniteTransition = rememberInfiniteTransition(label = "listen_pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale_anim"
    )

    Row(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(OverlayBackground)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .drawBehind {
                    drawCircle(
                        color = GenieRed,
                        radius = size.width / 2 * scale
                    )
                }
        )
        Text(
            text = "Listening...",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun ThinkingOrb() {
    val infiniteTransition = rememberInfiniteTransition(label = "thinking_spin")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle_anim"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .shadow(16.dp, CircleShape)
            .clip(CircleShape)
            .drawBehind {
                val brush = Brush.sweepGradient(
                    colors = listOf(GenieBlue, GenieRed, GenieYellow, GenieGreen, GenieBlue),
                    center = Offset(size.width / 2, size.height / 2)
                )
                // We simulate rotation by drawing the brush, but wait, SweepGradient doesn't rotate easily
                // without Canvas rotation. Let's just do a simple rotation modifier.
            }
    ) {
        // Rotating colorful gradient
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    rotate(angle) {
                        drawRect(
                            brush = Brush.sweepGradient(
                                colors = listOf(GenieBlue, GenieRed, GenieYellow, GenieGreen, GenieBlue),
                                center = Offset(size.width / 2, size.height / 2)
                            )
                        )
                    }
                }
        )
        // Inner dark circle to make it a ring
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color(0xFF1E1E1E))
                .align(Alignment.Center)
        )
    }
}

@Composable
fun ExecutingPill(title: String, subtitle: String?) {
    Row(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(OverlayBackground)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Little pulse indicator
        val infiniteTransition = rememberInfiniteTransition(label = "exec_pulse")
        val alpha by infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(500, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "alpha_anim"
        )
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(GenieBlue.copy(alpha = alpha))
        )

        Column {
            Text(
                text = title,
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun SpeakingPill(text: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "speak_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_anim"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth(0.85f) // Take up to 85% width for a chat bubble look
            .shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 4.dp))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 24.dp, bottomEnd = 4.dp))
            .background(OverlayBackground)
            .drawBehind {
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(GenieBlue.copy(alpha = alpha * 0.5f), GeniePrimary.copy(alpha = alpha * 0.5f))
                    )
                )
            }
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "🔊",
                fontSize = 18.sp,
                modifier = Modifier.padding(top = 2.dp)
            )
            Text(
                text = text,
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 22.sp
            )
        }
    }
}
@Composable
fun WakingPill() {
    val infiniteTransition = rememberInfiniteTransition(label = "wake_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_anim"
    )

    Row(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(OverlayBackground)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(GenieYellow.copy(alpha = alpha))
        )
        Text(
            text = "Waking up...",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}


@Composable
fun InitializingPill() {
    val infiniteTransition = rememberInfiniteTransition(label = "init_pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha_anim"
    )

    Row(
        modifier = Modifier
            .shadow(12.dp, RoundedCornerShape(24.dp))
            .clip(RoundedCornerShape(24.dp))
            .background(OverlayBackground)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(GeniePrimary.copy(alpha = alpha))
        )
        Text(
            text = "Initializing...",
            color = TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium
        )
    }
}
