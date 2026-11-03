package tv.tvwave.app

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Encrypted configuration plus a small encrypted viewing snapshot; full saves replace both atomically.
 * Keystore never participates in portable backup encryption. All calls run off UI thread. */
class Repository(context: Context) : SQLiteOpenHelper(context,"tvwave.db",null,8) {
    override fun onCreate(db: SQLiteDatabase) { db.execSQL("CREATE TABLE state (id INTEGER PRIMARY KEY, payload BLOB NOT NULL)");EpgCache.create(db);CacheMaintenance.create(db);createViewing(db) }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { EpgCache.migrate(db);CacheMaintenance.create(db);createViewing(db);if(oldVersion<4)db.execSQL("UPDATE guide_meta SET updated=0") }
    private val schedules=ScheduleMemoryCache()
    private fun createViewing(db:SQLiteDatabase){db.execSQL("CREATE TABLE IF NOT EXISTS viewing_state (id INTEGER PRIMARY KEY,payload BLOB NOT NULL)")}
    private fun encrypt(plain:ByteArray):ByteArray {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,deviceKey())
        return try{cipher.iv+cipher.doFinal(plain)}finally{plain.fill(0)}
    }
    private fun decrypt(bytes:ByteArray):ByteArray {
        require(bytes.size>=28)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,deviceKey(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        return cipher.doFinal(bytes,12,bytes.size-12)
    }
    private fun writeViewing(db:SQLiteDatabase,last:String,history:List<String>){
        require(history.size<=30&&history.distinct().size==history.size)
        val plain=JSONObject().put("version",1).put("last",last).put("history",JSONArray(history)).toString().toByteArray()
        require(plain.size<=65536)
        val values=ContentValues().apply{put("id",1);put("payload",encrypt(plain))}
        db.insertWithOnConflict("viewing_state",null,values,SQLiteDatabase.CONFLICT_REPLACE).also{check(it!=-1L)}
    }
    @Synchronized fun saveViewing(last:String,history:List<String>){writeViewing(writableDatabase,last,history)}
    @Synchronized fun invalidateSchedules(){schedules.clear()}
    @Synchronized fun installEpg(file:File,source:EpgSource,state:AppState){
        EpgCache.install(writableDatabase,file,source,state);schedules.clear()
    }
    @Synchronized fun schedule(channel:String,from:Long,to:Long,limit:Int=300,offsetMinutes:Int=0):List<Programme> =
        schedules.get(channel,from,to,limit,offsetMinutes){start,end,count->EpgCache.schedule(readableDatabase,channel,start,end,count,offsetMinutes)}
    @Synchronized fun maintain(state:AppState,now:Long=System.currentTimeMillis(),compact:Boolean=false,onFilter:()->Unit={}):Boolean {
        val cleaned=CacheMaintenance.clean(writableDatabase,state,now)
        val changed=CacheMaintenance.filterSchedules(writableDatabase,state,force=cleaned,onStart=onFilter)||cleaned
        if(changed)schedules.clear()
        if(compact)runCatching{CacheMaintenance.compactIfUseful(writableDatabase)}
        return changed
    }
    private fun deviceKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("tvwave-state-v1",null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder("tvwave-state-v1",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }
    @Synchronized fun load(): AppState {
        readableDatabase.rawQuery("SELECT id,payload FROM state ORDER BY id",null).use { cursor ->
            if (!cursor.moveToFirst()) return AppState()
            val output = java.io.ByteArrayOutputStream();var index=0
            do {
                if(cursor.getInt(0)!=index++)throw UserError(tr("storage_integrity_check_failed"))
                val part=cursor.getBlob(1)
                if(output.size().toLong()+part.size>SourceIO.MAX_STATE+28)throw UserError(tr("storage_limit_exceeded"))
                output.write(part)
            }while(cursor.moveToNext())
            val bytes = output.toByteArray()
            try {
                val plain=decrypt(bytes)
                val state=try{StateCodec.decode(plain)}finally{plain.fill(0)}
                // v6 snapshots carry history inline; absence of the new row keeps it.
                return readableDatabase.rawQuery("SELECT payload FROM viewing_state WHERE id=1",null).use { viewing->
                    if(!viewing.moveToFirst())state else {
                        val raw=decrypt(viewing.getBlob(0))
                        val json=try{JSONObject(raw.toString(Charsets.UTF_8))}finally{raw.fill(0)}
                        require(json.getInt("version")==1)
                        val ids=state.channels.map{it.id}.toSet();val array=json.getJSONArray("history")
                        require(array.length()<=30)
                        val history=(0 until array.length()).map{array.getString(it)}
                        require(history.distinct().size==history.size&&history.all{it in ids})
                        val last=json.getString("last");require(last.isEmpty()||last in ids)
                        state.copy(last=last,history=history)
                    }
                }
            } catch (_: Exception) { throw UserError(tr("could_not_read_saved_data_restore_a_backup_the_previous_storage_is_unc")) }
        }
    }
    private var mappingSignature:Int?=null
    @Synchronized fun save(state: AppState, resetCache:Boolean=false) {
        val encrypted=encrypt(StateCodec.encode(state.copy(epgBackup="",last="",history=emptyList())))
        val signature=listOf(state.sources,state.epgBindings,state.playlists,state.playlistEpgSources).hashCode()
        val db = writableDatabase; db.beginTransaction()
        try {
            db.delete("state",null,null)
            var offset=0;var index=0
            while(offset<encrypted.size) {
                val end=minOf(encrypted.size,offset+256*1024)
                val values=ContentValues().apply{put("id",index++);put("payload",encrypted.copyOfRange(offset,end))}
                if(db.insertOrThrow("state",null,values)<0)throw UserError(tr("could_not_save_data"))
                offset=end
            }
            writeViewing(db,state.last,state.history)
            if(resetCache||mappingSignature!=signature)EpgCache.reconcile(db,state,resetCache)
            db.setTransactionSuccessful()
        }
        finally { db.endTransaction() }
        if(resetCache||mappingSignature!=signature)schedules.clear()
        mappingSignature=signature
    }
}
