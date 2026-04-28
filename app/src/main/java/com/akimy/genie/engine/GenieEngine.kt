package com.akimy.genie.engine

import android.content.Context
import android.util.Log
import com.akimy.genie.telemetry.ErrorTaxonomy
import com.akimy.genie.telemetry.EventLogger
import com.akimy.genie.telemetry.GenieEvent
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException

private const val TAG = "GenieEngine"
private const val AGENT_MAX_CONTEXT_LENGTH = 32_000
private const val AGENT_MAX_TOKENS = 8_192
private const val AGENT_TOP_K = 64
private const val AGENT_TOP_P = 0.95
private const val AGENT_TEMPERATURE = 1.0

/**
 * Response chunk from the LLM during streaming.
 */
sealed class AgentResponse {
    /** Partial text token from the model */
    data class Token(val text: String) : AgentResponse()
    /** Streaming complete — full response is the concatenation of all Token.text */
    data object Done : AgentResponse()
    /** Model is requesting a tool call (when automaticToolCalling = false) */
    data class ToolCallRequest(val message: Message) : AgentResponse()
    /** Error during inference */
    data class Error(val error: ErrorTaxonomy) : AgentResponse()
}

/**
 * LiteRT-LM inference engine wrapper for Genie.
 *
 * Adapted from Gallery's LlmChatModelHelper.kt — the actual "brain" of the agent.
 *
 * Key patterns borrowed from Gallery:
 * - Engine(EngineConfig) + engine.initialize() on Dispatchers.IO
 * - engine.createConversation(ConversationConfig) with SamplerConfig
 * - conversation.sendMessageAsync() with MessageCallback
 * - conversation.close() + engine.close() for cleanup
 * - CancellationException handling
 *
 * Key changes from Gallery:
 * - Singleton class (not static object) — lifecycle managed by AccessibilityService
 * - automaticToolCalling = false by default (HITL requirement)
 * - MessageCallback converted to Kotlin Flow via callbackFlow
 * - Error mapping to ErrorTaxonomy (TransientErr, FatalErr)
 * - Integrated with EventLogger for observability
 */
class GenieEngine {

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var isInitialized = false

