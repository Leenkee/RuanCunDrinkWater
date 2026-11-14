package com.young.waterremote

import com.young.waterremote.data.CommandReceipt
import com.young.waterremote.data.DrinkingStatus
import com.young.waterremote.data.WaterType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class DrinkingStep(val deviceKey: String, val waterType: WaterType?)

internal sealed interface DrinkingSequenceResult {
    data object Finished : DrinkingSequenceResult
    data object MissingOrderNumber : DrinkingSequenceResult
    data class TimedOut(val lastStatus: DrinkingStatus?) : DrinkingSequenceResult
}

/** Sends each command only after the preceding order is confirmed finished. */
internal object DrinkingSequence {
    suspend fun run(
        steps: List<DrinkingStep>,
        onSending: (DrinkingStep) -> Unit,
        onSubmitted: (DrinkingStep, String?) -> Unit,
        onStatus: (DrinkingStatus) -> Unit,
        sendCommand: suspend (DrinkingStep) -> CommandReceipt,
        fetchStatus: suspend (DrinkingStep, String) -> DrinkingStatus
    ): DrinkingSequenceResult {
        require(steps.isNotEmpty())
        for (step in steps) {
            currentCoroutineContext().ensureActive()
            onSending(step)
            val receipt = sendCommand(step)
            currentCoroutineContext().ensureActive()
            val orderNumber = receipt.orderNumber?.takeIf { it.isNotBlank() }
            onSubmitted(step, orderNumber)
            if (orderNumber == null) return DrinkingSequenceResult.MissingOrderNumber

            var lastStatus: DrinkingStatus? = null
            val finished = DrinkingStatusMonitor.awaitCompletion(onStatus = { status ->
                lastStatus = status
                // Completion belongs to the whole sequence, not an intermediate order.
                if (status != DrinkingStatus.FINISHED) onStatus(status)
            }) {
                fetchStatus(step, orderNumber)
            }
            if (!finished) return DrinkingSequenceResult.TimedOut(lastStatus)
        }
        return DrinkingSequenceResult.Finished
    }
}
