package com.ambiance.recorder

/** Estado compartido entre los servicios y la pantalla (que lo consulta por sondeo). */
object RecorderState {
    @Volatile var recording = false
    /** Ruta absoluta del WAV que se está escribiendo. */
    @Volatile var currentFile: String? = null
    @Volatile var elapsedMs = 0L
    @Volatile var peak = 0f
    @Volatile var clipped = false
    @Volatile var format = ""
    @Volatile var error: String? = null
}

object ServerState {
    @Volatile var url: String? = null
    @Volatile var error: String? = null
}
