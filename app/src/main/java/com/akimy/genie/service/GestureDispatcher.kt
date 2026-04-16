package com.akimy.genie.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private const val TAG = "GenieGestureDispatcher"

/**
 * Gesture dispatcher for accessibility-level touch/swipe actions.
 *
 * Extracted from BetaAssist's separate swipe methods:
 * - rightSwipeGesture(), leftSwipeGesture(), upSwipeGesture(), downSwipeGesture()
 *
 * Consolidated into a single class with direction-based dispatch,
 * plus new tap() and longPress() methods needed by the agent.
 *
 * All methods are suspend functions wrapping dispatchGesture() callbacks.
 */
class GestureDispatcher(private val service: AccessibilityService) {

    enum class Direction { UP, DOWN, LEFT, RIGHT }

    private val displayMetrics: DisplayMetrics
        get() {
            val wm = service.getSystemService(AccessibilityService.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            wm.defaultDisplay.getMetrics(metrics)
            return metrics
        }

    /**
     * Perform a swipe gesture in the given direction.
     * Adapted from BetaAssist's individual swipe methods.
     *
     * @param direction The swipe direction
     * @param durationMs Duration of the swipe in milliseconds
     * @return true if the gesture was dispatched successfully
     */
    suspend fun swipe(direction: Direction, durationMs: Long = 500L): Boolean {
        val metrics = displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val offsetX = metrics.widthPixels / 3f
        val offsetY = metrics.heightPixels / 3f

        val (startX, startY, endX, endY) = when (direction) {
            Direction.UP -> arrayOf(centerX, centerY + offsetY, centerX, centerY - offsetY)
            Direction.DOWN -> arrayOf(centerX, centerY - offsetY, centerX, centerY + offsetY)
            Direction.LEFT -> arrayOf(centerX + offsetX, centerY, centerX - offsetX, centerY)
            Direction.RIGHT -> arrayOf(centerX - offsetX, centerY, centerX + offsetX, centerY)
        }

        return dispatchSwipe(startX, startY, endX, endY, durationMs)
    }

    /**
     * Perform a tap at specific screen coordinates.
     * New method not in BetaAssist — needed for agent clicking at coordinates.
     */
    suspend fun tap(x: Float, y: Float, durationMs: Long = 100L): Boolean {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x, y)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGesture(gesture)
    }

    /**
     * Perform a long press at specific screen coordinates.
     * New method not in BetaAssist.
     */
    suspend fun longPress(x: Float, y: Float, durationMs: Long = 1000L): Boolean {
        return tap(x, y, durationMs)
    }

    /**
     * Perform a scroll (shorter swipe distance, useful for lists).
     */
    suspend fun scroll(direction: Direction, durationMs: Long = 300L): Boolean {
        val metrics = displayMetrics
        val centerX = metrics.widthPixels / 2f
        val centerY = metrics.heightPixels / 2f
        val offset = metrics.heightPixels / 5f

        val (startX, startY, endX, endY) = when (direction) {
            Direction.UP -> arrayOf(centerX, centerY + offset, centerX, centerY - offset)
            Direction.DOWN -> arrayOf(centerX, centerY - offset, centerX, centerY + offset)
            else -> return swipe(direction, durationMs) // left/right use regular swipe
        }

        return dispatchSwipe(startX, startY, endX, endY, durationMs)
    }

    private suspend fun dispatchSwipe(
        startX: Float, startY: Float,
        endX: Float, endY: Float,
        durationMs: Long,
    ): Boolean {
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }

        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()

        return dispatchGesture(gesture)
    }

    /**
     * Dispatch a gesture and suspend until completion.
     * Wraps AccessibilityService.dispatchGesture() callback into a coroutine.
     */
    private suspend fun dispatchGesture(gesture: GestureDescription): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val dispatched = service.dispatchGesture(
                gesture,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.w(TAG, "Gesture cancelled")
                        if (continuation.isActive) continuation.resume(false)
                    }
                },
                null
            )

            if (!dispatched) {
                Log.w(TAG, "dispatchGesture returned false")
                if (continuation.isActive) continuation.resume(false)
            }
        }
    }

    companion object {
        fun parseDirection(dirStr: String): Direction? = when (dirStr.lowercase()) {
            "up" -> Direction.UP
            "down" -> Direction.DOWN
            "left" -> Direction.LEFT
            "right" -> Direction.RIGHT
            else -> null
        }
    }
}
