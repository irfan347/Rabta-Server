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
        const val GRAPHITE=0xFF080D14.toInt()
        const val VIOLET=0xFFA997FF.toInt()
        const val TEAL=0xFF54DDC5.toInt()
        const val TEXT=0xFFF3F6F5.toInt()
        const val SUB=0xFF94A5B5.toInt()
    }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val mark=c.getDrawable(R.drawable.ic_launcher_fg)
    private var state=VisionState()
    private var selected:String?=null
    private var foreground=false
    private var scheduled=false
    private var down:String?=null
    private var page=0
    private var zoom=1f
    private var panX=0f
    private var panY=0f
    private var lastX=0f
    private var lastY=0f
    private var moved=false
    var onViewportChanged:(()->Unit)?=null
    private val scale=android.view.ScaleGestureDetector(c,object:android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector:android.view.ScaleGestureDetector):Boolean {moved=true;zoomBy(detector.scaleFactor);return true}
    })
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
        background=RabtaUi.shape(c,AppTheme.surface(c),20)
        clipToOutline=true
        isFocusable=true
        contentDescription="Schematic nearby network. Tap a node, or choose an accessible peer button below. Positions are not location or distance."
        isClickable=true
    }
    private fun displayed():List<VisionPeer> {
        val first=state.peers.drop(page*12).take(12).toMutableList()
        val extra=state.peers.firstOrNull { it.id==selected }
        if(extra!=null&&first.none { it.id==extra.id }) { if(first.size==12)first.removeAt(11);first.add(extra) }
        return first
    }
    fun update(value:VisionState,selection:String?) {
        if(state!=value||selected!=selection){
            state=value;selected=selection
            page=page.coerceAtMost((pageCount()-1).coerceAtLeast(0))
            selection?.let {id->state.peers.indexOfFirst {it.id==id}.takeIf {it>=0}?.let {page=it/12}}
            invalidate();onViewportChanged?.invoke()
        }
        schedule()
    }
    fun canZoomIn()=zoom<3f
    fun canZoomOut()=zoom>1f
    fun canResetViewport()=zoom!=1f||panX!=0f||panY!=0f
    fun pageCount()=(state.peers.size+11)/12
    fun pageNumber()=page+1
    fun changePage(delta:Int) {
        page=(page+delta).coerceIn(0,(pageCount()-1).coerceAtLeast(0));selected=null
        resetViewport();onPeer?.invoke(null);onViewportChanged?.invoke();invalidate()
    }
    fun zoomBy(factor:Float) {zoom=(zoom*factor).coerceIn(1f,3f);boundPan();invalidate();onViewportChanged?.invoke()}
    fun resetViewport() {zoom=1f;panX=0f;panY=0f;invalidate();onViewportChanged?.invoke()}
    private fun boundPan(){panX=panX.coerceIn(-width*(zoom-1)/2,width*(zoom-1)/2);panY=panY.coerceIn(-height*(zoom-1)/2,height*(zoom-1)/2)}
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
        canvas.save();canvas.translate(cx+panX,cy+panY);canvas.scale(zoom,zoom);canvas.translate(-cx,-cy)
        val phase=(SystemClock.elapsedRealtime()%2400)/2400f
        val points=positions()
        val animate=shouldAnimate()
        for((peer,x,y) in points)if(peer.connected) {
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(1.5f)
            paint.color=if(peer.keyMatched) AppTheme.teal(context) else AppTheme.accent(context);paint.alpha=75
            canvas.drawLine(cx,cy,x,y,paint)
            if(animate) {
                paint.style=Paint.Style.FILL;paint.alpha=220
                canvas.drawCircle(cx+(x-cx)*phase,cy+(y-cy)*phase,dp(2.5f),paint)
            }
        }
        paint.alpha=255;paint.style=Paint.Style.FILL;paint.color=AppTheme.raised(context)
        canvas.drawCircle(cx,cy,dp(32f),paint)
        mark?.setBounds((cx-dp(25f)).toInt(),(cy-dp(25f)).toInt(),(cx+dp(25f)).toInt(),(cy+dp(25f)).toInt());mark?.draw(canvas)
        paint.color=AppTheme.text(context);paint.textSize=dp(13f);paint.textAlign=Paint.Align.CENTER
        canvas.drawText("You",cx,cy+dp(51f),paint)
        for((peer,x,y) in points) {
            paint.style=Paint.Style.FILL;paint.color=AppTheme.raised(context);canvas.drawCircle(x,y,dp(22f),paint)
            paint.style=Paint.Style.STROKE;paint.strokeWidth=dp(if(peer.id==selected)3f else 1.5f)
            paint.color=if(peer.id==selected)AppTheme.accent(context) else if(peer.connected)AppTheme.teal(context) else AppTheme.sub(context)
            canvas.drawCircle(x,y,dp(22f),paint)
            paint.style=Paint.Style.FILL;paint.textSize=dp(14f);paint.color=if(peer.connected)AppTheme.teal(context) else AppTheme.sub(context)
            canvas.drawText(peer.label.substringAfter(' '),x,y+dp(5f),paint)
            paint.textSize=dp(11f);paint.color=AppTheme.text(context)
            canvas.drawText(peer.label,x,y+dp(39f),paint)
        }
        canvas.restore()
    }
    private fun hit(x:Float,y:Float):String? {
        val worldX=(x-width/2f-panX)/zoom+width/2f
        val worldY=(y-height/2f-panY)/zoom+height/2f
        return positions().firstOrNull {(_,px,py)->hypot(worldX-px,worldY-py)<=dp(26f)}?.first?.id
    }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        scale.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN->{down=hit(event.x,event.y);lastX=event.x;lastY=event.y;moved=false;return true}
            MotionEvent.ACTION_POINTER_DOWN->{moved=true;parent?.requestDisallowInterceptTouchEvent(true)}
            MotionEvent.ACTION_MOVE->{
                val dx=event.x-lastX;val dy=event.y-lastY
                if(zoom>1f&&!scale.isInProgress&&(moved||hypot(dx,dy)>dp(4f))) {
                    moved=true;parent?.requestDisallowInterceptTouchEvent(true);panX+=dx;panY+=dy;boundPan();invalidate()
                }
                lastX=event.x;lastY=event.y
            }
            MotionEvent.ACTION_UP->{
                val target=hit(event.x,event.y)
                if(!moved&&target==down){selected=target;onPeer?.invoke(target);performClick();invalidate()}
                down=null;parent?.requestDisallowInterceptTouchEvent(false);return true
            }
            MotionEvent.ACTION_CANCEL->{down=null;parent?.requestDisallowInterceptTouchEvent(false);return true}
        }
        return true
    }
    override fun onKeyDown(keyCode:Int,event:android.view.KeyEvent):Boolean {
        val peers=displayed()
        if(peers.isNotEmpty()&&keyCode in listOf(android.view.KeyEvent.KEYCODE_DPAD_LEFT,android.view.KeyEvent.KEYCODE_DPAD_RIGHT)) {
            val current=peers.indexOfFirst {it.id==selected};val delta=if(keyCode==android.view.KeyEvent.KEYCODE_DPAD_RIGHT)1 else -1
            selected=peers[Math.floorMod(current+delta,peers.size)].id;onPeer?.invoke(selected);invalidate();return true
        }
        return super.onKeyDown(keyCode,event)
    }
    override fun performClick():Boolean { super.performClick();return true }
}
