package tv.tvwave.app

/** Bounded cache of complete programme objects; never truncates descriptions. */
class ScheduleMemoryCache(private val maxBytes:Long=8L*1024*1024,private val maxEntries:Int=32) {
    private data class Key(val channel:String,val from:Long,val to:Long,val offset:Int)
    private data class Entry(val rows:List<Programme>,val limit:Int,val complete:Boolean,val bytes:Long)
    private val entries=LinkedHashMap<Key,Entry>(16,.75f,true)
    var retainedBytes=0L;private set
    val size get()=entries.size
    fun clear(){entries.clear();retainedBytes=0}
    fun get(channel:String,from:Long,to:Long,limit:Int,offset:Int,load:(Long,Long,Int)->List<Programme>):List<Programme> {
        val boundedLimit=limit.coerceIn(1,10000)
        // A forward hour of coverage avoids reloading on every clock tick. Filter
        // the result to the exact requested window, preserving the original API.
        val coveredTo=Math.floorDiv(to+3599999L,3600000L)*3600000L
        val key=Key(channel,from,coveredTo,offset)
        var entry=entries[key]
        if(entry==null||(!entry.complete&&entry.limit<boundedLimit)){
            val rows=load(from,coveredTo,boundedLimit)
            val bytes=128L+channel.length*2+rows.sumOf{128L+2L*(it.epgId.length+it.title.length+it.description.length)}
            entry=Entry(rows,boundedLimit,rows.size<boundedLimit,bytes)
            entries.remove(key)?.let{retainedBytes-=it.bytes}
            if(bytes<=maxBytes&&maxEntries>0){
                entries[key]=entry;retainedBytes+=bytes
                while(retainedBytes>maxBytes||entries.size>maxEntries){val iterator=entries.entries.iterator();retainedBytes-=iterator.next().value.bytes;iterator.remove()}
            }
        }
        return entry.rows.asSequence().filter{it.end>from&&it.start<to}.take(boundedLimit).toList()
    }
}
