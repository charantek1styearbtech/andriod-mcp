package com.agent.androidmcp.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.agent.androidmcp.action.ScrollDirection
import com.agent.androidmcp.model.RectBounds
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

object GestureHelper {

    private const val TAG = "GestureHelper"
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun clickAt(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 60L
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(40L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "clickAt($x, $y) onCompleted")
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "clickAt($x, $y) onCancelled")
                if (continuation.isActive) continuation.resume(false)
            }
        }

        val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
        if (!dispatched) {
            Log.e(TAG, "dispatchGesture clickAt failed to start")
            if (continuation.isActive) continuation.resume(false)
        }
    }

    suspend fun longClickAt(
        service: AccessibilityService,
        x: Float,
        y: Float,
        durationMs: Long = 800L
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(x, y)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(400L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "longClickAt($x, $y) onCompleted")
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "longClickAt($x, $y) onCancelled")
                if (continuation.isActive) continuation.resume(false)
            }
        }

        val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
        if (!dispatched) {
            Log.e(TAG, "dispatchGesture longClickAt failed to start")
            if (continuation.isActive) continuation.resume(false)
        }
    }

    suspend fun swipe(
        service: AccessibilityService,
        startX: Float,
        startY: Float,
        endX: Float,
        endY: Float,
        durationMs: Long = 200L
    ): Boolean = suspendCancellableCoroutine { continuation ->
        val path = Path().apply {
            moveTo(startX, startY)
            lineTo(endX, endY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, durationMs.coerceAtLeast(80L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "swipe(($startX, $startY) -> ($endX, $endY)) onCompleted")
                if (continuation.isActive) continuation.resume(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "swipe onCancelled")
                if (continuation.isActive) continuation.resume(false)
            }
        }

        val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
        if (!dispatched) {
            Log.e(TAG, "dispatchGesture swipe failed to start")
            if (continuation.isActive) continuation.resume(false)
        }
    }

    suspend fun scroll(
        service: AccessibilityService,
        direction: ScrollDirection,
        bounds: RectBounds? = null,
        screenWidth: Int = 1080,
        screenHeight: Int = 2400
    ): Boolean {
        val centerX = bounds?.centerX?.toFloat() ?: (screenWidth / 2f)
        val centerY = bounds?.centerY?.toFloat() ?: (screenHeight / 2f)
        val distance = (bounds?.height?.toFloat() ?: (screenHeight * 0.4f)) * 0.6f

        val (startX, startY, endX, endY) = when (direction) {
            ScrollDirection.DOWN -> {
                // To scroll DOWN, finger swipes from bottom to top
                arrayOf(centerX, centerY + distance / 2, centerX, centerY - distance / 2)
            }
            ScrollDirection.UP -> {
                // To scroll UP, finger swipes from top to bottom
                arrayOf(centerX, centerY - distance / 2, centerX, centerY + distance / 2)
            }
            ScrollDirection.RIGHT -> {
                // To scroll RIGHT, finger swipes from right to left
                arrayOf(centerX + distance / 2, centerY, centerX - distance / 2, centerY)
            }
            ScrollDirection.LEFT -> {
                // To scroll LEFT, finger swipes from left to right
                arrayOf(centerX - distance / 2, centerY, centerX + distance / 2, centerY)
            }
        }

        return swipe(service, startX, startY, endX, endY, 220L)
    }
}
