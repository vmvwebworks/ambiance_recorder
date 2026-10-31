package com.ambiance.recorder

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.log10

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var sessionCard: View
    private lateinit var sessionName: TextView
    private lateinit var newSession: Button
    private lateinit var sessionMenu: Button
    private lateinit var timer: TextView
    private lateinit var format: TextView
    private lateinit var meter: ProgressBar
    private lateinit var level: TextView
    private lateinit var record: Button
    private lateinit var server: Button
    private lateinit var url: TextView
    private lateinit var status: TextView
    private lateinit var listTitle: TextView
    private lateinit var list: ListView
    private lateinit var empty: TextView

    private var sessions = listOf<String>()
    private var files = listOf<File>()
    private var player: MediaPlayer? = null
    private var playing: File? = null
    private var wasRecording = false

    private val tick = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Desde Android 15 la app se dibuja bajo las barras del sistema: se deja hueco para ellas.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        sessionCard = findViewById(R.id.sessionCard)
        sessionName = findViewById(R.id.sessionName)
        newSession = findViewById(R.id.newSession)
        sessionMenu = findViewById(R.id.sessionMenu)
        timer = findViewById(R.id.timer)
        format = findViewById(R.id.format)
        meter = findViewById(R.id.meter)
        level = findViewById(R.id.level)
        record = findViewById(R.id.record)
        server = findViewById(R.id.server)
        url = findViewById(R.id.url)
        status = findViewById(R.id.status)
        listTitle = findViewById(R.id.listTitle)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)

        Sessions.migrate(this)

        record.setOnClickListener {
            if (RecorderState.recording) {
                startService(Intent(this, RecorderService::class.java).setAction(RecorderService.ACTION_STOP))
            } else if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                requestPermissions(perms.toTypedArray(), REQUEST_MIC)
            }
        }
        server.setOnClickListener {
            val intent = Intent(this, WebServerService::class.java)
            if (ServerState.url != null) stopService(intent) else startForegroundService(intent)
        }
        sessionCard.setOnClickListener { pickSession() }
        newSession.setOnClickListener { createSession() }
        sessionMenu.setOnClickListener { showSessionMenu() }
        list.setOnItemClickListener { _, _, position, _ -> showFileMenu(files[position]) }

        loadSessions()
    }

    private fun startRecording() {
        stopPlayback()
        startForegroundService(Intent(this, RecorderService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode == REQUEST_MIC && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecording()
        }
    }

    override fun onResume() {
        super.onResume()
        loadSessions()
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    // --- Sesiones ---

    private fun loadSessions() {
        sessions = Sessions.list(this)
        sessionName.text = "${Sessions.current(this)} ▾"
        loadFiles()
    }

    /** Lista de sesiones con su número de grabaciones, y al final la opción de crear otra. */
    private fun pickSession() {
        val labels = sessions.map { name ->
            val n = Sessions.recordings(Sessions.dir(this, name)).size
            (if (name == Sessions.current(this)) "✓ " else "    ") + "$name  ($n)"
        } + "+ Crear nueva sesión…"
        AlertDialog.Builder(this)
            .setTitle("Sesiones")
            .setItems(labels.toTypedArray()) { _, which ->
                if (which == sessions.size) createSession()
                else {
                    stopPlayback()
                    Sessions.setCurrent(this, sessions[which])
                    loadSessions()
                }
            }
            .show()
    }

    private fun createSession() {
        askName("Nueva sesión (ej. Bosque, Castillo…)", "", "Crear") { name ->
            if (Sessions.create(this, name)) {
                stopPlayback()
                Sessions.setCurrent(this, name)
                loadSessions()
            }
        }
    }

    private fun showSessionMenu() {
        val current = Sessions.current(this)
        AlertDialog.Builder(this)
            .setTitle(current)
            .setItems(arrayOf("Renombrar sesión", "Borrar sesión")) { _, which ->
                when (which) {
                    0 -> askName("Renombrar sesión", current, "Renombrar") { name ->
                        if (Sessions.rename(this, current, name)) {
                            Sessions.setCurrent(this, name)
                            loadSessions()
                        } else toast("Ya existe una sesión con ese nombre")
                    }
                    1 -> confirm("¿Borrar la sesión \"$current\" y sus ${files.size} grabaciones?") {
                        stopPlayback()
                        Sessions.delete(this, current)
                        Sessions.setCurrent(this, Sessions.DEFAULT)
                        loadSessions()
                    }
                }
            }
            .show()
    }

    // --- Grabaciones ---

    private fun loadFiles() {
        files = Sessions.recordings(Sessions.dir(this, Sessions.current(this)))
            .filter { it.absolutePath != RecorderState.currentFile }
        val date = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        list.adapter = object : ArrayAdapter<File>(this, R.layout.item_recording, R.id.title, files) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                val f = files[position]
                v.findViewById<TextView>(R.id.title).text = f.nameWithoutExtension
                v.findViewById<TextView>(R.id.meta).text =
                    "${date.format(Date(f.lastModified()))} · ${Sessions.wavInfo(f)} · ${Sessions.mb(f.length())}"
                v.findViewById<Button>(R.id.play).apply {
                    text = if (f == playing) "■" else "▶"
                    setOnClickListener { if (f == playing) stopPlayback() else play(f) }
                }
                v.findViewById<Button>(R.id.more).setOnClickListener { showFileMenu(f) }
                return v
            }
        }
        listTitle.text = "Grabaciones (${files.size})"
        list.visibility = if (files.isEmpty()) View.GONE else View.VISIBLE
        empty.visibility = if (files.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showFileMenu(f: File) {
        val options = arrayOf("Renombrar", "Mover a otra sesión", "Borrar")
        AlertDialog.Builder(this)
            .setTitle(f.nameWithoutExtension)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> askName("Renombrar grabación", f.nameWithoutExtension, "Renombrar") { name ->
                        val target = File(f.parentFile, "$name.wav")
                        if (target.exists()) toast("Ya existe una grabación con ese nombre")
                        else {
                            if (f == playing) stopPlayback()
                            f.renameTo(target)
                            loadFiles()
                        }
                    }
                    1 -> moveFile(f)
                    2 -> confirm("¿Borrar \"${f.nameWithoutExtension}\"?") {
                        if (f == playing) stopPlayback()
                        f.delete()
                        loadFiles()
                    }
                }
            }
            .show()
    }

    private fun moveFile(f: File) {
        val others = sessions.filter { it != Sessions.current(this) }
        if (others.isEmpty()) {
            toast("Crea otra sesión primero")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Mover a…")
            .setItems(others.toTypedArray()) { _, which ->
                val dir = Sessions.dir(this, others[which]).apply { mkdirs() }
                val target = File(dir, f.name)
                if (target.exists()) toast("Ya hay un fichero con ese nombre en ${others[which]}")
                else {
                    if (f == playing) stopPlayback()
                    f.renameTo(target)
                    loadFiles()
                }
            }
            .show()
    }

    private fun play(f: File) {
        stopPlayback()
        player = MediaPlayer().apply {
            setDataSource(f.absolutePath)
            setOnCompletionListener { stopPlayback() }
            prepare()
            start()
        }
        playing = f
        loadFiles()
    }

    private fun stopPlayback() {
        player?.release()
        player = null
        if (playing != null) {
            playing = null
            loadFiles()
        }
    }

    // --- Diálogos ---

    private fun askName(title: String, initial: String, action: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(initial)
            setSelection(initial.length)
        }
        val box = FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(box)
            .setPositiveButton(action) { _, _ ->
                val name = Sessions.clean(input.text.toString())
                if (name == null) toast("Nombre no válido") else if (name != initial) onOk(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun confirm(message: String, onOk: () -> Unit) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("Borrar") { _, _ -> onOk() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // --- Estado ---

    private fun refresh() {
        val rec = RecorderState.recording
        if (rec != wasRecording) {
            wasRecording = rec
            // Al terminar una toma aparece en la lista; durante la grabación no se cambia de sesión.
            loadFiles()
            sessionCard.isEnabled = !rec
            newSession.isEnabled = !rec
            sessionMenu.isEnabled = !rec
        }
        val s = RecorderState.elapsedMs / 1000
        timer.text = if (rec) "%02d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "00:00:00"
        if (rec) format.text = RecorderState.format
        record.text = if (rec) "■ Parar" else "● Grabar"
        // Mantener la pantalla encendida mientras graba ayuda a vigilar el nivel.
        if (rec) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val peak = RecorderState.peak
        val db = if (peak > 0f) 20 * log10(peak) else Float.NEGATIVE_INFINITY
        meter.progress = if (db.isInfinite()) 0 else ((db + 60f) * 10).toInt().coerceIn(0, 600)
        level.text = if (db.isInfinite()) "pico: -∞ dBFS" else "pico: %.1f dBFS".format(db)

        val serverUrl = ServerState.url
        server.text = if (serverUrl != null) "🌐 Detener servidor web" else "🌐 Activar servidor web"
        url.text = serverUrl?.let { "Abre en el PC: $it" } ?: ""
        url.visibility = if (serverUrl != null) View.VISIBLE else View.GONE

        status.text = listOfNotNull(
            RecorderState.error,
            ServerState.error,
            if (RecorderState.clipped) "⚠ Ha saturado (0 dBFS) en esta toma" else null,
        ).joinToString("\n")
    }

    companion object {
        private const val REQUEST_MIC = 1
    }
}
