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
    /** Search only resolved playlist channels, once per EPG stream. Descriptions stay on disk. */
    fun find(db:SQLiteDatabase,state:AppState,query:String,now:Long,checkCancelled:()->Unit={}):GuideSearchResults {
        val term=normalize(query)
        if(term.isEmpty())return GuideSearchResults()
        val oldest=EpgRetention.cutoff(now)
        val words=term.split(' ')
        fun accepts(title:String)=normalize(title).let{text->words.all{text.contains(it)}}
        fun rank(title:String):Int=normalize(title).let{if(it==term)0 else if(it.startsWith(term))1 else 2}
        val channels=state.channels.distinctBy{it.id}
        val channelResults=channels.filter{accepts(it.name)}.sortedBy{rank(it.name)}
        val links=EpgCache.links(db)
        val owners=channels.filter{it.id in links}.groupBy{links.getValue(it.id)}
        if(owners.isEmpty())return GuideSearchResults(channels=channelResults)
        val order=compareBy<ProgrammeMatch>{rank(it.programme.title)}
            .thenBy{if(it.programme.start<=now&&it.programme.end>now)0 else 1}
            .thenBy{kotlin.math.abs(it.programme.start-now)}
            .thenBy{it.channel.name}.thenBy{it.key}
        val best=PriorityQueue(LIMIT,order.reversed())
        var count=0
        // The DISTINCT join avoids re-reading the same schedule for HD/SD aliases.
        // Unicode matching happens in Kotlin: SQLite NOCASE does not fold Cyrillic.
        db.rawQuery("SELECT p.source,p.epg,p.start,p.end,p.title FROM guide_programme p JOIN (SELECT DISTINCT source,epg FROM guide_link) l ON p.source=l.source AND p.epg=l.epg WHERE p.end>? AND p.start<?",
            arrayOf((oldest-12*3600000L).toString(),(now+3*86400000L+12*3600000L).toString())).use{cursor->
            while(cursor.moveToNext()) {
                checkCancelled()
                val title=cursor.getString(4)
                if(!accepts(title))continue
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
        return GuideSearchResults(channelResults,best.sortedWith(order),count)
    }
}
