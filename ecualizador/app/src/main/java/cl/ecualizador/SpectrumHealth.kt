package cl.ecualizador

/**
 * Qué hacer cuando el Visualizer deja de entregar datos (pasa al pausar y reanudar la música:
 * muchos teléfonos cortan los datos en silencio y, al volver, reinician la salida de audio).
 */
object SpectrumHealth {
    enum class Action { NONE, CLEAR, RESTART }

    const val STALE_MS = 300L
    const val DEAD_MS = 1500L
    const val MIN_RESTART_GAP_MS = 2000L

    /**
     * @param idleMs tiempo desde el último análisis recibido
     * @param musicActive si Android informa música sonando
     * @param showingData si las barras todavía muestran algo distinto de cero
     * @param sinceRestartMs tiempo desde el último reinicio del Visualizer
     */
    fun decide(idleMs: Long, musicActive: Boolean, showingData: Boolean, sinceRestartMs: Long): Action = when {
        // Suena música pero no llegan datos: el Visualizer quedó colgado tras reanudar.
        idleMs > DEAD_MS && musicActive && sinceRestartMs > MIN_RESTART_GAP_MS -> Action.RESTART
        // Pausa o silencio sin datos: dejar caer las barras en vez de congelarlas.
        idleMs > STALE_MS && showingData -> Action.CLEAR
        else -> Action.NONE
    }
}
