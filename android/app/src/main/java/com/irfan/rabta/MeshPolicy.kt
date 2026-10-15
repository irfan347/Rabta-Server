package com.irfan.rabta

object MeshPolicy {
    const val HOPS=8
    const val LIFE=24*60*60*1000L
    const val SKEW=5*60*1000L
    const val MAX_PACKET=30*1024
    fun validTime(sent:Long,expiry:Long,now:Long)=sent>0 && sent<=now+SKEW && expiry>now && expiry>sent && expiry-sent<=LIFE
    fun canReceive(hops:Int)=hops in 1..HOPS
    fun forwardHops(hops:Int)=(hops-1).coerceAtLeast(0)
    fun validId(id:String)=Regex("[0-9A-F]{12}").matches(id)
}
