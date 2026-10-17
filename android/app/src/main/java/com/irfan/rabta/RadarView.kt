package com.irfan.rabta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.PowerManager
import android.view.View

/** Decorative activity indicator. Rings and sweep never encode users, location or distance. */
class RadarView(c:Context):View(c) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var scheduled=false
    private var scanning=false
    private val bounds=android.graphics.Rect()
    private val mark=c.getDrawable(R.drawable.ic_launcher_fg)
    private val frame=object:Runnable {override fun run(){scheduled=false;if(shouldAnimate()){invalidate();animateIfVisible()}}}
    init {
        contentDescription="Radar discovery activity. No location or distance data."
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun scanning(value:Boolean) {scanning=value;animateIfVisible();invalidate()}
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
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f;val cy=height/2f;val radius=minOf(width,height)*0.43f
        paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density
        paint.color=AppTheme.line(context)
        for(scale in listOf(0.42f,0.70f,1f))canvas.drawCircle(cx,cy,radius*scale,paint)
        paint.color=AppTheme.accent(context);paint.alpha=if(scanning)170 else 45
        paint.strokeWidth=resources.displayMetrics.density*2
        canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,(android.os.SystemClock.elapsedRealtime()%6000)/6000f*360f,70f,false,paint)
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=0xFF080D14.toInt() // the lime mark always sits on NOVA navy
        canvas.drawCircle(cx,cy,radius*0.36f,paint)
        val half=(radius*0.45f).toInt()
        mark?.setBounds(cx.toInt()-half,cy.toInt()-half,cx.toInt()+half,cy.toInt()+half);mark?.draw(canvas)
    }
}
