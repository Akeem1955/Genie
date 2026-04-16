package com.akimy.genie.telemetry

/**
 * Four-tier error taxonomy for Genie's agent loop.
 *
 * Maps directly to the architecture diagram's error handling nodes:
 * - TransientErr → retry with exponential backoff
 * - LogicErr → replan with new strategy
 * - AuthErr → replan with user notification
 * - FatalErr → hard stop
 */
sealed class ErrorTaxonomy {
    /**
     * Transient errors that may resolve on retry.
     * Examples: UI element still loading, network timeout, AccessibilityService lag.
     */
    data class TransientErr(val message: String, val cause: Throwable? = null) : ErrorTaxonomy()

    /**
     * Logic errors indicating the agent's plan is wrong.
     * Examples: invalid tool name, wrong arguments, element not found.
     */
    data class LogicErr(val message: String, val cause: Throwable? = null) : ErrorTaxonomy()

    /**
     * Authentication/authorization errors from HITL.
     * Examples: biometric denied, biometric timeout, user cancelled.
     */
    data class AuthErr(val message: String) : ErrorTaxonomy()

    /**
     * Fatal errors requiring immediate hard stop.
     * Examples: OOM, LiteRtLmJniException, engine crash, unrecoverable state.
     */
    data class FatalErr(val message: String, val cause: Throwable? = null) : ErrorTaxonomy()
}
