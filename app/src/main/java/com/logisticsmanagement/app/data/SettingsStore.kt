package com.logisticsmanagement.app.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class AppSettings(
    val backgroundEnabled: Boolean = false,
    val nativeJevEnabled: Boolean = false,
    val nativeEvidenceVerbose: Boolean = false,
)

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(readSettings())
    private fun readSettings(): AppSettings {
        if (preferences.getInt("version", 1) < 2) {
            val settings = AppSettings()
            check(preferences.edit().putString("settings", Json.encodeToString(settings)).putInt("version", 2).commit())
            return settings
        }
        return preferences.getString("settings", null)?.let { Json.decodeFromString<AppSettings>(it) } ?: AppSettings()
    }
    fun observe(): Flow<AppSettings> = state.asStateFlow()
    suspend fun get(): AppSettings = state.value
    fun current(): AppSettings = state.value
    suspend fun save(settings: AppSettings) {
        check(preferences.edit().putString("settings", Json.encodeToString(settings)).commit())
        state.value = settings
    }
}
