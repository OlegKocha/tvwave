package tv.tvwave.app

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PlaylistRefreshTest {
    private val playlistId="00000000-0000-0000-0000-000000000001"
    private val epgId="00000000-0000-0000-0000-000000000002"
    private fun raw(url:String="https://example.invalid/old",second:Boolean=true)="""#EXTM3U
#EXTINF:-1 tvg-id="one" group-title="News",One
$url
"""+if(second)"#EXTINF:-1 tvg-id=\"two\",Two\nhttps://example.invalid/two\n"else""
    private fun playlist(id:String=playlistId,source:String="https://example.invalid/list",raw:String=raw())=Playlist(id,"Name",source,raw,M3u.parse(raw,id,source))
    @Test fun changedUrlsKeepStableReferencesAndRemoveOnlyMissingChannels(){
        val old=playlist();val ids=old.channels.map{it.id};val local=playlist("00000000-0000-0000-0000-000000000003","")
        val state=AppState(playlists=listOf(old,local),favourites=ids.toSet(),history=ids,last=ids[1],epgBindings=ids.associateWith{EpgBinding(epgId,"one")},epgOffsets=ids.associateWith{60},epgSources=listOf(EpgSource(epgId,"EPG","https://example.invalid/epg")),playlistEpgSources=mapOf(playlistId to epgId),fontPercent=85)
        val updated=playlist(raw=raw("https://example.invalid/new",false))
        val next=PlaylistRefresh.merge(state,old,updated)
        assertEquals(ids[0],updated.channels[0].id)
        assertEquals(setOf(ids[0]),next.favourites);assertEquals(listOf(ids[0]),next.history)
        assertEquals("",next.last);assertEquals(setOf(ids[0]),next.epgBindings.keys)
        assertEquals(setOf(ids[0]),next.epgOffsets.keys);assertEquals(state.playlistEpgSources,next.playlistEpgSources)
        assertEquals(local,next.playlists[1]);assertEquals(85,next.fontPercent)
        val restored=StateCodec.decode(StateCodec.encode(next))
        assertEquals(next.channels,restored.channels);assertEquals(next.favourites,restored.favourites)
    }
    @Test fun refreshDoesNotOverwriteConcurrentEditOrResurrectDeletedPlaylist(){
        val old=playlist();val updated=playlist(raw=raw("https://example.invalid/new"))
        val changed=AppState(playlists=listOf(old.copy(source="https://example.invalid/changed")))
        assertSame(changed,PlaylistRefresh.merge(changed,old,updated))
        val removed=AppState();assertSame(removed,PlaylistRefresh.merge(removed,old,updated))
        val renamed=AppState(playlists=listOf(old.copy(name="Renamed")))
        assertEquals("Renamed",PlaylistRefresh.merge(renamed,old,updated).playlists.single().name)
    }
    @Test fun realHttpRefreshValidatesBeforeReplacingAndRejectsFileImports()=runBlocking {
        val server=ServerSocket(0)
        val reply=AtomicReference(200 to raw("https://example.invalid/new"))
        val worker=thread(isDaemon=true){
            while(!server.isClosed) {
                val socket=try{server.accept()}catch(_:Exception){break}
                socket.use{
                    val reader=it.getInputStream().bufferedReader()
                    while(!reader.readLine().isNullOrEmpty()){}
                    val (code,body)=reply.get();val bytes=body.toByteArray()
                    it.getOutputStream().apply{
                        write("HTTP/1.1 $code Result\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(bytes);flush()
                    }
                }
            }
        }
        try {
            val old=playlist(source="http://127.0.0.1:${server.localPort}/list")
            val updated=PlaylistRefresh.download(old)
            assertEquals(old.id,updated.id);assertEquals(old.source,updated.source)
            assertEquals("https://example.invalid/new",updated.channels.first().url)
            for(body in listOf("<html>Error</html>","#EXTM3U\n","#EXTM3U\n#EXT-X-TARGETDURATION:10\nsegment.ts")){
                reply.set(200 to body);assertTrue(runCatching{PlaylistRefresh.download(old)}.isFailure)
            }
            reply.set(503 to raw());assertTrue(runCatching{PlaylistRefresh.download(old)}.isFailure)
            assertTrue(runCatching{PlaylistRefresh.download(old.copy(source=""))}.isFailure)
            assertEquals("https://example.invalid/old",old.channels.first().url)
        }finally{server.close();worker.join(2000)}
    }
}
