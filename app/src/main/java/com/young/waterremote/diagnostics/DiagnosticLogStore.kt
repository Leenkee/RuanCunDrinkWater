package com.young.waterremote.diagnostics

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/** All access is serialized by DiagnosticLogs, including export and retention cleanup. */
internal class DiagnosticLogStore(
    private val directory: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    fun append(entry: JsonObject) {
        val now = clock()
        prune(now)
        val timestamp = entry.get("timestampMs").asLong
        if (timestamp < now - RETENTION_MS) return
        checkDirectory()
        val date = Instant.ofEpochMilli(timestamp).atOffset(ZoneOffset.UTC).toLocalDate()
        val file = File(directory, "$date.jsonl")
        RandomAccessFile(file, "rw").use { log ->
            val length = log.length()
            // Preserve the next complete event after an interrupted append.
            if (length > 0) {
                log.seek(length - 1)
                if (log.read() != '\n'.code) log.write('\n'.code)
            }
            log.seek(log.length())
            log.write("$entry\n".toByteArray(Charsets.UTF_8))
        }
    }

    fun prune(now: Long = clock()) {
        directory.listFiles().orEmpty()
            .filter { it.name.endsWith(".tmp") }
            .forEach { if (!it.delete()) throw IOException("Cannot delete incomplete diagnostic log") }
        val cutoff = now - RETENTION_MS
        val oldestDate = Instant.ofEpochMilli(cutoff).atOffset(ZoneOffset.UTC).toLocalDate().toString()
        logFiles().forEach { file ->
            when {
                file.nameWithoutExtension < oldestDate -> {
                    if (!file.delete()) throw IOException("Cannot delete expired diagnostic log")
                }
                file.nameWithoutExtension == oldestDate -> trimBoundaryFile(file, cutoff)
            }
        }
    }

    fun export(output: OutputStream, metadata: String) {
        val now = clock()
        prune(now)
        val writer = output.bufferedWriter(Charsets.UTF_8)
        writer.appendLine("Ruan Cun Drinking Water diagnostic logs")
        writer.appendLine(metadata)
        writer.appendLine("Exported at: ${Instant.ofEpochMilli(now)}; retention: 7 days")
        writer.appendLine()
        logFiles().forEach { file ->
            file.useLines(Charsets.UTF_8) { lines ->
                lines.filter { isRetained(it, now - RETENTION_MS) }.forEach(writer::appendLine)
            }
        }
        writer.flush()
    }

    private fun trimBoundaryFile(file: File, cutoff: Long) {
        val needsTrimming = file.useLines(Charsets.UTF_8) { lines ->
            lines.any { !isRetained(it, cutoff) }
        }
        if (!needsTrimming) return
        val temporary = File(directory, "${file.name}.tmp")
        try {
            temporary.bufferedWriter(Charsets.UTF_8).use { writer ->
                file.useLines(Charsets.UTF_8) { lines ->
                    lines.filter { isRetained(it, cutoff) }.forEach(writer::appendLine)
                }
            }
            Files.move(
                temporary.toPath(), file.toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
        }
    }

    private fun isRetained(line: String, cutoff: Long): Boolean = runCatching {
        JsonParser.parseString(line).asJsonObject.get("timestampMs").asLong >= cutoff
    }.getOrDefault(false)

    private fun logFiles(): List<File> = directory.listFiles()
        .orEmpty()
        .filter { it.isFile && it.name.matches(LOG_FILE_NAME) }
        .sortedBy { it.name }

    private fun checkDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Cannot create diagnostic log directory")
        }
    }

    companion object {
        val RETENTION_MS: Long = TimeUnit.DAYS.toMillis(7)
        private val LOG_FILE_NAME = Regex("\\d{4}-\\d{2}-\\d{2}\\.jsonl")
    }
}
