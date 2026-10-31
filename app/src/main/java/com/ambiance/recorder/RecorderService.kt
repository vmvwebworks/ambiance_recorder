package com.ambiance.recorder

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max

class RecorderService : Service() {
    @Volatile private var running = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            running = false
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, Notifications.build(this, "Grabando ambiente…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        running = true
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ambiance:record")
            .apply { acquire() }
        thread(name = "recorder") {
            try {
                record()
            } catch (e: Exception) {
                RecorderState.error = e.message ?: e.toString()
            } finally {
                RecorderState.recording = false
                RecorderState.currentFile = null
                running = false
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private fun record() {
        // UNPROCESSED evita AGC, supresión de ruido y ecualización. Si el móvil no
        // lo soporta, VOICE_RECOGNITION es la fuente con menos procesado garantizada.
        val unprocessed = getSystemService(AudioManager::class.java)
            .getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
        val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val (rec, channels) = open(source, 2) ?: open(source, 1)
            ?: throw IllegalStateException("No se pudo abrir el micrófono")

        val session = Sessions.current(this)
        val dir = Sessions.dir(this, session).apply { mkdirs() }
        val name = session.replace(' ', '_') + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".wav"
        val file = File(dir, name)
        RecorderState.apply {
            error = null
            clipped = false
            elapsedMs = 0
            currentFile = file.absolutePath
            format = "${SAMPLE_RATE / 1000} kHz · 24 bit · ${if (channels == 2) "estéreo" else "mono"} · " +
                if (unprocessed) "sin procesar" else "procesado mínimo"
            recording = true
        }

        val buf = FloatArray(SAMPLE_RATE / 10 * channels)
        try {
            rec.startRecording()
            WavWriter(file, SAMPLE_RATE, channels).use { wav ->
                while (running && wav.dataBytes < WavWriter.MAX_DATA_BYTES) {
                    val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw IllegalStateException("Error leyendo el micrófono ($n)")
                    wav.write(buf, n)
                    var p = 0f
                    for (i in 0 until n) p = max(p, abs(buf[i]))
                    RecorderState.peak = p
                    if (p >= 0.999f) RecorderState.clipped = true
                    RecorderState.elapsedMs = wav.dataBytes * 1000 / (SAMPLE_RATE * channels * 3)
                }
            }
        } finally {
            rec.stop()
            rec.release()
            RecorderState.peak = 0f
        }
    }

    @SuppressLint("MissingPermission") // La pantalla pide el permiso antes de arrancar el servicio.
    private fun open(source: Int, channels: Int): Pair<AudioRecord, Int>? {
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val min = AudioRecord.getMinBufferSize(SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_FLOAT)
        if (min <= 0) return null
        // Medio segundo de margen para no perder muestras si el almacenamiento se atasca.
        val size = max(min, SAMPLE_RATE / 2 * channels * 4)
        val rec = AudioRecord(source, SAMPLE_RATE, mask, AudioFormat.ENCODING_PCM_FLOAT, size)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec to channels
    }

    companion object {
        const val ACTION_STOP = "stop"
        const val SAMPLE_RATE = 48_000
        private const val NOTIFICATION_ID = 1
    }
}
