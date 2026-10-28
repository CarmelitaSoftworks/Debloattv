package cl.ecualizador

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlin.math.ln
import kotlinx.coroutines.launch

/** Colores de la interfaz: los propios de la app o los del skin de Winamp elegido. */
private val LocalPalette = androidx.compose.runtime.staticCompositionLocalOf { AppTheme.DEFAULT }
private val Bg: Color @Composable get() = Color(LocalPalette.current.bg)
private val Card: Color @Composable get() = Color(LocalPalette.current.card)
private val Track: Color @Composable get() = Color(LocalPalette.current.track)
private val Accent: Color @Composable get() = Color(LocalPalette.current.accent)
private val Warn: Color @Composable get() = Color(LocalPalette.current.warn)
/** Texto principal. */
private val Ink: Color @Composable get() = Color(LocalPalette.current.ink)
/** Texto secundario. */
private val Muted: Color @Composable get() = Color(LocalPalette.current.muted)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpectrumPrefs.load(this)
        setContent {
            val settings by SpectrumPrefs.settings.collectAsState()
            val imported by WinampSkins.imported.collectAsState()
            val palette = remember(settings.appTheme, settings.winampSkin, imported) {
                if (settings.appTheme) AppTheme.from(WinampSkins.byName(settings.winampSkin)) else AppTheme.DEFAULT
            }
            val view = androidx.compose.ui.platform.LocalView.current
            SideEffect {
                androidx.core.view.WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = palette.light
                    isAppearanceLightNavigationBars = palette.light
                }
            }
            CompositionLocalProvider(LocalPalette provides palette) {
                val scheme = if (palette.light) {
                    lightColorScheme(
                        primary = Accent, background = Bg, surface = Card, onBackground = Ink, onSurface = Ink,
                        surfaceVariant = Track, onSurfaceVariant = Muted, outline = Muted, secondaryContainer = Track,
                    )
                } else {
                    darkColorScheme(
                        primary = Accent, background = Bg, surface = Card, onBackground = Ink, onSurface = Ink,
                        surfaceVariant = Track, onSurfaceVariant = Muted, outline = Muted, secondaryContainer = Track,
                    )
                }
                // Interlineado proporcional a la letra: el de Material (24 sp fijo) separa demasiado
                // las líneas de los textos chicos
                val base = Typography()
                val typography = base.copy(
                    bodyLarge = base.bodyLarge.copy(lineHeight = 1.4.em),
                    bodyMedium = base.bodyMedium.copy(lineHeight = 1.4.em),
                    bodySmall = base.bodySmall.copy(lineHeight = 1.4.em),
                    labelLarge = base.labelLarge.copy(lineHeight = 1.3.em),
                )
                MaterialTheme(colorScheme = scheme, typography = typography) {
                    Surface(Modifier.fillMaxSize(), color = Bg, contentColor = Ink) { EqualizerScreen() }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EqualizerScreen() {
    val ctx = LocalContext.current
    val engine = remember { EqEngine.get(ctx) }
    val eq by engine.state.collectAsState()
    val spectrumOn by Spectrum.running.collectAsState()
    val spectrumUsers by Spectrum.activeUsers.collectAsState()
    var spectrumError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    /** Lo que eligió el usuario; la captura se pausa sola con la app en segundo plano. */
    var wantSpectrum by rememberSaveable { mutableStateOf(false) }

    fun granted(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    if (wantSpectrum && granted(Manifest.permission.RECORD_AUDIO)) spectrumError = Spectrum.acquire("app", ctx)
                Lifecycle.Event.ON_STOP -> Spectrum.release("app")
                else -> {}
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            Spectrum.release("app")
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { engine.setEnabled(true) }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        spectrumError = if (ok) Spectrum.acquire("app", ctx) else "Sin el permiso no se puede mostrar el espectro."
        wantSpectrum = ok && spectrumError == null
    }
    val enable: (Boolean) -> Unit = { on ->
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else engine.setEnabled(on)
    }
    val gains = EqCore.finalGains(eq.levels, eq.bass)

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ecualizador", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(if (eq.enabled) "Activo en todo el teléfono" else "Apagado", color = if (eq.enabled) Accent else Muted)
            }
            Switch(checked = eq.enabled, onCheckedChange = enable)
        }

        Row(
            Modifier.clip(RoundedCornerShape(50)).background(Card).padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when {
                    eq.profileKey.startsWith("bt:") -> Icons.Filled.Bluetooth
                    eq.profileKey == "wired" -> Icons.Filled.Headphones
                    else -> Icons.Filled.Speaker
                }, null, tint = Accent, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text("Perfil: ${eq.profileName}", fontSize = 14.sp)
        }

        eq.error?.let { Text(it, color = Color(0xFFEF5350), fontSize = 13.sp) }
        if (eq.enabled && !eq.hasControl) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Otra app de ecualizador tomó el control del audio.", Modifier.weight(1f), fontSize = 13.sp, color = Warn)
                TextButton(onClick = engine::reclaimNow) { Text("Recuperar") }
            }
        }

        DeviceCard(eq.profileKey, engine)

        SpectrumCard(
            running = spectrumOn && "app" in spectrumUsers,
            error = spectrumError,
            onToggle = { on ->
                if (!on) {
                    wantSpectrum = false
                    Spectrum.release("app")
                } else if (granted(Manifest.permission.RECORD_AUDIO)) {
                    spectrumError = Spectrum.acquire("app", ctx)
                    wantSpectrum = spectrumError == null
                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            }
        )

        WallpaperCard()

        // Curva de respuesta del ecualizador
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(12.dp)) {
            Text("Curva del ecualizador", fontSize = 13.sp, color = Muted)
            ResponseGraph(gains)
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (EqCore.PRESETS.keys + eq.customPresets).forEach { name ->
                FilterChip(selected = eq.preset == name, onClick = { engine.usePreset(name) }, label = { Text(name) })
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(vertical = 8.dp, horizontal = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompareButton(enabled = eq.enabled, onHold = engine::setBypass)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = engine::undo, enabled = eq.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Deshacer") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                eq.levels.forEachIndexed { i, level -> BandSlider(level, EqCore.label(i)) { engine.setBand(i, it) } }
            }
            Text(
                "Arrastra o toca una banda · doble toque la deja en 0 dB",
                fontSize = 11.sp, color = Muted, modifier = Modifier.padding(start = 8.dp, top = 4.dp)
            )
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LabeledSlider("Graves", eq.bass, 0f..1f, "+%.0f dB".format(eq.bass * EqCore.BASS_MAX_DB), engine::setBass)
            if (eq.advanced) {
                LabeledSlider("Volumen extra", eq.boost, 0f..EqCore.BOOST_MAX_DB, "+%.1f dB".format(eq.boost), engine::setBoost)
                LabeledSlider(
                    "Balance", eq.balance, -1f..1f,
                    when {
                        eq.balance < -0.02f -> "I %.0f%%".format(-eq.balance * 100)
                        eq.balance > 0.02f -> "D %.0f%%".format(eq.balance * 100)
                        else -> "Centro"
                    }
                ) { v -> engine.setBalance(if (kotlin.math.abs(v) < 0.04f) 0f else v) }
            } else if (eq.engine.isNotEmpty()) {
                Text("Este teléfono solo permite el ecualizador básico: sin volumen extra ni balance.", fontSize = 12.sp, color = Muted)
            }
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Opciones", fontWeight = FontWeight.SemiBold)
            Text("Protección contra distorsión", fontSize = 14.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqCore.Headroom.entries.forEach { m ->
                    FilterChip(selected = eq.headroom == m, onClick = { engine.setHeadroom(m) }, label = { Text(m.label) })
                }
            }
            Text(
                eq.headroom.description + when {
                    eq.advanced || eq.engine.isEmpty() -> ""
                    eq.headroom == EqCore.Headroom.LOUD -> " Este teléfono no tiene limitador: puede distorsionar."
                    eq.headroom == EqCore.Headroom.BALANCED -> " Este teléfono no tiene limitador: aquí equivale a «Máxima»."
                    else -> ""
                },
                fontSize = 12.sp, lineHeight = 16.sp, color = Muted
            )
            OptionRow("Recuperar el control si otra app lo toma", eq.autoReclaim, engine::setAutoReclaim)
            OptionRow("Compensar volumen bajo (más graves y agudos al bajar el volumen)", eq.loudness, engine::setLoudness)
            Row {
                TextButton(onClick = { saving = true }) { Text("Guardar preajuste") }
                TextButton(onClick = engine::resetProfile) { Text("Restablecer perfil") }
            }
            Row {
                TextButton(onClick = {
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, EqCore.toGraphicEq(eq.levels))
                    runCatching { ctx.startActivity(android.content.Intent.createChooser(send, "Exportar curva")) }
                }) { Text("Exportar curva") }
                TextButton(onClick = { importing = true }) { Text("Importar curva") }
            }
            eq.customPresets.forEach { name ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, Modifier.weight(1f), fontSize = 14.sp)
                    IconButton(onClick = { engine.deleteCustomPreset(name) }) { Icon(Icons.Filled.Delete, "Borrar $name") }
                }
            }
            if (eq.engine.isNotEmpty()) Text("Motor: ${eq.engine}", fontSize = 12.sp, color = Muted)
        }

        Text(
            "Cada salida tiene su propio ajuste: tu equipo Bluetooth, los audífonos y el altavoz. " +
                "Agrega el mosaico «Ecualizador» en Ajustes rápidos para activarlo sin abrir la app.\n\n" +
                "Diseño inspirado en Equalizer314 (F-Droid, GPL-3.0): DynamicsProcessing sobre la mezcla global. Código propio.",
            fontSize = 12.sp, lineHeight = 17.sp, color = Muted
        )
    }

    if (importing) ImportDialog(onDismiss = { importing = false }) { curve ->
        engine.applyCurve(curve, 0f, "Importado")
        importing = false
    }

    if (saving) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saving = false },
            title = { Text("Guardar preajuste") },
            text = { OutlinedTextField(name, { name = it.take(24) }, label = { Text("Nombre") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { engine.saveCustomPreset(name); saving = false }) { Text("Guardar") }
            },
            dismissButton = { TextButton(onClick = { saving = false }) { Text("Cancelar") } }
        )
    }
}