    /**
     * Initialize the LiteRT-LM engine and create a conversation.
     *
     * Adapted from Gallery's LlmChatModelHelper.initialize():
     * - EngineConfig with model path, backend, cache dir
     * - Engine creation + initialization
     * - ConversationConfig with sampler, system instruction, tools
     *
     * Must be called on a background thread (Dispatchers.IO).
     */
    suspend fun initialize(
        context: Context,
        modelPath: String,
        systemPrompt: String,
        tools: List<ToolProvider> = emptyList(),
    ): ErrorTaxonomy? = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        try {
            Log.d(TAG, "Initializing engine with model: $modelPath")
            Log.d(
                TAG,
                "Using agent config: maxContextLength=$AGENT_MAX_CONTEXT_LENGTH, " +
                    "maxTokens=$AGENT_MAX_TOKENS, topK=$AGENT_TOP_K, " +
                    "temperature=$AGENT_TEMPERATURE"
            )

            // Engine config (from Gallery's LlmChatModelHelper)
            val engineConfig = EngineConfig(
                modelPath = modelPath,
                backend = Backend.GPU(),
                maxNumTokens = AGENT_MAX_TOKENS,
                cacheDir = context.cacheDir.path,
            )

            // Create and initialize engine (from Gallery)
            val newEngine = Engine(engineConfig)
            newEngine.initialize()

            // Create conversation with manual tool calling (critical for HITL)
            val conversationConfig = ConversationConfig(
                samplerConfig = agentSamplerConfig(),
                systemInstruction = PromptFormatting.buildSystemInstruction(systemPrompt),
                tools = tools,
                automaticToolCalling = false, // The "Golden Ticket" — HITL Safety Wrapper
            )

            val newConversation = createConversationWithConstrainedDecoding(
                engine = newEngine,
                conversationConfig = conversationConfig,
            )

            engine = newEngine
            conversation = newConversation
            isInitialized = true

            val duration = System.currentTimeMillis() - startTime
            EventLogger.emit(GenieEvent.BootstrapPhase("engine_init", duration))
            Log.d(TAG, "Engine initialized in ${duration}ms")

            null // Success — no error
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - startTime
            Log.e(TAG, "Engine initialization failed: ${e.message}", e)
            EventLogger.emit(GenieEvent.ErrorOccurred(
                ErrorTaxonomy.FatalErr("Engine init failed: ${e.message}", e),
                "GenieEngine.initialize"
            ))
            ErrorTaxonomy.FatalErr("Engine initialization failed: ${e.message}", e)
        }
    }

    /**
     * Send a message to the model and stream the response as a Flow.
     *
     * Adapted from Gallery's LlmChatModelHelper.runInference():
     * - Converts MessageCallback pattern to Kotlin Flow via callbackFlow
     * - Handles CancellationException → TransientErr (from Gallery)
     * - Handles other exceptions → FatalErr
     * - Detects tool call responses (toolCalls not empty)
     *
     * @param contents The message contents to send
     * @return Flow of AgentResponse chunks
     */
    fun sendMessageAsync(contents: Contents): Flow<AgentResponse> = callbackFlow {
        val conv = conversation
        if (conv == null || !isInitialized) {
            trySend(AgentResponse.Error(
                ErrorTaxonomy.FatalErr("Engine not initialized")
            ))
            close()
            return@callbackFlow
        }

        val startTime = System.currentTimeMillis()
        val tokenBuilder = StringBuilder()

        // MessageCallback pattern from Gallery's LlmChatModelHelper.runInference()
        conv.sendMessageAsync(
            contents,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val text = message.toString()
                    tokenBuilder.append(text)

                    // Check if model is requesting a tool call
                    if (message.toolCalls.isNotEmpty()) {
                        trySend(AgentResponse.ToolCallRequest(message))
                    } else {
                        trySend(AgentResponse.Token(text))
                    }
                }

                override fun onDone() {
                    val duration = System.currentTimeMillis() - startTime
                    EventLogger.emit(GenieEvent.InferenceLatency(
                        durationMs = duration,
                        tokenCount = tokenBuilder.length, // approximate
                    ))
                    trySend(AgentResponse.Done)
                    close()
                }
                /*
                
                */

                override fun onError(throwable: Throwable) {
                    val msg = throwable.message ?: ""
                    val error = if (throwable is CancellationException) {
                        // From Gallery: CancellationException is a normal stop
                        Log.i(TAG, "Inference cancelled")
                        ErrorTaxonomy.TransientErr("Inference cancelled", throwable)
                    } else if (msg.contains("Failed to parse tool calls", ignoreCase = true)) {
                        Log.w(TAG, "Inference parse error: $msg")
                        ErrorTaxonomy.LogicErr("Syntax error parsing tool call: $msg", throwable)
                    } else {
                        Log.e(TAG, "Inference error: $msg", throwable)
                        ErrorTaxonomy.FatalErr("Inference error: $msg", throwable)
                    }

                    EventLogger.emit(GenieEvent.ErrorOccurred(error, "GenieEngine.sendMessageAsync"))
                    trySend(AgentResponse.Error(error))
                    close()
                }
            },
        )

        awaitClose {
            // Flow cancelled — no action needed, conversation stays alive
        }
    }

    /**
     * Send a user message and stream the response.
     *
     * @param text prompt text for the planner
     * @param imagePngBytes optional screenshot PNG bytes for multimodal turns
     */
    fun sendAgentMessage(
        text: String,
        imagePngBytes: List<ByteArray> = emptyList(),
    ): Flow<AgentResponse> {
        return sendMessageAsync(PromptFormatting.buildUserContents(text, imagePngBytes))
    }

    /**
     * Send a tool response back to the model for the next turn.
     * Used after HITL-intercepted tool execution completes.
     *
     * From LiteRT-LM manual tool calling docs:
     *   val toolResponseMessage = Message.tool(Contents.of(toolResponses))
     *   conversation.sendMessage(toolResponseMessage)
     */
    fun sendToolResponse(toolName: String, resultJson: String): Flow<AgentResponse> {
        val toolMessage = PromptFormatting.buildToolResponse(toolName, resultJson)
        return sendMessageAsync(toolMessage.contents)
    }

    /**
     * Reset the conversation context while keeping the engine alive.
     * Adapted from Gallery's LlmChatModelHelper.resetConversation().
     */
    fun resetConversation(
        systemPrompt: String,
        tools: List<ToolProvider> = emptyList(),
    ) {
        try {
            Log.d(TAG, "Resetting conversation")
            conversation?.close()

            val eng = engine ?: return
            val newConversation = createConversationWithConstrainedDecoding(
                engine = eng,
                conversationConfig = ConversationConfig(
                    samplerConfig = agentSamplerConfig(),
                    systemInstruction = PromptFormatting.buildSystemInstruction(systemPrompt),
                    tools = tools,
                    automaticToolCalling = false,
                ),
            )
            conversation = newConversation
            Log.d(TAG, "Conversation reset complete")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset conversation: ${e.message}", e)
        }
    }

    /**
     * Stop the current inference response.
     * From Gallery's LlmChatModelHelper.stopResponse().
     */
    fun stopResponse() {
        try {
            conversation?.cancelProcess()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop response: ${e.message}")
        }
    }

    /**
     * Clean up all resources.
     * Adapted from Gallery's LlmChatModelHelper.cleanUp().
     */
    fun shutdown() {
        try {
            conversation?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close conversation: ${e.message}")
        }
        try {
            engine?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to close engine: ${e.message}")
        }
        conversation = null
        engine = null
        isInitialized = false
        Log.d(TAG, "Engine shutdown complete")
    }

    /**
     * Check if the engine is ready for inference.
     */
    fun isReady(): Boolean = isInitialized && engine != null && conversation != null

    private fun agentSamplerConfig(): SamplerConfig {
        return SamplerConfig(
            topK = AGENT_TOP_K,
            topP = AGENT_TOP_P,
            temperature = AGENT_TEMPERATURE,
        )
    }

    @OptIn(ExperimentalApi::class)
    private fun createConversationWithConstrainedDecoding(
        engine: Engine,
        conversationConfig: ConversationConfig,
    ): Conversation {
        return try {
            ExperimentalFlags.enableConversationConstrainedDecoding = true
            engine.createConversation(conversationConfig)
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }
}
