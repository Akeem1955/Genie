package com.akimy.genie.engine

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "GenieModelDownloadMgr"

/**
 * Download state machine for the model bootstrap phase.
 */
sealed class DownloadState {
    data object Idle : DownloadState()
    data object Checking : DownloadState()
    data class Downloading(
        val progressPercent: Int,
        val receivedBytes: Long,
        val totalBytes: Long,
        val bytesPerSecond: Long,
        val remainingMs: Long,
    ) : DownloadState()
    data object Ready : DownloadState()
    data class Failed(val message: String) : DownloadState()
}

/**
 * Manages the model download lifecycle for Genie.
 *
 * Adapted from Gallery's DownloadRepository — uses WorkManager for background downloads
 * with foreground service notification. Emits status via StateFlow instead of LiveData
 * (since Genie is a headless service, not a UI-bound ViewModel).
 *
 * Key features borrowed from Gallery:
 * - WorkManager-based background download
 * - Unique work policy (REPLACE) to avoid duplicate downloads
 * - Worker progress observation
 * - Download start/success/failure tracking
 *
 * Key changes from Gallery:
 * - Stripped Firebase analytics
 * - Stripped deep-link notifications
 * - StateFlow instead of LiveData
 * - Simplified API for single-model download
 */
class ModelDownloadManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

    /**
     * Check if the model is already downloaded, and if not, start downloading it.
     * This is the entry point for the Bootstrap phase.
     */
    fun ensureModelReady(
        modelConfig: GenieModelConfig = GenieModelConfig.DEFAULT,
        accessToken: String? = null,
    ) {
        _downloadState.value = DownloadState.Checking

        if (modelConfig.isDownloaded(context)) {
            Log.d(TAG, "Model already downloaded at: ${modelConfig.getLocalPath(context)}")
            _downloadState.value = DownloadState.Ready
            return
        }

        Log.d(TAG, "Model not found locally, starting download...")
        startDownload(modelConfig, accessToken)
    }

    /**
     * Start the model download using WorkManager.
     * Adapted from Gallery's DefaultDownloadRepository.downloadModel()
     */
    private fun startDownload(
        modelConfig: GenieModelConfig,
        accessToken: String?,
    ) {
        // Build input data (from Gallery's DownloadRepository)
        val inputDataBuilder = Data.Builder()
            .putString(DownloadConsts.KEY_MODEL_NAME, modelConfig.displayName)
            .putString(DownloadConsts.KEY_MODEL_URL, modelConfig.downloadUrl)
            .putString(DownloadConsts.KEY_MODEL_COMMIT_HASH, modelConfig.commitHash)
            .putString(DownloadConsts.KEY_MODEL_DOWNLOAD_MODEL_DIR, modelConfig.normalizedName)
            .putString(DownloadConsts.KEY_MODEL_DOWNLOAD_FILE_NAME, modelConfig.modelFile)
            .putLong(DownloadConsts.KEY_MODEL_TOTAL_BYTES, modelConfig.sizeInBytes)

        if (accessToken != null) {
            inputDataBuilder.putString(DownloadConsts.KEY_MODEL_DOWNLOAD_ACCESS_TOKEN, accessToken)
        }

        // Create worker request (from Gallery)
        val downloadWorkRequest = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setInputData(inputDataBuilder.build())
            .addTag("genie_model_download")
            .build()

        val workerId = downloadWorkRequest.id

        // Enqueue with REPLACE policy (from Gallery)
        workManager.enqueueUniqueWork(
            "genie_model_${modelConfig.normalizedName}",
            ExistingWorkPolicy.REPLACE,
            downloadWorkRequest,
        )

        // Observe progress (adapted from Gallery's DownloadRepository)
        workManager.getWorkInfoByIdLiveData(workerId).observeForever { workInfo ->
            if (workInfo != null) {
                when (workInfo.state) {
                    WorkInfo.State.ENQUEUED -> {
                        Log.d(TAG, "Download enqueued")
                        _downloadState.value = DownloadState.Downloading(
                            progressPercent = 0,
                            receivedBytes = 0,
                            totalBytes = modelConfig.sizeInBytes,
                            bytesPerSecond = 0,
                            remainingMs = 0,
                        )
                    }

                    WorkInfo.State.RUNNING -> {
                        val receivedBytes = workInfo.progress.getLong(
                            DownloadConsts.KEY_MODEL_DOWNLOAD_RECEIVED_BYTES, 0L
                        )
                        val downloadRate = workInfo.progress.getLong(
                            DownloadConsts.KEY_MODEL_DOWNLOAD_RATE, 0L
                        )
                        val remainingMs = workInfo.progress.getLong(
                            DownloadConsts.KEY_MODEL_DOWNLOAD_REMAINING_MS, 0L
                        )

                        if (receivedBytes > 0) {
                            val progress = if (modelConfig.sizeInBytes > 0) {
                                (receivedBytes * 100 / modelConfig.sizeInBytes).toInt()
                            } else 0

                            _downloadState.value = DownloadState.Downloading(
                                progressPercent = progress,
                                receivedBytes = receivedBytes,
                                totalBytes = modelConfig.sizeInBytes,
                                bytesPerSecond = downloadRate,
                                remainingMs = remainingMs,
                            )
                        }
                    }

                    WorkInfo.State.SUCCEEDED -> {
                        Log.d(TAG, "Download succeeded")
                        _downloadState.value = DownloadState.Ready
                    }

                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        val errorMessage = workInfo.outputData.getString(
                            DownloadConsts.KEY_MODEL_DOWNLOAD_ERROR_MESSAGE
                        ) ?: "Download failed"
                        Log.e(TAG, "Download failed: $errorMessage")
                        _downloadState.value = DownloadState.Failed(errorMessage)
                    }

                    else -> {}
                }
            }
        }
    }

    /**
     * Cancel any ongoing model download.
     */
    fun cancelDownload() {
        workManager.cancelAllWorkByTag("genie_model_download")
        _downloadState.value = DownloadState.Idle
    }
}