/** Curva de respuesta (−12..+12 dB, 20 Hz–20 kHz) con el espectro opcional detrás. */
@Composable
private fun ResponseGraph(gains: List<Float>) {
    val curve = remember(gains) { EqCore.responseCurve(gains, 96) }
    val ink = Ink; val accent = Accent; val track = Track; val muted = Muted
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        val minF = ln(20f)
        val maxF = ln(20000f)
        fun x(f: Float) = (ln(f) - minF) / (maxF - minF) * w
        fun y(db: Float) = h / 2 - db.coerceIn(-15f, 15f) / 15f * (h / 2)

        listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
            drawLine(ink.copy(alpha = if (db == 0f) 0.3f else 0.08f), Offset(0f, y(db)), Offset(w, y(db)), 1.dp.toPx())
        }
        listOf(100f, 1000f, 10000f).forEach { f ->
            drawLine(ink.copy(alpha = 0.08f), Offset(x(f), 0f), Offset(x(f), h), 1.dp.toPx())
        }
        val path = Path()
        curve.forEachIndexed { i, (f, db) -> if (i == 0) path.moveTo(x(f), y(db)) else path.lineTo(x(f), y(db)) }
        val fill = Path().apply { addPath(path); lineTo(w, h / 2); lineTo(0f, h / 2); close() }
        drawPath(fill, Brush.verticalGradient(listOf(accent.copy(alpha = 0.35f), Color.Transparent)))
        drawPath(path, accent, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf("20 Hz", "100", "1k", "10k", "20 kHz").forEach { Text(it, fontSize = 10.sp, color = Muted) }
    }
}

