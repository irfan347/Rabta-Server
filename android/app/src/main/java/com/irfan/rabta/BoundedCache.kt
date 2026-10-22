package com.irfan.rabta

/** Access-ordered cache. Eviction releases only the cache reference, never resources held by a view. */
internal class BoundedCache<K,V>(private val maxEntries:Int,private val maxWeight:Long,private val weigh:(V)->Long) {
    private val entries=LinkedHashMap<K,Pair<V,Long>>(16,0.75f,true)
    private var weight=0L
    init { require(maxEntries>0&&maxWeight>0) }
    operator fun get(key:K):V?=entries[key]?.first
    operator fun set(key:K,value:V) {
        val size=weigh(value).coerceAtLeast(1)
        entries.remove(key)?.let { weight-=it.second }
        if(size>maxWeight)return
        entries[key]=value to size;weight+=size
        val oldest=entries.entries.iterator()
        while(entries.size>maxEntries||weight>maxWeight) { val item=oldest.next();weight-=item.value.second;oldest.remove() }
    }
    fun clear() { entries.clear();weight=0 }
}
