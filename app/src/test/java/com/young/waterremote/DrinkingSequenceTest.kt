package com.young.waterremote

import com.young.waterremote.data.CommandReceipt
import com.young.waterremote.data.DrinkingStatus
import com.young.waterremote.data.RemoteApiException
import com.young.waterremote.data.SessionInvalidException
import com.young.waterremote.data.WaterType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DrinkingSequenceTest {
    private val cold = DrinkingStep("cold-device", WaterType.COLD)
    private val hot = DrinkingStep("hot-device", WaterType.HOT)

    @Test
    fun waitsForColdCompletionBeforeSendingHotAndForHotCompletionBeforeFinishing() = runTest {
        val commands = mutableListOf<Pair<DrinkingStep, Long>>()
        val observations = mutableListOf<DrinkingStatus>()
        val queries = mutableListOf<Pair<DrinkingStep, String>>()
        var coldFinished = false
        var hotFinished = false
        val completion = async {
            runSequence(
                onStatus = { observations.add(it) },
                sendCommand = { step ->
                    commands.add(step to currentTime)
                    CommandReceipt("${step.deviceKey}-order")
                },
                fetchStatus = { step, order ->
                    queries.add(step to order)
                    when {
                        step == cold && coldFinished -> DrinkingStatus.FINISHED
                        step == hot && hotFinished -> DrinkingStatus.FINISHED
                        else -> DrinkingStatus.IN_PROGRESS
                    }
                }
            )
        }
        runCurrent()
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(listOf(cold to 0L), commands)
        assertFalse(completion.isCompleted)

        coldFinished = true
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf(cold to 0L, hot to 4_000L), commands)
        assertFalse(completion.isCompleted)

        advanceTimeBy(2_000)
        runCurrent()
        assertFalse(completion.isCompleted)
        hotFinished = true
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(DrinkingSequenceResult.Finished, completion.await())
        assertEquals(setOf(cold to "cold-device-order", hot to "hot-device-order"), queries.toSet())
        assertEquals(setOf(DrinkingStatus.IN_PROGRESS), observations.toSet())
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(2, commands.size)
    }

    @Test
    fun missingColdOrderNeverStartsHotOrPolling() = runTest {
        for (orderNumber in listOf(null, "", "   ")) {
            val commands = mutableListOf<DrinkingStep>()
            val result = runSequence(
                sendCommand = { step ->
                    commands.add(step)
                    CommandReceipt(orderNumber)
                },
                fetchStatus = { _, _ -> error("Cannot poll without an order") }
            )

            assertEquals(DrinkingSequenceResult.MissingOrderNumber, result)
            assertEquals(listOf(cold), commands)
        }
    }

    @Test
    fun coldTimeoutNeverStartsHot() = runTest {
        for (status in listOf(DrinkingStatus.WAITING_FOR_DEVICE, DrinkingStatus.IN_PROGRESS)) {
            val commands = mutableListOf<DrinkingStep>()
            val result = runSequence(
                sendCommand = { step -> commands.add(step); CommandReceipt("cold-order") },
                fetchStatus = { _, _ -> status }
            )

            assertEquals(DrinkingSequenceResult.TimedOut(status), result)
            assertEquals(listOf(cold), commands)
        }
    }

    @Test
    fun persistentColdStatusFailureNeverStartsHot() = runTest {
        val commands = mutableListOf<DrinkingStep>()
        val result = runSequence(
            sendCommand = { step -> commands.add(step); CommandReceipt("cold-order") },
            fetchStatus = { _, _ -> throw RemoteApiException("网络不可用") }
        )

        assertEquals(DrinkingSequenceResult.TimedOut(null), result)
        assertEquals(listOf(cold), commands)
    }

    @Test
    fun transientColdStatusFailureCanRecoverBeforeStartingHot() = runTest {
        val commands = mutableListOf<Pair<DrinkingStep, Long>>()
        var attempts = 0
        val result = runSequence(
            sendCommand = { step -> commands.add(step to currentTime); CommandReceipt(step.deviceKey) },
            fetchStatus = { _, _ ->
                attempts += 1
                if (attempts == 1) throw RemoteApiException("网络暂时不可用")
                DrinkingStatus.FINISHED
            }
        )

        assertEquals(DrinkingSequenceResult.Finished, result)
        assertEquals(listOf(cold to 0L, hot to 2_000L), commands)
    }

    @Test
    fun commandFailureStopsTheSequenceWithoutRetries() = runTest {
        for (failingStep in listOf(cold, hot)) {
            val commands = mutableListOf<DrinkingStep>()
            val failure = RemoteApiException("指令发送失败")
            try {
                runSequence(sendCommand = { step ->
                    commands.add(step)
                    if (step == failingStep) throw failure
                    CommandReceipt(step.deviceKey)
                })
                fail("Expected the command error")
            } catch (error: RemoteApiException) {
                assertSame(failure, error)
            }

            assertEquals(if (failingStep == cold) listOf(cold) else listOf(cold, hot), commands)
        }
    }

    @Test
    fun missingHotOrderDoesNotReportCompletion() = runTest {
        val queries = mutableListOf<DrinkingStep>()
        val result = runSequence(
            sendCommand = { step -> CommandReceipt(if (step == cold) "cold-order" else null) },
            fetchStatus = { step, _ -> queries.add(step); DrinkingStatus.FINISHED }
        )

        assertEquals(DrinkingSequenceResult.MissingOrderNumber, result)
        assertEquals(listOf(cold), queries)
    }

    @Test
    fun hotTimeoutDoesNotReportCompletion() = runTest {
        val result = runSequence(fetchStatus = { step, _ ->
            if (step == cold) DrinkingStatus.FINISHED else DrinkingStatus.IN_PROGRESS
        })

        assertEquals(DrinkingSequenceResult.TimedOut(DrinkingStatus.IN_PROGRESS), result)
    }

    @Test
    fun invalidSessionDuringColdStopsBeforeHot() = runTest {
        val commands = mutableListOf<DrinkingStep>()
        val failure = SessionInvalidException()
        try {
            runSequence(
                sendCommand = { step -> commands.add(step); CommandReceipt(step.deviceKey) },
                fetchStatus = { _, _ -> throw failure }
            )
            fail("Expected an invalid session error")
        } catch (error: SessionInvalidException) {
            assertSame(failure, error)
        }

        assertEquals(listOf(cold), commands)
    }

    @Test
    fun cancellationWhileColdIsRunningNeverStartsHot() = runTest {
        val commands = mutableListOf<DrinkingStep>()
        val job = launch {
            runSequence(
                sendCommand = { step -> commands.add(step); CommandReceipt(step.deviceKey) },
                fetchStatus = { _, _ -> DrinkingStatus.IN_PROGRESS }
            )
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        job.cancel()
        job.join()
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(listOf(cold), commands)
    }

    @Test
    fun cancellationAtColdCompletionNeverStartsHot() = runTest {
        val commands = mutableListOf<DrinkingStep>()
        val job = launch {
            runSequence(
                sendCommand = { step -> commands.add(step); CommandReceipt(step.deviceKey) },
                fetchStatus = { _, _ ->
                    coroutineContext[kotlinx.coroutines.Job]!!.cancel()
                    DrinkingStatus.FINISHED
                }
            )
        }
        advanceUntilIdle()

        assertTrue(job.isCancelled)
        assertEquals(listOf(cold), commands)
    }

    @Test
    fun singleHotColdAndTemporaryRequestsStillComplete() = runTest {
        for (step in listOf(cold, hot, DrinkingStep("temporary-device", null))) {
            val commands = mutableListOf<DrinkingStep>()
            val result = runSequence(
                steps = listOf(step),
                sendCommand = { sent -> commands.add(sent); CommandReceipt(sent.deviceKey) }
            )

            assertEquals(DrinkingSequenceResult.Finished, result)
            assertEquals(listOf(step), commands)
        }
    }

    private suspend fun runSequence(
        steps: List<DrinkingStep> = listOf(cold, hot),
        onStatus: (DrinkingStatus) -> Unit = {},
        sendCommand: suspend (DrinkingStep) -> CommandReceipt = { CommandReceipt(it.deviceKey) },
        fetchStatus: suspend (DrinkingStep, String) -> DrinkingStatus = { _, _ -> DrinkingStatus.FINISHED }
    ) = DrinkingSequence.run(
        steps = steps,
        onSending = {},
        onSubmitted = { _, _ -> },
        onStatus = onStatus,
        sendCommand = sendCommand,
        fetchStatus = fetchStatus
    )
}
