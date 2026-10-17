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
    private lateinit var feed: LinearLayout
    private var posting = false
    private val handler=android.os.Handler(android.os.Looper.getMainLooper())
    private lateinit var stories:LinearLayout
    private var generation=0
    private var foreground=false
    private var owner=""
    private var limit=30
    private val expiry=Runnable {refresh()}


    override fun onCreate(state: Bundle?) {
        super.onCreate(state);owner=Session.id
        val col=FeatureUi.page(this,"Status")
        val intro=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(0,0,0,RabtaUi.dp(this@StatusActivity,16))}
        intro.addView(RabtaUi.text(this,"Share a moment",18f,true))
        intro.addView(RabtaUi.text(this,"24 hours. Only the people you choose.",13f,color=AppTheme.sub(this)))
        val actions=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL;setPadding(0,RabtaUi.dp(this@StatusActivity,16),0,0)}
        listOf(Triple("Text status","text",{composeText()}),Triple("Photo","image",{pick("image/*",31)}),Triple("Video","video",{pick("video/*",32)})).forEachIndexed {index,item ->
            actions.addView(RabtaUi.button(this,item.first,index==0,item.third).apply {
                textSize=14f;gravity=Gravity.CENTER;setPadding(RabtaUi.dp(this@StatusActivity,4),RabtaUi.dp(this@StatusActivity,12),RabtaUi.dp(this@StatusActivity,4),RabtaUi.dp(this@StatusActivity,12))
            },LinearLayout.LayoutParams(0,-2,1f).apply {if(index<2)marginEnd=RabtaUi.dp(this@StatusActivity,8)})
        }
        intro.addView(actions);col.addView(intro)
        stories=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        col.addView(HorizontalScrollView(this).apply {addView(stories);isHorizontalScrollBarEnabled=false})
        val heading=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        heading.addView(RabtaUi.section(this,"Recent updates"),LinearLayout.LayoutParams(0,-2,1f))
        heading.addView(RabtaUi.iconButton(this,"restore","Refresh status updates") {refresh()});col.addView(heading)
        feed=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};col.addView(feed)
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
        if (resultCode != RESULT_OK || requestCode !in 31..32) return
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

    private fun refresh() {
        if(!foreground||isDestroyed||isFinishing)return
        val request=++generation;handler.removeCallbacks(expiry)
        feed.removeAllViews();stories.removeAllViews();feed.addView(RabtaUi.loading(this,"Loading updates…"))
        Thread {
            val result=runCatching {FeatureApi.request("/api/status").getJSONArray("statuses")}
            runOnUiThread {
                if(!foreground||isDestroyed||isFinishing||request!=generation||owner!=Session.id)return@runOnUiThread
                result.onSuccess(::render).onFailure {
                    feed.removeAllViews()
                    feed.addView(RabtaUi.empty(this,"status","Could not load updates",it.message?:"Check your connection and try again.","Retry") {refresh()})
                }
            }
        }.start()
    }
    private fun render(items:JSONArray) {
        feed.removeAllViews();stories.removeAllViews()
        val now=System.currentTimeMillis()
        val updates=(0 until items.length()).map {items.getJSONObject(it)}.filter {it.optLong("expires")>now}
        if(updates.isEmpty()) {
            feed.addView(RabtaUi.empty(this,"status","No updates yet","Share your first moment, or come back for updates from your contacts."));return
        }
        updates.distinctBy {it.optString("owner")}.take(20).forEach {status ->
            val own=status.optString("owner")==owner
            val name=if(own)"Your status" else  status.optString("name").ifBlank {"Rabta contact"}
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=RabtaUi.dp(this@StatusActivity,88)
                setPadding(RabtaUi.dp(this@StatusActivity,4),RabtaUi.dp(this@StatusActivity,4),RabtaUi.dp(this@StatusActivity,4),RabtaUi.dp(this@StatusActivity,4))
                isFocusable=true;contentDescription="View $name";setOnClickListener {view(status)}
            }
            item.addView(RabtaUi.text(this,name.take(1).uppercase(),18f,true,AppTheme.accent(this)).apply {
                gravity=Gravity.CENTER;background=RabtaUi.shape(this@StatusActivity,AppTheme.surface(this@StatusActivity),999,AppTheme.accent(this@StatusActivity))
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(RabtaUi.dp(this,48),RabtaUi.dp(this,48)))
            item.addView(RabtaUi.text(this,name,12f,color=AppTheme.sub(this)).apply {gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,RabtaUi.dp(this@StatusActivity,8),0,0)})
            stories.addView(item,LinearLayout.LayoutParams(RabtaUi.dp(this,80),-2))
        }
        updates.take(limit).forEach {status ->
            val own=status.optString("owner")==owner
            val name=if(own)"Your status" else  status.optString("name").ifBlank {"Rabta contact"}
            val kind=status.optString("kind")
            val card=RabtaUi.card(this)
            card.tag="status:${status.optString("id")}";card.isFocusable=true;card.setOnClickListener {view(status)}
            card.background=RabtaUi.ripple(this,AppTheme.surface(this),20)
            val head=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
            head.addView(RabtaUi.text(this,name.take(1).uppercase(),16f,true,AppTheme.accent(this)).apply {
                gravity=Gravity.CENTER;background=RabtaUi.shape(this@StatusActivity,AppTheme.sent(this@StatusActivity),999)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(RabtaUi.dp(this,40),RabtaUi.dp(this,40)))
            val labels=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(RabtaUi.dp(this@StatusActivity,12),0,0,0)}
            labels.addView(RabtaUi.text(this,name,15f,true))
            val created=status.optLong("created")
            labels.addView(RabtaUi.text(this,android.text.format.DateUtils.getRelativeTimeSpanString(created,now,60000).toString(),12f,color=AppTheme.sub(this)))
            head.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
            if(own)head.addView(RabtaUi.iconButton(this,"more","Manage your status") {manage(status,status.optJSONArray("viewers")?:JSONArray())})
            card.addView(head)
            if(kind=="text")card.addView(RabtaUi.text(this,status.optString("body"),18f,true).apply {
                maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,RabtaUi.dp(this@StatusActivity,16),0,RabtaUi.dp(this@StatusActivity,8))
            })else card.addView(StatusMediaPreview(this,status.getString("body"),kind,status.getLong("expires")){view(status)},LinearLayout.LayoutParams(-1,RabtaUi.dp(this,160)).apply {topMargin=RabtaUi.dp(this@StatusActivity,16)})
            val remaining=((status.optLong("expires")-now)/60000).coerceAtLeast(1)
            card.addView(RabtaUi.text(this,"${if(own)(status.optJSONArray("viewers")?.length()?:0).toString()+" views · " else ""}Expires in ${if(remaining>=60)"${remaining/60}h ${remaining%60}m" else  "${remaining}m"}",12f,color=AppTheme.sub(this)).apply {setPadding(0,RabtaUi.dp(this@StatusActivity,8),0,0)})
            feed.addView(card)
        }
        if(updates.size>limit)feed.addView(RabtaUi.button(this,"Show more updates") {limit+=30;render(items)})
        handler.removeCallbacks(expiry)
        handler.postDelayed(expiry,(updates.minOf {it.optLong("expires")}-System.currentTimeMillis()).coerceAtLeast(1))
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
