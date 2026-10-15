package com.irfan.rabta

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import java.util.concurrent.LinkedBlockingQueue

/**
 * Low-latency voice call for Nearby Connections.
 *
 * Why the first version lagged: it used a continuous STREAM payload, which buffers
 * audio and never drops it, so any delay only grew. This version:
 *   - 8 kHz mono (half the data of 16 kHz; still clear for speech)
 *   - sends small 40 ms frames as individual BYTES payloads (discrete, lower latency)
 *   - a short jitter buffer on playback that DROPS old audio when it falls behind,
 *     so the delay can never pile up - the call stays "live".
 */
class VoiceCall(private val router: Router? = null) {

    private val sampleRate = 8000
    private val frameBytes = 640            // 320 samples = 40 ms at 8 kHz, 16-bit mono
    private val maxQueued = 8               // ~320 ms max buffered; beyond this we drop

    private val recMin = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(frameBytes * 2)
    private val playMin = AudioTrack.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(frameBytes * 2)

    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var captureThread: Thread? = null
    private var playThread: Thread? = null
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        startPlayback()
        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recMin
        )
        try { record?.startRecording() } catch (_: Exception) {}
        captureThread = Thread {
            val buf = ByteArray(frameBytes)
            while (running) {
                val n = record?.read(buf, 0, buf.size) ?: -1
                if (n > 0) router?.sendAudio(if (n == buf.size) buf.copyOf() else buf.copyOf(n))
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** A frame arrived from the other phone. */
    fun onAudioFrame(frame: ByteArray) {
        if (!running) startPlayback()
        // drop oldest if we're behind, so latency never grows
        while (queue.size >= maxQueued) queue.poll()
        queue.offer(frame)
    }

    @SuppressLint("MissingPermission")
    private fun startPlayback() {
        if (track != null) { running = true; return }
        running = true
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(playMin)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        try { track?.play() } catch (_: Exception) {}
        playThread = Thread {
            while (running) {
                val frame = try { queue.take() } catch (_: InterruptedException) { break }
                try { track?.write(frame, 0, frame.size) } catch (_: Exception) {}
            }
        }.also { it.isDaemon = true; it.start() }
    }

    fun stop() {
        running = false
        try { captureThread?.interrupt() } catch (_: Exception) {}
        try { playThread?.interrupt() } catch (_: Exception) {}
        try { record?.stop(); record?.release() } catch (_: Exception) {}
        try { track?.stop(); track?.release() } catch (_: Exception) {}
        record = null; track = null; queue.clear()
    }
}
