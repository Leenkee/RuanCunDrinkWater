package com.young.waterremote

import com.young.waterremote.data.DrinkingStatus
import com.young.waterremote.data.SessionInvalidException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal object DrinkingStatusMonitor {
    private const val INTERVAL_MS = 1_000L
    private const val MAX_ATTEMPTS = 120

    suspend fun awaitCompletion(fetchStatus: suspend () -> DrinkingStatus): Boolean {
        repeat(MAX_ATTEMPTS) {
            delay(INTERVAL_MS)
            val status = try {
                fetchStatus()
            } catch (error: CancellationException) {
                throw error
            } catch (error: SessionInvalidException) {
                throw error
            } catch (_: Exception) {
                return@repeat
            }
            if (status == DrinkingStatus.FINISHED) return true
        }
        return false
    }
}
