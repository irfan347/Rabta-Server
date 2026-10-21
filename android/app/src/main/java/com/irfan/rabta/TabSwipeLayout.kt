package com.irfan.rabta
import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.abs

class TabSwipeLayout(c:Context,private val enabled:()->Boolean,private val move:(Int)->Unit):FrameLayout(c) {
    private var x=0f;private var y=0f;private var swiping=false;private var vertical=false
    private val slop=android.view.ViewConfiguration.get(c).scaledTouchSlop
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(!enabled())return false
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{x=e.x;y=e.y;swiping=false;vertical=false}
            MotionEvent.ACTION_POINTER_DOWN->{vertical=true;swiping=false}
            MotionEvent.ACTION_MOVE->{
                val dx=abs(e.x-x);val dy=abs(e.y-y)
                if(dy>slop&&dy>dx)vertical=true
                if(!vertical&&e.pointerCount==1&&dx>slop*2&&dx>dy*2) {swiping=true;parent.requestDisallowInterceptTouchEvent(true);return true}
            }
        }
        return false
    }
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e:MotionEvent):Boolean {
        if(!swiping)return super.onTouchEvent(e)
        if(e.actionMasked==MotionEvent.ACTION_UP||e.actionMasked==MotionEvent.ACTION_CANCEL) {
            val dx=e.x-x
            if(e.actionMasked==MotionEvent.ACTION_UP&&abs(dx)>64*resources.displayMetrics.density) {
                val direction=if(dx<0)1 else -1
                move(if(layoutDirection==View.LAYOUT_DIRECTION_RTL)-direction else direction)
            }
            swiping=false;parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}
