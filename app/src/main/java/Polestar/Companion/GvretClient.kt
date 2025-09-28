package Polestar.Companion

import android.util.Log
import kotlinx.coroutines.*
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.CoroutineContext

enum class TxFormat { SIMPLE, LEN_PREFIXED }

data class GvretConfig(
    val ip: String = "192.168.4.1",
    val port: Int = 23,
    // Command bytes (tweak to match your firmware)
    val startStreamCmd: ByteArray = byteArrayOf(0xF1.toByte(), 0x00.toByte()),
    val canFrameCmd: Int = 0xF1,        // incoming CAN frame marker (common)
    val defaultTxCmd: Int = 0xF2,      // candidate transmit command; change if firmware differs
    val txFormat: TxFormat = TxFormat.SIMPLE
)

data class CanFrame(
    val bus: Int,
    val timestampUs: Long,
    val canId: Long,
    val dlc: Int,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CanFrame

        if (bus != other.bus) return false
        if (timestampUs != other.timestampUs) return false
        if (canId != other.canId) return false
        if (dlc != other.dlc) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = bus
        result = 31 * result + timestampUs.hashCode()
        result = 31 * result + canId.hashCode()
        result = 31 * result + dlc
        result = 31 * result + data.contentHashCode()
        return result
    }
}

