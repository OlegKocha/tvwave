package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import java.io.File

/** Disposable data only. Run on IO, serialized with configuration saves / EPG installs. */
object CacheMaintenance {
    private const val MIB=1024L*1024
    fun create(db:SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS epg_storage_policy (id INTEGER PRIMARY KEY)")
        db.execSQL("CREATE TABLE IF NOT EXISTS cache_maintenance (id INTEGER PRIMARY KEY, last_success INTEGER NOT NULL)")
    }
    fun lastSuccess(db:SQLiteDatabase):Long = db.rawQuery("SELECT last_success FROM cache_maintenance WHERE id=1",null).use {
        if(it.moveToFirst())it.getLong(0)else 0L
    }
    fun clean(db:SQLiteDatabase,state:AppState,now:Long):Boolean {
        if(!EpgRetention.due(lastSuccess(db),now))return false
        db.beginTransaction()
        try {
            val stored=db.rawQuery("SELECT source FROM guide_meta UNION SELECT source FROM guide_catalog UNION SELECT source FROM guide_programme UNION SELECT source FROM guide_link",null).use { c->
                buildList { while(c.moveToNext())add(c.getString(0)) }
            }
            val active=state.sources.map{it.id}.toSet()
            stored.filter{it !in active}.forEach{EpgCache.remove(db,it)}
            db.delete("guide_programme","end<=?",arrayOf(EpgRetention.storageCutoff(now,state.epgOffsets.values).toString()))
            db.delete("guide_summary",null,null)
            db.execSQL("INSERT OR REPLACE INTO cache_maintenance VALUES (1,?)",arrayOf(now))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return true
    }
    /** One-time pruning for existing installations, then alongside weekly cleanup.
     * The marker is committed with the deletion: interrupted migration retries safely. */
    fun filterSchedules(db:SQLiteDatabase,state:AppState,force:Boolean=false):Boolean {
        val done=db.rawQuery("SELECT id FROM epg_storage_policy WHERE id=1",null).use{it.moveToFirst()}
        if(done&&!force)return false
        db.beginTransaction()
        try {
            EpgCache.pruneUnused(db,state)
            db.execSQL("INSERT OR IGNORE INTO epg_storage_policy VALUES (1)")
            db.setTransactionSuccessful()
        }finally{db.endTransaction()}
        return true
    }
    /** Only at startup before playback. Otherwise SQLite reuses free pages on import.
     * VACUUM needs up to twice the database size in additional free space. */
    fun compactIfUseful(db:SQLiteDatabase,availableBytes:Long=File(db.path).parentFile?.usableSpace?:0):Boolean {
        fun pragma(name:String)=db.rawQuery("PRAGMA $name",null).use{it.moveToFirst();it.getLong(0)}
        val pages=pragma("page_count");val free=pragma("freelist_count");val size=pragma("page_size")
        if(free*size<32*MIB || free*4<pages || availableBytes<pages*size*2+16*MIB)return false
        db.execSQL("VACUUM")
        return true
    }
    /** Age guard and exact allowlist protect active imports, logos and user backups. */
    fun cleanAbandonedFiles(dir:File,now:Long):Int {
        val names=Regex("epg-(download-[a-zA-Z0-9-]+\\.tmp|staged-[a-zA-Z0-9-]+\\.db(?:-journal|-wal|-shm)?|download\\.tmp|staged\\.db(?:-journal|-wal|-shm)?)")
        return dir.listFiles().orEmpty().count { file->
            file.isFile && names.matches(file.name) && now-file.lastModified()>86400000L && file.delete()
        }
    }
}
