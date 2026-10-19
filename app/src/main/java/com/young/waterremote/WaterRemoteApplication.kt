package com.young.waterremote

import android.app.Application
import com.young.waterremote.diagnostics.DiagnosticLogs

class WaterRemoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DiagnosticLogs.initialize(this)
    }
}
