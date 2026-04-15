package flare.client.app.util

import android.content.Context
import flare.client.app.R

object LogDecoder {

    fun decode(context: Context, rawLog: String): String {
        
        
        

        val parts = rawLog.split(": ", limit = 2)
        if (parts.size < 2) return rawLog

        val header = parts[0]
        val message = parts[1].trim()

        val headerParts = header.trim().split(" ")
        val level = headerParts.last()
        val time = if (headerParts.size > 1) headerParts[headerParts.size - 2] else ""

        val decodedMessage = when {
            message.contains("Создание туннеля", ignoreCase = true) || 
            message.contains("Creating tunnel", ignoreCase = true) -> {
                context.getString(R.string.log_decoding_tunnel_creation)
            }
            
            (message.contains("MTU", ignoreCase = true) && message.contains("STACK", ignoreCase = true)) -> {
                val mtuMatch = Regex("MTU\\s+(\\d+)").find(message)
                val stackMatch = Regex("STACK\\s+(\\w+)").find(message)
                val mtu = mtuMatch?.groupValues?.get(1) ?: "unknown"
                val stack = stackMatch?.groupValues?.get(1) ?: "unknown"
                context.getString(R.string.log_decoding_mtu_stack, mtu, stack)
            }

            message.contains("Фрагментация включена", ignoreCase = true) || 
            message.contains("Fragmentation enabled", ignoreCase = true) -> {
                context.getString(R.string.log_decoding_fragmentation)
            }

            else -> message
        }

        return if (time.isNotEmpty()) "$time $level: $decodedMessage" else "$level: $decodedMessage"
    }
}
