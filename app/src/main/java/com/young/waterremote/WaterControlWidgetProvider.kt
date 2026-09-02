package com.young.waterremote

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

enum class WidgetAction {
    HOT,
    COLD,
    TEMPORARY_SCAN
}

data class WidgetActionRequest(
    val id: Long,
    val action: WidgetAction
)

class WaterControlWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_water_controls).apply {
                setOnClickPendingIntent(
                    R.id.widget_hot_water,
                    actionPendingIntent(context, WidgetAction.HOT)
                )
                setOnClickPendingIntent(
                    R.id.widget_cold_water,
                    actionPendingIntent(context, WidgetAction.COLD)
                )
                setOnClickPendingIntent(
                    R.id.widget_temporary_scan,
                    actionPendingIntent(context, WidgetAction.TEMPORARY_SCAN)
                )
            }
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun actionPendingIntent(context: Context, action: WidgetAction): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = ACTION_WIDGET_CONTROL
            putExtra(EXTRA_WIDGET_ACTION, action.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            action.ordinal + 1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

internal fun Intent?.widgetActionOrNull(): WidgetAction? {
    if (this?.action != ACTION_WIDGET_CONTROL) return null
    val value = getStringExtra(EXTRA_WIDGET_ACTION) ?: return null
    return WidgetAction.entries.firstOrNull { it.name == value }
}

private const val ACTION_WIDGET_CONTROL =
    "com.young.waterremote.action.WIDGET_CONTROL"
private const val EXTRA_WIDGET_ACTION =
    "com.young.waterremote.extra.WIDGET_ACTION"
