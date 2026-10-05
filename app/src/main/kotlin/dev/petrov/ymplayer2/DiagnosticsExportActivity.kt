package dev.petrov.ymplayer2

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.petrov.ymplayer2.localization.*
import kotlinx.coroutines.launch

/** Android 9 saves through SAF without granting access to the whole storage device.
 * ActivityResultRegistry preserves the pending picker across Activity recreation. */
class DiagnosticsExportActivity : ComponentActivity() {
    private val name get() = intent.getStringExtra(FILE_NAME) ?: "YMPlayer2-diagnostics.txt"
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) finish()
        else lifecycleScope.launch {
            val result = (application as PlayerApplication).diagnostics.exportDocument(uri, name)
            Toast.makeText(this@DiagnosticsExportActivity, result, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            try { destination.launch(name) }
            catch (_: ActivityNotFoundException) {
                Toast.makeText(this, tr(Msg.msg_920c04770d7a), Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    companion object { const val FILE_NAME = "diagnostic_file_name" }
}
