package cl.ecualizador

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Vuelve a activar el ecualizador tras reiniciar el teléfono o actualizar la app, si estaba activo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val engine = EqEngine.get(context) // crea el efecto si estaba activado
        if (engine.state.value.enabled) EqService.start(context)
    }
}
