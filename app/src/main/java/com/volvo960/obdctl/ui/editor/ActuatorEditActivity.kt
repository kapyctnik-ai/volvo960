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

        /**
         * ELM327 setup for talking to the engine ECU over Volvo's own
         * keyword-D3B0 K-line protocol rather than generic OBD-II. Only the
         * adapter-side setup is filled in — the actual control bytes are
         * car-specific and must be captured from a tool known to work on the
         * car (see README), never guessed.
         */
        private val PRESET_VOLVO_M44_INIT = """
            # Volvo Motronic M4.4 / KWP D3B0 / ECU 7A
            # Настройка адаптера. Байты команды подставь из лога.
            ATZ
            ATE0
            ATL0
            ATSP3
            ATKW0
            ATSH 84 7A F1
        """.trimIndent()

        private val PRESET_VOLVO_M44_COMMAND = """
            # Сюда — реальную команду теста вентилятора (hex).
            # Снимается из btsnoop-лога 850 OBD-II, см. README.
            # XX XX XX
        """.trimIndent()
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

        binding.buttonPresetVolvoM44.setOnClickListener {
            binding.inputInitScript.setText(PRESET_VOLVO_M44_INIT)
            if (binding.inputCommand.text.isNullOrBlank()) {
                binding.inputCommand.setText(PRESET_VOLVO_M44_COMMAND)
            }
            binding.radioHoldRepeat.isChecked = true
            Toast.makeText(this, R.string.preset_applied, Toast.LENGTH_LONG).show()
        }

        binding.buttonSaveActuator.setOnClickListener { save() }
    }

    private fun loadExisting(id: Long) {
        val app = application as VolvoApp
        lifecycleScope.launch {
            val actuator = app.repository.getById(id) ?: return@launch
            binding.inputName.setText(actuator.name)
            binding.inputInitScript.setText(actuator.initScript)
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
        val initScript = binding.inputInitScript.text?.toString()?.trim().orEmpty()
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
                initScript = initScript,
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
