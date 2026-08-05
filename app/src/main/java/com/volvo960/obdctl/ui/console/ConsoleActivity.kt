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
import com.volvo960.obdctl.ui.editor.ActuatorEditActivity
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
         * Reproduces how a tool already working on this car asks Motronic for
         * live data: it does not use generic OBD-II Mode 01 at all, but its own
         * `AE01` request under Volvo's keyword-D3B0 protocol.
         *
         * `ATSI` is the important line. Without it the bus is never initialised
         * — `ATKW` came back `1:-- 2:--` instead of the protocol's `1:D3 2:B0`
         * and every request to the car failed. The same slow init is what the
         * working fan sequence uses.
         *
         * `ATKW` appears twice on purpose: before the init it should be empty,
         * after it the keyword proves the ECU is actually talking.
         */
        val MOTRONIC_PROBE = listOf(
            "ATPC",
            "ATD",
            "ATZ",
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH1",
            "ATAL",
            "ATSP 3",
            "ATKW0",
            "ATSR 13",
            "ATAT 1",
            "ATST 32",
            "ATIIA 7A",
            "ATWM 82 7A 13 A1",
            "ATSI",
            "ATKW",
            "ATSH 82 7A 13",
            "A1",
            "ATSH 83 7A 13",
            "AE01",
            "AE01",
            "AE02",
        )
    }

    private lateinit var binding: ActivityConsoleBinding
    private var lastCommand: String? = null

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
        binding.buttonSaveAsActuator.setOnClickListener {
            val cmd = lastCommand ?: return@setOnClickListener
            startActivity(
                Intent(this, ActuatorEditActivity::class.java)
                    .putExtra(ActuatorEditActivity.EXTRA_PREFILL_COMMAND, cmd)
            )
        }
        binding.buttonRunMotronicProbe.setOnClickListener { runScript(MOTRONIC_PROBE) }
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
                        is ConnectionState.Connected -> getString(R.string.status_connected, state.deviceName)
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
                    lastCommand = command
                    binding.buttonSaveAsActuator.isEnabled = true
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
            lastCommand = commands.lastOrNull()
            binding.buttonSaveAsActuator.isEnabled = lastCommand != null
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
