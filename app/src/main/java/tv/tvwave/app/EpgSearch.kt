package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import java.util.Locale
import java.util.PriorityQueue

data class ProgrammeMatch(val channel:Channel,val programme:Programme) {
    val key:String get()="programme:${channel.id}:${programme.start}"
}
data class GuideSearchResults(val channels:List<Channel> = emptyList(),val programmes:List<ProgrammeMatch> = emptyList(),val hasNextPage:Boolean=false)

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
        val words=term.split(' ')
        val exact=ArrayList<Channel>()
        val prefix=ArrayList<Channel>()
        val other=ArrayList<Channel>()
        val seen=HashSet<String>()
        for(channel in state.channels){
            if(!seen.add(channel.id))continue
            val name=normalize(channel.name)
            when {
                name==term -> exact.add(channel)
                name.startsWith(term) -> prefix.add(channel)
                words.all{name.contains(it)} -> other.add(channel)
            }
        }
        return exact+prefix+other
    }
    private val newestFirst = compareByDescending<ProgrammeMatch> { it.programme.start }
        .thenBy { it.channel.name }.thenBy { it.channel.id }

    /** Read newest broadcasts first. Channel offsets can change their displayed order. */
    fun find(db: SQLiteDatabase, state: AppState, query: String, now: Long,
             after: ProgrammeMatch? = null, checkCancelled: () -> Unit = {}): GuideSearchResults {
        val channels = if(after==null)findChannels(state,query) else emptyList()
        return findWithChannels(db,state,query,now,after,channels,checkCancelled)
    }

    internal fun findWithChannels(db:SQLiteDatabase,state:AppState,query:String,now:Long,
                                 after:ProgrammeMatch?,channels:List<Channel>,checkCancelled:()->Unit):GuideSearchResults {
        val term=normalize(query)
        if(term.isEmpty())return GuideSearchResults()
        val (owners,extra) = EpgMerge.searchOwners(db,state.channels.associateBy { it.id })
        if (owners.isEmpty()) return GuideSearchResults(channels)
        fun shift(channel: Channel) = (state.epgOffsets[channel.id] ?: 0) * 60000L
        val shifts = owners.values.flatten().map(::shift)
        val maxShift = shifts.maxOrNull()!!
        val minShift = shifts.minOrNull()!!
        val oldest = EpgRetention.cutoff(now)
        val words = term.split(' ')
        val titleMatches = HashMap<String, Boolean>()
        val best = PriorityQueue(LIMIT + 1, newestFirst.reversed())
        var upper = after?.let { it.programme.start - minShift } ?: Long.MAX_VALUE
        var skippedAtUpper = 0
        // OFFSET only advances within one group of equal start times.
        val batchSize = 1024
        val sql = """SELECT start,title,source,epg,end FROM guide_programme
            WHERE start<=? AND end>? ORDER BY start DESC,source,epg LIMIT $batchSize OFFSET ?"""
        while (true) {
            checkCancelled()
            var count = 0
            var done = false
            db.rawQuery(sql, arrayOf(upper.toString(), (oldest - maxShift).toString(), skippedAtUpper.toString())).use { cursor ->
                while (cursor.moveToNext()) {
                    count++
                    if (count % 64 == 0) checkCancelled()
                    val rawStart = cursor.getLong(0)
                    // Even the largest positive offset cannot move later rows onto this page.
                    if (best.size > LIMIT && rawStart + maxShift < best.element().programme.start) {
                        done = true
                        break
                    }
                    if (rawStart != upper) {
                        upper = rawStart
                        skippedAtUpper = 0
                    }
                    skippedAtUpper++
                    val title = cursor.getString(1)
                    if (titleMatches.size >= 32768) titleMatches.clear()
                    if (!titleMatches.getOrPut(title) {
                        val folded = title.lowercase(Locale.ROOT).replace('ё', 'е')
                        words.all { it in folded }
                    }) continue
                    val binding = EpgBinding(cursor.getString(2), cursor.getString(3))
                    val rawEnd = cursor.getLong(4)
                    val secondary = extra[binding].orEmpty()
                    val primary = owners[binding].orEmpty()
                    val visible = if(secondary.isEmpty()) primary else primary + secondary.mapNotNull { it.channelAt(rawStart) }
                    for (channel in visible) {
                        val offset = shift(channel)
                        if (rawEnd + offset <= oldest) continue
                        val programme = Programme(binding.channel, rawStart + offset, rawEnd + offset, title)
                        val match = ProgrammeMatch(channel, programme)
                        if (after != null && newestFirst.compare(match, after) <= 0) continue
                        if (best.size <= LIMIT) best.add(match)
                        else if (newestFirst.compare(match, best.peek()) < 0) {
                            best.poll()
                            best.add(match)
                        }
                    }
                }
            }
            if (done || count < batchSize) break
        }
        val hasMore = best.size > LIMIT
        if (hasMore) best.poll()
        return GuideSearchResults(channels, best.sortedWith(newestFirst), hasMore)
    }
}
