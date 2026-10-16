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

class ConversationListActivity : ThemedActivity(), RabtaEvents.Listener {

    private val GREEN get() = AppTheme.accent(this)
    private val GREEN2 get() = AppTheme.ACCENT
    private val WHITE get() = AppTheme.surface(this@ConversationListActivity)
    private val TXT get() = AppTheme.text(this@ConversationListActivity)
    private val SUB get() = AppTheme.sub(this@ConversationListActivity)
    private val DIV get() = AppTheme.line(this@ConversationListActivity)
    private val BG get() = AppTheme.bg(this@ConversationListActivity)

    private lateinit var convList: LinearLayout
    private lateinit var callsList: LinearLayout
    private lateinit var chatsScroll: ScrollView
    private lateinit var callsScroll: ScrollView
    private lateinit var spacesList: LinearLayout
    private lateinit var spacesScroll: ScrollView
    private lateinit var nearbyScroll: ScrollView
    private lateinit var nearbyPanel: NearbyPanel
    private lateinit var bottomNav: LinearLayout
    private lateinit var pageTitle: TextView
    private lateinit var fab: TextView
    private val navItems=mutableListOf<LinearLayout>()
    private val queries=mutableMapOf<Int,String>()
    private var resumed=false
    private var callVersion=0
    private val homeBack=object:androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { switchTab(0) }
    }
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
        connectionTv.setTextColor(if(WsClient.connected) AppTheme.teal(this) else SUB)
    }
    private var activeTab = 0  // Chats, Nearby, Spaces (existing groups), Calls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (!Session.loggedIn) { go(AuthActivity::class.java); return }
        db = Db.get(this)
        activeTab=savedInstanceState?.getInt("destination",0)?:0
        queries[0]=savedInstanceState?.getString("chatQuery").orEmpty()
        queries[2]=savedInstanceState?.getString("spaceQuery").orEmpty()
        buildUi()
        onBackPressedDispatcher.addCallback(this,homeBack)
        askPermissions()
        MessageService.start(this)
        WsClient.connect(Session)
    }

    override fun onResume() {
        super.onResume();if(!::connectionTv.isInitialized)return
        resumed=true;MessageService.start(this);RabtaEvents.add(this)
        handler.removeCallbacks(connectionTick);handler.post(connectionTick)
        when(activeTab) { 1->nearbyPanel.resume();3->loadCalls();else->loadConvs() }
    }
    override fun onPause() {
        resumed=false;if(::nearbyPanel.isInitialized)nearbyPanel.pause()
        RabtaEvents.remove(this);handler.removeCallbacks(connectionTick);handler.removeCallbacks(searchTask)
        super.onPause()
    }
    override fun onDestroy() { RabtaEvents.remove(this);handler.removeCallbacksAndMessages(null);super.onDestroy() }
    override fun onSaveInstanceState(state:Bundle) {
        queries[activeTab]=searchQuery
        state.putInt("destination",activeTab);state.putString("chatQuery",queries[0]);state.putString("spaceQuery",queries[2])
        super.onSaveInstanceState(state)
    }

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
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setBackgroundColor(BG);isFocusableInTouchMode=true
        }
        setContentView(root)
        val header=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(8),dp(12),0)
        }
        header.addView(RabtaBrand.lockup(this,32,TXT,24f),LinearLayout.LayoutParams(0,-2,1f))
        header.addView(RabtaUi.iconButton(this,"status","Status") { startActivity(Intent(this,StatusActivity::class.java)) })
        header.addView(RabtaUi.iconButton(this,"more","More options") {
            RabtaUi.sheet(this,"Rabta",listOf(
                RabtaUi.SheetAction("Status") { startActivity(Intent(this,StatusActivity::class.java)) },
                RabtaUi.SheetAction("Starred messages") { startActivity(Intent(this,StarredActivity::class.java)) },
                RabtaUi.SheetAction("Appearance") { AppTheme.choose(this) },
                RabtaUi.SheetAction("Settings") { startActivity(Intent(this,SettingsActivity::class.java)) }
            ))
        })
        myAvatarView=FrameLayout(this).apply {
            contentDescription="My profile";isFocusable=true;background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT)
            setOnClickListener { startActivity(Intent(this@ConversationListActivity,ProfileActivity::class.java)) }
        }
        myAvatarView.addView(initials(Session.name,dp(36)),FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
        if(Session.profilePic.isNotEmpty()) MediaHelper.base64ToBitmap(Session.profilePic)?.let { bmp ->
            myAvatarView.addView(ImageView(this).apply {
                setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true;background=pill(WHITE)
            },FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
        }
        header.addView(myAvatarView,LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(header)
        connectionTv=RabtaUi.text(this,"",12f,color=SUB).apply {
            minHeight=dp(48);gravity=Gravity.CENTER_VERTICAL;setPadding(dp(20),0,dp(20),0)
            background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT)
            setOnClickListener { ConnectionHelp.show(this@ConversationListActivity) }
            contentDescription="Connection details and retry"
        }
        root.addView(connectionTv);updateConnection()
        pageTitle=RabtaUi.text(this,"Chats",28f,true).apply {
            setPadding(dp(20),dp(4),dp(20),dp(8))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
        }
        root.addView(pageTitle)
        searchField=EditText(this).apply {
            hint=L.t(this@ConversationListActivity,"Search names or messages")
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            textSize=15f;setTextColor(TXT);setHintTextColor(SUB);setSingleLine(true)
            minHeight=dp(52);setPadding(dp(18),dp(12),dp(18),dp(12))
            background=RabtaUi.shape(this@ConversationListActivity,WHITE,16,AppTheme.line(this@ConversationListActivity))
            contentDescription=L.t(this@ConversationListActivity,"Search names or messages")
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                    searchQuery=s?.toString().orEmpty();loadVersion++
                    handler.removeCallbacks(searchTask);handler.postDelayed(searchTask,180)
                }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
            setOnEditorActionListener { _,action,_ ->
                if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard();true } else false
            }
        }
        root.addView(searchField,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(20),0,dp(20),dp(8)) })
        fun listColumn()=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(100))
        }
        convList=listColumn();callsList=listColumn();spacesList=listColumn()
        chatsScroll=ScrollView(this).apply { addView(convList);isVerticalScrollBarEnabled=false }
        callsScroll=ScrollView(this).apply { addView(callsList);isVerticalScrollBarEnabled=false }
        spacesScroll=ScrollView(this).apply { addView(spacesList);isVerticalScrollBarEnabled=false }
        nearbyPanel=NearbyPanel(this)
        nearbyScroll=ScrollView(this).apply {
            addView(nearbyPanel.view.apply { setPadding(dp(20),dp(8),dp(20),dp(20)) });isVerticalScrollBarEnabled=false
        }
        val content=FrameLayout(this)
        listOf(chatsScroll,nearbyScroll,spacesScroll,callsScroll).forEach { content.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        fab=RabtaUi.iconButton(this,"compose","New chat or group") { if(activeTab==2) showCreateGroup() else showFabMenu() }.apply {
            UiIcons.set(this,"compose",Color.WHITE,label="New chat or group")
            background=RabtaUi.ripple(this@ConversationListActivity,AppTheme.ACCENT,20);elevation=dp(3).toFloat()
        }
        content.addView(fab,FrameLayout.LayoutParams(dp(56),dp(56),Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(20),dp(12),dp(20),dp(20)) })
        root.addView(View(this).apply { setBackgroundColor(DIV) },LinearLayout.LayoutParams(-1,dp(1)))
        bottomNav=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;setBackgroundColor(WHITE);setPadding(dp(8),dp(6),dp(8),dp(6))
            contentDescription="Main navigation"
        }
        val icons=listOf("chats","nearby","spaces","phone")
        listOf("Chats","Nearby","Spaces","Calls").forEachIndexed { index,label ->
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=dp(64)
                setPadding(dp(2),dp(8),dp(2),dp(8));isFocusable=true;contentDescription=L.t(this@ConversationListActivity,label)
                setOnClickListener { switchTab(index) }
            }
            item.addView(TextView(this).apply {
                UiIcons.set(this,icons[index],SUB,label=label);gravity=Gravity.CENTER
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(-1,dp(26)))
            item.addView(RabtaUi.text(this,label,12f,true,SUB).apply {
                gravity=Gravity.CENTER;maxLines=2;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0,dp(4),0,0)
            })
            navItems.add(item);bottomNav.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(bottomNav)
        switchTab(activeTab,animate=false)
    }

    private fun hideKeyboard() {
        currentFocus?.clearFocus()
        androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).hide(androidx.core.view.WindowInsetsCompat.Type.ime())
    }

    override fun onKeyboardVisibilityChanged(visible:Boolean) {
        if(::bottomNav.isInitialized) bottomNav.visibility=if(visible) View.GONE else View.VISIBLE
    }

    private fun switchTab(idx:Int,animate:Boolean=true) {
        val destination=idx.coerceIn(0,3)
        if(animate&&::searchField.isInitialized) queries[activeTab]=searchQuery
        activeTab=destination;homeBack.isEnabled=destination!=0
        if(!::bottomNav.isInitialized)return
        hideKeyboard()
        val searchable=destination==0||destination==2
        pageTitle.text=L.t(this,listOf("Chats","Nearby","Spaces","Calls")[destination])
        searchField.visibility=if(searchable) View.VISIBLE else View.GONE
        searchField.hint=L.t(this,if(destination==2) "Search your groups" else "Search names or messages")
        searchField.contentDescription=searchField.hint
        searchQuery=queries[destination].orEmpty();searchField.setText(searchQuery)
        val pages=listOf(chatsScroll,nearbyScroll,spacesScroll,callsScroll)
        pages.forEachIndexed { index,view -> view.visibility=if(index==destination) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { index,item ->
            val selected=index==destination;item.isSelected=selected
            item.background=RabtaUi.ripple(this,if(selected) AppTheme.sent(this) else Color.TRANSPARENT,16)
            val color=if(selected) AppTheme.accent(this) else SUB
            UiIcons.set(item.getChildAt(0) as TextView,listOf("chats","nearby","spaces","phone")[index],color,label=listOf("Chats","Nearby","Spaces","Calls")[index])
            (item.getChildAt(1) as TextView).setTextColor(color)
        }
        fab.visibility=if(searchable) View.VISIBLE else View.GONE
        fab.contentDescription=if(destination==2) L.t(this,"New group") else "New chat or group"
        if(destination==1 && resumed) nearbyPanel.resume() else nearbyPanel.pause()
        if(destination==3) loadCalls() else if(searchable) loadConvs()
        if(animate) RabtaUi.enter(pages[destination])
    }

    // ── Conversations ──
    private fun loadConvs() {
        if(activeTab!=0&&activeTab!=2)return
        val query=searchQuery;val destination=activeTab;val version=++loadVersion
        val target=if(destination==2) spacesList else convList
        if(target.childCount==0)target.addView(RabtaUi.loading(this,"Loading conversations…"))
        Thread {
            val result=runCatching { db.searchConversations(query).filter { destination!=2||it.isGroup } }
            handler.post {
                if(isDestroyed||isFinishing||version!=loadVersion||destination!=activeTab)return@post
                target.removeAllViews()
                result.onFailure {
                    target.addView(RabtaUi.empty(this,"chats","Could not load conversations","Your saved chats are still on this phone.","Retry") { loadConvs() })
                }.onSuccess { list ->
                    if(list.isEmpty()) {
                        val spaces=destination==2
                        target.addView(if(query.isNotBlank()) RabtaUi.empty(this,"search","No matches","Try a different name or message.","Clear search") { searchField.setText("") }
                        else RabtaUi.empty(this,if(spaces) "spaces" else "chats",if(spaces) "A space for your people" else "Start a conversation",
                            if(spaces) "Your existing groups live here. Create a group to bring people together." else "Find someone by username or Rabta ID, or add a nearby contact.",
                            if(spaces) "New group" else "New chat") { if(spaces) showCreateGroup() else showNewChat() })
                    } else list.forEach { target.addView(convRow(it)) }
                }
            }
        }.start()
    }

    private fun convRow(c: Db.Conversation): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background=RabtaUi.ripple(this@ConversationListActivity,WHITE,18);setPadding(dp(14),dp(16),dp(14),dp(16))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }
            isClickable = true; isFocusable = true
            setOnClickListener { open(c.peerId, c.peerName, c.isGroup) }
        }
        // Avatar: group icon or profile pic
        val av = FrameLayout(this)
        if (c.isGroup) av.addView(TextView(this).apply { UiIcons.set(this,"spaces",Color.WHITE,label="Group"); textSize = 20f; gravity = Gravity.CENTER; background = pill(0xFF607D8B.toInt()) }, FrameLayout.LayoutParams(dp(50),dp(50)))
        else {
            av.addView(initials(c.peerName, dp(50)), FrameLayout.LayoutParams(dp(50),dp(50)))
            ProfileHelper.loadIfNeeded(c.peerId, Session.serverUrl, Session.token) { bmp ->
                if (bmp != null) handler.post {
                    val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline=true; background = pill(0xFF607D8B.toInt()) }
                    av.addView(iv, FrameLayout.LayoutParams(dp(50),dp(50)))
                }
            }
        }
        row.addView(av)
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14),0,dp(8),0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = c.peerName; maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END; textSize = 16f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.lastTime > 0) top.addView(TextView(this).apply { text = fmtTime(c.lastTime); textSize = 12f; setTextColor(if (c.unread > 0) AppTheme.accent(this@ConversationListActivity) else SUB) })
        mid.addView(top)
        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,0) }
        bot.addView(TextView(this).apply { text = c.lastMsg.ifEmpty { "Tap to chat" }; textSize = 14f; setTextColor(SUB); maxLines = 1 }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.unread > 0) bot.addView(TextView(this).apply {
            text = if (c.unread > 99) "99+" else c.unread.toString()
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
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
        val version=++callVersion
        if(callsList.childCount==0)callsList.addView(RabtaUi.loading(this,"Loading calls…"))
        Thread {
            val result=runCatching { db.calls() }
            handler.post {
                if(isDestroyed||isFinishing||version!=callVersion)return@post
                callsList.removeAllViews()
                result.onFailure {
                    callsList.addView(RabtaUi.empty(this,"phone","Could not load calls","Try loading your call history again.","Retry") { loadCalls() })
                }.onSuccess { calls ->
                    if(calls.isEmpty()) callsList.addView(RabtaUi.empty(this,"phone","Keep the conversation going","Your voice and video call history appears here.","New chat") { showNewChat() })
                    else calls.forEach { callsList.addView(callRow(it)) }
                }
            }
        }.start()
    }

    private fun callRow(c: Db.CallRecord): View {
        val missed = c.type == "missed"
        val nameColor = if (missed) AppTheme.error(this) else TXT
        val arrowColor = if (missed) AppTheme.error(this) else AppTheme.accent(this)
        val arrowIcon = if (c.type == "outgoing") "↗" else "↙"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background=RabtaUi.ripple(this@ConversationListActivity,WHITE,18);setPadding(dp(14),dp(16),dp(14),dp(16))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }
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
            addView(TextView(this@ConversationListActivity).apply { text = c.type.replaceFirstChar { it.uppercase() }+" · "+fmtTime(c.startedAt)+durStr; textSize = 13f; setTextColor(SUB) })
        })
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        // Right: time + call-back icon
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        right.addView(TextView(this).apply {
            UiIcons.set(this, if (c.type == "video") "video" else "phone", GREEN, label="Open call conversation")
            textSize = 20f; setTextColor(GREEN); gravity = Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply { setColor(AppTheme.sent(this@ConversationListActivity)); cornerRadius = dp(24).toFloat() }
            setPadding(dp(12),dp(10),dp(12),dp(10))
        })
        row.addView(right)
        return row
    }

    // ── Helpers ──
    private fun initials(name: String, size: Int): TextView {
        val cols = listOf(0xFF6248A5,0xFF325E71,0xFF3D577E,0xFF286F68)
        return TextView(this).apply {
            text = name.filter { it.isLetterOrDigit() }.take(1).uppercase().ifEmpty { "?" }
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size*0.38f); includeFontPadding=false; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
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
        RabtaUi.sheet(this,"Start a conversation",listOf(
            RabtaUi.SheetAction("New chat","Find a username or Rabta ID") { showNewChat() },
            RabtaUi.SheetAction("New group","Bring your contacts together") { showCreateGroup() }
        ))
    }

    private fun showNewChat() {
        val dl = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(WHITE); setPadding(dp(24),dp(20),dp(24),dp(16)) }
        dl.addView(TextView(this).apply { text = L.t(this@ConversationListActivity,"New chat"); textSize = 20f; typeface = Typeface.DEFAULT_BOLD; setTextColor(TXT); setPadding(0,0,0,dp(16)) })
        val inp = EditText(this).apply { hint = "@username or Rabta ID"; textSize = 16f; setTextColor(TXT); setHintTextColor(SUB); setSingleLine(true); background = null; setPadding(dp(12),dp(12),dp(12),dp(12)); inputType = InputType.TYPE_CLASS_TEXT }
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
            if(Regex("[0-9a-f]{12}").matches(un)) { dlg.dismiss(); open(un.uppercase(),un.uppercase()); return@textBtn }
            status.text = "Dhoondh raha hai..."; status.setTextColor(GREEN)
            WsClient.findUser(Session.serverUrl, un, Session.token) { ok, msg, data ->
                if (ok && data != null) {
                    try { dlg.dismiss() } catch (_: Exception) {}
                    val pid = data.optString("id"); val pn = data.optString("name").ifEmpty { data.optString("username") }
                    if (pid.isNotEmpty()) open(pid, pn)
                } else { status.text = msg; status.setTextColor(AppTheme.error(this)) }
            }
        })
        dl.addView(btns); dlg.show(); dlg.window?.setBackgroundDrawable(RabtaUi.shape(this,WHITE,24))
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
        col.addView(btns); dlg.show(); dlg.window?.setBackgroundDrawable(RabtaUi.shape(this,WHITE,24))
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
        minHeight=dp(48);gravity=Gravity.CENTER;isFocusable=true;background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT);setPadding(dp(16),dp(10),dp(16),dp(10)); isClickable = true; setOnClickListener { onClick() }
    }

    private fun askPermissions() {
        val sdk = android.os.Build.VERSION.SDK_INT
        val want = mutableListOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        if (sdk >= 31) { want += android.Manifest.permission.BLUETOOTH_SCAN; want += android.Manifest.permission.BLUETOOTH_ADVERTISE; want += android.Manifest.permission.BLUETOOTH_CONNECT }
        if (sdk >= 33) { want += android.Manifest.permission.NEARBY_WIFI_DEVICES; want += android.Manifest.permission.POST_NOTIFICATIONS }
        val miss = want.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (miss.isNotEmpty()) requestPermissions(miss.toTypedArray(), 7)
    }

    override fun onRequestPermissionsResult(r: Int, p: Array<out String>, g: IntArray) { super.onRequestPermissionsResult(r,p,g); if (r==7||r==NearbySetup.PERMISSIONS) { MessageService.start(this); if(::nearbyPanel.isInitialized)nearbyPanel.refresh() } }

    override fun onActivityResult(req:Int,res:Int,data:Intent?) {
        super.onActivityResult(req,res,data)
        if(::nearbyPanel.isInitialized) nearbyPanel.handleResult(req,res,data)
    }

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
