package com.irfan.rabta

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.PowerManager
import android.view.View
import android.view.animation.LinearInterpolator

/** Decorative activity indicator. Rings and sweep never encode users, location or distance. */
class RadarView(c:Context):View(c) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private var animator:ValueAnimator?=null
    private var angle=0f
    private var scanning=false
    private val mark=c.getDrawable(R.drawable.ic_launcher_fg)
    init {
        contentDescription="Radar discovery activity. No location or distance data."
        importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    fun scanning(value:Boolean) { scanning=value;animateIfVisible();invalidate() }
    private fun animateIfVisible() {
        val power=context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val enabled=scanning&&isAttachedToWindow&&isShown&&windowVisibility==VISIBLE&&ValueAnimator.areAnimatorsEnabled()&&!power.isPowerSaveMode
        if(!enabled){animator?.cancel();animator=null;return}
        if(animator!=null)return
        animator=ValueAnimator.ofFloat(0f,360f).apply {
            duration=6000;repeatCount=ValueAnimator.INFINITE;interpolator=LinearInterpolator()
            addUpdateListener { angle=it.animatedValue as Float;invalidate() };start()
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow();animateIfVisible() }
    override fun onDetachedFromWindow() { animator?.cancel();animator=null;super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility:Int) { super.onWindowVisibilityChanged(visibility);animateIfVisible() }
    override fun onVisibilityChanged(changedView:View,visibility:Int) { super.onVisibilityChanged(changedView,visibility);animateIfVisible() }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val cx=width/2f;val cy=height/2f;val radius=minOf(width,height)*0.43f
        paint.style=Paint.Style.STROKE;paint.strokeWidth=resources.displayMetrics.density
        paint.color=AppTheme.line(context)
        for(scale in listOf(0.42f,0.70f,1f))canvas.drawCircle(cx,cy,radius*scale,paint)
        paint.color=AppTheme.accent(context);paint.alpha=if(scanning)170 else 45
        paint.strokeWidth=resources.displayMetrics.density*2
        canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,angle,70f,false,paint)
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=AppTheme.surface(context)
        canvas.drawCircle(cx,cy,radius*0.36f,paint)
        val half=(radius*0.45f).toInt()
        mark?.setBounds(cx.toInt()-half,cy.toInt()-half,cx.toInt()+half,cy.toInt()+half);mark?.draw(canvas)
    }
}
