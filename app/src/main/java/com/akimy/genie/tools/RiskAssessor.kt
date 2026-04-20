package com.akimy.genie.tools

import android.text.InputType
import android.util.Log

private const val TAG = "GenieRiskAssessor"

/**
 * The result of a deterministic risk assessment.
 */
sealed class RiskVerdict {
    /** No elevated risk — execute the tool directly. */
    data object Allow : RiskVerdict()

    /** Elevated risk detected — require biometric HITL before execution. */
    data class RequireBiometric(val reason: String) : RiskVerdict()
}

/**
 * A risk signal detected during screen analysis.
 */
private enum class RiskSignal(val label: String) {
    DESTRUCTIVE_VERB("click target matches a destructive verb"),
    FINANCIAL_SCREEN("financial indicators visible on screen"),
    SENSITIVE_FIELD("typing into a sensitive input field"),
    AUTH_FLOW("screen appears to be an authentication flow"),
    SENSITIVE_APP("foreground app is in sensitive app list"),
}

/**
 * Deterministic, local risk assessor for Smart HITL.
 *
 * Evaluates `click` and `type_text` tool calls against the current screen context
 * using hard-coded rules. Requires **≥2 independent risk signals** to trigger
 * biometric authentication.
 *
 * This is fully local — no LLM, no network, no latency.
 *
 * Design principles:
 * - Only `click` and `type_text` are assessed (they are the only mutation tools)
 * - All other tools short-circuit to [RiskVerdict.Allow]
 * - ≥2 signals required to avoid false positives on benign actions
 * - Signals are derived from data already in the accessibility tree
 */
object RiskAssessor {

    // -- Destructive verb patterns (word-boundary matched) --
    private val DESTRUCTIVE_VERBS = setOf(
        "send", "transfer", "pay", "confirm", "submit",
        "delete", "remove", "reset", "purchase", "authorize",
        "approve", "proceed", "execute", "withdraw", "uninstall",
    )

    // -- Financial screen indicators --
    private val FINANCIAL_KEYWORDS = setOf(
        "balance", "total", "payment", "transfer", "amount",
        "transaction", "invoice", "billing", "checkout", "subtotal",
    )
    private val CURRENCY_REGEX = Regex(
        """[$€£¥₦₹₩₿]\s*\d+|\d+\.\d{2}\s*(USD|EUR|GBP|NGN|INR|KRW)""",
        RegexOption.IGNORE_CASE,
    )

    // -- Sensitive input field indicators --
    private val SENSITIVE_FIELD_HINTS = setOf(
        "password", "pin", "otp", "cvv", "cvc", "security code",
        "card number", "account number", "routing number", "secret",
        "verification code", "passcode",
    )
    private val SENSITIVE_INPUT_TYPE_FLAGS = setOf(
        InputType.TYPE_TEXT_VARIATION_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        InputType.TYPE_NUMBER_VARIATION_PASSWORD,
    )

    // -- Auth flow indicators --
    private val AUTH_FLOW_KEYWORDS = setOf(
        "sign in", "log in", "login", "signin", "verify",
        "two-factor", "2fa", "one-time", "mfa", "authenticate",
        "security question", "recovery code",
    )

    // -- Conservative sensitive app list (package prefixes) --
    private val SENSITIVE_APP_PREFIXES = listOf(
        "com.google.android.apps.walletnfcrel",  // Google Wallet
        "com.google.android.apps.nbu",            // Google Pay
        "com.paypal",                              // PayPal
        "com.venmo",                               // Venmo
        "com.squareup.cash",                       // Cash App
        "com.android.settings",                    // System Settings
        "com.android.vending",                     // Play Store (purchases)
    )

