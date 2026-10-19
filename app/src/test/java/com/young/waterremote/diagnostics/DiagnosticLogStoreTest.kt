package com.young.waterremote.diagnostics

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.time.Instant

class DiagnosticLogStoreTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private var now = Instant.parse("2026-10-12T12:00:00Z").toEpochMilli()

    @Test
    fun appendDeletesOldFilesAndTrimsOnlyExpiredEntriesAtTheSevenDayBoundary() {
        val directory = temporaryFolder.newFolder()
        val store = DiagnosticLogStore(directory) { now }
        store.append(entry(now - 86_400_000, "old-day"))
        store.append(entry(now, "expired-within-day"))
        now += 60_000
        store.append(entry(now, "exact-boundary"))
        now += DiagnosticLogStore.RETENTION_MS

        store.append(entry(now, "new-entry"))

        assertFalse(directory.resolve("2026-10-11.jsonl").exists())
        val oldest = directory.resolve("2026-10-12.jsonl").readText()
        assertFalse(oldest.contains("expired-within-day"))
        assertTrue(oldest.contains("exact-boundary"))
        assertEquals(2, directory.listFiles()!!.size)
    }

    @Test
    fun exportPrunesExpiredLogsWithoutRequiringAnotherWriteAndKeepsChronologicalOrder() {
        val directory = temporaryFolder.newFolder()
        val store = DiagnosticLogStore(directory) { now }
        store.append(entry(now, "expired-entry"))
        now += 86_400_000
        store.append(entry(now, "retained-first"))
        now += 86_400_000
        store.append(entry(now, "retained-last"))
        now += DiagnosticLogStore.RETENTION_MS - 86_400_000
        val output = ByteArrayOutputStream()

        store.export(output, "App: test; Android: test")

        val exported = output.toString("UTF-8")
        assertFalse(exported.contains("expired-entry"))
        assertTrue(exported.contains("App: test"))
        assertTrue(exported.indexOf("retained-first") < exported.indexOf("retained-last"))
        assertFalse(directory.resolve("2026-10-12.jsonl").exists())
    }

    @Test
    fun pruningRecoversFromAnInterruptedFinalRecordAndPreservesUnicode() {
        val directory = temporaryFolder.newFolder()
        val store = DiagnosticLogStore(directory) { now }
        store.append(entry(now, "接水响应"))
        directory.resolve("2026-10-12.jsonl").appendText("{truncated\n")
        now += DiagnosticLogStore.RETENTION_MS
        val output = ByteArrayOutputStream()

        store.export(output, "")

        assertTrue(output.toString("UTF-8").contains("接水响应"))
        assertFalse(directory.resolve("2026-10-12.jsonl").readText().contains("truncated"))
    }

    @Test
    fun delayedEntriesOlderThanTheRetentionWindowAreNotWritten() {
        val directory = temporaryFolder.newFolder()
        val store = DiagnosticLogStore(directory) { now }

        store.append(entry(now - DiagnosticLogStore.RETENTION_MS - 1, "expired"))

        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test
    fun newRecordsSurviveAnInterruptedAppendAndOrphanedCleanupIsRemoved() {
        val directory = temporaryFolder.newFolder()
        val store = DiagnosticLogStore(directory) { now }
        directory.resolve("2026-10-12.jsonl").writeText("{truncated")
        directory.resolve("2026-10-05.jsonl.tmp").writeText("old-cleanup-record")

        store.append(entry(now, "complete-record"))
        val output = ByteArrayOutputStream()
        store.export(output, "")

        assertTrue(output.toString("UTF-8").contains("complete-record"))
        assertFalse(output.toString("UTF-8").contains("truncated"))
        assertFalse(directory.resolve("2026-10-05.jsonl.tmp").exists())
    }

    private fun entry(timestamp: Long, event: String) = JsonObject().apply {
        addProperty("timestampMs", timestamp)
        addProperty("event", event)
    }
}
