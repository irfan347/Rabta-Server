package com.irfan.rabta

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.Segmentation
import com.google.mlkit.vision.segmentation.Segmenter
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions
import org.webrtc.JavaI420Buffer
import org.webrtc.VideoFrame
import org.webrtc.VideoProcessor
import org.webrtc.VideoSink
import java.util.concurrent.Executors

/**
 * Your camera's look in a video call: a colour filter and/or background blur, applied to each frame before it is
 * encoded, so the other phone sees it and so does your own preview. The person is found on this phone with ML Kit's
 * selfie segmentation; no picture leaves the call. Off (and free) until an effect is chosen.
 */
object VideoEffects : VideoProcessor {
    @Volatile var filter = VideoFilter.NORMAL
    @Volatile var blur = false
    /** While the effects panel is open: a small upright snapshot for its previews, about twice a second. */
    @Volatile var onSnapshot: ((I420Planes) -> Unit)? = null
    @Volatile private var sink: VideoSink? = null
    @Volatile private var lastSnapshot = 0L

    fun reset() { filter = VideoFilter.NORMAL; blur = false; onSnapshot = null; Person.close() }

    override fun setSink(s: VideoSink?) { sink = s }
    override fun onCapturerStarted(success: Boolean) {}
    override fun onCapturerStopped() {}

    override fun onFrameCaptured(frame: VideoFrame) {
        val out = sink ?: return
        val f = filter; val b = blur; val snap = onSnapshot
        if (f == VideoFilter.NORMAL && !b && snap == null) { out.onFrame(frame); return }
        val source = frame.buffer.toI420() ?: run { out.onFrame(frame); return }
        try {
            val p = read(source)
            val now = android.os.SystemClock.elapsedRealtime()
            if (snap != null && now - lastSnapshot > 450) {
                lastSnapshot = now
                snap(VideoLook.smallUpright(p, frame.rotation, 96))
            }
            if (f == VideoFilter.NORMAL && !b) { out.onFrame(frame); return }
            if (b) { Person.find(p, frame.rotation); Person.latest(p.w, p.h, frame.rotation)?.let { m -> VideoLook.blurBackground(p, frame.rotation, m.data, m.w, m.h) } }
            VideoLook.colour(p, f)
            val processed = VideoFrame(write(p), frame.rotation, frame.timestampNs)
            out.onFrame(processed); processed.release()
        } catch (_: Throwable) {
            out.onFrame(frame)   // an effect that fails never stops the camera
        } finally { source.release() }
    }

    private fun read(b: VideoFrame.I420Buffer): I420Planes {
        val p = I420Planes.blank(b.width, b.height)
        copyIn(b.dataY, b.strideY, p.y, p.w, p.h); copyIn(b.dataU, b.strideU, p.u, p.cw, p.ch); copyIn(b.dataV, b.strideV, p.v, p.cw, p.ch)
        return p
    }
    private fun copyIn(src: java.nio.ByteBuffer, stride: Int, dst: ByteArray, w: Int, h: Int) {
        val buf = src.duplicate()
        for (row in 0 until h) { buf.position(row * stride); buf.get(dst, row * w, w) }
    }
    private fun write(p: I420Planes): JavaI420Buffer {
        val out = JavaI420Buffer.allocate(p.w, p.h)
        copyOut(p.y, p.w, p.h, out.dataY, out.strideY); copyOut(p.u, p.cw, p.ch, out.dataU, out.strideU); copyOut(p.v, p.cw, p.ch, out.dataV, out.strideV)
        return out
    }
    private fun copyOut(src: ByteArray, w: Int, h: Int, dst: java.nio.ByteBuffer, stride: Int) {
        val buf = dst.duplicate()
        for (row in 0 until h) { buf.position(row * stride); buf.put(src, row * w, w) }
    }

    /** Finds the person in the picture with ML Kit, one frame at a time, on its own thread; the newest outline is kept. */
    private object Person {
        class Outline(val w: Int, val h: Int, val data: FloatArray, val frameW: Int, val frameH: Int, val rotation: Int)
        private val worker = Executors.newSingleThreadExecutor()
        @Volatile private var segmenter: Segmenter? = null
        @Volatile private var busy = false
        @Volatile private var outline: Outline? = null

        fun find(p: I420Planes, rotation: Int) {
            if (busy) return
            busy = true
            try {
                val small = VideoLook.smallUpright(p, rotation, 256)
                val bitmap = Bitmap.createBitmap(VideoLook.argb(small), small.w, small.h, Bitmap.Config.ARGB_8888)
                val seg = segmenter ?: Segmentation.getClient(SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.STREAM_MODE).build()).also { segmenter = it }
                seg.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener(worker) { mask ->
                        val data = FloatArray(mask.width * mask.height)
                        mask.buffer.rewind(); mask.buffer.asFloatBuffer().get(data)
                        outline = Outline(mask.width, mask.height, data, p.w, p.h, rotation)
                        busy = false
                    }
                    .addOnFailureListener(worker) { busy = false }
            } catch (_: Throwable) { busy = false }
        }

        /** The newest outline if it belongs to frames of this size and rotation (not, say, the other camera's). */
        fun latest(w: Int, h: Int, rotation: Int) = outline?.takeIf { it.frameW == w && it.frameH == h && it.rotation == rotation }

        fun close() {
            outline = null
            val s = segmenter; segmenter = null
            if (s != null) worker.execute { runCatching { s.close() }; busy = false }
        }
    }
}
