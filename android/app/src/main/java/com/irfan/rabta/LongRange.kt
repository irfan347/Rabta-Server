package com.irfan.rabta

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
import android.bluetooth.le.AdvertisingSetParameters
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper

/**
 * Long Range chat channel using Bluetooth 5 "Coded PHY" (~3-4x normal range).
 * Google Nearby doesn't use Coded PHY, so this runs alongside it as a second radio path.
 *
 * Connectionless: messages are broadcast in extended advertisements and picked up by
 * any phone scanning nearby. Each message is repeated for a few seconds so it isn't missed.
 *
 * Packet (manufacturer data, company 0xFFFF):
 *   'R''B' | type | ...
 *   HELLO: type=1 | userId(6) | name(utf8, <=20 bytes)
 *   MSG:   type=2 | msgId(6) | hopsLeft(1) | fromId(6) | toId(6) | nameLen(1) | name | body(utf8)
 */
class LongRange(private val ctx: Context, private val listener: Listener) {

    interface Listener {
        fun onLrMessage(id: String, from: String, to: String, name: String, hopsLeft: Int, body: String)
        fun onLrHello(userId: String, name: String, rssi: Int)
        fun onLrStatus(msg: String)
    }

    companion object {
        private const val COMPANY = 0xFFFF
        private val MAGIC = byteArrayOf(0x52, 0x42) // "RB"
        private const val T_HELLO: Byte = 1
        private const val T_MSG: Byte = 2
        private const val ROUND_MS = 1500L   // switch broadcast content every 1.5 s
        private const val ROUNDS = 8         // each message is on air for ~8 rounds
        private const val MAX_QUEUE = 20

        fun supported(ctx: Context): Boolean {
            val a = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter ?: return false
            return try { a.isEnabled && a.isLeCodedPhySupported && a.isLeExtendedAdvertisingSupported } catch (_: Exception) { false }
        }

        fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        fun toHex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }

