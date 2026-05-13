package com.akimy.genie.engine

import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Message

/**
 * Utility functions for formatting prompts using LiteRT-LM's Message API.
 *
 * Replaces BetaAssist's raw string concatenation (the `fewshot` variable)
 * with proper LiteRT-LM Content/Message objects for type-safe prompt construction.
 */
object PromptFormatting {

    /**
     * Build a user message with optional image and audio context.
     *
     * Match Gallery's LiteRT-LM multimodal prompt order:
     * 1. Audio bytes first (if present)
     * 2. Image bytes next (if present)
     * 3. Text last so the text prompt is the final token context
     */
    fun buildUserContents(
        text: String,
        imagePngBytes: List<ByteArray> = emptyList(),
        audioBytes: List<ByteArray> = emptyList(),
    ): Contents {
        if (imagePngBytes.isEmpty() && audioBytes.isEmpty()) {
            return Contents.of(Content.Text(text))
        }

        val parts = mutableListOf<Content>()

        // Add audio first
        audioBytes.forEach { parts.add(Content.AudioBytes(it)) }

        // Then images
        imagePngBytes.forEach { parts.add(Content.ImageBytes(it)) }

        // Text last
        if (text.isNotBlank()) {
            parts.add(Content.Text(text))
        }
        return Contents.of(parts)
    }

    /**
     * Build a system instruction Contents for ConversationConfig.
     */
    fun buildSystemInstruction(text: String): Contents {
        return Contents.of(Content.Text(text))
    }

    /**
     * Build a tool response message to send back to the model.
     * Used when automaticToolCalling = false and we need to
     * manually feed tool results back to the conversation.
     *
     * @param toolName The name of the tool that was executed
     * @param resultJson The JSON result from tool execution
     */
    fun buildToolResponse(toolName: String, resultJson: String): Message {
        return Message.tool(
            Contents.of(Content.ToolResponse(toolName, resultJson))
        )
    }

    /**
     * Build an initial model message for conversation history injection.
     */
    fun buildModelMessage(text: String): Message {
        return Message.model(text)
    }

    /**
     * Build an initial user message for conversation history injection.
     */
    fun buildUserMessage(text: String): Message {
        return Message.user(text)
    }
}
