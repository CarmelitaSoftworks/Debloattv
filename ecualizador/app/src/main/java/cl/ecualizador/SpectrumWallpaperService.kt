package cl.ecualizador

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder

/**
 * Fondo de pantalla animado: tu foto con el espectro encima, dibujado en cada fotograma de la
 * pantalla (60 fps o más). Es la forma en que Android permite animación continua en el inicio.
 * Solo trabaja mientras el fondo está visible.
 */
class SpectrumWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = SpectrumEngine()

    inner class SpectrumEngine : Engine(), Choreographer.FrameCallback {
        private val renderer = SpectrumRenderer()
        /** Cada motor (inicio, bloqueo, vista previa) se registra aparte para no apagar a los demás. */
        private val spectrumUser = "wallpaper@${System.identityHashCode(this)}"
        private var smoother: SpectrumSmoother? = null
        private var visible = false
        private var width = 0
        private var height = 0
        private var lastFrameNanos = 0L
        private var background: Bitmap? = null
        private var backgroundVersion = -1L
        private var error: String? = null
        private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val dimPaint = Paint()
        private var gradientShader: Shader? = null
        private var gradientHeight = -1
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            // Algunos teléfonos crean el fondo en 16 bits (RGB 565): los degradados de la foto se ven con escalones.
            surfaceHolder.setFormat(PixelFormat.RGBA_8888)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, w: Int, h: Int) {
            width = w; height = h
            backgroundVersion = -1 // reescalar la foto al nuevo tamaño
            drawFrame()
        }

        override fun onVisibilityChanged(isVisible: Boolean) {
            visible = isVisible
            if (isVisible) {
                error = if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    "Abre Ecualizador y activa «Espectro en tiempo real» para dar el permiso"
                } else {
                    Spectrum.acquire(spectrumUser, this@SpectrumWallpaperService)
                }
                lastFrameNanos = 0
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                Choreographer.getInstance().removeFrameCallback(this)
                Spectrum.release(spectrumUser)
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            Choreographer.getInstance().removeFrameCallback(this)
            Spectrum.release(spectrumUser)
        }

        override fun onDestroy() {
            Choreographer.getInstance().removeFrameCallback(this)
            Spectrum.release(spectrumUser)
            background?.recycle()
        }

        override fun doFrame(frameTimeNanos: Long) {
            if (!visible) return
            val settings = SpectrumPrefs.load(this@SpectrumWallpaperService)
            val dt = if (lastFrameNanos == 0L) 16L else (frameTimeNanos - lastFrameNanos) / 1_000_000
            lastFrameNanos = frameTimeNanos
            val s = smoother?.takeIf { it.size == settings.effectiveBars } ?: SpectrumSmoother(settings.effectiveBars).also { smoother = it }
            s.fallPerSec = settings.fall.perSec
            s.step(SpectrumShape.resample(Spectrum.bars.value, settings.effectiveBars), dt)
            drawFrame()
            Choreographer.getInstance().postFrameCallback(this)
        }

        private fun loadBackground(ws: WallpaperSettings) {
            if (ws.imageVersion == backgroundVersion || width == 0) return
            background?.recycle()
            background = null
            backgroundVersion = ws.imageVersion
            val file = WallpaperPrefs.imageFile(this@SpectrumWallpaperService)
            if (!ws.hasImage || !file.exists()) return
            // Se decodifica cerca del tamaño de la pantalla y se reduce por pasos (ver WallpaperImage).
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val src = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply {
                inSampleSize = WallpaperImage.sampleSizeFor(bounds.outWidth, bounds.outHeight, width, height)
            }) ?: return
            background = WallpaperImage.cover(src, width, height)
            src.recycle()
        }

        private fun drawFrame() {
            if (width == 0 || height == 0) return
            val holder = surfaceHolder
            val canvas = runCatching { holder.lockHardwareCanvas() }.getOrNull() ?: runCatching { holder.lockCanvas() }.getOrNull() ?: return
            try {
                val ws = WallpaperPrefs.load(this@SpectrumWallpaperService)
                val settings = SpectrumPrefs.load(this@SpectrumWallpaperService)
                loadBackground(ws)
                val bg = background
                if (bg != null) {
                    canvas.drawBitmap(bg, 0f, 0f, bitmapPaint)
                    dimPaint.color = ((ws.dim.coerceIn(0f, 0.7f) * 255).toInt() shl 24)
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
                } else {
                    if (gradientHeight != height) {
                        gradientShader = LinearGradient(0f, 0f, 0f, height.toFloat(), 0xFF1A1033.toInt(), 0xFF0F1115.toInt(), Shader.TileMode.CLAMP)
                        gradientHeight = height
                    }
                    dimPaint.shader = gradientShader
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
                    dimPaint.shader = null
                }
                val density = resources.displayMetrics.density
                val margin = 16 * density
                val h = height * ws.height.coerceIn(0.1f, 0.6f)
                val top = when (ws.position) {
                    WallpaperPosition.TOP -> height * 0.12f
                    WallpaperPosition.CENTER -> (height - h) / 2
                    WallpaperPosition.BOTTOM -> height - h - height * 0.14f // por encima de la barra de apps
                }
                error?.let { msg ->
                    text.textSize = 15 * density
                    canvas.drawText(msg, width / 2f, top + h / 2, text)
                }
                smoother?.let { s ->
                    canvas.save()
                    canvas.translate(margin, top)
                    renderer.draw(canvas, width - 2 * margin, h, s.values, s.peaks, settings.copy(grid = false), density, Spectrum.wave.value)
                    canvas.restore()
                }
            } finally {
                runCatching { holder.unlockCanvasAndPost(canvas) }
            }
        }
    }
}
