package com.ambiance.recorder

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Escribe WAV PCM de 24 bits a partir de muestras float.
 * La cabecera se reescribe cada pocos segundos, así que si la app muere
 * a mitad de grabación el fichero sigue siendo legible hasta ese punto.
 */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) : Closeable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private var bytes = ByteArray(0)
    private var writes = 0
    var dataBytes = 0L
        private set

    init {
        raf.write(header())
    }

    fun write(samples: FloatArray, count: Int) {
        if (bytes.size < count * 3) bytes = ByteArray(count * 3)
        var j = 0
        for (i in 0 until count) {
            val v = (samples[i].coerceIn(-1f, 1f) * 8388607f).roundToInt()
            bytes[j++] = v.toByte()
            bytes[j++] = (v shr 8).toByte()
            bytes[j++] = (v shr 16).toByte()
        }
        raf.write(bytes, 0, j)
        dataBytes += j
        if (++writes % 30 == 0) patchHeader()
    }

    private fun header(): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt())
        put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16)
        putShort(1) // PCM entero
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(sampleRate * channels * 3)
        putShort((channels * 3).toShort())
        putShort(24)
        put("data".toByteArray()); putInt(dataBytes.toInt())
    }.array()

    private fun patchHeader() {
        val pos = raf.filePointer
        raf.seek(0)
        raf.write(header())
        raf.seek(pos)
    }

    override fun close() {
        patchHeader()
        raf.close()
    }

    companion object {
        const val HEADER_SIZE = 44
        // Límite del WAV clásico (tamaños de 32 bits), con margen: ~3h50m en estéreo.
        const val MAX_DATA_BYTES = 4_000_000_000L
    }
}