class GvretClient(
    private val cfg: GvretConfig,
    private val coroutineContext: CoroutineContext = Dispatchers.IO
) {
    var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    var readJob: Job? = null

    var onConnected: (() -> Unit)? = null
    var onDisconnected: ((Throwable?) -> Unit)? = null
    var onCanFrame: ((CanFrame) -> Unit)? = null
    var onOtherGvretMessage: ((cmd: Int, payload: ByteArray) -> Unit)? = null

    suspend fun connectAndStart() = withContext(coroutineContext) {
        try {
            Log.d("GvretClient", "Connecting to ${cfg.ip}:${cfg.port}")
            socket = Socket(cfg.ip, cfg.port)
            input = socket!!.getInputStream()
            output = socket!!.getOutputStream()
            onConnected?.invoke()
            Log.d("GvretClient", "Connected successfully")
            
            // Don't send start stream command - SavvyCAN doesn't send it
            // The device should start streaming automatically
            Log.d("GvretClient", "Connected - waiting for automatic CAN stream")
            
            // Give the device a moment to start streaming
            delay(500)
            
            readJob = CoroutineScope(coroutineContext).launch { readLoop() }
        } catch (t: Throwable) {
            Log.e("GvretClient", "Connection failed", t)
            disconnect()
            onDisconnected?.invoke(t)
        }
    }

    fun disconnect() {
        try {
            readJob?.cancel()
            readJob = null
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        input = null
        output = null
        Log.d("GvretClient", "Disconnected")
    }

    private suspend fun sendBytes(bytes: ByteArray) = withContext(coroutineContext) {
        try {
            output?.write(bytes)
            output?.flush()
            Log.d("GvretClient", "Sent ${bytes.size} bytes: ${bytes.joinToString(" ") { "%02X".format(it) }}")
        } catch (t: Throwable) {
            Log.w("GvretClient", "sendBytes failed: $t")
        }
    }

    /**
     * Send a CAN frame. This will construct the packet according to cfg.txFormat
     * and cfg.defaultTxCmd. If your firmware needs a different CMD or format,
     * tweak GvretConfig accordingly.
     */
    suspend fun sendCanFrame(bus: Int, canId: Long, data: ByteArray, extended: Boolean = false) =
        withContext(coroutineContext) {
            val dlc = data.size.coerceIn(0, 8)
            when (cfg.txFormat) {
                TxFormat.SIMPLE -> {
                    // [CMD (1)] [bus (1)] [id (4 LE)] [dlc (1)] [data...]
                    val bb = ByteBuffer.allocate(1 + 1 + 4 + 1 + dlc).order(ByteOrder.LITTLE_ENDIAN)
                    bb.put(cfg.defaultTxCmd.toByte())
                    bb.put((bus and 0xFF).toByte())
                    bb.putInt(canId.toInt())
                    bb.put((dlc and 0xFF).toByte())
                    bb.put(data, 0, dlc)
                    sendBytes(bb.array())
                }
                TxFormat.LEN_PREFIXED -> {
                    // [CMD (1)] [len (1)] [bus,id,dlc,data...]
                    val payloadLen = 1 + 4 + 1 + dlc // bus + id + dlc + data
                    val bb = ByteBuffer.allocate(1 + 1 + payloadLen).order(ByteOrder.LITTLE_ENDIAN)
                    bb.put(cfg.defaultTxCmd.toByte())
                    bb.put((payloadLen and 0xFF).toByte())
                    bb.put((bus and 0xFF).toByte())
                    bb.putInt(canId.toInt())
                    bb.put((dlc and 0xFF).toByte())
                    bb.put(data, 0, dlc)
                    sendBytes(bb.array())
                }
            }
        }

    // -------------- read loop -------------
    private suspend fun readLoop() = withContext(coroutineContext) {
        val inStream = input ?: return@withContext
        val buffer = ByteArray(64)
        try {
            Log.d("GvretClient", "Starting read loop")
            var totalBytesRead = 0
            var timestampDetected = false
            var timestampSize = 0
            
            while (isActive && socket?.isConnected == true) {
                val len = inStream.read(buffer)
                if (len <= 0) {
                    Log.d("GvretClient", "Read loop ended - no more data (total bytes read: $totalBytesRead)")
                    break
                }
                totalBytesRead += len
                
                // Only log every 10th read to reduce spam, but log first few reads
                if (totalBytesRead <= 64 || totalBytesRead % 640 == 0) {
                    Log.d("GvretClient", "Read ${len} bytes (total: $totalBytesRead)")
                }
                
                var index = 0
                while (index < len) {
                    val cmd = buffer[index].toInt() and 0xFF
                    index++
                    
                    if (cmd == 0xF0) { // CAN frame command
                        // Try with timestamp first (4 bytes)
                        if (!timestampDetected && index + 8 < len) {
                            val maybeTimestamp = ByteBuffer.wrap(buffer, index, 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                            val maybeId = ByteBuffer.wrap(buffer, index + 4, 4)
                                .order(ByteOrder.LITTLE_ENDIAN)
                                .int
                            
                            if (maybeId in 0x100..0x7FF || (maybeId and 0x1FFFFFFF) != 0) {
                                timestampDetected = true
                                timestampSize = 4
                                Log.d("GvretClient", "Timestamp field detected")
                            } else {
                                timestampDetected = true
                                timestampSize = 0
                                Log.d("GvretClient", "No timestamp field detected")
                            }
                        }
                        
                        // Check if we have enough bytes for frame parsing
                        val frameStart = index + timestampSize
                        if (frameStart + 6 >= len) {
                            Log.w("GvretClient", "Incomplete CAN frame at buffer end")
                            break
                        }
                        
                        // Parse frame
                        val id = ByteBuffer.wrap(buffer, frameStart, 4)
                            .order(ByteOrder.LITTLE_ENDIAN)
                            .int
                        val dlc = buffer[frameStart + 4].toInt() and 0xFF
                        val flags = buffer[frameStart + 5].toInt() and 0xFF
                        
                        // Validate DLC
                        if (dlc > 8) {
                            Log.w("GvretClient", "Invalid DLC: $dlc")
                            index++
                            continue
                        }
                        
                        // Check if we have enough data bytes
                        if (frameStart + 6 + dlc > len) {
                            Log.w("GvretClient", "Incomplete CAN data at buffer end")
                            break
                        }
                        
                        val data = buffer.copyOfRange(
                            frameStart + 6,
                            frameStart + 6 + dlc
                        )
                        
                        Log.d("GvretClient", "CAN ID=0x${id.toString(16)} DLC=$dlc Data=${data.joinToString(" ") { "%02X".format(it) }} Flags=$flags")
                        
                        // Create CanFrame and invoke callback
                        val frame = CanFrame(
                            bus = 0, // Default bus
                            timestampUs = System.currentTimeMillis() * 1000,
                            canId = id.toLong(),
                            dlc = dlc,
                            data = data
                        )
                        onCanFrame?.invoke(frame)
                        
                        index += timestampSize + 6 + dlc
                    } else {
                        // Unknown command → skip one byte
                        Log.w("GvretClient", "Unknown GVRET cmd: 0x${cmd.toString(16)}")
                    }
                }
            }
        } catch (t: Throwable) {
            Log.e("GvretClient", "Read loop terminated", t)
            onDisconnected?.invoke(t)
        } finally {
            disconnect()
            onDisconnected?.invoke(null)
        }
    }

    // Helper: read exactly len bytes or return how many were read (can be < len on EOF)
    private fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r <= 0) return total
            total += r
        }
        return total
    }
    
}
