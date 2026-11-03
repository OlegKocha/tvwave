package tv.tvwave.app
import org.junit.Test
import org.junit.Assert.*
import org.json.JSONObject
import java.util.UUID
import java.nio.ByteBuffer
import java.security.MessageDigest
class ConfigurationTest {
    private fun playlist(n:Int):Playlist {val id=UUID.nameUUIDFromBytes("playlist$n".toByteArray()).toString();val raw="#EXTM3U\n#EXTINF:-1,Channel $n\nhttps://example.invalid/$n\n";return Playlist(id,"Playlist $n","",raw,M3u.parse(raw,id))}
    @Test fun dailyEpgDefaultPreservesSavedIntervals() {
        assertEquals(24,AppState().epgIntervalHours)
        val old=JSONObject(String(StateCodec.encode(AppState()))).apply{remove("epgIntervalHours")}
        assertEquals(24,StateCodec.decode(old.toString().toByteArray()).epgIntervalHours)
        for(hours in listOf(6,12,24)) {
            val state=AppState(epgIntervalHours=hours)
            assertEquals(hours,BackupFile.decode(BackupFile.encode(state)).epgIntervalHours)
        }
    }
    @Test fun sourcesBindingsAndLimitsRoundtrip(){
        val lists=(1..15).map{playlist(it)};val sources=(1..10).map{legacyEpgSource("https://example.invalid/$it.xml")}
        val state=AppState(playlists=lists,epgSources=sources,epgBindings=mapOf(lists[0].channels[0].id to EpgBinding(sources[9].id,"manual")),epgIntervalHours=6)
        assertEquals(state,BackupFile.decode(BackupFile.encode(state)))
        for(invalid in listOf(state.copy(playlists=lists+playlist(16)),state.copy(epgSources=sources+legacyEpgSource("https://example.invalid/11.xml")),state.copy(epgIntervalHours=7),state.copy(epgBindings=mapOf("missing" to EpgBinding(sources[0].id,"one"))))){try{StateCodec.decode(StateCodec.encode(invalid));fail()}catch(_:UserError){}}
    }
    @Test fun backupExcludesProgrammeDataAndReadsOldConfiguration(){
        val url="https://example.invalid/guide.xml";val source=legacyEpgSource(url);val state=AppState(playlists=listOf(playlist(1)),epgSources=listOf(source))
        val restored=BackupFile.decode(BackupFile.encode(state.copy(epgBackup="SHOULD NEVER BE EXPORTED")))
        assertEquals(state,restored);assertEquals("",restored.epgBackup)
        val old=JSONObject(String(StateCodec.encode(state))).apply{put("format",LegacyBackup.STATE_FORMAT);remove("epgSources");put("minimumReaderVersion",2);put("epgUrl",url);put("epgBackup","old programme cache ignored")}.toString().toByteArray()
        val container=ByteBuffer.allocate(48).put(LegacyBackup.V2).putInt(2).putInt(old.size).put(MessageDigest.getInstance("SHA-256").digest(old)).array()+old
        assertEquals(state,BackupFile.decode(container))
    }
    @Test fun legacyCompressedBackupRestoresAndNewExportsUseTvWave() {
        val state=AppState(playlists=listOf(playlist(1)),uiLanguage="ru",fontPercent=85)
        val legacyJson=JSONObject(String(StateCodec.encode(state))).put("format",LegacyBackup.STATE_FORMAT).toString().toByteArray()
        val payload=java.io.ByteArrayOutputStream().apply{java.util.zip.GZIPOutputStream(this).use{it.write(legacyJson)}}.toByteArray()
        val old=ByteBuffer.allocate(48).put(LegacyBackup.V3).putInt(3).putInt(payload.size).put(MessageDigest.getInstance("SHA-256").digest(payload)).array()+payload
        assertEquals(state,BackupFile.decode(old))
        assertEquals("TVWAVBK3",String(BackupFile.encode(state).copyOfRange(0,8)))
        assertEquals("tvwave-state",JSONObject(String(StateCodec.encode(state))).getString("format"))
    }
    @Test fun manualOverridesSourcePriorityAndSurvivesNameMismatch(){
        val p=playlist(1);val c=p.channels.single();val a=legacyEpgSource("https://example.invalid/a");val b=legacyEpgSource("https://example.invalid/b")
        val catalogs=mapOf(a.id to listOf(EpgChannel("same",listOf(c.name))),b.id to listOf(EpgChannel("same",listOf(c.name)),EpgChannel("manual",listOf("Different name"))))
        val state=AppState(playlists=listOf(p),epgSources=listOf(a,b))
        assertEquals(EpgBinding(a.id,"same"),EpgLinks.resolve(state,catalogs)[c.id])
        assertEquals(EpgBinding(b.id,"manual"),EpgLinks.resolve(state.copy(epgBindings=mapOf(c.id to EpgBinding(b.id,"manual"))),catalogs)[c.id])
    }
    @Test fun orderedSourcesPlaylistScopeAndTimeCorrectionsRoundtrip(){
        val lists=(1..15).map{playlist(it)};val sources=(1..10).map{legacyEpgSource("https://example.invalid/$it.xml")}.reversed()
        val state=AppState(playlists=lists,epgSources=sources,playlistEpgSources=mapOf(lists[0].id to sources[2].id),epgOffsets=mapOf(lists[0].channels[0].id to -90),fontPercent=60,subtitles=true,audioLanguage="en",resume=true,epgIntervalHours=24)
        assertEquals(state,BackupFile.decode(BackupFile.encode(state)))
        assertEquals(4,JSONObject(String(StateCodec.encode(state))).getInt("minimumReaderVersion"))
        val c=lists[0].channels[0];val catalogs=sources.associate{it.id to listOf(EpgChannel("same",listOf(c.name)))}
        assertEquals(sources[2].id,EpgLinks.resolve(state,catalogs)[c.id]?.source)
        val automatic=state.copy(playlistEpgSources=emptyMap())
        assertEquals(sources[0].id,EpgLinks.resolve(automatic,catalogs)[c.id]?.source)
        assertEquals(sources.last().id,EpgLinks.resolve(automatic.copy(epgSources=sources.reversed()),catalogs)[c.id]?.source)
        assertEquals(sources.last().id,EpgLinks.resolve(state.copy(epgBindings=mapOf(c.id to EpgBinding(sources.last().id,"same"))),catalogs)[c.id]?.source)
        for(bad in listOf(state.copy(playlistEpgSources=mapOf("missing" to sources[0].id)),state.copy(playlistEpgSources=mapOf(lists[0].id to "missing")),state.copy(epgOffsets=mapOf(c.id to 721)),state.copy(epgOffsets=mapOf(c.id to 1)))){
            try{StateCodec.decode(StateCodec.encode(bad));fail()}catch(_:UserError){}
        }
    }
    @Test fun recentOrderDoesNotMoveWhileZapping(){
        val order=RecentOrder();order.enter(true,listOf("a","b","c"));order.enter(true,listOf("c","a","b"));assertEquals(listOf("a","b","c"),order.ids)
        order.enter(false,listOf("c","a","b"));order.enter(true,listOf("c","a","b"));assertEquals(listOf("c","a","b"),order.ids)
    }
}
