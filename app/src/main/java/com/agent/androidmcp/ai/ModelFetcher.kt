package com.agent.androidmcp.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

object ModelFetcher {

    private const val TAG = "ModelFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchModels(
        context: Context,
        config: AiConfig,
        forceRefresh: Boolean = false
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        // Return cached models if available and not forcing refresh
        if (!forceRefresh) {
            val cached = AiConfigRepository.getCachedModels(context, config.provider)
            if (!cached.isNullOrEmpty()) {
                return@withContext Result.success(cached)
            }
        }

        if (!config.isConfigured && config.provider != AiProvider.OLLAMA) {
            return@withContext Result.failure(IllegalStateException("API key is not configured for ${config.provider.displayName}"))
        }

        runCatching {
            val remoteModels = when (config.provider) {
                AiProvider.GEMINI -> fetchGeminiModels(config)
                AiProvider.OPENAI -> fetchOpenAiModels(config)
                AiProvider.ANTHROPIC -> fetchAnthropicModels(config)
                AiProvider.GROQ -> fetchGroqModels(config)
                AiProvider.OLLAMA -> fetchOllamaModels(config)
                AiProvider.OPENAI_COMPATIBLE -> fetchOpenAiModels(config)
            }

            if (remoteModels.isNotEmpty()) {
                AiConfigRepository.setCachedModels(context, config.provider, remoteModels)
                remoteModels
            } else {
                config.provider.models
            }
        }.onFailure { e ->
            Log.e(TAG, "Failed to fetch models for ${config.provider}: ${e.message}", e)
        }
    }

    private fun fetchGeminiModels(config: AiConfig): List<String> {
        val baseUrl = config.baseUrl.ifBlank { "https://generativelanguage.googleapis.com" }.trimEnd('/')
        val url = "$baseUrl/v1beta/models?key=${config.apiKey}"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Gemini API")

        if (!response.isSuccessful) {
            throw IOException("Gemini API error (${response.code}): $responseBody")
        }

        val root = Json.parseToJsonElement(responseBody).jsonObject
        val modelsArray = root["models"]?.jsonArray ?: return emptyList()

        return modelsArray.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val methods = obj["supportedGenerationMethods"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()

            // Filter models that support content generation
            if (methods.isNotEmpty() && !methods.contains("generateContent")) {
                return@mapNotNull null
            }

            val cleanName = name.removePrefix("models/")
            // Keep chat/reasoning models (gemini)
            if (cleanName.startsWith("gemini", ignoreCase = true)) cleanName else null
        }.distinct().sorted()
    }

    private fun fetchOpenAiModels(config: AiConfig): List<String> {
        val baseUrl = config.baseUrl.ifBlank { "https://api.openai.com" }.trimEnd('/')
        val url = "$baseUrl/v1/models"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from OpenAI API")

        if (!response.isSuccessful) {
            throw IOException("OpenAI API error (${response.code}): $responseBody")
        }

        val root = Json.parseToJsonElement(responseBody).jsonObject
        val dataArray = root["data"]?.jsonArray ?: return emptyList()

        val allModelIds = dataArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }

        // Filter and prioritize chat / reasoning models
        val chatModels = allModelIds.filter { id ->
            val lower = id.lowercase()
            (lower.startsWith("gpt-") || lower.startsWith("o1") || lower.startsWith("o3") || lower.startsWith("chatgpt")) &&
                    !lower.contains("realtime") &&
                    !lower.contains("audio") &&
                    !lower.contains("instruct")
        }.sorted()

        return if (chatModels.isNotEmpty()) chatModels else allModelIds.sorted()
    }

    private fun fetchAnthropicModels(config: AiConfig): List<String> {
        val baseUrl = config.baseUrl.ifBlank { "https://api.anthropic.com" }.trimEnd('/')
        val url = "$baseUrl/v1/models"

        val request = Request.Builder()
            .url(url)
            .addHeader("x-api-key", config.apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Anthropic API")

        if (!response.isSuccessful) {
            throw IOException("Anthropic API error (${response.code}): $responseBody")
        }

        val root = Json.parseToJsonElement(responseBody).jsonObject
        val dataArray = root["data"]?.jsonArray ?: return emptyList()

        return dataArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }.distinct().sorted()
    }

    private fun fetchGroqModels(config: AiConfig): List<String> {
        val baseUrl = config.baseUrl.ifBlank { "https://api.groq.com/openai" }.trimEnd('/')
        val url = "$baseUrl/v1/models"

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Groq API")

        if (!response.isSuccessful) {
            throw IOException("Groq API error (${response.code}): $responseBody")
        }

        val root = Json.parseToJsonElement(responseBody).jsonObject
        val dataArray = root["data"]?.jsonArray ?: return emptyList()

        return dataArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
            .filter { !it.contains("whisper") }
            .distinct()
            .sorted()
    }

    private fun fetchOllamaModels(config: AiConfig): List<String> {
        val baseUrl = config.baseUrl.ifBlank { "http://10.0.2.2:11434" }.trimEnd('/')
        val url = "$baseUrl/api/tags"

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: throw IOException("Empty response from Ollama API")

        if (!response.isSuccessful) {
            throw IOException("Ollama API error (${response.code}): $responseBody")
        }

        val root = Json.parseToJsonElement(responseBody).jsonObject
        val modelsArray = root["models"]?.jsonArray ?: return emptyList()

        return modelsArray.mapNotNull {
            it.jsonObject["name"]?.jsonPrimitive?.content
        }.distinct().sorted()
    }
}
