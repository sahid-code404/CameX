package com.sahidcode404.camex.core.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sahidcode404.camex.core.model.LensFacing
import com.sahidcode404.camex.core.model.LensPreferenceRecord
import com.sahidcode404.camex.core.model.LensPreferencesState
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val STORE_NAME = "lens_settings"
private val Context.lensSettingsDataStore by preferencesDataStore(name = STORE_NAME)

/**
 * Stores one versioned JSON document inside Preferences DataStore. Preferences are keyed only by
 * [com.sahidcode404.camex.core.model.LensFingerprint] values; Camera2 IDs never become durable
 * keys. Unknown or orphaned records survive firmware updates and are simply ignored by the lens
 * resolver until a deterministic migration alias is available.
 */
class LensSettingsStore(context: Context) {
    private val dataStore = context.applicationContext.lensSettingsDataStore
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val state: Flow<LensPreferencesState> = dataStore.data
        .catch { error ->
            if (error is IOException) emit(emptyPreferences()) else throw error
        }
        .map { preferences -> decode(preferences[stateKey]) }

    suspend fun setVisible(fingerprint: String, visible: Boolean) = updateRecord(fingerprint) {
        it.copy(visible = visible)
    }

    suspend fun rename(fingerprint: String, label: String?) = updateRecord(fingerprint) {
        it.copy(displayName = label?.trim()?.take(48)?.takeIf(String::isNotEmpty))
    }

    suspend fun setOrder(orderedFingerprints: List<String>) {
        val order = orderedFingerprints
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .withIndex()
            .associate { it.value to it.index }
        if (order.isEmpty()) return
        update { current ->
            val existing = current.records.associateBy { it.fingerprint }.toMutableMap()
            order.forEach { (fingerprint, position) ->
                val record = existing[fingerprint] ?: LensPreferenceRecord(fingerprint)
                existing[fingerprint] = record.copy(position = position)
            }
            current.copy(records = existing.values.sortedBy { it.position ?: Int.MAX_VALUE })
        }
    }

    suspend fun setOneXReference(fingerprint: String?) {
        update { current ->
            current.copy(oneXReferenceFingerprint = fingerprint.normalizedFingerprintOrNull())
        }
    }

    suspend fun setLastSelected(facing: LensFacing, fingerprint: String) {
        val normalized = fingerprint.normalizedFingerprintOrNull() ?: return
        update { current ->
            when (facing) {
                LensFacing.BACK -> current.copy(lastSelectedRearFingerprint = normalized)
                LensFacing.FRONT -> current.copy(lastSelectedFrontFingerprint = normalized)
                LensFacing.EXTERNAL, LensFacing.UNKNOWN -> current
            }
        }
    }

    suspend fun replaceAfterMigration(state: LensPreferencesState) {
        update { state.normalized() }
    }

    private suspend fun updateRecord(
        fingerprint: String,
        transform: (LensPreferenceRecord) -> LensPreferenceRecord,
    ) {
        val normalized = fingerprint.normalizedFingerprintOrNull() ?: return
        update { current ->
            val records = current.records.associateBy { it.fingerprint }.toMutableMap()
            val previous = records[normalized] ?: LensPreferenceRecord(normalized)
            records[normalized] = transform(previous).copy(fingerprint = normalized)
            current.copy(records = records.values.toList())
        }
    }

    private suspend fun update(transform: (LensPreferencesState) -> LensPreferencesState) {
        dataStore.edit { preferences ->
            val next = transform(decode(preferences[stateKey])).normalized()
            preferences[stateKey] = json.encodeToString(next)
        }
    }

    private fun decode(value: String?): LensPreferencesState {
        if (value.isNullOrBlank()) return LensPreferencesState()
        return try {
            json.decodeFromString<LensPreferencesState>(value).normalized()
        } catch (_: SerializationException) {
            LensPreferencesState()
        } catch (_: IllegalArgumentException) {
            LensPreferencesState()
        }
    }

    private fun LensPreferencesState.normalized(): LensPreferencesState = copy(
        schemaVersion = LensPreferencesState.CURRENT_SCHEMA_VERSION,
        records = records
            .mapNotNull { record ->
                val fingerprint = record.fingerprint.normalizedFingerprintOrNull()
                    ?: return@mapNotNull null
                record.copy(
                    fingerprint = fingerprint,
                    displayName = record.displayName?.trim()?.take(48)?.takeIf(String::isNotEmpty),
                    position = record.position?.takeIf { it >= 0 },
                )
            }
            .associateBy { it.fingerprint }
            .values
            .toList(),
        oneXReferenceFingerprint = oneXReferenceFingerprint.normalizedFingerprintOrNull(),
        lastSelectedRearFingerprint = lastSelectedRearFingerprint.normalizedFingerprintOrNull(),
        lastSelectedFrontFingerprint = lastSelectedFrontFingerprint.normalizedFingerprintOrNull(),
    )

    private fun String?.normalizedFingerprintOrNull(): String? = this
        ?.trim()
        ?.take(96)
        ?.takeIf { it.matches(FINGERPRINT_PATTERN) }

    private companion object {
        val stateKey = stringPreferencesKey("lens.preferences.json")
        val FINGERPRINT_PATTERN = Regex("^[a-z0-9][a-z0-9_-]{7,95}$")
    }
}
