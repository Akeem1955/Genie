package com.akimy.genie.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "GenieScreenCapture"

/**
 * Screenshot capture utility for the accessibility agent.
 *
 * Extracted from BetaAssist's processImage() method.
 * Wraps AccessibilityService.takeScreenshot() callback into a suspend function.
 * Handles HardwareBuffer → ARGB_8888 Bitmap conversion.
 */
object ScreenCapture {

    /**
     * Capture the current screen as a Bitmap.
     *
     * @param service The accessibility service instance
     * @return The captured Bitmap, or null if capture failed
     */
    suspend fun captureScreen(service: AccessibilityService): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Screenshot requires API 30+")
            return null
        }

        return suspendCancellableCoroutine { continuation ->
            service.takeScreenshot(
                0, // display ID
                service.mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback() {
                    override fun onSuccess(screenshotResult: ScreenshotResult) {
                        try {
                            val hardwareBuffer = screenshotResult.hardwareBuffer
                            val colorSpace = screenshotResult.colorSpace

                            // Convert HardwareBuffer to Bitmap (from BetaAssist)
                            val bitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                            hardwareBuffer.close()

                            if (bitmap != null) {
                                // Convert to software bitmap for processing
                                val softBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                                if (continuation.isActive) continuation.resume(softBitmap)
                            } else {
                                Log.e(TAG, "Failed to wrap hardware buffer")
                                if (continuation.isActive) continuation.resume(null)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Screenshot processing failed: ${e.message}", e)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "Screenshot failed with error code: $errorCode")
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            )
        }
    }
}
