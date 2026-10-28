package cl.ecualizador

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow

/** Guarda las opciones del visor; las comparten la app y el widget. */
object SpectrumPrefs {
    val settings = MutableStateFlow(SpectrumSettings())
    private var loaded = false

    fun load(ctx: Context): SpectrumSettings {
        if (!loaded) {
            WinampSkins.load(ctx)
            WinampWindows.init(ctx)
            val p = ctx.getSharedPreferences("spectrum", Context.MODE_PRIVATE)
            val d = SpectrumSettings()
            settings.value = SpectrumSettings(
                style = runCatching { SpectrumStyle.valueOf(p.getString("style", d.style.name)!!) }.getOrDefault(d.style),
                palette = runCatching { SpectrumPalette.valueOf(p.getString("palette", d.palette.name)!!) }.getOrDefault(d.palette),
                bars = p.getInt("bars", d.bars).takeIf { it in SpectrumSettings.BAR_OPTIONS } ?: d.bars,
                sensitivity = p.getFloat("sensitivity", d.sensitivity),
                fall = runCatching { FallSpeed.valueOf(p.getString("fall", d.fall.name)!!) }.getOrDefault(d.fall),
                peaks = p.getBoolean("peaks", d.peaks),
                grid = p.getBoolean("grid", d.grid),
                widgetFps = p.getInt("widgetFps", d.widgetFps).takeIf { it in SpectrumSettings.FPS_OPTIONS } ?: d.widgetFps,
                winampSkin = WinampSkins.byName(p.getString("winampSkin", d.winampSkin)).name,
                winampPattern = runCatching { WinampPattern.valueOf(p.getString("winampPattern", d.winampPattern.name)!!) }
                    .getOrDefault(d.winampPattern),
                winampFrame = p.getBoolean("winampFrame", d.winampFrame),
                appTheme = p.getBoolean("appTheme", d.appTheme),
                winampBars = p.getInt("winampBars", d.winampBars).takeIf { it in SpectrumSettings.WINAMP_BAR_OPTIONS } ?: d.winampBars,
                winampLines = p.getInt("winampLines", d.winampLines).takeIf { it in SpectrumSettings.WINAMP_LINE_OPTIONS } ?: d.winampLines,
            )
            loaded = true
        }
        return settings.value
    }

    fun update(ctx: Context, change: (SpectrumSettings) -> SpectrumSettings) {
        val s = change(load(ctx))
        settings.value = s
        ctx.getSharedPreferences("spectrum", Context.MODE_PRIVATE).edit()
            .putString("style", s.style.name).putString("palette", s.palette.name)
            .putInt("bars", s.bars).putFloat("sensitivity", s.sensitivity)
            .putString("fall", s.fall.name).putBoolean("peaks", s.peaks).putBoolean("grid", s.grid)
            .putInt("widgetFps", s.widgetFps).putString("winampSkin", s.winampSkin)
            .putString("winampPattern", s.winampPattern.name).putBoolean("winampFrame", s.winampFrame)
            .putBoolean("appTheme", s.appTheme)
            .putInt("winampBars", s.winampBars).putInt("winampLines", s.winampLines)
            .apply()
    }
}