// ---------------------------------------------------------------- Equipo conectado y perfil automático

private sealed interface CalibState {
    data object Intro : CalibState
    data class Running(val progress: Float, val text: String) : CalibState
    data class Done(val result: CalibrationResult) : CalibState
    data class Failed(val message: String) : CalibState
}

@Composable
private fun DeviceCard(profileKey: String, engine: EqEngine) {
    val ctx = LocalContext.current
    var info by remember(profileKey) { mutableStateOf(DeviceAnalyzer.analyze(ctx)) }
    val btPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        info = DeviceAnalyzer.analyze(ctx)
    }
    var showAuto by remember { mutableStateOf(false) }
    var calib by remember { mutableStateOf<CalibState?>(null) }
    val scope = rememberCoroutineScope()

    fun startCalibration() {
        calib = CalibState.Running(0f, "Preparando…")
        scope.launch {
            calib = try {
                CalibState.Done(Calibrator(ctx).run { p, t -> calib = CalibState.Running(p, t) })
            } catch (e: CalibrationException) {
                CalibState.Failed(e.message ?: "Error")
            } catch (e: Exception) {
                CalibState.Failed("No se pudo calibrar: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCalibration() else calib = CalibState.Failed("Sin el permiso de micrófono no se puede medir.")
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Equipo conectado", fontSize = 12.sp, color = Muted)
                Text(info.name, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Text(info.kind.label, color = Accent, fontSize = 13.sp)
            }
            IconButton(onClick = { info = DeviceAnalyzer.analyze(ctx) }) { Icon(Icons.Filled.Refresh, "Analizar de nuevo") }
        }
        info.details.forEach { Text("• $it", fontSize = 12.sp, color = Ink.copy(alpha = 0.65f)) }
        if (info.key.startsWith("bt:") && !DeviceAnalyzer.hasBtPermission(ctx) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            TextButton(onClick = { btPermission.launch(Manifest.permission.BLUETOOTH_CONNECT) }) {
                Text("Permitir leer el tipo de equipo Bluetooth")
            }
        }
        Button(onClick = { showAuto = true }, modifier = Modifier.fillMaxWidth()) { Text("Crear perfil automático") }
        if (AutoProfile.canCalibrate(info.kind)) {
            OutlinedButton(onClick = { calib = CalibState.Intro }, modifier = Modifier.fillMaxWidth()) {
                Text("Calibrar con el micrófono")
            }
        }
    }

    if (showAuto) {
        val curve = AutoProfile.curveFor(info.kind)
        AlertDialog(
            onDismissRequest = { showAuto = false },
            title = { Text("Perfil para ${info.kind.label.lowercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ResponseGraph(EqCore.finalGains(curve.levels, curve.bass))
                    Text(curve.why, fontSize = 13.sp, lineHeight = 18.sp)
                    Text("Se guardará en el perfil de «${info.name}». Luego puedes ajustarlo a tu gusto.", fontSize = 12.sp, color = Muted)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    engine.applyCurve(curve.levels, curve.bass, "Automático")
                    showAuto = false
                }) { Text("Aplicar") }
            },
            dismissButton = { TextButton(onClick = { showAuto = false }) { Text("Cancelar") } }
        )
    }

    calib?.let { state ->
        CalibrationDialog(
            state = state,
            info = info,
            onStart = {
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startCalibration()
                else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
            onApply = { levels, bass ->
                engine.applyCurve(levels, bass, "Calibrado")
                calib = null
            },
            onDismiss = { if (state !is CalibState.Running) calib = null },
        )
    }
}

@Composable
private fun CalibrationDialog(
    state: CalibState,
    info: OutputInfo,
    onStart: () -> Unit,
    onApply: (List<Float>, Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var addBase by remember { mutableStateOf(true) }
    val base = AutoProfile.curveFor(info.kind)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibrar ${info.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (state) {
                    CalibState.Intro -> Text(
                        "1. Pon el volumen del equipo a un nivel normal de escucha (ni muy bajo ni al máximo).\n" +
                            "2. Ubica el teléfono donde sueles escuchar, a 1–2 m del equipo, con el micrófono hacia él.\n" +
                            "3. Pide silencio: sonará un ruido tipo «shhh» durante 9 segundos.\n\n" +
                            "El ecualizador se pausa mientras mide. El micrófono de un teléfono no es de laboratorio: " +
                            "el resultado es una buena base, no una medición exacta.",
                        fontSize = 13.sp, lineHeight = 18.sp
                    )
                    is CalibState.Running -> {
                        Text(state.text, fontSize = 14.sp)
                        LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    }
                    is CalibState.Failed -> Text(state.message, fontSize = 14.sp, color = Warn)
                    is CalibState.Done -> {
                        val proposal = proposal(state.result.correction, base, addBase)
                        CompareGraph(state.result.measured, proposal)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("— Medido", fontSize = 12.sp, color = Muted)
                            Text("— Corrección", fontSize = 12.sp, color = Accent)
                        }
                        Text(
                            "Lo medido muestra cómo suena el equipo en tu espacio (los extremos no se corrigen: el micrófono no los mide bien). " +
                                "La corrección recorta los picos y rellena con cuidado los huecos.",
                            fontSize = 12.sp, lineHeight = 16.sp, color = Ink.copy(alpha = 0.75f)
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = addBase, onCheckedChange = { addBase = it })
                            Text("Sumar el perfil automático de ${info.kind.label.lowercase()}", fontSize = 13.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                CalibState.Intro, is CalibState.Failed -> TextButton(onClick = onStart) {
                    Text(if (state is CalibState.Failed) "Reintentar" else "Comenzar")
                }
                is CalibState.Done -> TextButton(onClick = {
                    onApply(proposal(state.result.correction, base, addBase), if (addBase) base.bass else 0f)
                }) { Text("Aplicar") }
                is CalibState.Running -> {}
            }
        },
        dismissButton = {
            if (state !is CalibState.Running) TextButton(onClick = onDismiss) { Text("Cerrar") }
        }
    )
}

private fun proposal(correction: List<Float>, base: AutoCurve, addBase: Boolean): List<Float> =
    correction.mapIndexed { i, c -> ((if (addBase) base.levels[i] else 0f) + c).coerceIn(EqCore.MIN_DB, EqCore.MAX_DB) }

/** Respuesta medida (gris) y curva propuesta (color), por bandas. */
@Composable
private fun CompareGraph(measured: List<Float>, proposal: List<Float>) {
    val ink = Ink; val accent = Accent; val track = Track; val muted = Muted
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        val w = size.width
        val h = size.height
        fun x(i: Int) = i / (EqCore.BANDS - 1f) * w
        fun y(db: Float) = h / 2 - db.coerceIn(-15f, 15f) / 15f * (h / 2)
        listOf(-12f, -6f, 0f, 6f, 12f).forEach { db ->
            drawLine(ink.copy(alpha = if (db == 0f) 0.3f else 0.08f), Offset(0f, y(db)), Offset(w, y(db)), 1.dp.toPx())
        }
        fun line(values: List<Float>, color: Color) {
            val path = Path()
            values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
            drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round))
            values.forEachIndexed { i, v -> drawCircle(color, 3.dp.toPx(), Offset(x(i), y(v))) }
        }
        line(measured, muted)
        line(proposal, accent)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        (0 until EqCore.BANDS).forEach { Text(EqCore.label(it), fontSize = 9.sp, color = Muted) }
    }
}

