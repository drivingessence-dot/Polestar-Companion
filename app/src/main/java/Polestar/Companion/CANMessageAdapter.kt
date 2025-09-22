package Polestar.Companion

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import Polestar.Companion.databinding.ItemCanMessageBinding

class CANMessageAdapter : RecyclerView.Adapter<CANMessageAdapter.CANMessageViewHolder>() {
    
    private val messages = mutableListOf<CANMessage>()
    
    class CANMessageViewHolder(private val binding: ItemCanMessageBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(message: CANMessage) {
            binding.textTimestamp.text = message.getFormattedTimestamp()
            binding.textCanId.text = message.getIdAsHex()
            binding.textDataLength.text = message.length.toString()
            binding.textDataHex.text = message.getDataAsHex()
            binding.textExtended.text = if (message.isExtended) "29-bit" else "11-bit"
            binding.textRtr.text = if (message.isRTR) "RTR" else "Data"
            
            // Color code based on CAN ID ranges
            val idColor = when {
                message.id in 0x1FFF0100..0x1FFF01FF -> 0xFF4CAF50.toInt() // Green for battery
                message.id in 0x1FFF0200..0x1FFF02FF -> 0xFF2196F3.toInt() // Blue for charging
                message.id in 0x1FFF0300..0x1FFF03FF -> 0xFFFF9800.toInt() // Orange for climate
                message.id in 0x1FFF0400..0x1FFF04FF -> 0xFF9C27B0.toInt() // Purple for dynamics
                message.id in 0x1FFF0500..0x1FFF05FF -> 0xFFE91E63.toInt() // Pink for infotainment
                else -> 0xFF607D8B.toInt() // Default gray
            }
            
            binding.textCanId.setTextColor(idColor)
        }
    }
    
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CANMessageViewHolder {
        val binding = ItemCanMessageBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CANMessageViewHolder(binding)
    }
    
    override fun onBindViewHolder(holder: CANMessageViewHolder, position: Int) {
        holder.bind(messages[position])
    }
    
    override fun getItemCount(): Int = messages.size
    
    fun updateMessages(newMessages: List<CANMessage>) {
        messages.clear()
        messages.addAll(newMessages)
        notifyDataSetChanged()
    }
    
    fun clearMessages() {
        messages.clear()
        notifyDataSetChanged()
    }
    
    fun addMessage(message: CANMessage) {
        messages.add(message)
        notifyItemInserted(messages.size - 1)
    }
}
