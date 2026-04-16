package com.akimy.genie.telemetry

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

private const val TAG = "GenieEventLogger"

/**
 * Events emitted by the Genie agent for observability.
 */
sealed class GenieEvent {
    data class StateTransition(
        val from: String,
        val to: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : GenieEvent()

    data class ToolExecution(
        val toolName: String,
        val args: Map<String, String>,
        val durationMs: Long,
        val success: Boolean,
        val timestamp: Long = System.currentTimeMillis(),
    ) : GenieEvent()

    data class InferenceLatency(
        val durationMs: Long,
        val tokenCount: Int,
        val timestamp: Long = System.currentTimeMillis(),
    ) : GenieEvent()

    data class ErrorOccurred(
        val error: ErrorTaxonomy,
        val context: String,
        val timestamp: Long = System.currentTimeMillis(),
    ) : GenieEvent()

    data class SkillWritten(
        val goalPattern: String,
        val stepCount: Int,
        val timestamp: Long = System.currentTimeMillis(),
    ) : GenieEvent()

    data class BootstrapPhase(
        val phase: String,
        val durationMs: Long,
        val timestamp: Long = System.currentTimeMillis(),
    ) : GenieEvent()
}

/**
 * Asynchronous event logger for the Genie agent.
 *
 * Uses a Kotlin Channel as an event bus with a dedicated consumer coroutine.
 * Non-blocking — never delays the main agent loop.
 * Currently logs to Logcat; can be extended to file/analytics.
 */
object EventLogger {

    private val eventChannel = Channel<GenieEvent>(capacity = Channel.BUFFERED)
    private var initialized = false

    /**
     * Initialize the event consumer. Call once during service startup.
     */
    fun init(scope: CoroutineScope) {
        if (initialized) return
        initialized = true

        scope.launch(Dispatchers.Default) {
            for (event in eventChannel) {
                processEvent(event)
            }
        }
    }

    /**
     * Emit an event to the logger. Non-blocking.
     */
    fun emit(event: GenieEvent) {
        eventChannel.trySend(event)
    }

    private fun processEvent(event: GenieEvent) {
        when (event) {
            is GenieEvent.StateTransition -> {
                Log.i(TAG, "⚡ State: ${event.from} → ${event.to}")
            }
            is GenieEvent.ToolExecution -> {
                val status = if (event.success) "✅" else "❌"
                Log.i(TAG, "$status Tool: ${event.toolName}(${event.args}) [${event.durationMs}ms]")
            }
            is GenieEvent.InferenceLatency -> {
                Log.i(TAG, "🧠 Inference: ${event.durationMs}ms, ${event.tokenCount} tokens")
            }
            is GenieEvent.ErrorOccurred -> {
                Log.w(TAG, "⚠️ Error [${event.context}]: ${event.error}")
            }
            is GenieEvent.SkillWritten -> {
                Log.i(TAG, "📚 Skill written: '${event.goalPattern}' (${event.stepCount} steps)")
            }
            is GenieEvent.BootstrapPhase -> {
                Log.i(TAG, "📦 Bootstrap: ${event.phase} [${event.durationMs}ms]")
            }
        }
    }
}