// ---------------------------------------------------------------- Espectro en vivo

@Composable
private fun SpectrumCard(running: Boolean, error: String?, onToggle: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val settings by SpectrumPrefs.settings.collectAsState()
    LaunchedEffect(Unit) { SpectrumPrefs.load(ctx) }
    var mode by remember { mutableStateOf(0) } // 0 analizador, 1 cascada
    var frozen by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var customizing by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Espectro en tiempo real", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Switch(checked = running, onCheckedChange = onToggle)
        }
        if (running) {
            SpectrumView(settings, mode, frozen, Modifier.fillMaxWidth().height(190.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Analizador") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Cascada") })
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { customizing = true }) { Icon(Icons.Filled.Palette, "Personalizar") }
                IconButton(onClick = { frozen = !frozen }) {
                    Icon(if (frozen) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (frozen) "Continuar" else "Congelar")
                }
                IconButton(onClick = { fullscreen = true }) { Icon(Icons.Filled.Fullscreen, "Pantalla completa") }
            }
        } else {
            Text(
                "Muestra en vivo los graves, medios y agudos de todo lo que suena. Android pide el permiso de micrófono para esto, pero no se graba nada. " +
                    "También puedes agregar el widget «Espectro» en tu pantalla de inicio.",
                fontSize = 12.sp, lineHeight = 16.sp, color = Muted
            )
        }
        error?.let { Text(it, fontSize = 12.sp, color = Warn) }
        if (running && SpectrumWidget.hasWidgets(ctx)) {
            TextButton(onClick = { SpectrumWidgetService.start(ctx) }) { Text("Animar el widget de la pantalla de inicio") }
        }
    }

    if (fullscreen && running) {
        Dialog(onDismissRequest = { fullscreen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxSize().background(Bg).systemBarsPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Analizador") })
                    Spacer(Modifier.width(8.dp))
                    FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Cascada") })
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { customizing = true }) { Icon(Icons.Filled.Palette, "Personalizar") }
                    IconButton(onClick = { frozen = !frozen }) {
                        Icon(if (frozen) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (frozen) "Continuar" else "Congelar")
                    }
                    IconButton(onClick = { fullscreen = false }) { Icon(Icons.Filled.Close, "Cerrar") }
                }
                SpectrumView(settings, mode, frozen, Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (customizing) SpectrumSettingsDialog(settings, running, onDismiss = { customizing = false })
}

/** Permiso para mostrar lo que suena (título, tiempo, reproducir/pausa) en el reproductor de Winamp. */
@Composable
private fun NowPlayingAccess() {
    val ctx = LocalContext.current
    // Se revisa al volver a la app (el permiso se da en Ajustes)
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val access = remember(state) { NowPlaying.hasAccess(ctx) }
    val track by NowPlaying.track.collectAsState()
    if (access) {
        Text(
            (track?.let { "Suena: ${it.winampTitle()}" } ?: "Muestra lo que suena en cualquier app. Pon música para verlo.") +
                " En el fondo, los botones del skin controlan la música (toca una zona sin íconos).",
            fontSize = 12.sp, color = Muted
        )
    } else {
        Text(
            "Para ver en el reproductor el título, el tiempo y si está en pausa, y para que sus botones controlen " +
                "la música, Android pide el «acceso a las notificaciones». La app solo lo usa para eso: no lee tus notificaciones.",
            fontSize = 12.sp, lineHeight = 16.sp, color = Muted
        )
        OutlinedButton(onClick = {
            runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
        }) { Text("Mostrar lo que suena") }
    }
}

/** Fondo de pantalla animado: la única forma de tener el espectro a 60 fps en la pantalla de inicio. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WallpaperCard() {
    val ctx = LocalContext.current
    val ws by WallpaperPrefs.settings.collectAsState()
    val render by WallpaperPrefs.render.collectAsState()
    LaunchedEffect(Unit) { WallpaperPrefs.load(ctx); WallpaperPrefs.loadRender(ctx) }
    var message by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        saving = true
        scope.launch {
            // La foto se copia entera (puede pesar decenas de MB): fuera del hilo principal
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { WallpaperPrefs.saveImage(ctx, uri) }
            saving = false
            message = if (ok) null else "No se pudo usar esa imagen."
        }
    }
    val imageSize = remember(ws.imageVersion, ws.hasImage) {
        WallpaperPrefs.currentImage(ctx)?.let { WallpaperImage.uprightSize(it.path) }
    }
    fun set(change: (WallpaperSettings) -> WallpaperSettings) = WallpaperPrefs.update(ctx, change)

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("Fondo de pantalla animado", fontWeight = FontWeight.SemiBold)
        Text(
            "El espectro sobre tu foto en la pantalla de inicio, a 60 cuadros por segundo. " +
                "Usa el mismo estilo y colores del visor, y se detiene cuando no se ve (con una app abierta o la pantalla apagada).",
            fontSize = 12.sp, lineHeight = 16.sp, color = Muted
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !saving, onClick = {
                picker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text(if (saving) "Guardando…" else if (ws.hasImage) "Cambiar foto" else "Elegir foto") }
            if (ws.hasImage && !saving) TextButton(onClick = { WallpaperPrefs.removeImage(ctx) }) { Text("Quitar foto") }
        }
        if (ws.hasImage) imageSize?.let { (w, h) ->
            val drawn = render?.let { r ->
                " Se dibuja a ${r.surfaceW}×${r.surfaceH} (pantalla ${r.displayW}×${r.displayH})" +
                    (if (r.enlarged) ", pedido a resolución completa porque el sistema daba menos." else ".")
            } ?: " Pon el fondo para ver con qué resolución se dibuja."
            Text("Foto original de ${w}×${h}, sin recomprimir.$drawn", fontSize = 12.sp, lineHeight = 16.sp, color = Muted)
        }
        Text("Qué se ve", color = Muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallpaperView.entries.forEach { v ->
                FilterChip(selected = ws.view == v, onClick = { set { it.copy(view = v) } }, label = { Text(v.label) })
            }
        }
        val spectrum by SpectrumPrefs.settings.collectAsState()
        val winampLook = ws.view == WallpaperView.PLAYER || spectrum.style.winamp
        Text(
            when {
                ws.view == WallpaperView.PLAYER -> "La ventana del skin de Winamp elegido a todo el ancho, con el espectro en su visor."
                spectrum.style.winamp -> "La pantalla del skin (reloj y visor, con su marco) ampliada a todo el ancho."
                else -> "Solo el espectro, con el estilo y los colores del visor."
            },
            fontSize = 12.sp, color = Muted
        )
        if (winampLook) {
            // Las miniaturas muestran el skin como se verá en el fondo
            WinampSkinPicker(ws.view.spectrumSettings(spectrum)) { change -> SpectrumPrefs.update(ctx, change) }
            NowPlayingAccess()
        }
        Text("Posición", color = Muted, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WallpaperPosition.entries.forEach { p ->
                FilterChip(selected = ws.position == p, onClick = { set { it.copy(position = p) } }, label = { Text(p.label) })
            }
        }
        if (!winampLook) {
            LabeledSlider("Alto", ws.height, 0.1f..0.6f, "%.0f%%".format(ws.height * 100)) { v -> set { it.copy(height = v) } }
        }
        if (ws.hasImage) {
            LabeledSlider("Oscurecer foto", ws.dim, 0f..0.7f, "%.0f%%".format(ws.dim * 100)) { v -> set { it.copy(dim = v) } }
        }
        Button(onClick = {
            val intent = android.content.Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                android.content.ComponentName(ctx, SpectrumWallpaperService::class.java)
            )
            message = if (runCatching { ctx.startActivity(intent) }.isSuccess) null
            else "Este teléfono no permite fondos animados desde aquí. Búscalo en Ajustes › Fondo de pantalla › Fondos animados."
        }, modifier = Modifier.fillMaxWidth()) { Text("Establecer como fondo de pantalla") }
        Text(
            "Necesita el permiso del espectro: activa primero «Espectro en tiempo real» arriba. Los cambios se aplican al instante si ya está puesto.",
            fontSize = 11.sp, color = Muted
        )
        message?.let { Text(it, fontSize = 12.sp, color = Warn) }
    }
}

private const val WATERFALL_ROWS = 120

/** Visor animado a la frecuencia de la pantalla: analizador (5 estilos) o cascada (espectrograma). */
@Composable
private fun SpectrumView(settings: SpectrumSettings, mode: Int, frozen: Boolean, modifier: Modifier) {
    val n = settings.effectiveBars
    val smoother = remember(n) { SpectrumSmoother(n) }
    smoother.fallPerSec = settings.fall.perSec
    val renderer = remember { SpectrumRenderer() }
    var frameTick by remember { mutableStateOf(0L) }
    val density = androidx.compose.ui.platform.LocalDensity.current.density

    // Cascada: mapa de bits de n x WATERFALL_ROWS; cada análisis nuevo entra como fila por abajo.
    val pixels = remember(n) { IntArray(n * WATERFALL_ROWS) { 0xFF0F1115.toInt() } }
    val bitmap = remember(n) { android.graphics.Bitmap.createBitmap(n, WATERFALL_ROWS, android.graphics.Bitmap.Config.ARGB_8888) }
    val image = remember(bitmap) { bitmap.asImageBitmap() }

    LaunchedEffect(frozen, n) {
        if (frozen) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16L else (now - last) / 1_000_000
                last = now
                smoother.step(SpectrumShape.resample(Spectrum.bars.value, n), dt)
                frameTick = now
            }
        }
    }
    // La cascada solo escucha los análisis nuevos cuando se muestra (no recompone el visor).
    LaunchedEffect(mode, frozen, n, settings.palette, settings.sensitivity) {
        if (frozen || mode != 1) return@LaunchedEffect
        Spectrum.frames.collect {
            val row = SpectrumShape.resample(Spectrum.bars.value, n)
            System.arraycopy(pixels, n, pixels, 0, n * (WATERFALL_ROWS - 1))
            for (i in 0 until n) {
                val v = SpectrumShape.applySensitivity(row[i], settings.sensitivity)
                pixels[n * (WATERFALL_ROWS - 1) + i] = if (v < 0.02f) 0xFF0F1115.toInt() else settings.palette.color(v)
            }
            bitmap.setPixels(pixels, 0, n, 0, 0, n, WATERFALL_ROWS)
        }
    }

    val labels = listOf(50f to "50", 100f to "100", 250f to "250", 1000f to "1k", 4000f to "4k", 10000f to "10k")
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            frameTick // redibuja en cada fotograma
            if (mode == 1) {
                drawImage(
                    image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low
                )
            } else {
                drawIntoCanvas { c ->
                    renderer.draw(
                        c.nativeCanvas, size.width, size.height, smoother.values, smoother.peaks, settings, density,
                        wave = Spectrum.wave.value,
                    )
                }
            }
        }
        if (settings.grid) {
            BoxWithConstraints(Modifier.fillMaxWidth().height(14.dp)) {
                labels.forEach { (hz, text) ->
                    Text(text, fontSize = 10.sp, color = Muted, modifier = Modifier.offset(x = maxWidth * SpectrumScale.xOf(hz) - 8.dp))
                }
            }
        }
    }
}

