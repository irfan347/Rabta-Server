package com.irfan.rabta

import android.content.Context
import android.media.MediaPlayer
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/** Plays the existing AAC payload. Duration and progress come from the decoder, never a fake waveform. */
class VoiceMessageView(context:Context,private val body:String):LinearLayout(context) {
    private val mainHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private var player:MediaPlayer?=null
    private var temporary:File?=null
    private var generation=0
    private var prepared=false
    private var loading=false
    private lateinit var play:TextView
    private lateinit var time:TextView
    private lateinit var seek:SeekBar
    private val tick=object:Runnable {
        override fun run() {
            val p=player?:return
            if(prepared) {
                val position=runCatching { p.currentPosition }.getOrDefault(0)
                seek.progress=position;time.text="${duration(position)} / ${duration(p.duration)}"
                if(p.isPlaying)postDelayed(this,250)
            }
        }
    }
    init {
        orientation=VERTICAL
        val row=LinearLayout(context).apply { orientation=HORIZONTAL;gravity=Gravity.CENTER_VERTICAL }
        play=RabtaUi.iconButton(context,"play","Play voice message") { toggle() }.apply { background=RabtaUi.ripple(context,AppTheme.raised(context),24) }
        row.addView(play)
        val labels=LinearLayout(context).apply { orientation=VERTICAL;setPadding(RabtaUi.dp(context,10),0,0,0) }
        labels.addView(RabtaUi.text(context,"Voice message",14f,true))
        time=RabtaUi.text(context,"Tap to load audio",12f,color=AppTheme.sub(context))
        labels.addView(time);row.addView(labels,LayoutParams(0,-2,1f));addView(row)
        seek=SeekBar(context).apply {
            isEnabled=false;minimumHeight=RabtaUi.dp(context,48);contentDescription="Voice message playback position"
            progressTintList=android.content.res.ColorStateList.valueOf(AppTheme.accent(context))
            thumbTintList=android.content.res.ColorStateList.valueOf(AppTheme.accent(context))
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar,value:Int,user:Boolean) { if(user&&prepared)player?.seekTo(value) }
                override fun onStartTrackingTouch(bar:SeekBar) {}
                override fun onStopTrackingTouch(bar:SeekBar) { removeCallbacks(tick);post(tick) }
            })
        }
        addView(seek,LayoutParams(-1,-2))
    }
    private fun duration(ms:Int)="%d:%02d".format(ms/60000,(ms/1000)%60)
    private fun icon(name:String,label:String) { UiIcons.set(play,name,AppTheme.accent(context),label=label) }
    private fun toggle() {
        if(loading)return
        val current=player
        if(prepared&&current!=null) {
            if(current.isPlaying) { current.pause();removeCallbacks(tick);icon("play","Resume voice message") }
            else { current.start();icon("pause","Pause voice message");post(tick) }
            return
        }
        loading=true;time.text="Loading audio…";play.isEnabled=false
        val version=++generation
        Thread {
            var f:File?=null
            val result=runCatching {
                f=File.createTempFile("rabta-voice-",".mp4",context.cacheDir)
                f!!.writeBytes(Base64.decode(body,Base64.DEFAULT));f!!
            }
            mainHandler.post {
                if(version!=generation||!isAttachedToWindow) { f?.delete();return@post }
                result.onSuccess { file ->
                    temporary=file
                    try {
                        val p=MediaPlayer();player=p
                        p.setOnPreparedListener {
                            if(version!=generation)return@setOnPreparedListener
                            loading=false;prepared=true;play.isEnabled=true
                            seek.max=p.duration;seek.isEnabled=true;p.start();icon("pause","Pause voice message");post(tick)
                        }
                        p.setOnCompletionListener { removeCallbacks(tick);seek.progress=seek.max;time.text="${duration(p.duration)} / ${duration(p.duration)}";icon("play","Replay voice message");p.seekTo(0) }
                        p.setOnErrorListener { _,_,_ -> failed();true }
                        p.setDataSource(file.absolutePath);p.prepareAsync()
                    } catch(_:Exception) { failed() }
                }.onFailure { f?.delete();failed() }
            }
        }.start()
    }
    private fun failed() { stop();time.text="Audio unavailable · Tap to retry" }
    fun stop() {
        generation++;removeCallbacks(tick);loading=false;prepared=false
        player?.let { runCatching { it.release() } };player=null
        temporary?.delete();temporary=null
        seek.isEnabled=false;seek.progress=0;play.isEnabled=true
        time.text="Tap to load audio";icon("play","Play voice message")
    }
    override fun onDetachedFromWindow() { stop();super.onDetachedFromWindow() }
}
