package com.irfan.rabta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

class StatusActivity : ThemedActivity() {
    private val ADD = 33
    private lateinit var feed: LinearLayout // loading, errors and empty state under the cards
    private var posting = false
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var stories:LinearLayout
    private var generation=0
    private var foreground=false
    private var owner=""
    private val expiry=Runnable {refresh()}


    override fun onCreate(state: Bundle?) {
        super.onCreate(state);owner=Session.id
        val col=FeatureUi.page(this,"Status")
        // WhatsApp-style row: "Add status" first, then one card per person with a live update.
        stories=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,RabtaUi.dp(this@StatusActivity,4),0,RabtaUi.dp(this@StatusActivity,4))}
        col.addView(HorizontalScrollView(this).apply {addView(stories);isHorizontalScrollBarEnabled=false;clipToPadding=false})
        feed=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};col.addView(feed)
        col.addView(RabtaUi.text(this,"Updates disappear after 24 hours. Only the people you choose can see yours.",12f,color=AppTheme.sub(this)).apply {
            setPadding(RabtaUi.dp(this@StatusActivity,4),RabtaUi.dp(this@StatusActivity,12),RabtaUi.dp(this@StatusActivity,4),0)
        })
        renderCards(cached())
    }
    override fun onResume() {super.onResume();foreground=true;if(Session.id!=owner){finish();return};refresh()}
    override fun onPause() {foreground=false;generation++;handler.removeCallbacksAndMessages(null);super.onPause()}
    override fun onDestroy() {generation++;handler.removeCallbacksAndMessages(null);super.onDestroy()}

    private fun composeText() {
        val input = EditText(this).apply {
            hint = "What would you like to share?"; minLines = 3; maxLines = 7; gravity = Gravity.TOP
            filters = arrayOf(android.text.InputFilter.LengthFilter(700))
            setTextColor(AppTheme.text(this@StatusActivity)); setHintTextColor(AppTheme.sub(this@StatusActivity))
            background = RabtaUi.shape(this@StatusActivity, AppTheme.raised(this@StatusActivity), 16)
            setPadding(RabtaUi.dp(this@StatusActivity, 14), RabtaUi.dp(this@StatusActivity, 12), RabtaUi.dp(this@StatusActivity, 14), RabtaUi.dp(this@StatusActivity, 12))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(RabtaUi.dp(this@StatusActivity, 24), 0, RabtaUi.dp(this@StatusActivity, 24), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Text status").setView(content).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text.toString().trim()
                        if (text.isEmpty()) { input.error = "Write something first"; return@setOnClickListener }
                        dialog.dismiss(); audience("text", text)
                    }
                }
                dialog.show()
            }
    }

    private fun pick(mime: String, code: Int) {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE), code)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        if (requestCode == ADD) {
            // Picked on the Add status screen: text opens the composer; media goes through the same audience flow.
            when (val kind = data?.getStringExtra("kind")) {
                "text" -> composeText()
                "image", "video" -> data.data?.let { uri -> FeatureApi.run(this, { Attachments.pick(this, uri, false) }) { body -> audience(kind, body) } }
            }
            return
        }
        if (requestCode !in 31..32) return
        val uri = data?.data ?: return
        FeatureApi.run(this, { Attachments.pick(this, uri, false) }) { body -> audience(if (requestCode == 31) "image" else "video", body) }
    }

    private fun audience(kind: String, body: String) {
        val contacts = Db.get(this).conversations().filter { !it.isGroup && !Privacy.blocked(this, it.peerId) }
        if (contacts.isEmpty()) { themedMessage("No available contacts", "Add a contact before sharing a status. Blocked contacts cannot be selected."); return }
        val selected = BooleanArray(contacts.size)
        AlertDialog.Builder(this).setTitle("Choose viewers")
            .setMultiChoiceItems(contacts.map { it.peerName }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("Cancel", null).setPositiveButton("Share") { _, _ ->
                if (posting) return@setPositiveButton
                val ids = contacts.filterIndexed { index, _ -> selected[index] }.map { it.peerId }
                if (ids.isEmpty()) { themedMessage("Choose at least one viewer", "A status is only shared with contacts you select."); return@setPositiveButton }
                posting = true
                feed.addView(RabtaUi.loading(this, "Sharing status…"), 0)
                Thread {
                    val result = runCatching {
                        val remote = if (kind == "text") body else Transfers.upload(this, body, "status") {}
                        FeatureApi.request("/api/status", "POST", JSONObject().put("kind", kind).put("body", remote).put("audience", JSONArray(ids)))
                    }
                    runOnUiThread {
                        posting = false
                        if (isDestroyed || isFinishing) return@runOnUiThread
                        refresh()
                        result.onFailure { themedMessage("Status not shared", it.message ?: "Check your connection and try again.") }
                    }
                }.start()
            }.show()
    }

    private fun cachePrefs()=getSharedPreferences("status_cache",MODE_PRIVATE)
    /** The last list this account received, so the row appears at once while the server answers. */
    private fun cached():JSONArray=runCatching {JSONArray(cachePrefs().getString(owner,"[]"))}.getOrDefault(JSONArray())

    private fun refresh() {
        if(!foreground||isDestroyed||isFinishing)return
        val request=++generation;handler.removeCallbacks(expiry)
        feed.removeAllViews();feed.addView(RabtaUi.loading(this,"Updating…"))
        // A sleeping free server can take a while to answer the first request; say so instead of spinning silently.
        handler.postDelayed({
            if(request==generation&&foreground) {feed.removeAllViews();feed.addView(RabtaUi.loading(this,"Server is waking up — this can take up to a minute…"))}
        },8000)
        Thread {
            val result=runCatching {FeatureApi.request("/api/status").getJSONArray("statuses")}
            runOnUiThread {
                if(!foreground||isDestroyed||isFinishing||request!=generation||owner!=Session.id)return@runOnUiThread
                feed.removeAllViews()
                result.onSuccess {
                    cachePrefs().edit().putString(owner,it.toString()).apply()
                    render(it)
                }.onFailure {
                    feed.addView(RabtaUi.empty(this,"status","Could not load updates",it.message?:"Check your connection and try again.","Retry") {refresh()})
                }
            }
        }.start()
    }
    private fun render(items:JSONArray) {
        val live=renderCards(items)
        if(live.isEmpty()) feed.addView(RabtaUi.text(this,"No updates yet. Share your first moment, or check back for updates from your contacts.",13f,color=AppTheme.sub(this)).apply {
            setPadding(RabtaUi.dp(this@StatusActivity,4),RabtaUi.dp(this@StatusActivity,12),RabtaUi.dp(this@StatusActivity,4),0)
        })
        handler.removeCallbacks(expiry)
        if(live.isNotEmpty()) handler.postDelayed(expiry,(live.minOf {it.optLong("expires")}-System.currentTimeMillis()).coerceAtLeast(1))
    }

    /** Draws the card row and returns the updates that are still live. */
    private fun renderCards(items:JSONArray):List<JSONObject> {
        stories.removeAllViews()
        val now=System.currentTimeMillis()
        val live=(0 until items.length()).map {items.getJSONObject(it)}.filter {it.optLong("expires")>now}
        stories.addView(addCard())
        // Newest update per person; your own first.
        val latest=live.distinctBy {it.optString("owner")}.sortedByDescending {it.optString("owner")==owner}
        latest.take(30).forEach {stories.addView(statusCard(it))}
        return live
    }

    private fun card()=FrameLayout(this).apply {
        background=RabtaUi.shape(this@StatusActivity,AppTheme.surface(this@StatusActivity),20,AppTheme.line(this@StatusActivity));clipToOutline=true;isFocusable=true
        layoutParams=LinearLayout.LayoutParams(RabtaUi.dp(this@StatusActivity,112),RabtaUi.dp(this@StatusActivity,188)).apply {marginEnd=RabtaUi.dp(this@StatusActivity,10)}
    }
    private fun ringed(name:String,size:Int)=FrameLayout(this).apply {
        background=android.graphics.drawable.GradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setStroke(RabtaUi.dp(this@StatusActivity,2),AppTheme.accent(this@StatusActivity))}
        val pad=RabtaUi.dp(this@StatusActivity,3);setPadding(pad,pad,pad,pad);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        addView(TextView(this@StatusActivity).apply {
            text=name.filter {it.isLetterOrDigit()}.take(1).uppercase().ifEmpty {"R"};gravity=Gravity.CENTER;textSize=size/resources.displayMetrics.density*0.4f
            typeface=android.graphics.Typeface.DEFAULT_BOLD;setTextColor(android.graphics.Color.WHITE)
            background=RabtaUi.shape(this@StatusActivity,0xFF325E71.toInt(),999)
        },FrameLayout.LayoutParams(-1,-1))
    }
    private fun nameLabel(text:String)=RabtaUi.text(this,text,13f,true,android.graphics.Color.WHITE).apply {
        maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(RabtaUi.dp(this@StatusActivity,10),RabtaUi.dp(this@StatusActivity,24),RabtaUi.dp(this@StatusActivity,8),RabtaUi.dp(this@StatusActivity,10))
        background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0xCC000000.toInt(),0x00000000))
    }

    private fun addCard()=card().apply {
        contentDescription="Add status";setOnClickListener {startActivityForResult(Intent(this@StatusActivity,AddStatusActivity::class.java),ADD)}
        background=RabtaUi.ripple(this@StatusActivity,AppTheme.surface(this@StatusActivity),20)
        val avatarBox=FrameLayout(this@StatusActivity)
        val photo=Session.profilePic.takeIf {it.isNotEmpty()}?.let {MediaHelper.base64ToBitmap(it)}
        if(photo!=null) avatarBox.addView(ImageView(this@StatusActivity).apply {
            setImageBitmap(photo);scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=RabtaUi.shape(this@StatusActivity,AppTheme.raised(this@StatusActivity),999)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },FrameLayout.LayoutParams(-1,-1))
        else avatarBox.addView(ringed(Session.name,RabtaUi.dp(this@StatusActivity,64)),FrameLayout.LayoutParams(-1,-1))
        avatarBox.addView(TextView(this@StatusActivity).apply {
            UiIcons.set(this,"plus",AppTheme.onPrimary(this@StatusActivity),18,"Add");gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background=android.graphics.drawable.GradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(AppTheme.primary(this@StatusActivity));setStroke(RabtaUi.dp(this@StatusActivity,2),AppTheme.surface(this@StatusActivity))}
        },FrameLayout.LayoutParams(RabtaUi.dp(this@StatusActivity,26),RabtaUi.dp(this@StatusActivity,26),Gravity.BOTTOM or Gravity.END))
        addView(avatarBox,FrameLayout.LayoutParams(RabtaUi.dp(this@StatusActivity,64),RabtaUi.dp(this@StatusActivity,64),Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {topMargin=RabtaUi.dp(this@StatusActivity,40)})
        addView(RabtaUi.text(this@StatusActivity,"Add status",13f,true).apply {
            gravity=Gravity.CENTER;setPadding(RabtaUi.dp(this@StatusActivity,6),0,RabtaUi.dp(this@StatusActivity,6),RabtaUi.dp(this@StatusActivity,12))
        },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    }
    private fun statusCard(status:JSONObject)=card().apply {
        val own=status.optString("owner")==owner
        val name=if(own)"My status" else status.optString("name").ifBlank {"Rabta contact"}
        val kind=status.optString("kind")
        tag="status:${status.optString("id")}";contentDescription="View $name"
        setOnClickListener {view(status)}
        if(own) setOnLongClickListener {manage(status,status.optJSONArray("viewers")?:JSONArray());true}
        if(kind=="text") addView(RabtaUi.text(this@StatusActivity,status.optString("body"),14f,true).apply {
            gravity=Gravity.CENTER;maxLines=6;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(RabtaUi.dp(this@StatusActivity,10),RabtaUi.dp(this@StatusActivity,52),RabtaUi.dp(this@StatusActivity,10),RabtaUi.dp(this@StatusActivity,40))
            background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TL_BR,intArrayOf(AppTheme.sent(this@StatusActivity),AppTheme.raised(this@StatusActivity)))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },FrameLayout.LayoutParams(-1,-1))
        else addView(StatusMediaPreview(this@StatusActivity,status.getString("body"),kind,status.getLong("expires")) {view(status)}.apply {
            if(own) setOnLongClickListener {manage(status,status.optJSONArray("viewers")?:JSONArray());true}
        },FrameLayout.LayoutParams(-1,-1))
        addView(ringed(name,RabtaUi.dp(this@StatusActivity,40)),FrameLayout.LayoutParams(RabtaUi.dp(this@StatusActivity,40),RabtaUi.dp(this@StatusActivity,40)).apply {
            leftMargin=RabtaUi.dp(this@StatusActivity,8);topMargin=RabtaUi.dp(this@StatusActivity,8)
        })
        addView(nameLabel(name),FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    }

    private fun manage(status: JSONObject, viewers: JSONArray) {
        val names = (0 until viewers.length()).map { i ->
            val id = viewers.optString(i)
            Db.get(this).conversations().find { it.peerId == id }?.peerName ?: id
        }
        AlertDialog.Builder(this).setTitle("Status activity")
            .setMessage(if (names.isEmpty()) "No one has viewed this update yet." else "Viewed by\n${names.joinToString("\n")}")
            .setNegativeButton("Close", null).setPositiveButton("Delete status", null).create().also { dialog ->
                dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    AlertDialog.Builder(this).setTitle("Delete this status?").setMessage("Viewers will no longer be able to open it.")
                        .setNegativeButton("Cancel", null).setPositiveButton("Delete") { _, _ ->
                            FeatureApi.run(this, { FeatureApi.request("/api/status/${status.getString("id")}", "DELETE") }) { refresh() }
                        }.show(); dialog.dismiss()
                } }
                dialog.show()
            }
    }

    private fun view(status:JSONObject) {
        if(status.optLong("expires")<=System.currentTimeMillis()){refresh();return}
        startActivity(Intent(this,StatusViewerActivity::class.java).putExtra("statusId",status.getString("id")))
    }

    private fun themedMessage(title: String, message: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Close", null).show()
}
