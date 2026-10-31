package com.ambiance.recorder

import android.content.Context
import android.os.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Cada sesión es una subcarpeta de la carpeta de grabaciones. */
object Sessions {
    const val DEFAULT = "General"
    private const val PREFS = "sessions"
    private const val KEY_CURRENT = "current"

    fun root(ctx: Context): File =
        ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC)!!.apply { mkdirs() }

    /** Pasa a "General" los WAV sueltos de versiones anteriores, que no tenían sesiones. */
    fun migrate(ctx: Context) {
        val loose = root(ctx).listFiles { f -> f.isFile && f.name.endsWith(".wav") } ?: return
        if (loose.isEmpty()) return
        val target = dir(ctx, DEFAULT).apply { mkdirs() }
        loose.forEach { it.renameTo(File(target, it.name)) }
    }

    fun list(ctx: Context): List<String> {
        val names = root(ctx).listFiles { f -> f.isDirectory }?.map { it.name }.orEmpty().toMutableList()
        if (DEFAULT !in names) names += DEFAULT
        return names.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it })
    }

    fun dir(ctx: Context, session: String) = File(root(ctx), session)

    fun recordings(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".wav") }
            .sortedByDescending { it.lastModified() }

    /** Quita caracteres que no valen en nombres de fichero; null si no queda nada. */
    fun clean(name: String): String? =
        name.trim().replace(Regex("""[\\/:*?"<>|]"""), "").trim('.', ' ').takeIf { it.isNotEmpty() }

    fun create(ctx: Context, name: String): Boolean = dir(ctx, name).run { exists() || mkdirs() }

    fun rename(ctx: Context, from: String, to: String): Boolean {
        val target = dir(ctx, to)
        return !target.exists() && dir(ctx, from).renameTo(target)
    }

    fun delete(ctx: Context, session: String) = dir(ctx, session).deleteRecursively()

    fun current(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT, DEFAULT)!!
            .takeIf { dir(ctx, it).isDirectory || it == DEFAULT } ?: DEFAULT

    fun setCurrent(ctx: Context, session: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT, session).apply()

    /** Duración y formato leídos de la cabecera que escribe [WavWriter]. */
    fun wavInfo(f: File): String = try {
        val h = ByteArray(WavWriter.HEADER_SIZE)
        f.inputStream().use { it.read(h) }
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt()
        val rate = bb.getInt(24)
        val secs = (f.length() - WavWriter.HEADER_SIZE) / bb.getInt(28)
        "%d:%02d · %d kHz · %s".format(secs / 60, secs % 60, rate / 1000, if (channels == 2) "estéreo" else "mono")
    } catch (_: Exception) {
        "?"
    }

    fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)
}