/** Espectro de muestra para las miniaturas: graves altos que bajan hacia los agudos, con picos. */
private object WinampDemo {
    private fun shape(n: Int) = FloatArray(n) { i ->
        val x = i / (n - 1f)
        (0.9f - 0.55f * x + 0.18f * kotlin.math.sin(x * 17f)).coerceIn(0.1f, 1f)
    }
    private fun peaksOf(v: FloatArray) = FloatArray(v.size) { (v[it] + 0.12f).coerceAtMost(1f) }
    private val cache = HashMap<Int, Pair<FloatArray, FloatArray>>()
    /** Valores y picos de muestra para [n] barras. */
    fun of(n: Int): Pair<FloatArray, FloatArray> = cache.getOrPut(n) { shape(n).let { it to peaksOf(it) } }
    val wave = FloatArray(256) { i -> (0.7f * kotlin.math.sin(i * 0.11f) * kotlin.math.cos(i * 0.023f)) }
}

/** Dibuja el visor con el espectro de muestra (para miniaturas de skins y patrones). */
@Composable
private fun WinampPreview(settings: SpectrumSettings, modifier: Modifier) {
    val renderer = remember { SpectrumRenderer() }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Canvas(modifier) {
        val (values, peaks) = WinampDemo.of(settings.effectiveBars)
        drawIntoCanvas { c ->
            renderer.draw(c.nativeCanvas, size.width, size.height, values, peaks, settings, density, WinampDemo.wave)
        }
    }
}

