package com.sahidcode404.camex.core.update

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.updatePreferencesDataStore by preferencesDataStore(name = "development_updates")

class UpdatePreferences(context: Context) {
    private val dataStore = context.applicationContext.updatePreferencesDataStore

    val state: Flow<UpdatePreferencesSnapshot> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences ->
            UpdatePreferencesSnapshot(
                lastCheckEpochMs = preferences[lastCheckKey]?.takeIf { it >= 0L },
                lastKnownVersionCode = preferences[lastKnownVersionKey]?.takeIf { it > 0 },
                dismissedVersionCode = preferences[dismissedVersionKey]?.takeIf { it > 0 },
            )
        }

    suspend fun markChecked(nowEpochMs: Long, lastKnownVersionCode: Int?) {
        dataStore.edit { preferences ->
            preferences[lastCheckKey] = nowEpochMs.coerceAtLeast(0L)
            if (lastKnownVersionCode != null && lastKnownVersionCode > 0) {
                preferences[lastKnownVersionKey] = lastKnownVersionCode
            }
        }
    }

    suspend fun dismiss(versionCode: Int) {
        if (versionCode <= 0) return
        dataStore.edit { preferences -> preferences[dismissedVersionKey] = versionCode }
    }

    private companion object {
        val lastCheckKey = longPreferencesKey("last_check_epoch_ms")
        val lastKnownVersionKey = intPreferencesKey("last_known_version_code")
        val dismissedVersionKey = intPreferencesKey("dismissed_version_code")
    }
}
