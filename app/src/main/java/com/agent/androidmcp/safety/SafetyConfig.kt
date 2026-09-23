package com.agent.androidmcp.safety

import android.content.Context
import android.content.SharedPreferences

data class SafetyConfig(
    val masterSafetyEnabled: Boolean = true,
    val confirmSendMessage: Boolean = true,
    val confirmDelete: Boolean = true,
    val confirmPayments: Boolean = true,
    val confirmSystemSettings: Boolean = true,
    val confirmOtherSensitive: Boolean = true
) {
    fun isConfirmationRequired(category: RiskCategory?): Boolean {
        if (!masterSafetyEnabled) return false
        return when (category) {
            RiskCategory.SEND_MESSAGE -> confirmSendMessage
            RiskCategory.DELETE_DATA -> confirmDelete
            RiskCategory.FINANCIAL_PAYMENT -> confirmPayments
            RiskCategory.SYSTEM_SETTINGS -> confirmSystemSettings
            RiskCategory.OTHER_SENSITIVE, null -> confirmOtherSensitive
        }
    }
}

object SafetyConfigRepository {
    private const val PREFS_NAME = "safety_policy_prefs"
    private const val KEY_MASTER = "key_master_safety"
    private const val KEY_SEND = "key_confirm_send"
    private const val KEY_DELETE = "key_confirm_delete"
    private const val KEY_PAY = "key_confirm_pay"
    private const val KEY_SETTINGS = "key_confirm_settings"
    private const val KEY_OTHER = "key_confirm_other"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun loadConfig(context: Context): SafetyConfig {
        val prefs = getPrefs(context)
        return SafetyConfig(
            masterSafetyEnabled = prefs.getBoolean(KEY_MASTER, true),
            confirmSendMessage = prefs.getBoolean(KEY_SEND, true),
            confirmDelete = prefs.getBoolean(KEY_DELETE, true),
            confirmPayments = prefs.getBoolean(KEY_PAY, true),
            confirmSystemSettings = prefs.getBoolean(KEY_SETTINGS, true),
            confirmOtherSensitive = prefs.getBoolean(KEY_OTHER, true)
        )
    }

    fun saveConfig(context: Context, config: SafetyConfig) {
        getPrefs(context).edit().apply {
            putBoolean(KEY_MASTER, config.masterSafetyEnabled)
            putBoolean(KEY_SEND, config.confirmSendMessage)
            putBoolean(KEY_DELETE, config.confirmDelete)
            putBoolean(KEY_PAY, config.confirmPayments)
            putBoolean(KEY_SETTINGS, config.confirmSystemSettings)
            putBoolean(KEY_OTHER, config.confirmOtherSensitive)
            apply()
        }
    }
}
