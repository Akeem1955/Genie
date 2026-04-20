package com.akimy.genie.engine

/**
 * Constants for WorkManager data keys used in model download operations.
 * Adapted from Gallery's Consts.kt — stripped of UI-specific constants.
 */
object DownloadConsts {
    // WorkManager data keys
    const val KEY_MODEL_URL = "KEY_MODEL_URL"
    const val KEY_MODEL_NAME = "KEY_MODEL_NAME"
    const val KEY_MODEL_COMMIT_HASH = "KEY_MODEL_COMMIT_HASH"
    const val KEY_MODEL_DOWNLOAD_MODEL_DIR = "KEY_MODEL_DOWNLOAD_MODEL_DIR"
    const val KEY_MODEL_DOWNLOAD_FILE_NAME = "KEY_MODEL_DOWNLOAD_FILE_NAME"
    const val KEY_MODEL_TOTAL_BYTES = "KEY_MODEL_TOTAL_BYTES"
    const val KEY_MODEL_DOWNLOAD_RECEIVED_BYTES = "KEY_MODEL_DOWNLOAD_RECEIVED_BYTES"
    const val KEY_MODEL_DOWNLOAD_RATE = "KEY_MODEL_DOWNLOAD_RATE"
    const val KEY_MODEL_DOWNLOAD_REMAINING_MS = "KEY_MODEL_DOWNLOAD_REMAINING_MS"
    const val KEY_MODEL_DOWNLOAD_ERROR_MESSAGE = "KEY_MODEL_DOWNLOAD_ERROR_MESSAGE"

    // Genie-specific temp file extension (Gallery uses "gallerytmp")
    const val TMP_FILE_EXT = "genietmp"
}
