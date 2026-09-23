package com.agent.androidmcp.ai

import android.graphics.Bitmap
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object VisionLocator {

    private const val TAG = "VisionLocator"
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    suspend fun locate(
        screenshot: Bitmap,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int,
        config: AiConfig
    ): VisionLocateResult = withContext(Dispatchers.IO) {
        if (!config.isConfigured) {
            return@withContext VisionLocateResult(
                found = false,
                reason = "AI API key is not configured in settings."
            )
        }

        val base64Image = VisionImageUtils.toBase64Jpeg(screenshot, quality = 80, maxDimension = 1280)

        runCatching {
            when (config.provider) {
                AiProvider.GEMINI -> callGeminiVision(base64Image, targetDescription, screenWidth, screenHeight, config)
                AiProvider.ANTHROPIC -> callAnthropicVision(base64Image, targetDescription, screenWidth, screenHeight, config)
                AiProvider.OPENAI,
                AiProvider.GROQ,
                AiProvider.OLLAMA,
                AiProvider.OPENAI_COMPATIBLE -> callOpenAiVision(base64Image, targetDescription, screenWidth, screenHeight, config)
            }
        }.getOrElse { e ->
            Log.e(TAG, "Vision localization failed: ${e.message}", e)
            VisionLocateResult(
                found = false,
                reason = "Vision request failed: ${e.message}"
            )
        }
    }

    private fun callGeminiVision(
        base64Image: String,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int,
        config: AiConfig
    ): VisionLocateResult {
        val modelName = if (config.model.isNotBlank()) config.model else "gemini-1.5-flash"
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/$modelName:generateContent?key=${config.apiKey}"
        val prompt = buildVisionPrompt(targetDescription, screenWidth, screenHeight)

        val requestJson = buildJsonObject {
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", prompt) })
                        add(buildJsonObject {
                            put("inline_data", buildJsonObject {
                                put("mime_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                    })
                })
            })
            put("generationConfig", buildJsonObject {
                put("response_mime_type", "application/json")
                put("temperature", 0.1)
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .post(requestJson.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Gemini Vision API")

        if (!response.isSuccessful) {
            Log.e(TAG, "Gemini Vision error: code=${response.code}, body=$responseBody")
            throw IOException("Gemini Vision error (${response.code}): $responseBody")
        }

        val rootJson = Json.parseToJsonElement(responseBody).jsonObject
        val text = rootJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw IOException("No candidate text in Gemini response: $responseBody")

        return parseVisionResponse(text, screenWidth, screenHeight)
    }

    private fun callOpenAiVision(
        base64Image: String,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int,
        config: AiConfig
    ): VisionLocateResult {
        val baseUrl = config.baseUrl.ifBlank { "https://api.openai.com" }.trimEnd('/')
        val url = "$baseUrl/v1/chat/completions"
        val prompt = buildVisionPrompt(targetDescription, screenWidth, screenHeight)
        val modelName = if (config.model.isNotBlank()) config.model else "gpt-4o-mini"

        val requestJson = buildJsonObject {
            put("model", modelName)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            put("image_url", buildJsonObject {
                                put("url", "data:image/jpeg;base64,$base64Image")
                                put("detail", "low")
                            })
                        })
                    })
                })
            })
            put("temperature", 0.1)
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(requestJson.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from OpenAI Vision API")

        if (!response.isSuccessful) {
            Log.e(TAG, "OpenAI Vision error: code=${response.code}, body=$responseBody")
            throw IOException("OpenAI Vision error (${response.code}): $responseBody")
        }

        val rootJson = Json.parseToJsonElement(responseBody).jsonObject
        val text = rootJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
            ?: throw IOException("No message content in OpenAI response: $responseBody")

        return parseVisionResponse(text, screenWidth, screenHeight)
    }

    private fun callAnthropicVision(
        base64Image: String,
        targetDescription: String,
        screenWidth: Int,
        screenHeight: Int,
        config: AiConfig
    ): VisionLocateResult {
        val baseUrl = config.baseUrl.ifBlank { "https://api.anthropic.com" }.trimEnd('/')
        val url = "$baseUrl/v1/messages"
        val prompt = buildVisionPrompt(targetDescription, screenWidth, screenHeight)
        val modelName = if (config.model.isNotBlank()) config.model else "claude-3-5-sonnet-20241022"

        val requestJson = buildJsonObject {
            put("model", modelName)
            put("max_tokens", 512)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", "image/jpeg")
                                put("data", base64Image)
                            })
                        })
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                    })
                })
            })
        }.toString()

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toRequestBody(jsonMediaType))
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Anthropic Vision API")

        if (!response.isSuccessful) {
            Log.e(TAG, "Anthropic Vision error: code=${response.code}, body=$responseBody")
            throw IOException("Anthropic Vision error (${response.code}): $responseBody")
        }

        val rootJson = Json.parseToJsonElement(responseBody).jsonObject
        val text = rootJson["content"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw IOException("No message content in Anthropic Vision response: $responseBody")

        return parseVisionResponse(text, screenWidth, screenHeight)
    }

    private fun buildVisionPrompt(targetDescription: String, screenWidth: Int, screenHeight: Int): String {
        return """
You are an expert mobile UI element locator.
Attached is a screenshot of an Android phone screen (actual resolution: ${screenWidth}x${screenHeight}).
Your task is to find the exact visual center of the UI element that matches this target:
"$targetDescription"

Return ONLY valid JSON in one of these two formats:

If the element is found:
{
  "found": true,
  "normalized_x": 500,
  "normalized_y": 850,
  "confidence": 0.95,
  "label": "Short description of the element identified"
}
(where normalized_x is an integer from 0 (left edge) to 1000 (right edge), and normalized_y is from 0 (top edge) to 1000 (bottom edge)).

If the element is NOT visible on screen:
{
  "found": false,
  "reason": "Explanation why element was not found"
}

Do NOT wrap the output in markdown blocks. Return ONLY the raw JSON string.
""".trimIndent()
    }

    private fun parseVisionResponse(rawText: String, screenWidth: Int, screenHeight: Int): VisionLocateResult {
        val cleanJson = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = Json.parseToJsonElement(cleanJson).jsonObject
        val found = root["found"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!found) {
            val reason = root["reason"]?.jsonPrimitive?.content ?: "Element not detected by Vision model."
            return VisionLocateResult(found = false, reason = reason)
        }

        val normX = root["normalized_x"]?.jsonPrimitive?.intOrNull
        val normY = root["normalized_y"]?.jsonPrimitive?.intOrNull
        val rawX = root["x"]?.jsonPrimitive?.intOrNull
        val rawY = root["y"]?.jsonPrimitive?.intOrNull

        val (pixelX, pixelY) = when {
            normX != null && normY != null -> Pair(
                VisionImageUtils.denormalize(normX, screenWidth),
                VisionImageUtils.denormalize(normY, screenHeight)
            )
            rawX != null && rawY != null -> {
                if (rawX in 0..1000 && rawY in 0..1000 && (screenWidth > 1000 || screenHeight > 1000)) {
                    Pair(
                        VisionImageUtils.denormalize(rawX, screenWidth),
                        VisionImageUtils.denormalize(rawY, screenHeight)
                    )
                } else {
                    Pair(rawX.coerceIn(0, screenWidth), rawY.coerceIn(0, screenHeight))
                }
            }
            else -> Pair(null, null)
        }

        if (pixelX == null || pixelY == null) {
            return VisionLocateResult(found = false, reason = "Model reported found but did not provide valid coordinates.")
        }

        val confidence = root["confidence"]?.jsonPrimitive?.floatOrNull ?: 0.9f
        val label = root["label"]?.jsonPrimitive?.content ?: targetDescriptionPreview(rawText)

        return VisionLocateResult(
            found = true,
            x = pixelX,
            y = pixelY,
            confidence = confidence,
            label = label
        )
    }

    private fun targetDescriptionPreview(raw: String): String {
        return "Visual Element"
    }
}
