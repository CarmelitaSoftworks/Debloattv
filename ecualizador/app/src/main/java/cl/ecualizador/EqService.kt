package cl.ecualizador

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Mantiene vivo el proceso para que el ecualizador siga actuando con la app cerrada
 * (los efectos de audio desaparecen si Android cierra la app).
 */
class EqService : Service() {
    companion object {
        private const val CHANNEL = "equalizer"
        private const val ACTION_STOP = "cl.controltv.EQ_STOP"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, EqService::class.java)) }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, EqService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            EqEngine.get(this).setEnabled(false)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ecualizador", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EqService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val profile = EqEngine.get(this).state.value.profileName
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Ecualizador activo")
            .setContentText("Perfil: $profile")
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Desactivar", stop).build())
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
        EqEngine.get(this) // asegura que el efecto exista
        return START_STICKY
    }
}
