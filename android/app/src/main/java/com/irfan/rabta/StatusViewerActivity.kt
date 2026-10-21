package com.irfan.rabta

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.*
import org.json.JSONObject

/**
 * Full-screen, auto-advancing status viewer (WhatsApp-style): bars across the top, tap right/left for next/previous,
 * hold to pause, swipe down to close. Intents carry IDs only; every update is checked against the
 * audience-filtered feed from the server before anything is shown, and expiry closes it on time.
 */
class StatusViewerActivity:ThemedActivity() {
    private lateinit var content:FrameLayout
    private lateinit var state:FrameLayout
    private lateinit var bars:StoryBarsView
    private lateinit var title:TextView
    private lateinit var subtitle:TextView
    private lateinit var avatarSlot:FrameLayout
    private lateinit var captionView:TextView
    private lateinit var footer:FrameLayout
    private lateinit var menu:View
    private lateinit var mute:ImageView
    private val handler=Handler(Looper.getMainLooper())
    private var video:VideoView?=null
    private var player:android.media.MediaPlayer?=null
    private var generation=0
    private var expires=0L
    private var resumed=false
    private var owner=""
    private var ids:List<String> = emptyList()
    private var index=0
    private var statusId=""
    private var current:JSONObject?=null
    private var feed:Map<String,JSONObject> = emptyMap()
    private var feedAt=0L
    private var mediaBody:String?=null
    private var openingExternal=false
    // Playback clock for photos and text; videos report their own position.
    private var playing=false
    private var paused=false
    private var held=false
    private var shownFor=0L
    private var startedAt=0L
    private var length=6000L
    private var muted=false
    private val expiry=object:Runnable {override fun run(){if(resumed){if(expires<=System.currentTimeMillis())unavailable("This update has expired.")else handler.postDelayed(this,minOf(1000,expires-System.currentTimeMillis()))}}}
    private val ticker=object:Runnable {override fun run(){
        if(!playing||paused)return
        val v=video
        bars.fraction=if(v!=null) (if(v.duration>0) v.currentPosition.toFloat()/v.duration else 0f) else elapsed().toFloat()/length
        if(v==null&&elapsed()>=length){next();return}
        handler.postDelayed(this,50)
    }}
    private fun elapsed()=shownFor+(if(playing&&!paused) SystemClock.elapsedRealtime()-startedAt else 0)
    private fun dp(n:Int)=RabtaUi.dp(this,n)

