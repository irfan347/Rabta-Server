package com.irfan.rabta

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.*
import org.json.JSONObject

/** Fetch the audience-filtered record again on every resume; intents carry an ID, never media or credentials. */
class StatusViewerActivity:ThemedActivity() {
    private lateinit var content:FrameLayout
    private lateinit var title:TextView
    private lateinit var subtitle:TextView
    private val handler=Handler(Looper.getMainLooper())
    private var video:VideoView?=null
    private var generation=0
    private var expires=0L
    private var resumed=false
    private var owner=""
    private var statusId=""
    private val expiry=object:Runnable {override fun run(){if(resumed){if(expires<=System.currentTimeMillis())unavailable("This update has expired.")else handler.postDelayed(this,minOf(1000,expires-System.currentTimeMillis()))}}}
    override fun onCreate(state:Bundle?) {
        super.onCreate(state);owner=Session.id;statusId=intent.getStringExtra("statusId").orEmpty()
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(AppTheme.bg(this@StatusViewerActivity))}
        val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(RabtaUi.dp(this@StatusViewerActivity,8),RabtaUi.dp(this@StatusViewerActivity,8),RabtaUi.dp(this@StatusViewerActivity,16),RabtaUi.dp(this@StatusViewerActivity,8))}
        head.addView(RabtaUi.iconButton(this,"back","Close status") {finish()})
        val labels=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        title=RabtaUi.text(this,"Status",18f,true);subtitle=RabtaUi.text(this,"",12f,color=AppTheme.sub(this))
        labels.addView(title);labels.addView(subtitle);head.addView(labels,LinearLayout.LayoutParams(0,-2,1f));root.addView(head)
        content=FrameLayout(this);root.addView(content,LinearLayout.LayoutParams(-1,0,1f));setContentView(root)
    }
    override fun onResume(){super.onResume();resumed=true;load()}
    override fun onPause(){resumed=false;generation++;handler.removeCallbacksAndMessages(null);video?.stopPlayback();video=null;content.removeAllViews();super.onPause()}
    override fun onDestroy(){generation++;handler.removeCallbacksAndMessages(null);video?.stopPlayback();super.onDestroy()}
    private fun load() {
        handler.removeCallbacks(expiry);video?.stopPlayback();video=null
        val request=++generation
        content.removeAllViews();content.addView(RabtaUi.loading(this,"Opening status…"))
        if(!Session.loggedIn||owner!=Session.id||!Regex("[a-f0-9]{32}").matches(statusId)){unavailable("Status unavailable.");return}
        Thread {
            val result=runCatching {
                val feed=FeatureApi.request("/api/status").getJSONArray("statuses")
                val status=(0 until feed.length()).map {feed.getJSONObject(it)}.firstOrNull {it.optString("id")==statusId}?:error("This update has expired or is no longer shared with you.")
                check(status.optLong("expires")>System.currentTimeMillis()) {"This update has expired."}
                FeatureApi.request("/api/status/$statusId/view","POST")
                status
            }
            runOnUiThread {
                if(!valid(request))return@runOnUiThread
                result.onSuccess {render(it,request)}.onFailure {unavailable(it.message?:"Could not open status. Check your connection.",true)}
            }
        }.start()
    }
    private fun valid(request:Int)=request==generation&&resumed&&!isDestroyed&&!isFinishing&&Session.id==owner
    private fun unavailable(message:String,retry:Boolean=false) {
        generation++;handler.removeCallbacks(expiry);video?.stopPlayback();video=null
        content.removeAllViews();content.addView(RabtaUi.empty(this,"status","Status unavailable",message,if(retry)"Retry" else "Close") {if(retry)load()else finish()})
    }
    private fun render(status:JSONObject,request:Int) {
        expires=status.optLong("expires");if(expires<=System.currentTimeMillis()){unavailable("This update has expired.");return}
        title.text=if(status.optString("owner")==owner)"Your status" else  status.optString("name","Rabta contact")
        subtitle.text=android.text.format.DateUtils.getRelativeTimeSpanString(status.optLong("created"),System.currentTimeMillis(),60000).toString()+" · 24-hour update"
        content.removeAllViews();handler.post(expiry)
        if(status.optString("kind")=="text") {
            content.addView(ScrollView(this).apply {isFillViewport=true;addView(RabtaUi.text(this@StatusViewerActivity,status.optString("body"),24f,true).apply {
                gravity=Gravity.CENTER;setPadding(RabtaUi.dp(context,24),RabtaUi.dp(context,24),RabtaUi.dp(context,24),RabtaUi.dp(context,24))
                background=RabtaUi.shape(context,AppTheme.sent(context),20)
            },FrameLayout.LayoutParams(-1,-1))});return
        }
        content.addView(RabtaUi.loading(this,"Loading media…"))
        StatusMedia.worker.execute {
            val result=runCatching {
                val body=status.getString("body")
                if(status.optString("kind")=="video") Transfers.download(this,body) to null
                else null to StatusMedia.thumbnail(applicationContext,body,"image",owner)
            }
            runOnUiThread {
                if(!valid(request))return@runOnUiThread
                if(expires<=System.currentTimeMillis()){unavailable("This update has expired.");return@runOnUiThread}
                result.onSuccess { (local,bitmap) ->
                    content.removeAllViews()
                    if(bitmap!=null)content.addView(ImageView(this).apply {setImageBitmap(bitmap);scaleType=ImageView.ScaleType.FIT_CENTER;contentDescription="Status photo"},FrameLayout.LayoutParams(-1,-1))
                    else {
                        video=VideoView(this).apply {
                            val controls=MediaController(this@StatusViewerActivity);controls.setAnchorView(this);setMediaController(controls)
                            contentDescription="Status video";setVideoURI(android.net.Uri.fromFile(Attachments.file(this@StatusViewerActivity,local!!)))
                            setOnPreparedListener {start()}
                            setOnErrorListener {_,_,_->unavailable("This video could not be played.",true);true}
                        }
                        content.addView(video,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
                    }
                }.onFailure {unavailable(it.message?:"Could not load media.",true)}
            }
        }
    }
}