/** Miniatura de un skin con el estilo, el patrón y la ventana elegidos, y el nombre debajo. */
@Composable
private fun WinampSkinTile(skin: WinampSkin, settings: SpectrumSettings, onClick: () -> Unit) {
    val selected = settings.winampSkin == skin.name
    val preview = remember(skin, settings.style, settings.winampPattern, settings.winampFrame, settings.peaks) {
        // Con otro estilo (solo tema de la app) la miniatura igual muestra el analizador del skin.
        settings.copy(winampSkin = skin.name, style = if (settings.style.winamp) settings.style else SpectrumStyle.WINAMP_CLASSIC)
    }
    Column(
        Modifier.width(112.dp).clip(RoundedCornerShape(10.dp))
            .border(2.dp, if (selected) Color(skin.chrome[WinampChrome.ACCENT]) else Color.Transparent, RoundedCornerShape(10.dp))
            .background(Bg).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        WinampPreview(preview, Modifier.fillMaxWidth().height(if (settings.winampFrame) 52.dp else 36.dp).clip(RoundedCornerShape(4.dp)))
        Text(
            skin.name, fontSize = 11.sp, lineHeight = 13.sp, maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
    }
}

/**
 * Selector de skins de Winamp: filtros por grupo, grilla de miniaturas, importar .wsz/.wal desde el
 * teléfono y quitar los importados.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WinampSkinPicker(settings: SpectrumSettings, set: ((SpectrumSettings) -> SpectrumSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val imported by WinampSkins.imported.collectAsState()
    val all = remember(imported) { WinampSkins.ALL }
    val selected = WinampSkins.byName(settings.winampSkin)
    var group by remember { mutableStateOf(selected.group) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        message = null
        scope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    val fileName = ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "Skin importado"
                    val archive = ctx.contentResolver.openInputStream(uri)?.use { SkinArchive.read(it) }
                        ?: throw IllegalArgumentException("No se pudo abrir el archivo")
                    val skins = SkinImport.import(archive, fileName.substringBeforeLast('.').replace('_', ' '), SkinImport.ANDROID_DECODER)
                    if (SkinImport.isModern(archive)) throw IllegalArgumentException("Solo se aceptan skins clásicos (.wsz); los modernos (.wal) no.")
                    if (skins.isEmpty()) throw IllegalArgumentException("No es un skin clásico de Winamp (no trae MAIN.BMP ni VISCOLOR.TXT)")
                    WinampSkins.add(ctx, skins)
                }
            }
            busy = false
            failed = result.isFailure
            result.onSuccess { added ->
                group = SkinImport.GROUP_IMPORTED
                set { it.copy(winampSkin = added.first().name) }
                message = "Importado: ${added.first().name}"
            }.onFailure { message = it.message ?: "No se pudo importar el skin" }
        }
    }

    Text("Skin de Winamp", color = Muted, fontSize = 13.sp)
    val groups = remember(all) { all.map { it.group }.distinct() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        groups.forEach { g ->
            FilterChip(selected = group == g, onClick = { group = g }, label = { Text("$g (${all.count { it.group == g }})") })
        }
    }
    val shown = remember(all, group) { all.filter { it.group == group }.ifEmpty { all } }
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = shown.indexOfFirst { it.name == settings.winampSkin }.coerceAtLeast(0)
    )
    // Alto de cada fila: miniatura + dos líneas de nombre al tamaño de letra del teléfono
    val rows = if (shown.size > 8) 3 else 2
    val nameHeight = with(androidx.compose.ui.platform.LocalDensity.current) { 26.sp.toDp() }
    val tileHeight = (if (settings.winampFrame) 52.dp else 36.dp) + nameHeight + 8.dp
    LazyHorizontalGrid(
        rows = GridCells.Fixed(rows), state = gridState,
        modifier = Modifier.fillMaxWidth().height(tileHeight * rows + 6.dp * (rows - 1) + 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(shown, key = { it.name }) { skin ->
            WinampSkinTile(skin, settings) { set { it.copy(winampSkin = skin.name) } }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) }) {
            Text(if (busy) "Importando…" else "Importar skin (.wsz)")
        }
        if (selected.group == SkinImport.GROUP_IMPORTED) {
            TextButton(onClick = {
                WinampSkins.remove(ctx, selected.name)
                set { it.copy(winampSkin = WinampSkins.DEFAULT.name) }
                if (WinampSkins.imported.value.isEmpty()) group = WinampSkins.GROUP_CLASSIC
            }) { Text("Quitar") }
        }
    }
    message?.let { Text(it, fontSize = 12.sp, color = if (failed) Warn else Accent) }
    Text(
        "Skins clásicos de Winamp 2 con su ventana real y sus colores originales (VISCOLOR.TXT, MAIN.BMP, " +
            "TEXT.BMP y PLEDIT.TXT). Puedes importar tus propios skins clásicos (.wsz); quedan solo en tu teléfono.",
        fontSize = 12.sp, lineHeight = 16.sp, color = Muted
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpectrumSettingsDialog(settings: SpectrumSettings, running: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    fun set(change: (SpectrumSettings) -> SpectrumSettings) = SpectrumPrefs.update(ctx, change)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(24.dp)).background(Card)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Personalizar visor y tema", Modifier.weight(1f), fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Cerrar") }
            }
            if (running) {
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Bg).padding(6.dp)) {
                    SpectrumView(settings, 0, false, Modifier.fillMaxWidth().height(120.dp))
                }
            }
            Text("Estilo", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectrumStyle.entries.forEach { st ->
                    FilterChip(selected = settings.style == st, onClick = { set { it.copy(style = st) } }, label = { Text(st.label) })
                }
            }
            Text("Colores", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectrumPalette.entries.forEach { pal ->
                    FilterChip(
                        selected = settings.palette == pal, onClick = { set { it.copy(palette = pal) } },
                        label = { Text(pal.label) },
                        leadingIcon = {
                            Box(
                                Modifier.size(14.dp).clip(RoundedCornerShape(50))
                                    .background(Brush.verticalGradient(pal.stops.reversed().map { Color(it.second) }))
                            )
                        }
                    )
                }
            }
            OptionRow("Tema de la app con los colores del skin", settings.appTheme) { v -> set { it.copy(appTheme = v) } }
            if (settings.style.winamp || settings.appTheme) {
                WinampSkinPicker(settings) { change -> set(change) }
            }
            if (settings.style.winamp) {
                Text("Patrón de color", color = Muted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WinampPattern.entries.forEach { pat ->
                        FilterChip(
                            selected = settings.winampPattern == pat,
                            onClick = { set { it.copy(winampPattern = pat) } },
                            label = { Text(pat.label) },
                            leadingIcon = {
                                val mini = remember(pat, settings.winampSkin, settings.style) {
                                    settings.copy(winampPattern = pat, winampFrame = false)
                                }
                                WinampPreview(mini, Modifier.size(width = 30.dp, height = 18.dp).clip(RoundedCornerShape(3.dp)))
                            }
                        )
                    }
                }
                OptionRow("Mostrar la ventana del skin", settings.winampFrame) { v -> set { it.copy(winampFrame = v) } }
                Text(
                    "Como el original: 16 píxeles de alto; el Winamp original tiene 19 bandas gruesas o 75 finas.",
                    fontSize = 12.sp, lineHeight = 16.sp, color = Muted
                )
            }
            if (settings.barOptions.isNotEmpty()) {
                Text("Cantidad de barras", color = Muted, fontSize = 13.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val original = when (settings.style) {
                        SpectrumStyle.WINAMP_CLASSIC, SpectrumStyle.WINAMP_FIRE -> SpectrumSettings.WINAMP_BARS_ORIGINAL
                        SpectrumStyle.WINAMP_LINES -> SpectrumSettings.WINAMP_LINES_ORIGINAL
                        else -> -1
                    }
                    settings.barOptions.forEach { b ->
                        FilterChip(
                            selected = settings.effectiveBars == b, onClick = { set { it.withBars(b) } },
                            label = { Text(if (b == original) "$b (original)" else "$b") }
                        )
                    }
                }
            }
            Text("Caída de las barras", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FallSpeed.entries.forEach { f ->
                    FilterChip(selected = settings.fall == f, onClick = { set { it.copy(fall = f) } }, label = { Text(f.label) })
                }
            }
            LabeledSlider("Sensibilidad", settings.sensitivity, 0.5f..2.5f, "×%.1f".format(settings.sensitivity)) { v ->
                set { it.copy(sensitivity = v) }
            }
            OptionRow("Mostrar picos", settings.peaks) { v -> set { it.copy(peaks = v) } }
            OptionRow("Mostrar guías de frecuencia", settings.grid) { v -> set { it.copy(grid = v) } }
            Text("Widget: cuadros por segundo", color = Muted, fontSize = 13.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpectrumSettings.FPS_OPTIONS.forEach { f ->
                    FilterChip(selected = settings.widgetFps == f, onClick = { set { it.copy(widgetFps = f) } }, label = { Text("$f") })
                }
            }
            Text(
                "Más cuadros por segundo en el widget se ve más fluido pero gasta más batería. El widget se pausa con la pantalla apagada.",
                fontSize = 12.sp, lineHeight = 16.sp, color = Muted
            )
        }
    }
}

/** Mantener presionado para escuchar sin ecualizador (comparación A/B). */
@Composable
private fun CompareButton(enabled: Boolean, onHold: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    val hold by rememberUpdatedState(onHold)
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (held) Accent else Track)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    held = true; hold(true)
                    try { tryAwaitRelease() } finally { held = false; hold(false) }
                })
            }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            if (held) "Sin ecualizador…" else "Mantén para comparar A/B",
            fontSize = 13.sp, color = if (enabled) Ink else Muted
        )
    }
}

