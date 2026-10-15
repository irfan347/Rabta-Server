package com.irfan.rabta

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class ConversationListActivity : Activity(), RabtaEvents.Listener {

    private val GREEN  = 0xFF128C7E.toInt()
    private val GREEN2 = 0xFF25D366.toInt()
    private val WHITE  = Color.WHITE
    private val TXT    = 0xFF1A1A1A.toInt()
    private val SUB    = 0xFF888888.toInt()
    private val DIV    = 0xFFEEEEEE.toInt()
    private val BG     = 0xFFF0F0F0.toInt()

    private lateinit var convList: LinearLayout
    private lateinit var callsList: LinearLayout
    private lateinit var chatsScroll: ScrollView
    private lateinit var callsScroll: ScrollView
    private lateinit var tabChats: TextView
    private lateinit var tabCalls: TextView
    private lateinit var onlineDot: View
    private lateinit var myAvatarView: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private lateinit var connectionTv: TextView
    private lateinit var searchField: EditText
    private var searchQuery = ""
    private var loadVersion = 0
    private val searchTask = Runnable { loadConvs() }
    private val connectionTick = object : Runnable {
        override fun run() { updateConnection(); handler.postDelayed(this, 3000) }
    }
    private fun updateConnection() {
        connectionTv.text = ConnectionStatus.text(this)
        onlineDot.background = pill(if (WsClient.connected) GREEN2 else 0x55FFFFFF)
    }
    private var activeTab = 0  // 0=Chats, 1=Calls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (!Session.loggedIn) { go(AuthActivity::class.java); return }
        db = Db.get(this)
        buildUi()
        askPermissions()
        MessageService.start(this)
        WsClient.connect(Session)
    }

    override fun onResume() { super.onResume(); if (!::connectionTv.isInitialized) return; RabtaEvents.add(this); loadConvs(); handler.post(connectionTick); if (activeTab == 1) loadCalls() }
    override fun onPause() { super.onPause(); RabtaEvents.remove(this); handler.removeCallbacks(connectionTick); handler.removeCallbacks(searchTask) }
    override fun onDestroy() { RabtaEvents.remove(this); handler.removeCallbacksAndMessages(null); super.onDestroy() }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun pill(c: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(999).toFloat() }
    private fun rect(c: Int, r: Int) = GradientDrawable().apply { setColor(c); cornerRadius = dp(r).toFloat() }
    private fun go(cls: Class<*>) { startActivity(Intent(this, cls)); finish() }

    private fun open(peerId: String, peerName: String, isGroup: Boolean = false) {
        startActivity(Intent(this, ChatActivity::class.java)
            .putExtra("peerId", peerId).putExtra("peerName", peerName).putExtra("isGroup", isGroup)
            .putExtra("searchQuery",if(peerName.contains(searchQuery,true)) "" else searchQuery))
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        setContentView(root)

        // ── Header ──
        val hdr = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(GREEN); setPadding(dp(14),dp(10),dp(14),dp(10)) }
        hdr.addView(TextView(this).apply { text = "Rabta"; textSize = 22f; typeface = Typeface.DEFAULT_BOLD; setTextColor(WHITE) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        onlineDot = View(this).apply { background = pill(0x55FFFFFF) }
        hdr.addView(onlineDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
        // My avatar (opens Profile)
        myAvatarView = FrameLayout(this).apply { isClickable = true; setOnClickListener { startActivity(Intent(this@ConversationListActivity, ProfileActivity::class.java)) } }
        val myInitials = initials(Session.name, dp(36))
        myAvatarView.addView(myInitials, FrameLayout.LayoutParams(dp(36), dp(36)))
        // Show own profile pic if set
        if (Session.profilePic.isNotEmpty()) {
            MediaHelper.base64ToBitmap(Session.profilePic)?.let { bmp ->
                val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; background = pill(0xFF607D8B.toInt()) }
                myAvatarView.addView(iv, FrameLayout.LayoutParams(dp(36), dp(36)))
            }
        }
        hdr.addView(myAvatarView)
        root.addView(hdr)
        connectionTv = TextView(this).apply { textSize=12f; setTextColor(WHITE); setBackgroundColor(GREEN); setPadding(dp(14),0,dp(14),dp(8)) }
        root.addView(connectionTv)
        updateConnection()

        // ── Tabs ──
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(GREEN) }
        tabChats = tab("CHATS", true)  { switchTab(0) }
        tabCalls = tab("CALLS", false) { switchTab(1) }
        tabBar.addView(tabChats, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabBar.addView(tabCalls, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabBar)
        searchField = EditText(this).apply {
            hint="Search names or messages"; textSize=15f; setTextColor(TXT); setHintTextColor(SUB)
            setSingleLine(true); setPadding(dp(16),dp(12),dp(16),dp(12)); background=rect(WHITE,24)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery=s?.toString().orEmpty(); loadVersion++
                    handler.removeCallbacks(searchTask); handler.postDelayed(searchTask,200)
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        root.addView(searchField,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(12),dp(10),dp(12),dp(10)) })

        // ── Chats content ──
        chatsScroll = ScrollView(this)
        convList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        chatsScroll.addView(convList)

        // ── Calls content ──
        callsScroll = ScrollView(this).apply { visibility = View.GONE }
        callsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        callsScroll.addView(callsList)

        val content = FrameLayout(this)
        content.addView(chatsScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        content.addView(callsScroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        root.addView(content, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ── FAB ──
        val fabFrame = FrameLayout(this).apply { minimumHeight = dp(1) }
        val fab = TextView(this).apply {
            UiIcons.set(this,"compose",label="New chat or group"); textSize = 22f; setTextColor(WHITE); gravity = Gravity.CENTER
            background = pill(GREEN2); elevation = dp(6).toFloat()
            isClickable = true; setOnClickListener { showFabMenu() }
        }
        fabFrame.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56)).apply {
            gravity = Gravity.BOTTOM or Gravity.END; bottomMargin = dp(20); marginEnd = dp(20)
        })
        root.addView(fabFrame)
    }

    private fun tab(label: String, selected: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; typeface = Typeface.DEFAULT_BOLD; setTextColor(WHITE)
        gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(10))
        if (selected) setCompoundDrawablesWithIntrinsicBounds(0, 0, 0, 0).also { background = rect(0x33FFFFFF, 0) }
        setOnClickListener { onClick() }
    }

    private fun switchTab(idx: Int) {
        activeTab = idx
        searchField.visibility = if (idx == 0) View.VISIBLE else View.GONE
        chatsScroll.visibility = if (idx == 0) View.VISIBLE else View.GONE
        callsScroll.visibility = if (idx == 1) View.VISIBLE else View.GONE
        tabChats.background = if (idx == 0) GradientDrawable().apply { setColor(0x33FFFFFF); } else null
        tabCalls.background = if (idx == 1) GradientDrawable().apply { setColor(0x33FFFFFF); } else null
        if (idx == 1) loadCalls()
    }

    // ── Conversations ──
    private fun loadConvs() {
        val query = searchQuery
        val version = ++loadVersion
        Thread {
            val list = try { db.searchConversations(query) } catch (_: Exception) { emptyList() }
            handler.post {
                if (isDestroyed || version != loadVersion) return@post
                convList.removeAllViews()
                if (list.isEmpty()) {
                    if(query.isBlank()) emptyState() else convList.addView(TextView(this).apply { text="Koi result nahi mila"; gravity=Gravity.CENTER; setPadding(0,dp(40),0,0); setTextColor(SUB) })
                    return@post
                }
                list.forEachIndexed { i, c ->
                    convList.addView(convRow(c))
                    if (i < list.size - 1) convList.addView(divider())
                }
            }
        }.start()
    }

    private fun emptyState() {
        convList.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(40),dp(80),dp(40),0)
            addView(TextView(this@ConversationListActivity).apply { text = "💬"; textSize = 56f; gravity = Gravity.CENTER })
            addView(TextView(this@ConversationListActivity).apply { text = "Koi chat nahi abhi tak"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT); gravity = Gravity.CENTER; setPadding(0,dp(16),0,dp(8)) })
            addView(TextView(this@ConversationListActivity).apply { text = "✎ dabayein aur kisi ko dhoondhen"; textSize = 14f; setTextColor(SUB); gravity = Gravity.CENTER })
        })
    }

    private fun convRow(c: Db.Conversation): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WHITE); setPadding(dp(14),dp(12),dp(14),dp(12))
            isClickable = true; isFocusable = true
            setOnClickListener { open(c.peerId, c.peerName, c.isGroup) }
        }
        // Avatar: group icon or profile pic
        val av = FrameLayout(this)
        if (c.isGroup) av.addView(TextView(this).apply { text = "👥"; textSize = 20f; gravity = Gravity.CENTER; background = pill(0xFF607D8B.toInt()) }, FrameLayout.LayoutParams(dp(50),dp(50)))
        else {
            av.addView(initials(c.peerName, dp(50)), FrameLayout.LayoutParams(dp(50),dp(50)))
            ProfileHelper.loadIfNeeded(c.peerId, Session.serverUrl, Session.token) { bmp ->
                if (bmp != null) handler.post {
                    val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; background = pill(0xFF607D8B.toInt()) }
                    av.addView(iv, FrameLayout.LayoutParams(dp(50),dp(50)))
                }
            }
        }
        row.addView(av)
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14),0,dp(8),0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = c.peerName; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.lastTime > 0) top.addView(TextView(this).apply { text = fmtTime(c.lastTime); textSize = 12f; setTextColor(if (c.unread > 0) GREEN else SUB) })
        mid.addView(top)
        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,0) }
        bot.addView(TextView(this).apply { text = c.lastMsg.ifEmpty { "Tap to chat" }; textSize = 14f; setTextColor(SUB); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.unread > 0) bot.addView(TextView(this).apply {
            text = if (c.unread > 99) "99+" else c.unread.toString()
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(WHITE); gravity = Gravity.CENTER
            background = pill(GREEN2); setPadding(dp(7),dp(2),dp(7),dp(2))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        mid.addView(bot)
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun divider() = View(this).apply { setBackgroundColor(DIV) }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(78) }
    }

    // ── Calls ──
    private fun loadCalls() {
        Thread {
            val calls = try { db.calls() } catch (_: Exception) { emptyList() }
            handler.post {
                callsList.removeAllViews()
                if (calls.isEmpty()) {
                    callsList.addView(LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(40),dp(80),dp(40),0)
                        addView(TextView(this@ConversationListActivity).apply { text = "📞"; textSize = 56f; gravity = Gravity.CENTER })
                        addView(TextView(this@ConversationListActivity).apply { text = "Koi call nahi abhi tak"; textSize = 18f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT); gravity = Gravity.CENTER; setPadding(0,dp(16),0,0) })
                    })
                    return@post
                }
                calls.forEachIndexed { i, c ->
                    callsList.addView(callRow(c))
                    if (i < calls.size - 1) callsList.addView(divider())
                }
            }
        }.start()
    }

    private fun callRow(c: Db.CallRecord): View {
        val missed = c.type == "missed"
        val nameColor = if (missed) 0xFFD32F2F.toInt() else TXT
        val arrowColor = if (missed) 0xFFD32F2F.toInt() else GREEN
        val arrowIcon = if (c.type == "outgoing") "↗" else "↙"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(WHITE); setPadding(dp(14),dp(14),dp(14),dp(14))
            isClickable = true; isFocusable = true
            setOnClickListener { open(c.peerId, c.peerName) }
        }
        // Avatar
        val av = FrameLayout(this)
        av.addView(initials(c.peerName, dp(52)), FrameLayout.LayoutParams(dp(52),dp(52)))
        row.addView(av)
        // Name + call info
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14),0,0,0) }
        mid.addView(TextView(this).apply { text = c.peerName; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(nameColor); maxLines = 1 })
        val durStr = if (c.duration > 0) " · ${c.duration/60}:${(c.duration%60).toString().padStart(2,'0')}" else ""
        mid.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(TextView(this@ConversationListActivity).apply { text = arrowIcon; textSize = 14f; setTextColor(arrowColor); setPadding(0,0,dp(4),0) })
            addView(TextView(this@ConversationListActivity).apply { text = fmtTime(c.startedAt)+durStr; textSize = 13f; setTextColor(SUB) })
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Right: time + call-back icon
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        right.addView(TextView(this).apply {
            UiIcons.set(this, if (c.type == "video") "video" else "phone", GREEN, label="Open call conversation")
            textSize = 20f; setTextColor(GREEN); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x11128C7E); cornerRadius = dp(24).toFloat() }
            setPadding(dp(12),dp(10),dp(12),dp(10))
        })
        row.addView(right)
        return row
    }

    // ── Helpers ──
    private fun initials(name: String, size: Int): TextView {
        val cols = listOf(0xFFE53935,0xFF9C27B0,0xFF3F51B5,0xFF009688,0xFF43A047,0xFFFF5722,0xFF607D8B)
        return TextView(this).apply {
            text = name.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }
            textSize = size*0.38f; typeface = Typeface.DEFAULT_BOLD; setTextColor(WHITE); gravity = Gravity.CENTER
            background = pill(cols[(name.hashCode() and 0x7FFFFFFF)%cols.size].toInt())
        }
    }

    private fun fmtTime(t: Long): String {
        val now = System.currentTimeMillis()
        return if (now - t < 86400000) java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t))
        else java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault()).format(java.util.Date(t))
    }

    // ── FAB menu ──
    private fun showFabMenu() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Start a conversation")
            .setItems(arrayOf("New chat", "New group")) { _, w -> if (w==0) showNewChat() else showCreateGroup() }
            .create().show()
    }

    private fun showNewChat() {
        val dl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WHITE); setPadding(dp(24),dp(20),dp(24),dp(16)) }
        dl.addView(TextView(this).apply { text = "New chat"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT); setPadding(0,0,0,dp(16)) })
        val inp = EditText(this).apply { hint = "@username"; textSize = 16f; setTextColor(TXT); setHintTextColor(SUB); setSingleLine(true); background = null; setPadding(dp(12),dp(12),dp(12),dp(12)); inputType = InputType.TYPE_CLASS_TEXT }
        dl.addView(inp, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) })
        dl.addView(View(this).apply { setBackgroundColor(GREEN) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        val status = TextView(this).apply { textSize = 13f; setTextColor(SUB); setPadding(dp(12),dp(8),0,0) }
        dl.addView(status)
        val dlg = android.app.AlertDialog.Builder(this).setView(dl).create()
        dlg.window?.setBackgroundDrawable(rect(WHITE, 16))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0,dp(16),0,0) }
        btns.addView(textBtn("Cancel", SUB) { dlg.dismiss() })
        btns.addView(textBtn("Find", GREEN) {
            val un = inp.text.toString().trim().lowercase(); if (un.isEmpty()) return@textBtn
            status.text = "Dhoondh raha hai..."; status.setTextColor(GREEN)
            WsClient.findUser(Session.serverUrl, un, Session.token) { ok, msg, data ->
                if (ok && data != null) {
                    try { dlg.dismiss() } catch (_: Exception) {}
                    val pid = data.optString("id"); val pn = data.optString("name").ifEmpty { data.optString("username") }
                    if (pid.isNotEmpty()) open(pid, pn)
                } else { status.text = msg; status.setTextColor(0xFFD32F2F.toInt()) }
            }
        })
        dl.addView(btns); dlg.show()
    }

    private fun showCreateGroup() {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WHITE); setPadding(dp(24),dp(20),dp(24),dp(16)) }
        col.addView(TextView(this).apply { text = "Create group"; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT); setPadding(0,0,0,dp(12)) })
        val nameF = EditText(this).apply { hint = "Group name"; textSize = 16f; setTextColor(TXT); setHintTextColor(SUB); setSingleLine(true); background = null; setPadding(dp(10),dp(10),dp(10),dp(10)); inputType = InputType.TYPE_CLASS_TEXT }
        col.addView(nameF, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(2) })
        col.addView(View(this).apply { setBackgroundColor(GREEN) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)))
        val membF = EditText(this).apply { hint = "Members (usernames, comma se)"; textSize = 15f; setTextColor(TXT); setHintTextColor(SUB); setSingleLine(true); background = null; setPadding(dp(10),dp(14),dp(10),dp(10)); inputType = InputType.TYPE_CLASS_TEXT }
        col.addView(membF)
        val statusTv = TextView(this).apply { textSize = 13f; setTextColor(SUB) }
        col.addView(statusTv)
        val dlg = android.app.AlertDialog.Builder(this).setView(col).create()
        dlg.window?.setBackgroundDrawable(rect(WHITE, 16))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0,dp(12),0,0) }
        btns.addView(textBtn("Cancel", SUB) { dlg.dismiss() })
        btns.addView(textBtn("Create", GREEN) {
            val name = nameF.text.toString().trim(); if (name.isEmpty()) { statusTv.text = "Group ka naam likhein"; return@textBtn }
            val uns = membF.text.toString().split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            statusTv.text = "Ban raha hai..."; val ids = mutableListOf(Session.id); var rem = uns.size
            if (rem == 0) { doCreateGroup(dlg, name, ids); return@textBtn }
            uns.forEach { un -> WsClient.findUser(Session.serverUrl, un, Session.token) { ok, _, d -> if (ok && d != null) ids.add(d.optString("id")); if (--rem == 0) doCreateGroup(dlg, name, ids) } }
        })
        col.addView(btns); dlg.show()
    }

    private fun doCreateGroup(dlg: android.app.AlertDialog, name: String, members: List<String>) {
        WsClient.createGroup(Session.serverUrl, Session.token, name, members) { ok, _, data ->
            if (ok && data != null) {
                val gId = data.optString("id"); val ml = mutableListOf<String>()
                val arr = try { data.optJSONArray("members") } catch (_: Exception) { null }
                if (arr != null) for (i in 0 until arr.length()) ml.add(arr.getString(i))
                Thread { db.saveGroup(gId, name, ml); db.upsertConversation(gId, name, "New group!", System.currentTimeMillis(), false, true) }.start()
                try { dlg.dismiss() } catch (_: Exception) {}
                handler.post { open(gId, name, true) }
            }
        }
    }

    private fun textBtn(text: String, color: Int, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(color)
        setPadding(dp(16),dp(10),dp(16),dp(10)); isClickable = true; setOnClickListener { onClick() }
    }

    private fun askPermissions() {
        val sdk = android.os.Build.VERSION.SDK_INT
        val want = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (sdk >= 31) { want += android.Manifest.permission.BLUETOOTH_SCAN; want += android.Manifest.permission.BLUETOOTH_ADVERTISE; want += android.Manifest.permission.BLUETOOTH_CONNECT }
        if (sdk >= 33) { want += android.Manifest.permission.NEARBY_WIFI_DEVICES; want += android.Manifest.permission.POST_NOTIFICATIONS }
        val miss = want.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) { super.onRequestPermissionsResult(r,p,g); if (r==7) MessageService.start(this) }

    // RabtaEvents
    override fun onMessage(fromId: String, fromName: String, msgId: String, body: String, t: Long, msgType: String, groupId: String) { handler.postDelayed({ loadConvs() }, 300) }
    override fun onGroupCreated(groupId: String, groupName: String, members: List<String>) {
        Thread { try { db.saveGroup(groupId, groupName, members); db.upsertConversation(groupId, groupName, "New group!", System.currentTimeMillis(), true, true) } catch (_: Exception) {} }.start()
        handler.postDelayed({ loadConvs() }, 400)
    }
    override fun onHistoryChanged(convId: String) { loadConvs() }
    override fun onConnected() { updateConnection() }
    override fun onDisconnected() { updateConnection() }
}