        /** UTF-8 bytes of s, cut on a character boundary so they fit maxBytes. */
        fun utf8Fit(s: String, maxBytes: Int): ByteArray {
            if (maxBytes <= 0) return ByteArray(0)
            var b = s.toByteArray(Charsets.UTF_8)
            if (b.size <= maxBytes) return b
            var end = s.length
            while (end > 0) {
                end--
                if (end > 0 && Character.isHighSurrogate(s[end - 1])) end--
                b = s.substring(0, end).toByteArray(Charsets.UTF_8)
                if (b.size <= maxBytes) return b
            }
            return ByteArray(0)
        }
    }

    private class Out(val data: ByteArray, var left: Int)

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Out>()
    private var helloData = ByteArray(0)
    private var advSet: AdvertisingSet? = null
    private var round = 0
    private var idx = 0
    var running = false
        private set
    /** Max bytes of our packet that fit in one extended advertisement. */
    var maxPacket = 180
        private set

    private fun adapter() = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    @SuppressLint("MissingPermission")
    fun start(userId: String, name: String): Boolean {
        val a = adapter() ?: return false
        if (!supported(ctx)) return false
        // AD header for manufacturer data = 4 bytes (len, type, company id x2); keep some margin
        maxPacket = try { (a.leMaximumAdvertisingDataLength - 8).coerceIn(31, 240) } catch (_: Exception) { 180 }
        helloData = MAGIC + byteArrayOf(T_HELLO) + hexToBytes(userId) + utf8Fit(name, 20)
        return try {
            val params = AdvertisingSetParameters.Builder()
                .setLegacyMode(false)
                .setConnectable(false)
                .setScannable(false)
                .setPrimaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setSecondaryPhy(BluetoothDevice.PHY_LE_CODED)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_HIGH)
                .build()
            a.bluetoothLeAdvertiser?.startAdvertisingSet(params, adData(helloData), null, null, null, advCb)
                ?: return false

            val filter = ScanFilter.Builder()
                .setManufacturerData(COMPANY, MAGIC, byteArrayOf(-1, -1))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setLegacy(false)
                .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
                .build()
            a.bluetoothLeScanner?.startScan(listOf(filter), settings, scanCb)
            running = true
            handler.postDelayed(rotate, ROUND_MS)
            true
        } catch (e: Exception) {
            listener.onLrStatus("Long Range start failed: ${e.message}")
            false
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(rotate)
        val a = adapter()
        try { a?.bluetoothLeAdvertiser?.stopAdvertisingSet(advCb) } catch (_: Exception) {}
        try { a?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        advSet = null; running = false; queue.clear()
    }

    /** Queue a chat message for long-range broadcast. Returns false if it's too long to fit. */
    fun broadcast(idHex: String, fromHex: String, toHex: String, name: String, hopsLeft: Int, body: String): Boolean {
        if (!running) return false
        if (idHex.length != 12 || fromHex.length != 12 || toHex.length != 12) return false
        val nameB = utf8Fit(name, 16)
        val head = MAGIC + byteArrayOf(T_MSG) + hexToBytes(idHex) +
            byteArrayOf(hopsLeft.coerceIn(0, 255).toByte()) + hexToBytes(fromHex) + hexToBytes(toHex) +
            byteArrayOf(nameB.size.toByte()) + nameB
        val bodyB = body.toByteArray(Charsets.UTF_8)
        if (head.size + bodyB.size > maxPacket) return false
        synchronized(queue) {
            queue.add(Out(head + bodyB, ROUNDS))
            while (queue.size > MAX_QUEUE) queue.removeAt(0)
        }
        return true
    }

    /** How many body bytes a message can have (shown to the user as a letter limit). */
    fun maxBodyBytes(name: String) = maxPacket - (2 + 1 + 6 + 1 + 6 + 6 + 1 + utf8Fit(name, 16).size)

    private fun adData(packet: ByteArray) = AdvertiseData.Builder()
        .setIncludeDeviceName(false)
        .setIncludeTxPowerLevel(false)
        .addManufacturerData(COMPANY, packet)
        .build()

    // every round: put the next queued message on air (and a "hello" every 4th round)
    private val rotate = object : Runnable {
        override fun run() {
            if (!running) return
            round++
            var packet = helloData
            synchronized(queue) {
                if (queue.isNotEmpty() && round % 4 != 0) {
                    if (idx >= queue.size) idx = 0
                    val o = queue[idx]
                    packet = o.data
                    o.left--
                    if (o.left <= 0) queue.removeAt(idx) else idx++
                }
            }
            try { advSet?.setAdvertisingData(adData(packet)) } catch (_: Exception) {}
            handler.postDelayed(this, ROUND_MS)
        }
    }

    private val advCb = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(set: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) advSet = set
            else listener.onLrStatus("Long Range broadcast failed (code $status)")
        }
        override fun onAdvertisingSetStopped(set: AdvertisingSet?) { advSet = null }
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val b = result.scanRecord?.getManufacturerSpecificData(COMPANY) ?: return
            parse(b, result.rssi)
        }
        override fun onScanFailed(errorCode: Int) { listener.onLrStatus("Long Range scan failed (code $errorCode)") }
    }

    private fun parse(b: ByteArray, rssi: Int) {
        if (b.size < 3 || b[0] != MAGIC[0] || b[1] != MAGIC[1]) return
        when (b[2]) {
            T_HELLO -> {
                if (b.size < 9) return
                val uid = toHex(b.copyOfRange(3, 9))
                val name = String(b, 9, b.size - 9, Charsets.UTF_8)
                listener.onLrHello(uid, name, rssi)
            }
            T_MSG -> {
                if (b.size < 23) return
                val id = toHex(b.copyOfRange(3, 9))
                val hops = b[9].toInt() and 0xFF
                val from = toHex(b.copyOfRange(10, 16))
                val to = toHex(b.copyOfRange(16, 22))
                val nameLen = b[22].toInt() and 0xFF
                if (23 + nameLen > b.size) return
                val name = String(b, 23, nameLen, Charsets.UTF_8)
                val body = String(b, 23 + nameLen, b.size - 23 - nameLen, Charsets.UTF_8)
                listener.onLrMessage(id, from, to, name, hops, body)
            }
        }
    }
}
