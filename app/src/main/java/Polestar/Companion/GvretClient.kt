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
    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var readJob: Job? = null

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
            
            // send start-stream (configurable)
            sendBytes(cfg.startStreamCmd)
            Log.d("GvretClient", "Sent start stream command")
            
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
        val single = ByteArray(1)
        try {
            Log.d("GvretClient", "Starting read loop")
            while (isActive && socket?.isConnected == true) {
                val r = inStream.read(single)
                if (r <= 0) break
                val cmd = single[0].toInt() and 0xFF
                
                when (cmd) {
                    cfg.canFrameCmd -> {
                        // Common GVRET/ESP32RET CAN format: [F1] [bus(1)] [timestamp(4LE)] [id(4LE)] [dlc(1)] [data..]
                        val header = ByteArray(1 + 4 + 4 + 1) // bus + ts + id + dlc
                        // we already consumed the cmd byte; now read header bytes
                        val got = readFully(inStream, header)
                        if (got < header.size) break
                        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        val bus = (bb.get().toInt() and 0xFF)
                        val timestamp = (bb.int.toLong() and 0xFFFFFFFFL)
                        val canId = (bb.int.toLong() and 0xFFFFFFFFL)
                        val dlc = (bb.get().toInt() and 0x0F)
                        val data = ByteArray(dlc)
                        if (dlc > 0) {
                            val gotData = readFully(inStream, data)
                            if (gotData < dlc) break
                        }
                        
                        val frame = CanFrame(bus, timestamp, canId, dlc, data)
                        Log.d("GvretClient", "Received CAN frame: bus=$bus id=0x${canId.toString(16)} dlc=$dlc data=${data.joinToString(" ") { "%02X".format(it) }}")
                        onCanFrame?.invoke(frame)
                    }
                    else -> {
                        // Generic fallback: many GVRET commands are length prefixed (1 or 2 bytes).
                        // We'll read a single length byte and that many payload bytes (safe fallback).
                        val lenBuf = ByteArray(1)
                        val g = inStream.read(lenBuf)
                        if (g <= 0) break
                        val L = lenBuf[0].toInt() and 0xFF
                        val payload = ByteArray(L)
                        if (L > 0) {
                            val gotP = readFully(inStream, payload)
                            if (gotP < L) break
                        }
                        Log.d("GvretClient", "Received other GVRET message: cmd=0x${cmd.toString(16)} len=$L")
                        onOtherGvretMessage?.invoke(cmd, payload)
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
