package cl.ecualizador

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.os.Build

/** Lo que se sabe de la salida de audio actual. */
data class OutputInfo(val key: String, val name: String, val kind: OutputKind, val details: List<String>)

/** Analiza la salida activa: tipo, nombre, clase Bluetooth y formatos que acepta. */
object DeviceAnalyzer {
    private val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
    private val USB = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)

    fun analyze(ctx: Context): OutputInfo {
        val outs = ctx.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val bt = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
        if (bt != null) {
            val name = bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            val btClass = bluetoothClass(ctx, bt, name)
            val kind = AutoProfile.classifyBluetooth(name, btClass)
            val details = formatDetails(bt) + listOfNotNull(
                btClass?.let { "Clase Bluetooth: ${classLabel(it)} (0x%04X)".format(it) }
                    ?: if (!hasBtPermission(ctx)) "Clase Bluetooth: sin permiso para leerla" else null,
                if (kind == OutputKind.BT_UNKNOWN) "Tipo no reconocido por el nombre" else "Reconocido como: ${kind.label}",
            )
            return OutputInfo("bt:$name", name, kind, details)
        }
        outs.firstOrNull { it.type in WIRED }?.let {
            return OutputInfo("wired", "Audífonos con cable", OutputKind.WIRED_HEADPHONES, formatDetails(it))
        }
        outs.firstOrNull { it.type in USB }?.let {
            val name = it.productName?.toString()?.takeIf { n -> n.isNotBlank() } ?: "Audio USB"
            return OutputInfo("wired", name, OutputKind.USB_AUDIO, formatDetails(it))
        }
        val speaker = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        return OutputInfo("speaker", "Altavoz del teléfono", OutputKind.PHONE_SPEAKER, speaker?.let { formatDetails(it) }.orEmpty())
    }

    fun hasBtPermission(ctx: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun bluetoothClass(ctx: Context, dev: AudioDeviceInfo, name: String): Int? {
        if (!hasBtPermission(ctx)) return null
        val bonded = runCatching {
            ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
        }.getOrNull().orEmpty()
        val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) dev.address else null
        val match = bonded.firstOrNull { address != null && it.address.equals(address, ignoreCase = true) }
            ?: bonded.firstOrNull { runCatching { it.name }.getOrNull() == name }
        return match?.bluetoothClass?.deviceClass
    }

    private fun classLabel(c: Int) = when (c) {
        0x0404, 0x0408 -> "manos libres / headset"
        0x0414 -> "parlante"
        0x0418 -> "audífonos"
        0x041C -> "audio portátil"
        0x0420 -> "audio de auto"
        0x0428 -> "equipo Hi-Fi"
        0x043C -> "pantalla con parlantes"
        else -> "audio"
    }

    private fun formatDetails(d: AudioDeviceInfo): List<String> {
        val rates = d.sampleRates.toList().sorted()
        val channels = d.channelCounts.toList()
        val encodings = d.encodings.toList().mapNotNull {
            when (it) {
                AudioFormat.ENCODING_PCM_16BIT -> "16 bits"
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24 bits"
                AudioFormat.ENCODING_PCM_32BIT -> "32 bits"
                AudioFormat.ENCODING_PCM_FLOAT -> "coma flotante"
                else -> null
            }
        }.distinct()
        return listOfNotNull(
            "Frecuencias de muestreo: " + (
                if (rates.isEmpty()) "las que pida el sistema"
                else rates.joinToString(" / ") { r: Int -> if (r % 1000 == 0) "${r / 1000} kHz" else "%.1f kHz".format(r / 1000f) }
                ),
            "Canales: " + when {
                channels.isEmpty() -> "según el contenido"
                channels.max() >= 2 -> "estéreo"
                else -> "mono"
            },
            encodings.takeIf { it.isNotEmpty() }?.let { "Formatos: " + it.joinToString(", ") },
        )
    }
}
