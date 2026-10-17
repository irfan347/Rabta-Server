package com.irfan.rabta

/** Monotonic-clock retry budget. One request per endpoint, including requests awaiting resolution. */
class MeshAttempts {
    private data class Attempt(val count:Int,val retryAt:Long,var pendingUntil:Long)
    private val attempts=mutableMapOf<String,Attempt>()
    @Synchronized fun begin(endpoint:String,now:Long):Boolean {
        val old=attempts[endpoint]
        if(old!=null && (now<old.retryAt || now<old.pendingUntil)) return false
        val count=((old?.count?:0)+1).coerceAtMost(4)
        attempts[endpoint]=Attempt(count,now+(15000L shl (count-1)),now+30000)
        return true
    }
    @Synchronized fun initiated(endpoint:String,now:Long) {
        val old=attempts[endpoint]
        if(old==null) attempts[endpoint]=Attempt(1,now+15000,now+30000)
        else old.pendingUntil=now+30000
    }
    @Synchronized fun failed(endpoint:String) { attempts[endpoint]?.pendingUntil=0 }
    @Synchronized fun remove(endpoint:String) { attempts.remove(endpoint) }
    @Synchronized fun clear() { attempts.clear() }
    @Synchronized fun pending(now:Long)=attempts.values.count { it.pendingUntil>now }
}
