package com.irfan.rabta

import android.app.Activity
import android.content.ComponentName
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.nfc.cardemulation.HostApduService
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** RAM-only share. A paused/locked screen or account change cannot serve a saved invitation. */
object InstantNfcSession {
    private data class Share(val owner:String,val account:String,val data:ByteArray,val expires:Long)
    @Volatile private var share:Share?=null
    @Synchronized fun arm(owner:String,account:String,raw:String) {
        val packet=InstantInvitation.verify(raw,System.currentTimeMillis())
        require(Session.loggedIn&&Session.id==account&&packet.record.id==account){"Open your own current pairing code to share by NFC"}
        share=Share(owner,account,raw.toByteArray(Charsets.UTF_8),packet.record.expires)
    }
    @Synchronized fun clear(owner:String) { if(share?.owner==owner)share=null }
    @Synchronized fun current():ByteArray? {
        val value=share?:return null
        if(System.currentTimeMillis()>=value.expires||!runCatching { Session.loggedIn&&Session.id==value.account }.getOrDefault(false)){share=null;return null}
        return value.data.copyOf()
    }
}

class InstantNfcHostService:HostApduService() {
    private val protocol=InstantNfcProtocol { InstantNfcSession.current() }
    override fun processCommandApdu(commandApdu:ByteArray,extras:Bundle?)=protocol.respond(commandApdu)
    override fun onDeactivated(reason:Int) { protocol.reset() }
}

/** Reader mode replaces removed Android Beam. NFC only hands over pairing records. */
class InstantNfcController(private val activity:Activity,private val onCode:(String)->Unit,private val onStatus:(String)->Unit) {
    private val adapter=NfcAdapter.getDefaultAdapter(activity)
    private val main=Handler(Looper.getMainLooper())
    private val owner=java.util.UUID.randomUUID().toString()
    private var epoch=0
    private var reading=false
    private var preferred=false
    private val receiving=AtomicBoolean()
    val supported get()=adapter!=null
    val enabled get()=adapter?.isEnabled==true
    val canShare get()=supported&&activity.packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)
    fun summary()=when { !supported->"NFC is unavailable on this phone. Use QR pairing.";!enabled->"NFC is off. Enable NFC in Android settings, or use QR.";!canShare->"NFC reading is available. This phone cannot share codes through card emulation.";else->"NFC reading and card sharing are available. Both phones must be unlocked and the pairing screen open." }
    fun share(raw:String):Boolean {
        stop()
        if(!enabled||!canShare){onStatus(summary());return false}
        return runCatching {
            check(CardEmulation.getInstance(adapter!!).setPreferredService(activity,ComponentName(activity,InstantNfcHostService::class.java))){"Android could not enable foreground NFC sharing. Use QR."}
            preferred=true;InstantNfcSession.arm(owner,Session.id,raw)
            onStatus("Ready to share this pairing code. The other phone selects Read NFC pairing code, then taps this phone.");true
        }.getOrElse { stop();onStatus(it.message?:"NFC sharing failed. Use QR.");false }
    }
    fun read() {
        stop()
        if(!enabled){onStatus(summary());return}
        val source=adapter?:return
        val attempt=++epoch;reading=true
        runCatching {
            source.enableReaderMode(activity,{ tag ->
                if(!receiving.compareAndSet(false,true))return@enableReaderMode
                main.post {
                    if(attempt!=epoch||!reading||activity.isDestroyed){receiving.set(false);return@post}
                    reading=false;runCatching { source.disableReaderMode(activity) }
                    onStatus("Reading NFC pairing code… Keep the phones together.")
                    Thread {
                        val result=runCatching {
                            val link=IsoDep.get(tag)?:error("This tag does not support Rabta card sharing. Use the other phone's pairing QR.")
                            try {
                                link.connect();link.timeout=3000
                                InstantNfcProtocol.payload(link.transceive(InstantNfcProtocol.select))
                                val size=InstantNfcProtocol.payload(link.transceive(InstantNfcProtocol.size))
                                require(size.size==2){"Invalid NFC invitation size"}
                                val length=((size[0].toInt()and 255)shl 8)or(size[1].toInt()and 255)
                                require(length in 100..InstantInvitation.MAX_TEXT){"NFC invitation is too large or invalid"}
                                val out=ByteArrayOutputStream()
                                while(out.size()<length) {
                                    val count=minOf(InstantNfcProtocol.CHUNK,length-out.size())
                                    val data=InstantNfcProtocol.payload(link.transceive(InstantNfcProtocol.read(out.size(),count)))
                                    require(data.size==count){"Incomplete NFC transfer. Tap again or use QR"};out.write(data)
                                }
                                String(out.toByteArray(),Charsets.UTF_8)
                            }finally{runCatching { link.close() }}
                        }
                        main.post {
                            if(attempt!=epoch||activity.isDestroyed||activity.isFinishing)return@post
                            receiving.set(false)
                            result.onSuccess { onCode(it) }.onFailure { onStatus(it.message?:"NFC transfer interrupted. Hold phones together and retry, or use QR.") }
                        }
                    }.start()
                }
            },NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,Bundle().apply { putInt(NfcAdapter.EXTRA_READER_PRESENCE_CHECK_DELAY,250) })
            onStatus("Waiting for the other phone's NFC pairing code…")
        }.onFailure { stop();onStatus("Could not start NFC reader. Check NFC settings and retry, or use QR.") }
    }
    fun stop() {
        epoch++;reading=false;receiving.set(false);InstantNfcSession.clear(owner)
        if(adapter!=null)runCatching { adapter.disableReaderMode(activity) }
        if(preferred){adapter?.let { runCatching { CardEmulation.getInstance(it).unsetPreferredService(activity) } };preferred=false}
    }
}
