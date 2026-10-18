package com.irfan.rabta

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Base64
import android.view.View
import java.io.File
import java.nio.ByteOrder

/** Facts about a stored voice note, read from the audio itself: length and a loudness envelope. */
object VoiceAudio {
    /** Writes the base64 note to a private temp file the caller deletes. */
    fun tempFile(c: Context, body: String): File =
        File.createTempFile("rabta-voice-", ".mp4", c.cacheDir).also { it.writeBytes(Base64.decode(body, Base64.DEFAULT)) }

    fun durationMs(path: String): Long = runCatching {
        MediaMetadataRetriever().run {
            try { setDataSource(path); extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L } finally { release() }
        }
    }.getOrDefault(0L)

    /** "0:07" for a stored note, or "" if it cannot be read. Used for chat-list previews. */
    fun label(c: Context, body: String): String = runCatching {
        val f = tempFile(c, body)
        try { durationMs(f.absolutePath).takeIf { it > 0 }?.let { format(it) } ?: "" } finally { f.delete() }
    }.getOrDefault("")

    fun format(ms: Long) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

    /**
     * Decodes the note and returns [buckets] peak levels (0..1) over its length, or null if the
     * phone cannot decode it. Nothing is invented: silence stays flat.
     */
    fun envelope(path: String, buckets: Int = 36): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        return try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0) return null
            val peaks = FloatArray(buckets)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!).also { codec = it }
            decoder.configure(format, null, null, 0); decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false; var guard = 0
            while (!outputDone && guard++ < 20000) {
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val size = extractor.readSampleData(decoder.getInputBuffer(index)!!, 0)
                        if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val out = decoder.dequeueOutputBuffer(info, 10_000)
                if (out >= 0) {
                    if (info.size > 0) {
                        val pcm = decoder.getOutputBuffer(out)!!.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        var peak = 0
                        while (pcm.hasRemaining()) { val v = Math.abs(pcm.get().toInt()); if (v > peak) peak = v }
                        val bucket = ((info.presentationTimeUs.toDouble() / durationUs) * buckets).toInt().coerceIn(0, buckets - 1)
                        peaks[bucket] = maxOf(peaks[bucket], peak / 32768f)
                    }
                    decoder.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            // Square root keeps quiet speech visible without exaggerating silence.
            FloatArray(buckets) { Math.sqrt(peaks[it].toDouble()).toFloat() }
        } catch (_: Exception) { null }
        finally { runCatching { codec?.stop() }; runCatching { codec?.release() }; extractor.release() }
    }
}

/** Bars from [VoiceAudio.envelope]; the played part is lime. Without levels it shows a flat line. */
class VoiceWaveView(c: Context) : View(c) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    var levels: FloatArray? = null
        set(value) { field = value; invalidate() }
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density; val cy = height / 2f
        val bars = levels
        if (bars == null || bars.isEmpty()) {
            paint.strokeWidth = 2 * d; paint.color = AppTheme.sub(context); canvas.drawLine(0f, cy, width.toFloat(), cy, paint)
            paint.color = AppTheme.accent(context); canvas.drawLine(0f, cy, width * progress, cy, paint); return
        }
        val step = width.toFloat() / bars.size; paint.strokeWidth = minOf(3 * d, step * 0.6f)
        bars.forEachIndexed { i, level ->
            val x = step * i + step / 2; val h = maxOf(2 * d, level * (height - 4 * d)) / 2
            paint.color = if (x <= width * progress) AppTheme.accent(context) else AppTheme.sub(context)
            canvas.drawLine(x, cy - h, x, cy + h, paint)
        }
    }
}
