package com.akimy.genie.telemetry

/**
 * Four-tier error taxonomy for Genie's agent loop.
 *
 * - TransientErr -> retry with exponential backoff
 * - LogicErr -> replan with a new strategy
 * - AuthErr -> stop with user notification
 * - FatalErr -> hard stop
 */
sealed class ErrorTaxonomy {
    data class TransientErr(val message: String, val cause: Throwable? = null) : ErrorTaxonomy()

    data class LogicErr(val message: String, val cause: Throwable? = null) : ErrorTaxonomy()

    data class AuthErr(val message: String) : ErrorTaxonomy()

    data class FatalErr(val message: String, val cause: Throwable? = null) : ErrorTaxonomy()
}
