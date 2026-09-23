package com.agent.androidmcp.ai

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

data class LlmStepResponse(
    val thought: String,
    val actionJson: String,
    val isDone: Boolean = false,
    val finalAnswer: String? = null
)

class LlmClient(private val config: AiConfig) {

    private val client = sharedClient

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    companion object {
        private const val TAG = "LlmClient"

        private val sharedClient by lazy {
            OkHttpClient.Builder()
                .connectionPool(okhttp3.ConnectionPool(5, 5, TimeUnit.MINUTES))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }

        fun getSystemPrompt(): String {
            return """
You are an Autonomous Android AI Agent running directly on an Android device.
Your task is to accomplish the user's goal by observing the current screen state and deciding on the single next action.

ACTION PROTOCOL (JSON):
Emit ONLY valid JSON with this exact schema:
{
  "thought": "Your concise step-by-step reasoning about what is currently on screen and what to do next",
  "status": "IN_PROGRESS" | "DONE" | "FAILED",
  "action": { ...action object... },
  "final_answer": "Brief explanation if status is DONE or FAILED"
}

Available Actions:
1. Open App:
   {"type": "OPEN_APP", "packageName": "com.android.settings"}
2. Click by Selector or Coordinates:
   {"type": "CLICK", "selector": {"text": "Display"}} or {"type": "CLICK", "x": 540, "y": 960}
3. Long Click:
   {"type": "LONG_CLICK", "selector": {"text": "Item"}}
4. Type Text:
   {"type": "TYPE", "text": "Search query", "selector": {"id": "e_5"}}
5. Clear Text:
   {"type": "CLEAR_TEXT"}
6. Scroll:
   {"type": "SCROLL", "direction": "DOWN"} (or UP, LEFT, RIGHT)
7. Swipe:
   {"type": "SWIPE", "startX": 540, "startY": 1600, "endX": 540, "endY": 400}
8. Key Event:
   {"type": "KEY_EVENT", "key": "BACK"} (or HOME, RECENTS)
9. Wait:
   {"type": "WAIT", "ms": 1500}

    Guidelines:
- Match UI elements using their exact text, id, or contentDescription from the provided screen tree.
- When an element is visible in the screenshot but missing or unlabelled in the screen tree (e.g. canvas, custom graphics, Flutter, WebViews), click it directly using pixel coordinates: {"type": "CLICK", "x": 540, "y": 960}.
- When typing into a search or text field, prefer clicking the field first if not focused.
- If the goal is fully achieved, set "status": "DONE" and provide "final_answer".
- Never output markdown code blocks like ```json ... ```, return ONLY pure JSON.
""".trimIndent()
        }
    }

    suspend fun queryNextStep(
        userGoal: String,
        history: List<String>,
        compactUiTree: String,
        currentApp: String,
        screenshotBase64: String? = null
    ): Result<LlmStepResponse> = withContext(Dispatchers.IO) {
        runCatching {
            when (config.provider) {
                AiProvider.GEMINI -> callGemini(userGoal, history, compactUiTree, currentApp, screenshotBase64)
                AiProvider.ANTHROPIC -> callAnthropic(userGoal, history, compactUiTree, currentApp, screenshotBase64)
                AiProvider.OPENAI,
                AiProvider.GROQ,
                AiProvider.OLLAMA,
                AiProvider.OPENAI_COMPATIBLE -> callOpenAi(userGoal, history, compactUiTree, currentApp, screenshotBase64)
            }
        }
    }

    private fun callAnthropic(
        userGoal: String,
        history: List<String>,
        compactUiTree: String,
        currentApp: String,
        screenshotBase64: String?
    ): LlmStepResponse {
        val baseUrl = config.baseUrl.ifBlank { "https://api.anthropic.com" }.trimEnd('/')
        val url = "$baseUrl/v1/messages"
        val userPrompt = buildUserPrompt(userGoal, history, compactUiTree, currentApp)
        val modelName = if (config.model.isNotBlank()) config.model else "claude-3-5-sonnet-20241022"

        val requestJson = buildJsonObject {
            put("model", modelName)
            put("max_tokens", 1024)
            put("system", getSystemPrompt())
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", buildJsonArray {
                        if (!screenshotBase64.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("type", "image")
                                put("source", buildJsonObject {
                                    put("type", "base64")
                                    put("media_type", "image/jpeg")
                                    put("data", screenshotBase64)
                                })
                            })
                        }
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", userPrompt)
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
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Anthropic API")