/** Pegar una curva GraphicEQ (Equalizer APO, AutoEQ). */
@Composable
private fun ImportDialog(onDismiss: () -> Unit, onImport: (List<Float>) -> Unit) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { EqCore.fromGraphicEq(text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Importar curva") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Pega una curva en formato GraphicEQ (Equalizer APO). Por ejemplo, las de AutoEQ para tus audífonos.",
                    fontSize = 13.sp, lineHeight = 18.sp
                )
                OutlinedTextField(
                    text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp),
                    placeholder = { Text("GraphicEQ: 20 -1.5; 31 -1.2; …") }
                )
                TextButton(onClick = { text = clipboard.getText()?.text.orEmpty() }) { Text("Pegar del portapapeles") }
                when {
                    text.isBlank() -> {}
                    parsed == null -> Text("No se reconoce el formato GraphicEQ.", fontSize = 12.sp, color = Warn)
                    else -> ResponseGraph(parsed)
                }
            }
        },
        confirmButton = { TextButton(enabled = parsed != null, onClick = { parsed?.let(onImport) }) { Text("Aplicar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } }
    )
}

/** Barra vertical arrastrable de −12 a +12 dB, en pasos de 0,5 dB. */
@Composable
private fun BandSlider(value: Float, label: String, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(onChange)
    val range = EqCore.MAX_DB - EqCore.MIN_DB
    fun fromY(y: Float, h: Float) = Math.round((EqCore.MAX_DB - (y / h).coerceIn(0f, 1f) * range) * 2) / 2f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("%+.1f".format(value).replace(".0", ""), fontSize = 10.sp, color = Ink.copy(alpha = 0.7f))
        val ink = Ink; val accent = Accent; val track = Track; val muted = Muted
        Canvas(
            Modifier.height(180.dp).width(28.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { current(0f) },
                        onTap = { current(fromY(it.y, size.height.toFloat())) },
                    )
                }
                .pointerInput(Unit) { detectVerticalDragGestures { change, _ -> current(fromY(change.position.y, size.height.toFloat())) } }
        ) {
            val x = size.width / 2
            val zeroY = size.height * (EqCore.MAX_DB / range)
            val y = size.height * ((EqCore.MAX_DB - value) / range)
            drawLine(track, Offset(x, 0f), Offset(x, size.height), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink.copy(alpha = 0.25f), Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1.dp.toPx())
            drawLine(accent, Offset(x, zeroY), Offset(x, y), strokeWidth = 6.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(ink, radius = 8.dp.toPx(), center = Offset(x, y))
        }
        Text(label, fontSize = 10.sp, color = ink.copy(alpha = 0.7f))
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, onChange: (Float) -> Unit) {
    // Etiqueta y valor arriba, el deslizador a todo el ancho: con letra grande nada se corta ni se
    // parte a mitad de palabra (con un ancho fijo para la etiqueta sí pasaba)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), fontSize = 14.sp)
            Text(display, Modifier.padding(start = 8.dp), fontSize = 13.sp, color = Muted, softWrap = false)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun OptionRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
