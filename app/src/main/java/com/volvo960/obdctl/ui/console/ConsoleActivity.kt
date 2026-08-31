package com.volvo960.obdctl.ui.console

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.databinding.ActivityConsoleBinding
import com.volvo960.obdctl.transport.ConnectionState
import com.volvo960.obdctl.transport.Elm327Transport
import kotlinx.coroutines.launch

/**
 * Raw-command console: the tool used to hunt for new actuator commands by
 * hand before they become permanent registry entries. Every request and
 * response goes through the same [Elm327Transport.sendRaw] queue as actuator
 * traffic, and both get written to [com.volvo960.obdctl.transport.CommandLogger].
 */
class ConsoleActivity : AppCompatActivity() {

    private companion object {
        /**
         * What the car will and won't answer, in one run. `0100`/`0120`/`0140`
         * are the supported-PID bitmaps; the rest are the readings the fuel
         * computation depends on, asked directly so a bitmap that lies (some
         * ECUs do) is caught immediately.
         */
        val CAPABILITY_PROBE = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATH0",
            "ATSP3",
            "0100",
            "0120",
            "0140",
            "0105",
            "010C",
            "010D",
            "010B",
            "010F",
            "0110",
            "0106",
            "0107",
            "012F",
            "015E",
        )
    }

    private lateinit var binding: ActivityConsoleBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityConsoleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.console_title)

        binding.buttonSendRaw.setOnClickListener { sendCurrentInput() }
        binding.inputRawCommand.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else {
                false
            }
        }
        binding.buttonRunProbe.setOnClickListener { runScript(CAPABILITY_PROBE) }
        binding.buttonShareLog.setOnClickListener { shareLog() }
        binding.buttonClearLog.setOnClickListener {
            (application as VolvoApp).logger.clear()
            Toast.makeText(this, R.string.log_cleared, Toast.LENGTH_SHORT).show()
        }

        observeConnection()
    }

    private fun observeConnection() {
        val app = application as VolvoApp
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.transport.connectionState.collect { state ->
                    binding.buttonSendRaw.isEnabled = state is ConnectionState.Connected
                    binding.textConsoleStatus.text = when (state) {
                        is ConnectionState.Connected -> getString(R.string.dash_status_connected, state.deviceName)
                        else -> getString(R.string.console_not_connected)
                    }
                }
            }
        }
    }

    private fun sendCurrentInput() {
        val app = application as VolvoApp
        val raw = binding.inputRawCommand.text?.toString().orEmpty()
        val commands = raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (commands.isEmpty()) return
        binding.inputRawCommand.setText("")
        if (commands.size > 1) {
            runScript(commands)
            return
        }
        val command = commands.first()
        appendLine("> $command")
        lifecycleScope.launch {
            when (val result = app.transport.sendRaw(command)) {
                is Elm327Transport.CommandResult.Success -> {
                    appendLine(result.response.ifEmpty { "(пустой ответ)" })
                }
                is Elm327Transport.CommandResult.Error -> appendLine("ошибка: ${result.message}")
            }
        }
    }

    /**
     * Runs a whole sequence under one lock and prints each exchange, so an
     * unknown reply format can be read off against the request that produced
     * it. Nothing else can slip onto the bus mid-run.
     */
    private fun runScript(commands: List<String>) {
        val app = application as VolvoApp
        appendLine("=== прогон, ${commands.size} команд ===")
        lifecycleScope.launch {
            val transcript = app.transport.sendScriptVerbose(commands, timeoutMs = 9_000L)
            for ((command, reply) in transcript) {
                appendLine("> $command")
                appendLine("  $reply")
            }
            appendLine("=== конец прогона ===")
        }
    }

    /**
     * Hands the raw traffic log to another app. Everything the transport ever
     * sends or receives lands there, so it is the fastest way to see what an
     * actuator actually did on the wire.
     */
    private fun shareLog() {
        val logFile = (application as VolvoApp).logger.file()
        if (!logFile.exists() || logFile.length() == 0L) {
            Toast.makeText(this, R.string.log_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", logFile)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, getString(R.string.share_log_title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, getString(R.string.share_log_title)))
    }

    private fun appendLine(text: String) {
        val current = binding.textConsoleOutput.text.toString()
        val base = if (current == getString(R.string.console_no_response_yet)) "" else current
        binding.textConsoleOutput.text = if (base.isEmpty()) text else "$base\n$text"
    }
}
