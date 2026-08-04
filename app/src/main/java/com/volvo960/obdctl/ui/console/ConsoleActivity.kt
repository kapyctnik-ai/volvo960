package com.volvo960.obdctl.ui.console

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
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
        val command = binding.inputRawCommand.text?.toString()?.trim().orEmpty()
        if (command.isEmpty()) return
        binding.inputRawCommand.setText("")
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

    private fun appendLine(text: String) {
        val current = binding.textConsoleOutput.text.toString()
        val base = if (current == getString(R.string.console_no_response_yet)) "" else current
        binding.textConsoleOutput.text = if (base.isEmpty()) text else "$base\n$text"
    }
}
