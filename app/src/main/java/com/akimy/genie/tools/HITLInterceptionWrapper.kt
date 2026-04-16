package com.akimy.genie.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.akimy.genie.agent.ToolOutcome
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "GenieHITLWrapper"

/**
 * Result from the BiometricAuthActivity.
 */
sealed class AuthResult {
    data object Approved : AuthResult()
    data object Denied : AuthResult()
    data object Timeout : AuthResult()
}

/**
 * Human-in-the-Loop interception wrapper.
 *
 * When a tool has requiresAuth = true, this wrapper intercepts the execution
 * and triggers a BiometricPrompt via a transparent FragmentActivity.
 *
 * Flow:
 * 1. Agent decides to execute an auth-required tool
 * 2. HITLWrapper fires Intent to BiometricAuthActivity
 * 3. BiometricAuthActivity shows BiometricPrompt overlay
 * 4. Result (Approved/Denied/Timeout) is sent back via in-process Channel
 * 5. If Approved → tool executes normally
 * 6. If Denied/Timeout → ToolOutcome.AuthErr is returned
 */
object HITLInterceptionWrapper {

    /** In-process channel for auth results from BiometricAuthActivity */
    val authResultChannel = Channel<AuthResult>(capacity = 1)

    /**
     * Wrap a tool execution with HITL biometric authentication.
     *
     * @param tool The tool to execute
     * @param args Tool arguments
     * @param serviceContext The accessibility service context
     * @param appContext Application context for launching the auth activity
     * @return ToolOutcome — the tool result if approved, AuthErr if denied
     */
    suspend fun executeWithAuth(
        tool: GenieTool,
        args: Map<String, String>,
        serviceContext: ToolServiceContext,
        appContext: Context,
    ): ToolOutcome {
        if (!tool.requiresAuth) {
            // No auth needed — execute directly
            return tool.execute(args, serviceContext)
        }

        Log.d(TAG, "HITL auth required for tool: ${tool.name}")

        // Launch transparent BiometricAuthActivity
        val intent = Intent(appContext, BiometricAuthActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(BiometricAuthActivity.EXTRA_TOOL_NAME, tool.name)
        }
        appContext.startActivity(intent)

        // Wait for auth result with timeout
        val result = withTimeoutOrNull(30_000L) {
            authResultChannel.receive()
        }

        return when (result) {
            is AuthResult.Approved -> {
                Log.d(TAG, "HITL approved for tool: ${tool.name}")
                tool.execute(args, serviceContext)
            }

            is AuthResult.Denied -> {
                Log.w(TAG, "HITL denied for tool: ${tool.name}")
                ToolOutcome.AuthErr("User denied biometric authentication for '${tool.name}'")
            }

            is AuthResult.Timeout, null -> {
                Log.w(TAG, "HITL timeout for tool: ${tool.name}")
                ToolOutcome.AuthErr("Biometric authentication timed out for '${tool.name}'")
            }
        }
    }
}
