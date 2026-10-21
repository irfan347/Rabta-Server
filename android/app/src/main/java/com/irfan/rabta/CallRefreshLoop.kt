package com.irfan.rabta

/** One lifecycle-owned timer. Resuming a still-visible bar must restart its canceled callback. */
class CallRefreshLoop(private val schedule:(Runnable)->Unit,private val cancel:(Runnable)->Unit,private val render:()->Boolean) {
    private var running=false
    private val tick=Runnable { refresh() }
    fun start() { running=true;refresh() }
    fun stop() { running=false;cancel(tick) }
    fun refresh() { cancel(tick);val visible=render();if(running&&visible)schedule(tick) }
}
