package com.young.waterremote.data

import android.content.Context
import androidx.core.content.edit

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun loadSession(): UserSession? {
        val token = preferences.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val mobile = preferences.getString(KEY_MOBILE, "").orEmpty()
        return UserSession(
            mobile = mobile,
            token = token,
            balance = preferences.getString(KEY_BALANCE, null)
        )
    }

    fun saveSession(session: UserSession) {
        preferences.edit {
            putString(KEY_MOBILE, session.mobile)
            putString(KEY_TOKEN, session.token)
            putString(KEY_BALANCE, session.balance)
        }
    }

    fun clearSession() {
        preferences.edit {
            remove(KEY_MOBILE)
            remove(KEY_TOKEN)
            remove(KEY_BALANCE)
        }
    }

    fun loadBindings(): DeviceBindings = DeviceBindings(
        hotKey = preferences.getString(KEY_HOT, "").orEmpty(),
        coldKey = preferences.getString(KEY_COLD, "").orEmpty()
    )

    fun saveBindings(bindings: DeviceBindings) {
        preferences.edit {
            putString(KEY_HOT, bindings.hotKey)
            putString(KEY_COLD, bindings.coldKey)
        }
    }

    fun isWaterConfirmationEnabled(): Boolean =
        preferences.getBoolean(KEY_WATER_CONFIRMATION, true)

    fun setWaterConfirmationEnabled(enabled: Boolean) {
        preferences.edit {
            putBoolean(KEY_WATER_CONFIRMATION, enabled)
        }
    }

    private companion object {
        const val PREFERENCES = "water_remote"
        const val KEY_MOBILE = "session_mobile"
        const val KEY_TOKEN = "session_token"
        const val KEY_BALANCE = "wallet_balance"
        const val KEY_HOT = "hot_device_key"
        const val KEY_COLD = "cold_device_key"
        const val KEY_WATER_CONFIRMATION = "water_confirmation_enabled"
    }
}
