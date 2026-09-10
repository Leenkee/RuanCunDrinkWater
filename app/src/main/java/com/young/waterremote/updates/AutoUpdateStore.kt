package com.young.waterremote.updates

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.young.waterremote.data.AvailableRelease
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import java.time.ZonedDateTime

class AutoUpdateStore(context: Context) {
    private val preferences = context.getSharedPreferences("automatic_updates", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = preferences.getBoolean("enabled", false)
    fun generation(): Long = preferences.getLong("generation", 0)
    fun lastAttemptDate(): String? = preferences.getString("last_attempt", null)

    fun setEnabled(enabled: Boolean) = synchronized(lock) {
        if (enabled == isEnabled()) return@synchronized
        preferences.edit {
            putBoolean("enabled", enabled)
            putLong("generation", generation() + 1)
            remove("pending_release")
            // Enabling after noon starts with tomorrow's check, not an immediate request.
            val now = ZonedDateTime.now()
            if (enabled && now.hour >= 12) putString("last_attempt", now.toLocalDate().toString())
        }
    }

    fun claimAttempt(expectedGeneration: Long, now: ZonedDateTime): Boolean = synchronized(lock) {
        if (!isEnabled() || generation() != expectedGeneration ||
            !UpdateSchedule.isDue(now, lastAttemptDate())
        ) return@synchronized false
        preferences.edit { putString("last_attempt", now.toLocalDate().toString()) }
        true
    }

    fun saveRelease(expectedGeneration: Long, release: AvailableRelease) = synchronized(lock) {
        // Ignore a request that completed after the user disabled/re-enabled the feature.
        if (isEnabled() && generation() == expectedGeneration) {
            val json = JsonObject().apply {
                addProperty("version", release.versionName)
                addProperty("download", release.downloadUrl)
                addProperty("page", release.releaseUrl)
            }
            preferences.edit { putString("pending_release", json.toString()) }
        }
    }

    fun pendingRelease(): AvailableRelease? {
        if (!isEnabled()) return null
        val json = preferences.getString("pending_release", null) ?: return null
        return runCatching {
            val root = JsonParser.parseString(json).asJsonObject
            AvailableRelease(root.get("version").asString, root.get("download").asString, root.get("page").asString)
        }.getOrNull()
    }

    fun clearRelease() {
        preferences.edit { remove("pending_release") }
    }

    fun observeRelease() = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(pendingRelease())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(pendingRelease())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private companion object {
        val lock = Any()
    }
}
