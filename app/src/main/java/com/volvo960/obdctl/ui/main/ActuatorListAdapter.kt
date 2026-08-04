package com.volvo960.obdctl.ui.main

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.volvo960.obdctl.R
import com.volvo960.obdctl.data.Actuator
import com.volvo960.obdctl.data.ActuatorBehavior
import com.volvo960.obdctl.databinding.ItemActuatorBinding

class ActuatorListAdapter(
    private val isHeld: (Long) -> Boolean,
    private val onToggleHold: (Actuator, Boolean) -> Unit,
    private val onSendOnce: (Actuator) -> Unit,
    private val onEdit: (Actuator) -> Unit,
    private val onDelete: (Actuator) -> Unit,
) : RecyclerView.Adapter<ActuatorListAdapter.ViewHolder>() {

    private var items: List<Actuator> = emptyList()

    fun submit(newItems: List<Actuator>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemActuatorBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemActuatorBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(actuator: Actuator) {
            val ctx = binding.root.context
            binding.textActuatorName.text = actuator.name

            val behaviorLabel = ctx.getString(
                if (actuator.behavior == ActuatorBehavior.ONCE) R.string.behavior_once else R.string.behavior_hold_repeat
            )
            binding.textActuatorMeta.text = if (actuator.behavior == ActuatorBehavior.HOLD_REPEAT) {
                "$behaviorLabel · ${actuator.repeatIntervalMs} ${ctx.getString(R.string.unit_ms)}"
            } else {
                behaviorLabel
            }

            val isOnce = actuator.behavior == ActuatorBehavior.ONCE
            binding.buttonSendOnce.visibility = if (isOnce) View.VISIBLE else View.GONE
            binding.switchHold.visibility = if (isOnce) View.GONE else View.VISIBLE

            binding.buttonSendOnce.setOnClickListener { onSendOnce(actuator) }

            binding.switchHold.setOnCheckedChangeListener(null)
            binding.switchHold.isChecked = isHeld(actuator.id)
            binding.switchHold.setOnCheckedChangeListener { _, checked -> onToggleHold(actuator, checked) }

            binding.buttonEditActuator.setOnClickListener { onEdit(actuator) }
            binding.buttonDeleteActuator.setOnClickListener { onDelete(actuator) }
        }
    }
}
