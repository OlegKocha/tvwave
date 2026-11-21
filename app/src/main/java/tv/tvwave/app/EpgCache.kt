package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import java.io.File
import java.io.InputStream

data class EpgStatus(val updated:Long=0,val count:Int=0,val programmes:Int=0,val tomorrow:Boolean=false,val until:Long=0)
object EpgCache {
    fun create(db:SQLiteDatabase, staging:Boolean=false){
        db.execSQL("CREATE TABLE IF NOT EXISTS guide_catalog (source TEXT, epg TEXT, name TEXT, aliases TEXT, icon TEXT NOT NULL DEFAULT '', PRIMARY KEY(source,epg))")
        db.execSQL("CREATE TABLE IF NOT EXISTS guide_programme (source TEXT, epg TEXT, start INTEGER, end INTEGER, title TEXT, description TEXT, PRIMARY KEY(source,epg,start))")
        db.execSQL("CREATE TABLE IF NOT EXISTS guide_meta (source TEXT PRIMARY KEY,url TEXT,updated INTEGER)")
        // Only these three tables are transferred out of the temporary import database.
        if(staging)return
        EpgHttpCache.create(db)
        // Search reads titles without touching the much larger description pages.
        db.execSQL("CREATE INDEX IF NOT EXISTS guide_programme_by_time ON guide_programme(start DESC,source,epg,end,title)")
        db.execSQL("CREATE TABLE IF NOT EXISTS guide_link (channel TEXT PRIMARY KEY,source TEXT,epg TEXT)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS guide_extra (
            channel TEXT, source TEXT, epg TEXT, first_start INTEGER, last_start INTEGER,
            PRIMARY KEY(channel,source,epg,first_start)) WITHOUT ROWID""")
        db.execSQL("""CREATE VIEW IF NOT EXISTS guide_listing AS
            SELECT m.channel,p.* FROM guide_link m JOIN guide_programme p ON p.source=m.source AND p.epg=m.epg
            UNION ALL
            SELECT x.channel,p.* FROM guide_extra x JOIN guide_programme p ON p.source=x.source AND p.epg=x.epg
                AND p.start BETWEEN x.first_start AND x.last_start""")
        db.execSQL("CREATE TABLE IF NOT EXISTS guide_summary (source TEXT PRIMARY KEY, catalog_count INTEGER, programmes INTEGER, until_time INTEGER, day_start INTEGER, day_end INTEGER, tomorrow INTEGER)")
    }
    fun migrate(db:SQLiteDatabase){
        db.execSQL("DROP INDEX IF EXISTS guide_programme_search")
        db.execSQL("DROP INDEX IF EXISTS guide_programme_title_search")
        create(db)
        val hasIcon=db.rawQuery("PRAGMA table_info(guide_catalog)",null).use{c->var found=false;while(c.moveToNext())if(c.getString(1)=="icon")found=true;found}
        if(!hasIcon)db.execSQL("ALTER TABLE guide_catalog ADD COLUMN icon TEXT NOT NULL DEFAULT ''")
        val exists=db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='epg_meta'",null).use{it.moveToFirst()}
        if(!exists)return
        db.rawQuery("SELECT url,updated FROM epg_meta WHERE id=1",null).use{c->if(c.moveToFirst()){
            val url=c.getString(0);val id=legacyEpgSource(url).id
            db.execSQL("INSERT OR REPLACE INTO guide_meta VALUES (?,?,?)",arrayOf<Any>(id,url,c.getLong(1)))
            db.execSQL("INSERT OR IGNORE INTO guide_programme SELECT ?,epg,start,end,title,description FROM epg_programme",arrayOf(id))
            db.execSQL("INSERT OR REPLACE INTO guide_link SELECT channel,?,epg FROM epg_mapping",arrayOf(id))
        }}
        for(t in listOf("epg_mapping","epg_programme","epg_meta"))db.execSQL("DROP TABLE IF EXISTS ${t}")
    }
    fun clear(db:SQLiteDatabase){for(t in listOf("guide_http","guide_extra","guide_link","guide_catalog","guide_programme","guide_meta","guide_summary"))db.execSQL("DELETE FROM ${t}")}
    fun stage(file:File,input:InputStream,channels:List<Channel>,source:EpgSource,now:Long,progress:(Int)->Unit,oldest:Long=EpgRetention.cutoff(now),state:AppState?=null):EpgImportResult {
        SQLiteDatabase.deleteDatabase(file);val db=SQLiteDatabase.openOrCreateDatabase(file,null)
        try{
            // This scratch database is regenerated from the durable download after interruption.
            db.rawQuery("PRAGMA journal_mode=OFF",null).use { it.moveToFirst() }
            db.execSQL("PRAGMA synchronous=OFF")
            create(db,staging=true);db.beginTransaction()
            var inferEnds=false
            val result=db.compileStatement("INSERT OR REPLACE INTO guide_programme VALUES (?,?,?,?,?,?)").use{s->
                s.bindString(1,source.id)
                Epg.parse(input,channels,now,{p->if(p.end==0L)inferEnds=true;s.bindString(2,p.epgId);s.bindLong(3,p.start);s.bindLong(4,p.end);s.bindString(5,p.title);s.bindString(6,p.description);s.execute()},progress,{catalog->
                    db.compileStatement("INSERT OR REPLACE INTO guide_catalog VALUES (?,?,?,?,?)").use{q->catalog.forEach{c->q.clearBindings();q.bindString(1,source.id);q.bindString(2,c.id);q.bindString(3,c.names.firstOrNull()?:c.id);q.bindString(4,JSONArray(c.names).toString());q.bindString(5,c.icon);q.executeInsert()}}
                },baseUrl=source.url,oldest=oldest,selectChannels={catalog->
                    if(state!=null)EpgLinks.needed(state,source.id,catalog)else Epg.match(channels,catalog).values.toSet()
                })
            }
            // Explicit end times and the retention boundary were already validated by the parser.
            if(inferEnds){
                db.execSQL("UPDATE guide_programme SET end=COALESCE((SELECT MIN(n.start) FROM guide_programme n WHERE n.source=guide_programme.source AND n.epg=guide_programme.epg AND n.start>guide_programme.start),0) WHERE end=0")
                db.execSQL("DELETE FROM guide_programme WHERE end<=start")
                db.delete("guide_programme","end<=?",arrayOf(oldest.toString()))
            }
            val count=db.rawQuery("SELECT COUNT(*) FROM guide_programme",null).use{it.moveToFirst();it.getInt(0)}
            if(count==0&&result.programmes>0)throw UserError(tr("no_programmes_with_a_known_end_time_in_xmltv"))
            db.execSQL("INSERT INTO guide_meta VALUES (?,?,?)",arrayOf<Any>(source.id,source.url,now))
            db.setTransactionSuccessful();return result.copy(programmes=count)
        }finally{if(db.inTransaction())db.endTransaction();db.close()}
    }
    fun remove(db:SQLiteDatabase,source:String){for(t in listOf("guide_http","guide_extra","guide_link","guide_catalog","guide_programme","guide_meta","guide_summary"))db.delete(t,"source=?",arrayOf(source))}
    internal fun install(db:SQLiteDatabase,file:File,source:EpgSource,state:AppState,imported:ImportedEpg?=null){
        db.execSQL("ATTACH DATABASE ? AS imported_epg",arrayOf(file.path))
        try {
            db.beginTransaction()
            val sameFeed = lastUpdated(db, source) != 0L
            val args = arrayOf(source.id)
            val cached = imported?.let { EpgHttpCache.read(db,source,it.selection,it.oldest) }
            val sameCatalog = sameFeed && catalogUnchanged(db,source.id)
            if(!sameCatalog){
                db.delete("guide_catalog","source=?",args)
                db.execSQL("INSERT INTO guide_catalog SELECT * FROM imported_epg.guide_catalog")
            }
            // Configuration saves reconcile bindings separately. A service import with a verified
            // selection can reuse that layout if all channel definitions and time intervals survive.
            val knownLayout = sameCatalog && cached != null
            if(sameFeed){
                val wanted = if(knownLayout)null else EpgLinks.needed(state,source.id,catalog(db,source.id))
                val changed=replaceCoveredProgrammes(db,source.id,wanted)
                changed.addAll(applyProgrammeChanges(db))
                if(knownLayout && changed.isNotEmpty()){
                    val catalogs=state.sources.associate { it.id to catalog(db,it.id) }
                    EpgMerge.updateIntervals(db,EpgLinks.ranked(state,catalogs),source.id,changed)
                }
            }else{
                db.delete("guide_programme","source=?",args)
                db.execSQL("INSERT INTO guide_programme SELECT * FROM imported_epg.guide_programme")
            }
            if(imported==null)db.delete("guide_http","source=?",args) else EpgHttpCache.record(db,source.id,imported)
            db.execSQL("INSERT OR REPLACE INTO guide_meta SELECT * FROM imported_epg.guide_meta")
            if(!knownLayout){
                db.delete("guide_link","source=?",args)
                rebind(db,state)
            }
            updateSummary(db, source.id, tomorrowWindow(lastUpdated(db, source)))
            db.setTransactionSuccessful()
        } finally {
            if(db.inTransaction())db.endTransaction()
            db.execSQL("DETACH DATABASE imported_epg")
        }
    }

    private fun catalogUnchanged(db:SQLiteDatabase,source:String):Boolean {
        val args=arrayOf(source)
        // Both catalogues have unique (source,epg) keys. Equal sizes plus this anti-join
        // detect removals as well as new aliases, renamed or otherwise changed channels.
        return db.rawQuery("""SELECT (SELECT COUNT(*) FROM guide_catalog WHERE source=?) =
            (SELECT COUNT(*) FROM imported_epg.guide_catalog) AND NOT EXISTS (
              SELECT 1 FROM imported_epg.guide_catalog n LEFT JOIN guide_catalog p
                ON p.source=n.source AND p.epg=n.epg
              WHERE p.epg IS NULL OR n.name IS NOT p.name OR n.aliases IS NOT p.aliases OR n.icon IS NOT p.icon)""",
            args).use { it.moveToFirst();it.getInt(0)!=0 }
    }

    private fun applyProgrammeChanges(db:SQLiteDatabase):Set<String> {
        db.execSQL("CREATE TEMP TABLE incoming_changes (incoming_row INTEGER PRIMARY KEY, timing INTEGER)")
        try {
            db.execSQL("""INSERT INTO incoming_changes SELECT n.rowid, p.start IS NULL OR p.end IS NOT n.end
                FROM imported_epg.guide_programme n LEFT JOIN guide_programme p
                ON p.source=n.source AND p.epg=n.epg AND p.start=n.start
                WHERE p.start IS NULL OR p.end IS NOT n.end OR p.title IS NOT n.title
                OR p.description IS NOT n.description""")
            db.execSQL("""INSERT OR REPLACE INTO guide_programme
                SELECT n.* FROM incoming_changes c CROSS JOIN imported_epg.guide_programme n
                WHERE n.rowid=c.incoming_row""")
            return db.rawQuery("""SELECT DISTINCT n.epg FROM incoming_changes c
                JOIN imported_epg.guide_programme n ON n.rowid=c.incoming_row WHERE c.timing=1""",null).use { rows ->
                buildSet { while(rows.moveToNext())add(rows.getString(0)) }
            }
        } finally { db.execSQL("DROP TABLE incoming_changes") }
    }

    private data class Coverage(val start:Long, val end:Long)

    /** Replace the interval supplied for each channel, including omissions inside it. */
    private fun replaceCoveredProgrammes(db:SQLiteDatabase, source:String, wanted:Set<String>?):MutableSet<String> {
        val changed=mutableSetOf<String>()
        val args = arrayOf(source)
        val coverage = db.rawQuery("""SELECT epg, MIN(start), MAX(end) FROM imported_epg.guide_programme
            WHERE source=? GROUP BY epg""", args).use { cursor ->
            buildMap { while(cursor.moveToNext())put(cursor.getString(0),Coverage(cursor.getLong(1),cursor.getLong(2))) }
        }
        val cached = db.rawQuery("SELECT DISTINCT epg FROM guide_programme WHERE source=?", args).use { cursor ->
            buildList { while(cursor.moveToNext())add(cursor.getString(0)) }
        }
        for (epg in cached) {
            val interval = coverage[epg]
            val deleted=if (wanted!=null && epg !in wanted) {
                db.delete("guide_programme", "source=? AND epg=?", arrayOf(source,epg))
            } else if (interval != null) {
                // A broadcast touching the boundary survives; one crossing it is superseded.
                db.delete("guide_programme", """source=? AND epg=? AND start<? AND end>?
                    AND NOT EXISTS (SELECT 1 FROM imported_epg.guide_programme n
                        WHERE n.source=guide_programme.source AND n.epg=guide_programme.epg
                        AND n.start=guide_programme.start)""",
                    arrayOf(source,epg,interval.end.toString(),interval.start.toString()))
            } else 0
            if(deleted>0)changed.add(epg)
        }
        return changed
    }
    fun catalog(db:SQLiteDatabase,source:String):List<EpgChannel> = db.rawQuery("SELECT epg,aliases,icon FROM guide_catalog WHERE source=? ORDER BY name COLLATE NOCASE",arrayOf(source)).use{c->buildList{while(c.moveToNext()){val a=JSONArray(c.getString(1));add(EpgChannel(c.getString(0),(0 until a.length()).map{a.getString(it)},c.getString(2)))}}}
    fun logos(db:SQLiteDatabase):Map<String,String> = db.rawQuery("SELECT l.channel,c.icon FROM guide_link l JOIN guide_catalog c ON c.source=l.source AND c.epg=l.epg WHERE c.icon<>''",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),c.getString(1))}}
    fun lastUpdated(db:SQLiteDatabase,source:EpgSource):Long = db.rawQuery("SELECT updated FROM guide_meta WHERE source=? AND url=?",arrayOf(source.id,source.url)).use{if(it.moveToFirst())it.getLong(0)else 0L}
    private fun tomorrowWindow(now:Long):Pair<Long,Long> {
        val day=java.util.Calendar.getInstance().apply{timeInMillis=now;set(java.util.Calendar.HOUR_OF_DAY,0);set(java.util.Calendar.MINUTE,0);set(java.util.Calendar.SECOND,0);set(java.util.Calendar.MILLISECOND,0);add(java.util.Calendar.DAY_OF_YEAR,1)}
        val start=day.timeInMillis;day.add(java.util.Calendar.DAY_OF_YEAR,1);return start to day.timeInMillis
    }
    private fun updateSummary(db:SQLiteDatabase,source:String,window:Pair<Long,Long>){
        val count=db.rawQuery("SELECT COUNT(*) FROM guide_catalog WHERE source=?",arrayOf(source)).use{it.moveToFirst();it.getInt(0)}
        db.rawQuery("SELECT COUNT(*),COALESCE(MAX(end),0),COALESCE(SUM(CASE WHEN end>? AND start<? THEN 1 ELSE 0 END),0) FROM guide_programme WHERE source=?",arrayOf(window.first.toString(),window.second.toString(),source)).use{c->
            c.moveToFirst()
            db.execSQL("INSERT OR REPLACE INTO guide_summary VALUES (?,?,?,?,?,?,?)",arrayOf<Any>(source,count,c.getInt(0),c.getLong(1),window.first,window.second,if(c.getInt(2)>0)1 else 0))
        }
    }
    fun status(db:SQLiteDatabase,source:EpgSource,now:Long=System.currentTimeMillis()):EpgStatus {
        val window=tomorrowWindow(now)
        fun cached():EpgStatus? = db.rawQuery("SELECT m.updated,s.catalog_count,s.programmes,s.tomorrow,s.until_time FROM guide_meta m JOIN guide_summary s ON m.source=s.source WHERE m.source=? AND m.url=? AND s.day_start=? AND s.day_end=?",arrayOf(source.id,source.url,window.first.toString(),window.second.toString())).use{c->
            if(c.moveToFirst())EpgStatus(c.getLong(0),c.getInt(1),c.getInt(2),c.getInt(3)!=0,c.getLong(4))else null
        }
        cached()?.let{return it}
        // Lazy backfill for existing installations, and once per calendar day.
        // Recheck under the write transaction so a concurrent import cannot leave
        // an old aggregate attached to the newly installed source.
        db.beginTransaction()
        try {
            val result=cached()?:if(lastUpdated(db,source)==0L)EpgStatus()else {
                updateSummary(db,source.id,window);cached()?:EpgStatus()
            }
            db.setTransactionSuccessful();return result
        }finally{db.endTransaction()}
    }
    fun links(db:SQLiteDatabase):Map<String,EpgBinding> = db.rawQuery("SELECT channel,source,epg FROM guide_link",null).use{c->buildMap{while(c.moveToNext())put(c.getString(0),EpgBinding(c.getString(1),c.getString(2)))}}
    fun rebind(db:SQLiteDatabase,state:AppState){
        // Preserve migrated v2 links until each source has its full catalogue.
        val catalogs=state.sources.associate{it.id to catalog(db,it.id)}
        val ranked=EpgLinks.ranked(state,catalogs)
        val resolved=ranked.mapValues { it.value.first() }.toMutableMap()
        val ids=state.channels.map{it.id}.toSet();val sources=state.sources.map{it.id}.toSet()
        val owner=state.playlists.flatMap{p->p.channels.map{it.id to p.id}}.toMap()
        for((id,b) in links(db))if(state.playlistEpgSources[owner[id]].let{it==null||it==b.source}&&id in ids&&b.source in sources&&catalogs[b.source].isNullOrEmpty()&&id !in state.epgBindings)resolved.getOrPut(id){b}
        db.delete("guide_link",null,null)
        db.compileStatement("INSERT INTO guide_link VALUES (?,?,?)").use{s->resolved.forEach{(id,b)->s.bindString(1,id);s.bindString(2,b.source);s.bindString(3,b.channel);s.executeInsert()}}
        EpgMerge.rebuild(db,ranked)
    }
    /** Caller owns the transaction. Never remove the full channel catalogue. */
    fun pruneUnused(db:SQLiteDatabase,state:AppState){
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS wanted_epg (source TEXT,epg TEXT,PRIMARY KEY(source,epg)) WITHOUT ROWID")
        db.execSQL("DELETE FROM wanted_epg")
        db.compileStatement("INSERT OR IGNORE INTO wanted_epg VALUES (?,?)").use{insert->
            for(source in state.sources){
                val catalog=catalog(db,source.id)
                val wanted=if(catalog.isNotEmpty())EpgLinks.needed(state,source.id,catalog)else
                    links(db).filterKeys{id->state.channels.any{it.id==id}}.values.filter{it.source==source.id}.map{it.channel}.toSet()
                for(id in wanted){insert.bindString(1,source.id);insert.bindString(2,id);insert.executeInsert()}
            }
        }
        db.execSQL("DELETE FROM guide_programme WHERE NOT EXISTS (SELECT 1 FROM wanted_epg w WHERE w.source=guide_programme.source AND w.epg=guide_programme.epg)")
        val removed=db.rawQuery("SELECT changes()",null).use { it.moveToFirst();it.getInt(0) }
        if(removed>0)db.delete("guide_http",null,null)
        db.execSQL("DELETE FROM guide_summary")
        db.execSQL("DROP TABLE wanted_epg")
    }
    fun reconcile(db:SQLiteDatabase,state:AppState,reset:Boolean=false){
        if(reset)clear(db)
        val stored=db.rawQuery("SELECT source,url FROM guide_meta",null).use{c->buildList{while(c.moveToNext())add(c.getString(0) to c.getString(1))}}
        for((id,url) in stored)if(state.sources.none{it.id==id&&it.url==url})remove(db,id)
        rebind(db,state)
    }
    /** Load neighbours by broadcast order, so navigation can cross gaps of any length. */
    fun guideWindow(db:SQLiteDatabase, channel:String, oldest:Long, pivot:Long, offsetMinutes:Int):List<Programme> {
        val shift=offsetMinutes*60000L
        val args=arrayOf(channel,(oldest-shift).toString(),(pivot-shift).toString())
        val query="""SELECT p.epg,p.start,p.end,p.title,p.description
            FROM guide_listing p
            WHERE p.channel=? AND p.end>? AND p.start"""
        fun read(order:String)=db.rawQuery(query+order,args).use { cursor ->
            buildList {
                while(cursor.moveToNext())add(Programme(cursor.getString(0),cursor.getLong(1)+shift,
                    cursor.getLong(2)+shift,cursor.getString(3),cursor.getString(4)))
            }
        }
        val before=read("<=? ORDER BY p.start DESC LIMIT 128")
        val after=read(">? ORDER BY p.start LIMIT 128")
        return before.asReversed()+after
    }

    /** Look up the on-air or next retained listing before the user binds a playlist channel. */
    fun candidateProgramme(db:SQLiteDatabase,binding:EpgBinding,time:Long,offsetMinutes:Int):Programme? {
        val offset=offsetMinutes*60_000L
        fun first(condition:String,order:String):Programme? = db.rawQuery(
            "SELECT start,end,title FROM guide_programme WHERE source=? AND epg=? AND $condition ORDER BY start $order LIMIT 1",
            arrayOf(binding.source,binding.channel,(time-offset).toString())
        ).use { c -> if(c.moveToFirst())Programme(binding.channel,c.getLong(0)+offset,c.getLong(1)+offset,c.getString(2),"") else null }
        return first("start<=?","DESC")?.takeIf{it.end>time} ?: first("start>?","ASC")
    }
    fun hasSchedule(db:SQLiteDatabase,channel:String,oldest:Long,offsetMinutes:Int):Boolean =
        db.rawQuery("""SELECT 1 FROM guide_listing p WHERE p.channel=? AND p.end>? LIMIT 1""",
            arrayOf(channel,(oldest-offsetMinutes*60000L).toString())).use { it.moveToFirst() }

    fun schedule(db:SQLiteDatabase,channel:String,from:Long,to:Long,limit:Int=300,offsetMinutes:Int=0):List<Programme> {
        val offset=offsetMinutes*60000L
        return db.rawQuery("SELECT p.epg,p.start,p.end,p.title,p.description FROM guide_listing p WHERE p.channel=? AND p.end>? AND p.start<? ORDER BY p.start LIMIT ?",arrayOf(channel,(from-offset).toString(),(to-offset).toString(),limit.coerceIn(1,10000).toString())).use{c->buildList{while(c.moveToNext())add(Programme(c.getString(0),c.getLong(1)+offset,c.getLong(2)+offset,c.getString(3),c.getString(4)))}}
    }
}
