package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import java.util.TreeMap

/** The primary schedule stays directly addressable. Fallback rows are represented by runs of
 * consecutive accepted start keys in their original schedule; titles/descriptions stay in one place. */
internal object EpgMerge {
    fun rebuild(db: SQLiteDatabase, ranked: Map<String,List<EpgBinding>>) {
        db.delete("guide_extra",null,null)
        writeIntervals(db,ranked)
    }

    /** The catalogue and bindings are unchanged. Only channels with an edited
     * candidate interval can gain or lose a listing from a lower-priority source. */
    fun updateIntervals(db:SQLiteDatabase,ranked:Map<String,List<EpgBinding>>,source:String,changed:Set<String>){
        val affected=ranked.filterValues { candidates ->
            candidates.size>1 && candidates.any { it.source==source && it.channel in changed }
        }
        if(affected.isEmpty())return
        db.compileStatement("DELETE FROM guide_extra WHERE channel=?").use { remove ->
            for(channel in affected.keys){remove.bindString(1,channel);remove.executeUpdateDelete()}
        }
        writeIntervals(db,affected)
    }

    private fun writeIntervals(db:SQLiteDatabase,ranked:Map<String,List<EpgBinding>>){
        val groups = ranked.entries.filter { it.value.size > 1 }.groupBy({ it.value }, { it.key })
        db.compileStatement("INSERT INTO guide_extra VALUES (?,?,?,?,?)").use { insert ->
            for ((bindings, channels) in groups) {
                val covered = TreeMap<Long,Long>()
                for ((priority, binding) in bindings.withIndex()) {
                    var first: Long? = null
                    var last = 0L
                    fun finishRun() {
                        val begin = first ?: return
                        for (channel in channels) {
                            insert.bindString(1,channel)
                            insert.bindString(2,binding.source)
                            insert.bindString(3,binding.channel)
                            insert.bindLong(4,begin)
                            insert.bindLong(5,last)
                            insert.executeInsert()
                        }
                        first = null
                    }
                    db.rawQuery("SELECT start,end FROM guide_programme WHERE source=? AND epg=? ORDER BY start",
                        arrayOf(binding.source,binding.channel)).use { rows ->
                        while(rows.moveToNext()) {
                            val start = rows.getLong(0)
                            val end = rows.getLong(1)
                            val previous = covered.floorEntry(start)
                            val next = covered.ceilingEntry(start)
                            if(priority > 0 && ((previous != null && previous.value > start) ||
                                        (next != null && next.key < end))) {
                                finishRun()
                                continue
                            }
                            // Fold overlapping and adjacent intervals so the next source can fill real gaps.
                            val begin = if(previous != null && previous.value >= start) previous.key else start
                            var until = maxOf(end,covered[begin] ?: end)
                            var following = covered.higherEntry(begin)
                            while(following != null && following.key <= until) {
                                until = maxOf(until,following.value)
                                covered.remove(following.key)
                                following = covered.higherEntry(begin)
                            }
                            covered[begin] = until
                            if(priority > 0) {
                                if(first == null) first = start
                                last = start
                            }
                        }
                    }
                    finishRun()
                }
            }
        }
    }

    class Owner(private val channel: Channel) {
        val runs = mutableListOf<LongRange>()
        fun channelAt(start: Long): Channel? {
            val index = runs.binarySearchBy(start) { it.first }
            val run = if(index >= 0) runs[index] else runs.getOrNull(-index-2)
            return if(run != null && start <= run.last) channel else null
        }
    }

    data class SearchLinks(val primary:Map<EpgBinding,List<Channel>>, val extra:Map<EpgBinding,List<Owner>>)

    fun searchOwners(db: SQLiteDatabase, channels: Map<String,Channel>): SearchLinks {
        val primary = mutableMapOf<EpgBinding,MutableList<Channel>>()
        val extra = mutableMapOf<EpgBinding,MutableMap<String,Owner>>()
        // Read both kinds of ownership in one snapshot, including during an import or reorder.
        db.rawQuery("""SELECT channel,source,epg,NULL AS first_start,NULL AS last_start FROM guide_link
            UNION ALL SELECT channel,source,epg,first_start,last_start FROM guide_extra
            ORDER BY channel,source,epg,first_start""",null).use { rows ->
            while(rows.moveToNext()) {
                val channel = channels[rows.getString(0)] ?: continue
                val binding = EpgBinding(rows.getString(1),rows.getString(2))
                if(rows.isNull(3)) {
                    primary.getOrPut(binding) { mutableListOf() }.add(channel)
                } else {
                    val owners = extra.getOrPut(binding) { mutableMapOf() }
                    owners.getOrPut(channel.id) { Owner(channel) }.runs.add(rows.getLong(3)..rows.getLong(4))
                }
            }
        }
        return SearchLinks(primary,extra.mapValues { it.value.values.toList() })
    }
}
