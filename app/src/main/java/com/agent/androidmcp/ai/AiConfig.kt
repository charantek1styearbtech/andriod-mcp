package com.agent.androidmcp.ai

import android.content.Context
import android.content.SharedPreferences


enum class AiProvider(
    val displayName: String,
    val defaultBaseUrl: String,
    val defaultModel: String,
    val models: List<String>
) {
    GEMINI(
        displayName = "Google Gemini",
        defaultBaseUrl = "https://generativelanguage.googleapis.com",
        defaultModel = "gemini-1.5-flash",
        models = listOf("gemini-1.5-flash", "gemini-1.5-pro", "gemini-2.0-flash", "gemini-2.0-flash-exp", "gemini-1.0-pro")
    ),
    OPENAI(
        displayName = "OpenAI",
        defaultBaseUrl = "https://api.openai.com",
        defaultModel = "gpt-4o-mini",
        models = listOf("gpt-4o-mini", "gpt-4o", "gpt-4-turbo", "o1-mini", "o3-mini")
    ),
    ANTHROPIC(
        displayName = "Anthropic Claude",
        defaultBaseUrl = "https://api.anthropic.com",
        defaultModel = "claude-3-5-sonnet-20241022",
        models = listOf("claude-3-5-sonnet-20241022", "claude-3-5-haiku-20241022", "claude-3-opus-20240229")
    ),
    GROQ(
        displayName = "Groq (Fast)",
        defaultBaseUrl = "https://api.groq.com/openai",
        defaultModel = "llama-3.3-70b-versatile",
        models = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "mixtral-8x7b-32768")
    ),
    OLLAMA(
        displayName = "Ollama (Local)",
        defaultBaseUrl = "http://10.0.2.2:11434",
        defaultModel = "llama3.2",
        models = listOf("llama3.2", "llama3.1", "mistral", "qwen2.5", "deepseek-r1")
    ),
    OPENAI_COMPATIBLE(
        displayName = "Custom / OpenAI-Compatible",
        defaultBaseUrl = "https://api.openai.com",
        defaultModel = "gpt-4o-mini",
        models = listOf("gpt-4o-mini", "gpt-4o", "custom")
    )
}

data class AiConfig(
    val provider: AiProvider = AiProvider.GEMINI,
    val apiKey: String = "",
    val model: String = "gemini-1.5-flash",
    val baseUrl: String = "https://generativelanguage.googleapis.com",
    val maxSteps: Int = 10,
    val visionFallbackEnabled: Boolean = true
) {
    val isConfigured: Boolean get() = if (provider == AiProvider.OLLAMA) true else apiKey.isNotBlank()
}

object AiConfigRepository {

    private const val PREFS_NAME = "ai_config_prefs"
    private const val KEY_PROVIDER = "ai_provider"
    private const val KEY_API_KEY = "ai_api_key"
    private const val KEY_API_KEY_PREFIX = "ai_api_key_"
    private const val KEY_MODEL = "ai_model"
    private const val KEY_BASE_URL = "ai_base_url"
    private const val KEY_MAX_STEPS = "ai_max_steps"
    private const val KEY_VISION_FALLBACK = "ai_vision_fallback"

    fun loadConfig(context: Context): AiConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val providerStr = prefs.getString(KEY_PROVIDER, AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
        val provider = runCatching { AiProvider.valueOf(providerStr) }.getOrDefault(AiProvider.GEMINI)

        // Load provider-specific key first, falling back to legacy global key
        val apiKey = prefs.getString(KEY_API_KEY_PREFIX + provider.name, null)
            ?: prefs.getString(KEY_API_KEY, "") ?: ""

        val defaultModel = provider.defaultModel
        val model = prefs.getString(KEY_MODEL + "_" + provider.name, null)
            ?: prefs.getString(KEY_MODEL, defaultModel)
            ?: defaultModel

        val defaultBaseUrl = provider.defaultBaseUrl
        val baseUrl = prefs.getString(KEY_BASE_URL + "_" + provider.name, null)
            ?: prefs.getString(KEY_BASE_URL, defaultBaseUrl)
            ?: defaultBaseUrl

        val maxSteps = prefs.getInt(KEY_MAX_STEPS, 10)
        val visionFallbackEnabled = prefs.getBoolean(KEY_VISION_FALLBACK, true)

        return AiConfig(
            provider = provider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            maxSteps = maxSteps,
            visionFallbackEnabled = visionFallbackEnabled
        )
    }

    fun saveConfig(context: Context, config: AiConfig) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PROVIDER, config.provider.name)
            .putString(KEY_API_KEY_PREFIX + config.provider.name, config.apiKey.trim())
            .putString(KEY_API_KEY, config.apiKey.trim())
            .putString(KEY_MODEL + "_" + config.provider.name, config.model.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_BASE_URL + "_" + config.provider.name, config.baseUrl.trim())
            .putString(KEY_BASE_URL, config.baseUrl.trim())
            .putInt(KEY_MAX_STEPS, config.maxSteps)
            .putBoolean(KEY_VISION_FALLBACK, config.visionFallbackEnabled)
            .apply()
    }

    fun switchProvider(context: Context, newProvider: AiProvider, newModel: String? = null): AiConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = prefs.getString(KEY_API_KEY_PREFIX + newProvider.name, "") ?: ""
        val model = newModel ?: prefs.getString(KEY_MODEL + "_" + newProvider.name, newProvider.defaultModel) ?: newProvider.defaultModel
        val baseUrl = prefs.getString(KEY_BASE_URL + "_" + newProvider.name, newProvider.defaultBaseUrl) ?: newProvider.defaultBaseUrl
        val maxSteps = prefs.getInt(KEY_MAX_STEPS, 10)
        val visionFallback = prefs.getBoolean(KEY_VISION_FALLBACK, true)

        val updated = AiConfig(
            provider = newProvider,
            apiKey = apiKey,
            model = model,
            baseUrl = baseUrl,
            maxSteps = maxSteps,
            visionFallbackEnabled = visionFallback
        )
        saveConfig(context, updated)
        return updated
    }

    fun setKeyForProvider(context: Context, provider: AiProvider, apiKey: String): AiConfig {
        val current = loadConfig(context)
        val updated = if (current.provider == provider) {
            current.copy(apiKey = apiKey.trim())
        } else {
            current
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_API_KEY_PREFIX + provider.name, apiKey.trim())
            .apply()

        if (current.provider == provider) {
            saveConfig(context, updated)
        }
        return loadConfig(context)
    }

    private const val KEY_CACHED_MODELS_PREFIX = "ai_cached_models_"

    fun getCachedModels(context: Context, provider: AiProvider): List<String>? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_CACHED_MODELS_PREFIX + provider.name, null) ?: return null
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setCachedModels(context: Context, provider: AiProvider, models: List<String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CACHED_MODELS_PREFIX + provider.name, models.joinToString(","))
            .apply()
    }
}

