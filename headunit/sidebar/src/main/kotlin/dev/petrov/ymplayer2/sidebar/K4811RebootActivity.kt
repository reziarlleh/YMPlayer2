package dev.petrov.ymplayer2.sidebar

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/** One confirmed K4811 reboot attempt. Leaving this window cancels an unsent request. */
class K4811RebootActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var dialog: AlertDialog
    private var readyService: IBinder? = null
    private var bound = false
    private var waiting = false
    private var attempted = false

    private val timeout = Runnable {
        if (!closed.get()) unavailable()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (closed.get() || !waiting || attempted) return
            runWorker {
                try {
                    K4811RebootProtocol.verify(service)
                    main.post {
                        if (!closed.get() && waiting && !attempted) {
                            main.removeCallbacks(timeout)
                            waiting = false
                            readyService = service
                            showStatus("Служба K4811 готова. Перезагрузить устройство?")
                        }
                    }
                } catch (_: Exception) {
                    main.post { unavailable() }
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = unavailable()
        override fun onNullBinding(name: ComponentName) = unavailable()
        override fun onBindingDied(name: ComponentName) = unavailable()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        attempted = savedInstanceState?.getBoolean("rebootAttempted") ?: false
        dialog = AlertDialog.Builder(this)
            .setTitle("Перезагрузка K4811")
            .setMessage(if (attempted) UNKNOWN else CONNECTING)
            .setNegativeButton("Отмена") { _, _ -> closeRequest(); finish() }
            .setPositiveButton("Перезагрузить", null)
            .create()
        dialog.setOnCancelListener { closeRequest(); finish() }
        dialog.setOnDismissListener { closeRequest(); finish() }
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { confirmReboot() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus()
        showStatus(if (attempted) UNKNOWN else CONNECTING)
        if (!attempted) connect()
    }

    private fun connect() {
        waiting = true
        main.postDelayed(timeout, TIMEOUT_MS)
        try {
            bound = bindService(K4811RebootProtocol.serviceIntent(), connection, BIND_AUTO_CREATE)
            if (!bound) unavailable()
        } catch (_: RuntimeException) {
            unavailable()
        }
    }

    private fun confirmReboot() {
        val service = readyService ?: return
        if (closed.get() || attempted) return
        attempted = true
        showStatus("Запрос перезагрузки отправляется…")
        main.postDelayed(timeout, TIMEOUT_MS)
        if (!runWorker {
            if (closed.get()) return@runWorker
            try {
                if (!K4811RebootProtocol.reboot(service, closed::get)) return@runWorker
                main.post {
                    if (!closed.get()) {
                        main.removeCallbacks(timeout)
                        unbind()
                        showStatus("Служба приняла запрос. Ожидайте перезагрузку K4811.")
                    }
                }
            } catch (_: Exception) {
                main.post { unavailable() }
            }
        }) unavailable()
    }

    private fun unavailable() {
        if (closed.get()) return
        main.removeCallbacks(timeout)
        waiting = false
        readyService = null
        unbind()
        showStatus(if (attempted) UNKNOWN else "Служба перезагрузки K4811 недоступна. Запрос не отправлен.")
    }

    private fun showStatus(message: String) {
        dialog.setMessage(message)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = readyService != null && !attempted
    }

    private fun runWorker(action: () -> Unit): Boolean = try {
        worker.execute { action() }
        true
    } catch (_: RejectedExecutionException) {
        false
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        try { unbindService(connection) } catch (_: IllegalArgumentException) { }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("rebootAttempted", attempted)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        closeRequest()
        super.onStop()
        finish()
    }

    override fun onDestroy() {
        closeRequest()
        super.onDestroy()
    }

    private fun closeRequest() {
        if (!closed.compareAndSet(false, true)) return
        main.removeCallbacksAndMessages(null)
        unbind()
        worker.shutdownNow()
    }

    companion object {
        private const val TIMEOUT_MS = 5_000L
        private const val CONNECTING = "Подключение к службе K4811…"
        private const val UNKNOWN = "Результат перезагрузки неизвестен. Повторный запрос не отправляется."

        fun open(context: Context): Boolean {
            if (!K4811Controls.isTargetDevice()) return false
            return try {
                context.startActivity(Intent(context, K4811RebootActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                true
            } catch (_: RuntimeException) {
                false
            }
        }
    }
}
