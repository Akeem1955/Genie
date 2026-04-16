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
     * Build a user message with text content.
     * Wraps Content.Text() into Contents for sendMessageAsync().
     */
    fun buildUserContents(text: String): Contents {
        return Contents.of(Content.Text(text))
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
