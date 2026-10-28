package cl.ecualizador

/** Tipo de salida de audio, para proponer una curva de partida. */
enum class OutputKind(val label: String) {
    PHONE_SPEAKER("Altavoz del teléfono"),
    WIRED_HEADPHONES("Audífonos con cable"),
    USB_AUDIO("Audio USB / DAC"),
    BT_HEADPHONES("Audífonos Bluetooth"),
    BT_PORTABLE_SPEAKER("Parlante portátil"),
    BT_PARTY_SPEAKER("Equipo de fiesta / minicomponente"),
    BT_CAR("Radio del auto"),
    BT_TV_SOUNDBAR("TV / barra de sonido"),
    BT_UNKNOWN("Equipo Bluetooth"),
}

/** Curva propuesta para un tipo de salida, con la explicación que se muestra al usuario. */
data class AutoCurve(val levels: List<Float>, val bass: Float, val why: String)

/**
 * Clasifica lo conectado por nombre y por la clase Bluetooth que informa el equipo, y propone una
 * curva de partida. Las curvas son orientativas (no hay medición); la calibración con micrófono
 * las afina para parlantes.
 */
object AutoProfile {
    private fun r(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    private val PARTY = r("""\b(gtk|mhc|shake)[- ]|partybox|xboom|\bxl\d|sound ?tower|\bmx-|srs-x[vp]|ult ?tower|boombox|hyperboom|everboom""")
    private val PORTABLE = r("""\bflip ?\d|\bcharge ?\d|\bclip ?\d|\bgo ?\d|srs-x[bge]|ult ?field|wonderboom|megaboom|\bboom ?\d|soundcore (motion|flare|boom)|tribit|emberton|soundlink|\bxtreme|stormbox|\bbluetooth speaker""")
    private val HEADPHONES = r("""\bw[hf]-|\bmdr|airpods|\bbuds|beats|quietcomfort|\bqc ?\d|\btune ?\d|\blive ?\d|liberty|\blife ?[pq]|jabra|sennheiser|momentum|headphone|earbud|\bear ?\(|nothing ear|\bheadset|\btws\b""")
    private val CAR = r("""\bcar\b|carkit|toyota|hyundai|\bkia\b|chevrolet|nissan|\bvw\b|volkswagen|\bford\b|mazda|uconnect|\bsync\b|my ?car|pioneer|kenwood|\bjvc\b|peugeot|renault|suzuki|honda|subaru|mitsubishi""")
    private val TV = r("""\btv\b|soundbar|sound ?bar|\bht-|\bbar ?\d|bravia|\[tv]""")

    // BluetoothClass.Device (valores de la especificación Bluetooth)
    private const val BT_WEARABLE_HEADSET = 0x0404
    private const val BT_HANDSFREE = 0x0408
    private const val BT_LOUDSPEAKER = 0x0414
    private const val BT_HEADPHONES = 0x0418
    private const val BT_PORTABLE_AUDIO = 0x041C
    private const val BT_CAR_AUDIO = 0x0420
    private const val BT_HIFI_AUDIO = 0x0428
    private const val BT_VIDEO_DISPLAY_AND_LOUDSPEAKER = 0x043C

    /** [btDeviceClass] es BluetoothClass.getDeviceClass(), o null si no se conoce. */
    fun classifyBluetooth(name: String, btDeviceClass: Int?): OutputKind {
        // El nombre manda cuando es claro: muchos equipos informan una clase genérica.
        return when {
            PARTY.containsMatchIn(name) -> OutputKind.BT_PARTY_SPEAKER
            HEADPHONES.containsMatchIn(name) -> OutputKind.BT_HEADPHONES
            CAR.containsMatchIn(name) -> OutputKind.BT_CAR
            TV.containsMatchIn(name) -> OutputKind.BT_TV_SOUNDBAR
            PORTABLE.containsMatchIn(name) -> OutputKind.BT_PORTABLE_SPEAKER
            else -> when (btDeviceClass) {
                BT_HEADPHONES, BT_WEARABLE_HEADSET, BT_HANDSFREE -> OutputKind.BT_HEADPHONES
                BT_CAR_AUDIO -> OutputKind.BT_CAR
                BT_HIFI_AUDIO -> OutputKind.BT_PARTY_SPEAKER
                BT_VIDEO_DISPLAY_AND_LOUDSPEAKER -> OutputKind.BT_TV_SOUNDBAR
                BT_LOUDSPEAKER, BT_PORTABLE_AUDIO -> OutputKind.BT_PORTABLE_SPEAKER
                else -> OutputKind.BT_UNKNOWN
            }
        }
    }

    fun curveFor(kind: OutputKind): AutoCurve = when (kind) {
        OutputKind.PHONE_SPEAKER -> AutoCurve(
            listOf(-6f, -5f, -2f, 0f, 1f, 1.5f, 2f, 1.5f, 1f, 0f), 0f,
            "El altavoz del teléfono no puede reproducir graves profundos: se recortan para que no distorsione y se realzan las voces."
        )
        OutputKind.WIRED_HEADPHONES, OutputKind.BT_HEADPHONES -> AutoCurve(
            listOf(4f, 4f, 3f, 1f, 0f, 0f, 1f, 1f, -1f, -1f), 0f,
            "Curva inspirada en el objetivo Harman para audífonos: graves algo más presentes y agudos suavizados para escuchar sin fatiga."
        )
        OutputKind.BT_PORTABLE_SPEAKER -> AutoCurve(
            listOf(-4f, 0f, 2.5f, 1.5f, 0f, 0f, 0.5f, 1f, 1.5f, 1f), 0.15f,
            "Los parlantes portátiles casi no suenan bajo 60 Hz: se baja esa zona (solo gasta potencia y distorsiona) y se refuerza 125–250 Hz, donde sí se nota el bajo."
        )
        OutputKind.BT_PARTY_SPEAKER -> AutoCurve(
            listOf(1f, 1.5f, 0f, -1.5f, -1f, 0f, 1f, 1.5f, 1.5f, 1f), 0f,
            "Los equipos de fiesta ya traen mucho bajo (por ejemplo EXTRA BASS): se limpia el «retumbe» de 250–500 Hz y se agrega claridad en 2–8 kHz."
        )
        OutputKind.BT_CAR -> AutoCurve(
            listOf(3f, 3f, 2f, 0f, -1f, 0f, 1f, 2f, 2f, 1f), 0f,
            "En el auto el ruido del camino tapa los graves y la claridad: se refuerzan ambos extremos."
        )
        OutputKind.BT_TV_SOUNDBAR -> AutoCurve(
            listOf(0f, 0f, -0.5f, -1f, 0f, 1.5f, 2.5f, 2f, 0.5f, 0f), 0f,
            "Pensado para TV y barras: realza 1–4 kHz para que los diálogos se entiendan mejor."
        )
        OutputKind.USB_AUDIO, OutputKind.BT_UNKNOWN -> AutoCurve(
            List(EqCore.BANDS) { 0f }, 0f,
            "No se pudo saber qué tipo de equipo es: se parte de una curva plana. Prueba la calibración con micrófono si es un parlante."
        )
    }

    /** La calibración con micrófono tiene sentido para parlantes, no para audífonos. */
    fun canCalibrate(kind: OutputKind) = kind !in setOf(OutputKind.WIRED_HEADPHONES, OutputKind.BT_HEADPHONES)
}
