package run.bestmodel.dictate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var store: ModelStore
    private lateinit var prefs: Prefs
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            refresh()
            if (Download.running) main.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = ModelStore(this)
        prefs = Prefs(this)

        button(R.id.download).setOnClickListener { startDownload() }
        button(R.id.import_model).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_IMPORT)
        }
        button(R.id.grant_mic).setOnClickListener { requestRuntimePermissions() }
        button(R.id.grant_overlay).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button(R.id.grant_accessibility).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        button(R.id.toggle_bubble).setOnClickListener { toggleBubble() }

        bindSwitch(R.id.auto_insert, prefs.autoInsert) { prefs.autoInsert = it }
        bindSwitch(R.id.remove_fillers, prefs.removeFillers) { prefs.removeFillers = it }
        bindSwitch(R.id.voice_commands, prefs.voiceCommands) { prefs.voiceCommands = it }
    }

    override fun onResume() {
        super.onResume()
        main.post(poll)
    }

    override fun onPause() {
        main.removeCallbacks(poll)
        super.onPause()
    }

    private fun refresh() {
        val ready = store.isReady()
        val mb = store.totalBytes / 1_000_000
        text(R.id.model_status).text = when {
            Download.running -> "Baixando… ${Download.bytes / 1_000_000} de $mb MB"
            ready -> "✓ Modelo pronto (Parakeet TDT 0.6B v3, int8)"
            Download.error != null -> "Erro: ${Download.error}"
            else -> "Modelo não baixado (~$mb MB, uma vez só)"
        }
        findViewById<ProgressBar>(R.id.progress).apply {
            visibility = if (Download.running) View.VISIBLE else View.GONE
            max = 1000
            progress = (Download.bytes * 1000 / store.totalBytes).toInt()
        }
        button(R.id.download).isEnabled = !ready && !Download.running
        button(R.id.import_model).isEnabled = !ready && !Download.running

        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val overlay = Settings.canDrawOverlays(this)
        val accessibility = InsertService.instance != null
        status(R.id.grant_mic, mic, "Microfone")
        status(R.id.grant_overlay, overlay, "Mostrar sobre outros apps")
        status(R.id.grant_accessibility, accessibility, "Digitar no campo (opcional)")

        button(R.id.toggle_bubble).apply {
            isEnabled = ready && mic && overlay
            text = if (BubbleService.running) "Fechar bolha" else "Abrir bolha"
        }
    }

    private fun startDownload() {
        if (Download.running) return
        Download.running = true
        Download.error = null
        Download.bytes = 0
        Thread {
            try {
                store.download { Download.bytes = it }
            } catch (e: Exception) {
                Download.error = e.message ?: e.toString()
            } finally {
                Download.running = false
            }
        }.start()
        main.post(poll)
    }

    @Deprecated("Activity result API kept minimal on purpose (no AndroidX activity dependency)")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val tree = data?.data
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK || tree == null) return
        Download.running = true
        Download.error = null
        Thread {
            try {
                store.importFrom(this, tree)
            } catch (e: Exception) {
                Download.error = e.message ?: e.toString()
            } finally {
                Download.running = false
            }
        }.start()
        main.post(poll)
    }

    private fun requestRuntimePermissions() {
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun toggleBubble() {
        val intent = Intent(this, BubbleService::class.java)
        if (BubbleService.running) {
            stopService(intent)
        } else {
            // Must start while this screen is visible: Android only lets a foreground
            // service use the mic if it was started from the foreground.
            startForegroundService(intent)
            Toast.makeText(this, "Toque na bolha para falar", Toast.LENGTH_SHORT).show()
            moveTaskToBack(true)
        }
        main.postDelayed({ refresh() }, 300)
    }

    private fun status(id: Int, ok: Boolean, label: String) {
        button(id).text = if (ok) "✓ $label" else "Permitir: $label"
        button(id).isEnabled = !ok
    }

    private fun bindSwitch(id: Int, initial: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun button(id: Int) = findViewById<Button>(id)
    private fun text(id: Int) = findViewById<TextView>(id)

    /** Survives the activity being recreated while a download runs. */
    private object Download {
        @Volatile var running = false
        @Volatile var bytes = 0L
        @Volatile var error: String? = null
    }

    private companion object {
        const val REQUEST_IMPORT = 10
        const val REQUEST_PERMISSIONS = 11
    }
}
