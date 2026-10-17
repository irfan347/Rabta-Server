package com.irfan.rabta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.PowerManager
import android.view.View

/**
 * Signal Orbit: the R mark inside orbital rings, with the actual discovered peers placed on them.
 * A peer's ring shows its connection stage and its angle comes from its ID, so the layout never
 * encodes distance, direction, location or signal strength.
 */
class RadarView(c:Context):View(c) {
    enum class Orbit { CONNECTED, PENDING, DISCOVERED }
    data class Dot(val id:String,val name:String,val orbit:Orbit)
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val label=Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign=Paint.Align.CENTER;typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) }
    private var scheduled=false
    private var scanning=false
    private var dots:List<Dot> = emptyList()
    private val bounds=android.graphics.Rect()
    private val mark=c.getDrawable(R.drawable.rabta_mark)
    private val frame=object:Runnable {override fun run(){scheduled=false;if(shouldAnimate()){invalidate();animateIfVisible()}}}
    init {
        contentDescription="Signal Orbit. Shows discovered people by connection stage, not location or distance."
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun scanning(value:Boolean) {scanning=value;animateIfVisible();invalidate()}
    fun peers(value:List<Dot>) {
        if(value==dots)return
        dots=value
        contentDescription="Signal Orbit. ${value.count { it.orbit==Orbit.CONNECTED }} connected, ${value.count { it.orbit==Orbit.PENDING }} waiting for approval, ${value.count { it.orbit==Orbit.DISCOVERED }} discovered. Not location or distance."
        invalidate()
    }
    private fun shouldAnimate():Boolean {
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return scanning&&isAttachedToWindow&&isShown&&windowVisibility==VISIBLE&&getGlobalVisibleRect(bounds)&&ValueAnimator.areAnimatorsEnabled()&&power.isInteractive&&!power.isPowerSaveMode
    }
    private fun animateIfVisible() {
        if(!shouldAnimate()){removeCallbacks(frame);scheduled=false;return}
        if(!scheduled){scheduled=true;postDelayed(frame,160)}
    }
    override fun onAttachedToWindow(){super.onAttachedToWindow();animateIfVisible()}
    override fun onDetachedFromWindow(){removeCallbacks(frame);scheduled=false;super.onDetachedFromWindow()}
    override fun onWindowVisibilityChanged(visibility:Int){super.onWindowVisibilityChanged(visibility);animateIfVisible()}
    override fun onVisibilityChanged(changedView:View,visibility:Int){super.onVisibilityChanged(changedView,visibility);animateIfVisible()}
    private fun ring(orbit:Orbit)=when(orbit) { Orbit.CONNECTED -> 0.5f; Orbit.PENDING -> 0.74f; Orbit.DISCOVERED -> 0.98f }
    private val glow=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val d=resources.displayMetrics.density
        val cx=width/2f;val cy=height/2f;val radius=minOf(width,height)/2f-18*d
        if(radius<=0)return
        val lime=AppTheme.accent(context)
        fun alpha(color:Int,a:Int)=(color and 0x00FFFFFF) or (a shl 24)
        // Soft lime glow behind the orbit (restrained, not neon).
        glow.shader=android.graphics.RadialGradient(cx,cy,radius*1.05f,intArrayOf(alpha(lime,if(scanning)0x2E else 0x18),alpha(lime,0x08),0),floatArrayOf(0f,0.6f,1f),android.graphics.Shader.TileMode.CLAMP)
        canvas.drawCircle(cx,cy,radius*1.05f,glow)
        // Sweep: a fading wedge that turns only while discovery is running.
        val angle=if(scanning)(android.os.SystemClock.elapsedRealtime()%6000)/6000f*360f else 300f
        canvas.save();canvas.rotate(angle,cx,cy)
        glow.shader=android.graphics.SweepGradient(cx,cy,intArrayOf(0,0,alpha(lime,if(scanning)0x55 else 0x22)),floatArrayOf(0f,0.82f,1f))
        canvas.drawCircle(cx,cy,radius,glow)
        canvas.restore()
        paint.shader=null;paint.style=Paint.Style.STROKE;paint.strokeWidth=d
        for(orbit in Orbit.values()) { paint.color=alpha(lime,if(orbit==Orbit.DISCOVERED)0x66 else 0x40);canvas.drawCircle(cx,cy,radius*ring(orbit),paint) }
        paint.color=alpha(lime,0x22);canvas.drawCircle(cx,cy,radius*0.28f+10*d,paint)
        // Centre: the R on navy with a lime ring.
        paint.style=Paint.Style.FILL;paint.color=0xFF080D14.toInt() // the lime mark always sits on NOVA navy
        canvas.drawCircle(cx,cy,radius*0.28f,paint)
        paint.style=Paint.Style.STROKE;paint.strokeWidth=3*d;paint.color=lime
        canvas.drawCircle(cx,cy,radius*0.28f,paint)
        val half=(radius*0.15f).toInt()
        mark?.setBounds(cx.toInt()-half,cy.toInt()-half,cx.toInt()+half,cy.toInt()+half);mark?.draw(canvas)
        val size=15*d;label.textSize=12*d
        for(dot in dots.take(24)) {
            val a=Math.toRadians(((dot.id.hashCode().toLong() and 0x7FFFFFFF)%360).toDouble())
            val r=radius*ring(dot.orbit)
            val x=cx+(r*Math.cos(a)).toFloat();val y=cy+(r*Math.sin(a)).toFloat()
            val accent=when(dot.orbit) { Orbit.CONNECTED -> AppTheme.teal(context); Orbit.PENDING -> AppTheme.violet(context); Orbit.DISCOVERED -> lime }
            paint.style=Paint.Style.FILL;paint.color=AppTheme.raised(context);canvas.drawCircle(x,y,size,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2*d;paint.color=accent;canvas.drawCircle(x,y,size,paint)
            label.color=AppTheme.text(context)
            canvas.drawText(dot.name.take(1).uppercase().ifBlank { "R" },x,y-(label.descent()+label.ascent())/2,label)
        }
    }
}
