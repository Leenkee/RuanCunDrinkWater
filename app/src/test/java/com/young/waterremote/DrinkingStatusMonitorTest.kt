package com.young.waterremote

import com.young.waterremote.data.DrinkingStatus
import com.young.waterremote.data.RemoteApiException
import com.young.waterremote.data.SessionInvalidException
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
class DrinkingStatusMonitorTest {
    @Test
    fun pollsEverySecondAndStopsWhenFinished() = runTest {
        val queryTimes = mutableListOf<Long>()
        val completion = async {
            DrinkingStatusMonitor.awaitCompletion {
                queryTimes.add(currentTime)
                if (queryTimes.size == 3) DrinkingStatus.FINISHED else DrinkingStatus.IN_PROGRESS
            }
        }
        runCurrent()

        advanceTimeBy(999)
        runCurrent()
        assertTrue(queryTimes.isEmpty())
        assertFalse(completion.isCompleted)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(1_000L), queryTimes)
        assertFalse(completion.isCompleted)

        advanceUntilIdle()
        assertTrue(completion.await())
        assertEquals(listOf(1_000L, 2_000L, 3_000L), queryTimes)

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(3, queryTimes.size)
    }

    @Test
    fun transientFailureRetriesAfterOneSecond() = runTest {
        val queryTimes = mutableListOf<Long>()

        val finished = DrinkingStatusMonitor.awaitCompletion {
            queryTimes.add(currentTime)
            if (queryTimes.size == 1) throw RemoteApiException("网络暂时不可用")
            DrinkingStatus.FINISHED
        }

        assertTrue(finished)
        assertEquals(listOf(1_000L, 2_000L), queryTimes)
    }

    @Test
    fun unfinishedOrderReachesLimitWithoutReportingCompletion() = runTest {
        var attempts = 0

        val finished = DrinkingStatusMonitor.awaitCompletion {
            attempts += 1
            DrinkingStatus.IN_PROGRESS
        }

        assertFalse(finished)
        assertEquals(120, attempts)
        assertEquals(120_000L, currentTime)
    }

    @Test
    fun persistentNetworkFailureDoesNotReportCompletion() = runTest {
        var attempts = 0

        val finished = DrinkingStatusMonitor.awaitCompletion {
            attempts += 1
            throw RemoteApiException("无法连接饮水机")
        }

        assertFalse(finished)
        assertEquals(120, attempts)
    }

    @Test
    fun invalidSessionStopsPollingImmediately() = runTest {
        val invalidSession = SessionInvalidException()
        var attempts = 0

        try {
            DrinkingStatusMonitor.awaitCompletion {
                attempts += 1
                throw invalidSession
            }
            fail("Expected an invalid session error")
        } catch (error: SessionInvalidException) {
            assertSame(invalidSession, error)
        }

        assertEquals(1, attempts)
        assertEquals(1_000L, currentTime)
    }

    @Test
    fun cancellationStopsFurtherQueries() = runTest {
        var attempts = 0
        val job = launch {
            DrinkingStatusMonitor.awaitCompletion {
                attempts += 1
                DrinkingStatus.IN_PROGRESS
            }
        }
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, attempts)

        job.cancel()
        job.join()
        advanceTimeBy(5_000)
        runCurrent()

        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
    }
}
