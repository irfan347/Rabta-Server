package com.irfan.rabta

import android.content.Context
import android.graphics.Color
import android.media.MediaPlayer
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/**
 * WhatsApp-style voice note: play, a waveform decoded from the note itself, its real length, and the
 * sender's avatar with a mic badge. Duration and progress come from the decoder, never a fake waveform.
 */
class VoiceMessageView(context:Context,private val body:String,private val sender:String="",photo:android.graphics.Bitmap?=null,badgeRing:Int=AppTheme.surface(context),
                       private val key:String=""):LinearLayout(context) {
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var player:MediaPlayer?=null
    private var temporary:File?=null
    private var generation=0
    private var preparing=false
    private var prepared=false
    private var loading=false
    private var lengthMs=0L
    private lateinit var play:TextView
    private lateinit var time:TextView
    private lateinit var seek:SeekBar
    private lateinit var photoView:android.widget.ImageView
    private val wave=VoiceWaveView(context)
    private val tick=object:Runnable {
        override fun run() {
            val p=player?:return
            if(prepared) {
                val position=runCatching { p.currentPosition }.getOrDefault(0)
                seek.progress=position;wave.progress=if(p.duration>0) position.toFloat()/p.duration else 0f
                time.text=VoiceAudio.format(position.toLong())
                if(p.isPlaying)postDelayed(this,100)
            }
        }
    }
    private fun dp(n:Int)=RabtaUi.dp(context,n)
    init {
        orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
        play=RabtaUi.iconButton(context,"play","Play voice message") { toggle() }.apply {
            UiIcons.set(this,"play",AppTheme.accent(context),30,"Play voice message");background=RabtaUi.ripple(context,Color.TRANSPARENT,999)
        }
        addView(play,LayoutParams(dp(44),dp(48)))
        val middle=LinearLayout(context).apply { orientation=VERTICAL;setPadding(dp(4),dp(6),dp(10),0) }
        // The seek bar sits over the waveform: its track is invisible, its thumb shows the position.
        val track=FrameLayout(context)
        track.addView(wave,FrameLayout.LayoutParams(-1,dp(30),Gravity.CENTER_VERTICAL).apply { leftMargin=dp(8);rightMargin=dp(8) })
        seek=SeekBar(context).apply {
            isEnabled=false;contentDescription="Voice message playback position"
            progressTintList=android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            progressBackgroundTintList=android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
            thumbTintList=android.content.res.ColorStateList.valueOf(AppTheme.accent(context))
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean) {
                    if(user&&prepared) { player?.seekTo(value);wave.progress=if(bar.max>0) value.toFloat()/bar.max else 0f }
                }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) { removeCallbacks(tick);post(tick) }
            })
        }
        track.addView(seek,FrameLayout.LayoutParams(-1,dp(48),Gravity.CENTER_VERTICAL))
        middle.addView(track,LayoutParams(-1,dp(40)))
        time=RabtaUi.text(context,"",12f,color=AppTheme.sub(context)).apply { setPadding(dp(8),0,0,0) }
        middle.addView(time)
        addView(middle,LayoutParams(0,-2,1f))
        // Sender's profile photo (initials until it loads) with a small mic badge, as in WhatsApp.
        val avatar=FrameLayout(context).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;clipChildren=false }
        val circle=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(0xFF325E71.toInt()) }
        avatar.addView(RabtaUi.text(context,sender.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "R" },18f,true,Color.WHITE).apply {
            gravity=Gravity.CENTER;includeFontPadding=false;background=circle
        },FrameLayout.LayoutParams(dp(46),dp(46),Gravity.CENTER))
        photoView=android.widget.ImageView(context).apply {
            scaleType=android.widget.ImageView.ScaleType.CENTER_CROP;background=circle.constantState?.newDrawable();clipToOutline=true;visibility=View.GONE
        }
        avatar.addView(photoView,FrameLayout.LayoutParams(dp(46),dp(46),Gravity.CENTER))
        // The badge ring matches the bubble so it reads as a cut-out on the photo.
        avatar.addView(UiIcons.image(context,"mic",AppTheme.accent(context),13,"Voice").apply {
            background=android.graphics.drawable.GradientDrawable().apply { shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(badgeRing) }
        },FrameLayout.LayoutParams(dp(20),dp(20),Gravity.BOTTOM or Gravity.START))
        addView(avatar,LayoutParams(dp(50),dp(50)).apply { marginStart=dp(2) })
        setPhoto(photo)
    }
    /** Shows the sender's real photo once it is known; null keeps the initials. */
    fun setPhoto(bitmap:android.graphics.Bitmap?) {
        if(bitmap==null)return
        photoView.setImageBitmap(bitmap);photoView.visibility=View.VISIBLE
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();prepareInfo() }
    /**
     * Reads length and waveform in the background so they show before the first tap. They are facts about the note:
     * kept once read (also on disk), so a chat drawn again or opened later shows them at once.
     */
    private fun prepareInfo() {
        if(preparing||loading||(lengthMs>0&&wave.levels!=null))return
        val id=factsKey()
        facts.get(id)?.let { (length,levels) -> show(length,levels);return }
        preparing=true
        val dir=File(context.cacheDir,"voice-facts")
        prepWorker.execute {
            val result=runCatching {
                readFacts(dir,id) ?: run {
                    val f=VoiceAudio.tempFile(context,body)
                    try { VoiceAudio.durationMs(f.absolutePath) to VoiceAudio.envelope(f.absolutePath) } finally { f.delete() }
                }.also { if(it.first>0) writeFacts(dir,id,it) }
            }
            result.getOrNull()?.takeIf { it.first>0 }?.let { facts.put(id,it) }
            mainHandler.post {
                preparing=false
                val info=result.getOrNull()
                if(info==null) { if(!prepared) time.text="Voice message";return@post }
                show(info.first,info.second)
            }
        }
    }
    private fun show(length:Long,levels:FloatArray?) {
        if(length>0&&lengthMs<=0) lengthMs=length
        if(levels!=null) wave.levels=levels
        if(!prepared&&!loading) time.text=if(lengthMs>0) VoiceAudio.format(lengthMs) else "Voice message"
    }
    private fun factsKey()=key.ifBlank { "n${body.length}-${Integer.toHexString(body.hashCode())}" }.replace(Regex("[^A-Za-z0-9_-]"),"_")
    companion object {
        /**
         * Two background readers for the voice notes on screen, newest request first: a chat opens at its newest
         * messages, so the notes in view get their waveform before older ones above.
         */
        private val prepWorker=java.util.concurrent.ThreadPoolExecutor(2,2,30,java.util.concurrent.TimeUnit.SECONDS,
            object:java.util.concurrent.LinkedBlockingDeque<Runnable>() { override fun offer(e:Runnable)=offerFirst(e) })
        /** Length and waveform of notes already read, by message. */
        private val facts=android.util.LruCache<String,Pair<Long,FloatArray?>>(300)
        private fun readFacts(dir:File,id:String):Pair<Long,FloatArray?>? = runCatching {
            val parts=File(dir,id).takeIf { it.exists() }?.readText()?.split(';') ?: return null
            val length=parts[0].toLong().takeIf { it>0 } ?: return null
            length to parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.split(',')?.map { it.toFloat() }?.toFloatArray()
        }.getOrNull()
        private fun writeFacts(dir:File,id:String,known:Pair<Long,FloatArray?>) {
            runCatching { dir.mkdirs();File(dir,id).writeText(known.first.toString()+";"+(known.second?.joinToString(",") ?: "")) }
        }
        /** The note playing now: starting another pauses it, as in WhatsApp. */
        private var playing:java.lang.ref.WeakReference<VoiceMessageView>?=null
        /** Something else is about to play (a video): the note playing pauses. */
        fun pauseAll() { playing?.get()?.yieldTo() }
    }
    /** This note is about to play: the one playing (or still loading) stops for it. */
    private fun claim() {
        val other=playing?.get()
        if(other!=null&&other!==this) other.yieldTo()
        playing=java.lang.ref.WeakReference(this)
    }
    private fun yieldTo() {
        val p=player
        if(prepared&&p!=null&&p.isPlaying) { p.pause();removeCallbacks(tick);icon("play","Resume voice message") }
        else if(loading) stop()
    }
    private fun icon(name:String,label:String) { UiIcons.set(play,name,AppTheme.accent(context),30,label) }
    private fun toggle() {
        if(loading)return
        val current=player
        if(prepared&&current!=null) {
            if(current.isPlaying) { current.pause();removeCallbacks(tick);icon("play","Resume voice message") }
            else { claim();current.start();icon("pause","Pause voice message");post(tick) }
            return
        }
        claim()
        loading=true;time.text="Loading…";play.isEnabled=false
        val version=++generation
        val ready=temporary
        Thread {
            val result=runCatching { ready?.takeIf { it.exists() } ?: VoiceAudio.tempFile(context,body) }
            mainHandler.post {
                if(version!=generation||!isAttachedToWindow) { if(result.getOrNull()!=ready) result.getOrNull()?.delete();return@post }
                result.onSuccess { file ->
                    temporary=file
                    try {
                        val p=MediaPlayer();player=p
                        p.setOnPreparedListener {
                            if(version!=generation)return@setOnPreparedListener
                            loading=false;prepared=true;play.isEnabled=true;lengthMs=p.duration.toLong()
                            seek.max=p.duration;seek.isEnabled=true;p.start();icon("pause","Pause voice message");post(tick)
                        }
                        p.setOnCompletionListener {
                            removeCallbacks(tick);seek.progress=0;wave.progress=0f;time.text=VoiceAudio.format(p.duration.toLong())
                            icon("play","Replay voice message");p.seekTo(0)
                        }
                        p.setOnErrorListener { _,_,_ -> failed();true }
                        p.setDataSource(file.absolutePath);p.prepareAsync()
                    } catch(_:Exception) { failed() }
                }.onFailure { failed() }
            }
        }.start()
    }
    private fun failed() { stop();time.text="Audio unavailable · Tap to retry" }
    fun stop() {
        if(playing?.get()===this) playing=null
        generation++;removeCallbacks(tick);loading=false;prepared=false
        player?.let { runCatching { it.release() } };player=null
        temporary?.delete();temporary=null
        seek.isEnabled=false;seek.progress=0;wave.progress=0f;play.isEnabled=true
        time.text=if(lengthMs>0) VoiceAudio.format(lengthMs) else "Voice message";icon("play","Play voice message")
    }
    override fun onDetachedFromWindow() { stop();super.onDetachedFromWindow() }
}
