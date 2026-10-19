package com.irfan.rabta

import android.content.Context
import android.graphics.Color
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
 * Playing is [VoicePlayer]'s, for the whole app: this view shows it and goes on showing it when drawn again,
 * and the note keeps playing when its chat closes. [origin] is the chat it is in, for the bar elsewhere.
 */
class VoiceMessageView(context:Context,private val body:String,private val sender:String="",photo:android.graphics.Bitmap?=null,badgeRing:Int=AppTheme.surface(context),
                       private val key:String="",private val origin:VoicePlayer.Origin?=null):LinearLayout(context),VoicePlayer.Listener {
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var preparing=false
    private var lengthMs=0L
    private lateinit var play:TextView
    private lateinit var time:TextView
    private lateinit var seek:SeekBar
    private lateinit var photoView:android.widget.ImageView
    private val wave=VoiceWaveView(context)
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
                    if(user&&current()&&VoicePlayer.prepared) { VoicePlayer.seekTo(value);wave.progress=if(bar.max>0) value.toFloat()/bar.max else 0f }
                }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) {}
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
    override fun onAttachedToWindow() { super.onAttachedToWindow();VoicePlayer.watch(this);render();prepareInfo() }
    // Leaving the screen does not stop the note: it plays on, and the bar in the chats shows it.
    override fun onDetachedFromWindow() { VoicePlayer.unwatch(this);super.onDetachedFromWindow() }
    /**
     * Reads length and waveform in the background so they show before the first tap. They are facts about the note:
     * kept once read (also on disk), so a chat drawn again or opened later shows them at once.
     */
    private fun prepareInfo() {
        if(preparing||(lengthMs>0&&wave.levels!=null))return
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
                if(info==null) { render();return@post }
                show(info.first,info.second)
            }
        }
    }
    private fun show(length:Long,levels:FloatArray?) {
        if(length>0&&lengthMs<=0) lengthMs=length
        if(levels!=null) wave.levels=levels
        shown="";render()
    }
    /** The note's name for the player and the facts on disk: its message id, or one made from the note itself. */
    private val noteKey by lazy { key.ifBlank { "n${body.length}-${Integer.toHexString(body.hashCode())}" }.replace(Regex("[^A-Za-z0-9_-]"),"_") }
    private fun factsKey()=noteKey
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
        /** Something else is about to play (a video): the voice note playing pauses. */
        fun pauseAll() = VoicePlayer.pause()
    }
    private fun current()=VoicePlayer.key==factsKey()
    private var iconShown=""
    private fun icon(name:String,label:String) { if(label==iconShown) return;iconShown=label;UiIcons.set(play,name,AppTheme.accent(context),30,label) }
    private fun toggle() = VoicePlayer.toggle(context,factsKey(),body,sender,origin)
    /** What is shown now, so the player's ten updates a second redraw only the note that plays. */
    private var shown=""
    override fun voiceChanged() = render()
    private fun render() {
        val mine=current()
        val state=when { !mine -> "idle:${VoicePlayer.leftAt(factsKey())}:${VoicePlayer.failedKey==factsKey()}"; VoicePlayer.loading -> "loading"; else -> "on" }
        if(state==shown&&state!="on") return
        shown=state
        when {
            mine&&VoicePlayer.loading -> { time.text="Loading…";play.isEnabled=false }
            mine&&VoicePlayer.prepared -> {
                val length=VoicePlayer.duration;val at=VoicePlayer.position
                if(lengthMs<=0&&length>0) lengthMs=length.toLong()
                play.isEnabled=true;seek.max=length;seek.isEnabled=true;seek.progress=at
                wave.progress=if(length>0) at.toFloat()/length else 0f;time.text=VoiceAudio.format(at.toLong())
                if(VoicePlayer.playing) icon("pause","Pause voice message") else icon("play","Resume voice message")
            }
            else -> {
                val left=VoicePlayer.leftAt(factsKey())
                play.isEnabled=true;seek.isEnabled=false
                if(left!=null&&lengthMs>0) { seek.max=lengthMs.toInt();seek.progress=left;wave.progress=left.toFloat()/lengthMs;time.text=VoiceAudio.format(left.toLong());icon("play","Resume voice message") }
                else { seek.progress=0;wave.progress=0f;icon("play","Play voice message")
                    time.text=if(VoicePlayer.failedKey==factsKey()) "Audio unavailable · Tap to retry" else if(lengthMs>0) VoiceAudio.format(lengthMs) else "Voice message" }
            }
        }
    }
    /** Stops this note if it is the one playing. */
    fun stop() { if(current()) VoicePlayer.stop() else { shown="";render() } }
}
