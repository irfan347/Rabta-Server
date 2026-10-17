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
    private lateinit var conversationSearchRow: LinearLayout
    private lateinit var callSearchRow: LinearLayout
    private lateinit var callSearch: EditText
    private lateinit var clearCallSearch: TextView
    private var callQuery=""
    private var callRecords: List<Db.CallRecord> = emptyList()
    private var visibleCalls=100
    private val callSearchTask=Runnable { renderCalls() }
    private val homeBack=object:androidx.activity.OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if(activeTab==1 && nearbyPanel.handleBack()) return
            if(activeTab==3 && callSearch.hasFocus()) { hideKeyboard();return }
            if(activeTab==3 && callQuery.isNotEmpty()) { callSearch.setText("");return }
            switchTab(0)
        }
    }
    private lateinit var myAvatarView: FrameLayout
    private var avatarName=""
    private var avatarPhoto=""
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var db: Db
    private lateinit var connectionTv: TextView
    private lateinit var connectionDot: View
    private lateinit var searchField: EditText
    private lateinit var chatFilters: LinearLayout
    private val filterItems=mutableListOf<TextView>()
    private var activeFilter=0 // All, Unread, Groups
    private var searchQuery = ""
    private var loadVersion = 0
    private val searchTask = Runnable { loadConvs() }
    private val connectionTick = object : Runnable {
        override fun run() { updateConnection(); uiHandler.postDelayed(this, 3000) }
    }
    private fun updateConnection() {
        val status=ConnectionStatus.text(this)
        connectionTv.text = status
        connectionTv.contentDescription="Connection status: $status. Tap for details or retry."
        connectionTv.setTextColor(if(WsClient.connected) AppTheme.teal(this) else SUB)
        if(::connectionDot.isInitialized) {
            val color=if(WsClient.connected||MeshBridge.peersConnected>0) AppTheme.teal(this) else SUB
            connectionDot.background=pill(color)
        }
    }
    private var activeTab = 0  // Chats, Nearby, Spaces (existing groups), Calls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (!Session.loggedIn) { go(AuthActivity::class.java); return }
        db = Db.get(this)
        activeTab=savedInstanceState?.getInt("destination",0)?:0
        activeFilter=savedInstanceState?.getInt("chatFilter",0)?.coerceIn(0,2)?:0
        queries[0]=savedInstanceState?.getString("chatQuery").orEmpty()
        queries[2]=savedInstanceState?.getString("spaceQuery").orEmpty()
        callQuery=savedInstanceState?.getString("callQuery").orEmpty()
        buildUi()
        nearbyPanel.restoreState(savedInstanceState)
        onBackPressedDispatcher.addCallback(this,homeBack)
        askPermissions()
        MessageService.start(this)
        WsClient.connect(Session)
    }

    override fun onResume() {
        super.onResume();if(!::connectionTv.isInitialized)return
        AccountUi.ensureTrustedPhone(this)
        resumed=true;refreshAvatar();MessageService.start(this);RabtaEvents.add(this)
        uiHandler.removeCallbacks(connectionTick);uiHandler.post(connectionTick)
        when(activeTab) { 1->nearbyPanel.resume();3->loadCalls();else->loadConvs() }
    }
    override fun onPause() {
        resumed=false;if(::nearbyPanel.isInitialized)nearbyPanel.pause()
        RabtaEvents.remove(this);uiHandler.removeCallbacks(connectionTick);uiHandler.removeCallbacks(searchTask);uiHandler.removeCallbacks(callSearchTask)
        super.onPause()
    }
    override fun onDestroy() { RabtaEvents.remove(this);uiHandler.removeCallbacksAndMessages(null);super.onDestroy() }
    override fun onSaveInstanceState(state:Bundle) {
        queries[activeTab]=searchQuery
        nearbyPanel.saveState(state)
        state.putInt("destination",activeTab);state.putString("chatQuery",queries[0]);state.putString("spaceQuery",queries[2])
        state.putString("callQuery",callQuery)
        state.putInt("chatFilter",activeFilter)
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
        val brand=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;minimumHeight=dp(48);gravity=Gravity.CENTER_VERTICAL;isFocusable=true;setOnClickListener {ConnectionHelp.show(this@ConversationListActivity)}}
        brand.addView(RabtaBrand.lockup(this,28,TXT,22f))
        connectionTv=RabtaUi.text(this,"",12f,color=SUB).apply {maxLines=2;setPadding(0,dp(4),0,0)}
        val connectionRow=LinearLayout(this).apply {gravity=Gravity.CENTER_VERTICAL}
        connectionDot=View(this).apply {background=pill(SUB);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        connectionRow.addView(connectionDot,LinearLayout.LayoutParams(dp(6),dp(6)).apply {marginEnd=dp(6)})
        connectionRow.addView(connectionTv,LinearLayout.LayoutParams(0,-2,1f))
        brand.addView(connectionRow)
        header.addView(brand,LinearLayout.LayoutParams(0,-2,1f))
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
            contentDescription="My profile, ${Session.name}";isFocusable=true;background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT)
            setOnClickListener { startActivity(Intent(this@ConversationListActivity,ProfileActivity::class.java)) }
        }
        refreshAvatar()
        header.addView(myAvatarView,LinearLayout.LayoutParams(dp(48),dp(48)))
        root.addView(header)
        updateConnection()
        pageTitle=RabtaUi.text(this,"Chats",24f,true).apply {
            setPadding(dp(20),dp(16),dp(20),dp(8))
            androidx.core.view.ViewCompat.setAccessibilityHeading(this,true)
        }
        root.addView(pageTitle)
        searchField=EditText(this).apply {
            hint=L.t(this@ConversationListActivity,"Search names or messages")
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            textSize=14f;setTextColor(TXT);setHintTextColor(SUB);setSingleLine(true)
            minHeight=dp(48);setPadding(0,dp(8),0,dp(8));background=null
            contentDescription=L.t(this@ConversationListActivity,"Search names or messages")
            addTextChangedListener(object:android.text.TextWatcher {
                override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
                override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                    searchQuery=s?.toString().orEmpty();loadVersion++
                    uiHandler.removeCallbacks(searchTask);uiHandler.postDelayed(searchTask,180)
                }
                override fun afterTextChanged(s:android.text.Editable?) {}
            })
            setOnEditorActionListener { _,action,_ ->
                if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard();true } else false
            }
        }
        val searchRow=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(12),0,0,0)
            background=RabtaUi.shape(this@ConversationListActivity,WHITE,16)
        }
        searchRow.addView(TextView(this).apply {
            UiIcons.set(this,"search",SUB,18,"Search conversations");gravity=Gravity.CENTER
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(10) })
        searchRow.addView(searchField,LinearLayout.LayoutParams(0,-2,1f))
        val clearChats=RabtaUi.iconButton(this,"close","Clear conversation search") {searchField.setText("")}.apply {visibility=View.GONE}
        searchRow.addView(clearChats,LinearLayout.LayoutParams(dp(48),dp(48)))
        searchField.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun afterTextChanged(s:android.text.Editable?) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {clearChats.visibility=if(s.isNullOrEmpty())View.GONE else View.VISIBLE}
        })
        conversationSearchRow=searchRow
        root.addView(searchRow,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(20),0,dp(20),dp(8)) })
        buildCallSearch(root)
        chatFilters=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(16),dp(2),dp(16),dp(8));contentDescription="Chat filters"
        }
        listOf("All","Unread","Groups").forEachIndexed { index,label ->
            val chip=RabtaUi.text(this,label,13f,true,SUB).apply {
                gravity=Gravity.CENTER;minHeight=dp(48);isFocusable=true
                contentDescription="$label filter";isSelected=index==activeFilter
                setOnClickListener { activeFilter=index;updateChatFilters();loadConvs() }
            }
            filterItems.add(chip);chatFilters.addView(chip,LinearLayout.LayoutParams(0,dp(48),1f).apply { marginEnd=dp(6) })
        }
        root.addView(chatFilters)
        fun listColumn()=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(8),dp(20),dp(100))
        }
        convList=listColumn();callsList=listColumn().apply { setPadding(dp(20),0,dp(20),dp(24)) };spacesList=listColumn()
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
            UiIcons.set(this,"compose",AppTheme.onPrimary(this@ConversationListActivity),label="New chat or group")
            background=RabtaUi.ripple(this@ConversationListActivity,AppTheme.primary(this@ConversationListActivity),20);elevation=dp(1).toFloat()
        }
        content.addView(fab,FrameLayout.LayoutParams(dp(56),dp(56),Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(20),dp(12),dp(20),dp(20)) })
        root.addView(View(this).apply { setBackgroundColor(DIV) },LinearLayout.LayoutParams(-1,dp(1)))
        bottomNav=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;setBackgroundColor(WHITE);setPadding(dp(8),dp(8),dp(8),dp(8))
            contentDescription="Main navigation"
        }
        val icons=listOf("chats","nearby","spaces","phone")
        listOf("Chats","Nearby","Spaces","Calls").forEachIndexed { index,label ->
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=dp(56)
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
        updateChatFilters()
    }

    private fun refreshAvatar() {
        if(!::myAvatarView.isInitialized)return
        val photo=Session.profilePic;val name=Session.name
        if(avatarName==name&&avatarPhoto==photo&&myAvatarView.childCount>0)return
        avatarName=name;avatarPhoto=photo;myAvatarView.removeAllViews()
        myAvatarView.contentDescription="My profile, $name"
        myAvatarView.addView(initials(name,dp(36)),FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
        if(photo.isNotEmpty())MediaHelper.base64ToBitmap(photo)?.let {bmp->
            myAvatarView.addView(ImageView(this).apply {
                setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true
                background=RabtaUi.shape(this@ConversationListActivity,WHITE,999)
            },FrameLayout.LayoutParams(dp(36),dp(36),Gravity.CENTER))
        }
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
        pageTitle.textSize=24f
        pageTitle.text=L.t(this,listOf("Chats","Nearby","Spaces","Calls")[destination])
        conversationSearchRow.visibility=if(searchable) View.VISIBLE else View.GONE
        callSearchRow.visibility=if(destination==3 && callRecords.isNotEmpty()) View.VISIBLE else View.GONE
        searchField.visibility=if(searchable) View.VISIBLE else View.GONE
        chatFilters.visibility=if(destination==0) View.VISIBLE else View.GONE
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

    private fun updateChatFilters() {
        if(!::chatFilters.isInitialized)return
        filterItems.forEachIndexed { index,chip ->
            val selected=index==activeFilter
            chip.isSelected=selected
            chip.setTextColor(if(selected) AppTheme.accent(this) else SUB)
            chip.background=RabtaUi.ripple(this,if(selected) AppTheme.sent(this) else Color.TRANSPARENT,24)
        }
    }

    // ── Conversations ──
    private fun loadConvs() {
        if(activeTab!=0&&activeTab!=2)return
        val query=searchQuery;val destination=activeTab;val filter=activeFilter;val version=++loadVersion
        val target=if(destination==2) spacesList else convList
        if(target.childCount==0)target.addView(RabtaUi.loading(this,"Loading conversations…"))
        Thread {
                val result=runCatching {
                    db.searchConversations(query).filter {
                        if(destination==2) it.isGroup else when(filter) {
                            1 -> it.unread>0
                            2 -> it.isGroup
                            else -> true
                        }
                    }
                }
            uiHandler.post {
                if(isDestroyed||isFinishing||version!=loadVersion||destination!=activeTab)return@post
                target.removeAllViews()
                result.onFailure {
                    target.addView(RabtaUi.empty(this,"chats","Could not load conversations","Your saved chats are still on this phone.","Retry") { loadConvs() })
                }.onSuccess { list ->
                    if(list.isEmpty()) {
                        val spaces=destination==2
                        target.addView(when {
                            query.isNotBlank() -> RabtaUi.empty(this,"search","No matches","Try a different name or message.","Clear search") { searchField.setText("") }
                            !spaces&&filter==1 -> RabtaUi.empty(this,"chats","You’re all caught up","Unread conversations will appear here.")
                            else -> RabtaUi.empty(this,if(spaces||filter==2) "spaces" else "chats",
                                if(spaces||filter==2) "No groups yet" else "Start a conversation",
                                if(spaces||filter==2) "Create a group to bring people together." else "Find someone by username or Rabta ID, or add a nearby contact.",
                                if(spaces||filter==2) "New group" else "New chat") { if(spaces||filter==2) showCreateGroup() else showNewChat() }
                        })
                    } else list.forEach { target.addView(convRow(it)) }
                }
            }
        }.start()
    }

    private fun convRow(c: Db.Conversation): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT,16);setPadding(0,dp(12),0,dp(12))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(0) }
            isClickable = true; isFocusable = true
            setOnClickListener { open(c.peerId, c.peerName, c.isGroup) }
        }
        // Avatar: group icon or profile pic
        val av = FrameLayout(this)
        if (c.isGroup) av.addView(TextView(this).apply { UiIcons.set(this,"spaces",Color.WHITE,label="Group"); textSize = 20f; gravity = Gravity.CENTER; background = pill(AppTheme.ACCENT) }, FrameLayout.LayoutParams(dp(50),dp(50)))
        else {
            av.addView(initials(c.peerName, dp(50)), FrameLayout.LayoutParams(dp(50),dp(50)))
            ProfileHelper.loadIfNeeded(c.peerId, Session.serverUrl, Session.token) { bmp ->
                if (bmp != null) uiHandler.post {
                    val iv = ImageView(this).apply { setImageBitmap(bmp); scaleType = ImageView.ScaleType.CENTER_CROP; clipToOutline=true; background = pill(0xFF607D8B.toInt()) }
                    av.addView(iv, FrameLayout.LayoutParams(dp(50),dp(50)))
                }
            }
        }
        row.addView(av)
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14),0,dp(8),0) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(TextView(this).apply { text = c.peerName; maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END; textSize = 16f; typeface = Typeface.create("sans-serif-medium",Typeface.NORMAL); setTextColor(TXT) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.lastTime > 0) top.addView(TextView(this).apply { text = fmtTime(c.lastTime); textSize = 12f; setTextColor(if (c.unread > 0) AppTheme.accent(this@ConversationListActivity) else SUB) })
        mid.addView(top)
        val bot = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,0) }
        bot.addView(TextView(this).apply { text = c.lastMsg.ifEmpty { "No messages yet" }; textSize = 14f; setTextColor(SUB); maxLines = 1;ellipsize=android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.unread > 0) bot.addView(TextView(this).apply {
            text = if (c.unread > 99) "99+" else c.unread.toString()
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = pill(GREEN2); setPadding(dp(7),dp(2),dp(7),dp(2))
            contentDescription="${c.unread} unread messages"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        mid.addView(bot)
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun divider() = View(this).apply { setBackgroundColor(DIV) }.also {
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(78) }
    }

    // Calls use their own query and the existing, unmodified local call records.
    private fun buildCallSearch(root:LinearLayout) {
        callSearchRow=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(12),0,dp(4),0)
            background=RabtaUi.shape(this@ConversationListActivity,
                if(AppTheme.dark(this@ConversationListActivity)) AppTheme.surface(this@ConversationListActivity) else WHITE,12,
                if(AppTheme.dark(this@ConversationListActivity)) 0xFF2A3142.toInt() else DIV)
            visibility=View.GONE
        }
        callSearchRow.addView(TextView(this).apply {
            gravity=Gravity.CENTER;UiIcons.set(this,"search",SUB,20,"Search calls")
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(20),dp(24)))
        callSearch=EditText(this).apply {
            hint="Search calls";contentDescription="Search calls";textSize=14f
            setTextColor(TXT);setHintTextColor(SUB);setSingleLine(true);background=null
            setPadding(dp(10),0,dp(4),0);minimumHeight=0;minimumWidth=0
            imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            inputType=InputType.TYPE_CLASS_TEXT
            setText(callQuery)
            setOnEditorActionListener { _,action,_ ->
                if(action==android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard();true } else false
            }
        }
        callSearchRow.addView(callSearch,LinearLayout.LayoutParams(0,-1,1f))
        clearCallSearch=RabtaUi.iconButton(this,"close","Clear call search") { callSearch.setText("") }.apply {
            UiIcons.set(this,"close",SUB,18,"Clear call search")
            visibility=if(callQuery.isEmpty()) View.GONE else View.VISIBLE
        }
        callSearchRow.addView(clearCallSearch,LinearLayout.LayoutParams(dp(40),dp(44)))
        callSearch.addTextChangedListener(object:android.text.TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int) {}
            override fun afterTextChanged(s:android.text.Editable?) {}
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                callQuery=s?.toString().orEmpty();visibleCalls=100
                clearCallSearch.visibility=if(callQuery.isEmpty()) View.GONE else View.VISIBLE
                callsScroll.scrollTo(0,0)
                uiHandler.removeCallbacks(callSearchTask)
                if(activeTab==3)uiHandler.postDelayed(callSearchTask,150)
            }
        })
        root.addView(callSearchRow,LinearLayout.LayoutParams(-1,dp(44)).apply { setMargins(dp(20),0,dp(20),dp(8)) })
    }

    private fun loadCalls() {
        val version=++callVersion
        if(callsList.childCount==0)callsList.addView(RabtaUi.loading(this,"Loading calls…"))
        Thread {
            val result=runCatching { db.calls(Int.MAX_VALUE) }
            uiHandler.post {
                if(isDestroyed||isFinishing||version!=callVersion||activeTab!=3)return@post
                result.onFailure {
                    callsList.removeAllViews();callSearchRow.visibility=View.GONE
                    callsList.addView(callState("phone","Could not load calls","Your saved call history is unchanged.","Retry") { loadCalls() })
                }.onSuccess { calls -> callRecords=calls;renderCalls() }
            }
        }.start()
    }

    private fun callState(icon:String,title:String,detail:String,action:String?=null,run:()->Unit={}):View =
        LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
            setPadding(0,dp(32),0,dp(24))
            addView(TextView(this@ConversationListActivity).apply {
                gravity=Gravity.CENTER;UiIcons.set(this,icon,SUB,24,title)
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(36),dp(36)))
            addView(RabtaUi.text(this@ConversationListActivity,title,17f,true).apply {
                gravity=Gravity.CENTER;setPadding(0,dp(12),0,dp(6))
                accessibilityLiveRegion=View.ACCESSIBILITY_LIVE_REGION_POLITE
            })
            addView(RabtaUi.text(this@ConversationListActivity,detail,13f,color=SUB).apply { gravity=Gravity.CENTER })
            if(action!=null)addView(textBtn(action,AppTheme.accent(this@ConversationListActivity),run),LinearLayout.LayoutParams(-2,dp(48)).apply { topMargin=dp(16) })
        }

    private fun renderCalls() {
        if(activeTab!=3)return
        callSearchRow.visibility=if(callRecords.isEmpty()) View.GONE else View.VISIBLE
        callsList.removeAllViews()
        if(callRecords.isEmpty()) {
            if(callSearch.hasFocus())hideKeyboard()
            callsList.addView(callState("phone","No calls yet","Your call history will appear here.","Start a call") { showCallContacts() })
            return
        }
        val query=callQuery.trim()
        val matches=callRecords.filter { it.peerName.contains(query,true)||it.peerId.contains(query,true) }
        if(matches.isEmpty()) {
            callsList.addView(callState("search","No calls found","Try another name or Rabta ID.","Clear search") { callSearch.setText("") })
            return
        }
        matches.take(visibleCalls).forEach { callsList.addView(callRow(it)) }
        if(matches.size>visibleCalls)callsList.addView(textBtn("Show more calls",AppTheme.accent(this)) { visibleCalls+=100;renderCalls() })
        callsList.addView(textBtn("Start a call",AppTheme.accent(this)) { showCallContacts() })
    }

    private fun callRow(c: Db.CallRecord): View {
        val missed=c.type.contains("missed",true)
        val video=c.type.contains("video",true)
        val name=c.peerName.ifBlank { c.peerId }
        val row=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(8),0,dp(8))
            background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT,12)
            isFocusable=true;setOnClickListener { open(c.peerId,name) }
            contentDescription="$name, ${c.type}, ${callTime(c.startedAt)}"
        }
        row.addView(initials(name,dp(42)),LinearLayout.LayoutParams(dp(42),dp(42)))
        val mid=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(12),0,dp(6),0) }
        mid.addView(RabtaUi.text(this,name,15f,true,if(missed) AppTheme.error(this) else TXT).apply {
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END
        })
        val medium=if(video) "video" else "voice"
        val direction=when {
            missed -> "↙ Missed call"
            c.type.startsWith("outgoing") -> "↗ Outgoing $medium"
            c.type.startsWith("incoming") -> "↙ Incoming $medium"
            else -> c.type.replaceFirstChar { it.uppercase() }
        }
        val duration=if(c.duration>0) " · ${c.duration/60}:${(c.duration%60).toString().padStart(2,'0')}" else ""
        mid.addView(RabtaUi.text(this,"$direction · ${callTime(c.startedAt)}$duration",12f,color=if(missed) AppTheme.error(this) else SUB).apply {
            maxLines=2;setPadding(0,dp(4),0,0)
        })
        row.addView(mid,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(RabtaUi.iconButton(this,if(video) "video" else "phone","Call back $name") {
            callContact(c.peerId,name,video)
        })
        return row
    }

    private fun callTime(time:Long):String =
        android.text.format.DateUtils.formatDateTime(this,time,android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH)

    private fun callContact(id:String,name:String,video:Boolean=false) {
        hideKeyboard()
        if(Privacy.blocked(this,id)) { android.widget.Toast.makeText(this,"Unblock this contact before calling",android.widget.Toast.LENGTH_SHORT).show();return }
        if(!WsClient.connected || WsClient.protocol<2) { ConnectionHelp.show(this);return }
        startActivity(Intent(this,ChatActivity::class.java).putExtra("peerId",id).putExtra("peerName",name)
            .putExtra("requestedCallKind",if(video) "video" else "voice"))
    }

    private fun showCallContacts() {
        hideKeyboard()
        Thread {
            val result=runCatching {
                (db.searchConversations("").filter { !it.isGroup }.map { it.peerId to it.peerName }+
                    db.calls(Int.MAX_VALUE).map { it.peerId to it.peerName }).distinctBy { it.first }
                    .filter { it.first!=Session.id && !Privacy.blocked(this,it.first) }
                    .sortedBy { it.second.lowercase(java.util.Locale.ROOT) }
            }
            uiHandler.post {
                if(isDestroyed||isFinishing)return@post
                result.onSuccess { contacts ->
                    RabtaUi.sheet(this,"Start a call",contacts.map { (id,name) ->
                        RabtaUi.SheetAction(name.ifBlank { id },"Voice call · $id") { callContact(id,name) }
                    }+RabtaUi.SheetAction("Find by username or Rabta ID") { showNewChat(forCall=true) })
                }.onFailure { android.widget.Toast.makeText(this,"Could not load contacts. Try again.",android.widget.Toast.LENGTH_SHORT).show() }
            }
        }.start()
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
        val locale=java.util.Locale.getDefault()
        val now=java.util.Calendar.getInstance()
        val then=java.util.Calendar.getInstance().apply { timeInMillis=t }
        val today=now.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR)&&now.get(java.util.Calendar.DAY_OF_YEAR)==then.get(java.util.Calendar.DAY_OF_YEAR)
        val yesterday=java.util.Calendar.getInstance().apply { add(java.util.Calendar.DAY_OF_YEAR,-1) }
        val wasYesterday=yesterday.get(java.util.Calendar.YEAR)==then.get(java.util.Calendar.YEAR)&&yesterday.get(java.util.Calendar.DAY_OF_YEAR)==then.get(java.util.Calendar.DAY_OF_YEAR)
        return when {
            today -> java.text.SimpleDateFormat("HH:mm",locale).format(java.util.Date(t))
            wasYesterday -> L.t(this,"Yesterday")
            else -> java.text.SimpleDateFormat("dd/MM",locale).format(java.util.Date(t))
        }
    }

    // ── FAB menu ──
    private fun showFabMenu() {
        RabtaUi.sheet(this,"Start a conversation",listOf(
            RabtaUi.SheetAction("New chat","Find a username or Rabta ID") { showNewChat() },
            RabtaUi.SheetAction("New group","Bring your contacts together") { showCreateGroup() }
        ))
    }

    private fun showNewChat(forCall:Boolean=false) {
        fun selected(id:String,name:String) { if(forCall)callContact(id,name) else open(id,name) }
        RabtaUi.inputSheet(this,if(forCall) "Start a call" else "New chat","@username or Rabta ID","Find") { raw,status,dlg ->
            val un=raw.lowercase()
            if(un.isEmpty()) { status.text="Enter a username or Rabta ID";status.setTextColor(AppTheme.error(this));return@inputSheet }
            if(Regex("[0-9a-f]{12}").matches(un)) { dlg.dismiss();selected(un.uppercase(),un.uppercase());return@inputSheet }
            status.text="Searching for account…";status.setTextColor(SUB)
            WsClient.findUser(Session.serverUrl,un,Session.token) { ok,msg,data ->
                if(ok&&data!=null) {
                    val pid=data.optString("id");val pn=data.optString("name").ifEmpty { data.optString("username") }
                    if(pid.isNotEmpty()) { dlg.dismiss();selected(pid,pn) }
                    else { status.text="No account was found";status.setTextColor(AppTheme.error(this)) }
                } else { status.text=msg;status.setTextColor(AppTheme.error(this)) }
            }
        }
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
                uiHandler.post { open(gId, name, true) }
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
    override fun onMessage(fromId: String, fromName: String, msgId: String, body: String, t: Long, msgType: String, groupId: String) { uiHandler.postDelayed({ loadConvs() }, 300) }
    override fun onGroupCreated(groupId: String, groupName: String, members: List<String>) {
        Thread { try { db.saveGroup(groupId, groupName, members); db.upsertConversation(groupId, groupName, "New group!", System.currentTimeMillis(), true, true) } catch (_: Exception) {} }.start()
        uiHandler.postDelayed({ loadConvs() }, 400)
    }
    override fun onHistoryChanged(convId: String) { loadConvs() }
    override fun onConnected() { updateConnection() }
    override fun onDisconnected() { updateConnection() }
}
