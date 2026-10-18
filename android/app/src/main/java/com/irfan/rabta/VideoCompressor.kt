package com.irfan.rabta

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.Surface
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Makes a picked video fit Rabta's 8 MB upload limit on this phone: H.264 up to 720p at a bitrate worked out
 * from its real length, with the original AAC audio copied unchanged. Videos already small enough are sent as they are.
 * Uses only the phone's own MediaCodec encoder; nothing leaves the phone until the user sends it.
 */
object VideoCompressor {
    /** Leaves headroom under the server's 8 MB (8,388,608 bytes) for MP4 overhead. */
    private const val TARGET = 7_600_000L
    private const val MIN_VIDEO_BPS = 220_000
    class Problem(message: String) : Exception(message)
    class Cancelled : Exception("Cancelled")
    private data class Info(val durationMs: Long, val size: Long, val rotation: Int)

    private fun info(c: Context, uri: Uri): Info {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(c, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val size = runCatching { c.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: -1L
            return Info(duration, size, rotation)
        } catch (e: Exception) {
            throw Problem("This video could not be read")
        } finally { runCatching { r.release() } }
    }
    private fun displayName(c: Context, uri: Uri): String {
        var name = "video"
        runCatching { c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) name = it.getString(0) ?: name } }
        return name
    }

    /**
     * Returns an [Attachments] body for the video, compressing it when it is over the limit.
     * [maxDurationMs] (status updates) rejects longer videos with a clear message instead of cutting them.
     */
    fun prepare(c: Context, uri: Uri, maxDurationMs: Long?, cancelled: () -> Boolean, progress: (Int) -> Unit): String {
        val info = info(c, uri)
        if (maxDurationMs != null && info.durationMs > maxDurationMs + 500)
            throw Problem("Status videos can be up to ${maxDurationMs / 1000} seconds. Choose a shorter video.")
        if (info.size in 1..TARGET) return Attachments.pick(c, uri, false)
        if (info.durationMs <= 0) throw Problem("This video could not be read")
        val out = File(c.cacheDir, "rabta-compress-${System.nanoTime()}.mp4")
        try {
            transcode(c, uri, out, info, cancelled, progress)
            if (out.length() !in 1..Attachments.MAX_BYTES.toLong()) throw Problem("This video is still over 8 MB after compression. Choose a shorter video.")
            return Attachments.save(c, out.readBytes(), displayName(c, uri).substringBeforeLast('.') + ".mp4", "video/mp4")
        } finally { out.delete() }
    }

    /** Runs [prepare] with a progress dialog and Cancel; [done] receives the ready attachment body. */
    fun prepareWithDialog(a: Activity, uri: Uri, maxDurationMs: Long?, done: (String) -> Unit) {
        val stop = AtomicBoolean(false)
        val bar = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(AppTheme.accent(a))
        }
        val label = RabtaUi.text(a, "Preparing video…", 14f, color = AppTheme.sub(a)).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(RabtaUi.dp(a, 24), RabtaUi.dp(a, 8), RabtaUi.dp(a, 24), 0)
            addView(label); addView(bar, LinearLayout.LayoutParams(-1, -2).apply { topMargin = RabtaUi.dp(a, 12) })
        }
        val dialog = AlertDialog.Builder(a).setTitle("Preparing video").setView(box).setCancelable(false)
            .setNegativeButton("Cancel") { _, _ -> stop.set(true) }.show()
        Thread {
            var last = -1
            val result = runCatching {
                prepare(a, uri, maxDurationMs, { stop.get() }) { p ->
                    if (p != last) { last = p; a.runOnUiThread { bar.progress = p; label.text = "Making it smaller to send… $p%" } }
                }
            }
            a.runOnUiThread {
                runCatching { dialog.dismiss() }
                if (a.isDestroyed || a.isFinishing) { result.getOrNull()?.let { Attachments.discard(a, it) }; return@runOnUiThread }
                result.onSuccess { if (stop.get()) Attachments.discard(a, it) else done(it) }.onFailure { e ->
                    when (e) {
                        is Cancelled -> {}
                        is Problem -> AlertDialog.Builder(a).setTitle("Video can't be sent").setMessage(e.message).setPositiveButton("OK", null).show()
                        else -> Toast.makeText(a, "Could not prepare this video. Try another one.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.start()
    }

    private fun transcode(c: Context, uri: Uri, out: File, info: Info, cancelled: () -> Boolean, progress: (Int) -> Unit) {
        val extractor = MediaExtractor(); val audio = MediaExtractor()
        var decoder: MediaCodec? = null; var encoder: MediaCodec? = null; var surfaces: CodecSurfaces? = null; var muxer: MediaMuxer? = null
        var muxing = false
        try {
            extractor.setDataSource(c, uri, null)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: throw Problem("No video track found")
            val input = extractor.getTrackFormat(videoTrack); extractor.selectTrack(videoTrack)
            // Audio is copied as-is when it is AAC (what phones record); other formats are left out rather than risk a broken file.
            audio.setDataSource(c, uri, null)
            val audioTrack = (0 until audio.trackCount).firstOrNull { audio.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
            val audioFormat = audioTrack?.let { audio.selectTrack(it); audio.getTrackFormat(it) }
            val audioBps = audioFormat?.let { if (it.containsKey(MediaFormat.KEY_BIT_RATE)) it.getInteger(MediaFormat.KEY_BIT_RATE) else 128_000 } ?: 0
            val seconds = info.durationMs / 1000.0
            val videoBps = ((TARGET * 8 * 0.94) / seconds - audioBps).toInt().coerceAtMost(2_500_000)
            if (videoBps < MIN_VIDEO_BPS) {
                val maxMinutes = ((TARGET * 8 * 0.94) / (MIN_VIDEO_BPS + audioBps) / 60).toInt().coerceAtLeast(1)
                throw Problem("This video is too long to send under 8 MB. Choose one shorter than about $maxMinutes minute${if (maxMinutes == 1) "" else "s"}.")
            }
            // 720p for most videos; lower bitrates look better at a smaller size.
            val longSide = when { videoBps >= 900_000 -> 1280; videoBps >= 500_000 -> 960; else -> 640 }
            val srcW = input.getInteger(MediaFormat.KEY_WIDTH); val srcH = input.getInteger(MediaFormat.KEY_HEIGHT)
            val scale = minOf(1.0, longSide.toDouble() / maxOf(srcW, srcH))
            val width = maxOf(16, ((srcW * scale) / 16).toInt() * 16); val height = maxOf(16, ((srcH * scale) / 16).toInt() * 16)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, videoBps); setInteger(MediaFormat.KEY_FRAME_RATE, 30); setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surfaces = CodecSurfaces(width, height, encoder.createInputSurface())
            encoder.start()
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME)!!)
            // Frames stay in their recorded orientation; the MP4 keeps the rotation hint instead (never rotate twice).
            if (input.containsKey(MediaFormat.KEY_ROTATION)) input.setInteger(MediaFormat.KEY_ROTATION, 0)
            decoder.configure(input, surfaces.decoderSurface, null, 0); decoder.start()
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).apply { setOrientationHint(info.rotation) }

            val decoded = MediaCodec.BufferInfo(); val encoded = MediaCodec.BufferInfo(); val copied = MediaCodec.BufferInfo()
            val audioBuffer = ByteBuffer.allocate(audioFormat?.takeIf { it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE) }?.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)?.coerceAtLeast(64 * 1024) ?: 256 * 1024)
            var videoOut = -1; var audioOut = -1
            var inputDone = false; var decodeDone = false; var encodeDone = false; var audioDone = audioFormat == null
            var lastRendered = Long.MIN_VALUE
            val durationUs = info.durationMs * 1000

            fun copyAudio(untilUs: Long) {
                while (!audioDone && muxing) {
                    val time = audio.sampleTime
                    if (time < 0) { audioDone = true; return }
                    if (time > untilUs) return
                    val n = audio.readSampleData(audioBuffer, 0)
                    if (n < 0) { audioDone = true; return }
                    copied.set(0, n, time, if (audio.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(audioOut, audioBuffer, copied); audio.advance()
                }
            }

            while (!encodeDone) {
                if (cancelled()) throw Cancelled()
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val n = extractor.readSampleData(decoder.getInputBuffer(i)!!, 0)
                        if (n < 0) { decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(i, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                var encoderBusy = true; var decoderBusy = !decodeDone
                while (encoderBusy || decoderBusy) {
                    val e = encoder.dequeueOutputBuffer(encoded, 10_000)
                    when {
                        e == MediaCodec.INFO_TRY_AGAIN_LATER -> encoderBusy = false
                        e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxing) { "Encoder format changed twice" }
                            videoOut = muxer.addTrack(encoder.outputFormat)
                            if (audioFormat != null) audioOut = muxer.addTrack(audioFormat)
                            muxer.start(); muxing = true
                        }
                        e >= 0 -> {
                            val data = encoder.getOutputBuffer(e)!!
                            if (encoded.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) encoded.size = 0
                            if (encoded.size > 0 && muxing) {
                                muxer.writeSampleData(videoOut, data, encoded)
                                copyAudio(encoded.presentationTimeUs)
                                progress((encoded.presentationTimeUs * 100 / maxOf(1, durationUs)).toInt().coerceIn(0, 99))
                            }
                            encoder.releaseOutputBuffer(e, false)
                            if (encoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) { encodeDone = true; encoderBusy = false; decoderBusy = false }
                        }
                    }
                    if (e != MediaCodec.INFO_TRY_AGAIN_LATER || encodeDone) continue
                    if (!decodeDone) {
                        val d = decoder.dequeueOutputBuffer(decoded, 10_000)
                        if (d == MediaCodec.INFO_TRY_AGAIN_LATER) decoderBusy = false
                        else if (d >= 0) {
                            val end = decoded.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            // At most ~30 frames a second: higher frame rates only spend the small bitrate on motion nobody sees.
                            val render = decoded.size > 0 && (lastRendered == Long.MIN_VALUE || decoded.presentationTimeUs - lastRendered >= 30_000)
                            decoder.releaseOutputBuffer(d, render)
                            if (render) {
                                surfaces.awaitFrame(); surfaces.draw()
                                surfaces.present(decoded.presentationTimeUs * 1000); lastRendered = decoded.presentationTimeUs
                            }
                            if (end) { encoder.signalEndOfInputStream(); decodeDone = true; decoderBusy = false }
                        }
                    } else decoderBusy = false
                }
            }
            copyAudio(Long.MAX_VALUE)
            progress(100)
        } finally {
            runCatching { decoder?.stop() }; runCatching { decoder?.release() }
            runCatching { encoder?.stop() }; runCatching { encoder?.release() }
            runCatching { surfaces?.release() }
            runCatching { if (muxing) muxer?.stop() }; runCatching { muxer?.release() }
            runCatching { extractor.release() }; runCatching { audio.release() }
        }
    }

    /** Decoder → SurfaceTexture → GLES draw at the new size → the encoder's input surface. */
    private class CodecSurfaces(private val width: Int, private val height: Int, private val encoderSurface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface
        private val texture: Int
        private val program: Int
        private val frames = HandlerThread("rabta-video-frames").apply { start() }
        private val lock = Object()
        private var available = false
        private val st: SurfaceTexture
        val decoderSurface: Surface
        private val matrix = FloatArray(16)
        private val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)).apply { position(0) }

        init {
            val version = IntArray(2)
            check(EGL14.eglInitialize(display, version, 0, version, 1)) { "EGL unavailable" }
            val configs = arrayOfNulls<EGLConfig>(1); val count = IntArray(1)
            EGL14.eglChooseConfig(display, intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1, EGL14.EGL_NONE), 0, configs, 0, 1, count, 0)
            check(count[0] > 0) { "No recordable EGL config" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "EGL make current failed" }
            program = link(
                "uniform mat4 uST;attribute vec4 aPos;attribute vec4 aTex;varying vec2 vTex;void main(){gl_Position=aPos;vTex=(uST*aTex).xy;}",
                "#extension GL_OES_EGL_image_external : require\nprecision mediump float;varying vec2 vTex;uniform samplerExternalOES sTex;void main(){gl_FragColor=texture2D(sTex,vTex);}")
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); texture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            st = SurfaceTexture(texture)
            st.setOnFrameAvailableListener({ synchronized(lock) { available = true; lock.notifyAll() } }, Handler(frames.looper))
            decoderSurface = Surface(st)
        }
        private fun shader(type: Int, code: String) = GLES20.glCreateShader(type).also {
            GLES20.glShaderSource(it, code); GLES20.glCompileShader(it)
            val ok = IntArray(1); GLES20.glGetShaderiv(it, GLES20.GL_COMPILE_STATUS, ok, 0); check(ok[0] != 0) { "Shader failed" }
        }
        private fun link(vertex: String, fragment: String) = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, shader(GLES20.GL_VERTEX_SHADER, vertex)); GLES20.glAttachShader(it, shader(GLES20.GL_FRAGMENT_SHADER, fragment))
            GLES20.glLinkProgram(it); val ok = IntArray(1); GLES20.glGetProgramiv(it, GLES20.GL_LINK_STATUS, ok, 0); check(ok[0] != 0) { "Program failed" }
        }
        fun awaitFrame() {
            synchronized(lock) {
                val until = System.currentTimeMillis() + 3000
                while (!available) { val wait = until - System.currentTimeMillis(); check(wait > 0) { "The phone's video decoder stopped responding" }; lock.wait(wait) }
                available = false
            }
            st.updateTexImage(); st.getTransformMatrix(matrix)
        }
        fun draw() {
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f); GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            val pos = GLES20.glGetAttribLocation(program, "aPos"); val tex = GLES20.glGetAttribLocation(program, "aTex")
            quad.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(pos)
            quad.position(2); GLES20.glVertexAttribPointer(tex, 2, GLES20.GL_FLOAT, false, 16, quad); GLES20.glEnableVertexAttribArray(tex)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uST"), 1, false, matrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        fun present(nanos: Long) { EGLExt.eglPresentationTimeANDROID(display, surface, nanos); EGL14.eglSwapBuffers(display, surface) }
        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context); EGL14.eglReleaseThread()
            // The default display is shared with video calls, so it is not terminated here.
            decoderSurface.release(); st.release(); encoderSurface.release(); frames.quitSafely()
        }
    }
}
