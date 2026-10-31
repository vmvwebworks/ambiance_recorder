package com.ambiance.recorder

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread

/**
 * Servidor HTTP mínimo para la red local:
 *   /        listado de grabaciones con reproductor
 *   /f/sesion/name  el WAV en línea (con Range, para poder saltar en el reproductor)
 *   /d/sesion/name  el WAV como descarga
 *   /z/sesion       la sesión entera en ZIP (sin comprimir: el WAV apenas comprime)
 */
class WebServer(private val dir: File, private val port: Int) {
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()

    fun start() {
        val s = ServerSocket(port)
        socket = s
        thread(name = "http-accept") {
            while (!s.isClosed) {
                val client = try { s.accept() } catch (_: IOException) { break }
                pool.execute { try { handle(client) } catch (_: IOException) { } }
            }
        }
    }

    fun stop() {
        socket?.close()
        pool.shutdownNow()
    }

    private fun handle(client: Socket) = client.use {
        client.soTimeout = 30_000
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val parts = requestLine.split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val path = URLDecoder.decode(parts[1].substringBefore('?'), "UTF-8")
        val out = BufferedOutputStream(client.getOutputStream(), 64 * 1024)
        val head = method == "HEAD"
        when {
            method != "GET" && !head -> sendText(out, 405, "Método no permitido")
            path == "/" -> send(out, 200, "text/html; charset=utf-8", listingHtml().toByteArray(), head)
            path.startsWith("/f/") -> serveFile(out, path.removePrefix("/f/"), headers["range"], false, head)
            path.startsWith("/d/") -> serveFile(out, path.removePrefix("/d/"), headers["range"], true, head)
            path.startsWith("/z/") -> serveZip(out, path.removePrefix("/z/"), head)
            else -> sendText(out, 404, "No encontrado")
        }
        out.flush()
    }

    /** Fichero o carpeta dentro de la carpeta de grabaciones; null si la ruta intenta salirse. */
    private fun resolve(rel: String): File? {
        val f = File(dir, rel).canonicalFile
        return f.takeIf { it.path.startsWith(dir.canonicalPath + File.separator) }
    }

    private fun recordings(session: File): List<File> =
        Sessions.recordings(session).filter { it.absolutePath != RecorderState.currentFile }

    private fun serveZip(out: OutputStream, session: String, head: Boolean) {
        val folder = resolve(session)
        if (folder == null || !folder.isDirectory) {
            sendText(out, 404, "No encontrado")
            return
        }
        // Sin Content-Length: el tamaño final no se conoce hasta terminar; la conexión se cierra al acabar.
        writeHead(out, 200, "application/zip", -1, listOf("Content-Disposition: attachment; filename=\"${folder.name}.zip\""))
        if (head) return
        ZipOutputStream(out).apply { setLevel(Deflater.NO_COMPRESSION) }.let { zip ->
            recordings(folder).forEach { f ->
                zip.putNextEntry(ZipEntry("${folder.name}/${f.name}").apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(zip, 64 * 1024) }
                zip.closeEntry()
            }
            zip.finish()
        }
    }

