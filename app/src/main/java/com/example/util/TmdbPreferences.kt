package com.example.util

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TmdbPreferences {
    private const val PREFS_NAME = "zippy_tmdb_prefs"
    private const val KEY_API_KEY = "custom_tmdb_api_key"

    private val _apiKeyFlow = MutableStateFlow("")
    val apiKeyFlow: StateFlow<String> = _apiKeyFlow.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        val prefs = getPrefs(context)
        val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
        _apiKeyFlow.value = savedKey.trim()
    }

    fun getApiKey(context: Context? = null): String {
        if (context != null) {
            val prefs = getPrefs(context)
            val savedKey = prefs.getString(KEY_API_KEY, "") ?: ""
            return savedKey.trim()
        }
        return _apiKeyFlow.value.trim()
    }

    fun setApiKey(context: Context, key: String) {
        val trimmed = key.trim()
        getPrefs(context).edit().putString(KEY_API_KEY, trimmed).apply()
        _apiKeyFlow.value = trimmed
    }

    fun clearApiKey(context: Context) {
        setApiKey(context, "")
    }

    fun isConfigured(context: Context? = null): Boolean {
        return getApiKey(context).isNotBlank()
    }
}

