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
    val aiEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiModel: String = "",
    val dailyReportEnabled: Boolean = false,
    val dailyReportHour: Int = 20,
    val dailyReportMinute: Int = 0,
    val backgroundEnabled: Boolean = true,
)

class SettingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(preferences.getString("settings", null)?.let { Json.decodeFromString<AppSettings>(it) } ?: AppSettings())
    fun observe(): Flow<AppSettings> = state.asStateFlow()
    suspend fun get(): AppSettings = state.value
    suspend fun save(settings: AppSettings) {
        require(settings.dailyReportHour in 0..23 && settings.dailyReportMinute in 0..59)
        check(preferences.edit().putString("settings", Json.encodeToString(settings)).commit())
        state.value = settings
    }
}