        if (!response.isSuccessful) {
            Log.e(TAG, "Anthropic error: code=${response.code}, body=$responseBody")
            throw IOException("Anthropic API error (${response.code}): $responseBody")
        }

        val rootJson = Json.parseToJsonElement(responseBody).jsonObject
        val text = rootJson["content"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw IOException("No text content in Anthropic response: $responseBody")

        return parseStepResponse(text)
    }

    private fun callGemini(
        userGoal: String,
        history: List<String>,
        compactUiTree: String,
        currentApp: String,
        screenshotBase64: String?
    ): LlmStepResponse {
        val modelName = if (config.model.isNotBlank()) config.model else "gemini-1.5-flash"
        val url = "${config.baseUrl.trimEnd('/')}/v1beta/models/$modelName:generateContent?key=${config.apiKey}"

        val userPrompt = buildUserPrompt(userGoal, history, compactUiTree, currentApp)

        val requestJson = buildJsonObject {
            put("system_instruction", buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", getSystemPrompt()) })
                })
            })
            put("contents", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", userPrompt) })
                        if (!screenshotBase64.isNullOrBlank()) {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "image/jpeg")
                                    put("data", screenshotBase64)
                                })
                            })
                        }
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
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Gemini API")

        if (!response.isSuccessful) {
            Log.e(TAG, "Gemini error: code=${response.code}, body=$responseBody")
            throw IOException("Gemini API error (${response.code}): $responseBody")
        }

        val rootJson = Json.parseToJsonElement(responseBody).jsonObject
        val text = rootJson["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("content")?.jsonObject
            ?.get("parts")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("text")?.jsonPrimitive?.content
            ?: throw IOException("No text found in Gemini response: $responseBody")

        return parseStepResponse(text)
    }

    private fun callOpenAi(
        userGoal: String,
        history: List<String>,
        compactUiTree: String,
        currentApp: String,
        screenshotBase64: String?
    ): LlmStepResponse {
        val baseUrl = config.baseUrl.ifBlank { "https://api.openai.com" }.trimEnd('/')
        val url = "$baseUrl/v1/chat/completions"
        val userPrompt = buildUserPrompt(userGoal, history, compactUiTree, currentApp)

        val requestJson = buildJsonObject {
            put("model", if (config.model.isNotBlank()) config.model else "gpt-4o-mini")
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "system")
                    put("content", getSystemPrompt())
                })
                add(buildJsonObject {
                    put("role", "user")
                    if (screenshotBase64.isNullOrBlank()) {
                        put("content", userPrompt)
                    } else {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", userPrompt)
                            })
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,$screenshotBase64")
                                    put("detail", "low")
                                })
                            })
                        })
                    }
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
        val responseBody = response.body?.string() ?: throw IOException("Empty response from OpenAI API")

        if (!response.isSuccessful) {
            Log.e(TAG, "OpenAI error: code=${response.code}, body=$responseBody")
            throw IOException("OpenAI API error (${response.code}): $responseBody")
        }

        val rootJson = Json.parseToJsonElement(responseBody).jsonObject
        val text = rootJson["choices"]?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.content
            ?: throw IOException("No choices found in OpenAI response: $responseBody")

        return parseStepResponse(text)
    }

    private fun buildUserPrompt(
        userGoal: String,
        history: List<String>,
        compactUiTree: String,
        currentApp: String
    ): String {
        return """
USER GOAL: "$userGoal"

CURRENT FOREGROUND APP: $currentApp

PREVIOUS STEPS EXECUTED:
${if (history.isEmpty()) "(None, this is step 1)" else history.joinToString("\n")}

CURRENT VISIBLE SCREEN ELEMENTS:
$compactUiTree

Decide the next single action. Return only JSON.
""".trimIndent()
    }

    private fun parseStepResponse(rawText: String): LlmStepResponse {
        val cleanJson = rawText.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        val root = Json.parseToJsonElement(cleanJson).jsonObject
        val thought = root["thought"]?.jsonPrimitive?.content ?: "Planning next action..."
        val status = root["status"]?.jsonPrimitive?.content ?: "IN_PROGRESS"
        val finalAnswer = root["final_answer"]?.jsonPrimitive?.content
        val isDone = status.equals("DONE", ignoreCase = true)

        val actionJson = root["action"]?.toString() ?: """{"type":"WAIT","ms":1000}"""

        return LlmStepResponse(
            thought = thought,
            actionJson = actionJson,
            isDone = isDone,
            finalAnswer = finalAnswer
        )
    }
}
