package com.akimy.genie.tools

import android.os.Bundle
import android.util.Log
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.akimy.genie.R
import kotlinx.coroutines.runBlocking

private const val TAG = "GenieBiometricAuth"

/**
 * Transparent FragmentActivity for HITL biometric authentication.
 *
 * This activity has no UI — it uses Theme.Translucent.NoTitleBar and
 * immediately shows the OS biometric overlay on creation.
 *
 * Flow:
 * 1. Launched by HITLInterceptionWrapper via Intent (FLAG_ACTIVITY_NEW_TASK)
 * 2. onCreate() → immediately triggers BiometricPrompt
 * 3. Biometric result (success/fail/error) → sent to HITLInterceptionWrapper.authResultChannel
 * 4. Activity immediately finish()es itself
 *
 * Declared in AndroidManifest with:
 *   android:theme="@android:style/Theme.Translucent.NoTitleBar"
 *   android:excludeFromRecents="true"
 *   android:taskAffinity=""
 */
class BiometricAuthActivity : FragmentActivity() {

    companion object {
        const val EXTRA_TOOL_NAME = "extra_tool_name"
        const val EXTRA_REASON = "extra_reason"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val toolName = intent.getStringExtra(EXTRA_TOOL_NAME) ?: "unknown"
        val reason = intent.getStringExtra(EXTRA_REASON) ?: ""
        Log.d(TAG, "BiometricAuth launched for tool: $toolName (reason: $reason)")

        // Check if biometric auth is available
        val biometricManager = BiometricManager.from(this)
        when (biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)) {
            BiometricManager.BIOMETRIC_SUCCESS -> {
                showBiometricPrompt(toolName, reason)
            }

            BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> {
                Log.w(TAG, "Biometric not available, auto-approving")
                sendResult(AuthResult.Approved)
            }

            else -> {
                Log.w(TAG, "Biometric check returned unexpected result")
                sendResult(AuthResult.Denied)
            }
        }
    }

    private fun showBiometricPrompt(toolName: String, reason: String) {
        val executor = ContextCompat.getMainExecutor(this)

        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                super.onAuthenticationSucceeded(result)
                Log.d(TAG, "Biometric auth succeeded for tool: $toolName")
                sendResult(AuthResult.Approved)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                super.onAuthenticationError(errorCode, errString)
                Log.w(TAG, "Biometric auth error ($errorCode): $errString")
                sendResult(AuthResult.Denied)
            }

            override fun onAuthenticationFailed() {
                super.onAuthenticationFailed()
                Log.w(TAG, "Biometric auth failed for tool: $toolName")
                // Don't finish yet — the system may allow retry
            }
        }

        val biometricPrompt = BiometricPrompt(this, executor, callback)
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.biometric_title))
            .setSubtitle(if (reason.isNotBlank()) "$toolName — $reason" else "Authorize: $toolName")
            .setNegativeButtonText(getString(R.string.biometric_cancel))
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    private fun sendResult(result: AuthResult) {
        runBlocking {
            HITLInterceptionWrapper.authResultChannel.trySend(result)
        }
        finish()
    }
}
