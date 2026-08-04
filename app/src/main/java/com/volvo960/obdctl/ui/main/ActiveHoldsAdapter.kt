package com.volvo960.obdctl.ui.main

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.volvo960.obdctl.R
import com.volvo960.obdctl.databinding.ItemActiveHoldBinding
import com.volvo960.obdctl.service.HoldStatus

class ActiveHoldsAdapter(
    private val onStop: (Long) -> Unit,
) : RecyclerView.Adapter<ActiveHoldsAdapter.ViewHolder>() {

    private var items: List<HoldStatus> = emptyList()

    fun submit(newItems: List<HoldStatus>) {
        items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemActiveHoldBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemActiveHoldBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(status: HoldStatus) {
            val ctx = binding.root.context
            binding.textHoldName.text = status.actuatorName

            val now = System.currentTimeMillis()
            val staleSec = (now - status.lastSuccessAt) / 1000
            if (status.lastError != null && staleSec > 0) {
                binding.textHoldStatus.text = ctx.getString(R.string.hold_status_stale, staleSec.toInt())
                binding.textHoldStatus.setTextColor(ctx.getColor(R.color.status_failed))
            } else {
                val intervalSec = (status.repeatIntervalMs / 1000).toInt().coerceAtLeast(1)
                binding.textHoldStatus.text = ctx.getString(R.string.hold_status_running, intervalSec)
                binding.textHoldStatus.setTextColor(ctx.getColor(R.color.status_connected))
            }

            binding.buttonStopHold.setOnClickListener { onStop(status.actuatorId) }
        }
    }
}
