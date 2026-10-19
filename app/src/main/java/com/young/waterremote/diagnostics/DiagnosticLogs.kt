package com.young.waterremote.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.young.waterremote.BuildConfig
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Private app storage; no request credentials or encrypted payloads are written. */
object DiagnosticLogs {
    private val worker = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "diagnostic-logs").apply { isDaemon = true }
    }
    private val dispatcher = worker.asCoroutineDispatcher()
    private val gson = Gson()
    private val processId = UUID.randomUUID().toString().take(8)
    @Volatile private var store: DiagnosticLogStore? = null
    private val metadata: String
        get() = "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); " +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); " +
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}"

    @Synchronized
    fun initialize(context: Context) {
        if (store != null) return
        store = DiagnosticLogStore(File(context.applicationContext.filesDir, "diagnostic-logs"))
        worker.scheduleWithFixedDelay({ safely { store?.prune() } }, 0, 1, TimeUnit.HOURS)
        record("app.started", mapOf("environment" to metadata))
    }

    fun record(event: String, details: Map<String, Any?> = emptyMap()) {
        val activeStore = store ?: return
        val timestamp = System.currentTimeMillis()
        worker.execute {
            safely {
                val entry = JsonObject().apply {
                    addProperty("timestamp", Instant.ofEpochMilli(timestamp).toString())
                    addProperty("timestampMs", timestamp)
                    addProperty("processId", processId)
                    addProperty("event", event)
                    add("details", DiagnosticRedactor.sanitize(gson.toJsonTree(details)))
                }
                activeStore.append(entry)
            }
        }
    }

    fun failure(event: String, error: Throwable, details: Map<String, Any?> = emptyMap()) {
        record(event, details + mapOf(
            "exception" to error.javaClass.simpleName,
            "message" to error.message,
            "cause" to error.cause?.javaClass?.simpleName,
            "stack" to error.stackTrace.take(12).joinToString("\n")
        ))
    }

    fun deviceFingerprint(deviceKey: String): String = DiagnosticRedactor.deviceFingerprint(deviceKey)

    suspend fun export(output: OutputStream) = withContext(dispatcher) {
        val activeStore = checkNotNull(store) { "Diagnostic logs are not initialized" }
        activeStore.export(output, DiagnosticRedactor.sanitizeText(metadata))
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: Exception) {
            // A full disk or failed cleanup must never interrupt a water command.
            Log.w("DiagnosticLogs", "Diagnostic storage failed: ${error.javaClass.simpleName}")
        }
    }
}
