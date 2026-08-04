package com.volvo960.obdctl.ui.editor

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.volvo960.obdctl.R
import com.volvo960.obdctl.VolvoApp
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.data.ActuatorBehavior
import com.volvo960.obdctl.databinding.ActivityActuatorEditBinding
import kotlinx.coroutines.launch

/** Create/edit form for one actuator registry entry. */
class ActuatorEditActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ACTUATOR_ID = "actuator_id"
        const val EXTRA_PREFILL_COMMAND = "prefill_command"
    }

    private lateinit var binding: ActivityActuatorEditBinding
    private var editingId: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityActuatorEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.editor_title_new)

        binding.inputRepeatInterval.setText("2000")
        binding.inputAutoStopTimeout.setText("300000")
        binding.inputResponseTimeout.setText("4000")

        intent.getStringExtra(EXTRA_PREFILL_COMMAND)?.let { binding.inputCommand.setText(it) }

        editingId = intent.getLongExtra(EXTRA_ACTUATOR_ID, 0L)
        if (editingId != 0L) {
            title = getString(R.string.editor_title_edit)
            loadExisting(editingId)
        }

        binding.buttonSaveActuator.setOnClickListener { save() }
    }

    private fun loadExisting(id: Long) {
        val app = application as VolvoApp
        lifecycleScope.launch {
            val actuator = app.repository.getById(id) ?: return@launch
            binding.inputName.setText(actuator.name)
            binding.inputCommand.setText(actuator.command)
            binding.inputOffCommand.setText(actuator.offCommand.orEmpty())
            binding.inputRepeatInterval.setText(actuator.repeatIntervalMs.toString())
            binding.inputAutoStopTimeout.setText(actuator.autoStopTimeoutMs.toString())
            binding.inputResponseTimeout.setText(actuator.responseTimeoutMs.toString())
            binding.inputNotes.setText(actuator.notes)
            if (actuator.behavior == ActuatorBehavior.ONCE) {
                binding.radioOnce.isChecked = true
            } else {
                binding.radioHoldRepeat.isChecked = true
            }
        }
    }

    private fun save() {
        val name = binding.inputName.text?.toString()?.trim().orEmpty()
        val command = binding.inputCommand.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            Toast.makeText(this, R.string.error_name_required, Toast.LENGTH_SHORT).show()
            return
        }
        if (command.isEmpty()) {
            Toast.makeText(this, R.string.error_command_required, Toast.LENGTH_SHORT).show()
            return
        }
        val offCommand = binding.inputOffCommand.text?.toString()?.trim().orEmpty().ifEmpty { null }
        val behavior = if (binding.radioOnce.isChecked) ActuatorBehavior.ONCE else ActuatorBehavior.HOLD_REPEAT
        val repeatInterval = binding.inputRepeatInterval.text?.toString()?.toLongOrNull() ?: 2_000L
        val autoStopTimeout = binding.inputAutoStopTimeout.text?.toString()?.toLongOrNull() ?: 300_000L
        val responseTimeout = binding.inputResponseTimeout.text?.toString()?.toLongOrNull() ?: 4_000L
        val notes = binding.inputNotes.text?.toString().orEmpty()

        val app = application as VolvoApp
        lifecycleScope.launch {
            val existing = if (editingId != 0L) app.repository.getById(editingId) else null
            val actuator = Actuator(
                id = editingId,
                name = name,
                command = command,
                offCommand = offCommand,
                behavior = behavior,
                repeatIntervalMs = repeatInterval.coerceAtLeast(200L),
                autoStopTimeoutMs = autoStopTimeout.coerceAtLeast(1_000L),
                responseTimeoutMs = responseTimeout.coerceAtLeast(500L),
                warningAcknowledged = existing?.warningAcknowledged ?: false,
                notes = notes,
                createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            )
            app.repository.save(actuator)
            finish()
        }
    }
}
