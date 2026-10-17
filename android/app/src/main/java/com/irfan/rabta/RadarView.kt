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
    private val mark=c.getDrawable(R.drawable.ic_launcher_fg)
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
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val d=resources.displayMetrics.density
        val cx=width/2f;val cy=height/2f;val radius=minOf(width,height)/2f-16*d
        paint.style=Paint.Style.STROKE;paint.strokeWidth=d;paint.color=AppTheme.line(context)
        for(orbit in Orbit.values())canvas.drawCircle(cx,cy,radius*ring(orbit),paint)
        paint.color=AppTheme.LIME;paint.alpha=if(scanning)170 else 50;paint.strokeWidth=2*d;paint.strokeCap=Paint.Cap.ROUND
        canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,(android.os.SystemClock.elapsedRealtime()%6000)/6000f*360f,64f,false,paint)
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=0xFF080D14.toInt() // the lime mark always sits on NOVA navy
        canvas.drawCircle(cx,cy,radius*0.3f,paint)
        val half=(radius*0.36f).toInt()
        mark?.setBounds(cx.toInt()-half,cy.toInt()-half,cx.toInt()+half,cy.toInt()+half);mark?.draw(canvas)
        val size=13*d;label.textSize=11*d
        for(dot in dots.take(24)) {
            val angle=Math.toRadians(((dot.id.hashCode().toLong() and 0x7FFFFFFF)%360).toDouble())
            val r=radius*ring(dot.orbit)
            val x=cx+(r*Math.cos(angle)).toFloat();val y=cy+(r*Math.sin(angle)).toFloat()
            val accent=when(dot.orbit) { Orbit.CONNECTED -> AppTheme.teal(context); Orbit.PENDING -> AppTheme.violet(context); Orbit.DISCOVERED -> AppTheme.accent(context) }
            paint.style=Paint.Style.FILL;paint.color=AppTheme.raised(context);canvas.drawCircle(x,y,size,paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=2*d;paint.color=accent;canvas.drawCircle(x,y,size,paint)
            label.color=AppTheme.text(context)
            canvas.drawText(dot.name.take(1).uppercase().ifBlank { "R" },x,y-(label.descent()+label.ascent())/2,label)
        }
    }
}
