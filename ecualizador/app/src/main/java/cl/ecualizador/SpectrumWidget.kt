package cl.ecualizador

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

/** Widget «Espectro»: toca para encenderlo o apagarlo; lo anima [SpectrumWidgetService]. */
class SpectrumWidget : AppWidgetProvider() {
    companion object {
        fun ids(ctx: Context): IntArray =
            AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, SpectrumWidget::class.java))

        fun hasWidgets(ctx: Context) = ids(ctx).isNotEmpty()

        /** Al tocar el widget se enciende o se apaga el servicio que lo anima. */
        fun toggleIntent(ctx: Context): PendingIntent = PendingIntent.getForegroundService(
            ctx, 10, Intent(ctx, SpectrumWidgetService::class.java).setAction(SpectrumWidgetService.ACTION_TOGGLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        /** Vista quieta con un mensaje (apagado, sin permiso, error). */
        fun showMessage(ctx: Context, text: String) {
            val views = RemoteViews(ctx.packageName, R.layout.widget_spectrum).apply {
                setImageViewBitmap(R.id.spectrum, null)
                setTextViewText(R.id.status, text)
                setOnClickPendingIntent(R.id.widget_root, toggleIntent(ctx))
            }
            ids(ctx).forEach { AppWidgetManager.getInstance(ctx).updateAppWidget(it, views) }
        }
    }

    override fun onUpdate(ctx: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        if (!SpectrumWidgetService.running) showMessage(ctx, "Espectro\nToca para ver en vivo")
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) {
        if (!SpectrumWidgetService.running) showMessage(ctx, "Espectro\nToca para ver en vivo")
    }

    override fun onDisabled(ctx: Context) {
        ctx.stopService(Intent(ctx, SpectrumWidgetService::class.java))
    }
}
