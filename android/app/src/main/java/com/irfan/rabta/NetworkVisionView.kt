package com.irfan.rabta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Schematic layout only. A line means a live direct Nearby connection, never distance or remote reachability. */
class NetworkVisionView(c:Context):View(c) {
    companion object {
        const val GRAPHITE=0xFF0B0F19.toInt()
        const val VIOLET=0xFFB5A0FF.toInt()
        const val TEAL=0xFF43D7C5.toInt()
        const val TEXT=0xFFF3F4FA.toInt()
        const val SUB=0xFFAAB5CC.toInt()
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark=c.getDrawable(R.drawable.ic_launcher_fg)
    private var state=VisionState()
    private var selected:String?=null
    private var foreground=false
    private var scheduled=false
    private var down:String?=null
    private val visibleBounds=Rect()
    var onPeer:((String?)->Unit)?=null
    private fun dp(n:Float)=n*resources.displayMetrics.density
    private val frame=object:Runnable {
        override fun run() {
            scheduled=false
            if(shouldAnimate()){invalidate();schedule()}
        }
    }
    init {
        setBackgroundColor(GRAPHITE)
        contentDescription="Schematic nearby network. Tap a node, or choose an accessible peer button below. Positions are not location or distance."
        isClickable=true
    }
    private fun displayed():List<VisionPeer> {
        val first=state.peers.take(12).toMutableList()
        val extra=state.peers.firstOrNull { it.id==selected }
        if(extra!=null&&first.none { it.id==extra.id }) { if(first.size==12)first.removeAt(11);first.add(extra) }
        return first
    }
    fun update(value:VisionState,selection:String?) {
        if(state!=value||selected!=selection){state=value;selected=selection;invalidate()}
        schedule()
    }
    fun foreground(value:Boolean) { foreground=value;schedule() }
    fun animationRunning()=scheduled
    private fun shouldAnimate()=foreground&&isAttachedToWindow&&isShown&&windowVisibility==VISIBLE&&
        getGlobalVisibleRect(visibleBounds)&&state.peers.any { it.connected }&&ValueAnimator.areAnimatorsEnabled()&&
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive&&
        !(context.getSystemService(Context.POWER_SERVICE) as PowerManager).isPowerSaveMode
    private fun schedule() {
        if(!shouldAnimate()){removeCallbacks(frame);scheduled=false;return}
        if(!scheduled){scheduled=true;postDelayed(frame,160)} // At most 6.25 frames/s; no inactive discovery sweep.
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();schedule()}
    override fun onDetachedFromWindow(){removeCallbacks(frame);scheduled=false;super.onDetachedFromWindow()}
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);schedule()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);schedule()}
    private fun positions():List<Triple<VisionPeer,Float,Float>> {
        val peers=displayed();val radius=(min(width,height)/2f-dp(44f)).coerceAtLeast(0f)
        return peers.mapIndexed { i,p ->
            val angle=-PI/2+2*PI*i/peers.size
            Triple(p,width/2f+cos(angle).toFloat()*radius,height/2f+sin(angle).toFloat()*radius)
        }
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f;val cy=height/2f
        val phase=(SystemClock.elapsedRealtime()%2400)/2400f
        val points=positions()
        val animate=shouldAnimate()
        for((peer,x,y) in points)if(peer.connected) {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.5f)
            paint.color=if(peer.keyMatched) TEAL else VIOLET;paint.alpha=75
            canvas.drawLine(cx,cy,x,y,paint)
            if(animate) {
                paint.style=Paint.Style.FILL;paint.alpha=220
                canvas.drawCircle(cx+(x-cx)*phase,cy+(y-cy)*phase,dp(2.5f),paint)
            }
        }
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=0xFF1D2636.toInt()
        canvas.drawCircle(cx,cy,dp(32f),paint)
        mark?.setBounds((cx-dp(25f)).toInt(),(cy-dp(25f)).toInt(),(cx+dp(25f)).toInt(),(cy+dp(25f)).toInt());mark?.draw(canvas)
        paint.color=TEXT;paint.textSize=dp(13f);paint.textAlign=Paint.Align.CENTER
        canvas.drawText("You",cx,cy+dp(51f),paint)
        for((peer,x,y) in points) {
            paint.style=Paint.Style.FILL;paint.color=0xFF171D2C.toInt();canvas.drawCircle(x,y,dp(22f),paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(if(peer.id==selected)3f else 1.5f)
            paint.color=if(peer.id==selected)VIOLET else if(peer.connected)TEAL else SUB
            canvas.drawCircle(x,y,dp(22f),paint)
            paint.style=Paint.Style.FILL;paint.textSize=dp(14f);paint.color=if(peer.connected)TEAL else SUB
            canvas.drawText(peer.label.substringAfter(' '),x,y+dp(5f),paint)
            paint.textSize=dp(11f);paint.color=TEXT
            canvas.drawText(peer.label,x,y+dp(39f),paint)
        }
    }
    private fun hit(x:Float,y:Float):String?=positions().firstOrNull { (_,px,py)->hypot(x-px,y-py)<=dp(26f) }?.first?.id
    override fun onTouchEvent(event:MotionEvent):Boolean {
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{down=hit(event.x,event.y);return true}
            MotionEvent.ACTION_UP->{val target=hit(event.x,event.y);if(target==down){selected=target;onPeer?.invoke(target);performClick();invalidate()};down=null;return true}
            MotionEvent.ACTION_CANCEL->{down=null;return true}
        }
        return true
    }
    override fun performClick():Boolean { super.performClick();return true }
}
