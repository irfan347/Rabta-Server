package com.irfan.rabta

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject

class StatusActivity : ThemedActivity() {
    private val ADD = 33
    private val EDIT = 34
    private lateinit var feed: LinearLayout // loading, errors and empty state under the cards
    private var posting = false
    private var sharing: Int? = null // upload progress of an update being shared, shown on My status
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var stories:LinearLayout
    private var generation=0
    private var foreground=false
    private var owner=""
    private var lastItems=JSONArray()
    private val expiry=Runnable {refresh()}
    private fun dp(n:Int)=RabtaUi.dp(this,n)


    override fun onCreate(state: Bundle?) {
        super.onCreate(state);owner=Session.id
        val col=FeatureUi.page(this,"Status")
        // WhatsApp-style row: My status first (or Add status), then one card per person with live updates.
        stories=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(4),0,dp(4))}
        col.addView(HorizontalScrollView(this).apply {addView(stories);isHorizontalScrollBarEnabled=false;clipToPadding=false})
        feed=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE};col.addView(feed)
        col.addView(RabtaUi.text(this,"Updates disappear after 24 hours. Only the people you choose can see yours.",12f,color=AppTheme.sub(this)).apply {
            setPadding(dp(4),dp(12),dp(4),0)
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
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), 0, dp(24), 0); addView(input) }
        AlertDialog.Builder(this).setTitle("Text status").setView(content).setNegativeButton("Cancel", null)
            .setPositiveButton("Continue", null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = input.text.toString().trim()
                        if (text.isEmpty()) { input.error = "Write something first"; return@setOnClickListener }
                        dialog.dismiss(); audience(text)
                    }
                }
                dialog.show()
            }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            // Picked on the Add status screen: text opens the composer; photos and videos open the editor first.
            ADD -> when (val kind = data?.getStringExtra("kind")) {
                "text" -> composeText()
                "image", "video" -> data.data?.let { edit(kind, it) }
            }
            EDIT -> {
                val kind = data?.getStringExtra("kind") ?: return; val body = data.getStringExtra("body") ?: return
                share(kind, body, data.getStringExtra("caption").orEmpty(), data.getStringArrayExtra("audience")?.toList().orEmpty())
            }
            31, 32 -> data?.data?.let { edit(if (requestCode == 31) "image" else "video", it) }
        }
    }
    private fun edit(kind: String, uri: android.net.Uri) {
        startActivityForResult(Intent(this, StatusEditorActivity::class.java).putExtra("kind", kind).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), EDIT)
    }

    /** Text updates still ask who can see them each time; the choice is remembered for the photo editor. */
    private fun audience(text: String) {
        val contacts = StatusPost.contacts(this)
        if (contacts.isEmpty()) { themedMessage("No available contacts", "Add a contact before sharing a status. Blocked contacts cannot be selected."); return }
        val selected = BooleanArray(contacts.size)
        AlertDialog.Builder(this).setTitle("Choose viewers")
            .setMultiChoiceItems(contacts.map { it.peerName }.toTypedArray(), selected) { _, index, checked -> selected[index] = checked }
            .setNegativeButton("Cancel", null).setPositiveButton("Share") { _, _ ->
                val ids = contacts.filterIndexed { index, _ -> selected[index] }.map { it.peerId }
                if (ids.isEmpty()) { themedMessage("Choose at least one viewer", "A status is only shared with contacts you select."); return@setPositiveButton }
                StatusPost.saveAudience(this, ids)
                share("text", text, "", ids)
            }.show()
    }

    private fun share(kind: String, body: String, caption: String, ids: List<String>) {
        if (posting) { themedMessage("Please wait", "Your previous update is still being shared."); return }
        if (ids.isEmpty()) { themedMessage("Choose at least one viewer", "A status is only shared with contacts you select."); return }
        posting = true; sharing = 0; renderCards(lastItems)
        feed.addView(RabtaUi.loading(this, "Sharing status…"), 0)
        Thread {
            val result = runCatching {
                StatusPost.publish(this, kind, body, caption, ids) {
                    val p = Transfers.progress[StatusPost.UPLOAD]
                    runOnUiThread { if (p != null && p != sharing) { sharing = p; showSharing() } }
                }
            }
            runOnUiThread {
                posting = false; sharing = null
                if (isDestroyed || isFinishing) return@runOnUiThread
                refresh()
                result.onFailure { themedMessage("Status not shared", it.message ?: "Check your connection and try again.") }
            }
        }.start()
    }
    private fun showSharing() { stories.findViewWithTag<TextView>("sharing")?.text = sharingLabel() }
    private fun sharingLabel() = "Sending… ${sharing ?: 0}%"

    private fun cachePrefs()=getSharedPreferences("status_cache",MODE_PRIVATE)
    /** The last list this account received, so the row appears at once while the server answers. */
    private fun cached():JSONArray=runCatching {JSONArray(cachePrefs().getString(owner,"[]"))}.getOrDefault(JSONArray())

    private fun refresh() {
        if(!foreground||isDestroyed||isFinishing)return
        val request=++generation;handler.removeCallbacks(expiry)
        feed.removeAllViews();feed.addView(RabtaUi.loading(this,if(posting) "Sharing status…" else "Updating…"))
        // A sleeping free server can take a while to answer the first request; say so instead of spinning silently.
        handler.postDelayed({
            if(request==generation&&foreground) {feed.removeAllViews();feed.addView(RabtaUi.loading(this,"Loading… this can take up to a minute"))}
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
        StatusPost.pruneSeen(this,live.map { it.optString("id") })
        if(live.isEmpty()) feed.addView(RabtaUi.text(this,"No updates yet. Share your first moment, or check back for updates from your contacts.",13f,color=AppTheme.sub(this)).apply {
            setPadding(dp(4),dp(12),dp(4),0)
        })
        handler.removeCallbacks(expiry)
        if(live.isNotEmpty()) handler.postDelayed(expiry,(live.minOf {it.optLong("expires")}-System.currentTimeMillis()).coerceAtLeast(1))
    }

    /** Draws the card row and returns the updates that are still live. */
    private fun renderCards(items:JSONArray):List<JSONObject> {
        lastItems=items
        stories.removeAllViews()
        val now=System.currentTimeMillis()
        val live=(0 until items.length()).map {items.getJSONObject(it)}.filter {it.optLong("expires")>now}
        val seen=StatusPost.seen(this)
        val mine=live.filter {it.optString("owner")==owner}.sortedBy {it.optLong("created")}
        stories.addView(if(mine.isEmpty()) addCard() else myCard(mine))
        // Everyone else: one card each, unseen updates first, newest first.
        val people=live.filter {it.optString("owner")!=owner}.groupBy {it.optString("owner")}.values.map {list->list.sortedBy {it.optLong("created")}}
            .sortedWith(compareBy<List<JSONObject>> {list->list.all {it.optString("id") in seen}}.thenByDescending {it.last().optLong("created")})
        people.take(30).forEachIndexed {i,list->stories.addView(statusCard(list,seen) {view(people.drop(i).flatten(),list.firstOrNull {it.optString("id") !in seen}?:list.first())})}
        return live
    }

    private fun card()=FrameLayout(this).apply {
        background=RabtaUi.shape(this@StatusActivity,AppTheme.surface(this@StatusActivity),20,AppTheme.line(this@StatusActivity));clipToOutline=true;isFocusable=true
        layoutParams=LinearLayout.LayoutParams(dp(112),dp(188)).apply {marginEnd=dp(10)}
    }
    private fun nameLabel(text:String)=RabtaUi.text(this,text,13f,true,android.graphics.Color.WHITE).apply {
        maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END
        setPadding(dp(10),dp(24),dp(8),dp(10))
        background=android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.BOTTOM_TOP,intArrayOf(0xCC000000.toInt(),0x00000000))
    }
    private fun plusBadge(size:Int)=TextView(this).apply {
        UiIcons.set(this,"plus",AppTheme.onPrimary(this@StatusActivity),size*2/3,"Add");gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        background=android.graphics.drawable.GradientDrawable().apply {shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(AppTheme.primary(this@StatusActivity));setStroke(dp(2),AppTheme.surface(this@StatusActivity))}
    }
    private fun addStatus()=startActivityForResult(Intent(this,AddStatusActivity::class.java),ADD)
    private fun sharingOverlay(card:FrameLayout) {
        if(sharing==null)return
        card.addView(RabtaUi.text(this,sharingLabel(),13f,true,android.graphics.Color.WHITE).apply {
            tag="sharing";gravity=Gravity.CENTER;setBackgroundColor(0x99000000.toInt());accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
        },FrameLayout.LayoutParams(-1,-1))
    }

    /** No live update of your own yet: your photo with a + to add one. */
    private fun addCard()=card().apply {
        contentDescription="Add status";setOnClickListener {addStatus()}
        background=RabtaUi.ripple(this@StatusActivity,AppTheme.surface(this@StatusActivity),20)
        val avatarBox=FrameLayout(this@StatusActivity)
        avatarBox.addView(StatusPost.avatar(this@StatusActivity,owner,Session.name,64),FrameLayout.LayoutParams(-1,-1))
        avatarBox.addView(plusBadge(26),FrameLayout.LayoutParams(dp(26),dp(26),Gravity.BOTTOM or Gravity.END))
        addView(avatarBox,FrameLayout.LayoutParams(dp(64),dp(64),Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply {topMargin=dp(40)})
        addView(RabtaUi.text(this@StatusActivity,"Add status",13f,true).apply {
            gravity=Gravity.CENTER;setPadding(dp(6),0,dp(6),dp(12))
        },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        sharingOverlay(this)
    }

    /** Your own updates in their own box: latest preview, ring per update, + to add another, and how many viewed. */
    private fun myCard(mine:List<JSONObject>)=card().apply {
        val latest=mine.last()
        contentDescription="My status, ${mine.size} update${if(mine.size==1) "" else "s"}";tag="status:${latest.optString("id")}"
        setOnClickListener {view(mine,mine.first())}
        setOnLongClickListener {manage(latest);true}
        addView(preview(latest) {view(mine,mine.first())}.apply {setOnLongClickListener {manage(latest);true}},FrameLayout.LayoutParams(-1,-1))
        val ring=StatusRingView(this@StatusActivity).apply {total=mine.size;seen=mine.size}
        ring.addView(StatusPost.avatar(this@StatusActivity,owner,Session.name,36),FrameLayout.LayoutParams(-1,-1))
        val avatarBox=FrameLayout(this@StatusActivity).apply {clipChildren=false}
        avatarBox.addView(ring,FrameLayout.LayoutParams(dp(44),dp(44)))
        avatarBox.addView(plusBadge(20).apply {
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES;contentDescription="Add status";isFocusable=true;setOnClickListener {addStatus()}
        },FrameLayout.LayoutParams(dp(22),dp(22),Gravity.BOTTOM or Gravity.END))
        addView(avatarBox,FrameLayout.LayoutParams(dp(48),dp(48)).apply {leftMargin=dp(6);topMargin=dp(6)})
        val viewers=mine.flatMap {s->s.optJSONArray("viewers")?.let {v->(0 until v.length()).map {v.optString(it)}}.orEmpty()}.toSet().size
        val label=nameLabel("My status").apply {
            setCompoundDrawablesRelative(null,null,null,null)
            text=android.text.SpannableStringBuilder("My status\n").append(TextView(this@StatusActivity).also {UiIcons.set(it,"eye",0xCCFFFFFF.toInt(),13,"Views")}.text).append(" $viewers")
            contentDescription="My status. Viewed by $viewers"
        }
        addView(label,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        sharingOverlay(this)
    }

    private fun preview(status:JSONObject,open:()->Unit):View {
        val kind=status.optString("kind")
        return if(kind=="text") RabtaUi.text(this,status.optString("body"),14f,true,android.graphics.Color.WHITE).apply {
            gravity=Gravity.CENTER;maxLines=6;ellipsize=android.text.TextUtils.TruncateAt.END
            setPadding(dp(10),dp(52),dp(10),dp(40))
            setBackgroundColor(StatusViewerActivity.textColor(status.optString("id")))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener {open()}
        } else StatusMediaPreview(this,status.getString("body"),kind,status.getLong("expires")) {open()}
    }

    private fun statusCard(list:List<JSONObject>,seen:Set<String>,open:()->Unit)=card().apply {
        val latest=list.last()
        val name=latest.optString("name").ifBlank {"Rabta contact"}
        tag="status:${latest.optString("id")}";contentDescription="View $name"
        setOnClickListener {open()}
        addView(preview(latest,open),FrameLayout.LayoutParams(-1,-1))
        val ring=StatusRingView(this@StatusActivity).apply {total=list.size;this.seen=list.count {it.optString("id") in seen}}
        ring.addView(StatusPost.avatar(this@StatusActivity,latest.optString("owner"),name,36),FrameLayout.LayoutParams(-1,-1))
        addView(ring,FrameLayout.LayoutParams(dp(44),dp(44)).apply {leftMargin=dp(6);topMargin=dp(6)})
        addView(nameLabel(name),FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
    }

    private fun manage(status: JSONObject) {
        StatusPost.viewers(this, status, onDelete = {
            FeatureApi.run(this, { FeatureApi.request("/api/status/${status.getString("id")}", "DELETE") }) { refresh() }
        })
    }

    /** Opens the viewer at [start], then carries on through [sequence] like WhatsApp. */
    private fun view(sequence:List<JSONObject>,start:JSONObject) {
        if(start.optLong("expires")<=System.currentTimeMillis()){refresh();return}
        val ids=sequence.filter {it.optLong("expires")>System.currentTimeMillis()}.map {it.getString("id")}
        startActivity(Intent(this,StatusViewerActivity::class.java).putExtra("statusId",start.getString("id")).putStringArrayListExtra("statusIds",ArrayList(ids)))
    }

    private fun themedMessage(title: String, message: String) =
        AlertDialog.Builder(this).setTitle(title).setMessage(message).setPositiveButton("Close", null).show()
}
