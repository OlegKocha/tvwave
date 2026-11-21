package tv.tvwave.app

import android.database.sqlite.SQLiteDatabase
import java.security.MessageDigest

/** Describes the exact response from which this selection was imported. */
internal data class EpgResponseVersion(
    val effectiveUrl: String,
    val etag: String,
    val modified: String,
    val digest: String
)

internal data class ImportedEpg(
    val version: EpgResponseVersion,
    val selection: String,
    val oldest: Long,
    val programmes: Int
)

internal object EpgHttpCache {
    fun create(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS guide_http (
            source TEXT PRIMARY KEY, effective_url TEXT, etag TEXT, modified TEXT,
            digest TEXT, selection TEXT, oldest INTEGER, programmes INTEGER)""")
    }

    // EpgLinks.needed depends on these fields. Stream URLs, logos, viewing history
    // and source order do not affect which raw XMLTV programmes we retain.
    fun selection(state: AppState, source: String): String {
        val hash = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            for (shift in 24 downTo 0 step 8) hash.update((bytes.size ushr shift).toByte())
            hash.update(bytes)
        }
        for (playlist in state.playlists) {
            val allowed = state.playlistEpgSources[playlist.id].let { it == null || it == source }
            for (channel in playlist.channels) {
                val manual = state.epgBindings[channel.id]?.takeIf { it.source == source }
                if (allowed) {
                    field("auto")
                    field(channel.id)
                    field(channel.epgId)
                    field(channel.epgName)
                    field(channel.name)
                }
                if (manual != null) {
                    field("manual")
                    field(manual.channel)
                }
            }
        }
        return hash.digest().toHex()
    }

    fun read(db: SQLiteDatabase, source: EpgSource, selection: String, oldest: Long): ImportedEpg? =
        db.rawQuery("""SELECT h.effective_url,h.etag,h.modified,h.digest,h.oldest,h.programmes
            FROM guide_http h JOIN guide_meta m USING(source)
            WHERE h.source=? AND m.url=? AND h.selection=? AND h.oldest<=?
            AND h.programmes>0 AND EXISTS (SELECT 1 FROM guide_programme p WHERE p.source=h.source)""",
            arrayOf(source.id, source.url, selection, oldest.toString())).use { c ->
            if (!c.moveToFirst()) null else ImportedEpg(
                EpgResponseVersion(c.getString(0),c.getString(1),c.getString(2),c.getString(3)),
                selection,c.getLong(4),c.getInt(5))
        }

    // Caller commits this with the programme rows, or with their successful revalidation.
    fun record(db: SQLiteDatabase, source: String, entry: ImportedEpg) {
        val v = entry.version
        db.execSQL("INSERT OR REPLACE INTO guide_http VALUES (?,?,?,?,?,?,?,?)",
            arrayOf<Any>(source,v.effectiveUrl,v.etag,v.modified,v.digest,entry.selection,entry.oldest,entry.programmes))
    }
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