    private fun serveFile(out: OutputStream, rel: String, range: String?, download: Boolean, head: Boolean) {
        val file = resolve(rel)
        if (file == null || !file.isFile || file.absolutePath == RecorderState.currentFile) {
            sendText(out, 404, "No encontrado")
            return
        }
        val name = file.name
        val length = file.length()
        var start = 0L
        var end = length - 1
        val m = range?.let { Regex("""bytes=(\d*)-(\d*)""").find(it) }
        if (m != null) {
            val (a, b) = m.destructured
            if (a.isEmpty()) {
                start = maxOf(0, length - (b.toLongOrNull() ?: 0))
            } else {
                start = a.toLong()
                if (b.isNotEmpty()) end = minOf(b.toLong(), length - 1)
            }
            if (start > end) {
                writeHead(out, 416, "text/plain", 0, listOf("Content-Range: bytes */$length"))
                return
            }
        }
        val count = end - start + 1
        val extra = mutableListOf("Accept-Ranges: bytes")
        if (m != null) extra += "Content-Range: bytes $start-$end/$length"
        if (download) extra += "Content-Disposition: attachment; filename=\"$name\""
        writeHead(out, if (m != null) 206 else 200, "audio/wav", count, extra)
        if (head) return
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val buf = ByteArray(64 * 1024)
            var left = count
            while (left > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                if (n < 0) break
                out.write(buf, 0, n)
                left -= n
            }
        }
    }

    private fun listingHtml(): String {
        val date = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
        val sessions = (dir.listFiles { f -> f.isDirectory } ?: emptyArray())
            .map { it to recordings(it) }
            .filter { it.second.isNotEmpty() }
            .sortedBy { it.first.name.lowercase() }
        val total = sessions.sumOf { it.second.size }
        val sections = sessions.joinToString("\n") { (folder, files) ->
            val s = enc(folder.name)
            val rows = files.joinToString("\n") { f ->
                val url = "$s/${enc(f.name)}"
                """
                <li>
                  <div class="top">
                    <strong>${esc(f.name)}</strong>
                    <a class="btn" href="/d/$url" download>Descargar</a>
                  </div>
                  <div class="meta">${date.format(Date(f.lastModified()))} · ${wavInfo(f)} · ${mb(f.length())}</div>
                  <audio controls preload="none" src="/f/$url"></audio>
                </li>
                """.trimIndent()
            }
            """
            <section>
              <div class="top"><h2>${esc(folder.name)} <span>${files.size} · ${mb(files.sumOf { it.length() })}</span></h2>
                <a class="btn ghost" href="/z/$s">Descargar sesión (ZIP)</a></div>
              <ul>$rows</ul>
            </section>
            """.trimIndent()
        }
        val recording = RecorderState.currentFile?.let {
            """<p class="rec">● Grabando ahora: ${esc(File(it).parentFile!!.name)} / ${esc(File(it).name)} (aparecerá al terminar)</p>"""
        } ?: ""
        val body = if (total == 0) "<p class=\"empty\">Todavía no hay grabaciones.</p>" else sections
        return """
            <!doctype html>
            <html lang="es"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Ambiance Recorder</title>
            <style>
              :root { --bg:#f6f6f4; --card:#fff; --fg:#1d1d1b; --muted:#6b6b66; --line:#e3e3df; --accent:#c2410c; }
              @media (prefers-color-scheme: dark) { :root { --bg:#151514; --card:#1f1f1d; --fg:#ececea; --muted:#9a9a94; --line:#33332f; --accent:#fb923c; } }
              body { margin:0; background:var(--bg); color:var(--fg); font:15px/1.45 system-ui, sans-serif; }
              main { max-width:760px; margin:0 auto; padding:24px 16px; }
              h1 { font-size:22px; margin:0 0 4px; }
              .sub { color:var(--muted); margin:0 0 20px; }
              ul { list-style:none; padding:0; margin:0; display:grid; gap:12px; }
              li { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:14px; }
              .top { display:flex; justify-content:space-between; gap:12px; align-items:center; }
              strong { word-break:break-all; }
              .meta { color:var(--muted); font-size:13px; margin:4px 0 10px; }
              audio { width:100%; }
              .btn { flex:none; background:var(--accent); color:#fff; text-decoration:none; padding:6px 12px; border-radius:6px; font-size:14px; }
              .rec { color:var(--accent); font-weight:600; }
              .empty { color:var(--muted); }
              section { margin:0 0 32px; }
              h2 { font-size:18px; margin:0; } h2 span { color:var(--muted); font-weight:400; font-size:14px; }
              section > .top { margin-bottom:10px; flex-wrap:wrap; }
              .ghost { background:none; color:var(--accent); border:1px solid var(--accent); }
            </style></head>
            <body><main>
              <h1>Ambiance Recorder</h1>
              <p class="sub">${sessions.size} sesiones · $total grabaciones · WAV PCM 24 bit · <a href="/" style="color:inherit">actualizar</a></p>
              $recording
              $body
            </main></body></html>
        """.trimIndent()
    }

    private fun wavInfo(f: File) = Sessions.wavInfo(f)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun mb(bytes: Long) = Sessions.mb(bytes)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun sendText(out: OutputStream, status: Int, text: String) =
        send(out, status, "text/plain; charset=utf-8", text.toByteArray(), false)

    private fun send(out: OutputStream, status: Int, type: String, body: ByteArray, head: Boolean) {
        writeHead(out, status, type, body.size.toLong(), emptyList())
        if (!head) out.write(body)
    }

    private fun writeHead(out: OutputStream, status: Int, type: String, length: Long, extra: List<String>) {
        val reason = when (status) {
            200 -> "OK"; 206 -> "Partial Content"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"; else -> "Error"
        }
        val sb = StringBuilder("HTTP/1.1 $status $reason\r\n")
            .append("Content-Type: $type\r\n")
        if (length >= 0) sb.append("Content-Length: $length\r\n")
        sb.append("Connection: close\r\n")
            .append("Cache-Control: no-store\r\n")
        extra.forEach { sb.append(it).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > 8192) return null
        }
        return buf.toString("ISO-8859-1")
    }
}
