package com.irfan.rabta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object MediaHelper {

    /** Compress image from URI to base64 JPEG (max 800px). */
    fun imageToBase64(ctx: Context, uri: Uri): String? {
        return try {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,bounds); true } ?: return null
            var sample=1
            while(maxOf(bounds.outWidth,bounds.outHeight)/sample>1600) sample*=2
            val options=BitmapFactory.Options().apply { inSampleSize=sample }
            var bmp=ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: return null
            // Correct orientation
            try {
                val exifStream = ctx.contentResolver.openInputStream(uri)!!
                val exif = ExifInterface(exifStream)
                val orient = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                val matrix = Matrix()
                when (orient) {
                    ExifInterface.ORIENTATION_ROTATE_90  -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                }
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
                exifStream.close()
            } catch (_: Exception) {}
            // Scale down
            val maxPx = 800
            val scale = maxPx.toFloat() / maxOf(bmp.width, bmp.height)
            if (scale < 1f) bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 75, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    fun base64ToBitmap(b64: String): Bitmap? {
        return try {
            val bytes = Base64.decode(b64, Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    // Voice note recording
    private var recorder: MediaRecorder? = null
    private var voiceFile: File? = null

    @Synchronized fun startRecording(ctx: Context): Boolean {
        if(recorder!=null) return false
        return try {
            val f = File(ctx.cacheDir, "voice_${System.currentTimeMillis()}.mp4")
            voiceFile = f
            val device=if(android.os.Build.VERSION.SDK_INT>=31) MediaRecorder(ctx) else MediaRecorder()
            recorder=device
            device.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioSamplingRate(16000)
                setAudioEncodingBitRate(32000)
                setOutputFile(f.absolutePath)
                prepare()
                start()
            }
            true
        } catch (_: Exception) {
            cancelRecording()
            false
        }
    }

    /** Stop recording and return base64 + duration in seconds. */
    @Synchronized fun stopRecording(): Pair<String, Int>? {
        val device=recorder ?: return null
        val file=voiceFile
        recorder=null; voiceFile=null
        return try {
            device.stop()
            val bytes=file?.readBytes() ?: return null
            if(bytes.isEmpty()) return null
            Base64.encodeToString(bytes,Base64.NO_WRAP) to maxOf(1,bytes.size/4000)
        } catch (_: Exception) { null }
        finally { try { device.release() } catch (_:Exception) {}; file?.delete() }
    }

    @Synchronized fun cancelRecording() {
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder=null; voiceFile?.delete(); voiceFile=null
    }

    /** Play a base64 voice note. */
    fun playVoice(ctx: Context, b64: String, onComplete: () -> Unit = {}): MediaPlayer? {
        return try {
            val f = File(ctx.cacheDir, "play_${System.currentTimeMillis()}.mp4")
            f.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
            MediaPlayer().apply {
                setDataSource(f.absolutePath)
                prepare()
                start()
                setOnCompletionListener { f.delete(); release(); onComplete() }
            }
        } catch (_: Exception) {
            null
        }
    }
}
