package com.irfan.rabta

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.VelocityTracker
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/** Drag the page with the finger, then settle or return it without moving the floating action button. */
class TabSwipeLayout(c:Context,private val enabled:()->Boolean,private val move:(Int)->Unit):FrameLayout(c) {
    var canMove: (Int)->Boolean = { true }
    private var x=0f;private var y=0f;private var swiping=false;private var vertical=false
    private var settling=false
    private var page:View?=null
    private var velocity:VelocityTracker?=null
    private val slop=android.view.ViewConfiguration.get(c).scaledTouchSlop
    private fun visiblePage()=(0 until childCount).map {getChildAt(it)}.firstOrNull {it.visibility==View.VISIBLE&&it.layoutParams.height==LayoutParams.MATCH_PARENT}
    private fun direction(dx:Float):Int {
        val physical=if(dx<0) 1 else -1
        return if(layoutDirection==View.LAYOUT_DIRECTION_RTL) -physical else physical
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(settling)return true
        if(!enabled())return false
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{
                x=e.x;y=e.y;swiping=false;vertical=false;page=visiblePage()
                velocity?.recycle();velocity=VelocityTracker.obtain().also {it.addMovement(e)}
            }
            MotionEvent.ACTION_POINTER_DOWN->{vertical=true;reset()}
            MotionEvent.ACTION_MOVE->{
                val dx=abs(e.x-x);val dy=abs(e.y-y)
                if(dy>slop&&dy>dx)vertical=true
                if(!vertical&&e.pointerCount==1&&dx>slop*2&&dx>dy*1.5f) {
                    swiping=true;parent?.requestDisallowInterceptTouchEvent(true);return true
                }
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{velocity?.recycle();velocity=null}
        }
        return false
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(settling)return true
        if(!swiping)return super.onTouchEvent(e)
        velocity?.addMovement(e)
        val dx=e.x-x
        when(e.actionMasked) {
            MotionEvent.ACTION_MOVE -> page?.translationX=dx*(if(canMove(direction(dx))) 1f else 0.2f)
            MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_CANCEL -> reset()
            MotionEvent.ACTION_UP -> {
                velocity?.computeCurrentVelocity(1000)
                val speed=velocity?.xVelocity?:0f
                val threshold=minOf(width*0.25f,96*resources.displayMetrics.density)
                val commit=canMove(direction(dx))&&(abs(dx)>threshold || (abs(dx)>slop*2&&abs(speed)>700*resources.displayMetrics.density&&speed*dx>0))
                velocity?.recycle();velocity=null
                swiping=false;parent?.requestDisallowInterceptTouchEvent(false)
                if(!commit) reset() else settle(dx)
            }
        }
        return true
    }
    private fun settle(dx:Float) {
        val outgoing=page
        if(!android.animation.ValueAnimator.areAnimatorsEnabled()) {outgoing?.translationX=0f;move(direction(dx));return}
        if(outgoing==null) {move(direction(dx));return}
        settling=true
        val sign=if(dx<0) -1f else 1f
        outgoing.animate().translationX(sign*width).setDuration(120).setInterpolator(DecelerateInterpolator()).withEndAction {
            outgoing.translationX=0f
            move(direction(dx))
            val incoming=visiblePage()
            if(incoming==null || incoming===outgoing) {settling=false;return@withEndAction}
            incoming.animate().cancel()
            incoming.translationX=-sign*width*0.25f
            incoming.animate().translationX(0f).setDuration(180).setInterpolator(DecelerateInterpolator()).withEndAction {settling=false}.start()
        }.start()
    }
    private fun reset() {
        page?.animate()?.translationX(0f)?.setDuration(180)?.start()
        swiping=false;parent?.requestDisallowInterceptTouchEvent(false)
        velocity?.recycle();velocity=null
    }
    override fun onDetachedFromWindow() {
        for(i in 0 until childCount) {getChildAt(i).animate().cancel();getChildAt(i).translationX=0f}
        velocity?.recycle();velocity=null;swiping=false;settling=false
        super.onDetachedFromWindow()
    }
}