    /**
     * Assess whether a tool call requires biometric HITL based on the current screen.
     *
     * Only `click` and `type_text` are evaluated. All other tools return [RiskVerdict.Allow].
     *
     * @param toolName The tool being invoked
     * @param args The tool arguments (contains "target" for click, "text" for type_text)
     * @param screen The current screen context snapshot
     * @return [RiskVerdict.Allow] or [RiskVerdict.RequireBiometric]
     */
    fun assess(toolName: String, args: Map<String, String>, screen: ScreenContext): RiskVerdict {
        // Only assess mutation tools
        if (toolName != "click" && toolName != "type_text") {
            return RiskVerdict.Allow
        }

        val signals = mutableListOf<RiskSignal>()

        // -- Shared signals (apply to both click and type_text) --
        if (isFinancialScreen(screen)) signals.add(RiskSignal.FINANCIAL_SCREEN)
        if (isSensitiveApp(screen)) signals.add(RiskSignal.SENSITIVE_APP)

        // -- Tool-specific signals --
        when (toolName) {
            "click" -> {
                val target = args["target"] ?: ""
                if (isDestructiveVerb(target)) signals.add(RiskSignal.DESTRUCTIVE_VERB)
            }

            "type_text" -> {
                if (isSensitiveField(screen)) signals.add(RiskSignal.SENSITIVE_FIELD)
                if (isAuthFlow(screen)) signals.add(RiskSignal.AUTH_FLOW)
            }
        }

        return if (signals.size >= 2) {
            val reason = signals.joinToString(" + ") { it.label }
            Log.d(TAG, "HITL triggered for $toolName: $reason")
            RiskVerdict.RequireBiometric(reason)
        } else {
            if (signals.isNotEmpty()) {
                Log.d(TAG, "Single signal for $toolName (below threshold): ${signals[0].label}")
            }
            RiskVerdict.Allow
        }
    }

    // ========================================================================
    // Signal Detectors
    // ========================================================================

    /**
     * Check if the click target matches a destructive verb.
     * Uses word-boundary matching to avoid false positives
     * (e.g. "Sending" won't match, but "Send" will).
     */
    private fun isDestructiveVerb(target: String): Boolean {
        val words = target.lowercase().split(Regex("""\s+"""))
        return words.any { it in DESTRUCTIVE_VERBS }
    }

    /**
     * Check if the screen contains financial indicators.
     * Looks for currency symbols/patterns AND financial keywords.
     */
    private fun isFinancialScreen(screen: ScreenContext): Boolean {
        val allText = screen.visibleTexts.joinToString(" ").lowercase()
        val hasCurrency = CURRENCY_REGEX.containsMatchIn(allText)
        val hasKeyword = FINANCIAL_KEYWORDS.any { it in allText }
        return hasCurrency || hasKeyword
    }

    /**
     * Check if the currently focused field is a sensitive input.
     * Uses a combination of: isPassword flag, inputType flags, and hint text.
     */
    private fun isSensitiveField(screen: ScreenContext): Boolean {
        // Direct password flag
        if (screen.focusedFieldIsPassword) return true

        // InputType flags
        val inputType = screen.focusedFieldInputType
        if (inputType != null) {
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            if (variation in SENSITIVE_INPUT_TYPE_FLAGS) return true
        }

        // Hint text matching
        val hint = screen.focusedFieldHint?.lowercase() ?: return false
        return SENSITIVE_FIELD_HINTS.any { it in hint }
    }

    /**
     * Check if the screen appears to be an authentication flow.
     * Requires both auth keywords on screen AND a focused input field.
     */
    private fun isAuthFlow(screen: ScreenContext): Boolean {
        // Must have a focused input field
        if (screen.focusedFieldClassName == null) return false

        val allText = screen.visibleTexts.joinToString(" ").lowercase()
        return AUTH_FLOW_KEYWORDS.any { it in allText }
    }

    /**
     * Check if the foreground app is in the sensitive app list.
     */
    private fun isSensitiveApp(screen: ScreenContext): Boolean {
        return SENSITIVE_APP_PREFIXES.any { screen.packageName.startsWith(it) }
    }
}
