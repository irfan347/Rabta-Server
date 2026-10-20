package com.irfan.rabta

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Base64
import org.json.JSONObject

/**
 * Voice call over the internet: mic → base64 frames → server → other phone.
 * 8kHz PCM, 40ms frames, played through a [CallJitter] that evens out how they arrive.
 */
class InternetVoiceCall(private val peerId: String, val id:String, private val onStatus: (String) -> Unit) {
    private val ringback=CallSounds()
    private val sampleRate = 8000
    private val frameBytes = 640       // 40ms × 8000Hz × 2 bytes
    // About a quarter second of audio waiting to go out: past that, a slow upload would hold the voice back more and
    // more, so frames are skipped until it is sent.
    private val maxBacklog = 6_000L
    private val handler = Handler(Looper.getMainLooper())

    // Room for a third of a second, so a busy moment on the phone does not lose microphone audio.
    private val recMinBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(frameBytes * 8)
    private val playMinBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(frameBytes * 2)

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private val jitter = CallJitter(frameBytes)
    private val effects = mutableListOf<AudioEffect>()
    @Volatile var running = false; private set
    @Volatile private var hasRemoteAudio = false
    @Volatile var muted = false
    private var captureStarted = false
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null

    /** initiate=true: I'm calling (sends call_init). false: I answered. */
    @SuppressLint("MissingPermission")
    fun start(initiate: Boolean) {
        if (running) return
        running = true; hasRemoteAudio=false; captureStarted=false
        if (initiate) {
            WsClient.sendRaw(JSONObject().put("type","call_init").put("to",peerId).put("callId",id).toString())
            handler.post { onStatus("Calling…") }
        } else {
            WsClient.sendRaw(JSONObject().put("type","call_accept").put("to",peerId).put("callId",id).toString())
            handler.post { onStatus("Connecting…") }
        }
        // Set speaker mode
        startPlayback()
        // The caller must wait for the answering phone before sending microphone audio.
        if (!initiate) startCapture()
    }

    fun state(state:String) {
        if(!running || hasRemoteAudio) return
        when(state) {
            "calling" -> { ringback.stop(); onStatus("Calling…") }
            "ringing" -> { ringback.ringing(); onStatus("Ringing…") }
            "accepted" -> { ringback.stop(); onStatus("Connecting…"); startCapture() }
        }
    }

    /** Audio frame received from the remote phone. */
    fun onAudio(b64: String) {
        if (!running) return
        val data = try { Base64.decode(b64, Base64.NO_WRAP) } catch (_: Exception) { return }
        if (data.isEmpty() || data.size > 10000) return
        if (!hasRemoteAudio) {
            hasRemoteAudio = true; ringback.stop()
            startCapture()
            handler.post { if(running) onStatus("Connected") }
        }
        jitter.put(data)
    }

    @SuppressLint("MissingPermission")
    @Synchronized private fun startCapture() {
        if(captureStarted || !running) return
        captureStarted=true
        try {
            val rec = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recMinBuf
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                handler.post { onStatus("Mic shuru nahi hua — permission check karein") }; return
            }
            record = rec; cleanMic(rec.audioSessionId); rec.startRecording()
            captureThread=Thread {
                val buf = ByteArray(frameBytes)
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0 && WsClient.connected) {
                        if (WsClient.backlog() > maxBacklog) continue
                        if(muted) java.util.Arrays.fill(buf,0,n,0.toByte())
                        val enc = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                        WsClient.sendRaw(JSONObject().put("type","call_audio").put("to",peerId).put("callId",id).put("data",enc).toString())
                    } else if (n < 0) {
                        handler.post { onStatus("Microphone problem") }; break
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            handler.post { onStatus("Microphone problem") }
        }
    }

    /** The other voice coming back from this phone's speaker, and noise around it, are taken out where the phone can. */
    private fun cleanMic(session: Int) {
        runCatching { if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(session)?.let { it.enabled = true; effects += it } }
        runCatching { if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(session)?.let { it.enabled = true; effects += it } }
    }

    private fun startPlayback() {
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(playMinBuf)
                .setTransferMode(AudioTrack.MODE_STREAM).build()
            track = t
            t.play()
            // One frame every 40 ms, real or filled in: writing waits for the speaker, which keeps the pace.
            playbackThread=Thread {
                while (running) {
                    val frame = jitter.next()
                    val written = try { t.write(frame, 0, frame.size) } catch (_: Exception) { -1 }
                    if (written < 0) try { Thread.sleep(40) } catch (_: InterruptedException) { break }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            handler.post { onStatus("Speaker problem") }
        }
    }

    /** sendEnd=false when the other side hung up first. */
    fun stop(sendEnd: Boolean = true) {
        ringback.stop()
        if (!running) return
        running = false
        if (sendEnd) try { WsClient.sendRaw(JSONObject().put("type","call_end").put("to",peerId).put("callId",id).toString()) } catch (_: Exception) {}
        val playing = playbackThread
        playbackThread?.interrupt()
        captureThread?.interrupt()
        playbackThread=null; captureThread=null
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        // The player is nearly always waiting to write: stopping the speaker lets it finish before it is released.
        try { track?.stop() } catch (_: Exception) {}
        runCatching { playing?.join(100) }
        try { track?.release() } catch (_: Exception) {}
        effects.forEach { runCatching { it.release() } }; effects.clear()
        record = null; track = null
    }
}
