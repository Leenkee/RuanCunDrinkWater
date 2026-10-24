package com.young.waterremote

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import com.young.waterremote.ui.WaterRemoteApp
import com.young.waterremote.ui.theme.WaterRemoteTheme
import com.young.waterremote.updates.AutoUpdateScheduler

class MainActivity : ComponentActivity() {
    private lateinit var viewModel: MainViewModel
    private var widgetRequestSequence = 0L
    private var widgetActionRequest by mutableStateOf<WidgetActionRequest?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        acceptWidgetAction(intent)

        viewModel = ViewModelProvider(
            this,
            MainViewModelFactory(applicationContext)
        )[MainViewModel::class.java]

        setContent {
            WaterRemoteTheme {
                WaterRemoteApp(
                    viewModel = viewModel,
                    widgetActionRequest = widgetActionRequest,
                    onWidgetActionConsumed = ::consumeWidgetAction
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptWidgetAction(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.reconcileDrinkingReturn()
        AutoUpdateScheduler(this).reconcile()
    }

    private fun acceptWidgetAction(intent: Intent?) {
        val action = intent.widgetActionOrNull() ?: return
        widgetRequestSequence += 1
        widgetActionRequest = WidgetActionRequest(widgetRequestSequence, action)
    }

    private fun consumeWidgetAction(requestId: Long) {
        if (widgetActionRequest?.id != requestId) return
        widgetActionRequest = null
        setIntent(Intent(this, MainActivity::class.java))
    }
}
