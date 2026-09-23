package com.agent.androidmcp.ai

import android.graphics.Bitmap
import android.util.Base64
import kotlinx.serialization.Serializable
import java.io.ByteArrayOutputStream

@Serializable
data class VisionLocateResult(
    val found: Boolean,
    val x: Int? = null,
    val y: Int? = null,
    val confidence: Float = 0.0f,
    val label: String? = null,
    val reason: String? = null
)

object VisionImageUtils {

    /**
     * Compresses a Bitmap into a Base64-encoded JPEG string.
     * Optionally downscales the image so max dimension does not exceed maxDimension.
     */
    fun toBase64Jpeg(bitmap: Bitmap, quality: Int = 80, maxDimension: Int = 1280): String {
        val scaled = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
            val scale = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
            val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        } else {
            bitmap
        }

        val outputStream = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(10, 100), outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    /**
     * Converts normalized coordinate (0..1000) to actual screen pixel dimension.
     */
    fun denormalize(normalized: Int, dimension: Int): Int {
        return ((normalized.toFloat() / 1000f) * dimension).toInt().coerceIn(0, dimension)
    }
}
