package com.irfan.rabta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import org.json.JSONObject
import java.util.concurrent.Executors

/** Small, account-scoped thumbnail cache. Actual authenticated files; no sample thumbnails. */
object StatusMedia {
    val worker=Executors.newFixedThreadPool(2)
    private val cache=object:LruCache<String,Bitmap>(8*1024*1024) {
        override fun sizeOf(key:String,value:Bitmap)=value.byteCount
    }
    fun thumbnail(c:Context,body:String,kind:String,owner:String):Bitmap {
        check(Session.id==owner) {"Account changed"}
        val metadata=JSONObject(body)
        val key=owner+":"+metadata.optString("remote",metadata.optString("local"))+":"+metadata.optString("sha256")
        cache.get(key)?.let {return it}
        val local=if(metadata.has("remote"))Transfers.download(c,body)else body
        check(Session.id==owner) {"Account changed"}
        val path=Attachments.file(c,local).absolutePath
        val bitmap=if(kind=="video") {
            if(Build.VERSION.SDK_INT>=27)MediaMetadataRetriever().let {retriever->
                try {retriever.setDataSource(path);retriever.getScaledFrameAtTime(0,MediaMetadataRetriever.OPTION_CLOSEST_SYNC,720,720)} finally {retriever.release()}
            }else {
                @Suppress("DEPRECATION")
                android.media.ThumbnailUtils.createVideoThumbnail(path,android.provider.MediaStore.Video.Thumbnails.MINI_KIND)
            }
        }else {
            val options=BitmapFactory.Options().apply {inJustDecodeBounds=true}
            BitmapFactory.decodeFile(path,options)
            require(options.outWidth>0&&options.outHeight>0) {"This photo could not be decoded"}
            options.inSampleSize=1
            while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>1024)options.inSampleSize*=2
            options.inJustDecodeBounds=false
            BitmapFactory.decodeFile(path,options)
        }?:error("Preview unavailable for this media")
        cache.put(key,bitmap);return bitmap
    }
}

/** Loads only when visible. Expired/old-account results are never installed into a view. */
class StatusMediaPreview(c:Context,private val body:String,private val kind:String,private val expires:Long,private val open:()->Unit):FrameLayout(c) {
    private val owner=Session.id
    private val image=ImageView(c).apply {scaleType=ImageView.ScaleType.CENTER_CROP;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
    private val label=RabtaUi.text(c,if(kind=="video")"Loading video preview…" else "Loading photo…",13f,color=AppTheme.sub(c)).apply {gravity=Gravity.CENTER;setPadding(RabtaUi.dp(c,16),RabtaUi.dp(c,16),RabtaUi.dp(c,16),RabtaUi.dp(c,16))}
    private var started=false
    private var attached=false
    private val bounds=android.graphics.Rect()
    private val scroll=ViewTreeObserver.OnScrollChangedListener {maybeLoad()}
    private val layout=ViewTreeObserver.OnGlobalLayoutListener {maybeLoad()}
    init {
        background=RabtaUi.shape(c,AppTheme.raised(c),16);clipToOutline=true
        addView(image,LayoutParams(-1,-1));addView(label,LayoutParams(-1,-1))
        contentDescription=if(kind=="video")"Open video status" else "Open photo status"
        isFocusable=true;setOnClickListener {open()}
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow();attached=true
        viewTreeObserver.addOnScrollChangedListener(scroll);viewTreeObserver.addOnGlobalLayoutListener(layout)
        post {maybeLoad()}
    }
    override fun onDetachedFromWindow() {
        attached=false
        if(viewTreeObserver.isAlive){viewTreeObserver.removeOnScrollChangedListener(scroll);viewTreeObserver.removeOnGlobalLayoutListener(layout)}
        super.onDetachedFromWindow()
    }
    private fun maybeLoad() {
        if(started||!attached||!isShown||!getGlobalVisibleRect(bounds)||expires<=System.currentTimeMillis()||owner!=Session.id)return
        started=true
        StatusMedia.worker.execute {
            val result=runCatching {StatusMedia.thumbnail(context.applicationContext,body,kind,owner)}
            post {
                if(!attached||owner!=Session.id||expires<=System.currentTimeMillis())return@post
                result.onSuccess {
                    image.setImageBitmap(it);label.visibility=View.GONE
                    if(kind=="video") {
                        label.visibility=View.VISIBLE;label.text="Video · tap to play";label.gravity=Gravity.BOTTOM or Gravity.START
                        label.setTextColor(android.graphics.Color.WHITE);label.setBackgroundColor(0x33000000)
                    }
                    setOnClickListener {open()}
                }.onFailure {
                    label.visibility=View.VISIBLE;label.text="Preview unavailable · tap to retry"
                    label.setTextColor(AppTheme.error(context))
                    setOnClickListener {started=false;label.text="Loading preview…";maybeLoad()}
                }
            }
        }
    }
}
