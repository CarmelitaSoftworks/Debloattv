package cl.ecualizador

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Anima el widget «Espectro»: analiza el audio y le envía una imagen nueva varias veces por
 * segundo (Android no permite animar widgets de otra forma). Se pausa con la pantalla apagada.
 */
class SpectrumWidgetService : Service() {
    companion object {
        const val ACTION_TOGGLE = "cl.ecualizador.SPECTRUM_WIDGET_TOGGLE"
        const val ACTION_STOP = "cl.ecualizador.SPECTRUM_WIDGET_STOP"
        private const val CHANNEL = "spectrum_widget"
        @Volatile var running = false
            private set

        /** Arranca la animación desde la app (estando en primer plano siempre se permite). */
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, SpectrumWidgetService::class.java)) }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private val renderer = SpectrumRenderer()
    private var smoother: SpectrumSmoother? = null
    @Volatile private var screenOn = true

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOn = intent.action == Intent.ACTION_SCREEN_ON
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val stopping = intent?.action == ACTION_STOP || (intent?.action == ACTION_TOGGLE && running)
        val hasMic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        // Android 14+ lanza SecurityException si se pide el tipo micrófono sin permiso o desde segundo
        // plano sin exención: en esos casos se arranca con el tipo «uso especial» solo para avisar y salir.
        val micOk = !stopping && hasMic && startForegroundCompat(microphone = true)
        if (!micOk) startForegroundCompat(microphone = false)
        if (stopping) {
            stopAll("Espectro\nToca para ver en vivo")
            return START_NOT_STICKY
        }
        if (!hasMic) {
            stopAll("Abre Ecualizador y activa\n«Espectro en tiempo real»\npara dar el permiso")
            return START_NOT_STICKY
        }
        if (!micOk) {
            stopAll("Abre Ecualizador y toca\n«Animar el widget»")
            return START_NOT_STICKY
        }
        Spectrum.acquire("widget", this)?.let { error ->
            stopAll(error)
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            running = true
            screenOn = getSystemService(PowerManager::class.java).isInteractive
            registerReceiver(screenReceiver, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            })
            loop = scope.launch { animate() }
        }
        return START_STICKY
    }

    /** Devuelve false si Android no permitió el servicio con ese tipo. */
    private fun startForegroundCompat(microphone: Boolean): Boolean {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Widget de espectro", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(
            this, 2, Intent(this, SpectrumWidgetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Widget de espectro en vivo")
            .setContentText("Se pausa con la pantalla apagada")
            .addAction(Notification.Action.Builder(null, "Detener", stop).build())
            .setOngoing(true)
            .build()
        return try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> startForeground(
                    2, notification,
                    if (microphone) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && microphone ->
                    startForeground(2, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                else -> startForeground(2, notification)
            }
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private suspend fun animate() {
        val manager = AppWidgetManager.getInstance(this)
        var last = System.currentTimeMillis()
        while (scope.isActive) {
            val settings = SpectrumPrefs.load(this)
            val frameMs = 1000L / settings.widgetFps
            val ids = SpectrumWidget.ids(this)
            if (ids.isEmpty()) {
                stopAll(null)
                return
            }
            if (screenOn) {
                val now = System.currentTimeMillis()
                val s = smoother?.takeIf { it.size == settings.effectiveBars } ?: SpectrumSmoother(settings.effectiveBars).also { smoother = it }
                s.fallPerSec = settings.fall.perSec * 1.5f // a pocos fps conviene caer un poco más rápido
                s.step(SpectrumShape.resample(Spectrum.bars.value, settings.effectiveBars), now - last)
                last = now
                ids.forEach { id -> runCatching { manager.updateAppWidget(id, frame(manager, id, s, settings)) } }
            } else {
                last = System.currentTimeMillis()
            }
            delay(frameMs)
        }
    }

    /** Imagen del widget a su tamaño (limitada para no saturar la comunicación con el launcher). */
    private fun frame(manager: AppWidgetManager, id: Int, s: SpectrumSmoother, settings: SpectrumSettings): RemoteViews {
        val opts = manager.getAppWidgetOptions(id)
        val density = resources.displayMetrics.density
        val wDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250).coerceAtLeast(60)
        val hDp = opts.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 100).coerceAtLeast(30)
        val scale = minOf(1f, 420f / (wDp * density))
        val w = (wDp * density * scale).toInt().coerceAtLeast(40)
        val h = (hDp * density * scale).toInt().coerceAtLeast(20)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bmp), w.toFloat(), h.toFloat(), s.values, s.peaks, settings, density * scale, Spectrum.wave.value)
        return RemoteViews(packageName, R.layout.widget_spectrum).apply {
            setImageViewBitmap(R.id.spectrum, bmp)
            setTextViewText(R.id.status, "")
            setOnClickPendingIntent(R.id.widget_root, SpectrumWidget.toggleIntent(this@SpectrumWidgetService))
        }
    }

    private fun stopAll(message: String?) {
        running = false
        loop?.cancel()
        loop = null
        runCatching { unregisterReceiver(screenReceiver) }
        Spectrum.release("widget")
        message?.let { SpectrumWidget.showMessage(this, it) }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (running) stopAll("Espectro\nToca para ver en vivo")
        scope.cancel()
        super.onDestroy()
    }
}
