package com.agent.androidmcp.auth

import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class GoogleAccount(
    val email: String? = null,
    val displayName: String? = null,
    val isLoggedIn: Boolean = false
)

object GoogleAuthManager {

    private const val TAG = "GoogleAuthManager"
    private const val PREFS_NAME = "google_auth_prefs"
    private const val KEY_EMAIL = "google_email"
    private const val KEY_DISPLAY_NAME = "google_display_name"

    private val _accountState = MutableStateFlow(GoogleAccount())
    val accountState: StateFlow<GoogleAccount> = _accountState.asStateFlow()

    private var pickerCallback: (() -> Unit)? = null

    fun init(context: Context) {
        val prefs = getPrefs(context)
        val savedEmail = prefs.getString(KEY_EMAIL, null)
        val savedName = prefs.getString(KEY_DISPLAY_NAME, null)

        if (!savedEmail.isNullOrBlank()) {
            _accountState.value = GoogleAccount(
                email = savedEmail,
                displayName = savedName ?: savedEmail.split("@")[0],
                isLoggedIn = true
            )
            Log.i(TAG, "Restored Google Account: $savedEmail")
        }
    }

    fun getSavedEmail(context: Context): String? {
        val prefs = getPrefs(context)
        return prefs.getString(KEY_EMAIL, null)
    }

    fun saveAccount(context: Context, email: String, displayName: String? = null) {
        val cleanEmail = email.trim().lowercase()
        val cleanName = displayName ?: cleanEmail.split("@")[0]

        getPrefs(context).edit()
            .putString(KEY_EMAIL, cleanEmail)
            .putString(KEY_DISPLAY_NAME, cleanName)
            .apply()

        _accountState.value = GoogleAccount(
            email = cleanEmail,
            displayName = cleanName,
            isLoggedIn = true
        )
        Log.i(TAG, "Saved Google Account: $cleanEmail")
    }

    fun signOut(context: Context) {
        getPrefs(context).edit().clear().apply()
        _accountState.value = GoogleAccount(
            email = null,
            displayName = null,
            isLoggedIn = false
        )
        Log.i(TAG, "User signed out from Google account")
    }

    fun createChooseAccountIntent(): Intent {
        return AccountManager.newChooseAccountIntent(
            null,
            null,
            arrayOf("com.google"),
            null,
            null,
            null,
            null
        )
    }

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
