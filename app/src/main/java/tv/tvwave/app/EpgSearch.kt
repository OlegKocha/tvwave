package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import java.util.Locale
import java.util.PriorityQueue

data class ProgrammeMatch(val channel:Channel,val programme:Programme) {
    val key:String get()="programme:${channel.id}:${programme.start}"
}
data class GuideSearchResults(val channels:List<Channel> = emptyList(),val programmes:List<ProgrammeMatch> = emptyList(),val programmeCount:Int=0)

object EpgSearch {
    const val LIMIT=200
    private val spaces=Regex("\\s+")
    fun normalize(value:String)=value.trim().lowercase(Locale.ROOT).replace('ё','е').replace(spaces," ")
    fun matches(title:String,query:String):Boolean {
        val words=normalize(query).split(' ').filter{it.isNotEmpty()}
        val text=normalize(title)
        return words.isNotEmpty()&&words.all{text.contains(it)}
    }
    fun findChannels(state:AppState,query:String):List<Channel> {
        val term=normalize(query)
        if(term.isEmpty())return emptyList()
        return state.channels.distinctBy{it.id}.filter{matches(it.name,term)}.sortedBy{
            val name=normalize(it.name)
            if(name==term)0 else if(name.startsWith(term))1 else 2
        }
    }
    /** Search only resolved playlist channels, once per EPG stream. Descriptions stay on disk. */
    fun find(db:SQLiteDatabase,state:AppState,query:String,now:Long,checkCancelled:()->Unit={}):GuideSearchResults {
        val term=normalize(query)
        if(term.isEmpty())return GuideSearchResults()
        val oldest=EpgRetention.cutoff(now)
        val words=term.split(' ')
        // Repeats and HD/SD variants share titles. Bound the per-request cache so
        // a large guide does not turn a search into an unbounded memory cache.
        val titleRanks=object:LinkedHashMap<String,Int>(1024,0.75f,true) {
            override fun removeEldestEntry(eldest:MutableMap.MutableEntry<String,Int>?)=size>4096
        }
        fun rank(title:String):Int=titleRanks.getOrPut(title) {
            val text=normalize(title)
            when {
                !words.all{text.contains(it)} -> -1
                text==term -> 0
                text.startsWith(term) -> 1
                else -> 2
            }
        }
        val channels=state.channels.distinctBy{it.id}
        val channelResults=findChannels(state,term)
        val links=EpgCache.links(db)
        val owners=channels.filter{it.id in links}.groupBy{links.getValue(it.id)}
        if(owners.isEmpty())return GuideSearchResults(channels=channelResults)
        val order=compareBy<ProgrammeMatch>{rank(it.programme.title)}
            .thenBy{if(it.programme.start<=now&&it.programme.end>now)0 else 1}
            .thenBy{kotlin.math.abs(it.programme.start-now)}
            .thenBy{it.channel.name}.thenBy{it.key}
        val best=PriorityQueue(LIMIT,order.reversed())
        var count=0
        // Search distinct titles through the covering index. HD/SD variants and
        // repeated broadcasts must not make us transfer the whole guide to Kotlin.
        // Keep cursors bounded and fold Unicode here (SQLite NOCASE is ASCII-only).
        val earliest=(oldest-12*3600000L).toString()
        val latest=(now+3*86400000L+12*3600000L).toString()
        var afterTitle:String?=null
        while(true) {
            checkCancelled()
            val titles=db.rawQuery(
                "SELECT DISTINCT title FROM guide_programme"+
                    (if(afterTitle==null) "" else " WHERE title>?")+" ORDER BY title LIMIT 256",
                afterTitle?.let{arrayOf(it)}).use{c->buildList{while(c.moveToNext())add(c.getString(0))}}
            if(titles.isEmpty())break
            for(title in titles) {
                checkCancelled()
                if(rank(title)<0)continue
                var offset=0
                do {
                    checkCancelled()
                    var rows=0
                    db.rawQuery("SELECT source,epg,start,end FROM guide_programme WHERE title=? AND start<? AND end>? ORDER BY source,epg,start LIMIT 512 OFFSET ?",
                        arrayOf(title,latest,earliest,offset.toString())).use{cursor->
                        while(cursor.moveToNext()) {
                            checkCancelled()
                            rows++
                            val binding=EpgBinding(cursor.getString(0),cursor.getString(1))
                            for(channel in owners[binding].orEmpty()) {
                                val shift=(state.epgOffsets[channel.id]?:0)*60000L
                                val start=cursor.getLong(2)+shift;val end=cursor.getLong(3)+shift
                                if(end<=oldest||start>now+3*86400000L)continue
                                count++
                                val match=ProgrammeMatch(channel,Programme(binding.channel,start,end,title))
                                if(best.size<LIMIT)best.add(match)
                                else if(order.compare(match,best.peek())<0){best.poll();best.add(match)}
                            }
                        }
                    }
                    offset+=rows
                }while(rows==512)
            }
            afterTitle=titles.last()
            if(titles.size<256)break
        }
        return GuideSearchResults(channelResults,best.sortedWith(order),count)
    }
}