    override fun onCreate(state0:Bundle?) {
        super.onCreate(state0);owner=Session.id
        statusId=intent.getStringExtra("statusId").orEmpty()
        ids=intent.getStringArrayListExtra("statusIds")?.filter {Regex("[a-f0-9]{32}").matches(it)}.orEmpty().ifEmpty {listOf(statusId)}
        index=ids.indexOf(statusId).coerceAtLeast(0)
        RabtaUi.systemBars(this,true)
        val root=FrameLayout(this).apply {setBackgroundColor(Color.BLACK)}
        content=FrameLayout(this);root.addView(content,FrameLayout.LayoutParams(-1,-1))
        // Gestures sit above the media and below the controls.
        root.addView(View(this).apply {importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO;setOnTouchListener {v,e->touch(v,e)}},FrameLayout.LayoutParams(-1,-1))
        state=FrameLayout(this);root.addView(state,FrameLayout.LayoutParams(-1,-1))

        val top=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(8),dp(8),dp(8),dp(20))
            background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(0x99000000.toInt(),0))
        }
        bars=StoryBarsView(this);top.addView(bars,LinearLayout.LayoutParams(-1,dp(6)).apply {leftMargin=dp(4);rightMargin=dp(4)})
        val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(6),0,0)}
        head.addView(RabtaUi.iconButton(this,"back","Close status") {finish()}.apply {UiIcons.set(this,"back",Color.WHITE,24,"Close status")})
        avatarSlot=FrameLayout(this);head.addView(avatarSlot,LinearLayout.LayoutParams(dp(40),dp(40)).apply {marginEnd=dp(10)})
        val labels=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        title=RabtaUi.text(this,"Status",16f,true,Color.WHITE).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        subtitle=RabtaUi.text(this,"",12f,color=0xCCFFFFFF.toInt())
        labels.addView(title);labels.addView(subtitle);head.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        mute=UiIcons.image(this,"speaker",Color.WHITE,22,"Mute video").apply {
            isFocusable=true;visibility=View.GONE;background=RabtaUi.ripple(this@StatusViewerActivity,Color.TRANSPARENT,999);setOnClickListener {toggleMute()}
        }
        head.addView(mute,LinearLayout.LayoutParams(dp(44),dp(44)))
        menu=UiIcons.image(this,"more",Color.WHITE,22,"Status options").apply {
            isFocusable=true;visibility=View.GONE;background=RabtaUi.ripple(this@StatusViewerActivity,Color.TRANSPARENT,999);setOnClickListener {options()}
        }
        head.addView(menu,LinearLayout.LayoutParams(dp(44),dp(44)))
        top.addView(head);root.addView(top,FrameLayout.LayoutParams(-1,-2,Gravity.TOP))

        val bottom=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(dp(16),dp(32),dp(16),dp(16))
            background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0xAA000000.toInt(),0))
        }
        captionView=RabtaUi.text(this,"",16f,color=Color.WHITE).apply {gravity=Gravity.CENTER;maxLines=6;visibility=View.GONE;setPadding(0,0,0,dp(12))}
        bottom.addView(captionView,LinearLayout.LayoutParams(-1,-2))
        footer=FrameLayout(this);bottom.addView(footer,LinearLayout.LayoutParams(-1,-2))
        root.addView(bottom,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        setContentView(root)
    }
    override fun onResume(){super.onResume();resumed=true;load(false)}
    override fun onPause(){resumed=false;generation++;stopPlayback();handler.removeCallbacksAndMessages(null);content.removeAllViews();super.onPause()}
    override fun onDestroy(){generation++;handler.removeCallbacksAndMessages(null);stopPlayback();super.onDestroy()}

    // ── Gestures: tap left/right, hold to pause, swipe down to close, swipe up for viewers ──
    private var downX=0f
    private var downY=0f
    private var downAt=0L
    private val holdStart=Runnable {held=true;pausePlayback()}
    private fun touch(v:View,e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;downAt=SystemClock.uptimeMillis();handler.postDelayed(holdStart,220)}
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL->{
                handler.removeCallbacks(holdStart)
                val dx=e.rawX-downX;val dy=e.rawY-downY
                val wasHeld=held;held=false
                if(e.actionMasked==MotionEvent.ACTION_CANCEL){if(wasHeld)resumePlayback();return true}
                when {
                    dy>dp(120)&&Math.abs(dy)>Math.abs(dx) -> finish()
                    dy< -dp(100)&&Math.abs(dy)>Math.abs(dx)&&current?.optString("owner")==owner -> {if(wasHeld)resumePlayback();viewers()}
                    wasHeld -> resumePlayback()
                    SystemClock.uptimeMillis()-downAt<350&&Math.abs(dx)<dp(24)&&Math.abs(dy)<dp(24) -> if(e.x<v.width/3f) previous() else next()
                }
            }
        }
        return true
    }

    // ── Loading and showing one update ──
    private fun load(forceFeed:Boolean) {
        handler.removeCallbacks(expiry);stopPlayback();mediaBody=null
        val request=++generation
        statusId=ids.getOrNull(index)?:statusId
        content.removeAllViews();state.removeAllViews();footer.removeAllViews();captionView.visibility=View.GONE
        state.addView(spinner(),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        current=feed[statusId]?.takeIf {!forceFeed&&SystemClock.elapsedRealtime()-feedAt<60_000}
        current?.let {header(it)}
        if(!Session.loggedIn||owner!=Session.id||!Regex("[a-f0-9]{32}").matches(statusId)){unavailable("Status unavailable.");return}
        Thread {
            var fetched:JSONObject?=null
            val result=runCatching {
                val status=current?:fetchStatus()
                check(status.optLong("expires")>System.currentTimeMillis()) {"This update has expired."}
                fetched=status
                // Count the view once; seen updates open without another request.
                if(status.optString("id") !in StatusPost.seen(this)) runCatching {FeatureApi.request("/api/status/${status.optString("id")}/view","POST")}
                val kind=status.optString("kind")
                val media=when(kind) {
                    "video"->Transfers.download(this,status.getString("body")) to null
                    "image"->null to StatusMedia.thumbnail(applicationContext,status.getString("body"),"image",owner)
                    else->null to null
                }
                status to media
            }
            runOnUiThread {
                if(!valid(request))return@runOnUiThread
                result.onSuccess {(status,media)->render(status,media.first,media.second,request)}.onFailure {
                    // A live photo/video this phone cannot show can still open in another app (re-checked first).
                    fetched?.takeIf {s->s.optString("kind")!="text"}?.let {s->mediaBody=s.optString("body");expires=s.optLong("expires")}
                    unavailable(it.message?:"Could not open status. Check your connection.",true)
                }
            }
        }.start()
    }
    /** The audience-filtered feed is the only source of truth; anything not in it is not shown. */
    private fun fetchStatus():JSONObject {
        check(Session.id==owner&&Session.loggedIn) {"Account changed. Open Status again."}
        val list=FeatureApi.request("/api/status").getJSONArray("statuses")
        val live=(0 until list.length()).map {list.getJSONObject(it)}.filter {it.optLong("expires")>System.currentTimeMillis()}.associateBy {it.optString("id")}
        runOnUiThread {feed=live;feedAt=SystemClock.elapsedRealtime()}
        return live[statusId]?:error("This update has expired or is no longer shared with you.")
    }
    private fun header(status:JSONObject) {
        val own=status.optString("owner")==owner
        title.text=if(own)"My status" else status.optString("name","Rabta contact")
        subtitle.text=StatusPost.stamp(this,status.optLong("created"))
        avatarSlot.removeAllViews()
        avatarSlot.addView(StatusPost.avatar(this,status.optString("owner"),title.text.toString(),40),FrameLayout.LayoutParams(-1,-1))
        menu.visibility=if(own)View.VISIBLE else View.GONE
        // Bars cover this person's run of updates.
        val who=status.optString("owner")
        var first=index;var last=index
        while(first>0&&feed[ids[first-1]]?.optString("owner")==who)first--
        while(last<ids.size-1&&feed[ids[last+1]]?.optString("owner")==who)last++
        bars.count=last-first+1;bars.index=index-first;bars.fraction=0f
    }
    private fun spinner()=ProgressBar(this).apply {indeterminateTintList=android.content.res.ColorStateList.valueOf(AppTheme.primary(this@StatusViewerActivity));contentDescription="Opening status"}

    private var photo:android.graphics.Bitmap?=null
    private fun render(status:JSONObject,local:String?,bitmap:android.graphics.Bitmap?,request:Int) {
        current=status;header(status);photo=if(status.optString("kind")=="image") bitmap else null
        mediaBody=if(status.optString("kind")=="text")null else status.optString("body")
        expires=status.optLong("expires");if(expires<=System.currentTimeMillis()){unavailable("This update has expired.");return}
        content.removeAllViews();state.removeAllViews();handler.post(expiry)
        StatusPost.markSeen(this,status.optString("id"))
        status.optString("caption").takeIf {it.isNotBlank()&&status.optString("kind")!="text"}?.let {captionView.text=it;captionView.visibility=View.VISIBLE}
        footer(status)
        when(status.optString("kind")) {
            "text"->{
                val body=status.optString("body")
                content.addView(RabtaUi.text(this,body,if(body.length<80)30f else 22f,true,Color.WHITE).apply {
                    gravity=Gravity.CENTER;setPadding(dp(28),dp(96),dp(28),dp(120))
                    setBackgroundColor(StatusLook.background(status));typeface=StatusLook.typeface(status.optString("font"))
                },FrameLayout.LayoutParams(-1,-1))
                startClock((5000L+body.length*40L).coerceAtMost(12000L))
            }
            "image"->{
                content.addView(ImageView(this).apply {setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Status photo"},FrameLayout.LayoutParams(-1,-1))
                startClock(6000L)
            }
            else->{
                state.addView(spinner(),FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
                video=VideoView(this).apply {
                    contentDescription="Status video";setVideoURI(android.net.Uri.fromFile(Attachments.file(this@StatusViewerActivity,local!!)))
                    setOnPreparedListener {mp->player=mp;applyMute();state.removeAllViews();if(valid(request)){startClock(mp.duration.toLong());if(!paused)start()}}
                    setOnCompletionListener {if(valid(request))next()}
                    setOnErrorListener {_,_,_->unavailable("This video could not be played.",true);true}
                }
                mute.visibility=View.VISIBLE
                content.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
            }
        }
    }
    private fun footer(status:JSONObject) {
        footer.removeAllViews()
        if(status.optString("owner")==owner) {
            val count=status.optJSONArray("viewers")?.length()?:0
            footer.addView(LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isFocusable=true;contentDescription="Viewed by $count. Show viewers"
                background=RabtaUi.ripple(this@StatusViewerActivity,Color.TRANSPARENT,16);setPadding(dp(20),dp(4),dp(20),dp(4));setOnClickListener {viewers()}
                addView(UiIcons.image(this@StatusViewerActivity,"chevron_up",Color.WHITE,20,"Swipe up"),LinearLayout.LayoutParams(-2,dp(20)))
                addView(LinearLayout(this@StatusViewerActivity).apply {
                    gravity=Gravity.CENTER
                    addView(UiIcons.image(this@StatusViewerActivity,"eye",Color.WHITE,20,"Views"),LinearLayout.LayoutParams(dp(24),dp(24)).apply {marginEnd=dp(6)})
                    addView(RabtaUi.text(this@StatusViewerActivity,count.toString(),16f,true,Color.WHITE))
                })
            },FrameLayout.LayoutParams(-2,-2,Gravity.CENTER))
        } else {
            // Reply, quick emoji replies like WhatsApp's, and a heart.
            val bar=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
            bar.addView(LinearLayout(this).apply {
                gravity=Gravity.CENTER_VERTICAL;isFocusable=true;contentDescription="Reply to ${status.optString("name")}";minimumHeight=dp(48)
                background=RabtaUi.ripple(this@StatusViewerActivity,0x33FFFFFF,999);setPadding(dp(18),0,dp(8),0);setOnClickListener {reply(status)}
                addView(UiIcons.image(this@StatusViewerActivity,"reply",Color.WHITE,20,"Reply"),LinearLayout.LayoutParams(dp(24),dp(24)).apply {marginEnd=dp(10)})
                addView(RabtaUi.text(this@StatusViewerActivity,"Reply",15f,true,Color.WHITE),LinearLayout.LayoutParams(0,-2,1f))
                listOf("😍","😂","😮").forEach {e->addView(RabtaUi.text(this@StatusViewerActivity,e,24f).apply {
                    gravity=Gravity.CENTER;isFocusable=true;contentDescription="Reply with $e";background=RabtaUi.ripple(this@StatusViewerActivity,Color.TRANSPARENT,999)
                    setOnClickListener {quickReply(status,e)}
                },LinearLayout.LayoutParams(dp(44),dp(44)))}
            },LinearLayout.LayoutParams(0,-2,1f))
            bar.addView(UiIcons.image(this,"heart",Color.WHITE,24,"Reply with a heart").apply {
                isFocusable=true;background=RabtaUi.ripple(this@StatusViewerActivity,0x33FFFFFF,999);setOnClickListener {quickReply(status,"❤️")}
            },LinearLayout.LayoutParams(dp(48),dp(48)).apply {marginStart=dp(8)})
            footer.addView(bar,FrameLayout.LayoutParams(-1,-2))
        }
    }

    // ── Playback clock ──
    private fun startClock(ms:Long) {length=ms.coerceAtLeast(1000);shownFor=0;startedAt=SystemClock.elapsedRealtime();playing=true;paused=held;handler.removeCallbacks(ticker);handler.post(ticker)}
    private fun pausePlayback() {if(!playing||paused)return;paused=true;shownFor+=SystemClock.elapsedRealtime()-startedAt;video?.pause()}
    private fun resumePlayback() {if(!playing||!paused)return;paused=false;startedAt=SystemClock.elapsedRealtime();video?.start();handler.removeCallbacks(ticker);handler.post(ticker)}
    private fun stopPlayback() {
        playing=false;paused=false;held=false;handler.removeCallbacks(ticker);handler.removeCallbacks(holdStart)
        video?.stopPlayback();video=null;player=null;mute.visibility=View.GONE
    }
    private fun next() {if(index<ids.size-1){index++;load(false)}else finish()}
    private fun previous() {if(index>0)index--;load(false)}
    private fun toggleMute() {muted=!muted;applyMute()}
    private fun applyMute() {
        runCatching {player?.setVolume(if(muted)0f else 1f,if(muted)0f else 1f)}
        UiIcons.drawable(this,if(muted)"volume_off" else "speaker",Color.WHITE,22).let {mute.setImageDrawable(it)}
        mute.contentDescription=if(muted)"Unmute video" else "Mute video"
    }

    // ── Own updates: viewers and delete. Others: reply in the chat ──
    private fun viewers() {
        val status=current?:return
        held=true;pausePlayback()
        StatusPost.viewers(this,status,onDelete={delete(status)},onClose={held=false;resumePlayback()})
    }
    private fun options() {
        val status=current?:return
        held=true;pausePlayback()
        android.app.AlertDialog.Builder(this).setItems(arrayOf("Viewers","Delete status")) {_,i->if(i==0)viewers() else delete(status)}
            .setOnCancelListener {held=false;resumePlayback()}.show()
    }
    private fun delete(status:JSONObject) {
        android.app.AlertDialog.Builder(this).setTitle("Delete this status?").setMessage("Viewers will no longer be able to open it.")
            .setNegativeButton("Cancel") {_,_->held=false;resumePlayback()}
            .setPositiveButton("Delete") {_,_->
                FeatureApi.run(this,{FeatureApi.request("/api/status/${status.getString("id")}","DELETE")}) {
                    Toast.makeText(this,"Status deleted",Toast.LENGTH_SHORT).show()
                    ids=ids-status.getString("id");feed=feed-status.getString("id")
                    if(ids.isEmpty())finish() else {index=index.coerceAtMost(ids.size-1);load(false)}
                }
            }.show()
    }
    /** A reply is an ordinary chat message quoting the update, sent to its owner. */
    private fun reply(status:JSONObject) {
        val to=status.optString("owner");val name=status.optString("name").ifBlank {"Rabta contact"}
        if(Privacy.blocked(this,to)){Toast.makeText(this,"Unblock this contact to reply",Toast.LENGTH_SHORT).show();return}
        held=true;pausePlayback()
        val field=EditText(this).apply {hint="Reply to $name…";maxLines=4;filters=arrayOf(android.text.InputFilter.LengthFilter(1000))}
        val box=LinearLayout(this).apply {setPadding(dp(20),dp(8),dp(20),0);addView(field,LinearLayout.LayoutParams(-1,-2))}
        android.app.AlertDialog.Builder(this).setTitle("Reply to status").setView(box)
            .setNegativeButton("Cancel",null).setPositiveButton("Send") {_,_->
                val text=field.text.toString().trim()
                if(text.isNotEmpty()&&send(status,text))Toast.makeText(this,"Reply sent to $name",Toast.LENGTH_SHORT).show()
            }.setOnDismissListener {held=false;resumePlayback()}.show()
    }
    private fun quickReply(status:JSONObject,emoji:String) {
        if(Privacy.blocked(this,status.optString("owner"))){Toast.makeText(this,"Unblock this contact to reply",Toast.LENGTH_SHORT).show();return}
        if(send(status,emoji)) {
            Toast.makeText(this,"Sent $emoji",Toast.LENGTH_SHORT).show()
            val reaction=RabtaUi.text(this,emoji,64f).apply { gravity=Gravity.CENTER }
            val decor=window.decorView as android.view.ViewGroup
            decor.addView(reaction,android.view.ViewGroup.LayoutParams(-1,-1));EmojiMotion.play(reaction)
            reaction.postDelayed({ decor.removeView(reaction) },700)
        }
    }
    /**
     * The reply quotes the update: "reply" is the line older apps show, and "status" lets chats show a card with the
     * owner, the words or caption and, for photos, a small picture.
     */
    private fun send(status:JSONObject,text:String):Boolean {
        if(!WsClient.connected){Toast.makeText(this,"Replies need internet",Toast.LENGTH_SHORT).show();return false}
        val to=status.optString("owner");val name=status.optString("name").ifBlank {"Rabta contact"};val kind=status.optString("kind")
        val quote="Status · "+when(kind){"text"->status.optString("body").take(80);"video"->"Video";else->"Photo"}+status.optString("caption").takeIf {it.isNotBlank()}?.let {" · ${it.take(60)}"}.orEmpty()
        val card=JSONObject().put("owner",to).put("name",name).put("kind",kind).put("text",(if(kind=="text") status.optString("body") else status.optString("caption")).take(300))
        if(kind=="text")card.put("bg",StatusLook.hex(StatusLook.background(status)))
        photo?.takeIf {kind=="image"}?.let {thumbnail(it)}?.let {card.put("thumb",it)}
        Outbox.send(to,name,false,JSONObject().put("text",text).put("reply",quote).put("status",card).toString(),"rich")
        return true
    }
    private fun thumbnail(src:android.graphics.Bitmap):String?=runCatching {
        val scale=112f/maxOf(src.width,src.height)
        val small=android.graphics.Bitmap.createScaledBitmap(src,maxOf(1,(src.width*scale).toInt()),maxOf(1,(src.height*scale).toInt()),true)
        val out=java.io.ByteArrayOutputStream();small.compress(android.graphics.Bitmap.CompressFormat.JPEG,70,out)
        android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP).takeIf {it.length<9000}
    }.getOrNull()

    private fun openExternal() {
        if(openingExternal)return
        if(expires<=System.currentTimeMillis()){unavailable("This update has expired.");return}
        openingExternal=true;val request=generation
        StatusMedia.worker.execute {
            val result=runCatching {
                val status=fetchStatus()
                check(status.optString("kind") in setOf("image","video")) {"Media unavailable"}
                status.getLong("expires") to Transfers.download(applicationContext,status.getString("body"))
            }
            runOnUiThread {
                openingExternal=false
                if(!valid(request))return@runOnUiThread
                result.onSuccess {(until,local)->
                    if(until<=System.currentTimeMillis())unavailable("This update has expired.")else Attachments.open(this,local)
                }.onFailure {mediaBody=null;unavailable(it.message?:"Could not open media. Check your connection.",true)}
            }
        }
    }
    private fun valid(request:Int)=request==generation&&resumed&&!isDestroyed&&!isFinishing&&Session.id==owner
    private fun unavailable(message:String,retry:Boolean=false) {
        generation++;handler.removeCallbacks(expiry);stopPlayback();current=null
        content.removeAllViews();state.removeAllViews();footer.removeAllViews();captionView.visibility=View.GONE
        val card=RabtaUi.empty(this,"status","Status unavailable",message,if(retry)"Retry" else "Close") {if(retry)load(true)else finish()}
        if(retry&&mediaBody!=null)card.addView(RabtaUi.button(this,"Open with another app") {openExternal()})
        card.background=RabtaUi.shape(this,AppTheme.surface(this),24)
        state.addView(card,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER).apply {leftMargin=dp(20);rightMargin=dp(20)})
    }

    companion object {
        private val TEXT_COLORS=intArrayOf(0xFF1E3A2F.toInt(),0xFF2B2459.toInt(),0xFF0E4F4A.toInt(),0xFF5B2C3A.toInt(),0xFF2C3E57.toInt(),0xFF4A3B12.toInt())
        /** Text updates get a steady background colour from their ID. */
        fun textColor(id:String)=TEXT_COLORS[(id.hashCode() and 0x7FFFFFFF)%TEXT_COLORS.size]
    }
}
