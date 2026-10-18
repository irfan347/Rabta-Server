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
    private lateinit var moments: LinearLayout
    private lateinit var momentsScroll: android.widget.HorizontalScrollView
    private var momentsVersion=0
    private var momentsLoadedAt=0L
    private val filterItems=mutableListOf<TextView>()
    private var activeFilter=0 // All, Unread, Groups, Nearby
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
        connectionTv.setTextColor(SUB)
        if(::connectionDot.isInitialized) {
            val color=if(MeshBridge.peersConnected>0) AppTheme.teal(this) else AppTheme.violet(this)
            connectionDot.background=pill(color)
        }
        // NOVA keeps the header clean: the strip appears only while the server is not online.
        if(::connectionStrip.isInitialized) connectionStrip.visibility=if(WsClient.connected) View.GONE else View.VISIBLE
        if(::myAvatarView.isInitialized) myAvatarView.foreground=if(WsClient.connected) onlineDot() else null
    }
    private lateinit var connectionStrip: LinearLayout
    private lateinit var searchToggle: TextView
    private lateinit var chatsBadge: TextView
    private var searchOpen=false
    /** A small teal presence dot on the header avatar while the server connection is live. */
    private fun onlineDot()=android.graphics.drawable.LayerDrawable(arrayOf(GradientDrawable().apply {
        shape=GradientDrawable.OVAL;setColor(AppTheme.teal(this@ConversationListActivity));setStroke(dp(2),BG)
    })).apply { setLayerGravity(0,Gravity.BOTTOM or Gravity.END);setLayerSize(0,dp(12),dp(12));setLayerInset(0,0,0,dp(4),dp(4)) }
    private var activeTab = 0  // Chats, Nearby, Spaces (existing groups), Calls

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Session.init(this)
        if (!Session.loggedIn) { go(AuthActivity::class.java); return }
        db = Db.get(this)
        activeTab=savedInstanceState?.getInt("destination",0)?:0
        activeFilter=savedInstanceState?.getInt("chatFilter",0)?.coerceIn(0,3)?:0
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
        resumed=true;refreshAvatar();ProfilePhoto.restore { if(!isDestroyed) refreshAvatar() };MessageService.start(this);RabtaEvents.add(this)
        uiHandler.removeCallbacks(connectionTick);uiHandler.post(connectionTick)
        when(activeTab) { 1->nearbyPanel.resume();3->loadCalls();else->loadConvs() }
        if(activeTab==0) loadMoments(force=true)
        AppUpdate.check(this)
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
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(18),dp(8),dp(8),dp(4))
        }
        header.addView(RabtaBrand.wordmark(this,26,23f),LinearLayout.LayoutParams(0,-2,1f))
        searchToggle=RabtaUi.iconButton(this,"search","Search chats") { toggleSearch() }.apply { UiIcons.set(this,"search",TXT,22,L.t(this@ConversationListActivity,"Search chats")) }
        header.addView(searchToggle)
        header.addView(RabtaUi.iconButton(this,"qr","QR Connect") { startActivity(Intent(this,InstantConnectActivity::class.java)) }.apply {
            UiIcons.set(this,"qr",TXT,22,L.t(this@ConversationListActivity,"QR Connect"))
        })
        myAvatarView=FrameLayout(this).apply {
            contentDescription="My profile, ${Session.name}";isFocusable=true;background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT,999)
            setOnClickListener { startActivity(Intent(this@ConversationListActivity,ProfileActivity::class.java)) }
        }
        refreshAvatar()
        header.addView(myAvatarView,LinearLayout.LayoutParams(dp(48),dp(48)).apply { marginStart=dp(4) })
        root.addView(header)
        connectionStrip=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;isFocusable=true;minimumHeight=dp(36);setPadding(dp(14),dp(6),dp(14),dp(6))
            background=RabtaUi.ripple(this@ConversationListActivity,AppTheme.raised(this@ConversationListActivity),12)
            setOnClickListener { ConnectionHelp.show(this@ConversationListActivity) }
        }
        connectionDot=View(this).apply {background=pill(SUB);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        connectionStrip.addView(connectionDot,LinearLayout.LayoutParams(dp(8),dp(8)).apply {marginEnd=dp(10)})
        connectionTv=RabtaUi.text(this,"",12f,color=SUB).apply {maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
        connectionStrip.addView(connectionTv,LinearLayout.LayoutParams(0,-2,1f))
        root.addView(connectionStrip,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),0,dp(16),dp(6)) })
        updateConnection()
        // Spaces has no NOVA reference screen, so it keeps a compact title; the other tabs open straight into content.
        pageTitle=RabtaUi.text(this,"Spaces",22f,true).apply {
            setPadding(dp(16),dp(6),dp(16),dp(8))
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
            setPadding(dp(14),0,0,0)
            background=RabtaUi.shape(this@ConversationListActivity,AppTheme.raised(this@ConversationListActivity),24)
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
        root.addView(searchRow,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),0,dp(16),dp(4)) })
        buildCallSearch(root)
        moments=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(2),dp(10),dp(4));contentDescription="Moments" }
        momentsScroll=android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false;addView(moments) }
        root.addView(momentsScroll)
        renderMoments(emptyList())
        // NOVA segmented filter: one rounded track, the active filter is a lime pill.
        chatFilters=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;contentDescription="Chat filters"
            setPadding(dp(4),0,dp(4),0);background=RabtaUi.shape(this@ConversationListActivity,AppTheme.surface(this@ConversationListActivity),24)
        }
        listOf("All","Unread","Groups","Nearby").forEachIndexed { index,label ->
            val chip=RabtaUi.text(this,label,13f,true,SUB).apply {
                gravity=Gravity.CENTER;minHeight=dp(48);isFocusable=true;maxLines=1
                contentDescription="$label filter";isSelected=index==activeFilter
                setOnClickListener { activeFilter=index;updateChatFilters();loadConvs() }
            }
            filterItems.add(chip);chatFilters.addView(chip,LinearLayout.LayoutParams(0,dp(48),1f))
        }
        root.addView(chatFilters,LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(16),dp(4),dp(16),dp(4)) })
        fun listColumn()=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(4),dp(16),dp(96))
        }
        convList=listColumn();callsList=listColumn().apply { setPadding(dp(16),0,dp(16),dp(24)) };spacesList=listColumn()
        chatsScroll=ScrollView(this).apply { addView(convList);isVerticalScrollBarEnabled=false }
        callsScroll=ScrollView(this).apply { addView(callsList);isVerticalScrollBarEnabled=false }
        spacesScroll=ScrollView(this).apply { addView(spacesList);isVerticalScrollBarEnabled=false }
        nearbyPanel=NearbyPanel(this)
        nearbyScroll=ScrollView(this).apply {
            addView(nearbyPanel.view.apply { setPadding(dp(16),dp(4),dp(16),dp(20)) });isVerticalScrollBarEnabled=false
        }
        val content=FrameLayout(this)
        listOf(chatsScroll,nearbyScroll,spacesScroll,callsScroll).forEach { content.addView(it,FrameLayout.LayoutParams(-1,-1)) }
        root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        fab=RabtaUi.iconButton(this,"compose","New chat or group") { if(activeTab==2) showCreateGroup() else showFabMenu() }.apply {
            UiIcons.set(this,"plus",AppTheme.onPrimary(this@ConversationListActivity),28,"New chat or group")
            background=RabtaUi.ripple(this@ConversationListActivity,AppTheme.primary(this@ConversationListActivity),999);elevation=dp(6).toFloat()
        }
        content.addView(fab,FrameLayout.LayoutParams(dp(60),dp(60),Gravity.BOTTOM or Gravity.END).apply { setMargins(dp(16),dp(12),dp(20),dp(16)) })
        bottomNav=LinearLayout(this).apply {
            orientation=LinearLayout.HORIZONTAL;setPadding(dp(10),dp(8),dp(10),dp(8))
            background=RabtaUi.shape(this@ConversationListActivity,WHITE,24).apply {
                val r=dp(24).toFloat();cornerRadii=floatArrayOf(r,r,r,r,0f,0f,0f,0f)
            }
            contentDescription="Main navigation"
        }
        val icons=listOf("chats","nearby","spaces","phone")
        listOf("Chats","Nearby","Spaces","Calls").forEachIndexed { index,label ->
            val item=LinearLayout(this).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;minimumHeight=dp(60)
                setPadding(dp(2),dp(6),dp(2),dp(6));isFocusable=true;contentDescription=L.t(this@ConversationListActivity,label)
                setOnClickListener { switchTab(index) }
            }
            val iconBox=FrameLayout(this).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
            iconBox.addView(TextView(this).apply {
                UiIcons.set(this,icons[index],SUB,24,label);gravity=Gravity.CENTER
                importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },FrameLayout.LayoutParams(dp(32),dp(28),Gravity.CENTER))
            if(index==0) {
                chatsBadge=RabtaUi.text(this,"",10f,true,AppTheme.onPrimary(this)).apply {
                    gravity=Gravity.CENTER;minWidth=dp(18);setPadding(dp(4),0,dp(4),0);visibility=View.GONE
                    background=pill(AppTheme.primary(this@ConversationListActivity));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                iconBox.addView(chatsBadge,FrameLayout.LayoutParams(-2,dp(18),Gravity.TOP or Gravity.END))
            }
            item.addView(iconBox,LinearLayout.LayoutParams(dp(48),dp(30)))
            item.addView(RabtaUi.text(this,label,12f,true,SUB).apply {
                gravity=Gravity.CENTER;maxLines=2;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setPadding(0,dp(2),0,0)
            })
            navItems.add(item);bottomNav.addView(item,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(3);marginEnd=dp(3) })
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
        val ring=FrameLayout(this).apply {
            background=GradientDrawable().apply { shape=GradientDrawable.OVAL;setStroke(dp(2),AppTheme.accent(this@ConversationListActivity)) }
            setPadding(dp(3),dp(3),dp(3),dp(3))
        }
        ring.addView(initials(name,dp(34)),FrameLayout.LayoutParams(-1,-1))
        if(photo.isNotEmpty())MediaHelper.base64ToBitmap(photo)?.let {bmp->
            ring.addView(ImageView(this).apply {
                setImageBitmap(bmp);scaleType=ImageView.ScaleType.CENTER_CROP;clipToOutline=true
                background=RabtaUi.shape(this@ConversationListActivity,WHITE,999)
            },FrameLayout.LayoutParams(-1,-1))
        }
        myAvatarView.addView(ring,FrameLayout.LayoutParams(dp(40),dp(40),Gravity.CENTER))
    }

    /** NOVA keeps search behind the header icon; it stays open while a query is entered. */
    private fun toggleSearch(open:Boolean=!searchOpen) {
        searchOpen=open
        val searchable=activeTab==0||activeTab==2
        conversationSearchRow.visibility=if(searchable&&(searchOpen||searchQuery.isNotEmpty())) View.VISIBLE else View.GONE
        if(open&&searchable) {
            searchField.requestFocus()
            androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).show(androidx.core.view.WindowInsetsCompat.Type.ime())
        } else if(!open) { searchField.setText("");hideKeyboard() }
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
        pageTitle.visibility=if(destination==2) View.VISIBLE else View.GONE
        pageTitle.text=L.t(this,"Spaces")
        searchQuery=queries[destination].orEmpty()
        searchOpen=searchQuery.isNotEmpty()
        conversationSearchRow.visibility=if(searchable&&searchOpen) View.VISIBLE else View.GONE
        searchToggle.visibility=if(searchable) View.VISIBLE else View.GONE
        callSearchRow.visibility=if(destination==3 && callRecords.isNotEmpty()) View.VISIBLE else View.GONE
        searchField.visibility=if(searchable) View.VISIBLE else View.GONE
        chatFilters.visibility=if(destination==0) View.VISIBLE else View.GONE
        momentsScroll.visibility=if(destination==0) View.VISIBLE else View.GONE
        if(destination==0) loadMoments()
        searchField.hint=L.t(this,if(destination==2) "Search your groups" else "Search names or messages")
        searchField.contentDescription=searchField.hint
        searchField.setText(searchQuery)
        val pages=listOf(chatsScroll,nearbyScroll,spacesScroll,callsScroll)
        pages.forEachIndexed { index,view -> view.visibility=if(index==destination) View.VISIBLE else View.GONE }
        navItems.forEachIndexed { index,item ->
            val selected=index==destination;item.isSelected=selected
            // NOVA: the destination sits on a lime-tinted tile with lime icon and label.
            item.background=RabtaUi.ripple(this,if(selected) AppTheme.sent(this) else Color.TRANSPARENT,16)
            val icon=(item.getChildAt(0) as FrameLayout).getChildAt(0) as TextView
            val color=if(selected) AppTheme.accent(this) else SUB
            UiIcons.set(icon,listOf("chats","nearby","spaces","phone")[index],color,24,listOf("Chats","Nearby","Spaces","Calls")[index])
            (item.getChildAt(1) as TextView).setTextColor(color)
        }
        fab.visibility=if(searchable) View.VISIBLE else View.GONE
        fab.contentDescription=if(destination==2) L.t(this,"New group") else "New chat or group"
        if(destination==1 && resumed) nearbyPanel.resume() else nearbyPanel.pause()
        if(destination==3) loadCalls() else if(searchable) loadConvs()
        if(animate) RabtaUi.enter(pages[destination])
    }

    // ── Moments: real 24-hour statuses from the server; nothing is shown when there are none ──
    private data class Moment(val owner:String,val name:String,val own:Boolean)
    private fun loadMoments(force:Boolean=false) {
        if(!::moments.isInitialized||activeTab!=0)return
        if(!force&&System.currentTimeMillis()-momentsLoadedAt<60000)return
        momentsLoadedAt=System.currentTimeMillis();val version=++momentsVersion;val owner=Session.id
        Thread {
            val result=runCatching {
                val items=FeatureApi.request("/api/status").getJSONArray("statuses");val now=System.currentTimeMillis()
                (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optLong("expires")>now }
                    .distinctBy { it.optString("owner") }
                    .map { Moment(it.optString("owner"),it.optString("name").ifBlank { "Rabta contact" },it.optString("owner")==owner) }
            }
            uiHandler.post {
                if(isDestroyed||isFinishing||version!=momentsVersion||owner!=Session.id)return@post
                result.onSuccess { renderMoments(it) }.onFailure { momentsLoadedAt=0L }
            }
        }.start()
    }
    private fun renderMoments(list:List<Moment>) {
        moments.removeAllViews()
        val mine=list.firstOrNull { it.own }
        moments.addView(momentItem("My Moment",Session.name,mine!=null,mine==null))
        list.filter { !it.own }.take(20).forEach { moments.addView(momentItem(it.name,it.name,true,false)) }
    }
    private fun momentItem(label:String,name:String,ring:Boolean,add:Boolean):View=LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isFocusable=true
        setPadding(dp(4),dp(4),dp(4),dp(4));background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT,16)
        contentDescription=when { label=="My Moment" -> "My Moment. Status"; else -> "View moments from $label" }
        setOnClickListener { startActivity(Intent(this@ConversationListActivity,StatusActivity::class.java)) }
        val circle=FrameLayout(this@ConversationListActivity).apply {
            // A lime ring means there is a live moment to see.
            background=RabtaUi.shape(this@ConversationListActivity,Color.TRANSPARENT,999,if(ring) AppTheme.accent(this@ConversationListActivity) else DIV)
                .apply { setStroke(dp(2),if(ring) AppTheme.accent(this@ConversationListActivity) else DIV) }
            setPadding(dp(4),dp(4),dp(4),dp(4))
        }
        if(add) circle.addView(TextView(this@ConversationListActivity).apply {
            UiIcons.set(this,"plus",AppTheme.accent(this@ConversationListActivity),22,"Add");gravity=Gravity.CENTER
            background=pill(AppTheme.raised(this@ConversationListActivity));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },FrameLayout.LayoutParams(-1,-1))
        else circle.addView(initials(name,dp(52)).apply { importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },FrameLayout.LayoutParams(-1,-1))
        addView(circle,LinearLayout.LayoutParams(dp(60),dp(60)))
        addView(RabtaUi.text(this@ConversationListActivity,label,12f,color=if(add) SUB else TXT).apply {
            gravity=Gravity.CENTER;maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(4),0,0)
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(78),-2))
    }

    private fun updateChatFilters() {
        if(!::chatFilters.isInitialized)return
        filterItems.forEachIndexed { index,chip ->
            val selected=index==activeFilter
            chip.isSelected=selected
            chip.setTextColor(if(selected) AppTheme.onPrimary(this) else SUB)
            // A 40dp lime pill inside the 48dp touch target.
            chip.background=android.graphics.drawable.InsetDrawable(RabtaUi.ripple(this,if(selected) AppTheme.primary(this) else Color.TRANSPARENT,999),0,dp(4),0,dp(4))
            if(index==3) {
                // The dot says someone is connected nearby right now.
                val label=L.t(this,"Nearby")
                chip.text=if(MeshBridge.peersConnected>0) android.text.SpannableString("$label ●").apply {
                    setSpan(android.text.style.ForegroundColorSpan(if(selected) AppTheme.onPrimary(this@ConversationListActivity) else AppTheme.accent(this@ConversationListActivity)),length-1,length,android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                } else label
            }
        }
    }

    // ── Conversations ──
    private fun loadConvs() {
        if(activeTab!=0&&activeTab!=2)return
        val query=searchQuery;val destination=activeTab;val filter=activeFilter;val version=++loadVersion
        val target=if(destination==2) spacesList else convList
        if(target.childCount==0)target.addView(RabtaUi.loading(this,"Loading conversations…"))
        // Nearby means a key-verified peer is directly connected right now.
        val nearby=MeshBridge.snapshot().peers.filter { it.keyMatched }.map { it.id }.toSet()
        Thread {
                val result=runCatching {
                    db.searchConversations(query).filter {
                        if(destination==2) it.isGroup else when(filter) {
                            1 -> it.unread>0
                            2 -> it.isGroup
                            3 -> !it.isGroup&&it.peerId in nearby
                            else -> true
                        }
                    }
                }
                val unreadChats=runCatching { db.searchConversations("").count { it.unread>0 } }.getOrDefault(0)
                // The newest message's real delivery state drives the ✓ / ✓✓ / blue ✓✓ in each row.
                val ticks=result.getOrNull()?.associate { it.peerId to runCatching { db.lastMessageState(it.peerId) }.getOrNull() }.orEmpty()
            uiHandler.post {
                if(isDestroyed||isFinishing||version!=loadVersion||destination!=activeTab)return@post
                chatsBadge.text=if(unreadChats>99) "99+" else unreadChats.toString()
                chatsBadge.visibility=if(unreadChats>0) View.VISIBLE else View.GONE
                navItems.firstOrNull()?.let { androidx.core.view.ViewCompat.setStateDescription(it,if(unreadChats>0) "$unreadChats unread" else null) }
                target.removeAllViews()
                result.onFailure {
                    target.addView(RabtaUi.empty(this,"chats","Could not load conversations","Your saved chats are still on this phone.","Retry") { loadConvs() })
                }.onSuccess { list ->
                    if(list.isEmpty()) {
                        val spaces=destination==2
                        target.addView(when {
                            query.isNotBlank() -> RabtaUi.empty(this,"search","No matches","Try a different name or message.","Clear search") { searchField.setText("") }
                            !spaces&&filter==1 -> RabtaUi.empty(this,"chats","You’re all caught up","Unread conversations will appear here.")
                            !spaces&&filter==3 -> RabtaUi.empty(this,"nearby","No one connected nearby","Chats with people connected to you nearby appear here.","Open Nearby") { switchTab(1) }
                            else -> RabtaUi.empty(this,if(spaces||filter==2) "spaces" else "chats",
                                if(spaces||filter==2) "No groups yet" else "Start a conversation",
                                if(spaces||filter==2) "Create a group to bring people together." else "Find someone by username or Rabta ID, or add a nearby contact.",
                                if(spaces||filter==2) "New group" else "New chat") { if(spaces||filter==2) showCreateGroup() else showNewChat() }
                        })
                    } else list.forEach { target.addView(convRow(it,ticks[it.peerId])) }
                    // Spaces: temporary nearby rooms need networking Rabta does not have yet.
                    if(destination==2&&query.isBlank()) target.addView(RabtaUi.comingSoon(this,"AirSpace","Temporary rooms with people nearby","nearby").apply {
                        (layoutParams as LinearLayout.LayoutParams).topMargin=dp(16)
                    })
                }
            }
        }.start()
    }

    private fun convRow(c: Db.Conversation, last: Pair<Boolean,String>? = null): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT,16);setPadding(dp(4),dp(10),dp(4),dp(10))
            layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(0) }
            isClickable = true; isFocusable = true
            setOnClickListener { open(c.peerId, c.peerName, c.isGroup) }
        }
        // Avatar: group icon or profile pic
        val av = FrameLayout(this)
        if (c.isGroup) av.addView(TextView(this).apply { UiIcons.set(this,"spaces",AppTheme.violet(this@ConversationListActivity),22,label="Group"); gravity = Gravity.CENTER; background = pill(AppTheme.raised(this@ConversationListActivity)) }, FrameLayout.LayoutParams(dp(50),dp(50)))
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
        bot.addView(TextView(this).apply {
            text = preview(c.lastMsg, last?.takeIf { it.first }?.second); textSize = 14f; setTextColor(SUB); maxLines = 1;ellipsize=android.text.TextUtils.TruncateAt.END
            last?.takeIf { it.first }?.let { contentDescription = "You: ${c.lastMsg}. ${MessageDelivery.label(it.second).removePrefix("✓✓ ").removePrefix("✓ ")}" }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (c.unread > 0) bot.addView(TextView(this).apply {
            text = if (c.unread > 99) "99+" else c.unread.toString()
            textSize = 12f; typeface = Typeface.DEFAULT_BOLD; setTextColor(AppTheme.onPrimary(this@ConversationListActivity)); gravity = Gravity.CENTER
            minWidth = dp(22); background = pill(AppTheme.primary(this@ConversationListActivity)); setPadding(dp(7),dp(2),dp(7),dp(2))
            contentDescription="${c.unread} unread messages"
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(6) })
        mid.addView(bot)
        row.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    /** Voice notes get a lime mic icon, as in WhatsApp; older rows saved before durations were added read "Voice message". */
    private fun preview(last:String, mineStatus:String?=null):CharSequence {
        if(last.isEmpty()) return "No messages yet"
        val out=android.text.SpannableStringBuilder()
        // Own last message: ✓ relayed, ✓✓ delivered, blue ✓✓ read, a clock while waiting, red ! when it failed.
        if(mineStatus!=null) {
            val color=when { mineStatus==MessageDelivery.READ -> AppTheme.readTick(this); MessageDelivery.retryable(mineStatus) -> AppTheme.error(this); else -> SUB }
            out.append(UiIcons.inline(this,MessageDelivery.tickIcon(mineStatus),color,17)).append(" ")
        }
        val voice=last=="Voice note"||last.startsWith("🎤 ")
        if(!voice) return out.append(last)
        val label=last.removePrefix("🎤 ").let { if(it=="Voice note") "Voice message" else it }
        return out.append(UiIcons.inline(this,"mic",AppTheme.accent(this),16)).append(" ").append(label)
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
                if(AppTheme.dark(this@ConversationListActivity)) 0xFF223040.toInt() else DIV)
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
        callsList.addView(callStarter())
        if(callRecords.isEmpty()) {
            if(callSearch.hasFocus())hideKeyboard()
            callsList.addView(callState("phone","No calls yet","Your call history will appear here."))
            return
        }
        val query=callQuery.trim()
        val matches=callRecords.filter { it.peerName.contains(query,true)||it.peerId.contains(query,true) }
        val head=LinearLayout(this).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),dp(8),0,dp(4)) }
        head.addView(RabtaUi.text(this,"Recent calls",17f,true).apply { androidx.core.view.ViewCompat.setAccessibilityHeading(this,true) },LinearLayout.LayoutParams(0,-2,1f))
        if(query.isEmpty()&&!showAllCalls&&matches.size>RECENT_CALLS)
            head.addView(textBtn("See all",AppTheme.accent(this)) { showAllCalls=true;renderCalls() })
        callsList.addView(head)
        if(matches.isEmpty()) {
            callsList.addView(callState("search","No calls found","Try another name or Rabta ID.","Clear search") { callSearch.setText("") })
            return
        }
        val limit=if(query.isEmpty()&&!showAllCalls) RECENT_CALLS else visibleCalls
        matches.take(limit).forEach { callsList.addView(callRow(it)) }
        if(limit==visibleCalls&&matches.size>visibleCalls)callsList.addView(textBtn("Show more calls",AppTheme.accent(this)) { visibleCalls+=100;renderCalls() })
    }
    private var showAllCalls=false
    private var callKind=0 // 0 voice, 1 video, 2 Walkie-Talkie (not built)
    private val RECENT_CALLS=8

    /** NOVA calls: a mode switch, one start-call orb and three round shortcuts. Walkie-Talkie is not built yet. */
    private fun callStarter():View = LinearLayout(this).apply {
        orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL
        layoutParams=LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) }
        val ctx=this@ConversationListActivity
        // Voice · Video · Walkie-Talkie
        val modes=LinearLayout(ctx).apply { setPadding(dp(4),0,dp(4),0);background=RabtaUi.shape(ctx,AppTheme.surface(ctx),24);contentDescription="Call type" }
        listOf("Voice","Video","Walkie-Talkie").forEachIndexed { index,label ->
            val selected=index==callKind
            modes.addView(RabtaUi.text(ctx,label,13f,true,if(selected) AppTheme.onPrimary(ctx) else SUB).apply {
                gravity=Gravity.CENTER;maxLines=1;isFocusable=true;isSelected=selected;contentDescription="$label calls"
                background=android.graphics.drawable.InsetDrawable(RabtaUi.ripple(ctx,if(selected) AppTheme.primary(ctx) else Color.TRANSPARENT,999),0,dp(4),0,dp(4))
                setOnClickListener { callKind=index;renderCalls() }
            },LinearLayout.LayoutParams(0,dp(48),1f))
        }
        addView(modes,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4) })
        val soon=callKind==2
        val start={ when(callKind) { 0 -> showCallContacts(); 1 -> showCallContacts(video=true); else -> walkieSoon() } }
        // The orb
        val orb=FrameLayout(ctx)
        orb.addView(CallOrbView(ctx),FrameLayout.LayoutParams(-1,-1))
        orb.addView(TextView(ctx).apply {
            UiIcons.set(this,listOf("phone","video","walkie")[callKind],AppTheme.accent(ctx),34,"Start a call");gravity=Gravity.CENTER;isFocusable=true
            background=RabtaUi.ripple(ctx,AppTheme.bg(ctx),999).also { (it.getDrawable(0) as GradientDrawable).setStroke(dp(3),AppTheme.accent(ctx)) }
            setOnClickListener { start() }
        },FrameLayout.LayoutParams(dp(92),dp(92),Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin=dp(44) })
        val caption=LinearLayout(ctx).apply { orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL }
        caption.addView(RabtaUi.text(ctx,if(soon) "Walkie-Talkie" else "Start a call",20f,true).apply { gravity=Gravity.CENTER;isClickable=true;setOnClickListener { start() } })
        caption.addView(RabtaUi.text(ctx,when(callKind) { 0 -> "Voice calls over the internet with your contacts"; 1 -> "Video calls over the internet with your contacts"; else -> "Push-to-talk is coming soon" },12f,color=SUB).apply { gravity=Gravity.CENTER;setPadding(dp(24),dp(4),dp(24),0) })
        if(soon) caption.addView(RabtaUi.badge(ctx,"Coming soon"),LinearLayout.LayoutParams(-2,-2).apply { topMargin=dp(6) })
        orb.addView(caption,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply { bottomMargin=dp(20) })
        addView(orb,LinearLayout.LayoutParams(-1,dp(300)))
        // Three round shortcuts
        val row=LinearLayout(ctx).apply { gravity=Gravity.CENTER }
        fun round(icon:String,label:String,primary:Boolean,run:()->Unit)=LinearLayout(ctx).apply {
            orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;isFocusable=true;contentDescription=label
            setOnClickListener { run() }
            addView(TextView(ctx).apply {
                UiIcons.set(this,icon,if(primary) AppTheme.onPrimary(ctx) else AppTheme.accent(ctx),26,label);gravity=Gravity.CENTER
                background=if(primary) pill(AppTheme.primary(ctx)) else pill(AppTheme.raised(ctx));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },LinearLayout.LayoutParams(dp(60),dp(60)))
            addView(RabtaUi.text(ctx,label,13f,true).apply { gravity=Gravity.CENTER;setPadding(0,dp(8),0,0);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        }
        row.addView(round("phone","Voice Call",true) { showCallContacts() },LinearLayout.LayoutParams(0,-2,1f))
        row.addView(round("video","Video Call",false) { showCallContacts(video=true) },LinearLayout.LayoutParams(0,-2,1f))
        row.addView(round("walkie","Walkie-Talkie",false) { walkieSoon() }.apply { contentDescription="Walkie-Talkie. Coming soon" },LinearLayout.LayoutParams(0,-2,1f))
        addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(4);bottomMargin=dp(8) })
    }
    private fun walkieSoon()=android.widget.Toast.makeText(this,"Walkie-Talkie: coming soon",android.widget.Toast.LENGTH_SHORT).show()

    private fun callRow(c: Db.CallRecord): View {
        val missed=c.type.contains("missed",true)
        val video=c.type.contains("video",true)
        val name=c.peerName.ifBlank { c.peerId }
        val row=LinearLayout(this).apply {
            gravity=Gravity.CENTER_VERTICAL;setPadding(dp(4),dp(8),0,dp(8))
            background=RabtaUi.ripple(this@ConversationListActivity,Color.TRANSPARENT,12)
            isFocusable=true;setOnClickListener { open(c.peerId,name) }
            contentDescription="$name, ${c.type}, ${callTime(c.startedAt)}"
        }
        row.addView(initials(name,dp(46)),LinearLayout.LayoutParams(dp(46),dp(46)))
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
        mid.addView(RabtaUi.text(this,"$direction$duration",12f,color=if(missed) AppTheme.error(this) else SUB).apply {
            maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END;setPadding(0,dp(3),0,0)
        })
        row.addView(mid,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(RabtaUi.text(this,fmtTime(c.startedAt),12f,color=SUB))
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

    private fun showCallContacts(video:Boolean=false) {
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
                    RabtaUi.sheet(this,if(video) "Start a video call" else "Start a call",contacts.map { (id,name) ->
                        RabtaUi.SheetAction(name.ifBlank { id },"${if(video) "Video" else "Voice"} call") { callContact(id,name,video) }
                    }+RabtaUi.SheetAction("Find by username or Rabta ID") { showNewChat(forCall=true) })
                }.onFailure { android.widget.Toast.makeText(this,"Could not load contacts. Try again.",android.widget.Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // ── Helpers ──
    private fun initials(name: String, size: Int): TextView {
        val cols = listOf(0xFF4B3F8F,0xFF325E71,0xFF3D6B2A,0xFF286F68)
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
            today -> android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(t)) // same 12/24 h as inside the chat
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
    override fun onConnected() { updateConnection();uiHandler.post { ProfilePhoto.restore { if(!isDestroyed) refreshAvatar() } } }
    override fun onDisconnected() { updateConnection() }
}
